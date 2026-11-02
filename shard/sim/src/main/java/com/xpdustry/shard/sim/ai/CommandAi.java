package com.xpdustry.shard.sim.ai;

import com.xpdustry.shard.common.geom.*;
import com.xpdustry.shard.common.math.*;
import com.xpdustry.shard.common.struct.*;
import com.xpdustry.shard.sim.ai.command.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.combat.*;
import com.xpdustry.shard.sim.entity.*;
import com.xpdustry.shard.sim.path.*;
import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.spatial.*;
import com.xpdustry.shard.sim.team.*;
import com.xpdustry.shard.sim.tile.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.world.*;

import java.util.*;

/**
 * Vanilla's {@code CommandAI} (CommandAI.java), the controller of RTS-commanded units: the command
 * ({@link UnitCommand}), the stances ({@link UnitStances}), the move target, the attack target, the command queue and
 * the formation group ({@link UnitGroup}), and the behaviour of the commands without an AI of their own:
 * <ul>
 *   <li>{@code move}: walk or fly to {@link #targetPos} or {@link #attackTarget}, offset by the unit's place in its
 *   group; ground units ask {@link ControlPathfinder#pathPosition} for each leg, give way to a unit closer to the same
 *   point near an obstacle, and an AI team's unit gives up on an unreachable target;</li>
 *   <li>{@code enterPayload}: walk onto a payload block and enter it ({@code unitEnteredPayload});</li>
 *   <li>{@code loadUnits}, {@code loadBlocks}, {@code unloadPayload}: pick up the units or the block below, or drop
 *   everything, while moving as {@code move} does;</li>
 *   <li>{@code loopPayload}: shuttle between two points, loading at one and unloading at the other.</li>
 * </ul>
 * The other commands run their own AI ({@link UnitCommand#controller}). Its state is synced and saved by
 * {@code TypeIO.writeController} (the command, the stances, the move target, the attack target, the queue); the group
 * isn't, so a loaded unit has none, as in vanilla. It keeps its state when a unit is read back ({@code keepState}).
 *
 * <p>Owner: WP72 (WP54 before). The unit core makes it ({@code Controllers}) and binds it.
 */
public final class CommandAi extends AiController{
    /** {@code maxCommandQueueSize}. */
    public static final int MAX_QUEUE = 50;
    /** {@code avoidInterval}: how often a ground unit checks for a unit blocking its way. */
    static final float AVOID_INTERVAL = 10;
    static final int TRANSFER_NONE = 0, TRANSFER_LOAD = 1, TRANSFER_UNLOAD = 2;

    /** {@code command}: the command's content id, or -1 before {@link #bound} picks the default. */
    public int command = -1;
    /** {@code stances}: one bit per stance id. */
    public Bits stances = new Bits();
    /** {@code targetPos}, or null. */
    public Vec2 targetPos;
    /** {@code targetBuild}: the building under {@link #targetPos} when the unit last moved, or null. */
    public Building targetBuild;
    /** {@code attackTarget}: a unit or a building, or null. Check it is still valid before use. */
    public Entity attackTarget;
    /** {@code commandQueue}: positions and targets, in order. */
    public QueuedTarget[] queue = {};
    /** {@code group}: the units ordered to the same point with this one, or null. */
    public UnitGroup group;
    /** {@code groupIndex}: this unit's place in {@link #group}. */
    public int groupIndex;
    /** {@code unreachableBuildings}: the positions of buildings this unit found it can't reach, each once. */
    public IntSeq unreachableBuildings = new IntSeq(8);

    /** {@code stopAtTarget}: stop chasing the attack target once in range (counter-attacks, defence). */
    public boolean stopAtTarget;
    /** {@code stopWhenInRange}: finish a position order once within 90% of the engage range. */
    public boolean stopWhenInRange;
    /** {@code blockingUnit}: a unit ahead that is closer to the same target blocks this one. */
    public boolean blockingUnit;
    /** {@code timeSpentBlocked}. */
    public float timeSpentBlocked;
    /** {@code payloadPickupCooldown}. */
    public float payloadPickupCooldown;
    /** {@code transferState}: what a {@code loopPayload} unit does at the end of a leg. */
    public int transferState = TRANSFER_NONE;
    /** {@code commandController}: the AI of a command that has one, made when the command changes. */
    public AiController commandController;
    /** {@code lastCommand}: the command {@link #commandController} was made for, or -1. */
    public int lastCommand = -1;
    /**
     * {@code readAttackTarget}: a loaded unit attack target's id, until {@link #afterReadAll} resolves it once every
     * saved entity is in the world; -1 otherwise. Only a load sets it, so it isn't hashed or checkpointed.
     */
    @Derived("set only while a save is read, and resolved before the load ends")
    public int readAttackTarget = -1;

