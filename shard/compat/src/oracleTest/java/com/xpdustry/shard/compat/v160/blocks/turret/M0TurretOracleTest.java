package com.xpdustry.shard.compat.v160.blocks.turret;

import com.xpdustry.shard.compat.v160.fixtures.*;
import com.xpdustry.shard.compat.v160.msav.*;
import com.xpdustry.shard.compat.v160.world.*;
import com.xpdustry.shard.oracle.*;
import com.xpdustry.shard.sim.blocks.turret.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.bullet.Bullets;
import com.xpdustry.shard.sim.rules.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.unit.Unit;
import com.xpdustry.shard.sim.world.*;
import com.xpdustry.shard.testkit.BundledMaps;
import mindustry.content.*;
import mindustry.entities.units.*;
import mindustry.game.*;
import mindustry.world.blocks.defense.turrets.*;
import org.junit.jupiter.api.*;

import java.util.*;

import static mindustry.Vars.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * M0's turret gate: fortress hosted for survival on both sides, an item source feeding copper down a conveyor line
 * into a duo between the enemy spawn and the core, and the full countdown to wave 1, whose daggers spawn through W2.11's
 * {@code WaveSpawner}. Until W2.17's ground AI lands, each dagger walks a straight line toward the duo once its
 * spawn's {@code unmoving} wears off (vanilla's controller is an {@code AIController}, so it draws its timers as
 * {@code GroundAI} would). The map's unported buildings (its mechanical drills, which draw every frame) are removed on
 * both sides first. Every frame from load to the last kill compares the random, the entity ids, {@code Time},
 * the duo, the conveyors' items, every unit and every bullet; the duo's save chunk at the end. Exact, no tolerance.
 */
class M0TurretOracleTest{
    static final BuildingCodecs CODECS = BuildingCodecs.builtIn();
    static final MsavReader READER = new MsavReader(Worlds.EPOCH);
    static final MapLoader LOADER = new MapLoader(Worlds.EPOCH, CODECS);

