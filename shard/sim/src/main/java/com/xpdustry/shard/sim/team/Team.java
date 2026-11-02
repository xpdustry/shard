package com.xpdustry.shard.sim.team;

import com.xpdustry.shard.common.math.*;
import com.xpdustry.shard.sim.rules.*;

import java.util.*;

/**
 * The team index (vanilla {@code Team}): the 256 teams by id, with their names and colours, the same in every world.
 * The seven named teams have vanilla's colours; {@code team#7} to {@code team#255} take theirs from a generator seeded
 * with 8, as {@code Team}'s static initializer draws them (Team.java:41). The rest of vanilla's {@code Team} is per world:
 * {@link Teams} holds the data, {@code Rules.teams} the rules.
 *
 * <p>A team is its id everywhere in the sim; this class only names it.
 */
public record Team(int id, String name, int color){
    public static final int DERELICT = VanillaIds.DERELICT, SHARDED = VanillaIds.SHARDED, CRUX = VanillaIds.CRUX, MALIS = 3,
        GREEN = 4, BLUE = 5, NEOPLASTIC = 6;
    /** {@code Team.all.length}. */
    public static final int COUNT = VanillaIds.TEAMS;
    /** {@code Team.baseTeams}: the teams the editor offers, derelict to blue. */
    public static final int BASE_TEAMS = 6;

    private static final List<Team> ALL = all();

    /** {@code Team.get(id)}: ids wrap to a byte, as vanilla's. */
    public static Team get(int id){
        return ALL.get(id & 0xff);
    }

    /** Every team, in id order. */
    public static List<Team> values(){
        return ALL;
    }

    /** {@code ignoreUnitCap}: only neoplastic's units ignore the cap. */
    public boolean ignoreUnitCap(){
        return id == NEOPLASTIC;
    }

    private static List<Team> all(){
        var out = new ArrayList<Team>(COUNT);
        out.add(new Team(DERELICT, "derelict", rgba("4d4e58")));
        out.add(new Team(SHARDED, "sharded", rgba("ffd37f")));
        out.add(new Team(CRUX, "crux", rgba("f25555")));
        out.add(new Team(MALIS, "malis", rgba("a27ce5")));
        out.add(new Team(GREEN, "green", rgba("54d67d")));
        out.add(new Team(BLUE, "blue", rgba("6c87fd")));
        out.add(new Team(NEOPLASTIC, "neoplastic", rgba("e05438")));
        var rand = new Rand(8);
        // Team.java:43: three draws "fix the random seed shift caused by new team"
        for(int i = 0; i < 3; i++) rand.nextFloat();
        for(int id = out.size(); id < COUNT; id++){
            // Mathf.random(min, max) is min + (max - min) * random()
            float h = 360f * rand.nextFloat(), s = 100f * (0.4f + (1f - 0.4f) * rand.nextFloat()), v = 100f * (0.6f + (1f - 0.6f) * rand.nextFloat());
            out.add(new Team(id, "team#" + id, hsv(h, s, v)));
        }
        return List.copyOf(out);
    }

    private static int rgba(String hex){
        return Color.valueOf(hex).rgba();
    }

    /** Arc's {@code Color.HSVtoRGB(h, s, v, 1f).rgba()}. */
    static int hsv(float h, float s, float v){
        if(h == 360) h = 359;
        h = (float)Math.max(0.0, Math.min(360.0, h));
        s = (float)Math.max(0.0, Math.min(100.0, s));
        v = (float)Math.max(0.0, Math.min(100.0, v));
        s /= 100;
        v /= 100;
        h /= 60;
        int i = Mathf.floor(h);
        float f = h - i, p = v * (1 - s), q = v * (1 - s * f), t = v * (1 - s * (1 - f));
        float r, g, b;
        switch(i){
            case 0 -> { r = v; g = t; b = p; }
            case 1 -> { r = q; g = v; b = p; }
            case 2 -> { r = p; g = v; b = t; }
            case 3 -> { r = p; g = q; b = v; }
            case 4 -> { r = t; g = p; b = v; }
            default -> { r = v; g = p; b = q; }
        }
        return new Color(Mathf.clamp(r), Mathf.clamp(g), Mathf.clamp(b), 1f).rgba();
    }
}
