package com.xpdustry.shard.server.ops;

import ch.qos.logback.classic.*;
import ch.qos.logback.classic.spi.*;
import ch.qos.logback.classic.util.*;
import ch.qos.logback.core.*;
import com.xpdustry.shard.server.Hang;
import com.xpdustry.shard.server.config.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.*;
import org.slf4j.*;
import org.slf4j.event.KeyValuePair;
import tools.jackson.databind.*;
import tools.jackson.databind.json.*;

import java.nio.charset.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

class LoggingTest{
    static final JsonMapper JSON = JsonMapper.shared();

    static JsonNode encode(LoggingEvent event){
        var encoder = new JsonLogEncoder();
        byte[] bytes = encoder.encode(event);
        String line = new String(bytes, StandardCharsets.UTF_8);
        assertTrue(line.endsWith("\n"));
        assertEquals(1, line.lines().count(), "one line per event, whatever the text holds");
        return JSON.readTree(line);
    }

    static LoggingEvent event(Map<String, String> mdc, String message, Throwable error, Object... arguments){
        var context = new LoggerContext();
        var event = new LoggingEvent("fqcn", context.getLogger("shard.test"), Level.WARN, message, error, arguments);
        event.setInstant(Instant.parse("2026-10-29T12:00:00.123Z"));
        event.setThreadName("tick-1");
        event.setMDCPropertyMap(mdc); // set it: a bare context has no MDC adapter
        return event;
    }

    @Test
    void fieldsAreFlatAndEscaped(){
        var event = event(Map.of("world", "alpha", "msg", "collides"), "player {} said {}",
            new IllegalStateException("bad", new RuntimeException("root")), "\"Foo\"", "line\nbreak");
        event.addKeyValuePair(new KeyValuePair("event", "chat.filtered"));
        event.addKeyValuePair(new KeyValuePair("tick", 42L));
        event.addKeyValuePair(new KeyValuePair("ok", true));
        event.addKeyValuePair(new KeyValuePair("tick", 43L));
        var json = encode(event);
        assertEquals("2026-10-29T12:00:00.123Z", json.get("ts").asString());
        assertEquals("WARN", json.get("level").asString());
        assertEquals("shard.test", json.get("logger").asString());
        assertEquals("tick-1", json.get("thread").asString());
        assertEquals("player \"Foo\" said line\nbreak", json.get("msg").asString());
        assertEquals("alpha", json.get("world").asString());
        assertEquals("collides", json.get("msg_").asString(), "a colliding key keeps its value under another name");
        assertEquals("chat.filtered", json.get("event").asString());
        assertEquals(42, json.get("tick").asLong());
        assertTrue(json.get("tick").isNumber());
        assertEquals(43, json.get("tick_").asLong());
        assertTrue(json.get("ok").asBoolean());
        var error = json.get("error");
        assertEquals("java.lang.IllegalStateException", error.get("type").asString());
        assertEquals("bad", error.get("message").asString());
        assertTrue(error.get("stack").asString().contains("caused by java.lang.RuntimeException: root"), error.get("stack").asString());
    }

    @Test
    void longTextIsCut(){
        var json = encode(event(Map.of(), "{}", null, "x".repeat(100_000)));
        String msg = json.get("msg").asString();
        assertTrue(msg.length() < JsonLogEncoder.MAX_TEXT + 64, "length " + msg.length());
        assertTrue(msg.endsWith("...(" + (100_000 - JsonLogEncoder.MAX_TEXT) + " more)"), msg.substring(msg.length() - 40));
    }

    @Test
    void configureWritesJsonToTheLogFile(@TempDir Path dir) throws Exception{
        Path file = dir.resolve("shard.log");
        var config = ConfigLoader.load(null, null, Map.of("SHARD__LOG__FILE", file.toString(), "SHARD__LOG__LEVEL", "warn"));
        try{
            Logging.configure(config);
            var log = LoggerFactory.getLogger("shard.logging-test");
            log.info("dropped: below warn");
            log.atWarn().setMessage("kept").addKeyValue("event", "test.kept").addKeyValue("world", "beta").log();
            var lines = lines(file, 1);
            assertEquals(1, lines.size(), lines.toString());
            var json = JSON.readTree(lines.getFirst());
            assertEquals("kept", json.get("msg").asString());
            assertEquals("test.kept", json.get("event").asString());
            assertEquals("beta", json.get("world").asString());

            Logging.setLevel(ConfigKeys.LogLevel.INFO);
            log.info("now kept");
            assertEquals(2, lines(file, 2).size());
        }finally{
            Logging.configure(Config.defaults());
        }
    }

    /** The file's lines once it has {@code count} of them: the queue writes them on its own thread. */
    static List<String> lines(Path file, int count) throws Exception{
        long deadline = System.nanoTime() + Hang.NANOS;
        List<String> lines = Files.exists(file) ? Files.readAllLines(file) : List.of();
        while(lines.size() < count && System.nanoTime() < deadline){
            Thread.sleep(10);
            lines = Files.exists(file) ? Files.readAllLines(file) : List.of();
        }
        return lines;
    }

    @Test
    void aStalledSinkNeverHoldsTheLoggingThread(){
        var context = new LoggerContext();
        context.setMDCAdapter(new LogbackMDCAdapter()); // the queue copies each event's MDC
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var written = new ConcurrentLinkedQueue<ILoggingEvent>();
        var stalled = new AppenderBase<ILoggingEvent>(){
            @Override
            protected void append(ILoggingEvent event){
                entered.countDown();
                try{
                    release.await(); // a stdout nobody reads
                }catch(InterruptedException e){
                    Thread.currentThread().interrupt();
                }
                written.add(event);
            }
        };
        stalled.setContext(context);
        stalled.start();
        var async = Logging.async(context, stalled);
        var log = context.getLogger("shard.stalled");
        log.addAppender(async);
        // the sink stalls on the first event, before any other is offered: the worker drains the queue in batches, so
        // a batch it took before stalling would make room the count below depends on
        log.info("stall");
        assertTimeoutPreemptively(Hang.TIMEOUT, () -> entered.await());
        int infos = 3 * Logging.QUEUE, warns = Logging.QUEUE / 10;
        // the sink is stalled until the release below, so a tick thread that waited for it would wait for good: a hang
        assertTimeoutPreemptively(Hang.TIMEOUT, () -> {
            for(int i = 0; i < infos; i++) log.info("join {}", i);
            for(int i = 0; i < warns; i++) log.warn("behind {}", i);
        }, "a tick thread logging into a full queue must not wait for the sink");
        release.countDown();
        async.stop();
        long warned = written.stream().filter(event -> event.getLevel() == Level.WARN).count();
        long informed = written.stream().filter(event -> event.getMessage().startsWith("join")).count();
        assertEquals(warns, warned, "WARN and ERROR are never dropped while the queue has room");
        // the queue takes INFO while at least a fifth of it is free
        assertEquals(Logging.QUEUE - Logging.QUEUE / 5 + 1, informed, "INFO drops once a fifth of the queue is left, never sooner");
    }
}
