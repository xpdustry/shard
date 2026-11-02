package com.xpdustry.shard.server.ops;

import ch.qos.logback.classic.spi.*;
import ch.qos.logback.core.encoder.*;
import tools.jackson.core.*;
import tools.jackson.databind.json.*;

import java.io.*;
import java.time.*;
import java.time.format.*;
import java.util.*;

/**
 * One JSON object per log line (note 34 "Logs, metrics, and health"):
 *
 * <pre>{"ts":"2026-10-29T12:00:00.123Z","level":"INFO","logger":"...","thread":"...","msg":"...",
 *  "event":"ops.listen","world":"alpha",...,"error":{"type":"...","message":"...","stack":"..."}}</pre>
 *
 * <p>After the fixed fields come the MDC entries, then SLF4J 2 key-value pairs ({@code log.atInfo().addKeyValue}),
 * each as a top-level field; one that collides with a fixed field gets a {@code _} suffix. Every string is escaped as
 * JSON data, and the message and each value are cut at {@value #MAX_TEXT} characters so user text can't flood a
 * line.
 */
public final class JsonLogEncoder extends EncoderBase<ILoggingEvent>{
    public static final int MAX_TEXT = 8192;
    static final int MAX_STACK = 16384;
    static final Set<String> FIXED = Set.of("ts", "level", "logger", "thread", "msg", "error");
    static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSX").withZone(ZoneOffset.UTC);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Override
    public byte[] headerBytes(){
        return null;
    }

    @Override
    public byte[] footerBytes(){
        return null;
    }

    @Override
    public byte[] encode(ILoggingEvent event){
        var bytes = new ByteArrayOutputStream(256);
        try(JsonGenerator json = JSON.createGenerator(bytes)){
            json.writeStartObject();
            json.writeStringProperty("ts", TIME.format(event.getInstant()));
            json.writeStringProperty("level", event.getLevel().toString());
            json.writeStringProperty("logger", event.getLoggerName());
            json.writeStringProperty("thread", event.getThreadName());
            json.writeStringProperty("msg", cut(event.getFormattedMessage(), MAX_TEXT));
            var written = new HashSet<>(FIXED);
            var mdc = event.getMDCPropertyMap();
            if(mdc != null){
                for(var entry : new TreeMap<>(mdc).entrySet()) field(json, written, entry.getKey(), entry.getValue());
            }
            var pairs = event.getKeyValuePairs();
            if(pairs != null){
                for(var pair : pairs) field(json, written, pair.key, pair.value);
            }
            var error = event.getThrowableProxy();
            if(error != null){
                json.writeName("error");
                json.writeStartObject();
                json.writeStringProperty("type", error.getClassName());
                json.writeStringProperty("message", cut(error.getMessage(), MAX_TEXT));
                json.writeStringProperty("stack", cut(stack(error), MAX_STACK));
                json.writeEndObject();
            }
            json.writeEndObject();
        }
        bytes.write('\n');
        return bytes.toByteArray();
    }

    private static void field(JsonGenerator json, Set<String> written, String key, Object value){
        String name = key;
        while(!written.add(name)) name += "_";
        json.writeName(name);
        switch(value){
            case null -> json.writeNull();
            case Boolean flag -> json.writeBoolean(flag);
            case Integer number -> json.writeNumber(number);
            case Long number -> json.writeNumber(number);
            case Double number when Double.isFinite(number) -> json.writeNumber(number);
            case Float number when Float.isFinite(number) -> json.writeNumber(number);
            default -> json.writeString(cut(String.valueOf(value), MAX_TEXT));
        }
    }

    private static String stack(IThrowableProxy error){
        var out = new StringBuilder();
        for(IThrowableProxy current = error; current != null && out.length() < MAX_STACK; current = current.getCause()){
            if(current != error) out.append("caused by ").append(current.getClassName()).append(": ").append(current.getMessage()).append('\n');
            for(var frame : current.getStackTraceElementProxyArray()) out.append("  at ").append(frame.getStackTraceElement()).append('\n');
        }
        return out.toString();
    }

    private static String cut(String text, int max){
        if(text == null) return null;
        return text.length() <= max ? text : text.substring(0, max) + "...(" + (text.length() - max) + " more)";
    }
}
