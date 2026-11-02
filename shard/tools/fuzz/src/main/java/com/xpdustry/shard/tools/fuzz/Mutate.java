package com.xpdustry.shard.tools.fuzz;

import com.xpdustry.shard.net.*;

import java.util.*;

/**
 * Byte-level damage: to a packet's payload (flips, interesting values, truncation, extension, splices, length fields
 * rewritten) and to the frame around it (the TCP length prefix, the raw length, the compression flag, LZ4 streams).
 * Payload mutations keep the frame valid, so they reach the decoders; frame mutations test the transport.
 */
final class Mutate{
    private static final byte[] INTERESTING = {0, -1, 1, 127, -128, 0x7f, (byte)0x80, 20, 21, -2};

    private final Random rnd;

    Mutate(Random rnd){
        this.rnd = rnd;
    }

    /** {@code payload} with 1-4 random mutations. */
    byte[] payload(byte[] payload){
        byte[] out = payload;
        for(int i = 0, n = 1 + rnd.nextInt(4); i < n; i++) out = once(out);
        return out;
    }

    private byte[] once(byte[] in){
        int n = in.length;
        switch(rnd.nextInt(9)){
            case 0 -> {
                if(n == 0) return in;
                byte[] out = in.clone();
                out[rnd.nextInt(n)] ^= (byte)(1 << rnd.nextInt(8));
                return out;
            }
            case 1 -> {
                if(n == 0) return in;
                byte[] out = in.clone();
                out[rnd.nextInt(n)] = INTERESTING[rnd.nextInt(INTERESTING.length)];
                return out;
            }
            case 2 -> {
                // a length field: an i16 or i32 somewhere set to an extreme
                if(n < 4) return in;
                byte[] out = in.clone();
                int at = rnd.nextInt(n - 3);
                int value = switch(rnd.nextInt(5)){
                    case 0 -> -1;
                    case 1 -> 0x7fff;
                    case 2 -> 0x8000;
                    case 3 -> Integer.MAX_VALUE;
                    default -> rnd.nextInt(5000);
                };
                if(rnd.nextBoolean()){
                    out[at] = (byte)(value >>> 8);
                    out[at + 1] = (byte)value;
                }else{
                    out[at] = (byte)(value >>> 24);
                    out[at + 1] = (byte)(value >>> 16);
                    out[at + 2] = (byte)(value >>> 8);
                    out[at + 3] = (byte)value;
                }
                return out;
            }
            case 3 -> {
                return Arrays.copyOf(in, n == 0 ? 0 : rnd.nextInt(n));
            }
            case 4 -> {
                byte[] extra = new byte[1 + rnd.nextInt(rnd.nextInt(8) == 0 ? 4000 : 16)];
                rnd.nextBytes(extra);
                byte[] out = Arrays.copyOf(in, n + extra.length);
                System.arraycopy(extra, 0, out, n, extra.length);
                return out;
            }
            case 5 -> {
                // repeat a slice: a list's element many times over
                if(n == 0) return in;
                int from = rnd.nextInt(n), len = 1 + rnd.nextInt(Math.min(32, n - from));
                int times = 1 + rnd.nextInt(200);
                byte[] out = Arrays.copyOf(in, Math.min(30_000, n + len * times));
                for(int at = n, t = 0; t < times && at + len <= out.length; t++, at += len) System.arraycopy(in, from, out, at, len);
                return out;
            }
            case 6 -> {
                if(n < 2) return in;
                byte[] out = in.clone();
                int a = rnd.nextInt(n), b = rnd.nextInt(n);
                byte t = out[a];
                out[a] = out[b];
                out[b] = t;
                return out;
            }
            case 7 -> {
                // delete a slice
                if(n == 0) return in;
                int from = rnd.nextInt(n), len = 1 + rnd.nextInt(n - from);
                byte[] out = new byte[n - len];
                System.arraycopy(in, 0, out, 0, from);
                System.arraycopy(in, from + len, out, from, n - from - len);
                return out;
            }
            default -> {
                byte[] out = new byte[n == 0 ? 1 + rnd.nextInt(32) : n];
                rnd.nextBytes(out);
                return out;
            }
        }
    }

    /** How a frame goes out. */
    enum FrameKind{
        /** As vanilla's client frames it. */
        VALID,
        /** Uncompressed even past the threshold, or compressed below it: legal for Arc. */
        ODD_COMPRESSION,
        /** The raw length disagrees with the payload. */
        WRONG_RAW_LENGTH,
        /** A compression flag other than 0 and 1, or LZ4 bytes damaged. */
        BAD_COMPRESSION,
        /** The TCP prefix says 0, more than the server's 16 KiB, or a different length: the stream desyncs. */
        BAD_PREFIX,
        /** A body cut short: id only, or id and part of the header. */
        TRUNCATED_HEADER
    }

