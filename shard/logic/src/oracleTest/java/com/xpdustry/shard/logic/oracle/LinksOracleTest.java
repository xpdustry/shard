package com.xpdustry.shard.logic.oracle;

import com.xpdustry.shard.logic.*;
import mindustry.*;
import mindustry.content.*;
import mindustry.game.*;
import mindustry.gen.*;
import mindustry.world.*;
import mindustry.world.blocks.logic.*;
import mindustry.world.blocks.logic.LogicBlock.*;
import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Event-driven links against vanilla's per-tick polling. Between ticks, both regions get the same random change at a
 * link tile: the building removed, replaced by another block, given to another team and back, linked or unlinked by
 * a tap, or a whole config uploaded (duplicates, links to empty tiles, names that don't match the block). Shard is only
 * told which tiles changed ({@link LinkIndex#tileChanged}); after every tick the link table, the link variables and
 * {@code getlink} must match.
 */
class LinksOracleTest{
    static final String code = """
        set i 0
        loop:
        getlink b i
        sensor t b @type
        print t
        print b
        op add i i 1
        jump loop lessThan i @links
        print @links
        print cell1
        print cell2
        print bank1
        print message1
        print switch1
        set cell2 5
        write i cell1 0
        printflush message1
        """;
    static final int[][] slots = {{2, 0}, {5, 0}, {8, 0}, {11, 0}, {2, 3}, {5, 3}, {8, 3}, {11, 3}};
    static final String[] names = {"cell1", "cell2", "cell3", "bank1", "message1", "switch1", "foo", "cell", "cell-1", "message12"};

    LogicBuild a, b;
    Processor p;
    LogicWorld world;
    Random random;

    @Test
    void churn(){
        VanillaLogic.world(40, 40);
        a = VanillaLogic.place(Blocks.logicProcessor, 4, 6);
        b = VanillaLogic.place(Blocks.logicProcessor, 4, 26);
        for(LogicBuild build : new LogicBuild[]{a, b}){
            VanillaLogic.link(build, Blocks.memoryCell, build.tileX() + 2, build.tileY(), "cell1");
            VanillaLogic.link(build, Blocks.message, build.tileX() + 5, build.tileY(), "message1");
            build.updateCode(code);
        }
        VanillaHost host = new VanillaHost();
        world = VanillaLogic.logicWorld(host);
        Pair pair = new Pair(a, b, world, host);
        p = pair.processor = VanillaLogic.processor(world, host, b, a);
        random = new Random(7);
        assertNull(pair.compare());

        for(int t = 0; t < 3_000; t++){
            String event = event();
            pair.tick();
            String bad = pair.compare();
            int tick = t;
            assertNull(bad, () -> "tick " + tick + " after " + event + ": " + bad);
        }
    }

    @SuppressWarnings("unchecked")
    private String event(){
        int[] slot = slots[random.nextInt(slots.length)];
        int dx = slot[0], dy = slot[1];
        switch(random.nextInt(8)){
            case 0 -> {
                for(LogicBuild build : both()) tile(build, dx, dy).setAir();
                changed(dx, dy);
                return "remove " + dx + "," + dy;
            }
            case 1, 2 -> {
                Block[] blocks = {Blocks.memoryCell, Blocks.memoryBank, Blocks.message, Blocks.switchBlock, Blocks.memoryCell};
                Block block = blocks[random.nextInt(blocks.length)];
                for(LogicBuild build : both()) tile(build, dx, dy).setBlock(block, Team.sharded, 0);
                changed(dx, dy);
                return "place " + block.name + " at " + dx + "," + dy;
            }
            case 3 -> {
                for(LogicBuild build : both()){
                    Building target = tile(build, dx, dy).build;
                    if(target != null) target.changeTeam(target.team == Team.sharded ? Team.crux : Team.sharded);
                }
                changed(dx, dy);
                return "team at " + dx + "," + dy;
            }
            case 4, 5 -> {
                int pos = arc.math.geom.Point2.pack(a.tileX() + dx, a.tileY() + dy);
                ((LogicBlock)a.block).configurations.get(Integer.class).get(a, pos);
                Building target = tile(b, dx, dy).build;
                if(target != null && p.toggleLink(target) && target.block.autoResetEnabled && target.lastDisabler == b){
                    target.enabled = true;
                }
                return "tap " + dx + "," + dy;
            }
            case 6 -> {
                ArrayList<LogicConfig.Link> links = new ArrayList<>();
                int count = random.nextInt(7);
                for(int i = 0; i < count; i++){
                    int[] at = slots[random.nextInt(slots.length)];
                    links.add(new LogicConfig.Link(names[random.nextInt(names.length)], at[0], at[1]));
                }
                byte[] data = LogicConfig.compress(code, links);
                a.readCompressed(data, true);
                p.configure(data);
                return "upload " + links;
            }
            default -> {
                return "nothing";
            }
        }
    }

    private LogicBuild[] both(){
        return new LogicBuild[]{a, b};
    }

    private static Tile tile(LogicBuild build, int dx, int dy){
        return Vars.world.tile(build.tileX() + dx, build.tileY() + dy);
    }

    /** The tiles a building at the slot covers, in Shard's region. */
    private void changed(int dx, int dy){
        for(int x = -1; x <= 2; x++){
            for(int y = -1; y <= 2; y++) world.links.tileChanged(b.tileX() + dx + x, b.tileY() + dy + y);
        }
    }
}