    // vanilla's static scratch vectors, set before each use
    @Derived("scratch, set before each use")
    private final Vec2 moveTarget = new Vec2(), offsetDestination = new Vec2(), currentDestination = new Vec2();
    /** The resolved {@link #command}, cached by id. */
    @Derived("a cache of command")
    private UnitCommand resolved;
    @Derived("a cache of command")
    private int resolvedId = -2;

    /** Vanilla's {@code new CommandAI()}: an AI, so it draws its timers. */
    public CommandAi(World world){
        super(world, "command-ai");
    }

    /** A blank controller for a checkpoint to fill: no draws. */
    public CommandAi(){
    }

    /**
     * A command AI whose construction draws were made with its unit, as a turret's or build turret's block unit draws
     * them when the block is constructed: no draws, and its kind set, so it hashes and copies as a drawn one does.
     */
    public static CommandAi blank(){
        var controller = new CommandAi();
        controller.kind = "command-ai";
        return controller;
    }

    private CommandAi(CommandAi source, CopyContext context){
        super(source, context);
        command = source.command;
        stances = source.stances.copy();
        targetPos = source.targetPos == null ? null : new Vec2(source.targetPos);
        targetBuild = context.map(source.targetBuild);
        attackTarget = context.map(source.attackTarget);
        queue = new QueuedTarget[source.queue.length];
        for(int i = 0; i < queue.length; i++) queue[i] = source.queue[i].copy(context);
        group = context.map(source.group);
        groupIndex = source.groupIndex;
        unreachableBuildings = new IntSeq(source.unreachableBuildings.ordered, source.unreachableBuildings.items.length);
        unreachableBuildings.addAll(source.unreachableBuildings);
        stopAtTarget = source.stopAtTarget;
        stopWhenInRange = source.stopWhenInRange;
        blockingUnit = source.blockingUnit;
        timeSpentBlocked = source.timeSpentBlocked;
        payloadPickupCooldown = source.payloadPickupCooldown;
        transferState = source.transferState;
        commandController = source.commandController == null ? null : (AiController)source.commandController.copy(context);
        lastCommand = source.lastCommand;
        resolved = source.resolved;
        resolvedId = source.resolvedId;
    }

    // region commands and stances

    /** {@code currentCommand()}: the command, {@code move} when there is none. */
    public UnitCommand currentCommand(){
        UnitCommand current = command();
        return current == null ? UnitCommand.MOVE : current;
    }

    /** {@code command}: the command, or null before the AI has one. */
    public UnitCommand command(){
        if(resolvedId != command){
            resolved = unit == null ? null : UnitCommand.byId(unit.world().content(), command);
            resolvedId = unit == null ? -2 : command;
        }
        return resolved;
    }

    /**
     * {@code init()}: a unit without a command takes its type's default, else the first of its commands, else
     * {@code move}. Baked content always has a default ({@code UnitType.init} sets it to the first command).
     */
    @Override
    public void bound(Unit unit){
        this.unit = unit;
        if(command != -1) return;
        String name = unit.params().defaultCommand();
        List<String> commands = unit.params().commands();
        if(name == null && !commands.isEmpty()) name = commands.getFirst();
        UnitCommand chosen = UnitCommand.of(name);
        command = (chosen == null ? UnitCommand.MOVE : chosen).id(unit.world().content());
    }

    /**
     * {@code command(command)}: switches to a command the unit's type has, dropping its mining and building plans;
     * ignores any other. For W4.1's factories and W4.7's cargo.
     */
    public void command(Unit unit, UnitCommand command){
        if(command == null || !command.allowedFor(unit)) return;
        // clear old state
        unit.mineTile = -1;
        unit.plans.clear();
        this.command = command.id(unit.world().content());
    }

    /** {@link #command(Unit, UnitCommand)} by content id; an id no command has is ignored. */
    public void command(Unit unit, int command){
        command(unit, UnitCommand.byId(unit.world().content(), command));
    }

    /** {@code hasStance(stance)}. */
    @Override
    public boolean hasStance(int stance){
        return stance >= 0 && stances.get(stance);
    }

    /** {@code setStance(stance, enabled)}. */
    public void setStance(int stance, boolean enabled){
        if(enabled) setStance(stance);
        else disableStance(stance);
    }

    /** {@code setStance(stance)}: on, and the stances it excludes off; {@code stop} isn't a stance and is ignored. */
    public void setStance(int stance){
        UnitStances table = UnitStances.of(unit.world().content());
        // this happens when an older save reads the default "shoot" stance, or any other removed stance
        if(stance == table.stop || !table.valid(stance)) return;
        stances.andNot(table.incompatible(stance));
        stances.set(stance);
        stanceChanged();
    }

    /** {@code disableStance(stance)}. */
    public void disableStance(int stance){
        if(stance < 0) return;
        stances.clear(stance);
        stanceChanged();
    }

    /** {@code stanceChanged()}: the command's own AI hears of it. */
    @Override
    public void stanceChanged(){
        if(commandController != null && !(commandController instanceof CommandAi)) commandController.stanceChanged();
    }

