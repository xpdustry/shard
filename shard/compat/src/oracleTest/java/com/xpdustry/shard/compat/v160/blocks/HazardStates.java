package com.xpdustry.shard.compat.v160.blocks;

import arc.math.*;
import com.xpdustry.shard.compat.v160.entity.*;
import com.xpdustry.shard.compat.v160.world.*;
import com.xpdustry.shard.content.epoch.*;
import com.xpdustry.shard.oracle.*;
import com.xpdustry.shard.sim.entity.*;
import com.xpdustry.shard.sim.world.*;
import com.xpdustry.shard.wire.v160_7.*;
import mindustry.entities.*;
import mindustry.gen.*;

import java.lang.reflect.*;
import java.util.*;

import static mindustry.Vars.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The fire and puddle oracles' comparisons, for a vanilla world and a Shard world of the same map: every fire and
 * puddle in {@code Groups.all} order with all its server state, {@code Groups.sync}'s order, the tile maps, building
 * health by tile, live bullets, and each fire's and puddle's {@code writeSync} and save body byte for byte.
 */
public final class HazardStates{
    static final Epoch EPOCH = Epoch.vanilla();
    static final EntityProjector PROJECTOR = new EntityProjector(EPOCH, BuildingCodecs.builtIn());

    private HazardStates(){
    }

    /** Vanilla's fires and puddles, group orders and tile maps. */
    public static List<String> vanilla(){
        var out = new ArrayList<String>();
        for(Entityc entity : Groups.all){
            switch(entity){
                case Fire f -> out.add("fire " + f.id + " at " + tile(f.tile) + " lifetime " + f.lifetime + " time " + f.time + " (" + f.x + ", " + f.y
                    + ") timers " + get(f, "damageTimer") + " " + get(f, "spreadTimer") + " " + get(f, "fireballTimer") + " puddle "
                    + get(f, "puddleFlammability"));
                case Puddle p -> out.add("puddle " + p.id + " at " + tile(p.tile) + " " + (p.liquid == null ? "none" : p.liquid.name) + " amount "
                    + p.amount + " accepting " + get(p, "accepting") + " updateTime " + get(p, "updateTime") + " (" + p.x + ", " + p.y + ")");
                default -> {
                }
            }
        }
        var sync = new StringJoiner(" ");
        for(Syncc entity : Groups.sync) sync.add(String.valueOf(entity.id()));
        out.add("sync " + sync);
        var map = new StringJoiner(" ");
        for(int i = 0; i < world.width() * world.height(); i++){
            Fire f = world.tiles.getFire(i);
            Puddle p = world.tiles.getPuddle(i);
            if(f != null) map.add("f" + i + "=" + f.id);
            if(p != null) map.add("p" + i + "=" + p.id);
        }
        out.add("map " + map);
        return out;
    }

    /** Shard's, in the same terms. */
    public static List<String> shard(World shard){
        var out = new ArrayList<String>();
        EntityGroups groups = EntityGroups.of(shard);
        var tiles = shard.tiles();
        for(int i = 0; i < groups.all().size(); i++){
            switch(groups.all().get(i)){
                case com.xpdustry.shard.sim.fire.Fire f -> out.add("fire " + f.id() + " at " + tile(shard, f.tile) + " lifetime " + f.lifetime + " time "
                    + f.time + " (" + f.x + ", " + f.y + ") timers " + f.damageTimer + " " + f.spreadTimer + " " + f.fireballTimer + " puddle "
                    + f.puddleFlammability);
                case com.xpdustry.shard.sim.puddle.Puddle p -> out.add("puddle " + p.id() + " at " + tile(shard, p.tile) + " "
                    + (p.liquid < 0 ? "none" : EPOCH.liquid(p.liquid).name()) + " amount " + p.amount + " accepting " + p.accepting
                    + " updateTime " + p.updateTime + " (" + p.x + ", " + p.y + ")");
                default -> {
                }
            }
        }
        var sync = new StringJoiner(" ");
        for(int i = 0; i < groups.sync().size(); i++) sync.add(String.valueOf(groups.sync().get(i).id()));
        out.add("sync " + sync);
        var map = new StringJoiner(" ");
        var fires = com.xpdustry.shard.sim.fire.Fires.of(shard);
        var puddles = com.xpdustry.shard.sim.puddle.Puddles.of(shard);
        for(int i = 0; i < tiles.size(); i++){
            var f = fires.get(i);
            var p = puddles.get(i);
            if(f != null) map.add("f" + i + "=" + f.id());
            if(p != null) map.add("p" + i + "=" + p.id());
        }
        out.add("map " + map);
        return out;
    }

    /** The random state and the next entity id. */
    static String vanillaCounters(){
        return "rand " + Mathf.rand.seed0 + " " + Mathf.rand.seed1 + " next id " + lastId();
    }

