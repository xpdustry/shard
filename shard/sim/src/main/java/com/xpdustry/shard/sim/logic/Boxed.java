package com.xpdustry.shard.sim.logic;

import com.xpdustry.shard.common.geom.*;
import com.xpdustry.shard.sim.entity.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.world.*;

/**
 * A building or unit a save names, as vanilla's {@code TypeIO.readObjectBoxed(read, true)} keeps it ({@code Boxed}):
 * by position or id, until the save's {@code afterReadAll} looks it up, when everything it could name is read.
 */
public sealed interface Boxed{
    /** {@code unbox()}: what it names now, or null. */
    Object unbox(World world);

    /** {@code BuildingBox}: {@code world.build(pos)}. */
    record BuildingBox(int pos) implements Boxed{
        @Override
        public Object unbox(World world){
            return world.buildings().at(Point2.x(pos), Point2.y(pos));
        }
    }

    /** {@code UnitBox}: {@code Groups.unit.getByID(id)}. */
    record UnitBox(int id) implements Boxed{
        @Override
        public Object unbox(World world){
            EntityGroups groups = EntityGroups.of(world);
            return groups != null && groups.byId(id) instanceof Unit unit ? unit : null;
        }
    }
}
