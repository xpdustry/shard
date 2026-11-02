package com.xpdustry.shard.compat.v160.blocks;

import com.xpdustry.shard.oracle.*;
import mindustry.game.*;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Gate (WP37): power graphs as vanilla's, every frame. Each fixture is built and edited in a vanilla world and a Shard
 * one on the same frames, and {@link PowerRun} compares graph membership and order, satisfaction, battery levels, links,
 * the power graph group, the random generator and the entity id counter after every frame, then every save chunk.
 * Placements that autolink go through {@code placed()}, as construction finishing does.
 */
class PowerOracleTest{
    @BeforeAll
    static void boot(){
        Vanilla.boot();
    }

    /**
     * M0's power consumer: a combustion generator burning coal from a source powers two illuminators through a node,
     * with a battery for the surplus; the coal stops for a while, so the battery carries them and runs dry.
     */
    @Test
    void aGeneratorPowersAConsumer(){
        var run = new PowerRun(16, 8);
        run.place("item-source", 1, 1);
        run.source(1, 1, "coal");
        run.build("combustion-generator", 2, 1);
        run.build("illuminator", 8, 1);
        run.build("illuminator", 9, 4);
        run.build("battery", 3, 1);
        run.build("power-node", 5, 3);
        run.at(250, () -> run.enabled(1, 1, false));
        run.at(520, () -> run.enabled(1, 1, true));
        run.run(600);
        assertTrue(run.vanilla(8, 1).efficiency > 0f, "the illuminator runs at the end");
        assertEquals(run.vanilla(8, 1).efficiency, run.ours(8, 1).efficiency);
    }

    /**
     * Two generators charge a battery bank (two batteries side by side and a large one) feeding four illuminators;
     * the coal stops on one, then both, so the bank drains; one battery is disabled by logic for a while.
     */
    @Test
    void batteriesChargeAndDrain(){
        var run = new PowerRun(20, 10);
        run.place("item-source", 1, 2);
        run.place("item-source", 1, 5);
        run.source(1, 2, "coal");
        run.source(1, 5, "spore-pod");
        run.build("combustion-generator", 2, 2);
        run.build("combustion-generator", 2, 5);
        run.build("battery", 4, 2);
        run.build("battery", 4, 3);
        run.build("battery-large", 6, 3);
        run.status(4, 3, 0.5f);
        for(int y = 1; y <= 7; y += 2) run.build("illuminator", 12, y);
        run.build("power-node", 9, 5);
        run.build("power-node", 4, 6);
        run.at(120, () -> run.enabled(1, 2, false));
        run.at(300, () -> run.enabled(1, 5, false));
        run.at(450, () -> {
            run.enabled(1, 2, true);
            run.enabled(1, 5, true);
        });
        run.at(500, () -> run.enabled(6, 3, false));
        run.at(600, () -> run.enabled(6, 3, true));
        run.run(700);
        assertTrue(run.ours(6, 3).power.status > 0f, "the large battery holds a charge");
    }

    /** Two grids, then a large node between them that links into both, merging them; then neighbours join by contact. */
    @Test
    void nodesMergeGraphs(){
        var run = new PowerRun(24, 10);
        run.place("item-source", 1, 1);
        run.source(1, 1, "coal");
        run.build("combustion-generator", 2, 1);
        run.build("illuminator", 3, 1);
        run.build("power-node", 3, 4);
        run.place("item-source", 22, 1);
        run.source(22, 1, "coal");
        run.build("combustion-generator", 21, 1);
        run.build("illuminator", 20, 1);
        run.build("illuminator", 20, 2);
        run.at(60, () -> run.build("power-node-large", 11, 4));
        run.at(150, () -> run.build("battery", 21, 2));
        run.at(200, () -> run.build("illuminator", 11, 8));
        run.at(260, () -> run.build("battery", 4, 4));
        run.run(400);
        assertSame(run.ours(2, 1).power.graph, run.ours(21, 1).power.graph, "one graph");
    }

