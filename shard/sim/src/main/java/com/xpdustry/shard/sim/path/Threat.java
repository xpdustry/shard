package com.xpdustry.shard.sim.path;

import com.xpdustry.shard.common.struct.*;

/**
 * The turret threat layer (note 22 §2.9): with {@link PathRules#threatPaths()} on, a
 * wave-AI field pays {@value #COST} more on a tile for every enemy turret whose range covers it. Derelict turrets
 * don't count. Tested in the prototype: 52% less turret exposure for 4% longer routes. A gameplay change, so off by
 * default.
 *
 * <p>Coverage is counted per turret team, so a team's threat is the total minus its own coverage. Derived from the
 * {@link PathGrid} view: the turret centres it holds.
 */
final class Threat{
    static final int COST = 20;
    /** {@code Vars.tilesize}. */
    private static final float TILE = 8f;

    private final int width, height;
    private final int[] total;
    private final int[][] byTeam = new int[256][];

    Threat(int width, int height){
        this.width = width;
        this.height = height;
        total = new int[width * height];
    }

    private Threat(Threat source){
        width = source.width;
        height = source.height;
        total = source.total.clone();
        for(int team = 0; team < 256; team++){
            if(source.byTeam[team] != null) byTeam[team] = source.byTeam[team].clone();
        }
    }

    Threat copy(){
        return new Threat(this);
    }

    /** The extra cost of {@code pos} for {@code team}. */
    int at(int team, int pos){
        int[] own = byTeam[team];
        return (total[pos] - (own == null ? 0 : own[pos])) * COST;
    }

    /**
     * Adds ({@code sign} 1) or removes (-1) a turret of {@code team} at {@code center}, covering a disc of
     * {@code range / tilesize} tiles. Adds every covered tile to {@code touched} when it isn't null.
     */
    void stamp(int team, int center, float range, int sign, IntSeq touched){
        if(team == 0 || range <= 0f) return;
        int[] own = byTeam[team];
        if(own == null) own = byTeam[team] = new int[total.length];
        int cx = center % width, cy = center / width, r = (int)(range / TILE), r2 = r * r;
        for(int dy = -r; dy <= r; dy++){
            for(int dx = -r; dx <= r; dx++){
                int x = cx + dx, y = cy + dy;
                if(dx * dx + dy * dy > r2 || x < 0 || y < 0 || x >= width || y >= height) continue;
                int pos = x + y * width;
                own[pos] += sign;
                total[pos] += sign;
                if(touched != null) touched.add(pos);
            }
        }
    }

    /** Every tile a stamp of {@code range} at {@code center} would cover. */
    void covered(int center, float range, IntSeq out){
        int cx = center % width, cy = center / width, r = (int)(range / TILE), r2 = r * r;
        for(int dy = -r; dy <= r; dy++){
            for(int dx = -r; dx <= r; dx++){
                int x = cx + dx, y = cy + dy;
                if(dx * dx + dy * dy > r2 || x < 0 || y < 0 || x >= width || y >= height) continue;
                out.add(x + y * width);
            }
        }
    }
}
