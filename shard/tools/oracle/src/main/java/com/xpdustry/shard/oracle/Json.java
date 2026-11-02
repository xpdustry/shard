package com.xpdustry.shard.oracle;

import tools.jackson.core.json.*;
import tools.jackson.core.util.*;
import tools.jackson.core.util.Separators.*;
import tools.jackson.databind.*;
import tools.jackson.databind.json.*;

import java.io.*;
import java.nio.file.*;

/**
 * Pretty, stable JSON for the views and dumps: maps keep their order, records keep component order, objects get one
 * field per line, arrays stay on one line unless they hold objects.
 */
public final class Json{
    private static final JsonMapper mapper = JsonMapper.builder()
        .disable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
        .enable(SerializationFeature.INDENT_OUTPUT)
        .enable(JsonWriteFeature.WRITE_NAN_AS_STRINGS)
        .defaultPrettyPrinter(new DefaultPrettyPrinter(Separators.createDefaultInstance().withObjectNameValueSpacing(Spacing.AFTER)))
        .build();

    /** Writes a view (records, maps, lists, scalars) as pretty JSON. */
    public static String write(Object view){
        return mapper.writeValueAsString(view);
    }

    public static void write(Path file, Object view){
        try{
            Files.createDirectories(file.toAbsolutePath().getParent());
            Files.writeString(file, write(view) + "\n");
        }catch(IOException e){
            throw new UncheckedIOException(e);
        }
    }

    /** Parses JSON into maps, lists and scalars, e.g. to read the dumps in data/vanilla-v160.7. */
    public static Object read(String json){
        return mapper.readValue(json, Object.class);
    }

    public static Object read(Path file){
        try{
            return read(Files.readString(file));
        }catch(IOException e){
            throw new UncheckedIOException(e);
        }
    }

    private Json(){}
}
