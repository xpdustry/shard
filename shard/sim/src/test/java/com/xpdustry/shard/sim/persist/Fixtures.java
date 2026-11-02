package com.xpdustry.shard.sim.persist;

import com.xpdustry.shard.common.registry.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.entity.*;
import com.xpdustry.shard.sim.toy.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.world.*;

import java.util.*;

/**
 * Checkpoint fixtures: codecs for the toy kinds (another owner's test classes, so they can't implement
 * {@link Checkpointable}), and a world that also exercises timers, attachments, a derived part and inputs. The extra
 * handlers and parts are not registered, so worlds built from {@link Systems#builtIn()} (and the golden hashes) don't
 * see them.
 */
public final class Fixtures{
    /** An authoritative attachment: stored by name through {@link HeatCodec}. */
    public static final AttachmentKey<Heat> HEAT = AttachmentKey.create("test.persist.heat", Heat.class,
        Persistence.AUTHORITATIVE, (heat, context) -> heat, (heat, hasher) -> hasher.i(heat.level()));
    /** A derived attachment: kept equal to the heat's label and rebuilt by {@link MarkHook}. */
    public static final AttachmentKey<String> MARK = AttachmentKey.create("test.persist.mark", String.class,
        Persistence.DERIVED, (mark, context) -> mark, (mark, hasher) -> hasher.str(mark));

    public record Heat(int level){
        String mark(){
            return "heat " + level;
        }
    }

    /** The toy world plus {@link Sparks} and {@link Census}. */
    public static WorldConfig config(long seed){
        var handlers = new ArrayList<PhaseHandler>();
        for(Phase phase : Phase.values()) handlers.addAll(Systems.builtIn().handlers(phase));
        handlers.add(new Sparks());
        handlers.add(new Census.Count());
        return WorldConfig.of(ToyContent.INSTANCE, 48, 48, seed)
            .withSystems(Systems.builtIn().withHandlers(handlers).with(Census.class, Census::new));
    }

    /** A fixture world with {@link Chaos} on. */
    public static World churning(long seed){
        var world = new World(config(seed));
        world.part(Chaos.class).enabled = true;
        return world;
    }

    /** Offers the inputs the gate test feeds at {@code tick}: joins, intents and admin commands. */
    public static void feed(World world, long tick){
        if(tick % 97 == 5) world.inbox().offer(new Submission(new Members.Join(tick, (int)tick % 3), Cause.SERVER));
        if(tick % 13 == 0){
            long session = (tick / 97) * 97 + 5;
            world.inbox().offer(new IntentEnvelope(session, (int)session % 3, tick, tick * 1000, new Poke((int)(tick * 31 % world.tiles().size()))));
        }
        if(tick % 41 == 0) world.inbox().offer(new Submission(new Wipe((int)(tick % 40) + 2, (int)(tick % 37) + 2), new Cause.Admin("tester")));
    }

    /** Schedules {@link Spark}s on random buildings and heats them. */
    public static final class Sparks implements PhaseHandler{
        @Override
        public Phase phase(){
            return Phase.MISC;
        }

        @Override
        public int order(){
            return 10;
        }

        @Override
        public void run(World world){
            var rand = world.rand();
            Building target = world.buildings().at(rand.random(world.tiles().size() - 1));
            if(target == null) return;
            if(rand.chance(0.4)) world.timers().run(rand.random(1f, 40f), new Spark(target, rand.random(1, 7)));
            if(rand.chance(0.3)){
                Heat heat = target.attachment(HEAT);
                var next = new Heat(heat == null ? 1 : heat.level() + 1);
                target.attach(HEAT, next);
                target.attach(MARK, next.mark());
            }
        }
    }

    /**
     * A timer task holding a building that may be gone when it fires. A big spark schedules a smaller one with no
     * delay, which the same timer pass runs (vanilla's {@code Time.run} quirk).
     */
    public record Spark(Building target, int amount) implements Timers.Task{
        @Override
        public void run(World world){
            if(target.isValid() && target instanceof Counter counter) counter.count += amount;
            if(amount > 3) world.timers().run(0f, new Spark(target, amount - 3));
        }
    }

    /** Damages the building on a tile. */
    public record Poke(int tile) implements Intent{
        @Override
        public Verdict apply(World world, IntentEnvelope envelope){
            Building building = world.buildings().at(tile);
            if(building == null) return Verdict.rejected("nothing at " + tile);
            building.health -= 1f;
            return Verdict.ADMITTED;
        }
    }

