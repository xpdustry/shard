package com.xpdustry.shard.compat.v160.world;

import com.xpdustry.shard.common.io.*;
import com.xpdustry.shard.compat.v160.rules.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.content.ContentType;

import java.security.*;
import java.util.*;
import java.util.function.*;

/**
 * A content header (vanilla {@code readContentHeader}/{@code writeContentHeader}): for each listed content type, the
 * content names by id. It is the id space of every content id after it in a save or world stream; a type it doesn't
 * list keeps the reader's own ids.
 *
 * <p>Types are vanilla's {@code ContentType}, by ordinal ({@code wire.v160_7.ContentType}). An epoch names the ones
 * vanilla's header lists: its own {@link ContentType}s but bullets (vanilla's aren't {@code MappableContent}), plus
 * the sectors, planets, unit commands and stances it carries for clients. Vanilla has no team content.
 *
 * <p>Passthrough bytes hold ids in the header of the save they came from. A stream that carries them uses
 * {@link #extend(ContentEpoch)} of that header, so the bytes stay valid without being decoded.
 */
public final class ContentHeader{
    /** Vanilla's {@code ContentType.all.length}. */
    public static final int TYPES = com.xpdustry.shard.wire.v160_7.ContentType.values().length;
    /** Where an epoch keeps the names of each type its header lists, by vanilla ordinal; null where it lists none. */
    private static final List<Function<ContentEpoch, List<String>>> CURRENT = current();

    private final List<List<String>> names; // by vanilla ordinal; empty = not listed
    private final String hash;
    /**
     * The last {@link #of}, {@link #extend} and {@link #mapping} answers and the content they were for: a hosted world
     * asks again at every join's stream capture, on its tick thread, and each answer hashed a header or built tables
     * (W2.21). Headers and mappings are immutable, so a cached answer equals a fresh one.
     */
    private static volatile Cached<ContentHeader> lastOf;
    private volatile Cached<ContentHeader> extended;
    private volatile Cached<IdMapping> mapped;

    private record Cached<T>(ContentEpoch content, T value){
    }

    private ContentHeader(List<List<String>> names){
        this.names = names;
        var out = new Writes();
        write(out);
        hash = sha256(out.toByteArray());
    }

    /** Vanilla's {@code writeContentHeader} for {@code content}: every mappable type that has content, in type order. */
    public static ContentHeader of(ContentEpoch content){
        Cached<ContentHeader> last = lastOf;
        if(last != null && last.content == content) return last.value;
        var names = empty();
        for(int ordinal = 0; ordinal < TYPES; ordinal++) names.set(ordinal, List.copyOf(current(content, ordinal)));
        var header = new ContentHeader(names);
        lastOf = new Cached<>(content, header);
        return header;
    }

    /** Vanilla's {@code readContentHeader}. */
    public static ContentHeader read(Reads in){
        var names = empty();
        int mapped = in.ub();
        for(int i = 0; i < mapped; i++){
            int ordinal = in.b();
            if(ordinal < 0 || ordinal >= TYPES) throw new IllegalArgumentException("content header lists unknown content type " + ordinal);
            int total = in.s();
            var list = new ArrayList<String>(Math.max(total, 0));
            for(int j = 0; j < total; j++) list.add(in.str());
            names.set(ordinal, List.copyOf(list));
        }
        return new ContentHeader(names);
    }

    public void write(Writes out){
        int listed = 0;
        for(List<String> list : names) if(!list.isEmpty()) listed++;
        out.b(listed);
        for(int ordinal = 0; ordinal < names.size(); ordinal++){
            List<String> list = names.get(ordinal);
            if(list.isEmpty()) continue;
            out.b(ordinal);
            out.s(list.size());
            for(String name : list) out.str(name);
        }
    }

    /** The names of vanilla type {@code ordinal} by id, empty when the header doesn't list the type. */
    public List<String> names(int ordinal){
        return names.get(ordinal);
    }

