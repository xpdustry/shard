package com.xpdustry.shard.compat.v160.blocks.units;

import org.junit.jupiter.api.*;

import java.util.*;

import static mindustry.Vars.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * What a reconstructor refuses, against vanilla frame by frame through vanilla's save ({@link UnitBlockRun}, as
 * {@link UnitBlockOracleTest}): its front side, a banned unit or upgrade, and an upgrade nobody researched (W4.1's gaps).
 * Units build 27.5 times faster and cost a tenth, on both sides.
 */
class ReconstructorRefusalOracleTest{
    static final float SPEED = UnitBlockOracleTest.SPEED, COST = UnitBlockOracleTest.COST;

    /** Places {@code block} at (x, y) facing east, fed from above. */
    private static void block(UnitBlockRun run, String block, int x, int y, int size, List<String> items, List<String> liquids){
        run.place(block, x, y, 0).feed(x, y, size, items, liquids);
    }

    /**
     * What a reconstructor refuses (W4.1's gaps), with daggers built 27.5 times faster and mace and flare banned on both
     * sides from the start:
     * <ul>
     *   <li>a ground factory pushes its novas straight into a reconstructor's front (it faces west, towards the
     *   factory): {@code acceptPayload} refuses its front side, and the factory holds the nova;</li>
     *   <li>a second row's daggers reach a reconstructor from behind, but their upgrade (mace) is banned: refused too;</li>
     *   <li>a dagger handed straight to a third reconstructor ({@code handlePayload}, past {@code acceptPayload}) is
     *   upgraded into a banned mace all the same, because {@code hasUpgrade} asks whether the dagger is banned, not the
     *   mace (Reconstructor.java:399); the mace is then held for good, as {@code Units.canCreate} refuses a banned type;</li>
     *   <li>a flare handed to a fourth is banned itself: {@code hasUpgrade} is false, so it isn't upgraded, and it is held
     *   for the same reason.</li>
     * </ul>
     * Vanilla saves at frame 350; both run to 700.
     */
    @Test
    void aReconstructorRefusesItsFrontAndBannedUnits(){
        var run = new UnitBlockRun(30, 56, pos -> "stone", Map.of()).unitRules(SPEED, COST, -1).ban("mace").ban("flare");
        List<String> upgrade = List.of("silicon", "graphite");
        // the front: the reconstructor faces the factory, which pushes into it (movePayload asks acceptPayload; a
        // payload conveyor facing a block's front never asks, payloadCheck stops it first, Building.java:735)
        // novas, whose upgrade (pulsar) isn't banned, so only the side refuses them
        block(run, "ground-factory", 6, 8, 3, List.of("silicon", "lead", "titanium"), List.of());
        run.configure(6, 8, mindustry.content.UnitTypes.nova,
            new com.xpdustry.shard.sim.building.ContentRef(com.xpdustry.shard.content.ContentType.UNIT, UnitBlockRun.EPOCH.unit("nova").id()));
        run.place("additive-reconstructor", 9, 8, 2).feed(9, 8, 3, upgrade, List.of());
        // a banned upgrade
        block(run, "ground-factory", 6, 20, 3, List.of("silicon", "lead"), List.of());
        run.place("payload-conveyor", 9, 20, 0);
        block(run, "additive-reconstructor", 12, 20, 3, upgrade, List.of());
        // handed over: a dagger (banned upgrade) and a flare (banned itself)
        block(run, "additive-reconstructor", 12, 32, 3, upgrade, List.of());
        block(run, "additive-reconstructor", 12, 44, 3, upgrade, List.of());
        run.at(2, () -> {
            run.feedUnit(12, 32, "dagger");
            run.feedUnit(12, 44, "flare");
        });
        run.saveAt(350).run(700);

        assertTrue(run.seen("holding in ground-factory") > 300, "the factories were held up behind the refused daggers: " + run.seen);
        var factory = (mindustry.world.blocks.units.UnitFactory.UnitFactoryBuild)world.build(6, 8);
        assertTrue(factory.payload != null && factory.payload.unit.type.name.equals("nova"), "the factory holds the nova the front refused");
        assertNull(((mindustry.world.blocks.units.Reconstructor.ReconstructorBuild)world.build(9, 8)).payload, "the reconstructor facing it took nothing");
        var conveyor = (mindustry.world.blocks.payloads.PayloadConveyor.PayloadConveyorBuild)world.build(9, 20);
        assertTrue(conveyor.item instanceof mindustry.world.blocks.payloads.UnitPayload u && u.unit.type.name.equals("dagger"),
            "a dagger whose upgrade is banned waits on the conveyor");
        assertNull(((mindustry.world.blocks.units.Reconstructor.ReconstructorBuild)world.build(12, 20)).payload, "its reconstructor took nothing");
        // a banned unit never leaves: moveOutPayload's dump asks Units.canCreate, which is false for a banned type
        var third = (mindustry.world.blocks.units.Reconstructor.ReconstructorBuild)world.build(12, 32);
        assertTrue(third.payload != null && third.payload.unit.type.name.equals("mace"), "the handed dagger became a banned mace, held: " + run.seen);
        var fourth = (mindustry.world.blocks.units.Reconstructor.ReconstructorBuild)world.build(12, 44);
        assertTrue(fourth.payload != null && fourth.payload.unit.type.name.equals("flare"), "the banned flare was held, not upgraded: " + run.seen);
        assertEquals(0, run.seen("made horizon"), "nothing upgraded the flare: " + run.seen);
        assertEquals(1, run.seen("loaded"), "through a save");
    }

    /**
     * An unresearched upgrade, in the campaign (serpulo's sector 15, where a server has researched nothing): a dagger
     * on a payload conveyor reaches a reconstructor from behind and is refused ({@code acceptPayload}'s
     * {@code unlockedNowHost}); a dagger handed straight to another is pushed out unupgraded ({@code hasUpgrade}); crux,
     * an AI team in the campaign, may build what it hasn't researched, so its reconstructor takes a dagger from its
     * conveyor and makes a mace, which a wall in front keeps inside. Vanilla saves at frame 300; both run to 600.
     */
    @Test
    void anUnresearchedUpgradeIsRefusedOutsideAnAiTeam(){
        var run = new UnitBlockRun(30, 44, pos -> "stone", Map.of()).unitRules(SPEED, COST, -1).campaign("serpulo", 15);
        List<String> upgrade = List.of("silicon", "graphite");
        run.place("payload-conveyor", 6, 8, 0);
        block(run, "additive-reconstructor", 9, 8, 3, upgrade, List.of());
        block(run, "additive-reconstructor", 9, 20, 3, upgrade, List.of());
        run.team(mindustry.game.Team.crux);
        run.place("payload-conveyor", 6, 32, 0);
        block(run, "additive-reconstructor", 9, 32, 3, upgrade, List.of());
        run.place("copper-wall", 11, 32, 0);
        run.at(2, () -> {
            run.feedUnit(6, 8, "dagger");
            run.feedUnit(9, 20, "dagger");
            run.feedUnit(6, 32, "dagger");
        });
        run.saveAt(300).run(600);

        assertTrue(state.isCampaign(), "still the campaign after the save");
        assertTrue(((mindustry.world.blocks.payloads.PayloadConveyor.PayloadConveyorBuild)world.build(6, 8)).item
            instanceof mindustry.world.blocks.payloads.UnitPayload, "the sharded dagger waits on its conveyor");
        assertNull(((mindustry.world.blocks.units.Reconstructor.ReconstructorBuild)world.build(9, 8)).payload, "its reconstructor refused it");
        mindustry.gen.Unit pushed = run.vanillaUnit("dagger");
        assertTrue(pushed != null && pushed.isAdded() && pushed.x > 9.5f * 8 && Math.abs(pushed.y - 20 * 8) < 3 * 8,
            "the handed sharded dagger was pushed out unupgraded: " + pushed);
        var crux = (mindustry.world.blocks.units.Reconstructor.ReconstructorBuild)world.build(9, 32);
        assertTrue(crux.payload != null && crux.payload.unit.type.name.equals("mace"), "crux made a mace: " + run.seen);
        assertEquals(1, run.seen("loaded"), "through a save");
    }
}
