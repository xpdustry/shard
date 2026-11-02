package com.xpdustry.shard.sim.entity;

import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.toy.*;
import com.xpdustry.shard.sim.world.*;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

class AttachmentTest{
    /** A mutable attachment, so the fork must copy it. */
    static final class Tally{
        int value;
    }

    static final AttachmentKey<Tally> TALLY = AttachmentKey.create("test.attachment.tally", Tally.class,
        Persistence.AUTHORITATIVE, (tally, context) -> {
            var out = new Tally();
            out.value = tally.value;
            return out;
        }, (tally, hasher) -> hasher.i(tally.value));
    static final AttachmentKey<String> NOTE = AttachmentKey.create("test.attachment.note", String.class,
        Persistence.DERIVED, (note, context) -> note, (note, hasher) -> hasher.str(note));

    @Test
    void attachmentsAreCopiedOnForkAndHashed(){
        World world = Worlds.quiet(1);
        Building building = world.buildings().create(ToyContent.COUNTER, 1, 4, 4, 0, Cause.SERVER);
        WorldHash bare = world.hash();
        var tally = new Tally();
        tally.value = 3;
        building.attach(TALLY, tally);
        assertNotEquals(bare, world.hash(), "authoritative attachments are hashed");
        WorldHash tallied = world.hash();
        building.attach(NOTE, "derived");
        assertEquals(tallied, world.hash(), "derived attachments are not");

        World fork = world.fork();
        Building copy = fork.buildings().get(building.index());
        assertEquals(3, copy.attachment(TALLY).value);
        assertNotSame(tally, copy.attachment(TALLY));
        copy.attachment(TALLY).value = 4;
        assertEquals(3, building.attachment(TALLY).value);
        assertEquals("derived", copy.attachment(NOTE));

        building.attach(TALLY, null);
        assertNull(building.attachment(TALLY));
        assertEquals(bare, world.hash(), "detaching restores the hash; the derived note never counted");
    }

    @Test
    void keysAreUnique(){
        assertThrows(IllegalArgumentException.class, () -> AttachmentKey.create("test.attachment.tally", Tally.class,
            Persistence.AUTHORITATIVE, (t, c) -> t, (t, h) -> {}));
        assertThrows(IllegalArgumentException.class, () -> AttachmentKey.create("test.attachment.external", Tally.class,
            Persistence.EXTERNAL, (t, c) -> t, (t, h) -> {}));
    }
}
