package com.xpdustry.shard.tools.replay.view;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * Splits a view stream into the pieces a lazy viewer fetches (docs/replay/FORMAT.md "Serving it in pieces"):
 * {@code header.json}, {@code terrain} and {@code segments/<i>}, so a static host serves it segment by segment:
 * {@code splitView -Pargs="file.sview outDir"}.
 */
public final class SplitView{
    public static void main(String[] args) throws IOException{
        byte[] file = Files.readAllBytes(Path.of(args[0]));
        Path out = Path.of(args[1]);
        Files.createDirectories(out.resolve("segments"));
        var in = new Bytes.In(file);
        if(!Arrays.equals(in.bytes(ViewWriter.MAGIC.length), ViewWriter.MAGIC) || in.u8() != ViewWriter.FORMAT) throw new IOException(args[0] + " is not a format " + ViewWriter.FORMAT + " view stream");
        Files.write(out.resolve("header.json"), in.bytes(in.u32()));
        Files.write(out.resolve("terrain"), in.bytes(in.u32()));
        int count = 0;
        while(in.more()) Files.write(out.resolve("segments").resolve(Integer.toString(count++)), in.bytes(in.u32()));
        System.out.println("split " + args[0] + " into " + out + ": header, terrain, " + count + " segments");
    }
}
