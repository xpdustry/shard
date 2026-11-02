package com.xpdustry.shard.sim.tile;

import com.xpdustry.shard.content.*;
import com.xpdustry.shard.content.epoch.*;
import com.xpdustry.shard.sim.blocks.environment.*;
import com.xpdustry.shard.sim.spatial.*;
import com.xpdustry.shard.sim.world.*;
import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** {@link TileGrid}'s predicates on vanilla blocks, its darkness pass and its change lists. */
class TileGridTest{
    static final Epoch BLOCKS = VanillaWorlds.VANILLA;
    static final BlockTable TABLE = BlockTable.of(BLOCKS);

    static int id(String name){
        return BLOCKS.block(name).id();
    }

    static FloorDef floor(String name){
        return (FloorDef)BLOCKS.block(name);
    }

    TileGrid grid = new TileGrid(24, 24, TABLE);

    @Test
    void solidityViewsFollowTheirOwnGrid(){
        TileSolidity original = TileSolidity.grid(grid);
        assertSame(original, TileSolidity.grid(grid));
        TileGrid copy = grid.copy();
        TileSolidity copied = TileSolidity.grid(copy);
        assertNotSame(original, copied);
        for(Solidity kind : Solidity.values()){
            assertTrue(original.solid(kind, -1, 0));
            assertTrue(copied.solid(kind, 24, 0));
        }
        grid.block(grid.pos(1, 1), id("stone-wall"));
        assertTrue(original.solid(Solidity.SOLID, 1, 1));
        assertFalse(copied.solid(Solidity.SOLID, 1, 1));
        copy.floor(copy.pos(2, 2), id("deep-water"));
        assertFalse(copied.solid(Solidity.WATER, 2, 2));
        assertTrue(original.solid(Solidity.WATER, 2, 2));
    }

    @Test
    void floorPredicatesFollowTheBlockFields(){
        int deep = grid.pos(1, 1), shallow = grid.pos(2, 1), space = grid.pos(3, 1), tar = grid.pos(4, 1), slag = grid.pos(5, 1);
        grid.floor(deep, id("deep-water"));
        grid.floor(shallow, id("sand-water"));
        grid.floor(space, id("space"));
        grid.floor(tar, id("tar"));
        grid.floor(slag, id("molten-slag"));
        assertTrue(grid.isDeep(deep) && grid.isLiquid(deep) && !grid.solid(deep) && grid.dangerous(deep));
        assertEquals(floor("deep-water").drownTime(), grid.drownTime(deep));
        assertTrue(grid.isLiquid(shallow) && !grid.isDeep(shallow) && !grid.dangerous(shallow));
        assertTrue(grid.solid(space) && grid.legSolid(space), "a solid floor without a block is solid for legs too");
        assertFalse(grid.passable(space));
        assertEquals(floor("tar").speedMultiplier(), grid.speedMultiplier(tar));
        assertEquals(floor("tar").status().id(), grid.status(tar));
        assertTrue(grid.dangerous(slag), "molten slag's status damages");
        assertTrue(grid.waterSolid(grid.pos(9, 9)) && !grid.waterSolid(deep), "naval units need liquid");
        grid.block(deep, id("copper-wall"));
        assertFalse(grid.isDeep(deep), "isDeep needs air above the floor");
        assertTrue(grid.solid(deep));
    }

    @Test
    void blocksDecideSolidityAndDrops(){
        int wall = grid.pos(1, 1), boulder = grid.pos(2, 1), cliff = grid.pos(3, 1), ore = grid.pos(4, 1), wallOre = grid.pos(5, 1);
        grid.block(wall, id("stone-wall"));
        grid.block(boulder, id("boulder"));
        grid.block(cliff, id("cliff"));
        grid.floor(ore, id("sand-floor"));
        assertEquals(BLOCKS.items(), 22);
        assertEquals(BLOCKS.block("sand-floor").itemDrop().id(), grid.drop(ore));
        grid.overlay(ore, id("ore-copper"));
        assertEquals(BLOCKS.block("ore-copper").itemDrop().id(), grid.drop(ore), "the ore wins over the floor");
        grid.block(wallOre, id("stone-wall"));
        grid.overlay(wallOre, id("ore-wall-beryllium"));
        assertEquals(BLOCKS.block("ore-wall-beryllium").itemDrop().id(), grid.wallDrop(wallOre));
        assertEquals(BlockTable.NONE, grid.wallDrop(wall));
        assertTrue(grid.solid(wall) && grid.isStatic(wall) && !grid.passable(wall));
        assertFalse(grid.solid(boulder));
        assertTrue(grid.breakable(boulder));
        assertTrue(grid.solid(cliff) && grid.isStatic(cliff));
        assertFalse(grid.isDarkened(cliff, false), "cliffs don't fill their tile");
        assertTrue(grid.isDarkened(wall, false));
    }

