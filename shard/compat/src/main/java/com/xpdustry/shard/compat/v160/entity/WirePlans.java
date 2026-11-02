package com.xpdustry.shard.compat.v160.entity;

import com.xpdustry.shard.common.geom.*;
import com.xpdustry.shard.compat.v160.world.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.logic.lang.LAccess;
import com.xpdustry.shard.sim.building.ConfigValue;
import com.xpdustry.shard.sim.building.ContentRef;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.wire.*;

/**
 * Build plans as {@code TypeIO.writePlan} sends them, and back. A {@link BuildPlan}'s config uses the sim's values
 * ({@link Vec2}, {@code int[]} for an {@code IntSeq}, {@link ContentRef} for content); the wire uses {@link WireObjects}'
 * ({@link Codecs.Vec2}, {@link WireObjects.IntSeqValue}, {@link WireObjects.ContentRef}), nested arrays included. Other
 * values are the same on both sides; values vanilla can't write (a {@code Short}, say) pass through for
 * {@link WireObjects#writeObject} to refuse.
 *
 * <p>Content crosses through {@link ContentIds}: the world's own ids, which are the client's, or a file's header. A
 * wire id vanilla's {@code getByID} gives null for (negative, or past the content) becomes null, which a building's
 * {@code configured} then treats as a clear, as vanilla's does. Content of a type the epoch doesn't hold (a
 * {@code UnitCommand}, a planet) becomes a {@link ContentRef.Foreign}, its ids unmapped.
 *
 * <p>The other tags have sim values of their own ({@link ConfigValue}): a team is a {@link ConfigValue.Team}, a tech
 * node a {@link ConfigValue.TechNode} of its content, a sensor its {@link LAccess}, an {@code int[]} (tag 21) an
 * {@link ConfigValue.IntArray}; a unit command (tag 23) is the content vanilla reads it as, so it goes back as content
 * (tag 5), as vanilla's {@code writeObject} writes a {@code UnitCommand}. Buildings and units stay references for the
 * caller to resolve.
 */
public final class WirePlans{
    /** {@code ContentType.unitCommand.ordinal()}. */
    private static final int UNIT_COMMAND = ContentType.VANILLA_NAMES.indexOf("unitCommand");

    private WirePlans(){
    }

    /** How content ids cross between the wire and the sim. */
    public interface ContentIds{
        /** The sim id of wire id {@code id}, or -1 for none (vanilla's null). */
        int toCurrent(ContentType type, int id);

        /** The wire id of sim id {@code id}. */
        int toWire(ContentType type, int id);

        /** The world's own ids, which the wire and the client share: a wire id outside the content is none. */
        static ContentIds of(ContentEpoch content){
            return new ContentIds(){
                @Override
                public int toCurrent(ContentType type, int id){
                    return id >= 0 && id < content.count(type) ? id : -1;
                }

                @Override
                public int toWire(ContentType type, int id){
                    return id;
                }
            };
        }

        /** A file's ids, as its content header maps them ({@code getByID} under the file's mapper). */
        static ContentIds of(ContentHeader.IdMapping ids){
            return new ContentIds(){
                @Override
                public int toCurrent(ContentType type, int id){
                    return ids.toCurrent(type, id);
                }

                @Override
                public int toWire(ContentType type, int id){
                    return ids.requireStream(type, id);
                }
            };
        }
    }

    public static Codecs.Plan of(BuildPlan plan, ContentIds ids){
        int pos = Point2.pack(plan.x, plan.y);
        return plan.breaking ? Codecs.Plan.breaking(pos) : new Codecs.Plan(false, pos, plan.block, plan.rotation, config(plan.config, ids));
    }

    /** {@code readPlan}'s plan as the sim holds it. */
    public static BuildPlan plan(Codecs.Plan plan, ContentIds ids){
        int x = Point2.x(plan.pos()), y = Point2.y(plan.pos());
        return plan.breaking() ? BuildPlan.breaking(x, y) : BuildPlan.place(x, y, plan.rotation(), plan.block(), value(plan.config(), ids));
    }

    /** A sim config value as {@link WireObjects} writes it. */
    public static Object config(Object value, ContentIds ids){
        return switch(value){
            case Vec2 vec -> new Codecs.Vec2(vec.x, vec.y);
            case Vec2[] vecs -> {
                var out = new Codecs.Vec2[vecs.length];
                for(int i = 0; i < vecs.length; i++) out[i] = new Codecs.Vec2(vecs[i].x, vecs[i].y);
                yield out;
            }
            case int[] ints -> new WireObjects.IntSeqValue(ints.clone());
            case ConfigValue.IntArray ints -> ints.ints();
            case ContentRef ref -> new WireObjects.ContentRef(ref.type().vanillaOrdinal(), ids.toWire(ref.type(), ref.id()));
            case ContentRef.Foreign ref -> new WireObjects.ContentRef(ref.vanillaType(), ref.id());
            case ConfigValue.TechNode node -> {
                var content = (WireObjects.ContentRef)config(node.content(), ids);
                yield new WireObjects.TechNodeRef(content.type(), content.id());
            }
            case ConfigValue.Team team -> new WireObjects.TeamRef(team.id());
            case LAccess sensor -> new WireObjects.LAccessRef(sensor.ordinal());
            case Point2[] points -> points.clone();
            case byte[] bytes -> bytes.clone();
            case boolean[] bools -> bools.clone();
            case Object[] values -> {
                var out = new Object[values.length];
                for(int i = 0; i < values.length; i++) out[i] = config(values[i], ids);
                yield out;
            }
            case null, default -> value;
        };
    }

    /** A {@link WireObjects} value as a sim config. */
    public static Object value(Object config, ContentIds ids){
        return switch(config){
            case Codecs.Vec2 vec -> new Vec2(vec.x(), vec.y());
            case Codecs.Vec2[] vecs -> {
                var out = new Vec2[vecs.length];
                for(int i = 0; i < vecs.length; i++) out[i] = new Vec2(vecs[i].x(), vecs[i].y());
                yield out;
            }
            case WireObjects.IntSeqValue seq -> seq.items().clone();
            // a power node's links: a Point2[] the Object[] case below would make an Object[] (review 39's gate found it)
            case Point2[] points -> points.clone();
            case int[] ints -> new ConfigValue.IntArray(ints);
            case WireObjects.ContentRef ref -> content(ref, ids);
            case WireObjects.TechNodeRef ref -> {
                Object content = content(new WireObjects.ContentRef(ref.type(), ref.id()), ids);
                yield content == null ? null : new ConfigValue.TechNode(content);
            }
            case WireObjects.TeamRef ref -> new ConfigValue.Team(ref.id());
            case WireObjects.LAccessRef ref -> LAccess.values()[ref.ordinal()];
            // content.unitCommand(id): a unit command is content
            case WireObjects.UnitCommandRef ref -> content(new WireObjects.ContentRef(UNIT_COMMAND, ref.id()), ids);
            case Object[] values -> {
                var out = new Object[values.length];
                for(int i = 0; i < values.length; i++) out[i] = value(values[i], ids);
                yield out;
            }
            case null, default -> config;
        };
    }

    /** {@code content.getByID(ContentType.all[type], id)} as a sim value. */
    private static Object content(WireObjects.ContentRef ref, ContentIds ids){
        ContentType type = null;
        for(ContentType candidate : ContentType.values()) if(candidate.vanillaOrdinal() == ref.type()) type = candidate;
        if(type == null) return new ContentRef.Foreign(ref.type(), ref.id());
        int id = ids.toCurrent(type, ref.id());
        return id < 0 ? null : new ContentRef(type, id);
    }
}
