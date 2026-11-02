package com.xpdustry.shard.server.connect;

import com.xpdustry.shard.server.admin.*;
import com.xpdustry.shard.server.session.*;

import java.util.*;
import java.util.concurrent.atomic.*;

/**
 * The admitted sessions, as the connect flow's binding checks see them: how many there are, and, for strict mode, their
 * names, UUIDs and USIDs. {@link Admission} checks a connect against a snapshot, in vanilla's order, and two connects
 * can both pass it; {@link #reserve} then checks the player limit and the duplicates again and takes the place in one
 * step, so only one of them gets it. The place goes back exactly once, when the session closes or the connect fails.
 *
 * <p>Thread-safe.
 */
final class Ledger{
    /** Guarded by this: how many admitted sessions hold each key. */
    private final HashMap<String, Integer> names = new HashMap<>(), uuids = new HashMap<>(), usids = new HashMap<>();
    private int size;

    /** What {@link #reserve} decided. */
    sealed interface Reservation{
    }

    /** A place to give back with {@link #release}. */
    final class Ticket implements Reservation{
        private final String name, uuid, usid;
        private final AtomicBoolean released = new AtomicBoolean();

        private Ticket(String name, String uuid, String usid){
            this.name = name;
            this.uuid = uuid;
            this.usid = usid;
        }

        /** Safe to call again, from any thread. */
        void release(){
            if(released.compareAndSet(false, true)) Ledger.this.release(this);
        }
    }

    /** No place: kick with {@code reason}. */
    record Refused(KickReason reason, String detail) implements Reservation{
    }

    /** Admitted sessions not yet closed: what {@code net.player-limit} counts. */
    synchronized int size(){
        return size;
    }

    /**
     * Takes a place for {@code identity} unless the server is full ({@code limit} > 0; an admin passes) or, in strict
     * mode, another admitted session has its name, UUID or USID: vanilla's {@code playerLimit}, {@code nameInUse}
     * and {@code idInUse}, in its order.
     */
    synchronized Reservation reserve(Identity identity, boolean admin, boolean strict, int limit){
        if(limit > 0 && size >= limit && !admin) return new Refused(KickReason.PLAYER_LIMIT, "player limit " + limit);
        String name = Names.stripColors(identity.name()).trim().toLowerCase(Locale.ROOT);
        if(strict){
            if(names.containsKey(name)) return new Refused(KickReason.NAME_IN_USE, "name " + name);
            if(uuids.containsKey(identity.uuid()) || usids.containsKey(String.valueOf(identity.usid()))){
                return new Refused(KickReason.ID_IN_USE, "uuid " + identity.uuid());
            }
        }
        var ticket = new Ticket(name, identity.uuid(), String.valueOf(identity.usid()));
        names.merge(ticket.name, 1, Integer::sum);
        uuids.merge(ticket.uuid, 1, Integer::sum);
        usids.merge(ticket.usid, 1, Integer::sum);
        size++;
        return ticket;
    }

    private synchronized void release(Ticket ticket){
        names.computeIfPresent(ticket.name, (key, count) -> count == 1 ? null : count - 1);
        uuids.computeIfPresent(ticket.uuid, (key, count) -> count == 1 ? null : count - 1);
        usids.computeIfPresent(ticket.usid, (key, count) -> count == 1 ? null : count - 1);
        size--;
    }
}
