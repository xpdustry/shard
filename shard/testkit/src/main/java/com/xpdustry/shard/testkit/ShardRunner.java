package com.xpdustry.shard.testkit;

import com.xpdustry.shard.common.registry.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.content.epoch.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.world.*;
import com.xpdustry.shard.testkit.Action.*;
import com.xpdustry.shard.testkit.Projection.*;

import java.util.*;

/**
 * Runs scenarios on Shard {@link World}s, binding blocks through the kind registry.
 * <pre>{@code
 * ShardRunner shard = ShardRunner.of(content);           // built-in kinds, systems and registered adapters
 * ShardRunner toy = ShardRunner.of(content).kinds(KindRegistry.of(List.of(Pump.class))).name("shard (toy)");
 * }</pre>
 * Per kind, a {@link KindAdapter} (registered, or given to {@link #adapter}) names the kind's fields and applies
 * configs; {@link WorldAdapter}s provide cores and waves, and {@link RulesAdapter}, always asked first, rules. Actions
 * are applied directly between ticks, the way a map load or the editor would, with {@link Cause#SERVER}: items and
 * liquids go straight into the modules. An empty world plays with vanilla's {@code Vanilla.emptyWorld} rules: fresh
 * ones without game over. Bundled maps load with {@link ShardMaps} when the content is a pack epoch.
 */
public final class ShardRunner implements Runner{
    /** Applies a {@link Action.Custom} action on Shard. */
    @FunctionalInterface
    public interface Handler{
        void apply(World world, Map<String, Object> args, ShardNames names);
    }

    /** Makes the world of a {@link MapSpec.Bundled} map from the runner's content, kinds, systems and the scenario's seed. */
    @FunctionalInterface
    public interface MapLoader{
        World load(String map, WorldConfig template);
    }

    private final ContentEpoch content;
    private String name = "shard";
    private KindRegistry kinds = KindRegistry.builtIn();
    private Systems systems = Systems.builtIn();
    private final TreeMap<KindKey, KindAdapter<?>> adapters = new TreeMap<>();
    private final ArrayList<WorldAdapter> worlds = new ArrayList<>();
    private final TreeMap<String, Handler> handlers = new TreeMap<>();
    private MapLoader maps;

    private ShardRunner(ContentEpoch content){
        this.content = Objects.requireNonNull(content, "content");
        // Shard's paths are built inside the tick, with no path threads: nothing to wait for
        on(OracleRunner.WAIT_FOR_PATHS, (world, args, names) -> {});
        for(KindAdapter<?> adapter : Registries.instances(KindAdapter.class)){
            KindAdapter<?> previous = adapters.putIfAbsent(adapter.kind(), adapter);
            if(previous != null){
                throw new IllegalStateException("two KindAdapters are registered for kind " + adapter.kind() + ": "
                    + previous.getClass().getName() + " and " + adapter.getClass().getName());
            }
        }
        worlds.add(new RulesAdapter());
        worlds.addAll(Registries.instances(WorldAdapter.class));
        maps = content instanceof Epoch epoch ? ShardMaps.of(epoch) : null;
    }

    /** Built-in kinds and systems, every registered {@link KindAdapter} and {@link WorldAdapter}. */
    public static ShardRunner of(ContentEpoch content){
        return new ShardRunner(content);
    }

    /** The name in reports; give two Shard runners of one diff different names. */
    public ShardRunner name(String name){
        this.name = Objects.requireNonNull(name);
        return this;
    }

    public ShardRunner kinds(KindRegistry kinds){
        this.kinds = Objects.requireNonNull(kinds);
        return this;
    }

    public ShardRunner systems(Systems systems){
        this.systems = Objects.requireNonNull(systems);
        return this;
    }

    /** Uses {@code adapter} for its kind, instead of a registered one. */
    public ShardRunner adapter(KindAdapter<?> adapter){
        adapters.put(adapter.kind(), adapter);
        return this;
    }

    /** Asks {@code adapter} after the registered ones. */
    public ShardRunner world(WorldAdapter adapter){
        worlds.add(Objects.requireNonNull(adapter));
        return this;
    }

    /** Handles {@link Action.Custom} actions named {@code action}. */
    public ShardRunner on(String action, Handler handler){
        handlers.put(action, Objects.requireNonNull(handler));
        return this;
    }

    public ShardRunner maps(MapLoader maps){
        this.maps = maps;
        return this;
    }

    @Override
    public String name(){
        return name;
    }

    @Override
    public Session open(Scenario scenario){
        World world = switch(scenario.map()){
            case MapSpec.Empty(int width, int height) -> {
                var empty = new World(config(width, height, scenario.seed()));
                com.xpdustry.shard.sim.rules.Rules rules = RulesAdapter.rules(empty);
                if(rules != null) rules.canGameOver = false;
                yield empty;
            }
            case MapSpec.Bundled(String map) -> {
                if(maps == null) throw new UnsupportedOperationException("Shard can't load map '" + map + "' of content " + content.hash() + ": give ShardRunner.maps(...) a loader");
                yield maps.load(map, config(1, 1, scenario.seed()));
            }
        };
        return new ShardSession(world);
    }

