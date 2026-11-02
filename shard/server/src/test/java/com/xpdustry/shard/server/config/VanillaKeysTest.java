package com.xpdustry.shard.server.config;

import org.junit.jupiter.api.*;

import java.lang.reflect.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Against the vanilla jar on the test classpath: the class is only inspected, never initialized. */
class VanillaKeysTest{
    @Test
    void everyVanillaConfigKeyIsMappedOrDropped() throws Exception{
        var config = Class.forName("mindustry.net.Administration$Config", false, getClass().getClassLoader());
        var fields = new TreeSet<String>();
        for(Field field : config.getDeclaredFields()){
            if(Modifier.isStatic(field.getModifiers()) && field.getType() == config) fields.add(field.getName());
        }
        assertEquals(34, fields.size(), fields.toString());
        assertEquals(fields, VanillaKeys.fields());
    }

    @Test
    void entriesAreConsistent(){
        var names = new HashSet<String>();
        for(var entry : VanillaKeys.ALL){
            assertTrue(names.add(entry.name()), "duplicate " + entry.name());
            if(entry.isDropped()) assertFalse(entry.note().isEmpty(), entry.name() + " is dropped without a reason");
            else assertTrue(ConfigKeys.ALL.contains(entry.key()), entry.name());
        }
    }
}
