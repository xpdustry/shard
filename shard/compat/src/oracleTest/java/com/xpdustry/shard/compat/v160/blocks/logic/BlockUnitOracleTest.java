package com.xpdustry.shard.compat.v160.blocks.logic;

import com.xpdustry.shard.oracle.*;
import com.xpdustry.shard.sim.blocks.turret.*;
import com.xpdustry.shard.sim.unit.*;
import mindustry.content.*;
import mindustry.game.*;
import mindustry.world.blocks.defense.turrets.*;
import org.junit.jupiter.api.*;

import java.util.*;

import static mindustry.Vars.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * W5.7's gate for block units (WP60): a player controls a duo through its block unit, as {@code unitControl} gives it
 * one, aims and shoots crux daggers dead, compared frame by frame with vanilla by {@link UnitLogicRun}, through a save.
 * Besides the run's comparison, every frame compares the turrets (rotation, reload, ammo, aim, shooting, team, health)
 * and the players (team, unit, dead).
 *
 * <p>What passes through the block unit, as {@code BlockUnitComp} has it: {@code damage(amount, true)} hurts the duo;
 * {@code kill()} only sends {@code unitDeath} with the block unit's id; the player's team change moves the duo to the new
 * team at once ({@code Player.team}) and back, and a duo moved to another team goes back to its player's at the
 * player's next update ({@code PlayerComp.update}'s {@code unit.team(team)}); {@code killed()}
 * destroys a second duo; and enough damage destroys the controlled duo, so the player loses the unit and stays dead
 * (no core). Players aren't saved: after the load a new player takes the duo over again.
 */
class BlockUnitOracleTest{
    static final int X = 20, Y = 20, SECOND_X = 20, SECOND_Y = 26;

    @BeforeAll
    static void boot(){
        Vanilla.boot();
    }

    @Test
    void aPlayerControlsADuoThroughItsBlockUnit(){
        try(var run = new UnitLogicRun(40, Map.of())){
            run.place("duo", X, Y, Team.sharded);
            run.place("duo", SECOND_X, SECOND_Y, Team.sharded);
            run.dummy("dagger", Team.crux, 25 * 8f, 20 * 8f);
            run.dummy("dagger", Team.crux, 26 * 8f, 22 * 8f);
            var players = new Object[2];
            control(run, players, "first");
            for(int frame = 1; frame <= 700; frame++){
                int at = frame;
                if(frame % 40 == 1) run.at(frame, () -> {
                    feed(run, X, Y);
                    feed(run, SECOND_X, SECOND_Y);
                });
                // the client's aim and trigger, as its snapshots set them: at each dagger in turn
                run.at(frame, () -> aim(players, at < 260 ? 25 * 8f : 26 * 8f, at < 260 ? 20 * 8f : 22 * 8f, at % 200 < 170));
            }
            run.at(150, () -> {
                vanillaUnit(players).damage(30f, true);
                shardUnit(players).damage(30f, true);
            });
            run.sending(200, () -> {
                vanillaUnit(players).kill();
                shardUnit(players).kill();
            });
            run.at(220, () -> {
                ((mindustry.gen.Player)players[0]).team(Team.blue);
                ((Player)players[1]).team(Team.blue.id);
            });
            run.at(280, () -> {
                ((mindustry.gen.Player)players[0]).team(Team.sharded);
                ((Player)players[1]).team(Team.sharded.id);
            });
            // the duo goes to blue by itself: its player's next update moves it back to the player's team
            run.at(300, () -> {
                world.build(X, Y).changeTeam(Team.blue);
                var duo = run.shard.buildings().at(X, Y);
                run.shard.buildings().changeTeam(duo, Team.blue.id, com.xpdustry.shard.sim.world.Cause.SERVER);
            });
            // players aren't saved: both leave before vanilla saves (a player still in Groups.player during vanilla's
            // load would let go of the block unit there, whose new controller draws), and a new one takes the duo over
            // after the load
            run.at(319, () -> {
                ((mindustry.gen.Player)players[0]).remove();
                ((Player)players[1]).remove();
            });
            run.at(320, () -> control(run, players, "second"));
            run.at(500, () -> {
                ((Turret.TurretBuild)world.build(SECOND_X, SECOND_Y)).unit().killed();
                ((TurretBuild)run.shard.buildings().at(SECOND_X, SECOND_Y)).blockUnit().killed();
            });
            run.at(560, () -> {
                vanillaUnit(players).damage(10_000f, true);
                shardUnit(players).damage(10_000f, true);
            });
            int[] blueFrames = {0};
            run.also = run.also.andThen(where -> {
                for(int[] at : new int[][]{{X, Y}, {SECOND_X, SECOND_Y}}){
                    assertEquals(vanillaTurret(at[0], at[1]), shardTurret(run, at[0], at[1]), where + ": the duo at " + at[0] + "," + at[1]);
                }
                var v = (mindustry.gen.Player)players[0];
                var s = (Player)players[1];
                if(!v.isAdded()) return;
                assertEquals(v.team().id + " dead " + v.dead() + " block " + (v.unit() instanceof mindustry.gen.BlockUnitc),
                    s.team + " dead " + s.dead() + " block " + (s.unit() != null && s.unit().blockTile != null), where + ": the player");
                if(world.build(X, Y) != null && world.build(X, Y).team == Team.blue) blueFrames[0]++;
            });
            run.saveAt(320);
            run.run(700);

            assertTrue(run.seen("Death") >= 1, "kill() sent unitDeath: " + run.seen);
            assertTrue(blueFrames[0] >= 50, "the duo followed its player to blue and back: " + blueFrames[0]);
            assertNull(run.shard.buildings().at(SECOND_X, SECOND_Y), "killed() destroyed the second duo");
            assertNull(run.shard.buildings().at(X, Y), "damage through the block unit destroyed the controlled duo");
            assertTrue(((Player)players[1]).dead(), "the player lost the destroyed duo");
            assertTrue(shots[0] > 0 && run.shard.units().size() < 2, "the player shot a dagger dead: " + shots[0] + " shots, "
                + run.shard.units().size() + " daggers left");
        }
    }

