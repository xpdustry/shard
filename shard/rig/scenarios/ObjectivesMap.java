import arc.files.*;
import arc.struct.*;
import com.xpdustry.shard.oracle.*;
import mindustry.content.*;
import mindustry.game.*;
import mindustry.game.MapObjectives.*;
import mindustry.io.*;
import mindustry.maps.Map;
import mindustry.world.blocks.logic.*;

import static mindustry.Vars.*;

/**
 * Writes objectives.msav, the map of objectives.rig, with headless vanilla (the oracle; no client):
 *
 *   java -Dcom.xpdustry.shard.oracle.dataDir=<scratch dir> -cp tools/oracle/build/libs/oracle.jar:<server-release.jar> \
 *     rig/scenarios/ObjectivesMap.java rig/scenarios/objectives.msav
 *
 * The W3.12 rig map, in sandbox (infinite resources): a core on open stone; three objectives (build a conveyor, with a
 * text marker, which adds the flag "built"; a flag objective for "done" with a shape marker; a long timer that stays
 * up); a world processor that sets "done" once "built" is set, and makes three markers of its own (a shape-and-text, a
 * point and a line).
 */
public class ObjectivesMap{
    static final String PROGRAM = String.join("\n",
        "makemarker shapeText 1 12 12 false",
        "print \"W3.12 marker\"",
        "setmarker flushText 1 false",
        "setmarker radius 1 4",
        "makemarker point 2 14 10 false",
        "setmarker minimap 2 1",
        "makemarker line 3 10 14 false",
        "setmarker endPos 3 16 16",
        "setmarker color 3 %ff4040ff",
        "getflag d \"built\"",
        "jump 0 equal d false",
        "setflag \"done\" true");

    public static void main(String[] args) throws Exception{
        Vanilla.emptyWorld(48, 48);
        world.tile(8, 8).setBlock(Blocks.coreShard, Team.sharded, 0);
        state.rules.allowEditWorldProcessors = true;
        world.tile(2, 44).setBlock(Blocks.worldProcessor, Team.sharded, 0);
        world.build(2, 44).configureAny(LogicBlock.compress(PROGRAM, new Seq<>()));
        Vanilla.runPosted();
        state.rules.allowEditWorldProcessors = false;

        var timer = new BuildCountObjective(Blocks.conveyor, 1);
        timer.flagsAdded = new String[]{"built"};
        timer.markers = new ObjectiveMarker[]{new ShapeTextMarker("Build marker", 8f * 20, 8f * 8)};
        var flag = new FlagObjective("done", "Flag objective: wait for the processor");
        flag.markers = new ObjectiveMarker[]{new ShapeMarker(8f * 20, 8f * 12)};
        flag.parents.add(timer);
        var longer = new TimerObjective("Long timer: [accent]@[]", 3600f * 60f);
        longer.parents.add(flag);
        state.rules.objectives.add(timer, flag, longer);

        state.map = new Map(StringMap.of("name", "objectives", "author", "W3.12", "description", "Objectives, objective markers and world-processor markers", "genfilters", "[]"));
        state.rules.waves = false;
        state.rules.infiniteResources = true;
        state.rules.modeName = "objectives";
        MapIO.writeMap(new Fi(args[0]), state.map);
        System.out.println("wrote " + args[0]);
    }
}
