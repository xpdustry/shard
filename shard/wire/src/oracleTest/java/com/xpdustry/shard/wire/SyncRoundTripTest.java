package com.xpdustry.shard.wire;

import arc.util.io.*;
import com.xpdustry.shard.oracle.*;
import com.xpdustry.shard.oracle.Vanilla.*;
import com.xpdustry.shard.wire.v160_7.EntityClasses;
import mindustry.content.*;
import mindustry.game.*;
import mindustry.gen.*;
import mindustry.type.*;
import mindustry.world.blocks.payloads.*;
import org.junit.jupiter.api.*;

import java.io.*;
import java.util.*;
import java.util.function.*;

import static mindustry.Vars.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * L1 for entity layouts (proto 25's {@code SyncRoundTrip}), for every unit type and every other synced or saved class:
 * <ul>
 * <li>sync: vanilla's {@code writeSync} (A); Shard reads A, consuming all of it, and writes it back (B); A == B. Vanilla
 *     reads B into a fresh entity and writes it again (C); B == C. Read as the entity's own client, Shard consumes the
 *     same bytes and drops the @SyncLocal fields.
 * <li>save: the same with {@code write}, revision included, and with unit payloads; payload units carry unit and block
 *     payloads, whose building bodies vanilla reads for the test ({@link VanillaContext}).
 * </ul>
 */
class SyncRoundTripTest{
    static final int SEEDS = 12;
    static Randoms random;
    static final VanillaContext context = new VanillaContext();
    static final Set<String> syncLayouts = new TreeSet<>(), saveLayouts = new TreeSet<>();

    @BeforeAll
    static void world(){
        random = Randoms.world(160);
    }


    static byte[] bytes(Consumer<Writes> writer){
        return Golden.bytes(writer::accept);
    }

    /** Sync: A == B == C, and a local read consumes all of A. */
    static void sync(Syncc entity, Supplier<Syncc> fresh){
        int classId = entity.classId() & 0xFF;
        byte[] a = bytes(entity::writeSync);
        String name = entity.getClass().getSimpleName();

        var read = new com.xpdustry.shard.common.io.Reads(a);
        WireEntity state = EntityClasses.readSync(read, classId, false, context.source(a));
        assertEquals(a.length, read.position(), () -> name + ": bytes left after Shard's read of " + Hex.of(a));
        var write = new com.xpdustry.shard.common.io.Writes();
        EntityClasses.writeSync(write, classId, state, context);
        byte[] b = write.toByteArray();
        assertArrayEquals(a, b, () -> name + " sync\nA=" + Hex.of(a) + "\nB=" + Hex.of(b));

        byte[] c = Vanilla.asClient(() -> Vanilla.net(NetMode.client, () -> {
            Syncc back = fresh.get();
            back.readSync(new Reads(new DataInputStream(new ByteArrayInputStream(b))));
            back.snapSync();
            return bytes(back::writeSync);
        }));
        assertArrayEquals(b, c, () -> name + " vanilla reads Shard's sync back\nB=" + Hex.of(b) + "\nC=" + Hex.of(c));

        var local = new com.xpdustry.shard.common.io.Reads(a);
        EntityClasses.readSync(local, classId, true, context);
        assertEquals(a.length, local.position(), name + ": a local read consumes the same bytes");
        syncLayouts.add(EntityClasses.get(classId).layout());
    }

    /** Save: Shard reads vanilla's {@code write} and writes the same bytes. */
    static void save(Entityc entity){
        int classId = entity.classId() & 0xFF;
        byte[] a = bytes(entity::write);
        String name = entity.getClass().getSimpleName();
        var read = new com.xpdustry.shard.common.io.Reads(a);
        WireEntity state = EntityClasses.readSave(read, classId, context.source(a));
        assertEquals(a.length, read.position(), () -> name + ": bytes left after Shard's read of " + Hex.of(a));
        var write = new com.xpdustry.shard.common.io.Writes();
        EntityClasses.writeSave(write, classId, state, context);
        assertArrayEquals(a, write.toByteArray(), () -> name + " save\nA=" + Hex.of(a) + "\nB=" + Hex.of(write.toByteArray()));
        saveLayouts.add(EntityClasses.get(classId).layout());
    }

    static void payload(Payload payload){
        byte[] a = bytes(w -> Payload.write(payload, w));
        var read = new com.xpdustry.shard.common.io.Reads(a);
        Codecs.Payload decoded = EntityClasses.readPayload(read, context.source(a));
        assertEquals(a.length, read.position());
        var write = new com.xpdustry.shard.common.io.Writes();
        EntityClasses.writePayload(write, decoded, context);
        assertArrayEquals(a, write.toByteArray(), () -> payload + "\nA=" + Hex.of(a) + "\nB=" + Hex.of(write.toByteArray()));
    }

    @Test
    void everyLayout(){
        units();
        otherEntities();
        Set<String> withSync = new TreeSet<>(), withSave = new TreeSet<>();
        for(var c : EntityClasses.ALL){
            if(c.synced()) withSync.add(c.layout());
            if(c.revision() >= 0) withSave.add(c.layout());
        }
        assertEquals(withSync, syncLayouts, "sync layouts covered");
        assertEquals(withSave, saveLayouts, "save layouts covered");
    }

    void units(){
        for(UnitType type : content.units()){
            for(int seed = 0; seed < SEEDS; seed++){
                Unit unit = random.unit(type);
                sync(unit, () -> type.create(Team.sharded));
                save(unit);
                payload(new UnitPayload(unit));
            }
        }
    }

    void otherEntities(){
        for(int seed = 0; seed < SEEDS * 4; seed++){
            Player player = random.player();
            sync(player, Player::create);
            save(player);
            Fire fire = random.fire();
            sync(fire, Fire::create);
            save(fire);
            Puddle puddle = random.puddle();
            sync(puddle, Puddle::create);
            save(puddle);
            WeatherState weather = random.weather();
            sync(weather, WeatherState::create);
            save(weather);
            WorldLabel label = random.label();
            sync(label, WorldLabel::create);
            save(label);
            save(random.launchPayload());
        }
    }

    @Test
    void blockPayloads(){
        payload(null);
        payload(new BuildPayload(Blocks.router, Team.sharded));
        payload(new BuildPayload(Blocks.duo, Team.crux));
    }
}
