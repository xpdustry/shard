package com.xpdustry.shard.sim.building;

import com.xpdustry.shard.content.*;
import com.xpdustry.shard.sim.toy.*;
import com.xpdustry.shard.sim.world.*;
import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class KindRegistryTest{
    @Test
    void theBuiltInRegistryReadsKindsFromTheIndex(){
        KindRegistry kinds = KindRegistry.builtIn();
        assertEquals(Counter.class, kinds.type(KindKey.of("test-counter")));
        assertEquals(Link.class, kinds.type(KindKey.of("test-link")));
        assertTrue(kinds.keys().contains(KindKey.of("test-wall")));
    }

    @Test
    void duplicateAndMalformedKindsAreRefused(){
        var duplicate = assertThrows(IllegalArgumentException.class, () -> KindRegistry.of(List.of(Counter.class, Counter.class)));
        assertTrue(duplicate.getMessage().contains("registered twice"), duplicate.getMessage());
        for(String bad : List.of("Shouting", "snake_case", "-leading", "trailing-", "double--dash", "9lives", "")){
            assertThrows(IllegalArgumentException.class, () -> KindKey.of(bad), bad);
        }
        var missing = assertThrows(IllegalArgumentException.class, () -> KindRegistry.of(List.of(Unannotated.class)));
        assertTrue(missing.getMessage().contains("has no @Kind"), missing.getMessage());
    }

    @Test
    void contentWithAnUnsupportedKindIsALoadError(){
        var content = new ToyContent(List.of(new ToyContent.Block(0, "mystery", KindKey.of("not-ported"), 1, 1f,
            false, false, false, false, false)));
        var error = assertThrows(IllegalStateException.class, () -> new World(WorldConfig.of(content, 8, 8, 0)));
        assertTrue(error.getMessage().contains("mystery (not-ported)"), error.getMessage());
    }

    @Test
    void tileKindsAreSupportedButNeverInstantiated(){
        KindRegistry kinds = KindRegistry.builtIn();
        KindKey floor = KindKey.of("floor");
        assertTrue(kinds.supports(floor) && kinds.tileKind(floor), "the environment registers its kinds");
        assertFalse(kinds.tileKind(KindKey.of("test-counter")));
        assertFalse(kinds.withPassthrough().passesThrough(floor), "a tile kind is supported, not passed through");
        assertThrows(IllegalStateException.class, () -> kinds.type(floor));
        var sand = new ToyContent.Block(1, "sand", floor, 1, 10f, true, false, false, false, false);
        var error = assertThrows(IllegalStateException.class, () -> kinds.instantiate(sand));
        assertTrue(error.getMessage().contains("tile kind"), error.getMessage());
        var duplicate = assertThrows(IllegalArgumentException.class,
            () -> KindRegistry.of(List.of(Counter.class), List.of(KindKey.of("test-counter"))));
        assertTrue(duplicate.getMessage().contains("registered twice"), duplicate.getMessage());
    }

    @Test
    void withKindReplacesOneBindingInACopy(){
        KindRegistry kinds = KindRegistry.builtIn().withPassthrough();
        KindKey counter = KindKey.of("test-counter");
        KindRegistry instrumented = kinds.withKind(counter, Unannotated.class, Unannotated::new);
        assertEquals(Unannotated.class, instrumented.type(counter));
        assertEquals(Counter.class, kinds.type(counter), "the original keeps its class");
        assertTrue(instrumented.passthrough());
        assertEquals(Unannotated.class, KindRegistry.of(List.of()).withKind(KindKey.of("new-kind"), Unannotated.class,
            Unannotated::new).type(KindKey.of("new-kind")));
        var tile = assertThrows(IllegalArgumentException.class, () -> kinds.withKind(KindKey.of("floor"), Unannotated.class, Unannotated::new));
        assertTrue(tile.getMessage().contains("tile kind"), tile.getMessage());
        assertEquals(Link.class, kinds.withKind(counter, Link.class).type(counter), "a registered class needs no constructor");
        var unregistered = assertThrows(IllegalArgumentException.class, () -> kinds.withKind(counter, Unannotated.class));
        assertTrue(unregistered.getMessage().contains("not registered"), unregistered.getMessage());
    }

    /** Not registered, having no {@code @Kind}. */
    public static final class Unannotated extends Building{
    }
}
