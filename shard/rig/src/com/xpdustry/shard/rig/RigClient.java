package com.xpdustry.shard.rig;

import arc.*;
import arc.scene.*;
import arc.scene.ui.*;
import arc.scene.ui.layout.*;
import arc.util.*;
import mindustry.*;
import mindustry.desktop.*;
import mindustry.gen.*;
import mindustry.world.*;
import mindustry.world.blocks.ConstructBlock.*;

import java.io.*;
import java.net.*;
import java.nio.charset.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * Runs inside an unmodified vanilla desktop client JVM: boots {@link DesktopLauncher}, then answers the rig on a
 * loopback control socket. It only reads client state and calls the client's own UI entry points (the join dialog's
 * connect, the pause menu's quit); every key press and click comes from {@link RigInput} as real X11 events.
 *
 * <p>Protocol: one request line, one reply line, {@code ok} or {@code err <message>}, then tab-separated
 * {@code key=value} pairs. Coordinates in replies are window pixels, origin top left, like X11.
 *
 * <pre>java -cp Mindustry.jar:build com.xpdustry.shard.rig.RigClient &lt;name&gt; &lt;controlPort&gt; &lt;width&gt; &lt;height&gt;</pre>
 */
public final class RigClient{
    static String name;
    /** Set by {@code quit}; a disconnect without it is unexpected and gets logged with the dialog that explains it. */
    static volatile boolean expectDisconnect;
    static volatile boolean wasConnected;
    static volatile int unexpectedDisconnects;
    static volatile String lastDisconnect = "";

    public static void main(String[] args) throws IOException{
        name = args[0];
        int port = Integer.parseInt(args[1]);
        var server = new ServerSocket(port, 4, InetAddress.getLoopbackAddress());
        Thread.ofPlatform().daemon().name("rig-control").start(() -> serve(server));
        Thread.ofPlatform().daemon().name("rig-watch").start(RigClient::watch);
        DesktopLauncher.main(new String[]{"-width", args[2], "-height", args[3], "-maximized", "false"});
    }