    /** Which frame damage, if any: mostly none, since each closes the connection. */
    FrameKind frameKind(int frameRate){
        if(rnd.nextInt(1000) >= frameRate) return rnd.nextInt(20) == 0 ? FrameKind.ODD_COMPRESSION : FrameKind.VALID;
        FrameKind[] bad = {FrameKind.WRONG_RAW_LENGTH, FrameKind.BAD_COMPRESSION, FrameKind.BAD_PREFIX, FrameKind.TRUNCATED_HEADER};
        return bad[rnd.nextInt(bad.length)];
    }

    /** The body (no TCP prefix) of game packet {@code id} carrying {@code payload}, damaged as {@code kind} says. */
    byte[] body(int id, byte[] payload, FrameKind kind, Lz4 lz4){
        int len = payload.length;
        boolean compress = switch(kind){
            case VALID -> len >= Frame.COMPRESS_THRESHOLD;
            case ODD_COMPRESSION -> len < Frame.COMPRESS_THRESHOLD;
            default -> rnd.nextBoolean();
        };
        byte[] data = payload;
        if(compress){
            byte[] out = new byte[Lz4.bound(len)];
            int size = lz4.compress(payload, 0, len, out, 0);
            data = Arrays.copyOf(out, size);
        }
        int raw = len;
        int flag = compress ? 1 : 0;
        switch(kind){
            case WRONG_RAW_LENGTH -> raw = rnd.nextBoolean() ? len + 1 + rnd.nextInt(100) : rnd.nextInt(0x10000);
            case BAD_COMPRESSION -> {
                if(compress && data.length > 0 && rnd.nextBoolean()){
                    data = data.clone();
                    for(int i = 0, n = 1 + rnd.nextInt(4); i < n; i++) data[rnd.nextInt(data.length)] = (byte)rnd.nextInt();
                }else{
                    flag = 2 + rnd.nextInt(254);
                }
            }
            case TRUNCATED_HEADER -> {
                return Arrays.copyOf(new byte[]{(byte)id, (byte)(raw >>> 8), (byte)raw}, 1 + rnd.nextInt(3));
            }
            default -> {
            }
        }
        byte[] body = new byte[Frame.HEADER + data.length];
        body[0] = (byte)id;
        body[1] = (byte)(raw >>> 8);
        body[2] = (byte)raw;
        body[3] = (byte)flag;
        System.arraycopy(data, 0, body, Frame.HEADER, data.length);
        return body;
    }

    /** {@code body} with its TCP length prefix, the right one unless {@code kind} is {@link FrameKind#BAD_PREFIX}. */
    byte[] tcp(byte[] body, FrameKind kind){
        int prefix = body.length;
        if(kind == FrameKind.BAD_PREFIX){
            prefix = switch(rnd.nextInt(4)){
                case 0 -> 0;
                case 1 -> Frame.MAX_SERVER_BODY + 1 + rnd.nextInt(0xffff - Frame.MAX_SERVER_BODY);
                case 2 -> Math.max(1, body.length - 1 - rnd.nextInt(Math.max(1, body.length)));
                default -> body.length + 1 + rnd.nextInt(64);
            };
        }
        byte[] out = new byte[2 + body.length];
        out[0] = (byte)(prefix >>> 8);
        out[1] = (byte)prefix;
        System.arraycopy(body, 0, out, 2, body.length);
        return out;
    }

    /** A framework message, valid or not: pings, keepalives, discovery, registrations with wrong ids, unknown types. */
    byte[] framework(){
        return switch(rnd.nextInt(7)){
            case 0 -> new byte[]{Frame.FRAMEWORK, Frame.PING, 0, 0, 0, (byte)rnd.nextInt(), (byte)rnd.nextInt(2)};
            case 1 -> new byte[]{Frame.FRAMEWORK, Frame.KEEP_ALIVE};
            case 2 -> new byte[]{Frame.FRAMEWORK, Frame.DISCOVER_HOST};
            case 3 -> new byte[]{Frame.FRAMEWORK, Frame.REGISTER_UDP, (byte)rnd.nextInt(), (byte)rnd.nextInt(), (byte)rnd.nextInt(), (byte)rnd.nextInt()};
            case 4 -> new byte[]{Frame.FRAMEWORK, Frame.REGISTER_TCP, 1, 2, 3, 4};
            case 5 -> new byte[]{Frame.FRAMEWORK, (byte)(5 + rnd.nextInt(250))};
            default -> {
                byte[] out = new byte[1 + rnd.nextInt(40)];
                rnd.nextBytes(out);
                out[0] = Frame.FRAMEWORK;
                yield out;
            }
        };
    }
}