    /** The names of {@code type} by id, empty when the header doesn't list it. */
    public List<String> names(ContentType type){
        return names(type.vanillaOrdinal());
    }

    /** Whether the header lists {@code type} (and so remaps its ids). */
    public boolean lists(ContentType type){
        return !names(type).isEmpty();
    }

    /** A SHA-256 of the written header, which identifies the id space. */
    public String hash(){
        return hash;
    }

    /**
     * This header with, for each type it lists, every name of {@code content} that none of its entries resolves to
     * appended. Ids this header gave keep their meaning, and all current content can be written.
     */
    public ContentHeader extend(ContentEpoch content){
        Cached<ContentHeader> last = extended;
        if(last != null && last.content == content) return last.value;
        var out = empty();
        for(int ordinal = 0; ordinal < TYPES; ordinal++){
            List<String> list = names.get(ordinal);
            if(list.isEmpty()) continue;
            List<String> current = current(content, ordinal);
            var resolved = new TreeSet<Integer>();
            for(String name : list){
                int id = resolve(content, ordinal, current, name);
                if(id >= 0) resolved.add(id);
            }
            var extended = new ArrayList<>(list);
            for(int id = 0; id < current.size(); id++){
                if(!resolved.contains(id)) extended.add(current.get(id));
            }
            if(extended.size() > Short.MAX_VALUE) throw new IllegalStateException(typeName(ordinal) + " has too many entries for a content header");
            out.set(ordinal, List.copyOf(extended));
        }
        var header = new ContentHeader(out);
        this.extended = new Cached<>(content, header);
        return header;
    }

    /**
     * Whether every id {@code base} gives means the same content here: the same types are listed, each starting
     * with {@code base}'s names. True of {@code base.extend(...)}, so bytes read with {@code base} can be written here.
     */
    public boolean keepsIdsOf(ContentHeader base){
        for(int ordinal = 0; ordinal < names.size(); ordinal++){
            List<String> mine = names.get(ordinal), theirs = base.names.get(ordinal);
            if(mine.isEmpty() != theirs.isEmpty() || mine.size() < theirs.size() || !mine.subList(0, theirs.size()).equals(theirs)){
                return false;
            }
        }
        return true;
    }

    /** The translation between this header's ids and {@code content}'s. */
    public IdMapping mapping(ContentEpoch content){
        Cached<IdMapping> last = mapped;
        if(last != null && last.content == content) return last.value;
        var mapping = new IdMapping(this, content);
        mapped = new Cached<>(content, mapping);
        return mapping;
    }

    @Override
    public boolean equals(Object o){
        return o instanceof ContentHeader other && hash.equals(other.hash);
    }

    @Override
    public int hashCode(){
        return hash.hashCode();
    }

    @Override
    public String toString(){
        var out = new StringBuilder("ContentHeader[");
        for(int ordinal = 0; ordinal < TYPES; ordinal++){
            if(!names.get(ordinal).isEmpty()) out.append(typeName(ordinal)).append('=').append(names.get(ordinal).size()).append(' ');
        }
        return out.append(hash, 0, 12).append(']').toString();
    }

    /** The names {@code content} has for vanilla type {@code ordinal}, empty when its header doesn't list the type. */
    static List<String> current(ContentEpoch content, int ordinal){
        Function<ContentEpoch, List<String>> names = CURRENT.get(ordinal);
        return names == null ? List.of() : names.apply(content);
    }

    /** The current id {@code name} stands for, as vanilla's reader resolves it: block names through the save fallbacks. */
    private static int resolve(ContentEpoch content, int ordinal, List<String> current, String name){
        for(ContentType type : ContentType.values()){
            if(type.vanillaOrdinal() == ordinal) return ContentLookup.byNameWithFallback(content, type, name);
        }
        return current.indexOf(name);
    }

    private static String typeName(int ordinal){
        return com.xpdustry.shard.wire.v160_7.ContentType.of(ordinal).name();
    }

