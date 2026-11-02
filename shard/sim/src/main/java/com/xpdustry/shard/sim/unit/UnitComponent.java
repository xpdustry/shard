package com.xpdustry.shard.sim.unit;

import com.xpdustry.shard.common.registry.*;

import java.util.*;

/**
 * One vanilla entity component's {@code update()} body. Vanilla's annotation processor merges the bodies of every
 * component a generated unit class has into one {@code update()}, ordered by {@code @MethodPriority} (ascending),
 * then by the component's simple name ({@code String.compareTo}). {@code UnitEntity.update()} is
 * {@code VelComp} (priority -1), {@code BuilderComp}, {@code HealthComp}, {@code ItemsComp}, {@code MinerComp},
 * {@code ShieldComp}, {@code StatusComp}, {@code SyncComp}, {@code UnitComp}, {@code WeaponsComp} (javap of
 * v160.7); a {@code MechUnit} adds {@code MechComp} between {@code ItemsComp} and {@code MinerComp}, a missile
 * {@code TimedKillComp} last (priority 100).
 *
 * <p>Each implementation is registered and stateless: {@link #applies} says which unit types' classes have the
 * component, and {@link UnitParams} keeps each type's merged list ({@link #merged}). A work package porting a
 * component (weapons, statuses, building, mining, payloads, a movement shape) registers its own; nothing here
 * changes. Components whose update only drives drawing ({@code HealthComp.hitTime}, {@code ShieldComp.shieldAlpha})
 * are not ported.
 */
@Registry
public interface UnitComponent{
    /** Merge order: priority, then name, as the annotation processor sorts. */
    Comparator<UnitComponent> ORDER = Comparator.comparingDouble(UnitComponent::priority).thenComparing(UnitComponent::name);

    /** The vanilla component's simple name, such as {@code "VelComp"}: the tie-breaker of the merge order. */
    String name();

    /** The {@code @MethodPriority} of the component's {@code update()}; 0 when it has none. */
    default float priority(){
        return 0f;
    }

    /** Whether the entity class of units with {@code params} has this component. */
    boolean applies(UnitParams params);

    /** The component's {@code update()} body, for one unit. */
    void update(Unit unit);

    /** The registered components that apply to {@code params}, in merge order. */
    static UnitComponent[] merged(UnitParams params){
        return Registered.ALL.stream().filter(component -> component.applies(params)).sorted(ORDER).toArray(UnitComponent[]::new);
    }

    final class Registered{
        static final List<UnitComponent> ALL = Registries.instances(UnitComponent.class);

        private Registered(){
        }
    }
}
