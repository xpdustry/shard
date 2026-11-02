package com.xpdustry.shard.net;

import java.nio.*;
import java.util.*;

/**
 * One encoded Arc-net message, immutable and shareable: encode it once per world or visibility class and hand the
 * same frame to every connection. The layout is {@code u16 length | body}; TCP sends all of it, UDP only the body.
 *
 * <p>A game packet's body is {@code u8 id | u16 rawLength | u8 compression | payload}, as vanilla's
 * {@code PacketSerializer} writes it ({@code ArcNetProvider.java:543-580}). A framework message's body is
 * {@code u8 -2 | u8 type | fields} ({@code ArcNetProvider.java:594-609}).
 */
public final class Frame{
    /** The id byte of an Arc framework message. */
    public static final byte FRAMEWORK = -2;
    /** Framework message types ({@code ArcNetProvider.java:594-609}). */
    public static final byte PING = 0, DISCOVER_HOST = 1, KEEP_ALIVE = 2, REGISTER_UDP = 3, REGISTER_TCP = 4;
    /** The two packets vanilla registers by hand before the generated Calls ({@code Net.java:47-56}). */
    public static final int STREAM_BEGIN = 0, STREAM_CHUNK = 1;
    /** The client's read buffer ({@code ArcNetProvider.clientReadBufferSize}): a longer body kills the client. */
    public static final int MAX_CLIENT_BODY = 25_000;
    /** The server's object buffer ({@code ArcNetProvider.java:113}): vanilla rejects longer client frames. */
    public static final int MAX_SERVER_BODY = 16_384;
    /** Both sides decode into a 32 KiB buffer ({@code ArcNetProvider.java:487}), so no payload may be longer. */
    public static final int MAX_PAYLOAD = 32_768;
    /** Vanilla compresses a payload of at least this many bytes, except a {@code StreamChunk}. */
    public static final int COMPRESS_THRESHOLD = 36;
    /** The id, raw length and compression flag before a game packet's payload. */
    public static final int HEADER = 4;

    public static final Frame KEEP_ALIVE_FRAME = new Frame(new byte[]{0, 2, FRAMEWORK, KEEP_ALIVE});

    private final byte[] bytes;

    private Frame(byte[] bytes){
        this.bytes = bytes;
    }

    /** A game packet, compressed as vanilla would, with this thread's {@link Lz4#forThread() compressor}. */
    public static Frame packet(int id, byte[] payload){
        return packet(id, payload, 0, payload.length, id != STREAM_CHUNK, null);
    }

    /** A game packet, compressed as vanilla would. */
    public static Frame packet(int id, byte[] payload, Lz4 lz4){
        return packet(id, payload, 0, payload.length, id != STREAM_CHUNK, lz4);
    }

    /**
     * A game packet from {@code payload[off, off + len)}. With {@code compress}, payloads that vanilla would
     * compress are LZ4-compressed, with {@code lz4}, or this thread's {@link Lz4#forThread() compressor} if it is null;
     * vanilla keeps incompressible ones compressed too, and so does this. A payload too short to compress takes no
     * compressor: on a virtual thread, {@code forThread} makes a new one each time.
     *
     * @throws IllegalArgumentException if the payload or the frame is too long for the client
     */
    public static Frame packet(int id, byte[] payload, int off, int len, boolean compress, Lz4 lz4){
        Objects.checkFromIndexSize(off, len, payload.length);
        if(len > MAX_PAYLOAD) throw new IllegalArgumentException("payload over " + MAX_PAYLOAD + " B: " + len);
        // vanilla quirk: it compares (short)length, so a payload of exactly 32,768 B goes uncompressed
        boolean lz = compress && (short)len >= COMPRESS_THRESHOLD;
        byte[] out = new byte[2 + HEADER + (lz ? Lz4.bound(len) : len)];
        int body = HEADER + (lz ? (lz4 != null ? lz4 : Lz4.forThread()).compress(payload, off, len, out, 2 + HEADER) : len);
        if(!lz) System.arraycopy(payload, off, out, 2 + HEADER, len);
        if(body > MAX_CLIENT_BODY) throw new IllegalArgumentException("frame body over the client's read buffer: " + body);
        out[0] = (byte)(body >>> 8);
        out[1] = (byte)body;
        out[2] = (byte)id;
        out[3] = (byte)(len >>> 8);
        out[4] = (byte)len;
        out[5] = (byte)(lz ? 1 : 0);
        return new Frame(lz ? Arrays.copyOf(out, 2 + body) : out);
    }

