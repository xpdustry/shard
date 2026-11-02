package com.xpdustry.shard.server.intent;

import com.xpdustry.shard.common.geom.*;
import com.xpdustry.shard.common.math.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.server.intent.action.*;
import com.xpdustry.shard.sim.build.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.rules.*;
import com.xpdustry.shard.sim.tile.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.world.*;
import com.xpdustry.shard.wire.v160_7.Packets;

import java.util.*;

/**
 * {@code clientSnapshot} (NetServer.java:754-903), the input a client sends every 66 ms: where its unit is and where it
 * aims, its flags, its build plans and its view. The newest waiting one replaces older ones in the session's mailbox.
 *
 * <p>Admission, as vanilla's handler:
 * <ol>
 *   <li>An id below the last applied one is out of date. Non-finite numbers become 0.</li>
 *   <li>The player's flags follow the client, except that a flying mech can't shoot and only boosting types boost.</li>
 *   <li>A builder's queue is replaced by the plans: done plans, plans out of the map or of unknown blocks, placements
 *   rotated outside 0 to 3 (Shard's bound: the wire's signed byte allows any), and plans the action filters deny are
 *   skipped, a denied one also removed from the client's queue ({@code removeQueueBlock}). A plan's processor code past
 *   Shard's bounds or the tick's budget is dropped from it ({@code Build.admitConfig}).</li>
 *   <li>The unit aims, shoots and mines as the client says. Unless the client thinks it's dead or names another unit,
 *   the unit takes the client's velocity, capped at its speed, and moves toward the client's position by at most what
 *   its speed allows since the last snapshot ({@code 1.1 × speed × elapsed}), through tile collisions on the ground.
 *   With {@code strict} on, the server keeps that position, and corrects the client with {@code setPosition} when it
 *   is over 14 tiles off; with it off, the server takes the client's position as it is.</li>
 *   <li>The position and rotation become the targets the unit is interpolated toward ({@link RemoteSync}).</li>
 * </ol>
 * The elapsed time is the time between the two snapshots' arrivals, which the transport records (vanilla reads
 * {@code Time.millis()} when it handles them, at the start of a frame).
 *
 * @param mining the packed tile, or -1
 * @param selectedBlock the block's content id, or -1
 * @param plans the plan queue, empty for none (vanilla's null queue does the same); each plan's config is
 *     {@link Configs#storable}
 */
