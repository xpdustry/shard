package com.xpdustry.shard.sim.unit.ability;

import com.xpdustry.shard.common.math.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.spatial.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.world.*;

import java.util.*;

/**
 * Vanilla's {@code RepairFieldAbility} (RepairFieldAbility.java): every {@code reload} ticks, heals the damaged units of
 * its team in range, itself included. Nova, poly and oct carry it.
 *
 * <p>With {@code maxTargets}, it collects at most {@code maxTargets + 2} damaged units in the team tree's order, then
 * heals the closest ({@code sameTypeHealMult < 1} pushes its own type back by 80 world units). With {@code smartHeal},
 * it waits until the missing health is worth a heal, rechecking every {@code smartInterval} ticks, unless a unit is
 * below {@code smartHealPercent} or nothing changed for {@code smartDowntime} ticks. A heal draws a sound pitch; a check
 * that heals nothing draws again only with {@code randDesync}.
 */
@AbilityKind("repair-field-ability")
public final class RepairFieldAbility extends Ability{
    private float amount, reload, range, healPercent, sameTypeHealMult, smartHealPercent, smartHealStrength, smartDowntime;
    private float smartInterval, randDesync;
    private int maxTargets;
    private boolean smartHeal;

    @Override
    protected void load(AbilityDef def, int index, ContentEpoch content){
        super.load(def, index, content);
        amount = def.amount();
        reload = def.reload();
        range = def.range();
        healPercent = def.healPercent();
        sameTypeHealMult = def.sameTypeHealMult();
        maxTargets = def.maxTargets();
        smartHeal = def.smartHeal();
        smartHealPercent = def.smartHealPercent();
        smartHealStrength = def.smartHealStrength();
        smartDowntime = def.smartDowntime();
        smartInterval = def.smartInterval();
        randDesync = def.randDesync();
    }

    @Override
    public UnitAbility create(){
        return new State();
    }

    @Override
    public void update(Unit unit, UnitAbility ability){
        var state = (State)ability;
        state.healTimer += 1f;
        state.downTimer = smartHeal && state.healthChange >= state.healthMissing && state.healthMissing > 0f ? state.downTimer + 1f : 0f;
        if(state.healTimer < reload) return;

        var targets = new ArrayList<Unit>();
        boolean[] healNow = {false};
        state.healthChange = state.healthMissing;
        // healthMissing, sumMaxHealth, sumTypeMult
        float[] sums = new float[3];
        boolean limitTargets = maxTargets >= 0;
        float healPercentMult = healPercent / 100f;

        SpatialIndex index = SpatialIndex.of(unit.world());
        if(index != null){
            index.nearby(unit.team, unit.x, unit.y, range, other -> {
                // check for 2 more targets just in case
                if(limitTargets && targets.size() >= maxTargets + 2) return;
                if(other.damaged()){
                    targets.add(other);
                    if(smartHeal){
                        float maxHealth = other.maxHealth;
                        sums[0] += maxHealth - other.health;
                        sums[1] += maxHealth;
                        sums[2] += unit.type() == other.type() ? sameTypeHealMult : 1f;
                        if(other.healthf() < smartHealPercent) healNow[0] = true;
                    }
                }
            });
        }
        state.healthMissing = sums[0];
        float healthMissing = sums[0], sumMaxHealth = sums[1], sumTypeMult = sums[2];
        int targetCount = targets.size();

        // mixed approach, care both about groups and single low hp units
        float ratio = amount + healPercentMult * sumMaxHealth * (sumTypeMult / targetCount);
        float requiredHeals = (healthMissing * 0.7f + healthMissing / (limitTargets ? maxTargets : targetCount) * 0.3f) / smartHealStrength / ratio;

        if(requiredHeals >= 1f || !smartHeal || healNow[0] || state.downTimer >= smartDowntime){
            // sort closest if the number of targets is limited; Seq.sort is stable, as List.sort is
            if(limitTargets){
                boolean isSameType = sameTypeHealMult < 1f;
                targets.sort((a, b) -> Float.compare(key(a, unit, isSameType), key(b, unit, isSameType)));
            }
            boolean hasHealed = false;
            int len = limitTargets ? Math.min(targetCount, maxTargets) : targetCount;
            for(int i = 0; i < len; i++){
                Unit other = targets.get(i);
                if(other.damaged()){
                    float maxHealth = other.maxHealth;
                    float healMult = unit.type() == other.type() ? sameTypeHealMult : 1f;
                    other.heal((amount + healPercentMult * maxHealth) * healMult);
                    hasHealed = true;
                }
            }
            if(hasHealed){
                state.healTimer = 0f;
                // sound.at(unit, 1f + Mathf.range(0.1f), soundVolume): the pitch draws even headless
                Mathf.range(unit.world().rand(), 0.1f);
            }
        // increase how often this checks if there are damaged units but still below the healing threshold
        }else if(smartHeal && targetCount > 0){
            state.healTimer = reload >= (2f * smartInterval) ? reload - smartInterval : smartInterval;
        }else if(randDesync > 0){
            state.healTimer = Mathf.random(unit.world().rand(), randDesync) * reload;
        }else{
            state.healTimer = 0;
        }
    }

    private static float key(Unit other, Unit unit, boolean isSameType){
        return other.dst2(unit.x, unit.y) + (isSameType && other.type() == unit.type() ? 6400f : 0f);
    }

    /**
     * {@code healTimer}, {@code downTimer}, {@code healthMissing} and {@code healthChange}, which carry from one check to
     * the next; the other sums and flags are reset by each check before it reads them.
     */
    public static final class State extends UnitAbility{
        public float healTimer, downTimer, healthMissing, healthChange;

        @Override
        public UnitAbility copy(){
            State out = copyInto(new State());
            out.healTimer = healTimer;
            out.downTimer = downTimer;
            out.healthMissing = healthMissing;
            out.healthChange = healthChange;
            return out;
        }

        @Override
        public void hash(Hasher hasher){
            super.hash(hasher);
            hasher.f(healTimer).f(downTimer).f(healthMissing).f(healthChange);
        }

        @Override
        public void write(CheckpointOut out){
            super.write(out);
            out.f("healTimer", healTimer).f("downTimer", downTimer).f("healthMissing", healthMissing).f("healthChange", healthChange);
        }

        @Override
        public void read(CheckpointIn in){
            super.read(in);
            healTimer = in.f("healTimer");
            downTimer = in.f("downTimer");
            healthMissing = in.f("healthMissing");
            healthChange = in.f("healthChange");
        }
    }
}
