package com.xpdustry.shard.compat.v160.entity;

import com.xpdustry.shard.common.io.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.world.*;
import com.xpdustry.shard.wire.*;
import com.xpdustry.shard.wire.v160_7.*;

/**
 * Reads a player's save body ({@code Player.write}) into a Shard {@link Player}, as vanilla's {@code read()}: every
 * field as written, "no command" back to -1, and the unit reference resolved by id among the world's units, or none.
 * A block unit (reference kind 1) is none until WP43 gives turrets theirs.
 *
 * <p>Deviation: vanilla's {@code read()} only sets the {@code unit} field; Shard's player always controls its unit, so
 * the unit is taken over with {@link Player#unit(Unit)}, after which the {@code lastCommand} read is put back.
 */
public final class PlayerSink implements PlayerLayout.Sink{
    private final Player player;
    private final World world;
    private Codecs.UnitRef unit = Codecs.UnitRef.NONE;
    private int lastCommand = -1;

    private PlayerSink(Player player){
        this.player = player;
        this.world = player.world();
    }

    /** Vanilla's {@code read()} of a player, from its revision on, into {@code player}. */
    public static void readSave(Player player, Reads read, WireContext context){
        var type = EntityClasses.get(EntityClasses.PLAYER);
        int revision = read.s();
        if(revision != type.revision()){
            throw new WireException(type.name() + " revision " + revision + ": only revision " + type.revision() + " is supported");
        }
        var sink = new PlayerSink(player);
        PlayerLayout.readSave(read, sink, context);
        Unit unit = sink.unit.kind() == 2 ? WireControllers.unit(sink.world, sink.unit.ref()) : null;
        player.unit(unit);
        player.lastCommand = sink.lastCommand;
    }

    @Override
    public void admin(boolean value){
        player.admin = value;
    }

    @Override
    public void boosting(boolean value){
        player.boosting = value;
    }

    @Override
    public void color(int value){
        player.color = value;
    }

    @Override
    public void lastCommand(int value){
        lastCommand = value == Codecs.NO_COMMAND ? -1 : value;
    }

    @Override
    public void mouseX(float value){
        player.mouseX = value;
    }

    @Override
    public void mouseY(float value){
        player.mouseY = value;
    }

    @Override
    public void name(String value){
        player.name = value;
    }

    @Override
    public void selectedBlock(short value){
        player.selectedBlock = value;
    }

    @Override
    public void selectedRotation(int value){
        player.selectedRotation = value;
    }

    @Override
    public void shooting(boolean value){
        player.shooting = value;
    }

    @Override
    public void team(int value){
        player.team(value);
    }

    @Override
    public void typing(boolean value){
        player.typing = value;
    }

    @Override
    public void unit(Codecs.UnitRef value){
        unit = value;
    }

    @Override
    public void x(float value){
        player.x = value;
    }

    @Override
    public void y(float value){
        player.y = value;
    }
}
