package com.xpdustry.shard.rig;

import java.io.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

/**
 * A log that a process is still writing. Reads strip ANSI colours. {@link #next} matches from a cursor, so two waits
 * for the same line need two occurrences; {@link #problems} groups lines into entries ({@code [E] text} plus its
 * stack trace) and sorts out errors, desync lines and kicks.
 */
public final class LogFile{
    static final Pattern ansi = Pattern.compile("\u001B\\[[0-9;]*[A-Za-z]");
    /** Vanilla's {@code [E] text}, optionally after the server's {@code [date] } prefix. */
    static final Pattern entry = Pattern.compile("^(?:\\[[^\\]]*\\] )?\\[([DIWE])\\] (.*)");
    static final Pattern desync = Pattern.compile("(?i)desync|Block ID mismatch|Missing entity at|Error reading entity snapshot|Skipping block snapshot|Failed to read rule|not received UDP snapshots");
    static final Pattern kick = Pattern.compile("(?i)\\[rig\\] disconnected unexpectedly|\\bkicked\\b");
    static final Pattern exception = Pattern.compile("(Exception|Error)(:|$)|^\\s+at [\\w$.]+\\(");
    /** Startup noise of the vanilla client in an offline Xvfb VM: GL version probing, no sound card, no internet. */
    static final List<Pattern> clientNoise = List.of(
        Pattern.compile("^Failed to initialize OpenGL"),
        Pattern.compile("^Failed to initialize audio"),
        Pattern.compile("^Failed to fetch community servers"));

    public final Path path;
    long cursor;

    public LogFile(Path path){
        this.path = path;
    }

    public List<String> lines(){
        if(!Files.exists(path)) return List.of();
        try{
            var text = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
            return ansi.matcher(text).replaceAll("").lines().toList();
        }catch(IOException e){
            throw new UncheckedIOException(e);
        }
    }

    /** The first line at or after the cursor that matches; on a match the cursor moves past it. */
    public String next(Pattern pattern){
        var lines = lines();
        for(int i = (int)cursor; i < lines.size(); i++){
            if(pattern.matcher(lines.get(i)).find()){
                cursor = i + 1;
                return lines.get(i);
            }
        }
        return null;
    }

    public String any(Pattern pattern){
        return any(pattern, null);
    }

    /**
     * The first line matching {@code pattern} after the first line matching {@code after} (null: from the start), or
     * null.
     *
     * @throws RigException if no line matches {@code after}
     */
    public String any(Pattern pattern, Pattern after){
        boolean on = after == null;
        for(String line : lines()){
            if(!on){
                on = after.matcher(line).find();
                continue;
            }
            if(pattern.matcher(line).find()) return line;
        }
        if(!on) throw new RigException("no line matches /" + after.pattern() + "/ in " + path.getFileName());
        return null;
    }

    public record Problems(List<String> errors, List<String> desync, List<String> kicks){
        public boolean clean(){
            return errors.isEmpty() && desync.isEmpty() && kicks.isEmpty();
        }
    }

    /** Errors are {@code [E]} entries and exception text; {@code allow} patterns (and client noise) excuse an entry. */
    public Problems problems(List<Pattern> allow, boolean client){
        var errors = new ArrayList<String>();
        var desyncs = new ArrayList<String>();
        var kicks = new ArrayList<String>();
        var entries = new ArrayList<List<String>>();
        for(String line : lines()){
            if(entry.matcher(line).find() || entries.isEmpty()) entries.add(new ArrayList<>());
            entries.getLast().add(line);
        }
        for(var lines : entries){
            String first = lines.getFirst();
            var m = entry.matcher(first);
            boolean leveled = m.find();
            String level = leveled ? m.group(1) : "", text = leveled ? m.group(2) : first;
            String whole = String.join("\n", lines);
            if(allowed(text, allow) || (client && allowed(text, clientNoise))) continue;
            if(desync.matcher(whole).find()) desyncs.add(first);
            else if(kick.matcher(first).find()) kicks.add(first);
            else if(level.equals("E") || lines.stream().anyMatch(l -> exception.matcher(l).find())) errors.add(first);
        }
        return new Problems(errors, desyncs, kicks);
    }

    static boolean allowed(String text, List<Pattern> allow){
        for(var p : allow) if(p.matcher(text).find()) return true;
        return false;
    }
}