    private WorldConfig config(int width, int height, long seed){
        return new WorldConfig(content, width, height, seed, systems, kinds, WorldConfig.DEFAULT_INBOX_CAPACITY, new MemoryJournal());
    }

    @SuppressWarnings("unchecked")
    private KindAdapter<Building> adapter(Building building){
        return (KindAdapter<Building>)adapters.get(building.world().kinds().key(building.block()));
    }

    private final class ShardSession implements Session{
        private final World world;
        private final ShardNames names;

        ShardSession(World world){
            this.world = world;
            this.names = new ShardNames(world);
        }

        @Override
        public long tick(){
            return world.ticks();
        }

        @Override
        public void step(){
            world.tick();
        }

        @Override
        public void apply(Action action){
            switch(action){
                case Place p -> {
                    Building building = world.buildings().create(names.block(p.block()), names.team(p.team()), p.x(), p.y(), p.rotation(), Cause.SERVER);
                    if(!(p.config() instanceof Config.None)) configure(building, p.config());
                }
                case Remove r -> world.buildings().remove(names.building(r.x(), r.y()), Cause.SERVER);
                case Configure c -> configure(names.building(c.x(), c.y()), c.config());
                case Action.Items i -> {
                    Building building = names.building(i.x(), i.y());
                    if(building.items == null) throw new IllegalStateException(building.block().name() + " has no item module");
                    building.items.items[names.item(i.item())] += i.amount();
                    building.items.total += i.amount();
                }
                case Liquid l -> {
                    Building building = names.building(l.x(), l.y());
                    if(building.liquids == null) throw new IllegalStateException(building.block().name() + " has no liquid module");
                    int id = names.liquid(l.liquid());
                    building.liquids.liquids[id] += l.amount();
                    building.liquids.current = id;
                }
                case CoreItems c -> {
                    int team = names.team(c.team()), item = names.item(c.item());
                    if(worlds.stream().noneMatch(w -> w.coreItems(world, team, item, c.amount()))){
                        throw new UnsupportedOperationException("no WorldAdapter handles core items on Shard yet (cores are WP23's)");
                    }
                }
                case Spawn s -> {
                    Unit unit = Unit.create(world, names.unit(s.unit()), names.team(s.team()));
                    unit.x = s.x();
                    unit.y = s.y();
                    unit.rotation = s.rotation();
                    unit.add();
                }
                case Action.Rules r -> r.set().forEach((rule, value) -> {
                    if(worlds.stream().noneMatch(w -> w.rule(world, rule, value))){
                        throw new UnsupportedOperationException("no WorldAdapter handles rule '" + rule + "': this world has no rules part");
                    }
                });
                case Custom c -> {
                    Handler handler = handlers.get(c.name());
                    if(handler == null) throw new UnsupportedOperationException("no handler for custom action '" + c.name() + "': ShardRunner.on(\"" + c.name() + "\", ...)");
                    handler.apply(world, c.args(), names);
                }
            }
        }

        private void configure(Building building, Config config){
            KindAdapter<Building> adapter = adapter(building);
            if(adapter == null){
                throw new UnsupportedOperationException(building.block().name() + " (kind " + building.world().kinds().key(building.block())
                    + ") takes no config: it has no KindAdapter (register one, or ShardRunner.adapter(...))");
            }
            adapter.configure(building, config, names);
        }

        @Override
        public Projection project(){
            var out = Projection.builder(name, world.ticks(), world.tiles().width(), world.tiles().height());
            world.buildings().each(building -> out.building(building(building)));
            for(int i = 0; i < world.units().size(); i++){
                Unit u = world.units().get(i);
                out.unit(new UnitState(u.id(), u.type().name(), Teams.name(u.team), u.x, u.y, u.rotation, u.health));
            }
            for(WorldAdapter adapter : worlds) adapter.project(world, out, names);
            return out.build();
        }

        private BuildingState building(Building b){
            Map<String, Integer> items = null;
            if(b.items != null){
                items = new LinkedHashMap<>();
                for(int id = 0; id < b.items.items.length; id++){
                    if(b.items.items[id] != 0) items.put(names.itemName(id), b.items.items[id]);
                }
            }
            Map<String, Float> liquids = null;
            if(b.liquids != null){
                liquids = new LinkedHashMap<>();
                for(int id = 0; id < b.liquids.liquids.length; id++){
                    if(b.liquids.liquids[id] != 0f) liquids.put(names.liquidName(id), b.liquids.liquids[id]);
                }
            }
            var fields = new Fields(names);
            KindAdapter<Building> adapter = adapter(b);
            if(adapter == null) fields.reflect(b);
            else adapter.fields(b, fields);
            return new BuildingState(names.x(b), names.y(b), b.block().name(), b.block().kind().name(), Teams.name(b.team),
                b.rotation, b.health, b.enabled, items, liquids, b.power == null ? null : new PowerState(b.power.status), fields.values());
        }

        @Override
        public void close(){
        }
    }
}
