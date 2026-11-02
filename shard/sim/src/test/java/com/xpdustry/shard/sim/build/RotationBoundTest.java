package com.xpdustry.shard.sim.build;

import com.xpdustry.shard.common.geom.*;
import com.xpdustry.shard.common.math.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.sim.blocks.storage.*;
import com.xpdustry.shard.sim.blocks.transport.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.rules.*;
import com.xpdustry.shard.sim.team.*;
import com.xpdustry.shard.sim.tile.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.world.*;
import com.xpdustry.shard.sim.world.Observer;
import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Review 40's blocker, in {@link Build} alone: whatever rotation a plan holds, the building ends at
 * {@link Build#rotation}'s 0 to 3, and so do the Calls, through ordinary placement, derelict repair and rotation in
 * place. Repaired at -1, a derelict duct bridge facing a friendly one indexed that one's {@code occupied} with it,
 * which threw out of the tick.
 */
class RotationBoundTest{
    static final ContentEpoch VANILLA = VanillaWorlds.VANILLA;
    static final BlockDef CONVEYOR = VANILLA.block("conveyor"), BRIDGE = VANILLA.block("duct-bridge");

    World base;

    @BeforeEach
    void world(){
        base = UnitWorlds.world(48, 48, 3);
        // sun's rules place both planets' blocks: the conveyor is serpulo's, the duct bridge erekir's
        base.part(WorldRules.class).rules.planet = 0;
        var core = (Core)base.buildings().setBlock(6, 6, VANILLA.block("core-shard"), Team.SHARDED, 0, Cause.SERVER);
        core.items.set(VANILLA.item("copper").id(), 1_000);
        // a derelict bridge with a friendly one two tiles off each side: repaired at any rotation, it links
        base.buildings().setBlock(20, 20, BRIDGE, VanillaIds.DERELICT, 0, Cause.SERVER);
        for(int d = 0; d < 4; d++){
            base.buildings().setBlock(20 + Geometry.d4x(d) * 2, 20 + Geometry.d4y(d) * 2, BRIDGE, Team.SHARDED, 0, Cause.SERVER);
        }
        base.buildings().setBlock(30, 20, CONVEYOR, Team.SHARDED, 0, Cause.SERVER);
        base.tick();
    }

    @Test
    void everySignedByteRotationEndsAtZeroToThree(){
        for(int rotation = Byte.MIN_VALUE; rotation <= Byte.MAX_VALUE; rotation++){
            World world = base.fork();
            Observer events = world.observe(64, List.of(BuildEvent.class), null);
            Unit unit = Unit.spawn(world, VANILLA.unit("alpha"), Team.SHARDED, 25 * 8, 25 * 8, 0f);
            unit.controller(new NoAi());
            // ordinary placement, derelict repair, rotation in place
            for(var plan : List.of(BuildPlan.place(30, 30, rotation, CONVEYOR, null), BuildPlan.place(20, 20, rotation, BRIDGE, null),
                BuildPlan.place(30, 20, rotation, CONVEYOR, null))){
                Builder.addBuild(unit, plan, true);
            }
            for(int tick = 0; tick < 120; tick++) world.tick();

            int expected = Mathf.mod(rotation, 4);
            String at = "rotation " + rotation;
            assertTrue(unit.plans.isEmpty(), at + ": every plan done or dropped");
            assertEquals(expected, assertInstanceOf(Conveyor.class, world.buildings().at(30, 30), at).rotation, at + ", placed");
            var bridge = assertInstanceOf(DuctBridge.class, world.buildings().at(20, 20), at);
            assertEquals(Team.SHARDED, bridge.team, at + ", repaired");
            assertEquals(expected, bridge.rotation, at + ", repaired");
            assertSame(world.buildings().at(20 + Geometry.d4x(expected) * 2, 20 + Geometry.d4y(expected) * 2), bridge.lastLink, at + ", linked");
            assertEquals(expected, world.buildings().at(30, 20).rotation, at + ", rotated in place");
            for(WorldEvent event; (event = events.poll()) != null; ){
                int sent = switch(event){
                    case BuildEvent.BeginPlace begin -> begin.rotation();
                    case BuildEvent.ConstructFinish finish -> finish.rotation();
                    default -> expected;
                };
                assertEquals(expected, sent, at + ": " + event);
            }
        }
    }

    @Test
    void theRotationIsBoundedEvenPastTheBuilder(){
        Observer events = base.observe(64, List.of(BuildEvent.class), null);
        Build.beginPlace(base, null, BRIDGE, Team.SHARDED, 20, 20, -1, null);
        Build.constructFinish(base, base.tiles().pos(30, 30), CONVEYOR, null, -2, Team.SHARDED, null);
        for(int tick = 0; tick < 10; tick++) base.tick();
        assertEquals(3, base.buildings().at(20, 20).rotation);
        assertEquals(2, base.buildings().at(30, 30).rotation);
        var begin = assertInstanceOf(BuildEvent.BeginPlace.class, events.poll());
        assertEquals(3, begin.rotation());
        var finish = assertInstanceOf(BuildEvent.ConstructFinish.class, events.poll());
        assertEquals(2, finish.rotation());
    }
}
