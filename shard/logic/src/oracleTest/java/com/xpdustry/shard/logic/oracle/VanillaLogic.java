package com.xpdustry.shard.logic.oracle;

import arc.util.*;
import com.xpdustry.shard.common.geom.*;
import com.xpdustry.shard.content.epoch.*;
import com.xpdustry.shard.logic.*;
import com.xpdustry.shard.oracle.*;
import mindustry.*;
import mindustry.game.*;
import mindustry.world.*;
import mindustry.world.blocks.logic.*;
import mindustry.world.blocks.logic.LogicBlock.*;

import java.io.*;
import java.nio.charset.*;

/**
 * Vanilla v160.7 as the oracle for mlog: worlds with processors in them, and Shard processors that run on vanilla's
 * buildings through {@link VanillaHost}, with the {@link Globals} table of {@link Epoch#vanilla()}.
 */
final class VanillaLogic{
    private static Globals globals;

    private VanillaLogic(){
    }

    /** Boots vanilla with {@code Time.delta == 1}, as the prototype's harness did. */
    static void boot(){
        Vanilla.boot();
        Time.setDeltaProvider(() -> 1f);
    }

    /** An empty world in play, with vanilla's {@code GlobalVars.rand} seeded to 0. */
    static void world(int width, int height){
        boot();
        Vanilla.emptyWorld(width, height);
        Time.setDeltaProvider(() -> 1f);
        Vars.state.rules.allowLogicData = false;
    }

    /** The clock part of {@code Logic.update}, before the buildings update. */
    static void tick(){
        Time.update();
        Vars.state.tick += 1;
        Vars.logicVars.update();
    }

    /** Shard's tick start, from vanilla's state. */
    static void tick(LogicWorld world){
        world.beginTick(Vars.state.tick, Vars.state.wave, Vars.state.wavetime, Vars.world.width(), Vars.world.height(), Time.delta);
    }

    static LogicBuild place(Block block, int x, int y){
        Vars.world.tile(x, y).setBlock(block, Team.sharded, 0);
        LogicBuild build = (LogicBuild)Vars.world.build(x, y);
        build.efficiency = 1f;
        return build;
    }

    /** Places {@code block} at {@code (x, y)} unless it is there, and adds a link to it as a config upload would. */
    static void link(LogicBuild processor, Block block, int x, int y, String name){
        Tile tile = Vars.world.tile(x, y);
        if(tile.block() != block) tile.setBlock(block, Team.sharded, 0);
        processor.links.add(new LogicLink(x, y, name, true));
    }

    /** The globals table of {@link Epoch#vanilla()}, built once per JVM; vanilla is booted for the comparisons. */
    static synchronized Globals globals(){
        boot();
        if(globals == null) globals = new Globals(Epoch.vanilla());
        return globals;
    }

    /** A Shard world on vanilla's world. */
    static LogicWorld logicWorld(VanillaHost host){
        LogicWorld world = new LogicWorld(host, globals());
        world.allowLogicData(Vars.state.rules.allowLogicData);
        return world;
    }

    /**
     * A Shard processor for vanilla processor {@code build}, with its code and links (as its config). {@code as} is
     * the building whose {@code @thisx/@thisy} it reports, so a copy in another region of the world sees the same
     * numbers as the original.
     */
    static Processor processor(LogicWorld world, VanillaHost host, LogicBuild build, LogicBuild as){
        LogicBlock block = (LogicBlock)build.block;
        Processor p = new Processor(world, build, block.privileged, block.instructionsPerTick, block.maxInstructionsPerTick,
        Point2.pack(build.tileX(), build.tileY()), mindustry.core.World.conv(as.x), mindustry.core.World.conv(as.y));
        host.register(p);
        p.team(build.team.id);
        p.ipt(build.ipt);
        p.configure(build.config());
        return p;
    }

    /** A vanilla program from the test resources. */
    static String program(String name){
        try(InputStream in = VanillaLogic.class.getResourceAsStream("/programs/" + name + ".mlog")){
            if(in == null) throw new FileNotFoundException(name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }catch(IOException e){
            throw new UncheckedIOException(e);
        }
    }
}
