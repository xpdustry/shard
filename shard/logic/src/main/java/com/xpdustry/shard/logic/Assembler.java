package com.xpdustry.shard.logic;

import com.xpdustry.shard.content.ContentType;
import com.xpdustry.shard.logic.HostOp.*;
import com.xpdustry.shard.logic.lang.*;

import java.util.*;

import static com.xpdustry.shard.logic.Program.*;

/**
 * mlog text → {@link Program}. Mirrors vanilla's {@code LParser}, the generated {@code LogicIO} (statement fields
 * filled positionally, enums by {@code valueOf}), each statement's {@code build} (the order it names variables in),
 * {@code LAssembler}, and the slot-creating parts of {@code LogicBuild.updateCode} and {@code LExecutor.load}. That
 * creation order is vanilla's variable order, which {@code syncVariable} ids and saves depend on.
 *
 * <p>Unlike vanilla's parser it keeps no static state, so worlds on different threads can assemble at once.
 */
public final class Assembler{
    /** {@code LExecutor.maxInstructions}: the parser silently stops after this many statements. */
    public static final int maxInstructions = 1000;
    static final int maxJumps = 500, maxTokens = 16;

    /**
     * What a program's assembly depends on besides its text: vanilla's {@code LogicBlock.privileged}, the rule
     * {@code allowLogicData} ({@code clientdata} assembles to nothing without it), the names of the links that are
     * valid at assembly, in link order, and whether a building runs it. Two processors with equal options and code
     * share one program.
     *
     * @param script vanilla's {@code LExecutor.runLogicScript}: no building, so {@code updateCode}'s constants
     *     ({@code @links}, {@code @thisx}, {@code @thisy}) don't exist and {@code @ipt} is 0
     */
    public record Options(boolean privileged, boolean allowLogicData, List<String> links, boolean script){
        public Options{
            links = List.copyOf(links);
            if(script && !links.isEmpty()) throw new IllegalArgumentException("a script has no links");
        }

        /** A building's program. */
        public Options(boolean privileged, boolean allowLogicData, List<String> links){
            this(privileged, allowLogicData, links, false);
        }

        public static Options of(boolean privileged){
            return new Options(privileged, false, List.of());
        }

        /** {@code runLogicScript}'s: privileged, with no building. */
        public static Options script(boolean allowLogicData){
            return new Options(true, allowLogicData, List.of(), true);
        }
    }

    private static final Set<String> PRIVILEGED = new TreeSet<>(List.of("query", "getblock", "setblock", "spawn",
    "bullet", "status", "weathersense", "weatherset", "spawnwave", "setrule", "message", "cutscene", "effect",
    "explosion", "fetch", "sync", "clientdata", "getflag", "setflag", "setprop", "playsound", "playmusic", "setmarker",
    "makemarker", "localeprint"));

    private final Globals globals;
    private final Options options;
    private final boolean privileged;
    private final Set<String> statusEffects;

    // the slot table under construction, in vanilla's OrderedMap insertion order
    private final Map<String, Integer> vars = new HashMap<>(); // lookups only; the order lives in the lists
    private final ArrayList<String> names = new ArrayList<>();
    private final ArrayList<Byte> kinds = new ArrayList<>();
    private final ArrayList<Object> values = new ArrayList<>();
    private final ArrayList<Boolean> consts = new ArrayList<>();
    private final ArrayList<Integer> dyn = new ArrayList<>();
    private final Map<String, Integer> globalSlots = new HashMap<>();

    private final ArrayList<int[]> records = new ArrayList<>();
    private final ArrayList<Object> aux = new ArrayList<>();
    private final ArrayList<Boolean> touches = new ArrayList<>();
    /** Whether the statement being built named slot 0 ({@code @counter}). */
    private boolean touchedCounter;
    /** The empty program that stands for code that failed to parse. */
    private boolean malformed;

    private Assembler(Globals globals, Options options, Set<String> statusEffects){
        this.globals = globals;
        this.options = options;
        this.privileged = options.privileged();
        this.statusEffects = statusEffects;
    }

