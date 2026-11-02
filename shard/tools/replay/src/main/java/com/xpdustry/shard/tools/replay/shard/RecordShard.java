package com.xpdustry.shard.tools.replay.shard;

import com.xpdustry.shard.tools.replay.view.*;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * Plays a scripted {@link ShardMatch} on Shard's own sim and writes its view stream: the Shard half of the view
 * model, in the same format as {@code RecordVanilla}.
 *
 * <pre>--map veins --minutes 2 --out ../replay/recordings/veins-shard.sview --frame-every 3</pre>
 */
public final class RecordShard{
    public static void main(String[] args) throws IOException{
        var options = new HashMap<String, String>();
        for(int i = 0; i + 1 < args.length; i += 2) options.put(args[i].substring(2), args[i + 1]);
        String map = options.getOrDefault("map", "veins");
        long ticks = Math.round(Double.parseDouble(options.getOrDefault("minutes", "2")) * 3600);
        Path out = Path.of(options.getOrDefault("out", "../replay/recordings/" + map + "-shard.sview"));

        var match = new ShardMatch(map);
        var writer = new ViewWriter(new ShardSource(match.world, map), new ViewWriter.Options(Integer.parseInt(options.getOrDefault("frame-every", "3")), 600, 6));
        System.out.println("recording " + map + " (" + match.world.tiles().width() + "x" + match.world.tiles().height() + ") on Shard, cores " + match.cores);

        long began = System.nanoTime();
        writer.sample();
        for(long tick = 0; tick < ticks; tick++){
            match.inputs();
            match.world.tick();
            writer.sample();
        }
        double seconds = (System.nanoTime() - began) / 1e9;
        byte[] file = writer.finish();
        Files.createDirectories(out.toAbsolutePath().getParent());
        Files.write(out, file);
        System.out.printf("wrote %s: %,d bytes for %d ticks, %.3f ms per tick (with recording)%n", out, file.length, writer.ticks(), seconds * 1000 / ticks);
        System.out.print(writer.stats().summary(writer.ticks()));
    }
}
