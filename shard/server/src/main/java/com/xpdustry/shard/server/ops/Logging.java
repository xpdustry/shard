package com.xpdustry.shard.server.ops;

import ch.qos.logback.classic.*;
import ch.qos.logback.classic.encoder.*;
import ch.qos.logback.classic.spi.*;
import ch.qos.logback.core.*;
import ch.qos.logback.core.encoder.*;
import ch.qos.logback.core.rolling.*;
import ch.qos.logback.core.util.*;
import com.xpdustry.shard.server.config.*;
import com.xpdustry.shard.server.config.ConfigKeys.*;
import org.slf4j.*;

/**
 * Configures Logback from the config, in code (no {@code logback.xml}): stdout, plus a size-rolled file when
 * {@code log.file} is set, in {@code log.format} ({@link JsonLogEncoder} or a text pattern). Chat never goes here;
 * it has its own table ({@code store.ChatLog}).
 *
 * <p>Each of the two writes from its own queue ({@link #async}), so a slow stdout or disk never stalls the thread
 * that logs: tick threads log joins and leaves, and a 0.4 s write to stdout on a busy VM once held a world's tick
 * (26 ticks dropped, W2.4's rig). Once a queue is four-fifths full, INFO and below are dropped; WARN and ERROR wait
 * only when it is full. {@link #stop} writes what is queued before the JVM exits.
 */
public final class Logging{
    static final String TEXT_PATTERN = "%d{yyyy-MM-dd'T'HH:mm:ss.SSSX,UTC} %-5level [%thread] %logger{36} - %msg %kvp%n";
    static final int KEEP_FILES = 5;
    static final int QUEUE = 8192;
    /** How long {@link #stop} waits for the queue to drain. */
    static final int FLUSH_MILLIS = 2000;

    /** Replaces the whole Logback setup. Call once at startup, before much else logs. */
    public static void configure(Config config){
        var context = (LoggerContext)LoggerFactory.getILoggerFactory();
        context.reset();
        var root = context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);

        var console = new ConsoleAppender<ILoggingEvent>();
        console.setContext(context);
        console.setName("stdout");
        console.setEncoder(encoder(context, config.get(ConfigKeys.LOG_FORMAT)));
        console.start();
        root.addAppender(async(context, console));

        String file = config.get(ConfigKeys.LOG_FILE);
        if(!file.isEmpty()){
            var appender = new RollingFileAppender<ILoggingEvent>();
            appender.setContext(context);
            appender.setName("file");
            appender.setFile(file);
            var rolling = new FixedWindowRollingPolicy();
            rolling.setContext(context);
            rolling.setParent(appender);
            rolling.setFileNamePattern(file + ".%i");
            rolling.setMinIndex(1);
            rolling.setMaxIndex(KEEP_FILES);
            rolling.start();
            var trigger = new SizeBasedTriggeringPolicy<ILoggingEvent>();
            trigger.setContext(context);
            trigger.setMaxFileSize(new FileSize(config.get(ConfigKeys.LOG_MAX_FILE_SIZE)));
            trigger.start();
            appender.setRollingPolicy(rolling);
            appender.setTriggeringPolicy(trigger);
            appender.setEncoder(encoder(context, config.get(ConfigKeys.LOG_FORMAT)));
            appender.start();
            root.addAppender(async(context, appender));
        }
        setLevel(config.get(ConfigKeys.LOG_LEVEL));
    }

    /** The queue in front of {@code sink} (Logback's takes one appender), started, with the drop policy above. */
    static AsyncAppender async(Context context, Appender<ILoggingEvent> sink){
        var async = new AsyncAppender();
        async.setContext(context);
        async.setName("async-" + sink.getName());
        async.setQueueSize(QUEUE);
        // Logback's default: INFO and below go once less than a fifth of the queue is left
        async.setDiscardingThreshold(QUEUE / 5);
        async.setMaxFlushTime(FLUSH_MILLIS);
        async.addAppender(sink);
        async.start();
        return async;
    }

    /** Writes what the queue holds (for up to {@value #FLUSH_MILLIS} ms) and stops logging. Call before the JVM exits. */
    public static void stop(){
        ((LoggerContext)LoggerFactory.getILoggerFactory()).stop();
    }

    /** Sets the root level; {@code log.level} is live, so a config reload calls this. */
    public static void setLevel(LogLevel level){
        var context = (LoggerContext)LoggerFactory.getILoggerFactory();
        context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).setLevel(Level.toLevel(level.name()));
    }

    /** Follows {@code log.level} across reloads. */
    public static void follow(ConfigService config){
        config.subscribe(reloaded -> setLevel(config.current().get(ConfigKeys.LOG_LEVEL)));
    }

    static Encoder<ILoggingEvent> encoder(Context context, LogFormat format){
        if(format == LogFormat.JSON){
            var json = new JsonLogEncoder();
            json.setContext(context);
            json.start();
            return json;
        }
        var text = new PatternLayoutEncoder();
        text.setContext(context);
        text.setPattern(TEXT_PATTERN);
        text.start();
        return text;
    }

    private Logging(){}
}
