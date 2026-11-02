package com.xpdustry.shard.oracle.dump;

import arc.func.*;
import mindustry.gen.*;
import mindustry.world.*;
import com.xpdustry.shard.oracle.*;

import java.util.*;

import static mindustry.Vars.*;

/** entities.json and buildings.json: class ids and the field order of every serializer, from bytecode. */
final class EntityDump{
    static final String writeDesc = "(Larc/util/io/Writes;)V", readDesc = "(Larc/util/io/Reads;B)V";

    @SuppressWarnings("rawtypes")
    static Object entities(){
        //class id -> class, from the generated EntityMapping (creating an entity does not add it)
        Map<Integer, Class<?>> byId = new TreeMap<>();
        for(int id = 0; id < EntityMapping.idMap.length; id++){
            Prov<?> prov = EntityMapping.idMap[id];
            if(prov != null) byId.put(id, prov.get().getClass());
        }
        Map<String, List<String>> names = new TreeMap<>();
        for(var e : EntityMapping.nameMap.entries()){
            names.computeIfAbsent(e.value.get().getClass().getName(), k -> new ArrayList<>()).add(e.key);
        }
        names.values().forEach(Collections::sort);

        List<Object> classes = new ArrayList<>();
        for(var e : byId.entrySet()){
            Class<?> type = e.getValue();
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", e.getKey());
            entry.put("class", type.getName());
            entry.put("names", names.getOrDefault(type.getName(), List.of()));
            entry.put("syncc", Syncc.class.isAssignableFrom(type));
            Layouts.Layout sync = Layouts.of(type, "writeSync", writeDesc);
            if(sync != null) entry.put("writeSync", Views.map("declaredIn", sync.declaredIn(), "ops", sync.view()));
            Layouts.Layout save = Layouts.of(type, "write", writeDesc);
            if(save != null) entry.put("write", Views.map("declaredIn", save.declaredIn(), "revision", save.revision(), "ops", save.view()));
            classes.add(entry);
        }
        return Views.map(
            "note", "Class ids from mindustry.gen.EntityMapping.idMap; names from its nameMap (unit types and other names that map to "
                + "the class). writeSync is the entity snapshot layout (after i32 id, u8 class id); write is the save/world stream "
                + "layout (after u8 class id, i32 id), starting with its i16 revision. ops are in wire order: 'field' is the field "
                + "written, 'codec' the call that writes it; 'loop' marks per-element writes.",
            "count", classes.size(),
            "classes", classes);
    }

    static Object buildings(){
        Map<Class<?>, List<String>> blocksByClass = new LinkedHashMap<>();
        List<Object> blocks = new ArrayList<>();
        for(Block block : content.blocks()){
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", (int)block.id);
            entry.put("name", block.name);
            entry.put("class", block.getClass().getName());
            if(block.hasBuilding()){
                Building build = block.newBuilding();
                entry.put("building", build.getClass().getName());
                entry.put("version", (int)build.version());
                blocksByClass.computeIfAbsent(build.getClass(), k -> new ArrayList<>()).add(block.name);
            }else{
                entry.put("building", null);
            }
            blocks.add(entry);
        }

        //every building class and its superclasses up to mindustry.gen.Building
        Map<String, Object> classes = new TreeMap<>();
        for(Class<?> leaf : blocksByClass.keySet()){
            for(Class<?> type = leaf; type != null && Building.class.isAssignableFrom(type); type = type.getSuperclass()){
                if(classes.containsKey(type.getName())) continue;
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("superclass", type == Building.class ? null : type.getSuperclass().getName());
                List<String> declares = new ArrayList<>();
                for(String[] m : new String[][]{{"version", "()B"}, {"write", writeDesc}, {"read", readDesc}, {"writeSync", writeDesc},
                    {"readSync", readDesc}, {"writeAll", writeDesc}, {"readAll", readDesc}}){
                    if(Layouts.declares(type, m[0], m[1])) declares.add(m[0]);
                }
                entry.put("declares", declares);
                if(blocksByClass.containsKey(type)){
                    entry.put("version", (int)content.block(blocksByClass.get(type).getFirst()).newBuilding().version());
                    entry.put("blocks", blocksByClass.get(type));
                }
                Layouts.Layout write = Layouts.declared(type, "write", writeDesc);
                if(write != null) entry.put("write", write.view());
                classes.put(type.getName(), entry);
            }
        }

        Layouts.Layout base = Layouts.declared(Building.class, "writeBase", writeDesc);
        return Views.map(
            "note", "A building in a world stream or save: i32 chunk length, u8 version(), writeBase, then write() of the building "
                + "class chain (each override calls super.write first). Block snapshots use writeSync, which is writeAll unless "
                + "overridden. 'declares' lists the serialization methods a class itself overrides. write ops are recovered from "
                + "hand-written bytecode: 'loop' and 'if' mark repeated and conditional writes; Golden.building() gives exact bytes.",
            "writeBase", base == null ? null : base.view(),
            "blocks", blocks,
            "classes", classes);
    }

    private EntityDump(){}
}
