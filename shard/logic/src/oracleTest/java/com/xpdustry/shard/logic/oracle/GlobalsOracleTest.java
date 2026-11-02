package com.xpdustry.shard.logic.oracle;

import arc.struct.*;
import com.xpdustry.shard.content.ContentDef;
import com.xpdustry.shard.logic.*;
import mindustry.*;
import mindustry.ctype.*;
import mindustry.game.*;
import mindustry.logic.*;
import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Every name in vanilla's {@code GlobalVars} resolves to the same value in Shard's {@link Globals}, and no other does;
 * {@code lookup}, logic ids, icons and printed names of the epoch's content are vanilla's.
 */
class GlobalsOracleTest{
    static final Set<String> dynamic = Set.of("@time", "@tick", "@second", "@minute", "@waveNumber", "@waveTime", "@mapw",
    "@maph", "@server", "@client", "@clientTeam", "@clientMobile", "@clientMusicPlaying");

    @Test
    @SuppressWarnings("unchecked")
    void sameTable() throws ReflectiveOperationException{
        Globals globals = VanillaLogic.globals();
        var field = GlobalVars.class.getDeclaredField("vars");
        field.setAccessible(true);
        ObjectMap<String, LVar> vars = (ObjectMap<String, LVar>)field.get(Vars.logicVars);
        List<String> names = new ArrayList<>();
        for(String name : vars.keys()) names.add(name);
        Collections.sort(names);
        assertTrue(names.size() > 500, "vanilla has " + names.size() + " globals");
        for(String name : names){
            for(boolean privileged : new boolean[]{false, true}){
                LVar vanilla = Vars.logicVars.get(name, privileged);
                Globals.Entry shard = globals.lookup(name, privileged);
                assertNotNull(shard, name);
                if(dynamic.contains(vanilla.name)){
                    assertTrue(shard.dynamic() >= 0, name + " is dynamic");
                    continue;
                }
                assertEquals(-1, shard.dynamic(), name);
                Object value = vanilla.isobj ? vanilla.objval : (Object)vanilla.numval;
                Object mine = VanillaHost.unshard(shard.value());
                if(value instanceof Double d){
                    assertInstanceOf(Double.class, mine, name);
                    assertEquals(Double.doubleToRawLongBits(d), Double.doubleToRawLongBits((Double)mine), name);
                }else{
                    assertSame(value, mine, name + (privileged ? " (privileged)" : ""));
                }
            }
        }
        for(String name : List.of("@sfx-shoot", "@counter", "@unit", "@this", "@links", "@ipt", "@thisx", "@queries", "x", "@colorred")){
            assertNull(globals.lookup(name, true), name + " is not a global");
        }
    }

    @Test
    void lookupsAndLogicIds(){
        Globals globals = VanillaLogic.globals();
        for(ContentType type : ContentType.all){
            int count = type == ContentType.team ? Team.all.length : Vars.content.getBy(type).size;
            for(int id = -1; id <= count + 1; id++){
                assertSame(Vars.logicVars.lookupContent(type, id), VanillaHost.unshard(globals.lookup(type.ordinal(), id)), type + " " + id);
            }
        }
        for(var type : com.xpdustry.shard.content.ContentType.values()){
            for(int id = 0, n = globals.content().count(type); id < n; id++){
                ContentDef def = globals.content().get(type, id);
                // vanilla reads a logic id of unlockable content only
                if(VanillaHost.unshard(def) instanceof UnlockableContent vanilla){
                    assertEquals(Vars.logicVars.lookupLogicId(vanilla), globals.logicId(def), def.name());
                }
            }
        }
    }

    @Test
    void printedContentAndTeams(){
        Globals globals = VanillaLogic.globals();
        LogicWorld world = VanillaLogic.logicWorld(new VanillaHost());
        for(var type : com.xpdustry.shard.content.ContentType.values()){
            for(int id = 0, n = globals.content().count(type); id < n; id++){
                ContentDef def = globals.content().get(type, id);
                Object vanilla = VanillaHost.unshard(def);
                assertInstanceOf(Content.class, vanilla, def.name());
                assertSame(def, VanillaHost.shard(vanilla), def.name());
                assertEquals(LExecutor.PrintI.toString(vanilla), world.toString(def), def.name());
                // printchar: UnlockableContent's emojiChar(), nothing for other content
                int emoji = vanilla instanceof UnlockableContent u ? u.emojiChar() : -1;
                assertEquals(emoji, type == com.xpdustry.shard.content.ContentType.BULLET ? -1 : def.emoji(), def.name());
            }
        }
        for(Team team : Team.all){
            assertSame(team, VanillaHost.unshard(globals.team(team.id)));
            assertEquals(LExecutor.PrintI.toString(team), world.toString(globals.team(team.id)), team.name);
        }
    }

    @Test
    void statusEffects(){
        Globals globals = VanillaLogic.globals();
        for(var effect : Vars.content.statusEffects()) assertTrue(globals.statusEffects().contains(effect.name), effect.name);
    }
}
