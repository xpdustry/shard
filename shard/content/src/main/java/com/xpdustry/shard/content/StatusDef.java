package com.xpdustry.shard.content;

import java.util.*;

/** The typed, immutable definition of a status effect ({@code mindustry.type.StatusEffect}). */
public interface StatusDef extends ContentDef{
    @Override
    default ContentType type(){
        return ContentType.STATUS;
    }

    /** Damage per tick; negative heals. */
    default float damage(){
        return fields().f("damage");
    }

    default float damageMultiplier(){
        return fields().f("damageMultiplier");
    }

    default float healthMultiplier(){
        return fields().f("healthMultiplier");
    }

    default float speedMultiplier(){
        return fields().f("speedMultiplier");
    }

    default float reloadMultiplier(){
        return fields().f("reloadMultiplier");
    }

    default float buildSpeedMultiplier(){
        return fields().f("buildSpeedMultiplier");
    }

    default float dragMultiplier(){
        return fields().f("dragMultiplier");
    }

    default float transitionDamage(){
        return fields().f("transitionDamage");
    }

    default boolean disarm(){
        return fields().b("disarm");
    }

    /** Ticks between interval hits; at most 0 disables them. */
    default float intervalDamageTime(){
        return fields().f("intervalDamageTime");
    }

    default float intervalDamage(){
        return fields().f("intervalDamage");
    }

    /** Interval hits ignore armor. */
    default boolean intervalDamagePierce(){
        return fields().b("intervalDamagePierce");
    }

    default boolean permanent(){
        return fields().b("permanent");
    }

    /** Applied once, for its reactions, and never kept. */
    default boolean reactive(){
        return fields().b("reactive");
    }

    /** Its multipliers are per unit, set by whoever applies it. */
    default boolean dynamic(){
        return fields().b("dynamic");
    }

    /** Statuses that react with this one ({@code StatusEffect.affinity}), in id order. */
    List<StatusDef> affinities();

    /** Statuses that this one removes, in id order. */
    List<StatusDef> opposites();
}
