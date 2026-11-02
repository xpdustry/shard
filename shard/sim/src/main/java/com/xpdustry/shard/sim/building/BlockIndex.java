package com.xpdustry.shard.sim.building;

import com.xpdustry.shard.common.struct.*;
import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.rules.*;
import com.xpdustry.shard.sim.world.*;

import java.util.*;

/**
 * The building half of vanilla's {@code BlockIndexer}: per-team flag lists, the teams that ever had a building
 * ({@code activeTeams}), and per-team damaged lists. {@link Buildings} feeds it where vanilla's tile events do
 * ({@code process} on {@code TileChangeEvent}, {@code removeIndex} on {@code TilePreChangeEvent} and around a team
 * change, a full rebuild in tile order on {@code WorldLoadEvent}), under the team the chunk index files the building
 * under. Range queries use {@link BuildingChunks}; ores, floors and team data (WP23) are not here.
 *
 * <p>Every list's order is vanilla's history (flag lists are ordered {@code Seq}s, damaged lists unordered ones), so
 * they are {@link Persistence#AUTHORITATIVE} and stored by {@link Buildings#write}; so is each team's
 * {@link BuildingTree}, whose shape is history too.
 */
final class BlockIndex{
    private BuildingList[][] flagged = new BuildingList[VanillaIds.TEAMS][];
    private final IntSeq activeTeams;
    private BuildingList[] damaged = new BuildingList[VanillaIds.TEAMS];
    /** Each team's {@code turretTree}: its filed buildings with {@code attacks}, in no meaningful order. */
    @Derived("rebuildTurrets(): only membership is read")
    private BuildingList[] turrets = new BuildingList[VanillaIds.TEAMS];
    /** Each team's {@code buildingTree}, made on its first building, over the whole map. */
    private BuildingTree[] trees = new BuildingTree[VanillaIds.TEAMS];
    /** Each team's {@code TeamData.buildings}, an unordered {@code Seq} (W2.23, for {@code fetch build}). */
    private BuildingList[] teamBuildings = new BuildingList[VanillaIds.TEAMS];
    /** Each team's {@code buildingTypes}, by block id: unordered {@code Seq}s, made on first use as vanilla's map makes them. */
    private BuildingList[][] typeBuildings = new BuildingList[VanillaIds.TEAMS][];
    @Derived("the map's size in world units, from the world")
    private final float treeWidth, treeHeight;

    /** An empty index for a map of {@code width × height} world units. */
    BlockIndex(float width, float height){
        activeTeams = new IntSeq();
        treeWidth = width;
        treeHeight = height;
    }

    private BlockIndex(BlockIndex source, CopyContext context){
        activeTeams = source.activeTeams.copy();
        treeWidth = source.treeWidth;
        treeHeight = source.treeHeight;
        for(int t = 0; t < VanillaIds.TEAMS; t++){
            if(source.trees[t] != null) trees[t] = source.trees[t].copy(context);
            if(source.flagged[t] != null){
                flagged[t] = new BuildingList[BlockFlag.count()];
                for(int f = 0; f < flagged[t].length; f++){
                    if(source.flagged[t][f] != null) flagged[t][f] = source.flagged[t][f].copy(context);
                }
            }
            if(source.damaged[t] != null) damaged[t] = source.damaged[t].copy(context);
            if(source.teamBuildings[t] != null) teamBuildings[t] = source.teamBuildings[t].copy(context);
            if(source.typeBuildings[t] != null){
                typeBuildings[t] = new BuildingList[source.typeBuildings[t].length];
                for(int b = 0; b < typeBuildings[t].length; b++){
                    if(source.typeBuildings[t][b] != null) typeBuildings[t][b] = source.typeBuildings[t][b].copy(context);
                }
            }
            if(source.turrets[t] != null) turrets[t] = source.turrets[t].copy(context);
        }
    }

    BlockIndex copy(CopyContext context){
        return new BlockIndex(this, context);
    }