    @Override
    public boolean keepState(){
        return true;
    }

    /** {@code isLogicControllable()}: a processor may take over a unit without an order. */
    public boolean isLogicControllable(){
        return !hasCommand();
    }

    /** {@code isAttacking()}. */
    public boolean isAttacking(){
        return target != null && Targets.within(target, unit.x, unit.y, type().range + 10f);
    }

    /** {@code clearCommands()}. */
    public void clearCommands(){
        queue = new QueuedTarget[0];
        targetPos = null;
        attackTarget = null;
    }

    // endregion
    // region behaviour

    @Override
    public void updateUnit(Unit unit){
        this.unit = unit;
        World world = unit.world();
        UnitStances table = UnitStances.of(world.content());
        UnitCommand current = command();

        if(current == UnitCommand.MINE && !hasStance(table.mineAuto) && !hasItemStance(table)){
            setStance(table.mineAuto);
        }

        // pursue the target if relevant
        if(hasStance(table.pursueTarget) && !hasStance(table.patrol) && target != null && attackTarget == null && targetPos == null){
            commandTarget(target, false);
        }
        // pursue the target for patrol, keeping the current position
        if(hasStance(table.patrol) && hasStance(table.pursueTarget) && target != null && attackTarget == null){
            // commanding a target overwrites targetPos, so add it to the queue
            if(targetPos != null) enqueue(new QueuedTarget(targetPos.x, targetPos.y, null));
            commandTarget(target, false);
        }

        // remove invalid targets
        if(queue.length > 0) removeInvalid();

        // assign defaults: init() already did, a command can't be unset

        // update the command controller when the command changes
        if(lastCommand != command){
            lastCommand = command;
            commandController = current == null ? null : current.controller(world);
        }

        if(commandController != null){
            // commandController.unit(unit) runs its init() when the unit changes
            if(commandController.unit() != unit) commandController.bound(unit);
            commandController.updateUnit(unit);
        }else{
            // a unit that entered a payload block still runs the boost update, as in vanilla
            defaultBehavior();
            if(shouldBoost(table) && unit.params().canBoost()){
                // auto land when near target
                if((attackTarget != null && Targets.within(attackTarget, unit.x, unit.y, type().range))
                    || (hasStance(table.patrol) && target != null && Targets.within(target, unit.x, unit.y, type().range))){
                    unit.updateBoosting(false);
                }else{
                    unit.updateBoosting(true);
                }
            }else{
                // boosting control is not supported, so just don't
                unit.updateBoosting(false);
            }
        }
    }

    private boolean shouldBoost(UnitStances table){
        return hasStance(table.boost) || command() == UnitCommand.ENTER_PAYLOAD;
    }

    /** {@code ItemUnitStance.all().contains(this::hasStance)}. */
    private boolean hasItemStance(UnitStances table){
        for(int i = stances.nextSetBit(0); i >= 0; i = stances.nextSetBit(i + 1)){
            if(table.itemOf(i) != -1) return true;
        }
        return false;
    }

    /** {@code commandQueue.removeAll(e -> e instanceof Healthc h && !h.isValid())}, keeping the order. */
    private void removeInvalid(){
        int kept = 0;
        for(QueuedTarget entry : queue) if(entry.target == null || Targets.isValid(entry.target)) kept++;
        if(kept == queue.length) return;
        var out = new QueuedTarget[kept];
        int i = 0;
        for(QueuedTarget entry : queue) if(entry.target == null || Targets.isValid(entry.target)) out[i++] = entry;
        queue = out;
    }

    /**
     * {@code tryPickupUnit(pay)}: the closest grounded AI unit of the team within twice the type's size that the
     * carrier can take and touches.
     */
    void tryPickupUnit(){
        SpatialIndex index = SpatialIndex.of(unit.world());
        if(index == null) return;
        Unit carrier = unit;
        Unit target = index.closest(unit.team, unit.x, unit.y, unit.type().hitSize() * 2f,
            u -> u.isAI() && u != carrier && u.isGrounded() && Payloads.canPickup(carrier, u) && u.within(carrier.x, carrier.y, u.hitSize + carrier.hitSize));
        if(target != null) Payloads.pickedUnitPayload(unit, target);
    }

    @Override
    public Entity findMainTarget(float x, float y, float range, boolean air, boolean ground){
        if(!type().autoFindTarget && !hasStance(UnitStances.of(unit.world().content()).patrol)
            && !(targetPos == null || nearAttackTarget(unit.x, unit.y, type().range))){
            return null;
        }
        return super.findMainTarget(x, y, range, air, ground);
    }

