package com.xpdustry.shard.sim.blocks.units;

import com.xpdustry.shard.common.geom.*;
import com.xpdustry.shard.common.math.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.sim.ai.*;
import com.xpdustry.shard.sim.ai.command.*;
import com.xpdustry.shard.sim.blocks.payload.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.entity.*;
import com.xpdustry.shard.sim.logic.*;
import com.xpdustry.shard.sim.objectives.*;
import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.tile.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.world.*;

import java.util.*;

/**
 * Vanilla's {@code UnitBlock.UnitBuild} (UnitBlock.java:29), the base of the unit factory and the reconstructor: a
 * payload block holding one unit, with the construction {@link #progress} and the drawing clock ({@link #time},
 * {@link #speedScl}), which a server keeps because the save carries it. Dumping the unit onto the ground sends
 * {@code unitBlockSpawn} ({@link UnitBlockEvent.Spawned}), whose handler ({@link #spawned()}) clears the payload and
 * the progress. It is not a kind.
 *
 * <p>Both kinds also keep a rally point ({@link #commandPos}) and a command for the units they make ({@link #command}),
 * which vanilla declares on each; they share them here, with {@link #commandUnit}, the one place a new unit takes
 * them.
 */
public abstract class UnitBuild extends PayloadBuild<Payload.UnitPayload>{
    /** Vanilla's {@code ContentType.unitCommand} ordinal: the config value a command arrives as. */
    public static final int UNIT_COMMAND = ContentType.VANILLA_NAMES.indexOf("unitCommand");

    public float progress, time, speedScl;
    /** {@code commandPos}: where new units are sent, or null. */
    public Vec2 commandPos;
    /** {@code command}: the command new units take, by the content's command id; -1 for none (the type's default). */
    public int command = -1;

    /**
     * {@code spawned()}: the {@code unitBlockSpawn} handler. The unit left; the next one starts from nothing.
     */
    public void spawned(){
        progress = 0f;
        payload = null;
    }

    /** {@code dumpPayload()}: the unit joins the world where it stands, and everyone hears of it. */
    @Override
    public void dumpPayload(){
        if(payload.dump()){
            // Call.unitBlockSpawn(tile): the handler runs first, then the packet goes out
            spawned();
            world().publish(new UnitBlockEvent.Spawned(tile()));
        }
    }

    @Override
    public Vec2 getCommandPosition(){
        return commandPos;
    }

    /**
     * {@code onCommand(target)}: the rally point, snapped to an own building under it for a command that does
     * ({@code snapToBuilding}). Vanilla keeps the caller's vector; Shard copies it.
     */
    @Override
    public void onCommand(Vec2 target){
        commandPos = new Vec2(target);
        UnitCommand current = UnitCommand.byId(world().content(), command);
        if(current != null && current.snapToBuilding){
            // world.buildWorld(x, y)
            int tx = Math.round(target.x / Footprint.TILE_SIZE), ty = Math.round(target.y / Footprint.TILE_SIZE);
            Building build = world().tiles().inBounds(tx, ty) ? world().buildings().at(world().tiles().pos(tx, ty)) : null;
            if(build != null && build.team == team) commandPos.set(build.x(), build.y());
        }
    }

    /**
     * The seam where a made unit takes this block's rally point and command (UnitFactory.java:432, Reconstructor.java:346):
     * a commandable unit goes to {@link #commandPos} if there is one, then takes {@link #command}, or its type's default
     * when there is none. A command its type lacks changes nothing ({@link CommandAi#command(Unit, int)}).
     */
    protected final void commandUnit(Unit unit){
        if(!(unit.controller instanceof CommandAi ai)) return;
        if(commandPos != null) ai.commandPosition(commandPos);
        int next = command;
        String defaultCommand = unit.params().defaultCommand();
        if(next == -1 && defaultCommand != null) next = world().content().unitCommands().indexOf(defaultCommand);
        ai.command(unit, next);
    }

    /** The made unit's sound, {@code createSound.at(this, 1f + Mathf.range(0.06f), volume)}: its pitch draws. */
    protected final void createSound(){
        Mathf.range(world().rand(), 0.06f);
    }

    /** {@code Events.fire(new UnitCreateEvent(unit, this))}: the game stats count it. */
    protected final void unitCreated(Unit unit){
        ObjectiveState.unitCreated(world(), unit.team);
    }

    // shared rules

