package com.xpdustry.shard.content.yaml;

/** A load error, at the place in a file that caused it. Content errors are fatal: an epoch with any is refused. */
public record Diagnostic(Mark mark, String message){
    @Override
    public String toString(){
        return mark + ": " + message;
    }
}