    /** {@code defaultBehavior()}: payload handling, then target and move toward the order. */
    public void defaultBehavior(){
        World world = unit.world();
        AiType type = type();
        UnitStances table = UnitStances.of(world.content());
        UnitCommand current = command();
        Cause cause = new Cause.Ai(unit.team);

        if(unit.payloads != null){
            payloadPickupCooldown -= 1f;
            // auto-drop everything
            if(current == UnitCommand.UNLOAD_PAYLOAD && Payloads.hasPayload(unit)) Payloads.payloadDropped(unit, unit.x, unit.y, cause);
            // try to pick up what's under it
            if(current == UnitCommand.LOAD_UNITS) tryPickupUnit();
            // try to pick up a block
            if(current == UnitCommand.LOAD_BLOCKS && (targetPos == null || unit.within(targetPos.x, targetPos.y, 1f))) loadBlock(world, cause);
        }

        if(current == UnitCommand.ENTER_PAYLOAD && unit.type().allowedInPayloads() && (targetPos == null ? buildOn() != null : targetBuild != null)){
            Building build = targetPos == null ? buildOn() : targetBuild;
            if(build.canControlSelect(unit) && build.team == unit.team && build.acceptPayload(build, new Payload.UnitPayload(unit))){
                Orders.unitEnteredPayload(unit, build);
                // no use updating after this, the unit is gone
                return;
            }
        }

        updateVisuals();
        updateTargeting();

        if(attackTarget != null && invalid(attackTarget)){
            attackTarget = null;
            targetPos = null;
        }

        // move on to the next target
        if(attackTarget == null && targetPos == null) finishPath();

        boolean ramming = hasStance(table.ram);

        if(attackTarget != null){
            if(targetPos == null) targetPos = new Vec2();
            // don't target where target dummies are right now, target their block
            if(attackTarget instanceof Unit dummy && dummy.params().dummy() && dummy.tether != null){
                targetPos.set(dummy.tether.x(), dummy.tether.y());
            }else{
                targetPos.set(Targets.x(attackTarget), Targets.y(attackTarget));
            }
            if(unit.isGrounded() && attackTarget instanceof Building build && world.tiles().solid(build.tile()) && type.pathCost != PathCost.LEGS && !ramming){
                int best = closestEdge(build);
                if(best != -1) targetPos.set(world.tiles().x(best) * TILESIZE, world.tiles().y(best) * TILESIZE);
            }
        }

        boolean alwaysArrive = false;
        float engageRange = type.typeRange - 10f;
        boolean withinAttackRange = attackTarget != null && Targets.within(attackTarget, unit.x, unit.y, engageRange) && !ramming;

        if(targetPos == null){
            if(target != null){
                if(type.circleTarget && shouldFire()) circleAttack(type.circleTargetRadius);
                else faceTarget();
            }
            return;
        }

        boolean move = true, isFinalPoint = queue.length == 0;
        // the actual final point it needs to go to
        currentDestination.set(targetPos);
        targetBuild = buildWorld(targetPos.x, targetPos.y);
        if(unit.isGrounded() && targetBuild != null && world.tiles().solid(targetBuild.tile()) && targetBuild.block().acceptsUnitPayloads()
            && type.pathCost != PathCost.LEGS && !ramming){
            int best = closestEdge(targetBuild);
            if(best != -1) currentDestination.set(world.tiles().x(best) * TILESIZE, world.tiles().y(best) * TILESIZE);
        }
        // the straight-line point it is moving to: can be a pathfinding waypoint
        moveTarget.set(currentDestination);
        // destination + formation offset
        offsetDestination.set(currentDestination);
        // the enter payload command requires an exact position
        if(group != null && group.valid && groupIndex < group.size && current != UnitCommand.ENTER_PAYLOAD){
            offsetDestination.add(group.positions[groupIndex * 2], group.positions[groupIndex * 2 + 1]);
        }

        Building underTarget = buildWorld(targetPos.x, targetPos.y);
        if((hasStance(table.patrol) && !hasStance(table.pursueTarget) && target != null && Targets.within(target, unit.x, unit.y, type.typeRange - 2f) && !type.circleTarget)
            || (current == UnitCommand.ENTER_PAYLOAD && unit.within(currentDestination.x, currentDestination.y, 4f)
                || (underTarget != null && !type.circleTarget && unit.within(underTarget.x(), underTarget.y(), underTarget.block().size() * TILESIZE / 2f * 0.9f)))
            || (current == UnitCommand.LOOP_PAYLOAD && unit.within(offsetDestination.x, offsetDestination.y, 10f))){
            move = false;
        }

        if(unit.isGrounded() && !ramming){
            ControlPathfinder paths = ControlPathfinder.of(world);
            if(timerGet(TIMER_TARGET3, AVOID_INTERVAL)) blockingUnit = blocked(world, paths);

            float maxBlockTime = 60f * 5f;
            if(blockingUnit){
                timeSpentBlocked += 1f;
                if(timeSpentBlocked >= maxBlockTime * 2f) timeSpentBlocked = 0f;
            }else{
                timeSpentBlocked = 0f;
            }

            boolean unreachable = false;
            // next to the target, stop asking the pathfinder how to get there
            if(withinAttackRange){
                move = true;
                moveTarget.set(offsetDestination);
            }else if(paths != null && paths.active()){
                ControlPathfinder.Result result = paths.pathPosition(unit, offsetDestination.x, offsetDestination.y, currentDestination.x, currentDestination.y);
                unreachable = result.unreachable();
                move &= result.move() && (!blockingUnit || timeSpentBlocked > maxBlockTime);
                if(result.move()) moveTarget.set(result.x(), result.y());
                // do not wiggle in place
                if(unit.type().naval() && result.next() != -1 && move){
                    TileGrid tiles = world.tiles();
                    int nextX = result.next() % tiles.width(), nextY = result.next() / tiles.width();
                    if(!unit.canPass(nextX, nextY) && unit.tileOn() == tileWorld(tiles, moveTarget.x, moveTarget.y)) move = false;
                }
            }else{
                move = false;
            }

            // rare case where the unit must be perfectly aligned (happens with 1-tile gaps)
            alwaysArrive = moveTarget.epsilonEquals(unit.tileX() * TILESIZE, unit.tileY() * TILESIZE, 0.000001f);
            // the final point is reached if the returned coordinate is the one asked for
            isFinalPoint &= offsetDestination.epsilonEquals(moveTarget.x, moveTarget.y, 4.1f);

            // if the path is invalid, stop trying and record the end as unreachable
            if(UnitCalls.isAI(world, unit.team) && (unreachable || isPathImpassable(paths, UnitGroup.toTile(offsetDestination.x), UnitGroup.toTile(offsetDestination.y)))){
                if(attackTarget instanceof Building build && !unreachableBuildings.contains(Point2.pack(build.tileX(), build.tileY()))) unreachableBuildings.add(Point2.pack(build.tileX(), build.tileY()));
                attackTarget = null;
                finishPath();
                return;
            }
        }else{
            moveTarget.set(offsetDestination);
        }

        if(current == UnitCommand.LOOP_PAYLOAD) alwaysArrive = true;

        if(move){
            if(type.circleTarget && attackTarget != null){
                target = attackTarget;
                circleAttack(type.circleTargetRadius);
            }else{
                moveTo(moveTarget.x, moveTarget.y, withinAttackRange ? engageRange : unit.isGrounded() ? 0f : attackTarget != null && !ramming ? engageRange : 0f,
                    unit.isFlying() ? 40f : 100f, false, null, isFinalPoint || alwaysArrive);
            }
        }

        // if stopAtTarget is set, stop trying to move to the target once it is reached - used for defending
        if(attackTarget != null && stopAtTarget && Targets.within(attackTarget, unit.x, unit.y, engageRange - 1f)) attackTarget = null;

        if(unit.isFlying() && move && !(type.circleTarget && !unit.params().omniMovement())
            && (attackTarget == null || !Targets.within(attackTarget, unit.x, unit.y, type.typeRange))){
            unit.lookAt(offsetDestination.x, offsetDestination.y);
        }else{
            faceTarget();
        }

        UnitCommand exact = currentCommand();
        // reached the destination: end pathfinding
        if(attackTarget == null && (unit.within(offsetDestination.x, offsetDestination.y, exact.exactArrival && queue.length == 0 ? 1f : Math.max(5f, unit.hitSize / 2f))
            // circling units needn't reach the exact waypoint
            || (!unit.params().omniMovement() && unit.params().flying() && !type.rotateMoveFirst && !exact.exactArrival
                && Angles.angleDist(unit.angleTo(offsetDestination.x, offsetDestination.y), unit.rotation) > 60f
                && unit.within(offsetDestination.x, offsetDestination.y, Math.min(50f, 360f / unit.params().rotateSpeed() * unit.params().speed() / (Mathf.pi * 2f)))))){
            finishPath();
        }

        if(stopWhenInRange && targetPos != null && unit.within(offsetDestination.x, offsetDestination.y, engageRange * 0.9f)){
            finishPath();
            stopWhenInRange = false;
        }
    }

