package com.xpdustry.shard.sim.ai;

import com.xpdustry.shard.common.math.*;
import com.xpdustry.shard.sim.build.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.combat.*;
import com.xpdustry.shard.sim.entity.*;
import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.rules.*;
import com.xpdustry.shard.sim.spatial.*;
import com.xpdustry.shard.sim.team.*;
import com.xpdustry.shard.sim.tile.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.unit.BuildPlan;
import com.xpdustry.shard.sim.world.*;

import java.util.*;

/**
 * Vanilla's {@code BuilderAI} (BuilderAI.java), for poly and the core units of AI teams, and the {@code rebuild} and
 * {@code assist} commands: helps a unit of its team that is building a site it can reach (copying that unit's first plan
 * into its own queue each frame, the same plan object, as vanilla does), else rebuilds its team's lost blocks
 * ({@link TeamData#plans()}, one plan every {@code rebuildPeriod} frames, the front plan moving to the back), and flies
 * home from enemies when idle. Building goes through {@link Builder}; {@code onlyAssist} (the {@code assist} command)
 * only follows the closest building player.
 *
 * <p>Vanilla quirk: the reach check takes {@code Math.min(dst - buildRange, 0)}, which is never positive, so any unit
 * in {@code buildRadius} that is building a site is followed, however far it is (BuilderAI.java:178).
 *
 * <p>{@link #lastPlan} is one of the team's plans, by identity: vanilla drops a queued plan once that object is marked
 * removed. A fork maps it to the fork's plan at the same place in the team's list; a checkpoint stores that place.
 */
public class BuilderAi extends AiController{
    public static final String KIND = Controllers.BUILDER_AI;
    public static final float BUILD_RADIUS = 1500f, RETREAT_DST = 110f, RETREAT_DELAY = 60f * 2f, DEFAULT_REBUILD_PERIOD = 60f * 2f;

    /** {@code assistFollowing}: the player's unit an {@code onlyAssist} AI stays with, or null. */
    public Unit assistFollowing;
    /** {@code following}: the unit whose plan it builds, or null. */
    public Unit following;
    /** {@code enemy}: what it flees from, or null. */
    public Entity enemy;
    /** {@code lastPlan}: the team plan it queued last, or null. */
    public BlockPlan lastPlan;
    public float fleeRange = 370f, rebuildPeriod = DEFAULT_REBUILD_PERIOD;
    public boolean alwaysFlee, onlyAssist;
    public float retreatTimer;
    /**
     * {@link #lastPlan}'s place in the team's plans, as a checkpoint stores it: written from {@link #lastPlan}, and read
     * back until {@link #restored()} takes the plan there; -1 otherwise.
     */
    private int lastPlanLink = -1;
    /**
     * A copy's {@code unit.plans.first()}, when it was the plan object {@link #following} holds: its place in that unit's
     * queue, until {@link FollowerPlans} hands the follower that object again, once the copy's units are in place; -1
     * otherwise. A fork and a checkpoint copy each queue's plans on their own, and a follower's builder may work on its
     * plan before its AI takes the leader's again.
     */
    public int planLink = -1;

    public BuilderAi(World world){
        super(world, KIND);
    }

    /** {@code new BuilderAI(alwaysFlee, fleeRange)}. */
    public BuilderAi(World world, boolean alwaysFlee, float fleeRange){
        this(world);
        this.alwaysFlee = alwaysFlee;
        this.fleeRange = fleeRange;
    }

    /** A blank controller for a checkpoint to fill: no draws. */
    public BuilderAi(){
    }

