package com.xpdustry.shard.sim.build;

import com.xpdustry.shard.common.math.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.sim.blocks.power.*;
import com.xpdustry.shard.sim.blocks.storage.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.fog.*;
import com.xpdustry.shard.sim.logic.*;
import com.xpdustry.shard.sim.objectives.*;
import com.xpdustry.shard.sim.rules.*;
import com.xpdustry.shard.sim.spatial.*;
import com.xpdustry.shard.sim.team.*;
import com.xpdustry.shard.sim.tile.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.world.*;

import java.util.*;

/**
 * Vanilla's {@code Build} (Build.java) and {@code ConstructBlock}'s static methods: whether a block may be placed or
 * broken, and the remote methods that start and finish construction. {@link #beginPlace}, {@link #beginBreak},
 * {@link #constructFinish} and {@link #deconstructFinish} are the {@code Call}s: each runs vanilla's handler, then
 * publishes its {@link BuildEvent}, as the server sends the packet after running it locally.
 *
 * <p>Tiles are (x, y) here, as vanilla's calls take them. Changes go through {@link Buildings} with
 * {@link Cause.Team} for a builder's team (the journal can't name the player behind a unit yet) or
 * {@link Cause#SERVER} without one.
 *
 * <p>Fog ({@link FogControl}): with static fog, undiscovered tiles are refused, and a construction finished out of its
 * team's sight plays no place sound, so draws no pitch. Not modelled: the campaign's sector darkness and unlocks
 * (every block is unlocked outside the campaign).
 */
public final class Build{
    private Build(){
    }

    // region rules

    /** {@code validPlace(type, team, x, y, rotation)}: visible blocks, the core radius checked, no unit in the way. */
    public static boolean validPlace(World world, BlockDef type, int team, int x, int y, int rotation){
        return validPlace(world, type, team, x, y, rotation, true, true);
    }

    public static boolean validPlace(World world, BlockDef type, int team, int x, int y, int rotation, boolean checkVisible, boolean checkCoreRadius){
        return validPlaceIgnoreUnits(world, type, team, x, y, rotation, checkVisible, checkCoreRadius) && checkNoUnitOverlap(world, type, x, y);
    }

    /** {@code checkNoUnitOverlap(type, x, y)}: a solid block needs its footprint free of ground units. */
    public static boolean checkNoUnitOverlap(World world, BlockDef type, int x, int y){
        PlaceInfo info = info(world, type);
        if(!info.solid && !info.solidifes) return true;
        SpatialIndex units = SpatialIndex.of(world);
        float size = info.size * Footprint.TILE_SIZE;
        return units == null || !units.anyEntities(x * Footprint.TILE_SIZE + info.offset - info.size * Footprint.TILE_SIZE / 2f,
            y * Footprint.TILE_SIZE + info.offset - info.size * Footprint.TILE_SIZE / 2f, size, size, true);
    }

    /**
     * {@code validPlaceIgnoreUnits(type, team, x, y, rotation, checkVisible, checkCoreRadius)} (Build.java:183): the
     * block is placeable, no enemy core protects the spot, the block's own rule allows the tile, and every tile of the
     * footprint is in the map, lit, on ground it can stand on, owned by the team or derelict, and holds only what the
     * block may replace.
     */
    public static boolean validPlaceIgnoreUnits(World world, BlockDef type, int team, int x, int y, int rotation, boolean checkVisible, boolean checkCoreRadius){
        if(type == null) return false;
        rotation = rotation(rotation);
        Rules rules = UnitCalls.rules(world);
        PlaceInfo info = info(world, type);
        // the wave team can build whatever they want as long as it's visible: banned blocks are not applicable
        if(!rules.editor && checkVisible && (!environmentBuildable(world, info) || (!isPlaceable(world, info) && !(rules.waves && team == rules.waveTeam && isVisible(world, info))))){
            return false;
        }
        float wx = x * Footprint.TILE_SIZE + info.offset, wy = y * Footprint.TILE_SIZE + info.offset;
        if(!rules.editor && checkCoreRadius && !(!checkVisible && team == VanillaIds.DERELICT)){
            Teams teams = Teams.of(world);
            if(rules.polygonCoreProtection){
                // the closest core of a team that protects its cores decides
                float mindst = Float.MAX_VALUE;
                Core closest = null;
                if(teams != null){
                    for(int other : teams.activeTeams().toArray()){
                        if(!rules.teams.get(other).protectCores) continue;
                        for(Core core : teams.cores(other)){
                            float dx = core.x() - wx, dy = core.y() - wy, dst = dx * dx + dy * dy;
                            if(dst < mindst){
                                closest = core;
                                mindst = dst;
                            }
                        }
                    }
                }
                if(closest != null && closest.team != team) return false;
            }else if(teams != null && teams.anyEnemyCoresWithinBuildRadius(world, team, wx, wy)){
                return false;
            }
        }
        TileGrid tiles = world.tiles();
        if(!tiles.inBounds(x, y)) return false;
        if(!canPlaceOn(world, info, x, y, team, rotation)) return false;
        int pos = tiles.pos(x, y);
        // floors have different checks
        if(info.isFloor) return info.isOverlay ? tiles.overlay(pos) != info.id : tiles.floor(pos) != info.id;
        // campaign darkness
        if(!info.ignoreBuildDarkness && darkness(world, x, y) >= 3) return false;
        if(!info.requiresWater && !contactsShallows(world, x, y, info) && !info.placeableLiquid) return false;
        // limits for non-AI teams
        if(overPlacementLimit(world, info, team)) return false;
        BlockTable table = tiles.table();
        int offset = Footprint.offset(info.size);
        int water = world.content().id(ContentType.LIQUID, "water");
        for(int dx = 0; dx < info.size; dx++){
            for(int dy = 0; dy < info.size; dy++){
                int cx = dx + offset + x, cy = dy + offset + y;
                if(!tiles.inBounds(cx, cy)) return false;
                int check = tiles.pos(cx, cy);
                int floor = tiles.floor(check), block = tiles.block(check), checkTeam = tiles.team(check);
                Building build = world.buildings().at(check);
                boolean derelictSame = info.id == block && team != VanillaIds.DERELICT && checkTeam == VanillaIds.DERELICT;
                if((info.size == 2 && darkness(world, cx, cy) >= 3)
                    // Build.java:252
                    || (rules.staticFog && rules.fog && !FogControl.discoveredTile(world, team, cx, cy))
                    || (table.isDeep(floor) && !info.floating && !info.requiresWater && !info.placeableLiquid)
                    // deep water
                    || (!rules.derelictRepair && checkTeam == VanillaIds.DERELICT && build != null)
                    // same block, same rotation
                    || (info.id == block && build != null && rotation == build.rotation && info.rotate && !derelictSame)
                    || !Teams.canInteract(team, checkTeam)
                    // solid floor
                    || (!table.placeableOn(floor) && !info.ignoreBuildDarkness)
                    // a payload placement replaces only what is always replaced
                    || (!checkVisible && checkCoreRadius && !table.alwaysReplace(block))
                    || !(((canReplace(world, info, info(world, block)) || derelictSame)
                    // or the same block under construction here
                    || (build instanceof ConstructBuild site && site.current == info.id && build.tileX() == x && build.tileY() == y))
                    && contains(info, x, y, info(world, block), build == null ? cx : build.tileX(), build == null ? cy : build.tileY()))
                    // requires water but none found
                    || (info.requiresWater && table.liquidDrop(floor) != water)){
                    return false;
                }
            }
        }
        return !(rules.placeRangeCheck && checkCoreRadius && !rules.editor && enemyOverlap(world, info, team, x, y) != null);
    }

