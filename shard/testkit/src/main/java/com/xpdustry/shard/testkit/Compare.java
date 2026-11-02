package com.xpdustry.shard.testkit;

import com.xpdustry.shard.testkit.Flat.*;
import com.xpdustry.shard.testkit.Tolerances.*;

import java.util.*;

/** Compares two {@link Flat}s under {@link Tolerances}; {@link Notes} collects what a whole run left uncompared. */
final class Compare{
    /** Differences kept per checkpoint; the report shows fewer. */
    static final int LIMIT = 500;

    private final Tolerances tolerances;
    final Notes notes = new Notes();

    Compare(Tolerances tolerances){
        this.tolerances = tolerances;
    }

    /** What a run didn't compare, by reason. */
    static final class Notes{
        /** Kind fields only the reference projects, by block: the candidate hasn't ported them. */
        final TreeMap<String, TreeSet<String>> referenceOnly = new TreeMap<>();
        /** Sections a side doesn't project, with the engines that don't. */
        final TreeMap<Scope, TreeSet<String>> absent = new TreeMap<>();

        List<String> lines(String reference, String candidate){
            var out = new ArrayList<String>();
            if(!absent.isEmpty()){
                var by = new TreeMap<String, List<String>>();
                absent.forEach((scope, sides) -> by.computeIfAbsent(String.join(", ", sides), s -> new ArrayList<>()).add(scope.toString()));
                by.forEach((side, scopes) -> out.add(String.join(", ", scopes) + " (not projected by " + side + ")"));
            }
            if(!referenceOnly.isEmpty()){
                var parts = new ArrayList<String>();
                referenceOnly.forEach((block, fields) -> parts.add(block + ": " + String.join(", ", fields)));
                out.add("kind fields only " + reference + " projects (" + String.join("; ", parts) + ")");
            }
            return out;
        }
    }

    /** The differences at one checkpoint; {@code previous} (or null) gives each its {@link Difference#before()}. */
    List<Difference> compare(Flat reference, Flat candidate, Flat previous){
        var out = compare(reference, candidate);
        if(previous == null) return out;
        var keys = new HashMap<String, Key>();
        for(Entity entity : previous.entities()) keys.put(entity.key().toString(), entity.key());
        out.replaceAll(d -> {
            Key key = keys.get(d.entity());
            Entity before = key == null ? null : previous.entity(key);
            if(before == null) return d;
            if(d.path().isEmpty()) return d.withBefore(exists(before));
            return before.leaves().containsKey(d.path()) ? d.withBefore(before.leaves().get(d.path())) : d;
        });
        return out;
    }

    List<Difference> compare(Flat reference, Flat candidate){
        for(Scope scope : reference.absent()) notes.absent.computeIfAbsent(scope, s -> new TreeSet<>()).add(reference.engine());
        for(Scope scope : candidate.absent()) notes.absent.computeIfAbsent(scope, s -> new TreeSet<>()).add(candidate.engine());
        var out = new ArrayList<Difference>();
        var keys = new TreeSet<Key>(reference.map().keySet());
        keys.addAll(candidate.map().keySet());
        for(Key key : keys){
            if(out.size() >= LIMIT) break;
            Entity r = reference.entity(key), c = candidate.entity(key);
            if(r == null || c == null){
                Scope scope = key.scope();
                if(r == null && reference.absent().contains(scope) || c == null && candidate.absent().contains(scope)) continue;
                Entity present = r != null ? r : c;
                Rule rule = tolerances.resolve(present, "");
                if(rule.op() == Op.IGNORE) continue;
                out.add(new Difference(reference.tick(), key.toString(), present.label(), "", r == null ? Difference.ABSENT : exists(r),
                    c == null ? Difference.ABSENT : exists(c), "exists", Difference.ABSENT));
                continue;
            }
            if(key.scope() == Scope.BUILDING && !Objects.equals(r.label(), c.label())){
                Rule rule = tolerances.resolve(r, "block");
                if(rule.op() == Op.IGNORE) continue;
                out.add(new Difference(reference.tick(), key.toString(), r.label(), "block", r.label(), c.label(), rule.describe(), Difference.ABSENT));
                continue;
            }
            entity(reference.tick(), r, c, out);
        }
        return out;
    }

    private void entity(long tick, Entity r, Entity c, List<Difference> out){
        Set<String> candidateRoots = null;
        Map<String, Object> cl = c.leaves();
        for(var leaf : r.leaves().entrySet()){
            String path = leaf.getKey();
            Object a = leaf.getValue();
            Object b = cl.containsKey(path) ? cl.get(path) : Difference.ABSENT;
            if(b == Difference.ABSENT){
                if(zeroByDefault(r.key().scope(), path)){
                    b = 0;
                }else if(isField(path)){
                    if(candidateRoots == null) candidateRoots = roots(c);
                    if(!candidateRoots.contains(root(path))){
                        Rule rule = tolerances.resolve(r, path);
                        if(rule.op() == Op.IGNORE) continue;
                        if(tolerances.missingFields() == MissingFields.NOTE){
                            notes.referenceOnly.computeIfAbsent(String.valueOf(r.label()), x -> new TreeSet<>()).add(root(path).substring(7));
                            continue;
                        }
                    }
                }
            }
            check(tick, r, path, a, b, out);
        }
        for(var leaf : cl.entrySet()){
            String path = leaf.getKey();
            if(r.leaves().containsKey(path)) continue;
            Object a = zeroByDefault(r.key().scope(), path) ? (Object)0 : Difference.ABSENT;
            check(tick, r, path, a, leaf.getValue(), out);
        }
    }

    private void check(long tick, Entity r, String path, Object a, Object b, List<Difference> out){
        Rule rule = tolerances.resolve(r, path);
        if(rule.op() == Op.IGNORE) return;
        if(a != Difference.ABSENT && b != Difference.ABSENT && rule.accepts(a, b)) return;
        out.add(new Difference(tick, r.key().toString(), r.label(), path, a, b, rule.describe(), Difference.ABSENT));
    }

    private static String exists(Entity entity){
        return entity.label() == null ? "present" : "present (" + entity.label() + ")";
    }

    static boolean zeroByDefault(Scope scope, String path){
        return scope == Scope.CORE || scope == Scope.BUILDING && (path.startsWith("items.") || path.startsWith("liquids."));
    }

    static boolean isField(String path){
        return path.startsWith("fields.");
    }

    /** {@code fields.ys[2]} → {@code fields.ys}. */
    static String root(String path){
        int end = 7;
        while(end < path.length() && path.charAt(end) != '.' && path.charAt(end) != '[') end++;
        return path.substring(0, end);
    }

    private static Set<String> roots(Entity entity){
        var out = new HashSet<String>();
        for(String path : entity.leaves().keySet()){
            if(isField(path)) out.add(root(path));
        }
        return out;
    }
}
