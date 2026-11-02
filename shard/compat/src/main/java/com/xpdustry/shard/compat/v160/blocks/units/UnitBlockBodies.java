package com.xpdustry.shard.compat.v160.blocks.units;

import com.xpdustry.shard.common.geom.*;
import com.xpdustry.shard.common.io.*;
import com.xpdustry.shard.compat.v160.world.*;
import com.xpdustry.shard.sim.blocks.units.*;

import java.util.*;

/**
 * What the unit factory's and the reconstructor's bodies share: {@code TypeIO.writeVecNullable} for the rally point
 * and {@code TypeIO.writeCommand} for the command.
 */
final class UnitBlockBodies{
    /** {@code ContentType.unitCommand}, the header's list of command names. */
    private static final int UNIT_COMMAND = com.xpdustry.shard.wire.v160_7.ContentType.unitCommand.ordinal();

    private UnitBlockBodies(){
    }

    /** {@code writeVecNullable}: two NaNs for none. */
    static void writeVec(Writes out, Vec2 v){
        out.f(v == null ? Float.NaN : v.x);
        out.f(v == null ? Float.NaN : v.y);
    }

    /** {@code readVecNullable}: none when either is NaN. */
    static Vec2 readVec(Reads in){
        float x = in.f(), y = in.f();
        return Float.isNaN(x) || Float.isNaN(y) ? null : new Vec2(x, y);
    }

    /** {@code writeCommand}: the command id, 255 for none. */
    static void writeCommand(Writes out, int command){
        out.b(command < 0 ? 255 : command);
    }

    /**
     * {@code readCommand}: 255 is none; another id is the command of that id in the header's list when it has one (by
     * name), else in the content's. One the content lacks is none, as {@code content.unitCommand(id)} gives null.
     */
    static int readCommand(Reads in, UnitBuild building, ContentHeader.IdMapping ids){
        int id = in.ub();
        if(id == 255) return -1;
        List<String> current = building.world().content().unitCommands();
        List<String> names = ids.header().names(UNIT_COMMAND);
        if(!names.isEmpty()) return id < names.size() ? current.indexOf(names.get(id)) : -1;
        return id < current.size() ? id : -1;
    }
}
