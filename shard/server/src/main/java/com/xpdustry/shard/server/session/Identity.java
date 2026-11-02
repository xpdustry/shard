package com.xpdustry.shard.server.session;

import com.xpdustry.shard.common.io.*;
import com.xpdustry.shard.server.admin.*;
import com.xpdustry.shard.wire.*;

import java.util.*;

/**
 * Who a client says it is: the fields of its {@code ConnectPacket} ({@code Packets.java:174-190}), read by
 * {@link Bootstrap}, the pack-independent decoder of ARCHITECTURE.md §6. Nothing here is authenticated; the uuid is
 * what the client claims.
 *
 * @param version the client's build ({@code 160} for v160.7, {@code -1} for a custom build)
 * @param versionType {@code official}, {@code bleeding-edge}, a mod's name…; null if the client sent none
 * @param name the name the server uses: vanilla's {@code fixName} of the one sent
 * @param locale {@code en} if the client sent none, as vanilla
 * @param uuid what vanilla keys players by: Base64 of the 16 uuid bytes, the client's id and its CRC32
 * @param mods the client's mods, {@code name:version}
 */
public record Identity(int version, String versionType, String name, String locale, String usid, String uuid, boolean mobile,
                       int color, List<String> mods){
    public Identity{
        mods = Collections.unmodifiableList(new ArrayList<>(mods));
    }

    /** The identity of {@code connect}, with its name fixed as vanilla does ({@link Names#fix}). */
    public static Identity of(Bootstrap.Connect connect){
        return new Identity(connect.build(), connect.versionType(), Names.fix(connect.name()), connect.locale() == null ? "en" : connect.locale(),
            connect.usid(), connect.uuidString(), connect.mobile(), connect.color(), connect.mods());
    }

    /**
     * Reads a {@code ConnectPacket} body.
     *
     * @throws IllegalArgumentException if it is malformed
     */
    public static Bootstrap.Connect read(byte[] payload){
        try{
            return Bootstrap.read(new Reads(payload));
        }catch(WireException e){
            throw new IllegalArgumentException(e.getMessage(), e);
        }
    }

    /** The name without Mindustry's color tags, for logs. */
    public String plainName(){
        return Names.stripColors(name).trim();
    }
}
