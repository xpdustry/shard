package com.xpdustry.shard.sim.building;

import com.xpdustry.shard.content.*;
import com.xpdustry.shard.content.epoch.*;
import com.xpdustry.shard.sim.tile.*;
import com.xpdustry.shard.sim.world.*;
import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The placement primitives against vanilla's rules, read from {@code Tile.setBlock} and {@code BuildingComp}: the
 * oracle versions of these checks are in {@code PlacementOracleTest}.
 */
class PlacementTest{
    static final Epoch BLOCKS = VanillaWorlds.VANILLA;
    static final Cause PLAYER = new Cause.Player(7);

    World world;
    Buildings buildings;
    TileGrid tiles;

    @BeforeEach
    void world(){
        world = VanillaWorlds.of(32, 32, "stone");
        buildings = world.buildings();
        tiles = world.tiles();
        Probe.LOG.clear();
    }

    Building place(String block, int x, int y){
        return place(block, 1, x, y, 0);
    }

    Building place(String block, int team, int x, int y, int rotation){
        return buildings.setBlock(x, y, BLOCKS.block(block), team, rotation, PLAYER);
    }

    Building at(int x, int y){
        return buildings.at(x, y);
    }

    @Test
    void multiblocksCoverTheirFootprintWithVanillasEvenSizeOffset(){
        // sizeOffset = -(size - 1) / 2: 2×2 reaches up and right, 3×3 is centred, 4×4 reaches one further up and right
        for(var c : List.of(new int[]{2, 10, 10, 10, 11}, new int[]{3, 20, 10, 19, 21}, new int[]{4, 10, 20, 9, 12})){
            String name = switch(c[0]){
                case 2 -> "container";
                case 3 -> "vault";
                default -> "blast-drill";
            };
            assertEquals(c[0], BLOCKS.block(name).size());
            Building building = place(name, c[1], c[2]);
            for(int x = c[3] - 1; x <= c[4] + 1; x++){
                for(int y = c[3] - c[1] + c[2] - 1; y <= c[4] - c[1] + c[2] + 1; y++){
                    boolean inside = x >= c[3] && x <= c[4] && y >= c[3] - c[1] + c[2] && y <= c[4] - c[1] + c[2];
                    Tile tile = Tile.at(world, x, y);
                    assertEquals(inside ? building : null, tile.build(), name + " at " + x + "," + y);
                    assertEquals(inside ? BLOCKS.block(name).id() : 0, tile.blockId());
                    assertEquals(inside ? 1 : 0, tile.team());
                    assertEquals(!inside || (x == c[1] && y == c[2]), tile.isCenter());
                    if(inside) assertEquals(c[1], tile.centerX());
                }
            }
        }
    }

    @Test
    void aMultiblockAtTheMapEdgeLinksOnlyTheTilesInside(){
        Building vault = place("vault", 0, 0);
        assertNotNull(vault);
        assertSame(vault, at(1, 1));
        assertSame(vault, at(0, 1));
        assertNull(at(2, 2));
        assertTrue(vault.isValid());
        buildings.remove(vault, PLAYER);
        assertEquals(0, tiles.block(tiles.pos(1, 1)));
    }

    @Test
    void placingOverBuildingsRemovesThem(){
        Building router = place("router", 11, 11);
        Building wall = place("copper-wall", 12, 11);
        Building container = place("container", 10, 10);
        assertFalse(router.isValid());
        assertEquals(-1, router.index());
        assertTrue(wall.isValid(), "outside the footprint");
        assertSame(container, at(11, 11));
        assertEquals(2, buildings.count());
        assertEquals(List.of(wall), container.proximityList());
    }

    @Test
    void settingALinkedTileRemovesTheWholeMultiblock(){
        Building container = place("container", 10, 10);
        Building router = place("router", 11, 11);
        assertFalse(container.isValid());
        assertSame(router, at(11, 11));
        for(int[] p : new int[][]{{10, 10}, {10, 11}, {11, 10}}){
            assertNull(at(p[0], p[1]));
            assertEquals(0, tiles.block(tiles.pos(p[0], p[1])));
        }
    }