    /**
     * A chain of nodes and batteries cut apart: a node removed, a battery killed, then the node built again and the
     * generator removed. Explosions don't damage, so the kill makes no explosion (the building base's, not ported).
     */
    @Test
    void breakingSplitsGraphs(){
        var run = new PowerRun(24, 10);
        run.rules(r -> r.damageExplosions = false, r -> r.damageExplosions = false);
        run.place("item-source", 1, 4);
        run.source(1, 4, "coal");
        run.build("combustion-generator", 2, 4);
        run.build("power-node", 4, 6);
        run.build("power-node", 9, 6);
        run.build("power-node", 14, 6);
        run.build("illuminator", 16, 4);
        run.build("battery", 17, 4);
        run.build("battery", 18, 4);
        run.build("illuminator", 19, 4);
        run.at(100, () -> run.remove(9, 6));
        run.at(200, () -> run.kill(17, 4));
        run.at(300, () -> run.build("power-node", 9, 6));
        run.at(350, () -> run.remove(2, 4));
        run.run(450);
        assertNotSame(run.ours(16, 4).power.graph, run.ours(18, 4).power.graph, "the killed battery split the chain");
        assertSame(run.ours(4, 6).power.graph, run.ours(16, 4).power.graph, "the rebuilt node joined the chain again");
    }

    /**
     * A node placed among more candidates than it may link: equidistant consumers, an adjacent one, one behind an
     * insulated wall, one of another team, two batteries of one graph. Then a consumer and a second node join it.
     */
    @Test
    void autolinkOnPlacement(){
        var run = new PowerRun(24, 18);
        run.place("item-source", 1, 1);
        run.source(1, 1, "coal");
        run.build("combustion-generator", 2, 1);
        int[][] around = {{7, 8}, {13, 8}, {10, 5}, {10, 11}, {8, 10}, {12, 6}, {12, 10}, {8, 6}, {11, 8}, {6, 12}, {14, 4}, {5, 5}};
        for(int[] at : around) run.build("illuminator", at[0], at[1]);
        run.place("plastanium-wall", 10, 13);
        run.build("illuminator", 10, 14);
        run.build("illuminator", 6, 7, 0, Team.crux);
        run.build("battery", 15, 10);
        run.build("battery", 15, 11);
        run.build("power-node", 10, 8);
        run.at(30, () -> run.build("illuminator", 9, 13));
        run.at(60, () -> run.build("power-node", 16, 8));
        run.at(90, () -> run.build("power-node", 4, 2));
        run.run(150);
        assertTrue(run.ours(10, 8).power.links.size >= 8, "the node linked many: " + run.ours(10, 8).power.links);
    }

    /** A node's links set as a whole ({@code Point2[]}) and toggled one by one, out of range and from the other end. */
    @Test
    void linkConfigsRoundTrip(){
        var run = new PowerRun(24, 14);
        run.place("item-source", 1, 1);
        run.source(1, 1, "coal");
        run.build("combustion-generator", 2, 1);
        run.place("power-node", 5, 5);
        run.place("illuminator", 8, 5);
        run.place("illuminator", 5, 9);
        run.place("illuminator", 3, 2);
        run.place("power-node", 10, 5);
        run.place("illuminator", 20, 5);
        run.at(10, () -> run.links(5, 5, 3, 0, 0, 4, -2, -4, -3, -3));
        run.at(20, () -> run.link(5, 5, 8, 5));
        run.at(30, () -> run.link(5, 5, 10, 5));
        run.at(40, () -> run.link(5, 5, 20, 5));
        run.at(50, () -> run.links(5, 5));
        run.at(60, () -> run.links(5, 5, 5, 0, 3, 0, -2, -4));
        run.at(70, () -> run.link(10, 5, 5, 5));
        run.at(80, () -> run.link(10, 5, 20, 5));
        run.run(120);
        // the last set links (10,5) and (8,5); (10,5) then unlinked itself from its end
        assertArrayEquals(new int[]{com.xpdustry.shard.common.geom.Point2.pack(8, 5)}, run.ours(5, 5).power.links.toArray());
    }

    /** Diodes between two battery banks: solar panels fill the back one, which the forward diode drains into the front. */
    @Test
    void diodesMoveStoredPower(){
        var run = new PowerRun(16, 8);
        for(int x = 1; x <= 3; x++) run.place("solar-panel", x, 1);
        run.place("battery", 4, 1);
        run.place("battery", 4, 2);
        run.place("diode", 5, 1, 0, Team.sharded);
        run.place("battery", 6, 1);
        run.place("battery", 7, 1);
        run.place("illuminator", 8, 1);
        run.place("diode", 5, 2, 2, Team.sharded);
        run.place("battery", 6, 2);
        run.status(4, 1, 1f);
        run.status(4, 2, 0.75f);
        run.status(6, 2, 0.25f);
        run.at(150, () -> run.enabled(5, 1, false));
        run.at(220, () -> run.enabled(5, 1, true));
        run.run(300);
        assertTrue(run.ours(7, 1).power.status > 0f, "the front bank was charged through the diode");
    }

