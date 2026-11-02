package com.xpdustry.shard.sim.unit;

import com.xpdustry.shard.common.math.*;
import com.xpdustry.shard.common.struct.*;
import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.world.*;

import java.util.*;

/**
 * The status effects on a unit, in application order (vanilla {@code StatusComp.statuses}, an ordered {@code Seq}),
 * with the multipliers {@code StatusComp.update} folds them into each tick (WP42). Effects are status content ids;
 * their stats and transitions come from the world's {@link StatusTable}.
 *
 * <p>Applying ({@link #apply}, {@link #setDuration}), in vanilla's order: an empty, unknown or immune effect does
 * nothing; an entry of the same effect is extended (or set, when shortening); otherwise the first entry whose effect
 * has a transition to it reacts ({@link StatusTable#transition}) and the search stops; otherwise a non-reactive effect
 * is appended. Vanilla quirks kept: the {@link #hasEffect} bits are only cleared by {@link #update}, so they stay set
 * after {@link #unapply}, {@link #clear} and a transition to another effect until the next update; {@link #unapply}
 * removes only the first matching entry.
 *
 * <p>The multipliers and {@code hasEffect} bits are transient in vanilla, but they are read until the next status
 * update, so they are state here. Every field is {@link Persistence#AUTHORITATIVE}, the entries in order.
 *
 * <p>Deviation: vanilla obtains entries from Arc's global {@code StatusEntry} pool without resetting the dynamic
 * multipliers, so a dynamic status applied after another unit's dynamic status was unapplied inherits its
 * multipliers. Shard's entries start fresh.
 */
public final class Statuses implements Checkpointable{
    private StatusEntry[] entries;
    private int size;
    /** {@code StatusComp.applied}: the effects seen by the last update, plus those added since. */
    private Bits applied;

    public float speedMultiplier = 1f, damageMultiplier = 1f, healthMultiplier = 1f, reloadMultiplier = 1f;
    public float buildSpeedMultiplier = 1f, dragMultiplier = 1f, armorOverride = -1f;
    public boolean disarmed;

    public Statuses(){
        entries = new StatusEntry[4];
        applied = new Bits();
    }

    private Statuses(Statuses source){
        entries = new StatusEntry[source.entries.length];
        size = source.size;
        for(int i = 0; i < size; i++) entries[i] = source.entries[i].copy();
        applied = source.applied.copy();
        speedMultiplier = source.speedMultiplier;
        damageMultiplier = source.damageMultiplier;
        healthMultiplier = source.healthMultiplier;
        reloadMultiplier = source.reloadMultiplier;
        buildSpeedMultiplier = source.buildSpeedMultiplier;
        dragMultiplier = source.dragMultiplier;
        armorOverride = source.armorOverride;
        disarmed = source.disarmed;
    }

    public int size(){
        return size;
    }

    public StatusEntry get(int index){
        Objects.checkIndex(index, size);
        return entries[index];
    }

    public void add(StatusEntry entry){
        Objects.requireNonNull(entry, "entry");
        if(size == entries.length) entries = Arrays.copyOf(entries, size * 7 / 4 + 1);
        entries[size++] = entry;
    }

    /** Removes the entry at {@code index}, keeping the order of the rest. */
    public StatusEntry remove(int index){
        Objects.checkIndex(index, size);
        StatusEntry entry = entries[index];
        System.arraycopy(entries, index + 1, entries, index, size - index - 1);
        entries[--size] = null;
        return entry;
    }

    /** Vanilla's {@code clearStatuses()}: every entry goes; the {@link #hasEffect} bits stay until the next update. */
    public void clear(){
        Arrays.fill(entries, 0, size, null);
        size = 0;
    }

    // region applying (StatusComp)

    /** {@code apply(effect)}: for one tick, as permanent effects are applied. */
    public void apply(Unit unit, int effect){
        apply(unit, effect, 1f);
    }

