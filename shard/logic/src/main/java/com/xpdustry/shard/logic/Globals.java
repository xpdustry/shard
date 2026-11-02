package com.xpdustry.shard.logic;

import com.xpdustry.shard.content.*;
import com.xpdustry.shard.logic.lang.*;

import java.util.*;

/**
 * Vanilla's {@code GlobalVars}: the names every processor can read ({@code true}, {@code @pi}, {@code @time},
 * {@code @copper}, {@code @colorRed}, {@code @totalItems}…), and the logic ids {@code lookup} reads. One table per
 * content epoch, shared by every world that pins it; programs bake the values in, so a {@link ProgramCache} belongs to
 * one table.
 *
 * <p>Vanilla's table is one set of shared {@code LVar}s for the whole JVM, and its dynamic entries ({@code @time},
 * {@code @tick}…) are updated once per frame. Here a dynamic entry has an id into {@link LogicWorld#globals()}, which
 * each world updates for itself. Content values are the epoch's definitions ({@link ContentDef}) and teams are
 * {@link LogicTeam}s, both the table's own, so a host hands them back as they came.
 *
 * <p>As on vanilla's headless server, there are no {@code @sfx-*} names: {@code GlobalVars.init} only adds them when
 * {@code Core.assets} is loaded, which a server never does. {@code @sfx-shoot} is an ordinary variable there.
 */
public final class Globals{
    /** Ids of the dynamic globals in {@link LogicWorld#globals()}. */
    public static final int TIME = 0, TICK = 1, SECOND = 2, MINUTE = 3, WAVE = 4, WAVE_TIME = 5, MAP_W = 6, MAP_H = 7,
    SERVER = 8, CLIENT = 9, CLIENT_TEAM = 10, CLIENT_MOBILE = 11, CLIENT_MUSIC_PLAYING = 12;
    static final int DYNAMIC = 13;

    /** {@code GlobalVars.ctrlProcessor}, {@code ctrlPlayer}, {@code ctrlCommand}: what {@code sensor @controlled} gives. */
    public static final int ctrlProcessor = 1, ctrlPlayer = 2, ctrlCommand = 3;

    /** A global: a fixed value (a {@code Double} or an object) or a dynamic one ({@code dynamic >= 0}). */
    public record Entry(Object value, int dynamic){
    }

    /** {@code GlobalVars.writableLookableContent}: the types with logic ids, in the order of their counts. */
    private static final List<ContentType> LOOKABLE = List.of(ContentType.BLOCK, ContentType.UNIT, ContentType.ITEM, ContentType.LIQUID);
    /** The vanilla ordinal of {@code team}, which {@code lookup} reads without content. */
    private static final int TEAM = ContentType.VANILLA_NAMES.indexOf("team");

    private final ContentEpoch content;
    private final LogicTeam[] teams = new LogicTeam[ContentEpoch.TEAMS];
    /** By vanilla ordinal: content by logic id, and logic ids by content id; null for types without logic ids. */
    private final ContentDef[][] byLogicId = new ContentDef[ContentType.VANILLA_NAMES.size()][];
    private final int[][] logicIds = new int[ContentType.VANILLA_NAMES.size()][];
    private final Map<String, Entry> entries = new HashMap<>(); // lookups only, never iterated
    private final Set<String> privileged = new HashSet<>();
    private final Set<String> statusEffects = new HashSet<>();
    private final Entry nullEntry;
    private final ProgramCache cache = new ProgramCache(this, 4096);

    private static final Map<ContentEpoch, Globals> shared = new IdentityHashMap<>();

    /**
     * The table of {@code content}, built once and shared by every world that pins that epoch, so they share its
     * programs too. Thread-safe.
     */
    public static Globals of(ContentEpoch content){
        synchronized(shared){
            return shared.computeIfAbsent(content, Globals::new);
        }
    }

