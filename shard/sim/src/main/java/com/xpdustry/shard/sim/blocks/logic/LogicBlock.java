package com.xpdustry.shard.sim.blocks.logic;

import com.xpdustry.shard.common.geom.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.logic.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.entity.*;
import com.xpdustry.shard.sim.logic.*;
import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.rules.*;
import com.xpdustry.shard.sim.tile.*;
import com.xpdustry.shard.sim.world.*;

import java.util.*;

/**
 * Vanilla's {@code LogicBlock.LogicBuild} (LogicBlock.java): the micro, logic, hyper and world processors. The mlog
 * state and its turn are the {@link Processor}'s; this kind runs it in its turn of the building update, as vanilla's
 * {@code updateTile} does, and gives it vanilla's link rule ({@link #validLink}), configs, permissions and save hooks.
 *
 * <ul>
 * <li>Each turn: the processor takes the building's team, follows its links if a tile they name changed, then runs
 * with the building's {@code edelta()} unless it is disabled, or a world processor while the rules disable them.</li>
 * <li>Configs ({@code accessible()} first, which a world processor is only in the editor or with
 * {@code allowEditWorldProcessors}): code and links as {@code byte[]}, links relative; a world processor's tag
 * ({@code String}) and icon ({@code Character}); a building position ({@code Integer}) links or unlinks it, and
 * unlinking re-enables a target this processor disabled if its block resets ({@code autoResetEnabled}).</li>
 * <li>A save's processor is loaded where the building is read ({@link Processor#load}) and finished in
 * {@link #afterReadAll()}.</li>
 * <li>Leaving its tile re-enables the links it disabled (LogicBlock.java:452), after the neighbours' proximity
 * updates, and stops watching them.</li>
 * </ul>
 *
 * <p>State: the processor, stored as {@link LogicData} ({@link Processor#writeState}), restored in
 * {@link #restored()} once the world's {@link LogicPart} is.
 *
 * <p>Not ported: bullet collisions of world processors
 * (bullets are WP41's), and the editor's drawing.
 */
@Kind("logic-block")
public final class LogicBlock extends Building implements Checkpointable, ProcessorBuilding, LogicReadable, LogicWritable{
    private static final KindKey LOGIC_BLOCK = KindKey.of("logic-block");
    /** {@code LogicBlock.maxNameLength}. */
    public static final int MAX_NAME_LENGTH = 32;

    static final Configs<LogicBlock> CONFIGS = Configs.of(LogicBlock.class)
        .on(byte[].class, (build, data) -> {
            if(build.accessible()) build.processor.configure(data);
        })
        .on(String.class, (build, tag) -> {
            if(build.accessible() && build.privileged() && tag.length() < MAX_NAME_LENGTH) build.processor.tag = tag;
        })
        .on(Character.class, (build, icon) -> {
            if(build.accessible() && build.privileged()) build.processor.iconTag = icon;
        })
        .on(Integer.class, (build, pos) -> {
            if(!build.accessible()) return;
            Building target = build.world().buildings().at(Point2.x(pos), Point2.y(pos));
            // if there is no valid link in the first place, nobody cares
            if(!build.validLink(target)) return;
            if(build.processor.toggleLink(target) && autoResetEnabled(target.block()) && target.lastDisabler == build){
                target.enabled = true;
            }
        });

    /** Stored as {@link LogicData}, and rebuilt from it in {@link #restored()}. */
    private Processor processor;
    @Derived("a checkpoint's processor between read() and restored(); null otherwise")
    private LogicData restoring;
    @Derived("the block's instructionsPerTick, maxInstructionsPerTick and range, read on first use")
    private int ipt = -1, maxIpt;
    @Derived("read with ipt")
    private float range;

    @Override
    public void created(){
        processor = newProcessor();
    }

    private Processor newProcessor(){
        params();
        return new Processor(LogicPart.require(world()).logic(), this, privileged(), ipt, maxIpt, pos(), thisx(), thisy());
    }

    /** {@code World.conv(x)}: {@code @thisx}. */
    private double thisx(){
        return x() / Footprint.TILE_SIZE;
    }

    private double thisy(){
        return y() / Footprint.TILE_SIZE;
    }

    private void params(){
        if(ipt != -1) return;
        BlockDef block = block();
        if(block.baked()){
            ipt = block.fields().i("instructionsPerTick");
            maxIpt = block.fields().i("maxInstructionsPerTick");
            range = block.fields().f("range");
        }else{
            ipt = 1;
            maxIpt = 40;
            range = 80f;
        }
    }

    @Override
    public Processor processor(){
        return processor;
    }

