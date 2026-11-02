package com.xpdustry.shard.content.reload;

import com.xpdustry.shard.content.*;
import com.xpdustry.shard.content.epoch.*;
import com.xpdustry.shard.content.load.*;
import com.xpdustry.shard.content.schema.*;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class ReloadTest{
    static final Epoch current = Epoch.vanilla();

    static Candidate candidate(String yaml){
        return Reload.candidate(current, Pack.vanilla().with(List.of(new Pack.Source("world.yml", yaml))), block -> true);
    }

    @Test
    void serverFieldChangesApplyAlone(){
        Candidate c = candidate("""
            - type: unit
              id: dagger
              overlay: true
              circleTarget: true
            """);
        assertTrue(c.accepted(), c.diagnostics().toString());
        assertEquals(List.of(new Change(ContentType.UNIT, "dagger", "circleTarget", Tag.SERVER)), c.changes());
        assertTrue(c.serverOnly());
    }

    @Test
    void sharedChangesListDerivedFieldsToo(){
        Candidate c = candidate("""
            - type: block
              id: duo
              overlay: true
              requirements: {copper: 50}
            """);
        assertTrue(c.accepted(), c.diagnostics().toString());
        assertFalse(c.serverOnly());
        assertTrue(c.changes().contains(new Change(ContentType.BLOCK, "duo", "requirements", Tag.SHARED)));
        assertTrue(c.changes().contains(new Change(ContentType.BLOCK, "duo", "buildTime", Tag.SHARED)), c.changes().toString());
    }

    @Test
    void unknownFieldsAreRefused(){
        Candidate c = candidate("""
            - type: block
              id: duo
              overlay: true
              helth: 300
            """);
        assertFalse(c.accepted());
        assertNull(c.epoch());
        assertTrue(c.diagnostics().getFirst().message().contains("helth"));
    }

    @Test
    void noPatchChangesAreRefused(){
        Candidate c = candidate("""
            - type: block
              id: duo
              overlay: true
              size: 2
            """);
        assertFalse(c.accepted());
        assertTrue(c.diagnostics().getFirst().message().contains("@NoPatch"), c.diagnostics().toString());
    }

    @Test
    void unsupportedKindsAreRefused(){
        Candidate c = Reload.candidate(current, Pack.vanilla(), block -> !block.kind().equals(KindKey.of("item-turret")));
        assertFalse(c.accepted());
        assertTrue(c.diagnostics().stream().anyMatch(d -> d.message().startsWith("block duo has kind item-turret")));
        assertTrue(c.changes().isEmpty());
    }
}
