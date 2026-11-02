package com.xpdustry.shard.compat.v160.entity;

import arc.*;
import arc.math.*;
import com.xpdustry.shard.compat.v160.world.*;
import com.xpdustry.shard.content.epoch.*;
import com.xpdustry.shard.oracle.*;
import com.xpdustry.shard.sim.building.KindRegistry;
import com.xpdustry.shard.sim.entity.EntityGroups;
import com.xpdustry.shard.sim.entity.Pooled;
import com.xpdustry.shard.sim.rules.Attributes;
import com.xpdustry.shard.sim.rules.Rules;
import com.xpdustry.shard.sim.rules.WorldRules;
import com.xpdustry.shard.sim.weather.WeatherEvent;
import com.xpdustry.shard.sim.weather.Weathers;
import com.xpdustry.shard.sim.world.*;
import com.xpdustry.shard.sim.world.Observer;
import com.xpdustry.shard.wire.v160_7.*;
import mindustry.entities.EntityGroup;
import mindustry.game.*;
import mindustry.gen.*;
import mindustry.gen.WeatherState;
import mindustry.type.Weather.WeatherEntry;
import org.junit.jupiter.api.*;

import java.lang.reflect.*;
import java.util.*;

import static mindustry.Vars.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Gate (WP42): the weather schedule from the rules, against vanilla frame by frame for 4,000 frames. Four entries
 * (rain, a pushing sandstorm, a spore storm that spares ground units, and an always-on fog), their cooldowns rerolled
 * by the game start ({@code PlayEvent}). Each frame: every entry's cooldown, the {@code Call.createWeather}s
 * ({@link WeatherEvent.Created}), each active event's {@code writeSync} byte for byte in {@code Groups.weather} order,
 * the {@code Groups.sync} order, the environment attributes, a ground and a flying unit's statuses, the flyer's motion,
 * the random and the next entity id (expired events go back to their pool and draw a reset id).
 */
class WeatherOracleTest{
    static final int SIZE = 64, FRAMES = 4000;
    static final Epoch EPOCH = Epoch.vanilla();
    static final EntityProjector PROJECTOR = new EntityProjector(EPOCH, BuildingCodecs.builtIn());

