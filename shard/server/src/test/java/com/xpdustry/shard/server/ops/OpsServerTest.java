package com.xpdustry.shard.server.ops;

import com.xpdustry.shard.server.Hang;
import com.xpdustry.shard.server.config.*;
import com.xpdustry.shard.server.ops.Health.*;
import com.xpdustry.shard.server.ops.OpsServer.*;
import org.junit.jupiter.api.*;
import tools.jackson.databind.*;
import tools.jackson.databind.json.*;

import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import static org.junit.jupiter.api.Assertions.*;

/** The REST smoke test: binds 127.0.0.1 on a free port, never anything else. */
class OpsServerTest{
    static final InetAddress LOOPBACK = InetAddress.ofLiteral("127.0.0.1");
    static final JsonMapper JSON = JsonMapper.shared();

    final AtomicReference<Result> readiness = new AtomicReference<>(Result.up());
    final Health health = new Health();
    final Metrics metrics = new Metrics();
    final Events events = new Events();
    final HttpClient client = HttpClient.newBuilder().connectTimeout(Hang.TIMEOUT).build();
    OpsServer server;

    @BeforeEach
    void start() throws Exception{
        health.register("process", Kind.LIVENESS, Result::up);
        health.register("storage", Kind.READINESS, readiness::get);
        metrics.counter("shard_test_total", "A test counter.").inc(3);
        var config = new ConfigService(() -> ConfigLoader.load(null, null, Map.of("SHARD__NET__PORT", "7000")));
        server = OpsServer.builder()
            .health(health)
            .metrics(metrics)
            .events(events)
            .config(config)
            .route("POST", "/api/v1/echo", request -> Response.json(200, Map.of("got", request.json().get("value").asString())))
            .route("GET", "/api/v1/fail", request -> {
                throw new IllegalStateException("broken handler");
            })
            .start(LOOPBACK, 0);
    }

    @AfterEach
    void stop(){
        server.close();
        client.close();
    }

    HttpResponse<String> get(String path) throws Exception{
        return client.send(HttpRequest.newBuilder(server.uri().resolve(path)).timeout(Hang.TIMEOUT).build(), HttpResponse.BodyHandlers.ofString());
    }

