package com.xpdustry.shard.content.yaml;

import org.snakeyaml.engine.v2.api.*;
import org.snakeyaml.engine.v2.api.lowlevel.*;
import org.snakeyaml.engine.v2.exceptions.*;
import org.snakeyaml.engine.v2.nodes.*;

import java.util.*;

/**
 * Reads YAML text into {@link Node}s with marks, using snakeyaml-engine's composer only: no type resolution, no object
 * construction. Duplicate keys and non-scalar keys are errors.
 */
public final class YamlReader{
    /** Deeper than this is a mistake (or an alias loop), not content. */
    private static final int maxDepth = 64;

    private YamlReader(){
    }

    /** The single document in {@code text}; parse errors go to {@code diagnostics} and give null. */
    public static Node read(String source, String text, Diagnostics diagnostics){
        var settings = LoadSettings.builder()
            .setLabel(source)
            .setAllowDuplicateKeys(false)
            .setUseMarks(true)
            .setMaxAliasesForCollections(64)
            .setCodePointLimit(16 * 1024 * 1024)
            .build();
        try{
            Optional<org.snakeyaml.engine.v2.nodes.Node> root = new Compose(settings).composeString(text);
            if(root.isEmpty()) return new Node.Sequence(List.of(), new Mark(source, 1, 1));
            return convert(root.get(), source, diagnostics, 0);
        }catch(MarkedYamlEngineException e){
            Mark mark = e.getProblemMark().map(m -> mark(m, source)).orElse(new Mark(source, 0, 0));
            diagnostics.error(mark, "YAML: " + e.getProblem());
            return null;
        }catch(YamlEngineException e){
            diagnostics.error(new Mark(source, 0, 0), "YAML: " + e.getMessage());
            return null;
        }
    }

    private static Node convert(org.snakeyaml.engine.v2.nodes.Node node, String source, Diagnostics diagnostics, int depth){
        Mark mark = node.getStartMark().map(m -> mark(m, source)).orElse(new Mark(source, 0, 0));
        if(depth > maxDepth){
            diagnostics.error(mark, "nested deeper than " + maxDepth + " levels");
            return new Node.Scalar("null", false, mark);
        }
        return switch(node){
            case ScalarNode s -> new Node.Scalar(s.getValue(), !s.isPlain(), mark);
            case SequenceNode s -> {
                List<Node> items = new ArrayList<>(s.getValue().size());
                for(var item : s.getValue()) items.add(convert(item, source, diagnostics, depth + 1));
                yield new Node.Sequence(items, mark);
            }
            case MappingNode m -> {
                var entries = new LinkedHashMap<String, Node>();
                var keys = new LinkedHashMap<String, Mark>();
                for(NodeTuple t : m.getValue()){
                    Mark keyMark = t.getKeyNode().getStartMark().map(k -> mark(k, source)).orElse(mark);
                    if(!(t.getKeyNode() instanceof ScalarNode key)){
                        diagnostics.error(keyMark, "mapping keys must be plain text");
                        continue;
                    }
                    if(entries.containsKey(key.getValue())){
                        diagnostics.error(keyMark, "duplicate key '" + key.getValue() + "'");
                        continue;
                    }
                    entries.put(key.getValue(), convert(t.getValueNode(), source, diagnostics, depth + 1));
                    keys.put(key.getValue(), keyMark);
                }
                yield new Node.Mapping(entries, keys, mark);
            }
            default -> {
                diagnostics.error(mark, "unsupported YAML node " + node.getNodeType());
                yield new Node.Scalar("null", false, mark);
            }
        };
    }

    private static Mark mark(org.snakeyaml.engine.v2.exceptions.Mark m, String source){
        return new Mark(source, m.getLine() + 1, m.getColumn() + 1);
    }
}
