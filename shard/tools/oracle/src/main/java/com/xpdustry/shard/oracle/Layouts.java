package com.xpdustry.shard.oracle;

import java.io.*;
import java.lang.classfile.*;
import java.lang.classfile.constantpool.*;
import java.lang.classfile.instruction.*;
import java.lang.constant.*;
import java.util.*;

/**
 * Serialization layouts recovered from vanilla's bytecode with the ClassFile API: the order in which a
 * {@code write}/{@code writeSync}/{@code handled} method writes or reads its fields. The jar has no {@code revisions}
 * folder, so this is the source of truth for field order.
 *
 * <p>Each {@link Op} is one call that takes a {@code Writes} or {@code Reads} ({@code Writes.f}, {@code TypeIO.writeTile},
 * {@code items.write}, {@code super.write}, ...), with the {@code this} field it serializes when there is one. Generated
 * entity and packet code is straight-line, so its layouts are exact. Hand-written building code has loops and branches:
 * those ops are flagged {@code loop}/{@code condition}, and the golden bytes ({@link Golden}) are the ground truth.
 */
public final class Layouts{
    static final String writes = "arc/util/io/Writes", reads = "arc/util/io/Reads";
    private static final Map<String, ClassModel> models = new HashMap<>();

    /**
     * One serialized value.
     * @param field the {@code this} field it writes, or reads into; null for computed values
     * @param codec the call, {@code Owner.method} ({@code Writes.f}, {@code TypeIO.writeTile}, {@code super.write}, ...)
     * @param constant the constant written, e.g. a save revision
     * @param loop inside a loop: repeated per element
     * @param condition set when inside a branch; {@code "net.client()"}/{@code "net.server()"} for the side checks of
     * generated packets, otherwise {@code "?"}
     */
    public record Op(String field, String codec, Object constant, boolean loop, String condition,
                     String owner, String method, String descriptor){
        /** A compact form for the dumps. */
        public Map<String, Object> view(){
            Map<String, Object> out = new LinkedHashMap<>();
            if(field != null) out.put("field", field);
            out.put("codec", codec);
            if(constant != null) out.put("constant", constant);
            if(loop) out.put("loop", true);
            if(condition != null) out.put("if", condition);
            return out;
        }
    }

    /** The ops of a method and the class that declares it. */
    public record Layout(String declaredIn, String method, List<Op> ops){
        /** The distinct fields, in wire order. */
        public List<String> fields(){
            return ops.stream().map(Op::field).filter(Objects::nonNull).distinct().toList();
        }

        /** The first constant {@code Writes.s}: the save revision of generated entity code. */
        public Integer revision(){
            return ops.isEmpty() || ops.getFirst().constant() == null || !ops.getFirst().codec().equals("Writes.s") ? null : ((Number)ops.getFirst().constant()).intValue();
        }

        public List<Object> view(){
            return new ArrayList<>(ops.stream().map(Op::view).toList());
        }
    }

    /**
     * The layout of {@code method(descriptor)} for {@code type}, read from the nearest class in its superclass chain
     * that declares it. Null when no class declares it.
     */
    public static Layout of(Class<?> type, String method, String descriptor){
        for(Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()){
            Layout layout = declared(c, method, descriptor);
            if(layout != null) return layout;
        }
        return null;
    }

    /** The layout of a method declared by exactly this class, or null. */
    public static Layout declared(Class<?> type, String method, String descriptor){
        ClassModel model = model(type);
        if(model == null) return null;
        for(MethodModel m : model.methods()){
            if(m.methodName().equalsString(method) && m.methodType().equalsString(descriptor)){
                return new Layout(type.getName(), method, m.code().map(code -> ops(model, code)).orElse(List.of()));
            }
        }
        return null;
    }

    /** Whether exactly this class declares the method. */
    public static boolean declares(Class<?> type, String method, String descriptor){
        ClassModel model = model(type);
        return model != null && model.methods().stream().anyMatch(m -> m.methodName().equalsString(method) && m.methodType().equalsString(descriptor));
    }

