package com.xpdustry.shard.oracle;

import arc.*;
import arc.files.*;
import arc.func.*;
import arc.math.*;
import arc.mock.*;
import arc.struct.*;
import arc.util.*;
import arc.util.Log.*;
import mindustry.*;
import mindustry.content.*;
import mindustry.core.*;
import mindustry.core.GameState.*;
import mindustry.entities.*;
import mindustry.game.EventType.*;
import mindustry.game.*;
import mindustry.gen.*;
import mindustry.io.*;
import mindustry.logic.*;
import mindustry.maps.Map;
import mindustry.mod.*;
import mindustry.net.*;
import mindustry.ui.*;
import mindustry.world.*;

import java.io.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.*;

import static mindustry.Vars.*;

/**
 * Vanilla Mindustry v160.7, booted headless inside this JVM: content, world, state and logic, no sockets and no loop
 * thread. Everything runs on the caller's thread; {@link #frame()} is one server frame.
 *
 * <p>Vanilla keeps all of its state in static globals, so there is one vanilla per JVM. {@link #boot()} sets these:
 * <ul>
 * <li>{@code arc.Core}: {@code app} (headless, posted tasks run in {@link #frame()}), {@code settings} (data dir below),
 * {@code files} ({@code MockFiles}: internal files come from the classpath, i.e. the vanilla jar), {@code graphics}
 * (fixed 1/60 s frames), {@code audio}, {@code input} (mocks). {@code Core.bundle} stays empty: names are not localized.
 * <li>{@code arc.util.Time}: the delta provider {@code ServerControl} installs, {@code min(frame * 60, maxDeltaServer)},
 * so {@code Time.delta == 1} each frame.
 * <li>{@code arc.util.Log}: level from {@code -Dcom.xpdustry.shard.oracle.log} (default {@code info}).
 * <li>{@code mindustry.Vars}: {@code headless = true}, {@code loadLocales = false}, {@code net} (inactive, see
 * {@link #net(NetMode, Supplier)}), all that {@code Vars.init()} creates ({@code content, world, state, maps, mods,
 * asyncCore, pathfinder, ...}), {@code logic}, {@code netServer}, {@code netClient} (for the client-side readers).
 * <li>{@code Building.tmpTiles} and {@code Tile.tileSet} are ordered sets ({@link VanillaOrder}): vanilla collects
 * neighbours in identity-hashed sets, so its proximity order would differ from one JVM to the next.
 * <li>{@code Vars.player} is null, as on a dedicated server (with a player, {@code Logic.update} reaches client-only
 * code). The decoders install {@link #localPlayer()} while they read as a client, see {@link #asClient(Supplier)}.
 * </ul>
 * The data directory is {@code -Dcom.xpdustry.shard.oracle.dataDir}, or a temporary directory deleted on exit.
 */
public final class Vanilla{
    /** The only build this oracle is written against. */
    public static final int build = 160, revision = 7;
    /** Vanilla's server frame: 60 frames per second, Time.delta = 1. */
    public static final float frameSeconds = 1f / 60f;
    /**
     * A wall-clock time (ms) far in the future: a pin that sets one of vanilla's {@code Time.timeSinceMillis} clocks to it
     * keeps that clock not due however slow the frame that reads it is. Pinning to {@code Time.millis()} instead lets a
     * frame slower than the interval (32 ms for the place sound) fire it (W5.2).
     */
    public static final long NOT_DUE = Long.MAX_VALUE / 2;

    private static boolean booted;
    private static OracleApp app;
    private static OracleGraphics graphics;
    private static final Capture capture = new Capture();
    private static Path dataDir;
    private static Player localPlayer;

    /** Which side vanilla's {@code net} pretends to be. Generated packet code reads and writes differently per side. */
    public enum NetMode{
        /** Singleplayer: {@code net.active() == false}. The default. */
        off,
        /** A server with one fake connection; everything it sends is captured (see {@link #captured()}). */
        server,
        /** A client; whatever it sends is captured. */
        client
    }

