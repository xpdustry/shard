package com.xpdustry.shard.server.session;

import com.xpdustry.shard.net.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * Every live session, by id and by connection. Session ids start at 1, grow and are never reused while the server
 * runs, so a stale id in an old envelope can't name a new session. A closed session stays until every world it was
 * leaving has taken its leave, so {@link #size()} counts what worlds still hold. Thread-safe.
 */
public final class Sessions{
    private final AtomicLong ids = new AtomicLong();
    private final ConcurrentHashMap<Long, Session> byId = new ConcurrentHashMap<>();

    /**
     * A new session for {@code conn}, attached to it. {@link Conn#attach} is atomic with the connection's close, so
     * either the handler's {@code disconnected} will see this session, or there is no session.
     *
     * @return null if the connection is closed or already has a session
     */
    public Session open(Conn conn, Identity identity){
        var session = new Session(this, ids.incrementAndGet(), conn, identity);
        byId.put(session.id(), session);
        if(conn.attach(session)) return session;
        byId.remove(session.id(), session);
        return null;
    }

    /** The session of {@code conn}, or null before its connect packet. */
    public Session of(Conn conn){
        return conn.attachment() instanceof Session session ? session : null;
    }

    public Session get(long id){
        return byId.get(id);
    }

    /**
     * For the handler's {@code disconnected}: closes the connection's session, if it has one ({@link Session#close()}).
     *
     * @return the closed session, or null
     */
    public Session disconnected(Conn conn){
        Session session = of(conn);
        if(session != null) session.close();
        return session;
    }

    /** From a session that closed and has no leave left to deliver. */
    void forget(Session session){
        byId.remove(session.id(), session);
    }

    public int size(){
        return byId.size();
    }

    /** A snapshot of the live sessions, in id order. */
    public List<Session> all(){
        var out = new ArrayList<>(byId.values());
        out.sort(Comparator.comparingLong(Session::id));
        return out;
    }
}
