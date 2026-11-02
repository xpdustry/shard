package com.xpdustry.shard.compat.v160.command;

import com.xpdustry.shard.sim.ai.CommandAi;
import com.xpdustry.shard.sim.blocks.storage.Core;
import com.xpdustry.shard.sim.building.Building;
import com.xpdustry.shard.sim.unit.Unit;
import org.junit.jupiter.api.*;

import static com.xpdustry.shard.compat.v160.command.TeamAiRun.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * WP82's gates: the team AIs exact against vanilla frame by frame, pinned: {@link CommandRun}'s pins plus
 * {@link TeamAiRun}'s (the recompute window from Shard's clock, {@code damagedSet} in first-hit order). Each runs
 * through vanilla's save, after which both sides start their AIs anew as vanilla's {@code logic.reset()} does.
 */
class TeamAiOracleTest{
    /**
     * {@code rtsAi}: crux has a core, two ground factories (cheating, so they need nothing, and fast), four daggers and
     * two generators, one in range of sharded's loaded duo; sharded has a core, a generator, a battery, a drill on
     * copper, the duo, and two daggers by crux's other generator; near crux a battery guarded by a second duo and an
     * unguarded generator behind crux's lines; and a column of 14 batteries by the east edge, so squads rate more
     * targets than the 15 they keep. The duo's shots are damage the RTS AI hears, so squads
     * go to defend (at the aggressor, or at the turret); the others rate sharded's buildings, past the duo, and go for
     * the best; crux's core makes its core unit. Vanilla saves
     * at frame 700; both run to 1,500. Run twice: at vanilla's {@code rtsMinWeight} (1.2), where any dagger squad goes, and
     * at 6, where a squad waits for more units unless it outweighs what guards its target 6 to 1, so the estimate decides
     * when squads go.
     */
    @Test
    void rtsSquadsDefendAndAttackThroughASave(){
        rts(1.2f);
    }

    /**
     * {@link #rtsSquadsDefendAndAttackThroughASave()}, with squads that wait until the estimate lets them go. At frame
     * 351 sharded's duo bullet #208 has two crux daggers in its sweep, #62 and #54, and vanilla's team quadtree gives
     * #62 first, which takes the hit (W5.2): Shard's candidates come in that tree's order ({@code BulletOrderOracleTest}).
     */
    @Test
    void rtsSquadsWaitForTheirEstimateThroughASave(){
        rts(6f);
    }

    private static void rts(float minWeight){
        int w = 100, h = 50;
        try(var run = new TeamAiRun(w, h, (x, y) -> x >= 73 && x <= 78 && y >= 12 && y <= 18
            ? new CommandRun.Cell("stone", null, "ore-copper") : CommandRun.Cell.of("stone"))){
            run.teamRules(CRUX, r -> {
                r.rtsAi = true;
                r.cheat = true;
                r.unitBuildSpeedMultiplier = 25f;
                r.rtsMinWeight = minWeight;
            }, r -> {
                r.rtsAi = true;
                r.cheat = true;
                r.unitBuildSpeedMultiplier = 25f;
                r.rtsMinWeight = minWeight;
            });
            var c = run.run;
            c.place("core-shard", 12, 25, CRUX);
            c.place("ground-factory", 18, 18, CRUX);
            c.place("ground-factory", 18, 32, CRUX);
            c.place("combustion-generator", 30, 42, CRUX);
            // in the duo's range: its shots are bullet damage the RTS AI hears
            c.place("combustion-generator", 55, 25, CRUX);
            c.place("core-shard", 85, 25, SHARDED);
            c.place("combustion-generator", 70, 8, SHARDED);
            c.place("battery", 70, 42, SHARDED);
            c.place("mechanical-drill", 75, 15, SHARDED);
            c.place("duo", 60, 25, SHARDED);
            run.ammo(60, 25, "graphite", 10);
            // the nearest target, guarded by its own duo; a farther generator behind crux's lines that nothing guards:
            // the turrets' estimated damage is what sends squads past the battery
            c.place("battery", 42, 10, SHARDED);
            c.place("duo", 44, 12, SHARDED);
            run.ammo(44, 12, "graphite", 10);
            c.place("combustion-generator", 8, 44, SHARDED);
            // more than the 15 targets a squad rates: which ones it keeps follows their order and the shuffle
            for(int y = 5; y <= 44; y += 3) c.place("battery", 96, y, SHARDED);
            for(int i = 0; i < 4; i++) c.spawn("dagger", CRUX, (24 + (i % 2) * 2) * 8f, (24 + (i / 2) * 2) * 8f);
            c.spawn("dagger", SHARDED, 35 * 8f, 42 * 8f);
            c.spawn("dagger", SHARDED, 36 * 8f, 40 * 8f);

            int[] defended = {0}, attacked = {0}, coreUnits = {0}, heard = {0};
            run.after = at -> {
                var rts = run.ais().rts(CRUX);
                if(rts != null && !rts.damaged().isEmpty()) heard[0]++;
                for(int i = 0; i < c.shard.units().size(); i++){
                    Unit u = c.shard.units().get(i);
                    if(u.team != CRUX) continue;
                    if(u.type().name().equals("alpha")) coreUnits[0] = Math.max(coreUnits[0], 1);
                    if(u.controller instanceof CommandAi ai){
                        // sent to the aggressor: at its position, or at the unit itself where that is impassable
                        // or at the turret that hits it, which is no attack target of the RTS AI's
                        if(u.flag == 1 && (ai.attackTarget instanceof Unit enemy && enemy.team == SHARDED || ai.targetPos != null && ai.attackTarget == null
                            || ai.attackTarget instanceof com.xpdustry.shard.sim.blocks.turret.TurretBuild t && t.team == SHARDED)) defended[0]++;
                        if(ai.attackTarget instanceof Building b && b.team == SHARDED) attacked[0]++;
                    }
                }
            };
            c.saveAt(700);
            c.run(1500);
            assertEquals(1, c.seen("saved and loaded"), "the save ran");
            assertNotNull(run.ais().rts(CRUX), "crux's RTS AI ran after the load");
            System.out.println("heard " + heard[0] + ", defended " + defended[0] + ", attacked " + attacked[0]);
            assertTrue(heard[0] > 0, "the RTS AI heard its buildings hit");
            assertTrue(defended[0] > 0, "a squad went to the aggressor of a damaged building");
            assertTrue(attacked[0] > 0, "squads attacked sharded's buildings");
            assertEquals(1, coreUnits[0], "the RTS AI made crux's core unit");
            Core core = com.xpdustry.shard.sim.team.Teams.of(c.shard).core(SHARDED);
            System.out.println("defended " + defended[0] + ", attacked " + attacked[0] + ", sharded core "
                + (core == null ? "destroyed" : core.health + "/" + core.maxHealth));
        }
    }

