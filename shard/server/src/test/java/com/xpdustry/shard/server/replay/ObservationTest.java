package com.xpdustry.shard.server.replay;

import com.xpdustry.shard.content.*;
import com.xpdustry.shard.compat.v160.entity.*;
import com.xpdustry.shard.compat.v160.msav.*;
import com.xpdustry.shard.compat.v160.sync.*;
import com.xpdustry.shard.compat.v160.world.*;
import com.xpdustry.shard.logic.*;
import com.xpdustry.shard.server.host.*;
import com.xpdustry.shard.server.replay.view.*;
import com.xpdustry.shard.sim.ai.*;
import com.xpdustry.shard.sim.blocks.logic.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.rules.*;
import com.xpdustry.shard.sim.team.*;
import com.xpdustry.shard.sim.fire.*;
import com.xpdustry.shard.sim.puddle.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.world.*;
import org.junit.jupiter.params.*;
import org.junit.jupiter.params.provider.*;

import java.util.*;
import java.util.function.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Observation is pure: what the server reads from a live world, outside its inputs, must not change it. A replay of
 * the inputs alone never reads it so, and would diverge from then on. On each of vanilla's default maps, with
 * processors running (waiting, busy, controlling a unit, stopped), a building of every kind a codec writes, both teams'
 * units fighting and waves coming, every way the server reads a live world on its tick thread is done once to one of two identical
 * worlds. Its hash must stay as it was, and the next ticks must hash as the twin's, which no one read.
 */
class ObservationTest{
    static final BuildingCodecs CODECS = BuildingCodecs.builtIn();
    /** Ticks before the first reading, and after each. */
    static final int WARMUP = 300, AFTER = 60;
    /**
     * Programs that wait (parking), run every tick, control a unit between waits (a mega, or an elude where the rules'
     * environment has no megas), and stop (parked for good, so last).
     */
    static final List<String> PROGRAMS = List.of("op add i i 1\nwait 0.3\n", "op add i i 1\nwait 0.7\n", "set t @tick\nop add n n 1\nprint n\n",
        "ubind @mega\njump 3 notEqual @unit null\nubind @elude\nucontrol move @thisx @thisy 0 0 0\nsensor h @unit @health\nwait 0.4\n",
        "op add i i 1\nstop\n");

    /** A way the server reads a live world. */
    record Reading(String name, Consumer<MapLoader.Loaded> read){
        @Override
        public String toString(){
            return name;
        }
    }

    static final List<Reading> READINGS = List.of(
        // WorldSends.capture, encoded off the tick thread; a save is made of the same capture
        new Reading("a join's world stream and a save", loaded -> {
            World world = loaded.world();
            WorldStreamSource source = new WorldProjection(LiveReplayTest.VANILLA, CODECS).capture(world, loaded.state(), PlayerState.fresh(-1, "joining", 1));
            WorldStreamWriter.write(source);
            int team = world.part(WorldRules.class).rules.defaultTeam;
            MsavWriter.write(MsavWriter.meta(source, loaded.state().tags, team, MsavWriter.noCores(world, team), 0L), source);
        }),
        // WorldSync.endTick's block snapshots, and requestBlockSnapshot's answer for each building
        new Reading("block snapshots", loaded -> {
            var blocks = new BlockSnapshots(LiveReplayTest.VANILLA, CODECS);
            blocks.write(loaded.world());
            loaded.world().buildings().each(building -> {
                if(!(building instanceof PassthroughBuilding)) blocks.one(building);
            });
        }),
        // WorldSync.snapshots: the state, then the entities, for everyone and for each team under fog; health updates
        new Reading("state, entity and health snapshots", loaded -> {
            World world = loaded.world();
            WorldWireContext wire = WorldWireContext.capture(world);
            GameState.of(world, loaded.state(), GameState.TPS, 0).packet();
            var entities = new EntitySnapshots(new EntityProjector(LiveReplayTest.VANILLA, CODECS));
            entities.all(world, wire);
            for(int team = 0; team < 256; team++) entities.team(world, team, wire);
            var health = new HealthUpdates();
            world.buildings().each(building -> health.changed(world, world.tiles().pos(building.tileX(), building.tileY())));
            health.drain(world);
        }),
        // ManagedWorld: a fork with the inbox as it is, captured and encoded off the tick thread; a fresh world directly
        new Reading("a checkpoint", loaded -> {
            World world = loaded.world();
            World fork = world.fork();
            fork.inbox().restore(world.inbox().snapshot());
            Checkpoint.capture(fork).encode(Compression.NONE);
            Checkpoint.capture(world).encode(Compression.NONE);
        }),
        // MatchRecorder: a keyframe of the view, as a recording's first
        new Reading("a replay view keyframe", loaded -> {
            World world = loaded.world();
            var view = new ShardView(world, "observed", "observation", "survival");
            view.accept(List.of(), world.ticks());
            var writer = new ViewWriter(view, ViewWriter.Options.DEFAULT);
            writer.sample();
            writer.finish();
        })
    );

