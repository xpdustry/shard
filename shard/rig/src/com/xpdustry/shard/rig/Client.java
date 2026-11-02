package com.xpdustry.shard.rig;

import java.io.*;
import java.net.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.util.*;

/**
 * One vanilla client: its Xvfb display, the client JVM running {@link RigClient}, and the {@link RigInput} process
 * that types and clicks on that display. Files live in {@code <run>/<name>/}: {@code stdout.log}, {@code input.log},
 * {@code shots/}, and {@code data/Mindustry/} (settings, {@code last_log.txt}, crashes).
 *
 * <p>Xvfb and the client run at {@link #NICE}: two software-rendered clients kept a 4-vCPU VM at 90% CPU, and the
 * server under test, sharing it, missed 22 ticks while one client went back to its menu and rejoined.
 */
public final class Client{
    /** The niceness of Xvfb and the client JVM, below the server's 0. */
    static final String NICE = "10";

    public final String name;
    public final int index;
    public final Path dir;
    public final String display;
    public final LogFile lastLog, stdout;
    final int controlPort;
    Process xvfb, game, input;
    BufferedReader inputOut;
    PrintWriter inputIn;
    Socket control;
    BufferedReader controlIn;
    PrintWriter controlOut;
    /** Window pixel to screen pixel; measured once the window is up. */
    int offsetX, offsetY;
    boolean calibrated, joined;
    float markX, markY;
    int shots;
    int baselineUnexpected;
    final List<Map<String, Object>> screenshots = new ArrayList<>();

    Client(String name, int index, Path runDir){
        this.name = name;
        this.index = index;
        this.dir = runDir.resolve(name);
        this.display = ":" + (101 + index);
        this.controlPort = 17101 + index;
        this.lastLog = new LogFile(dir.resolve("data/Mindustry/last_log.txt"));
        this.stdout = new LogFile(dir.resolve("stdout.log"));
    }

    void start(Rig.Env env) throws IOException, InterruptedException{
        Files.createDirectories(dir.resolve("home"));
        Files.createDirectories(dir.resolve("data"));
        Files.createDirectories(dir.resolve("shots"));
        xvfb = new ProcessBuilder(Rig.Env.pinned(env.clientCpus(), "nice", "-n", NICE, "Xvfb", display, "-screen", "0", env.width() + "x" + env.height() + "x24",
            "-nolisten", "tcp"))
            .redirectErrorStream(true).redirectOutput(dir.resolve("xvfb.log").toFile()).start();
        var socket = Path.of("/tmp/.X11-unix/X" + display.substring(1));
        for(int i = 0; i < 40 && !Files.exists(socket); i++) Thread.sleep(100);

        var game = new ProcessBuilder(Rig.Env.pinned(env.clientCpus(), "nice", "-n", NICE, env.java(), "-Duser.home=" + dir.resolve("home"),
            "--enable-native-access=ALL-UNNAMED", "-cp", env.jar() + ":" + env.classes(), RigClient.class.getName(), name, String.valueOf(controlPort),
            String.valueOf(env.width()), String.valueOf(env.height())))
            .redirectErrorStream(true).redirectOutput(dir.resolve("stdout.log").toFile());
        game.environment().put("DISPLAY", display);
        game.environment().put("HOME", dir.resolve("home").toString());
        game.environment().put("XDG_DATA_HOME", dir.resolve("data").toString());
        this.game = game.start();

        var input = new ProcessBuilder(Rig.Env.pinned(env.clientCpus(), env.java(), "-cp", env.classes(), RigInput.class.getName()))
            .redirectError(dir.resolve("input.log").toFile());
        input.environment().put("DISPLAY", display);
        this.input = input.start();
        inputIn = new PrintWriter(new OutputStreamWriter(this.input.getOutputStream(), StandardCharsets.UTF_8), true);
        inputOut = new BufferedReader(new InputStreamReader(this.input.getInputStream(), StandardCharsets.UTF_8));
        String ready = inputOut.readLine();
        if(!"ok ready".equals(ready)) throw new RigException(name + ": input helper did not start: " + ready + " (see " + name + "/input.log)");
    }

    /** One {@link RigInput} command; an {@code err} reply fails the step. */
    String input(String command){
        if(!input.isAlive()) throw new RigException(name + ": input helper exited");
        inputIn.println(command);
        try{
            String reply = inputOut.readLine();
            if(reply == null || !reply.startsWith("ok")) throw new RigException(name + ": input '" + command + "': " + reply);
            return reply;
        }catch(IOException e){
            throw new RigException(name + ": input helper: " + e);
        }
    }

    /** One {@link RigClient} request, as key/value pairs. */
    Map<String, String> ask(String command){
        if(!game.isAlive()) throw new RigException(name + ": the client process exited (code " + game.exitValue() + ")");
        try{
            if(control == null){
                control = new Socket(InetAddress.getLoopbackAddress(), controlPort);
                control.setSoTimeout(15_000);
                controlIn = new BufferedReader(new InputStreamReader(control.getInputStream(), StandardCharsets.UTF_8));
                controlOut = new PrintWriter(new OutputStreamWriter(control.getOutputStream(), StandardCharsets.UTF_8), true);
            }
            controlOut.println(command);
            String reply = controlIn.readLine();
            if(reply == null) throw new IOException("closed");
            if(!reply.startsWith("ok")) throw new RigException(name + ": " + command.split(" ")[0] + ": " + reply.substring(Math.min(4, reply.length())));
            var map = new LinkedHashMap<String, String>();
            for(String pair : reply.split("\t")){
                int eq = pair.indexOf('=');
                if(eq > 0) map.put(pair.substring(0, eq), pair.substring(eq + 1));
            }
            return map;
        }catch(IOException e){
            closeControl();
            throw new RigException(name + ": control socket: " + e.getMessage());
        }
    }

    /** Like {@link #ask}, but null while the control socket isn't up yet (the client JVM is still starting). */
    Map<String, String> tryAsk(String command){
        try{
            return ask(command);
        }catch(RigException e){
            if(!game.isAlive()) throw e;
            return null;
        }
    }

    void closeControl(){
        try{
            if(control != null) control.close();
        }catch(IOException ignored){
        }
        control = null;
    }

    boolean alive(){
        return game != null && game.isAlive();
    }

    void stop(){
        closeControl();
        for(var p : new Process[]{input, game, xvfb}){
            if(p == null) continue;
            p.descendants().forEach(ProcessHandle::destroy);
            p.destroy();
        }
    }
}
