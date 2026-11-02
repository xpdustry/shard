package com.xpdustry.shard.server.chat;

import com.xpdustry.shard.server.host.*;
import com.xpdustry.shard.server.intent.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.world.*;
import com.xpdustry.shard.wire.v160_7.Packets;

/**
 * {@code sendChatMessage(player, message)}'s admission (NetClient.java:333-392), on the world thread: the speaker must
 * be playing and not kicked; a line with the command prefix is a command, any other runs through the
 * {@link ChatFilters} and goes out formatted. {@code /t}'s message runs through them too, as vanilla's {@code /t}
 * calls {@code filterMessage}: here, at admission, where the filters' state is world state. The text has already been
 * checked against {@code maxTextLength} and lost its line breaks when the packet arrived.
 */
public record ChatLine(ChatPolicy policy, String text) implements Intent{
    private static final System.Logger LOG = System.getLogger(ChatLine.class.getName());

    @Override
    public Verdict apply(World world, IntentEnvelope envelope){
        try{
            return admit(world, envelope);
        }catch(RuntimeException e){
            // every input is checked as vanilla checks it: a failure here is Shard's bug, not the client's
            LOG.log(System.Logger.Level.ERROR, "a chat line failed: " + this, e);
            return Verdict.rejected("failed: " + e);
        }
    }

    private Verdict admit(World world, IntentEnvelope envelope){
        Roster roster = Roster.of(world);
        Player player = roster == null ? null : roster.player(envelope.session());
        if(player == null || !player.isAdded()) return Verdict.rejected("not playing");
        if(PlayerInput.of(player).kicked) return Verdict.rejected("kicked");
        var context = new Context(world, envelope, player, policy);
        if(text.startsWith(Chat.PREFIX)){
            String line = text;
            String team = teamMessage(text);
            if(team != null){
                String filtered = ChatFilters.builtIn().filter(team, context);
                if(filtered == null) return Verdict.rejected("filtered");
                line = Chat.PREFIX + Chat.TEAM + " " + filtered;
            }
            world.publish(new ChatEvent.Commanded(envelope.session(), player.id(), line));
            return Verdict.ADMITTED;
        }
        String message = ChatFilters.builtIn().filter(text, context);
        if(message == null) return Verdict.rejected("filtered");
        world.publish(new ChatEvent.Said(envelope.session(), player.id(), Chat.plain(player.name), Chat.format(player, message), message));
        return Verdict.ADMITTED;
    }

    /** {@code /t}'s {@code <message...>} as Arc's handler reads it, or null if the line isn't a {@code /t} with one. */
    static String teamMessage(String line){
        String rest = line.substring(Chat.PREFIX.length());
        int space = rest.indexOf(' ');
        if(space < 0 || !rest.substring(0, space).equals(Chat.TEAM)) return null;
        String message = rest.substring(space + 1);
        return message.isEmpty() ? null : message;
    }

    /** The admission as the filters see it. */
    private record Context(World world, IntentEnvelope envelope, Player player, ChatPolicy policy) implements ChatContext{
        @Override
        public ChatInput input(){
            return ChatInput.of(player);
        }

        @Override
        public long arrivalMillis(){
            return Math.floorDiv(envelope.arrivalNanos(), 1_000_000L);
        }

        @Override
        public void message(String text){
            world.publish(new IntentCall.To(envelope.session(), new Packets.SendMessage(text)));
        }

        @Override
        public void kick(String reason, long banMillis){
            PlayerInput input = PlayerInput.of(player);
            if(input.kicked) return;
            input.kicked = true;
            world.publish(new IntentCall.Kick(envelope.session(), reason, banMillis));
        }
    }
}