    static List<String> maps(){
        return MapCatalog.DEFAULT_MAPS;
    }

    @ParameterizedTest
    @MethodSource("maps")
    void readingALiveWorldChangesNothing(String map) throws Exception{
        MapLoader.Loaded live = loaded(map), twin = loaded(map);
        World world = live.world(), other = twin.world();
        // the same steps on identical worlds: the same buildings and units
        int[] processors = stage(world);
        assertArrayEquals(processors, stage(other));
        for(int t = 0; t < WARMUP; t++){
            world.tick();
            other.tick();
        }
        assertSameState(world, other, map + " after " + WARMUP + " ticks");
        int waiting = 0, controlled = 0;
        for(Reading reading : READINGS){
            WorldHash before = world.hash();
            assertEquals(before, world.hash(), map + ": hashing changes the world");
            // or the reading proves nothing about parked processors
            assertTrue(parked(world, processors[PROGRAMS.size() - 1]), map + ": the stopped processor is parked");
            if(parked(world, processors[0]) || parked(world, processors[1])) waiting++;
            for(int i = 0; i < world.units().size(); i++) if(world.units().get(i).controller instanceof LogicAi) controlled++;
            reading.read().accept(live);
            WorldHash after = world.hash();
            assertEquals(before, after, () -> map + ": " + reading + " changed the world: " + before.firstDifference(after).orElse("?"));
            for(int t = 1; t <= AFTER; t++){
                world.tick();
                other.tick();
                if(t == 1 || t % 15 == 0) assertSameState(world, other, map + ", " + t + " ticks after " + reading);
            }
        }
        assertTrue(waiting > 0, map + ": no reading came while a waiting processor was parked");
        assertTrue(controlled > 0, map + ": no reading came while a processor controlled a unit");
    }

    static MapLoader.Loaded loaded(String map) throws Exception{
        return LiveReplayTest.loaded("maps/default/" + map + ".msav", 240);
    }

