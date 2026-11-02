package com.xpdustry.shard.server.sync;

/**
 * Who in a world gets a frame: the {@link Outbox}'s visibility classes (ARCHITECTURE.md §8). Global, team and
 * visible-at-point are the Call classes; snapshots are filtered per group of viewers that see the same bytes
 * ({@link Outbox#snapshotGroups()}). {@link Only} and {@link Except} are the per-connection forms vanilla's
 * {@code Call.x(con, ...)} and forwarded client Calls use.
 */
public sealed interface Audience{
    Audience ALL = new All();

    /** Whether the viewer of {@code session}, on {@code team}, is in it. */
    boolean includes(long session, int team);

    static Audience team(int team){
        return new Team(team, -1);
    }

    static Audience only(long session){
        return new Only(session);
    }

    static Audience except(long session){
        return new Except(session);
    }

    /** Every viewer in the world. */
    record All() implements Audience{
        @Override
        public boolean includes(long session, int team){
            return true;
        }
    }

    /** The viewers on {@code team}, but {@code except}'s session ({@code -1} for none). */
    record Team(int team, long except) implements Audience{
        @Override
        public boolean includes(long session, int team){
            return team == this.team && session != except;
        }
    }

    /**
     * The viewers that can see the point, in world units. Without fog every viewer can (vanilla sends effects to all);
     * fog (WP73) narrows it to the teams that see it.
     */
    record At(float x, float y) implements Audience{
        @Override
        public boolean includes(long session, int team){
            return true;
        }
    }

    record Only(long session) implements Audience{
        @Override
        public boolean includes(long session, int team){
            return session == this.session;
        }
    }

    /** Everyone but {@code session}: a client's Call the server ran and forwards. */
    record Except(long session) implements Audience{
        @Override
        public boolean includes(long session, int team){
            return session != this.session;
        }
    }
}