    @Test
    void proximityIsRebuiltInEdgeOrderAndNeighboursAppend(){
        Building center = place("router", 10, 10);
        Building up = place("router", 10, 11), left = place("router", 9, 10), right = place("router", 11, 10);
        Building enemy = place("router", 2, 10, 9, 0);
        assertEquals(List.of(up, left, right), center.proximityList(), "later neighbours append");
        assertEquals(List.of(center), up.proximityList());
        assertTrue(enemy.proximityList().isEmpty(), "other teams are not proximity");
        place("router", 10, 10);
        Building again = at(10, 10);
        // Edges.getEdges(1), sorted by angle: right, up, left, down
        assertEquals(List.of(right, up, left), again.proximityList());
        assertEquals(List.of(again), up.proximityList(), "the old center left, the new one appended");
    }

    @Test
    void multiblockProximityFollowsVanillasEdgeOrder(){
        // a 2×2 at (10,10) covers 10..11; its edges sorted by angle from the origin start at (2,0)
        Building container = place("container", 10, 10);
        Building east = place("router", 12, 10), north = place("router", 11, 12), west = place("router", 9, 11),
            south = place("router", 10, 9);
        Building eastTop = place("router", 12, 11);
        assertEquals(List.of(east, north, west, south, eastTop), container.proximityList());
        place("container", 10, 10);
        Building rebuilt = at(10, 10);
        assertEquals(List.of(east, eastTop, north, west, south), rebuilt.proximityList());
        assertEquals(List.of(east, rebuilt), eastTop.proximityList(), "rebuilt while the container was gone, then appended");
    }

    @Test
    void callbacksRunInVanillasOrder(){
        var probes = VanillaWorlds.withKind(KindKey.of("test-probe"), "copper-wall");
        world = VanillaWorlds.filled(probes, 16, 16, "stone");
        buildings = world.buildings();
        BlockDef probe = probes.block("copper-wall");
        buildings.setBlock(5, 5, probe, 1, 0, Cause.SERVER);
        assertEquals(List.of("5,5:added", "5,5:update"), take());
        buildings.setBlock(6, 5, probe, 1, 0, Cause.SERVER);
        assertEquals(List.of("6,5:added", "6,5:update", "5,5:update"), take());
        buildings.setBlock(5, 5, probes.block(0), 0, 0, Cause.SERVER);
        // preChanged: onRemoved, removeFromProximity (removed, then each neighbour updates); changeBuild: the edges
        // update their proximity; changed: no building left, so the d4 neighbours update once more
        assertEquals(List.of("5,5:onRemoved", "5,5:removed", "6,5:update", "6,5:added", "6,5:update", "6,5:update"), take());
        buildings.setBlock(6, 6, probe, 2, 0, Cause.SERVER);
        assertEquals(List.of("6,6:added", "6,6:update"), take(), "another team's neighbour is not told");
        buildings.setBlock(6, 6, probes.block(0), 0, 0, Cause.SERVER);
        assertEquals(List.of("6,6:onRemoved", "6,6:removed", "6,5:update", "6,5:added", "6,5:update", "6,5:update"), take(),
            "but removal tells every neighbour, whatever its team");
    }

    List<String> take(){
        var out = List.copyOf(Probe.LOG);
        Probe.LOG.clear();
        return out;
    }

    @Test
    void solidityFollowsCheckSolid(){
        var probes = VanillaWorlds.withKind(KindKey.of("test-probe"), "door-large");
        world = VanillaWorlds.filled(probes, 16, 16, "stone");
        var door = (Probe)world.buildings().setBlock(5, 5, probes.block("door-large"), 1, 0, Cause.SERVER);
        TileGrid grid = world.tiles();
        assertFalse(grid.solid(grid.pos(6, 6)));
        door.closed = true;
        world.buildings().solidityChanged(door);
        for(int[] p : new int[][]{{5, 5}, {5, 6}, {6, 5}, {6, 6}}) assertTrue(grid.solid(grid.pos(p[0], p[1])));
        assertFalse(grid.solid(grid.pos(7, 7)));
        assertTrue(TileState.solid(grid.packed(grid.pos(6, 6))));
        world.buildings().remove(door, Cause.SERVER);
        assertFalse(grid.solid(grid.pos(6, 6)), "solidity leaves with the building");
    }

