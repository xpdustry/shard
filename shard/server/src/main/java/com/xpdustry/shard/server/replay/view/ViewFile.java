package com.xpdustry.shard.server.replay.view;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * A whole view stream: its header, terrain chunk and segments (deflated), as one {@code .sview} file or split into
 * {@code header.json}, {@code terrain} and {@code segments/<i>} for static hosting ({@code docs/replay/FORMAT.md}, "Serving
 * it in pieces").
 *
 * <pre>
 * "SVIEW" u8 format
 * u32 headerLength  header: UTF-8 JSON
 * u32 terrainLength terrain: deflate-raw(Terrain)
 * segment*          u32 length, deflate-raw(Segment)
 * </pre>
 */
public record ViewFile(ViewHeader header, byte[] terrain, List<byte[]> segments){
    public ViewFile{
        segments = List.copyOf(segments);
        if(header.segments().size() != segments.size()){
            throw new IllegalArgumentException("the header lists " + header.segments().size() + " segments, not " + segments.size());
        }
    }

    /**
     * A file of {@code header}'s stream: the index gets each segment's offset and length, and {@code hashes} (one per
     * segment, of its inflated bytes) and frame counts are kept from {@code header}'s index.
     */
    public static ViewFile of(ViewHeader header, byte[] terrain, List<byte[]> segments){
        var index = new ArrayList<ViewHeader.Index>(segments.size());
        long offset = 0;
        for(int i = 0; i < segments.size(); i++){
            ViewHeader.Index at = header.segments().get(i);
            index.add(new ViewHeader.Index(offset, segments.get(i).length, at.tick(), at.frames(), at.hash()));
            offset += 4 + segments.get(i).length;
        }
        return new ViewFile(header.withSegments(index, terrain.length).withEnd(header.start() + header.ticks(), header.complete(), header.winner()), terrain, segments);
    }

    public byte[] encode(){
        byte[] json = header.json();
        long total = 10 + json.length + 4 + terrain.length;
        for(byte[] segment : segments) total += 4 + segment.length;
        var out = new Bytes((int)Math.min(Integer.MAX_VALUE - 16, total));
        out.bytes(ViewWriter.MAGIC).u8(ViewWriter.FORMAT).u32(json.length).bytes(json).u32(terrain.length).bytes(terrain);
        for(byte[] segment : segments) out.u32(segment.length).bytes(segment);
        return out.toArray();
    }

    /** Reads a format 2 file's pieces; segments stay deflated. */
    public static ViewFile decode(byte[] file){
        var in = new Bytes.In(file);
        byte[] magic = in.bytes(ViewWriter.MAGIC.length);
        if(!Arrays.equals(magic, ViewWriter.MAGIC)) throw new IllegalArgumentException("not a view stream");
        int format = in.u8();
        if(format != ViewWriter.FORMAT) throw new IllegalArgumentException("view stream format " + format + ", not " + ViewWriter.FORMAT);
        ViewHeader header = ViewHeader.parse(in.bytes(in.length()));
        byte[] terrain = in.bytes(in.length());
        var segments = new ArrayList<byte[]>();
        while(in.more()) segments.add(in.bytes(in.length()));
        return new ViewFile(header, terrain, segments);
    }

    /** Writes the pieces to {@code dir}: {@code header.json}, {@code terrain} and {@code segments/<i>}. */
    public void split(Path dir) throws IOException{
        Files.createDirectories(dir.resolve("segments"));
        for(int i = 0; i < segments.size(); i++) write(dir.resolve("segments").resolve(Integer.toString(i)), segments.get(i));
        write(dir.resolve("terrain"), terrain);
        // the header last: a reader that sees it finds every piece it lists
        write(dir.resolve("header.json"), header.json());
    }

    /** Writes {@code bytes} to a temporary file beside {@code file}, then moves it into place. */
    static void write(Path file, byte[] bytes) throws IOException{
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        Files.write(temporary, bytes);
        Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}
