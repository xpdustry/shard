package com.xpdustry.shard.sim.toy;

import com.xpdustry.shard.common.math.*;
import com.xpdustry.shard.common.registry.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.unit.Unit;
import com.xpdustry.shard.sim.world.*;

/** See {@link Chaos}. Runs in {@code MISC}, before units and buildings update. */
@Register
public final class ChaosHandler implements PhaseHandler{
    @Override
    public Phase phase(){
        return Phase.MISC;
    }

    @Override
    public void run(World world){
        Chaos chaos = world.part(Chaos.class);
        if(!chaos.enabled) return;
        Rand rand = world.rand();
        var tiles = world.tiles();
        for(int i = 0; i < 3; i++){
            var block = switch(rand.random(2)){
                case 0 -> ToyContent.COUNTER;
                case 1 -> ToyContent.LINK;
                default -> ToyContent.WALL;
            };
            int x = rand.random(1, tiles.width() - 2), y = rand.random(1, tiles.height() - 2);
            if(free(world, block.size(), x, y)){
                Building building = world.buildings().create(block, rand.random(1, 3), x, y, rand.random(3), Cause.SERVER);
                if(building instanceof Link link){
                    link.target = any(world, rand);
                    link.watched = world.units().isEmpty() ? null : world.units().get(rand.random(world.units().size() - 1));
                }
                chaos.placed++;
            }
        }
        if(world.buildings().count() > 0 && rand.chance(0.6)){
            Building victim = any(world, rand);
            if(victim != null){
                world.buildings().remove(victim, new Cause.Team(victim.team));
                chaos.removed++;
            }
        }
        if(rand.chance(0.5)){
            Unit unit = Unit.create(world, ToyContent.DRONE, rand.random(1, 3));
            unit.x = rand.random(tiles.width() * 8f);
            unit.y = rand.random(tiles.height() * 8f);
            unit.controller = new Follow(any(world, rand));
            unit.add();
            chaos.spawned++;
        }
        if(!world.units().isEmpty() && rand.chance(0.3)){
            Unit unit = world.units().get(rand.random(world.units().size() - 1));
            unit.kill();
            chaos.killed++;
        }
        int shots = rand.random(4);
        for(int i = 0; i < shots; i++){
            Building owner = any(world, rand);
            world.bullets().add(world.ids(), rand.random(5), owner == null ? 0 : owner.team, rand.random(100f), rand.random(100f),
                rand.range(2f), rand.range(2f), rand.random(10f, 60f), rand.random(5f, 20f), owner);
            chaos.fired++;
        }
    }

    static boolean free(World world, int size, int x, int y){
        int offset = -(size - 1) / 2;
        for(int dx = 0; dx < size; dx++){
            for(int dy = 0; dy < size; dy++){
                int tx = x + dx + offset, ty = y + dy + offset;
                if(!world.tiles().inBounds(tx, ty) || world.tiles().build(world.tiles().pos(tx, ty)) != -1) return false;
            }
        }
        return true;
    }

    /** A random live building, picked by tile, or null. */
    static Building any(World world, Rand rand){
        for(int attempt = 0; attempt < 8; attempt++){
            Building building = world.buildings().at(rand.random(world.tiles().size() - 1));
            if(building != null) return building;
        }
        return null;
    }
}
