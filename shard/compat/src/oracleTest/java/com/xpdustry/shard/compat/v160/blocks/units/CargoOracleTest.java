package com.xpdustry.shard.compat.v160.blocks.units;

import com.xpdustry.shard.sim.ai.*;
import com.xpdustry.shard.sim.blocks.units.*;
import com.xpdustry.shard.sim.unit.*;
import mindustry.game.*;
import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * L1 for the cargo blocks and {@code CargoAI}, and for {@code PrebuildAI} (W4.7), against vanilla frame by frame
 * ({@link CargoRun}): buildings, units, their AIs, the clock, the random, entity ids and Calls, then on through
 * vanilla's save, with every building's save chunk byte-equal.
 */
class CargoOracleTest{
    /** The loader's origin (3×3), and the three unload points' (2×2). */
    private static final int LX = 10, LY = 24, P1X = 30, P1Y = 32, P2X = 30, P2Y = 14, P3X = 38, P3Y = 24;

    /**
     * Erekir: a cargo loader, powered by a power source and fed nitrogen by a liquid source, takes beryllium down a
     * duct and graphite along another. It builds its manifold, which carries to three unload points: two for
     * beryllium, one for graphite. The points by an item void empty themselves; the first beryllium point has no
     * neighbour, fills and goes stale, and the manifold moves on to the other. The graphite source is removed at
     * frame 900, so beryllium comes to dominate. After frame 1,600, while the manifold
     * flies beryllium to the second beryllium point, that point is reconfigured to graphite, and the manifold gives it
     * up. Vanilla saves at frame 1,100; both run to 2,000.
     */
    @Test
    void aManifoldCarriesToThreeUnloadPoints(){
        var run = new CargoRun(48, Map.of(), r -> {}, r -> {});
        run.place("unit-cargo-loader", LX, LY, 0);
        run.place("power-source", LX - 2, LY, 0);
        run.place("liquid-source", LX, LY - 2, 0).configureLiquid(LX, LY - 2, "nitrogen");
        // beryllium, down a duct into the loader's top
        run.place("item-source", LX, LY + 6, 0).configureItem(LX, LY + 6, "beryllium");
        for(int y = LY + 5; y >= LY + 2; y--) run.place("duct", LX, y, 3);
        // graphite, along a duct into its right side
        run.place("item-source", LX + 5, LY, 0).configureItem(LX + 5, LY, "graphite");
        for(int x = LX + 4; x >= LX + 2; x--) run.place("duct", x, LY, 2);
        // the points: beryllium with nowhere to go, beryllium and graphite each by an item void
        run.place("unit-cargo-unload-point", P1X, P1Y, 0).configureItem(P1X, P1Y, "beryllium");
        run.place("unit-cargo-unload-point", P2X, P2Y, 0).configureItem(P2X, P2Y, "beryllium");
        run.place("item-void", P2X + 2, P2Y, 0);
        run.place("unit-cargo-unload-point", P3X, P3Y, 0).configureItem(P3X, P3Y, "graphite");
        run.place("item-void", P3X + 2, P3Y, 0);
        // graphite stops: from then on the loader holds mostly beryllium, with a stale and a live point for it
        run.at(900, () -> run.remove(LX + 5, LY));

        Set<String> carried = new TreeSet<>();
        boolean[] stale = {false}, full = {false}, gaveUp = {false};
        int[] carrying = {0}, prank = {-1};
        run.watch((r, frame) -> {
            for(int i = 0; i < r.shard.units().size(); i++){
                Unit unit = r.shard.units().get(i);
                if(unit.controller instanceof CargoAi ai && unit.hasItem()){
                    carrying[0]++;
                    if(ai.unloadTarget != null) carried.add(CargoRun.EPOCH.item(unit.item()).name() + "->" + ai.unloadTarget.tileX() + "," + ai.unloadTarget.tileY());
                    boolean toP2 = ai.unloadTarget != null && ai.unloadTarget.tileX() == P2X && ai.unloadTarget.tileY() == P2Y;
                    // the prankster: once past the save, P2 turns to graphite under a manifold flying beryllium to it
                    if(prank[0] == -1 && frame >= 1_600 && toP2 && !unit.within(ai.unloadTarget.x(), ai.unloadTarget.y(), CargoAi.TRANSFER_RANGE)){
                        prank[0] = frame + 1;
                        r.at(frame + 1, () -> r.configureItem(P2X, P2Y, "graphite"));
                    }
                    if(frame == prank[0] && !toP2) gaveUp[0] = true;
                }
            }
            if(r.shard.buildings().at(P1X, P1Y) instanceof UnitCargoUnloadPoint p){
                stale[0] |= p.stale;
                full[0] |= p.items.total() >= 100;
            }
        });
        run.saveAt(1_100).run(2_000);

        System.out.println("cargo: Calls " + run.calls + ", seen " + run.seen + ", carried " + carried + ", unit-frames carrying " + carrying[0]);
        assertEquals(1, run.seen("loaded"), "through a save");
        assertEquals(1, run.seen("Call UnitTetherBlockSpawned"), "the loader made its manifold: " + run.seen);
        assertTrue(run.seen("Call TakeItems") >= 4, "the manifold took stacks: " + run.seen);
        assertTrue(run.seen("Call TransferItemTo") >= 4, "the manifold put stacks in: " + run.seen);
        assertTrue(full[0] && stale[0], "the point with nowhere to go filled and went stale");
        assertTrue(prank[0] != -1 && gaveUp[0], "the manifold gave up the point reconfigured under it, at frame " + prank[0]);
        assertTrue(carried.contains("beryllium->" + P1X + "," + P1Y) && carried.contains("beryllium->" + P2X + "," + P2Y)
            && carried.contains("graphite->" + P3X + "," + P3Y), "each point was flown to with its item: " + carried);
        assertTrue(carrying[0] > 300, "the manifold spent its time carrying: " + carrying[0]);
    }

