package com.xpdustry.shard.sim.persist;

import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.world.*;
import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The W1.4 gaps a replay bundle needs: inputs that hold world objects are stored by identity and replay into a
 * restored world; inputs waiting in the inbox at a checkpoint come back with their arrival numbers, and numbering
 * goes on where it was.
 */
class InputLogTest{
    /** An admin command that holds world objects: it damages a unit and a building if both are still there. */
    public record Hit(Unit unit, Building building, float damage) implements Command.Admin{
        @Override
        public Verdict apply(World world, Cause cause){
            if(unit == null || !unit.isAdded() || building == null || !building.isValid()) return Verdict.rejected("gone");
            unit.health -= damage;
            building.health -= damage;
            return Verdict.ADMITTED;
        }
    }

    /** A {@link Hit} on a unit and a building picked by {@code pick}, outside the world's random; null if either is missing. */
    static Hit hit(World world, Random pick){
        if(world.units().size() == 0) return null;
        Unit unit = world.units().get(pick.nextInt(world.units().size()));
        int size = world.tiles().size(), from = pick.nextInt(size);
        for(int i = 0; i < size; i++){
            Building building = world.buildings().at((from + i) % size);
            if(building != null) return new Hit(unit, building, 0.5f);
        }
        return null;
    }

    /** {@code world} run on until it has a unit and a building to hit. */
    static World hittable(World world){
        for(int i = 0; i < 600 && hit(world, new Random(1)) == null; i++){
            Fixtures.feed(world, world.ticks());
            world.tick();
        }
        assertNotNull(hit(world, new Random(1)), "the fixture has units and buildings");
        return world;
    }

    static void feed(World world, Random pick){
        Fixtures.feed(world, world.ticks());
        if(world.ticks() % 7 == 3){
            Hit hit = hit(world, pick);
            if(hit != null) world.submit(hit, new Cause.Admin("tester"));
        }
    }

    @Test
    void inputsHoldingWorldObjectsReplayIntoARestoredWorld(){
        World live = hittable(CheckpointTest.run(Fixtures.churning(31), 200));
        var pick = new Random(7);
        feed(live, pick);
        Hit waiting = hit(live, pick);
        assertNotNull(waiting, "still a unit and a building after a tick");
        live.submit(waiting, Cause.SERVER);
        long nextArrival = live.inbox().nextArrival();
        assertTrue(live.inbox().size() >= 1);

        byte[] checkpoint = CheckpointTest.file(live);
        long start = live.ticks(), position = live.log().position();
        var writer = new InputLog.Writer(start);
        var hashes = new TreeMap<Long, WorldHash>();
        int hits = 0;
        for(int i = 0; i < 400; i++){
            if(i > 0) feed(live, pick);
            long logged = live.log().position();
            live.tick();
            for(CommandLog.Entry entry : live.log().since(logged)){
                writer.add(entry);
                if(entry.input() instanceof Submission submission && submission.command() instanceof Hit && entry.verdict().admitted()) hits++;
            }
            hashes.put(live.ticks(), live.hash());
        }
        assertTrue(hits > 20, "hits were admitted: " + hits);
        byte[] file = writer.encode(live.content().hash(), Checkpoint.systemsId(live.config()), new TreeMap<>(), Compression.FAST);

        World restored = Checkpoint.decode(checkpoint).restore(Fixtures.config(31));
        assertEquals(nextArrival, restored.inbox().nextArrival(), "numbering goes on where it was");
        List<Inbox.Arrival> pending = restored.inbox().snapshot().pending();
        var restoredHit = (Hit)((Submission)pending.getLast().input()).command();
        assertEquals(waiting.unit().id(), restoredHit.unit().id());
        assertSame(restored, restoredHit.unit().world(), "the waiting input holds the restored world's unit");
        assertSame(restored.buildings().at(waiting.building().tile()), restoredHit.building());

        // a replay offers the logged inputs instead of the waiting ones, under their recorded numbers
        restored.inbox().forget();
        InputLog.Reader reader = InputLog.read(file, restored.content());
        assertEquals(writer.size(), reader.size());
        long logStart = restored.log().position();
        assertEquals(position, logStart);
        var replayed = new ArrayList<CommandLog.Entry>();
        while(restored.ticks() < start + 400){
            for(CommandLog.Entry entry : reader.take(restored.ticks(), restored)){
                assertTrue(restored.inbox().replay(new Inbox.Arrival(entry.arrival(), entry.input())));
                replayed.add(entry);
            }
            restored.tick();
            WorldHash expected = hashes.get(restored.ticks());
            assertEquals(expected, restored.hash(), "diverged at tick " + restored.ticks());
        }
        assertEquals(Long.MAX_VALUE, reader.nextTick());
        assertEquals(replayed, restored.log().since(logStart), "every entry admitted again, in order, with its number");
        List<CommandLog.Entry> recorded = live.log().since(position);
        assertEquals(recorded.size(), replayed.size());
        for(int i = 0; i < recorded.size(); i++){
            CommandLog.Entry a = recorded.get(i), b = replayed.get(i);
            assertEquals(List.of(a.tick(), a.arrival(), a.verdict()), List.of(b.tick(), b.arrival(), b.verdict()), "entry " + i);
            if(b.input() instanceof Submission submission && submission.command() instanceof Hit hit){
                assertSame(restored, hit.unit().world(), "decoded against the replay world");
                assertEquals(((Hit)((Submission)a.input()).command()).unit().id(), hit.unit().id());
            }
        }
    }