    /**
     * The {@code loadBlocks} pick-up: the payload a building of a team it may touch holds, or the whole building if
     * that can't be taken.
     */
    private void loadBlock(World world, Cause cause){
        Building build = buildWorld(unit.x, unit.y);
        if(build == null || !Teams.canInteract(unit.team, build.team)) return;
        // pick up the block's payload
        Payload current = build.getPayload();
        if(current != null && Payloads.canPickupPayload(unit, current)){
            Payloads.pickedBuildPayload(unit, build, false, cause);
        // pick up the whole building directly
        }else if(Payloads.visible(build) && build.canPickup() && Payloads.canPickup(unit, build)){
            Payloads.pickedBuildPayload(unit, build, true, cause);
        }
    }

    /**
     * {@code UnitComp.isPathImpassable(x, y)}: a type that doesn't fly can't enter the tile at all. Vanilla asks
     * {@code type.flying}, so a boosting mech still can't order itself onto deep water.
     */
    private boolean isPathImpassable(ControlPathfinder paths, int x, int y){
        return paths != null && paths.active() && !unit.params().flying() && paths.pathImpassable(unit, x, y);
    }

    /**
     * {@code blockingUnit}'s check: another commanded unit just ahead, heading for the same point but closer to it,
     * facing another way, with an obstacle between them.
     */
    private boolean blocked(World world, ControlPathfinder paths){
        SpatialIndex index = SpatialIndex.of(world);
        if(index == null || paths == null || !paths.active()) return false;
        var ahead = new Vec2().trns(unit.rotation, unit.hitSize / 2f);
        float max = unit.hitSize / 2f, radius = Math.max(7f, max), margin = 4f;
        Unit self = unit;
        Vec2 pos = targetPos;
        return index.nearbyCheck(unit.x + ahead.x - radius / 2f, unit.y + ahead.y - radius / 2f, radius, radius,
            u -> u != self && u.within(self.x, self.y, u.hitSize / 2f + self.hitSize / 2f + margin) && u.controller instanceof CommandAi ai
                && ai.targetPos != null
                // stop for the other unit only if it's closer to the target
                && ai.targetPos.x == pos.x && ai.targetPos.y == pos.y && u.dst2(pos.x, pos.y) < self.dst2(pos.x, pos.y)
                // don't stop if they're facing the same way
                && !Angles.within(self.rotation, u.rotation, 15f)
                // must be near an obstacle, stopping in open ground is pointless
                && paths.isNearObstacle(self, self.tileX(), self.tileY(), u.tileX(), u.tileY()));
    }

