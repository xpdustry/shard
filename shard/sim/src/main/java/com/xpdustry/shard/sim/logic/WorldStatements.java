package com.xpdustry.shard.sim.logic;

import com.xpdustry.shard.common.geom.*;
import com.xpdustry.shard.common.math.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.logic.*;
import com.xpdustry.shard.logic.HostOp.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.bullet.*;
import com.xpdustry.shard.sim.combat.*;
import com.xpdustry.shard.sim.entity.*;
import com.xpdustry.shard.sim.rules.*;
import com.xpdustry.shard.sim.team.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.waves.*;
import com.xpdustry.shard.sim.weather.*;
import com.xpdustry.shard.sim.world.*;

import java.util.*;

/**
 * World processors' statements on units, bullets, waves and weather, as {@code LExecutor}'s {@code FetchI},
 * {@code SpawnUnitI}, {@code SpawnBulletI}, {@code ApplyEffectI}, {@code ExplosionI}, {@code SpawnWaveI},
 * {@code SenseWeatherI} and {@code SetWeatherI} run them (LExecutor.java:1372-1436, 1615-1777, 2004-2044, 2159-2199),
 * for {@link WorldHost}, with their Calls in vanilla's order.
 *
 * <p>One deviation: {@code bullet} leaves its result null, where vanilla gives the pooled {@code Bullet}: Shard's bullets
 * are rows of a table, with no object a processor could hold.
 */
final class WorldStatements{
    static final float TILESIZE = 8f;
    private static final KindKey ITEM_TURRET = KindKey.of("item-turret"), LIQUID_TURRET = KindKey.of("liquid-turret"),
        CONTINUOUS_LIQUID_TURRET = KindKey.of("continuous-liquid-turret"), POWER_TURRET = KindKey.of("power-turret"),
        CONTINUOUS_TURRET = KindKey.of("continuous-turret");

    private final WorldHost host;
    private final World world;

    WorldStatements(WorldHost host){
        this.host = host;
        this.world = host.world;
    }

    //region fetch

    /** FetchI (LExecutor.java:1388): a team's units, players, cores and buildings, by index, or how many. */
    void fetch(Processor p, Fetch f){
        int index = p.numi(f.index());
        int team = WorldHost.team(p, f.team());
        if(team < 0) return;
        Object extra = p.obj(f.extra());
        UnitDef unitType = extra instanceof UnitDef def ? world.content().unit(def.id()) : null;
        BlockDef block = extra instanceof BlockDef def ? world.content().block(def.id()) : null;
        TeamUnits units = TeamUnits.of(world);
        switch(f.type()){
            case unit -> {
                UnitSeq seq = unitType == null ? units.units(team) : units.units(team, unitType);
                p.setobj(f.result(), seq == null || index < 0 || index >= seq.size() ? null : seq.get(index));
            }
            case player -> {
                List<Player> players = players(team);
                Unit unit = index < 0 || index >= players.size() ? null : players.get(index).unit();
                // a player in a turret is the turret
                p.setobj(f.result(), unit != null && unit.blockTile != null ? unit.blockTile : unit);
            }
            case core -> {
                List<Building> cores = cores(team);
                p.setobj(f.result(), index < 0 || index >= cores.size() ? null : cores.get(index));
            }
            case build -> {
                List<Building> builds = block == null ? world.buildings().teamBuildings(team) : world.buildings().teamBuildings(team, block);
                p.setobj(f.result(), index < 0 || index >= builds.size() ? null : builds.get(index));
            }
            case unitCount -> {
                UnitSeq seq = unitType == null ? units.units(team) : units.units(team, unitType);
                p.setnum(f.result(), seq == null ? 0 : seq.size());
            }
            case coreCount -> p.setnum(f.result(), cores(team).size());
            case playerCount -> p.setnum(f.result(), players(team).size());
            case buildCount -> p.setnum(f.result(), (block == null ? world.buildings().teamBuildings(team) : world.buildings().teamBuildings(team, block)).size());
        }
    }

    /** {@code team.data().players}: the team's players, in {@code Groups.player} order. */
    private List<Player> players(int team){
        var out = new ArrayList<Player>();
        Players players = Players.of(world);
        if(players == null) return out;
        for(int i = 0; i < players.list().size(); i++){
            Player player = players.list().get(i);
            if(player.team == team) out.add(player);
        }
        return out;
    }

