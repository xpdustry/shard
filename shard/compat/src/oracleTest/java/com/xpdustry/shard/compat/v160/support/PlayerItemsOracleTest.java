package com.xpdustry.shard.compat.v160.support;

import com.xpdustry.shard.content.*;
import com.xpdustry.shard.sim.rules.*;
import com.xpdustry.shard.sim.tile.*;
import com.xpdustry.shard.sim.unit.*;
import org.junit.jupiter.api.*;

import static mindustry.Vars.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * L1 for a player's items (WP45): a client on Ground Zero, hosted for survival by vanilla and Shard, compared frame by
 * frame ({@link SupportRun}), Calls included, driven only by what a client sends: {@code clientSnapshot}s naming the
 * tile it mines, and the item Calls through vanilla's generated handlers and W2.14's intents. In order:
 * <ol>
 *   <li>mining by the core: each item goes straight into the core ({@code transferItemTo} per item);</li>
 *   <li>the core's copper full: each item flies into the unit's stack instead, landing a flight later;</li>
 *   <li>{@code transferInventory} into a container placed beside the player;</li>
 *   <li>more mining, then {@code dropItem};</li>
 *   <li>{@code requestItem} out of the container;</li>
 *   <li>with room in the core again, {@code transferInventory} into it.</li>
 * </ol>
 * A full core turns items away only with {@code coreIncinerates} off, which both sides set. Ground Zero's map area is a
 * basin within 30 tiles of the core, so a player can't mine out of its {@code mineTransferRange} (27.5 tiles) there.
 */
class PlayerItemsOracleTest{
    @Test
    void aPlayerMinesDropsAndTransfersThroughItsClient(){
        var run = new SupportRun("serpulo/groundZero");
        run.withPlayer = true;
        state.rules.coreIncinerates = false;
        run.shard.part(WorldRules.class).rules.coreIncinerates = false;
        int copper = SupportRun.EPOCH.item("copper").id();
        int[] near = ore(run, copper, 0f, 6f);
        assertNotNull(near, "copper ore by the core");
        int[] box = room(run, near[0], near[1]);
        assertNotNull(box, "room for a container by the ore");
        int cx = run.coreX, cy = run.coreY;
        int full = run.ours(cx, cy).getMaximumAccepted(copper);

        // stand next to the ore by the core, and mine it
        run.until(400, () -> run.shardPlayer() != null && !run.shardPlayer().dead() && run.vanillaPlayer != null && !run.vanillaPlayer.dead());
        int start = run.frame + 1;
        run.at(start, () -> {
            run.teleportPlayer(near[0] * 8f + 8f, near[1] * 8f);
            run.mining = near;
        });
        run.run(360);
        int byCore = run.calls.getOrDefault("TransferItemTo", 0);
        assertTrue(byCore >= 4, "mined items went straight into the core: " + run.calls + "; " + state(run));

        // the core full: the stack fills
        run.at(run.frame + 1, () -> {
            run.items("copper", full);
            run.place("container", box[0], box[1], 0);
        });
        run.run(400);
        Unit unit = run.shardPlayer().unit();
        int carried = unit.stackAmount;
        assertTrue(carried >= 5, "the stack filled by the full core: " + state(run));
        assertEquals(byCore, run.calls.getOrDefault("TransferItemTo", 0), "nothing went into the full core");

        run.at(run.frame + 1, () -> run.transferInventory(box[0], box[1]));
        run.run(200);
        assertTrue(run.ours(box[0], box[1]).items.get(copper) >= carried, "the stack went into the container");

        // mine a little more, stop, and drop what is carried once the last item has landed
        int stop = run.frame + 120;
        run.at(stop, () -> run.mining = null);
        run.at(stop + 30, () -> run.dropItem(45f));
        run.at(stop + 60, () -> run.requestItem(box[0], box[1], "copper", 7));
        run.run(200);
        assertEquals(7, run.shardPlayer().unit().stackAmount, "the player took 7 back");

        run.at(run.frame + 1, () -> run.items("copper", full - 100));
        run.at(run.frame + 6, () -> run.transferInventory(cx, cy));
        run.run(60);
        assertEquals(0, run.shardPlayer().unit().stackAmount, "the stack went into the core");
        System.out.println("a player's items on Ground Zero: " + run.frame + " frames, ore " + near[0] + "," + near[1] + ", container " + box[0]
            + "," + box[1] + ", carried " + carried + "; Calls " + run.calls);
        assertTrue(run.calls.getOrDefault("TransferInventory", 0) >= 2, "both deposits were forwarded: " + run.calls);
        assertEquals(1, run.calls.getOrDefault("TakeItems", 0), "one takeItems: " + run.calls);
        assertEquals(1, run.calls.getOrDefault("RequestItem", 0), "one requestItem forward: " + run.calls);
        assertEquals(full - 100 + 7, world.build(cx, cy).items.get(content.item("copper")), "the core took the 7");
    }

