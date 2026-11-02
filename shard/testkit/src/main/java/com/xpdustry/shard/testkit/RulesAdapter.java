package com.xpdustry.shard.testkit;

import com.xpdustry.shard.sim.rules.*;
import com.xpdustry.shard.sim.world.*;

import java.lang.reflect.*;
import java.util.*;

/**
 * Rules on Shard: the world's {@link WorldRules}, set and projected by vanilla's field names. Projected are the fields
 * {@link OracleRunner} projects of vanilla's {@code Rules}, every primitive or string one: Shard's {@link Rules}
 * declares each of them with vanilla's name and type. Its own primitive fields that vanilla holds as objects (teams,
 * the planet, the sector, objectives) are left out. {@link ShardRunner} always asks it; a world whose systems have no
 * {@link WorldRules} part has no rules to set or project.
 */
public final class RulesAdapter implements WorldAdapter{
    /** Shard's ids and strings for what vanilla's {@code Rules} holds as objects, which the oracle doesn't project. */
    static final Set<String> OBJECTS_IN_VANILLA = Set.of("defaultTeam", "waveTeam", "planet", "planetBackground", "sector", "objectives");
    private static final Map<String, Field> FIELDS = fields();

    @Override
    public boolean rule(World world, String name, Object value){
        Rules rules = rules(world);
        if(rules == null) return false;
        Field field = FIELDS.get(name);
        if(field == null) throw new IllegalArgumentException("no simple rule named '" + name + "' in vanilla's Rules");
        Class<?> type = field.getType();
        Object converted;
        if(type == boolean.class && value instanceof Boolean) converted = value;
        else if(type == int.class && value instanceof Number n) converted = n.intValue();
        else if(type == long.class && value instanceof Number n) converted = n.longValue();
        else if(type == float.class && value instanceof Number n) converted = n.floatValue();
        else if(type == double.class && value instanceof Number n) converted = n.doubleValue();
        else if(type == String.class && value instanceof String) converted = value;
        else throw new IllegalArgumentException("rule " + name + " is a " + type.getSimpleName() + ", not " + Values.show(value));
        try{
            field.set(rules, converted);
        }catch(IllegalAccessException e){
            throw new IllegalStateException(e);
        }
        return true;
    }

    @Override
    public void project(World world, Projection.Builder out, ShardNames names){
        Rules rules = rules(world);
        if(rules == null) return;
        out.rules();
        FIELDS.forEach((name, field) -> {
            try{
                out.rule(name, Values.of(field.get(rules), names::ref));
            }catch(IllegalAccessException e){
                throw new IllegalStateException(e);
            }
        });
    }

    /** The world's rules, or null when it has no {@link WorldRules} part. */
    static Rules rules(World world){
        for(WorldPart part : world.parts()) if(part instanceof WorldRules rules) return rules.rules;
        return null;
    }

    private static Map<String, Field> fields(){
        var out = new TreeMap<String, Field>();
        for(Field field : Rules.class.getFields()){
            Class<?> type = field.getType();
            if(Modifier.isStatic(field.getModifiers()) || OBJECTS_IN_VANILLA.contains(field.getName())) continue;
            if(type.isPrimitive() || type == String.class || type.isEnum()) out.put(field.getName(), field);
        }
        return Collections.unmodifiableMap(out);
    }
}
