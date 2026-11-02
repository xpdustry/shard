package com.xpdustry.shard.logic;

import java.util.*;

/**
 * A processor's part of a save, after {@code Building.writeBase}: {@code LogicBuild.write}, version 5. {@code :compat}
 * writes it as {@code [int length][compressed code and links][int count]{[str name][TypeIO object]}[int 0 memory]
 * [short ipt][TypeIO string tag][short icon][short waits]{[short index][float time]}[float accumulator]}, with the
 * links absolute ({@link LogicConfig#compress}). Values are {@code Double}s or the host's objects; nulls are not
 * listed, and {@code @unit} comes first when set.
 *
 * @param ipt 0 for a normal processor at the block's rate
 */
public record LogicSave(String code, List<Link> links, List<Variable> variables, short ipt, String tag, char iconTag,
                        int[] waitIndices, float[] waitTimes, float accumulator){
    public LogicSave{
        links = List.copyOf(links);
        variables = List.copyOf(variables);
    }

    public record Variable(String name, Object value){
        public Variable{
            Objects.requireNonNull(value);
        }
    }

    public record Link(String name, int x, int y){
    }

    /** The compressed code and links as the save stores them. */
    public byte[] compressed(){
        ArrayList<LogicConfig.Link> list = new ArrayList<>(links.size());
        for(Link link : links) list.add(new LogicConfig.Link(link.name(), link.x(), link.y()));
        return LogicConfig.compress(code == null ? "" : code, list);
    }
}
