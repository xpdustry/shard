package com.xpdustry.shard.sim.path;

import arc.struct.*;
import com.xpdustry.shard.common.struct.IntSeq;
import com.xpdustry.shard.sim.logic.*;
import com.xpdustry.shard.sim.tile.*;
import com.xpdustry.shard.sim.world.*;
import mindustry.world.Tile;

import java.util.*;

import static mindustry.Vars.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * What the control pathfinder's oracles share: the bundled maps, a Shard mirror of the hosted map that runs the
 * {@link ControlPathfinder}, and exact comparisons of its clusters and fields with vanilla's.
 */
final class ControlOracle{
    /** The default maps, then the Serpulo sectors. */
    static final List<String> SERPULO = List.of(
        "archipelago", "caldera", "maps/default/canyon.msav", "debrisField", "domain", "fork", "fortress", "glacier", "islands",
        "labyrinth", "maze", "moltenLake", "mudFlats", "passage", "shattered", "tendrils", "triad", "veins", "wasteland",
        "serpulo/atolls", "serpulo/biomassFacility", "serpulo/coastline", "serpulo/crateredBattleground", "serpulo/cruxscape",
        "serpulo/desolateRift", "serpulo/extractionOutpost", "serpulo/facility32m", "serpulo/fallenVessel", "serpulo/frontier",
        "serpulo/frozenForest", "serpulo/fungalPass", "serpulo/geothermalStronghold", "serpulo/groundZero",
        "serpulo/impact0078", "serpulo/infestedCanyons", "serpulo/littoralShipyard", "serpulo/mycelialBastion",
        "serpulo/navalFortress", "serpulo/nuclearComplex", "serpulo/overgrowth", "serpulo/perilousHarbor",
        "serpulo/planetaryTerminal", "serpulo/ruinousShores", "serpulo/saltFlats", "serpulo/stainedMountains",
        "serpulo/sunkenPier", "serpulo/taintedWoods", "serpulo/tarFields", "serpulo/testingGrounds",
        "serpulo/weatheredChannels", "serpulo/windsweptIslands");
    static final List<String> EREKIR = List.of(
        "erekir/aegis", "erekir/atlas", "erekir/basin", "erekir/caldera-erekir", "erekir/crevice", "erekir/crossroads",
        "erekir/intersect", "erekir/karst", "erekir/lake", "erekir/marsh", "erekir/onset", "erekir/origin", "erekir/peaks",
        "erekir/ravine", "erekir/siege", "erekir/split", "erekir/stronghold");

    /**
     * A Shard world with the hosted map, the path service and the control pathfinder (and the logic part its
     * processors need), its paths refreshed under the hosted rules. {@code extra} handlers run in {@code PATH_FLUSH} too.
     */
    static World mirror(PhaseHandler... extra){
        World shard = VanillaMaps.load(PathWorlds.world(world.width(), world.height(), 1, List.of(ControlPathfinder.class, LogicPart.class), extra));
        var grid = shard.tiles();
        for(Tile tile : world.tiles){
            if(tile.build != null && tile.build.tile == tile) shard.buildings().at(grid.pos(tile.x, tile.y)).health = tile.build.health;
        }
        sameSolidity(shard);
        PathService paths = PathWorlds.paths(shard);
        paths.rules(VanillaPaths.rules());
        paths.invalidateAll();
        shard.tick();
        return shard;
    }

    /**
     * The map loader in the oracle makes fresh buildings, not the saved ones: a door saved open comes up closed. Each
     * building's {@code checkSolid()} is copied from vanilla, with a door's {@code open}, before the paths read them.
     */
    static void sameSolidity(World shard){
        var grid = shard.tiles();
        var fixed = new TreeMap<String, Integer>();
        for(Tile tile : world.tiles){
            if(tile.build == null) continue;
            int pos = grid.pos(tile.x, tile.y);
            boolean solid = tile.build.checkSolid();
            if(grid.buildSolid(pos) == solid) continue;
            grid.buildSolid(pos, solid);
            if(shard.buildings().at(pos) instanceof com.xpdustry.shard.sim.blocks.defense.Door door) door.open = !solid;
            fixed.merge(tile.block().name, 1, Integer::sum);
        }
        if(!fixed.isEmpty()) System.out.println("solidity copied from vanilla's saved buildings: " + fixed);
    }