    /** Shots the controlled duo fired, counted from its shot counter. */
    private static final int[] shots = {0};

    /** A connected player on sharded on each side, who takes the duo's block unit over ({@code Call.unitControl}). */
    private static void control(UnitLogicRun run, Object[] players, String name){
        var v = mindustry.gen.Player.create();
        v.con = Vanilla.connection();
        v.name = name;
        v.team(Team.sharded);
        v.add();
        v.unit(((Turret.TurretBuild)world.build(X, Y)).unit());
        Vanilla.runPosted();
        var s = Player.create(run.shard);
        s.name = name;
        s.color = v.color.rgba();
        s.team(Team.sharded.id);
        s.add();
        s.unit(((TurretBuild)run.shard.buildings().at(X, Y)).blockUnit());
        players[0] = v;
        players[1] = s;
    }

    private static void feed(UnitLogicRun run, int x, int y){
        var v = world.build(x, y);
        var s = run.shard.buildings().at(x, y);
        if(v == null || s == null) return;
        int copper = run.shard.content().item("copper").id();
        for(int i = 0; i < 4; i++){
            if(v.acceptItem(null, Items.copper)) v.handleItem(null, Items.copper);
            if(s.acceptItem(null, copper)) s.handleItem(null, copper);
        }
    }

    private static void aim(Object[] players, float x, float y, boolean shooting){
        var v = (mindustry.gen.Player)players[0];
        var s = (Player)players[1];
        if(!v.dead()){
            v.unit().aimX = x;
            v.unit().aimY = y;
            v.unit().isShooting = shooting;
        }
        if(!s.dead()){
            s.unit().aimX = x;
            s.unit().aimY = y;
            s.unit().isShooting = shooting;
        }
    }

    private static mindustry.gen.Unit vanillaUnit(Object[] players){
        return ((mindustry.gen.Player)players[0]).unit();
    }

    private static Unit shardUnit(Object[] players){
        return ((Player)players[1]).unit();
    }

    private static String vanillaTurret(int x, int y){
        if(!(world.build(x, y) instanceof Turret.TurretBuild b)) return "-";
        return turret(b.rotation, b.reloadCounter, b.totalAmmo, b.targetPos.x, b.targetPos.y, b.totalShots, b.isShooting, b.team.id, b.health,
            b.unit().team.id);
    }

    private static String shardTurret(UnitLogicRun run, int x, int y){
        if(!(run.shard.buildings().at(x, y) instanceof TurretBuild b)) return "-";
        if(x == X && y == Y) shots[0] = b.totalShots;
        return turret(b.turretRotation, b.reloadCounter, b.totalAmmo, b.targetX, b.targetY, b.totalShots, b.isShooting, b.team, b.health,
            b.blockUnit().team);
    }

    private static String turret(float rotation, float reload, int ammo, float tx, float ty, int shots, boolean shooting, int team, float health,
                                 int unitTeam){
        return "rot " + UnitLogicRun.bits(rotation) + " reload " + UnitLogicRun.bits(reload) + " ammo " + ammo + " aim " + UnitLogicRun.bits(tx)
            + "," + UnitLogicRun.bits(ty) + " shots " + shots + " shooting " + shooting + " team " + team + " health " + UnitLogicRun.bits(health)
            + " unit team " + unitTeam;
    }
}