    /**
     * {@code type.bounds(x, y).grow(0.01f).contains(other.bounds(ox, oy))}: Arc's strict containment, in floats, as
     * vanilla computes it.
     */
    private static boolean contains(PlaceInfo type, int x, int y, PlaceInfo other, int ox, int oy){
        float w = type.size * Footprint.TILE_SIZE;
        float rx = x * Footprint.TILE_SIZE + type.offset - w / 2f, ry = y * Footprint.TILE_SIZE + type.offset - w / 2f;
        rx -= 0.01f / 2f;
        ry -= 0.01f / 2f;
        float rw = w + 0.01f;
        float ow = other.size * Footprint.TILE_SIZE;
        float xmin = ox * Footprint.TILE_SIZE + other.offset - ow / 2f, ymin = oy * Footprint.TILE_SIZE + other.offset - ow / 2f;
        float xmax = xmin + ow, ymax = ymin + ow;
        return (xmin > rx && xmin < rx + rw) && (xmax > rx && xmax < rx + rw) && (ymin > ry && ymin < ry + rw) && (ymax > ry && ymax < ry + rw);
    }

    /** {@code getEnemyOverlap(block, team, x, y)}: vanilla adds the block's size, not its offset, to the position. */
    public static Building enemyOverlap(World world, PlaceInfo block, int team, int x, int y){
        Rules rules = UnitCalls.rules(world);
        return BuildingTargets.of(world).findEnemyTile(team, x * Footprint.TILE_SIZE + block.size, y * Footprint.TILE_SIZE + block.size,
            block.placeOverlapRange + 4f, b -> rules.teams.get(b.team).checkPlacement, -1);
    }

    /** {@code contactsGround(x, y, block)}: an edge tile of the footprint is on solid ground. */
    public static boolean contactsGround(World world, int x, int y, PlaceInfo block){
        TileGrid tiles = world.tiles();
        if(block.multiblock()){
            for(int i = 0; i < Footprint.edgeCount(block.size); i++){
                int ex = x + Footprint.edgeX(block.size, i), ey = y + Footprint.edgeY(block.size, i);
                if(tiles.inBounds(ex, ey) && !tiles.table().isLiquid(tiles.floor(tiles.pos(ex, ey)))) return true;
            }
        }else{
            for(int d = 0; d < 4; d++){
                int ex = x + com.xpdustry.shard.common.geom.Geometry.d4x(d), ey = y + com.xpdustry.shard.common.geom.Geometry.d4y(d);
                if(tiles.inBounds(ex, ey) && !tiles.table().isLiquid(tiles.floor(tiles.pos(ex, ey)))) return true;
            }
        }
        return false;
    }

    /** {@code contactsShallows(x, y, block)}: some tile inside or around the footprint isn't deep water. */
    public static boolean contactsShallows(World world, int x, int y, PlaceInfo block){
        TileGrid tiles = world.tiles();
        BlockTable table = tiles.table();
        if(block.multiblock()){
            for(int i = 0; i < Footprint.edgeCount(block.size); i++){
                int ex = x + Footprint.insideEdgeX(block.size, i), ey = y + Footprint.insideEdgeY(block.size, i);
                if(tiles.inBounds(ex, ey) && !table.isDeep(tiles.floor(tiles.pos(ex, ey)))) return true;
            }
            for(int i = 0; i < Footprint.edgeCount(block.size); i++){
                int ex = x + Footprint.edgeX(block.size, i), ey = y + Footprint.edgeY(block.size, i);
                if(tiles.inBounds(ex, ey) && !table.isDeep(tiles.floor(tiles.pos(ex, ey)))) return true;
            }
            return false;
        }
        for(int d = 0; d < 4; d++){
            int ex = x + com.xpdustry.shard.common.geom.Geometry.d4x(d), ey = y + com.xpdustry.shard.common.geom.Geometry.d4y(d);
            if(tiles.inBounds(ex, ey) && !table.isDeep(tiles.floor(tiles.pos(ex, ey)))) return true;
        }
        return tiles.inBounds(x, y) && !table.isDeep(tiles.floor(tiles.pos(x, y)));
    }