    @Test
    void darknessCountsInFromTheEdgeOfAWallMass(){
        for(int x = 5; x <= 17; x++){
            for(int y = 5; y <= 17; y++) grid.block(grid.pos(x, y), id("stone-wall"));
        }
        grid.addDarkness(false);
        var row = new ArrayList<Integer>();
        for(int x = 5; x <= 17; x++) row.add((int)grid.data(grid.pos(x, 11)));
        assertEquals(List.of(0, 1, 2, 3, 4, 5, 5, 5, 4, 3, 2, 1, 0), row);
        assertFalse(grid.legSolid(grid.pos(6, 11)), "darkness 1 is walkable for legs");
        assertTrue(grid.legSolid(grid.pos(7, 11)));
        assertEquals(0, grid.data(grid.pos(4, 11)), "outside the wall");
    }

    @Test
    void coloredWallsAndWorldProcessorsHaveTheirOwnDarkness(){
        int colored = grid.pos(3, 3), processor = grid.pos(6, 6);
        grid.block(colored, id("colored-wall"));
        assertFalse(grid.isDarkened(colored, false));
        grid.extraData(colored, TileGrid.COLORED_WALL_DARK);
        assertTrue(grid.isDarkened(colored, false));
        grid.block(processor, id("world-processor"));
        assertTrue(grid.isDarkened(processor, false));
        assertFalse(grid.isDarkened(processor, true), "an accessible world processor is not dark");
        grid.block(processor, id("micro-processor"));
        assertFalse(grid.isDarkened(processor, false));
    }

    @Test
    void sumAttributeSkipsDeepFloorsUnlessTheBlockFloats(){
        for(int x = 4; x <= 6; x++){
            for(int y = 4; y <= 6; y++) grid.floor(grid.pos(x, y), id("sand-floor"));
        }
        grid.floor(grid.pos(6, 6), id("deep-water"));
        float oil = BLOCKS.block("sand-floor").attributes().get("oil");
        assertTrue(oil > 0);
        assertEquals(oil * 8, grid.sumAttribute(Attributes.OIL, 5, 5, 3, false), 1e-5);
        assertEquals(oil * 8 + BLOCKS.block("deep-water").attributes().getOrDefault("oil", 0f), grid.sumAttribute(Attributes.OIL, 5, 5, 3, true), 1e-5);
    }

    @Test
    void eachTickListsItsChangesOncePerTileWithTheirFirstOldState(){
        int a = grid.pos(1, 1), b = grid.pos(2, 2);
        long before = grid.packed(a);
        grid.floor(a, id("deep-water"));
        grid.block(b, id("stone-wall"));
        grid.block(a, id("copper-wall"));
        grid.team(a, 3);
        grid.extraData(b, 9);
        assertEquals(2, grid.pendingChanges());
        assertEquals(0, grid.changeCount(), "pending until a tick begins");
        grid.beginTick();
        assertEquals(0, grid.pendingChanges());
        List<String> seen = changes(grid);
        assertEquals(2, seen.size());
        assertEquals(a + ":" + before, seen.getFirst());
        assertTrue(seen.get(1).startsWith(b + ":"));
        assertEquals(seen, changes(grid), "every reader reads the whole list");
        long now = grid.packed(a);
        assertEquals(id("deep-water"), TileState.floor(now));
        assertEquals(id("copper-wall"), TileState.block(now));
        assertEquals(3, TileState.team(now));
        assertTrue(TileState.solid(now));
        assertFalse(TileState.deep(now), "a block covers the deep floor");
        assertTrue(TileState.liquid(now));
        grid.floor(a, 1);
        assertEquals(1, grid.pendingChanges(), "a change during the tick goes to the next one");
        assertEquals(seen, changes(grid));
        assertThrows(IllegalStateException.class, grid::beginTick, "the tick hasn't ended");
        grid.endTick();
        assertEquals(List.of(), changes(grid), "the list clears when the tick ends");
        grid.beginTick();
        assertEquals(List.of(a + ":" + now), changes(grid));
        grid.endTick();
        grid.floor(b, 2);
        grid.beginTick();
        grid.clearChanges();
        assertEquals(List.of(), changes(grid));
        assertEquals(0, grid.pendingChanges());
        grid.recording(false);
        grid.floor(a, 3);
        assertEquals(0, grid.pendingChanges(), "not recorded while loading");
    }