    /** {@code build.findClosestEdge(unit, Tile::solid)}: the nearest tile around the building's edge that isn't solid, or -1. */
    private int closestEdge(Building build){
        TileGrid tiles = unit.world().tiles();
        int size = build.block().size(), best = -1;
        float min = 0f;
        for(int i = 0; i < Footprint.edgeCount(size); i++){
            int x = build.tileX() + Footprint.edgeX(size, i), y = build.tileY() + Footprint.edgeY(size, i);
            if(!tiles.inBounds(x, y)) continue;
            int pos = tiles.pos(x, y);
            float dst = unit.dst2(x * TILESIZE, y * TILESIZE);
            if(!tiles.solid(pos) && (best == -1 || dst < min)){
                best = pos;
                min = dst;
            }
        }
        return best;
    }

    /** {@code world.buildWorld(x, y)}. */
    private Building buildWorld(float x, float y){
        int tx = Math.round(x / TILESIZE), ty = Math.round(y / TILESIZE);
        TileGrid tiles = unit.world().tiles();
        return tiles.inBounds(tx, ty) ? unit.world().buildings().at(tiles.pos(tx, ty)) : null;
    }

    /** {@code world.tileWorld(x, y)}'s position, or -1 outside the map. */
    private static int tileWorld(TileGrid tiles, float x, float y){
        int tx = Math.round(x / TILESIZE), ty = Math.round(y / TILESIZE);
        return tiles.inBounds(tx, ty) ? tiles.pos(tx, ty) : -1;
    }

    /** {@code finishPath()}: the next queued order, if any; a patrol or a payload loop puts the finished one back last. */
    void finishPath(){
        World world = unit.world();
        UnitCommand current = command();
        // the enter payload command never finishes until the unit is accepted
        if(current == UnitCommand.ENTER_PAYLOAD && queue.length == 0 && targetPos != null){
            Building build = buildWorld(targetPos.x, targetPos.y);
            if(build != null && build.block().acceptsUnitPayloads()) return;
        }

        if(current == UnitCommand.LOOP_PAYLOAD && unit.payloads != null){
            Cause cause = new Cause.Ai(unit.team);
            if(transferState == TRANSFER_NONE) transferState = Payloads.hasPayload(unit) ? TRANSFER_UNLOAD : TRANSFER_LOAD;
            if(payloadPickupCooldown > 0f) return;
            if(transferState == TRANSFER_UNLOAD){
                // drop until there's a failure
                int prev = -1;
                while(Payloads.hasPayload(unit) && prev != unit.payloads.size()){
                    prev = unit.payloads.size();
                    Payloads.payloadDropped(unit, unit.x, unit.y, cause);
                }
                // wait for everything to unload before running the code below
                if(Payloads.hasPayload(unit)) return;
                payloadPickupCooldown = 60f;
            }else if(transferState == TRANSFER_LOAD){
                // pick up units until there's a failure
                int prev = -1;
                while(prev != unit.payloads.size()){
                    prev = unit.payloads.size();
                    tryPickupUnit();
                }
                // wait to load things before running the code below
                if(!Payloads.hasPayload(unit)) return;
                payloadPickupCooldown = 60f;
            }
            // it will never finish
            if(queue.length == 0) return;
        }

        transferState = TRANSFER_NONE;
        Vec2 prev = targetPos;
        targetPos = null;

        if(queue.length > 0){
            QueuedTarget next = queue[0];
            queue = Arrays.copyOfRange(queue, 1, queue.length);
            if(next.target != null) commandTarget(next.target, stopAtTarget);
            else commandPosition(new Vec2(next.x, next.y));

            if(prev != null && (hasStance(UnitStances.of(world.content()).patrol) || current == UnitCommand.LOOP_PAYLOAD)){
                enqueue(new QueuedTarget(prev.x, prev.y, null));
            }
            // make sure the spot in the formation is reachable
            if(group != null){
                float nx = next.target != null ? Targets.x(next.target) : next.x, ny = next.target != null ? Targets.y(next.target) : next.y;
                group.updateRaycast(world, groupIndex, nx, ny);
            }
        }else{
            group = null;
        }
    }

