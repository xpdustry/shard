package com.xpdustry.shard.server.session;

import org.junit.jupiter.api.*;

import static com.xpdustry.shard.server.session.SessionTest.*;
import static org.junit.jupiter.api.Assertions.*;

/** Review 38: a seat belongs to the epoch it was taken in, and is given back exactly once. */
class SeatEpochTest{
    LoopbackConns conns;
    Sessions sessions = new Sessions();

    @BeforeEach
    void open() throws Exception{
        conns = new LoopbackConns();
    }

    @AfterEach
    void close() throws Exception{
        conns.close();
    }

    Session session(String name) throws Exception{
        return sessions.open(conns.open(), identity(name));
    }

    static Place onePlace(String name){
        var world = world(64);
        return new Place(name, world, new WorldMailbox(world.inbox().capacity(), 64, 16, 8, 1));
    }

    @Test
    void aStaleReturnFromAFailedEpochNeverFreesANewSeat() throws Exception{
        Place from = new Place("from", world(64)), to = onePlace("to");
        Session moving = session("moving");
        assertTrue(moving.join(from));
        tick(from);
        confirmed(moving, 0);
        assertNotNull(moving.beginTransfer(to), "reserves the last seat");

        to.mailbox().release();
        to.mailbox().reopen();
        Session fresh = session("fresh");
        assertTrue(fresh.join(to), "the reopened world has its seat free");
        assertEquals(1, to.mailbox().seats());

        moving.close();
        assertEquals(1, to.mailbox().seats(), "the old epoch's reservation gives nothing back");
        assertFalse(session("third").join(to), "the limit holds");
    }

    @Test
    void aReservationDoesNotSurviveAReleaseAndReopenBeforeArrival() throws Exception{
        Place from = new Place("from", world(64)), to = onePlace("to");
        Session moving = session("moving");
        moving.join(from);
        tick(from);
        confirmed(moving, 0);
        Session.Transfer transfer = moving.beginTransfer(to);
        assertNotNull(transfer);
        tick(from);

        to.mailbox().release();
        to.mailbox().reopen();
        assertFalse(moving.arrive(transfer, BEGIN), "no route starts on a seat of a released epoch");
        assertEquals(SessionState.CONNECTED, moving.state());
        assertEquals(0, to.mailbox().seats());
        assertTrue(session("fresh").join(to), "the seat is still free");
        assertFalse(moving.join(to), "and now taken");
    }
}