    /**
     * Assembles {@code code} as {@code LogicBuild.updateCode} does. Malformed code (a bad string, a missing space, an
     * undefined or duplicate label, more than 16 tokens on a line, more than 500 labels) assembles to the empty
     * program, as in vanilla. {@code statusEffects} are the status names {@code status} accepts without
     * {@code @status-} (vanilla's {@code LParser.checkRead}).
     */
    public static Program assemble(String code, Options options, Globals globals, Set<String> statusEffects){
        try{
            return new Assembler(globals, options, statusEffects).build(code);
        }catch(ParseException e){
            Assembler empty = new Assembler(globals, options, statusEffects);
            empty.malformed = true;
            return empty.build("");
        }
    }

    private Program build(String code){
        putVar("@counter");
        values.set(0, 0.0); // @counter starts numeric
        putConst("@unit", null);
        putConst("@this", null);

        parse(code);

        List<String> links = options.links();
        int[] linkSlots = new int[links.size()];
        int linksSlot = -1, iptSlot, thisxSlot = -1, thisySlot = -1;
        ArrayList<Integer> export;
        if(options.script()){
            // runLogicScript: no updateCode, so the variables are picked before LExecutor.load puts @ipt (0, no building)
            export = exports();
            iptSlot = putConst("@ipt", 0.0);
        }else{
            for(int i = 0; i < linkSlots.length; i++){
                linkSlots[i] = putConst(links.get(i), null); // the target is set per processor
            }
            linksSlot = putConst("@links", 0.0);
            iptSlot = putConst("@ipt", 0.0);
            thisxSlot = putConst("@thisx", 0.0);
            thisySlot = putConst("@thisy", 0.0);
            export = exports();
        }
        // quirk kept: load adds @queries after it picked the variables, so code that names @queries exports a constant
        int queriesSlot = privileged ? putConst("@queries", null) : -1;

        int n = names.size();
        byte[] slotKind = new byte[n];
        double[] initNum = new double[n];
        Object[] initObj = new Object[n];
        boolean[] initConst = new boolean[n];
        int dynCount = 0;
        for(int i = 0; i < n; i++){
            slotKind[i] = kinds.get(i);
            initConst[i] = consts.get(i);
            Object v = values.get(i);
            if(slotKind[i] == DYN_GLOBAL){
                v = 0.0; // numeric; refreshed when the processor runs
                dynCount++;
            }
            if(v instanceof Double d){
                initNum[i] = d;
                initObj[i] = Rt.NUM;
            }else{
                initObj[i] = v;
            }
        }
        int[] dynSlots = new int[dynCount], dynIds = new int[dynCount];
        for(int i = 0, d = 0; i < n; i++){
            if(slotKind[i] == DYN_GLOBAL){
                dynSlots[d] = i;
                dynIds[d++] = dyn.get(i);
            }
        }

        int len = records.size();
        int[] flat = new int[len * STRIDE];
        boolean[] touchesCounter = new boolean[len];
        for(int i = 0; i < len; i++){
            int[] r = records.get(i);
            System.arraycopy(r, 0, flat, i * STRIDE, r.length);
            touchesCounter[i] = touches.get(i);
        }
        return new Program(options, malformed, len, flat, aux.toArray(), touchesCounter, privileged, names.toArray(String[]::new), slotKind,
        initNum, initObj, initConst, dynSlots, dynIds, export.stream().mapToInt(Integer::intValue).toArray(),
        links.toArray(String[]::new), linkSlots, linksSlot, iptSlot, thisxSlot, thisySlot, queriesSlot);
    }

    /** {@code LExecutor.load} keeps the non-constants, and the constants not named like builtins (@) or literals (_): links. */
    private ArrayList<Integer> exports(){
        ArrayList<Integer> export = new ArrayList<>();
        for(int i = 0; i < names.size(); i++){
            String name = names.get(i);
            if(kinds.get(i) == USER && (!consts.get(i) || name.charAt(0) != '_' && name.charAt(0) != '@')) export.add(i);
        }
        return export;
    }

    //region variables (LAssembler)

    private int putVar(String name){
        Integer existing = vars.get(name);
        if(existing != null) return existing;
        int id = names.size();
        vars.put(name, id);
        names.add(name);
        kinds.add(USER);
        values.add(null); // variables are null objects by default
        consts.add(false);
        dyn.add(-1);
        return id;
    }

    private int putConst(String name, Object value){
        int id = putVar(name);
        values.set(id, value instanceof Number n ? (Object)n.doubleValue() : value);
        consts.set(id, true);
        if(name.startsWith("___")) kinds.set(id, LITERAL);
        return id;
    }

    /** {@code LAssembler.var}: a global, a string or number literal, or a variable. */
    private int var(String symbol){
        int slot = resolve(symbol);
        if(slot == 0) touchedCounter = true;
        return slot;
    }

