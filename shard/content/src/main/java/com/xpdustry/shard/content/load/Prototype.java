package com.xpdustry.shard.content.load;

import com.xpdustry.shard.content.*;
import com.xpdustry.shard.content.yaml.*;

import java.util.*;

/**
 * One YAML document of a pack, before inheritance and typing.
 *
 * <pre>{@code
 * - type: block            # content type: item block bullet liquid status unit weather
 *   id: plastanium-wall    # the name; bullets hoisted out of their owner have path ids (duo/ammoTypes.copper)
 *   parent: [wall-base]    # optional, one name or a list; SS14 rules: the first parent wins over later ones
 *   abstract: true         # optional: only a parent, never content
 *   kind: wall             # the kind; inherited when absent
 *   wire: added            # vanilla (the default for ids.yml names) or added (new content, ids after vanilla's)
 *   health: 760            # fields of the kind
 * - type: block
 *   id: duo
 *   overlay: true          # changes the prototype above instead of defining one (worlds, mods)
 *   remove: [shootY]       # optional, in overlays: fields to drop, so they fall back to the parent or default
 *   reload: 15
 * }</pre>
 *
 * @param fields the document without its reserved keys
 */
record Prototype(ContentType type, String id, List<String> parents, boolean isAbstract, boolean overlay, List<String> remove,
                 Wire wire, Node.Mapping fields, Mark mark){
    /** Whether the client knows the content ({@code vanilla}, its id from {@code ids.yml}) or it is new ({@code added}). */
    enum Wire{
        vanilla, added
    }

    /** Keys that are not fields. {@code kind} is in {@link #fields}, since it is inherited and merged like one. */
    static final Set<String> reserved = Set.of("type", "id", "parent", "abstract", "overlay", "remove", "wire");

    /** Reads a document; null (with diagnostics) when it isn't one. */
    static Prototype read(Node node, Diagnostics diagnostics){
        if(!(node instanceof Node.Mapping m)){
            diagnostics.error(node, "a prototype is a mapping with type and id");
            return null;
        }
        String typeName = scalar(m, "type", diagnostics);
        String id = scalar(m, "id", diagnostics);
        if(typeName == null || id == null){
            if(typeName == null && !m.has("type")) diagnostics.error(m, "a prototype needs a type");
            if(id == null && !m.has("id")) diagnostics.error(m, "a prototype needs an id");
            return null;
        }
        ContentType type = ContentType.of(typeName);
        if(type == null){
            List<String> known = Arrays.stream(ContentType.values()).map(ContentType::yamlName).toList();
            diagnostics.error(m.get("type"), "unknown content type '" + typeName + "'; one of " + known);
            return null;
        }
        List<String> parents = names(m, "parent", diagnostics);
        List<String> remove = names(m, "remove", diagnostics);
        boolean isAbstract = flag(m, "abstract", diagnostics), overlay = flag(m, "overlay", diagnostics);
        Wire wire = null;
        String wireName = scalar(m, "wire", diagnostics);
        if(wireName != null){
            try{
                wire = Wire.valueOf(wireName);
            }catch(IllegalArgumentException e){
                diagnostics.error(m.get("wire"), id + ": wire is vanilla or added, not '" + wireName + "'");
            }
            if(overlay) diagnostics.error(m.keyMark("wire"), id + ": an overlay can't change wire");
        }
        if(overlay && isAbstract) diagnostics.error(m.keyMark("abstract"), id + ": an overlay can't be abstract");
        if(overlay && !parents.isEmpty()) diagnostics.error(m.keyMark("parent"), id + ": an overlay can't change parents");
        if(!overlay && !remove.isEmpty()) diagnostics.error(m.keyMark("remove"), id + ": remove is for overlays");
        Node.Mapping fields = m;
        for(String key : reserved) fields = fields.without(key);
        return new Prototype(type, id, parents, isAbstract, overlay, remove, wire, fields, m.mark());
    }

    private static String scalar(Node.Mapping m, String key, Diagnostics diagnostics){
        Node n = m.get(key);
        if(n == null) return null;
        if(n instanceof Node.Scalar s && !s.isNull() && !s.value().isBlank()) return s.value();
        diagnostics.error(n, key + " must be a name");
        return null;
    }

    private static boolean flag(Node.Mapping m, String key, Diagnostics diagnostics){
        Node n = m.get(key);
        if(n == null) return false;
        if(n instanceof Node.Scalar s && !s.quoted() && (s.value().equals("true") || s.value().equals("false"))) return s.value().equals("true");
        diagnostics.error(n, key + " must be true or false");
        return false;
    }

    private static List<String> names(Node.Mapping m, String key, Diagnostics diagnostics){
        Node n = m.get(key);
        if(n == null) return List.of();
        if(n instanceof Node.Scalar s && !s.isNull()) return List.of(s.value());
        List<String> out = new ArrayList<>();
        if(n instanceof Node.Sequence seq){
            for(Node item : seq.items()){
                if(item instanceof Node.Scalar s && !s.isNull()) out.add(s.value());
                else diagnostics.error(item, key + " lists names");
            }
        }else{
            diagnostics.error(n, key + " is a name or a list of names");
        }
        return List.copyOf(out);
    }

    /** The kind key this document writes, or null to inherit one. */
    String kind(){
        return fields.get("kind") instanceof Node.Scalar s && !s.isNull() ? s.value() : null;
    }

    String where(){
        return type.yamlName() + " " + id;
    }
}
