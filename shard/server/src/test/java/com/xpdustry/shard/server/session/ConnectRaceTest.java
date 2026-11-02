package com.xpdustry.shard.server.session;

import com.xpdustry.shard.server.Hang;
import com.xpdustry.shard.net.*;
import org.junit.jupiter.api.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import static com.xpdustry.shard.server.session.SessionTest.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * A connection that closes while its reader opens a session (review 36): whichever comes first, no session outlives
 * the connection and its world membership is undone exactly once. The handler does what {@code DevHandler} does.
 */
class ConnectRaceTest{
    final Sessions sessions = new Sessions();
    final Place a = new Place("a", world(4096));
    LoopbackConns conns;

    @BeforeEach
    void open() throws Exception{
        conns = new LoopbackConns(sessions::disconnected);
    }

    @AfterEach
    void close() throws Exception{
        conns.close();
    }

    static void await(CountDownLatch latch){
        try{
            if(!latch.await(Hang.SECONDS, TimeUnit.SECONDS)) throw new AssertionError("latch timed out");
        }catch(InterruptedException e){
            throw new AssertionError(e);
        }
    }

    @Test
    void aDisconnectBeforeTheAttachmentLeavesNoSession() throws Exception{
        Conn conn = conns.open();
        var decoded = new CountDownLatch(1);
        var disconnected = new CountDownLatch(1);
        var opened = new AtomicReference<Session>();
        Thread reader = Thread.ofVirtual().start(() -> {
            decoded.countDown(); // the reader has the connect packet and is about to open the session
            await(disconnected);
            Session session = sessions.open(conn, identity("alpha"));
            opened.set(session);
            if(session != null) session.join(a);
        });
        await(decoded);
        conn.close("closed by client"); // the handler's disconnected runs here, before any attachment
        disconnected.countDown();
        reader.join();

        assertNull(opened.get(), "a closed connection takes no session");
        assertNull(conn.attachment());
        assertEquals(0, sessions.size());
        assertEquals("", verdicts(tick(a)), "nothing joined");
    }

    @Test
    void aDisconnectBetweenAttachmentAndJoinQueuesNothing() throws Exception{
        Conn conn = conns.open();
        var attached = new CountDownLatch(1);
        var disconnected = new CountDownLatch(1);
        var joined = new AtomicBoolean(true);
        Thread reader = Thread.ofVirtual().start(() -> {
            Session session = sessions.open(conn, identity("alpha"));
            attached.countDown();
            await(disconnected);
            joined.set(session.join(a));
        });
        await(attached);
        conn.close("closed by client"); // the handler finds the session and closes it
        disconnected.countDown();
        reader.join();

        assertFalse(joined.get(), "a closed session doesn't join");
        assertEquals(0, sessions.size());
        assertEquals("", verdicts(tick(a)));
    }

    @Test
    void aDisconnectAfterTheJoinLeavesOnce() throws Exception{
        Conn conn = conns.open();
        Session session = sessions.open(conn, identity("alpha"));
        assertTrue(session.join(a));
        conn.close("closed by client");
        assertEquals(SessionState.CLOSED, session.state());
        assertEquals("Join Leave", verdicts(tick(a)));
        assertEquals(0, a.world().members().size());
        assertEquals(0, sessions.size());
    }

    @RepeatedTest(3)
    void racingConnectsAndDisconnectsLeaveNothingBehind() throws Exception{
        int count = 64;
        var open = new ArrayList<Conn>();
        for(int i = 0; i < count; i++) open.add(conns.open());
        var start = new CountDownLatch(1);
        try(var pool = Executors.newVirtualThreadPerTaskExecutor()){
            for(Conn conn : open){
                pool.submit(() -> {
                    await(start);
                    Session session = sessions.open(conn, identity("bot"));
                    if(session != null) session.join(a);
                });
                pool.submit(() -> {
                    await(start);
                    conn.close("closed by client");
                });
            }
            start.countDown();
        }
        for(int i = 0; i < 3; i++) tick(a);
        assertEquals(0, a.world().members().size(), "every member left");
        assertEquals(0, sessions.size(), "every session is gone");
        for(Conn conn : open){
            assertTrue(conn.attachment() == null || ((Session)conn.attachment()).state() == SessionState.CLOSED);
        }
    }
}