    @Test
    void aConveyorFedDuoKillsFortressWaveOne(){
        Vanilla.host("fortress");
        SaveFile save = READER.read(BundledMaps.read("fortress"));
        com.xpdustry.shard.sim.rules.Rules rules = LOADER.hostRules(save, com.xpdustry.shard.sim.rules.Gamemode.SURVIVAL);
        MapLoader.Loaded loaded = LOADER.loadMap(save, Worlds.config(0), rules);
        MapLoader.play(loaded);
        World shard = loaded.world();
        // the counters start where hosting left them; ids are compared as they are
        assertEquals(arc.math.Mathf.rand.seed0, shard.rand().getState(0), "random after hosting");
        assertEquals(TurretRun.lastId(), shard.ids().peek(), "next id after hosting");
        // Vanilla.reset zeroes Time.time but not Arc's raw clock, which the next frame copies back
        arc.util.Time.setInternalTime(shard.timers().time());

        // the map's unported buildings (fortress's mechanical drills, whose update draws every frame) would diverge: gone
        var unported = new ArrayList<int[]>();
        for(int pos = 0; pos < shard.tiles().size(); pos++){
            if(shard.buildings().at(pos) instanceof PassthroughBuilding b && b.tile() == pos) unported.add(new int[]{b.tileX(), b.tileY()});
        }
        for(int[] at : unported){
            world.tile(at[0], at[1]).remove();
            Vanilla.runPosted();
            shard.buildings().setBlock(at[0], at[1], Worlds.EPOCH.block("air"), 0, 0, Cause.SERVER);
        }
        System.out.println("fortress: removed " + unported.size() + " unported buildings");

        // a station per ground spawn: a clear straight walk out of the drop zone, and a duo fed by a conveyor beside its end
        var stations = new ArrayList<Station>();
        for(var spawn : spawner.getSpawns()) stations.add(station(shard, spawn.worldx(), spawn.worldy()));
        for(Station st : stations) System.out.println("fortress: " + st);

        var dagger = content.unit("dagger");
        var aiBefore = dagger.aiController;
        dagger.aiController = () -> new VanillaStraight(stations);
        var walking = new ArrayList<Unit>();
        int frame = 0, deaths = 0, spawned = 0;
        try{
            while(true){
                frame++;
                if(frame >= 20_000){
                    var duos = new StringJoiner("; ");
                    for(Station st : stations) duos.add(duo((Turret.TurretBuild)world.build(st.duoX, st.duoY)));
                    fail("wave 1 fought within 20,000 frames: wave " + state.wave + ", duos " + duos + ", units " + vanillaUnits());
                }
                Vanilla.frame();
                shard.tick();
                String at = "frame " + frame;
                sameRandom(shard, at);
                assertEquals(TurretRun.lastId(), shard.ids().peek(), at + ": the entity id counter");
                TurretRun.same(at + ": Time.time", arc.util.Time.time, shard.timers().time());
                assertEquals(state.wavetime, WaveStateView.wavetime(shard), at + ": wavetime");
                for(Station st : stations){
                    assertEquals(duo((Turret.TurretBuild)world.build(st.duoX, st.duoY)), duo((TurretBuild)shard.buildings().at(st.duoX, st.duoY)),
                        at + ": the duo at " + st.duoX + "," + st.duoY);
                    for(int x = st.duoX - 3; x < st.duoX; x++){
                        assertEquals(world.build(x, st.duoY).items.total(), shard.buildings().at(x, st.duoY).items.total(), at + ": conveyor " + x + " items");
                    }
                }
                assertEquals(vanillaUnits(), shardUnits(shard), at + ": units");
                assertEquals(vanillaBullets(), shardBullets(shard), at + ": bullets");
                // wave 1's daggers walk from the frame after they spawn, as the vanilla ones do
                for(int i = 0; i < shard.units().size(); i++){
                    Unit unit = shard.units().get(i);
                    if(unit.team == rules.waveTeam && !walking.contains(unit)){
                        unit.controller(new ShardStraight(stations));
                        walking.add(unit);
                        spawned++;
                    }
                }
                deaths = (int)walking.stream().filter(u -> !u.isAdded()).count();
                if(spawned > 0 && deaths == spawned && mindustry.gen.Groups.bullet.isEmpty()) break;
            }
            int shots = 0, ammo = 0;
            var current = ContentHeader.of(Worlds.EPOCH).mapping(Worlds.EPOCH);
            for(Station st : stations){
                var duo = (Turret.TurretBuild)world.build(st.duoX, st.duoY);
                shots += duo.totalShots;
                ammo += duo.totalAmmo;
                assertEquals(TurretRun.hex(TurretRun.vanillaChunk(duo)), TurretRun.hex(CODECS.chunk(shard.buildings().at(st.duoX, st.duoY), Worlds.EPOCH, current)),
                    "the duo's save chunk at " + st.duoX + "," + st.duoY);
            }
            System.out.println("fortress wave 1: " + spawned + " daggers killed by frame " + frame + " after " + shots + " shots");
            assertTrue(spawned >= 2, "wave 1 spawned its daggers: " + spawned);
            assertTrue(shots > 0, "the duos fired");
            assertTrue(shots > 15 || ammo > 0, "the conveyors kept the duos fed");
        }finally{
            dagger.aiController = aiBefore;
        }
    }

    /** Fails unless both generators agree, saying how many draws one side is ahead (up to 64). */
    static void sameRandom(World shard, String at){
        long v0 = arc.math.Mathf.rand.seed0, v1 = arc.math.Mathf.rand.seed1;
        if(v0 == shard.rand().getState(0) && v1 == shard.rand().getState(1)) return;
        var ours = new com.xpdustry.shard.common.math.Rand(shard.rand().getState(0), shard.rand().getState(1));
        for(int i = 1; i <= 64; i++){
            ours.nextLong();
            if(ours.seed0 == v0 && ours.seed1 == v1) fail(at + ": random state, Shard " + i + " draws behind vanilla");
        }
        var theirs = new com.xpdustry.shard.common.math.Rand(v0, v1);
        for(int i = 1; i <= 64; i++){
            theirs.nextLong();
            if(theirs.seed0 == shard.rand().getState(0) && theirs.seed1 == shard.rand().getState(1)) fail(at + ": random state, Shard " + i + " draws ahead of vanilla");
        }
        fail(at + ": random state differs by more than 64 draws");
    }

    static void place(World shard, String block, int x, int y, int rotation){
        world.tile(x, y).setBlock(content.block(block), state.rules.defaultTeam, rotation);
        Vanilla.runPosted();
        shard.buildings().setBlock(x, y, Worlds.EPOCH.block(block), state.rules.defaultTeam.id, rotation, Cause.SERVER);
        assertEquals(arc.math.Mathf.rand.seed0, shard.rand().getState(0), "random after placing " + block);
        assertEquals(TurretRun.lastId(), shard.ids().peek(), "next id after placing " + block);
    }

