package com.xpdustry.shard.tools.replay.view;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * Prints a view stream as {@link ViewReader} decodes it, one JSON line per frame, so another decoder (the browser
 * viewer's) can be compared with the reference: {@code dumpView -Pargs="file.sview [every]"}. A line holds the tick,
 * the wave, the building count and a checksum of the buildings, every unit as {@code [id, type, team, x, y, rotation,
 * health, elevation, flags]} and every bullet as {@code [id, style, team, x, y, rotation, age]}, quantized as stored.
 */
public final class DumpView{
    public static void main(String[] args) throws IOException{
        var reader = new ViewReader(Files.readAllBytes(Path.of(args[0])));
        int every = args.length > 1 ? Integer.parseInt(args[1]) : 1;
        var out = new PrintStream(new BufferedOutputStream(System.out), false);
        int[] frame = {0};
        reader.playAll(state -> {
            if(frame[0]++ % every != 0) return;
            out.print(line(state));
            out.print('\n');
        });
        out.flush();
    }

    static String line(ViewReader.State state){
        var out = new StringBuilder(256);
        long builds = 0;
        for(var entry : state.builds.entrySet()){
            var b = entry.getValue();
            builds = builds * 31 + entry.getKey();
            builds = builds * 31 + b.block() * 7L + b.team() * 3L + b.rotation() + b.health() * 11L + b.flags() * 13L + b.aim() * 17L + b.progress();
        }
        out.append("{\"tick\":").append(state.tick).append(",\"wave\":").append(state.wave)
            .append(",\"builds\":").append(state.builds.size()).append(",\"buildSum\":").append(Long.toString(builds & 0xFFFFFFFFL))
            .append(",\"events\":").append(state.events.size()).append(",\"units\":[");
        boolean first = true;
        for(var entry : state.units.entrySet()){
            var u = entry.getValue();
            if(!first) out.append(',');
            first = false;
            out.append('[').append(entry.getKey()).append(',').append(u.type).append(',').append(u.team).append(',').append(u.x).append(',').append(u.y)
                .append(',').append(u.rotation).append(',').append(u.health).append(',').append(u.elevation).append(',').append(u.flags).append(']');
        }
        out.append("],\"bullets\":[");
        first = true;
        for(var entry : state.bullets.entrySet()){
            var b = entry.getValue();
            if(!first) out.append(',');
            first = false;
            out.append('[').append(entry.getKey()).append(',').append(b.type).append(',').append(b.team).append(',').append(b.x).append(',').append(b.y)
                .append(',').append(b.rotation).append(',').append(b.age).append(']');
        }
        return out.append("]}").toString();
    }
}