    /** {@code apply(effect, duration)}: adds, extends or reacts. */
    public void apply(Unit unit, int effect, float duration){
        applyStatus(unit, effect, duration, false);
    }

    /** {@code setDuration(effect, duration)}: as {@link #apply}, but sets the time, and 0 removes the effect. */
    public void setDuration(Unit unit, int effect, float duration){
        applyStatus(unit, effect, duration, true);
    }

    /** {@code getDuration(effect)}: the first entry's time, or 0. */
    public float getDuration(int effect){
        for(int i = 0; i < size; i++){
            if(entries[i].effect == effect) return entries[i].time;
        }
        return 0f;
    }

    /** {@code hasEffect(effect)}: the effect was on the unit at the last update, or applied since. */
    public boolean hasEffect(int effect){
        return effect >= 0 && applied.get(effect);
    }

    /** {@code isBoss()}. */
    public boolean isBoss(World world){
        return hasEffect(StatusTable.of(world).boss());
    }

    /** {@code isImmune(effect)}: {@code type.immunities.contains(effect)}. */
    public static boolean isImmune(Unit unit, int effect){
        return StatusTable.of(unit.world()).immune(unit.type(), effect);
    }

    /** {@code unapply(effect)}: removes the first entry of {@code effect}, if any. */
    public void unapply(int effect){
        for(int i = 0; i < size; i++){
            if(entries[i].effect == effect){
                remove(i);
                return;
            }
        }
    }

    /** {@code StatusComp.applyStatus} (StatusComp.java:46). */
    private void applyStatus(Unit unit, int effect, float duration, boolean shorten){
        StatusTable table = StatusTable.of(unit.world());
        if(!table.exists(effect) || effect == table.none() || table.immune(unit.type(), effect)) return;

        if(shorten && duration == 0f){
            if(hasEffect(effect)) unapply(effect);
            return;
        }

        for(int i = 0; i < size; i++){
            StatusEntry entry = entries[i];
            if(entry.effect == effect){
                entry.time = shorten ? duration : Math.max(entry.time, duration);
                return;
            }
            // vanilla would throw on an entry of unknown content here
            if(table.exists(entry.effect) && transition(unit, table, entry, effect, duration)){
                // only the first reaction counts
                return;
            }
        }

        if(!table.reactive(effect)){
            add(new StatusEntry(effect, duration));
            applied.set(effect);
        }
    }

    /** {@code entry.effect.applyTransition(unit, to, entry, time)}: whether {@code entry}'s effect reacts to {@code to}. */
    private static boolean transition(Unit unit, StatusTable table, StatusEntry entry, int to, float time){
        byte code = table.transition(entry.effect, to);
        if(code == StatusTable.NONE) return false;
        if(code == StatusTable.OPPOSITE){
            // StatusEffect.handleOpposite: the applied effect takes over once it outlasts half of the entry
            entry.time -= time * 0.5f;
            if(entry.time <= 0f){
                entry.time = time;
                entry.effect = to;
            }
            return true;
        }
        Rand rand = unit.world().rand();
        switch(StatusTable.Reaction.of(code)){
            case BURNING_TARRED -> {
                unit.damagePierce(table.transitionDamage(entry.effect));
                // Fx.burning.at(x + Mathf.range(bounds / 2), y + Mathf.range(bounds / 2)): the offsets draw even headless
                Mathf.range(rand, unit.bounds() / 2f);
                Mathf.range(rand, unit.bounds() / 2f);
                entry.time = Math.min(time + entry.time, 300f);
            }
            case FREEZING_BLASTED -> unit.damagePierce(table.transitionDamage(entry.effect));
            case WET_SHOCKED -> unit.damage(table.transitionDamage(entry.effect));
            case MELTING_TARRED -> {
                unit.damagePierce(8f);
                Mathf.range(rand, unit.bounds() / 2f);
                Mathf.range(rand, unit.bounds() / 2f);
                entry.time = Math.min(time + entry.time, 200f);
            }
            case TARRED_MELTING -> {
                entry.time = entry.time + time;
                entry.effect = table.id("melting");
            }
            case TARRED_BURNING -> {
                entry.time = entry.time + time;
                entry.effect = table.id("burning");
            }
        }
        return true;
    }

