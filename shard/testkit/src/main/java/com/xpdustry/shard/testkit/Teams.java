package com.xpdustry.shard.testkit;

/**
 * Team names, as vanilla's {@code Team} has them: the six base teams plus derelict, then {@code team#<id>}.
 * Projections name teams so that both engines agree whatever their team type.
 */
public final class Teams{
    private static final String[] NAMES = {"derelict", "sharded", "crux", "malis", "green", "blue", "neoplastic"};

    public static final int DERELICT = 0, SHARDED = 1, CRUX = 2;

    /** The name of team {@code id} (0–255). */
    public static String name(int id){
        if(id < 0 || id > 255) throw new IllegalArgumentException("no team " + id);
        return id < NAMES.length ? NAMES[id] : "team#" + id;
    }

    /** The id of the team named {@code name}. */
    public static int id(String name){
        for(int i = 0; i < NAMES.length; i++){
            if(NAMES[i].equals(name)) return i;
        }
        if(name.startsWith("team#")){
            try{
                int id = Integer.parseInt(name.substring(5));
                if(id >= NAMES.length && id <= 255) return id;
            }catch(NumberFormatException ignored){
            }
        }
        throw new IllegalArgumentException("no team named '" + name + "' (derelict, sharded, crux, malis, green, blue, neoplastic, team#7..team#255)");
    }

    private Teams(){}
}
