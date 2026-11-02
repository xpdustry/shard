package com.xpdustry.shard.content.epoch;

import com.xpdustry.shard.common.io.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.content.bake.*;
import com.xpdustry.shard.content.schema.*;

import java.io.*;
import java.util.*;

/**
 * The content hash: SHA-256 over every baked definition, in type and id order, as hex. It covers names, the
 * added flag, kinds, and every slot (hidden ones too) with its field name, so equal hashes mean equal content.
 * Checkpoints record it and patches are cached by it.
 */
public final class ContentHash{
    private ContentHash(){
    }

    public static String of(Baked baked){
        var digest = new Sha256();
        try(var out = new DataOutputStream(digest)){
            for(ContentType type : ContentType.values()){
                List<Struct> structs = baked.of(type);
                List<String> names = baked.names(type);
                int vanilla = baked.loaded().pack().ids().of(type).size();
                out.writeUTF(type.yamlName());
                out.writeInt(structs.size());
                for(int id = 0; id < structs.size(); id++){
                    out.writeUTF(names.get(id));
                    out.writeBoolean(id >= vanilla);
                    write(out, structs.get(id));
                }
            }
        }catch(IOException e){
            throw new UncheckedIOException(e);
        }
        return Sha256.hex(digest.digest());
    }

    private static void write(DataOutputStream out, Object value) throws IOException{
        switch(value){
            case null -> out.writeByte(0);
            case Boolean b -> {
                out.writeByte(1);
                out.writeBoolean(b);
            }
            case Integer i -> {
                out.writeByte(2);
                out.writeInt(i);
            }
            case Long l -> {
                out.writeByte(3);
                out.writeLong(l);
            }
            case Float f -> {
                out.writeByte(4);
                out.writeInt(Float.floatToIntBits(f));
            }
            case Double d -> {
                out.writeByte(9);
                out.writeLong(Double.doubleToLongBits(d));
            }
            case String s -> {
                out.writeByte(5);
                out.writeUTF(s);
            }
            case Struct s -> {
                out.writeByte(6);
                out.writeUTF(s.kind().key());
                List<FieldSpec> slots = s.kind().slots();
                out.writeInt(slots.size());
                for(FieldSpec f : slots){
                    out.writeUTF(f.name());
                    write(out, s.get(f));
                }
            }
            case List<?> l -> {
                out.writeByte(7);
                out.writeInt(l.size());
                for(Object o : l) write(out, o);
            }
            case Map<?, ?> m -> {
                out.writeByte(8);
                out.writeInt(m.size());
                for(var e : m.entrySet()){
                    out.writeUTF((String)e.getKey());
                    write(out, e.getValue());
                }
            }
            default -> throw new IllegalArgumentException("unhashable value " + value.getClass().getName());
        }
    }
}