    /** Removes the building at a tile. */
    public record Wipe(int x, int y) implements Command.Admin{
        @Override
        public Verdict apply(World world, Cause cause){
            Building building = world.buildings().at(world.tiles().pos(x, y));
            if(building == null) return Verdict.rejected("nothing to wipe");
            world.buildings().remove(building, cause);
            return Verdict.ADMITTED;
        }
    }

    /** A derived part: building counts by block, recounted at the end of every tick and on restore. */
    public static final class Census implements WorldPart{
        public int[] counts = new int[0];

        @Override
        public void init(World world){
            rebuild(world);
        }

        @Override
        public void rebuild(World world){
            counts = new int[world.content().blocks()];
            world.buildings().each(building -> counts[building.block().id()]++);
        }

        @Override
        public WorldPart copy(CopyContext context){
            var out = new Census();
            out.counts = counts.clone();
            return out;
        }

        @Override
        public void hash(Hasher hasher){
        }

        @Override
        public Persistence persistence(){
            return Persistence.DERIVED;
        }

        static final class Count implements PhaseHandler{
            @Override
            public Phase phase(){
                return Phase.values()[Phase.values().length - 1];
            }

            @Override
            public void run(World world){
                world.part(Census.class).rebuild(world);
            }
        }
    }

    @Register
    public static final class CounterCodec implements ClassCodec<Counter>{
        @Override
        public Class<Counter> type(){
            return Counter.class;
        }

        @Override
        public void write(Counter value, CheckpointOut out){
            out.i("count", value.count).bool("idle", value.idle);
        }

        @Override
        public void read(Counter value, CheckpointIn in){
            value.count = in.i("count");
            value.idle = in.bool("idle");
        }
    }

    @Register
    public static final class LinkCodec implements ClassCodec<Link>{
        @Override
        public Class<Link> type(){
            return Link.class;
        }

        @Override
        public void write(Link value, CheckpointOut out){
            out.ref("target", value.target).ref("watched", value.watched).i("pulses", value.pulses).floats("history", value.history);
        }

        @Override
        public void read(Link value, CheckpointIn in){
            value.target = in.ref("target", Building.class);
            value.watched = in.ref("watched", Unit.class);
            value.pulses = in.i("pulses");
            value.history = in.floats("history");
        }
    }

    @Register
    public static final class FollowCodec implements ClassCodec<Follow>{
        @Override
        public Class<Follow> type(){
            return Follow.class;
        }

        @Override
        public Follow create(){
            return new Follow(null);
        }

        @Override
        public void write(Follow value, CheckpointOut out){
            out.ref("home", value.home).i("steps", value.steps);
        }

        @Override
        public void read(Follow value, CheckpointIn in){
            value.home = in.ref("home", Building.class);
            value.steps = in.i("steps");
        }
    }

    @Register
    public static final class ChaosCodec implements ClassCodec<Chaos>{
        @Override
        public Class<Chaos> type(){
            return Chaos.class;
        }

        @Override
        public void write(Chaos value, CheckpointOut out){
            out.bool("enabled", value.enabled).i("placed", value.placed).i("removed", value.removed).i("spawned", value.spawned)
                .i("killed", value.killed).i("fired", value.fired);
        }

        @Override
        public void read(Chaos value, CheckpointIn in){
            value.enabled = in.bool("enabled");
            value.placed = in.i("placed");
            value.removed = in.i("removed");
            value.spawned = in.i("spawned");
            value.killed = in.i("killed");
            value.fired = in.i("fired");
        }
    }

    @Register
    public static final class HeatCodec implements AttachmentCodec<Heat>{
        @Override
        public AttachmentKey<Heat> key(){
            return HEAT;
        }

        @Override
        public void write(Heat value, CheckpointOut out){
            out.i("level", value.level());
        }

        @Override
        public Heat read(CheckpointIn in){
            return new Heat(in.i("level"));
        }
    }

    /** Rebuilds {@link #MARK} from {@link #HEAT}. */
    @Register
    public static final class MarkHook implements RebuildHook{
        @Override
        public void rebuild(World world){
            world.buildings().each(building -> {
                Heat heat = building.attachment(HEAT);
                if(heat != null) building.attach(MARK, heat.mark());
            });
        }
    }

    private Fixtures(){}
}
