package com.xpdustry.shard.server;

import com.xpdustry.shard.server.config.*;
import com.xpdustry.shard.server.console.Console;
import com.xpdustry.shard.server.ops.*;
import com.xpdustry.shard.server.replay.*;
import com.xpdustry.shard.server.world.*;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * The {@code shard} command: starts a {@link Server} from a config directory and runs its stdin console until
 * {@code exit}, end of input or a signal; {@code replay} re-runs a crash bundle.
 */
public final class Main{
    static final String USAGE = """
        Usage: shard [--config <dir>] [--quiet] [--no-console]
               shard replay <bundle.zip> [--until <tick>] [--hash-every <n>] [--force]
               shard replay list|verify|export|bake ...
               shard --help

        Shard, a Mindustry v160.7 server. It reads <dir>/shard.yml and <dir>/worlds/<id>.yml (default: the working
        directory) and SHARD__ environment variables, hosts host.worlds, and reads console commands from stdin
        (type help).

          --config <dir>   the config directory
          --quiet          no per-player log lines
          --no-console     don't read stdin; run until a signal
          replay           re-run a world's crash bundle on this build (see shard replay --help), or list, verify,
                           export and bake recorded matches (see shard replay list --help)
        """;

    public static void main(String[] args){
        int status = run(args, System.out, System.err);
        if(status >= 0){
            Logging.stop();
            System.exit(status);
        }
    }

    /**
     * Runs the command with explicit streams.
     *
     * @return the exit status, or -1 when a server was started and keeps running on its own threads
     */
    static int run(String[] args, PrintStream out, PrintStream err){
        if(args.length >= 1 && args[0].equals("replay")){
            List<String> rest = Arrays.asList(args).subList(1, args.length);
            return ReplayTool.handles(rest) ? ReplayTool.run(rest, out, err) : ReplayCommand.run(rest, out, err);
        }
        Path config = Path.of(".");
        boolean quiet = false, console = true;
        for(int i = 0; i < args.length; i++){
            switch(args[i]){
                case "--help" -> {
                    if(args.length != 1) return usage(err, "--help takes nothing else");
                    out.print(USAGE);
                    return 0;
                }
                case "--config" -> {
                    if(i + 1 >= args.length) return usage(err, "--config needs a directory");
                    config = Path.of(args[++i]);
                }
                case "--quiet" -> quiet = true;
                case "--no-console" -> console = false;
                default -> {
                    return usage(err, "unknown argument " + args[i]);
                }
            }
        }
        if(!Files.isDirectory(config)) return usage(err, "no config directory " + config);
        Server server;
        try{
            server = Server.start(new Server.Options(config, System.getenv(), quiet));
        }catch(ConfigException e){
            err.println("shard: the config is invalid:\n" + e.getMessage());
            return 2;
        }catch(IOException | RuntimeException e){
            err.println("shard: can't start: " + Server.cause(e));
            e.printStackTrace(err);
            return 1;
        }
        var stopped = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(Thread.ofPlatform().name("shutdown").unstarted(() -> {
            server.close();
            Logging.stop();
            stopped.countDown();
        }));
        var shell = new Console(server, out, () -> {
            stopped.countDown();
            System.exit(0);
        });
        for(String command : server.config().current().get(ConfigKeys.CONSOLE_START_COMMANDS)) shell.run(command);
        if(console){
            Thread.ofPlatform().name("console").daemon().start(() -> {
                shell.read(System.in);
                // stdin ended: a detached server keeps running until a signal
                out.println("console: stdin closed; stop with a signal");
            });
        }
        return -1;
    }

    private static int usage(PrintStream err, String problem){
        err.println("shard: " + problem);
        err.print(USAGE);
        return 2;
    }

    private Main(){}
}