    /** {@code validBreak(team, x, y)}: something breakable the team may touch stands there. */
    public static boolean validBreak(World world, int team, int x, int y){
        TileGrid tiles = world.tiles();
        if(!tiles.inBounds(x, y)) return false;
        int pos = tiles.pos(x, y);
        int block = tiles.block(pos);
        if(block == 0) return false;
        Rules rules = UnitCalls.rules(world);
        return canBreak(world, info(world, block), pos) && (tiles.breakable(pos) || rules.allowEnvironmentDeconstruct)
            && Teams.canInteract(team, tiles.team(pos));
    }

    /** {@code block.canBreak(tile)}: a core keeps the last one; privileged logic blocks only where accessible. */
    static boolean canBreak(World world, PlaceInfo block, int pos){
        Rules rules = UnitCalls.rules(world);
        return switch(block.canBreak){
            case ANY -> true;
            case CORE -> {
                Teams teams = Teams.of(world);
                // always keep at least one core, not to lose the save
                yield rules.editor || (rules.coreBuildAndConfig && teams != null && teams.cores(world.tiles().team(pos)).size() > 1);
            }
            case LOGIC -> !block.privileged || rules.editor || rules.allowEditWorldProcessors;
        };
    }

    /**
     * {@code block.canReplace(other)} (Block.java:795, with the overrides of {@code CoreBlock}, {@code Conveyor},
     * {@code StaticWall}, {@code RemoveOre} and {@code RemoveWall}): props always; privileged blocks never; otherwise the
     * same group (or block, which must rotate), the same size or a bigger block of the same class or of an
     * any-replace group.
     */
    public static boolean canReplace(World world, PlaceInfo type, PlaceInfo other){
        switch(type.canPlaceOn){
            case REMOVE_ORE -> {
                return true;
            }
            case REMOVE_WALL -> {
                return other.id != 0 && !other.synthetic;
            }
            default -> {
            }
        }
        if(type.block.baked() && type.block.isA(KindKey.of("static-wall")) && other.block.baked() && other.block.isA(KindKey.of("static-wall"))) return true;
        boolean replace = baseCanReplace(type, other);
        if(type.core) return replace || (other.core && type.size >= other.size && other.id != type.id);
        // stack conveyors should be bridged over, not replaced
        if(type.block.baked() && type.block.isA(KindKey.of("conveyor"))) return replace && !(other.block.baked() && other.block.isA(KindKey.of("stack-conveyor")));
        return replace;
    }

    private static boolean baseCanReplace(PlaceInfo type, PlaceInfo other){
        if(other.alwaysReplace) return true;
        if(other.privileged) return false;
        return other.replaceable && (other.id != type.id || (type.rotate && type.quickRotate))
            && ((type.group != null && type.group.equals(other.group)) || other.id == type.id)
            && (type.size == other.size || (type.size >= other.size && (type.block.kind().equals(other.block.kind()) || type.anyReplace)));
    }