    private int resolve(String symbol){
        Globals.Entry global = globals.lookup(symbol, privileged);
        if(global != null){
            // vanilla shares one LVar per global; here each program gets a constant slot, outside the variable order
            Integer existing = globalSlots.get(symbol);
            if(existing != null) return existing;
            int id = names.size();
            globalSlots.put(symbol, id);
            names.add("$global:" + symbol);
            kinds.add(global.dynamic() >= 0 ? DYN_GLOBAL : GLOBAL);
            values.add(global.value());
            consts.add(true);
            dyn.add(global.dynamic());
            return id;
        }

        if(symbol.length() > 1 && symbol.charAt(0) == '"' && symbol.charAt(symbol.length() - 1) == '"'){
            return putConst("___" + symbol, unescape(symbol.substring(1, symbol.length() - 1)));
        }

        double value = parseNumber(symbol);
        if(Double.isNaN(value)){
            return putVar(symbol);
        }else{
            if(Double.isInfinite(value)) value = 0.0; // quirk kept: 1e999 is 0, not a huge number
            return putConst("___" + value, value);
        }
    }

    static String unescape(String s){
        if(s.indexOf('\\') == -1) return s;
        StringBuilder out = new StringBuilder(s.length());
        for(int i = 0; i < s.length(); i++){
            char c = s.charAt(i);
            if(c == '\\' && i + 1 < s.length()){
                char next = s.charAt(i + 1);
                if(next == 'n'){
                    out.append('\n');
                    i++;
                    continue;
                }else if(next == '"' || next == '\\'){
                    out.append(next);
                    i++;
                    continue;
                }else if(next == 'u' && i + 5 < s.length()){
                    out.append((char)Integer.parseInt(s.substring(i + 2, i + 6), 16));
                    i += 5;
                    continue;
                }
            }
            out.append(c);
        }
        return out.toString();
    }

    /** {@code LAssembler.parseDouble}: NaN when the symbol is not a number. */
    static double parseNumber(String symbol){
        if(symbol.isEmpty() || !isNumStart(symbol.charAt(0))) return Double.NaN;
        if(symbol.startsWith("0b")) return parseHexOrBin(false, symbol, true, 2);
        if(symbol.startsWith("+0b")) return parseHexOrBin(false, symbol, true, 3);
        if(symbol.startsWith("-0b")) return parseHexOrBin(true, symbol, true, 3);
        if(symbol.startsWith("0x")) return parseHexOrBin(false, symbol, false, 2);
        if(symbol.startsWith("+0x")) return parseHexOrBin(false, symbol, false, 3);
        if(symbol.startsWith("-0x")) return parseHexOrBin(true, symbol, false, 3);
        if(symbol.startsWith("%[") && symbol.endsWith("]") && symbol.length() > 3){
            Double c = LogicColors.get(symbol.substring(2, symbol.length() - 1));
            return c == null ? Double.NaN : c;
        }
        if(symbol.startsWith("%") && (symbol.length() == 7 || symbol.length() == 9)){
            int r = MlogMath.parseInt(symbol, 16, 0, 1, 3), g = MlogMath.parseInt(symbol, 16, 0, 3, 5),
            b = MlogMath.parseInt(symbol, 16, 0, 5, 7), a = symbol.length() == 9 ? MlogMath.parseInt(symbol, 16, 0, 7, 9) : 255;
            return MlogMath.packColor(r, g, b, a);
        }
        return MlogMath.parseDouble(symbol, Double.NaN);
    }

    private static boolean isNumStart(char c){
        return c >= '0' && c <= '9' || c == '.' || c == '-' || c == '+' || c == '%';
    }

    /** Unsigned hex or binary: {@code 0xffffffffffffffff} is -1. */
    private static double parseHexOrBin(boolean negative, String s, boolean binary, int offset){
        int end = s.length();
        if(offset >= end) return Double.NaN;
        int pos = offset;
        while(pos < end && s.charAt(pos) == '0') pos++;
        int shift = binary ? 1 : 4;
        if(end - pos > 64 / shift) return Double.NaN;
        long acc = 0;
        int radix = 1 << shift;
        while(pos < end){
            int digit = Character.digit(s.charAt(pos), radix);
            if(digit < 0) return Double.NaN;
            acc = acc << shift | digit;
            pos++;
        }
        return negative ? -acc : acc;
    }

