package com.xpdustry.shard.tools.replay.shard;

import com.xpdustry.shard.content.*;
import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.tile.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.world.*;
import com.xpdustry.shard.tools.replay.view.*;
import com.xpdustry.shard.sim.world.Observer;

import java.util.*;
import java.util.function.*;

/**
 * A Shard {@link World} as a {@link ViewSource}. It reads the world between ticks, on the world's thread, which is
 * where a live recorder would sit (the {@code SYNC} phase's frozen projection, ARCHITECTURE.md §8); unit deaths come
 * from an {@link Observer}, as an external recorder would get them.
 */
public final class ShardSource implements ViewSource{
    private final World world;
    private final String map;
    private final Observer observer;
    private final Map<Integer, UnitView> lastSeen = new HashMap<>();
    private final BitSet teamsSeen = new BitSet(256);

    public ShardSource(World world, String map){
        this.world = world;
        this.map = map;
        this.observer = world.observe(4096, List.of(UnitEvent.class), null);
    }

    @Override
    public String producer(){
        return "shard " + BuildInfo.id();
    }

    @Override
    public String game(){
        return "v160.7";
    }

    @Override
    public String content(){
        return world.content().hash();
    }

    @Override
    public String map(){
        return map;
    }

    @Override
    public int width(){
        return world.tiles().width();
    }

    @Override
    public int height(){
        return world.tiles().height();
    }

    @Override
    public long tick(){
        return world.ticks();
    }

    @Override
    public List<TeamView> teams(){
        var out = new ArrayList<TeamView>();
        for(int id = teamsSeen.nextSetBit(0); id >= 0; id = teamsSeen.nextSetBit(id + 1)) out.add(new TeamView(id, Teams.name(id), Teams.color(id)));
        return out;
    }

    @Override
    public String blockName(int id){
        return world.content().block(id).name();
    }

    @Override
    public void terrain(short[] floor, short[] overlay, short[] env){
        TileGrid tiles = world.tiles();
        for(int pos = 0; pos < tiles.size(); pos++){
            floor[pos] = tiles.floor(pos);
            overlay[pos] = tiles.overlay(pos);
            env[pos] = tiles.build(pos) < 0 ? tiles.block(pos) : 0;
        }
    }

    @Override
    public void buildings(Consumer<BuildView> out){
        world.buildings().each(build -> {
            teamsSeen.set(build.team);
            out.accept(new BuildView(build.tile(), build.block().name(), build.team, build.rotation, build.healthf(), 0, 0f, null, 0f));
        });
    }

    @Override
    public void units(Consumer<UnitView> out){
        lastSeen.clear();
        world.units().each(unit -> {
            teamsSeen.set(unit.team);
            var view = new UnitView(unit.id(), unit.type().name(), unit.team, unit.x, unit.y, unit.rotation, unit.healthf(), unit.elevation, 0,
                unit.isPlayer() ? "player" : null);
            lastSeen.put(unit.id(), view);
            out.accept(view);
        });
    }

    @Override
    public void bullets(Consumer<BulletView> out){
        // Shard has no weapons yet (WP43); the table is read when it has rows
        var bullets = world.bullets();
        for(int row = 0; row < bullets.size(); row++){
            float rotation = (float)Math.toDegrees(Math.atan2(bullets.velY[row], bullets.velX[row]));
            out.accept(new BulletView(bullets.id[row], new BulletStyle("basic", "bullet", 5f, 7f, -1, 0xffd37fff, 0.5f, bullets.lifetime[row], 0, 0f, 0,
                bullets.hitSize[row]), bullets.team[row], bullets.x[row], bullets.y[row], rotation, bullets.time[row]));
        }
    }

    @Override
    public List<ViewEvent> events(){
        var out = new ArrayList<ViewEvent>();
        for(WorldEvent event : observer.drain()){
            if(event instanceof UnitEvent.Death death){
                UnitView unit = lastSeen.get(death.unit());
                if(unit != null) out.add(new ViewEvent("unit-death", unit.x(), unit.y(), unit.rotation(), unit.team(), unit.type(), 8f));
            }
        }
        return out;
    }

    @Override
    public Status status(){
        return new Status(0, 0f, 0);
    }

    /** Vanilla's six named teams; others are {@code team#id}, grey. */
    static final class Teams{
        static final String[] NAMES = {"derelict", "sharded", "crux", "malis", "green", "blue", "neoplastic"};
        static final int[] COLORS = {0x4d4e58ff, 0xffd37fff, 0xf25555ff, 0xa27ce5ff, 0x54d67dff, 0x6c87fdff, 0xe05438ff};

        static String name(int id){
            return id < NAMES.length ? NAMES[id] : "team#" + id;
        }

        static int color(int id){
            return id < COLORS.length ? COLORS[id] : 0x808080ff;
        }
    }
}