    /** Forgets everything: {@code WorldLoadEvent} makes new lists before processing every tile. */
    void clear(){
        flagged = new BuildingList[VanillaIds.TEAMS][];
        damaged = new BuildingList[VanillaIds.TEAMS];
        turrets = new BuildingList[VanillaIds.TEAMS];
        trees = new BuildingTree[VanillaIds.TEAMS];
        teamBuildings = new BuildingList[VanillaIds.TEAMS];
        typeBuildings = new BuildingList[VanillaIds.TEAMS][];
        activeTeams.clear();
    }

    /** {@code team.data().buildingTree}, or null before the team's first building. */
    BuildingTree tree(int team){
        return trees[team];
    }

    /** Refills the turret lists from the buildings each team has filed, after a restore. */
    void rebuildTurrets(Buildings buildings){
        turrets = new BuildingList[VanillaIds.TEAMS];
        for(int i = 0; i < buildings.indexLimit(); i++){
            Building building = buildings.get(i);
            int team = building == null ? -1 : buildings.indexedTeam(building);
            if(team != -1 && building.info().attacks) turrets(team).add(building);
        }
    }

    /** {@code turretTree.any(…)}: whether any of {@code team}'s attacking buildings passes {@code test}. */
    boolean anyTurret(int team, java.util.function.Predicate<Building> test){
        BuildingList list = turrets[team];
        if(list == null) return false;
        for(int i = 0; i < list.size; i++){
            if(test.test(list.items[i])) return true;
        }
        return false;
    }

    private BuildingList turrets(int team){
        return turrets[team] != null ? turrets[team] : (turrets[team] = new BuildingList());
    }

    /** {@code process(tile)} (BlockIndexer.java:589) for a building's origin, filed under {@code team}. */
    void add(Building building, int team){
        BlockInfo info = building.info();
        for(int i = 0; i < info.flagCount(); i++) flags(team, info.flag(i)).add(building);
        typed(team, building.block().id()).add(building);
        listOf(team).add(building);
        if(!activeTeams.contains(team)) activeTeams.add(team);
        if(trees[team] == null) trees[team] = new BuildingTree(0, 0, treeWidth, treeHeight);
        trees[team].insert(building);
        if(info.attacks) turrets(team).add(building);
        healthChanged(building);
    }

    /** {@code removeIndex(tile)} (BlockIndexer.java:146) for a building filed under {@code team}. */
    void remove(Building building, int team){
        BlockInfo info = building.info();
        for(int i = 0; i < info.flagCount(); i++) flags(team, info.flag(i)).removeOrdered(building);
        if(teamBuildings[team] != null) teamBuildings[team].removeUnordered(building);
        typed(team, building.block().id()).removeUnordered(building);
        if(trees[team] != null) trees[team].remove(building);
        if(info.attacks && turrets[team] != null) turrets[team].removeUnordered(building);
        if(building.wasDamaged && damaged[team] != null) damaged[team].removeUnordered(building);
        building.wasDamaged = false;
    }

    /** {@code notifyHealthChanged(build)}: the damaged list of the building's current team follows its health. */
    void healthChanged(Building building){
        boolean now = building.damaged();
        if(building.wasDamaged == now) return;
        int team = building.team;
        if(damaged[team] == null) damaged[team] = new BuildingList();
        if(now){
            damaged[team].add(building);
        }else{
            damaged[team].removeUnordered(building);
        }
        building.wasDamaged = now;
    }

    /** {@code getFlagged(team, flag)}: in vanilla's order, as a new list. */
    List<Building> flagged(int team, BlockFlag flag){
        BuildingList list = flagged[team] == null ? null : flagged[team][flag.ordinal()];
        return list == null ? List.of() : list.toList();
    }

