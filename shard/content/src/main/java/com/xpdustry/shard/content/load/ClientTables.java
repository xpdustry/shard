package com.xpdustry.shard.content.load;

import com.xpdustry.shard.content.*;
import com.xpdustry.shard.content.schema.*;
import com.xpdustry.shard.content.yaml.*;

import java.util.*;

/**
 * What the v160.7 client numbers or shows that the content prototypes don't describe, from the vanilla pack's
 * {@code client.yml}: the sector and planet names its content header lists, the logic ids ({@code logicids.dat}, which
 * {@code lookup} and {@code @<type>Count} use) and the emoji chars of its icon font ({@code icons/icons.properties}).
 * They belong to the client, not to a world: every epoch shares these.
 *
 * <p>{@code :tools:content-extract} generates the file from a booted vanilla, and {@code :logic}'s and {@code :compat}'s
 * oracle tests check it against one.
 */
public final class ClientTables{
    private static final String PATH = "content/vanilla/client.yml";
    /** {@code GlobalVars.writableLookableContent}: the types with logic ids, in {@code logicids.dat} order. */
    private static final List<ContentType> LOGIC_TYPES = List.of(ContentType.BLOCK, ContentType.UNIT, ContentType.ITEM, ContentType.LIQUID);
    private static volatile ClientTables v160_7;

    private final List<String> sectors, planets;
    private final EnumMap<ContentType, List<String>> logicIds = new EnumMap<>(ContentType.class);
    private final Map<String, Integer> emoji;

    private ClientTables(Node root, Diagnostics diagnostics){
        var map = root instanceof Node.Mapping m ? m : Node.mapping(root.mark());
        sectors = names(map.get("sector"), "sector", diagnostics);
        planets = names(map.get("planet"), "planet", diagnostics);
        var logic = map.get("logic-ids") instanceof Node.Mapping m ? m : Node.mapping(map.mark());
        for(ContentType type : LOGIC_TYPES) logicIds.put(type, names(logic.get(type.yamlName()), "logic-ids." + type.yamlName(), diagnostics));
        var chars = new HashMap<String, Integer>();
        if(map.get("emoji") instanceof Node.Mapping m){
            m.entries().forEach((name, node) -> {
                try{
                    chars.put(name, Integer.parseInt(((Node.Scalar)node).value()));
                }catch(RuntimeException e){
                    diagnostics.error(node, "emoji." + name + " is a char code");
                }
            });
        }else{
            diagnostics.error(map, "emoji is a mapping of content names to char codes");
        }
        emoji = Collections.unmodifiableMap(chars);
    }

    /** The v160.7 client's tables, read once. */
    public static ClientTables v160_7(){
        ClientTables t = v160_7;
        if(t == null){
            synchronized(ClientTables.class){
                t = v160_7;
                if(t == null){
                    var diagnostics = new Diagnostics();
                    t = new ClientTables(YamlReader.read(PATH, Schema.resource(PATH), diagnostics), diagnostics);
                    diagnostics.check();
                    v160_7 = t;
                }
            }
        }
        return t;
    }

    private static List<String> names(Node node, String key, Diagnostics diagnostics){
        if(!(node instanceof Node.Sequence seq)){
            diagnostics.error(node == null ? Mark.none : node.mark(), key + " is a list of names");
            return List.of();
        }
        var out = new ArrayList<String>(seq.items().size());
        for(Node n : seq.items()){
            if(n instanceof Node.Scalar s) out.add(s.value());
            else diagnostics.error(n, "expected a name");
        }
        return List.copyOf(out);
    }

    /** The sector names by id ({@code SectorPresets} and the generated sectors), as the content header lists them. */
    public List<String> sectors(){
        return sectors;
    }

    /** The planet names by id. */
    public List<String> planets(){
        return planets;
    }

    /** The content names of {@code type} in logic id order; empty for a type without logic ids. */
    public List<String> logicIds(ContentType type){
        return logicIds.getOrDefault(type, List.of());
    }

    /** {@code Fonts.getUnicode(name)}: the icon char shown for content named {@code name}, or 0. */
    public int emoji(String name){
        return emoji.getOrDefault(name, 0);
    }
}
