package com.xpdustry.shard.server.host;

import com.xpdustry.shard.server.*;
import com.xpdustry.shard.server.config.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.*;

import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Nobody gets into a world before the server is ready: it hosts every startup world, then listens, as vanilla hosts
 * its map before {@code net.host}. A client knocking from the start gets in only once the slow 500x500 world is
 * hosted, and loads it; listening first, it was told no world was running.
 */
class ListenAfterHostingTest{
    @TempDir
    Path temp;

    @Test
    void theFirstClientInFindsEveryWorldHosted() throws Exception{
        Path root = temp.resolve("main");
        Files.createDirectories(root.resolve("maps"));
        CaptureFootprintTest.stoneMap(root.resolve("maps/stone.msav"));
        // the port is free when picked, but anything may take it before the server listens: then pick another
        for(int attempt = 1; ; attempt++){
            int port;
            try(var probe = new ServerSocket(0, 0, InetAddress.getLoopbackAddress())){
                port = probe.getLocalPort();
            }
            var options = new Server.Options(ServerTest.configure(root, """
                host:
                  worlds: ["big=stone", maze]
                """), Map.of(ConfigKeys.NET_PORT.env(), String.valueOf(port)), false);
            CompletableFuture<Server> starting = CompletableFuture.supplyAsync(() -> {
                try{
                    return Server.start(options);
                }catch(Exception e){
                    throw new CompletionException(e);
                }
            });
            TestClient client = null;
            try{
                var address = new InetSocketAddress(InetAddress.getLoopbackAddress(), port);
                long deadline = System.nanoTime() + Hang.NANOS;
                while(client == null && !starting.isCompletedExceptionally()){
                    assertTrue(System.nanoTime() < deadline, "the server never answered");
                    try{
                        client = new TestClient(address);
                    }catch(Exception refused){
                        Thread.sleep(5);
                    }
                }
                if(client == null){
                    Throwable failure = assertThrows(ExecutionException.class, starting::get).getCause();
                    assertTrue(failure instanceof BindException && attempt < 3, () -> "the server didn't start: " + failure);
                    continue;
                }
                client.connect("alpha");
                assertEquals(List.of("load Stone"), client.loads(1), "the first world, hosted before anyone got in");
                return;
            }finally{
                if(client != null) client.close();
                if(!starting.isCompletedExceptionally()) starting.get(Hang.SECONDS, TimeUnit.SECONDS).close();
            }
        }
    }
}
