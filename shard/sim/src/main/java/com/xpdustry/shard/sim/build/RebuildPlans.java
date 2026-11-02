package com.xpdustry.shard.sim.build;

import com.xpdustry.shard.common.geom.*;
import com.xpdustry.shard.common.struct.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.rules.*;
import com.xpdustry.shard.sim.team.*;
import com.xpdustry.shard.sim.tile.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.world.*;

import java.util.*;

/**
 * A team's rebuild plans ({@code TeamData.plans}), as vanilla's {@code Logic} keeps them: with {@code ghostBlocks} on,
 * a destroyed rebuildable building becomes a plan at the front of its team's queue ({@code BlockDestroyEvent},
 * {@code Building.addPlan}); a finished placement drops the team's plans it overlaps ({@code BlockBuildEndEvent},
 * {@code checkOverlappingPlans}); a player's {@code deletePlans} drops plans by tile. A site destroyed while being
 * built becomes a plan for the block it was building, with the last builder's config; one destroyed while being broken
 * becomes none.
 */
public final class RebuildPlans{
    private RebuildPlans(){
    }

    /** Logic.java:41: a destroyed building's plan, while the rules keep ghost blocks. */
    static void destroyed(Building building){
        World world = building.world();
        if(!UnitCalls.rules(world).ghostBlocks) return;
        if(!Build.info(world, building.block()).rebuildable) return;
        addPlan(building, true, false);
    }

    /**
     * {@code building.addPlan(checkPrevious, ignoreConditions)} (BuildingComp.java:339): the plan goes to the front of
     * the team's queue, replacing the one at its tile when {@code checkPrevious}.
     */
    public static void addPlan(Building building, boolean checkPrevious, boolean ignoreConditions){
        World world = building.world();
        Rules rules = UnitCalls.rules(world);
        PlaceInfo info = Build.info(world, building.block());
        if(!ignoreConditions && (!info.rebuildable || (building.team == rules.defaultTeam && UnitCalls.isCampaign(rules) && !Build.isVisible(world, info)))) return;
        Object overrideConfig = null;
        int toAdd = building.block().id();
        if(building instanceof ConstructBuild site){
            // what was being constructed; an interrupted deconstruction isn't rebuilt
            if(Build.info(world, site.current).synthetic && site.wasConstructing){
                toAdd = site.current;
                overrideConfig = site.lastConfig;
            }else{
                return;
            }
        }
        Teams teams = Teams.of(world);
        if(teams == null) return;
        List<BlockPlan> plans = teams.get(building.team).plans();
        int x = building.tileX(), y = building.tileY();
        if(checkPrevious){
            for(int i = 0; i < plans.size(); i++){
                BlockPlan plan = plans.get(i);
                if(plan.x == x && plan.y == y){
                    plans.remove(i);
                    break;
                }
            }
        }
        plans.addFirst(new BlockPlan(x, y, building.rotation, toAdd, overrideConfig == null ? building.config() : overrideConfig));
    }

    /**
     * {@code checkOverlappingPlans(team, tile)} (Logic.java:255), after a block was built at {@code pos}: the team's
     * plans whose footprint overlaps the tile's block are removed.
     */
    static void buildEnded(World world, int team, int pos){
        Teams teams = Teams.of(world);
        if(teams == null) return;
        List<BlockPlan> plans = teams.get(team).plans();
        if(plans.isEmpty()) return;
        TileGrid tiles = world.tiles();
        PlaceInfo block = Build.info(world, tiles.block(pos));
        float w = block.size * Footprint.TILE_SIZE;
        float bx = tiles.x(pos) * Footprint.TILE_SIZE + block.offset - w / 2f, by = tiles.y(pos) * Footprint.TILE_SIZE + block.offset - w / 2f;
        for(var it = plans.iterator(); it.hasNext(); ){
            BlockPlan plan = it.next();
            PlaceInfo other = Build.info(world, plan.block);
            float ow = other.size * Footprint.TILE_SIZE;
            float ox = plan.x * Footprint.TILE_SIZE + other.offset - ow / 2f, oy = plan.y * Footprint.TILE_SIZE + other.offset - ow / 2f;
            // Rect.overlaps: strict on every side
            if(bx < ox + ow && bx + w > ox && by < oy + ow && by + w > oy){
                plan.removed = true;
                it.remove();
            }
        }
    }

    /**
     * {@code deletePlans(player, positions)}'s handler (InputHandler.java:262): the team's plans at these tiles, as
     * {@code Point2.pack}ed positions, are removed, in the plans' order. Returns how many went.
     *
     * <p>Shard's bound (review 40): vanilla checks each plan against every position, so a packet of positions that
     * match nothing costs its length times the team's rebuild history. Here the positions are indexed and the plans
     * compacted in one pass, so a packet costs its length plus the history ({@link Construction#deleteVisits} counts
     * it); the plans left, and their order, are vanilla's.
     */
    public static int delete(World world, int team, int[] positions){
        Teams teams = Teams.of(world);
        if(teams == null || positions.length == 0) return 0;
        List<BlockPlan> plans = teams.get(team).plans();
        if(plans.isEmpty()) return 0;
        var wanted = new IntSet(positions.length);
        for(int pos : positions) wanted.add(pos);
        int before = plans.size();
        int[] checked = {0};
        plans.removeIf(plan -> {
            checked[0]++;
            // packing is one-to-one on shorts: a plan is at a position exactly when it packs to it
            boolean at = plan.x == (short)plan.x && plan.y == (short)plan.y && wanted.contains(Point2.pack(plan.x, plan.y));
            if(at) plan.removed = true;
            return at;
        });
        // what the packet cost: each position indexed once and each plan checked once, where vanilla checks every position
        // for every plan
        Construction construction = world.find(Construction.class);
        if(construction != null) construction.deleteVisited(world, positions.length + checked[0]);
        return before - plans.size();
    }
}
