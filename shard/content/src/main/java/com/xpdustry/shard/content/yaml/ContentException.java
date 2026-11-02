package com.xpdustry.shard.content.yaml;

import java.util.*;

/** Content that failed to load. The message lists every error as {@code file:line:column: message}. */
public final class ContentException extends RuntimeException{
    private final List<Diagnostic> diagnostics;

    public ContentException(List<Diagnostic> diagnostics){
        super(format(diagnostics));
        this.diagnostics = List.copyOf(diagnostics);
    }

    public List<Diagnostic> diagnostics(){
        return diagnostics;
    }

    private static String format(List<Diagnostic> diagnostics){
        var sb = new StringBuilder(diagnostics.size() + (diagnostics.size() == 1 ? " content error" : " content errors"));
        int shown = 0;
        for(Diagnostic d : diagnostics){
            if(shown++ == 50){
                sb.append("\n  … and ").append(diagnostics.size() - 50).append(" more");
                break;
            }
            sb.append("\n  ").append(d);
        }
        return sb.toString();
    }
}
