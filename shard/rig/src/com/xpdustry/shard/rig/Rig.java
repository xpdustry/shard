package com.xpdustry.shard.rig;

import com.xpdustry.shard.rig.Script.*;

import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import java.util.regex.*;

/**
 * Runs one {@code .rig} script against a server and N vanilla clients on this machine (a boat VM, inside
 * {@code unshare -rn}), then writes {@code verdict.json} and {@code summary.txt}. Exit code 0 means the verdict is
 * pass. {@code rig/run.sh} is the entry point from the dev box; the script reference is {@code rig/README.md}.
 *
 * <pre>java -cp build com.xpdustry.shard.rig.Rig --server CMD --clients N --script FILE --out DIR
 *     [--port 6567] [--jar Mindustry.jar] [--java java] [--size 1280x720]</pre>
 */
public final class Rig{
    static final List<String> names = List.of("alpha", "bravo", "charlie", "delta", "echo", "foxtrot", "golf", "hotel");
    static final int tilesize = 8;

    /** Where things are on this machine, and what the server command sees in its environment. */
    /**
     * @param serverCpus the CPUs the server's command runs on ({@code taskset -c}), or null for any
     * @param clientCpus the CPUs each client's Xvfb, JVM and input run on, or null for any
     */
    public record Env(String java, String jar, String classes, int width, int height, int port, Map<String, String> vars,
                      String serverCpus, String clientCpus){
        /** {@code command}, on {@code cpus} if set. */
        static List<String> pinned(String cpus, String... command){
            var out = new ArrayList<String>();
            if(cpus != null) out.addAll(List.of("taskset", "-c", cpus));
            out.addAll(List.of(command));
            return out;
        }
    }

    final Script script;
    final Path out, scriptFile;
    final Env env;
    final Server server;
    final Map<String, Client> clients = new LinkedHashMap<>();
    final List<Map<String, Object>> results = new ArrayList<>(), checks = new ArrayList<>();
    final PrintWriter trace;
    final long started = System.currentTimeMillis();
    long deadline;
    Map<String, Object> failure;
    /** The detail of the step that is running; actions add to it. */
    Map<String, Object> detail;

    Rig(Script script, Path scriptFile, Path out, Env env, String serverCommand, int clientCount) throws IOException{
        this.script = script;
        this.scriptFile = scriptFile;
        this.out = out;
        this.env = env;
        Files.createDirectories(out);
        this.trace = new PrintWriter(Files.newBufferedWriter(out.resolve("rig.log")), true);
        this.server = new Server(serverCommand, out, env);
        for(int i = 0; i < clientCount; i++) clients.put(names.get(i), new Client(names.get(i), i, out));
    }

