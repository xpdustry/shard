package com.xpdustry.shard.testkit;

import com.xpdustry.shard.testkit.Flat.*;

import java.util.*;
import java.util.concurrent.*;

/**
 * What the diff accepts as equal. Immutable: every method returns a new instance. Start from {@link #exact()}.
 *
 * <p>A rule applies to the leaves its pattern matches; when several match, <b>the last one added wins</b>, so put
 * broad rules first and exceptions after. Patterns:
 * <pre>
 *   building.health                     every building's health
 *   building[conveyor].fields.ys        one block (by name); a prefix also covers fields.ys[0], fields.ys[1]…
 *   building[kind=item-turret].fields   one kind (Shard's kind key; vanilla's block class in kebab case)
 *   building(3,4)                       one building, all its leaves, and whether it exists at all
 *   unit[dagger].x   unit#12   unit     a unit type, one unit id, every unit
 *   core[sharded].copper   rules.waveSpacing   wave.wavetime   world
 *   *.health   building.fields.*.x      * matches any scope or any one path segment
 * </pre>
 * Numbers compare as their values whatever their boxed type ({@link Values#same}); missing item, liquid and core
 * amounts count as zero.
 */
public final class Tolerances{
    public enum Op{
        /** Not compared. */
        IGNORE,
        /** Equal values only; use it to take a leaf back out of a broader rule. */
        EXACT,
        /** {@code |a - b| <= amount}. */
        ABS,
        /** {@code |a - b| <= amount × max(|a|, |b|)}. */
        REL
    }

    /** What to do with a kind field only the reference side projects (a field the candidate hasn't ported yet). */
    public enum MissingFields{
        /** List it under "not compared" in the report; don't fail. */
        NOTE,
        /** Fail. */
        FAIL
    }

    public record Rule(Op op, String pattern, double amount){
        public Rule{
            Objects.requireNonNull(op, "op");
            Objects.requireNonNull(pattern, "pattern");
            if((op == Op.ABS || op == Op.REL) && !(amount >= 0)) throw new IllegalArgumentException("tolerance must be ≥ 0: " + amount);
        }

        public boolean accepts(Object reference, Object candidate){
            return switch(op){
                case IGNORE -> true;
                case EXACT -> Values.same(reference, candidate);
                case ABS, REL -> {
                    if(Values.same(reference, candidate)) yield true;
                    if(!(reference instanceof Number a) || !(candidate instanceof Number b)) yield false;
                    double x = a.doubleValue(), y = b.doubleValue(), delta = Math.abs(x - y);
                    yield op == Op.ABS ? delta <= amount : delta <= amount * Math.max(Math.abs(x), Math.abs(y));
                }
            };
        }

        /** {@code exact}, {@code ±0.001}, {@code ±1%} or {@code ignored}. */
        public String describe(){
            return switch(op){
                case IGNORE -> "ignored";
                case EXACT -> "exact";
                case ABS -> "±" + amount;
                case REL -> "±" + amount * 100 + "%";
            };
        }
    }

    static final Rule EXACT = new Rule(Op.EXACT, "", 0);
    private static final Tolerances EXACT_ALL = new Tolerances(List.of(), UnitMatch.ID, MissingFields.NOTE);

    private final List<Rule> rules;
    private final List<Pattern> patterns;
    private final UnitMatch units;
    private final MissingFields missingFields;
    private final boolean pinpoint;
    private final ConcurrentHashMap<CacheKey, Rule> cache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String[]> segments = new ConcurrentHashMap<>();

    private Tolerances(List<Rule> rules, UnitMatch units, MissingFields missingFields){
        this.rules = List.copyOf(rules);
        this.patterns = this.rules.stream().map(rule -> Pattern.parse(rule.pattern())).toList();
        this.units = units;
        this.missingFields = missingFields;
        this.pinpoint = patterns.stream().anyMatch(p -> p.at != null);
    }

    /** Every leaf exact, units by id, missing kind fields noted. */
    public static Tolerances exact(){
        return EXACT_ALL;
    }

    /**
     * The documented exception to vanilla's determinism (the oracle README): ground units steer by flowfields that
     * vanilla's {@code Pathfinder} threads build on their own clock, so two vanilla runs disagree on where those units
     * walk. This ignores the position and rotation of units of {@code types}; everything else stays compared.
     */
    public Tolerances unitPaths(String... types){
        Tolerances out = this;
        for(String type : types){
            out = out.ignore("unit[" + type + "].x").ignore("unit[" + type + "].y").ignore("unit[" + type + "].rotation");
        }
        return out;
    }

    public Tolerances ignore(String pattern){
        return with(new Rule(Op.IGNORE, pattern, 0));
    }

    public Tolerances exactly(String pattern){
        return with(new Rule(Op.EXACT, pattern, 0));
    }

    public Tolerances abs(String pattern, double amount){
        return with(new Rule(Op.ABS, pattern, amount));
    }

    /** A relative tolerance: {@code 0.01} is 1%. */
    public Tolerances rel(String pattern, double fraction){
        return with(new Rule(Op.REL, pattern, fraction));
    }

    public Tolerances with(Rule rule){
        var list = new ArrayList<>(rules);
        list.add(rule);
        return new Tolerances(list, units, missingFields);
    }

