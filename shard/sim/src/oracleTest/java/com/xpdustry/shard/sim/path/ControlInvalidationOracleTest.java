package com.xpdustry.shard.sim.path;

import arc.struct.*;
import com.xpdustry.shard.common.math.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.oracle.*;
import com.xpdustry.shard.sim.world.*;
import com.xpdustry.shard.sim.world.Cause;
import mindustry.content.*;
import mindustry.game.*;
import mindustry.gen.*;
import mindustry.world.*;
import org.junit.jupiter.params.*;
import org.junit.jupiter.params.provider.*;

import java.util.*;
import java.util.stream.*;

import static mindustry.Vars.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The invalidation gate of the control pathfinder (WP71): with paths cached on both sides (requests of every cost type
 * for both teams, their fields complete), tile changes mid-run reach the same clusters as in vanilla. Each change is
 * made on both sides between frames, then the thread's steps run on both, compared after each:
 * <ol>
 *   <li>the posted tile updates: the same clusters to re-derive whole ({@code clustersToUpdate}) and to re-derive the
 *   inner edges of ({@code clustersToInnerUpdate}), in the same iteration order;</li>
 *   <li>the cluster updates: every cluster equal, and the same requests invalid, in the same order;</li>
 *   <li>the invalid check, then the next pass: the same fields dropped and rebuilt, equal tile for tile, and every
 *   request with the same portal path, result and old field.</li>
 * </ol>
 * The changes: two walls built in one frame (one on a cluster edge, one inside a cluster) across a path, one of them
 * destroyed, and a core captured ({@code coreCapture}: its tile set to the core again, for the other team, as
 * {@code CoreBuild.afterDestroyed} does). Vanilla is pinned as ARCHITECTURE.md §1 and §9 say: its threads are stopped,
 * its changed tiles' rings repacked ({@code VanillaPathfinder.freshRings}), and its invalid set ordered.
 */
class ControlInvalidationOracleTest{
    static final int GOALS = 3;
    static final ContentEpoch CONTENT = PathWorlds.VANILLA;

    static Stream<String> maps(){
        return Stream.of("fortress", "serpulo/frontier", "erekir/onset");
    }

    /** Posts the tick's tile changes to the control pathfinder, as {@code updateTile} does, and nothing else. */
    static final class Posts implements PhaseHandler{
        @Override
        public Phase phase(){
            return Phase.PATH_FLUSH;
        }

        @Override
        public int order(){
            return 1;
        }