    public static void main(String[] args) throws Exception{
        var opts = new HashMap<String, String>();
        for(int i = 0; i + 1 < args.length; i += 2){
            if(!args[i].startsWith("--")) usage("unexpected " + args[i]);
            opts.put(args[i].substring(2), args[i + 1]);
        }
        for(String required : List.of("server", "script", "out")) if(!opts.containsKey(required)) usage("missing --" + required);
        int clients = Integer.parseInt(opts.getOrDefault("clients", "1"));
        if(clients < 0 || clients > names.size()) usage("--clients is 0 to " + names.size());
        String[] size = opts.getOrDefault("size", "1280x720").split("x");
        int port = Integer.parseInt(opts.getOrDefault("port", "6567"));
        String classes = Path.of(Rig.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        String jar = opts.getOrDefault("jar", System.getProperty("user.home") + "/vanilla/v160.7/Mindustry.jar");
        var vars = new TreeMap<String, String>();
        vars.put("PORT", String.valueOf(port));
        vars.put("VANILLA", Path.of(jar).getParent().toString());
        vars.put("RIG_OUT", Path.of(opts.get("out")).toAbsolutePath().toString());
        if(opts.containsKey("push")) vars.put("PUSH", opts.get("push"));
        var env = new Env(opts.getOrDefault("java", ProcessHandle.current().info().command().orElse("java")), jar, classes,
            Integer.parseInt(size[0]), Integer.parseInt(size[1]), port, vars, opts.get("server-cpus"), opts.get("client-cpus"));

        Path scriptFile = Path.of(opts.get("script"));
        Script script;
        try{
            script = Script.parse(scriptFile);
        }catch(RigException e){
            System.err.println("rig: " + e.getMessage());
            System.exit(2);
            return;
        }
        var rig = new Rig(script, scriptFile, Path.of(opts.get("out")), env, opts.get("server"), clients);
        System.exit(rig.run() ? 0 : 1);
    }

    static void usage(String problem){
        System.err.println("rig: " + problem + "\nusage: Rig --server CMD --clients N --script FILE --out DIR [--port P] [--jar J] [--java J] [--size WxH] [--push DIR] [--server-cpus L] [--client-cpus L]");
        System.exit(2);
    }

    boolean run() throws Exception{
        Files.copy(scriptFile, out.resolve("script.rig"), StandardCopyOption.REPLACE_EXISTING);
        deadline = started + script.timeoutMillis;
        Runtime.getRuntime().addShutdownHook(new Thread(this::stopAll));
        for(String target : script.targets){
            if(!target.equals("all") && !clients.containsKey(target)){
                System.err.println("rig: the script uses client '" + target + "' but the run has " + clients.keySet() + "; pass --clients");
                return false;
            }
        }

        log("server: " + server.command);
        try{
            server.start();
            for(var c : clients.values()){
                log(c.name + ": starting on " + c.display);
                c.start(env);
            }
        }catch(RigException | IOException e){
            fail(null, "setup: " + e.getMessage());
        }

        for(var step : script.steps){
            var result = new LinkedHashMap<String, Object>();
            result.put("line", step.line());
            result.put("text", step.text());
            results.add(result);
            if(failure != null){
                result.put("status", "skipped");
                continue;
            }
            long t0 = System.currentTimeMillis();
            detail = new LinkedHashMap<>();
            log("line " + step.line() + ": " + step.text());
            try{
                health();
                execute(step);
                health();
                result.put("status", "pass");
            }catch(RigException e){
                result.put("status", "fail");
                fail(step, e.getMessage());
            }catch(RuntimeException e){
                result.put("status", "fail");
                fail(step, "rig error: " + e);
                e.printStackTrace(trace);
            }
            result.put("seconds", (System.currentTimeMillis() - t0) / 1000.0);
            if(!detail.isEmpty()) result.put("detail", detail);
        }

        finish();
        return failure == null;
    }

    void fail(Step step, String message){
        log("FAIL " + (step == null ? "" : "line " + step.line() + ": ") + message);
        if(failure != null) return;
        failure = new LinkedHashMap<>();
        failure.put("line", step == null ? null : step.line());
        failure.put("step", step == null ? null : step.text());
        failure.put("message", message);
    }

    // ---- dispatch

    void execute(Step s){
        if(s.target() == null && !isClientAction(s.action())){
            global(s);
            return;
        }
        Collection<Client> targets;
        if(s.target() == null){
            if(clients.size() != 1) throw new RigException("'" + s.action() + "' needs a client: write 'alpha: " + s.action() + " ...'");
            targets = clients.values();
        }else{
            targets = s.target().equals("all") ? clients.values() : List.of(clients.get(s.target()));
        }
        for(var c : targets){
            if(c.joined) c.tryAsk("mark line " + s.line() + ": " + s.text());
            client(c, s);
        }
    }

    static boolean isClientAction(String action){
        return !Script.globalActions.contains(action);
    }

    void global(Step s){
        switch(s.action()){
            case "wait" -> {
                if(s.word(0).equals("port")){
                    int i = 1, port = env.port();
                    if(s.has(i) && !Script.isDuration(s.word(i))) port = Integer.parseInt(s.word(i++));
                    int p = port;
                    waitUntil("the server to accept connections on port " + p, s.has(i) ? Script.millis(s.word(i)) : 60_000, () -> {
                        try{
                            new java.net.Socket(java.net.InetAddress.getLoopbackAddress(), p).close();
                            return true;
                        }catch(IOException e){
                            return false;
                        }
                    });
                }else if(s.word(0).equals("log")){
                    var log = logOf(s.word(1));
                    var pattern = s.arg(2).regex();
                    long timeout = s.has(3) ? Script.millis(s.word(3)) : 30_000;
                    String[] hit = {null};
                    waitUntil("/" + pattern + "/ in " + s.word(1) + "'s log", timeout, () -> (hit[0] = log.next(pattern)) != null);
                    detail.put("matched", hit[0]);
                }else{
                    sleep(Script.millis(s.word(0)));
                }
            }
            case "console" -> {
                server.console(s.raw());
                detail.put("sent", s.raw());
            }
            case "server" -> {
                switch(s.word(0)){
                    case "start" -> io(server::start);
                    case "stop" -> {
                        expectDrops();
                        server.stoppedByScript = true;
                        io(() -> server.stop(10_000));
                        detail.put("exit", server.exit());
                    }
                    case "kill" -> {
                        // An unannounced SIGKILL is a crash: the run fails, unless the script says it expects it.
                        server.stoppedByScript = s.has(1) && s.word(1).equals("expected");
                        if(server.stoppedByScript) expectDrops();
                        server.kill();
                        detail.put("exit", server.exit());
                    }
                    default -> throw new RigException("server takes start, stop or kill");
                }
            }
            case "exec" -> exec(s.raw());
            case "assert" -> assertion(s, null);
            default -> throw new RigException("unknown action " + s.action());
        }
    }

    void client(Client c, Step s){
        switch(s.action()){
            case "join" -> {
                String host = "127.0.0.1";
                int port = env.port();
                for(var t : s.args()){
                    if(t.text().equals(NO_UNIT)) continue;
                    String[] hp = t.text().split(":");
                    host = hp[0];
                    if(hp.length > 1) port = Integer.parseInt(hp[1]);
                }
                join(c, host, port, !noUnit(s));
            }
            case "reconnect" -> {
                if(ingame(c)) throw new RigException(c.name + " is still connected; use disconnect first, or rejoin");
                join(c, "127.0.0.1", env.port(), !noUnit(s));
            }
            case "rejoin" -> {
                disconnect(c);
                sleep(1000);
                join(c, "127.0.0.1", env.port(), !noUnit(s));
            }
            case "disconnect" -> disconnect(c);
            case "dismiss" -> dismiss(c);
            // the next disconnect is planned: a kick the script is about to cause
            case "drop" -> c.ask("expectdrop");
            case "refused" -> refused(c, s.has(0) ? s.arg(0).regex() : null, s.has(1) ? Script.millis(s.word(1)) : 30_000);
            case "hold" -> {
                String[] keys = s.word(0).split("\\+");
                long ms = Script.millis(s.word(1));
                var before = c.ask("status");
                try{
                    // MOUSE_LEFT, MOUSE_RIGHT and MOUSE_MIDDLE hold a button where the pointer is: CONTROL+MOUSE_LEFT
                    // is a ctrl-click, MOUSE_LEFT alone holds the trigger
                    for(String k : keys) c.input(k.startsWith("MOUSE_") ? "down " + mouseButton(k) : "keydown " + k);
                    sleep(ms);
                }finally{
                    for(int i = keys.length - 1; i >= 0; i--){
                        c.input(keys[i].startsWith("MOUSE_") ? "up " + mouseButton(keys[i]) : "keyup " + keys[i]);
                    }
                }
                var after = c.ask("status");
                detail.put(c.name + ".moved", String.format(Locale.ROOT, "%.1f tiles", dist(before, after) / tilesize));
            }
            case "press", "hotbar" -> {
                for(var t : s.args()) press(c, t.text());
            }
            case "type" -> c.input("type " + s.arg(0).text());
            case "chat" -> chat(c, s.arg(0).text());
            case "scroll" -> {
                c.input("wheel " + Integer.parseInt(s.word(0)));
                settle(c);
            }
            case "zoom" -> zoom(c, Float.parseFloat(s.word(0)));
            case "move" -> pointTo(c, position(s, 0).pos);
            case "click" -> {
                int i = 0;
                String button = "left";
                if(s.word(0).matches("left|right|middle")) button = s.word(i++);
                click(c, position(s, i).pos, button);
            }
            case "drag" -> {
                int i = 0;
                String button = "left";
                if(s.word(0).matches("left|right|middle")) button = s.word(i++);
                var from = position(s, i);
                var to = position(s, from.next);
                drag(c, from.pos, to.pos, button);
            }
            case "select" -> select(c, s.word(0));
            case "rotate" -> rotate(c, Integer.parseInt(s.word(0)));
            case "place" -> {
                var at = position(s, 1);
                Integer rotation = null;
                if(s.has(at.next) && s.word(at.next).equals("rot")) rotation = Integer.parseInt(s.word(at.next + 1));
                place(c, s.word(0), at.pos, rotation);
            }
            case "shot" -> shot(c, s.word(0), s.has(1) && s.word(1).equals("any"));
            case "mark" -> {
                var st = c.ask("status");
                c.markX = f(st, "x");
                c.markY = f(st, "y");
            }
            case "expect" -> expect(c, s);
            case "sample" -> sample(c, s);
            case "assert" -> assertion(s, c);
            case "wait" -> {
                String d = s.word(0);
                if(d.endsWith("t") && Script.isDuration(d)){
                    double ticks = Double.parseDouble(d.substring(0, d.length() - 1));
                    double from = Double.parseDouble(c.ask("status").get("tick"));
                    waitUntil(c.name + " to simulate " + d, (long)(ticks / 60 * 10_000) + 10_000,
                        () -> Double.parseDouble(c.ask("status").get("tick")) - from >= ticks);
                }else{
                    sleep(Script.millis(d));
                }
            }
            default -> throw new RigException("unknown action " + s.action());
        }
    }

    // ---- connection

    /** The join argument for a server that doesn't spawn players yet: the world loaded is enough. */
    static final String NO_UNIT = "nounit";

    static boolean noUnit(Script.Step s){
        return s.args().stream().anyMatch(t -> t.text().equals(NO_UNIT));
    }

    void join(Client c, String host, int port, boolean unit){
        waitUntil(c.name + " to finish loading", 180_000, () -> {
            var r = c.tryAsk("loaded");
            return r != null && r.get("loaded").equals("true");
        });
        if(!c.calibrated) calibrate(c);
        if(ingame(c)) throw new RigException(c.name + " is already in a game");
        dismiss(c);
        c.ask("connect " + host + " " + port);
        var status = new AtomicReference<Map<String, String>>();
        waitUntil(c.name + " to join " + host + ":" + port + (unit ? " and spawn" : ""), 120_000, () -> {
            var st = c.ask("status");
            status.set(st);
            return st.get("ingame").equals("true") && (!unit || !st.get("unit").equals("none"));
        });
        var st = status.get();
        c.joined = true;
        c.baselineUnexpected = Integer.parseInt(st.get("unexpected"));
        c.input("move " + (c.offsetX + env.width() / 2) + " " + (c.offsetY + env.height() / 2));
        detail.put(c.name + ".unit", st.get("unit"));
        detail.put(c.name + ".tile", tileOf(st));
        log(c.name + ": joined as player " + st.get("player") + " with " + st.get("unit") + " at tile " + tileOf(st));
    }

    /** Connects, expecting the server to turn the client away, and waits for a dialog that matches {@code pattern}. */
    void refused(Client c, java.util.regex.Pattern pattern, long timeout){
        if(ingame(c)) throw new RigException(c.name + " is already in a game");
        dismiss(c);
        c.ask("connect 127.0.0.1 " + env.port() + " planned");
        String[] seen = {""};
        waitUntil(c.name + " to be refused" + (pattern == null ? "" : " with /" + pattern + "/"), timeout, () -> {
            seen[0] = c.ask("dialogs").getOrDefault("text", "");
            return !ingame(c) && !seen[0].isEmpty() && (pattern == null || pattern.matcher(seen[0]).find());
        });
        if(ingame(c)) throw new RigException(c.name + " joined: the server didn't refuse it");
        detail.put(c.name + ".refused", seen[0]);
        log(c.name + ": refused: " + seen[0]);
    }

    void disconnect(Client c){
        if(!ingame(c)) throw new RigException(c.name + " is not in a game");
        c.ask("quit");
        waitUntil(c.name + " to leave the game", 15_000, () -> !ingame(c));
    }

    /** Clicks away open dialogs (a disconnect message stays up over the next game otherwise), as a player would. */
    void dismiss(Client c){
        for(int i = 0; i < 5; i++){
            var r = c.ask("dialogbutton");
            if(r.get("found").equals("false")) return;
            detail.merge(c.name + ".dismissed", r.get("title"), (a, b) -> a + " | " + b);
            click(c, new Pos("screen", c.offsetX + f(r, "sx"), c.offsetY + f(r, "sy"), null), "left");
            sleep(300);
        }
        throw new RigException(c.name + ": dialogs keep coming back: " + c.ask("dialogs").get("text"));
    }

    boolean ingame(Client c){
        var r = c.tryAsk("status");
        return r != null && r.get("ingame").equals("true");
    }

    /**
     * Finds where the client window sits on the X screen: point the mouse, read back what the client saw. Until SDL
     * gets its first motion event the client reports a stale position, so two probes in a row must agree.
     */
    void calibrate(Client c){
        int[][] probes = {{3, 4}, {2, 2}, {3, 4}, {2, 3}, {4, 3}, {2, 2}, {3, 2}};
        int[] off = null;
        for(int i = 0; ; i++){
            if(i == probes.length) throw new RigException(c.name + ": the client never agreed on the pointer position (last offset " + Arrays.toString(off) + ")");
            int x = env.width() / probes[i][0], y = env.height() / probes[i][1];
            c.input("move " + x + " " + y);
            sleep(300);
            var st = c.ask("status");
            int[] o = {x - Math.round(f(st, "mx")), y - Math.round(f(st, "my"))};
            if(Arrays.equals(o, off)) break;
            off = o;
        }
        c.offsetX = off[0];
        c.offsetY = off[1];
        c.calibrated = true;
        var st = c.ask("status");
        log(c.name + ": window " + st.get("w") + "x" + st.get("h") + " at screen offset " + c.offsetX + "," + c.offsetY);
    }

    // ---- input

    void press(Client c, String key){
        c.input("keydown " + key);
        sleep(50);
        c.input("keyup " + key);
        sleep(100);
    }

    void chat(Client c, String text){
        press(c, "ENTER");
        waitUntil(c.name + "'s chat field to open", 3000, () -> c.ask("status").get("chat").equals("true"));
        c.input("type " + text);
        sleep(150);
        press(c, "ENTER");
        waitUntil(c.name + "'s chat field to close", 3000, () -> c.ask("status").get("chat").equals("false"));
    }

    /** Scrolls until the camera's target scale is within 10% of {@code scale}. With a rotatable block selected, the wheel rotates instead. */
    void zoom(Client c, float scale){
        for(int i = 0; i < 40; i++){
            var st = c.ask("status");
            float target = f(st, "target");
            if(Math.abs(target - scale) <= scale * 0.1f){
                settle(c);
                detail.put(c.name + ".scale", target);
                return;
            }
            if(!st.get("selected").equals("none")) throw new RigException(c.name + ": cannot zoom with " + st.get("selected") + " selected (the wheel rotates it)");
            c.input("wheel " + (target > scale ? 1 : -1));
            sleep(80);
            if(f(c.ask("status"), "target") == target) throw new RigException(c.name + ": zoom stuck at " + target + " (clamped?), wanted " + scale);
        }
        throw new RigException(c.name + ": could not reach zoom " + scale);
    }

    /** Waits until the camera has stopped following and zooming, so world-to-screen maths holds until the click. */
    void settle(Client c){
        Map<String, String> prev = null;
        for(int i = 0; i < 30; i++){
            var st = c.ask("status");
            if(prev != null && Math.abs(f(st, "camx") - f(prev, "camx")) < 0.05f && Math.abs(f(st, "camy") - f(prev, "camy")) < 0.05f
                && Math.abs(f(st, "scale") - f(st, "target")) < 0.001f) return;
            prev = st;
            sleep(100);
        }
        log(c.name + ": camera still moving after 3 s; using its last position");
    }

    record Pos(String kind, float a, float b, String name){
        @Override
        public String toString(){
            return kind.equals("ui") ? "ui " + name : kind + " " + fmt(a) + " " + fmt(b);
        }
    }

    record Parsed(Pos pos, int next){}

    static Parsed position(Step s, int i){
        String kind = s.word(i);
        return switch(kind){
            case "tile", "world", "screen" -> new Parsed(new Pos(kind, Float.parseFloat(s.word(i + 1)), Float.parseFloat(s.word(i + 2)), null), i + 3);
            case "ui" -> new Parsed(new Pos(kind, 0, 0, s.word(i + 1)), i + 2);
            case "player" -> new Parsed(new Pos(kind, 0, 0, null), i + 1);
            default -> throw new RigException("expected a position (tile X Y, world X Y, screen X Y, ui NAME, player), got " + kind);
        };
    }

    /** A {@code player} position is where the client's own player stands now, in world units; others are as given. */
    Pos resolved(Client c, Pos p){
        if(!p.kind.equals("player")) return p;
        var st = c.ask("status");
        return new Pos("world", f(st, "x"), f(st, "y"), null);
    }

    /** Screen pixel of a position, through the client's own camera (centre and zoom) or UI layout. */
    int[] screen(Client c, Pos p){
        p = resolved(c, p);
        return switch(p.kind){
            case "screen" -> new int[]{Math.round(p.a), Math.round(p.b)};
            case "ui" -> {
                var r = c.ask("ui " + p.name);
                yield new int[]{c.offsetX + Math.round(f(r, "sx")), c.offsetY + Math.round(f(r, "sy"))};
            }
            default -> {
                settle(c);
                float wx = p.kind.equals("tile") ? p.a * tilesize : p.a, wy = p.kind.equals("tile") ? p.b * tilesize : p.b;
                var r = c.ask("project " + wx + " " + wy);
                if(!r.get("onscreen").equals("true")){
                    var st = c.ask("status");
                    throw new RigException(c.name + ": " + p + " is off screen (player at tile " + tileOf(st) + ", scale " + st.get("scale") + "); walk closer or zoom out");
                }
                yield new int[]{c.offsetX + Math.round(f(r, "sx")), c.offsetY + Math.round(f(r, "sy"))};
            }
        };
    }

    /** Moves the pointer and checks that the client agrees about what is under it. */
    void pointTo(Client c, Pos p){
        p = resolved(c, p);
        String seen = null;
        for(int attempt = 0; attempt < 2; attempt++){
            int[] xy = screen(c, p);
            c.input("move " + xy[0] + " " + xy[1]);
            sleep(150);
            if(p.kind.equals("screen")) return;
            if(p.kind.equals("ui")){
                var r = c.ask("hover " + p.name);
                if(r.get("hit").equals("true")) return;
                seen = "element " + r.get("element");
            }else{
                var st = c.ask("status");
                int tx = Math.round(p.kind.equals("tile") ? p.a : p.a / tilesize), ty = Math.round(p.kind.equals("tile") ? p.b : p.b / tilesize);
                if(st.get("mtx").equals(String.valueOf(tx)) && st.get("mty").equals(String.valueOf(ty))) return;
                seen = "tile " + st.get("mtx") + "," + st.get("mty");
            }
        }
        throw new RigException(c.name + ": pointer for " + p + " lands on " + seen);
    }

    void click(Client c, Pos p, String button){
        pointTo(c, p);
        c.input("down " + button);
        sleep(80);
        c.input("up " + button);
        sleep(150);
    }

    void drag(Client c, Pos from, Pos to, String button){
        int[] b = screen(c, to);
        pointTo(c, from);
        int[] a = screen(c, from);
        c.input("down " + button);
        sleep(100);
        for(int i = 1; i <= 10; i++){
            c.input("move " + (a[0] + (b[0] - a[0]) * i / 10) + " " + (a[1] + (b[1] - a[1]) * i / 10));
            sleep(40);
        }
        sleep(100);
        c.input("up " + button);
        sleep(150);
    }

    /** Picks a block the way a player does: its category tab, then its button in the placement menu. */
    void select(Client c, String block){
        if(c.ask("status").get("selected").equals(block)) return;
        var info = c.ask("block " + block);
        click(c, new Pos("ui", 0, 0, "category-" + info.get("category")), "left");
        click(c, new Pos("ui", 0, 0, "block-" + block), "left");
        String selected = c.ask("status").get("selected");
        if(!selected.equals(block)) throw new RigException(c.name + ": clicked block-" + block + " but " + selected + " is selected");
    }

    void rotate(Client c, int rotation){
        for(int i = 0; i < 4; i++){
            if(Integer.parseInt(c.ask("status").get("rotation")) == rotation) return;
            c.input("wheel 1");
            sleep(120);
        }
        if(Integer.parseInt(c.ask("status").get("rotation")) != rotation) throw new RigException(c.name + ": could not rotate to " + rotation);
    }

    void place(Client c, String block, Pos at, Integer rotation){
        select(c, block);
        if(rotation != null) rotate(c, rotation);
        int before = Integer.parseInt(c.ask("status").get("plans"));
        click(c, at, "left");
        int tx = Math.round(at.kind.equals("tile") ? at.a : at.a / tilesize), ty = Math.round(at.kind.equals("tile") ? at.b : at.b / tilesize);
        waitUntil(c.name + " to queue or build " + block + " at " + tx + "," + ty, 3000, () -> {
            String now = c.ask("tile " + tx + " " + ty).get("block");
            return Integer.parseInt(c.ask("status").get("plans")) > before || now.equals(block) || now.equals("constructing:" + block);
        });
    }

    void shot(Client c, String name, boolean allowBlank){
        if(!c.alive()) throw new RigException(c.name + ": no client to screenshot");
        String file = String.format(Locale.ROOT, "%02d-%s.png", ++c.shots, name);
        String reply = c.input("shot " + c.dir.resolve("shots").resolve(file).toAbsolutePath());
        var stats = new LinkedHashMap<String, Object>();
        stats.put("file", c.name + "/shots/" + file);
        for(String kv : reply.substring(3).split(" ")){
            String[] p = kv.split("=");
            stats.put(p[0], Double.parseDouble(p[1]));
        }
        double mean = (double)stats.get("mean"), std = (double)stats.get("std"), colours = (double)stats.get("colours");
        boolean blank = (mean < 8 && std < 4) || colours < 16 || std < 2;
        stats.put("blank", blank);
        c.screenshots.add(stats);
        detail.put(c.name + ".shot", stats);
        log(c.name + ": screenshot " + file + " " + reply.substring(3) + (blank ? " BLANK" : ""));
        if(blank && !allowBlank) throw new RigException(c.name + ": screenshot " + file + " is black or empty (" + reply.substring(3) + ")");
    }

    // ---- expectations and assertions

    void expect(Client c, Step s){
        switch(s.word(0)){
            case "moved" -> {
                float tiles = Float.parseFloat(s.word(1));
                var st = c.ask("status");
                float moved = (float)Math.hypot(f(st, "x") - c.markX, f(st, "y") - c.markY) / tilesize;
                detail.put(c.name + ".moved", moved);
                if(moved < tiles) throw new RigException(c.name + " moved " + fmt(moved) + " tiles since mark, expected at least " + tiles);
            }
            case "tile" -> {
                int x = Integer.parseInt(s.word(1)), y = Integer.parseInt(s.word(2));
                String block = s.word(3);
                long timeout = s.has(4) ? Script.millis(s.word(4)) : 30_000;
                String[] seen = {null};
                try{
                    waitUntil(c.name + " to show " + block + " at " + x + "," + y, timeout,
                        () -> (seen[0] = c.ask("tile " + x + " " + y).get("block")).equals(block));
                }catch(RigException e){
                    throw new RigException(e.getMessage() + " (it shows " + seen[0] + ")");
                }
            }
            case "player" -> {
                String other = s.word(1);
                Float within = null;
                int i = 2;
                if(s.has(i) && s.word(i).equals("within")){
                    within = Float.parseFloat(s.word(i + 1));
                    i += 2;
                }
                long timeout = s.has(i) ? Script.millis(s.word(i)) : 10_000;
                Float limit = within;
                String[] last = {"not in the player list"};
                try{
                    waitUntil(c.name + " to see player " + other + (limit == null ? "" : " within " + limit + " tiles of where " + other + " is"), timeout, () -> {
                        for(String entry : c.ask("players").get("list").split(",")){
                            String[] p = entry.split("@");
                            if(p.length != 3 || !p[0].equals(other)) continue;
                            if(limit == null) return true;
                            var them = clients.get(other);
                            if(them == null) throw new RigException("'within' needs " + other + " to be a rig client");
                            var st = them.ask("status");
                            float d = (float)Math.hypot(Float.parseFloat(p[1]) - f(st, "x"), Float.parseFloat(p[2]) - f(st, "y")) / tilesize;
                            last[0] = fmt(d) + " tiles from where " + other + " is";
                            detail.put(c.name + ".sees." + other, last[0]);
                            return d <= limit;
                        }
                        return false;
                    });
                }catch(RigException e){
                    throw new RigException(e.getMessage() + " (" + last[0] + ")");
                }
            }
            case "chat" -> {
                var pattern = s.arg(1).regex();
                long timeout = s.has(2) ? Script.millis(s.word(2)) : 10_000;
                String[] hit = {null};
                waitUntil(c.name + "'s chat to show /" + pattern + "/", timeout, () -> {
                    for(String m : c.ask("chat").getOrDefault("messages", "").split("\u001f")){
                        if(pattern.matcher(m).find()){
                            hit[0] = m;
                            return true;
                        }
                    }
                    return false;
                });
                detail.put(c.name + ".chat", hit[0]);
            }
            case "dialog" -> {
                var pattern = s.arg(1).regex();
                String[] seen = {""};
                waitUntil(c.name + "'s dialogs to show /" + pattern + "/", s.has(2) ? Script.millis(s.word(2)) : 10_000, () -> {
                    seen[0] = c.ask("dialogs").getOrDefault("text", "");
                    return pattern.matcher(seen[0]).find();
                });
                detail.put(c.name + ".dialog", seen[0]);
            }
            case "connected" -> waitUntil(c.name + " to be in a game", s.has(1) ? Script.millis(s.word(1)) : 10_000, () -> ingame(c));
            case "disconnected" -> waitUntil(c.name + " to be out of the game", s.has(1) ? Script.millis(s.word(1)) : 10_000, () -> !ingame(c));
            case "selected" -> {
                String selected = c.ask("status").get("selected");
                if(!selected.equals(s.word(1))) throw new RigException(c.name + " has " + selected + " selected, expected " + s.word(1));
            }
            case "js" -> {
                String expr = s.word(1);
                var pattern = s.arg(2).regex();
                long timeout = s.has(3) ? Script.millis(s.word(3)) : 10_000;
                String[] last = {null};
                try{
                    waitUntil(c.name + "'s js `" + expr + "` to match /" + pattern + "/", timeout,
                        () -> pattern.matcher(last[0] = js(c, expr)).find());
                }catch(RigException e){
                    throw new RigException(e.getMessage() + " (it gives " + last[0] + ")");
                }
                detail.put(c.name + ".js", last[0]);
            }
            default -> throw new RigException("expect takes moved, tile, player, chat, connected, disconnected, selected or js");
        }
    }

    /** One {@code js} probe: what vanilla's script console prints for {@code expr}. */
    static String js(Client c, String expr){
        if(expr.indexOf('\n') >= 0 || expr.indexOf('\r') >= 0) throw new RigException("a js expression is one line");
        return c.ask("js " + expr).getOrDefault("value", "");
    }

    /**
     * {@code sample js "EXPR" every DUR for DUR to FILE}: probes the client at a fixed rate and writes
     * {@code <ms since the first probe>\t<value>} lines to FILE in the client's directory. The step takes the whole
     * duration; a probe that fails ends it.
     */
    void sample(Client c, Step s){
        if(!s.word(0).equals("js") || !s.word(2).equals("every") || !s.word(4).equals("for") || !s.word(6).equals("to")){
            throw new RigException("usage: sample js \"EXPR\" every DUR for DUR to FILE");
        }
        String expr = s.word(1);
        long every = Script.millis(s.word(3)), total = Script.millis(s.word(5));
        if(every <= 0) throw new RigException("sample needs a positive interval");
        Path file = c.dir.resolve(s.word(7));
        io(() -> {
            Files.createDirectories(file.toAbsolutePath().getParent());
            try(var writer = Files.newBufferedWriter(file)){
                long start = System.nanoTime(), samples = 0;
                for(long next = 0; next <= total; next += every){
                    long wait = next - (System.nanoTime() - start) / 1_000_000;
                    if(wait > 0) Thread.sleep(wait);
                    long at = (System.nanoTime() - start) / 1_000_000;
                    writer.write(at + "\t" + js(c, expr) + "\n");
                    samples++;
                }
                detail.put(c.name + ".samples." + file.getFileName(), samples);
                log(c.name + ": " + samples + " samples of `" + expr + "` in " + c.dir.relativize(file));
            }
        });
    }

    /** {@code assert log SRC /re/ [after /re/]}, {@code assert log SRC !/re/ [after /re/]}, {@code assert clean [SRC|all]}. */
    void assertion(Step s, Client target){
        int i = 0;
        String kind = s.word(i++);
        switch(kind){
            case "log" -> {
                String source = target != null ? target.name : s.word(i++);
                var token = s.arg(i);
                // after /re/: only the lines after the first that matches it
                var after = s.has(i + 1) && s.word(i + 1).equals("after") ? s.arg(i + 2).regex() : null;
                String hit = logOf(source).any(token.regex(), after);
                if(token.kind().equals("regex") && hit == null) throw new RigException("no line matches /" + token.text() + "/ in " + source + "'s log");
                if(token.kind().equals("notregex") && hit != null) throw new RigException(source + "'s log has a line matching /" + token.text() + "/: " + hit);
                if(hit != null) detail.put("matched", hit);
            }
            case "clean" -> {
                String source = target != null ? target.name : s.has(i) ? s.word(i) : "all";
                var problems = new ArrayList<String>();
                for(String src : source.equals("all") ? clients.keySet() : List.of(source)){
                    var p = problems(src);
                    if(!p.clean()) problems.add(src + ": " + describe(p));
                }
                if(!problems.isEmpty()) throw new RigException("log not clean: " + String.join("; ", problems));
            }
            default -> throw new RigException("assert takes log or clean");
        }
    }

    LogFile logOf(String source){
        if(source.equals("server")) return server.log;
        var c = clients.get(source);
        if(c == null) throw new RigException("no log named " + source + " (server or a client name)");
        return c.lastLog;
    }

    LogFile.Problems problems(String source){
        return source.equals("server") ? server.log.problems(script.allowServer, false) : logOf(source).problems(script.allowClient, true);
    }

    static String describe(LogFile.Problems p){
        var parts = new ArrayList<String>();
        if(!p.errors().isEmpty()) parts.add(p.errors().size() + " error(s), first: " + p.errors().getFirst());
        if(!p.desync().isEmpty()) parts.add(p.desync().size() + " desync line(s), first: " + p.desync().getFirst());
        if(!p.kicks().isEmpty()) parts.add(p.kicks().size() + " kick/disconnect line(s), first: " + p.kicks().getFirst());
        return String.join(", ", parts);
    }

    /** Tells every client that its next disconnect is the script's doing, before the script takes the server down. */
    void expectDrops(){
        for(var c : clients.values()) if(c.calibrated) c.tryAsk("expectdrop");
    }

    void exec(String command){
        try{
            var pb = new ProcessBuilder("bash", "-c", command).directory(out.toFile()).redirectErrorStream(true);
            pb.environment().putAll(env.vars());
            pb.environment().put("RIG_SERVER_PID", String.valueOf(server.pid()));
            var p = pb.start();
            String output = new String(p.getInputStream().readAllBytes());
            if(!p.waitFor(60, TimeUnit.SECONDS)) p.destroyForcibly();
            trace.print(output);
            detail.put("exit", p.exitValue());
            if(p.exitValue() != 0) throw new RigException("exec exited with " + p.exitValue() + ": " + output.strip());
        }catch(IOException | InterruptedException e){
            throw new RigException("exec: " + e);
        }
    }

    // ---- health, time

    /** Fails the step when something died or dropped that the script didn't ask for. */
    void health(){
        if(crashed() && !server.crashReported){
            server.crashReported = true;
            throw new RigException("the server exited unexpectedly with code " + server.exit() + " (see server/server.log)");
        }
        for(var c : clients.values()){
            if(c.game != null && !c.alive()) throw new RigException(c.name + ": the client process exited with code " + c.game.exitValue());
            if(!c.calibrated) continue;
            var st = c.tryAsk("status");
            if(st != null && Integer.parseInt(st.get("unexpected")) > c.baselineUnexpected){
                c.baselineUnexpected = Integer.parseInt(st.get("unexpected"));
                c.joined = false;
                throw new RigException(c.name + " was disconnected unexpectedly: " + st.get("lastDisconnect"));
            }
        }
        if(System.currentTimeMillis() > deadline) throw new RigException("the run hit its timeout of " + script.timeoutMillis / 1000 + " s");
    }

    boolean crashed(){
        return server.starts > 0 && !server.alive() && !server.stoppedByScript;
    }

    void sleep(long ms){
        long end = System.currentTimeMillis() + ms;
        while(true){
            long left = end - System.currentTimeMillis();
            if(left <= 0) return;
            try{
                Thread.sleep(Math.min(left, 250));
            }catch(InterruptedException e){
                throw new RigException("interrupted");
            }
            health();
        }
    }

    void waitUntil(String what, long timeoutMillis, BooleanSupplier done){
        long end = System.currentTimeMillis() + timeoutMillis;
        while(!done.getAsBoolean()){
            if(System.currentTimeMillis() > end) throw new RigException("timed out after " + fmt(timeoutMillis / 1000f) + " s waiting for " + what);
            sleep(250);
        }
    }

    interface IoAction{
        void run() throws IOException, InterruptedException;
    }

    static void io(IoAction action){
        try{
            action.run();
        }catch(IOException | InterruptedException e){
            throw new RigException(String.valueOf(e));
        }
    }

    // ---- the end of a run

    /** Final screenshots and log checks, then the verdict. These run even after a failed step. */
    void finish() throws IOException{
        detail = new LinkedHashMap<>();
        boolean crashed = crashed();
        Integer crashExit = server.exit();
        if(failure != null){
            try{
                Thread.sleep(1500); // let the client draw whatever dialog the failure brought up
            }catch(InterruptedException ignored){
            }
        }
        for(var c : clients.values()){
            if(!c.alive()) continue;
            try{
                shot(c, "final", true);
            }catch(RigException e){
                log("final screenshot of " + c.name + ": " + e.getMessage());
            }
        }
        stopAll();

        for(var c : clients.values()){
            var p = problems(c.name);
            check(c.name + " last_log.txt has no errors, desync or kick lines", p.clean() ? null : describe(p));
        }
        check("the server ran until the end, or the script stopped it", crashed ? "exited with code " + crashExit : null);

        var verdict = new LinkedHashMap<String, Object>();
        verdict.put("verdict", failure == null ? "pass" : "fail");
        verdict.put("failure", failure);
        verdict.put("script", scriptFile.getFileName().toString());
        verdict.put("server", server.command);
        verdict.put("started", Instant.ofEpochMilli(started).toString());
        verdict.put("seconds", (System.currentTimeMillis() - started) / 1000.0);
        var perClient = new LinkedHashMap<String, Object>();
        for(var c : clients.values()){
            var p = problems(c.name);
            var m = new LinkedHashMap<String, Object>();
            m.put("joined", c.calibrated && c.lastLog.any(Pattern.compile("\\[rig\\] connect")) != null);
            m.put("errors", p.errors());
            m.put("desync", p.desync());
            m.put("kicks", p.kicks());
            m.put("screenshots", c.screenshots);
            m.put("lastLog", c.name + "/data/Mindustry/last_log.txt");
            perClient.put(c.name, m);
        }
        verdict.put("clients", perClient);
        var srv = new LinkedHashMap<String, Object>();
        srv.put("exit", server.exit());
        srv.put("crashed", crashed);
        srv.put("stoppedByScript", server.stoppedByScript);
        srv.put("log", "server/server.log");
        srv.put("errors", server.log.problems(script.allowServer, false).errors());
        verdict.put("server", srv);
        verdict.put("checks", checks);
        verdict.put("steps", results);
        Files.writeString(out.resolve("verdict.json"), Json.write(verdict));
        Files.writeString(out.resolve("summary.txt"), summary());
        System.out.print(summary());
    }

    /** A final check: {@code problem} null means it passed. */
    void check(String name, String problem){
        var m = new LinkedHashMap<String, Object>();
        m.put("check", name);
        m.put("status", problem == null ? "pass" : "fail");
        if(problem != null) m.put("detail", problem);
        checks.add(m);
        if(problem != null) fail(null, name + ": " + problem);
    }

    String summary(){
        long passed = results.stream().filter(r -> "pass".equals(r.get("status"))).count();
        long skipped = results.stream().filter(r -> "skipped".equals(r.get("status"))).count();
        var sb = new StringBuilder();
        sb.append("verdict: ").append(failure == null ? "PASS" : "FAIL").append('\n');
        sb.append("script:  ").append(scriptFile.getFileName()).append(", ").append(clients.size()).append(" client(s), server: ").append(server.command).append('\n');
        sb.append("steps:   ").append(passed).append(" passed, ").append(results.size() - passed - skipped).append(" failed, ").append(skipped).append(" skipped\n");
        if(failure != null){
            sb.append("failure: ").append(failure.get("line") == null ? "" : "line " + failure.get("line") + " `" + failure.get("step") + "`: ").append(failure.get("message")).append('\n');
        }
        for(var c : checks) sb.append("check:   ").append(c.get("status")).append("  ").append(c.get("check")).append(c.containsKey("detail") ? " (" + c.get("detail") + ")" : "").append('\n');
        for(var c : clients.values()) sb.append("shots:   ").append(c.name).append(": ").append(c.screenshots.size()).append('\n');
        sb.append("out:     ").append(out.toAbsolutePath()).append('\n');
        return sb.toString();
    }

    void stopAll(){
        for(var c : clients.values()) c.stop();
        try{
            if(server.alive()) server.stop(5000);
        }catch(InterruptedException ignored){
        }
    }

    void log(String line){
        trace.printf(Locale.ROOT, "[%7.1fs] %s%n", (System.currentTimeMillis() - started) / 1000.0, line);
    }

    static float f(Map<String, String> m, String key){
        String v = m.get(key);
        if(v == null) throw new RigException("the client did not report " + key);
        return Float.parseFloat(v);
    }

    /** {@code MOUSE_LEFT} as RigInput's {@code left}. */
    static String mouseButton(String key){
        return key.substring("MOUSE_".length()).toLowerCase(Locale.ROOT);
    }

    static float dist(Map<String, String> a, Map<String, String> b){
        return (float)Math.hypot(f(a, "x") - f(b, "x"), f(a, "y") - f(b, "y"));
    }

    static String tileOf(Map<String, String> st){
        return Math.round(f(st, "x") / tilesize) + "," + Math.round(f(st, "y") / tilesize);
    }

    static String fmt(float v){
        return v == Math.round(v) ? String.valueOf(Math.round(v)) : String.format(Locale.ROOT, "%.1f", v);
    }
}
