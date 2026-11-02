package com.xpdustry.shard.sim.ai;

import com.xpdustry.shard.common.geom.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.content.schema.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.combat.*;
import com.xpdustry.shard.sim.entity.*;
import com.xpdustry.shard.sim.rules.*;
import com.xpdustry.shard.sim.tile.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.world.*;

/**
 * Vanilla's {@code SuicideAI} (SuicideAI.java): the crawler's. It runs straight at a target it can see, and blows up
 * (its weapon kills its shooter) in range or against an enemy block in the way; otherwise it walks the flowfield. It
 * never walks at walls, liquid or transport blocks (cores excepted), and never targets conveyors or conduits.
 */
public class SuicideAi extends GroundAi{
    public static final String KIND = "suicide-ai";

    public SuicideAi(World world){
        super(world, KIND);
    }

    /** A blank controller for a checkpoint to fill: no draws. */
    public SuicideAi(){
    }

    protected SuicideAi(SuicideAi source, CopyContext context){
        super(source, context);
    }

    @Override
    public void updateUnit(Unit unit){
        this.unit = unit;
        World world = unit.world();
        AiType type = type();
        Rules rules = UnitCalls.rules(world);

        if(Targets.invalidateTarget(target, unit.team, unit.x, unit.y, Float.MAX_VALUE)) target = null;

        if(retarget()) target = target(unit.x, unit.y, type.range, type.targetAir, type.targetGround);

        Building core = closestEnemyCore();

        boolean rotate = false, shoot = false, moveToTarget = false;

        if(target == null) target = core;

        if(!Targets.invalidateTarget(target, unit.team, unit.x, unit.y, type.range) && type.hasWeapons){
            rotate = true;
            float bulletRange = Weapon.of(unit, unit.mounts[0]).bullet.range;
            shoot = Targets.within(target, unit.x, unit.y, bulletRange
                + (target instanceof Building b ? b.block().size() * TILESIZE / 2f : Targets.hitSize(target) / 2f));

            // do not move toward walls or transport blocks
            if(!(target instanceof Building build && !build.info().hasFlag(BlockFlag.core) && avoided(build.block()))){
                Entity aim = target;
                TileGrid tiles = world.tiles();
                boolean[] blockedByBlock = {false};

                // raycast for target
                boolean blocked = raycast(unit.tileX(), unit.tileY(), tileX(target), tileY(target), (x, y) -> {
                    // vanilla quirk: every branch returns in the first direction, so only the tile to the right is looked at
                    Point2 p = Geometry.d4c.getFirst();
                    int ox = x + p.x(), oy = y + p.y();
                    boolean in = tiles.inBounds(ox, oy);
                    Building build = in ? world.buildings().at(tiles.pos(ox, oy)) : null;
                    if(in && build == aim) return false;
                    if(in && build != null && build.team != unit.team){
                        blockedByBlock[0] = true;
                        return true;
                    }
                    return !in || tiles.solid(tiles.pos(ox, oy));
                });

                // shoot when there's an enemy block in the way
                if(blockedByBlock[0]) shoot = true;

                if(!blocked){
                    moveToTarget = true;
                    // move towards target directly
                    unit.movePref(new Vec2(Targets.x(target), Targets.y(target)).sub(unit.x, unit.y).limit(unit.speed()));
                }
            }
        }

        if(!moveToTarget){
            boolean move = true;

            // stop moving toward the drop zone if applicable
            if(core == null && rules.waves && unit.team == rules.defaultTeam){
                int spawner = closestSpawner();
                if(spawner != -1 && withinTile(spawner, rules.dropZoneRadius + 120f)) move = false;
            }

            if(move) pathfind();
        }

        Weapons.controlWeapons(unit, rotate, shoot);

        faceTarget();
    }

    /** Walls, liquid and transport blocks, by their {@code group}. */
    static boolean avoided(BlockDef block){
        if(!block.baked()) return false;
        String group = block.group();
        return "walls".equals(group) || "liquids".equals(group) || "transportation".equals(group);
    }

    @Override
    public Entity target(float x, float y, float range, boolean air, boolean ground){
        // do not target conveyors or conduits
        return Targets.closestTarget(unit.world(), unit.team, x, y, range, u -> Targets.checkTarget(u, air, ground),
            t -> ground && !(isKind(t.block(), "conveyor") || isKind(t.block(), "conduit")));
    }

    /** Whether the block's class is vanilla's {@code kind} class or a subclass of it. */
    static boolean isKind(BlockDef block, String kind){
        if(!block.baked()) return false;
        for(KindSchema at = block.fields().kind(); at != null; at = at.parent()){
            if(at.key().equals(kind)) return true;
        }
        return false;
    }

    @Override
    public UnitController copy(CopyContext context){
        return new SuicideAi(this, context);
    }
}