    //endregion
    //region parser (LParser)

    private static final class ParseException extends RuntimeException{
        ParseException(String message){
            super(message, null, false, false);
        }
    }

    /** A statement field failed {@code valueOf}: vanilla replaces the statement with a noop. */
    private static final class InvalidStatement extends RuntimeException{
        static final InvalidStatement INSTANCE = new InvalidStatement();

        private InvalidStatement(){
            super(null, null, false, false);
        }
    }

    private char[] chars;
    private int pos, line, tok;
    private final String[] tokens = new String[maxTokens];
    private final Map<String, Integer> labels = new HashMap<>(); // lookups only
    private final ArrayList<int[]> pendingJumps = new ArrayList<>();
    private final ArrayList<String> pendingLabels = new ArrayList<>();

    private void parse(String text){
        if(text == null || text.isEmpty()) return;
        chars = text.toCharArray();
        for(int i = 0; i < chars.length; i++) if(chars[i] == '\r') chars[i] = '\n';
        while(pos < chars.length && line < maxInstructions){
            switch(chars[pos]){
                case '\n', ';', ' ' -> pos++;
                default -> statement();
            }
        }
        for(int i = 0; i < pendingJumps.size(); i++){
            Integer at = labels.get(pendingLabels.get(i));
            if(at == null) throw new ParseException("Undefined jump location");
            pendingJumps.get(i)[3] = at;
        }
    }

    private void statement(){
        boolean expectNext = false;
        tok = 0;
        while(pos < chars.length){
            char c = chars[pos];
            if(tok >= tokens.length) throw new ParseException("Line too long");
            if(c == '\n' || c == ';') break;
            if(expectNext && c != ' ' && c != '#' && c != '\t') throw new ParseException("Expected space after string/token.");
            expectNext = false;
            if(c == '#'){
                while(pos < chars.length && chars[pos++] != '\n');
                break;
            }else if(c == '"'){
                tokens[tok++] = string();
                expectNext = true;
            }else if(c != ' ' && c != '\t'){
                tokens[tok++] = token();
                expectNext = true;
            }else{
                pos++;
            }
        }
        if(tok == 0) return;

        // LParser.checkRead (vanilla's token array is static, so it may also rewrite a stale token; that one is unused)
        if(tokens[0].equals("op") && tok > 1){
            if(tokens[1].equals("atan2")) tokens[1] = "angle";
            else if(tokens[1].equals("dst")) tokens[1] = "len";
        }
        if(tokens[0].equals("status") && tok > 2 && statusEffects.contains(tokens[2])){
            tokens[2] = "@status-" + tokens[2];
        }

        if(tok == 1 && tokens[0].charAt(tokens[0].length() - 1) == ':'){
            if(labels.size() >= maxJumps) throw new ParseException("Too many jump locations");
            String label = tokens[0].substring(0, tokens[0].length() - 1);
            if(labels.containsKey(label)) throw new ParseException("Jump label already defined");
            labels.put(label, line);
            return;
        }

        String jumpLoc = null;
        boolean wasJump = tokens[0].equals("jump") && tok > 1 && !MlogMath.canParseInt(tokens[1]);
        if(wasJump){
            jumpLoc = tokens[1];
            tokens[1] = "-1";
        }
        for(int i = 1; i < tok; i++){
            if(tokens[i].equals("@configure")) tokens[i] = "@config";
            if(tokens[i].equals("configure")) tokens[i] = "config";
        }

        int[] rec;
        try{
            rec = emit(tokens[0]);
        }catch(InvalidStatement e){
            rec = noop();
        }
        if(rec != null && wasJump && rec[0] >= JUMP && rec[0] < OP){
            pendingJumps.add(rec);
            pendingLabels.add(jumpLoc);
        }
        line++;
    }