    /** {@code block.privileged}: a world processor. */
    public boolean privileged(){
        return block().privileged();
    }

    /** {@code LogicBlock.accessible()}: a world processor is only editable in the editor, or when the rules allow it. */
    public boolean accessible(){
        Rules rules = rules();
        return !privileged() || rules.editor || rules.allowEditWorldProcessors;
    }

    @Override
    public float range(){
        params();
        return range;
    }

    @Override
    public void updateTile(){
        processor.team(team);
        Rules rules = rules();
        processor.update(enabled && !(rules.disableWorldProcessors && privileged()), edelta());
    }

    @Override
    public void afterReadAll(){
        processor.checkReadCode();
    }

    /**
     * LogicBlock.java:452: links this processor disabled are enabled again when it leaves its tile, after its
     * neighbours heard of it.
     */
    @Override
    public void afterProximityRemoved(){
        for(int i = 0; i < processor.linkCount(); i++){
            if(processor.link(i) instanceof Building link && !link.enabled && link.lastDisabler == this) link.enabled = true;
        }
        processor.dispose();
    }

    /** Editor-only processors can't be damaged. */
    @Override
    public void damage(float damage){
        if(!privileged() || block().destructible()) super.damage(damage);
    }

    @Override
    public boolean validLink(Building other){
        if(other == null || !other.isValid() || WorldHost.constructing(other)) return false;
        Rules rules = rules();
        boolean privileged = privileged();
        if(privileged && !rules.worldProcessorPlayerLink && other.team == rules.defaultTeam) return false;
        if(privileged) return true;
        return !other.block().privileged() && other.team == team
            && other.within(x(), y(), range() + other.block().size() * Footprint.TILE_SIZE / 2f);
    }

    /** {@code readable(exec)}: same team and not privileged, unless the reader is a world processor. */
    @Override
    public boolean readable(Processor exec){
        return isValid() && (exec.privileged || (team == exec.team() && !block().privileged()));
    }

    @Override
    public void read(Processor exec, int position, int output){
        processor.externalRead(exec, position, output);
    }

    @Override
    public boolean writable(Processor exec){
        return readable(exec);
    }

    @Override
    public void write(Processor exec, int position, int value){
        processor.externalWrite(exec, position, value);
    }

    @Override
    protected Configs<?> configs(){
        return CONFIGS;
    }

    /** A client's code and links ({@code byte[]}) must expand within the bounds and the tick's budget; only an accessible processor reads them. */
    @Override
    public String admitConfig(Object value){
        return value instanceof byte[] data && accessible() ? LogicPart.require(world()).admitConfig(data) : null;
    }

    /** {@code config()}: the code and links, relative to this processor. */
    @Override
    public Object config(){
        var links = new ArrayList<LogicConfig.Link>();
        int x = tileX(), y = tileY();
        for(Processor.Link link : processor.links()) links.add(new LogicConfig.Link(link.name(), link.x - x, link.y - y));
        return LogicConfig.compress(processor.code(), links);
    }

    static boolean autoResetEnabled(BlockDef block){
        return !block.baked() || !block.fields().has("autoResetEnabled") || block.fields().b("autoResetEnabled");
    }

    /** Whether {@code building} is a processor (which {@code control config} won't copy). */
    static boolean isProcessor(Building building){
        return building.block().baked() && building.block().isA(LOGIC_BLOCK);
    }

    @Override
    public void write(CheckpointOut out){
        out.obj("processor", processor == null ? null : LogicData.of(processor::writeState));
    }

    @Override
    public void read(CheckpointIn in){
        restoring = in.obj("processor", LogicData.class);
    }

    @Override
    public void restored(){
        if(restoring == null) throw new CheckpointException(this + " was stored without its processor");
        params();
        processor = Processor.readState(LogicPart.require(world()).logic(), this, privileged(), ipt, maxIpt, pos(), thisx(), thisy(),
            restoring.reader());
        restoring = null;
    }

    @Override
    protected void copyState(Entity into, CopyContext context){
        super.copyState(into, context);
        var out = (LogicBlock)into;
        out.ipt = ipt;
        out.maxIpt = maxIpt;
        out.range = range;
        out.processor = processor.copy(LogicPart.require(context.world()).logic(), out, LogicData.remap(context));
    }

    @Override
    public void hashState(Hasher hasher){
        super.hashState(hasher);
        processor.writeState(new LogicPart.HashOut(hasher));
    }

    /** {@code canPickup()}: payload units may lift it never. */
    @Override
    public boolean canPickup(){
        return false;
    }
}
