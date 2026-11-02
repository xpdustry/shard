package com.xpdustry.shard.sim.tile;

import com.xpdustry.shard.common.math.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.content.epoch.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.world.*;
import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Forks and hashes cover the tile state, proximity, passthrough bytes and the chunk index; derived state rebuilds. */
class TileForkTest{
    /**
     * The vanilla blocks, with the cargo loader and the tank assembler given a kind no class registers, so they stay
     * passthroughs: every vanilla block is ported (W5.1), and the churn needs small passthroughs to place often.
     */
    static final ContentEpoch BLOCKS = VanillaWorlds.withKind(KindKey.of("unported-probe"), "unit-cargo-loader", "tank-assembler");
    /** Ported kinds, environment blocks and unported ones, whose passthrough bodies the churn sets. */
    static final String[] PLACED = {"router", "conveyor", "container", "vault", "copper-wall", "copper-wall-large",
        "boulder", "stone-wall", "laser-drill", "door-large", "tank-assembler", "unit-cargo-loader", "air"};

    /** Random placements, removals, floors and team changes, all drawn from {@code rand}. */
    static void churn(World world, Rand rand, int steps){
        TileGrid tiles = world.tiles();
        for(int i = 0; i < steps; i++){
            int x = rand.random(tiles.width() - 1), y = rand.random(tiles.height() - 1);
            switch(rand.random(9)){
                case 0 -> world.buildings().setFloor(x, y, BLOCKS.block(rand.chance(0.5) ? "deep-water" : "sand-floor"), Cause.SERVER);
                case 1 -> world.buildings().setOverlay(x, y, BLOCKS.block(rand.chance(0.5) ? "ore-copper" : "air"), Cause.SERVER);
                case 2 -> {
                    Building building = world.buildings().at(x, y);
                    if(building != null) world.buildings().changeTeam(building, rand.random(1, 3), new Cause.Team(building.team));
                }
                default -> {
                    Building building = world.buildings().setBlock(x, y, BLOCKS.block(PLACED[rand.random(PLACED.length - 1)]),
                        rand.random(1, 2), rand.random(3), new Cause.Player(rand.random(5)));
                    if(building instanceof PassthroughBuilding passthrough && rand.chance(0.5)){
                        passthrough.load(new byte[]{(byte)x, (byte)y, 7}, 1, world.content().hash());
                    }
                }
            }
        }
    }

    static World churned(long seed){
        World world = VanillaWorlds.filled(BLOCKS, 40, 40, "stone");
        churn(world, new Rand(seed), 1500);
        return world;
    }

    @Test
    void aForkHashesAlikeAndContinuesAlike(){
        World source = churned(1);
        assertTrue(source.tiles().pendingChanges() > 0, "the fork must carry undrained changes");
        World fork = source.fork();
        assertEquals(source.hash(), fork.hash());
        Rand a = new Rand(2), b = new Rand(2);
        for(int i = 0; i < 20; i++){
            churn(source, a, 50);
            churn(fork, b, 50);
            source.tick();
            fork.tick();
            assertEquals(source.hash(), fork.hash(), "step " + i + ": " + source.hash().firstDifference(fork.hash()).orElse("?"));
        }
        assertEquals(changes(source), changes(fork));
    }

    /** The pending changes, as the next tick will hand them to its readers. */
    static List<Long> changes(World world){
        var out = new ArrayList<Long>();
        world.tiles().beginTick();
        world.tiles().changes((pos, before) -> out.add((long)pos << 32 ^ before));
        world.tiles().endTick();
        return out;
    }

    @Test
    void aForkIsIndependent(){
        World source = churned(3);
        WorldHash before = source.hash();
        World fork = source.fork();
        churn(fork, new Rand(4), 300);
        fork.tick();
        assertEquals(before, source.hash());
        assertNotEquals(before, fork.hash());
    }

    @Test
    void proximityAndPassthroughBytesAreCopiedAndRemapped(){
        World source = churned(5);
        World fork = source.fork();
        int proximities = 0, bodies = 0;
        for(int i = 0; i < source.buildings().indexLimit(); i++){
            Building original = source.buildings().get(i), copy = fork.buildings().get(i);
            if(original == null){
                assertNull(copy);
                continue;
            }
            assertEquals(original.proximitySize(), copy.proximitySize());
            for(int p = 0; p < original.proximitySize(); p++){
                assertSame(fork.buildings().get(original.proximity(p).index()), copy.proximity(p), "proximity " + p + " of " + original);
                proximities++;
            }
            if(original instanceof PassthroughBuilding passthrough && passthrough.hasBody()){
                var copied = (PassthroughBuilding)copy;
                assertArrayEquals(passthrough.body(source.content()), copied.body(fork.content()));
                assertEquals(passthrough.revision(), copied.revision());
                bodies++;
            }
        }
        assertTrue(proximities > 50 && bodies > 5, proximities + " proximity entries, " + bodies + " bodies");
    }

    @Test
    void derivedStateRebuildsExactly(){
        World world = churned(6);
        // a door closed after placement: its solidity comes from checkSolid, not from the blocks
        WorldHash live = world.hash();
        world.buildings().rebuildDerived();
        assertEquals(live, world.hash());
        world.tiles().rebuildDerived();
        assertEquals(live, world.hash());
    }

    @Test
    void theNewStateMovesTheRightHashSections(){
        World world = churned(7);
        World floor = world.fork(), team = world.fork(), body = world.fork();
        floor.tiles().data(floor.tiles().pos(1, 1), 9);
        assertEquals("tiles", world.hash().firstDifference(floor.hash()).orElseThrow());
        Building any = null;
        for(int i = 0; i < team.buildings().indexLimit() && any == null; i++) any = team.buildings().get(i);
        team.buildings().changeTeam(any, any.team == 3 ? 4 : 3, Cause.SERVER);
        assertEquals("tiles", world.hash().firstDifference(team.hash()).orElseThrow(), "team columns, then buildings");
        // a probe passthrough (the tank assembler, re-kinded), 5x5 inside the map's corner
        BlockDef unported = BLOCKS.block("tank-assembler");
        var a = (PassthroughBuilding)body.buildings().setBlock(2, 37, unported, 1, 0, Cause.SERVER);
        World other = body.fork();
        a.load(new byte[]{1}, 1, body.content().hash());
        ((PassthroughBuilding)other.buildings().at(2, 37)).load(new byte[]{2}, 1, other.content().hash());
        assertEquals("buildings", body.hash().firstDifference(other.hash()).orElseThrow());
    }
}
