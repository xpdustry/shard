package com.xpdustry.shard.content.yaml;

/** Where something was written: source name (a file path or a label), 1-based line and column. */
public record Mark(String source, int line, int column){
    /** For nodes that no file wrote (schema defaults, values the bake derived). */
    public static final Mark none = new Mark("<none>", 0, 0);

    @Override
    public String toString(){
        return line == 0 ? source : source + ":" + line + ":" + column;
    }
}