    /**
     * {@code getEnemy(team, flag)}: every other team's flagged buildings, teams in id order (vanilla's
     * {@code state.teams.present}, or every team when that is empty), derelict only with {@code coreCapture}.
     */
    List<Building> enemy(int team, BlockFlag flag, boolean coreCapture){
        var out = new ArrayList<Building>();
        for(int t = 0; t < VanillaIds.TEAMS; t++){
            if(t == team || (t == VanillaIds.DERELICT && !coreCapture) || flagged[t] == null) continue;
            BuildingList list = flagged[t][flag.ordinal()];
            if(list != null) list.addTo(out);
        }
        return out;
    }

    /** {@code getDamaged(team)}: drops the buildings healed since, without clearing their {@code wasDamaged} (vanilla quirk). */
    List<Building> damaged(int team){
        BuildingList list = damaged[team];
        if(list == null){
            damaged[team] = new BuildingList();
            return List.of();
        }
        list.removeIf(b -> !b.damaged());
        return list.toList();
    }

    /** {@code team.data().buildings}: every building filed under the team, in vanilla's (swap-removal) order. */
    List<Building> teamBuildings(int team){
        BuildingList list = teamBuildings[team];
        return list == null ? List.of() : list.toList();
    }

    /** {@code team.data().buildings.size}. */
    int teamBuildingCount(int team){
        BuildingList list = teamBuildings[team];
        return list == null ? 0 : list.size;
    }

    /** {@code indexer.getFlagged(team, flag)}'s size and entries, without a copy. */
    int flaggedCount(int team, BlockFlag flag){
        BuildingList list = flagged[team] == null ? null : flagged[team][flag.ordinal()];
        return list == null ? 0 : list.size;
    }

    Building flaggedAt(int team, BlockFlag flag, int index){
        return flagged[team][flag.ordinal()].items[index];
    }

    /** {@code team.data().getBuildings(block)}, which makes the block's list on first use, as vanilla's map does. */
    List<Building> teamBuildings(int team, int block){
        return typed(team, block).toList();
    }

    private BuildingList listOf(int team){
        return teamBuildings[team] != null ? teamBuildings[team] : (teamBuildings[team] = new BuildingList());
    }

    private BuildingList typed(int team, int block){
        BuildingList[] lists = typeBuildings[team];
        if(lists == null || lists.length <= block) lists = typeBuildings[team] = Arrays.copyOf(lists == null ? new BuildingList[0] : lists, block + 1);
        return lists[block] != null ? lists[block] : (lists[block] = new BuildingList());
    }

    /** Vanilla's {@code activeTeams}: teams in the order their first building was indexed. */
    IntSeq activeTeams(){
        return activeTeams;
    }

    private BuildingList flags(int team, BlockFlag flag){
        if(flagged[team] == null) flagged[team] = new BuildingList[BlockFlag.count()];
        BuildingList list = flagged[team][flag.ordinal()];
        return list != null ? list : (flagged[team][flag.ordinal()] = new BuildingList());
    }

    // checkpoints

