package com.xpdustry.shard.server.world;

import com.xpdustry.shard.content.*;
import com.xpdustry.shard.server.host.*;
import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.world.*;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * {@code shard replay <bundle> [--until T] [--hash-every N] [--force]}: re-runs a crash bundle on this build and
 * reports whether the hashes, verdicts and error come back (ARCHITECTURE.md §4 "Crash bundle"). The content epoch
 * comes from a registered {@link ContentSource}; the systems are the build's own, a hosted world's
 * ({@link HostedSystems}) or a plain one's, whichever the checkpoint was written with. Exit status: 0 when the replay
 * matches, 1 when it diverges, 2 for bad arguments, an unreadable bundle or one this build can't rebuild a world from
 * (its content's kinds aren't this build's, as with {@code --force} on other systems).
 */
public final class ReplayCommand{
    public static final String USAGE = """
        Usage: shard replay <bundle.zip> [--until <tick>] [--hash-every <n>] [--force]

          --until <tick>      stop once the world reaches this tick
          --hash-every <n>    print the world hash every n ticks
          --force             replay a bundle from another build or other systems (hashes may diverge)
        """;

    public static int run(List<String> args, PrintStream out, PrintStream err){
        Path file = null;
        long until = Long.MAX_VALUE;
        int hashEvery = 0;
        boolean force = false;
        try{
            for(int i = 0; i < args.size(); i++){
                String arg = args.get(i);
                switch(arg){
                    case "--until" -> until = Long.parseLong(value(args, ++i, arg));
                    case "--hash-every" -> hashEvery = Integer.parseInt(value(args, ++i, arg));
                    case "--force" -> force = true;
                    case "--help" -> {
                        out.print(USAGE);
                        return 0;
                    }
                    default -> {
                        if(arg.startsWith("--") || file != null) throw new IllegalArgumentException("unexpected " + arg);
                        file = Path.of(arg);
                    }
                }
            }
            if(file == null) throw new IllegalArgumentException("no bundle given");
            if(hashEvery < 0) throw new IllegalArgumentException("--hash-every must be at least 0");
        }catch(IllegalArgumentException e){
            err.println("shard replay: " + e.getMessage());
            err.print(USAGE);
            return 2;
        }
        CrashBundle bundle;
        WorldConfig config;
        try{
            bundle = CrashBundle.decode(Files.readAllBytes(file));
            ContentEpoch content = ContentSource.resolve(Checkpoint.header(bundle.checkpoint()).content());
            WorldConfig stored = Checkpoint.decode(bundle.checkpoint()).config(content);
            // a hosted world runs the server's roster too: pick the systems the checkpoint was written with
            WorldConfig hosted = HostedSystems.matching(stored, Checkpoint.header(bundle.checkpoint()).systems());
            config = hosted != null ? hosted : stored;
        }catch(IOException | CheckpointException e){
            err.println("shard replay: can't read " + file + ": " + e.getMessage());
            return 2;
        }
        try{
            Replay.Result result = Replay.run(bundle, config, new Replay.Options(until, hashEvery, force), out::println);
            if(result.ok()){
                out.println(result.reproduced() ? "replay reproduced the failure at tick " + result.to() : "replay matched through tick " + result.to());
                return 0;
            }
            out.println("replay diverged: " + result.divergence());
            return 1;
        }catch(CheckpointException e){
            err.println("shard replay: " + e.getMessage());
            return 2;
        }catch(IllegalStateException e){
            // Replay.run catches what the ticks throw: this came from building the world (World's requireSupported)
            err.println("shard replay: can't rebuild the world of " + file + " on this build: " + e.getMessage());
            return 2;
        }
    }

    private static String value(List<String> args, int index, String flag){
        if(index >= args.size()) throw new IllegalArgumentException(flag + " needs a value");
        return args.get(index);
    }

    private ReplayCommand(){}
}