    /** Shard's packed tiles equal vanilla's, in vanilla's bits. */
    static void sameTiles(String map, World shard){
        int[] vanilla = com.xpdustry.shard.oracle.VanillaPathfinder.tiles();
        PathService paths = PathWorlds.paths(shard);
        for(int pos = 0; pos < vanilla.length; pos++){
            int mine = paths.packed(pos) & PathTile.VANILLA_BITS;
            if(mine != vanilla[pos]){
                fail(map + ": packed tile " + (pos % world.width()) + "," + (pos / world.width()) + " is "
                    + Integer.toBinaryString(mine) + ", vanilla " + Integer.toBinaryString(vanilla[pos]));
            }
        }
    }

    /** Every cluster of every team and cost type: the same portals, and the same edges with bit-equal costs. */
    static int sameClusters(String where, mindustry.ai.ControlPathfinder vanilla, ControlPathfinder mine){
        Object[][][] theirs = VanillaControlPath.clusters(vanilla);
        ControlPathfinder.Cluster[][][] ours = mine.clusters();
        int compared = 0;
        for(int team = 0; team < 256; team++){
            Object[][] dim1 = theirs[team];
            ControlPathfinder.Cluster[][] myDim1 = ours[team];
            assertEquals(dim1 == null, myDim1 == null, where + ": team " + team + " has clusters on one side only");
            if(dim1 == null) continue;
            for(int cost = 0; cost < ControlPathfinder.COSTS; cost++){
                Object[] dim2 = dim1[cost];
                ControlPathfinder.Cluster[] myDim2 = myDim1[cost];
                assertEquals(dim2 == null, myDim2 == null, where + ": team " + team + " cost " + cost + " on one side only");
                if(dim2 == null) continue;
                for(int index = 0; index < dim2.length; index++){
                    String at = where + ": team " + team + " cost " + cost + " cluster " + index;
                    assertEquals(dim2[index] == null, myDim2[index] == null, at + " exists on one side only");
                    if(dim2[index] == null) continue;
                    sameCluster(at, dim2[index], myDim2[index]);
                    compared++;
                }
            }
        }
        return compared;
    }

    static void sameCluster(String at, Object cluster, ControlPathfinder.Cluster mine){
        arc.struct.IntSeq[] portals = VanillaControlPath.portals(cluster);
        LongSeq[][] connections = VanillaControlPath.connections(cluster);
        for(int d = 0; d < 4; d++){
            arc.struct.IntSeq theirs = portals[d];
            IntSeq ours = mine.portals[d];
            assertEquals(theirs == null, ours == null, at + " dir " + d + ": portals on one side only");
            if(theirs != null){
                assertArrayEquals(theirs.toArray(), Arrays.copyOf(ours.items, ours.size), at + " dir " + d + ": portals");
            }
            LongSeq[] edges = connections[d];
            ControlPathfinder.Edges[] myEdges = mine.portalConnections[d];
            assertEquals(edges == null, myEdges == null, at + " dir " + d + ": edges on one side only");
            if(edges == null) continue;
            assertEquals(edges.length, myEdges.length, at + " dir " + d + ": edge lists");
            for(int i = 0; i < edges.length; i++){
                assertEquals(edges[i] == null, myEdges[i] == null, at + " dir " + d + " portal " + i + ": edges on one side only");
                if(edges[i] == null) continue;
                assertArrayEquals(edges[i].toArray(), Arrays.copyOf(myEdges[i].items, myEdges[i].size), at + " dir " + d + " portal " + i + ": edges");
            }
        }
    }

    /** Two fields: the same clusters, the same weight on every tile, and the same frontier. */
    static void sameField(String where, Object field, ControlPathfinder.Field mine){
        assertEquals(field == null, mine == null, where + ": field on one side only");
        if(field == null) return;
        IntMap<int[]> theirs = VanillaControlPath.fieldClusters(field);
        int count = 0;
        for(int index = 0; index < mine.fields.length; index++){
            int[] ours = mine.fields[index];
            int[] other = theirs.get(index);
            assertEquals(other == null, ours == null, where + ": field cluster " + index + " on one side only");
            if(ours == null) continue;
            count++;
            assertArrayEquals(other, ours, where + ": field cluster " + index);
        }
        assertEquals(theirs.size, count, where + ": field clusters");
        IntQueue frontier = VanillaControlPath.frontier(field);
        assertEquals(frontier.size, mine.frontier.size, where + ": frontier");
        for(int i = 0; i < frontier.size; i++) assertEquals(frontier.get(i), mine.frontier.get(i), where + ": frontier " + i);
    }

    private ControlOracle(){}
}