    @Test
    void aForkCapturedWithTheLiveInboxStoresItsWaitingInputs(){
        World live = hittable(CheckpointTest.run(Fixtures.churning(32), 150));
        Hit waiting = hit(live, new Random(3));
        live.submit(waiting, Cause.SERVER);
        World fork = live.fork();
        assertEquals(0, fork.inbox().size(), "a fork starts with an empty inbox");
        fork.inbox().restore(live.inbox().snapshot());
        World restored = Checkpoint.decode(CheckpointTest.file(fork)).restore(Fixtures.config(32));
        assertEquals(live.inbox().nextArrival(), restored.inbox().nextArrival());
        assertEquals(live.inbox().size(), restored.inbox().size());
        live.tick();
        restored.tick();
        assertEquals(live.hash(), restored.hash(), "the restored world admitted what was waiting");
        CommandLog.Entry a = live.log().entries().getLast(), b = restored.log().entries().getLast();
        assertEquals(List.of(a.tick(), a.arrival(), a.verdict()), List.of(b.tick(), b.arrival(), b.verdict()));
    }

    @Test
    void aCrashBundleSaysWhenItsLogNeedsAWorld(){
        World live = hittable(CheckpointTest.run(Fixtures.churning(33), 100));
        byte[] checkpoint = CheckpointTest.file(live);
        live.submit(hit(live, new Random(5)), Cause.SERVER);
        live.tick();
        byte[] zip = CrashBundle.of(checkpoint, live, List.of(), -1, null, new TreeMap<>()).encode();
        var refused = assertThrows(CheckpointException.class, () -> CrashBundle.decode(zip, live.content()));
        assertTrue(refused.getMessage().contains("world object"), refused.getMessage());
    }

    @Test
    void anInputLogRefusesEntriesOutOfOrder(){
        var writer = new InputLog.Writer(100);
        var entry = new CommandLog.Entry(120, 4, new Submission(new Members.Leave(1), Cause.SERVER), Verdict.ADMITTED);
        writer.add(entry);
        assertThrows(IllegalArgumentException.class, () -> writer.add(new CommandLog.Entry(119, 5, entry.input(), Verdict.ADMITTED)));
        assertThrows(IllegalArgumentException.class, () -> new InputLog.Writer(130).add(entry));
    }
}