    /** {@code StreamBegin}: {@code i32 id, i32 total, u8 type} ({@code Packets.java:107-111}). */
    public static Frame streamBegin(int streamId, int total, int type){
        byte[] payload = ByteBuffer.allocate(9).putInt(streamId).putInt(total).put((byte)type).array();
        return packet(STREAM_BEGIN, payload, 0, payload.length, false, null);
    }

    /** {@code StreamChunk}: {@code i32 id, i16 length, bytes} ({@code Packets.java:131-135}), never compressed. */
    public static Frame streamChunk(int streamId, byte[] data, int off, int len){
        byte[] payload = ByteBuffer.allocate(6 + len).putInt(streamId).putShort((short)len).put(data, off, len).array();
        return packet(STREAM_CHUNK, payload, 0, payload.length, false, null);
    }

    /** {@code RegisterTCP} or {@code RegisterUDP} with its connection id. */
    public static Frame register(byte type, int connectionId){
        if(type != REGISTER_TCP && type != REGISTER_UDP) throw new IllegalArgumentException("not a register type: " + type);
        return new Frame(ByteBuffer.allocate(8).putShort((short)6).put(FRAMEWORK).put(type).putInt(connectionId).array());
    }

    /** A framework {@code Ping}. */
    public static Frame ping(int pingId, boolean reply){
        return new Frame(ByteBuffer.allocate(9).putShort((short)7).put(FRAMEWORK).put(PING).putInt(pingId)
            .put((byte)(reply ? 1 : 0)).array());
    }

    /** A framework {@code DiscoverHost}, as a client sends it. */
    public static Frame discoverHost(){
        return new Frame(new byte[]{0, 2, FRAMEWORK, DISCOVER_HOST});
    }

    /** Size on the TCP wire, prefix included. */
    public int size(){
        return bytes.length;
    }

    /** Size of the body: the datagram on UDP. */
    public int bodySize(){
        return bytes.length - 2;
    }

    /** The packet id, or {@link #FRAMEWORK}. */
    public int id(){
        return bytes[2] == FRAMEWORK ? FRAMEWORK : bytes[2] & 0xff;
    }

    /** A read-only view of the whole frame, for TCP. Clients and tests; the server's writers use {@link #putTcp}. */
    public ByteBuffer tcp(){
        return ByteBuffer.wrap(bytes).asReadOnlyBuffer();
    }

    /** A read-only view of the body, for UDP, with its own position. Clients and tests; the server's writers use {@link #putUdp}. */
    public ByteBuffer udp(){
        return ByteBuffer.wrap(bytes, 2, bytes.length - 2).slice().asReadOnlyBuffer();
    }

    /** Copies the whole frame into {@code out}: the frame is shared, so a writer sends its own buffer, not a view. */
    void putTcp(ByteBuffer out){
        out.put(bytes);
    }

    /** Copies the body into {@code out}: the datagram. */
    void putUdp(ByteBuffer out){
        out.put(bytes, 2, bytes.length - 2);
    }

    /** A copy of the whole frame. */
    public byte[] toByteArray(){
        return bytes.clone();
    }

    /**
     * Decodes a game packet body after its id byte ({@code u16 rawLength | u8 compression | payload}) into the raw
     * payload. The body must be consumed exactly, as Arc requires ({@code TcpConnection.java:136-141}): an
     * uncompressed payload fills it, and a compressed one decodes to exactly {@code rawLength} bytes.
     *
     * @throws IllegalArgumentException if the body is malformed
     */
    public static byte[] decodePayload(byte[] body, int off, int len){
        Objects.checkFromIndexSize(off, len, body.length);
        if(len < 3) throw new IllegalArgumentException("truncated packet header");
        int rawLength = (body[off] & 0xff) << 8 | body[off + 1] & 0xff;
        byte compression = body[off + 2];
        if(rawLength > MAX_PAYLOAD) throw new IllegalArgumentException("payload over " + MAX_PAYLOAD + " B: " + rawLength);
        int start = off + 3, rest = len - 3;
        if(compression == 0){
            if(rest != rawLength) throw new IllegalArgumentException("payload is " + rest + " B, header says " + rawLength);
            return Arrays.copyOfRange(body, start, start + rest);
        }
        return Lz4.decompress(body, start, rest, rawLength);
    }

    @Override
    public String toString(){
        return "Frame[id=" + id() + ", " + bytes.length + " B]";
    }
}