    protected BuilderAi(BuilderAi source, CopyContext context){
        super(source, context);
        assistFollowing = context.map(source.assistFollowing);
        following = context.map(source.following);
        enemy = context.map(source.enemy);
        // the fork's parts are in place before its entities are filled: take its plan at the same place
        int link = source.lastPlanIndex();
        List<BlockPlan> plans = link == -1 ? null : teamPlans(context.world(), source.unit.team);
        lastPlan = plans != null && link < plans.size() ? plans.get(link) : detached(source.lastPlan);
        planLink = source.sharedPlanIndex();
        fleeRange = source.fleeRange;
        rebuildPeriod = source.rebuildPeriod;
        alwaysFlee = source.alwaysFlee;
        onlyAssist = source.onlyAssist;
        retreatTimer = source.retreatTimer;
    }

    @Override
    public void init(){
        // rebuild much faster with buildAi; there are usually few builder units so this is fine
        if(rebuildPeriod == DEFAULT_REBUILD_PERIOD && UnitCalls.rules(unit.world()).teams.get(unit.team).buildAi){
            rebuildPeriod = 10f;
        }
    }

    @Override
    public void updateMovement(){
        World world = unit.world();
        AiType type = type();

        if(target != null && shouldShoot()){
            unit.lookAt(Targets.x(target), Targets.y(target));
        }else if(!unit.params().flying()){
            unit.lookAt(prefRotation(unit));
        }

        unit.updateBuilding = true;

        if(assistFollowing != null && !assistFollowing.isValid()) assistFollowing = null;
        if(following != null && !following.isValid()) following = null;

        if(assistFollowing != null && Builder.activelyBuilding(assistFollowing)){
            following = assistFollowing;
        }

        boolean moving = false;
        boolean hold = hasStance(stance("holdposition"));

        if(following != null){
            retreatTimer = 0f;
            // try to follow and mimic someone

            // validate follower
            if(!following.isValid() || !Builder.activelyBuilding(following)){
                following = null;
                unit.plans.clear();
                return;
            }

            // set to the follower's first build plan, whatever that is: the same object
            unit.plans.clear();
            unit.plans.addFirst(Builder.buildPlan(following));
            lastPlan = null;
        }else if((Builder.buildPlan(unit) == null || alwaysFlee) && !hold){
            // not following anyone or building
            if(timerGet(TIMER_TARGET4, 40)){
                enemy = target(unit.x, unit.y, fleeRange, true, true);
            }

            // fly away from enemy when not doing anything, but only after a delay
            if((retreatTimer += 1f) >= RETREAT_DELAY || alwaysFlee){
                if(enemy != null){
                    Builder.clearBuilding(unit);
                    Building core = closestCore();
                    if(core != null && !unit.within(core.x(), core.y(), RETREAT_DST)){
                        moveTo(core.x(), core.y(), RETREAT_DST);
                        moving = true;
                    }
                }
            }
        }

        if(Builder.buildPlan(unit) != null){
            if(!alwaysFlee) retreatTimer = 0f;
            // approach the plan if building
            BuildPlan req = Builder.buildPlan(unit);

            // clear the break plan if another player is breaking something
            if(!req.breaking && timerGet(TIMER_TARGET2, 40f)){
                var players = Players.of(world).list();
                for(int i = 0; i < players.size(); i++){
                    Player player = players.get(i);
                    Unit other = player.unit();
                    if(other != null && Builder.canBuild(other) && Builder.activelyBuilding(other) && samePos(Builder.buildPlan(other), req)
                        && Builder.buildPlan(other).breaking){
                        unit.plans.removeFirst();
                        // remove from the list of plans
                        List<BlockPlan> plans = teamPlans(world, unit.team);
                        if(plans != null){
                            for(int p = 0; p < plans.size(); p++){
                                if(plans.get(p).x == req.x && plans.get(p).y == req.y){
                                    plans.remove(p);
                                    break;
                                }
                            }
                        }
                        return;
                    }
                }
            }

            TileGrid tiles = world.tiles();
            int pos = tiles.inBounds(req.x, req.y) ? tiles.pos(req.x, req.y) : -1;
            boolean valid = !(lastPlan != null && lastPlan.removed)
                && ((pos != -1 && world.buildings().at(pos) instanceof ConstructBuild cons && cons.current == req.block)
                || (req.breaking ? Build.validBreak(world, unit.team, req.x, req.y)
                : Build.validPlace(world, world.content().block(req.block), unit.team, req.x, req.y, req.rotation)));

            if(valid){
                float tx = tileX(world, req.x, req.y), ty = tileY(world, req.x, req.y);
                if(!hold){
                    float range = Math.min(type.buildRange - unit.hitSize * 2f, BUILD_RADIUS);
                    // move toward the plan
                    moveTo(tx, ty, range, 20f);
                    moving = !unit.within(tx, ty, range);
                }else if(!unit.within(Builder.planX(world, req), Builder.planY(world, req), type.buildRange - TILESIZE)
                    && !UnitCalls.rules(world).infiniteResources){
                    // discard the plan, it's too far away to reach while holding position; try the next one
                    unit.plans.removeFirst();
                    lastPlan = null;
                }
            }else{
                // discard invalid plan
                unit.plans.removeFirst();
                lastPlan = null;
            }
        }else{

            if(assistFollowing != null && !hold){
                float hitSize = assistFollowing.params().hitSize();
                moveTo(assistFollowing.x, assistFollowing.y, hitSize + unit.hitSize / 2f + 60f);
                moving = !unit.within(assistFollowing.x, assistFollowing.y, hitSize + unit.hitSize / 2f + 65f);
            }

            // follow someone and help them build
            if(timerGet(TIMER_TARGET2, 20f)){
                follow(world);

                if(onlyAssist){
                    float minDst = Float.MAX_VALUE;
                    Player closest = null;
                    var players = Players.of(world).list();
                    for(int i = 0; i < players.size(); i++){
                        Player player = players.get(i);
                        if(!player.dead() && player.unit() != null && Builder.canBuild(player.unit()) && player.team == unit.team){
                            float dst = unit.dst2(player.x, player.y);
                            if(dst < minDst){
                                closest = player;
                                minDst = dst;
                            }
                        }
                    }

                    assistFollowing = closest == null ? null : closest.unit();
                }
            }

            // find a new plan
            List<BlockPlan> blocks = teamPlans(world, unit.team);
            if(!onlyAssist && blocks != null && !blocks.isEmpty() && following == null && timerGet(TIMER_TARGET3, rebuildPeriod)){
                if(hold){
                    // essentially build turret behaviour (find the first plan in range)
                    for(int i = 0; i < blocks.size(); i++){
                        BlockPlan block = blocks.get(i);
                        if(UnitCalls.rules(world).infiniteResources || unit.within(block.x * TILESIZE, block.y * TILESIZE, type.buildRange)){
                            if(Build.validPlace(world, world.content().block(block.block), unit.team, block.x, block.y, block.rotation)){
                                Builder.addBuild(unit, plan(world, block), true);
                                // shift the build plan to the tail so the next unit builds something else
                                blocks.addLast(blocks.remove(i));
                                lastPlan = block;
                                break;
                            }
                        }
                    }
                }else{
                    BlockPlan block = blocks.getFirst();
                    TileGrid tiles = world.tiles();

                    // check if it's already been placed
                    if(tiles.inBounds(block.x, block.y) && tiles.block(tiles.pos(block.x, block.y)) == block.block){
                        blocks.removeFirst();
                    }else if(Build.validPlace(world, world.content().block(block.block), unit.team, block.x, block.y, block.rotation)
                        && (!alwaysFlee || !nearEnemy(block.x, block.y))){
                        lastPlan = block;
                        // add the build plan
                        Builder.addBuild(unit, plan(world, block), true);
                        // shift the build plan to the tail so the next unit builds something else
                        blocks.addLast(blocks.removeFirst());
                    }else{
                        // shift the head of the queue to the tail, try something else next time
                        blocks.addLast(blocks.removeFirst());
                    }
                }
            }
        }

        if(!unit.params().flying()){
            unit.updateBoosting(type.boostWhenBuilding || moving || MinerAi.dangerousFloor(unit));
        }
    }

