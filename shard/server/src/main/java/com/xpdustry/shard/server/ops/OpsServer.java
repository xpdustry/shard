package com.xpdustry.shard.server.ops;

import com.sun.net.httpserver.*;
import com.xpdustry.shard.server.config.*;
import org.slf4j.*;
import tools.jackson.databind.*;
import tools.jackson.databind.json.*;

import java.io.*;
import java.net.*;
import java.nio.charset.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * The ops API: REST and Server-Sent Events on the JDK's {@link HttpServer}, one virtual thread per exchange (note 34
 * "Console and remote control"). It binds a loopback address or nothing: {@link Builder#start} refuses any other
 * address, {@code 0.0.0.0} and {@code ::} included, before it opens a socket. Remote access goes through an
 * authenticated TLS reverse proxy.
 *
 * <p>Built-in routes, when their component is given: {@code GET /livez}, {@code GET /readyz}, {@code GET /metrics},
 * {@code GET /api/v1/config}, {@code GET /api/v1/events}. Later work adds its own with {@link Builder#route}; a handler
 * must not touch a world, only queue commands to it.
 *
 * <p>Browser-facing defences, since anything local can reach a loopback port: the {@code Host} header must name this
 * server (against DNS rebinding), request bodies are capped at {@value #MAX_BODY} bytes and must be JSON (a plain
 * form post can't send that cross-origin), and no CORS headers are ever sent.
 */
public final class OpsServer implements AutoCloseable{
    public static final int MAX_BODY = 64 << 10;
    public static final int MAX_STREAMS = 16;
    static final long KEEPALIVE_MILLIS = 15_000;
    static final ObjectMapper JSON = JsonMapper.builder().build();
    private static final Logger log = LoggerFactory.getLogger(OpsServer.class);

    private final HttpServer server;
    private final ExecutorService executor;
    private final Map<String, Map<String, Handler>> routes;
    private final Set<String> hosts;
    private final Events events;
    private final Set<Events.Subscription> streams = ConcurrentHashMap.newKeySet();
    private final AtomicInteger openStreams = new AtomicInteger();
    private volatile boolean closing;

    /** Handles one request. Throw {@link IllegalArgumentException} for a 400 with its message. */
    @FunctionalInterface
    public interface Handler{
        Response handle(Request request) throws Exception;
    }

    /**
     * A request.
     *
     * @param path the raw path, without the query
     * @param headers by lower-case name
     */
    public record Request(String method, String path, Map<String, String> query, Map<String, List<String>> headers, byte[] body){
        public String header(String name){
            var values = headers.get(name.toLowerCase(Locale.ROOT));
            return values == null || values.isEmpty() ? null : values.getFirst();
        }

        /** The body as JSON. */
        public JsonNode json(){
            if(body.length == 0) throw new IllegalArgumentException("expected a JSON body");
            try{
                return JSON.readTree(body);
            }catch(RuntimeException e){
                throw new IllegalArgumentException("malformed JSON body");
            }
        }
    }

    public record Response(int status, String contentType, byte[] body){
        public static Response json(int status, Object value){
            return new Response(status, "application/json", JSON.writeValueAsBytes(value));
        }

        public static Response text(int status, String contentType, String text){
            return new Response(status, contentType, text.getBytes(StandardCharsets.UTF_8));
        }

        public static Response error(int status, String message){
            return json(status, Map.of("error", message));
        }
    }

    public static Builder builder(){
        return new Builder();
    }

    public static final class Builder{
        private final Map<String, Map<String, Handler>> routes = new LinkedHashMap<>();
        private Events events;

        private Builder(){
        }

        public Builder route(String method, String path, Handler handler){
            if(!path.startsWith("/")) throw new IllegalArgumentException("paths start with /");
            if(routes.computeIfAbsent(path, p -> new TreeMap<>()).putIfAbsent(method, handler) != null){
                throw new IllegalArgumentException(method + " " + path + " is already routed");
            }
            return this;
        }

        public Builder health(Health health){
            route("GET", "/livez", request -> report(health.live()));
            return route("GET", "/readyz", request -> report(health.ready()));
        }

        public Builder metrics(Metrics metrics){
            return route("GET", "/metrics", request -> Response.text(200, "text/plain; version=0.0.4; charset=utf-8", metrics.scrape()));
        }

        /** {@code GET /api/v1/config}: every effective value and its source, secrets redacted, and pending changes. */
        public Builder config(ConfigService config){
            return route("GET", "/api/v1/config", request -> {
                var current = config.current();
                String world = request.query().get("world");
                if(world != null) current = current.world(world);
                var values = new ArrayList<Map<String, Object>>();
                for(var key : ConfigKeys.ALL){
                    values.add(Map.of("key", key.path(), "value", key.display(current.get(key)), "source", current.source(key).toString(),
                        "reload", key.reload().name().toLowerCase(Locale.ROOT)));
                }
                var body = new LinkedHashMap<String, Object>();
                body.put("generation", config.generation());
                body.put("world", world);
                body.put("values", values);
                body.put("pending", config.pending().stream().map(Object::toString).toList());
                return Response.json(200, body);
            });
        }

        /** {@code GET /api/v1/events}: the event stream. */
        public Builder events(Events events){
            this.events = events;
            return route("GET", "/api/v1/events", request -> {
                throw new AssertionError("streamed by the dispatcher");
            });
        }

        /**
         * Binds and starts.
         *
         * @param port 0 for any free port
         * @throws IllegalArgumentException if {@code bind} is not a loopback address
         */
        public OpsServer start(InetAddress bind, int port) throws IOException{
            if(!bind.isLoopbackAddress()){
                throw new IllegalArgumentException("the ops API binds a loopback address only; refusing " + bind.getHostAddress()
                    + (bind.isAnyLocalAddress() ? " (the wildcard address)" : ""));
            }
            return new OpsServer(this, new InetSocketAddress(bind, port));
        }
    }

    private OpsServer(Builder builder, InetSocketAddress address) throws IOException{
        routes = Map.copyOf(builder.routes);
        events = builder.events;
        server = HttpServer.create(address, 64);
        executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        server.createContext("/", this::dispatch);
        server.start();
        hosts = hosts(server.getAddress());
        log.atInfo().setMessage("ops API listening").addKeyValue("event", "ops.listen").addKeyValue("address", server.getAddress().toString()).log();
    }

    /** The bound address, with the real port. */
    public InetSocketAddress address(){
        return server.getAddress();
    }

    /** {@code http://127.0.0.1:7810}. */
    public URI uri(){
        var address = server.getAddress();
        String host = address.getAddress() instanceof Inet6Address ? "[" + address.getAddress().getHostAddress() + "]" : address.getAddress().getHostAddress();
        return URI.create("http://" + host + ":" + address.getPort());
    }

    private static Set<String> hosts(InetSocketAddress address){
        var hosts = new HashSet<String>();
        String literal = address.getAddress() instanceof Inet6Address ? "[" + address.getAddress().getHostAddress() + "]" : address.getAddress().getHostAddress();
        for(String name : List.of(literal, "localhost", address.getAddress() instanceof Inet6Address ? "[::1]" : "127.0.0.1")){
            hosts.add(name);
            hosts.add(name + ":" + address.getPort());
        }
        return Set.copyOf(hosts);
    }

    private void dispatch(HttpExchange exchange){
        try(exchange){
            String method = exchange.getRequestMethod();
            String path = exchange.getRequestURI().getRawPath();
            String host = exchange.getRequestHeaders().getFirst("Host");
            if(host != null && !hosts.contains(host.toLowerCase(Locale.ROOT))){
                send(exchange, Response.error(403, "unexpected Host header"));
                return;
            }
            var byMethod = routes.get(path);
            if(byMethod == null){
                send(exchange, Response.error(404, "no route " + path));
                return;
            }
            var handler = byMethod.get(method);
            if(handler == null){
                exchange.getResponseHeaders().set("Allow", String.join(", ", byMethod.keySet()));
                send(exchange, Response.error(405, method + " is not allowed on " + path));
                return;
            }
            byte[] body = exchange.getRequestBody().readNBytes(MAX_BODY + 1);
            if(body.length > MAX_BODY){
                send(exchange, Response.error(413, "bodies are at most " + MAX_BODY + " bytes"));
                return;
            }
            String type = exchange.getRequestHeaders().getFirst("Content-Type");
            if(body.length > 0 && (type == null || !type.toLowerCase(Locale.ROOT).startsWith("application/json"))){
                send(exchange, Response.error(415, "bodies are application/json"));
                return;
            }
            var request = new Request(method, path, query(exchange.getRequestURI()), headers(exchange.getRequestHeaders()), body);
            if(events != null && path.equals("/api/v1/events")){
                stream(exchange, request);
                return;
            }
            Response response;
            try{
                response = handler.handle(request);
            }catch(IllegalArgumentException e){
                response = Response.error(400, e.getMessage());
            }catch(Exception e){
                log.atError().setMessage("ops request failed").addKeyValue("event", "ops.error").addKeyValue("method", method)
                    .addKeyValue("path", path).setCause(e).log();
                response = Response.error(500, "internal error");
            }
            send(exchange, response);
        }catch(IOException e){
            log.atDebug().setMessage("ops client went away").addKeyValue("event", "ops.disconnect").setCause(e).log();
        }
    }

    private void send(HttpExchange exchange, Response response) throws IOException{
        var headers = exchange.getResponseHeaders();
        headers.set("Content-Type", response.contentType());
        headers.set("Cache-Control", "no-store");
        headers.set("X-Content-Type-Options", "nosniff");
        exchange.sendResponseHeaders(response.status(), response.body().length == 0 ? -1 : response.body().length);
        if(response.body().length > 0) exchange.getResponseBody().write(response.body());
    }

    private static Response report(Health.Report report){
        var checks = new LinkedHashMap<String, Object>();
        report.checks().forEach((name, result) -> checks.put(name, Map.of("status", result.status().name().toLowerCase(Locale.ROOT), "detail", result.detail())));
        return Response.json(report.httpStatus(), Map.of("status", report.status().name().toLowerCase(Locale.ROOT), "checks", checks));
    }

    /** Server-Sent Events until the client leaves, falls behind, or the server closes. */
    private void stream(HttpExchange exchange, Request request) throws IOException{
        if(openStreams.incrementAndGet() > MAX_STREAMS){
            openStreams.decrementAndGet();
            send(exchange, Response.error(503, "too many event streams"));
            return;
        }
        long lastEventId = 0;
        String last = request.header("last-event-id");
        if(last == null) last = request.query().get("lastEventId");
        if(last != null){
            try{
                lastEventId = Long.parseLong(last.strip());
            }catch(NumberFormatException e){
                openStreams.decrementAndGet();
                send(exchange, Response.error(400, "bad Last-Event-ID"));
                return;
            }
        }
        var subscription = events.subscribe(lastEventId);
        streams.add(subscription);
        try{
            var headers = exchange.getResponseHeaders();
            headers.set("Content-Type", "text/event-stream; charset=utf-8");
            headers.set("Cache-Control", "no-store");
            headers.set("X-Content-Type-Options", "nosniff");
            exchange.sendResponseHeaders(200, 0);
            var out = exchange.getResponseBody();
            write(out, "retry: 2000\n\n");
            if(subscription.missed()) write(out, "event: missed\ndata: {}\n\n");
            long lastWrite = System.currentTimeMillis();
            while(!closing && !subscription.isClosed()){
                Events.Event event;
                try{
                    event = subscription.poll(250, TimeUnit.MILLISECONDS);
                }catch(InterruptedException e){
                    break;
                }
                if(subscription.overflowed()){
                    write(out, "event: overflow\ndata: {}\n\n");
                    break;
                }
                if(event != null){
                    write(out, "id: " + event.id() + "\nevent: " + event.type() + "\ndata: " + event.data() + "\n\n");
                    lastWrite = System.currentTimeMillis();
                }else if(System.currentTimeMillis() - lastWrite >= KEEPALIVE_MILLIS){
                    write(out, ": keepalive\n\n"); // also notices a client that left
                    lastWrite = System.currentTimeMillis();
                }
            }
        }finally{
            subscription.close();
            streams.remove(subscription);
            openStreams.decrementAndGet();
        }
    }

    private static void write(OutputStream out, String text) throws IOException{
        out.write(text.getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    private static Map<String, String> query(URI uri){
        var query = new LinkedHashMap<String, String>();
        String raw = uri.getRawQuery();
        if(raw == null || raw.isEmpty()) return query;
        for(String pair : raw.split("&")){
            int equals = pair.indexOf('=');
            String name = URLDecoder.decode(equals < 0 ? pair : pair.substring(0, equals), StandardCharsets.UTF_8);
            String value = equals < 0 ? "" : URLDecoder.decode(pair.substring(equals + 1), StandardCharsets.UTF_8);
            query.putIfAbsent(name, value);
        }
        return query;
    }

    private static Map<String, List<String>> headers(Headers headers){
        var out = new HashMap<String, List<String>>();
        headers.forEach((name, values) -> out.put(name.toLowerCase(Locale.ROOT), List.copyOf(values)));
        return out;
    }

    /** Ends the event streams, then stops the server, waiting at most two seconds for exchanges in flight. */
    @Override
    public void close(){
        closing = true;
        for(var subscription : streams) subscription.close();
        server.stop(2);
        executor.close();
    }
}