    /** Boots vanilla once per JVM. Safe to call from every test; later calls do nothing. */
    public static synchronized void boot(){
        if(booted) return;
        long bootStart = System.currentTimeMillis();

        Log.level = LogLevel.valueOf(System.getProperty("com.xpdustry.shard.oracle.log", "info"));
        Log.useColors = false;

        dataDir = dataDirectory();
        graphics = new OracleGraphics();
        app = new OracleApp();
        Core.app = app;
        Core.settings = new Settings();
        Core.files = new MockFiles();
        Core.audio = new MockAudio();
        Core.graphics = graphics;
        Core.input = new MockInput();
        Core.settings.setDataDirectory(new Fi(dataDir.toFile()));

        //ServerLauncher#init, minus the console, mods folder scan and network
        loadLocales = false;
        headless = true;
        net = new Net(capture);
        Vars.loadSettings();
        Vars.init();
        UI.loadColors();
        Fonts.loadContentIconsHeadless();
        content.createBaseContent();
        mods.loadScripts();
        content.createModContent();
        content.init();
        mods.loadModPatches();
        bases.load();

        logic = new Logic();
        netServer = new NetServer();
        netClient = new NetClient();
        localPlayer = Player.create();
        player = null;
        mods.eachClass(Mod::init);
        VanillaOrder.patch();

        Time.setDeltaProvider(() -> Math.min(Core.graphics.getDeltaTime() * 60f, maxDeltaServer));

        if(Version.build != build || Version.revision != revision){
            throw new IllegalStateException("Expected vanilla " + build + "." + revision + " on the classpath, found " + Version.buildString());
        }
        booted = true;
        resetPlayer();
        BootTiming.booted(bootStart, System.currentTimeMillis());
    }

    public static boolean booted(){
        return booted;
    }

    public static Path dataDir(){
        boot();
        return dataDir;
    }

    /**
     * Back to the menu with an empty world, as {@code Logic.reset()} does between games. Also resets the entity id
     * counter, {@code Time.time}, the client's "already seen" ids, the local player and the captured packets, so a test
     * sees the same entity ids and timers whatever ran before it.
     */
    public static void reset(){
        boot();
        setNet(NetMode.off);
        player = null;
        logic.reset();
        app.runPosted();
        Time.setInternalTime(0); //also Arc's timeRaw, which the next Time.update copies back into Time.time
        setStatic(EntityGroup.class, "lastId", 0);
        ((IntSet)get(netClient, "removed")).clear();
        resetPlayer();
        capture.clear();
        Mathf.rand.setSeed(0);
        GlobalVars.rand.setSeed(0);
    }

    private static void resetPlayer(){
        localPlayer.reset();
        localPlayer.id = -1;
        localPlayer.name = "oracle";
        localPlayer.team(Team.sharded);
        localPlayer.color.set(1f, 1f, 1f, 1f);
        localPlayer.admin = false;
    }

    /**
     * The player of the vanilla client the decoders emulate: {@code NetworkIO.loadWorld} reads the stream's player into
     * it, and units whose controller is this player are "local" in {@code readSync}.
     */
    public static Player localPlayer(){
        boot();
        return localPlayer;
    }

    /** Runs {@code action} as a vanilla client would: {@code Vars.player} is {@link #localPlayer()} meanwhile. */
    public static <T> T asClient(Supplier<T> action){
        boot();
        Player previous = player;
        player = localPlayer;
        try{
            return action.get();
        }finally{
            player = previous;
        }
    }

    /** One server frame, in the order HeadlessApplication and ServerLauncher run it. */
    public static void frame(){
        boot();
        app.runPosted();
        graphics.frameId++;
        app.defaultUpdate(); //settings autosave (off) + Time.updateGlobal()
        asyncCore.begin();
        logic.update();
        netServer.update();
        asyncCore.end();
    }

    /** Runs tasks queued with {@code Core.app.post}, without a frame. */
    public static void runPosted(){
        boot();
        app.runPosted();
    }

    /**
     * An empty, coreless {@code width x height} stone world in play, like the stone-world PoC:
     * default rules plus {@code canGameOver = false} (a coreless vanilla server would otherwise end the game).
     */
    public static void emptyWorld(int width, int height){
        boot(); //Blocks.stone is null until content loads
        emptyWorld(width, height, Blocks.stone);
    }

