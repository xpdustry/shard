package com.xpdustry.shard.tools.fuzz;

import com.xpdustry.shard.content.epoch.*;
import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.world.*;

import java.nio.file.*;

/**
 * Prints a world's crash bundle: its error, then every command in its log with its tick and verdict, optionally only
 * those whose text contains a filter. {@code shard-fuzz}'s reports name the bundle of a failed world; this finds the
 * input that failed it.
 *
 * <pre>./gradlew :tools:fuzz:crashLog -Pargs="path/to/crash-300-0.zip [filter]"</pre>
 */
public final class CrashLog{
    public static void main(String[] args) throws Exception{
        CrashBundle bundle = CrashBundle.decode(Files.readAllBytes(Path.of(args[0])), Epoch.vanilla());
        String filter = args.length > 1 ? args[1] : null;
        System.out.println(bundle.errorSummary());
        System.out.println(bundle.entries().size() + " commands from tick " + bundle.logStart());
        for(CommandLog.Entry entry : bundle.entries()){
            String line = entry.tick() + " " + entry.verdict() + " " + entry.input();
            if(filter == null || line.contains(filter)) System.out.println(line.length() > 400 ? line.substring(0, 400) + "..." : line);
        }
    }

    private CrashLog(){
    }
}