    @Test
    void theWeatherScheduleRunsAsVanillas(){
        Vanilla.emptyWorld(SIZE, SIZE);
        state.rules.disableUnitCap = true;
        var world = new World(WorldConfig.of(EPOCH, SIZE, SIZE, 1).withKinds(KindRegistry.builtIn().withPassthrough()));
        world.buildings().beginLoad();
        for(int pos = 0; pos < world.tiles().size(); pos++) world.tiles().floor(pos, EPOCH.block("stone").id());
        world.buildings().endLoad(false);
        Rules rules = world.part(WorldRules.class).rules;
        rules.disableUnitCap = true;

        state.rules.weather.clear();
        entry("rain", 300f, 900f, 200f, 500f, 0.7f, false);
        entry("sandstorm", 100f, 400f, 300f, 700f, 1.5f, false);
        entry("sporestorm", 200f, 600f, 250f, 400f, 1f, false);
        entry("fog", 0f, 0f, 0f, 0f, 0.4f, true);
        for(WeatherEntry from : state.rules.weather){
            var to = new com.xpdustry.shard.sim.rules.WeatherEntry();
            to.weather = from.weather.id;
            to.minFrequency = from.minFrequency;
            to.maxFrequency = from.maxFrequency;
            to.minDuration = from.minDuration;
            to.maxDuration = from.maxDuration;
            to.intensity = from.intensity;
            to.always = from.always;
            to.cooldown = from.cooldown;
            rules.weather.add(to);
        }

        mindustry.gen.Unit vanillaGround = content.unit("dagger").spawn(Team.sharded, 100f, 100f);
        mindustry.gen.Unit vanillaAir = content.unit("flare").spawn(Team.sharded, 300f, 300f);
        var shardGround = com.xpdustry.shard.sim.unit.Unit.spawn(world, EPOCH.unit("dagger"), 1, 100f, 100f, 0f);
        var shardAir = com.xpdustry.shard.sim.unit.Unit.spawn(world, EPOCH.unit("flare"), 1, 300f, 300f, 0f);
        // idle controllers: the AI isn't ported, and vanilla's CommandAI nudges an idle unit
        for(mindustry.gen.Unit unit : List.of(vanillaGround, vanillaAir)) unit.controller(new mindustry.ai.types.NoAI());
        for(var unit : List.of(shardGround, shardAir)) unit.controller(new com.xpdustry.shard.sim.unit.NoAi());
        Vanilla.runPosted();

        world.rand().setState(Mathf.rand.seed0, Mathf.rand.seed1);
        int next = lastId();
        assertTrue(world.ids().peek() <= next, "Shard drew no more ids than vanilla");
        world.ids().checkNextId(next - 1);
        Events.fire(new EventType.PlayEvent());
        Weathers.play(world);

        Observer observer = world.observe(1 << 12, List.of(WeatherEvent.class), null);
        Weathers weathers = Weathers.of(world);
        EntityGroups groups = EntityGroups.of(world);
        int created = 0, pushed = 0, sporeSlowed = 0;
        for(int frame = 0; frame < FRAMES; frame++){
            String at = "frame " + frame;
            var calls = new ArrayList<String>();
            Vanilla.net(Vanilla.NetMode.server, () -> {
                Vanilla.captured().clear();
                Vanilla.frame();
                for(var sent : Vanilla.captured()){
                    if(sent.packet() instanceof CreateWeatherCallPacket p){
                        calls.add(p.weather.id + " " + p.intensity + " " + p.duration + " " + p.windX + "," + p.windY);
                    }
                }
            });
            float velBefore = shardAir.vel.len();
            world.tick();
            var events = new ArrayList<String>();
            for(WorldEvent event : observer.drain()){
                var e = (WeatherEvent.Created)event;
                events.add(e.weather() + " " + e.intensity() + " " + e.duration() + " " + e.windX() + "," + e.windY());
            }
            assertEquals(calls, events, at + ": createWeather calls");
            created += calls.size();

            for(int i = 0; i < state.rules.weather.size; i++){
                assertEquals(state.rules.weather.get(i).cooldown, rules.weather.get(i).cooldown, at + ": cooldown of entry " + i);
            }
            assertEquals(Groups.weather.size(), weathers.list().size(), at + ": active events");
            for(int i = 0; i < Groups.weather.size(); i++){
                WeatherState vanilla = Groups.weather.index(i);
                com.xpdustry.shard.sim.weather.WeatherState shard = weathers.list().get(i);
                assertEquals(vanilla.id, shard.id(), at + ": event " + i + " id");
                WeatherStateProjection projection = PROJECTOR.weather(shard);
                assertEquals(EntityClasses.WEATHER_STATE, EntityProjector.classId(shard));
                EntitySyncOracleTest.compare(at + ": event " + i + " writeSync", Golden.bytes(vanilla::writeSync),
                    out -> EntityClasses.writeSync(out, EntityClasses.WEATHER_STATE, projection, null));
            }
            var vanillaSync = new ArrayList<Integer>();
            for(var entity : Groups.sync) vanillaSync.add(entity.id());
            var shardSync = new ArrayList<Integer>();
            for(int i = 0; i < groups.sync().size(); i++) shardSync.add(groups.sync().get(i).id());
            assertEquals(vanillaSync, shardSync, at + ": Groups.sync");

            for(int attribute = 0; attribute < Attributes.NAMES.size(); attribute++){
                assertEquals(state.envAttrs.get(mindustry.world.meta.Attribute.all[attribute]), weathers.envAttrs().get(attribute),
                    at + ": envAttrs " + Attributes.NAMES.get(attribute));
            }
            statuses(at + ": ground unit", vanillaGround, shardGround);
            statuses(at + ": flying unit", vanillaAir, shardAir);
            motion(at + ": flying unit", vanillaAir, shardAir);
            if(shardAir.vel.len() != velBefore) pushed++;
            if(vanillaAir.hasEffect(mindustry.content.StatusEffects.sporeSlowed)) sporeSlowed++;
            assertEquals(Mathf.rand.seed0, world.rand().getState(0), at + ": random");
            assertEquals(Mathf.rand.seed1, world.rand().getState(1), at + ": random");
            assertEquals(lastId(), world.ids().peek(), at + ": next entity id");
        }
        assertTrue(created >= 6, "events were created: " + created);
        assertTrue(world.ids().free(Pooled.WEATHER_STATE) > 0, "expired events went back to their pool");
        assertTrue(pushed > 100, "the sandstorm pushed the flyer: " + pushed);
        assertTrue(sporeSlowed > 0, "the spore storm slowed the flyer");
        assertFalse(shardGround.statuses.hasEffect(EPOCH.status("spore-slowed").id()), "the spore storm spared the ground unit");
        assertEquals(0, observer.dropped());
    }

    static void entry(String weather, float minFrequency, float maxFrequency, float minDuration, float maxDuration, float intensity, boolean always){
        var entry = new WeatherEntry();
        entry.weather = content.getByName(mindustry.ctype.ContentType.weather, weather);
        entry.minFrequency = minFrequency;
        entry.maxFrequency = maxFrequency;
        entry.minDuration = minDuration;
        entry.maxDuration = maxDuration;
        entry.intensity = intensity;
        entry.always = always;
        state.rules.weather.add(entry);
    }

    /** Position and velocity; only the flying shape is ported, so ground units' motion isn't compared. */
    static void motion(String what, mindustry.gen.Unit vanilla, com.xpdustry.shard.sim.unit.Unit shard){
        assertEquals(vanilla.vel.x, shard.vel.x, what + " vel x");
        assertEquals(vanilla.vel.y, shard.vel.y, what + " vel y");
        assertEquals(vanilla.x, shard.x, what + " x");
        assertEquals(vanilla.y, shard.y, what + " y");
    }

    static void statuses(String what, mindustry.gen.Unit vanilla, com.xpdustry.shard.sim.unit.Unit shard){
        for(var effect : content.statusEffects()){
            assertEquals(vanilla.hasEffect(effect), shard.statuses.hasEffect(effect.id), what + " has " + effect.name);
            assertEquals(vanilla.getDuration(effect), shard.statuses.getDuration(effect.id), what + " " + effect.name + " time");
        }
    }

    static int lastId(){
        try{
            Field field = EntityGroup.class.getDeclaredField("lastId");
            field.setAccessible(true);
            return field.getInt(null);
        }catch(ReflectiveOperationException e){
            throw new IllegalStateException(e);
        }
    }
}