public record Snapshot(Policy policy, int snapshotId, int unitId, boolean dead, float x, float y, float pointerX, float pointerY,
                       float rotation, float baseRotation, float xVelocity, float yVelocity, int mining, boolean boosting, boolean shooting,
                       boolean chatting, boolean building, int selectedBlock, int selectedRotation, List<BuildPlan> plans,
                       float viewX, float viewY, float viewWidth, float viewHeight) implements Guarded{
    /** {@code correctDist}: a client this far from the server's position is moved back. */
    public static final float CORRECT_DIST = Footprint.TILE_SIZE * 14f;
    /** The longest gap a move may cover, in ms. */
    public static final long MAX_ELAPSED = 1500;

    public Snapshot{
        Objects.requireNonNull(policy, "policy");
        plans = List.copyOf(plans);
    }

    @Override
    public Verdict admit(World world, IntentEnvelope envelope){
        Acting acting = Acting.of(world, envelope, policy);
        Verdict refused = Acting.refused(acting);
        if(refused != null) return refused;
        PlayerInput con = acting.input;
        if(snapshotId < con.lastSnapshot) return Verdict.rejected("out of date");
        Player player = acting.player;

        float x = valid(this.x), y = valid(this.y), xVelocity = valid(this.xVelocity), yVelocity = valid(this.yVelocity);
        float pointerX = valid(this.pointerX), pointerY = valid(this.pointerY), rotation = valid(this.rotation), baseRotation = valid(this.baseRotation);
        // admins.isStrict() && headless: a server is headless
        boolean verifyPosition = policy.strict();
        long now = acting.arrivalMillis();
        if(!con.hasClientTime){
            con.lastClientTime = now - 16;
            con.hasClientTime = true;
        }
        con.viewX = viewX;
        con.viewY = viewY;
        con.viewWidth = viewWidth;
        con.viewHeight = viewHeight;

        boolean shooting = this.shooting, boosting = this.boosting;
        Unit unit = player.unit();
        // disable shooting when a mech flies
        if(!player.dead() && unit.isFlying() && unit.shape instanceof MoveShape.Mech) shooting = false;
        if(!player.dead() && (unit.params().flying() || !unit.params().canBoost())) boosting = false;

        player.mouseX = pointerX;
        player.mouseY = pointerY;
        player.typing = chatting;
        player.shooting = shooting;
        player.boosting = boosting;
        player.selectedBlock = world.content().blocks() > selectedBlock && selectedBlock >= 0 ? selectedBlock : -1;
        player.selectedRotation = selectedRotation;

        if(UnitInput.isBuilder(player)) queue(acting, unit);

        if(!player.dead()){
            UnitInput.controlWeapons(unit, shooting, shooting);
            UnitInput.aim(unit, pointerX, pointerY, true);
            unit.mineTile = acting.tileExists(mining) ? mining : -1;

            long elapsed = Math.min(now - con.lastClientTime, MAX_ELAPSED);
            float maxSpeed = unit.speed();
            float maxMove = elapsed / 1000f * 60f * maxSpeed * 1.1f;

            // ignore the position if the player thinks they're dead, or the unit is wrong
            boolean ignorePosition = dead || unit.id() != unitId;
            float newx = unit.x, newy = unit.y;
            if(!ignorePosition){
                con.snapshots++;
                con.maxLag = Math.max(con.maxLag, Mathf.dst(unit.x, unit.y, x, y));
                unit.vel.set(xVelocity, yVelocity).limit(maxSpeed);
                var vector = new Vec2(x, y).sub(unit.x, unit.y);
                if(vector.len2() > maxMove * maxMove) con.clamped++;
                vector.limit(maxMove);
                float prevx = unit.x, prevy = unit.y;
                if(!unit.isFlying()){
                    unit.move(vector.x, vector.y);
                }else{
                    unit.x += vector.x;
                    unit.y += vector.y;
                }
                newx = unit.x;
                newy = unit.y;
                if(!verifyPosition){
                    unit.set(prevx, prevy);
                    newx = x;
                    newy = y;
                }else if(!Mathf.within(x, y, newx, newy, CORRECT_DIST)){
                    // teleport and correct position when necessary
                    acting.reply(new Packets.SetPosition(newx, newy));
                    con.corrections++;
                }
                con.maxDeviation = Math.max(con.maxDeviation, Mathf.dst(newx, newy, x, y));
            }
            // the buffer vanilla reads back: the base rotation for a mech, then the rotation and the position
            RemoteSync.read(unit, baseRotation, rotation, newx, newy);
        }else{
            player.x = x;
            player.y = y;
        }
        con.lastSnapshot = snapshotId;
        con.lastClientTime = now;
        return Verdict.ADMITTED;
    }

    /** The builder's queue becomes the client's (NetServer.java:810-841). */
    private void queue(Acting acting, Unit unit){
        World world = acting.world;
        unit.plans.clear();
        unit.updateBuilding = building;
        TileGrid tiles = world.tiles();
        BlockTable table = tiles.table();
        var rejected = new ArrayList<BuildPlan>();
        for(BuildPlan req : plans){
            if(!tiles.inBounds(req.x, req.y)) continue;
            BlockDef block = !req.breaking && req.block >= 0 && req.block < world.content().blocks() ? world.content().block(req.block) : null;
            if(!req.breaking && block == null) continue;
            // Shard's bound (review 40): stock clients rotate by 0 to 3, and a building must never hold another
            if(!req.breaking && (req.rotation < 0 || req.rotation > 3)) continue;
            int pos = tiles.pos(req.x, req.y);
            Building build = world.buildings().at(pos);
            // auto-skip done requests
            if(req.breaking && table.isAir(tiles.block(pos))) continue;
            if(!req.breaking && tiles.block(pos) == req.block && tiles.team(pos) != VanillaIds.DERELICT
                && (!block.rotate() || (build != null && build.rotation == req.rotation))) continue;
            // a plan rejected earlier in this snapshot
            if(contains(rejected, req)) continue;
            PlayerAction action = acting.action(req.breaking ? ActionType.BREAK_BLOCK : ActionType.PLACE_BLOCK).tile(req.x, req.y)
                .block(req.block, req.rotation, req.config).build();
            if(acting.denied(action) != null){
                // force the player to remove this plan
                acting.reply(new Packets.RemoveQueueBlock(req.x, req.y, req.breaking));
                rejected.add(req);
                continue;
            }
            // Shard's bound (review 40): a processor's code is charged to the tick's budget as a direct config is, and
            // one it refuses is dropped, so the block is built without it and its constructFinish carries none
            boolean admitted = req.breaking || Build.admitConfig(world, block, req.config) == null;
            unit.plans.addLast(copy(req, block, admitted ? req.config : null));
        }
    }

    private static boolean contains(List<BuildPlan> rejected, BuildPlan req){
        for(BuildPlan r : rejected) if(r.breaking == req.breaking && r.x == req.x && r.y == req.y) return true;
        return false;
    }

    /** The queue's own plan, rotated as {@code readPlan}'s constructor rotates it: the logged one stays as it arrived. */
    private static BuildPlan copy(BuildPlan plan, BlockDef block, Object config){
        return plan.breaking ? BuildPlan.breaking(plan.x, plan.y) : BuildPlan.place(plan.x, plan.y, plan.rotation, block, Configs.copy(config));
    }

    /** {@code invalid(f)}: infinite or NaN become 0. */
    private static float valid(float value){
        return Float.isInfinite(value) || Float.isNaN(value) ? 0f : value;
    }
}