    /**
     * {@code PrebuildAI}: crux, an AI team with the {@code prebuildAi} rule and a core holding 5 copper, gets its core
     * unit from the prebuild spawn (an alpha, whose {@code BuilderAI} hands over to {@code PrebuildAI}). Its plans: a
     * conveyor by the core, a mechanical drill on copper ore 14 tiles off, a duo far from anything. It takes the drill
     * (production weighs most), can't pay for it, mines copper (with 25 given at frame 3), brings the stack back, and
     * walks out to build it. Vanilla saves at frame 400; both run to 1,200.
     */
    @Test
    void aPrebuildAlphaMinesForItsPlanAndBuildsIt(){
        int size = 40;
        var ores = new HashMap<Integer, String>();
        for(int x = 33; x <= 36; x++) for(int y = 18; y <= 23; y++) ores.put(y * size + x, "ore-copper");
        var run = new CargoRun(size, ores, r -> {
            r.waves = true;
            r.teams.get(Team.crux).prebuildAi = true;
        }, r -> {
            r.waves = true;
            r.teams.get(Team.crux.id).prebuildAi = true;
        });
        run.place("core-shard", 20, 20, 0, Team.crux);
        run.stock(20, 20, "copper", 5 - mindustry.Vars.world.build(20, 20).items.get(mindustry.content.Items.copper));
        run.plan(Team.crux, "conveyor", 22, 20, 2);
        run.plan(Team.crux, "mechanical-drill", 34, 20, 0);
        run.plan(Team.crux, "duo", 10, 32, 0);
        run.at(3, () -> run.giveItem("alpha", "copper", 25));

        boolean[] collecting = {false}, mined = {false};
        run.watch((r, frame) -> {
            for(int i = 0; i < r.shard.units().size(); i++){
                Unit unit = r.shard.units().get(i);
                if(unit.controller instanceof AiController ai && ai.fallback instanceof PrebuildAi p){
                    collecting[0] |= p.collectingItems;
                    mined[0] |= unit.mineTile != -1 && unit.mineTimer > 0f;
                }
            }
        });
        run.saveAt(400).run(1_200);

        System.out.println("prebuild: Calls " + run.calls + ", seen " + run.seen);
        assertEquals(1, run.seen("loaded"), "through a save");
        assertTrue(run.seen("Call UnitSpawn") >= 1, "the core unit was spawned: " + run.seen);
        assertTrue(collecting[0] && mined[0], "the alpha collected items for its plan, mining");
        assertTrue(run.seen("Call TransferItemTo") >= 1, "it brought its stack back: " + run.seen);
        assertTrue(run.seen("Call BeginPlace") >= 1 && run.seen("Call ConstructFinish") >= 1, "it built its plan: " + run.seen);
        assertTrue(run.shard.buildings().at(34, 20) != null && run.shard.buildings().at(34, 20).block().name().equals("mechanical-drill"),
            "the drill stands");
    }
}
