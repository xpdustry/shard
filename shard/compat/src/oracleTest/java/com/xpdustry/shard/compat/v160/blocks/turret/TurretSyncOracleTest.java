package com.xpdustry.shard.compat.v160.blocks.turret;

import com.xpdustry.shard.common.io.*;
import com.xpdustry.shard.compat.v160.world.*;
import com.xpdustry.shard.oracle.*;
import com.xpdustry.shard.sim.blocks.turret.*;
import com.xpdustry.shard.sim.building.Building;
import mindustry.game.*;
import mindustry.world.blocks.defense.turrets.*;
import org.junit.jupiter.api.*;

import java.io.*;
import java.util.*;

import static mindustry.Vars.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The turret family's sync and save bytes against vanilla, for one block of each kind: {@code writeSync} (a block
 * snapshot's body, {@code writeAll}) and the save chunk byte for byte; a snapshot read with {@code readSync}, whose
 * quirk keeps the reader's aim and reload while everything else (health, ammo, the base's rotation byte) takes the
 * snapshot's; and a save chunk read back. Item turrets also read revision 1 (item ids as bytes), which no v160.7 writes.
 */
class TurretSyncOracleTest{
    /** One block per turret kind: item, power, liquid, laser, continuous, continuous liquid. */
    static final List<String> BLOCKS = List.of("salvo", "lancer", "wave", "meltdown", "lustre", "sublimate");

    @BeforeAll
    static void boot(){
        Vanilla.boot();
    }

    @Test
    void syncAndSaveBytesAreVanillas(){
        for(String block : BLOCKS){
            try(var run = new TurretRun(64)){
                run.place(block, 10, 10, Team.sharded);
                run.place(block, 30, 30, Team.sharded);
                fill(run, block, 10, 10, 37.5f, 7.25f, 140f, 0);
                fill(run, block, 30, 30, -12.75f, 3.5f, 90f, 1);
                var ids = ContentHeader.of(TurretRun.EPOCH).mapping(TurretRun.EPOCH);
                var vanillaA = (Turret.TurretBuild)world.build(10, 10);
                var vanillaB = (Turret.TurretBuild)world.build(30, 30);
                var shardA = (TurretBuild)run.shard.buildings().at(10, 10);
                var shardB = (TurretBuild)run.shard.buildings().at(30, 30);

                // writeSync: a block snapshot's body
                byte[] snapshot = vanillaSync(vanillaA);
                assertEquals(TurretRun.hex(snapshot), TurretRun.hex(shardSync(shardA, ids)), block + ": writeSync");
                assertEquals(TurretRun.hex(TurretRun.vanillaChunk(vanillaA)), TurretRun.hex(TurretRun.CODECS.chunk(shardA, TurretRun.EPOCH, ids)),
                    block + ": save chunk");

                // readSync: B reads A's snapshot, keeping its own aim and reload
                float bRotation = vanillaB.rotation, bReload = vanillaB.reloadCounter;
                vanillaB.readSync(reads(snapshot), vanillaB.version());
                TurretBodies.readSync(TurretRun.CODECS, shardB, new Reads(snapshot), vanillaB.version(), ids);
                assertEquals(bRotation, vanillaB.rotation, block + ": vanilla keeps its aim");
                assertEquals(bReload, vanillaB.reloadCounter, block + ": vanilla keeps its reload");
                assertEquals(run.vanillaTurret(30, 30), run.shardTurret(30, 30), block + ": the state after readSync");
                assertEquals(TurretRun.hex(vanillaSync(vanillaB)), TurretRun.hex(shardSync(shardB, ids)), block + ": writeSync after readSync");
                assertNotEquals(vanillaA.rotation, vanillaB.rotation, block + ": the quirk is exercised");

                // a save chunk read back
                byte[] chunk = TurretRun.vanillaChunk(vanillaA);
                var in = new Reads(chunk);
                int revision = in.ub();
                BuildingBase.read(shardB, BuildingBase.header(in), in, ids, pos -> null);
                TurretRun.CODECS.get(run.shard.kinds().key(shardB.block())).read(shardB, in, revision, ids);
                assertEquals(TurretRun.hex(chunk), TurretRun.hex(TurretRun.CODECS.chunk(shardB, TurretRun.EPOCH, ids)), block + ": a chunk read back");
            }
        }
    }