    /** A wave spawn's station: where its daggers walk to, and the duo beside it fed from the west. */
    record Station(float spawnX, float spawnY, float goalX, float goalY, int duoX, int duoY){
        /** The goal of the station whose spawn is nearest (x, y). */
        static float[] goalFor(List<Station> stations, float x, float y){
            Station best = stations.getFirst();
            for(Station st : stations){
                if(arc.math.Mathf.dst2(x, y, st.spawnX, st.spawnY) < arc.math.Mathf.dst2(x, y, best.spawnX, best.spawnY)) best = st;
            }
            return new float[]{best.goalX, best.goalY};
        }
    }

    /**
     * The station of the spawn at (sx, sy): the first of 16 directions whose straight walk to just past the drop zone
     * crosses only open ground, and a duo on free ground 5 tiles to one side of its end, out of the drop zone.
     */
    static Station station(World shard, float sx, float sy){
        float reach = state.rules.dropZoneRadius + 40f;
        for(int i = 0; i < 16; i++){
            float angle = i * 22.5f, gx = sx + arc.math.Angles.trnsx(angle, reach), gy = sy + arc.math.Angles.trnsy(angle, reach);
            if(!clear(sx, sy, gx, gy)) continue;
            for(float side : new float[]{90f, -90f}){
                float px = gx + arc.math.Angles.trnsx(angle + side, 40f), py = gy + arc.math.Angles.trnsy(angle + side, 40f);
                int[] site = site(px, py, sx, sy);
                if(site == null) continue;
                place(shard, "item-source", site[0] - 4, site[1], 0);
                for(int x = site[0] - 3; x < site[0]; x++) place(shard, "conveyor", x, site[1], 0);
                place(shard, "duo", site[0], site[1], 0);
                world.build(site[0] - 4, site[1]).configureAny(Items.copper);
                Vanilla.runPosted();
                shard.buildings().at(site[0] - 4, site[1]).configureAny(ContentRef.item(Worlds.EPOCH.item("copper").id()));
                return new Station(sx, sy, gx, gy, site[0], site[1]);
            }
        }
        throw new AssertionError("no station for the spawn at " + sx + "," + sy);
    }

    /** Whether a dagger can walk straight from (x0, y0) to (x1, y1): no solid or deep tile within a tile of the line. */
    static boolean clear(float x0, float y0, float x1, float y1){
        float len = arc.math.Mathf.dst(x0, y0, x1, y1);
        for(float d = 0; d <= len; d += 4f){
            float x = x0 + (x1 - x0) * d / len, y = y0 + (y1 - y0) * d / len;
            for(int ox = -1; ox <= 1; ox++){
                for(int oy = -1; oy <= 1; oy++){
                    var tile = world.tile(Math.round(x / 8f) + ox, Math.round(y / 8f) + oy);
                    if(tile == null || tile.solid() || tile.floor().isDeep()) return false;
                }
            }
        }
        return true;
    }

    /** The nearest tile to (wx, wy) whose row of five tiles to the west is open buildable ground, out of the drop zone; or null. */
    static int[] site(float wx, float wy, float sx, float sy){
        int cx = Math.round(wx / 8f), cy = Math.round(wy / 8f);
        for(int r = 0; r < 30; r++){
            for(int ox = -r; ox <= r; ox++){
                for(int oy = -r; oy <= r; oy++){
                    if(Math.max(Math.abs(ox), Math.abs(oy)) != r) continue;
                    int x = cx + ox, y = cy + oy;
                    boolean free = true;
                    for(int i = -4; i <= 0 && free; i++){
                        var tile = world.tile(x + i, y);
                        free = tile != null && tile.block() == Blocks.air && !tile.floor().isDeep() && !tile.floor().solid
                            && tile.floor().placeableOn && tile.overlay() == Blocks.air;
                    }
                    // out of the drop zone, with a margin for the conveyor line
                    if(free && arc.math.Mathf.dst(x * 8f, y * 8f, sx, sy) > state.rules.dropZoneRadius + 48f) return new int[]{x, y};
                }
            }
        }
        return null;
    }

    static String duo(Turret.TurretBuild b){
        var ammo = new StringBuilder();
        for(Turret.AmmoEntry entry : b.ammo) ammo.append(entry.amount).append(' ');
        String target = b.target instanceof mindustry.gen.Unit u ? "unit " + u.id : b.target == null ? "-" : b.target.toString();
        return "rot " + b.rotation + " reload " + b.reloadCounter + " warmup " + b.shootWarmup + " ammo [" + ammo + "] total "
            + b.totalAmmo + " target " + target + " aim (" + b.targetPos.x + ", " + b.targetPos.y + ") shots " + b.totalShots
            + " barrel " + b.barrelCounter + " eff " + b.efficiency;
    }

