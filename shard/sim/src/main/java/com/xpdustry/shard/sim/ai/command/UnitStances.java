package com.xpdustry.shard.sim.ai.command;

import com.xpdustry.shard.common.struct.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.sim.unit.*;

import java.util.*;

/**
 * Vanilla's {@code UnitStance} and {@code ItemUnitStance} (UnitStance.java:95-112, ItemUnitStance.java) over one
 * content's stance ids ({@link ContentEpoch#unitStances()}): the named stances' ids, which are toggles, which stances
 * and commands each is incompatible with, and a type's stance list ({@code getUnitStances}).
 *
 * <p>Incompatibilities, as {@code init()} turns vanilla's lists into bits: {@code mineauto} and every item stance
 * exclude each other (item stances don't exclude one another: each copies {@code mineAuto.incompatibleStances} while
 * that list is still empty); {@code patrol} is incompatible with {@code repair}, {@code assist} and {@code rebuild};
 * {@code boost} with those and {@code enterPayload}.
 *
 * <p>A table is immutable; {@link #of} keeps the last one made, so per-tick lookups cost a field read.
 */
public final class UnitStances{
    private static volatile UnitStances last;

    public final ContentEpoch content;
    /** The named stances' ids, or -1 where the content lacks one. */
    public final int stop, holdFire, pursueTarget, patrol, ram, boost, holdPosition, mineAuto;
    /** The stance count. */
    public final int size;
    /** By stance id: the item an item stance mines, or -1. */
    private final int[] itemOf;
    /** By item id: its stance's id, or -1. */
    private final int[] stanceOf;
    /** By stance id: the stances it excludes ({@code incompatibleStanceBits}). */
    private final Bits[] incompatible;
    private final boolean[] toggle;

    private UnitStances(ContentEpoch content){
        this.content = content;
        List<String> names = content.unitStances();
        size = names.size();
        stop = names.indexOf("stop");
        holdFire = names.indexOf("holdfire");
        pursueTarget = names.indexOf("pursuetarget");
        patrol = names.indexOf("patrol");
        ram = names.indexOf("ram");
        boost = names.indexOf("boost");
        holdPosition = names.indexOf("holdposition");
        mineAuto = names.indexOf("mineauto");
        itemOf = new int[size];
        stanceOf = new int[content.items()];
        Arrays.fill(itemOf, -1);
        Arrays.fill(stanceOf, -1);
        incompatible = new Bits[size];
        toggle = new boolean[size];
        for(int i = 0; i < size; i++){
            incompatible[i] = new Bits(size);
            String name = names.get(i);
            // stop and mineauto aren't toggles (UnitStance.java:96, 103)
            toggle[i] = i != stop && i != mineAuto;
            if(name.startsWith("item-")){
                ItemDef item = content.item(name.substring(5));
                if(item != null){
                    itemOf[i] = item.id();
                    if(item.id() < stanceOf.length) stanceOf[item.id()] = i;
                }
            }
        }
        for(int i = 0; i < size; i++){
            if(itemOf[i] == -1 || mineAuto == -1) continue;
            incompatible[i].set(mineAuto);
            incompatible[mineAuto].set(i);
        }
    }

    /** The table of {@code content}. */
    public static UnitStances of(ContentEpoch content){
        UnitStances table = last;
        if(table == null || table.content != content) last = table = new UnitStances(content);
        return table;
    }

    /** Whether {@code id} names a stance. */
    public boolean valid(int id){
        return id >= 0 && id < size;
    }

    /** {@code toggle}: false for {@code stop} and {@code mineauto}, which pressing always turns on. */
    public boolean toggles(int stance){
        return valid(stance) && toggle[stance];
    }

    /** {@code ItemUnitStance.item}'s id, or -1 for a stance that isn't an item's. */
    public int itemOf(int stance){
        return valid(stance) ? itemOf[stance] : -1;
    }

    /** {@code ItemUnitStance.getByItem(item)}'s id, or -1. */
    public int itemStance(int item){
        return item >= 0 && item < stanceOf.length ? stanceOf[item] : -1;
    }

    /** {@code incompatibleStanceBits}. */
    public Bits incompatible(int stance){
        return incompatible[stance];
    }

    /** {@code stance.isCompatible(command)}: false for {@code patrol} and {@code boost} under the commands they exclude. */
    public boolean compatible(int stance, UnitCommand command){
        if(command == null) return true;
        if(stance == patrol) return command != UnitCommand.REPAIR && command != UnitCommand.ASSIST && command != UnitCommand.REBUILD;
        if(stance == boost){
            return command != UnitCommand.REBUILD && command != UnitCommand.REPAIR && command != UnitCommand.ASSIST
                && command != UnitCommand.ENTER_PAYLOAD;
        }
        return true;
    }

    /**
     * {@code type.getUnitStances(unit, out)}: under {@code mine}, {@code mineauto} and the stances of the items the unit
     * can mine and the map has where it mines; otherwise the type's stances its command allows; then the command's
     * extra stances. Units without a command AI have none.
     */
    public void stancesOf(Unit unit, UnitCommand current, IntSeq out){
        if(current == UnitCommand.MINE){
            out.add(mineAuto);
            OreIndex ores = OreIndex.of(unit.world());
            UnitDef type = unit.type();
            for(int item = 0; item < stanceOf.length; item++){
                int stance = stanceOf[item];
                if(stance == -1 || !Miner.canMine(unit, item) || ores == null) continue;
                if((type.mineFloor() && ores.hasOre(item)) || (type.mineWalls() && ores.hasWallOre(item))) out.add(stance);
            }
        }else{
            List<String> names = content.unitStances(), own = unit.params().stances();
            for(int i = 0; i < own.size(); i++){
                int stance = names.indexOf(own.get(i));
                if(stance != -1 && compatible(stance, current)) out.add(stance);
            }
        }
        if(current != null && current.holdPosition() && holdPosition != -1) out.add(holdPosition);
    }

    /** {@code type.allowStance(unit, stance)}: {@code stop} always; otherwise one of {@link #stancesOf}. */
    public boolean allowed(Unit unit, UnitCommand current, int stance){
        if(stance == stop) return true;
        var out = new IntSeq();
        stancesOf(unit, current, out);
        return out.contains(stance);
    }
}