    /** A node, then a battery, change team inside a grid and back: their old graphs reflow. */
    @Test
    void teamChangesReflow(){
        var run = new PowerRun(16, 10);
        run.place("item-source", 1, 3);
        run.source(1, 3, "coal");
        run.build("combustion-generator", 2, 3);
        run.build("power-node", 4, 5);
        run.build("illuminator", 6, 3);
        run.build("battery", 7, 3);
        run.build("battery", 8, 3);
        run.build("illuminator", 9, 3);
        run.build("power-node", 10, 6);
        run.at(100, () -> run.team(4, 5, Team.crux));
        run.at(200, () -> run.team(7, 3, Team.crux));
        run.at(300, () -> run.team(4, 5, Team.sharded));
        run.run(400);
    }

    /** Insulated walls stop autolinks, but not a link set by config. */
    @Test
    void insulationStopsAutolinks(){
        var run = new PowerRun(16, 10);
        run.place("item-source", 1, 1);
        run.source(1, 1, "coal");
        run.build("combustion-generator", 2, 1);
        run.build("illuminator", 4, 1);
        for(int y = 0; y < 10; y++) run.place("plastanium-wall", 7, y);
        run.build("illuminator", 10, 4);
        run.build("power-node", 5, 4);
        run.at(20, () -> run.link(5, 4, 10, 4));
        run.at(40, () -> run.build("power-node", 9, 6));
        run.run(80);
        assertTrue(run.ours(5, 4).power.links.contains(com.xpdustry.shard.common.geom.Point2.pack(10, 4)), "a config links across the wall");
    }

    /**
     * Beam nodes cast to the first powered building in each direction, past laser nodes and plain walls, stopped by
     * insulation; walls built and broken in the way, a battery (which never counts as added) taking a beam and
     * leaving, and a beam tower reaching a node.
     */
    @Test
    void beamNodesFollowTileChanges(){
        var run = new PowerRun(32, 16);
        run.place("battery", 5, 9);
        run.status(5, 9, 1f);
        run.place("beam-node", 5, 5);
        run.place("illuminator", 9, 5);
        run.place("power-node", 5, 3);
        run.place("illuminator", 5, 1);
        run.place("plastanium-wall", 3, 5);
        run.place("illuminator", 1, 5);
        run.place("copper-wall", 7, 5);
        run.place("beam-tower", 20, 6);
        run.place("illuminator", 20, 12);
        run.at(30, () -> run.place("plastanium-wall", 8, 5));
        run.at(60, () -> run.remove(8, 5));
        run.at(90, () -> run.place("battery", 8, 5));
        run.at(120, () -> run.remove(8, 5));
        run.at(150, () -> run.place("beam-node", 12, 5));
        run.at(180, () -> run.team(9, 5, Team.crux));
        run.at(210, () -> run.place("battery", 16, 6));
        run.run(260);
        var beam = (com.xpdustry.shard.sim.blocks.power.BeamNode)run.ours(5, 5);
        assertNull(beam.links[2], "the beam west stops at the insulated wall");
        assertSame(run.ours(5, 1), beam.links[3], "the beam south passes the laser node");
    }

    /** A cheating team's consumers are always fully powered, whatever the graph has. */
    @Test
    void cheatingTeamsAreFullyPowered(){
        var run = new PowerRun(12, 6);
        run.place("illuminator", 2, 2);
        run.place("illuminator", 3, 2);
        run.place("battery", 4, 2);
        run.at(40, () -> run.rules(r -> r.teams.get(Team.sharded).cheat = true, r -> r.teams.get(Team.sharded.id).cheat = true));
        run.at(80, () -> run.rules(r -> r.teams.get(Team.sharded).cheat = false, r -> r.teams.get(Team.sharded.id).cheat = false));
        run.run(120);
    }
}
