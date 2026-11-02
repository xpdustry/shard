package com.xpdustry.shard.server.world;

import com.xpdustry.shard.common.registry.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.entity.*;
import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.world.*;

import java.util.*;

/**
 * The server tests' own content: a crate kind that counts its updates, a fuse that makes a chosen tick throw, and the
 * commands that drive them. All registered, so worlds built from the built-in systems (as {@code shard replay}
 * builds them) have them too.
 */
public final class TestWorlds{
    public static final TestContent CONTENT = new TestContent();

    public static WorldConfig config(long seed){
        return WorldConfig.of(CONTENT, 32, 32, seed);
    }

    public record Block(int id, String name, KindKey kind, int size, float health, boolean update, boolean noUpdateDisabled,
                        boolean hasItems, boolean hasLiquids, boolean hasPower) implements BlockDef{
    }

    public record Drone(int id, String name, float health) implements UnitDef{
    }

    public static final class TestContent implements ContentEpoch{
        public final Block air = new Block(0, "air", KindKey.of("server-test-crate"), 1, 0f, false, false, false, false, false);
        public final Block crate = new Block(1, "crate", KindKey.of("server-test-crate"), 1, 50f, true, false, true, false, false);
        public final Drone drone = new Drone(0, "drone", 10f);

        @Override
        public String hash(){
            return "server-test-1";
        }

        @Override
        public int blocks(){
            return 2;
        }

        @Override
        public BlockDef block(int id){
            return id == 0 ? air : crate;
        }

        @Override
        public int items(){
            return 2;
        }

        @Override
        public int liquids(){
            return 1;
        }

        @Override
        public int units(){
            return 1;
        }

        @Override
        public UnitDef unit(int id){
            return drone;
        }
    }

    @Register
    public static final class Source implements ContentSource{
        @Override
        public ContentEpoch find(String hash){
            return CONTENT.hash().equals(hash) ? CONTENT : null;
        }
    }

    @Kind("server-test-crate")
    public static final class Crate extends Building implements Checkpointable{
        public int count;

        @Override
        public void updateTile(){
            count++;
            if(world().rand().chance(0.01)) health -= 1f;
        }

        @Override
        protected void copyState(Entity into, CopyContext context){
            super.copyState(into, context);
            ((Crate)into).count = count;
        }

        @Override
        public void hashState(Hasher hasher){
            super.hashState(hasher);
            hasher.i(count);
        }

        @Override
        public void write(CheckpointOut out){
            out.i("count", count);
        }

        @Override
        public void read(CheckpointIn in){
            count = in.i("count");
        }
    }

    /** The tick at which {@link FuseHandler} throws, or -1. */
    @Register
    public static final class Fuse implements WorldPart, Checkpointable{
        public long at = -1;

        @Override
        public WorldPart copy(CopyContext context){
            var out = new Fuse();
            out.at = at;
            return out;
        }

        @Override
        public void hash(Hasher hasher){
            hasher.l(at);
        }

        @Override
        public void write(CheckpointOut out){
            out.l("at", at);
        }

        @Override
        public void read(CheckpointIn in){
            at = in.l("at");
        }
    }

    @Register
    public static final class FuseHandler implements PhaseHandler{
        @Override
        public Phase phase(){
            return Phase.BUILDINGS;
        }

        @Override
        public void run(World world){
            long at = world.part(Fuse.class).at;
            if(at == world.ticks()) throw new IllegalStateException("fuse blew at tick " + at);
        }
    }

    public record Arm(long at) implements Command.Admin{
        @Override
        public Verdict apply(World world, Cause cause){
            world.part(Fuse.class).at = at;
            return Verdict.ADMITTED;
        }
    }

    public record Build(int x, int y) implements Command.Admin{
        @Override
        public Verdict apply(World world, Cause cause){
            if(world.buildings().at(world.tiles().pos(x, y)) != null) return Verdict.rejected("occupied");
            world.buildings().create(CONTENT.crate, 1, x, y, 0, cause);
            return Verdict.ADMITTED;
        }
    }

    public static Submission admin(Command command){
        return new Submission(command, new Cause.Admin("test"));
    }

    public static Submission join(long session){
        return new Submission(new Members.Join(session, 0), Cause.SERVER);
    }

    public static Submission leave(long session){
        return new Submission(new Members.Leave(session), Cause.SERVER);
    }

    /** Builds crates on a few tiles. */
    public static List<Submission> crates(int count){
        var out = new ArrayList<Submission>();
        for(int i = 0; i < count; i++) out.add(admin(new Build(2 + i % 28, 2 + i / 28)));
        return out;
    }

    private TestWorlds(){}
}