    static List<String> changes(TileGrid grid){
        var out = new ArrayList<String>();
        grid.changes((pos, old) -> out.add(pos + ":" + old));
        return out;
    }

    @Test
    void edgesMatchVanillasGeneration(){
        // Edges.getEdges(1): right, up, left, down; (2): starts at (2,0), corners never included
        assertEquals(List.of("1,0", "0,1", "-1,0", "0,-1"), edges(1));
        assertEquals(List.of("2,0", "2,1", "1,2", "0,2", "-1,1", "-1,0", "0,-1", "1,-1"), edges(2));
        for(int size = 1; size <= Footprint.MAX_SIZE; size++){
            assertEquals(size * 4, Footprint.edgeCount(size));
            for(int i = 0; i < size * 4; i++){
                int x = Footprint.edgeX(size, i), y = Footprint.edgeY(size, i), o = Footprint.offset(size);
                boolean outside = x < o || x >= o + size || y < o || y >= o + size;
                boolean corner = (x == o - 1 || x == o + size) && (y == o - 1 || y == o + size);
                assertTrue(outside && !corner, "edge " + x + "," + y + " of size " + size);
            }
        }
    }

    static List<String> edges(int size){
        var out = new ArrayList<String>();
        for(int i = 0; i < Footprint.edgeCount(size); i++) out.add(Footprint.edgeX(size, i) + "," + Footprint.edgeY(size, i));
        return out;
    }

    @Test
    void aCopyIsIndependentAndHashesAlike(){
        grid.floor(grid.pos(3, 3), id("deep-water"));
        grid.data(grid.pos(4, 4), 3);
        TileGrid copy = grid.copy();
        assertEquals(hash(grid), hash(copy));
        copy.extraData(grid.pos(5, 5), 1);
        assertNotEquals(hash(grid), hash(copy));
        assertEquals(0, grid.extraData(grid.pos(5, 5)));
        assertTrue(grid.pendingChanges() > 0);
        assertEquals(grid.pendingChanges(), copy.pendingChanges(), "pending changes are forked");
    }

    @Test
    void everyColumnFeedsTheHash(){
        int pos = grid.pos(7, 7);
        long base = hash(grid);
        List<java.util.function.Consumer<TileGrid>> edits = List.of(
            g -> g.floor(pos, 2), g -> g.overlay(pos, 2), g -> g.block(pos, 2), g -> g.team(pos, 2), g -> g.build(pos, 2),
            g -> g.data(pos, 2), g -> g.floorData(pos, 2), g -> g.overlayData(pos, 2), g -> g.extraData(pos, 2),
            g -> g.buildSolid(pos, true), g -> g.clearChanges());
        for(var edit : edits){
            TileGrid copy = grid.copy();
            copy.floor(grid.pos(0, 0), 0);
            long touched = hash(copy);
            edit.accept(copy);
            assertNotEquals(touched, hash(copy));
        }
        assertEquals(base, hash(grid));
    }

    @Test
    void derivedBitsetsRebuildToTheLiveState(){
        var rand = new com.xpdustry.shard.common.math.Rand(5);
        String[] floors = {"stone", "deep-water", "sand-water", "space", "tar"}, blocks = {"air", "stone-wall", "boulder", "cliff", "copper-wall"};
        for(int i = 0; i < 400; i++){
            int pos = rand.random(grid.size() - 1);
            grid.floor(pos, id(floors[rand.random(floors.length - 1)]));
            grid.block(pos, id(blocks[rand.random(blocks.length - 1)]));
        }
        grid.addDarkness(false);
        long live = hash(grid);
        grid.rebuildDerived();
        assertEquals(live, hash(grid));
    }

    static long hash(TileGrid grid){
        var hasher = new Hasher();
        grid.hash(hasher);
        return hasher.value();
    }
}
