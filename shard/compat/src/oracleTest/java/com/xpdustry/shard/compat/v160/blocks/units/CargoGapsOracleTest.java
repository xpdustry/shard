package com.xpdustry.shard.compat.v160.blocks.units;

import com.xpdustry.shard.sim.ai.*;
import com.xpdustry.shard.sim.blocks.units.*;
import com.xpdustry.shard.sim.unit.*;
import mindustry.game.*;
import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * W4.7's untested cargo and prebuild cases against vanilla, frame by frame through vanilla's save ({@link CargoRun}, as
 * {@link CargoOracleTest}): a stale unload point passed over, a destroyed one flown to, and where {@code PrebuildAI}'s
 * ore search starts.
 */
class CargoGapsOracleTest{
    /** The loader's origin (3×3), and the two unload points' (2×2), as {@link CargoOracleTest}'s first two. */
    private static final int LX = 10, LY = 24, P1X = 30, P1Y = 32, P2X = 30, P2Y = 14;

    /**
     * W4.7's gaps on the manifold: two unload points for beryllium, the first with no neighbour, the second by an item
     * void, so the manifold alternates between them ({@code findDropTarget} moves its index on each emptied stack).
     * <ul>
     *   <li>Stale points taken last: the first time the manifold heads back empty with its index on the first point,
     *   that point is filled and its stale timer set a frame short of {@code staleTimeDuration}, so it goes stale before
     *   the manifold picks up; {@code findAnyTarget} then passes over it (its {@code !target.stale}) for the second.</li>
     *   <li>A destroyed point: after the save, while the manifold flies beryllium to the second point, that point is
     *   destroyed ({@code Call.buildDestroyed}). Vanilla's {@code CargoAI} doesn't check that its target is still
     *   valid, so the manifold flies on to it.</li>
     * </ul>
     * Vanilla saves at frame 1,100; both run to 2,000.
     */
    @Test
    void aManifoldPassesOverAStalePointAndOutlivesADestroyedOne(){
        var run = new CargoRun(48, Map.of(), r -> {}, r -> {});
        run.place("unit-cargo-loader", LX, LY, 0);
        run.place("power-source", LX - 2, LY, 0);
        run.place("liquid-source", LX, LY - 2, 0).configureLiquid(LX, LY - 2, "nitrogen");
        run.place("item-source", LX, LY + 6, 0).configureItem(LX, LY + 6, "beryllium");
        for(int y = LY + 5; y >= LY + 2; y--) run.place("duct", LX, y, 3);
        run.place("unit-cargo-unload-point", P1X, P1Y, 0).configureItem(P1X, P1Y, "beryllium");
        run.place("unit-cargo-unload-point", P2X, P2Y, 0).configureItem(P2X, P2Y, "beryllium");
        run.place("item-void", P2X + 2, P2Y, 0);

        int[] staled = {-1}, skipped = {-1}, killed = {-1}, flownToDead = {0};
        run.watch((r, frame) -> {
            var p1 = (UnitCargoUnloadPoint)r.shard.buildings().at(P1X, P1Y);
            for(int i = 0; i < r.shard.units().size(); i++){
                Unit unit = r.shard.units().get(i);
                if(!(unit.controller instanceof CargoAi ai)) continue;
                boolean toP2 = ai.unloadTarget != null && ai.unloadTarget.tileX() == P2X && ai.unloadTarget.tileY() == P2Y;
                boolean toP1 = ai.unloadTarget != null && ai.unloadTarget.tileX() == P1X && ai.unloadTarget.tileY() == P1Y;
                // heading back empty from the second point: findDropTarget left the first as its target, and its index as
                // targetIndex, which the next pickup's findAnyTarget looks at first
                if(staled[0] == -1 && frame >= 100 && !unit.hasItem() && toP1 && !p1.stale && !unit.within(r.shard.buildings().at(LX, LY).x(),
                    r.shard.buildings().at(LX, LY).y(), CargoAi.TRANSFER_RANGE * 3)){
                    staled[0] = frame + 1;
                    r.at(frame + 1, () -> {
                        r.stock(P1X, P1Y, "beryllium", 100 - mindustry.Vars.world.build(P1X, P1Y).items.total());
                        float almost = ((mindustry.world.blocks.units.UnitCargoUnloadPoint)mindustry.Vars.world.build(P1X, P1Y).block).staleTimeDuration - 1f;
                        ((mindustry.world.blocks.units.UnitCargoUnloadPoint.UnitCargoUnloadPointBuild)mindustry.Vars.world.build(P1X, P1Y)).staleTimer = almost;
                        ((UnitCargoUnloadPoint)r.shard.buildings().at(P1X, P1Y)).staleTimer = almost;
                    });
                }
                // the first pickup after: the stale first point is passed over
                if(staled[0] != -1 && frame > staled[0] && skipped[0] == -1 && unit.hasItem()) skipped[0] = p1.stale && toP2 ? frame : -2;
                if(killed[0] == -1 && skipped[0] != -1 && frame >= 1_300 && unit.hasItem() && toP2
                    && !unit.within(ai.unloadTarget.x(), ai.unloadTarget.y(), CargoAi.TRANSFER_RANGE * 2)){
                    killed[0] = frame + 1;
                    r.at(frame + 1, () -> r.kill(P2X, P2Y));
                }
                if(killed[0] != -1 && frame > killed[0] && toP2) flownToDead[0]++;
            }
        });
        run.saveAt(1_100).run(2_000);

        System.out.println("stale and destroyed: Calls " + run.calls + ", seen " + run.seen + ", staled at " + staled[0] + ", skipped at " + skipped[0]
            + ", killed at " + killed[0] + ", frames flown to the dead point " + flownToDead[0]);
        assertEquals(1, run.seen("loaded"), "through a save");
        assertTrue(staled[0] != -1 && skipped[0] > staled[0], "the manifold passed over the stale first point for the second: " + staled[0] + ", " + skipped[0]);
        assertTrue(killed[0] > 1_100 && run.seen("Call BuildDestroyed") == 1, "the second point was destroyed under the manifold after the save: " + killed[0]);
        assertTrue(killed[0] + 300 <= 2_000, "the run went on 300 frames past the destruction: " + killed[0]);
    }