    private static List<Function<ContentEpoch, List<String>>> current(){
        var out = new ArrayList<Function<ContentEpoch, List<String>>>(Collections.nCopies(TYPES, null));
        for(ContentType type : ContentType.values()){
            if(type != ContentType.BULLET) out.set(type.vanillaOrdinal(), content -> content.names(type));
        }
        out.set(com.xpdustry.shard.wire.v160_7.ContentType.sector.ordinal(), ContentEpoch::sectors);
        out.set(com.xpdustry.shard.wire.v160_7.ContentType.planet.ordinal(), ContentEpoch::planets);
        out.set(com.xpdustry.shard.wire.v160_7.ContentType.unitCommand.ordinal(), ContentEpoch::unitCommands);
        out.set(com.xpdustry.shard.wire.v160_7.ContentType.unitStance.ordinal(), ContentEpoch::unitStances);
        return Collections.unmodifiableList(out);
    }

    private static List<List<String>> empty(){
        return new ArrayList<>(Collections.nCopies(TYPES, List.of()));
    }

    private static String sha256(byte[] bytes){
        try{
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        }catch(NoSuchAlgorithmException e){
            throw new IllegalStateException(e);
        }
    }

    /** Converts content ids between a header's id space ("stream ids") and the current content's. */
    public static final class IdMapping{
        private final int[][] toCurrent, toStream;
        private final ContentHeader header;

        private IdMapping(ContentHeader header, ContentEpoch content){
            this.header = header;
            int types = ContentType.values().length;
            toCurrent = new int[types][];
            toStream = new int[types][];
            for(ContentType type : ContentType.values()){
                int count = content.count(type);
                int[] stream = new int[count];
                if(!header.lists(type)){
                    for(int id = 0; id < count; id++) stream[id] = id;
                    toStream[type.ordinal()] = stream;
                    continue;
                }
                Arrays.fill(stream, -1);
                List<String> names = header.names(type);
                int[] current = new int[names.size()];
                for(int id = 0; id < names.size(); id++){
                    // As vanilla's reader: block names go through the save fallbacks first.
                    int resolved = ContentLookup.byNameWithFallback(content, type, names.get(id));
                    current[id] = resolved;
                    if(resolved >= 0 && stream[resolved] < 0) stream[resolved] = id;
                }
                toCurrent[type.ordinal()] = current;
                toStream[type.ordinal()] = stream;
            }
        }

        public ContentHeader header(){
            return header;
        }

        /**
         * The current id of stream id {@code id}, as vanilla's {@code getByID} under a temporary mapper: negative is
         * -1 (null); an id the header lists but the content doesn't know, or one past its end, is 0. Without a mapper
         * for the type, the id itself, or -1 when out of range.
         */
        public int toCurrent(ContentType type, int id){
            int[] map = toCurrent[type.ordinal()];
            if(map == null) return id >= 0 && id < toStream[type.ordinal()].length ? id : -1;
            if(id < 0) return -1;
            if(id >= map.length || map[id] < 0) return 0;
            return map[id];
        }

        /** Whether stream id {@code id} names content that exists (rather than defaulting to id 0). */
        public boolean known(ContentType type, int id){
            int[] map = toCurrent[type.ordinal()];
            if(map == null) return id >= 0 && id < toStream[type.ordinal()].length;
            return id >= 0 && id < map.length && map[id] >= 0;
        }

        /** The stream id of current id {@code id}, or -1 if the header cannot name it. */
        public int toStream(ContentType type, int id){
            int[] map = toStream[type.ordinal()];
            return id >= 0 && id < map.length ? map[id] : -1;
        }

        /** {@link #toStream}, failing when the header cannot name the content. */
        public int requireStream(ContentType type, int id){
            int out = toStream(type, id);
            if(out < 0) throw new IllegalStateException("the content header cannot name " + type.yamlName() + " " + id);
            return out;
        }
    }
}