    private void enqueue(QueuedTarget entry){
        queue = Arrays.copyOf(queue, queue.length + 1);
        queue[queue.length - 1] = entry;
    }

    /** {@code afterRead(unit)}, which {@code afterReadAll} calls once a load added every entity: the unit attack target by id. */
    public void afterReadAll(World world){
        if(readAttackTarget == -1) return;
        EntityGroups groups = EntityGroups.of(world);
        attackTarget = groups != null && groups.byId(readAttackTarget) instanceof Unit target ? target : null;
        readAttackTarget = -1;
    }

    /** {@code removed(unit)}: {@code clearCommands()}. */
    @Override
    public void removed(Unit unit){
        clearCommands();
    }

    /** {@code commandQueue(location)}: the order now if there is none, else queued (up to 50, no duplicates). */
    public void commandQueue(QueuedTarget location){
        if(targetPos == null && attackTarget == null){
            if(location.target != null) commandTarget(location.target, stopAtTarget);
            else commandPosition(new Vec2(location.x, location.y));
        }else if(queue.length < MAX_QUEUE && !queued(location)){
            enqueue(location);
        }
    }

    /** {@code commandQueue.contains(location)}: positions by value, targets by identity. */
    private boolean queued(QueuedTarget location){
        for(QueuedTarget entry : queue){
            if(location.target != null ? entry.target == location.target : entry.target == null && entry.x == location.x && entry.y == location.y) return true;
        }
        return false;
    }

    /**
     * {@code hit(bullet)}: an AI team's unit with no order counter-attacks what shot it, at most every ten seconds,
     * if its weapons can reach it.
     */
    @Override
    public void hit(Unit unit, Entity owner){
        this.unit = unit;
        AiType type = type();
        if(UnitCalls.isAI(unit.world(), unit.team) && (owner instanceof Unit || owner instanceof Building) && Targets.team(owner) != unit.team
            && attackTarget == null && !(owner instanceof Unit u && !Targets.checkTarget(u, type.targetAir, type.targetGround))
            && timerGet(TIMER_TARGET4, 60f * 10f)){
            commandTarget(owner, true);
        }
    }

    @Override
    public boolean shouldFire(){
        return !hasStance(UnitStances.of(unit.world().content()).holdFire);
    }

    @Override
    public Entity findTarget(float x, float y, float range, boolean air, boolean ground){
        if(!nearAttackTarget(x, y, range)) return super.findTarget(x, y, range, air, ground);
        // Units.isHittable(attackTarget, air, ground)
        boolean hittable = attackTarget instanceof Building ? ground : attackTarget instanceof Unit u && Targets.checkTarget(u, air, ground);
        return hittable ? attackTarget : null;
    }

    /** {@code nearAttackTarget(x, y, range)}. */
    public boolean nearAttackTarget(float x, float y, float range){
        return attackTarget != null && Targets.within(attackTarget, x, y, range + 3f + Targets.hitSize(attackTarget) / 2f);
    }

    /** {@code retarget()}: faster with an explicit target. */
    @Override
    public boolean retarget(){
        return timerGet(TIMER_TARGET, attackTarget != null ? 10f : 20f);
    }

    /** {@code hasCommand()}: it has a position to go to. */
    public boolean hasCommand(){
        return targetPos != null;
    }

    /** {@code commandPosition(pos)}: go to {@code pos}; the command's own AI hears of it too. */
    @Override
    public void commandPosition(Vec2 pos){
        if(pos == null) return;
        commandPosition(pos, false);
        if(commandController != null) commandController.commandPosition(pos);
    }

    /** {@code commandPosition(pos, stopWhenInRange)}: a copy of {@code pos}, snapped to an own building for the commands that do. */
    public void commandPosition(Vec2 pos, boolean stopWhenInRange){
        if(pos == null) return;
        targetPos = new Vec2(pos);
        UnitCommand current = unit == null ? null : command();
        if(current != null && current.snapToBuilding){
            Building build = buildWorld(targetPos.x, targetPos.y);
            if(build != null && build.team == unit.team) targetPos.set(build.x(), build.y());
        }
        attackTarget = null;
        this.stopWhenInRange = stopWhenInRange;
    }

