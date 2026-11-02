package com.xpdustry.shard.logic.oracle;

import com.xpdustry.shard.logic.lang.*;
import mindustry.logic.*;
import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Shard's mlog enums have vanilla's constants in vanilla's order: ordinals are what the VM and the host exchange.
 * Content types are {@code :content}'s, with vanilla's ordinals as an explicit table.
 */
class LangOracleTest{
    @Test
    void enums(){
        VanillaLogic.boot();
        same(com.xpdustry.shard.logic.lang.LAccess.class, mindustry.logic.LAccess.class);
        same(com.xpdustry.shard.logic.lang.LUnitControl.class, mindustry.logic.LUnitControl.class);
        same(com.xpdustry.shard.logic.lang.RadarTarget.class, mindustry.logic.RadarTarget.class);
        same(com.xpdustry.shard.logic.lang.RadarSort.class, mindustry.logic.RadarSort.class);
        same(com.xpdustry.shard.logic.lang.LLocate.class, mindustry.logic.LLocate.class);
        same(com.xpdustry.shard.logic.lang.BlockFlag.class, mindustry.world.meta.BlockFlag.class);
        same(com.xpdustry.shard.logic.lang.QueryShape.class, mindustry.logic.QueryShape.class);
        same(com.xpdustry.shard.logic.lang.QueryType.class, mindustry.logic.QueryType.class);
        same(com.xpdustry.shard.logic.lang.TileLayer.class, mindustry.logic.TileLayer.class);
        same(com.xpdustry.shard.logic.lang.LogicRule.class, mindustry.logic.LogicRule.class);
        same(com.xpdustry.shard.logic.lang.MessageType.class, mindustry.logic.MessageType.class);
        same(com.xpdustry.shard.logic.lang.CutsceneAction.class, mindustry.logic.CutsceneAction.class);
        same(com.xpdustry.shard.logic.lang.FetchType.class, mindustry.logic.FetchType.class);
        same(com.xpdustry.shard.logic.lang.LMarkerControl.class, mindustry.logic.LMarkerControl.class);
        same(com.xpdustry.shard.logic.lang.GraphicsType.class, mindustry.world.blocks.logic.LogicDisplay.GraphicsType.class);
        same(com.xpdustry.shard.logic.lang.ConditionOp.class, mindustry.logic.ConditionOp.class);
        same(com.xpdustry.shard.logic.lang.LogicOp.class, mindustry.logic.LogicOp.class);
    }

    @Test
    void contentTypes(){
        VanillaLogic.boot();
        // lookup parses vanilla's names, which are also what the wire's ordinals number
        assertEquals(names(mindustry.ctype.ContentType.class), com.xpdustry.shard.content.ContentType.VANILLA_NAMES);
        for(var type : com.xpdustry.shard.content.ContentType.values()){
            assertEquals(type.yamlName(), mindustry.ctype.ContentType.all[type.vanillaOrdinal()].name(), type.name());
        }
    }

    @Test
    void access(){
        VanillaLogic.boot();
        for(mindustry.logic.LAccess vanilla : mindustry.logic.LAccess.values()){
            var shard = com.xpdustry.shard.logic.lang.LAccess.values()[vanilla.ordinal()];
            assertEquals(vanilla.isObj, shard.isObj, vanilla.name());
            assertEquals(vanilla.privileged, shard.privileged, vanilla.name());
            assertEquals(List.of(vanilla.params), names(shard), vanilla.name());
        }
    }

    @Test
    void unary(){
        VanillaLogic.boot();
        for(mindustry.logic.LogicOp vanilla : mindustry.logic.LogicOp.values()){
            assertEquals(vanilla.unary, com.xpdustry.shard.logic.lang.LogicOp.values()[vanilla.ordinal()].unary(), vanilla.name());
        }
    }

    @Test
    void aligns(){
        VanillaLogic.boot();
        Map<String, Integer> vanilla = new TreeMap<>();
        LStatement.nameToAlign.each((name, align) -> vanilla.put(name, align));
        Map<String, Integer> shard = new TreeMap<>();
        for(int i = 0; i < Align.names.length; i++) shard.put(Align.names[i], Align.values[i]);
        assertEquals(vanilla, shard);
    }

    private static List<String> names(com.xpdustry.shard.logic.lang.LAccess access){
        List<String> names = new ArrayList<>();
        for(int i = 0; i < access.params(); i++) names.add(access.param(i));
        return names;
    }

    private static void same(Class<? extends Enum<?>> shard, Class<? extends Enum<?>> vanilla){
        assertEquals(names(vanilla), names(shard), shard.getSimpleName());
    }

    private static List<String> names(Class<? extends Enum<?>> type){
        return Arrays.stream(type.getEnumConstants()).map(Enum::name).toList();
    }
}