    /** {@code team.data().cores}. */
    private List<Building> cores(int team){
        Teams teams = Teams.of(world);
        return teams == null ? List.of() : teams.cores(world, team);
    }

    //endregion
    //region units

    /** SpawnUnitI (LExecutor.java:1632): a unit, a little off the point so units don't stack, with or without the spawn effect. */
    void spawn(Processor p, SpawnUnit s){
        int team = WorldHost.team(p, s.team());
        if(team < 0 || !(p.obj(s.type()) instanceof UnitDef def)) return;
        UnitDef type = world.content().unit(def.id());
        if(type.internal() || !Units.canCreate(world, team, type)) return;
        Rand rand = world.rand();
        // random offset to prevent stacking
        float x = p.numf(s.x()) * TILESIZE + Mathf.range(rand, 0.01f);
        float y = p.numf(s.y()) * TILESIZE + Mathf.range(rand, 0.01f);
        Unit unit = Unit.spawn(world, type, team, x, y, p.numf(s.rotation()));
        if(p.bool(s.effect())){
            WaveSpawner.spawnEffect(unit);
        }else{
            // manually call events
            unit.unloaded();
        }
        p.setobj(s.result(), unit);
    }

    /** ApplyEffectI (LExecutor.java:1766): a status applied for a number of seconds, or cleared. */
    void status(Processor p, ApplyStatus s){
        if(!(p.obj(s.unit()) instanceof Unit unit) || !(p.obj(s.effect()) instanceof StatusDef effect)) return;
        if(s.clear()){
            unit.statuses.unapply(effect.id());
        }else{
            unit.statuses.apply(unit, effect.id(), p.numf(s.duration()) * 60f);
        }
    }

    //endregion
    //region bullets

    /** SpawnBulletI (LExecutor.java:1675): a unit weapon's or a turret's bullet, as its {@code create} makes it. */
    void bullet(Processor p, SpawnBullet s){
        Object fromVal = p.obj(s.from());
        Entity owner = p.obj(s.owner()) instanceof Unit || p.obj(s.owner()) instanceof Building ? (Entity)p.obj(s.owner()) : null;
        int team = WorldHost.team(p, s.team());
        if(team < 0 && owner != null) team = Targets.team(owner);
        if(team < 0) team = VanillaIds.DERELICT;

        BulletDef type = bulletOf(p, fromVal, s.index());
        if(type == null) return;
        world.bullets().types(world).get(type).create(world, owner, owner, team, p.numf(s.x()) * TILESIZE, p.numf(s.y()) * TILESIZE,
            p.numf(s.rotation()), p.numf(s.damage()), p.numf(s.velocityScl()), p.numf(s.lifeScl()), null, p.numf(s.aimX()) * TILESIZE,
            p.numf(s.aimY()) * TILESIZE, null);
        // Shard's bullets have no object to hold
        p.setobj(s.result(), null);
    }

    /** The bullet {@code from} shoots: a unit type's weapon by index, an item or liquid turret's ammo, a turret's shot. */
    private BulletDef bulletOf(Processor p, Object fromVal, int weapon){
        ContentEpoch content = world.content();
        if(fromVal instanceof UnitDef def){
            List<WeaponDef> weapons = content.unit(def.id()).weapons();
            int index = p.numi(weapon);
            return index < 0 || index >= weapons.size() ? null : weapons.get(index).bullet();
        }
        if(!(fromVal instanceof BlockDef def)) return null;
        BlockDef block = content.block(def.id());
        if(!block.baked()) return null;
        if(block.isA(ITEM_TURRET)){
            return p.obj(weapon) instanceof ItemDef item ? ammo(block, item.name()) : null;
        }
        if(block.isA(LIQUID_TURRET) || block.isA(CONTINUOUS_LIQUID_TURRET)){
            return p.obj(weapon) instanceof LiquidDef liquid ? ammo(block, liquid.name()) : null;
        }
        if(block.isA(POWER_TURRET) || block.isA(CONTINUOUS_TURRET)){
            String shot = block.fields().has("shootType") ? block.fields().s("shootType") : null;
            return shot == null ? null : content.bullet(shot);
        }
        return null;
    }