    /**
     * W4.7's gap on {@code PrebuildAI}'s ore search ({@code indexer.findClosestOre(unit, item)}, PrebuildAI.java:210):
     * the closest ore to the unit, not to the core. The indexer measures to the first ore tile of each 20-tile quadrant
     * (BlockIndexer.java:527). Crux's core stands between two copper patches, whose
     * nearest indexed tiles are 12 tiles west and 13 east; its drill plan is on the east patch. The alpha mines the west patch, the closer to the core and to
     * itself; once it does, it is held next to the east patch for 61 frames, and its next search (every 60 frames) takes
     * the east patch, though the west one is still closer to the core. Vanilla saves at frame 400; both run to 900.
     */
    @Test
    void thePrebuildOreSearchStartsFromTheUnit(){
        int size = 40;
        var ores = new HashMap<Integer, String>();
        for(int y = 18; y <= 23; y++){
            for(int x = 8; x <= 11; x++) ores.put(y * size + x, "ore-copper");
            for(int x = 33; x <= 36; x++) ores.put(y * size + x, "ore-copper");
        }
        var run = new CargoRun(size, ores, r -> {
            r.waves = true;
            r.teams.get(Team.crux).prebuildAi = true;
        }, r -> {
            r.waves = true;
            r.teams.get(Team.crux.id).prebuildAi = true;
        });
        run.place("core-shard", 20, 20, 0, Team.crux);
        run.stock(20, 20, "copper", 5 - mindustry.Vars.world.build(20, 20).items.get(mindustry.content.Items.copper));
        run.plan(Team.crux, "mechanical-drill", 34, 20, 0);

        int[] moved = {-1}, west = {0}, eastAfter = {0};
        run.watch((r, frame) -> {
            for(int i = 0; i < r.shard.units().size(); i++){
                Unit unit = r.shard.units().get(i);
                if(!(unit.controller instanceof AiController ai) || !(ai.fallback instanceof PrebuildAi p) || p.ore == -1) continue;
                int ox = com.xpdustry.shard.common.geom.Point2.x(p.ore);
                if(ox <= 11) west[0]++;
                if(ox >= 33 && moved[0] != -1) eastAfter[0]++;
                if(moved[0] == -1 && ox <= 11 && unit.mineTile != -1){
                    moved[0] = frame + 1;
                    // held there for a whole search interval (60 frames), as a client's position would hold it
                    for(int f = frame + 1; f <= frame + 61; f++) r.at(f, () -> r.move("alpha", 30 * 8f, 26 * 8f));
                }
            }
        });
        run.saveAt(400).run(900);

        System.out.println("ore search: Calls " + run.calls + ", seen " + run.seen + ", moved at " + moved[0] + ", west " + west[0] + ", east after " + eastAfter[0]);
        assertEquals(1, run.seen("loaded"), "through a save");
        assertTrue(west[0] > 0 && moved[0] != -1, "the alpha mined the west patch first, by the core");
        assertTrue(eastAfter[0] > 0, "moved by the east patch, it searched from where it stood");
    }
}