    private String string(){
        int from = pos;
        int utflen = 0;
        while(++pos < chars.length){
            char c = chars[pos];
            if(c == '\\' && pos + 1 < chars.length && (chars[pos + 1] == 'n' || chars[pos + 1] == '"' || chars[pos + 1] == '\\')){
                utflen += utf16size(chars[pos + 1]);
                pos++;
                continue;
            }
            if(c == '\\' && pos + 1 < chars.length && chars[pos + 1] == 'u'){
                if(pos + 5 >= chars.length) throw new ParseException("Invalid \\u escape");
                int value = 0;
                for(int j = pos + 2; j <= pos + 5; j++) value = value << 4 | Character.digit(chars[j], 16);
                if(value < 0) throw new ParseException("Invalid \\u escape");
                utflen += utf16size(value);
                pos += 5;
                continue;
            }
            if(c == '\n') throw new ParseException("Missing closing quote");
            else if(c == '"') break;
            utflen += utf16size(c);
        }
        if(pos >= chars.length || chars[pos] != '"') throw new ParseException("Missing closing quote");
        if(utflen > 65535) throw new ParseException("String value too long.");
        pos++;
        return new String(chars, from, pos - from);
    }

    private static int utf16size(int c){
        return c != 0 && c < 0x80 ? 1 : c < 0x800 ? 2 : 3;
    }

    private String token(){
        int from = pos;
        while(pos < chars.length){
            char c = chars[pos];
            if(c == '\n' || c == ' ' || c == '#' || c == '\t' || c == ';' || c == '"') break;
            pos++;
        }
        return new String(chars, from, pos - from);
    }

    //endregion
    //region statements (LogicIO.read + LStatements.build)

    /** Field {@code i} (1-based, as {@code LogicIO} counts), or its default when the line is shorter. */
    private String t(int i, String def){
        return tok > i ? tokens[i] : def;
    }

    /** An enum field: {@code valueOf} of the token. A failure makes the whole statement a noop, before any variable. */
    private <E extends Enum<E>> E e(Class<E> type, int i, E def){
        if(tok <= i) return def;
        try{
            return Enum.valueOf(type, tokens[i]);
        }catch(IllegalArgumentException e){
            throw InvalidStatement.INSTANCE;
        }
    }

    /** A boolean field: {@code Boolean.valueOf}, which never fails. */
    private boolean bool(int i, boolean def){
        return tok > i ? Boolean.parseBoolean(tokens[i]) : def;
    }

    private int[] add(Object auxValue, int... rec){
        records.add(rec);
        aux.add(auxValue);
        touches.add(touchedCounter);
        touchedCounter = false;
        return rec;
    }

    private int[] noop(){
        touchedCounter = false;
        return add(null, NOOP);
    }

    private int[] host(HostOp op){
        return add(op, HOST);
    }

