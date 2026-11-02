package com.xpdustry.shard.logic;

import java.io.*;
import java.nio.charset.*;
import java.util.*;
import java.util.zip.*;

/**
 * The processor config format, {@code LogicBlock.compress} and the parsing half of {@code readCompressed}: deflate of
 * {@code [byte version 1][int length][UTF-8 code][int count]{[UTF name][short x][short y]}}. Configs carry links
 * relative to the processor; saves carry them absolute.
 *
 * <p>Bounds vanilla lacks (review 39): vanilla limits the code to {@link #maxByteLen} and the links to
 * {@link Processor#maxLinks}, but reads each name with {@code readUTF}, so 40 kB of deflate can expand to hundreds of
 * megabytes of names. Shard reads at most {@link #MAX_INFLATED} bytes of a config and names of at most
 * {@link #MAX_NAME_BYTES}, and refuses anything past them whole, before it keeps or applies any of it.
 */
public final class LogicConfig{
    /** {@code LogicBlock.maxByteLen}. */
    public static final int maxByteLen = 1024 * 100;
    /**
     * The most bytes one config may inflate to. The largest config a stock client writes is 239,302: the most code
     * ({@link #maxByteLen}) and {@link Processor#maxLinks} links to blocks with vanilla's longest link name,
     * {@code reconstructor1} to {@code reconstructor6000} ({@code findLinkName}); the largest of the 718 in the bundled
     * maps is 15,669 ({@code hidden/20}).
     */
    public static final int MAX_INFLATED = 256 * 1024;
    /**
     * The longest link name, in modified UTF-8 bytes: vanilla names links after their block ({@code findLinkName}),
     * at most 17 bytes, and 15 in the bundled maps; this leaves room for longer block names.
     */
    public static final int MAX_NAME_BYTES = 64;

    private LogicConfig(){
    }

    /** A link as the format stores it. */
    public record Link(String name, int x, int y){
    }

    /**
     * What {@code readCompressed} got from the data before it stopped. {@code code} is null when it failed while
     * reading the links: vanilla has replaced the links by then, but doesn't update the code.
     */
    public record Decoded(String code, List<Link> links){
    }

    /** {@code LogicBlock.compress(code, links)}, byte for byte. */
    public static byte[] compress(String code, List<Link> links){
        try{
            var bytes = new ByteArrayOutputStream();
            var stream = new DataOutputStream(new DeflaterOutputStream(bytes));
            byte[] text = code.getBytes(StandardCharsets.UTF_8);
            stream.write(1);
            stream.writeInt(text.length);
            stream.write(text);
            stream.writeInt(links.size());
            for(Link link : links){
                stream.writeUTF(link.name());
                stream.writeShort(link.x());
                stream.writeShort(link.y());
            }
            stream.close();
            return bytes.toByteArray();
        }catch(IOException e){
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Parses data as {@code readCompressed} does. @return null if it fails before the links (vanilla changes nothing)
     * or goes past Shard's bounds (refused whole), else the code (null if the links failed) and the links read.
     */
    public static Decoded decode(byte[] data){
        if(data == null) return null;
        Parse parse = parse(data, MAX_INFLATED, true);
        return parse.refused ? null : parse.decoded;
    }

    /**
     * How many bytes {@link #decode} inflates {@code data} to, read at most up to {@code limit} and keeping nothing.
     * The decision is decode's: data it refuses, or that needs more than {@code limit} bytes, is refused here.
     *
     * @return the bytes inflated, or -1 if refused
     */
    public static int measure(byte[] data, int limit){
        if(data == null) return 0;
        Parse parse = parse(data, Math.min(limit, MAX_INFLATED), false);
        return parse.refused ? -1 : parse.bytes;
    }

    /** What {@link #parse} found: what it decoded (if asked), how many bytes it inflated, and whether it refused. */
    private record Parse(Decoded decoded, int bytes, boolean refused){
    }

    private static Parse parse(byte[] data, int limit, boolean keep){
        var counted = new Counted(new InflaterInputStream(new ByteArrayInputStream(data)), limit);
        try(var stream = new DataInputStream(counted)){
            int version, length;
            byte[] bytes;
            try{
                version = stream.read();
                length = stream.readInt();
                if(length > maxByteLen) return new Parse(null, counted.count, false);
                if(keep){
                    bytes = new byte[length];
                    stream.readFully(bytes);
                }else{
                    bytes = null;
                    skip(stream, length);
                }
            }catch(IOException | RuntimeException e){
                return new Parse(null, counted.count, counted.refused);
            }
            ArrayList<Link> links = keep ? new ArrayList<>() : null;
            try{
                int total = Math.min(stream.readInt(), Processor.maxLinks);
                if(version == 0){
                    // the old format only had link positions, which are ignored
                    for(int i = 0; i < total; i++) stream.readInt();
                }else{
                    for(int i = 0; i < total; i++){
                        String name = name(stream, counted);
                        short x = stream.readShort(), y = stream.readShort();
                        if(keep) links.add(new Link(name, x, y));
                    }
                }
            }catch(IOException | RuntimeException e){
                return new Parse(counted.refused ? null : new Decoded(null, links), counted.count, counted.refused);
            }
            return new Parse(keep ? new Decoded(new String(bytes, StandardCharsets.UTF_8), links) : null, counted.count, false);
        }catch(IOException e){
            return new Parse(null, counted.count, counted.refused);
        }
    }

    /** {@code readUTF}, refusing a name over {@link #MAX_NAME_BYTES} from its length, before reading it. */
    private static String name(DataInputStream stream, Counted counted) throws IOException{
        int length = stream.readUnsignedShort();
        if(length > MAX_NAME_BYTES) throw counted.refuse("a link name of " + length + " bytes");
        byte[] utf = new byte[2 + length];
        utf[0] = (byte)(length >>> 8);
        utf[1] = (byte)length;
        stream.readFully(utf, 2, length);
        return new DataInputStream(new ByteArrayInputStream(utf)).readUTF();
    }

    private static void skip(DataInputStream stream, int length) throws IOException{
        if(length < 0) throw new NegativeArraySizeException(String.valueOf(length));
        byte[] scratch = new byte[Math.min(length, 8192)];
        for(int left = length; left > 0; left -= scratch.length) stream.readFully(scratch, 0, Math.min(left, scratch.length));
    }

    /** The inflated bytes, counted: reading past {@code limit} fails and marks the data refused. */
    private static final class Counted extends FilterInputStream{
        final int limit;
        int count;
        boolean refused;

        Counted(InputStream in, int limit){
            super(in);
            this.limit = limit;
        }

        IOException refuse(String what){
            refused = true;
            return new IOException(what + ", past Shard's bounds");
        }

        @Override
        public int read() throws IOException{
            int b = super.read();
            if(b >= 0 && ++count > limit) throw refuse("over " + limit + " inflated bytes");
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException{
            // never inflate more than one byte past the limit
            int read = super.read(b, off, Math.min(len, limit - count + 1));
            if(read > 0 && (count += read) > limit) throw refuse("over " + limit + " inflated bytes");
            return read;
        }

        @Override
        public long skip(long n) throws IOException{
            throw new UnsupportedOperationException("skips would not be counted");
        }
    }
}
