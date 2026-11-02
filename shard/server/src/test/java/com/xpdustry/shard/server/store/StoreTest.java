package com.xpdustry.shard.server.store;

import com.xpdustry.shard.server.Hang;
import com.xpdustry.shard.server.store.ChatLog.*;
import com.xpdustry.shard.server.store.StoreFixture.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.*;

import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import static org.junit.jupiter.api.Assertions.*;

class StoreTest{
    @TempDir
    Path dir;

    static final ManualClock CLOCK = new ManualClock(1_800_000_000_000L);

    static Message chat(String world, String text){
        return new Message(0, CLOCK.millis(), world, null, null, Channel.GLOBAL, text, true);
    }

    static long count(Connection connection, String sql) throws SQLException{
        try(var statement = connection.createStatement(); var rows = statement.executeQuery(sql)){
            rows.next();
            return rows.getLong(1);
        }
    }

    /** Many producers, one writer: every write lands, on the writer thread, in each producer's order, in fewer commits. */
    @Test
    void concurrentWritersShareOneWriterThread() throws Exception{
        int producers = 32, perProducer = 200;
        // room for the whole burst: queuesAreBoundedAndUrgentGoesFirst covers overflow
        try(var store = Store.open(StoreFixture.settings(dir, producers * perProducer, 4), CLOCK)){
            var threads = ConcurrentHashMap.<String>newKeySet();
            var futures = new ConcurrentLinkedQueue<CompletableFuture<Long>>();
            var start = new CountDownLatch(1);
            try(var executor = Executors.newVirtualThreadPerTaskExecutor()){
                for(int p = 0; p < producers; p++){
                    String world = "w" + p;
                    executor.execute(() -> {
                        try{
                            start.await();
                        }catch(InterruptedException e){
                            throw new RuntimeException(e);
                        }
                        for(int i = 0; i < perProducer; i++){
                            String text = Integer.toString(i);
                            futures.add(store.write(c -> {
                                threads.add(Thread.currentThread().getName());
                                ChatLog.append(c, List.of(chat(world, text)));
                                return count(c, "SELECT last_insert_rowid()");
                            }));
                        }
                    });
                }
                start.countDown();
            }
            for(var future : futures) future.get(Hang.SECONDS, TimeUnit.SECONDS);

            assertEquals(Set.of("store-writer"), threads);
            var stats = store.stats();
            assertEquals(producers * perProducer, stats.committed());
            assertEquals(0, stats.failed());
            assertEquals(0, stats.rejected());
            assertTrue(stats.batches() < stats.committed(), "group commit: " + stats.batches() + " batches for " + stats.committed() + " writes");

            store.read(c -> {
                assertEquals(producers * perProducer, count(c, "SELECT count(*) FROM chat_log"));
                // each producer's messages are in submission order
                for(int p = 0; p < producers; p++){
                    var messages = ChatLog.recent(c, "w" + p, perProducer);
                    assertEquals(perProducer, messages.size());
                    for(int i = 0; i < perProducer; i++) assertEquals(Integer.toString(i), messages.get(i).message());
                }
                return null;
            }).get(Hang.SECONDS, TimeUnit.SECONDS);
        }
    }

    /** A job that throws rolls back only its own savepoint. */
    @Test
    void aFailingJobDoesNotTakeItsBatchDown() throws Exception{
        try(var store = StoreFixture.open(dir, CLOCK)){
            var gate = new CountDownLatch(1);
            var blocker = blockWriter(store, gate);
            var good = store.write(c -> ChatLog.append(c, List.of(chat("a", "kept"))));
            var bad = store.write(c -> {
                ChatLog.append(c, List.of(chat("a", "rolled back")));
                throw new SQLException("boom");
            });
            var bad2 = store.write(c -> ChatLog.append(c, List.of(new Message(0, 0, "a", null, null, Channel.GLOBAL, "x".repeat(5000), true))));
            var after = store.write(c -> ChatLog.append(c, List.of(chat("a", "also kept"))));
            gate.countDown();
            blocker.get(Hang.SECONDS, TimeUnit.SECONDS);
            assertEquals(1, good.get(Hang.SECONDS, TimeUnit.SECONDS));
            assertEquals(1, after.get(Hang.SECONDS, TimeUnit.SECONDS));
            var error = assertThrows(ExecutionException.class, () -> bad.get(Hang.SECONDS, TimeUnit.SECONDS));
            assertEquals("boom", error.getCause().getMessage());
            assertThrows(ExecutionException.class, () -> bad2.get(Hang.SECONDS, TimeUnit.SECONDS), "the length CHECK refuses it");
            var texts = store.read(c -> ChatLog.recent(c, "a", 10)).get(Hang.SECONDS, TimeUnit.SECONDS).stream().map(Message::message).toList();
            assertEquals(List.of("kept", "also kept"), texts);
            assertEquals(2, store.stats().failed());
        }
    }

