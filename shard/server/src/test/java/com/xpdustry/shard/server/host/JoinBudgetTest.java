package com.xpdustry.shard.server.host;

import com.xpdustry.shard.server.*;
import com.xpdustry.shard.server.session.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.*;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

import static com.xpdustry.shard.server.ServerTest.await;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Review 38's second finding: join captures hold memory until their streams are deflated, so they reserve it first.
 * With the deflates of the largest default map stalled, joins beyond the budget wait instead of capturing.
 */
class JoinBudgetTest{
    @TempDir
    static Path temp;
    static Server server;

    @BeforeAll
    static void start() throws Exception{
        server = Server.start(new Server.Options(ServerTest.configure(temp.resolve("main"), """
            host:
              worlds: [domain]
            """), Map.of(), false));
    }

    @AfterAll
    static void stop(){
        if(server != null) server.close();
    }

    @Test
    void stalledDeflatesHoldNoMoreThanTheBudget() throws Exception{
        HostedWorld domain = server.host().world("domain").orElseThrow();
        WorldSends.Footprint footprint = domain.footprint();
        long limit = footprint.capture() + 2 * footprint.stream();
        assertTrue(footprint.capture() > 0 && footprint.stream() > 0, footprint.toString());
        var budget = new CaptureBudget(4 * limit, limit, limit, 8);
        server.host().captureBudget(budget);
        var held = new LinkedBlockingQueue<Runnable>();
        domain.encodeOn(held::add);

        int joins = 6;
        var clients = new ArrayList<TestClient>();
        // the tick thread waits until every join is routed, so they all reach the world in one tick
        var gate = new CountDownLatch(1);
        var waiting = new CountDownLatch(1);
        CompletableFuture<Object> blocked = domain.inspect(world -> {
            waiting.countDown();
            try{
                return gate.await(Hang.SECONDS, TimeUnit.SECONDS);
            }catch(InterruptedException e){
                throw new IllegalStateException(e);
            }
        });
        try{
            assertTrue(waiting.await(Hang.SECONDS, TimeUnit.SECONDS), "the tick thread waits");
            for(int i = 0; i < joins; i++){
                var client = new TestClient(server.transport().address());
                clients.add(client);
                client.connect("join" + i);
            }
            await("every join to load", () -> server.sessions().all().stream().filter(s -> s.state() == SessionState.LOADING).count() == joins);
            gate.countDown();
            blocked.get(Hang.SECONDS, TimeUnit.SECONDS);
            // that tick refused the third stream; the next one, its retry: by then the first two were handed over
            await("a retry", () -> budget.refused() >= 2);
            assertEquals(2, held.size(), "two streams fit the world's budget with their capture");
            assertTrue(budget.reserved(domain) <= limit, "reserved " + budget.reserved(domain));
            assertEquals(2, budget.inFlight());
            assertTrue(budget.refused() > 0, "the others waited");

            int loaded = 0;
            long deadline = System.nanoTime() + Hang.NANOS;
            while(loaded < joins){
                assertTrue(System.nanoTime() < deadline, "only " + loaded + " loaded");
                Runnable next = held.poll(100, TimeUnit.MILLISECONDS);
                if(next != null){
                    next.run();
                    assertTrue(budget.reserved(domain) <= limit, "reserved " + budget.reserved(domain));
                }
                loaded = 0;
                for(TestClient client : clients) loaded += client.loads().size();
            }
            for(TestClient client : clients) assertEquals(List.of("load Domain"), client.loads());
            await("the budget to empty", () -> budget.reserved() == 0 && budget.inFlight() == 0);
        }finally{
            gate.countDown();
            domain.encodeOn(null);
            server.host().captureBudget(CaptureBudget.defaults());
            for(TestClient client : clients) client.close();
        }
    }
}
