package com.xpdustry.shard.wire;

import arc.struct.IntSet;
import arc.util.io.*;
import com.xpdustry.shard.oracle.*;
import com.xpdustry.shard.oracle.Vanilla.*;
import com.xpdustry.shard.wire.ServerDecode.*;
import com.xpdustry.shard.wire.v160_7.Packets;
import mindustry.Vars;
import mindustry.core.NetClient;
import mindustry.entities.*;
import mindustry.gen.*;
import mindustry.net.*;
import mindustry.net.Packet;

import java.io.*;
import java.lang.reflect.*;
import java.util.*;

/**
 * Reads a server-sent body as vanilla's client does and as Shard's does, and reports each {@link Verdict}. Vanilla
 * parses a generated packet in {@code handled()}, from {@code Packet.BAIS}, as the local player;
 * {@code Packet.allow} rejects packet types a client may not receive. Each case starts from the same world: entities
 * vanilla's read adds, even halfway before it throws (a block's unit joins three groups first), are taken out again.
 */
final class ClientDecode{
    private static final Field bais, playerUnit, removed;
    private static final List<EntityGroup<Entityc>> groups = new ArrayList<>();

    static{
        try{
            bais = Packet.class.getDeclaredField("BAIS");
            bais.setAccessible(true);
            playerUnit = Player.class.getDeclaredField("unit");
            playerUnit.setAccessible(true);
            removed = NetClient.class.getDeclaredField("removed");
            removed.setAccessible(true);
            for(Field field : Groups.class.getFields()){
                if(field.getType() == EntityGroup.class) groups.add(group(field.get(null)));
            }
        }catch(ReflectiveOperationException e){
            throw new IllegalStateException(e);
        }
    }

    private ClientDecode(){}

    @SuppressWarnings("unchecked")
    private static EntityGroup<Entityc> group(Object group){
        return (EntityGroup<Entityc>)group;
    }

    static Verdict vanilla(int id, byte[] body){
        List<Set<Entityc>> before = groups.stream().map(g -> {
            Set<Entityc> seen = Collections.newSetFromMap(new IdentityHashMap<>());
            g.each(seen::add);
            return seen;
        }).toList();
        Map<Player, Object> units = new IdentityHashMap<>();
        units.put(Vanilla.localPlayer(), get(playerUnit, Vanilla.localPlayer()));
        if(Vars.player != null) units.put(Vars.player, get(playerUnit, Vars.player));
        Groups.player.each(player -> units.put(player, get(playerUnit, player)));
        IntSet removedBefore = new IntSet((IntSet)get(removed, Vars.netClient));
        try{
            return Vanilla.asClient(() -> Vanilla.net(NetMode.client, () -> verdict(id, body)));
        }finally{
            for(int i = 0; i < groups.size(); i++){
                Set<Entityc> seen = before.get(i);
                for(Entityc added : groups.get(i).copy().select(e -> !seen.contains(e))) groups.get(i).remove(added);
            }
            units.forEach((player, unit) -> set(playerUnit, player, unit));
            set(removed, Vars.netClient, removedBefore);
        }
    }

    private static Object get(Field field, Object owner){
        try{
            return field.get(owner);
        }catch(IllegalAccessException e){
            throw new IllegalStateException(e);
        }
    }

    private static void set(Field field, Object owner, Object value){
        try{
            field.set(owner, value);
        }catch(IllegalAccessException e){
            throw new IllegalStateException(e);
        }
    }

    private static Verdict verdict(int id, byte[] body){
        try{
            Packet packet = Net.newPacket((byte)id);
            if(!packet.allow(false)) throw new IllegalStateException("Invalid packet type for endpoint: " + packet.getClass());
            packet.read(new Reads(new DataInputStream(new ByteArrayInputStream(body))), body.length);
            packet.handled();
            return Verdict.accept(((ReusableByteInStream)bais.get(null)).position());
        }catch(Throwable t){
            return Verdict.reject(t);
        }
    }

    /** Shard's verdict. Any exception but {@link WireException} is a Shard bug and propagates. */
    static Verdict shard(int id, byte[] body, VanillaContext context){
        var read = new com.xpdustry.shard.common.io.Reads(body);
        context.source(body);
        try{
            Packets.read(id, read, true, context);
            return Verdict.accept(read.position());
        }catch(WireException e){
            return Verdict.reject(e);
        }
    }
}