    /** {@code commandTarget(target)}: attack or follow {@code target}; the command's own AI hears of it too. */
    @Override
    public void commandTarget(Entity target){
        commandTarget(target, false);
        if(commandController != null) commandController.commandTarget(target);
    }

    public void commandTarget(Entity target, boolean stopAtTarget){
        attackTarget = target;
        this.stopAtTarget = stopAtTarget;
    }

    // endregion
    // region persistence

    @Override
    public UnitController copy(CopyContext context){
        return new CommandAi(this, context);
    }

    @Override
    public void hash(Hasher hasher){
        super.hash(hasher);
        hasher.i(command);
        for(int i = stances.nextSetBit(0); i >= 0; i = stances.nextSetBit(i + 1)) hasher.i(i);
        hasher.bool(targetPos != null);
        if(targetPos != null) hasher.f(targetPos.x).f(targetPos.y);
        hasher.i(targetBuild == null ? -1 : targetBuild.id());
        hasher.i(attackTarget == null ? -1 : attackTarget.id()).i(queue.length);
        for(QueuedTarget target : queue) target.hash(hasher);
        hasher.bool(group != null);
        if(group != null) group.hash(hasher);
        hasher.i(groupIndex).i(unreachableBuildings.size);
        for(int i = 0; i < unreachableBuildings.size; i++) hasher.i(unreachableBuildings.items[i]);
        hasher.bool(stopAtTarget).bool(stopWhenInRange).bool(blockingUnit).f(timeSpentBlocked).f(payloadPickupCooldown).i(transferState);
        hasher.bool(commandController != null);
        if(commandController != null) commandController.hash(hasher);
        hasher.i(lastCommand);
    }

    @Override
    public void write(CheckpointOut out){
        super.write(out);
        out.i("command", command).ints("stances", stanceIds()).obj("targetPos", targetPos).ref("targetBuild", targetBuild).ref("attackTarget", attackTarget)
            .list("queue", Arrays.asList(queue)).ref("group", group).i("groupIndex", groupIndex).intSeq("unreachableBuildings", unreachableBuildings)
            .bool("stopAtTarget", stopAtTarget).bool("stopWhenInRange", stopWhenInRange).bool("blockingUnit", blockingUnit)
            .f("timeSpentBlocked", timeSpentBlocked).f("payloadPickupCooldown", payloadPickupCooldown).i("transferState", transferState)
            .obj("commandController", commandController).i("lastCommand", lastCommand);
    }

    @Override
    public void read(CheckpointIn in){
        super.read(in);
        command = in.i("command");
        stances = new Bits();
        for(int stance : in.ints("stances")) stances.set(stance);
        targetPos = in.obj("targetPos", Vec2.class);
        targetBuild = in.ref("targetBuild", Building.class);
        attackTarget = in.ref("attackTarget", Entity.class);
        List<QueuedTarget> stored = in.list("queue", QueuedTarget.class);
        if(stored == null || stored.contains(null)) throw new CheckpointException("a command queue is missing an entry");
        queue = stored.toArray(QueuedTarget[]::new);
        group = in.ref("group", UnitGroup.class);
        groupIndex = in.i("groupIndex");
        IntSeq unreachable = in.intSeq("unreachableBuildings");
        unreachableBuildings = unreachable == null ? new IntSeq(8) : unreachable;
        stopAtTarget = in.bool("stopAtTarget");
        stopWhenInRange = in.bool("stopWhenInRange");
        blockingUnit = in.bool("blockingUnit");
        timeSpentBlocked = in.f("timeSpentBlocked");
        payloadPickupCooldown = in.f("payloadPickupCooldown");
        transferState = in.i("transferState");
        commandController = in.obj("commandController", AiController.class);
        lastCommand = in.i("lastCommand");
        resolvedId = -2;
    }

    /** The stances' ids, ascending: {@code TypeIO.writeController}'s order. */
    public int[] stanceIds(){
        var ids = new IntSeq();
        for(int i = stances.nextSetBit(0); i >= 0; i = stances.nextSetBit(i + 1)) ids.add(i);
        return Arrays.copyOf(ids.items, ids.size);
    }

    // endregion

    /** One {@code commandQueue} entry: a target entity, or a position when {@link #target} is null. */
    public static final class QueuedTarget implements Checkpointable{
        public float x, y;
        public Entity target;

        public QueuedTarget(){
        }

        public QueuedTarget(float x, float y, Entity target){
            this.x = x;
            this.y = y;
            this.target = target;
        }

        QueuedTarget copy(CopyContext context){
            return new QueuedTarget(x, y, context.map(target));
        }

        void hash(Hasher hasher){
            hasher.f(x).f(y).i(target == null ? -1 : target.id());
        }

        @Override
        public void write(CheckpointOut out){
            out.f("x", x).f("y", y).ref("target", target);
        }

        @Override
        public void read(CheckpointIn in){
            x = in.f("x");
            y = in.f("y");
            target = in.ref("target", Entity.class);
        }
    }
}