    /** {@code ItemTurretBuild.read} at revision 1: ids as bytes, an invalid ammo item dropped, amounts capped at maxAmmo. */
    @Test
    void itemTurretsReadRevisionOne(){
        try(var run = new TurretRun(32)){
            run.place("duo", 10, 10, Team.sharded);
            var vanilla = (Turret.TurretBuild)world.build(10, 10);
            var shard = (TurretBuild)run.shard.buildings().at(10, 10);
            var bytes = new ByteArrayOutputStream();
            var out = new arc.util.io.Writes(new DataOutputStream(bytes));
            out.f(5.5f);
            out.f(123.25f);
            out.b(3);
            out.b(content.item("graphite").id);
            out.s(12);
            out.b(content.item("lead").id);
            out.s(7);
            out.b(content.item("copper").id);
            out.s(500);
            byte[] body = bytes.toByteArray();
            vanilla.read(reads(body), (byte)1);
            var ids = ContentHeader.of(TurretRun.EPOCH).mapping(TurretRun.EPOCH);
            TurretRun.CODECS.get(run.shard.kinds().key(shard.block())).read(shard, new Reads(body), 1, ids);
            assertEquals(run.vanillaTurret(10, 10), run.shardTurret(10, 10), "duo read at revision 1");
            assertEquals(2, vanilla.ammo.size, "lead is no duo ammo");
            assertEquals(TurretRun.hex(TurretRun.vanillaChunk(vanilla)), TurretRun.hex(TurretRun.CODECS.chunk(shard, TurretRun.EPOCH, ids)),
                "the chunk written after");
        }
    }

    /** Gives the turret at (x, y) the same state on both sides: aim, reload, health and the kind's ammo. */
    static void fill(TurretRun run, String block, int x, int y, float rotation, float reload, float health, int variant){
        var v = (Turret.TurretBuild)world.build(x, y);
        var s = (TurretBuild)run.shard.buildings().at(x, y);
        v.rotation = s.turretRotation = rotation;
        v.reloadCounter = s.reloadCounter = reload;
        v.health = s.health = health;
        switch(block){
            case "salvo" -> {
                run.items(x, y, variant == 0 ? "graphite" : "copper", 3);
                run.items(x, y, variant == 0 ? "copper" : "thorium", 2);
            }
            case "wave" -> run.liquid(x, y, variant == 0 ? "water" : "slag", 6.5f);
            case "sublimate" -> run.liquid(x, y, variant == 0 ? "ozone" : "cyanogen", 3.25f);
            case "lustre", "meltdown" -> {
                if(v instanceof mindustry.world.blocks.defense.turrets.ContinuousTurret.ContinuousTurretBuild c){
                    c.lastLength = 42.5f + variant;
                    ((ContinuousTurretBuild)s).lastLength = 42.5f + variant;
                }
                if(block.equals("meltdown")) run.liquid(x, y, "water", 4f + variant);
            }
            default -> {}
        }
    }

    static byte[] vanillaSync(mindustry.gen.Building build){
        var bytes = new ByteArrayOutputStream();
        build.writeSync(new arc.util.io.Writes(new DataOutputStream(bytes)));
        return bytes.toByteArray();
    }

    /** {@code writeSync}: the base, then the kind's body, as W2.13's block snapshots write it. */
    static byte[] shardSync(Building building, ContentHeader.IdMapping ids){
        var out = new Writes();
        BuildingBase.write(building, out, ids);
        TurretRun.CODECS.get(building.world().kinds().key(building.block())).write(building, out, ids);
        return out.toByteArray();
    }

    static arc.util.io.Reads reads(byte[] bytes){
        return new arc.util.io.Reads(new DataInputStream(new ByteArrayInputStream(bytes)));
    }
}