    /** {@code block.canPlaceOn(tile, team, rotation)}: each class's own rule; most blocks may go anywhere. */
    public static boolean canPlaceOn(World world, PlaceInfo block, int x, int y, int team, int rotation){
        TileGrid tiles = world.tiles();
        BlockTable table = tiles.table();
        int pos = tiles.pos(x, y);
        int offset = Footprint.offset(block.size);
        switch(block.canPlaceOn){
            case ANY -> {
                return true;
            }
            case UNIT_ASSEMBLER -> {
                return com.xpdustry.shard.sim.blocks.units.UnitAssembler.canPlaceOn(world, block.block, x, y, team, rotation);
            }
            case UNIT_ASSEMBLER_MODULE -> {
                return com.xpdustry.shard.sim.blocks.units.UnitAssemblerModule.canPlaceOn(world, block.block, x, y, team, rotation);
            }
            case DRILL -> {
                if(!block.multiblock()) return canMine(world, block, pos);
                for(int dx = 0; dx < block.size; dx++){
                    for(int dy = 0; dy < block.size; dy++){
                        int ox = x + dx + offset, oy = y + dy + offset;
                        if(tiles.inBounds(ox, oy) && canMine(world, block, tiles.pos(ox, oy))) return true;
                    }
                }
                return false;
            }
            case BEAM_DRILL -> {
                for(int i = 0; i < block.size; i++){
                    int sx = side(block, x, rotation, i, true), sy = side(block, y, rotation, i, false);
                    for(int j = 0; j < block.range; j++){
                        int ox = sx + com.xpdustry.shard.common.geom.Geometry.d4x(rotation) * j, oy = sy + com.xpdustry.shard.common.geom.Geometry.d4y(rotation) * j;
                        if(!tiles.inBounds(ox, oy)) continue;
                        int other = tiles.pos(ox, oy);
                        if(tiles.solid(other)){
                            int drop = tiles.wallDrop(other);
                            if(drop != BlockTable.NONE && world.content().item(drop).hardness() <= block.tier && !blocked(block, drop)) return true;
                            break;
                        }
                    }
                }
                return false;
            }
            case PUMP -> {
                if(!block.multiblock()) return table.liquidDrop(tiles.floor(pos)) != BlockTable.NONE;
                int last = BlockTable.NONE;
                for(int dx = 0; dx < block.size; dx++){
                    for(int dy = 0; dy < block.size; dy++){
                        int ox = x + dx + offset, oy = y + dy + offset;
                        if(!tiles.inBounds(ox, oy)) continue;
                        int drop = table.liquidDrop(tiles.floor(tiles.pos(ox, oy)));
                        if(drop == BlockTable.NONE) continue;
                        if(drop != last && last != BlockTable.NONE) return false;
                        last = drop;
                    }
                }
                return last != BlockTable.NONE;
            }
            case SOLID_PUMP -> {
                float sum = 0f;
                for(int dx = 0; dx < block.size; dx++){
                    for(int dy = 0; dy < block.size; dy++){
                        int ox = x + dx + offset, oy = y + dy + offset;
                        if(!tiles.inBounds(ox, oy)) continue;
                        int other = tiles.pos(ox, oy);
                        sum += !table.isLiquid(tiles.floor(other)) ? block.baseEfficiency + (block.attribute != -1 ? tiles.attribute(other, block.attribute) : 0f) : 0f;
                    }
                }
                return sum > 0.00001f;
            }
            case THERMAL_GENERATOR -> {
                return floorAttributes(tiles, block, x, y) > block.minEfficiency;
            }
            case ATTRIBUTE_CRAFTER -> {
                return block.baseEfficiency + floorAttributes(tiles, block, x, y) >= block.minEfficiency;
            }
            case WALL_CRAFTER -> {
                float eff = 0f;
                for(int i = 0; i < block.size; i++){
                    int rx = side(block, x, rotation, i, true), ry = side(block, y, rotation, i, false);
                    if(!tiles.inBounds(rx, ry)) continue;
                    int other = tiles.pos(rx, ry);
                    if(tiles.solid(other)) eff += table.attribute(tiles.block(other), block.attribute);
                }
                return eff > 0;
            }
            case OVERLAY_FLOOR -> {
                return !block.wallOre || table.solid(tiles.block(pos));
            }
            case REMOVE_ORE -> {
                return tiles.overlay(pos) != 0;
            }
            case REMOVE_WALL -> {
                return tiles.block(pos) != 0;
            }
            case CORE -> {
                Rules rules = UnitCalls.rules(world);
                // in the editor or with the rule, anywhere for convenience
                if(rules.editor || rules.coreBuildAndConfig) return true;
                Teams teams = Teams.of(world);
                Core core = teams == null ? null : teams.core(team);
                // a special floor upon which cores can be placed
                boolean allZone = true, onCore = false;
                for(int dx = 0; dx < block.size; dx++){
                    for(int dy = 0; dy < block.size; dy++){
                        int ox = x + dx + offset, oy = y + dy + offset;
                        if(!tiles.inBounds(ox, oy)) continue;
                        int other = tiles.pos(ox, oy);
                        PlaceInfo standing = info(world, tiles.block(other));
                        if(!info(world, tiles.floor(other)).allowCorePlacement || standing.core) allZone = false;
                    }
                }
                if(allZone) return true;
                // must have all requirements
                if(core == null || (!rules.infiniteResources && !hasAll(core.items, block, rules.buildCostMultiplier))) return false;
                PlaceInfo current = info(world, tiles.block(pos));
                if(!current.core || block.size <= current.size) return false;
                if(!block.requiresCoreZone) return true;
                for(int dx = 0; dx < block.size; dx++){
                    for(int dy = 0; dy < block.size; dy++){
                        int ox = x + dx + offset, oy = y + dy + offset;
                        if(tiles.inBounds(ox, oy) && !info(world, tiles.floor(tiles.pos(ox, oy))).allowCorePlacement) return false;
                    }
                }
                onCore = true;
                return onCore;
            }
            case UNIT_CARGO_LOADER -> {
                return Units.canCreate(world, team, block.unitType);
            }
        }
        return true;
    }

    /** {@code Drill.canMine(tile)}: not under a static wall, with an ore the drill's tier may mine. */
    private static boolean canMine(World world, PlaceInfo drill, int pos){
        TileGrid tiles = world.tiles();
        if(tiles.isStatic(pos)) return false;
        int drop = tiles.drop(pos);
        return drop != BlockTable.NONE && world.content().item(drop).hardness() <= drill.tier && !blocked(drill, drop);
    }

    private static boolean blocked(PlaceInfo drill, int item){
        if(drill.blockedItems == null) return false;
        for(int blocked : drill.blockedItems) if(blocked == item) return true;
        return false;
    }

    /** {@code nearbySide(x, y, rotation, index)}'s x (or y): the tile beside the footprint's face. */
    private static int side(PlaceInfo block, int coordinate, int rotation, int index, boolean xAxis){
        int corner = coordinate - (block.size - 1) / 2, s = block.size;
        return switch(rotation){
            case 0 -> xAxis ? corner + s : corner + index;
            case 1 -> xAxis ? corner + index : corner + s;
            case 2 -> xAxis ? corner - 1 : corner + index;
            case 3 -> xAxis ? corner + index : corner - 1;
            default -> coordinate;
        };
    }

    /** The floors' attribute over the footprint, deep floors included ({@code getLinkedTilesAs(...).sumf(...)}). */
    private static float floorAttributes(TileGrid tiles, PlaceInfo block, int x, int y){
        int offset = Footprint.offset(block.size);
        float sum = 0f;
        for(int dx = 0; dx < block.size; dx++){
            for(int dy = 0; dy < block.size; dy++){
                int ox = x + dx + offset, oy = y + dy + offset;
                if(tiles.inBounds(ox, oy)) sum += tiles.attribute(tiles.pos(ox, oy), block.attribute);
            }
        }
        return sum;
    }