    /** The table vanilla's {@code GlobalVars.init} builds, with {@code content}'s constants and logic ids. */
    public Globals(ContentEpoch content){
        this.content = content;
        for(int id = 0; id < teams.length; id++) teams[id] = new LogicTeam(id, content.teamName(id));

        put("the end", null, false);
        put("false", 0.0, false);
        put("true", 1.0, false);
        put("null", null, false);
        nullEntry = entries.get("null");

        put("@pi", (double)3.1415927f, false);
        put("π", (double)3.1415927f, false);
        put("@e", (double)2.7182818f, false);
        put("@degToRad", (double)(3.1415927f / 180), false);
        put("@radToDeg", (double)(180f / 3.1415927f), false);

        dynamic("@time", TIME, false);
        dynamic("@tick", TICK, false);
        dynamic("@second", SECOND, false);
        dynamic("@minute", MINUTE, false);
        dynamic("@waveNumber", WAVE, false);
        dynamic("@waveTime", WAVE_TIME, false);
        dynamic("@mapw", MAP_W, false);
        dynamic("@maph", MAP_H, false);
        put("@wait", null, true);

        dynamic("@server", SERVER, true);
        dynamic("@client", CLIENT, true);
        // the client's own variables: a server has no local player, so they keep their initial values
        put("@clientLocale", null, true);
        put("@clientUnit", null, true);
        put("@clientName", null, true);
        dynamic("@clientTeam", CLIENT_TEAM, true);
        dynamic("@clientMobile", CLIENT_MOBILE, true);
        dynamic("@clientMusicPlaying", CLIENT_MUSIC_PLAYING, true);
        put("@clientCurrentMusic", null, true);

        put("@ctrlProcessor", (double)ctrlProcessor, false);
        put("@ctrlPlayer", (double)ctrlPlayer, false);
        put("@ctrlCommand", (double)ctrlCommand, false);

        for(int id = 0; id < ContentEpoch.BASE_TEAMS; id++) put("@" + teams[id].name(), teams[id], false);
        constants(ContentType.ITEM);
        constants(ContentType.LIQUID);
        for(int id = 0; id < content.blocks(); id++){
            // blocks named like an item (sand) are left out, and legacy blocks, which every map load removes
            BlockDef block = content.block(id);
            if(content.item(block.name()) == null && !block.legacy()) put("@" + block.name(), block, false);
        }
        for(int id = 0; id < content.units(); id++){
            UnitDef unit = content.unit(id);
            if(!unit.internal()) put("@" + unit.name(), unit, false);
        }
        constants(ContentType.WEATHER);
        for(int id = 0, n = content.count(ContentType.STATUS); id < n; id++){
            ContentDef effect = content.get(ContentType.STATUS, id);
            statusEffects.add(effect.name());
            put("@status-" + effect.name(), effect, false);
        }

        for(var color : LogicColors.all().entrySet()){
            // vanilla skips the uppercase variants, which are duplicates
            if(Character.isUpperCase(color.getKey().charAt(0))) continue;
            put("@color" + capitalize(color.getKey()), color.getValue(), false);
        }
        for(LAccess access : LAccess.values()){
            put("@" + access.name(), access, false);
        }
        for(int i = 0; i < Align.names.length; i++){
            put("@" + Align.names[i], (double)Align.values[i], false);
        }

        // logicids.dat, which hand-built content lacks, as vanilla without the file
        for(ContentType type : LOOKABLE){
            List<String> names = content.logicIds(type);
            if(names.isEmpty()) continue;
            put("@" + type.yamlName() + "Count", (double)names.size(), false);
            var byId = new ContentDef[names.size()];
            // content the client's table doesn't list has logic id 0, as in vanilla
            var ids = new int[content.count(type)];
            for(int i = 0; i < names.size(); i++){
                ContentDef def = content.find(type, names.get(i));
                if(def == null) continue;
                byId[i] = def;
                ids[def.id()] = i;
            }
            byLogicId[type.vanillaOrdinal()] = byId;
            logicIds[type.vanillaOrdinal()] = ids;
        }
    }

    /** The content epoch whose definitions are this table's content values. */
    public ContentEpoch content(){
        return content;
    }

    /** {@code Team.get(id)}: the table's team with id {@code id}, 0 to 255. */
    public LogicTeam team(int id){
        return teams[id];
    }

    /**
     * {@code GlobalVars.lookupContent}: the content of vanilla {@code ContentType} ordinal {@code type} with logic id
     * (not content id) {@code id}, the team with id {@code id} for {@code team}, else null.
     */
    public Object lookup(int type, int id){
        if(type == TEAM) return id >= 0 && id < teams.length ? teams[id] : null;
        ContentDef[] byId = type >= 0 && type < byLogicId.length ? byLogicId[type] : null;
        return byId != null && id >= 0 && id < byId.length ? byId[id] : null;
    }

    /**
     * {@code GlobalVars.lookupLogicId}, which {@code sensor @id} of content gives: {@code def}'s logic id, 0 for content
     * the client's table doesn't list, -1 for a type without logic ids.
     */
    public int logicId(ContentDef def){
        int[] ids = logicIds[def.type().vanillaOrdinal()];
        return ids != null && def.id() >= 0 && def.id() < ids.length ? ids[def.id()] : -1;
    }

    /**
     * {@code GlobalVars.get(name, privileged)}: the entry, or null if {@code name} is not a global. A privileged name
     * read by a normal processor is the {@code null} global.
     */
    public Entry lookup(String name, boolean privileged){
        if(!privileged && this.privileged.contains(name)) return nullEntry;
        return entries.get(name);
    }

    /** The status effect names, which {@code status} accepts bare ({@code LParser}'s {@code checkRead}). */
    public Set<String> statusEffects(){
        return Collections.unmodifiableSet(statusEffects);
    }

    /** The programs assembled against this table. */
    public ProgramCache cache(){
        return cache;
    }

    /** {@code GlobalVars.put}: the first value for a name wins (vanilla #6910). */
    private void put(String name, Object value, boolean privileged){
        if(entries.containsKey(name)) return;
        entries.put(name, new Entry(value, -1));
        if(privileged) this.privileged.add(name);
    }

    private void constants(ContentType type){
        for(int id = 0, n = content.count(type); id < n; id++){
            ContentDef def = content.get(type, id);
            put("@" + def.name(), def, false);
        }
    }

    private void dynamic(String name, int id, boolean privileged){
        entries.put(name, new Entry(null, id));
        if(privileged) this.privileged.add(name);
    }

    /** Arc's {@code Strings.capitalize}. */
    private static String capitalize(String text){
        return text.isEmpty() ? text : Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }
}
