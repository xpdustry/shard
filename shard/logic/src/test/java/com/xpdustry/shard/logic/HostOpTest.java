package com.xpdustry.shard.logic;

import com.xpdustry.shard.logic.HostOp.*;
import com.xpdustry.shard.logic.lang.*;
import org.junit.jupiter.api.*;

import java.util.*;

import static com.xpdustry.shard.logic.TestHost.*;
import static org.junit.jupiter.api.Assertions.*;

/** Unit and world-processor statements reach the host as typed records, with the VM-side parts done by the VM. */
class HostOpTest{
    final TestHost host = new TestHost();

    private Processor world(String code){
        Processor p = host.processor(0, 0, code + "\nstop", true, 8, 1000);
        host.run(2, p);
        return p;
    }

    @Test
    void unitStatements(){
        Processor p = host.processor(0, 0, """
            ubind @poly
            ucontrol move 10 20 0 0 0
            ulocate building core true @copper outx outy found build
            radar enemy any any distance @this 1 result
            uradar enemy ground any health 0 0 result2
            stop
            """);
        host.run(2, p);
        assertEquals(List.of("UnitBind", "UnitControl", "UnitLocate", "Radar", "Radar"), host.calls);
        UnitControl move = (UnitControl)p.program().hostOp(1);
        assertEquals(10, p.num(move.p1()));
        assertEquals(20, p.num(move.p2()));
        assertEquals(LUnitControl.move, move.type());
        Radar uradar = (Radar)p.program().hostOp(4);
        assertEquals("@unit", p.program().slotNames[uradar.radar()]);
    }

    @Test
    void worldStatements(){
        world("""
            setblock block @memory-cell 1 2 @sharded 0
            spawn @poly 10 10 90 @sharded result
            setrule waveTimer false 0 0 100 100
            explosion @crux 10 10 5 50 true true false
            fetch unitCount result @sharded 0 @poly
            getflag f "flag"
            setflag "flag" true
            """);
        assertEquals(List.of("SetBlock", "SpawnUnit", "SetRule", "Explosion", "Fetch", "GetFlag", "SetFlag"), host.calls);
    }

    @Test
    void messagesClearTheText(){
        Processor p = world("print \"hi\"\nmessage announce 3 ok\nprint \"x\"\nmessage mission 0 ok2");
        assertEquals(1, num(p, "ok"));
        assertEquals(1, num(p, "ok2"));
        assertEquals("", p.text().toString());
        assertEquals(List.of("Message"), host.calls, "only the mission text matters to a server");
    }

    @Test
    void clientStatementsDoNothingByDefault(){
        world("cutscene pan 0 0 0.1 0\nplaysound false @sfx-shoot 1 1 0 0 0 true\neffect warn 1 1 0 %ffffff 0");
        assertEquals(List.of("Cutscene", "PlaySound", "Effect"), host.calls);
    }

    @Test
    void syncIsRateLimited(){
        Processor p = host.processor(0, 0, "op add x x 1\nsync x\nsync @counter\nwait 0", true, 8, 1000);
        host.run(13, p);
        // ticks 2..13 run it; a variable syncs when more than 3 ticks passed since its last sync
        assertEquals(List.of("x=1.0", "@counter=3.0", "x=5.0", "@counter=3.0", "x=9.0", "@counter=3.0"), host.synced);
    }

    @Test
    void unitIsTheBoundUnit(){
        Processor p = host.processor(0, 0, "stop");
        assertNull(p.unit());
        Object unit = new Object();
        p.bindUnit(unit);
        assertSame(unit, p.unit());
        p.setCode("set u @unit\nstop");
        assertNull(p.unit(), "new code drops the unit, as updateCode does");
    }
}