    /** @return the record, or null for a statement that assembles to nothing ({@code clientdata} without the rule). */
    private int[] emit(String name){
        // privileged statements in a normal processor are noops (whether or not their fields parse)
        if(!privileged && PRIVILEGED.contains(name)) return noop();
        switch(name){
            case "noop": return noop();
            case "read":{
                String output = t(1, "result"), target = t(2, "cell1"), address = t(3, "0");
                int tg = var(target), ad = var(address), out = var(output);
                return add(null, READ, tg, ad, out);
            }
            case "write":{
                String input = t(1, "result"), target = t(2, "cell1"), address = t(3, "0");
                int tg = var(target), ad = var(address), in = var(input);
                return add(null, WRITE, tg, ad, in);
            }
            case "draw":{
                GraphicsType type = e(GraphicsType.class, 1, GraphicsType.clear);
                String x = t(2, "0"), y = t(3, "0"), p1 = t(4, "0"), p2 = t(5, "0"), p3 = t(6, "0"), p4 = t(7, "0");
                // DrawStatement.afterRead
                if(type == GraphicsType.color && p2.equals("0")) p2 = "255"; // a constant alpha of 0 is not allowed
                if(type == GraphicsType.print && Align.of(p1) >= 0) p1 = "@" + p1;
                int sx = var(x), sy = var(y), s1 = var(p1), s2 = var(p2), s3 = var(p3), s4 = var(p4);
                return add(null, DRAW, type.ordinal(), sx, sy, s1, s2, s3, s4);
            }
            case "print": return add(null, PRINT, var(t(1, "\"frog\"")));
            case "printchar": return add(null, PRINTCHAR, var(t(1, "65")));
            case "format": return add(null, FORMAT, var(t(1, "\"frog\"")));
            case "drawflush": return add(null, DRAWFLUSH, var(t(1, "display1")));
            case "printflush": return add(null, PRINTFLUSH, var(t(1, "message1")));
            case "getlink":{
                String output = t(1, "result"), address = t(2, "0");
                int out = var(output), addr = var(address);
                return add(null, GETLINK, out, addr);
            }
            case "control":{
                LAccess type = e(LAccess.class, 1, LAccess.enabled);
                String target = t(2, "block1"), p1 = t(3, "0"), p2 = t(4, "0"), p3 = t(5, "0"), p4 = t(6, "0");
                int tg = var(target), s1 = var(p1), s2 = var(p2), s3 = var(p3), s4 = var(p4);
                return add(type, CONTROL, type.ordinal(), tg, s1, s2, s3, s4);
            }
            case "radar", "uradar":{
                RadarTarget t1 = e(RadarTarget.class, 1, RadarTarget.enemy), t2 = e(RadarTarget.class, 2, RadarTarget.any),
                t3 = e(RadarTarget.class, 3, RadarTarget.any);
                RadarSort sort = e(RadarSort.class, 4, RadarSort.distance);
                String radar = t(5, "turret1"), sortOrder = t(6, "1"), output = t(7, "result");
                // uradar inherits radar's fields but builds with @unit in place of the radar
                int src = var(name.equals("uradar") ? "@unit" : radar), order = var(sortOrder), out = var(output);
                return host(new Radar(t1, t2, t3, sort, src, order, out));
            }
            case "sensor":{
                String to = t(1, "result"), from = t(2, "block1"), type = t(3, "@copper");
                int f = var(from), d = var(to), ty = var(type);
                return add(null, SENSOR, f, d, ty);
            }
            case "set":{
                String to = t(1, "result"), from = t(2, "0");
                int f = var(from), d = var(to);
                return add(null, SET, f, d);
            }
            case "op":{
                LogicOp op = e(LogicOp.class, 1, LogicOp.add);
                String dest = t(2, "result"), a = t(3, "a"), b = t(4, "b");
                int sa = var(a), sb = var(b), sd = var(dest);
                return add(null, OP + op.ordinal(), sa, sb, sd);
            }
            case "select":{
                String result = t(1, "result");
                ConditionOp op = e(ConditionOp.class, 2, ConditionOp.notEqual);
                String comp0 = t(3, "x"), comp1 = t(4, "false"), a = t(5, "a"), b = t(6, "b");
                int r = var(result), c0 = var(comp0), c1 = var(comp1), sa = var(a), sb = var(b);
                return add(null, SELECT, op.ordinal(), r, c0, c1, sa, sb);
            }
            case "wait": return add(null, WAIT, var(t(1, "0.5")));
            case "stop": return add(null, STOP);
            case "lookup":{
                // vanilla's ContentType by name, kept as its ordinal
                int type = tok > 1 ? ContentType.VANILLA_NAMES.indexOf(tokens[1]) : ContentType.ITEM.vanillaOrdinal();
                if(type < 0) throw InvalidStatement.INSTANCE;
                String result = t(2, "result"), id = t(3, "0");
                int r = var(result), from = var(id);
                return add(null, LOOKUP, r, from, type);
            }
            case "packcolor":{
                String result = t(1, "result"), r = t(2, "1"), g = t(3, "0"), b = t(4, "0"), a = t(5, "1");
                int sr = var(result), cr = var(r), cg = var(g), cb = var(b), ca = var(a);
                return add(null, PACKCOLOR, sr, cr, cg, cb, ca);
            }
            case "unpackcolor":{
                String r = t(1, "r"), g = t(2, "g"), b = t(3, "b"), a = t(4, "a"), value = t(5, "color");
                int sr = var(r), sg = var(g), sb = var(b), sa = var(a), v = var(value);
                return add(null, UNPACKCOLOR, sr, sg, sb, sa, v);
            }
            case "end": return add(null, END);
            case "jump":{
                int dest;
                try{
                    dest = tok > 1 ? Integer.valueOf(tokens[1]) : 0;
                }catch(NumberFormatException e){
                    throw InvalidStatement.INSTANCE;
                }
                ConditionOp op = e(ConditionOp.class, 2, ConditionOp.notEqual);
                String value = t(3, "x"), compare = t(4, "false");
                int v = var(value), c = var(compare);
                return add(null, JUMP + op.ordinal(), v, c, dest);
            }
            case "ubind": return host(new UnitBind(var(t(1, "@poly"))));
            case "ucontrol":{
                LUnitControl type = e(LUnitControl.class, 1, LUnitControl.move);
                String p1 = t(2, "0"), p2 = t(3, "0"), p3 = t(4, "0"), p4 = t(5, "0"), p5 = t(6, "0");
                int s1 = var(p1), s2 = var(p2), s3 = var(p3), s4 = var(p4), s5 = var(p5);
                return host(new UnitControl(type, s1, s2, s3, s4, s5));
            }
            case "ulocate":{
                LLocate locate = e(LLocate.class, 1, LLocate.building);
                BlockFlag flag = e(BlockFlag.class, 2, BlockFlag.core);
                String enemy = t(3, "true"), ore = t(4, "@copper"), outX = t(5, "outx"), outY = t(6, "outy"),
                outFound = t(7, "found"), outBuild = t(8, "building");
                int se = var(enemy), so = var(ore), sx = var(outX), sy = var(outY), sf = var(outFound), sb = var(outBuild);
                return host(new UnitLocate(locate, flag, se, so, sx, sy, sf, sb));
            }
            case "query":{
                QueryShape shape = e(QueryShape.class, 1, QueryShape.circle);
                QueryType type = e(QueryType.class, 2, QueryType.unit);
                String team = t(3, "null"), x = t(4, "0"), y = t(5, "0"), w = t(6, "10"), h = t(7, "10");
                int st = var(team), sx = var(x), sy = var(y), sw = var(w), sh = var(h);
                return host(new Query(shape, type, st, sx, sy, sw, sh));
            }
            case "getblock":{
                TileLayer layer = e(TileLayer.class, 1, TileLayer.block);
                String result = t(2, "result"), x = t(3, "0"), y = t(4, "0");
                int sx = var(x), sy = var(y), r = var(result);
                return host(new GetBlock(layer, sx, sy, r));
            }
            case "setblock":{
                TileLayer layer = e(TileLayer.class, 1, TileLayer.block);
                String block = t(2, "@air"), x = t(3, "0"), y = t(4, "0"), team = t(5, "@derelict"), rotation = t(6, "0");
                int sx = var(x), sy = var(y), sb = var(block), st = var(team), sr = var(rotation);
                return host(new SetBlock(layer, sx, sy, sb, st, sr));
            }
            case "spawn":{
                String type = t(1, "@dagger"), x = t(2, "10"), y = t(3, "10"), rotation = t(4, "90"), team = t(5, "@sharded"),
                result = t(6, "result"), effect = t(7, "true");
                int s0 = var(type), s1 = var(x), s2 = var(y), s3 = var(rotation), s4 = var(team), s5 = var(result), s6 = var(effect);
                return host(new SpawnUnit(s0, s1, s2, s3, s4, s5, s6));
            }
            case "bullet":{
                int[] s = vars(t(1, "result"), t(2, "@dagger"), t(3, "0"), t(4, "x"), t(5, "y"), t(6, "angle"), t(7, "null"),
                t(8, "null"), t(9, "-1"), t(10, "1"), t(11, "1"), t(12, "-1"), t(13, "-1"));
                return host(new SpawnBullet(s[0], s[1], s[2], s[3], s[4], s[5], s[6], s[7], s[8], s[9], s[10], s[11], s[12]));
            }
            case "status":{
                boolean clear = bool(1, false);
                String effect = t(2, "@status-wet"), unit = t(3, "unit"), duration = t(4, "10");
                int se = var(effect), su = var(unit), sd = var(duration);
                return host(new ApplyStatus(clear, se, su, sd));
            }
            case "weathersense":{
                String to = t(1, "result"), weather = t(2, "@rain");
                int sw = var(weather), st = var(to);
                return host(new WeatherSense(sw, st));
            }
            case "weatherset":{
                String weather = t(1, "@rain"), state = t(2, "true");
                int sw = var(weather), ss = var(state);
                return host(new WeatherSet(sw, ss));
            }
            case "spawnwave":{
                String x = t(1, "10"), y = t(2, "10"), natural = t(3, "false");
                int sn = var(natural), sx = var(x), sy = var(y);
                return host(new SpawnWave(sn, sx, sy));
            }
            case "setrule":{
                LogicRule rule = e(LogicRule.class, 1, LogicRule.waveSpacing);
                String value = t(2, "10"), p1 = t(3, "0"), p2 = t(4, "0"), p3 = t(5, "100"), p4 = t(6, "100");
                int sv = var(value), s1 = var(p1), s2 = var(p2), s3 = var(p3), s4 = var(p4);
                return host(new SetRule(rule, sv, s1, s2, s3, s4));
            }
            case "message":{
                MessageType type = e(MessageType.class, 1, MessageType.announce);
                String duration = t(2, "3"), outSuccess = t(3, "@wait");
                int sd = var(duration), so = var(outSuccess);
                return host(new Message(type, sd, so));
            }
            case "cutscene":{
                CutsceneAction action = e(CutsceneAction.class, 1, CutsceneAction.pan);
                String p1 = t(2, "100"), p2 = t(3, "100"), p3 = t(4, "0.06"), p4 = t(5, "0");
                int s1 = var(p1), s2 = var(p2), s3 = var(p3), s4 = var(p4);
                return host(new Cutscene(action, s1, s2, s3, s4));
            }
            case "effect":{
                String type = t(1, "warn");
                int[] s = vars(t(2, "0"), t(3, "0"), t(4, "2"), t(5, "%ffaaff"), t(6, ""));
                return host(new Effect(type, s[0], s[1], s[2], s[3], s[4]));
            }
            case "explosion":{
                int[] s = vars(t(1, "@crux"), t(2, "0"), t(3, "0"), t(4, "5"), t(5, "50"), t(6, "true"), t(7, "true"),
                t(8, "false"), t(9, "true"));
                return host(new Explosion(s[0], s[1], s[2], s[3], s[4], s[5], s[6], s[7], s[8]));
            }
            case "setrate": return add(null, SETRATE, var(t(1, "10")));
            case "fetch":{
                FetchType type = e(FetchType.class, 1, FetchType.unit);
                String result = t(2, "result"), team = t(3, "@sharded"), index = t(4, "0"), extra = t(5, "@conveyor");
                int sr = var(result), st = var(team), se = var(extra), si = var(index);
                return host(new Fetch(type, sr, st, se, si));
            }
            case "sync": return host(new Sync(var(t(1, "var"))));
            case "clientdata":{
                if(!options.allowLogicData()) return null; // quirk kept: no instruction at all, so later jumps shift
                String channel = t(1, "\"frog\""), value = t(2, "\"bar\""), reliable = t(3, "0");
                int sc = var(channel), sv = var(value), sr = var(reliable);
                return host(new ClientData(sc, sv, sr));
            }
            case "getflag":{
                String result = t(1, "result"), flag = t(2, "\"flag\"");
                int sr = var(result), sf = var(flag);
                return host(new GetFlag(sr, sf));
            }
            case "setflag":{
                String flag = t(1, "\"flag\""), value = t(2, "true");
                int sf = var(flag), sv = var(value);
                return host(new SetFlag(sf, sv));
            }
            case "setprop":{
                String type = t(1, "@copper"), of = t(2, "block1"), value = t(3, "0");
                int st = var(type), so = var(of), sv = var(value);
                return host(new SetProp(st, so, sv));
            }
            case "playsound":{
                boolean positional = bool(1, false);
                int[] s = vars(t(2, "@sfx-shoot"), t(3, "1"), t(4, "1"), t(5, "0"), t(6, "@thisx"), t(7, "@thisy"), t(8, "true"));
                return host(new PlaySound(positional, s[0], s[1], s[2], s[3], s[4], s[5], s[6]));
            }
            case "playmusic":{
                String music = t(1, "\"game1\""), interrupt = t(2, "true");
                int sn = var(music), si = var(interrupt);
                return host(new PlayMusic(sn, si));
            }
            case "setmarker":{
                LMarkerControl type = e(LMarkerControl.class, 1, LMarkerControl.pos);
                String id = t(2, "0"), p1 = t(3, "0"), p2 = t(4, "0"), p3 = t(5, "0");
                int si = var(id), s1 = var(p1), s2 = var(p2), s3 = var(p3);
                return host(new SetMarker(type, si, s1, s2, s3));
            }
            case "makemarker":{
                String type = t(1, "shape"), id = t(2, "0"), x = t(3, "0"), y = t(4, "0"), replace = t(5, "true");
                int si = var(id), sx = var(x), sy = var(y), sr = var(replace);
                return host(new MakeMarker(type, si, sx, sy, sr));
            }
            case "localeprint": return host(new LocalePrint(var(t(1, "\"name\""))));
            default: return noop(); // an unknown statement
        }
    }

    /** Slots for {@code symbols}, named in order. */
    private int[] vars(String... symbols){
        int[] out = new int[symbols.length];
        for(int i = 0; i < symbols.length; i++) out[i] = var(symbols[i]);
        return out;
    }

    //endregion
}
