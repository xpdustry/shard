package com.xpdustry.shard.rig;

import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

/**
 * A parsed {@code .rig} script: one statement per line, {@code [target:] action args...}, {@code #} comments.
 * Tokens are words, {@code "quoted strings"}, {@code /regex/} and {@code !/regex/}. {@code console} and
 * {@code exec} take the raw rest of their line. The reference is {@code rig/README.md}.
 */
public final class Script{
    static final Set<String> rawActions = Set.of("console", "exec");
    static final Set<String> globalActions = Set.of("wait", "console", "server", "exec", "assert");
    static final Set<String> clientActions = Set.of("join", "reconnect", "rejoin", "disconnect", "dismiss", "hold", "press",
        "hotbar", "type", "chat", "scroll", "zoom", "move", "click", "drag", "select", "rotate", "place", "shot", "mark", "expect",
        "sample", "assert", "wait", "drop", "refused");
    static final Pattern duration = Pattern.compile("(\\d+(?:\\.\\d+)?)(ms|s|m|t)");

    public final List<Step> steps = new ArrayList<>();
    public final List<Pattern> allowClient = new ArrayList<>();
    public final List<Pattern> allowServer = new ArrayList<>();
    public final Set<String> targets = new TreeSet<>();
    public long timeoutMillis = 10 * 60_000;

    /** One statement. {@code target} is a client name, {@code all}, or null for global actions. */
    public record Step(int line, String text, String target, String action, List<Token> args, String raw){
        public String word(int i){
            if(i >= args.size()) throw new RigException("'" + action + "' needs more arguments");
            return args.get(i).text;
        }

        public Token arg(int i){
            if(i >= args.size()) throw new RigException("'" + action + "' needs more arguments");
            return args.get(i);
        }

        public boolean has(int i){
            return i < args.size();
        }
    }

    /** {@code kind} is {@code word}, {@code string}, {@code regex} or {@code notregex}. */
    public record Token(String kind, String text){
        public Pattern regex(){
            if(!kind.equals("regex") && !kind.equals("notregex")) throw new RigException("expected /regex/, got " + text);
            return Pattern.compile(text);
        }
    }

    /** Syntax check without a VM: {@code java rig/src/com/xpdustry/shard/rig/Script.java FILE...}. */
    public static void main(String[] args) throws java.io.IOException{
        int bad = 0;
        for(String file : args){
            try{
                var script = parse(Path.of(file));
                System.out.println(file + ": " + script.steps.size() + " steps, targets " + script.targets);
            }catch(RigException e){
                System.err.println("rig: " + e.getMessage());
                bad++;
            }
        }
        System.exit(bad == 0 ? 0 : 2);
    }

    public static Script parse(Path file) throws java.io.IOException{
        var script = new Script();
        var lines = Files.readAllLines(file);
        for(int i = 0; i < lines.size(); i++){
            try{
                script.add(i + 1, lines.get(i));
            }catch(RigException e){
                throw new RigException(file.getFileName() + ":" + (i + 1) + ": " + e.getMessage());
            }
        }
        return script;
    }

    void add(int line, String text){
        String trimmed = text.strip();
        if(trimmed.isEmpty() || trimmed.startsWith("#")) return;

        String target = null, rest = trimmed;
        var head = Pattern.compile("^([A-Za-z][\\w-]*):\\s+").matcher(rest);
        if(head.find()){
            target = head.group(1);
            rest = rest.substring(head.end());
            targets.add(target);
        }
        String action = rest.split("\\s+", 2)[0];
        if(!globalActions.contains(action) && !clientActions.contains(action) && !action.equals("timeout") && !action.equals("allow")){
            throw new RigException("unknown action '" + action + "'");
        }
        String raw = rest.substring(action.length()).strip();
        List<Token> args = rawActions.contains(action) ? List.of() : tokens(raw);

        switch(action){
            case "timeout" -> {
                timeoutMillis = millis(args.getFirst().text);
                return;
            }
            case "allow" -> {
                if(args.size() != 2) throw new RigException("usage: allow client-log|server-log /regex/");
                (switch(args.get(0).text){
                    case "client-log" -> allowClient;
                    case "server-log" -> allowServer;
                    default -> throw new RigException("allow takes client-log or server-log");
                }).add(args.get(1).regex());
                return;
            }
            default -> {}
        }
        steps.add(new Step(line, trimmed, target, action, args, raw));
    }

    static List<Token> tokens(String text){
        var out = new ArrayList<Token>();
        int i = 0, n = text.length();
        while(i < n){
            char c = text.charAt(i);
            if(Character.isWhitespace(c)){
                i++;
            }else if(c == '#'){
                break;
            }else if(c == '"'){
                var sb = new StringBuilder();
                for(i++; i < n && text.charAt(i) != '"'; i++){
                    if(text.charAt(i) == '\\' && i + 1 < n) i++;
                    sb.append(text.charAt(i));
                }
                if(i >= n) throw new RigException("unterminated string");
                i++;
                out.add(new Token("string", sb.toString()));
            }else if(c == '/' || (c == '!' && i + 1 < n && text.charAt(i + 1) == '/')){
                boolean not = c == '!';
                var sb = new StringBuilder();
                for(i += not ? 2 : 1; i < n && text.charAt(i) != '/'; i++){
                    if(text.charAt(i) == '\\' && i + 1 < n && text.charAt(i + 1) == '/') i++;
                    sb.append(text.charAt(i));
                }
                if(i >= n) throw new RigException("unterminated /regex/");
                i++;
                out.add(new Token(not ? "notregex" : "regex", sb.toString()));
            }else{
                int start = i;
                while(i < n && !Character.isWhitespace(text.charAt(i))) i++;
                out.add(new Token("word", text.substring(start, i)));
            }
        }
        return out;
    }

    static boolean isDuration(String text){
        return duration.matcher(text).matches();
    }

    /** Milliseconds of wall time; ticks count 1/60 s. */
    static long millis(String text){
        var m = duration.matcher(text);
        if(!m.matches()) throw new RigException("not a duration (500ms, 2s, 1m, 120t): " + text);
        double v = Double.parseDouble(m.group(1));
        return Math.round(switch(m.group(2)){
            case "ms" -> v;
            case "s" -> v * 1000;
            case "m" -> v * 60_000;
            default -> v * 1000 / 60;
        });
    }
}
