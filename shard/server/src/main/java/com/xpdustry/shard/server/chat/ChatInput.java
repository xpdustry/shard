package com.xpdustry.shard.server.chat;

import com.xpdustry.shard.common.registry.*;
import com.xpdustry.shard.sim.entity.*;
import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.world.*;

/**
 * What the chat filters remember about one player, attached to its {@link Player}: the parts of vanilla's
 * {@code PlayerInfo} that the anti-spam filter reads ({@code lastMessageTime}, {@code lastSentMessage},
 * {@code messageInfractions}). Times are the lines' recorded arrivals, in milliseconds. Vanilla keeps them per uuid
 * across reconnects; Shard per player, as W2.14's {@code PlayerInput} keeps the interaction window.
 *
 * <p>{@link Persistence#AUTHORITATIVE}: a fork or a replay filters the same lines the same way.
 */
public final class ChatInput{
    public static final AttachmentKey<ChatInput> KEY = AttachmentKey.create("server.chat.chat-input", ChatInput.class,
        Persistence.AUTHORITATIVE, (input, context) -> input.copy(), ChatInput::hash);

    /** {@code lastMessageTime}; meaningless until {@link #sent}. Vanilla's starts at 0, so its first line is never too soon. */
    public long lastMessageTime;
    public boolean sent;
    /** {@code lastSentMessage}, or null. */
    public String lastSentMessage;
    /** {@code messageInfractions}. */
    public int infractions;

    public ChatInput(){
    }

    /** {@code player}'s, attached now if it has none. */
    public static ChatInput of(Player player){
        ChatInput input = player.attachment(KEY);
        if(input == null){
            input = new ChatInput();
            player.attach(KEY, input);
        }
        return input;
    }

    /** {@code Time.timeSinceMillis(lastMessageTime)} at {@code now}: as good as forever before the first line. */
    public long since(long now){
        return sent ? now - lastMessageTime : Long.MAX_VALUE;
    }

    ChatInput copy(){
        var out = new ChatInput();
        out.lastMessageTime = lastMessageTime;
        out.sent = sent;
        out.lastSentMessage = lastSentMessage;
        out.infractions = infractions;
        return out;
    }

    void hash(Hasher hasher){
        hasher.l(lastMessageTime).bool(sent).bool(lastSentMessage != null);
        if(lastSentMessage != null) hasher.str(lastSentMessage);
        hasher.i(infractions);
    }

    @Register
    public static final class Codec implements AttachmentCodec<ChatInput>{
        @Override
        public AttachmentKey<ChatInput> key(){
            return KEY;
        }

        @Override
        public void write(ChatInput value, CheckpointOut out){
            out.l("lastMessageTime", value.lastMessageTime).bool("sent", value.sent).str("lastSentMessage", value.lastSentMessage)
                .i("infractions", value.infractions);
        }

        @Override
        public ChatInput read(CheckpointIn in){
            var input = new ChatInput();
            input.lastMessageTime = in.l("lastMessageTime");
            input.sent = in.bool("sent");
            input.lastSentMessage = in.str("lastSentMessage");
            input.infractions = in.i("infractions");
            return input;
        }
    }
}
