package com.xpdustry.shard.sim.unit;

import com.xpdustry.shard.common.math.*;
import com.xpdustry.shard.common.registry.*;
import com.xpdustry.shard.sim.entity.*;
import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.world.*;

/**
 * A player's unit as its client last reported it: the {@code _LAST_} and {@code _TARGET_} fields vanilla generates for
 * every {@code @SyncField} ({@code PosComp.x}, {@code y}, {@code RotComp.rotation}, {@code MechComp.baseRotation}),
 * which {@code readSyncManual} sets from a {@code clientSnapshot} and {@link SyncComponent} interpolates between. It is
 * attached to a unit at its first read and stays with it, as vanilla's fields do.
 *
 * <p>Vanilla times both with {@code Time.millis()}; a world has no clock, so here they count ticks: the read's tick and
 * the ticks since the read before it. A server admits a snapshot at the start of a frame and moves units later in it,
 * in either case, so the ratio is the same.
 *
 * <p>{@link Persistence#AUTHORITATIVE}: it moves the unit.
 */
public final class RemoteSync{
    public static final AttachmentKey<RemoteSync> KEY = AttachmentKey.create("sim.unit.remote-sync", RemoteSync.class, Persistence.AUTHORITATIVE,
        (sync, context) -> sync.copy(), RemoteSync::hash);

    /** The tick of the last read plus one; 0 before the first, vanilla's {@code lastUpdated == 0}. */
    public long lastUpdated;
    /** Ticks between the last two reads, vanilla's {@code updateSpacing}; 0 until there were two. */
    public long updateSpacing;
    public float lastX, lastY, lastRotation, lastBaseRotation;
    public float targetX, targetY, targetRotation, targetBaseRotation;

    public RemoteSync(){
    }

    /** {@code unit}'s, attached now if it has none. */
    public static RemoteSync of(Unit unit){
        RemoteSync sync = unit.attachment(KEY);
        if(sync == null){
            sync = new RemoteSync();
            unit.attach(KEY, sync);
        }
        return sync;
    }

    /**
     * {@code readSyncManual(buffer)} with the buffer {@code NetServer.clientSnapshot} fills (NetServer.java:888-895):
     * the base rotation only for a mech, then the rotation and the position. Each field's last value is the unit's
     * current one.
     */
    public static void read(Unit unit, float baseRotation, float rotation, float x, float y){
        RemoteSync sync = of(unit);
        long now = unit.world().ticks() + 1;
        if(sync.lastUpdated != 0) sync.updateSpacing = now - sync.lastUpdated;
        sync.lastUpdated = now;
        if(unit.shape instanceof MoveShape.Mech mech){
            sync.lastBaseRotation = mech.baseRotation;
            sync.targetBaseRotation = baseRotation;
        }
        sync.lastRotation = unit.rotation;
        sync.targetRotation = rotation;
        sync.lastX = unit.x;
        sync.targetX = x;
        sync.lastY = unit.y;
        sync.targetY = y;
    }

    /**
     * The generated {@code snapInterpolation()}: the last values and the targets become the unit's own, so the unit
     * stays where it is until its client reports otherwise. A server runs it when a player takes control of a unit
     * (PlayerComp.java:308), and logic's {@code setprop @x}/{@code @y} on any unit (UnitComp.java:366). Vanilla's spacing
     * is 16 ms, a frame.
     */
    public static void snap(Unit unit){
        RemoteSync sync = of(unit);
        sync.updateSpacing = 1;
        sync.lastUpdated = unit.world().ticks() + 1;
        if(unit.shape instanceof MoveShape.Mech mech) sync.lastBaseRotation = sync.targetBaseRotation = mech.baseRotation;
        sync.lastRotation = sync.targetRotation = unit.rotation;
        sync.lastX = sync.targetX = unit.x;
        sync.lastY = sync.targetY = unit.y;
    }

    /** The generated {@code interpolate()} (MechUnit, UnitEntity): toward the targets, up to twice past them. */
    void interpolate(Unit unit){
        var mech = unit.shape instanceof MoveShape.Mech m ? m : null;
        if(lastUpdated != 0 && updateSpacing != 0){
            float timeSinceUpdate = unit.world().ticks() + 1 - lastUpdated;
            float alpha = Math.min(timeSinceUpdate / (float)updateSpacing, 2f);
            if(mech != null) mech.baseRotation = Mathf.slerp(lastBaseRotation, targetBaseRotation, alpha);
            unit.rotation = Mathf.slerp(lastRotation, targetRotation, alpha);
            unit.x = Mathf.lerp(lastX, targetX, alpha);
            unit.y = Mathf.lerp(lastY, targetY, alpha);
        }else if(lastUpdated != 0){
            if(mech != null) mech.baseRotation = targetBaseRotation;
            unit.rotation = targetRotation;
            unit.x = targetX;
            unit.y = targetY;
        }
    }

    RemoteSync copy(){
        var out = new RemoteSync();
        out.lastUpdated = lastUpdated;
        out.updateSpacing = updateSpacing;
        out.lastX = lastX;
        out.lastY = lastY;
        out.lastRotation = lastRotation;
        out.lastBaseRotation = lastBaseRotation;
        out.targetX = targetX;
        out.targetY = targetY;
        out.targetRotation = targetRotation;
        out.targetBaseRotation = targetBaseRotation;
        return out;
    }

    void hash(Hasher hasher){
        hasher.l(lastUpdated).l(updateSpacing).f(lastX).f(lastY).f(lastRotation).f(lastBaseRotation)
            .f(targetX).f(targetY).f(targetRotation).f(targetBaseRotation);
    }

    @Register
    public static final class Codec implements AttachmentCodec<RemoteSync>{
        @Override
        public AttachmentKey<RemoteSync> key(){
            return KEY;
        }

        @Override
        public void write(RemoteSync value, CheckpointOut out){
            out.l("lastUpdated", value.lastUpdated).l("updateSpacing", value.updateSpacing)
                .f("lastX", value.lastX).f("lastY", value.lastY).f("lastRotation", value.lastRotation).f("lastBaseRotation", value.lastBaseRotation)
                .f("targetX", value.targetX).f("targetY", value.targetY).f("targetRotation", value.targetRotation)
                .f("targetBaseRotation", value.targetBaseRotation);
        }

        @Override
        public RemoteSync read(CheckpointIn in){
            var sync = new RemoteSync();
            sync.lastUpdated = in.l("lastUpdated");
            sync.updateSpacing = in.l("updateSpacing");
            sync.lastX = in.f("lastX");
            sync.lastY = in.f("lastY");
            sync.lastRotation = in.f("lastRotation");
            sync.lastBaseRotation = in.f("lastBaseRotation");
            sync.targetX = in.f("targetX");
            sync.targetY = in.f("targetY");
            sync.targetRotation = in.f("targetRotation");
            sync.targetBaseRotation = in.f("targetBaseRotation");
            return sync;
        }
    }
}
