package com.xpdustry.shard.server.session;

import com.xpdustry.shard.sim.world.*;

/**
 * A world a {@link Session} can be routed to, as the session layer sees it: a name for logs, the {@link WorldMailbox}
 * in front of the world's inbox, and what a route offers it as it starts, loads and ends. Never the {@code World}
 * itself, which only its tick thread touches; its host pumps the mailbox right before each tick.
 *
 * <p>The membership commands default to the foundation's {@link Members.Join} and {@link Members.Leave}. A world that
 * keeps more per-session state (a player entity) overrides them with commands that do both in one admission, so a
 * membership change and its state change can't be split.
 */
public interface Destination{
    /** A name for logs and transfer records. */
    String name();

    WorldMailbox mailbox();

    /** The command that routes {@code session} to this world under {@code generation}. */
    default Command join(Session session, int generation){
        return new Members.Join(session.id(), generation);
    }

    /** The command that removes {@code session} from this world. */
    default Command leave(Session session){
        return new Members.Leave(session.id());
    }

    /**
     * The intent of {@code session}'s {@code connectConfirm} for {@code load}: the first envelope of a new route, or,
     * for a {@link Session.Load.Kind#SYNC} load, an intent of the route it reloaded.
     */
    Intent confirm(Session session, Session.Load load);
}
