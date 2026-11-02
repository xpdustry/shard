package com.xpdustry.shard.content.load;

import com.xpdustry.shard.content.*;
import com.xpdustry.shard.content.yaml.*;

import java.util.*;

/**
 * The wire id order ({@code ids.yml}): per content type, names in id order, as the vanilla client numbers them. Also
 * the unit commands and stances (referenced by name) and the effects every client has, so patches can tell vanilla
 * effects from ones they would create.
 */
public final class Ids{
    private final Map<String, List<String>> lists;

    private Ids(Map<String, List<String>> lists){
        this.lists = lists;
    }

    public static Ids of(Map<String, List<String>> lists){
        Map<String, List<String>> copy = new LinkedHashMap<>();
        lists.forEach((k, v) -> copy.put(k, List.copyOf(v)));
        return new Ids(Collections.unmodifiableMap(copy));
    }

    static Ids read(Node root, Diagnostics diagnostics){
        Map<String, List<String>> lists = new LinkedHashMap<>();
        if(!(root instanceof Node.Mapping m)){
            diagnostics.error(root, "ids are a mapping type: [names]");
            return of(lists);
        }
        m.entries().forEach((type, node) -> {
            List<String> names = new ArrayList<>();
            if(node instanceof Node.Sequence seq){
                for(Node n : seq.items()){
                    if(n instanceof Node.Scalar s) names.add(s.value());
                    else diagnostics.error(n, "expected a name");
                }
            }else{
                diagnostics.error(node, "ids of " + type + " are a list of names");
            }
            lists.put(type, names);
        });
        return of(lists);
    }

    /** Names of {@code type} in wire id order. */
    public List<String> of(ContentType type){
        return lists.getOrDefault(type.yamlName(), List.of());
    }

    /** A list by its {@code ids.yml} key ({@code unit-command}, {@code unit-stance}, {@code effect}). */
    public List<String> list(String key){
        return lists.getOrDefault(key, List.of());
    }
}
