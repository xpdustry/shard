package com.xpdustry.shard.tools.extract;

import arc.func.*;
import arc.graphics.*;
import arc.math.*;
import mindustry.ai.*;
import mindustry.content.*;
import mindustry.entities.*;
import mindustry.entities.part.DrawPart.*;
import mindustry.gen.*;
import mindustry.graphics.*;
import mindustry.world.meta.*;

import java.lang.reflect.*;
import java.util.*;

/**
 * Names for vanilla objects that YAML refers to by name: the kind key of a class, the shared statics ({@code Fx},
 * {@code Sounds}, {@code Interp}…) and the lambdas ("hooks").
 */
final class Names{
    /** Registries of named statics, by YAML prefix, in declaration order. */
    static final Map<String, Class<?>> registries = new LinkedHashMap<>();

    static{
        registries.put("fx", Fx.class);
        registries.put("sound", Sounds.class);
        registries.put("interp", Interp.class);
        registries.put("progress", PartProgress.class);
        registries.put("blending", Blending.class);
        registries.put("visibility", BuildVisibility.class);
        registries.put("cache", CacheLayer.class);
        registries.put("sort", UnitSorts.class);
        registries.put("cost", ControlPathfinder.class);
    }

    /** Named static → {@code prefix:name}. Identity: two statics may be equal but not the same object. */
    private final IdentityHashMap<Object, String> statics = new IdentityHashMap<>();
    /** Prefix → names in declaration order. */
    final Map<String, List<String>> registryNames = new LinkedHashMap<>();
    /** Lambda class → hook name. Lambdas from one call site share their (hidden) class. */
    private final Map<Class<?>, String> hooks = new HashMap<>();
    private final Map<String, Integer> hookCounts = new TreeMap<>();

    Names(){
        registries.forEach((prefix, type) -> {
            List<String> names = new ArrayList<>();
            for(Field f : type.getFields()){
                if(!Modifier.isStatic(f.getModifiers()) || f.getType().isPrimitive() || f.getType() == String.class) continue;
                Object v;
                try{
                    v = f.get(null);
                }catch(IllegalAccessException e){
                    throw new IllegalStateException(e);
                }
                if(v == null || v instanceof Number || v instanceof Collection<?> || v instanceof arc.struct.Seq<?> || v.getClass().isArray()) continue;
                if(!registryType(prefix).isInstance(v)) continue;
                if(statics.putIfAbsent(v, prefix + ":" + f.getName()) == null) names.add(f.getName());
            }
            registryNames.put(prefix, names);
        });
    }

    /** The type a registry's statics have, so that helper statics of other types in the same class are skipped. */
    static Class<?> registryType(String prefix){
        return switch(prefix){
            case "fx" -> Effect.class;
            case "sound" -> arc.audio.Sound.class;
            case "interp" -> Interp.class;
            case "progress" -> PartProgress.class;
            case "blending" -> Blending.class;
            case "visibility" -> BuildVisibility.class;
            case "cache" -> CacheLayer.class;
            case "sort" -> Object.class;
            case "cost" -> Pathfinder.PathCost.class;
            default -> throw new IllegalArgumentException(prefix);
        };
    }

    /** {@code fx:none}, or null when {@code value} isn't a named static. */
    String named(Object value){
        return statics.get(value);
    }

    /**
     * Names a hook from a probe: every lambda of {@code sample}'s class gets this name, or {@code name#2}… when
     * another lambda class already has it. Returns the name given.
     */
    String hook(Object sample, String name){
        if(sample == null) throw new IllegalStateException("no sample for hook " + name);
        String previous = hooks.get(sample.getClass());
        if(previous != null) return previous;
        String unique = name;
        for(int n = 2; hooks.containsValue(unique); n++) unique = name + "#" + n;
        hooks.put(sample.getClass(), unique);
        return unique;
    }

    /**
     * Names a hook several lambda classes implement alike (one per {@code UnitTypes} initializer that copies the same
     * lambda): each sample's class gets {@code name}, which no other hook may take.
     */
    String sharedHook(Object sample, String name){
        String previous = hooks.putIfAbsent(sample.getClass(), name);
        if(previous != null && !previous.equals(name)) throw new IllegalStateException(sample.getClass() + " is already hook " + previous);
        return name;
    }

    /**
     * {@code <DefiningClass>#n} for a lambda, numbered per defining class in the order this extractor meets them; or
     * the name a probe gave its class. Comparisons across runs ignore the {@code #n}.
     */
    String hook(Object lambda){
        return hooks.computeIfAbsent(lambda.getClass(), type -> {
            String owner = definingClass(type);
            int n = hookCounts.merge(owner, 1, Integer::sum) - 1;
            return owner + "#" + n;
        });
    }

    static boolean isLambda(Object value){
        Class<?> type = value.getClass();
        return type.isHidden() || type.isSynthetic() || type.getName().contains("$$Lambda");
    }

    static String definingClass(Class<?> lambda){
        String name = lambda.getName();
        int cut = name.indexOf("$$Lambda");
        if(cut >= 0) name = name.substring(0, cut);
        return name.substring(name.lastIndexOf('.') + 1);
    }

    /** The class a kind stands for: anonymous {@code new X(){{ }}} subclasses are X. */
    static Class<?> concrete(Class<?> type){
        while(type.isAnonymousClass()) type = type.getSuperclass();
        return type;
    }

    /** {@code ItemTurret} → {@code item-turret}, {@code BasicBulletType} → {@code basic-bullet-type}. */
    static String kebab(String simpleName){
        var out = new StringBuilder();
        for(int i = 0; i < simpleName.length(); i++){
            char c = simpleName.charAt(i);
            if(Character.isUpperCase(c)){
                boolean afterLower = i > 0 && !Character.isUpperCase(simpleName.charAt(i - 1));
                boolean acronymEnd = i > 0 && i + 1 < simpleName.length() && Character.isUpperCase(simpleName.charAt(i - 1))
                    && Character.isLowerCase(simpleName.charAt(i + 1));
                if(out.length() > 0 && (afterLower || acronymEnd)) out.append('-');
                out.append(Character.toLowerCase(c));
            }else if(c == '$' || c == '_'){
                out.append('-');
            }else{
                out.append(c);
            }
        }
        return out.toString();
    }

    /** The kind key of a class: the kebab-case simple name. */
    static String kind(Class<?> type){
        return kebab(concrete(type).getSimpleName());
    }

    /** Field names of statics, kebab-cased: {@code Bullets.damageLightning} → {@code damage-lightning}. */
    static String staticId(String fieldName){
        return kebab(fieldName);
    }

    /** True for functional interfaces from Arc and vanilla (lambdas live in such fields). */
    static boolean functional(Class<?> type){
        if(!type.isInterface()) return false;
        if(type.isAnnotationPresent(FunctionalInterface.class)) return true;
        String pkg = type.getPackageName();
        if(pkg.equals("arc.func")) return true;
        int abstracts = 0;
        for(Method m : type.getMethods()) if(Modifier.isAbstract(m.getModifiers())) abstracts++;
        return abstracts == 1;
    }

    static boolean isProv(Object value){
        return value instanceof Prov<?>;
    }
}