    /** {@code core.items.has(requirements, multiplier)}: each requirement, scaled and rounded. */
    static boolean hasAll(ItemModule items, PlaceInfo block, float multiplier){
        for(int i = 0; i < block.items.length; i++){
            if(!items.has(block.items[i], Math.round(block.amounts[i] * multiplier))) return false;
        }
        return true;
    }

    /** {@code core.items.has(requirements)}: each requirement in full. */
    static boolean hasAll(ItemModule items, PlaceInfo block){
        for(int i = 0; i < block.items.length; i++){
            if(!items.has(block.items[i], block.amounts[i])) return false;
        }
        return true;
    }

    /** {@code block.isOverPlacementLimit(team)}: the rules' limit for the block, for teams not run by AI. */
    public static boolean overPlacementLimit(World world, PlaceInfo block, int team){
        Rules rules = UnitCalls.rules(world);
        if(rules.editor || UnitCalls.isAI(world, team)) return false;
        int limit = rules.blockLimits.getOrDefault(block.id, 0);
        if(limit <= 0) return false;
        // team.data().getBuildings(block): the indexed buildings of the team and block
        TileGrid tiles = world.tiles();
        int[] count = {0};
        world.buildings().eachInChunks(team, 0, 0, tiles.width() - 1, tiles.height() - 1, building -> {
            if(building.block().id() == block.id) count[0]++;
        });
        return count[0] >= limit;
    }

    /** {@code block.isVisible()}: not hidden, and not banned while banned blocks hide. */
    public static boolean isVisible(World world, PlaceInfo block){
        Rules rules = UnitCalls.rules(world);
        return !isHidden(world, block) && (rules.editor || !rules.hideBannedBlocks || !rules.isBlockBanned(block.id));
    }

    /** {@code block.isPlaceable()}: visible, not banned, and in the rules' environment. */
    public static boolean isPlaceable(World world, PlaceInfo block){
        Rules rules = UnitCalls.rules(world);
        return isVisible(world, block) && (!rules.isBlockBanned(block.id) || rules.editor) && block.block.supportsEnv(rules.env);
    }

    /** {@code block.environmentBuildable()}: shown on the rules' planet. */
    public static boolean environmentBuildable(World world, PlaceInfo block){
        return block.onPlanet(planet(UnitCalls.rules(world)));
    }

    /** {@code state.getPlanet()}: the sector's planet in the campaign, else the rules'. */
    static int planet(Rules rules){
        if(rules.sector != null){
            int dash = rules.sector.lastIndexOf('-');
            String name = dash < 0 ? rules.sector : rules.sector.substring(0, dash);
            String[] planets = {"sun", "erekir", "gier", "notva", "tantros", "serpulo", "verilus"};
            for(int i = 0; i < planets.length; i++) if(planets[i].equals(name)) return i;
        }
        return rules.planet;
    }

    /** {@code block.isHidden()}: its build visibility says so and the rules don't reveal it. */
    static boolean isHidden(World world, PlaceInfo block){
        Rules rules = UnitCalls.rules(world);
        boolean campaign = UnitCalls.isCampaign(rules);
        // state.isGame(): a hosted world is always in a game
        boolean visible = switch(block.visibility){
            case HIDDEN, DEBUG_ONLY -> false;
            case SHOWN -> true;
            case EDITOR_ONLY -> rules.editor;
            case CORE_ZONE_ONLY -> Construction.of(world).coreZonePresent(world);
            case WORLD_PROCESSOR_ONLY -> rules.editor || rules.allowEditWorldProcessors;
            case SANDBOX_ONLY -> rules.infiniteResources;
            case CAMPAIGN_ONLY -> campaign;
            // the campaign's legacy launch pads aren't modelled
            case LEGACY_LAUNCH_PAD_ONLY -> false;
            case NOT_LEGACY_LAUNCH_PAD_ONLY -> rules.infiniteResources || campaign;
            case LIGHTING_ONLY -> rules.lighting || campaign || rules.infiniteResources;
            case FOG_ONLY -> rules.fog || rules.editor;
        };
        return !visible && !rules.revealedBlocks.contains(block.id);
    }

    /**
     * {@code world.getDarkness(x, y)} (World.java:484): the map edge's darkness, and a darkened tile's own. The
     * campaign's generated-sector border isn't modelled.
     */
    public static float darkness(World world, int x, int y){
        Rules rules = UnitCalls.rules(world);
        TileGrid tiles = world.tiles();
        float dark = 0;
        if(rules.borderDarkness){
            int edgeBlend = 2;
            int edgeDst;
            if(!rules.limitMapArea){
                edgeDst = Math.min(x, Math.min(y, Math.min(-(x - (tiles.width() - 1)), -(y - (tiles.height() - 1)))));
            }else{
                edgeDst = Math.min(x - rules.limitX, Math.min(y - rules.limitY,
                    Math.min(-(x - (rules.limitX + rules.limitWidth - 1)), -(y - (rules.limitY + rules.limitHeight - 1)))));
            }
            if(edgeDst <= edgeBlend) dark = Math.max((edgeBlend - edgeDst) * (4f / edgeBlend), dark);
        }
        if(tiles.inBounds(x, y)){
            int pos = tiles.pos(x, y);
            if(tiles.isDarkened(pos, rules.editor || rules.allowEditWorldProcessors)) dark = Math.max(dark, tiles.data(pos));
        }
        return dark;
    }

    // endregion

    // region calls

