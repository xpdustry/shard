package com.xpdustry.shard.oracle;

import arc.func.*;
import mindustry.game.*;
import mindustry.gen.*;
import mindustry.type.*;
import mindustry.world.*;
import com.xpdustry.shard.oracle.Oracle.*;

import java.util.*;

import static mindustry.Vars.*;

/**
 * The simulation oracle: a vanilla world stepped one server frame at a time, with the server's time step
 * ({@code Time.delta == 1}, so 60 frames are one second and {@code state.tick} grows by 1 per frame).
 * <pre>{@code
 * Sim sim = Sim.empty(16, 8);
 * Building source = sim.place("item-source", 1, 1, 0);
 * source.configure(Items.copper);
 * for(int x = 2; x < 12; x++) sim.place("conveyor", x, 1, 0);
 * sim.place("item-void", 12, 1, 0);
 * sim.step(600);
 * sim.state().buildings();  // every building's items, config and block-specific fields
 * }</pre>
 * Determinism: {@code Mathf.rand} and the logic {@code rand} are seeded with 0 on creation, and physics runs in
 * {@code asyncCore} as on a server (threads, joined every frame). Ground-unit flowfields and RTS paths come from
 * vanilla's {@code Pathfinder} and {@code ControlPathfinder} threads, which run on their own clock as on a real server,
 * so unit movement that depends on them is not frame-exact between runs.
 */
public final class Sim{
    private long frames;

    private Sim(){}

    /** A built-in or {@code .msav} map, hosted as the server's {@code host <map> survival} command does. */
    public static Sim host(String map){
        return host(map, Gamemode.survival);
    }

    public static Sim host(String map, Gamemode mode){
        Vanilla.host(map, mode);
        return new Sim();
    }

    /** An empty, coreless stone world in play ({@code canGameOver = false}). */
    public static Sim empty(int width, int height){
        Vanilla.emptyWorld(width, height);
        return new Sim();
    }

    /** Edits the current rules in place. */
    public Sim rules(Cons<Rules> edit){
        edit.get(state.rules);
        return this;
    }

    /** Places a building for team sharded, as a map editor would ({@code tile.setBlock}; nothing is consumed). */
    public Building place(String block, int x, int y, int rotation){
        return place(content.block(block), x, y, rotation, Team.sharded);
    }

    public Building place(Block block, int x, int y, int rotation, Team team){
        if(block == null) throw new IllegalArgumentException("no such block");
        Tile tile = world.tile(x, y);
        if(tile == null) throw new IllegalArgumentException("(" + x + ", " + y + ") is outside the world");
        tile.setBlock(block, team, rotation);
        Vanilla.runPosted();
        return tile.build;
    }

    public Building build(int x, int y){
        return world.build(x, y);
    }

    /** Spawns and adds a unit at a world position (8 world units per tile). */
    public Unit spawn(String type, Team team, float x, float y){
        UnitType unitType = content.unit(type);
        if(unitType == null) throw new IllegalArgumentException("no unit type " + type);
        Unit unit = unitType.spawn(team, x, y);
        Vanilla.runPosted();
        return unit;
    }

    /** Runs server frames. */
    public Sim step(int frames){
        for(int i = 0; i < frames; i++) Vanilla.frame();
        this.frames += frames;
        return this;
    }

    /** Runs frames until the condition holds; returns how many ran, or -1 if it did not hold within {@code maxFrames}. */
    public int until(Boolp condition, int maxFrames){
        for(int i = 0; i < maxFrames; i++){
            if(condition.get()) return i;
            step(1);
        }
        return condition.get() ? maxFrames : -1;
    }

    /** Frames run by this sim. */
    public long frames(){
        return frames;
    }

    /** The world's state now: buildings (with block-specific fields), units, and core items per team. */
    public record State(long frames, double tick, int wave, float wavetime, boolean gameOver,
                        List<BuildingView> buildings, List<EntityView> units, Map<String, Map<String, Integer>> coreItems){
        public String json(){
            return Json.write(this);
        }

        public BuildingView building(int x, int y){
            return buildings.stream().filter(b -> b.x() == x && b.y() == y).findFirst().orElse(null);
        }
    }

    public State state(){
        Map<String, Map<String, Integer>> cores = new TreeMap<>();
        for(var data : state.teams.getActive()){
            if(data.core() == null) continue;
            Map<String, Integer> items = new LinkedHashMap<>();
            data.core().items.each((item, amount) -> items.put(item.name, amount));
            cores.put(data.team.name, items);
        }
        return new State(frames, state.tick, state.wave, state.wavetime, state.gameOver, Oracle.buildings(), Oracle.units(), cores);
    }
}
