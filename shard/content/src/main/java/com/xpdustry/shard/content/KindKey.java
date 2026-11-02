package com.xpdustry.shard.content;

import java.util.regex.*;

/**
 * The kebab-case name of a behaviour kind ({@code conveyor}, {@code generic-crafter}, {@code item-turret}…). A block
 * definition names its kind; {@code :sim}'s kind registry maps the key to the class registered with the same name
 * (ARCHITECTURE.md §5 "Kinds"). Ordered by name.
 */
public record KindKey(String name) implements Comparable<KindKey>{
    private static final Pattern FORMAT = Pattern.compile("[a-z][a-z0-9]*(-[a-z0-9]+)*");

    public KindKey{
        if(!FORMAT.matcher(name).matches()){
            throw new IllegalArgumentException("kind key must be kebab-case (like generic-crafter): '" + name + "'");
        }
    }

    public static KindKey of(String name){
        return new KindKey(name);
    }

    /** Whether {@code name} is a well-formed kind key. */
    public static boolean valid(String name){
        return FORMAT.matcher(name).matches();
    }

    @Override
    public int compareTo(KindKey other){
        return name.compareTo(other.name);
    }

    @Override
    public String toString(){
        return name;
    }
}
