package com.xpdustry.shard.tools.extract;

import com.xpdustry.shard.content.*;

import java.util.*;

/**
 * Prints where Shard's bake differs from vanilla, grouped by kind and field: a development aid for porting
 * {@code init()} code; {@link ContentGatesTest#bakeMatchesVanilla} is the gate. Arguments: optional content types to check.
 */
public final class BakeDiff{
    public static void main(String[] args){
        var fidelity = new Fidelity();
        fidelity.diagnostics.forEach(d -> System.out.println("bake error: " + d));
        Set<String> only = new HashSet<>(Arrays.asList(args));
        Map<String, List<String>> groups = new TreeMap<>();
        for(ContentType type : ContentType.values()){
            if(!only.isEmpty() && !only.contains(type.yamlName())) continue;
            int n = Math.max(fidelity.vanillaCount(type), fidelity.baked.of(type).size());
            for(int id = 0; id < n; id++){
                List<String> diffs = new ArrayList<>();
                Map<String, Object> v = id < fidelity.vanillaCount(type) ? fidelity.vanilla(type, id) : null;
                Map<String, Object> s = id < fidelity.baked.of(type).size() ? fidelity.shard(type, id) : null;
                String name = type.yamlName() + " " + (s != null ? fidelity.baked.names(type).get(id) : "#" + id);
                Fidelity.diff(name, v, s, diffs);
                for(String d : diffs){
                    String kind = v == null ? "?" : String.valueOf(v.get("kind"));
                    String field = d.substring(name.length()).replaceAll("\\.\\d+", ".#").replaceAll(":.*", "");
                    groups.computeIfAbsent(type.yamlName() + " " + kind + field, k -> new ArrayList<>()).add(d);
                }
            }
        }
        int total = 0;
        for(var e : groups.entrySet()){
            total += e.getValue().size();
            System.out.println(e.getKey() + " ×" + e.getValue().size());
            for(String d : e.getValue().subList(0, Math.min(2, e.getValue().size()))) System.out.println("    " + d);
        }
        System.out.println(total + " differences in " + groups.size() + " groups");
        System.exit(0);
    }
}
