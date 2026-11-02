package com.xpdustry.shard.common.registry;

import com.xpdustry.shard.common.registry.fixtures.*;
import com.xpdustry.shard.common.registry.fixtures.a.*;
import com.xpdustry.shard.common.registry.fixtures.b.*;
import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The index the processor wrote for this module's test classes: two registered classes in two packages, read back
 * sorted, with no hand-edited list anywhere.
 */
class RegistriesTest{
    @Test
    void registeredClassesFromTwoPackagesAreFoundSorted(){
        assertEquals(List.of(Conveyor.class, Router.class), Registries.classes(Sample.class));
        List<Sample> instances = Registries.instances(Sample.class);
        assertEquals(List.of("conveyor", "router"), instances.stream().map(Sample::name).toList());
    }

    @Test
    void aTypeThatIsNotARegistryIsRefused(){
        assertThrows(IllegalArgumentException.class, () -> Registries.classes(Runnable.class));
    }

    @Test
    void aRegistryWithNothingRegisteredIsEmpty(){
        assertEquals(List.of(), Registries.classes(Empty.class));
    }

    @Registry
    public interface Empty{
    }
}
