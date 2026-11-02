package com.xpdustry.shard.sim.blocks.logic;

import com.xpdustry.shard.sim.building.*;

/**
 * Vanilla's {@code SwitchBlock.SwitchBuild} (SwitchBlock.java): the switch and the world switch. Its state is the
 * building's {@code enabled}, which a {@code Boolean} config sets and processors read and set; its block doesn't
 * {@code autoResetEnabled}, so unlinking it leaves it as it is. A world switch can't be damaged. It has no fields of
 * its own: its save writes {@code enabled} again, after the base.
 */
@Kind("switch-block")
public final class SwitchBlock extends Building{
    static final Configs<SwitchBlock> CONFIGS = Configs.of(SwitchBlock.class).on(Boolean.class, (build, on) -> build.enabled = on);

    @Override
    public void damage(float damage){
        if(block().privileged()) return;
        super.damage(damage);
    }

    @Override
    protected Configs<?> configs(){
        return CONFIGS;
    }

    @Override
    public Object config(){
        return enabled;
    }

    /** {@code canPickup()}: payload units may lift it unless it is a world block. */
    @Override
    public boolean canPickup(){
        return !block().privileged();
    }
}