    static String duo(TurretBuild b){
        var ammo = new StringBuilder();
        for(int i = 0; i < b.ammoSize; i++) ammo.append(b.ammoAmounts[i]).append(' ');
        String target = b.target instanceof Unit u ? "unit " + u.id() : b.target == null ? "-" : b.target.toString();
        return "rot " + b.turretRotation + " reload " + b.reloadCounter + " warmup " + b.shootWarmup + " ammo [" + ammo + "] total "
            + b.totalAmmo + " target " + target + " aim (" + b.targetX + ", " + b.targetY + ") shots " + b.totalShots
            + " barrel " + b.barrelCounter + " eff " + b.efficiency;
    }

    static List<String> vanillaUnits(){
        var out = new ArrayList<String>();
        for(mindustry.gen.Unit u : mindustry.gen.Groups.unit){
            out.add(u.id + " " + u.type.name + " team " + u.team.id + " (" + u.x + ", " + u.y + ") rot " + u.rotation + " health " + u.health);
        }
        return out;
    }

    static List<String> shardUnits(World shard){
        var out = new ArrayList<String>();
        for(int i = 0; i < shard.units().size(); i++){
            Unit u = shard.units().get(i);
            out.add(u.id() + " " + u.type().name() + " team " + u.team + " (" + u.x + ", " + u.y + ") rot " + u.rotation + " health " + u.health);
        }
        return out;
    }

    static List<String> vanillaBullets(){
        var out = new ArrayList<String>();
        for(mindustry.gen.Bullet b : mindustry.gen.Groups.bullet){
            out.add(b.id + " t" + b.type.id + " (" + b.x + ", " + b.y + ") v(" + b.vel.x + ", " + b.vel.y + ") time " + b.time + " dmg " + b.damage);
        }
        return out;
    }

    static List<String> shardBullets(World shard){
        var out = new ArrayList<String>();
        Bullets t = shard.bullets();
        for(int r = 0; r < t.size(); r++){
            out.add(t.id[r] + " t" + t.type[r] + " (" + t.x[r] + ", " + t.y[r] + ") v(" + t.velX[r] + ", " + t.velY[r] + ") time " + t.time[r] + " dmg " + t.damage[r]);
        }
        return out;
    }

    /** {@code WaveState.of(world).wavetime}. */
    static final class WaveStateView{
        static float wavetime(World world){
            return com.xpdustry.shard.sim.waves.WaveState.of(world).wavetime;
        }
    }

    /**
     * Walks straight toward a point at the unit's speed once {@code unmoving} wears off. An {@code AIController}, so
     * making it draws the two timers a dagger's {@code GroundAI} would.
     */
    static final class VanillaStraight extends AIController{
        final List<Station> stations;
        float tx = Float.NaN, ty;

        VanillaStraight(List<Station> stations){
            this.stations = stations;
        }

        @Override
        public void updateUnit(){
            if(Float.isNaN(tx)){
                float[] goal = Station.goalFor(stations, unit.x, unit.y);
                tx = goal[0];
                ty = goal[1];
            }
            if(unit.hasEffect(StatusEffects.unmoving) || unit.within(tx, ty, 2f)) return;
            unit.movePref(new arc.math.geom.Vec2(tx - unit.x, ty - unit.y).limit(unit.speed()));
            unit.lookAt(tx, ty);
        }
    }

    static final class ShardStraight implements com.xpdustry.shard.sim.unit.UnitController{
        final List<Station> stations;
        float tx = Float.NaN, ty;

        ShardStraight(List<Station> stations){
            this.stations = stations;
        }

        @Override
        public void updateUnit(Unit unit){
            if(Float.isNaN(tx)){
                float[] goal = Station.goalFor(stations, unit.x, unit.y);
                tx = goal[0];
                ty = goal[1];
            }
            if(unit.statuses.hasEffect(StatusTable.of(unit.world()).id("unmoving"))
                || com.xpdustry.shard.common.math.Mathf.within(unit.x, unit.y, tx, ty, 2f)) return;
            unit.movePref(new com.xpdustry.shard.common.geom.Vec2(tx - unit.x, ty - unit.y).limit(unit.speed()));
            unit.lookAt(tx, ty);
        }

        @Override
        public com.xpdustry.shard.sim.unit.UnitController copy(CopyContext context){
            return this;
        }

        @Override
        public void hash(Hasher hasher){
        }
    }
}