    // endregion

    // region dynamic statuses

    /**
     * {@code applyDynamicStatus()}: the unit's dynamic entry, added (forever) if it has none. Its multipliers are the
     * caller's to set.
     */
    public StatusEntry applyDynamicStatus(Unit unit){
        StatusTable table = StatusTable.of(unit.world());
        int dynamic = table.dynamicEffect();
        if(hasEffect(dynamic)){
            for(int i = 0; i < size; i++){
                if(table.exists(entries[i].effect) && table.dynamic(entries[i].effect)) return entries[i];
            }
        }
        var entry = new StatusEntry(dynamic, Float.POSITIVE_INFINITY);
        add(entry);
        applied.set(dynamic);
        return entry;
    }

    /** {@code statusSpeed(speed)}: the speed in tiles per second. */
    public void statusSpeed(Unit unit, float speed){
        applyDynamicStatus(unit).speedMultiplier = speed / (unit.params().speed() * 60f / 8f);
    }

    public void statusDamageMultiplier(Unit unit, float damageMultiplier){
        applyDynamicStatus(unit).damageMultiplier = damageMultiplier;
    }

    public void statusReloadMultiplier(Unit unit, float reloadMultiplier){
        applyDynamicStatus(unit).reloadMultiplier = reloadMultiplier;
    }

    /** {@code statusMaxHealth(health)}. */
    public void statusMaxHealth(Unit unit, float health){
        applyDynamicStatus(unit).healthMultiplier = health / unit.maxHealth;
    }

    /** {@code statusBuildSpeed(buildSpeed)}. */
    public void statusBuildSpeed(Unit unit, float buildSpeed){
        applyDynamicStatus(unit).buildSpeedMultiplier = buildSpeed / unit.type().fields().f("buildSpeed");
    }

    /** {@code statusDrag(drag)}: a type without drag gets none. */
    public void statusDrag(Unit unit, float drag){
        float typeDrag = unit.params().drag();
        applyDynamicStatus(unit).dragMultiplier = typeDrag == 0f ? 0f : drag / typeDrag;
    }

    /** {@code statusArmor(armor)}: an armor override, at 0 or more. */
    public void statusArmor(Unit unit, float armor){
        applyDynamicStatus(unit).armorOverride = armor;
    }

    // endregion

    /**
     * {@code StatusComp.update()} (StatusComp.java:194): the floor's status for grounded units, then each entry ticks
     * down; expired ones (unless permanent) and unknown ones leave, the rest fold their multipliers in and run their
     * effect's update: damage or healing per tick, and interval damage.
     */
    void update(Unit unit){
        StatusTable table = StatusTable.of(unit.world());
        int floor = unit.floorOn();
        if(unit.isGrounded() && !unit.params().hovering()){
            apply(unit, table.floorStatus(floor), table.floorStatusDuration(floor));
        }

        applied.clear();
        armorOverride = -1f;
        speedMultiplier = damageMultiplier = healthMultiplier = reloadMultiplier = buildSpeedMultiplier = dragMultiplier = 1f;
        disarmed = false;

        int index = 0;
        while(index < size){
            StatusEntry entry = entries[index++];
            entry.time = Math.max(entry.time - 1f, 0f);
            int effect = entry.effect;

            if(!table.exists(effect) || (entry.time <= 0f && !table.permanent(effect))){
                index--;
                remove(index);
                continue;
            }
            applied.set(effect);
            if(table.dynamic(effect)){
                speedMultiplier *= entry.speedMultiplier;
                healthMultiplier *= entry.healthMultiplier;
                damageMultiplier *= entry.damageMultiplier;
                reloadMultiplier *= entry.reloadMultiplier;
                buildSpeedMultiplier *= entry.buildSpeedMultiplier;
                dragMultiplier *= entry.dragMultiplier;
                if(entry.armorOverride >= 0f) armorOverride = entry.armorOverride;
            }else{
                speedMultiplier *= table.speedMultiplier(effect);
                healthMultiplier *= table.healthMultiplier(effect);
                damageMultiplier *= table.damageMultiplier(effect);
                reloadMultiplier *= table.reloadMultiplier(effect);
                buildSpeedMultiplier *= table.buildSpeedMultiplier(effect);
                dragMultiplier *= table.dragMultiplier(effect);
            }
            disarmed |= table.disarm(effect);
            effectUpdate(unit, table, entry);
        }
    }

