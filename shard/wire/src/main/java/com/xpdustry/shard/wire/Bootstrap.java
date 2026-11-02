package com.xpdustry.shard.wire;

import com.xpdustry.shard.common.io.*;

import java.util.*;
import java.util.zip.*;

/**
 * Reads a client's first packet, {@code ConnectPacket}, without a protocol pack (Packets.java:144-190). Its id (3) and
 * layout are the same in v146, v159 and v160.7, and it starts with the client's build number, so the server reads it
 * here and then picks the pack for the connection. {@code BootstrapTest} reads bodies each build's own jar wrote
 * ({@code tools/wirelift/fixtures/bootstrap}); record a new build's before trusting this with it.
 *
 * <pre>
 * i32 build | String versionType | String name | String locale | String usid | 8 bytes uuid | i64 crc32(uuid)
 *   | u8 mobile | i32 color | i8 mod count | String mod...
 * </pre>
 */
public final class Bootstrap{
    /** {@code ConnectPacket}'s id in every supported build. */
    public static final int CONNECT_ID = 3;
    /** Vanilla's build number for a custom client ({@code Version.build} of a source build). */
    public static final int CUSTOM_BUILD = -1;

    private Bootstrap(){}

    /**
     * The packet as vanilla's server reads it.
     * @param build        {@code Version.build}: the major build only, 160 for v160.7
     * @param versionType  "official", "bleeding-edge" or a custom client's; null if absent
     * @param name         raw: vanilla trims and strips it later ({@code NetServer.fixName})
     * @param uuid         the 16 bytes vanilla's reader takes as one: the client's 8-byte id, then the big-endian
     *                     CRC32 of those 8 bytes
     * @param mobile       the byte was 1
     */
    public record Connect(int build, String versionType, String name, String locale, String usid, byte[] uuid, boolean mobile,
                          int color, List<String> mods){
        public Connect{
            if(uuid.length != 16) throw new IllegalArgumentException("a connect packet's uuid has 16 bytes with its CRC, not " + uuid.length);
            //a mod name may be null, which List.copyOf refuses
            mods = Collections.unmodifiableList(new ArrayList<>(mods));
        }

        /** Builds one from a client's 8-byte id, adding its CRC as vanilla's client does. */
        public static Connect of(int build, String versionType, String name, String locale, String usid, byte[] id, boolean mobile,
                                 int color, List<String> mods){
            if(id.length != 8) throw new IllegalArgumentException("a client id has 8 bytes, not " + id.length);
            CRC32 crc = new CRC32();
            crc.update(id);
            byte[] uuid = Arrays.copyOf(id, 16);
            long value = crc.getValue();
            for(int i = 0; i < 8; i++) uuid[8 + i] = (byte)(value >>> (56 - 8 * i));
            return new Connect(build, versionType, name, locale, usid, uuid, mobile, color, mods);
        }

        /** The uuid string vanilla's server keys players by: Base64 of all 16 bytes. */
        public String uuidString(){
            return Base64.getEncoder().encodeToString(uuid);
        }

        /** Whether the last 8 bytes are the CRC32 of the first 8, as vanilla's client writes them. */
        public boolean crcMatches(){
            CRC32 crc = new CRC32();
            crc.update(uuid, 0, 8);
            long value = 0;
            for(int i = 8; i < 16; i++) value = value << 8 | (uuid[i] & 0xFF);
            return crc.getValue() == value;
        }
    }

    /** The build number at the start of a connect packet body, or throws if the body is shorter than 4 bytes. */
    public static int build(byte[] body){
        if(body.length < 4) throw new WireException("a connect packet starts with an i32 build, but has " + body.length + " bytes");
        return new Reads(body).i();
    }

    /**
     * Reads a connect packet body as vanilla's {@code ConnectPacket.read}. Trailing bytes are left unread, as vanilla
     * leaves them; a negative mod count fails, as its {@code Seq} allocation does.
     */
    public static Connect read(Reads read){
        return WireException.decoding("connect packet", () -> {
            int build = read.i();
            String versionType = Codecs.readString(read), name = Codecs.readString(read), locale = Codecs.readString(read),
                usid = Codecs.readString(read);
            byte[] uuid = read.b(16);
            boolean mobile = read.b() == 1;
            int color = read.i();
            int count = read.b();
            if(count < 0) throw new WireException("negative mod count " + count);
            List<String> mods = new ArrayList<>(count);
            for(int i = 0; i < count; i++) mods.add(Codecs.readString(read));
            return new Connect(build, versionType, name, locale, usid, uuid, mobile, color, mods);
        });
    }

    /** Writes a connect packet body as vanilla's client does. A mod list over 127 entries can't be sent. */
    public static void write(Writes write, Connect connect){
        if(connect.mods.size() > Byte.MAX_VALUE) throw new WireException(connect.mods.size() + " mods, over 127");
        write.i(connect.build);
        Codecs.writeString(write, connect.versionType);
        Codecs.writeString(write, connect.name);
        Codecs.writeString(write, connect.locale);
        Codecs.writeString(write, connect.usid);
        write.b(connect.uuid);
        write.b(connect.mobile ? 1 : 0);
        write.i(connect.color);
        write.b(connect.mods.size());
        for(String mod : connect.mods) Codecs.writeString(write, mod);
    }
}
