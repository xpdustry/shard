package com.xpdustry.shard.content;

/** The typed, immutable definition of a liquid or gas ({@code mindustry.type.Liquid}). */
public interface LiquidDef extends ContentDef{
    @Override
    default ContentType type(){
        return ContentType.LIQUID;
    }

    default boolean gas(){
        return fields().b("gas");
    }

    /** Whether turrets and reactors accept it as coolant. */
    default boolean coolant(){
        return fields().b("coolant");
    }

    default float heatCapacity(){
        return fields().f("heatCapacity");
    }

    default float temperature(){
        return fields().f("temperature");
    }

    default float viscosity(){
        return fields().f("viscosity");
    }

    default float flammability(){
        return fields().f("flammability");
    }

    default float explosiveness(){
        return fields().f("explosiveness");
    }

    /** The temperature it boils at, or -1 ({@code Liquid.init} sets -1 for gases). */
    default float boilPoint(){
        return fields().f("boilPoint");
    }

    default boolean capPuddles(){
        return fields().b("capPuddles");
    }

    default boolean moveThroughBlocks(){
        return fields().b("moveThroughBlocks");
    }

    default boolean blockReactive(){
        return fields().b("blockReactive");
    }

    default boolean incinerable(){
        return fields().b("incinerable");
    }

    default boolean hidden(){
        return fields().b("hidden");
    }

    /** The status a puddle of it applies, or null. */
    StatusDef effect();

    /** The names of the liquids a puddle of it can lie on without reacting ({@code canStayOn}: oil on water). */
    default java.util.List<String> canStayOn(){
        var out = new java.util.ArrayList<String>();
        for(Object liquid : fields().list("canStayOn")) out.add((String)liquid);
        return java.util.List.copyOf(out);
    }

    /** A {@code cell-liquid}'s {@code spreadTarget}: the name of the liquid it eats, or null. */
    default String spreadTarget(){
        return fields().s("spreadTarget");
    }

    /** A {@code cell-liquid}'s {@code maxSpread}. */
    default float maxSpread(){
        return fields().f("maxSpread");
    }

    /** A {@code cell-liquid}'s {@code spreadConversion}. */
    default float spreadConversion(){
        return fields().f("spreadConversion");
    }

    /** A {@code cell-liquid}'s {@code spreadDamage}. */
    default float spreadDamage(){
        return fields().f("spreadDamage");
    }

    /** A {@code cell-liquid}'s {@code removeScaling}. */
    default float removeScaling(){
        return fields().f("removeScaling");
    }
}