    HttpResponse<String> post(String path, String type, String body) throws Exception{
        var request = HttpRequest.newBuilder(server.uri().resolve(path)).timeout(Hang.TIMEOUT).POST(HttpRequest.BodyPublishers.ofString(body));
        if(type != null) request.header("Content-Type", type);
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void bindsLoopbackOnly(){
        assertEquals(LOOPBACK, server.address().getAddress());
        assertTrue(server.address().getPort() > 0);
        for(String refused : List.of("0.0.0.0", "::", "10.0.0.1", "192.168.1.1")){
            var error = assertThrows(IllegalArgumentException.class, () -> OpsServer.builder().health(new Health()).start(InetAddress.ofLiteral(refused), 0), refused);
            assertTrue(error.getMessage().contains("loopback"), error.getMessage());
        }
    }

    @Test
    void health() throws Exception{
        var live = get("/livez");
        assertEquals(200, live.statusCode());
        assertEquals("up", JSON.readTree(live.body()).get("status").asString());
        assertEquals("no-store", live.headers().firstValue("Cache-Control").orElseThrow());
        assertTrue(live.headers().firstValue("Access-Control-Allow-Origin").isEmpty());

        readiness.set(Result.degraded("slow disk"));
        var degraded = get("/readyz");
        assertEquals(200, degraded.statusCode(), "degraded still serves");
        var body = JSON.readTree(degraded.body());
        assertEquals("degraded", body.get("status").asString());
        assertEquals("slow disk", body.get("checks").get("storage").get("detail").asString());

        readiness.set(Result.down("closed"));
        assertEquals(503, get("/readyz").statusCode());
        assertEquals(200, get("/livez").statusCode(), "readiness checks don't touch liveness");

        health.register("exploding", Kind.READINESS, () -> {
            throw new IllegalStateException("oops");
        });
        readiness.set(Result.up());
        var exploded = JSON.readTree(get("/readyz").body());
        assertEquals("down", exploded.get("checks").get("exploding").get("status").asString());
    }

    @Test
    void metrics() throws Exception{
        var response = get("/metrics");
        assertEquals(200, response.statusCode());
        assertEquals("text/plain; version=0.0.4; charset=utf-8", response.headers().firstValue("Content-Type").orElseThrow());
        assertTrue(response.body().contains("# TYPE shard_test_total counter\nshard_test_total 3\n"), response.body());
    }

    @Test
    void config() throws Exception{
        var body = JSON.readTree(get("/api/v1/config").body());
        assertEquals(1, body.get("generation").asInt());
        boolean found = false;
        for(var value : body.get("values")){
            if(value.get("key").asString().equals("net.port")){
                assertEquals("7000", value.get("value").asString());
                assertEquals("env SHARD__NET__PORT", value.get("source").asString());
                assertEquals("restart", value.get("reload").asString());
                found = true;
            }
        }
        assertTrue(found);
        assertEquals(400, get("/api/v1/config?world=Bad!").statusCode());
        assertEquals("alpha", JSON.readTree(get("/api/v1/config?world=alpha").body()).get("world").asString());
    }

    @Test
    void routingAndBodies() throws Exception{
        assertEquals(404, get("/nope").statusCode());
        var wrongMethod = post("/livez", null, "");
        assertEquals(405, wrongMethod.statusCode());
        assertEquals("GET", wrongMethod.headers().firstValue("Allow").orElseThrow());

        var echo = post("/api/v1/echo", "application/json", "{\"value\":\"hi\"}");
        assertEquals(200, echo.statusCode());
        assertEquals("hi", JSON.readTree(echo.body()).get("got").asString());
        assertEquals(415, post("/api/v1/echo", "application/x-www-form-urlencoded", "value=hi").statusCode(), "no form posts");
        assertEquals(400, post("/api/v1/echo", "application/json", "{not json").statusCode());
        assertEquals(413, post("/api/v1/echo", "application/json", "\"" + "x".repeat(OpsServer.MAX_BODY) + "\"").statusCode());
        var failed = get("/api/v1/fail");
        assertEquals(500, failed.statusCode());
        assertFalse(failed.body().contains("broken handler"), "internals stay in the log");
    }

    /** Against DNS rebinding: a request for another host name is refused. */
    @Test
    void foreignHostHeadersAreRefused() throws Exception{
        assertEquals(403, rawStatus("evil.example:" + server.address().getPort()));
        assertEquals(403, rawStatus("127.0.0.1:1"));
        assertEquals(200, rawStatus("localhost:" + server.address().getPort()));
        assertEquals(200, rawStatus("127.0.0.1:" + server.address().getPort()));
    }

    int rawStatus(String host) throws IOException{
        try(var socket = new Socket(server.address().getAddress(), server.address().getPort())){
            socket.setSoTimeout((int)Hang.MILLIS);
            var out = socket.getOutputStream();
            out.write(("GET /livez HTTP/1.1\r\nHost: " + host + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            out.flush();
            String status = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII)).readLine();
            return Integer.parseInt(status.split(" ")[1]);
        }
    }

    @Test
    void serverSentEvents() throws Exception{
        var lines = new LinkedBlockingQueue<String>();
        var first = openStream(lines, null);
        events.publish("ban.created", Map.of("id", 1));
        events.publish("world.state", Map.of("world", "alpha", "state", "running"));
        assertEquals(List.of("id: 1", "event: ban.created", "data: {\"id\":1}", ""), take(lines, 4, "retry: 2000"));
        assertEquals(List.of("id: 2", "event: world.state"), take(lines, 2, null));

        // resume after event 1: event 2 is replayed
        var resumed = new LinkedBlockingQueue<String>();
        var second = openStream(resumed, "1");
        assertEquals(List.of("id: 2", "event: world.state"), take(resumed, 2, "retry: 2000"));

        // an id from before a restart is told it missed events
        var stale = new LinkedBlockingQueue<String>();
        var third = openStream(stale, "999");
        assertEquals(List.of("event: missed", "data: {}"), take(stale, 2, "retry: 2000"));

        assertEquals(3, events.subscribers());
        server.close();
        for(var stream : List.of(first, second, third)) stream.get(Hang.SECONDS, TimeUnit.SECONDS);
        assertEquals(0, events.subscribers(), "closing ends every stream");
    }

    CompletableFuture<Void> openStream(BlockingQueue<String> lines, String lastEventId) throws Exception{
        var request = HttpRequest.newBuilder(server.uri().resolve("/api/v1/events"));
        if(lastEventId != null) request.header("Last-Event-ID", lastEventId);
        var response = client.send(request.build(), HttpResponse.BodyHandlers.ofLines());
        assertEquals(200, response.statusCode());
        assertEquals("text/event-stream; charset=utf-8", response.headers().firstValue("Content-Type").orElseThrow());
        return CompletableFuture.runAsync(() -> response.body().forEach(lines::add), Executors.newVirtualThreadPerTaskExecutor());
    }

    /** Skips to the line after {@code after} (if given), then returns the next {@code count} lines. */
    static List<String> take(BlockingQueue<String> lines, int count, String after) throws InterruptedException{
        if(after != null){
            while(true){
                String line = lines.poll(Hang.SECONDS, TimeUnit.SECONDS);
                assertNotNull(line, "timed out before " + after);
                if(line.equals(after)) break;
            }
            assertEquals("", lines.poll(Hang.SECONDS, TimeUnit.SECONDS));
        }
        var out = new ArrayList<String>();
        while(out.size() < count){
            String line = lines.poll(Hang.SECONDS, TimeUnit.SECONDS);
            assertNotNull(line, "timed out after " + out);
            out.add(line);
        }
        return out;
    }

    @Test
    void slowSubscribersAreCutOff(){
        var subscription = events.subscribe(0);
        for(int i = 0; i <= Events.QUEUE; i++) events.publish("tick", Map.of("i", i));
        assertTrue(subscription.overflowed());
        subscription.close();
        assertEquals(0, events.subscribers());
        assertThrows(IllegalArgumentException.class, () -> events.publish("Bad Type", Map.of()));
    }
}