    /** The parsed class file, from the class loader that loaded the class. */
    public static synchronized ClassModel model(Class<?> type){
        String resource = type.getName().replace('.', '/') + ".class";
        return models.computeIfAbsent(resource, r -> {
            ClassLoader loader = type.getClassLoader() == null ? ClassLoader.getSystemClassLoader() : type.getClassLoader();
            try(InputStream in = loader.getResourceAsStream(r)){
                return in == null ? null : ClassFile.of().parse(in.readAllBytes());
            }catch(IOException e){
                throw new UncheckedIOException(e);
            }
        });
    }

    static List<Op> ops(ClassModel model, CodeModel code){
        List<CodeElement> elements = code.elementList();
        Map<Label, Integer> labels = new HashMap<>();
        for(int i = 0; i < elements.size(); i++){
            if(elements.get(i) instanceof LabelTarget t) labels.put(t.label(), i);
        }

        //loops: a backward jump covers [target, jump]; its exit is the first element after the jump
        List<int[]> loops = new ArrayList<>();
        Set<Integer> loopExits = new HashSet<>();
        for(int i = 0; i < elements.size(); i++){
            if(elements.get(i) instanceof BranchInstruction b && labels.get(b.target()) <= i){
                loops.add(new int[]{labels.get(b.target()), i});
                loopExits.add(nextInstruction(elements, i + 1));
            }
        }
        //branches: a forward conditional jump skips (jump, target); loop exits do not count
        List<int[]> branches = new ArrayList<>();
        List<String> conditions = new ArrayList<>();
        //the branches that guard serializer calls are statements (if blocks), not ternaries inside an argument
        Set<Integer> guards = new HashSet<>();
        for(int i = 0; i < elements.size(); i++){
            if(elements.get(i) instanceof BranchInstruction b && b.opcode() != Opcode.GOTO && b.opcode() != Opcode.GOTO_W){
                int target = labels.get(b.target());
                if(target > i && !loopExits.contains(nextInstruction(elements, target))){
                    branches.add(new int[]{i, target});
                    conditions.add(sideCheck(elements, i, b.opcode()));
                }
                if(target > i && elements.subList(i, target).stream().anyMatch(Layouts::serializes)) guards.add(i);
            }
        }

        String self = model.thisClass().asInternalName();
        List<Op> ops = new ArrayList<>();
        String pendingField = null;
        Object pendingConstant = null;
        Op awaitingStore = null;
        int awaitingIndex = -1;
        CodeElement previous = null;

        for(int i = 0; i < elements.size(); i++){
            CodeElement e = elements.get(i);
            boolean guard = guards.contains(i);
            switch(e){
                case FieldInstruction f when f.opcode() == Opcode.GETFIELD -> {
                    //this$0 is the outer instance (the Block of a Building): its fields are config, not state
                    if(pendingField == null && isLoadThis(previous) && !f.name().stringValue().startsWith("this$")) pendingField = f.name().stringValue();
                }
                //a field read by an earlier statement or an if condition is not what the next call writes
                case StoreInstruction st -> pendingField = null;
                case BranchInstruction b when guard -> pendingField = null;
                case FieldInstruction f when f.opcode() == Opcode.PUTFIELD -> {
                    if(awaitingStore != null && f.owner().asInternalName().equals(self)){
                        ops.set(awaitingIndex, withField(awaitingStore, f.name().stringValue()));
                        awaitingStore = null;
                    }
                }
                case ConstantInstruction c -> pendingConstant = c.constantValue();
                case InvokeInstruction inv -> {
                    String owner = inv.owner().asInternalName();
                    String name = inv.name().stringValue();
                    String desc = inv.type().stringValue();
                    boolean writing = desc.contains("L" + writes + ";") || owner.equals(writes);
                    boolean reading = desc.contains("L" + reads + ";") || owner.equals(reads);
                    if(serializes(inv)){
                        String condition = null;
                        for(int b = 0; b < branches.size(); b++){
                            if(i > branches.get(b)[0] && i < branches.get(b)[1]) condition = conditions.get(b);
                        }
                        boolean loop = false;
                        for(int[] l : loops) loop |= i >= l[0] && i <= l[1];
                        Object constant = owner.equals(writes) && pendingField == null && previous instanceof ConstantInstruction ? pendingConstant : null;
                        Op op = new Op(pendingField, codec(inv, self), constant, loop, condition, owner, name, desc);
                        ops.add(op);
                        if(reading && !writing && pendingField == null){
                            awaitingStore = op;
                            awaitingIndex = ops.size() - 1;
                        }
                        pendingField = null;
                    }else if(desc.endsWith(")V")){
                        //a statement that serializes nothing, e.g. BAIS.setBytes(DATA)
                        pendingField = null;
                    }
                }
                default -> {}
            }
            if(e instanceof Instruction) previous = e;
        }
        return ops;
    }

