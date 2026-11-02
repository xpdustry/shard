package com.xpdustry.shard.sim.persist;

import com.xpdustry.shard.common.registry.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.entity.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.world.*;

import java.lang.reflect.*;
import java.util.*;

/**
 * Finds the {@link Plan} for each class, once, and the registered {@link ClassCodec}s and {@link AttachmentCodec}s.
 * The rules, in order:
 * <ul>
 *   <li>the building modules have built-in plans;</li>
 *   <li>an entity is its foundation layers ({@code Entity}, then {@code Building} or {@code Unit}) plus its own
 *   state;</li>
 *   <li>a record is its components;</li>
 *   <li>own state is the class's {@link Checkpointable} methods, else its registered {@link ClassCodec}, else
 *   nothing if it has no fields to store, else an error naming them.</li>
 * </ul>
 */
final class Codecs{
    private static final ClassValue<Plan> PLANS = new ClassValue<>(){
        @Override
        protected Plan computeValue(Class<?> type){
            return build(type);
        }
    };

    static Plan plan(Class<?> type){
        return PLANS.get(type);
    }

    private static Plan build(Class<?> type){
        if(type.isHidden() || type.isSynthetic() || type.isAnonymousClass() || type.isLocalClass()){
            return new Plan.OwnPlan(type, new Plan.Missing(type.getName() + " is a lambda, anonymous or local class, which a"
                + " checkpoint can't name; use a record or a named class"), true);
        }
        if(type == ItemModule.class) return new Foundation.Items();
        if(type == LiquidModule.class) return new Foundation.Liquids();
        if(type == PowerModule.class) return new Foundation.Power();
        if(Entity.class.isAssignableFrom(type)){
            var layers = new ArrayList<Plan.Layer<?>>();
            layers.add(Foundation.ENTITY);
            Class<?> base = Entity.class;
            if(Building.class.isAssignableFrom(type)){
                layers.add(Foundation.BUILDING);
                base = Building.class;
            }else if(Unit.class.isAssignableFrom(type)){
                layers.add(Foundation.UNIT);
                base = Unit.class;
            }
            return new Plan.EntityPlan(type, layers, own(type, base));
        }
        if(type.isRecord()) return new RecordPlan(type);
        return new Plan.OwnPlan(type, own(type, Object.class), !Copyable.class.isAssignableFrom(type));
    }

    /** The state of {@code type} declared below {@code base}. */
    static Plan.Own own(Class<?> type, Class<?> base){
        if(Checkpointable.class.isAssignableFrom(type)) return new Plan.Self();
        ClassCodec<?> codec = Registered.CLASSES.get(type.getName());
        if(codec != null && codec.type() == type) return new Plan.ByCodec(codec);
        List<String> fields = stateFields(type, base);
        return fields.isEmpty() ? new Plan.Stateless() : new Plan.Missing(type, fields);
    }

    /**
     * The instance fields declared by {@code type} and its superclasses below {@code base}, minus synthetic and
     * {@link Derived} ones: what own state must store.
     */
    static List<String> stateFields(Class<?> type, Class<?> base){
        var out = new ArrayList<String>();
        for(Class<?> at = type; at != null && at != base && at != Object.class; at = at.getSuperclass()){
            for(Field field : at.getDeclaredFields()){
                if(Modifier.isStatic(field.getModifiers()) || field.isSynthetic() || field.isAnnotationPresent(Derived.class)) continue;
                out.add(at.getSimpleName() + "." + field.getName());
            }
        }
        return out;
    }

    /**
     * A new instance through the public no-argument constructor, by reflection: any state class, registered or not.
     * Constructor errors propagate; any other failure is a {@link CheckpointException}.
     */
    static Object construct(Class<?> type){
        try{
            return type.getConstructor().newInstance();
        }catch(InvocationTargetException e){
            if(e.getCause() instanceof Error error) throw error;
            throw restoreFailure(type, e.getCause());
        }catch(ReflectiveOperationException | RuntimeException e){
            throw restoreFailure(type, e);
        }
    }

    private static CheckpointException restoreFailure(Class<?> type, Throwable cause){
        return new CheckpointException(type.getName() + " can't be restored: it needs a public no-argument constructor"
            + " or a ClassCodec that creates it", cause);
    }

    @SuppressWarnings("unchecked")
    static AttachmentCodec<Object> attachment(String key){
        AttachmentCodec<?> codec = Registered.ATTACHMENTS.get(key);
        if(codec == null) throw new CheckpointException("the authoritative attachment " + key + " has no registered AttachmentCodec");
        return (AttachmentCodec<Object>)codec;
    }

    /** The registered codec classes, read once. */
    private static final class Registered{
        static final TreeMap<String, ClassCodec<?>> CLASSES = new TreeMap<>();
        static final TreeMap<String, AttachmentCodec<?>> ATTACHMENTS = new TreeMap<>();

        static{
            for(ClassCodec<?> codec : Registries.instances(ClassCodec.class)){
                ClassCodec<?> previous = CLASSES.putIfAbsent(codec.type().getName(), codec);
                if(previous != null){
                    throw new IllegalStateException(codec.type().getName() + " has two ClassCodecs: " + previous.getClass().getName()
                        + " and " + codec.getClass().getName());
                }
            }
            for(AttachmentCodec<?> codec : Registries.instances(AttachmentCodec.class)){
                AttachmentCodec<?> previous = ATTACHMENTS.putIfAbsent(codec.key().name(), codec);
                if(previous != null){
                    throw new IllegalStateException("attachment " + codec.key().name() + " has two AttachmentCodecs: "
                        + previous.getClass().getName() + " and " + codec.getClass().getName());
                }
            }
        }
    }

    private Codecs(){}
}