    static void serve(ServerSocket server){
        while(true){
            try(var socket = server.accept();
                var in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                var out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true)){
                for(String line; (line = in.readLine()) != null; ) out.println(handle(line));
            }catch(IOException e){
                System.out.println("[rig] control connection closed: " + e);
            }
        }
    }

    /** Polls the connection on the app thread; a drop the rig didn't ask for is logged to last_log.txt with its dialog text. */
    static void watch(){
        while(true){
            sleep(100);
            if(!Vars.clientLoaded) continue;
            onApp(() -> {
                // worldDataBegin (a new map, /sync, a transfer) resets to the menu while the next world loads: still connected
                boolean connected = Vars.net.client() && (Vars.state.isGame() || Vars.netClient.isConnecting());
                if(wasConnected && !connected){
                    if(expectDisconnect){
                        Log.info("[rig] disconnected (requested)");
                    }else{
                        unexpectedDisconnects++;
                        lastDisconnect = dialogs();
                        Log.warn("[rig] disconnected unexpectedly: @", lastDisconnect);
                    }
                }
                wasConnected = connected;
                return "";
            });
        }
    }

    static String handle(String line){
        String[] a = line.trim().split(" ");
        try{
            if(a[0].equals("loaded")) return ok("loaded", Vars.clientLoaded && Vars.ui != null && Vars.ui.join != null);
            if(!Vars.clientLoaded) return "err client not loaded";
            if(a[0].equals("js")) return js(line.trim().substring(2).strip());
            return onApp(() -> switch(a[0]){
                case "status" -> status();
                case "connect" -> {
                    Vars.player.name = name;
                    // "planned": the server is expected to turn this connect away
                    expectDisconnect = a.length > 3 && a[3].equals("planned");
                    Log.info("[rig] connect @:@ as @", a[1], a[2], name);
                    stopNetThreads();
                    Vars.ui.join.connect(a[1], Integer.parseInt(a[2]));
                    yield ok();
                }
                case "quit" -> {
                    expectDisconnect = true;
                    Log.info("[rig] quit through the pause menu path");
                    Vars.ui.paused.runExitSave();
                    yield ok();
                }
                case "expectdrop" -> {
                    // the script is about to stop the server: the next disconnect is planned
                    expectDisconnect = true;
                    yield ok();
                }
                case "project" -> project(Float.parseFloat(a[1]), Float.parseFloat(a[2]));
                case "ui" -> ui(a[1]);
                case "hover" -> hover(a[1]);
                case "block" -> block(a[1]);
                case "tile" -> tile(Integer.parseInt(a[1]), Integer.parseInt(a[2]));
                case "players" -> players();
                case "dialogs" -> ok("text", dialogs());
                case "dialogbutton" -> dialogButton();
                case "chat" -> chat();
                case "mark" -> {
                    // stdout only: a script line in last_log.txt would match the script's own log waits
                    System.out.println("[rig] step " + line.substring(5));
                    yield ok();
                }
                default -> "err unknown command " + a[0];
            });
        }catch(RuntimeException e){
            return "err " + clean(String.valueOf(e));
        }
    }

    /**
     * {@code js <expr>}: vanilla's own script console ({@code Scripts.runConsole}) on the app thread, which reads any
     * client state without a typed probe for it. The first call creates the engine there, as the dev console does
     * (Rhino binds it to that thread). A script error comes back as its message ({@code EcmaError: ...}), like the
     * console shows it; a script that never returns fails the probe after 10 s, and nothing else waits on it.
     */
    static String js(String expr){
        if(expr.isEmpty()) return "err js needs an expression";
        return onApp(() -> {
            var scripts = Vars.mods.getScripts();
            if(scripts.hasErrored()) return "err the script engine failed to start (see last_log.txt)";
            return ok("value", scripts.runConsole(expr));
        });
    }

    /**
     * Vanilla's {@code ArcNetProvider.connectClient} stops the client and at once starts a new update thread, whose
     * {@code run()} clears the stop flag: an old update thread that hasn't seen the flag yet runs on, two threads read
     * one socket, and the client reads garbage as a frame length ("mismatch": "Unable to read object larger than read
     * buffer: 25445", bytes "ce", after the 5th rejoin of shard-maps.rig). Stopping the client here and waiting for its
     * update threads closes the race; connectClient's own {@code stop()} is then a no-op.
     */
    static void stopNetThreads(){
        if(!(Reflect.get(Vars.net, "provider") instanceof mindustry.net.ArcNetProvider provider)) return;
        arc.net.Client client = Reflect.get(provider, "client");
        client.stop();
        for(Thread thread : Thread.getAllStackTraces().keySet()){
            if(!thread.getName().equals("Net Client")) continue;
            try{
                thread.join(2000);
            }catch(InterruptedException e){
                Thread.currentThread().interrupt();
            }
            if(thread.isAlive()) Log.warn("[rig] a Net Client thread is still running after its stop");
        }
    }

    static String status(){
        var unit = Vars.player.unit();
        boolean alive = unit != null && !Vars.player.dead();
        var cam = Core.camera;
        float mx = Core.input.mouseX(), my = Core.input.mouseY();
        var mouse = Core.input.mouseWorld(mx, my);
        var input = Vars.control.input;
        return ok(
            "state", Vars.state.getState(),
            "client", Vars.net.client(),
            "ingame", Vars.net.client() && Vars.state.isGame(),
            "name", Vars.player.name,
            "player", Vars.player.id,
            "unit", alive ? unit.type.name + "#" + unit.id : "none",
            "x", Vars.player.x, "y", Vars.player.y,
            "camx", cam.position.x, "camy", cam.position.y,
            "scale", Vars.renderer.getDisplayScale(), "target", Vars.renderer.getScale(),
            "w", Core.graphics.getWidth(), "h", Core.graphics.getHeight(),
            "mx", mx, "my", Core.graphics.getHeight() - my,
            "mtx", mindustry.core.World.toTile(mouse.x), "mty", mindustry.core.World.toTile(mouse.y),
            "selected", input.block == null ? "none" : input.block.name,
            "rotation", input.rotation,
            "plans", alive ? unit.plans().size : 0,
            "tick", Vars.state.tick,
            "players", Groups.player.size(),
            "chat", Vars.ui.chatfrag.shown(),
            "unexpected", unexpectedDisconnects,
            "lastDisconnect", lastDisconnect
        );
    }

    /** World to window pixels with the camera of this frame: the view is centred on the camera, which follows the player. */
    static String project(float wx, float wy){
        var cam = Core.camera;
        float w = Core.graphics.getWidth(), h = Core.graphics.getHeight();
        float sx = (wx - cam.position.x) / cam.width * w + w / 2f;
        float sy = (wy - cam.position.y) / cam.height * h + h / 2f;
        return ok("sx", sx, "sy", h - sy, "onscreen", sx >= 0 && sx < w && sy >= 0 && sy < h);
    }

    /** The centre of a named UI element (placement menu buttons are {@code block-<name>} and {@code category-<name>}). */
    static String ui(String elementName){
        Element e = Core.scene.find(elementName);
        if(e == null) return "err no element named " + elementName;
        if(!shown(e)) return "err element " + elementName + " is hidden";
        var v = e.localToStageCoordinates(Tmp.v1.set(e.getWidth() / 2f, e.getHeight() / 2f));
        return ok("sx", v.x, "sy", Core.graphics.getHeight() - v.y);
    }

    /** Whether the element under the mouse is the named element or inside it. */
    static String hover(String elementName){
        Element e = Core.scene.find(elementName);
        Element hit = Core.scene.hit(Core.input.mouseX(), Core.input.mouseY(), true);
        return ok("hit", e != null && hit != null && (hit == e || hit.isDescendantOf(e)), "element", hit == null ? "none" : hit.name);
    }

    static boolean shown(Element e){
        for(Element at = e; at != null; at = at.parent) if(!at.visible) return false;
        return true;
    }

    static String block(String blockName){
        Block block = Vars.content.block(blockName);
        if(block == null) return "err no block named " + blockName;
        return ok("category", block.category.name(), "placeable", block.isPlaceable(), "rotate", block.rotate, "size", block.size);
    }

    static String tile(int x, int y){
        Tile tile = Vars.world.tile(x, y);
        if(tile == null) return "err tile " + x + "," + y + " is outside the world";
        String block = tile.build instanceof ConstructBuild c ? "constructing:" + c.current.name : tile.block().name;
        return ok("block", block, "floor", tile.floor().name, "overlay", tile.overlay().name,
            "team", tile.build == null ? "none" : tile.build.team.name, "rotation", tile.build == null ? 0 : tile.build.rotation);
    }

    static String players(){
        var list = new StringJoiner(",");
        Groups.player.each(p -> list.add(clean(p.plainName()).replace(',', '_').replace(' ', '_') + "@" + p.x + "@" + p.y));
        return ok("list", list.toString());
    }

    /** The chat panel's messages, newest first, separated by {@code \u001f}; the client doesn't log what it receives. */
    @SuppressWarnings("unchecked")
    static String chat(){
        try{
            var field = Vars.ui.chatfrag.getClass().getDeclaredField("messages");
            field.setAccessible(true);
            var messages = (arc.struct.Seq<String>)field.get(Vars.ui.chatfrag);
            var out = new StringJoiner("\u001f");
            for(String m : messages) out.add(clean(m));
            return ok("messages", out.toString());
        }catch(ReflectiveOperationException e){
            throw new RuntimeException(e);
        }
    }

    /** Titles and labels of every visible dialog, which is where the client says why it was kicked or dropped. */
    static String dialogs(){
        var text = new StringJoiner(" | ");
        for(Element e : Core.scene.root.getChildren()){
            if(e instanceof Dialog d && d.isShown()){
                var labels = new StringJoiner(" ");
                labels.add(d.title.getText().toString());
                collectLabels(d.cont, labels);
                text.add(labels.toString());
            }
        }
        return clean(text.toString());
    }

    /** The first button of the topmost dialog (OK on an error), for the rig to click like a player would. */
    static String dialogButton(){
        Dialog top = null;
        for(Element e : Core.scene.root.getChildren()) if(e instanceof Dialog d && d.isShown()) top = d;
        if(top == null) return ok("found", false);
        for(Element e : top.buttons.getChildren()){
            if(e instanceof Button && shown(e)){
                var v = e.localToStageCoordinates(Tmp.v1.set(e.getWidth() / 2f, e.getHeight() / 2f));
                return ok("found", true, "sx", v.x, "sy", Core.graphics.getHeight() - v.y, "title", top.title.getText());
            }
        }
        return "err the dialog '" + clean(top.title.getText().toString()) + "' has no button";
    }

    static void collectLabels(Group group, StringJoiner out){
        for(Element e : group.getChildren()){
            if(e instanceof Label l) out.add(l.getText().toString());
            else if(e instanceof Group g) collectLabels(g, out);
        }
    }

    static <T> T onApp(Callable<T> task){
        var future = new CompletableFuture<T>();
        Core.app.post(() -> {
            try{
                future.complete(task.call());
            }catch(Throwable t){
                future.completeExceptionally(t);
            }
        });
        try{
            return future.get(10, TimeUnit.SECONDS);
        }catch(ExecutionException e){
            throw new RuntimeException(e.getCause());
        }catch(InterruptedException | TimeoutException e){
            throw new RuntimeException("app thread did not answer in 10 s: " + e);
        }
    }

    static String ok(Object... pairs){
        var out = new StringBuilder("ok");
        for(int i = 0; i < pairs.length; i += 2) out.append('\t').append(pairs[i]).append('=').append(clean(String.valueOf(pairs[i + 1])));
        return out.toString();
    }

    static String clean(String text){
        return Strings.stripColors(text).replace('\t', ' ').replace('\n', ' ').replace('\r', ' ');
    }

    static void sleep(long ms){
        try{
            Thread.sleep(ms);
        }catch(InterruptedException e){
            throw new RuntimeException(e);
        }
    }
}