    /** Each queue is bounded and fails fast; urgent writes run before normal ones. */
    @Test
    void queuesAreBoundedAndUrgentGoesFirst() throws Exception{
        try(var store = Store.open(StoreFixture.settings(dir, 16, 2), CLOCK)){
            var gate = new CountDownLatch(1);
            var blocker = blockWriter(store, gate);
            var order = new CopyOnWriteArrayList<String>();
            var accepted = new ArrayList<CompletableFuture<?>>();
            for(int i = 0; i < 16; i++){
                String name = "normal" + i;
                accepted.add(store.write(c -> order.add(name)));
            }
            var overflow = store.write(c -> order.add("overflow"));
            var error = assertThrows(ExecutionException.class, () -> overflow.get(Hang.SECONDS, TimeUnit.SECONDS));
            assertInstanceOf(RejectedExecutionException.class, error.getCause());
            for(int i = 0; i < 16; i++){
                String name = "urgent" + i;
                accepted.add(store.write(Store.Priority.URGENT, c -> order.add(name)));
            }
            assertTrue(store.stats().queued() == 32 && store.stats().oldestQueuedNanos() > 0, store.stats().toString());
            gate.countDown();
            blocker.get(Hang.SECONDS, TimeUnit.SECONDS);
            for(var future : accepted) future.get(Hang.SECONDS, TimeUnit.SECONDS);
            assertFalse(order.contains("overflow"));
            assertEquals(32, order.size());
            for(int i = 0; i < 16; i++) assertTrue(order.get(i).startsWith("urgent"), order.toString());
            assertEquals(1, store.stats().rejected());
        }
    }

    /** Reads run on virtual threads, at most one per reader connection at a time, and see only committed data. */
    @Test
    void readsRunOnVirtualThreadsWithBoundedConnections() throws Exception{
        int readers = 3;
        try(var store = Store.open(StoreFixture.settings(dir, 64, readers), CLOCK)){
            store.write(c -> ChatLog.append(c, List.of(chat("a", "hello")))).get(Hang.SECONDS, TimeUnit.SECONDS);
            AtomicInteger active = new AtomicInteger(), peak = new AtomicInteger();
            // the first reads hold their connections until every reader connection is taken
            var together = new CountDownLatch(readers);
            var reads = new ArrayList<CompletableFuture<Long>>();
            for(int i = 0; i < 20; i++){
                reads.add(store.read(c -> {
                    assertTrue(Thread.currentThread().isVirtual());
                    peak.accumulateAndGet(active.incrementAndGet(), Math::max);
                    together.countDown();
                    try{
                        if(!together.await(Hang.SECONDS, TimeUnit.SECONDS)) throw new SQLException("only " + active.get() + " reads at once");
                    }catch(InterruptedException e){
                        throw new SQLException(e);
                    }
                    active.decrementAndGet();
                    return count(c, "SELECT count(*) FROM chat_log");
                }));
            }
            for(var read : reads) assertEquals(1, read.get(Hang.SECONDS, TimeUnit.SECONDS));
            assertEquals(readers, peak.get(), "one read per reader connection at once, and all of them");

            // a read sees nothing of a write that hasn't committed
            var gate = new CountDownLatch(1);
            var started = new CountDownLatch(1);
            var pending = store.write(c -> {
                ChatLog.append(c, List.of(chat("a", "uncommitted")));
                started.countDown();
                try{
                    gate.await();
                }catch(InterruptedException e){
                    throw new SQLException(e);
                }
                return null;
            });
            started.await();
            assertEquals(1, store.read(c -> count(c, "SELECT count(*) FROM chat_log")).get(Hang.SECONDS, TimeUnit.SECONDS));
            gate.countDown();
            pending.get(Hang.SECONDS, TimeUnit.SECONDS);
            assertEquals(2, store.read(c -> count(c, "SELECT count(*) FROM chat_log")).get(Hang.SECONDS, TimeUnit.SECONDS));

            // readers are read-only
            var write = store.read(c -> ChatLog.append(c, List.of(chat("a", "nope"))));
            assertThrows(ExecutionException.class, () -> write.get(Hang.SECONDS, TimeUnit.SECONDS));
        }
    }

    @Test
    void closeFinishesQueuedWritesThenRefuses() throws Exception{
        var futures = new ArrayList<CompletableFuture<Integer>>();
        var store = StoreFixture.open(dir, CLOCK);
        for(int i = 0; i < 500; i++) futures.add(store.write(c -> ChatLog.append(c, List.of(chat("a", "m")))));
        store.close();
        for(var future : futures) assertEquals(1, future.getNow(-1), "completed before close returned");
        assertFalse(store.isOpen());
        assertThrows(ExecutionException.class, () -> store.write(c -> 1).get());
        assertThrows(ExecutionException.class, () -> store.read(c -> 1).get());

        try(var reopened = StoreFixture.open(dir, CLOCK)){
            assertEquals(500, reopened.read(c -> count(c, "SELECT count(*) FROM chat_log")).get(Hang.SECONDS, TimeUnit.SECONDS));
        }
    }

    @Test
    void pragmasAreSet() throws Exception{
        try(var store = StoreFixture.open(dir, CLOCK)){
            assertEquals("wal", store.read(c -> pragma(c, "journal_mode")).get());
            assertEquals("1", store.read(c -> pragma(c, "foreign_keys")).get());
            assertEquals("2", store.write(c -> pragma(c, "synchronous")).get(), "FULL");
            assertEquals("5000", store.write(c -> pragma(c, "busy_timeout")).get());
        }
    }

    static String pragma(Connection connection, String name) throws SQLException{
        try(var statement = connection.createStatement(); var rows = statement.executeQuery("PRAGMA " + name)){
            rows.next();
            return rows.getString(1);
        }
    }

    /** Occupies the writer thread until {@code gate} opens; returns once the writer is inside it. */
    static CompletableFuture<Object> blockWriter(Store store, CountDownLatch gate) throws InterruptedException{
        var inside = new CountDownLatch(1);
        var future = store.write(Store.Priority.URGENT, c -> {
            inside.countDown();
            try{
                gate.await();
            }catch(InterruptedException e){
                throw new SQLException(e);
            }
            return null;
        });
        assertTrue(inside.await(Hang.SECONDS, TimeUnit.SECONDS));
        return future;
    }
}
