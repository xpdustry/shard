import arc.files.*;
import arc.struct.*;
import com.xpdustry.shard.oracle.*;
import mindustry.content.*;
import mindustry.game.*;
import mindustry.io.*;
import mindustry.maps.Map;
import mindustry.type.*;
import mindustry.world.*;
import mindustry.world.blocks.logic.*;

import static mindustry.Vars.*;

/**
 * Writes replay-gate.msav, the map of the live replay gate (gate/live-gate.sh), with headless vanilla (the oracle):
 *
 *   java -Dcom.xpdustry.shard.oracle.dataDir=<scratch dir> -cp tools/oracle/build/libs/oracle.jar:<server-release.jar> \
 *     tools/replay/gate/GateMap.java <out>/replay-gate.msav
 *
 * Ground Zero in survival with the wave timer on, a fed turret line in front of the core (duos on graphite, hails on
 * pyratite: bullets, shells, burning), and a crux outpost of walls, an unarmed duo and a coal-filled container in hail
 * range, which a world processor keeps rebuilding and refilling. The match then never runs out of fighting: the hails
 * set the outpost alight, the container burns and explodes, and the processor builds it again. The outpost's duo has no
 * ammo: a duo graphite shell flies 26 tiles, so its misses at the turret line flew on into the core and killed it
 * within minutes. The waves deal half damage, so the core lasts the gate's 13 minutes.
 */
public class GateMap{
    public static void main(String[] args) throws Exception{
        Vanilla.host("serpulo/groundZero", Gamemode.survival);
        Tile core = state.rules.defaultTeam.core().tile;
        Tile spawn = spawner.getSpawns().first();
        // the line from the core toward where the waves come from
        float dx = spawn.x - core.x, dy = spawn.y - core.y, length = (float)Math.hypot(dx, dy);
        float ux = dx / length, uy = dy / length;

        // the turret line, 7 tiles out and 24 wide: each turret with an item source of its ammo beside it
        for(int i = -4; i <= 4; i++){
            int x = Math.round(core.x + ux * 7 - uy * i * 3), y = Math.round(core.y + uy * 7 + ux * i * 3);
            Block turret = i % 2 == 0 ? Blocks.hail : Blocks.duo;
            Tile at = fit(turret, Team.sharded, x, y);
            if(at == null) continue;
            at.setBlock(turret, Team.sharded, 0);
            Tile source = fit(Blocks.itemSource, Team.sharded, at.x - Math.round(ux), at.y - Math.round(uy));
            if(source == null) continue;
            source.setBlock(Blocks.itemSource, Team.sharded, 0);
            source.build.configureAny(turret == Blocks.hail ? Items.pyratite : Items.graphite);
        }

        // the crux outpost, 20 tiles out: what the world processor rebuilds
        int ox = Math.round(core.x + ux * 20), oy = Math.round(core.y + uy * 20);
        var program = new StringBuilder();
        Tile container = null, duo = null;
        var walls = new Seq<Tile>();
        for(int r = 0; r < 5 && walls.size < 8; r++){
            for(int i = -4; i <= 4 && walls.size < 8; i++){
                Tile wall = fit(Blocks.copperWall, Team.crux, Math.round(ox - uy * i + ux * (2 + r)), Math.round(oy + ux * i + uy * (2 + r)));
                if(wall == null) continue;
                wall.setBlock(Blocks.copperWall, Team.crux, 0);
                walls.add(wall);
            }
        }
        container = fit(Blocks.container, Team.crux, ox, oy);
        if(container != null){
            container.setBlock(Blocks.container, Team.crux, 0);
            container.build.items.add(Items.coal, 300);
        }
        duo = fit(Blocks.duo, Team.crux, Math.round(ox - uy * 3), Math.round(oy + ux * 3));
        if(duo != null) duo.setBlock(Blocks.duo, Team.crux, 0);
        for(Tile wall : walls) program.append("setblock block @copper-wall ").append(wall.x).append(' ').append(wall.y).append(" @crux 0\n");
        if(container != null){
            program.append("setblock block @container ").append(container.x).append(' ').append(container.y).append(" @crux 0\n");
            program.append("getblock building c ").append(container.x).append(' ').append(container.y).append('\n');
            program.append("setprop @coal c 300\n");
        }
        if(duo != null) program.append("setblock block @duo ").append(duo.x).append(' ').append(duo.y).append(" @crux 0\n");
        program.append("wait 5\n");

        // the world processor, behind the core
        Tile processor = fit(Blocks.worldProcessor, Team.sharded, Math.round(core.x - ux * 6), Math.round(core.y - uy * 6));
        if(processor == null) throw new IllegalStateException("no room for the world processor");
        state.rules.allowEditWorldProcessors = true;
        processor.setBlock(Blocks.worldProcessor, Team.sharded, 0);
        processor.build.configureAny(LogicBlock.compress(program.toString(), new Seq<>()));
        Vanilla.runPosted();

        state.map = new Map(StringMap.of("name", "replay-gate", "author", "RP2",
            "description", "Ground Zero with a fed turret line, a crux outpost a world processor keeps rebuilding, and waves every 40 s"));
        state.rules.allowEditWorldProcessors = false;
        state.rules.waves = true;
        state.rules.waveTimer = true;
        state.rules.waveSpacing = 40 * 60f;
        state.rules.initialWaveSpacing = 30 * 60f;
        state.rules.loadout = ItemStack.list(Items.copper, 4000, Items.lead, 1000);
        state.rules.teams.get(Team.crux).unitDamageMultiplier = 0.5f;
        state.rules.modeName = "survival";
        MapIO.writeMap(new Fi(args[0]), state.map);
        System.out.println("wrote " + args[0] + ": core " + core.x + "," + core.y + ", spawn " + spawn.x + "," + spawn.y + ", outpost " + ox + "," + oy
            + ", " + walls.size + " walls, container " + (container != null) + ", duo " + (duo != null) + ", processor " + processor.x + "," + processor.y);
        System.out.println(program);
    }

    /** Whether every tile {@code block} covers at ({@code x}, {@code y}) is empty, buildable ground. */
    static boolean free(Block block, int x, int y){
        int offset = -(block.size - 1) / 2;
        for(int dx = 0; dx < block.size; dx++){
            for(int dy = 0; dy < block.size; dy++){
                Tile other = world.tile(x + offset + dx, y + offset + dy);
                if(other == null || other.block() != Blocks.air || other.floor().isDeep() || other.floor().solid) return false;
            }
        }
        return true;
    }

    /** {@code block} for {@code team} at or near ({@code x}, {@code y}): the nearest tile within 6 where it can be placed. */
    static Tile fit(Block block, Team team, int x, int y){
        for(int r = 0; r <= 6; r++){
            for(int ox = -r; ox <= r; ox++){
                for(int oy = -r; oy <= r; oy++){
                    if(Math.max(Math.abs(ox), Math.abs(oy)) != r) continue;
                    Tile tile = world.tile(x + ox, y + oy);
                    if(tile != null && free(block, tile.x, tile.y)) return tile;
                }
            }
        }
        return null;
    }
}
