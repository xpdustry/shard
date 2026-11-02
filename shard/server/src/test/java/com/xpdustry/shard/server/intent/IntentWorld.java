package com.xpdustry.shard.server.intent;

import com.xpdustry.shard.content.*;
import com.xpdustry.shard.content.epoch.*;
import com.xpdustry.shard.server.host.*;
import com.xpdustry.shard.server.intent.action.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.rules.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.world.*;
import com.xpdustry.shard.sim.world.Observer;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A hosted world of stone on vanilla content for intent tests: sessions join and confirm through the host's own
 * commands, get units, and send intents with recorded arrival times; the test reads each intent's verdict and the
 * {@link IntentEvent}s its admission published.
 */
final class IntentWorld{
    static final Epoch VANILLA = Epoch.vanilla();

    final World world;
    final Observer events;
    private final TreeMap<Long, Long> sequences = new TreeMap<>();

    IntentWorld(){
        this(48, 48);
    }

    IntentWorld(int width, int height){
        world = new World(config(width, height));
        world.buildings().beginLoad();
        for(int pos = 0; pos < world.tiles().size(); pos++) world.tiles().floor(pos, VANILLA.block("stone").id());
        world.buildings().endLoad(false);
        rules().disableUnitCap = true;
        events = world.observe(4096, List.of(IntentEvent.class), null);
    }

    /** A hosted world's config, as the host makes it: its systems, parts and kinds, which a restore and a replay need too. */
    static WorldConfig config(int width, int height){
        WorldConfig template = HostedSystems.template(VANILLA, 5, 4096);
        return new WorldConfig(template.content(), width, height, 5, template.systems(), template.kinds(), template.inboxCapacity(),
            template.journal());
    }

    /** A fresh config like this world's. */
    WorldConfig config(){
        return config(world.config().width(), world.config().height());
    }

    Rules rules(){
        return world.part(WorldRules.class).rules;
    }

    /** A session that joined and confirmed, with no unit. */
    Player join(long session, String name, boolean admin){
        assertTrue(world.submit(new HostInputs.Join(session, 1, name, -1, admin), Cause.SERVER));
        world.tick();
        assertEquals(Verdict.ADMITTED, admit(session, new HostInputs.Confirm(1), 0));
        events.drain();
        return Roster.of(world).player(session);
    }

    /** A session's player controlling a new unit of {@code type} at (x, y). */
    Player joinWith(long session, String name, String type, float x, float y){
        Player player = join(session, name, false);
        give(player, type, x, y);
        return player;
    }

    Unit give(Player player, String type, float x, float y){
        Unit unit = Unit.spawn(world, VANILLA.unit(type), player.team, x, y, 0f);
        player.unit(unit);
        world.tick();
        events.drain();
        return unit;
    }

    Building place(String block, int x, int y, int team, int rotation){
        return world.buildings().setBlock(x, y, VANILLA.block(block), team, rotation, Cause.SERVER);
    }

    /** Offers {@code intent} as {@code session}'s next packet, arrived at {@code arrivalMillis}, and runs one tick. */
    Verdict admit(long session, Intent intent, long arrivalMillis){
        offer(session, intent, arrivalMillis);
        int before = world.log().size();
        world.tick();
        assertEquals(before + 1, world.log().size(), "one entry per admitted input");
        return world.log().get(world.log().size() - 1).verdict();
    }

    /** Offers {@code intent} as {@code session}'s next packet without a tick, so several are admitted in the next one. */
    void offer(long session, Intent intent, long arrivalMillis){
        long sequence = sequences.merge(session, 1L, Long::sum);
        assertTrue(world.inbox().offer(new IntentEnvelope(session, 1, sequence, arrivalMillis * 1_000_000L, intent)));
    }

    void rule(String name, ActionType... types){
        assertEquals(Verdict.ADMITTED, apply(new ActionRules.Put(ActionRules.Rule.deny(name, "[scarlet]Not here.", types))));
    }

    Verdict apply(Command command){
        assertTrue(world.submit(command, new Cause.Admin("test")));
        int before = world.log().size();
        world.tick();
        return world.log().get(before).verdict();
    }

    /** The events the ticks since the last call published. */
    List<IntentEvent> drain(){
        var out = new ArrayList<IntentEvent>();
        for(WorldEvent event : events.drain()) out.add((IntentEvent)event);
        return out;
    }

    /** The Calls of {@code events}, of {@code type}. */
    static <T> List<T> calls(List<IntentEvent> events, Class<T> type){
        var out = new ArrayList<T>();
        for(IntentEvent event : events){
            Object packet = switch(event){
                case IntentCall.To to -> to.packet();
                case IntentCall.All all -> all.packet();
                case IntentCall.AllExcept all -> all.packet();
                case IntentCall.Team team -> team.packet();
                default -> null;
            };
            if(type.isInstance(packet)) out.add(type.cast(packet));
        }
        return out;
    }

    static ItemDef item(String name){
        return VANILLA.item(name);
    }
}
