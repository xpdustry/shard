package com.xpdustry.shard.sim.build;

import com.xpdustry.shard.common.geom.Point2;
import com.xpdustry.shard.oracle.*;
import com.xpdustry.shard.sim.team.*;
import com.xpdustry.shard.sim.tile.*;
import com.xpdustry.shard.sim.world.*;
import mindustry.content.*;
import mindustry.gen.*;
import mindustry.input.*;
import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Review 40: {@link RebuildPlans#delete} indexes the positions where vanilla's {@code InputHandler.deletePlans} checks
 * each plan against every one. On 500 seeded cases of up to 40 plans and 30 positions, crowded onto a few tiles so
 * plans share tiles and positions repeat or match nothing, the plans left, their order, the removed flags and the
 * count equal vanilla's exactly.
 */
class DeletePlansOracleTest{
    @BeforeAll
    static void boot(){
        Vanilla.boot();
    }

    @Test
    void theRemainingPlansAreVanillas(){
        Vanilla.reset();
        Vanilla.emptyWorld(32, 32);
        Player player = Player.create();
        player.team(mindustry.game.Team.sharded);
        var vanilla = mindustry.game.Team.sharded.data().plans;
        World world = VanillaWorlds.world(32, 32, 3);
        List<BlockPlan> shard = Teams.of(world).get(com.xpdustry.shard.sim.team.Team.SHARDED).plans();
        int conveyor = VanillaWorlds.VANILLA.block("conveyor").id();
        var random = new SplittableRandom(40);
        for(int round = 0; round < 500; round++){
            vanilla.clear();
            shard.clear();
            var vanillaPlans = new ArrayList<mindustry.game.Teams.BlockPlan>();
            var shardPlans = new ArrayList<BlockPlan>();
            for(int i = random.nextInt(41); i > 0; i--){
                int x = random.nextInt(6), y = random.nextInt(6);
                var plan = new mindustry.game.Teams.BlockPlan(x, y, (short)0, Blocks.conveyor, null);
                vanilla.addLast(plan);
                vanillaPlans.add(plan);
                var ours = new BlockPlan(x, y, 0, conveyor, null);
                shard.add(ours);
                shardPlans.add(ours);
            }
            int[] positions = new int[random.nextInt(31)];
            for(int i = 0; i < positions.length; i++){
                // mostly tiles near the plans, some far off or negative, which nothing matches
                positions[i] = random.nextInt(8) == 0 ? Point2.pack(random.nextInt(-40, 40), random.nextInt(-40, 40))
                    : Point2.pack(random.nextInt(7), random.nextInt(7));
            }
            int before = vanilla.size;
            InputHandler.deletePlans(player, positions);
            int removed = RebuildPlans.delete(world, com.xpdustry.shard.sim.team.Team.SHARDED, positions);

            String at = "round " + round + ", positions " + Arrays.toString(positions);
            assertEquals(before - vanilla.size, removed, at);
            var expected = new ArrayList<String>();
            for(var plan : vanilla) expected.add(plan.x + "," + plan.y + "#" + vanillaPlans.indexOf(plan));
            var actual = new ArrayList<String>();
            for(BlockPlan plan : shard) actual.add(plan.x + "," + plan.y + "#" + shardPlans.indexOf(plan));
            assertEquals(expected, actual, at);
            for(int i = 0; i < vanillaPlans.size(); i++) assertEquals(vanillaPlans.get(i).removed, shardPlans.get(i).removed, at + ", plan " + i);
        }
    }
}