    /**
     * Well inside the rules' map area (Ground Zero limits it): units outside the area are pushed back in
     * ({@code UnitComp.update}).
     */
    static boolean inArea(SupportRun run, int x, int y){
        var rules = run.rules;
        if(!rules.limitMapArea) return x > 4 && y > 4 && x < run.shard.tiles().width() - 6 && y < run.shard.tiles().height() - 6;
        return x >= rules.limitX + 4 && y >= rules.limitY + 4 && x < rules.limitX + rules.limitWidth - 6 && y < rules.limitY + rules.limitHeight - 6;
    }

    /** The player's unit, as Shard has it. */
    static String state(SupportRun run){
        Unit unit = run.shardPlayer().unit();
        return unit == null ? "no unit" : unit + " at " + unit.x / 8f + "," + unit.y / 8f + " stack " + unit.stackAmount + " mine " + unit.mineTile + " timer "
            + unit.mineTimer + ", core " + run.coreX + "," + run.coreY;
    }

    /** The {@code item} ore tile with no block nearest the core between {@code min} and {@code max} tiles from it, or null. */
    static int[] ore(SupportRun run, int item, float min, float max){
        TileGrid tiles = run.shard.tiles();
        int[] best = null;
        float bestDst = Float.MAX_VALUE;
        for(int pos = 0; pos < tiles.size(); pos++){
            if(tiles.block(pos) != 0 || tiles.drop(pos) != item) continue;
            int x = tiles.x(pos), y = tiles.y(pos);
            float dst = (float)Math.hypot(x - run.coreX, y - run.coreY);
            if(dst < min || dst > max || !inArea(run, x, y)) continue;
            if(dst < bestDst){
                best = new int[]{x, y};
                bestDst = dst;
            }
        }
        return best;
    }

    /**
     * The container's origin nearest (x, y) but 3 to 8 tiles from it, clear of the core: a 2x2 of open floor (no block,
     * no deep water) in the map area; or null.
     */
    static int[] room(SupportRun run, int x, int y){
        TileGrid tiles = run.shard.tiles();
        int[] best = null;
        float bestDst = Float.MAX_VALUE;
        for(int pos = 0; pos < tiles.size(); pos++){
            int bx = tiles.x(pos), by = tiles.y(pos);
            float dst = (float)Math.hypot(bx - x, by - y);
            if(dst < 3 || dst > 8 || dst >= bestDst || !inArea(run, bx, by) || Math.hypot(bx - run.coreX, by - run.coreY) < 4) continue;
            boolean open = true;
            for(int dx = 0; dx < 2; dx++){
                for(int dy = 0; dy < 2; dy++){
                    int tile = tiles.pos(bx + dx, by + dy);
                    open &= tiles.block(tile) == 0 && !(SupportRun.EPOCH.block(tiles.floor(tile)) instanceof FloorDef floor && floor.isDeep());
                }
            }
            if(open){
                best = new int[]{bx, by};
                bestDst = dst;
            }
        }
        return best;
    }
}