    /**
     * {@code Units.nearby(team, x, y, buildRadius, …)}: the first unit of the team, other than this one, that is
     * actively building a site, becomes the one to follow.
     */
    private void follow(World world){
        SpatialIndex index = SpatialIndex.of(world);
        if(index == null) return;
        Unit self = unit;
        var found = new boolean[1];
        index.nearby(unit.team, unit.x, unit.y, BUILD_RADIUS, u -> {
            if(found[0]) return;

            if(Builder.canBuild(u) && u != self && Builder.activelyBuilding(u)){
                BuildPlan plan = Builder.buildPlan(u);

                Building build = world.buildings().at(plan.x, plan.y);
                if(build instanceof ConstructBuild cons){
                    // vanilla quirk: never positive
                    float dist = Math.min(Mathf.dst(cons.x(), cons.y(), self.x, self.y) - type().buildRange, 0);

                    // make sure you can reach the plan in time
                    if(dist / self.speed() < cons.buildCost * 0.9f){
                        following = u;
                        found[0] = true;
                    }
                }
            }
        });
    }

    /** {@code Units.nearEnemy(team, x * tilesize - fleeRange / 2, y * tilesize - fleeRange / 2, fleeRange, fleeRange)}. */
    protected boolean nearEnemy(int x, int y){
        SpatialIndex index = SpatialIndex.of(unit.world());
        return index != null && index.nearEnemy(unit.team, x * TILESIZE - fleeRange / 2f, y * TILESIZE - fleeRange / 2f, fleeRange, fleeRange,
            unit.world().buildings().targets());
    }

