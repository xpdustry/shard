package com.xpdustry.shard.sim.unit;

import com.xpdustry.shard.content.*;
import com.xpdustry.shard.sim.unit.ability.*;

/**
 * The {@link UnitParams} of every unit type of a world's content, by unit id, read once when the world is made.
 * Immutable: forks share it.
 */
public final class UnitTable{
    private final ContentEpoch content;
    private final UnitParams[] params;
    private final AiType[] ai;
    private final MineType[] mine;
    private final Abilities[] abilities;
    private final ShapeTable shapes;

    private UnitTable(ContentEpoch content){
        this.content = content;
        params = new UnitParams[content.units()];
        for(int id = 0; id < params.length; id++) params[id] = UnitParams.of(content.unit(id));
        ai = new AiType[params.length];
        for(int id = 0; id < ai.length; id++) ai[id] = new AiType(content.unit(id));
        mine = new MineType[params.length];
        for(int id = 0; id < mine.length; id++) mine[id] = new MineType(content, content.unit(id));
        abilities = new Abilities[params.length];
        for(int id = 0; id < abilities.length; id++) abilities[id] = Abilities.of(content, content.unit(id));
        shapes = ShapeTable.of(content);
    }

    public static UnitTable of(ContentEpoch content){
        return new UnitTable(content);
    }

    /** The parameters of {@code type}, a unit type of this table's content. */
    public UnitParams params(UnitDef type){
        int id = type.id();
        if(id < 0 || id >= params.length || !content.unit(id).equals(type)){
            throw new IllegalArgumentException("unit type " + type.name() + " is not in the world's content");
        }
        return params[id];
    }

    /** The fields AI controllers read of {@code type}, a unit type of this table's content (as {@link #params} checked). */
    public AiType ai(UnitDef type){
        return ai[type.id()];
    }

    /** The mining fields of {@code type}, a unit type of this table's content. */
    public MineType mine(UnitDef type){
        return mine[type.id()];
    }

    /** The abilities of {@code type}, a unit type of this table's content. */
    public Abilities abilities(UnitDef type){
        return abilities[type.id()];
    }

    /** The block fields the movement shapes read. */
    public ShapeTable shapes(){
        return shapes;
    }
}
