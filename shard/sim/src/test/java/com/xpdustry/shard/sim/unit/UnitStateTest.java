package com.xpdustry.shard.sim.unit;

import com.xpdustry.shard.content.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.tile.VanillaWorlds;
import com.xpdustry.shard.sim.toy.*;
import com.xpdustry.shard.sim.world.*;
import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Every field of a unit, its composed parts included, is copied by a fork and covered by its hash: the checkpoint
 * contract for W1.4's serializers.
 */
class UnitStateTest{
    @Test
    void aForkCopiesEveryFieldOfEveryPart(){
        World world = UnitWorlds.world(32, 32, 11);
        Unit legs = populated(world, "atrax"), carrier = populated(world, "mega");
        World fork = world.fork();
        StateCoverage.assertForked(world, fork, legs, fork.units().get(0), "atrax");
        StateCoverage.assertForked(world, fork, carrier, fork.units().get(1), "mega");
        var carried = ((Payload.UnitPayload)fork.units().get(1).payloads.get(0)).unit();
        assertSame(fork, carried.world(), "the carried unit was copied into the fork");
        assertSame(fork.buildings().get(carrier.tether.index()), fork.units().get(1).tether);
        assertEquals(world.hash(), fork.hash());
    }

    @Test
    void theHashCoversEveryFieldOfEveryPart(){
        World world = UnitWorlds.world(32, 32, 12);
        for(String type : List.of("atrax", "mega", "latum", "risso", "elude", "stell", "dagger")){
            Unit unit = populated(world, type);
            int fields = StateCoverage.assertHashed(unit, () -> {
                var hasher = new Hasher();
                unit.hashState(hasher);
                return hasher.value();
            }, Set.of("syncIndex", "allIndex")); // positions in groups EntityGroups hashes
            assertTrue(fields > 60, type + ": only " + fields + " fields");
        }
    }

    /** A unit of {@code type} with every part filled in and every field away from its default. */
    static Unit populated(World world, String type){
        UnitDef def = VanillaWorlds.VANILLA.unit(type);
        Unit unit = Unit.spawn(world, def, 2, 40f, 60f, 30f);
        unit.vel.set(0.5f, -0.25f);
        unit.shield = 12f;
        unit.flag = 3.5;
        unit.controller = new Follow(null);
        unit.abilities = new UnitAbility[]{new UnitAbility()};
        unit.abilities[0].data = 2f;
        if(unit.mounts.length == 0) unit.mounts = new WeaponMount[]{new WeaponMount(0, 0f)};
        WeaponMount mount = unit.mounts[0];
        mount.recoils = new float[]{0.5f, 0.25f};
        mount.reload = 4f;
        mount.target = unit;
        unit.statuses.add(new StatusEntry(3, 120f));
        unit.statuses.speedMultiplier = 1.2f;
        unit.plans.addLast(BuildPlan.place(3, 4, 1, 2, new int[]{7, 8}));
        unit.plans.addLast(BuildPlan.breaking(5, 6));
        unit.stackItem = 1;
        unit.stackAmount = 10;
        unit.mineTile = 12;
        switch(unit.shape){
            case MoveShape.Legs legs -> {
                legs.legs = new MoveShape.Leg[]{new MoveShape.Leg(), new MoveShape.Leg()};
                legs.legs[1].base.set(3f, 4f);
                legs.legs[1].moving = true;
            }
            case MoveShape.Crawl crawl -> crawl.segmentRot = 15f;
            default -> {
            }
        }
        if(unit.payloads != null){
            Unit carried = Unit.spawn(world, VanillaWorlds.VANILLA.unit("dagger"), 2, 40f, 60f, 0f);
            carried.remove();
            unit.payloads.add(new Payload.UnitPayload(carried));
            Building building = world.buildings().create(world.content().block("router"), 2, 1 + world.units().size(), 1, 0, Cause.SERVER);
            unit.tether = building;
        }
        return unit;
    }
}