    /**
     * Around the defending team's core, nearest first: the processors, one building of every block a codec writes (but
     * cores), item sources feeding their neighbours, both teams' units of the types the rules' environment supports (a
     * builder with a plan, so a construction site), a fire and a puddle. Placements that fail are left out.
     *
     * @return the processors' tiles, in {@link #PROGRAMS}' order
     */
    static int[] stage(World world){
        ContentEpoch content = world.content();
        Rules rules = world.part(WorldRules.class).rules;
        int team = rules.defaultTeam;
        var core = Teams.of(world).core(team);
        int cx = core == null ? world.tiles().width() / 2 : core.tileX(), cy = core == null ? world.tiles().height() / 2 : core.tileY();
        int[] processors = new int[PROGRAMS.size()];
        for(int i = 0; i < processors.length; i++){
            // away from the enemies and the fire, which come from the other side
            int[] at = near(world, cx - 12, cy + 12, 1);
            var processor = (LogicBlock)world.buildings().setBlock(at[0], at[1], content.block("micro-processor"), team, 0, Cause.SERVER);
            processor.configureAny(LogicConfig.compress(PROGRAMS.get(i), List.of()));
            processors[i] = world.tiles().pos(at[0], at[1]);
        }
        int copper = content.item("copper").id();
        for(int id = 0; id < content.count(ContentType.BLOCK); id++){
            BlockDef block = content.block(id);
            if(!block.hasBuilding() || block.name().startsWith("core-") || world.kinds().passesThrough(block) || CODECS.get(world.kinds().key(block)) == null) continue;
            int[] at = near(world, cx, cy, block.size());
            if(at == null) continue;
            try{
                Building building = world.buildings().setBlock(at[0], at[1], block, team, 0, Cause.SERVER);
                if(building instanceof com.xpdustry.shard.sim.blocks.sandbox.ItemSource) building.configureAny(ContentRef.item(copper));
            }catch(RuntimeException ignored){
                // a block the server can't place this way
            }
        }
        float x = cx * 8f, y = cy * 8f;
        for(String type : List.of("dagger", "flare", "mono", "mega", "stell", "elude", "merui")) spawn(world, type, team, x + 24f, y + 24f);
        Unit poly = spawn(world, "poly", team, x - 24f, y + 24f);
        int[] site = near(world, cx, cy, 1);
        if(poly != null && site != null) poly.plans.addLast(BuildPlan.place(site[0], site[1], 0, content.block("conveyor"), null));
        for(String type : List.of("dagger", "dagger", "flare", "stell", "elude")) spawn(world, type, rules.waveTeam, x + 96f, y - 96f);
        int[] hazard = near(world, cx + 6, cy - 6, 1);
        if(hazard != null){
            int pos = world.tiles().pos(hazard[0], hazard[1]);
            Fires.create(world, pos);
            Puddles.deposit(world, pos, content.liquid("water").id(), 40f);
        }
        return processors;
    }

    /** A unit of {@code type}, or null where the rules' environment doesn't support it: it would die on its first tick. */
    static Unit spawn(World world, String type, int team, float x, float y){
        UnitDef def = world.content().unit(type);
        if(!world.unitTable().params(def).supportsEnv(world.part(WorldRules.class).rules.env)) return null;
        return Unit.spawn(world, def, team, x, y, 0f);
    }

    static void assertSameState(World world, World twin, String when){
        WorldHash a = world.hash(), b = twin.hash();
        assertEquals(b, a, () -> when + ": " + b.firstDifference(a).orElse("?"));
    }

    static boolean parked(World world, int pos){
        return world.buildings().at(world.tiles().x(pos), world.tiles().y(pos)) instanceof LogicBlock processor && processor.processor().parked();
    }

    /** The origin nearest ({@code cx}, {@code cy}), ring by ring, where a block of {@code size} fits on empty, buildable tiles; or null. */
    static int[] near(World world, int cx, int cy, int size){
        int rings = Math.max(world.tiles().width(), world.tiles().height());
        for(int r = 0; r < rings; r++){
            for(int y = cy - r; y <= cy + r; y++){
                boolean edge = y == cy - r || y == cy + r;
                for(int x = cx - r; x <= cx + r; x += edge || r == 0 ? 1 : 2 * r){
                    if(fits(world, x, y, size)) return new int[]{x, y};
                }
            }
        }
        return null;
    }

    static boolean fits(World world, int x, int y, int size){
        var tiles = world.tiles();
        int offset = (size - 1) / 2;
        for(int ty = y - offset; ty < y - offset + size; ty++){
            for(int tx = x - offset; tx < x - offset + size; tx++){
                if(!tiles.inBounds(tx, ty)) return false;
                int pos = tiles.pos(tx, ty);
                if(tiles.block(pos) != 0 || tiles.solid(pos) || tiles.isDeep(pos) || world.buildings().at(tx, ty) != null) return false;
            }
        }
        return true;
    }
}