    /** A call that writes to a {@code Writes} or reads from a {@code Reads}. */
    private static boolean serializes(CodeElement e){
        if(!(e instanceof InvokeInstruction inv) || inv.name().equalsString("<init>")) return false;
        String owner = inv.owner().asInternalName(), desc = inv.type().stringValue();
        return owner.equals(writes) || owner.equals(reads) || desc.contains("L" + writes + ";") || desc.contains("L" + reads + ";");
    }

    private static Op withField(Op op, String field){
        return new Op(field, op.codec(), op.constant(), op.loop(), op.condition(), op.owner(), op.method(), op.descriptor());
    }

    private static int nextInstruction(List<CodeElement> elements, int from){
        for(int i = from; i < elements.size(); i++){
            if(elements.get(i) instanceof Instruction) return i;
        }
        return elements.size();
    }

    /** The {@code if(net.client())}/{@code if(net.server())} checks generated packets use for Loc.both player arguments. */
    private static String sideCheck(List<CodeElement> elements, int branch, Opcode opcode){
        for(int i = branch - 1; i >= 0; i--){
            if(!(elements.get(i) instanceof Instruction)) continue;
            if(elements.get(i) instanceof InvokeInstruction inv && inv.owner().asInternalName().equals("mindustry/net/Net")
            && (inv.name().equalsString("client") || inv.name().equalsString("server")) && opcode == Opcode.IFEQ){
                return "net." + inv.name().stringValue() + "()";
            }
            return "?";
        }
        return "?";
    }

    private static boolean isLoadThis(CodeElement e){
        return e instanceof LoadInstruction l && l.slot() == 0 && l.typeKind() == TypeKind.REFERENCE;
    }

    private static String codec(InvokeInstruction inv, String self){
        String owner = inv.owner().asInternalName();
        String name = inv.name().stringValue();
        if(inv.opcode() == Opcode.INVOKESPECIAL && !owner.equals(self)) return "super." + name;
        String codec = owner.substring(owner.lastIndexOf('/') + 1).replace('$', '.') + "." + name;
        //Writes.b(byte[]) and Writes.b(int) differ on the wire: name the array overloads
        String desc = inv.type().stringValue();
        if((owner.equals(writes) || owner.equals(reads)) && desc.contains("[")){
            codec += "(" + String.join(", ", parameterTypes(desc)) + ")";
        }
        return codec;
    }

    /** The class a constant pool class entry names, as a binary name. */
    static String binaryName(ClassEntry entry){
        return entry.asInternalName().replace('/', '.');
    }

    /** Parameter types of a method descriptor, as class names. */
    public static List<String> parameterTypes(String descriptor){
        return MethodTypeDesc.ofDescriptor(descriptor).parameterList().stream().map(Layouts::typeName).toList();
    }

    public static String typeName(ClassDesc desc){
        if(desc.isArray()) return typeName(desc.componentType()) + "[]";
        if(desc.isPrimitive()) return desc.displayName();
        String d = desc.descriptorString();
        return d.substring(1, d.length() - 1).replace('/', '.');
    }

    private Layouts(){}
}
