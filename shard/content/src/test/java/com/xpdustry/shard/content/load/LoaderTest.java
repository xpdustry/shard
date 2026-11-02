package com.xpdustry.shard.content.load;

import com.xpdustry.shard.content.*;
import com.xpdustry.shard.content.yaml.*;
import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class LoaderTest{
    /** The vanilla pack plus {@code yaml} as {@code world.yml}. */
    static Pack world(String yaml){
        return Pack.vanilla().with(List.of(new Pack.Source("world.yml", yaml)));
    }

    /** The errors of loading the vanilla pack plus {@code yaml}. */
    static List<String> errors(String yaml){
        var diagnostics = new Diagnostics();
        Loader.load(world(yaml), diagnostics);
        return diagnostics.list().stream().map(Diagnostic::toString).toList();
    }

    static void assertError(String yaml, String... fragments){
        List<String> errors = errors(yaml);
        for(String fragment : fragments){
            assertTrue(errors.stream().anyMatch(e -> e.contains(fragment)), "no error with '" + fragment + "' in " + errors);
        }
    }

    @Test
    void vanillaPackLoadsWithEveryIdFilled(){
        Loaded loaded = Loader.load(Pack.vanilla());
        Ids ids = Pack.vanilla().ids();
        for(ContentType type : ContentType.values()){
            assertEquals(ids.of(type), loaded.names(type), type + " ids");
            long missing = loaded.of(type).stream().filter(Objects::isNull).count();
            // only BuildTurret's unit is derived by the bake rather than written
            assertEquals(type == ContentType.UNIT ? 1 : 0, missing, type + " slots without a prototype");
        }
        assertNull(loaded.get(ContentType.UNIT, "turret-unit-build-tower"));
        assertEquals("item-turret", loaded.get(ContentType.BLOCK, "duo").kind().key());
    }

    @Test
    void parentsMergeInOrderAndAbstractsStayOut(){
        Loaded loaded = Loader.load(world("""
            - type: block
              id: armored
              abstract: true
              kind: wall
              health: 500
              armor: 5
            - type: block
              id: heavy
              abstract: true
              kind: wall
              health: 900
              size: 2
            - type: block
              id: test-wall
              wire: added
              parent: [armored, heavy]
              armor: 7
            """));
        Authored wall = loaded.get(ContentType.BLOCK, "test-wall");
        assertEquals("wall", wall.kind().key());
        assertEquals(500, wall.fields().i("health"), "the first parent wins");
        assertEquals(2, wall.fields().i("size"), "the second parent fills what the first lacks");
        assertEquals(7f, wall.fields().f("armor"), "own fields win");
        assertTrue(wall.added());
        assertEquals(Pack.vanilla().ids().of(ContentType.BLOCK).size(), wall.id(), "added content follows vanilla's ids");
        assertNull(loaded.get(ContentType.BLOCK, "armored"));
        assertEquals("world.yml", wall.mark().source());
        assertEquals(13, wall.mark().line());
    }

    @Test
    void subObjectsOfOneKindMergeFieldByField(){
        Loaded loaded = Loader.load(world("""
            - type: block
              id: base-gun
              abstract: true
              kind: item-turret
              shoot: {kind: shoot-alternate, spread: 4.0}
            - type: block
              id: test-gun
              wire: added
              parent: base-gun
              shoot: {shots: 2}
            """));
        var shoot = loaded.get(ContentType.BLOCK, "test-gun").fields().struct("shoot");
        assertEquals("shoot-alternate", shoot.kind().key());
        assertEquals(4f, shoot.f("spread"));
        assertEquals(2, shoot.i("shots"));
    }

    @Test
    void overlaysChangeAndRemoveFields(){
        Loaded loaded = Loader.load(world("""
            - type: block
              id: duo
              overlay: true
              health: 300
              remove: [inaccuracy]
            """));
        Authored duo = loaded.get(ContentType.BLOCK, "duo");
        assertEquals(300, duo.fields().i("health"));
        assertEquals(0f, duo.fields().f("inaccuracy"), "removed: back to the class default");
        assertEquals(1, duo.fields().i("size"));
        assertFalse(duo.added());
    }

    @Test
    void selfNamesTheContentItself(){
        Loaded loaded = Loader.load(world("""
            - type: block
              id: test-floor
              wire: added
              kind: floor
              blendGroup: self
            """));
        assertEquals("test-floor", loaded.get(ContentType.BLOCK, "test-floor").fields().s("blendGroup"));
    }

    @Test
    void unknownFieldsAreErrorsWithPlaceAndSuggestion(){
        List<String> errors = errors("""
            - type: block
              id: duo
              overlay: true
              helth: 300
            """);
        assertEquals(1, errors.size(), errors.toString());
        assertTrue(errors.getFirst().startsWith("world.yml:4:3"), errors.getFirst());
        assertTrue(errors.getFirst().contains("helth") && errors.getFirst().contains("health"), errors.getFirst());
    }

    @Test
    void everyErrorIsReportedInOneLoad(){
        List<String> errors = errors("""
            - type: block
              id: duo
              overlay: true
              health: lots
              reload: [1]
            - type: item
              id: copper
              overlay: true
              hardness: 1.5
            """);
        assertEquals(3, errors.size(), errors.toString());
    }

    @Test
    void badPrototypesAreErrors(){
        assertError("""
            - type: block
              id: duo
              kind: wall
            """, "already defined", "overlay: true");
        assertError("""
            - type: block
              id: mystery
              wire: added
              kind: no-such-kind
            """, "no-such-kind");
        assertError("""
            - type: block
              id: loop-a
              abstract: true
              parent: loop-b
            - type: block
              id: loop-b
              abstract: true
              parent: loop-a
            - type: block
              id: loop-user
              wire: added
              parent: loop-a
            """, "is its own ancestor");
        assertError("""
            - type: block
              id: orphan
              wire: added
              parent: nobody
            """, "nobody");
        assertError("""
            - type: block
              id: ghost
              overlay: true
              health: 5
            """, "overlay of a prototype that isn't defined");
        assertError("""
            - type: block
              id: duo
              overlay: true
              kind: wall
            """, "can't change the kind");
        assertError("""
            - type: block
              id: fresh
              wire: added
              kind: wall
              remove: [health]
            """, "remove is for overlays");
        assertError("""
            - type: block
              id: unlisted
              kind: wall
            """, "needs wire: added");
        assertError("""
            - type: unit
              id: turret-unit-build-tower
              wire: added
              kind: unit-type
            """, "the client already has a unit turret-unit-build-tower");
        assertError("""
            - type: block
              id: duo
              overlay: true
              ammoTypes: {unobtainium: duo/ammoTypes.copper}
            """, "unobtainium");
        assertError("""
            - type: block
              id: duo
              overlay: true
              health: [unclosed
            """, "world.yml");
    }
}
