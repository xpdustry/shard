package com.xpdustry.shard.sim.blocks.logic;

import com.xpdustry.shard.logic.*;
import com.xpdustry.shard.logic.lang.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.entity.*;
import com.xpdustry.shard.sim.logic.*;
import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.rules.*;
import com.xpdustry.shard.sim.world.*;

/**
 * Vanilla's {@code MessageBlock.MessageBuild} (MessageBlock.java): messages, reinforced messages and the world message.
 * A {@code String} config sets the text (trimmed, at most {@code maxNewlines + 1} line breaks kept, refused past
 * {@code maxTextLength} or when not {@code accessible()}); {@code printflush} replaces it with the processor's text,
 * cut at {@code maxTextLength}; {@code read} gives its characters. A world message can't be damaged.
 *
 * <p>Not ported: bullet collisions of world messages (WP41).
 */
@Kind("message-block")
public final class MessageBlock extends Building implements Checkpointable, LogicReadable, LogicPrintable{
    static final Configs<MessageBlock> CONFIGS = Configs.of(MessageBlock.class).on(String.class, MessageBlock::setText);

    public final StringBuilder message = new StringBuilder();

    private int maxTextLength(){
        return block().baked() ? block().fields().i("maxTextLength") : 400;
    }

    private int maxNewlines(){
        return block().baked() ? block().fields().i("maxNewlines") : 24;
    }

    /** {@code accessible()}: a world message is only editable in the editor or when the rules allow it. */
    public boolean accessible(){
        Rules rules = rules();
        return !block().privileged() || rules.editor || rules.allowEditWorldProcessors;
    }

    /** The {@code String} config (MessageBlock.java:39). */
    private void setText(String text){
        if(text.length() > maxTextLength() || !accessible()) return;
        message.setLength(0);
        text = text.trim();
        int count = 0, newlines = maxNewlines();
        for(int i = 0; i < text.length(); i++){
            char c = text.charAt(i);
            if(c == '\n'){
                if(count++ <= newlines) message.append('\n');
            }else{
                message.append(c);
            }
        }
    }

    @Override
    public boolean readable(Processor exec){
        return isValid();
    }

    @Override
    public void read(Processor exec, int position, int output){
        int address = exec.numi(position);
        exec.setnum(output, address < 0 || address >= message.length() ? Double.NaN : message.charAt(address));
    }

    @Override
    public boolean printable(Processor exec){
        return isValid() && (exec.privileged || (team == exec.team() && !block().privileged()));
    }

    @Override
    public void print(CharSequence text){
        message.setLength(0);
        message.append(text, 0, Math.min(text.length(), maxTextLength()));
    }

    @Override
    public double sense(LAccess sensor){
        if(sensor == LAccess.bufferSize) return message.length();
        return super.sense(sensor);
    }

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
        return message.toString();
    }

    @Override
    public void write(CheckpointOut out){
        out.str("message", message.toString());
    }

    @Override
    public void read(CheckpointIn in){
        message.setLength(0);
        message.append(in.str("message"));
    }

    @Override
    protected void copyState(Entity into, CopyContext context){
        super.copyState(into, context);
        ((MessageBlock)into).message.append(message);
    }

    @Override
    public void hashState(Hasher hasher){
        super.hashState(hasher);
        hasher.str(message.toString());
    }

    /** {@code canPickup()}: payload units may lift it never. */
    @Override
    public boolean canPickup(){
        return false;
    }
}