    /** {@code StatusEffect.update(unit, entry)}; the visual effect's chance is drawn only off headless. */
    private static void effectUpdate(Unit unit, StatusTable table, StatusEntry entry){
        int effect = entry.effect;
        float damage = table.damage(effect);
        if(damage > 0f){
            unit.damageContinuousPierce(damage);
        }else if(damage < 0f){
            unit.heal(-1f * damage);
        }
        float interval = table.intervalDamageTime(effect);
        if(interval > 0f){
            entry.damageTime += 1f;
            if(entry.damageTime >= interval){
                entry.damageTime %= interval;
                if(table.intervalDamagePierce(effect)){
                    unit.damagePierce(table.intervalDamage(effect));
                }else{
                    unit.damage(table.intervalDamage(effect));
                }
            }
        }
    }

    Statuses copy(){
        return new Statuses(this);
    }

    /** The entries in order, then the set effects of the {@link #hasEffect} bits; spare capacity isn't state. */
    @Override
    public void write(CheckpointOut out){
        out.list("entries", Arrays.asList(entries).subList(0, size)).i("size", size).intSeq("applied", setBits(applied));
        out.f("speedMultiplier", speedMultiplier).f("damageMultiplier", damageMultiplier).f("healthMultiplier", healthMultiplier)
            .f("reloadMultiplier", reloadMultiplier).f("buildSpeedMultiplier", buildSpeedMultiplier)
            .f("dragMultiplier", dragMultiplier).f("armorOverride", armorOverride).bool("disarmed", disarmed);
    }

    @Override
    public void read(CheckpointIn in){
        List<StatusEntry> stored = in.list("entries", StatusEntry.class);
        size = in.i("size");
        if(stored == null || stored.size() != size || stored.contains(null)) throw new CheckpointException("a unit's statuses are inconsistent");
        entries = stored.toArray(new StatusEntry[Math.max(4, size)]);
        IntSeq bits = in.intSeq("applied");
        applied = new Bits();
        for(int i = 0; i < bits.size; i++){
            if(bits.items[i] < 0) throw new CheckpointException("a unit's applied statuses are corrupt");
            applied.set(bits.items[i]);
        }
        speedMultiplier = in.f("speedMultiplier");
        damageMultiplier = in.f("damageMultiplier");
        healthMultiplier = in.f("healthMultiplier");
        reloadMultiplier = in.f("reloadMultiplier");
        buildSpeedMultiplier = in.f("buildSpeedMultiplier");
        dragMultiplier = in.f("dragMultiplier");
        armorOverride = in.f("armorOverride");
        disarmed = in.bool("disarmed");
    }

    private static IntSeq setBits(Bits bits){
        var out = new IntSeq();
        for(int i = bits.nextSetBit(0); i >= 0; i = bits.nextSetBit(i + 1)) out.add(i);
        return out;
    }

    void hash(Hasher hasher){
        hasher.i(size);
        for(int i = 0; i < size; i++) entries[i].hash(hasher);
        IntSeq bits = setBits(applied);
        hasher.ints(bits.items, 0, bits.size);
        hasher.f(speedMultiplier).f(damageMultiplier).f(healthMultiplier).f(reloadMultiplier);
        hasher.f(buildSpeedMultiplier).f(dragMultiplier).f(armorOverride).bool(disarmed);
    }
}
