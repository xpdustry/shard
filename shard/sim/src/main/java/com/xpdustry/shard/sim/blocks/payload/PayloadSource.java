package com.xpdustry.shard.sim.blocks.payload;

import com.xpdustry.shard.sim.ai.CommandAi;
import com.xpdustry.shard.common.geom.*;
import com.xpdustry.shard.common.math.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.sim.ai.*;
import com.xpdustry.shard.sim.build.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.objectives.*;
import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.rules.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.world.*;

/**
 * Vanilla's {@code PayloadSource.PayloadSourceBuild} (PayloadSource.java:104), the sandbox's payload source: whenever it
 * holds nothing it makes a new payload of its configured unit type or block, and moves it out of its front. A new unit
 * takes the rally point ({@code commandPos}) if its controller is a command AI. A config that changes what it makes
 * drops what it holds.
 *
 * <p>State, by vanilla's names: {@code unit} and {@code configBlock} (content ids, -1 for none), {@code commandPos}
 * (null for none) and {@code scl}, which only draws but is kept. Saved at revision 1: the payload block's body, then the
 * unit type, the block and the rally point. {@code UnitCreateEvent} counts {@code unitsCreated} (Logic.java:228,
 * {@code ObjectiveState.unitCreated}). Vanilla's {@code removed} content flag is never set for v160.7 content, so it isn't checked.
 */
@Kind("payload-source")
public final class PayloadSource extends PayloadBuild<Payload>{
    static final Configs<PayloadSource> CONFIGS = Configs.of(PayloadSource.class)
        .on(ContentType.BLOCK, (build, block) -> {
            if(build.canProduceBlock(block) && build.configBlock != block){
                build.configBlock = block;
                build.unit = -1;
                build.payload = null;
                build.scl = 0f;
            }
        })
        .on(ContentType.UNIT, (build, unit) -> {
            if(build.canProduceUnit(unit) && build.unit != unit){
                build.unit = unit;
                build.configBlock = -1;
                build.payload = null;
                build.scl = 0f;
            }
        })
        .clear(build -> {
            build.configBlock = -1;
            build.unit = -1;
            build.payload = null;
            build.scl = 0f;
        });

    /** The unit type it makes, or -1. */
    public int unit = -1;
    /** The block it makes, or -1. */
    public int configBlock = -1;
    /** {@code commandPos}: where new units are sent, or null. */
    public Vec2 commandPos;
    public float scl;

    /** {@code canProduce(Block)}: visible, smaller than this block, carriable, not banned, and buildable here. */
    public boolean canProduceBlock(int block){
        BlockDef def = world().content().block(block);
        PlaceInfo info = PayloadCosts.place(world(), block);
        return Build.isVisible(world(), info) && def.size() < block().size() && def.allowedInPayloads()
            && !rules().isBlockBanned(block) && Build.environmentBuildable(world(), info);
    }

    /** {@code canProduce(UnitType)}: not hidden, not banned, and able to live in the rules' environment. */
    public boolean canProduceUnit(int unit){
        UnitDef type = world().content().unit(unit);
        Rules rules = rules();
        int env = rules.env;
        boolean supportsEnv = (type.envEnabled() & env) != 0 && (type.envDisabled() & env) == 0
            && (type.envRequired() == 0 || (type.envRequired() & env) == type.envRequired());
        return !type.fields().b("hidden") && !rules.isUnitBanned(unit) && supportsEnv;
    }

    /** {@code getCommandPosition()}. */
    @Override
    public Vec2 getCommandPosition(){
        return commandPos;
    }

    /** {@code onCommand(target)}: the rally point for the units it makes. */
    @Override
    public void onCommand(Vec2 target){
        commandPos = target;
    }

    @Override
    protected Configs<?> configs(){
        return CONFIGS;
    }

    /** {@code config()}: the unit type, else the block, else null. */
    @Override
    public Object config(){
        if(unit != -1) return new ContentRef(ContentType.UNIT, unit);
        return configBlock == -1 ? null : ContentRef.block(configBlock);
    }

    @Override
    public boolean acceptPayload(Building source, Payload payload){
        return false;
    }

    @Override
    public void updateTile(){
        super.updateTile();
        if(payload == null){
            scl = 0f;
            if(unit != -1){
                Unit made = Unit.create(world(), world().content().unit(unit), team);
                payload = new Payload.UnitPayload(made);
                if(commandPos != null && made.isCommandable()){
                    ((CommandAi)made.controller).commandPosition(commandPos);
                }
                // Events.fire(new UnitCreateEvent(p, this)): the game stats count the default team's
                ObjectiveState.unitCreated(world(), team);
            }else if(configBlock != -1){
                payload = new Payload.BuildPayload(world().buildings().createPayload(world().content().block(configBlock), team));
            }
            payVector.setZero();
            payRotation = rotdeg();
        }
        scl = Mathf.lerpDelta(scl, 1f, 0.1f);
        moveOutPayload();
    }

    @Override
    protected void copyState(com.xpdustry.shard.sim.entity.Entity into, CopyContext context){
        super.copyState(into, context);
        var out = (PayloadSource)into;
        out.unit = unit;
        out.configBlock = configBlock;
        out.commandPos = commandPos == null ? null : commandPos.cpy();
        out.scl = scl;
    }

    @Override
    public void hashState(Hasher hasher){
        super.hashState(hasher);
        hasher.i(unit).i(configBlock).bool(commandPos != null);
        if(commandPos != null) hasher.f(commandPos.x).f(commandPos.y);
        hasher.f(scl);
    }

    @Override
    public void write(CheckpointOut out){
        super.write(out);
        out.i("unit", unit).i("configBlock", configBlock).obj("commandPos", commandPos).f("scl", scl);
    }

    @Override
    public void read(CheckpointIn in){
        super.read(in);
        unit = in.i("unit");
        configBlock = in.i("configBlock");
        commandPos = in.obj("commandPos", Vec2.class);
        scl = in.f("scl");
    }
}
