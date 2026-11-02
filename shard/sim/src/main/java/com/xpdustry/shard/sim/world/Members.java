package com.xpdustry.shard.sim.world;

import java.util.*;

/**
 * The sessions currently routed to a world, with the route generation and last admitted sequence of each. Admission
 * checks every {@link IntentEnvelope} against it before the intent runs. Sessions join and leave through the
 * {@link Join} and {@link Leave} commands, so membership changes are logged like everything else.
 */
public final class Members{
    /** {@link Member}s as mutable slots, so admitting an intent records its sequence in place. */
    private final TreeMap<Long, Slot> members;

    private static final class Slot{
        final int routeGeneration;
        long lastSequence;

        Slot(int routeGeneration, long lastSequence){
            this.routeGeneration = routeGeneration;
            this.lastSequence = lastSequence;
        }

        Member member(){
            return new Member(routeGeneration, lastSequence);
        }
    }

    public Members(){
        members = new TreeMap<>();
    }

    private Members(Members source){
        members = new TreeMap<>();
        source.members.forEach((session, slot) -> members.put(session, new Slot(slot.routeGeneration, slot.lastSequence)));
    }

    /** Null if {@code envelope} may be applied, else why not. Admitting it records its sequence. */
    Verdict check(IntentEnvelope envelope){
        Slot member = members.get(envelope.session());
        if(member == null) return Verdict.rejected("not a member");
        if(member.routeGeneration != envelope.routeGeneration()) return Verdict.rejected("stale route");
        if(envelope.sequence() <= member.lastSequence) return Verdict.rejected("replayed sequence");
        member.lastSequence = envelope.sequence();
        return null;
    }

    public boolean contains(long session){
        return members.containsKey(session);
    }

    public Optional<Member> get(long session){
        Slot slot = members.get(session);
        return slot == null ? Optional.empty() : Optional.of(slot.member());
    }

    /** Sessions in ascending order. */
    public List<Long> sessions(){
        return List.copyOf(members.keySet());
    }

    public int size(){
        return members.size();
    }

    public Members copy(){
        return new Members(this);
    }

    /** For a world restored from a checkpoint ({@link World.Restoring}). */
    void restore(long session, Member member){
        Objects.requireNonNull(member, "member");
        members.put(session, new Slot(member.routeGeneration(), member.lastSequence()));
    }

    void hash(Hasher hasher){
        hasher.i(members.size());
        members.forEach((session, member) -> hasher.l(session).i(member.routeGeneration).l(member.lastSequence));
    }

    /** @param lastSequence the highest admitted sequence; envelopes must be above it */
    public record Member(int routeGeneration, long lastSequence){
    }

    /** Routes {@code session} to this world with a new route generation; it may then send sequences from 0. */
    public record Join(long session, int routeGeneration) implements Command.Internal{
        @Override
        public Verdict apply(World world, Cause cause){
            Members members = world.members();
            Slot current = members.members.get(session);
            if(current != null && current.routeGeneration >= routeGeneration){
                return Verdict.rejected("route generation not newer");
            }
            members.members.put(session, new Slot(routeGeneration, -1));
            return Verdict.ADMITTED;
        }
    }

    /** Removes {@code session} from this world. */
    public record Leave(long session) implements Command.Internal{
        @Override
        public Verdict apply(World world, Cause cause){
            return world.members().members.remove(session) != null ? Verdict.ADMITTED : Verdict.rejected("not a member");
        }
    }
}