    /**
     * {@code Call.beginPlace(unit, result, team, x, y, rotation, placeConfig)} (Build.java:71): a valid placement
     * rotates a block of the same kind in place, repairs a derelict one, finishes an instant block at once, or puts a
     * construct site over the footprint, taking the team's buildings it covers as the site's {@code prevBuild}. Props
     * in the way are broken first. The rotation is taken as {@link #rotation} bounds it, and so is it sent.
     */
    public static void beginPlace(World world, Unit unit, BlockDef result, int team, int x, int y, int rotation, Object placeConfig){
        rotation = rotation(rotation);
        Object sent = begin(world, unit, result, team, x, y, rotation, placeConfig);
        world.publish(new BuildEvent.BeginPlace(world.ticks(), unit == null ? -1 : unit.id(), result.id(), team, x, y, rotation, sent));
    }

    /** The handler; returns the config the Call sends: an instant block's as it applied it, else {@code placeConfig}. */
    private static Object begin(World world, Unit unit, BlockDef result, int team, int x, int y, int rotation, Object placeConfig){
        if(!validPlace(world, result, team, x, y, rotation)) return placeConfig;
        TileGrid tiles = world.tiles();
        if(!tiles.inBounds(x, y)) return placeConfig;
        Buildings buildings = world.buildings();
        Cause cause = cause(team);
        int pos = tiles.pos(x, y);
        Building standing = buildings.at(pos);
        PlaceInfo info = info(world, result);
        int tileTeam = tiles.team(pos);
        // auto-rotate the block to the correct orientation and bail out
        if(tileTeam == team && tiles.block(pos) == result.id() && standing != null && info.quickRotate){
            buildings.rotate(standing, Mathf.mod(rotation, 4), cause);
            return placeConfig;
        }
        Rules rules = UnitCalls.rules(world);
        // repair a derelict tile
        if(tileTeam == VanillaIds.DERELICT && team != VanillaIds.DERELICT && tiles.block(pos) == result.id() && standing != null
            && info.allowDerelictRepair && rules.derelictRepair){
            standing.rotation = rotation;
            buildings.changeTeam(standing, team, cause);
            standing.enabled = true;
            if(standing.power != null){
                standing.power.links.clear();
                PowerGraph.powerGraphRemoved(standing);
            }
            standing.checkAllowUpdate();
            buildings.updateProximity(standing, cause);
            // onRepaired(): placed(), then a fog radius block refreshes its team's fog (BuildingComp.java:1377)
            standing.placed();
            if(standing.info().hasFlag(com.xpdustry.shard.sim.building.BlockFlag.hasFogRadius)) FogControl.forceUpdate(standing);
            // repair counts as a tile change
            buildings.countTileChange();
            RebuildPlans.buildEnded(world, team, pos);
            return placeConfig;
        }
        // break all props in the way
        int offset = Footprint.offset(info.size);
        for(int dx = 0; dx < info.size; dx++){
            for(int dy = 0; dy < info.size; dy++){
                int ox = x + dx + offset, oy = y + dy + offset;
                if(!tiles.inBounds(ox, oy)) continue;
                int block = tiles.block(tiles.pos(ox, oy));
                if(block != 0 && tiles.table().alwaysReplace(block)) buildings.setBlock(ox, oy, world.content().block(0), VanillaIds.DERELICT, 0, cause);
            }
        }
        // complete it immediately
        if(info.instantBuild){
            placeBegan(world, info, x, y, tiles.block(pos), unit);
            return constructFinish(world, pos, result, unit, rotation, team, placeConfig, false);
        }
        int previous = tiles.block(pos);
        PlaceInfo sub = info(world, construct(world, info.size));
        var prevBuild = new ArrayList<Building>(9);
        beforePlaceBegan(world, info, pos);
        for(int dx = 0; dx < info.size; dx++){
            for(int dy = 0; dy < info.size; dy++){
                int ox = x + dx + offset, oy = y + dy + offset;
                if(!tiles.inBounds(ox, oy)) continue;
                Building other = buildings.at(tiles.pos(ox, oy));
                if(other != null && other.team == team && !prevBuild.contains(other)) prevBuild.add(other);
            }
        }
        var site = (ConstructBuild)buildings.setBlock(x, y, sub.block, team, rotation, cause);
        site.setConstruct(info(world, previous).size == sub.size ? previous : 0, result.id());
        site.prevBuild = prevBuild;
        placeBegan(world, info, x, y, previous, unit);
        return placeConfig;
    }

    /**
     * {@code Call.beginBreak(unit, team, x, y)} (Build.java:25): a breakable block becomes a construct site at full
     * progress and the old building's health, or is gone at once if it deconstructs instantly.
     */
    public static void beginBreak(World world, Unit unit, int team, int x, int y){
        beginBreakLocal(world, unit, team, x, y);
        world.publish(new BuildEvent.BeginBreak(world.ticks(), unit == null ? -1 : unit.id(), team, x, y));
    }

    private static void beginBreakLocal(World world, Unit unit, int team, int x, int y){
        if(!validBreak(world, team, x, y)) return;
        TileGrid tiles = world.tiles();
        Buildings buildings = world.buildings();
        Cause cause = cause(team);
        Building standing = buildings.at(x, y);
        // world.tileBuilding(x, y): the building's origin
        int ox = standing == null ? x : standing.tileX(), oy = standing == null ? y : standing.tileY();
        int pos = tiles.pos(ox, oy);
        float prevPercent = standing == null ? 1f : standing.healthf();
        int rotation = standing == null ? 0 : standing.rotation;
        int previous = tiles.block(pos);
        PlaceInfo info = info(world, previous);
        // instantly deconstruct if necessary
        if(info.instantDeconstruct){
            deconstructFinish(world, pos, info.block, unit, false);
            return;
        }
        PlaceInfo sub = info(world, construct(world, info.size));
        List<Building> prevBuild = new ArrayList<>(1);
        if(standing != null){
            prevBuild.add(standing);
            onDeconstructed(world, standing);
            standing.dead = true;
        }
        var site = (ConstructBuild)buildings.setBlock(ox, oy, sub.block, team, rotation, cause);
        site.setDeconstruct(previous);
        site.prevBuild = prevBuild;
        site.health = site.maxHealth * prevPercent;
    }