    /**
     * {@code buildAi}, on an attack map: crux has a core on a copper and titanium field, sharded a core across the map,
     * and the map a spawn tile in the far corner. Crux's base builder fills its core, makes core units, walks its ground
     * field from the attack-mode spawn by its core to sharded's core, and places base parts that need copper or titanium
     * (or, rarely, none) off that path and away from the spawn tile, which its core units build. Vanilla saves at frame
     * 600; both run to 1,500.
     */
    @Test
    void theBaseBuilderPlansAndBuildsPartsThroughASave(){
        int w = 110, h = 70;
        try(var run = new TeamAiRun(w, h, (x, y) -> {
            if(x == 2 && y == 67) return new CommandRun.Cell("stone", null, "spawn");
            if(x < 60) return new CommandRun.Cell("stone", null, "ore-copper");
            if(x < 75) return new CommandRun.Cell("stone", null, "ore-titanium");
            return CommandRun.Cell.of("stone");
        })){
            // an attack map, as vanilla's built-in ones that turn buildAi on: with waves on, the wave team's core units
            // would fall back to the wave AI (BuilderAI.useFallback) and never build. The ground spawn is then by crux's
            // core (WaveSpawner.eachGroundSpawn's attack branch); the spawn tile still keeps parts away (getSpawns)
            run.rules(r -> {
                r.waves = false;
                r.attackMode = true;
            }, r -> {
                r.waves = false;
                r.attackMode = true;
            });
            run.teamRules(CRUX, r -> {
                r.buildAi = true;
                r.buildSpeedMultiplier = 20f;
            }, r -> {
                r.buildAi = true;
                r.buildSpeedMultiplier = 20f;
            });
            var c = run.run;
            c.place("core-shard", 30, 35, CRUX);
            c.place("core-shard", 100, 35, SHARDED);
            run.preloadWaveField();

            int[] built = {0};
            run.after = at -> {
                run.maxPlans = Math.max(run.maxPlans, com.xpdustry.shard.sim.team.Teams.of(c.shard).get(CRUX).plans().size());
                int count = 0;
                var buildings = c.shard.buildings();
                for(int pos = 0; pos < c.shard.tiles().size(); pos++){
                    Building b = buildings.at(pos);
                    if(b != null && b.tile() == pos && b.team == CRUX && !(b instanceof com.xpdustry.shard.sim.build.ConstructBuild)) count++;
                }
                built[0] = Math.max(built[0], count);
            };
            c.saveAt(600);
            c.run(1500);
            assertEquals(1, c.seen("saved and loaded"), "the save ran");
            var ai = run.ais().builder(CRUX);
            assertNotNull(ai, "crux's base builder ran after the load");
            assertTrue(ai.foundPath(), "the walk reached sharded's core");
            assertTrue(ai.path().length > 0, "the path is kept clear");
            assertTrue(run.maxPlans > 0, "base parts were queued as plans");
            assertTrue(built[0] > 1, "crux's builders built parts: " + built[0] + " buildings");
            System.out.println("most plans " + run.maxPlans + ", crux buildings " + built[0] + ", path " + ai.path().length);
        }
    }
}