    @Override
    public AiController fallback(){
        World world = unit.world();
        if(UnitCalls.isAI(world, unit.team) && UnitCalls.rules(world).teams.get(unit.team).prebuildAi){
            return AiKinds.make(world, PrebuildAi.KIND);
        }
        return AiKinds.make(world, unit.params().flying() ? FLYING : GROUND);
    }

    @Override
    public boolean useFallback(){
        World world = unit.world();
        Rules rules = UnitCalls.rules(world);
        if(UnitCalls.isAI(world, unit.team) && rules.teams.get(unit.team).prebuildAi) return true;
        return rules.waves && unit.team == rules.waveTeam && !rules.teams.get(unit.team).rtsAi;
    }

    @Override
    public boolean shouldFire(){
        return !(unit.controller instanceof CommandAi ai) || ai.shouldFire();
    }

    @Override
    public boolean shouldShoot(){
        return !Builder.isBuilding(unit) && type().canAttack;
    }

    // helpers

    private int stance(String name){
        return unit.world().content().unitStances().indexOf(name);
    }

    private static boolean samePos(BuildPlan a, BuildPlan b){
        return a.x == b.x && a.y == b.y;
    }

    /** {@code new BuildPlan(block.x, block.y, block.rotation, block.block, block.config)}. */
    private static BuildPlan plan(World world, BlockPlan block){
        return BuildPlan.place(block.x, block.y, block.rotation, world.content().block(block.block), block.config);
    }

    /** {@code tile.drawx()}: the tile's position plus the offset of the block on it now. */
    private static float tileX(World world, int x, int y){
        TileGrid tiles = world.tiles();
        return x * TILESIZE + (tiles.inBounds(x, y) ? Footprint.worldOffset(tiles.table().size(tiles.block(tiles.pos(x, y)))) : 0f);
    }

    private static float tileY(World world, int x, int y){
        TileGrid tiles = world.tiles();
        return y * TILESIZE + (tiles.inBounds(x, y) ? Footprint.worldOffset(tiles.table().size(tiles.block(tiles.pos(x, y)))) : 0f);
    }