    /** Writes the lists under their own names into the {@code #buildings} section. */
    void write(CheckpointOut out){
        var keys = new IntSeq();
        var sizes = new IntSeq();
        var members = new ArrayList<Building>();
        for(int t = 0; t < VanillaIds.TEAMS; t++){
            if(flagged[t] == null) continue;
            for(int f = 0; f < flagged[t].length; f++){
                BuildingList list = flagged[t][f];
                if(list == null) continue;
                keys.add(t * BlockFlag.count() + f);
                sizes.add(list.size);
                list.addTo(members);
            }
        }
        out.intSeq("flagKeys", keys).intSeq("flagSizes", sizes).refs("flagged", members.toArray(Building[]::new), members.size());
        out.intSeq("activeTeams", activeTeams);
        var teams = new IntSeq();
        var counts = new IntSeq();
        var hurt = new ArrayList<Building>();
        for(int t = 0; t < VanillaIds.TEAMS; t++){
            if(damaged[t] == null) continue;
            teams.add(t);
            counts.add(damaged[t].size);
            damaged[t].addTo(hurt);
        }
        out.intSeq("damagedTeams", teams).intSeq("damagedSizes", counts).refs("damaged", hurt.toArray(Building[]::new), hurt.size());
        var treeTeams = new IntSeq();
        var shape = new IntSeq();
        var inTrees = new ArrayList<Building>();
        for(int t = 0; t < VanillaIds.TEAMS; t++){
            if(trees[t] == null) continue;
            treeTeams.add(t);
            trees[t].write(shape, inTrees);
        }
        out.intSeq("trees", treeTeams).intSeq("treeShape", shape).refs("treeMembers", inTrees.toArray(Building[]::new), inTrees.size());
        // the team lists: per team its list, then one per block id (a block with an empty list made counts, as its key)
        var listKeys = new IntSeq();
        var listSizes = new IntSeq();
        var listed = new ArrayList<Building>();
        for(int t = 0; t < VanillaIds.TEAMS; t++){
            if(teamBuildings[t] != null){
                listKeys.add(-1 - t);
                listSizes.add(teamBuildings[t].size);
                teamBuildings[t].addTo(listed);
            }
            if(typeBuildings[t] == null) continue;
            for(int b = 0; b < typeBuildings[t].length; b++){
                if(typeBuildings[t][b] == null) continue;
                listKeys.add(t * 65536 + b);
                listSizes.add(typeBuildings[t][b].size);
                typeBuildings[t][b].addTo(listed);
            }
        }
        out.intSeq("teamListKeys", listKeys).intSeq("teamListSizes", listSizes).refs("teamLists", listed.toArray(Building[]::new), listed.size());
    }

    void read(CheckpointIn in){
        clear();
        var keys = new IntSeq();
        var sizes = new IntSeq();
        in.intSeq("flagKeys", keys);
        in.intSeq("flagSizes", sizes);
        Building[] members = in.refs("flagged", Building.class);
        int at = 0;
        for(int i = 0; i < keys.size; i++){
            int key = keys.get(i), team = key / BlockFlag.count(), flag = key % BlockFlag.count();
            if(team >= VanillaIds.TEAMS || members == null || at + sizes.get(i) > members.length) throw new CheckpointException("the flag index is inconsistent");
            if(flagged[team] == null) flagged[team] = new BuildingList[BlockFlag.count()];
            var list = new BuildingList();
            for(int j = 0; j < sizes.get(i); j++) list.add(members[at++]);
            flagged[team][flag] = list;
        }
        in.intSeq("activeTeams", activeTeams);
        var teams = new IntSeq();
        var counts = new IntSeq();
        in.intSeq("damagedTeams", teams);
        in.intSeq("damagedSizes", counts);
        Building[] hurt = in.refs("damaged", Building.class);
        at = 0;
        for(int i = 0; i < teams.size; i++){
            if(hurt == null || at + counts.get(i) > hurt.length) throw new CheckpointException("the damaged index is inconsistent");
            var list = new BuildingList();
            for(int j = 0; j < counts.get(i); j++) list.add(hurt[at++]);
            damaged[teams.get(i)] = list;
        }
        var treeTeams = new IntSeq();
        var shape = new IntSeq();
        in.intSeq("trees", treeTeams);
        in.intSeq("treeShape", shape);
        Building[] inTrees = in.refs("treeMembers", Building.class);
        if(inTrees == null) throw new CheckpointException("the building trees' members are missing");
        int[] cursor = {0, 0};
        for(int i = 0; i < treeTeams.size; i++){
            var tree = new BuildingTree(0, 0, treeWidth, treeHeight);
            tree.read(shape, inTrees, cursor);
            trees[treeTeams.get(i)] = tree;
        }
        if(cursor[0] != shape.size || cursor[1] != inTrees.length) throw new CheckpointException("the building trees have extra entries");
        var listKeys = new IntSeq();
        var listSizes = new IntSeq();
        in.intSeq("teamListKeys", listKeys);
        in.intSeq("teamListSizes", listSizes);
        Building[] listed = in.refs("teamLists", Building.class);
        at = 0;
        for(int i = 0; i < listKeys.size; i++){
            if(listed == null || at + listSizes.get(i) > listed.length) throw new CheckpointException("the team building lists are inconsistent");
            int key = listKeys.get(i);
            BuildingList list = key < 0 ? listOf(-1 - key) : typed(key / 65536, key % 65536);
            for(int j = 0; j < listSizes.get(i); j++) list.add(listed[at++]);
        }
        if(listed != null && at != listed.length) throw new CheckpointException("the team building lists have extra entries");
    }

