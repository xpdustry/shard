package com.xpdustry.shard.tools.replay.vanilla;

import arc.*;
import com.xpdustry.shard.tools.replay.view.*;
import mindustry.content.*;
import mindustry.core.*;
import mindustry.entities.bullet.*;
import mindustry.game.EventType.*;
import mindustry.game.*;
import mindustry.gen.*;
import mindustry.world.*;
import mindustry.world.blocks.ConstructBlock.*;
import mindustry.world.blocks.defense.turrets.BaseTurret.*;
import mindustry.world.blocks.defense.turrets.Turret.*;

import java.util.*;
import java.util.function.*;

import static mindustry.Vars.*;

/**
 * Vanilla v160.7, booted by the oracle, as a {@link ViewSource}: it reads {@code world}, {@code Groups} and
 * {@code state} directly, and listens for unit deaths, destroyed blocks and waves. Make it after the map is hosted.
 */
public final class VanillaSource implements ViewSource{
    private final List<ViewEvent> events = new ArrayList<>();
    private final Map<BulletType, BulletStyle> styles = new IdentityHashMap<>();
    private final BitSet teamsSeen = new BitSet(256);

    public VanillaSource(){
        Events.on(UnitDestroyEvent.class, e -> events.add(new ViewEvent("unit-death", e.unit.x, e.unit.y, e.unit.rotation, e.unit.team.id,
            e.unit.type.name, e.unit.hitSize)));
        Events.on(BlockDestroyEvent.class, e -> {
            Tile tile = e.tile;
            Block block = tile.build != null ? tile.build.block : tile.block();
            events.add(new ViewEvent("block-destroy", tile.worldx() + block.offset, tile.worldy() + block.offset, 0f, tile.team().id, block.name, block.size));
        });
        Events.on(WaveEvent.class, e -> events.add(new ViewEvent("wave", 0f, 0f, 0f, state.rules.waveTeam.id, null, state.wave)));
    }

    @Override
    public String producer(){
        return "vanilla " + Version.buildString() + " (shard tools:oracle)";
    }

    @Override
    public String game(){
        return "v" + Version.buildString();
    }

    @Override
    public String content(){
        return "";
    }

    @Override
    public String map(){
        return state.map == null ? "" : state.map.plainName();
    }

    @Override
    public int width(){
        return world.width();
    }

    @Override
    public int height(){
        return world.height();
    }

    @Override
    public long tick(){
        return Math.round(state.tick);
    }

    @Override
    public List<TeamView> teams(){
        var out = new ArrayList<TeamView>();
        for(var data : state.teams.getActive()) teamsSeen.set(data.team.id);
        for(int id = teamsSeen.nextSetBit(0); id >= 0; id = teamsSeen.nextSetBit(id + 1)){
            Team team = Team.get(id);
            out.add(new TeamView(id, team.name, team.color.rgba()));
        }
        return out;
    }

    @Override
    public String blockName(int id){
        return content.block(id).name;
    }

    @Override
    public void terrain(short[] floor, short[] overlay, short[] env){
        int size = world.width() * world.height();
        for(int i = 0; i < size; i++){
            Tile tile = world.tiles.geti(i);
            floor[i] = tile.floorID();
            overlay[i] = tile.overlayID();
            env[i] = tile.build == null ? tile.blockID() : 0;
        }
    }

    @Override
    public void buildings(Consumer<BuildView> out){
        int width = world.width();
        Groups.build.each(build -> {
            teamsSeen.set(build.team.id);
            int flags = build.efficiency > 0f ? BuildView.ACTIVE : 0;
            float aim = 0f;
            String target = null;
            float progress = 0f;
            if(build instanceof BaseTurretBuild turret){
                flags |= BuildView.AIM;
                aim = turret.rotation;
                if(build instanceof TurretBuild shooter && shooter.isShooting()) flags |= BuildView.SHOOTING;
            }
            if(build instanceof ConstructBuild site){
                flags |= site.activeDeconstruct ? BuildView.DECONSTRUCT : BuildView.CONSTRUCT;
                Block current = site.current != null && site.current != Blocks.air ? site.current : site.previous;
                target = current == null ? null : current.name;
                progress = site.progress;
            }
            out.accept(new BuildView(build.tile.y * width + build.tile.x, build.block.name, build.team.id, build.rotation, build.healthf(),
                flags, aim, target, progress));
        });
    }

    @Override
    public void units(Consumer<UnitView> out){
        Groups.unit.each(unit -> {
            teamsSeen.set(unit.team.id);
            int flags = (unit.isShooting() ? UnitView.SHOOTING : 0) | (!unit.type.flying && unit.elevation > 0.01f ? UnitView.BOOSTING : 0);
            Player player = unit.getPlayer();
            out.accept(new UnitView(unit.id, unit.type.name, unit.team.id, unit.x, unit.y, unit.rotation, unit.healthf(), unit.elevation, flags,
                player == null ? null : player.plainName()));
        });
    }

    @Override
    public void bullets(Consumer<BulletView> out){
        Groups.bullet.each(bullet -> out.accept(new BulletView(bullet.id, style(bullet.type), bullet.team.id, bullet.x, bullet.y, bullet.rotation(), bullet.time)));
    }

    @Override
    public List<ViewEvent> events(){
        var out = List.copyOf(events);
        events.clear();
        return out;
    }

    @Override
    public Status status(){
        return new Status(state.wave, state.rules.waves ? state.wavetime : 0f, state.enemies);
    }

    /** What a bullet type looks like; one style per type. */
    BulletStyle style(BulletType type){
        return styles.computeIfAbsent(type, VanillaSource::describe);
    }

    static BulletStyle describe(BulletType type){
        String kind = kind(type);
        String sprite = "";
        float width = type.hitSize, height = type.hitSize, shrinkY = 0f;
        int front = -1, back = -1;
        if(type instanceof BasicBulletType basic){
            sprite = basic.sprite;
            width = basic.width;
            height = basic.height;
            front = basic.frontColor.rgba();
            back = basic.backColor.rgba();
            shrinkY = basic.shrinkY;
        }else if(type instanceof LaserBulletType laser){
            width = laser.width;
            height = laser.length;
            front = laser.colors[laser.colors.length - 1].rgba();
            back = laser.colors[0].rgba();
        }else if(type instanceof ContinuousBulletType continuous){
            height = continuous.length;
            front = back = type.hitColor.rgba();
        }else{
            front = back = type.hitColor.rgba();
        }
        return new BulletStyle(kind, sprite, width, height, front, back, shrinkY, type.lifetime, type.trailLength, type.trailWidth,
            type.trailColor.rgba(), type.hitSize);
    }

    /** The bullet class family: {@code BasicBulletType} is {@code basic}; anonymous subclasses take their parent's. */
    static String kind(BulletType type){
        Class<?> c = type.getClass();
        while(c.isAnonymousClass() || c.getSimpleName().isEmpty()) c = c.getSuperclass();
        String name = c.getSimpleName();
        if(name.endsWith("BulletType")) name = name.substring(0, name.length() - "BulletType".length());
        return name.isEmpty() ? "bullet" : Character.toLowerCase(name.charAt(0)) + name.substring(1);
    }
}
