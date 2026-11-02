package com.xpdustry.shard.compat.v160.entity;

import com.xpdustry.shard.compat.v160.blocks.sandbox.*;
import com.xpdustry.shard.compat.v160.world.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.sim.blocks.sandbox.LaunchPayload;
import com.xpdustry.shard.sim.entity.*;
import com.xpdustry.shard.sim.fire.*;
import com.xpdustry.shard.sim.puddle.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.weather.*;
import com.xpdustry.shard.wire.*;
import com.xpdustry.shard.wire.v160_7.*;

import java.util.*;

/**
 * Freezes synced entities for the generated layouts, on the world thread between ticks: a {@link Unit} as a
 * {@link UnitProjection}, a {@link Player} as a {@link PlayerProjection}. The results encode anywhere with
 * {@link EntityClasses#writeSync} or {@link EntityClasses#writeSave} under {@link #classId}, against a
 * {@link WorldWireContext} captured at the same boundary.
 *
 * <p>A unit's class is its type's entity class ({@code UnitType.constructor}, baked into the content as
 * {@code entityClass}). A carried building is encoded with W2.1's {@link BuildingCodecs}: its map chunk is the revision
 * byte, then {@code writeAll}, which is exactly a block payload's version and body.
 */
public final class EntityProjector{
    private final ContentEpoch content;
    private final BuildingCodecs codecs;
    private final ContentHeader.IdMapping ids;
    private final Map<Integer, Integer> savedClasses;

    /** Projects entities of worlds of {@code content}; sync carries its ids as they are. */
    public EntityProjector(ContentEpoch content, BuildingCodecs codecs){
        this(content, codecs, Map.of());
    }

    /**
     * Projects entities of worlds of {@code content}, where a unit whose id {@code savedClasses} holds keeps the class
     * it was saved with ({@code MapState.classes}), carried or not.
     */
    public EntityProjector(ContentEpoch content, BuildingCodecs codecs, Map<Integer, Integer> savedClasses){
        this.content = content;
        this.codecs = codecs;
        this.ids = ContentHeader.of(content).mapping(content);
        this.savedClasses = savedClasses;
    }

    /** The class {@code unit} writes as: the one it was saved with, else its type's ({@link #classId}). */
    public int classOf(Unit unit){
        if(savedClasses.isEmpty()) return classId(unit);
        Integer saved = savedClasses.get(unit.id());
        return saved != null ? saved : classId(unit);
    }

    /**
     * The entity class id vanilla gives {@code entity}: {@code classId()} of its generated class. Synced entities that
     * Shard doesn't model yet (world labels) add their case when they arrive.
     */
    public static int classId(Entity entity){
        return switch(entity){
            case Unit unit -> unit.type().entityClass().classId();
            case Player ignored -> EntityClasses.PLAYER;
            case WeatherState ignored -> EntityClasses.WEATHER_STATE;
            case Fire ignored -> EntityClasses.FIRE;
            case Puddle ignored -> EntityClasses.PUDDLE;
            case LaunchPayload ignored -> EntityClasses.LAUNCH_PAYLOAD;
            default -> throw new IllegalArgumentException(entity.getClass().getSimpleName() + " has no entity class");
        };
    }

    /** {@code entity}, frozen for its layout. */
    public WireEntity project(Entity entity){
        return switch(entity){
            case Unit unit -> unit(unit);
            case Player player -> player(player);
            case WeatherState weather -> weather(weather);
            case Fire fire -> new FireProjection(fire);
            case Puddle puddle -> new PuddleProjection(puddle);
            case LaunchPayload payload -> new LaunchPayloadProjection(payload);
            default -> throw new IllegalArgumentException(entity.getClass().getSimpleName() + " has no layout");
        };
    }

    /** {@code unit}, frozen: safe to keep and to encode on any thread. */
    public UnitProjection unit(Unit unit){
        return new UnitProjection(view(unit, new UnitView()));
    }

    /**
     * {@link #project}, but a unit comes as {@code view} bound to it, not frozen: for an encoder that writes it at
     * once, on the world thread, and keeps nothing (W4.6). Other entities are projected as {@link #project} does.
     */
    public WireEntity project(Entity entity, UnitView view){
        return entity instanceof Unit unit ? view(unit, view) : project(entity);
    }

    /** {@code view}, bound to {@code unit}; its carried payloads, if any, are frozen. */
    private UnitView view(Unit unit, UnitView view){
        List<Codecs.Payload> payloads = List.of();
        if(unit.payloads != null && !unit.payloads.isEmpty()){
            var list = new ArrayList<Codecs.Payload>(unit.payloads.size());
            for(int i = 0; i < unit.payloads.size(); i++) list.add(payload(unit.payloads.get(i)));
            payloads = list;
        }
        return view.bind(unit, classOf(unit), payloads);
    }

    public PlayerProjection player(Player player){
        return new PlayerProjection(player);
    }

    public WeatherStateProjection weather(WeatherState state){
        return new WeatherStateProjection(state);
    }

    /** {@code Payload.write}'s view: a unit in its save layout, or a building's block, version and body. */
    public Codecs.Payload payload(Payload payload){
        return switch(payload){
            case Payload.UnitPayload(Unit unit) -> new Codecs.UnitPayload(classOf(unit), unit(unit));
            case Payload.BuildPayload(var building) -> {
                byte[] chunk = codecs.chunk(building, content, ids);
                yield new Codecs.BuildPayload(building.block().id(), chunk[0] & 0xff, Arrays.copyOfRange(chunk, 1, chunk.length));
            }
        };
    }
}
