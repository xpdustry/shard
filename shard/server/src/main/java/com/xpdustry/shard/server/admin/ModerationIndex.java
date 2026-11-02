package com.xpdustry.shard.server.admin;

import com.xpdustry.shard.server.store.Bans.*;
import com.xpdustry.shard.server.store.Grants.*;
import com.xpdustry.shard.server.store.NameBans.*;

import java.net.*;
import java.util.*;
import java.util.regex.*;

/**
 * An immutable snapshot of committed moderation data, which admission reads without touching the database
 * (note 34): bans that were in force when it was built, name bans, admins and the whitelist. Expiry is checked
 * against the time of each lookup, so a ban that expires later stops matching without a rebuild.
 *
 * @param version grows with every committed moderation change; admission returns the version it decided with
 */
public record ModerationIndex(long version, Map<String, List<Ban>> uuidBans, Map<String, List<Ban>> addressBans,
                              List<SubnetBan> subnetBans, Map<String, String> adminUsids, Map<String, String> whitelistUsids,
                              List<Pattern> nameBans){
    public static final ModerationIndex EMPTY = new ModerationIndex(0, Map.of(), Map.of(), List.of(), Map.of(), Map.of(), List.of());
    /**
     * How many characters one name ban may read while matching one name: a backtracking pattern stops there instead
     * of holding the connect thread (a 40-character name against a sane pattern reads a few hundred).
     */
    static final int NAME_MATCH_STEPS = 100_000;

    /** A subnet ban with its parsed block. */
    public record SubnetBan(Subnet subnet, Ban ban){
    }

    static ModerationIndex build(long version, List<Ban> bans, List<Grant> admins, List<Grant> whitelist, List<NameBan> names){
        var uuids = new HashMap<String, List<Ban>>();
        var addresses = new HashMap<String, List<Ban>>();
        var subnets = new ArrayList<SubnetBan>();
        for(var ban : bans){
            switch(ban.kind()){
                case UUID -> uuids.computeIfAbsent(ban.target(), k -> new ArrayList<>()).add(ban);
                case ADDRESS -> addresses.computeIfAbsent(ban.target(), k -> new ArrayList<>()).add(ban);
                case SUBNET -> subnets.add(new SubnetBan(Subnet.parse(ban.target(), false), ban));
            }
        }
        var adminUsids = new HashMap<String, String>();
        for(var grant : admins) adminUsids.put(grant.player().uuid(), grant.usidHash());
        var whitelistUsids = new HashMap<String, String>();
        for(var grant : whitelist) whitelistUsids.put(grant.player().uuid(), grant.usidHash());
        var patterns = new ArrayList<Pattern>();
        for(var name : names){
            try{
                patterns.add(compileName(name.pattern()));
            }catch(IllegalArgumentException ignored){
                // stored by an older build or by hand: vanilla skips a pattern that doesn't compile too
            }
        }
        return new ModerationIndex(version, freeze(uuids), freeze(addresses), List.copyOf(subnets), Map.copyOf(adminUsids), Map.copyOf(whitelistUsids),
            List.copyOf(patterns));
    }

    /** A name ban's pattern as vanilla compiles it ({@code Pattern.CASE_INSENSITIVE}); throws for an invalid one. */
    static Pattern compileName(String regex){
        try{
            return Pattern.compile(regex, Pattern.CASE_INSENSITIVE);
        }catch(PatternSyntaxException e){
            throw new IllegalArgumentException("Invalid regex: " + e.getDescription(), e);
        }
    }

    /**
     * The first name ban {@code name} contains a match of (vanilla's {@code isNameBanned},
     * {@code Administration.java:408}), as its pattern. A pattern that runs out of {@link #NAME_MATCH_STEPS} counts as
     * a match: refusing one name is cheaper than a stalled admission.
     */
    public Optional<String> nameBan(String name){
        for(Pattern pattern : nameBans){
            try{
                if(pattern.matcher(new Bounded(name)).find()) return Optional.of(pattern.pattern());
            }catch(Bounded.Exhausted e){
                return Optional.of(pattern.pattern());
            }
        }
        return Optional.empty();
    }

    /** A name that stops a match after {@link #NAME_MATCH_STEPS} character reads. */
    private static final class Bounded implements CharSequence{
        private final String text;
        private final int[] steps;

        Bounded(String text){
            this(text, new int[]{NAME_MATCH_STEPS});
        }

        private Bounded(String text, int[] steps){
            this.text = text;
            this.steps = steps;
        }

        @Override
        public char charAt(int index){
            if(--steps[0] < 0) throw new Exhausted();
            return text.charAt(index);
        }

        @Override
        public int length(){
            return text.length();
        }

        @Override
        public CharSequence subSequence(int start, int end){
            return new Bounded(text.substring(start, end), steps);
        }

        @Override
        public String toString(){
            return text;
        }

        static final class Exhausted extends RuntimeException{
            Exhausted(){
                super(null, null, false, false);
            }
        }
    }

    /** The UUID ban in force at {@code now} that expires last, if any. */
    public Optional<Ban> uuidBan(String uuid, long now){
        return longest(uuidBans.getOrDefault(uuid, List.of()), now);
    }

    /** The ban in force on this address or a subnet that contains it, expiring last. */
    public Optional<Ban> addressBan(InetAddress address, long now){
        var matches = new ArrayList<Ban>(addressBans.getOrDefault(Addresses.format(address), List.of()));
        for(var subnet : subnetBans) if(subnet.subnet().contains(address)) matches.add(subnet.ban());
        return longest(matches, now);
    }

    /** Vanilla's {@code isAdmin}: the UUID is an admin and the USID matches the one pinned at grant time. */
    public boolean isAdmin(String uuid, String usidHash){
        return usidHash != null && usidHash.equals(adminUsids.get(uuid));
    }

    /** Vanilla's {@code isWhitelisted}, keyed by UUID and USID together ({@code Administration.java:378}). */
    public boolean isWhitelisted(String uuid, String usidHash){
        return usidHash != null && usidHash.equals(whitelistUsids.get(uuid));
    }

    private static Optional<Ban> longest(List<Ban> bans, long now){
        Ban best = null;
        for(var ban : bans){
            if(!ban.activeAt(now)) continue;
            if(best == null || ban.expiresAt() == null || best.expiresAt() != null && ban.expiresAt() > best.expiresAt()) best = ban;
        }
        return Optional.ofNullable(best);
    }

    private static Map<String, List<Ban>> freeze(Map<String, List<Ban>> map){
        var frozen = new HashMap<String, List<Ban>>();
        map.forEach((key, list) -> frozen.put(key, List.copyOf(list)));
        return Map.copyOf(frozen);
    }
}