    public static void emptyWorld(int width, int height, Block floor){
        reset();
        state.rules = new Rules();
        state.rules.canGameOver = false;
        state.map = new Map(StringMap.of("name", "oracle"));
        world.loadGenerator(width, height, tiles -> {
            for(int y = 0; y < height; y++){
                for(int x = 0; x < width; x++){
                    tiles.set(x, y, new Tile(x, y, floor, Blocks.air, Blocks.air));
                }
            }
        });
        logic.play();
        app.runPosted();
    }

    /** Hosts a map exactly as the server's {@code host <map> survival} command does. */
    public static Map host(String map){
        return host(map, Gamemode.survival);
    }

    /**
     * Hosts a map as the server's {@code host} command does: reset, {@code world.loadMap}, rules from the map and the
     * mode, {@code logic.play()}. See {@link #findMap(String)} for the accepted names.
     */
    public static Map host(String name, Gamemode mode){
        reset();
        Map map = findMap(name);
        world.loadMap(map, map.applyRules(mode));
        state.rules = map.applyRules(mode);
        Events.fire(new RulesLoadEvent(state.rules));
        logic.play();
        app.runPosted();
        return map;
    }

    /**
     * Finds a map by: the file name of a built-in map ({@code "fortress"}), its display name ({@code "Ancient Caldera"},
     * case and {@code _} insensitive), an internal path ({@code "serpulo/groundZero"}, {@code "maps/serpulo/frontier.msav"}),
     * or a path to an {@code .msav} file.
     */
    public static Map findMap(String name){
        boot();
        String plain = name.replace('_', ' ');
        Map map = maps.all().find(m -> m.file.nameWithoutExtension().equalsIgnoreCase(name) || m.plainName().replace('_', ' ').equalsIgnoreCase(plain));
        if(map != null) return map;

        String internal = name.startsWith("maps/") ? name.substring(5) : name;
        if(internal.endsWith("." + mapExtension)) internal = internal.substring(0, internal.length() - mapExtension.length() - 1);
        if(Core.files.internal("maps/" + internal + "." + mapExtension).exists()){
            return maps.loadInternalMap(internal);
        }

        Fi file = new Fi(name);
        if(file.exists()){
            try{
                return MapIO.createMap(file, true);
            }catch(IOException e){
                throw new UncheckedIOException(e);
            }
        }
        throw new IllegalArgumentException("No map named '" + name + "'. Built-in: " + maps.all().map(m -> m.file.nameWithoutExtension()));
    }

    /** The fake connection that stands for "a client" in {@link NetMode#server}; pass it to {@code Call.x(con, ...)}. */
    public static NetConnection connection(){
        boot();
        return capture.connection;
    }

    /** What vanilla sent while {@code net} was in server or client mode, oldest first. */
    public static List<Capture.Sent> captured(){
        return capture.sent;
    }

    /** Runs {@code action} with vanilla's {@code net} in the given mode, then restores the previous mode. */
    public static <T> T net(NetMode mode, Supplier<T> action){
        boot();
        NetMode previous = netMode();
        setNet(mode);
        try{
            return action.get();
        }finally{
            setNet(previous);
        }
    }

    public static void net(NetMode mode, Runnable action){
        net(mode, () -> {
            action.run();
            return null;
        });
    }

    public static NetMode netMode(){
        return !net.active() ? NetMode.off : net.server() ? NetMode.server : NetMode.client;
    }

    /**
     * Writes the two fields in an order where no moment between them reads as a client ({@code active && !server})
     * unless client is the target: vanilla's control-pathfinder thread exits for good when it sees a client
     * ({@code ControlPathfinder.java:1553}), so a harness that keeps vanilla's threads running lost its pathfinder (W4.10).
     */
    private static void setNet(NetMode mode){
        if(mode == NetMode.server){
            set(net, "server", true);
            set(net, "active", true);
        }else{
            set(net, "active", mode != NetMode.off);
            set(net, "server", false);
        }
    }

    /** Runs {@code action} and returns what vanilla logged at warn level or above meanwhile (also passed on to the log). */
    public static List<String> logged(Runnable action){
        boot();
        List<String> lines = new ArrayList<>();
        LogHandler previous = Log.logger;
        Log.logger = (level, text) -> {
            if(level.ordinal() >= LogLevel.warn.ordinal()) lines.add(level + ": " + text);
            previous.log(level, text);
        };
        try{
            action.run();
        }finally{
            Log.logger = previous;
        }
        return lines;
    }

