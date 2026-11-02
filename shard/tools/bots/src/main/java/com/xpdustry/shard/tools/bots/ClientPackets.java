package com.xpdustry.shard.tools.bots;

import com.xpdustry.shard.common.io.*;

import java.util.*;
import java.util.zip.*;

/**
 * The few v160.7 client packets a bot sends, encoded by hand (the generated codecs of {@code :wire}, WP14, replace
 * them), and the ids of what it reads.
 */
final class ClientPackets{
    static final int STREAM_BEGIN = 0, STREAM_CHUNK = 1, CONNECT = 3, CLIENT_SNAPSHOT = 29, CONNECT_CONFIRM = 34, ENTITY_SNAPSHOT = 49,
        KICK = 65, KICK_REASON = 66, PING = 84, PING_RESPONSE = 86, SEND_CHAT_MESSAGE = 105, WORLD_DATA_BEGIN = 172;

    /**
     * {@code ConnectPacket} ({@code Packets.java:153-172}): build 160, version type, name, locale, usid, then the 8-byte
     * uuid followed by its CRC32 as an i64, mobile, colour and no mods.
     */
    static byte[] connect(int index, String name){
        byte[] uuid = new byte[8];
        new Random(0xB07L * 31 + index).nextBytes(uuid);
        var crc = new CRC32();
        crc.update(uuid);
        var out = new Writes(96);
        out.i(160);
        string(out, "official");
        string(out, name);
        string(out, "en");
        string(out, Base64.getEncoder().encodeToString(new byte[]{(byte)index, (byte)(index >> 8), (byte)(index >> 16), 1, 2, 3, 4, 5}));
        out.b(uuid);
        out.l(crc.getValue());
        out.bool(false);
        out.i(0xffd37fff);
        out.b(0);
        return out.toByteArray();
    }

    /**
     * {@code clientSnapshot} with a bot circling the map. The unit is unknown to the bot, so it reports itself dead
     * and vanilla ignores the position; the view rectangle still updates.
     */
    static byte[] clientSnapshot(int snapshotId, double seconds, int index){
        double t = seconds * 0.5 + index;
        float x = (float)(200 + 100 * Math.cos(t)), y = (float)(200 + 100 * Math.sin(t));
        var out = new Writes(96);
        out.i(snapshotId);
        out.i(-1); // unit id
        out.bool(true); // dead
        out.f(x);
        out.f(y);
        out.f(x + 40); // pointer
        out.f(y + 40);
        out.f((float)(t * 20 % 360)); // rotation
        out.f(0); // base rotation
        out.f(0.4f); // velocity
        out.f(0.2f);
        out.i(-1); // mining tile
        out.bool(false); // boosting
        out.bool(false); // shooting
        out.bool(false); // chatting
        out.bool(false); // building
        out.s(-1); // selected block
        out.i(0); // selected rotation
        out.i(-1); // plans: null
        out.f(x); // view
        out.f(y);
        out.f(1920 / 4f);
        out.f(1080 / 4f);
        return out.toByteArray();
    }

    /**
     * {@code clientSnapshot} driving unit {@code unitId}: its position, aim, velocity in world units a tick, shooting,
     * and, while {@code building}, the plan queue ({@code writePlansQueueNet}; null leaves the server's queue alone).
     */
    static byte[] driveSnapshot(int snapshotId, int unitId, float x, float y, float aimX, float aimY, float rotation, float vx, float vy,
                                boolean shooting, boolean building, java.util.List<com.xpdustry.shard.wire.Codecs.Plan> plans){
        var out = new Writes(160);
        out.i(snapshotId);
        out.i(unitId);
        out.bool(false); // dead
        out.f(x);
        out.f(y);
        out.f(aimX);
        out.f(aimY);
        out.f(rotation);
        out.f(rotation); // base rotation
        out.f(vx);
        out.f(vy);
        out.i(-1); // mining tile
        out.bool(false); // boosting
        out.bool(shooting);
        out.bool(false); // chatting
        out.bool(building);
        out.s(-1); // selected block
        out.i(0); // selected rotation
        com.xpdustry.shard.wire.Codecs.writePlansQueueNet(out, plans);
        out.f(x); // view
        out.f(y);
        out.f(1920 / 4f);
        out.f(1080 / 4f);
        return out.toByteArray();
    }

    static byte[] chat(String text){
        var out = new Writes(text.length() + 3);
        string(out, text);
        return out.toByteArray();
    }

    static byte[] ping(long nanos){
        var out = new Writes(8);
        out.l(nanos);
        return out.toByteArray();
    }

    /** {@code TypeIO.writeString}: a presence byte, then {@code writeUTF}. */
    static void string(Writes out, String value){
        out.b(1);
        out.str(value);
    }

    /** {@code TypeIO.readString}, or the hex of the payload if it isn't one. */
    static String readString(byte[] payload){
        try{
            var in = new Reads(payload);
            return in.b() == 0 ? null : in.str();
        }catch(RuntimeException e){
            return HexFormat.of().formatHex(payload);
        }
    }

    /**
     * The player id from an inflated world stream ({@code NetworkIO.loadWorld}): content patches, rules and locales
     * JSON, map tags, wave state and seeds come first.
     */
    static int playerId(byte[] deflated){
        try(var in = new InflaterInputStream(new java.io.ByteArrayInputStream(deflated))){
            byte[] raw = in.readAllBytes();
            var reads = new Reads(raw);
            reads.i(); // patch format
            int patches = reads.i();
            for(int i = 0; i < patches; i++) reads.skip(reads.i());
            reads.str(); // rules
            reads.str(); // locales
            int tags = reads.s();
            for(int i = 0; i < tags; i++){
                reads.str();
                reads.str();
            }
            reads.i(); // wave
            reads.f(); // wave countdown
            reads.d(); // tick
            reads.l(); // seeds
            reads.l();
            return reads.i();
        }catch(java.io.IOException | RuntimeException e){
            return -1;
        }
    }

    private ClientPackets(){
    }
}
