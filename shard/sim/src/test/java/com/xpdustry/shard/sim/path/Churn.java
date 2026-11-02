package com.xpdustry.shard.sim.path;

import com.xpdustry.shard.common.math.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.world.*;

import java.util.*;

import static com.xpdustry.shard.sim.path.PathWorlds.*;

/**
 * Random maps and edits for the path tests, drawn from the world's {@code Rand}, so a fork replays them. The edits
 * cover what moves a field: team walls (cost up for enemies, impassable for their own team), removals (cost down),
 * floors (liquid, deep, damaging), static walls (on load they get darkness, so {@code legSolid}; edits never do, as in
 * vanilla), turrets (threat), cores and spawns
 * (targets), rings of walls (unreachable pockets), damage without a tile change (the health quirk) and batches large
 * enough for the full-rebuild fallback.
 */
final class Churn{
    static final String[] FLOORS = {"stone", "stone", "stone", "shallow-water", "deep-water", "tar", "molten-slag", "sand-water", "darksand"};
    static final String[] WALLS = {"copper-wall", "titanium-wall", "copper-wall-large"};

    /**
     * Floors, static walls, buildings of four teams, two cores and two spawns, as a map load: the static walls get
     * their darkness. Call before the first flush, then {@link PathService#invalidateAll()}.
     */
    static void generate(World world){
        world.buildings().beginLoad();
        Rand rand = world.rand();
        int width = world.tiles().width(), height = world.tiles().height();
        for(int pos = 0; pos < world.tiles().size(); pos++) world.tiles().floor(pos, id("stone"));
        for(int i = 0; i < 14; i++) disc(world, rand.random(width - 1), rand.random(height - 1), 2 + rand.random(5), FLOORS[rand.random(FLOORS.length - 1)], false);
        for(int i = 0; i < 10; i++) disc(world, rand.random(width - 1), rand.random(height - 1), 1 + rand.random(3), "stone-wall", true);
        place(world, "core-shard", 1, 3 + rand.random(width / 4), 3 + rand.random(height - 7));
        place(world, "core-shard", 2, width - 4 - rand.random(width / 4), 3 + rand.random(height - 7));
        for(int i = 0; i < 40; i++) place(world, WALLS[rand.random(1)], rand.random(3), rand.random(width - 1), rand.random(height - 1));
        for(int i = 0; i < 6; i++) place(world, "duo", 1 + rand.random(2), rand.random(width - 1), rand.random(height - 1));
        for(int i = 0; i < 2; i++) overlay(world, rand.random(width - 1), rand.random(height - 1), "spawn");
        world.buildings().endLoad(false);
    }

    /** One random edit. */
    static void edit(World world){
        Rand rand = world.rand();
        int width = world.tiles().width(), height = world.tiles().height();
        int x = rand.random(width - 1), y = rand.random(height - 1);
        switch(rand.random(13)){
            case 0, 1, 2 -> place(world, WALLS[rand.random(WALLS.length - 1)], rand.random(3), x, y);
            case 3, 4 -> {
                List<Building> all = buildings(world);
                if(!all.isEmpty()){
                    Building victim = all.get(rand.random(all.size() - 1));
                    // keep at least one core most of the time
                    if(!BLOCKS.is(victim.block().id(), PathBlocks.CORE) || rand.chance(0.3)) remove(world, victim);
                }
            }
            case 5 -> disc(world, x, y, rand.random(2), FLOORS[rand.random(FLOORS.length - 1)], false);
            case 6 -> wall(world, x, y, rand.chance(0.5) ? "stone-wall" : "air");
            case 7 -> place(world, "duo", 1 + rand.random(2), x, y);
            case 8 -> overlay(world, x, y, rand.chance(0.5) ? "spawn" : "air");
            case 9 -> ring(world, x, y, 2 + rand.random(2));
            case 10 -> place(world, "core-shard", 1 + rand.random(2), x, y);
            case 11 -> {
                // damage without a tile change: the view keeps the health it sampled
                List<Building> all = buildings(world);
                if(!all.isEmpty()) all.get(rand.random(all.size() - 1)).health *= 0.4f;
            }
            case 12 -> {
                // a building's tile changes without moving anything: re-sample its health
                List<Building> all = buildings(world);
                if(!all.isEmpty()) world.part(PathService.class).tileChanged(all.get(rand.random(all.size() - 1)).tile());
            }
            default -> {
                for(int i = 0; i < 6; i++) wall(world, Math.min(width - 1, x + i), y, "stone-wall");
            }
        }
    }

    /** A rectangle of walls over more than 5% of the map: the batch falls back to full rebuilds. */
    static void flood(World world){
        Rand rand = world.rand();
        int width = world.tiles().width(), height = world.tiles().height();
        int team = rand.random(3), w = width / 3, h = height / 3, x0 = rand.random(width - w), y0 = rand.random(height - h);
        for(int x = x0; x < x0 + w; x++){
            for(int y = y0; y < y0 + h; y++){
                if((x + y) % 3 != 0) place(world, "copper-wall", team, x, y);
            }
        }
    }

    /** A closed ring of static walls: the tiles inside become a pocket no ground target reaches. */
    static void ring(World world, int cx, int cy, int r){
        for(int x = cx - r; x <= cx + r; x++){
            for(int y = cy - r; y <= cy + r; y++){
                boolean edge = Math.abs(x - cx) == r || Math.abs(y - cy) == r;
                if(edge && world.tiles().inBounds(x, y)) wall(world, x, y, "stone-wall");
            }
        }
    }

    private static void disc(World world, int cx, int cy, int r, String name, boolean block){
        for(int x = cx - r; x <= cx + r; x++){
            for(int y = cy - r; y <= cy + r; y++){
                if((x - cx) * (x - cx) + (y - cy) * (y - cy) > r * r || !world.tiles().inBounds(x, y)) continue;
                if(block) wall(world, x, y, name);
                else floor(world, x, y, name);
            }
        }
    }

    private Churn(){}
}
