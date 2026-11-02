package com.xpdustry.shard.compat.v160.blocks;

import com.xpdustry.shard.content.*;
import com.xpdustry.shard.content.epoch.*;
import com.xpdustry.shard.oracle.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.building.Building;
import com.xpdustry.shard.sim.puddle.Puddles;
import com.xpdustry.shard.sim.world.*;
import mindustry.game.*;
import mindustry.gen.*;
import org.junit.jupiter.api.*;

import java.util.*;

import static mindustry.Vars.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Gate (WP61): puddles against vanilla, frame by frame for {@value #FRAMES} frames. Two conduits leak water and oil onto
 * open ground, where the puddles grow, overflow to their neighbours and evaporate once the conduits run dry; oil also
 * leaks onto deep water, where it floats. Slag poured on the oil sets it alight (vanilla's flammable-and-hot reaction),
 * and the burning puddle feeds the fire; cryofluid poured on slag boils off as steam; wave turret bullets of water and
 * slag land and leave puddles, and water ones cool the fires they cross. Every frame compares the random and ids, every
 * fire and puddle with its state in {@code Groups.all} order, {@code Groups.sync}, the tile maps, building health,
 * bullets and the conduits' liquids; every {@value #BYTES} frames, each puddle's and fire's {@code writeSync} and save
 * body byte for byte.
 *
 * <p>The conduits are Shard's own ({@code sim.blocks.liquid.Conduit}).
 */
class PuddleOracleTest{
    static final int SIZE = 40, FRAMES = 600, BYTES = 20;
    static final Epoch EPOCH = HazardStates.EPOCH;
    static final KindRegistry KINDS = KindRegistry.builtIn().withPassthrough();

    @BeforeAll
    static void boot(){
        Vanilla.boot();
    }

    @Test
    void conduitsLeakBulletsSplashAndOilIgnites(){
        Sim sim = Sim.empty(SIZE, SIZE);
        World shard = new World(WorldConfig.of(EPOCH, SIZE, SIZE, 0).withKinds(KINDS));
        shard.buildings().beginLoad();
        for(int pos = 0; pos < shard.tiles().size(); pos++) shard.tiles().floor(pos, EPOCH.block("stone").id());
        shard.buildings().endLoad(false);
        HazardPools.empty();
        // deep water east of x = 30
        for(int x = 30; x < SIZE; x++){
            for(int y = 0; y < SIZE; y++){
                world.tile(x, y).setFloor(content.block("deep-water").asFloor());
                shard.buildings().setFloor(x, y, EPOCH.block("deep-water"), Cause.SERVER);
            }
        }
        place(sim, shard, "conduit", 5, 10, 0);
        place(sim, shard, "conduit", 6, 10, 0);
        place(sim, shard, "conduit", 5, 20, 0);
        place(sim, shard, "conduit", 6, 20, 0);
        place(sim, shard, "conduit", 29, 30, 0);
        place(sim, shard, "copper-wall", 9, 11, 0);
        HazardStates.same("as built", shard);

        var burnt = new TreeSet<Integer>();
        int maxPuddles = 0, onWater = 0, bytes = 0, liquidHits = 0;
        float maxOil = 0f;
        for(int frame = 1; frame <= FRAMES; frame++){
            String at = "frame " + frame;
            if(frame <= 240) fill(sim, shard, 5, 10, "water", 8f);
            if(frame <= 200) fill(sim, shard, 5, 20, "oil", 8f);
            if(frame <= 150) fill(sim, shard, 29, 30, "oil", 6f);
            if(frame == 60){
                deposit(shard, 20, 5, "slag", 40f);
                deposit(shard, 20, 5, "slag", 40f);
            }
            if(frame == 80) deposit(shard, 20, 5, "cryofluid", 20f);
            if(frame == 210 || frame == 230) deposit(shard, 8, 20, "slag", 25f);
            if(frame == 250){
                shoot(shard, "wave/ammoTypes.slag", 8 * 8f, 26 * 8f, 270f);
                shoot(shard, "wave/ammoTypes.water", 9 * 8f, 4 * 8f, 90f);
            }
            if(frame == 330){
                for(int i = 0; i < 6; i++) shoot(shard, "wave/ammoTypes.water", 9 * 8f + i, 14 * 8f, 270f);
            }
            HazardStates.same(at + " before", shard);
            sim.step(1);
            shard.tick();
            HazardStates.same(at, shard);
            assertEquals(conduits(true, shard), conduits(false, shard), at + ": conduits");
            if(frame % BYTES == 0) bytes += HazardStates.sameBytes(at, shard);
            int puddles = 0;
            for(var entity : Groups.all){
                if(entity instanceof Puddle p){
                    puddles++;
                    if(p.tile.floor().isLiquid) onWater++;
                    if(p.liquid == mindustry.content.Liquids.oil) maxOil = Math.max(maxOil, p.amount);
                }
                if(entity instanceof Fire f) burnt.add(f.tile.pos());
            }
            for(var b : Groups.bullet) if(b.type instanceof mindustry.entities.bullet.LiquidBulletType) liquidHits++;
            maxPuddles = Math.max(maxPuddles, puddles);
        }
        System.out.println("puddles: at most " + maxPuddles + ", oil up to " + maxOil + ", " + onWater + " puddle-frames on water, "
            + burnt.size() + " tiles burnt, " + liquidHits + " liquid bullet-frames, " + bytes + " bodies compared");
        assertTrue(maxPuddles > 8, "the puddles overflowed to their neighbours: " + maxPuddles);
        assertTrue(maxOil > 46f, "the oil puddle filled up: " + maxOil);
        assertTrue(onWater > 0, "oil floated on deep water");
        assertTrue(burnt.size() > 2, "the oil caught fire and the fire spread: " + burnt);
        assertTrue(liquidHits > 0, "liquid bullets flew");
        assertTrue(bytes > 50, "puddles were compared byte for byte: " + bytes);
        int left = 0;
        for(var entity : Groups.all) if(entity instanceof Puddle) left++;
        assertTrue(left < maxPuddles, "puddles evaporated once the conduits ran dry");
    }

    static void place(Sim sim, World shard, String block, int x, int y, int rotation){
        sim.place(content.block(block), x, y, rotation, Team.sharded);
        shard.buildings().setBlock(x, y, EPOCH.block(block), Team.sharded.id, rotation, Cause.SERVER);
    }

    /** Tops a conduit up with a liquid, on both sides. */
    static void fill(Sim sim, World shard, int x, int y, String liquid, float amount){
        var vanilla = sim.build(x, y);
        vanilla.liquids.add(content.liquid(liquid), amount);
        vanilla.noSleep();
        Building ours = shard.buildings().at(x, y);
        ours.liquids.add(EPOCH.liquid(liquid).id(), amount);
        ours.noSleep();
    }

    /** {@code Puddles.deposit(tile, liquid, amount)} on both sides. */
    static void deposit(World shard, int x, int y, String liquid, float amount){
        mindustry.entities.Puddles.deposit(world.tile(x, y), content.liquid(liquid), amount);
        Puddles.deposit(shard, shard.tiles().pos(x, y), EPOCH.liquid(liquid).id(), amount);
    }

    /** A turret's liquid bullet, ownerless, on both sides. */
    static void shoot(World shard, String bullet, float x, float y, float angle){
        mindustry.entities.bullet.BulletType vanilla = null;
        for(var type : content.bullets()) if(type.id == EPOCH.bullet(bullet).id()) vanilla = type;
        vanilla.create(null, Team.sharded, x, y, angle);
        shard.bullets().types(shard).get(EPOCH.bullet(bullet)).create(shard, null, Team.sharded.id, x, y, angle);
    }

    static String conduits(boolean vanilla, World shard){
        var out = new StringJoiner("\n");
        for(var tile : world.tiles){
            if(tile.build == null || tile.block() != mindustry.content.Blocks.conduit) continue;
            if(vanilla){
                out.add(tile.x + "," + tile.y + " " + tile.build.liquids.current().name + " " + tile.build.liquids.currentAmount());
            }else{
                Building ours = shard.buildings().at(tile.x, tile.y);
                out.add(tile.x + "," + tile.y + " " + EPOCH.liquid(ours.liquids.current()).name() + " " + ours.liquids.currentAmount());
            }
        }
        return out.toString();
    }
}
