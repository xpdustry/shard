package com.xpdustry.shard.server.intent;

import com.xpdustry.shard.common.math.*;
import com.xpdustry.shard.server.intent.action.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.rules.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.world.*;
import com.xpdustry.shard.wire.v160_7.Packets;

/**
 * The remote calls that move items between a player's unit and a building (InputHandler.java:505-543, 686-696), with
 * vanilla's checks: in reach ({@code itemTransferRange}), interactable, the action filter, and for deposits the rate
 * limit. The transfers are vanilla's {@code takeItems} and {@code transferItemTo} Calls ({@link ItemTransfers}), which
 * run here and go to every player before the forward of the client's call; the item flying is an effect (Tier E).
 */
public final class ItemIntents{
    /** {@code Vars.itemTransferRange}. */
    public static final float TRANSFER_RANGE = 220f;

    private ItemIntents(){
    }

    /** {@code build.interactable(team)}: the team's, or derelict. */
    static boolean interactable(Building build, int team){
        return build.team == team || build.team == VanillaIds.DERELICT;
    }

    /**
     * {@code requestItem(player, build, item, amount)}: take up to {@code amount} of an item from a building in reach
     * into the player's unit. Requests vanilla returns from early are still forwarded.
     */
    public record RequestItem(Policy policy, int build, int item, int amount) implements Guarded{
        @Override
        public Verdict admit(World world, IntentEnvelope envelope){
            Acting acting = Acting.of(world, envelope, policy);
            Verdict refused = Acting.refused(acting);
            if(refused != null) return refused;
            Player player = acting.player;
            Building building = acting.building(build);
            var forward = new Packets.RequestItem(player.id(), build, (short)item, amount);
            if(building == null || !interactable(building, player.team) || !Mathf.within(player.x, player.y, building.x(), building.y(), TRANSFER_RANGE)
                || player.dead() || amount <= 0){
                acting.forward(forward);
                return Verdict.rejected("nothing to take");
            }
            // readItem gives null, and vanilla's takeItems fails on it: no forward
            if(item < 0 || item >= world.content().items()) return Verdict.rejected("no item " + item);
            PlayerAction action = acting.action(ActionType.WITHDRAW_ITEM).tile(building.tileX(), building.tileY()).item(item, amount).build();
            Verdict denied = BlockIntents.canInteract(player, building) ? acting.denied(action) : Verdict.rejected("can't interact");
            if(denied != null) return denied;
            Unit unit = player.unit();
            ItemTransfers.takeItems(world, building, item, Math.min(unit.maxAccepted(item), amount), unit);
            acting.forward(forward);
            return Verdict.ADMITTED;
        }
    }

    /**
     * {@code transferInventory(player, build)}: put the unit's whole stack into a building in reach that takes
     * deposits, at most twice per two deposit cooldowns ({@code itemDepositRate}).
     */
    public record TransferInventory(Policy policy, int build) implements Guarded{
        @Override
        public Verdict admit(World world, IntentEnvelope envelope){
            Acting acting = Acting.of(world, envelope, policy);
            Verdict refused = Acting.refused(acting);
            if(refused != null) return refused;
            Player player = acting.player;
            Building building = acting.building(build);
            var forward = new Packets.TransferInventory(player.id(), build);
            if(building == null || !Mathf.within(player.x, player.y, building.x(), building.y(), TRANSFER_RANGE) || building.items == null
                || player.dead() || !building.allowDeposit()){
                acting.forward(forward);
                return Verdict.rejected("nowhere to deposit");
            }
            Unit unit = player.unit();
            if(unit.stackAmount <= 0) return Verdict.rejected("no items");
            if(!BlockIntents.canInteract(player, building)) return Verdict.rejected("can't interact");
            // the cap is double the cooldown with 2 deposits, so deposits that overlap in flight aren't refused
            long cooldown = (long)(building.rules().itemDepositCooldown * 1000 * 2);
            if(!acting.input.deposits.allow(acting.arrivalMillis(), cooldown, 2)) return Verdict.rejected("depositing too fast");
            PlayerAction action = acting.action(ActionType.DEPOSIT_ITEM).tile(building.tileX(), building.tileY())
                .item(unit.item(), unit.stackAmount).build();
            Verdict denied = acting.denied(action);
            if(denied != null) return denied;
            int item = unit.item();
            int accepted = building.acceptStack(item, unit.stackAmount, unit.team);
            ItemTransfers.transferItemTo(world, unit, item, accepted, unit.x, unit.y, building);
            acting.forward(forward);
            return Verdict.ADMITTED;
        }
    }

    /** {@code dropItem(player, angle)}: the unit drops its stack; only the drop effect, which isn't sent, shows it. */
    public record DropItem(float angle) implements Guarded{
        @Override
        public Verdict admit(World world, IntentEnvelope envelope){
            Acting acting = Acting.of(world, envelope, Policy.VANILLA);
            Verdict refused = Acting.refused(acting);
            if(refused != null) return refused;
            Unit unit = acting.player.unit();
            if(unit == null || unit.stackAmount <= 0) return Verdict.rejected("no item to drop");
            unit.clearItem();
            return Verdict.ADMITTED;
        }
    }
}
