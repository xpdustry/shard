package com.xpdustry.shard.compat.v160.blocks.logic;

import com.xpdustry.shard.common.io.*;
import com.xpdustry.shard.compat.v160.world.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.logic.*;
import com.xpdustry.shard.logic.lang.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.logic.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.wire.*;

import java.util.*;

/**
 * Logic values as {@code TypeIO.writeObject} and {@code readObjectBoxed(read, true)} store them in processor and memory
 * saves: numbers as doubles, the logic table's content by the stream's ids, teams, {@link LAccess}, buildings by
 * position and units by id, lists ({@code Seq}) as null. A read building or unit is a {@link Boxed} until the save's
 * {@code afterReadAll}.
 */
final class LogicValues{
    private static final LAccess[] ACCESS = LAccess.values();
    private static final ContentType[] BY_VANILLA = new ContentType[WireObjects.CONTENT_TYPES];

    static{
        for(ContentType type : ContentType.values()) BY_VANILLA[type.vanillaOrdinal()] = type;
    }

    /** Saves name no tech nodes, players or synced entities: only the content checks matter. */
    static final WireContext CONTEXT = new WireContext(){
        @Override
        public boolean statusDynamic(int status){
            return false;
        }

        @Override
        public boolean blockRotates(int block){
            return false;
        }

        @Override
        public boolean blockExists(int block){
            return true;
        }

        @Override
        public boolean unlockable(int type, int id){
            return true;
        }

        @Override
        public boolean tileExists(int pos){
            return true;
        }

        @Override
        public int syncedClass(int id){
            return -1;
        }
    };

    private LogicValues(){
    }

    /** {@code TypeIO.writeObject} of a value a processor or memory cell holds. */
    static void write(Writes out, Object value, ContentHeader.IdMapping ids){
        WireObjects.writeObject(out, wire(value, ids));
    }

    static Object wire(Object value, ContentHeader.IdMapping ids){
        return switch(value){
            case null -> null;
            case Double d -> d;
            case ContentDef def -> new WireObjects.ContentRef(def.type().vanillaOrdinal(), ids.requireStream(def.type(), def.id()));
            case LogicTeam team -> new WireObjects.TeamRef(team.id());
            case LAccess access -> new WireObjects.LAccessRef(access.ordinal());
            case Building building -> new WireObjects.BuildingRef(building.pos());
            case Unit unit -> new WireObjects.UnitIdRef(unit.id());
            case Boxed.BuildingBox box -> new WireObjects.BuildingRef(box.pos());
            case Boxed.UnitBox box -> new WireObjects.UnitIdRef(box.id());
            // a Seq (query results) is written as null
            case List<?> list -> null;
            default -> value;
        };
    }

    /** {@code TypeIO.readObjectBoxed(read, true)}, as the logic VM holds it. */
    static Object read(Reads in, ContentHeader.IdMapping ids, Globals table){
        return vm(WireObjects.readObject(in, CONTEXT), ids, table);
    }

    /** A read value: numbers as {@code Number}s, content and teams as the table's, buildings and units boxed. */
    static Object vm(Object value, ContentHeader.IdMapping ids, Globals table){
        return switch(value){
            case WireObjects.ContentRef ref -> {
                ContentType type = ref.type() < BY_VANILLA.length ? BY_VANILLA[ref.type()] : null;
                if(type == null) yield value;
                int id = ids.toCurrent(type, ref.id());
                // content.getByID of an unknown id is null
                yield id < 0 || id >= table.content().count(type) ? null : table.content().get(type, id);
            }
            case WireObjects.TeamRef team -> table.team(team.id());
            case WireObjects.LAccessRef access -> ACCESS[access.ordinal()];
            case WireObjects.BuildingRef building -> new Boxed.BuildingBox(building.pos());
            case WireObjects.UnitIdRef unit -> new Boxed.UnitBox(unit.id());
            case null, default -> value;
        };
    }

    /** Skips one {@code TypeIO} value on a payload's tape. */
    static void skip(Tape in){
        int type = in.b();
        switch(type){
            case WireObjects.NULL -> {}
            case WireObjects.INT, WireObjects.FLOAT, WireObjects.BUILDING, WireObjects.UNIT -> in.i();
            case WireObjects.LONG, WireObjects.DOUBLE -> in.l();
            case WireObjects.STRING -> {
                if(in.b() != 0) in.str();
            }
            case WireObjects.CONTENT, WireObjects.TECH_NODE -> {
                in.b();
                in.s();
            }
            case WireObjects.INT_SEQ -> in.b(in.s() * 4);
            case WireObjects.POINT2, WireObjects.VEC2 -> in.l();
            case WireObjects.POINT2_ARRAY -> in.b(in.ub() * 4);
            case WireObjects.BOOL, WireObjects.LEGACY, WireObjects.TEAM -> in.b();
            case WireObjects.LACCESS, WireObjects.UNIT_COMMAND -> in.s();
            case WireObjects.BYTES, WireObjects.BOOL_ARRAY -> in.b(in.i());
            case WireObjects.VEC2_ARRAY -> in.b(in.s() * 8);
            case WireObjects.INT_ARRAY -> in.b(in.s() * 4);
            case WireObjects.OBJECT_ARRAY -> {
                for(int i = in.i(); i > 0; i--) skip(in);
            }
            default -> throw new IllegalArgumentException("Unknown object type: " + type);
        }
    }
}