    private static Path dataDirectory(){
        try{
            String configured = System.getProperty("com.xpdustry.shard.oracle.dataDir");
            if(configured != null) return java.nio.file.Files.createDirectories(Path.of(configured));
            Path temp = java.nio.file.Files.createTempDirectory("shard-oracle-");
            Runtime.getRuntime().addShutdownHook(new Thread(() -> new Fi(temp.toFile()).deleteDirectory()));
            return temp;
        }catch(IOException e){
            throw new UncheckedIOException(e);
        }
    }

    static Object get(Object target, String field){
        try{
            Field f = target.getClass().getDeclaredField(field);
            f.setAccessible(true);
            return f.get(target);
        }catch(ReflectiveOperationException e){
            throw new IllegalStateException(e);
        }
    }

    private static void set(Object target, String field, Object value){
        try{
            Field f = target.getClass().getDeclaredField(field);
            f.setAccessible(true);
            f.set(target, value);
        }catch(ReflectiveOperationException e){
            throw new IllegalStateException(e);
        }
    }

    private static void setStatic(Class<?> type, String field, Object value){
        try{
            Field f = type.getDeclaredField(field);
            f.setAccessible(true);
            f.set(null, value);
        }catch(ReflectiveOperationException e){
            throw new IllegalStateException(e);
        }
    }

    /** Headless application without a loop thread: there is no "main thread", so every thread counts as main. */
    static final class OracleApp implements Application{
        private final Seq<ApplicationListener> listeners = new Seq<>();
        private final TaskQueue posted = new TaskQueue();

        void runPosted(){
            posted.run();
        }

        @Override
        public Seq<ApplicationListener> getListeners(){
            return listeners;
        }

        @Override
        public ApplicationType getType(){
            return ApplicationType.headless;
        }

        @Override
        public String getClipboardText(){
            return null;
        }

        @Override
        public void setClipboardText(String text){}

        @Override
        public void post(Runnable runnable){
            posted.post(runnable);
        }

        @Override
        public void exit(){}
    }

    /** Mock graphics where every frame lasts exactly 1/60 s, so Time.delta and state.tick advance by 1 per frame. */
    static final class OracleGraphics extends MockGraphics{
        long frameId;

        @Override
        public float getDeltaTime(){
            return frameSeconds;
        }

        @Override
        public int getFramesPerSecond(){
            return 60;
        }

        @Override
        public long getFrameId(){
            return frameId;
        }
    }

    /** A {@code NetProvider} with no sockets: one fake client connection, and a record of everything sent. */
    public static final class Capture implements Net.NetProvider{
        /** One packet vanilla sent. {@code frame} is serialized while the net mode that sent it is still active. */
        public record Sent(Object packet, boolean reliable, String target, Wire.Frame frame){}

        final List<Sent> sent = new ArrayList<>();
        final NetConnection connection = new NetConnection("127.0.0.1"){
            @Override
            public void send(Object object, boolean reliable){
                record(object, reliable, "connection");
            }

            @Override
            public void close(){}
        };

        void clear(){
            sent.clear();
        }

        void record(Object object, boolean reliable, String target){
            sent.add(new Sent(object, reliable, target, object instanceof Packet packet ? Wire.frame(packet) : null));
        }

        @Override
        public void sendAllServer(Object object, boolean reliable){
            record(object, reliable, "all");
        }

        @Override
        public void sendExceptServer(NetConnection except, Object object, boolean reliable){
            record(object, reliable, "except");
        }

        @Override
        public void connectClient(String ip, int port, Runnable success){
            throw new UnsupportedOperationException("the oracle has no sockets");
        }

        @Override
        public void sendClient(Object object, boolean reliable){
            record(object, reliable, "server");
        }

        @Override
        public void disconnectClient(){}

        @Override
        public void discoverServers(Cons<Host> callback, Runnable done){
            done.run();
        }

        @Override
        public void pingHost(String address, int port, Cons<Host> valid, Cons<Exception> failed){
            failed.get(new IOException("the oracle has no sockets"));
        }

        @Override
        public void hostServer(int port){
            throw new UnsupportedOperationException("the oracle has no sockets");
        }

        @Override
        public Iterable<? extends NetConnection> getConnections(){
            return List.of(connection);
        }

        @Override
        public void closeServer(){}
    }

    private Vanilla(){}
}
