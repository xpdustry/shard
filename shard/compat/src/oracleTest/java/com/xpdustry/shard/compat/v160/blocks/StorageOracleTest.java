package com.xpdustry.shard.compat.v160.blocks;

import com.xpdustry.shard.compat.v160.world.*;
import com.xpdustry.shard.content.epoch.*;
import com.xpdustry.shard.oracle.*;
import com.xpdustry.shard.sim.blocks.storage.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.rules.WorldRules;
import com.xpdustry.shard.sim.world.*;
import arc.math.*;
import arc.util.io.*;
import mindustry.game.*;
import mindustry.world.blocks.storage.*;
import org.junit.jupiter.api.*;

import java.io.*;
import java.util.*;
import java.util.function.*;

import static mindustry.Vars.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Gate: cores and storage take, burn and share items as vanilla's do. Each fixture is built in a vanilla world
 * ({@link Sim}) and a Shard one, with item sources feeding the cores and storage, then run {@link #FRAMES} frames with
 * the same edits (rules, placing and removing cores and storage) on the same frames, and compared after every frame:
 * every building's items, each core's capacity and burn flag, each storage's linked core, which buildings hold the
 * same item module, and the generator (burning an item draws an effect chance). At the end every building's save chunk
 * is compared.
 */
public class StorageOracleTest{
    static final int FRAMES = 800;
    static final Epoch EPOCH = Epoch.vanilla();
    static final BuildingCodecs CODECS = BuildingCodecs.builtIn();
    static final KindRegistry KINDS = KindRegistry.builtIn().withPassthrough();

    /**
     * A shard core with a container and a vault linked to it and a crux container beside it, all fed by sources, the
     * core nearly full. Incineration is turned off and on again; the core is removed (its storage takes its share back)
     * and placed again (taking nothing back); a second core joins the team; the container goes.
     */
    @Test
    void linking(){
        var run = new Run(24, 18);
        run.place("core-shard", 6, 6, Team.sharded);
        run.place("container", 8, 6, Team.sharded);
        run.place("vault", 6, 9, Team.sharded);
        run.place("container", 3, 9, Team.crux);
        run.place("item-source", 4, 6, Team.sharded);
        run.place("item-source", 10, 6, Team.sharded);
        run.place("item-source", 6, 11, Team.sharded);
        run.configure(4, 6, "copper");
        run.configure(10, 6, "lead");
        run.configure(6, 11, "sand");
        run.set(6, 6, "copper", 5250);
        run.at(200, () -> run.rules(r -> r.coreIncinerates = false, r -> r.coreIncinerates = false));
        run.at(300, () -> run.rules(r -> r.coreIncinerates = true, r -> r.coreIncinerates = true));
        run.at(400, () -> run.remove(6, 6));
        run.at(500, () -> run.place("core-shard", 6, 6, Team.sharded));
        run.at(520, () -> run.set(6, 6, "lead", 2000));
        run.at(600, () -> run.place("core-foundation", 16, 14, Team.sharded));
        run.at(700, () -> run.remove(8, 6));
        run.run();
    }

    /**
     * An Erekir core burns the sand it can't build with and keeps the beryllium; a reinforced container beside it, which
     * doesn't merge, fills up on its own.
     */
    @Test
    void erekir(){
        var run = new Run(16, 16);
        run.place("core-bastion", 6, 6, Team.sharded);
        run.place("reinforced-container", 6, 9, Team.sharded);
        run.place("item-source", 4, 6, Team.sharded);
        run.place("item-source", 9, 6, Team.sharded);
        run.place("item-source", 6, 11, Team.sharded);
        run.configure(4, 6, "sand");
        run.configure(9, 6, "beryllium");
        run.configure(6, 11, "tungsten");
        run.at(300, () -> run.set(6, 6, "beryllium", 1990));
        run.run();
    }

    /** One fixture in both worlds, built and edited in step. */
    static final class Run{
        final Sim sim;
        final World shard;
        final Map<Integer, List<Runnable>> edits = new TreeMap<>();

        Run(int width, int height){
            sim = Sim.empty(width, height);
            state.rules.waves = false;
            shard = new World(WorldConfig.of(EPOCH, width, height, 1).withKinds(KINDS));
            shard.buildings().beginLoad();
            for(int pos = 0; pos < shard.tiles().size(); pos++) shard.tiles().floor(pos, EPOCH.block("stone").id());
            shard.buildings().endLoad(false);
            shard.part(WorldRules.class).rules.waves = false;
        }

        /** As a map editor places it: {@code tile.setBlock}, rotation 0. */
        void place(String block, int x, int y, Team team){
            sim.place(content.block(block), x, y, 0, team);
            shard.buildings().setBlock(x, y, EPOCH.block(block), team.id, 0, Cause.SERVER);
        }

        void remove(int x, int y){
            world.tile(x, y).remove();
            shard.buildings().remove(shard.buildings().at(x, y), Cause.SERVER);
        }

        void configure(int x, int y, String item){
            sim.build(x, y).configureAny(content.item(item));
            Vanilla.runPosted();
            shard.buildings().at(x, y).configureAny(ContentRef.item(EPOCH.item(item).id()));
        }

        void set(int x, int y, String item, int amount){
            sim.build(x, y).items.set(content.item(item), amount);
            shard.buildings().at(x, y).items.set(EPOCH.item(item).id(), amount);
        }

        void rules(Consumer<mindustry.game.Rules> vanilla, Consumer<com.xpdustry.shard.sim.rules.Rules> shard){
            vanilla.accept(state.rules);
            shard.accept(this.shard.part(WorldRules.class).rules);
        }

        void at(int when, Runnable edit){
            edits.computeIfAbsent(when, k -> new ArrayList<>()).add(edit);
        }

        void run(){
            shard.rand().setState(Mathf.rand.seed0, Mathf.rand.seed1);
            assertEquals(vanillaStates(), shardStates(), "as placed");
            for(int frame = 1; frame <= FRAMES; frame++){
                for(Runnable edit : edits.getOrDefault(frame, List.of())) edit.run();
                sim.step(1);
                shard.tick();
                assertEquals(vanillaStates(), shardStates(), "after frame " + frame);
            }
            var current = ContentHeader.of(EPOCH).mapping(EPOCH);
            for(mindustry.gen.Building expected : buildings()){
                Building actual = shard.buildings().at(expected.tile.x, expected.tile.y);
                assertEquals(hex(chunk(expected)), hex(CODECS.chunk(actual, EPOCH, current)),
                    expected.block.name + " at (" + expected.tile.x + ", " + expected.tile.y + "): save chunk");
            }
        }

        List<mindustry.gen.Building> buildings(){
            var out = new ArrayList<mindustry.gen.Building>();
            for(var tile : world.tiles) if(tile.build != null && tile.isCenter()) out.add(tile.build);
            return out;
        }

        List<String> vanillaStates(){
            var out = new ArrayList<String>();
            List<mindustry.gen.Building> builds = buildings();
            for(mindustry.gen.Building build : builds){
                var line = new StringBuilder(build.block.name + " (" + build.tile.x + ", " + build.tile.y + ") team " + build.team.id
                    + " items " + items(id -> build.items == null ? 0 : build.items.get(id)));
                int shares = -1;
                for(int i = 0; i < builds.size(); i++) if(build.items != null && builds.get(i).items == build.items){ shares = i; break; }
                line.append(" module ").append(shares);
                switch(build){
                    case CoreBlock.CoreBuild core -> line.append(" capacity ").append(core.storageCapacity).append(" noEffect ").append(core.noEffect);
                    case StorageBlock.StorageBuild storage -> line.append(" linked ").append(storage.linkedCore == null ? -1 : storage.linkedCore.pos());
                    default -> {}
                }
                out.add(line.toString());
            }
            out.add("rand " + Mathf.rand.seed0 + " " + Mathf.rand.seed1);
            return out;
        }

        List<String> shardStates(){
            var out = new ArrayList<String>();
            var tiles = shard.tiles();
            var builds = new ArrayList<Building>();
            for(mindustry.gen.Building expected : buildings()) builds.add(shard.buildings().at(expected.tile.x, expected.tile.y));
            for(Building build : builds){
                if(build == null){
                    out.add("missing");
                    continue;
                }
                var line = new StringBuilder(build.block().name() + " (" + tiles.x(build.tile()) + ", " + tiles.y(build.tile()) + ") team " + build.team
                    + " items " + items(id -> build.items == null ? 0 : build.items.get(id)));
                int shares = -1;
                for(int i = 0; i < builds.size(); i++) if(build.items != null && builds.get(i) != null && builds.get(i).items == build.items){ shares = i; break; }
                line.append(" module ").append(shares);
                switch(build){
                    case Core core -> line.append(" capacity ").append(core.storageCapacity).append(" noEffect ").append(core.noEffect);
                    case Storage storage -> line.append(" linked ").append(storage.linkedCore == null ? -1 : storage.linkedCore.pos());
                    default -> {}
                }
                out.add(line.toString());
            }
            out.add("rand " + shard.rand().getState(0) + " " + shard.rand().getState(1));
            return out;
        }
    }

    static String items(IntUnaryOperator amount){
        var out = new StringJoiner(",", "[", "]");
        for(int id = 0; id < EPOCH.items(); id++) if(amount.applyAsInt(id) != 0) out.add(EPOCH.item(id).name() + "=" + amount.applyAsInt(id));
        return out.toString();
    }

    /** What {@code writeMap} puts in a building's chunk: the revision, then {@code writeAll}. */
    static byte[] chunk(mindustry.gen.Building building){
        var bytes = new ByteArrayOutputStream();
        var out = new Writes(new DataOutputStream(bytes));
        out.b(building.version());
        building.writeAll(out);
        return bytes.toByteArray();
    }

    static String hex(byte[] bytes){
        return HexFormat.of().formatHex(bytes);
    }
}