        @Override
        public void run(World world){
            ControlPathfinder control = world.part(ControlPathfinder.class);
            world.tiles().changes((pos, before) -> control.updateSingleTile(pos));
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("maps")
    void tileChangesInvalidateTheSameClusters(String map){
        Vanilla.host(map);
        state.rules.coreCapture = true;
        mindustry.ai.ControlPathfinder vanilla = VanillaControlPath.take();
        VanillaPathfinder.take();
        VanillaPathfinder.freshRings();
        VanillaControlPath.pinOrder(vanilla);
        // repacked under coreCapture, as Shard's rules are
        VanillaPaths.repack();
        World shard = ControlOracle.mirror(new Posts());
        ControlOracle.sameTiles(map, shard);
        ControlPathfinder mine = shard.part(ControlPathfinder.class);
        mine.bindForTests(shard);

        int width = world.width(), height = world.height();
        int[] teams = {state.rules.defaultTeam.id, state.rules.waveTeam.id};
        var rand = new Rand(map.hashCode() * 31L);
        var theirs = new ArrayList<Object>();
        var ours = new ArrayList<ControlPathfinder.Request>();
        for(int costId = 0; costId < ControlPathfinder.COSTS; costId++){
            for(int g = 0; g < GOALS; g++){
                int goal = open(rand, costId == ControlPathfinder.COST_NAVAL);
                for(int team : teams){
                    int start = open(rand, costId == ControlPathfinder.COST_NAVAL);
                    int sx = start % width, sy = start / width;
                    Unit unit = UnitTypes.dagger.create(Team.get(team));
                    unit.set(sx * tilesize, sy * tilesize);
                    Object request = VanillaControlPath.request(unit, team, costId, goal);
                    VanillaControlPath.start(vanilla, request);
                    theirs.add(request);
                    ControlPathfinder.Request request2 = mine.request(team, costId, goal, sx, sy);
                    mine.start(request2);
                    ours.add(request2);
                }
            }
        }
        pass(vanilla, mine);
        same(map + " at the start", vanilla, mine, theirs, ours);

        // a path to cut: the first field a request built
        ControlPathfinder.Field field = null;
        for(int i = 0; i < ours.size() && field == null; i++){
            ControlPathfinder.Request request = ours.get(i);
            if(!request.notFound) field = mine.field(request.destination, request.costId, request.team);
        }
        assertNotNull(field, map + ": no request built a field");
        int edge = pick(field, true), inside = pick(field, false);
        assertTrue(edge != -1 && inside != -1, map + ": no open tile to wall in the first field");

        change(map + " walls built", vanilla, shard, mine, theirs, ours, () -> {
            build(shard, edge, Blocks.copperWall, teams[0]);
            build(shard, inside, Blocks.titaniumWall, teams[1]);
        });
        change(map + " a wall destroyed", vanilla, shard, mine, theirs, ours, () -> build(shard, edge, Blocks.air, 0));

        var core = state.teams.cores(state.rules.defaultTeam).firstOpt();
        assertNotNull(core, map + ": no core to capture");
        int corePos = core.tile.array();
        Block block = core.block;
        change(map + " a core captured", vanilla, shard, mine, theirs, ours, () -> build(shard, corePos, block, teams[1]));
    }

    /** A random tile open to the cost type: not solid, and water for naval where the map has any. */
    static int open(Rand rand, boolean naval){
        for(int attempt = 0; ; attempt++){
            Tile tile = world.tiles.geti(rand.random(world.tiles.width * world.tiles.height - 1));
            boolean floor = attempt > 20_000 || tile.floor().isLiquid == naval;
            if(!tile.solid() && floor && tile.build == null) return tile.array();
            if(attempt > 100_000) throw new IllegalStateException("no open tile");
        }
    }

    /** A tile the field reached, empty, buildable, on a cluster edge or not. */
    static int pick(ControlPathfinder.Field field, boolean edge){
        int width = world.width(), size = ControlPathfinder.CLUSTER_SIZE, cwidth = (width + size - 1) / size;
        for(int index = 0; index < field.fields.length; index++){
            int[] weights = field.fields[index];
            if(weights == null) continue;
            for(int i = 0; i < weights.length; i++){
                int mx = i % size, my = i / size, x = index % cwidth * size + mx, y = index / cwidth * size + my;
                boolean onEdge = mx == 0 || my == 0 || mx == size - 1 || my == size - 1;
                Tile tile = world.tile(x, y);
                if(weights[i] <= 0 || onEdge != edge || tile == null || tile.block() != Blocks.air || tile.floor().isDeep()
                    || tile.floor().isLiquid || tile.overlay() == Blocks.spawn) continue;
                return tile.array();
            }
        }
        return -1;
    }

    /** {@code tile.setBlock(block, team)} on both sides. */
    static void build(World shard, int pos, Block block, int team){
        Tile tile = world.tiles.geti(pos);
        tile.setBlock(block, Team.get(team));
        com.xpdustry.shard.sim.building.Building made = shard.buildings().setBlock(tile.x, tile.y, CONTENT.block(block.name), team, 0, Cause.SERVER);
        if(made != null) made.health = tile.build.health;
    }

    /** One change between frames, then the thread's steps on both sides, compared after each. */
    static void change(String where, mindustry.ai.ControlPathfinder vanilla, World shard, ControlPathfinder mine, List<Object> theirs,
                       List<ControlPathfinder.Request> ours, Runnable edit){
        edit.run();
        // Shard's flush repacks the changed tiles and posts their clusters
        shard.tick();
        mine.bindForTests(shard);
        ControlOracle.sameTiles(where, shard);

        VanillaControlPath.runQueue(vanilla);
        mine.runQueue();
        IntSeq update = order(VanillaControlPath.toUpdate(vanilla)), inner = order(VanillaControlPath.toInner(vanilla));
        assertTrue(update.size + inner.size > 0, where + ": no cluster was queued");
        assertArrayEquals(update.toArray(), mine.clustersToUpdate().toSeq().toArray(), where + ": clusters to update whole");
        assertArrayEquals(inner.toArray(), mine.clustersToInnerUpdate().toSeq().toArray(), where + ": clusters to update inside");

        VanillaControlPath.updateClusters(vanilla);
        mine.updateClusters();
        ControlOracle.sameClusters(where, vanilla, mine);
        var invalid = new IntSeq();
        for(Object request : VanillaControlPath.invalid(vanilla)) invalid.add(indexOf(theirs, request));
        var myInvalid = new IntSeq();
        for(ControlPathfinder.Request request : mine.invalidRequests()) myInvalid.add(ours.indexOf(request));
        assertArrayEquals(invalid.toArray(), myInvalid.toArray(), where + ": invalid requests");
        System.out.println("| " + where + " | " + update + " | " + inner + " | " + invalid + " |");

        VanillaControlPath.checkInvalid(vanilla);
        mine.checkInvalid();
        pass(vanilla, mine);
        same(where, vanilla, mine, theirs, ours);
    }

    /** The rest of a pass on both sides: the queue, the clusters, every field to the end. */
    static void pass(mindustry.ai.ControlPathfinder vanilla, ControlPathfinder mine){
        VanillaControlPath.runQueue(vanilla);
        VanillaControlPath.updateClusters(vanilla);
        VanillaControlPath.updateFields(vanilla);
        Vanilla.runPosted();
        mine.runQueue();
        mine.updateClusters();
        mine.updateAllFields(0);
    }

    /** Clusters, fields and requests equal on both sides. */
    static void same(String where, mindustry.ai.ControlPathfinder vanilla, ControlPathfinder mine, List<Object> theirs, List<ControlPathfinder.Request> ours){
        ControlOracle.sameClusters(where, vanilla, mine);
        LongMap<Object> fields = VanillaControlPath.fields(vanilla);
        assertEquals(fields.size, mine.fields().size(), where + ": fields");
        for(Object field : fields.values()){
            ControlPathfinder.Field ourField = mine.field(goal(field), costId(field), team(field));
            ControlOracle.sameField(where + " field to " + goal(field), field, ourField);
        }
        assertEquals(VanillaControlPath.fieldList(vanilla).size, mine.fieldList().size(), where + ": field list");
        var threads = new IntSeq();
        for(Object request : VanillaControlPath.threads(vanilla)) threads.add(indexOf(theirs, request));
        var myThreads = new IntSeq();
        for(ControlPathfinder.Request request : mine.threadPathRequests()) myThreads.add(ours.indexOf(request));
        assertArrayEquals(threads.toArray(), myThreads.toArray(), where + ": thread requests");
        for(int i = 0; i < theirs.size(); i++){
            Object request = theirs.get(i);
            ControlPathfinder.Request ourRequest = ours.get(i);
            String at = where + " request " + i;
            assertEquals(VanillaControlPath.notFound(request), ourRequest.notFound, at + ": found");
            if(!ourRequest.notFound){
                assertArrayEquals(VanillaControlPath.resultPath(request).toArray(), ourRequest.resultPath.toArray(), at + ": portal path");
            }
            Object old = VanillaControlPath.oldCache(request);
            assertEquals(old == null, ourRequest.oldCache == null, at + ": old field");
            if(old != null){
                assertEquals(goal(old), ourRequest.oldCache.goalPos, at + ": old field's goal");
                assertEquals(costId(old), ourRequest.oldCache.costId, at + ": old field's cost");
                assertEquals(team(old), ourRequest.oldCache.team, at + ": old field's team");
            }
        }
    }

    static int indexOf(List<Object> list, Object value){
        for(int i = 0; i < list.size(); i++) if(list.get(i) == value) return i;
        return -1;
    }

    /** Arc's iteration order of a set. */
    static IntSeq order(arc.struct.IntSet set){
        var out = new IntSeq();
        set.each(out::add);
        return out;
    }

    static int goal(Object field){
        return field(field, "goalPos");
    }

    static int costId(Object field){
        return field(field, "costId");
    }

    static int team(Object field){
        return field(field, "team");
    }

    private static int field(Object owner, String name){
        try{
            var member = owner.getClass().getDeclaredField(name);
            member.setAccessible(true);
            return member.getInt(owner);
        }catch(ReflectiveOperationException e){
            throw new IllegalStateException(e);
        }
    }
}