    /**
     * {@code ConstructBlock.constructed(tile, block, builder, rotation, team, config)}: {@code Call.constructFinish},
     * then {@code placed()} on the new building, which autolinks power nodes ({@link Building#placed()}).
     */
    public static void constructed(World world, int pos, BlockDef block, Unit builder, int rotation, int team, Object config){
        constructFinish(world, pos, block, builder, rotation, team, config, true);
        Building building = world.buildings().at(pos);
        if(building != null) building.placed();
    }

    /** {@code Call.constructFinish(tile, block, builder, rotation, team, config)} (ConstructBlock.java:72). */
    public static void constructFinish(World world, int pos, BlockDef block, Unit builder, int rotation, int team, Object config){
        constructFinish(world, pos, block, builder, rotation, team, config, true);
    }

    /**
     * The handler: the block replaces the site at its health fraction, takes the config and what the replaced
     * buildings held ({@code overwrote}); then the place sound's pitch, the block's {@code placeEnded}, and the rebuild
     * plans it covers are dropped. Published unless an instant build runs it directly, as vanilla does without a Call.
     *
     * <p>Shard's bound (review 40): the config goes through the building's {@link Building#admitConfig} first, as a
     * client's direct config does, so a processor's code is charged to the tick's budget however many sites finish in
     * it. A config the building refuses isn't applied, and the rest of the handler and the Call carry null in its
     * place, so clients apply what the server did. Vanilla applies and sends any config.
     *
     * @return the config as applied: {@code config}, or null if the building refused it
     */
    static Object constructFinish(World world, int pos, BlockDef block, Unit builder, int rotation, int team, Object config, boolean call){
        rotation = rotation(rotation);
        TileGrid tiles = world.tiles();
        Buildings buildings = world.buildings();
        Cause cause = cause(team);
        int x = tiles.x(pos), y = tiles.y(pos);
        Building before = buildings.at(pos);
        float healthf = before == null ? 1f : before.healthf();
        List<Building> prev = before instanceof ConstructBuild site ? site.prevBuild : null;
        PlaceInfo info = info(world, block);
        BlockTable table = tiles.table();
        if(info.isOverlay){
            buildings.setOverlay(x, y, block, cause);
        }else if(info.isFloor){
            buildings.setFloor(x, y, block, cause);
            if(!table.isOverlay(tiles.overlay(pos)) && !info.supportsOverlay) buildings.setOverlay(x, y, world.content().block(0), cause);
        }else{
            buildings.setBlock(x, y, block, team, rotation, cause);
        }
        Building building = buildings.at(pos);
        if(building != null && building.block() == block){
            building.health = block.health() * healthf;
            if(config != null){
                if(building.admitConfig(config) == null) building.configured(builder, config);
                else config = null;
            }
            if(prev != null && !prev.isEmpty()) building.overwrote(prev);
            // make sure the block indexer knows it's damaged
            buildings.notifyHealthChanged(building);
        }
        // ConstructBlock.java:113: the place effect and sound only where the team sees
        if(FogControl.visibleTile(world, team, x, y)) Construction.of(world).placeSound(world, info);
        placeEnded(world, info, pos, builder, rotation, config);
        RebuildPlans.buildEnded(world, team, pos);
        // BlockBuildEndEvent (ConstructBlock.java:120): the game stats objectives read
        ObjectiveState.buildEnded(world, tiles.block(pos), team, builder == null ? -1 : builder.team);
        if(call){
            world.publish(new BuildEvent.ConstructFinish(world.ticks(), x, y, block.id(), builder == null ? -1 : builder.id(), (byte)rotation, team, config));
        }
        return config;
    }

    /**
     * Shard's bound on a config a plan carries for {@code block}, before any building of it stands: null to keep it,
     * or why the building would refuse it ({@link Building#admitConfig}). Only a processor bounds its configs: code and
     * links ({@code byte[]}) it would read must expand within {@code LogicPart}'s bounds and this tick's budget, which
     * checking charges.
     */
    public static String admitConfig(World world, BlockDef block, Object config){
        if(!(config instanceof byte[] data) || !block.baked() || !block.isA(KindKey.of("logic-block"))) return null;
        // LogicBlock.accessible(): a world processor reads configs only in the editor or when the rules allow it
        Rules rules = UnitCalls.rules(world);
        if(block.privileged() && !rules.editor && !rules.allowEditWorldProcessors) return null;
        return LogicPart.require(world).admitConfig(data);
    }

    /** {@code Call.deconstructFinish(tile, block, builder)} (ConstructBlock.java:59): the tile becomes air. */
    public static void deconstructFinish(World world, int pos, BlockDef block, Unit builder){
        deconstructFinish(world, pos, block, builder, true);
    }

    static void deconstructFinish(World world, int pos, BlockDef block, Unit builder, boolean call){
        TileGrid tiles = world.tiles();
        int x = tiles.x(pos), y = tiles.y(pos);
        // BlockBuildEndEvent, breaking (ConstructBlock.java:67)
        ObjectiveState.breakEnded(world, tiles.team(pos), builder == null ? -1 : builder.team);
        // headless: no effects; the tile's team doesn't matter to anything a server keeps
        world.buildings().setBlock(x, y, world.content().block(0), VanillaIds.DERELICT, 0, cause(tiles.team(pos)));
        if(call) world.publish(new BuildEvent.DeconstructFinish(world.ticks(), x, y, block.id(), builder == null ? -1 : builder.id()));
    }

