package com.xpdustry.shard.sim.tile;

import arc.math.geom.*;
import com.xpdustry.shard.content.epoch.*;
import com.xpdustry.shard.oracle.*;
import com.xpdustry.shard.sim.blocks.environment.*;
import mindustry.world.*;
import mindustry.world.blocks.environment.*;
import mindustry.world.blocks.logic.*;
import mindustry.world.meta.*;
import org.junit.jupiter.api.*;

import java.util.*;

import static mindustry.Vars.*;
import static org.junit.jupiter.api.Assertions.*;

/** The block fixture and {@link BlockTable} against the live vanilla content, and {@link Footprint} against {@code Edges}. */
class BlockTableOracleTest{
    static final Epoch BLOCKS = VanillaWorlds.VANILLA;
    static final BlockTable TABLE = BlockTable.of(BLOCKS);

    @BeforeAll
    static void boot(){
        Vanilla.boot();
    }

    @Test
    void everyBlockMatchesVanilla(){
        assertEquals(content.blocks().size, TABLE.blocks());
        var wrong = new ArrayList<String>();
        for(Block block : content.blocks()){
            int id = block.id;
            String name = block.name;
            check(wrong, name, "name", block.name, BLOCKS.block(id).name());
            check(wrong, name, "size", block.size, TABLE.size(id));
            check(wrong, name, "sizeOffset", block.sizeOffset, TABLE.sizeOffset(id));
            check(wrong, name, "solid", block.solid, TABLE.solid(id));
            check(wrong, name, "solidifes", block.solidifes, TABLE.solidifes(id));
            check(wrong, name, "update", block.update, TABLE.update(id));
            check(wrong, name, "destructible", block.destructible, TABLE.destructible(id));
            check(wrong, name, "hasBuilding", block.hasBuilding(), TABLE.hasBuilding(id));
            check(wrong, name, "synthetic", block.synthetic(), TABLE.synthetic(id));
            check(wrong, name, "breakable", block.breakable, TABLE.breakable(id));
            check(wrong, name, "alwaysReplace", block.alwaysReplace, TABLE.alwaysReplace(id));
            check(wrong, name, "teamPassable", block.teamPassable, TABLE.teamPassable(id));
            check(wrong, name, "fillsTile", block.fillsTile, TABLE.fillsTile(id));
            check(wrong, name, "forceDark", block.forceDark, TABLE.forceDark(id));
            check(wrong, name, "privileged", block.privileged, TABLE.privileged(id));
            check(wrong, name, "isStatic", block.isStatic(), TABLE.isStatic(id));
            check(wrong, name, "saveData", block.saveData, TABLE.saveData(id));
            check(wrong, name, "isFloor", block.isFloor(), TABLE.isFloor(id));
            check(wrong, name, "isOverlay", block.isOverlay(), TABLE.isOverlay(id));
            check(wrong, name, "itemDrop", block.itemDrop == null ? BlockTable.NONE : block.itemDrop.id, TABLE.itemDrop(id));
            check(wrong, name, "forceTeam", block.forceTeam == null ? BlockTable.NONE : block.forceTeam.id, TABLE.forceTeam(id));
            check(wrong, name, "darkness", darkness(block), TABLE.darkness(id));
            if(block instanceof Floor floor){
                check(wrong, name, "isLiquid", floor.isLiquid, TABLE.isLiquid(id));
                check(wrong, name, "isDeep", floor.isDeep(), TABLE.isDeep(id));
                check(wrong, name, "damages", floor.damages(), TABLE.damages(id));
                check(wrong, name, "drownTime", floor.drownTime, TABLE.drownTime(id));
                check(wrong, name, "speedMultiplier", floor.speedMultiplier, TABLE.speedMultiplier(id));
                check(wrong, name, "dragMultiplier", floor.dragMultiplier, TABLE.dragMultiplier(id));
                check(wrong, name, "damageTaken", floor.damageTaken, TABLE.damageTaken(id));
                check(wrong, name, "status", floor.status.id, TABLE.status(id));
                check(wrong, name, "statusDuration", floor.statusDuration, TABLE.statusDuration(id));
                check(wrong, name, "liquidDrop", floor.liquidDrop == null ? BlockTable.NONE : floor.liquidDrop.id, TABLE.liquidDrop(id));
                check(wrong, name, "liquidMultiplier", floor.liquidMultiplier, TABLE.liquidMultiplier(id));
                check(wrong, name, "wallOre", floor.wallOre, TABLE.wallOre(id));
                check(wrong, name, "playerUnmineable", floor.playerUnmineable, TABLE.playerUnmineable(id));
                for(int a = 0; a < Attributes.COUNT; a++){
                    Attribute attribute = Attribute.all[a];
                    check(wrong, name, attribute.name, floor.attributes.get(attribute), TABLE.attribute(id, a));
                }
            }
        }
        assertTrue(wrong.isEmpty(), wrong.size() + " wrong: " + String.join("\n", wrong.subList(0, Math.min(30, wrong.size()))));
    }

    static int darkness(Block block){
        if(block instanceof ColoredWall) return BlockTable.DARK_COLORED_WALL;
        if(block instanceof LogicBlock) return BlockTable.DARK_LOGIC;
        return BlockTable.DARK_BLOCK;
    }

    static void check(List<String> wrong, String block, String field, Object vanilla, Object shard){
        if(!Objects.equals(normal(vanilla), normal(shard))) wrong.add(block + "." + field + ": vanilla " + vanilla + ", shard " + shard);
    }

    /** Integral numbers as longs, so a byte column equals vanilla's int. */
    static Object normal(Object value){
        return switch(value){
            case Byte b -> (long)b;
            case Short s -> (long)s;
            case Integer i -> (long)i;
            default -> value;
        };
    }

    @Test
    void edgesAreVanillas(){
        for(int size = 1; size <= Footprint.MAX_SIZE; size++){
            Point2[] edges = Edges.getEdges(size), inside = Edges.getInsideEdges(size);
            assertEquals(edges.length, Footprint.edgeCount(size));
            for(int i = 0; i < edges.length; i++){
                assertEquals(edges[i].x, Footprint.edgeX(size, i), "size " + size + " edge " + i);
                assertEquals(edges[i].y, Footprint.edgeY(size, i), "size " + size + " edge " + i);
                assertEquals(inside[i].x, Footprint.insideEdgeX(size, i), "size " + size + " inside edge " + i);
                assertEquals(inside[i].y, Footprint.insideEdgeY(size, i), "size " + size + " inside edge " + i);
            }
            assertEquals(-(size - 1) / 2, Footprint.offset(size));
        }
    }
}