    /** {@code ammoTypes.get(ammo)}: the bullet an item or liquid makes in the turret, or null. */
    private BulletDef ammo(BlockDef block, String ammo){
        Map<String, Object> map = block.fields().map("ammoTypes");
        Object bullet = map == null ? null : map.get(ammo);
        return bullet instanceof String name ? world.content().bullet(name) : null;
    }

    /**
     * ExplosionI (LExecutor.java:2023): {@code Call.logicExplosion}, which runs here first: area damage to the other teams
     * (every team, with none), at most 100 tiles across, a shockwave when it pierces; then the Call goes out.
     */
    void explosion(Processor p, Explosion e){
        int team = WorldHost.team(p, e.team());
        float x = p.numf(e.x()) * TILESIZE, y = p.numf(e.y()) * TILESIZE, radius = Math.min(p.numf(e.radius()), 100) * TILESIZE;
        float damage = p.numf(e.damage());
        boolean air = p.bool(e.air()), ground = p.bool(e.ground()), pierce = p.bool(e.pierce()), effect = p.bool(e.effect());
        if(damage >= 0f){
            Damage.damage(world, team < 0 ? Targets.ANY_TEAM : team, x, y, radius, damage, pierce, air, ground, true, null, 1f);
        }
        host.publish(new LogicWorldEvent.Explosion(world.ticks(), team, x, y, radius, damage, air, ground, pierce, effect));
    }

    //endregion
    //region waves and weather

    /**
     * SpawnWaveI (LExecutor.java:2173): the next wave, as the countdown would; or this wave's groups at a point, each
     * unit scattered up to two tiles around it.
     */
    void spawnWave(Processor p, SpawnWave s){
        if(p.bool(s.natural())){
            Waves.skipWave(world);
            return;
        }
        float spawnX = p.numf(s.x()) * TILESIZE, spawnY = p.numf(s.y()) * TILESIZE;
        int packed = Point2.pack(p.numi(s.x()), p.numi(s.y()));
        WaveSpawner spawner = WaveSpawner.of(world);
        WaveState state = WaveState.of(world);
        if(spawner == null) return;
        int wave = (state == null ? 1 : state.wave) - 1;
        Rand rand = world.rand();
        var spread = new Vec2();
        for(SpawnGroup group : host.rules().spawns){
            if(group.type < 0 || group.type >= world.content().units() || (group.spawn != -1 && group.spawn != packed)) continue;
            int spawned = group.getSpawned(wave);
            for(int i = 0; i < spawned; i++){
                // Tmp.v1.rnd(spread)
                spread.setToRandomDirection(rand).scl(TILESIZE * 2);
                spawner.spawnUnit(world, group, spawnX + spread.x, spawnY + spread.y);
            }
        }
    }

    /** SenseWeatherI: whether the weather is on. */
    void weatherSense(Processor p, WeatherSense w){
        Weathers weathers = Weathers.of(world);
        p.setbool(w.to(), p.obj(w.weather()) instanceof WeatherDef weather && weathers != null && weathers.isActive(weather.id()));
    }

    /**
     * SetWeatherI (LExecutor.java:1733): on, a weather that isn't is created in a random direction
     * ({@code Call.createWeather}), and one that is gets four seconds to live (vanilla quirk: setting it again shortens
     * it); off, one with more left fades out over four seconds.
     */
    void weatherSet(Processor p, WeatherSet w){
        Weathers weathers = Weathers.of(world);
        if(weathers == null || !(p.obj(w.weather()) instanceof WeatherDef weather)) return;
        if(p.bool(w.state())){
            if(!weathers.isActive(weather.id())){
                var wind = new Vec2().setToRandomDirection(world.rand());
                Weathers.createWeather(world, weather.id(), 1f, WeatherState.FADE_TIME, wind.x, wind.y);
            }else{
                weathers.instance(weather.id()).life = WeatherState.FADE_TIME;
            }
        }else if(weathers.isActive(weather.id()) && weathers.instance(weather.id()).life > WeatherState.FADE_TIME){
            weathers.instance(weather.id()).life = WeatherState.FADE_TIME;
        }
    }

    //endregion
}
