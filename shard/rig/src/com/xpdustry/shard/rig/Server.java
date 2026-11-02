package com.xpdustry.shard.rig;

import java.io.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.util.concurrent.*;

/**
 * The server under test: any shell command, run in {@code <run>/server/} with its stdin as the console and its
 * output in {@code server/server.log}. An exit the script didn't ask for fails the run.
 */
public final class Server{
    public final Path dir;
    public final LogFile log;
    final String command;
    final Rig.Env env;
    Process process;
    PrintWriter console;
    /** The script stopped it on purpose ({@code server stop}, {@code server kill expected}); the rig itself stopping it at the end doesn't count. */
    boolean stoppedByScript;
    /** An unexpected exit was already reported as a failed step. */
    boolean crashReported;
    int starts;

    Server(String command, Path runDir, Rig.Env env){
        this.command = command;
        this.env = env;
        this.dir = runDir.resolve("server");
        this.log = new LogFile(dir.resolve("server.log"));
    }

    void start() throws IOException{
        if(alive()) throw new RigException("the server is already running");
        Files.createDirectories(dir);
        var pb = new ProcessBuilder(Rig.Env.pinned(env.serverCpus(), "bash", "-c", command)).directory(dir.toFile()).redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.appendTo(dir.resolve("server.log").toFile()));
        pb.environment().putAll(env.vars());
        process = pb.start();
        console = new PrintWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8), true);
        stoppedByScript = false;
        crashReported = false;
        starts++;
    }

    void console(String line){
        if(!alive()) throw new RigException("the server is not running, cannot send '" + line + "'");
        console.println(line);
        if(console.checkError()) throw new RigException("the server console is closed");
    }

    boolean alive(){
        return process != null && process.isAlive();
    }

    /** Exit code, or null while it runs or before it started. */
    Integer exit(){
        return process == null || process.isAlive() ? null : process.exitValue();
    }

    long pid(){
        return process == null ? -1 : process.pid();
    }

    /** SIGTERM to the whole tree, then SIGKILL after {@code graceMillis}. */
    void stop(long graceMillis) throws InterruptedException{
        if(process == null) return;
        var tree = process.descendants().toList();
        tree.forEach(ProcessHandle::destroy);
        process.destroy();
        try{
            process.onExit().get(graceMillis, TimeUnit.MILLISECONDS);
        }catch(ExecutionException | TimeoutException e){
            kill();
        }
        for(var p : tree) p.onExit().completeOnTimeout(p, 2, TimeUnit.SECONDS).join();
    }

    void kill(){
        if(process == null) return;
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
        process.onExit().join();
    }
}
