package com.xpdustry.shard.compat.v160.command;

import com.xpdustry.shard.sim.ai.command.*;
import com.xpdustry.shard.sim.unit.*;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The payload commands with W3.9's carriers, exact against vanilla frame by frame with the payload Calls byte for byte
 * ({@link CommandRun}): {@code loadUnits} and {@code unloadPayload} (a mega carries daggers across a wall),
 * {@code loadBlocks}, {@code loopPayload} (a mega shuttles units between two points) and {@code enterPayload} (units
 * walk into a payload conveyor).
 */
class PayloadCommandsOracleTest{
    static final int W = 64, H = 48, WALL = 32;

    /** Stone with a wall of stone at x = 32, open at y 22-25. */
    static CommandRun.Cell walled(int x, int y){
        if((x == WALL) && !(y >= 22 && y <= 25)) return new CommandRun.Cell("stone", "stone-wall", null);
        return CommandRun.Cell.of("stone");
    }

    @Test
    void aMegaLoadsUnitsAndUnloadsThemAtATarget(){
        try(var run = new CommandRun(W, H, PayloadCommandsOracleTest::walled)){
            int t = run.team;
            int mega = run.spawn("mega", t, 10 * 8f, 30 * 8f);
            // a dagger touching the mega, and two 12 units out: within hitSize + hitSize, which the pick-up needs
            int[] daggers = {run.spawn("dagger", t, 10 * 8f + 2f, 30 * 8f), run.spawn("dagger", t, 10 * 8f - 12f, 30 * 8f),
                run.spawn("dagger", t, 10 * 8f, 30 * 8f + 12f)};
            run.command(2, "loadUnits", mega);
            run.at(150, () -> assertEquals(3, run.unit(mega).payloads.size(), "the mega loaded the daggers"));
            // carry them over the wall; a position order keeps the payload commands (switchToMove is false)
            run.move(151, 50 * 8f, 10 * 8f, false, mega);
            run.command(400, "unloadPayload", mega);
            run.saveAt(300);
            run.run(700);
            assertFalse(Payloads.hasPayload(run.unit(mega)), "unloaded");
            // a load renumbers carried units (Payload.read draws their ids), so the daggers are found by type
            int dropped = 0;
            for(int i = 0; i < run.shard.units().size(); i++){
                var unit = run.shard.units().get(i);
                if(!unit.type().name().equals("dagger")) continue;
                dropped++;
                assertTrue(unit.x > (WALL + 1) * 8f, "across the wall: " + unit.x);
            }
            assertEquals(daggers.length, dropped, "every dagger dropped again");
            assertTrue(run.seen("call PickedUnitPayload") >= 3 && run.seen("call PayloadDropped") >= 3, "the Calls: " + run.seen);
        }
    }

    @Test
    void aMegaLoadsABlockAndAQuadLoopsUnits(){
        try(var run = new CommandRun(W, H, PayloadCommandsOracleTest::walled)){
            int t = run.team;
            run.place("copper-wall", 12, 12, t);
            int mega = run.spawn("mega", t, 12 * 8f, 14 * 8f);
            run.command(2, "loadBlocks", mega);
            // exactArrival: the mega must stand on the block to lift it
            run.move(3, 12 * 8f, 12 * 8f, false, mega);
            run.at(200, () -> assertEquals(1, run.unit(mega).payloads.size(), "the mega lifted the wall"));
            run.move(201, 20 * 8f, 30 * 8f, false, mega);
            run.command(380, "unloadPayload", mega);

            // a quad shuttles daggers from the left side to the right, and back: the loop never ends
            int quad = run.spawn("quad", t, 8 * 8f, 40 * 8f);
            for(int i = 0; i < 4; i++) run.spawn("dagger", t, 8 * 8f + i * 3f, 40 * 8f + (i % 2) * 4f);
            run.command(2, "loopPayload", quad);
            run.move(3, 8 * 8f, 40 * 8f, false, quad);
            run.move(4, 50 * 8f, 40 * 8f, true, quad);
            run.saveAt(450);
            run.run(900);
            assertNotNull(run.shard.buildings().at(20, 30), "the wall stands where it was dropped");
            assertEquals(UnitCommand.LOOP_PAYLOAD, run.ai(quad).command());
            assertTrue(run.seen("call PickedBuildPayload") >= 1, "the Calls: " + run.seen);
            assertTrue(run.seen("call PickedUnitPayload") >= 4 && run.seen("call PayloadDropped") >= 5, "the loop carried units: " + run.seen);
        }
    }

    @Test
    void unitsEnterAPayloadConveyor(){
        try(var run = new CommandRun(W, H, PayloadCommandsOracleTest::walled)){
            int t = run.team;
            run.place("payload-conveyor", 44, 20, t);
            int a = run.spawn("dagger", t, 10 * 8f, 20 * 8f), b = run.spawn("flare", t, 12 * 8f, 26 * 8f);
            run.command(2, "enterPayload", a, b);
            // snapToBuilding: the order lands on the conveyor's centre
            run.move(3, 45 * 8f, 21 * 8f, false, a, b);
            run.run(800);
            assertTrue(run.seen("call UnitEnteredPayload") >= 1, "a unit entered: " + run.seen);
        }
    }
}
