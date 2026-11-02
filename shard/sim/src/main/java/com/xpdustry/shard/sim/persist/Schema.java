package com.xpdustry.shard.sim.persist;

import java.util.*;

/**
 * The stored layout of one class (or one attachment key): its fields' names and types in write order. The first
 * object of a class written into a checkpoint records it; every later one must write the same fields in the same
 * order, so there are no conditional fields (write a null instead). The reader checks each field it reads against
 * the stored schema.
 *
 * @param key the binary class name, or {@code @} and the attachment key's name
 */
public record Schema(String key, List<Field> fields){
    public Schema{
        fields = List.copyOf(fields);
    }

    /** The field names, in order. */
    public List<String> names(){
        return fields.stream().map(Field::name).toList();
    }

    public record Field(String name, FieldType type){
        public Field{
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(type, "type");
        }

        @Override
        public String toString(){
            return name + ":" + type.name().toLowerCase(Locale.ROOT);
        }
    }

    /** A schema while it is recorded or checked, with interned names so checks compare by identity first. */
    static final class Builder{
        final String key;
        String[] names = new String[8];
        FieldType[] types = new FieldType[8];
        int size;
        boolean frozen;

        Builder(String key){
            this.key = key;
        }

        Builder(Schema schema){
            this(schema.key());
            for(Field field : schema.fields()) add(field.name().intern(), field.type());
            frozen = true;
        }

        void add(String name, FieldType type){
            for(int i = 0; i < size; i++){
                if(names[i].equals(name)) throw new CheckpointException(key + " writes the field " + name + " twice");
            }
            if(size == names.length){
                names = Arrays.copyOf(names, size * 2);
                types = Arrays.copyOf(types, size * 2);
            }
            names[size] = name;
            types[size] = type;
            size++;
        }

        boolean matches(int index, String name, FieldType type){
            return index < size && types[index] == type && (names[index] == name || names[index].equals(name));
        }

        String describe(int index){
            return index < size ? names[index] + ":" + types[index].name().toLowerCase(Locale.ROOT) : "nothing";
        }

        Schema build(){
            var fields = new ArrayList<Field>(size);
            for(int i = 0; i < size; i++) fields.add(new Field(names[i], types[i]));
            return new Schema(key, fields);
        }
    }
}