    @Test
    void changeTeamRewritesTheTilesAndTheProximity(){
        Building container = place("container", 10, 10);
        Building friend = place("router", 12, 10), other = place("router", 2, 9, 10, 0);
        assertEquals(List.of(friend), container.proximityList());
        buildings.changeTeam(container, 2, PLAYER);
        assertEquals(List.of(other), container.proximityList());
        for(int[] p : new int[][]{{10, 10}, {10, 11}, {11, 10}, {11, 11}}) assertEquals(2, tiles.team(tiles.pos(p[0], p[1])));
        assertEquals(List.of(container), other.proximityList());
        assertEquals(List.of(container), friend.proximityList(), "vanilla quirk: the old team's neighbour keeps it");
    }

    @Test
    void theJournalKeepsEachTileOncePerCallWithItsCause(){
        int before = world.journal().entries().size();
        place("vault", 10, 10);
        var placed = world.journal().entries().subList(before, world.journal().entries().size());
        assertEquals(9, placed.size(), "one before-image per tile, though setBlock writes some tiles twice");
        assertTrue(placed.stream().allMatch(e -> e.cause().equals(PLAYER) && e.before().block() == 0));
        assertEquals(Set.of(PLAYER), Set.copyOf(placed.stream().map(Journal.Entry::cause).toList()));
        int mark = world.journal().entries().size();
        buildings.setFloor(5, 5, BLOCKS.block("sand-floor"), Cause.SERVER);
        assertEquals(BLOCKS.block("stone").id(), world.journal().entries().get(mark).before().floor());
    }

    @Test
    void eventsArePublishedPerTileAfterTheTick(){
        var observer = world.observe(64);
        place("container", 10, 10);
        assertNull(observer.poll(), "events wait for the end of the tick");
        world.tick();
        var events = observer.drain();
        // pass 0 clears the three linked tiles, then the origin's change fires
        assertEquals(4, events.size(), events.toString());
        var last = (TileEvent.Changed)events.getLast();
        assertEquals(tiles.pos(10, 10), last.pos());
        assertEquals(BLOCKS.block("container").id(), last.block());
        assertEquals(PLAYER, last.cause());
    }

    @Test
    void floorAndOverlayChangesReachTheBuilding(){
        var probes = VanillaWorlds.withKind(KindKey.of("test-probe"), "copper-wall");
        world = VanillaWorlds.filled(probes, 16, 16, "stone");
        world.buildings().setBlock(5, 5, probes.block("copper-wall"), 1, 0, Cause.SERVER);
        take();
        world.buildings().setFloor(5, 5, probes.block("sand-floor"), Cause.SERVER);
        world.buildings().setFloor(5, 5, probes.block("sand-floor"), Cause.SERVER);
        world.buildings().setOverlay(5, 5, probes.block("ore-copper"), Cause.SERVER);
        assertEquals(List.of("5,5:update", "5,5:update"), take(), "an unchanged floor does nothing");
        assertEquals(probes.block("ore-copper").id(), world.tiles().overlay(world.tiles().pos(5, 5)));
    }

    @Test
    void loadingDefersProximityToTheEnd(){
        buildings.beginLoad();
        Building container = place("container", 10, 10), router = place("router", 12, 10);
        assertTrue(container.proximityList().isEmpty());
        int journal = world.journal().entries().size();
        buildings.endLoad(false);
        assertEquals(List.of(router), container.proximityList());
        assertEquals(List.of(container), router.proximityList());
        assertEquals(journal, world.journal().entries().size(), "loads are not journaled");
        assertEquals(0, tiles.pendingChanges());
    }

    @Test
    void eachBlockFindsBuildingsByCentreDistance(){
        Building near = place("vault", 10, 10), far = place("vault", 20, 10), enemy = place("vault", 2, 10, 20, 0);
        place("router", 30, 30);
        var found = new ArrayList<Building>();
        // the vault at 20,10 is 80 units away; within range + size * 4 = 70 + 12 is enough
        assertTrue(buildings.eachBlock(1, 10 * 8f, 10 * 8f, 70f, found::add));
        assertEquals(List.of(near, far), found);
        found.clear();
        buildings.eachBlock(1, 10 * 8f, 10 * 8f, 60f, found::add);
        assertEquals(List.of(near), found);
        found.clear();
        buildings.eachBlock(2, 10 * 8f, 10 * 8f, 200f, found::add);
        assertEquals(List.of(enemy), found);
    }
}