    private static List<BlockPlan> teamPlans(World world, int team){
        Teams teams = Teams.of(world);
        return teams == null ? null : teams.get(team).plans();
    }

    /** {@link #lastPlan}'s place in its unit's team plans, by identity, or -1. */
    private int lastPlanIndex(){
        if(lastPlan == null || unit == null) return -1;
        List<BlockPlan> plans = teamPlans(unit.world(), unit.team);
        if(plans == null) return -1;
        for(int i = 0; i < plans.size(); i++) if(plans.get(i) == lastPlan) return i;
        return -1;
    }

    /** {@link #planLink} as it stands: a pending link, else the first plan's place in the leader's queue, by identity. */
    private int sharedPlanIndex(){
        if(planLink != -1) return planLink;
        if(following == null || unit == null || unit.plans.isEmpty()) return -1;
        BuildPlan first = unit.plans.first();
        for(int i = 0; i < following.plans.size(); i++) if(following.plans.get(i) == first) return i;
        return -1;
    }

    /** {@link FollowerPlans}: the follower's first plan is the leader's object at {@link #planLink} again. */
    void relink(){
        if(planLink != -1 && following != null && unit != null && !unit.plans.isEmpty() && planLink < following.plans.size()){
            unit.plans.removeFirst();
            unit.plans.addFirst(following.plans.get(planLink));
        }
        planLink = -1;
    }

    /** A plan no list holds any more: only its {@code removed} flag is read again. */
    private static BlockPlan detached(BlockPlan plan){
        if(plan == null) return null;
        var out = new BlockPlan(plan.x, plan.y, plan.rotation, plan.block, plan.config);
        out.removed = plan.removed;
        return out;
    }

    // state

    @Override
    public UnitController copy(CopyContext context){
        return new BuilderAi(this, context);
    }

    @Override
    public void hash(Hasher hasher){
        super.hash(hasher);
        hasher.i(assistFollowing == null ? -1 : assistFollowing.id()).i(following == null ? -1 : following.id()).i(enemy == null ? -1 : enemy.id());
        hasher.i(lastPlanIndex()).bool(lastPlan != null && lastPlan.removed);
        hasher.f(fleeRange).f(rebuildPeriod).bool(alwaysFlee).bool(onlyAssist).f(retreatTimer);
    }

    @Override
    public void write(CheckpointOut out){
        super.write(out);
        int link = lastPlanIndex();
        out.ref("assistFollowing", assistFollowing).ref("following", following).ref("enemy", enemy)
            .i("lastPlanLink", link).obj("lastPlan", link == -1 ? lastPlan : null).i("planLink", sharedPlanIndex())
            .f("fleeRange", fleeRange).f("rebuildPeriod", rebuildPeriod).bool("alwaysFlee", alwaysFlee).bool("onlyAssist", onlyAssist)
            .f("retreatTimer", retreatTimer);
    }

    @Override
    public void read(CheckpointIn in){
        super.read(in);
        assistFollowing = in.ref("assistFollowing", Unit.class);
        following = in.ref("following", Unit.class);
        enemy = in.ref("enemy", Entity.class);
        lastPlanLink = in.i("lastPlanLink");
        lastPlan = in.obj("lastPlan", BlockPlan.class);
        planLink = in.i("planLink");
        fleeRange = in.f("fleeRange");
        rebuildPeriod = in.f("rebuildPeriod");
        alwaysFlee = in.bool("alwaysFlee");
        onlyAssist = in.bool("onlyAssist");
        retreatTimer = in.f("retreatTimer");
    }

    /** The team's plans are restored now: {@link #lastPlan} is the one at its stored place. */
    @Override
    public void restored(){
        if(lastPlanLink != -1 && unit != null){
            List<BlockPlan> plans = teamPlans(unit.world(), unit.team);
            if(plans != null && lastPlanLink < plans.size()) lastPlan = plans.get(lastPlanLink);
        }
        lastPlanLink = -1;
    }
}