    /** Every rule of {@code other} after this one's, and other's unit and field policies. */
    public Tolerances with(Tolerances other){
        var list = new ArrayList<>(rules);
        list.addAll(other.rules);
        return new Tolerances(list, other.units, other.missingFields);
    }

    public Tolerances units(UnitMatch units){
        return new Tolerances(rules, Objects.requireNonNull(units), missingFields);
    }

    public Tolerances missingFields(MissingFields missingFields){
        return new Tolerances(rules, units, Objects.requireNonNull(missingFields));
    }

    public List<Rule> rules(){
        return rules;
    }

    public UnitMatch units(){
        return units;
    }

    public MissingFields missingFields(){
        return missingFields;
    }

    /** The rule for one leaf of {@code entity}; {@code path} "" stands for the entity's existence. */
    Rule resolve(Entity entity, String path){
        var key = new CacheKey(entity.key().scope(), entity.label(), entity.kind(), pinpoint ? entity.key() : null, path);
        Rule rule = cache.get(key);
        if(rule == null){
            String[] leaf = segments.computeIfAbsent(path, Pattern::split);
            rule = EXACT;
            for(int i = patterns.size() - 1; i >= 0; i--){
                if(patterns.get(i).matches(entity, leaf)){
                    rule = rules.get(i);
                    break;
                }
            }
            cache.put(key, rule);
        }
        return rule;
    }

    @Override
    public boolean equals(Object o){
        return o instanceof Tolerances t && rules.equals(t.rules) && units == t.units && missingFields == t.missingFields;
    }

    @Override
    public int hashCode(){
        return Objects.hash(rules, units, missingFields);
    }

    @Override
    public String toString(){
        return "Tolerances[" + rules + ", units " + units + ", missing fields " + missingFields + "]";
    }

    private record CacheKey(Scope scope, String label, String kind, Key at, String path){
    }

    /** A parsed pattern; null parts match anything. */
    private record Pattern(Scope scope, String label, String kind, Key at, String[] field){
        static Pattern parse(String text){
            try{
                return parse0(text);
            }catch(RuntimeException e){
                throw new IllegalArgumentException("bad tolerance pattern '" + text + "': " + e.getMessage(), e);
            }
        }

        private static Pattern parse0(String text){
            int i = 0, n = text.length();
            while(i < n && "[(#.".indexOf(text.charAt(i)) < 0) i++;
            String word = text.substring(0, i);
            Scope scope = word.equals("*") ? null : Scope.of(word);
            String label = null, kind = null;
            Key at = null;
            if(i < n && text.charAt(i) == '['){
                int end = text.indexOf(']', i);
                if(end < 0) throw new IllegalArgumentException("unclosed [");
                String selector = text.substring(i + 1, end);
                if(selector.startsWith("kind=")) kind = selector.substring(5);
                else if(!selector.equals("*")) label = selector;
                i = end + 1;
            }
            if(i < n && text.charAt(i) == '('){
                int end = text.indexOf(')', i);
                if(end < 0) throw new IllegalArgumentException("unclosed (");
                if(scope != Scope.BUILDING) throw new IllegalArgumentException("(x,y) only selects buildings");
                String[] xy = text.substring(i + 1, end).split(",");
                at = Key.building(Integer.parseInt(xy[0].strip()), Integer.parseInt(xy[1].strip()));
                i = end + 1;
            }else if(i < n && text.charAt(i) == '#'){
                if(scope != Scope.UNIT) throw new IllegalArgumentException("#id only selects units");
                int end = i + 1;
                while(end < n && Character.isDigit(text.charAt(end))) end++;
                at = Key.unit(Integer.parseInt(text.substring(i + 1, end)));
                i = end;
            }
            String[] field = {};
            if(i < n){
                if(text.charAt(i) != '.') throw new IllegalArgumentException("expected '.' at " + i);
                field = split(text.substring(i + 1));
            }
            return new Pattern(scope, label, kind, at, field);
        }

        /** {@code fields.ys[2]} → {@code fields}, {@code ys}, {@code [2]}. */
        static String[] split(String path){
            if(path.isEmpty()) return new String[0];
            var out = new ArrayList<String>();
            for(String part : path.split("\\.", -1)){
                int bracket = part.indexOf('[');
                if(bracket < 0){
                    out.add(part);
                    continue;
                }
                if(bracket > 0) out.add(part.substring(0, bracket));
                for(int start = bracket; start < part.length(); ){
                    int end = part.indexOf(']', start);
                    if(end < 0) end = part.length() - 1;
                    out.add(part.substring(start, end + 1));
                    start = end + 1;
                }
            }
            return out.toArray(String[]::new);
        }

        boolean matches(Entity entity, String[] leaf){
            if(scope != null && scope != entity.key().scope()) return false;
            if(label != null && !label.equals(entity.label())) return false;
            if(kind != null && !kind.equals(entity.kind())) return false;
            if(at != null && !at.equals(entity.key())) return false;
            if(field.length > leaf.length) return false;
            for(int i = 0; i < field.length; i++){
                if(!field[i].equals("*") && !field[i].equals(leaf[i])) return false;
            }
            return true;
        }
    }
}
