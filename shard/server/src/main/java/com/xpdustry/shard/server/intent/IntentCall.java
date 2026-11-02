package com.xpdustry.shard.server.intent;

import com.xpdustry.shard.server.sync.*;
import com.xpdustry.shard.sim.world.*;
import com.xpdustry.shard.wire.*;

/**
 * A Call an intent's admission sends, as the wire packet vanilla sends and with its exact recipients, published in
 * vanilla's order and delivered after the tick commits. Admission never touches a connection: the four Calls are
 * {@link Outbound}, which the world's sync sends to their {@link Audience} as they come, with the tick's other Calls
 * and held for players still loading (WP25). A {@link Kick} closes a connection with a cooldown, which only
 * {@link Intents#deliver} can do.
 *
 * <p>Recipients follow the generated {@code Call} (CallGenerator.java:328): a server's own Call goes to everyone; the
 * forward of an admitted remote call goes to everyone but its sender when the sender already ran it
 * ({@code called} includes the client), else to everyone, sender included ({@link Forwards}).
 *
 * <p>Values in the packets are wire values ({@code WireObjects}), and entity references are entity ids or positions,
 * so delivering one reads nothing from the world but its content.
 */
public sealed interface IntentCall extends IntentEvent{
    /** For one session: a correction ({@code setPosition}), a rollback ({@code removeQueueBlock}, the old config), a reply. */
    record To(long session, Packet packet) implements IntentCall, Outbound{
        @Override
        public Audience audience(){
            return Audience.only(session);
        }
    }

    /** For every player in the world: a server Call ({@code takeItems}), or a forward its sender didn't run. */
    record All(Packet packet) implements IntentCall, Outbound{
        @Override
        public Audience audience(){
            return Audience.ALL;
        }
    }

    /** For every player but {@code session}'s: the forward of a call its sender already ran ({@code net.sendExcept}). */
    record AllExcept(long session, Packet packet) implements IntentCall, Outbound{
        @Override
        public Audience audience(){
            return Audience.except(session);
        }
    }

    /** For the players of {@code team} but {@code except}'s: a builder's plan previews. */
    record Team(int team, long except, Packet packet) implements IntentCall, Outbound{
        @Override
        public Audience audience(){
            return new Audience.Team(team, except);
        }
    }

    /** Vanilla's {@code player.kick(reason, duration)}: the reason, then the connection closes, and a cooldown. */
    record Kick(long session, String reason, long banMillis) implements IntentCall{
    }
}
