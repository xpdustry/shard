package com.xpdustry.shard.sim.team;

import com.xpdustry.shard.common.io.*;
import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.world.*;

/**
 * One of a team's rebuild plans (vanilla {@code Teams.BlockPlan}): a block the team lost, to build again at
 * ({@link #x}, {@link #y}) with its rotation and config. Plans are made when a building is destroyed and dropped once
 * something is built over them ({@code sim.build.RebuildPlans}); {@link #removed} tells an AI holding one that it went.
 *
 * <p>{@link Persistence#AUTHORITATIVE}.
 */
public final class BlockPlan implements Checkpointable{
    public int x, y, rotation;
    /** The block's content id. */
    public int block;
    /** The config to build it with, as {@link BuildPlan#config} holds one: frozen when the plan is made. */
    public Object config;
    public boolean removed;

    /** A blank plan for a checkpoint to fill. */
    public BlockPlan(){
    }

    /** {@code new BlockPlan(x, y, rotation, block, config)}: vanilla keeps x, y and rotation as shorts. */
    public BlockPlan(int x, int y, int rotation, int block, Object config){
        this.x = (short)x;
        this.y = (short)y;
        this.rotation = (short)rotation;
        this.block = block;
        this.config = BuildPlan.copyConfig(config);
    }

    BlockPlan copy(){
        var out = new BlockPlan(x, y, rotation, block, config);
        out.removed = removed;
        return out;
    }

    void hash(Hasher hasher){
        hasher.i(x).i(y).i(rotation).i(block).bool(removed);
        BuildPlan.hashConfig(config, hasher);
    }

    /** The config is stored as {@link BuildPlan}'s tagged bytes, since its type varies. */
    @Override
    public void write(CheckpointOut out){
        var config = new Writes(16);
        BuildPlan.writeConfig(this.config, config);
        out.i("x", x).i("y", y).i("rotation", rotation).i("block", block).bytes("config", config.buffer(), config.size())
            .bool("removed", removed);
    }

    @Override
    public void read(CheckpointIn in){
        x = in.i("x");
        y = in.i("y");
        rotation = in.i("rotation");
        block = in.i("block");
        var config = new Reads(in.bytes("config"));
        try{
            this.config = BuildPlan.readConfig(config);
        }catch(RuntimeException e){
            throw new CheckpointException("a rebuild plan's config is corrupt", e);
        }
        removed = in.bool("removed");
    }

    @Override
    public String toString(){
        return "BlockPlan{x=" + x + ", y=" + y + ", rotation=" + rotation + ", block=" + block + ", config=" + config + "}";
    }
}
