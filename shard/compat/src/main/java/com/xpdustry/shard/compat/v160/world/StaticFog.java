package com.xpdustry.shard.compat.v160.world;

import com.xpdustry.shard.common.io.*;
import com.xpdustry.shard.common.math.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.rules.*;
import com.xpdustry.shard.sim.world.*;

import java.util.*;

/**
 * Vanilla's {@code FogControl} static fog as a save writes it, the {@code static-fog-data} custom chunk: per team, the
 * tiles it has discovered. Shard doesn't simulate fog yet, so this is the chunk as vanilla has it right after loading
 * a file, which a world stream or save then writes: {@code WorldLoadEvent} draws a circle of each building's
 * {@code fogRadius()} around every building with a fog radius when the file's rules have static fog (FogControl.java:46),
 * then {@code readCustomChunks} replaces the teams the file's own chunk holds (FogControl.java:476).
 *
 * <p>Gaps: discovery after the load (units moving, buildings placed) isn't drawn; a turret's ammo {@code rangeChange} is
 * left out of its radius.
 */
public final class StaticFog{
    /** The chunk's name ({@code SaveVersion.addCustomChunk}). */
    public static final String CHUNK = "static-fog-data";
    private static final KindKey TURRET = KindKey.of("turret"), RADAR = KindKey.of("radar");
    private static final float TILESIZE = 8f;

    /** The world's size, which the chunk's header holds. */
    private final int worldWidth, worldHeight;
    /** Vanilla's static {@code ww} and {@code wh}: the world's, or the last chunk read's. */
    private final int width, height;
    /** Each team's discovered tiles, by team id. */
    private final SortedMap<Integer, BitSet> teams = new TreeMap<>();

    private StaticFog(int worldWidth, int worldHeight, int width, int height){
        this.worldWidth = worldWidth;
        this.worldHeight = worldHeight;
        this.width = width;
        this.height = height;
    }

    /**
     * The chunk vanilla holds after loading {@code world} under the file's {@code rules}, its own chunk {@code saved}
     * (null: none) read over it; null when vanilla has no fog data ({@code fog == null}).
     */
    public static byte[] afterLoad(World world, Rules rules, byte[] saved){
        if(!(rules.fog && rules.staticFog) && saved == null) return null;
        int w = world.tiles().width(), h = world.tiles().height();
        var fog = new StaticFog(w, h, w, h);
        if(rules.fog && rules.staticFog){
            world.buildings().each(building -> {
                if(!building.block().flags().contains("hasFogRadius")) return;
                BitSet data = fog.teams.computeIfAbsent(building.team, team -> new BitSet());
                fog.circle(data, building.tileX(), building.tileY(), Mathf.round(fogRadius(building)));
            });
        }
        if(saved == null) return fog.write();
        return fog.read(saved).write();
    }

    /** {@code fogRadius()}: the block's, a turret's from its range, a radar's grown with its progress. */
    static float fogRadius(Building building){
        BlockDef block = building.block();
        // Turret.java:426, without the ammo's rangeChange
        if(block.isA(TURRET)) return block.fields().f("range") / TILESIZE * block.fields().f("fogRadiusMultiplier");
        // Radar.java:55: progress is the body's first float (0 when placed live), smoothEfficiency starts at 1
        if(block.isA(RADAR) && building instanceof PassthroughBuilding passthrough){
            byte[] body = passthrough.hasBody() ? passthrough.body(building.world().content()) : new byte[0];
            return block.fogRadius() * (body.length >= 4 ? new Reads(body).f() : 0f);
        }
        return block.fogRadius();
    }

    /** {@code read}: each team in the chunk gets its tiles anew; the chunk's size becomes the fog's. */
    private StaticFog read(byte[] chunk){
        var in = new Reads(chunk);
        int count = in.ub();
        int w = in.s(), h = in.s();
        var fog = new StaticFog(worldWidth, worldHeight, w, h);
        fog.teams.putAll(teams);
        int length = w * h;
        for(int i = 0; i < count; i++){
            int team = in.ub();
            var data = new BitSet();
            fog.teams.put(team, data);
            int pos = 0;
            while(pos < length){
                int run = in.ub();
                int consecutive = run & 0x7f;
                if((run & 0x80) != 0) data.set(pos, pos + consecutive);
                pos += consecutive;
            }
        }
        return fog;
    }

    /** {@code write}: the teams with data, the size, then each team's tiles in runs of at most 127. */
    private byte[] write(){
        var out = new Writes();
        out.b(teams.size());
        out.s(worldWidth);
        out.s(worldHeight);
        int size = width * height;
        teams.forEach((team, data) -> {
            out.b(team);
            int pos = 0;
            while(pos < size){
                int consecutive = 0;
                boolean current = data.get(pos);
                while(consecutive < 127 && pos < size && data.get(pos) == current){
                    consecutive++;
                    pos++;
                }
                out.b((current ? 0x80 : 0) | consecutive);
            }
        });
        return out.toByteArray();
    }

    /** {@code circle}: the midpoint circle of {@code radius} around a tile, filled by rows. */
    private void circle(BitSet data, int x, int y, int radius){
        int f = 1 - radius;
        int ddFx = 1, ddFy = -2 * radius;
        int px = 0, py = radius;
        hline(data, x, x, y + radius);
        hline(data, x, x, y - radius);
        hline(data, x - radius, x + radius, y);
        while(px < py){
            if(f >= 0){
                py--;
                ddFy += 2;
                f += ddFy;
            }
            px++;
            ddFx += 2;
            f += ddFx;
            hline(data, x - px, x + px, y + py);
            hline(data, x - px, x + px, y - py);
            hline(data, x - py, x + py, y + px);
            hline(data, x - py, x + py, y - px);
        }
    }

    private void hline(BitSet data, int x1, int x2, int y){
        if(y < 0 || y >= height) return;
        if(x1 > x2){
            int tmp = x1;
            x1 = x2;
            x2 = tmp;
        }
        if(x1 >= width || x2 < 0) return;
        if(x1 < 0) x1 = 0;
        if(x2 >= width) x2 = width - 1;
        int off = y * width;
        data.set(off + x1, off + x2 + 1);
    }
}
