package com.xpdustry.shard.wire;

import arc.util.io.*;
import com.xpdustry.shard.oracle.*;
import com.xpdustry.shard.oracle.Vanilla.*;
import com.xpdustry.shard.wire.v160_7.Packets;
import mindustry.net.*;
import mindustry.net.Packet;
import mindustry.net.Packets.*;

import java.io.*;
import java.lang.reflect.*;

/**
 * Reads a client-sent body as vanilla's server does and as Shard's does, and reports each verdict: accepted or
 * rejected, and how many bytes the reader consumed. Vanilla parses a generated packet in {@code handled()}, from
 * {@code Packet.BAIS}; {@code Packet.allow} rejects packet types a server may not receive.
 */
final class ServerDecode{
    /**
     * @param json vanilla rejected only the content of a JSON blob (rules, objectives, a marker): it parses them while
     *             reading, but {@code :wire} keeps them raw for {@code :compat} (ARCHITECTURE.md §6), so Shard accepts
     */
    record Verdict(boolean accepted, int consumed, String reason, boolean json){
        static Verdict accept(int consumed){
            return new Verdict(true, consumed, null, false);
        }

        /** A rejection is {@link #json} when it happened inside Arc's JSON reader, which vanilla's JSON codecs call. */
        static Verdict reject(Throwable t){
            for(Throwable c = t; c != null; c = c.getCause()){
                for(StackTraceElement frame : c.getStackTrace()){
                    if(frame.getClassName().startsWith("arc.util.serialization.Json")) return new Verdict(false, -1, t.toString(), true);
                }
            }
            return new Verdict(false, -1, t.toString(), false);
        }

        /** Equal verdicts: both reject, or both accept after consuming the same bytes; Shard accepts what only JSON failed. */
        boolean same(Verdict other){
            if(json) return other.accepted;
            return accepted == other.accepted && (!accepted || consumed == other.consumed);
        }

        @Override
        public String toString(){
            return accepted ? "accept(" + consumed + ")" : (json ? "reject-json(" : "reject(") + reason + ")";
        }
    }

    private static final Field bais;

    static{
        try{
            bais = Packet.class.getDeclaredField("BAIS");
            bais.setAccessible(true);
        }catch(NoSuchFieldException e){
            throw new IllegalStateException(e);
        }
    }

    private ServerDecode(){}

    static Verdict vanilla(int id, byte[] body){
        return Vanilla.net(NetMode.server, () -> {
            try{
                Packet packet = Net.newPacket((byte)id);
                if(!packet.allow(true)) throw new IllegalStateException("Invalid packet type for endpoint: " + packet.getClass());
                packet.read(new Reads(new DataInputStream(new ByteArrayInputStream(body))), body.length);
                packet.handled();
                return Verdict.accept(((ReusableByteInStream)bais.get(null)).position());
            }catch(Throwable t){
                return Verdict.reject(t);
            }
        });
    }

    /** Shard's verdict. Any exception but {@link WireException} is a Shard bug and propagates. */
    static Verdict shard(int id, byte[] body, VanillaContext context){
        var read = new com.xpdustry.shard.common.io.Reads(body);
        context.source(body);
        try{
            Packets.read(id, read, false, context);
            return Verdict.accept(read.position());
        }catch(WireException e){
            return Verdict.reject(e);
        }
    }

    static Verdict vanillaConnect(byte[] body){
        var input = new ByteArrayInputStream(body);
        try{
            new ConnectPacket().read(new Reads(new DataInputStream(input)), body.length);
            return Verdict.accept(body.length - input.available());
        }catch(Throwable t){
            return Verdict.reject(t);
        }
    }

    static Verdict shardConnect(byte[] body){
        var read = new com.xpdustry.shard.common.io.Reads(body);
        try{
            Bootstrap.read(read);
            return Verdict.accept(read.position());
        }catch(WireException e){
            return Verdict.reject(e);
        }
    }
}