    static String shardCounters(World shard){
        return "rand " + shard.rand().getState(0) + " " + shard.rand().getState(1) + " next id " + shard.ids().peek();
    }

    /** Every building's health, by its centre tile. */
    static List<String> vanillaHealth(){
        var out = new ArrayList<String>();
        for(var tile : world.tiles) if(tile.build != null && tile.isCenter()) out.add(tile.build.block.name + " (" + tile.x + ", " + tile.y + ") " + tile.build.health);
        return out;
    }

    static List<String> shardHealth(World shard){
        var out = new ArrayList<String>();
        var tiles = shard.tiles();
        for(int pos = 0; pos < tiles.size(); pos++){
            var build = shard.buildings().at(pos);
            if(build != null && build.tile() == pos) out.add(build.block().name() + " (" + tiles.x(pos) + ", " + tiles.y(pos) + ") " + build.health);
        }
        return out;
    }

    /** Live bullets: id, type, team, position, velocity, time, damage. */
    static List<String> vanillaBullets(){
        return GeneratorExplosionOracleTest.vanillaBullets();
    }

    static List<String> shardBullets(World shard){
        var out = new ArrayList<String>();
        var t = shard.bullets();
        for(int r = 0; r < t.size(); r++){
            out.add(GeneratorExplosionOracleTest.bullet(t.id[r], t.type[r], t.team[r], t.x[r], t.y[r], t.velX[r], t.velY[r], t.time[r], t.lifetime[r], t.damage[r]));
        }
        return out;
    }

    /** Fails with {@code at} unless the two worlds' fires, puddles, counters, health and bullets are equal. */
    static void same(String at, World shard){
        assertEquals(vanillaCounters(), shardCounters(shard), at + ": counters");
        assertEquals(String.join("\n", vanilla()), String.join("\n", shard(shard)), at + ": fires and puddles");
        assertEquals(String.join("\n", vanillaHealth()), String.join("\n", shardHealth(shard)), at + ": health");
        assertEquals(String.join("\n", vanillaBullets()), String.join("\n", shardBullets(shard)), at + ": bullets");
    }

    /**
     * Each fire's and puddle's {@code writeSync} and save body ({@code write}: revision, then fields), vanilla's against
     * the Shard entity at the same place in {@code Groups.all}; returns how many were compared.
     */
    static int sameBytes(String at, World shard){
        EntityGroups groups = EntityGroups.of(shard);
        var vanilla = new ArrayList<Entityc>();
        for(Entityc entity : Groups.all) if(entity instanceof Fire || entity instanceof Puddle) vanilla.add(entity);
        var ours = new ArrayList<Entity>();
        for(int i = 0; i < groups.all().size(); i++){
            Entity entity = groups.all().get(i);
            if(entity instanceof com.xpdustry.shard.sim.fire.Fire || entity instanceof com.xpdustry.shard.sim.puddle.Puddle) ours.add(entity);
        }
        assertEquals(vanilla.size(), ours.size(), at + ": fires and puddles");
        for(int i = 0; i < vanilla.size(); i++){
            Entityc expected = vanilla.get(i);
            Entity actual = ours.get(i);
            int classId = EntityProjector.classId(actual);
            assertEquals(expected.classId(), classId, at + ": class of " + actual);
            var projection = PROJECTOR.project(actual);
            byte[] sync = Golden.bytes(((Syncc)expected)::writeSync);
            var syncOut = new com.xpdustry.shard.common.io.Writes();
            EntityClasses.writeSync(syncOut, classId, projection, null);
            assertEquals(Hex.of(sync), Hex.of(syncOut.toByteArray()), at + ": writeSync of " + actual);
            byte[] save = Golden.bytes(expected::write);
            var saveOut = new com.xpdustry.shard.common.io.Writes();
            EntityClasses.writeSave(saveOut, classId, projection, null);
            assertEquals(Hex.of(save), Hex.of(saveOut.toByteArray()), at + ": save body of " + actual);
        }
        return vanilla.size();
    }

    static String tile(mindustry.world.Tile tile){
        return tile == null ? "none" : tile.x + "," + tile.y;
    }

    static String tile(World shard, int pos){
        return pos < 0 ? "none" : shard.tiles().x(pos) + "," + shard.tiles().y(pos);
    }

    static Object get(Object target, String name){
        try{
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(target);
        }catch(ReflectiveOperationException e){
            throw new IllegalStateException(e);
        }
    }

    static int lastId(){
        try{
            Field field = EntityGroup.class.getDeclaredField("lastId");
            field.setAccessible(true);
            return field.getInt(null);
        }catch(ReflectiveOperationException e){
            throw new IllegalStateException(e);
        }
    }

    static void lastId(int value){
        try{
            Field field = EntityGroup.class.getDeclaredField("lastId");
            field.setAccessible(true);
            field.setInt(null, value);
        }catch(ReflectiveOperationException e){
            throw new IllegalStateException(e);
        }
    }
}
