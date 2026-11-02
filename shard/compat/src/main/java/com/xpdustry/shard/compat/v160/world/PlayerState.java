package com.xpdustry.shard.compat.v160.world;

import com.xpdustry.shard.common.io.*;
import com.xpdustry.shard.compat.v160.entity.*;
import com.xpdustry.shard.wire.*;

/**
 * The joining player as a world stream holds it: its id, then vanilla's generated {@code Player.write} (revision 2).
 * Content ids here are current ids: the player section comes before the content header.
 *
 * @param color RGBA8888
 * @param lastCommand the unit command id, or -1
 * @param selectedBlock the block id, or -1
 * @param unitKind {@code TypeIO.writeUnit}'s tag: 0 none, 1 block unit, 2 unit
 * @param unit the unit id, or the block unit's packed position; 0 when there is none
 */
public record PlayerState(int id, boolean admin, boolean boosting, int color, int lastCommand, float mouseX, float mouseY,
                          String name, int selectedBlock, int selectedRotation, boolean shooting, int team, boolean typing,
                          int unitKind, int unit, float x, float y){
    /** The {@code Player.write} revision. */
    public static final int REVISION = 2;

    /** A new player as {@code Player.create()} makes it, with a name and team, controlling nothing. */
    public static PlayerState fresh(int id, String name, int team){
        return new PlayerState(id, false, false, 0, -1, 0f, 0f, name, -1, 0, false, team, false, 0, 0, 0f, 0f);
    }

    /**
     * The live player as W2.2's projection sees it, field for field: what {@code EntityClasses.writeSave} writes for it,
     * after the id.
     */
    public static PlayerState of(PlayerProjection player){
        Codecs.UnitRef unit = player.unit();
        return new PlayerState(player.id(), player.admin(), player.boosting(), player.color(),
            player.lastCommand() == Codecs.NO_COMMAND ? -1 : player.lastCommand(), player.mouseX(), player.mouseY(), player.name(),
            player.selectedBlock(), player.selectedRotation(), player.shooting(), player.team(), player.typing(), unit.kind(), unit.ref(),
            player.x(), player.y());
    }

    /** Writes the id, then {@code Player.write}. */
    public void write(Writes out){
        out.i(id);
        out.s(REVISION);
        out.bool(admin);
        out.bool(boosting);
        out.i(color);
        out.b(lastCommand < 0 ? 255 : lastCommand);
        out.f(mouseX);
        out.f(mouseY);
        if(name == null){
            out.b(0);
        }else{
            out.b(1);
            out.str(name);
        }
        out.s(selectedBlock);
        out.i(selectedRotation);
        out.bool(shooting);
        out.b(team);
        out.bool(typing);
        out.b(unitKind);
        out.i(unit);
        out.f(x);
        out.f(y);
    }
}
