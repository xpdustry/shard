package com.xpdustry.shard.tools.fuzz;

import ch.qos.logback.classic.*;
import ch.qos.logback.classic.spi.*;
import ch.qos.logback.core.*;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * Every server log line at WARN or above, caught on the root logger. A line with a throwable, or any ERROR, is an
 * escaped exception: the server's loops log what they catch (the transport's "reader of ... failed", a world's
 * "failed at tick"). Other warnings are counted by message, for the report.
 */
final class LogTap extends AppenderBase<ILoggingEvent>{
    /** Warnings a fuzzed server gives by design: load, not faults. */
    static final List<String> EXPECTED = List.of("watchdog stall", "ticks behind");

    final ConcurrentLinkedQueue<String> escaped = new ConcurrentLinkedQueue<>();
    final ConcurrentHashMap<String, AtomicLong> warnings = new ConcurrentHashMap<>();

    static LogTap install(){
        var tap = new LogTap();
        var context = (LoggerContext)LoggerFactory.getILoggerFactory();
        tap.setContext(context);
        tap.setName("fuzz-tap");
        tap.start();
        context.getLogger(Logger.ROOT_LOGGER_NAME).addAppender(tap);
        return tap;
    }

    void uninstall(){
        var context = (LoggerContext)LoggerFactory.getILoggerFactory();
        context.getLogger(Logger.ROOT_LOGGER_NAME).detachAppender(this);
        stop();
    }

    @Override
    protected void append(ILoggingEvent event){
        if(!event.getLevel().isGreaterOrEqual(Level.WARN)) return;
        IThrowableProxy thrown = event.getThrowableProxy();
        String message = event.getFormattedMessage();
        if(thrown != null || event.getLevel() == Level.ERROR){
            var line = new StringBuilder(event.getLevel() + " " + event.getLoggerName() + ": " + message);
            for(IThrowableProxy t = thrown; t != null; t = t.getCause()){
                line.append("\n  ").append(t.getClassName()).append(": ").append(t.getMessage());
                StackTraceElementProxy[] stack = t.getStackTraceElementProxyArray();
                for(int i = 0; i < Math.min(stack.length, 12); i++) line.append("\n    at ").append(stack[i].getSTEAsString());
            }
            escaped.add(line.toString());
            return;
        }
        String key = message == null ? "" : message.replaceAll("[0-9]+", "#");
        if(key.length() > 160) key = key.substring(0, 160);
        warnings.computeIfAbsent(event.getLoggerName() + ": " + key, k -> new AtomicLong()).incrementAndGet();
    }
}
