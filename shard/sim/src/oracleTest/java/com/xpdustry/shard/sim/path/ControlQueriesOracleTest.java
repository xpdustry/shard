package com.xpdustry.shard.sim.path;

import arc.math.geom.*;
import com.xpdustry.shard.common.math.*;
import com.xpdustry.shard.oracle.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.tile.*;
import com.xpdustry.shard.sim.unit.NoAi;
import com.xpdustry.shard.sim.world.*;
import mindustry.ai.ControlPathfinder.*;
import mindustry.content.*;
import mindustry.game.*;
import mindustry.gen.*;
import mindustry.type.*;
import mindustry.world.*;
import mindustry.world.Tile;
import org.junit.jupiter.params.*;
import org.junit.jupiter.params.provider.*;

import java.util.*;
import java.util.stream.*;

import static mindustry.Vars.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The query the RTS AIs make, {@code getPathPosition} (WP71): {@value #UNITS} units of every cost type and both teams
 * walk to their goals for {@value #FRAMES} frames on a bundled map, asking vanilla's control pathfinder and Shard's
 * every frame, and get the same answer: whether to move, whether the goal is unreachable, the point, and the tile a
 * blocked line stopped at. The test moves both sides' units alike along the answer, so the walk itself is the
 * query's (first requests, fields read before they are complete, raycast caches, re-requests after a recompute).
 * Midway a derelict wall goes up across a walker's way, and later comes down: the invalid check recomputes the paths
 * through it. Starts and goals lie in the map area, where a map limits it.
 *
 * <p>Vanilla runs on Shard's schedule (ARCHITECTURE.md §1): its thread stopped, a pass every
 * {@value ControlPathfinder#PASS_TICKS} frames with its fields to the end, the invalid check on Shard's ticks, and
 * the {@code Trigger.update} clean-up after each pass, with {@code state.updateId} following Shard's tick. Its
 * request and invalid maps iterate in insertion order; its changed tiles' rings are repacked.
 */
class ControlQueriesOracleTest{
    static final int UNITS = 16, FRAMES = 900, WALL_AT = 250, WALL_GONE_AT = 600;
    static final String[] TYPES = {"dagger", "atrax", "elude", "risso"};

    static Stream<String> maps(){
        return Stream.of("fortress", "serpulo/frontier", "erekir/onset");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("maps")
    void unitsGetTheSameAnswers(String map){
        Vanilla.host(map);
        mindustry.ai.ControlPathfinder vanilla = VanillaControlPath.take();
        VanillaPathfinder.take();
        VanillaPathfinder.freshRings();
        VanillaControlPath.pinOrder(vanilla);

        // every part, but only the path handlers: nothing moves the units but the test
        World shard = new World(WorldConfig.of(PathWorlds.VANILLA, world.width(), world.height(), 1)
            .withSystems(Systems.builtIn().withHandlers(List.of(new PathFlush(), new ControlFlush())))
            .withKinds(KindRegistry.builtIn().withPassthrough()));
        VanillaMaps.load(shard);
        var grid = shard.tiles();
        for(Tile tile : world.tiles){
            if(tile.build != null && tile.build.tile == tile) shard.buildings().at(grid.pos(tile.x, tile.y)).health = tile.build.health;
        }
        ControlOracle.sameSolidity(shard);
        PathService paths = PathWorlds.paths(shard);
        paths.rules(VanillaPaths.rules());
        paths.invalidateAll();
        shard.tick();
        ControlOracle.sameTiles(map, shard);
        ControlPathfinder mine = ControlPathfinder.of(shard);

        int width = world.width();
        var rand = new Rand(map.hashCode() * 7L);
        var theirs = new ArrayList<Unit>();
        var ours = new ArrayList<com.xpdustry.shard.sim.unit.Unit>();
        var goals = new ArrayList<float[]>();
        for(int i = 0; i < UNITS; i++){
            String type = TYPES[i % TYPES.length];
            int team = i % 2 == 0 ? state.rules.defaultTeam.id : state.rules.waveTeam.id;
            boolean naval = type.equals("risso");
            int start = open(rand, naval), goal = open(rand, naval);
            float x = start % width * tilesize, y = start / width * tilesize;
            Unit unit = content.unit(type).create(Team.get(team));
            unit.set(x, y);
            unit.add();
            theirs.add(unit);
            var ourUnit = com.xpdustry.shard.sim.unit.Unit.spawn(shard, shard.content().unit(type), team, x, y, 0f);
            ourUnit.controller(new NoAi());
            ours.add(ourUnit);
            goals.add(new float[]{goal % width * tilesize, goal / width * tilesize});
        }

        long nextPass = mine.nextPass(), lastInvalidCheck = mine.lastInvalidCheck();
        int moved = 0, unreachable = 0, blocked = 0, walls = 0;
        var wall = new ArrayList<Integer>();
        for(int frame = 0; frame < FRAMES; frame++){
            long now = shard.ticks();
            // across the first walker's way that has open ground halfway
            for(int i = 0; frame == WALL_AT && wall.isEmpty() && i < UNITS; i++) walls += wall(ours.get(i), goals.get(i), wall, shard);
            if(frame == WALL_GONE_AT){
                for(int pos : wall) build(shard, pos, Blocks.air);
            }

            // vanilla's thread and Trigger.update, at Shard's PATH_FLUSH of tick `now`
            state.updateId = now;
            if(now >= nextPass){
                nextPass = now + ControlPathfinder.PASS_TICKS;
                VanillaControlPath.runQueue(vanilla);
                VanillaControlPath.updateClusters(vanilla);
                if(now - lastInvalidCheck > ControlPathfinder.INVALIDATE_TICKS){
                    lastInvalidCheck = now;
                    VanillaControlPath.checkInvalid(vanilla);
                }
                VanillaControlPath.updateFields(vanilla);
                Vanilla.runPosted();
            }
            VanillaControlPath.trigger(vanilla);
            state.updateId = now + 1;
            shard.tick();
            assertEquals(nextPass, mine.nextPass(), map + " frame " + frame + ": the pass schedule");

            for(int i = 0; i < UNITS; i++){
                Unit unit = theirs.get(i);
                var ourUnit = ours.get(i);
                float[] goal = goals.get(i);
                PathfindResult result = vanilla.getPathPosition(unit, new Vec2(goal[0], goal[1]));
                ControlPathfinder.Result ourResult = mine.pathPosition(ourUnit, goal[0], goal[1]);
                String where = map + " frame " + frame + " unit " + i + " (" + unit.type.name + ", team " + unit.team.id + ") at " + unit.x + "," + unit.y;
                assertEquals(result.move, ourResult.move(), where + ": move");
                assertEquals(result.unreachable, ourResult.unreachable(), where + ": unreachable");
                assertEquals(result.next == null ? -1 : result.next.array(), ourResult.next(), where + ": next");
                assertEquals(result.dest.x, ourResult.x(), where + ": x");
                assertEquals(result.dest.y, ourResult.y(), where + ": y");
                if(result.unreachable) unreachable++;
                if(result.next != null) blocked++;
                if(!result.move) continue;
                // both sides take the same step towards the answer
                float dx = result.dest.x - unit.x, dy = result.dest.y - unit.y, len = (float)Math.sqrt(dx * dx + dy * dy);
                if(len < 0.01f) continue;
                float step = Math.min(len, 1.5f), x = unit.x + dx / len * step, y = unit.y + dy / len * step;
                unit.set(x, y);
                ourUnit.set(x, y);
                moved++;
            }
        }
        System.out.println("| " + map + " | " + FRAMES + " frames | " + moved + " steps | " + unreachable + " unreachable | "
            + blocked + " blocked | " + walls + " wall tiles |");
        assertTrue(moved > FRAMES, map + ": the units hardly moved");
        assertTrue(walls > 0, map + ": no wall went up");
    }

    /** A random tile open to the unit, in the map area: not solid, water for naval units where the map has any. */
    static int open(Rand rand, boolean naval){
        for(int attempt = 0; ; attempt++){
            Tile tile = world.tiles.geti(rand.random(world.tiles.width * world.tiles.height - 1));
            boolean floor = attempt > 20_000 || (naval ? tile.floor().isLiquid : !tile.floor().isLiquid);
            if(!tile.solid() && floor && tile.build == null && world.isInMapArea(tile.x, tile.y)) return tile.array();
            if(attempt > 100_000) throw new IllegalStateException("no open tile");
        }
    }

    /** Derelict walls across the line from the unit to its goal, halfway, on open ground: their positions. */
    static int wall(com.xpdustry.shard.sim.unit.Unit unit, float[] goal, List<Integer> out, World shard){
        int mx = Math.round((unit.x + goal[0]) / 2f / tilesize), my = Math.round((unit.y + goal[1]) / 2f / tilesize);
        boolean vertical = Math.abs(goal[0] - unit.x) > Math.abs(goal[1] - unit.y);
        for(int i = -4; i <= 4; i++){
            Tile tile = world.tile(mx + (vertical ? 0 : i), my + (vertical ? i : 0));
            if(tile == null || tile.block() != Blocks.air || tile.floor().isDeep() || tile.overlay() == Blocks.spawn) continue;
            build(shard, tile.array(), Blocks.copperWall);
            out.add(tile.array());
        }
        return out.size();
    }

    /** {@code tile.setBlock(block, derelict)} on both sides. */
    static void build(World shard, int pos, Block block){
        Tile tile = world.tiles.geti(pos);
        tile.setBlock(block, Team.derelict);
        com.xpdustry.shard.sim.building.Building made = shard.buildings().setBlock(tile.x, tile.y,
            PathWorlds.VANILLA.block(block.name), 0, 0, Cause.SERVER);
        if(made != null) made.health = tile.build.health;
    }
}