    /** {@code team.activateUnitFactories()}: the game is past the team's unit factory activation delay. */
    protected final boolean activateUnitFactories(){
        LogicPart logic = LogicPart.of(world());
        double tick = logic == null ? world().ticks() : logic.tick();
        return tick >= rules().unitActivationDelay(team);
    }

    /** {@code state.rules.unitCost(team)}. */
    protected final float unitCost(){
        return rules().unitCost(team);
    }

    /** {@code state.rules.unitBuildSpeed(team)}. */
    protected final float unitBuildSpeed(){
        return rules().unitBuildSpeed(team);
    }

    /** {@code type.isBanned()}. */
    protected final boolean banned(int type){
        return rules().isUnitBanned(type);
    }

    /**
     * {@code type.unlockedNow()} and {@code unlockedNowHost()} on a server: always outside the campaign; in it, only
     * {@code alwaysUnlocked} content, since a Shard server researches nothing itself.
     */
    protected final boolean unlockedNow(int type){
        if(!UnitCalls.isCampaign(rules())) return true;
        UnitDef def = world().content().unit(type);
        return def.baked() && def.fields().has("alwaysUnlocked") && def.fields().b("alwaysUnlocked");
    }

    /** {@code team.isAI()}. */
    protected final boolean teamIsAI(){
        return UnitCalls.isAI(world(), team);
    }

    /** Whether units of {@code type} may take command {@code command} ({@code type.commands.contains(command)}). */
    protected final boolean hasCommand(int type, int command){
        String name = commandName(command);
        return name != null && world().unitTable().params(world().content().unit(type)).commands().contains(name);
    }

    /** The command's name, or null for none or one the content lacks. */
    protected final String commandName(int command){
        List<String> commands = world().content().unitCommands();
        return command >= 0 && command < commands.size() ? commands.get(command) : null;
    }

    /** The command as a config value ({@code UnitCommand}), or null for none. */
    protected final Object commandConfig(){
        return command == -1 ? null : new ContentRef.Foreign(UNIT_COMMAND, command);
    }

    /**
     * {@code config(UnitCommand.class, …)}: a command by its content id, read as vanilla's {@code content.unitCommand}
     * does: one the content lacks is null, which clears.
     */
    protected final void commandConfigured(ContentRef.Foreign ref){
        if(ref.vanillaType() != UNIT_COMMAND) return;
        command = commandName(ref.id()) == null ? -1 : ref.id();
    }

    /** {@code getMaximumAccepted(item)}: twice the most any recipe takes, scaled by the unit cost. */
    protected final int maximumAccepted(int[] capacities, int item){
        return Mathf.round((item < capacities.length ? capacities[item] : 0) * unitCost());
    }

    /** {@code sense(LAccess.itemCapacity)}: the block's capacity, scaled by the unit cost. */
    protected final double scaledItemCapacity(){
        return Mathf.round(info().itemCapacity * unitCost());
    }

    /** The block's {@code capacities}, by item id, from the baked definition. */
    protected static int[] readCapacities(BlockDef block, int items){
        int[] out = new int[items];
        if(!block.baked() || !block.fields().has("capacities")) return out;
        List<Object> list = block.fields().list("capacities");
        for(int i = 0; i < Math.min(items, list.size()); i++) out[i] = ((Number)list.get(i)).intValue();
        return out;
    }

    @Override
    public BlockStatus status(double stateTick){
        if(!activateUnitFactories()) return BlockStatus.inactiveUnitFactory;
        return super.status(stateTick);
    }

    @Override
    protected void copyState(Entity into, CopyContext context){
        super.copyState(into, context);
        var out = (UnitBuild)into;
        out.progress = progress;
        out.time = time;
        out.speedScl = speedScl;
        out.commandPos = commandPos == null ? null : new Vec2(commandPos);
        out.command = command;
    }

    @Override
    public void hashState(Hasher hasher){
        super.hashState(hasher);
        hasher.f(progress).f(time).f(speedScl).bool(commandPos != null);
        if(commandPos != null) hasher.f(commandPos.x).f(commandPos.y);
        hasher.i(command);
    }

    @Override
    public void write(CheckpointOut out){
        super.write(out);
        out.f("progress", progress).f("time", time).f("speedScl", speedScl).obj("commandPos", commandPos).i("command", command);
    }

    @Override
    public void read(CheckpointIn in){
        super.read(in);
        progress = in.f("progress");
        time = in.f("time");
        speedScl = in.f("speedScl");
        commandPos = in.obj("commandPos", Vec2.class);
        command = in.i("command");
    }
}
