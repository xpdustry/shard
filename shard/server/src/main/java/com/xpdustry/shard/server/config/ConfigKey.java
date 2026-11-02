package com.xpdustry.shard.server.config;

import java.util.*;
import java.util.regex.*;

/**
 * One config key, defined once in {@link ConfigKeys}: its dotted YAML path, type, default, scope, reload policy and
 * documentation. The schema, the reference, the environment name and the reload diff all come from here.
 *
 * @param path the dotted path in YAML, lower case with dashes ({@code persistence.autosave-interval})
 * @param secret redacted in {@code config explain} and logs
 */
public record ConfigKey<T>(String path, ConfigType<T> type, T defaultValue, Scope scope, Reload reload, boolean secret,
                           String description){
    static final Pattern PATH = Pattern.compile("[a-z][a-z0-9]*(-[a-z0-9]+)*(\\.[a-z][a-z0-9]*(-[a-z0-9]+)*)+");

    public ConfigKey{
        if(!PATH.matcher(path).matches()) throw new IllegalArgumentException("bad config path: " + path);
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(defaultValue, "defaultValue");
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(reload, "reload");
        Objects.requireNonNull(description, "description");
    }

    /** Where a key may be set. */
    public enum Scope{
        /** Host-wide: {@code shard.yml} and {@code SHARD__...} only. */
        HOST,
        /** Also per world: {@code worlds/<id>.yml} and {@code SHARD__WORLDS__<ID>__...} override the host value. */
        WORLD
    }

    /** What a changed value needs before it takes effect (note 34 "Shard configuration"). */
    public enum Reload{
        /** {@link ConfigService#reload()} applies it: services read the new value from the next config generation. */
        LIVE,
        /** It is read once at startup (sockets, the database, log sinks). A reload reports it as pending. */
        RESTART
    }

    /** The environment variable for the host value: {@code SHARD__PERSISTENCE__AUTOSAVE_INTERVAL}. */
    public String env(){
        return ConfigLoader.ENV_PREFIX + envSuffix();
    }

    /** The environment variable for one world's value: {@code SHARD__WORLDS__<ID>__PERSISTENCE__AUTOSAVE_INTERVAL}. */
    public String env(String world){
        return ConfigLoader.ENV_PREFIX + "WORLDS__" + ConfigLoader.envWorld(world) + "__" + envSuffix();
    }

    String envSuffix(){
        return path.toUpperCase(Locale.ROOT).replace('-', '_').replace(".", "__");
    }

    /** The value as YAML would write it, or {@code <redacted>} for a secret. */
    public String display(Object value){
        return secret ? "<redacted>" : type.formatAny(value);
    }

    @Override
    public String toString(){
        return path;
    }

    static <T> Builder<T> key(String path, ConfigType<T> type, T defaultValue){
        return new Builder<>(path, type, defaultValue);
    }

    static final class Builder<T>{
        final String path;
        final ConfigType<T> type;
        final T defaultValue;
        Scope scope = Scope.HOST;
        Reload reload = Reload.LIVE;
        boolean secret;

        Builder(String path, ConfigType<T> type, T defaultValue){
            this.path = path;
            this.type = type;
            this.defaultValue = defaultValue;
        }

        Builder<T> world(){
            scope = Scope.WORLD;
            return this;
        }

        Builder<T> restart(){
            reload = Reload.RESTART;
            return this;
        }

        Builder<T> secret(){
            secret = true;
            return this;
        }

        ConfigKey<T> doc(String description){
            return new ConfigKey<>(path, type, defaultValue, scope, reload, secret, description);
        }
    }
}