    void hash(Hasher hasher){
        for(int t = 0; t < VanillaIds.TEAMS; t++){
            if(flagged[t] != null){
                for(int f = 0; f < flagged[t].length; f++){
                    BuildingList list = flagged[t][f];
                    if(list == null || list.size == 0) continue;
                    hasher.i(t).i(f).i(list.size);
                    for(int i = 0; i < list.size; i++) hasher.i(list.items[i].id());
                }
            }
            if(damaged[t] != null && damaged[t].size > 0){
                hasher.i(-1 - t).i(damaged[t].size);
                for(int i = 0; i < damaged[t].size; i++) hasher.i(damaged[t].items[i].id());
            }
        }
        hasher.ints(activeTeams.items, 0, activeTeams.size);
        for(int t = 0; t < VanillaIds.TEAMS; t++){
            if(trees[t] == null) continue;
            hasher.i(t);
            trees[t].hash(hasher);
        }
    }

    /** The team building lists, which only logic's {@code fetch} reads: hashed in its section ({@code LogicUnits}). */
    void hashTeamLists(Hasher hasher){
        for(int t = 0; t < VanillaIds.TEAMS; t++){
            if(teamBuildings[t] != null){
                hasher.i(-1 - t).i(teamBuildings[t].size);
                for(int i = 0; i < teamBuildings[t].size; i++) hasher.i(teamBuildings[t].items[i].id());
            }
            if(typeBuildings[t] == null) continue;
            for(int b = 0; b < typeBuildings[t].length; b++){
                BuildingList list = typeBuildings[t][b];
                if(list == null) continue;
                hasher.i(t).i(b).i(list.size);
                for(int i = 0; i < list.size; i++) hasher.i(list.items[i].id());
            }
        }
    }

    /** Arc's {@code Seq<Building>}, ordered or unordered by the removal used. */
    private static final class BuildingList{
        Building[] items = new Building[4];
        int size;

        BuildingList copy(CopyContext context){
            var out = new BuildingList();
            out.items = new Building[Math.max(4, size)];
            for(int i = 0; i < size; i++) out.items[i] = context.map(items[i]);
            out.size = size;
            return out;
        }

        void add(Building building){
            if(size == items.length) items = Arrays.copyOf(items, size * 2);
            items[size++] = building;
        }

        /** {@code Seq.remove(value)} on an ordered seq: the first occurrence, later entries shift down. */
        void removeOrdered(Building building){
            for(int i = 0; i < size; i++){
                if(items[i] == building){
                    System.arraycopy(items, i + 1, items, i, size - i - 1);
                    items[--size] = null;
                    return;
                }
            }
        }

        /** {@code Seq.remove(value)} on an unordered seq: the last entry takes its place. */
        void removeUnordered(Building building){
            for(int i = 0; i < size; i++){
                if(items[i] == building){
                    removeIndex(i);
                    return;
                }
            }
        }

        private void removeIndex(int i){
            items[i] = items[--size];
            items[size] = null;
        }

        /** {@code Seq.removeAll(pred)} through its iterator on an unordered seq: a swapped-in entry is tested next. */
        void removeIf(java.util.function.Predicate<Building> predicate){
            for(int i = 0; i < size; i++){
                if(predicate.test(items[i])){
                    removeIndex(i);
                    i--;
                }
            }
        }

        void addTo(List<Building> out){
            for(int i = 0; i < size; i++) out.add(items[i]);
        }

        List<Building> toList(){
            return List.of(Arrays.copyOf(items, size));
        }
    }
}
