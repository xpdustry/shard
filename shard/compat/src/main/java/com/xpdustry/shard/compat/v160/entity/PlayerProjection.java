package com.xpdustry.shard.compat.v160.entity;

import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.wire.*;
import com.xpdustry.shard.wire.v160_7.*;

/**
 * One player, frozen for {@link PlayerLayout}: its id, then {@code Player}'s synced fields. The controlled unit goes as
 * {@code writeUnit}'s reference (none, or a unit by id); "no command" and "no block" as vanilla writes them.
 */
public final class PlayerProjection implements PlayerLayout.Source{
    private final int id;
    private final boolean admin, boosting, shooting, typing;
    private final int color, lastCommand, selectedRotation, team;
    private final float mouseX, mouseY, x, y;
    private final String name;
    private final short selectedBlock;
    private final Codecs.UnitRef unit;

    PlayerProjection(Player player){
        id = player.id();
        admin = player.admin;
        boosting = player.boosting;
        color = player.color;
        lastCommand = player.lastCommand < 0 ? Codecs.NO_COMMAND : player.lastCommand;
        mouseX = player.mouseX;
        mouseY = player.mouseY;
        name = player.name;
        selectedBlock = (short)player.selectedBlock;
        selectedRotation = player.selectedRotation;
        shooting = player.shooting;
        team = player.team;
        typing = player.typing;
        // TypeIO.writeUnit: a block unit is named by its building's position (kind 1)
        Unit controlled = player.unit();
        unit = controlled == null ? Codecs.UnitRef.NONE
            : controlled.blockTile != null ? Codecs.UnitRef.block(controlled.blockTile.pos()) : Codecs.UnitRef.unit(controlled.id());
        x = player.x;
        y = player.y;
    }

    /** The player's entity id. */
    public int id(){
        return id;
    }

    @Override
    public boolean admin(){
        return admin;
    }

    @Override
    public boolean boosting(){
        return boosting;
    }

    @Override
    public int color(){
        return color;
    }

    @Override
    public int lastCommand(){
        return lastCommand;
    }

    @Override
    public float mouseX(){
        return mouseX;
    }

    @Override
    public float mouseY(){
        return mouseY;
    }

    @Override
    public String name(){
        return name;
    }

    @Override
    public short selectedBlock(){
        return selectedBlock;
    }

    @Override
    public int selectedRotation(){
        return selectedRotation;
    }

    @Override
    public boolean shooting(){
        return shooting;
    }

    @Override
    public int team(){
        return team;
    }

    @Override
    public boolean typing(){
        return typing;
    }

    @Override
    public Codecs.UnitRef unit(){
        return unit;
    }

    @Override
    public float x(){
        return x;
    }

    @Override
    public float y(){
        return y;
    }
}