    /**
     * {@code onDeconstructed(builder)}: a building that holds a liquid it can't burn (or must drop all of) spills it
     * over its tiles as puddles.
     */
    static void onDeconstructed(World world, Building building){
        if(building.liquids == null || building.liquids.currentAmount() <= 0) return;
        int liquid = building.liquids.current();
        boolean incinerable = world.content().liquid(liquid).fields().b("incinerable");
        boolean dropAll = building.block().baked() && building.block().fields().b("deconstructDropAllLiquid");
        if(incinerable && !dropAll) return;
        int size = building.block().size();
        float perCell = building.liquids.currentAmount() / (size * size) * 2f;
        TileGrid tiles = world.tiles();
        BuildingEffects effects = BuildingEffects.of(world);
        int offset = Footprint.offset(size);
        for(int dx = 0; dx < size; dx++){
            for(int dy = 0; dy < size; dy++){
                int ox = building.tileX() + dx + offset, oy = building.tileY() + dy + offset;
                if(!tiles.inBounds(ox, oy)) continue;
                int other = tiles.pos(ox, oy);
                effects.puddle(world, other, other, liquid, perCell, true, false);
            }
        }
    }

    /** {@code CoreBlock.beforePlaceBegan}: an upgraded core's items, minus the new core's cost, wait for it. */
    private static void beforePlaceBegan(World world, PlaceInfo result, int pos){
        if(!result.core) return;
        Construction construction = Construction.of(world);
        construction.nextItems = null;
        if(world.buildings().at(pos) instanceof Core core){
            ItemModule items = core.items.copy();
            Rules rules = UnitCalls.rules(world);
            if(!rules.infiniteResources){
                // ItemStack.mult(requirements, multiplier): each amount rounded
                for(int i = 0; i < result.items.length; i++) items.remove(result.items[i], Math.round(result.amounts[i] * rules.buildCostMultiplier));
            }
            construction.nextItems = items;
        }
    }

    /** {@code CoreBlock.placeBegan}: placing a core over a smaller one finishes at once and keeps its items. */
    private static void placeBegan(World world, PlaceInfo result, int x, int y, int previous, Unit builder){
        if(!result.core || !info(world, previous).core) return;
        TileGrid tiles = world.tiles();
        int pos = tiles.pos(x, y);
        int team = tiles.team(pos);
        world.buildings().setBlock(x, y, result.block, team, 0, cause(team));
        Construction construction = Construction.of(world);
        if(construction.nextItems != null){
            Teams teams = Teams.of(world);
            Core core = teams == null ? null : teams.core(team);
            // force-set the total items
            if(core != null) core.items.set(construction.nextItems);
            construction.nextItems = null;
        }
        RebuildPlans.buildEnded(world, team, pos);
        // BlockBuildEndEvent (CoreBlock.java:224)
        ObjectiveState.buildEnded(world, tiles.block(pos), team, builder == null ? -1 : builder.team);
    }

    /** {@code block.placeEnded(tile, builder, rotation, config)}: the environment blocks' and the kinds' that have one. */
    private static void placeEnded(World world, PlaceInfo block, int pos, Unit builder, int rotation, Object config){
        TileGrid tiles = world.tiles();
        Buildings buildings = world.buildings();
        int x = tiles.x(pos), y = tiles.y(pos);
        Cause cause = cause(tiles.team(pos));
        if(block.block.baked()){
            if(block.block.isA(KindKey.of("colored-wall")) || block.block.isA(KindKey.of("colored-floor"))){
                // config is assumed to be an integer RGBA color
                if(config instanceof Integer color) tiles.extraData(pos, color);
            }else if(block.block.isA(KindKey.of("remove-ore"))){
                buildings.setOverlay(x, y, world.content().block(0), cause);
            }else if(block.block.isA(KindKey.of("remove-wall"))){
                buildings.setBlock(x, y, world.content().block(0), VanillaIds.DERELICT, 0, cause);
                if(tiles.table().wallOre(tiles.overlay(pos))) buildings.setOverlay(x, y, world.content().block(0), cause);
            }else if(block.block.isA(KindKey.of("character-overlay"))){
                byte data = config instanceof Integer i ? i.byteValue() : 0;
                // CharOverlayData.get(character, rotation): six bits of character, two of rotation
                tiles.overlayData(pos, (byte)((data & 0x3f) | ((byte)rotation << 6 & 0xc0)));
            }else if(block.block.isA(KindKey.of("rune-overlay"))){
                if(config instanceof Integer i) tiles.overlayData(pos, i.byteValue());
            }
        }
        if(buildings.at(pos) instanceof PlaceEnded ended) ended.placeEnded(builder, rotation, config);
    }

    // endregion

    /**
     * Shard's bound on a placement's rotation (review 40): 0 to 3, as {@code Mathf.mod(rotation, 4)}. Stock clients
     * send nothing else, and the quick-rotate branch already takes it this way. Vanilla stores any other byte as it
     * came, and a negative one indexes past the arrays blocks keep by direction ({@code DuctBridge.occupied}).
     */
    public static int rotation(int rotation){
        return Mathf.mod(rotation, 4);
    }

    /** {@code ConstructBlock.get(size)}: the construct block of that size, {@code build<size>}. */
    public static int construct(World world, int size){
        int id = world.content().id(ContentType.BLOCK, "build" + size);
        if(id < 0) throw new IllegalArgumentException("no construct block of size " + size);
        return id;
    }

    /** The journal's cause for a team's construction. */
    static Cause cause(int team){
        return new Cause.Team(team);
    }

    static PlaceInfo info(World world, BlockDef block){
        return Construction.of(world).info(block.id());
    }

    static PlaceInfo info(World world, int block){
        return Construction.of(world).info(block);
    }
}
