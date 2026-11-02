package com.xpdustry.shard.tools.wirelift;

import java.io.*;
import java.lang.classfile.*;
import java.lang.classfile.attribute.*;
import java.lang.classfile.instruction.*;
import java.lang.constant.*;
import java.lang.reflect.AccessFlag;
import java.nio.file.*;
import java.util.*;
import java.util.jar.JarFile;
import java.util.stream.*;

/**
 * Mindustry's wire layouts, lifted out of a vanilla jar's bytecode (proto 25 {@code WireLift}). No vanilla class is
 * loaded or initialised: the JDK's ClassFile API reads the class files, and {@link #lift} symbolically executes one IO
 * method at a time into a list of {@link Op}s, one per {@code Writes}/{@code Reads}/{@code TypeIO} call, delegated
 * call, branch and loop.
 *
 * <p>Branch targets snapshot the symbolic stack and labels merge it, which recovers ternaries: {@code type == null ?
 * -1 : type.id} becomes the value {@code type.id|-1}. {@link SchemaEmit} turns the lifted bodies into the schema.
 */
public final class WireLift{
    static final String W = "arc/util/io/Writes", R = "arc/util/io/Reads", BUILDING = "mindustry/gen/Building";
    static final String WRITE = "(L" + W + ";)V", READ_SYNC = "(L" + R + ";)V";

    final Map<String, ClassModel> classes;
    private final Map<String, String> sourceFiles;
    /** {@code build} from the jar's {@code version.properties}, such as {@code 160.7}. */
    final String build;
    /** The types lifted reads cast a read entity to, by simple name: internal name. */
    final Map<String, String> entityCasts = new TreeMap<>();

    private WireLift(Map<String, ClassModel> classes, Map<String, String> sourceFiles, String build){
        this.classes = classes;
        this.sourceFiles = sourceFiles;
        this.build = build;
    }

    /** Reads every {@code mindustry/} and {@code arc/} class of a client or server jar (they lift the same). */
    public static WireLift open(Path jar) throws IOException{
        Map<String, ClassModel> classes = new TreeMap<>();
        Map<String, String> sourceFiles = new TreeMap<>();
        String build = "?";
        try(var file = new JarFile(jar.toFile())){
            var versions = file.getEntry("version.properties");
            if(versions != null){
                var properties = new Properties();
                try(var in = file.getInputStream(versions)){
                    properties.load(in);
                }
                build = properties.getProperty("build", "?");
            }
            for(var entry : Collections.list(file.entries())){
                String name = entry.getName();
                if(!name.endsWith(".class") || !(name.startsWith("mindustry/") || name.startsWith("arc/"))) continue;
                byte[] bytes;
                try(var in = file.getInputStream(entry)){
                    bytes = in.readAllBytes();
                }
                ClassModel model = ClassFile.of().parse(bytes);
                String internal = model.thisClass().asInternalName();
                classes.put(internal, model);
                model.findAttribute(Attributes.sourceFile()).ifPresent(s -> sourceFiles.put(internal, s.sourceFile().stringValue()));
            }
        }
        return new WireLift(classes, sourceFiles, build);
    }

    //region packets

    /**
     * One registered packet: its id is its position in registration order.
     * @param direction who handles it: {@code C>S}, {@code S>C}, {@code both}, or {@code framework} without handlers
     * @param accepts   who reads it, from {@code allow(boolean server)}: {@code C>S} only servers, {@code S>C} only
     *                  clients, {@code both}
     */
    record PacketLift(int id, String className, String handler, String direction, String accepts, String priority, String reliable,
                      String sends, String write, String read){
        String simpleName(){
            return simple(className).replace("Packets$", "");
        }
    }

    /**
     * Every packet in id order. Net.&lt;clinit&gt; registers 7 framework packets, then Call.registerPackets() the
     * generated ones; reliability comes from the boolean that Call's static methods pass to net.send / con.send.
     */
    List<PacketLift> packets(){
        List<String> order = new ArrayList<>();
        order.addAll(registrations(method("mindustry/net/Net", "<clinit>", null)));
        order.addAll(registrations(method("mindustry/gen/Call", "registerPackets", null)));
        Map<String, Set<String>> reliability = new TreeMap<>(), variants = new TreeMap<>();
        for(MethodModel m : classes.get("mindustry/gen/Call").methods()){
            String name = m.methodName().stringValue();
            if(name.equals("registerPackets") || name.equals("<clinit>") || name.equals("<init>")) continue;
            Lifted l = lift("mindustry/gen/Call", m);
            String packet = l.news.stream().filter(n -> n.endsWith("CallPacket") || n.matches(".*CallPacket\\d")).findFirst().orElse(null);
            if(packet == null) continue;
            for(Op op : l.ops){
                if(!op.kind.equals("send")) continue;
                reliability.computeIfAbsent(packet, k -> new TreeSet<>()).add(op.arg.equals("1") || op.arg.equals("true") ? "tcp" : op.arg.equals("0") || op.arg.equals("false") ? "udp" : op.arg);
                variants.computeIfAbsent(packet, k -> new TreeSet<>()).add(name.endsWith("__forward") ? "forward" : op.codec);
            }
        }
        List<PacketLift> out = new ArrayList<>();
        for(int id = 0; id < order.size(); id++){
            String cls = order.get(id);
            ClassModel model = classes.get(cls);
            String priority = switch(constReturn(resolve(cls, "getPriority", "()I"))){
                case "0" -> "low";
                case "2" -> "high";
                default -> "normal";
            };
            String server = handlerTarget(model, "handleServer"), client = handlerTarget(model, "handleClient");
            String direction = server != null && client != null ? "both" : server != null ? "C>S" : client != null ? "S>C" : "framework";
            String handler = server != null ? server : client != null ? client : "-";
            MethodModel write = method(cls, "write", WRITE), handled = method(cls, "handled", "()V");
            boolean toServer = allows(cls, true), toClient = allows(cls, false);
            String accepts = toServer && toClient ? "both" : toServer ? "C>S" : toClient ? "S>C" : "none";
            out.add(new PacketLift(id, cls, handler, direction, accepts, priority, String.join(",", reliability.getOrDefault(cls, Set.of("-"))),
                String.join(",", variants.getOrDefault(cls, Set.of("-"))),
                write == null ? "-" : compact(lift(cls, write)), handled == null ? "-" : compact(lift(cls, handled))));
        }
        return out;
    }

    /**
     * Evaluates the {@code allow(boolean server)} a packet inherits for one side: whether vanilla's serializer lets that
     * side read it (ArcNetProvider.java:513). The bodies are constant or {@code server}/{@code !server}.
     */
    boolean allows(String cls, boolean server){
        MethodModel m = resolve(cls, "allow", "(Z)Z");
        if(m == null) throw new IllegalStateException(cls + " has no allow(boolean)");
        List<CodeElement> code = m.code().orElseThrow().elementList();
        Map<Label, Integer> labels = new HashMap<>();
        for(int i = 0; i < code.size(); i++){
            if(code.get(i) instanceof LabelTarget target) labels.put(target.label(), i);
        }
        Deque<Integer> stack = new ArrayDeque<>();
        for(int pc = 0, steps = 0; pc < code.size() && steps < 64; steps++){
            CodeElement e = code.get(pc++);
            switch(e){
                case LoadInstruction load when load.slot() == 1 -> stack.push(server ? 1 : 0);
                case ConstantInstruction c when c.constantValue() instanceof Integer value -> stack.push(value);
                case BranchInstruction branch -> {
                    boolean jump = switch(branch.opcode()){
                        case GOTO, GOTO_W -> true;
                        case IFEQ -> stack.pop() == 0;
                        case IFNE -> stack.pop() != 0;
                        default -> throw new IllegalStateException(cls + ".allow: unsupported " + branch);
                    };
                    if(jump) pc = labels.get(branch.target());
                }
                case ReturnInstruction ret -> {
                    return stack.pop() != 0;
                }
                case PseudoInstruction pseudo -> {}
                case Instruction other -> throw new IllegalStateException(cls + ".allow: unsupported " + other);
                default -> {}
            }
        }
        throw new IllegalStateException(cls + ".allow does not return");
    }

    /** Class names registered by {@code Net.registerPacket(X::new)} calls, in order. */
    static List<String> registrations(MethodModel m){
        List<String> out = new ArrayList<>();
        for(CodeElement e : m.code().orElseThrow()){
            if(!(e instanceof InvokeDynamicInstruction indy)) continue;
            // X::new compiles to an invokedynamic whose implementation handle is X.<init>
            for(var arg : indy.bootstrapArgs()){
                if(arg instanceof DirectMethodHandleDesc handle && handle.methodName().equals("<init>")){
                    out.add(handle.owner().descriptorString().replaceAll("^L|;$", ""));
                }
            }
        }
        return out;
    }

    /** {@link #constReturn(MethodModel)} of a method the class itself declares, or {@code fallback}. */
    static String constReturn(ClassModel model, String name, String fallback){
        for(MethodModel m : model.methods()){
            if(m.methodName().stringValue().equals(name) && m.code().isPresent()) return constReturn(m);
        }
        return fallback;
    }

    /** The first constant a method loads, which for a {@code return CONSTANT} body is what it returns. */
    static String constReturn(MethodModel m){
        for(CodeElement e : m.code().orElseThrow()){
            if(e instanceof ConstantInstruction c) return String.valueOf(c.constantValue());
        }
        throw new IllegalStateException(m.parent().map(c -> c.thisClass().asInternalName()).orElse("?") + "." + m.methodName() + " returns no constant");
    }

    /** The last non-generated static method a packet handler calls: its {@code @Remote} target. */
    static String handlerTarget(ClassModel model, String name){
        for(MethodModel m : model.methods()){
            if(!m.methodName().stringValue().equals(name) || m.code().isEmpty()) continue;
            String last = null;
            for(CodeElement e : m.code().orElseThrow()){
                if(e instanceof InvokeInstruction inv && inv.opcode() == Opcode.INVOKESTATIC && !inv.owner().asInternalName().startsWith("mindustry/gen/")){
                    last = inv.owner().asInternalName().replace("mindustry/", "").replace('/', '.') + "." + inv.name().stringValue();
                }
            }
            return last == null ? "?" : last;
        }
        return null;
    }

    //endregion
    //region enums

    /** An enum's constants in declaration order, which is ordinal order: what {@code X.all[ordinal]} indexes. */
    List<String> enumConstants(String cls){
        ClassModel model = classes.get(cls);
        if(model == null) throw new IllegalArgumentException("no class " + cls + " in the jar");
        List<String> out = new ArrayList<>();
        for(FieldModel f : model.fields()){
            if(f.flags().has(AccessFlag.ENUM)) out.add(f.fieldName().stringValue());
        }
        return out;
    }

    //endregion
    //region symbolic execution

    /** A symbolic stack value: an expression and its slot size. */
    record V(String e, int size){
        @Override
        public String toString(){
            return e;
        }
    }

    /**
     * One lifted operation. {@code kind} is emit (a primitive or TypeIO call: {@code codec} is its name), delegate (any
     * other call taking the stream), send, if, goto, loop, switch, set (a field store), let (a local store), call (any other
     * method called on one of this object's fields, such as {@code statuses.add(item)}) or return.
     */
    static final class Op{
        final String kind, codec, arg;
        final int index, line;

        Op(String kind, String codec, String arg, int index, int line){
            this.kind = kind;
            this.codec = codec;
            this.arg = arg;
            this.index = index;
            this.line = line;
        }
    }

    /** A lifted method body. */
    final class Lifted{
        final String cls;
        final List<Op> ops = new ArrayList<>();
        final List<String> news = new ArrayList<>();
        /** {from, to} instruction indices of every branch. */
        final List<int[]> branches = new ArrayList<>();
        int firstLine = -1;
        boolean hasSwitch, hasCall;

        Lifted(String cls){
            this.cls = cls;
        }

        String source(){
            String pkg = cls.contains("/") ? cls.substring(0, cls.lastIndexOf('/') + 1) : "";
            return "core/src/" + pkg + sourceFiles.getOrDefault(cls, "?") + ":" + firstLine;
        }

        /** straight: no branches and no loops; branchy: forward branches only; loop: a backward jump. */
        String shape(){
            if(branches.stream().anyMatch(b -> b[1] <= b[0])) return "loop";
            if(!branches.isEmpty() || hasSwitch) return "branchy";
            return "straight";
        }

        boolean conditional(int index){
            return branches.stream().anyMatch(b -> b[1] > b[0] && index > b[0] && index < b[1]);
        }

        boolean looped(int index){
            return branches.stream().anyMatch(b -> b[1] <= b[0] && index >= b[1] && index <= b[0]);
        }
    }

    Lifted lift(String cls, MethodModel m){
        Lifted l = new Lifted(cls);
        CodeModel code = m.code().orElse(null);
        if(code == null) return l;
        MethodTypeDesc type = m.methodTypeSymbol();
        Map<Integer, String> names = new HashMap<>();
        int slot = 0;
        if(!m.flags().has(AccessFlag.STATIC)) names.put(slot++, "this");
        for(int i = 0; i < type.parameterCount(); i++){
            String d = type.parameterType(i).descriptorString();
            names.put(slot, d.equals("L" + W + ";") ? "W" : d.equals("L" + R + ";") ? "R" : "p" + i);
            slot += d.equals("J") || d.equals("D") ? 2 : 1;
        }
        code.findAttribute(Attributes.localVariableTable()).ifPresent(table -> {
            for(var local : table.localVariables()){
                int s = local.slot();
                String d = local.type().stringValue();
                if(!names.containsKey(s) || names.get(s).startsWith("p")){
                    names.put(s, d.equals("L" + W + ";") ? "W" : d.equals("L" + R + ";") ? "R" : local.name().stringValue());
                }
            }
        });

        // pass 1: label positions
        Map<Label, Integer> labels = new HashMap<>();
        int index = 0;
        for(CodeElement e : code){
            if(e instanceof LabelTarget target) labels.put(target.label(), index);
            if(e instanceof Instruction) index++;
        }
        // pass 2: interpret. Stacks are snapshotted at branch targets so that `c ? a : b` merges into one value "a|b".
        Deque<V> stack = new ArrayDeque<>();
        Map<Label, Deque<V>> saved = new HashMap<>();
        boolean dead = false;
        index = 0;
        int line = -1;
        for(CodeElement e : code){
            if(e instanceof LineNumber number){
                line = number.line();
                if(l.firstLine < 0) l.firstLine = line;
                continue;
            }
            if(e instanceof LabelTarget target){
                Deque<V> snapshot = saved.get(target.label());
                if(dead){
                    stack = snapshot == null ? new ArrayDeque<>() : new ArrayDeque<>(snapshot);
                    dead = false;
                }else if(snapshot != null && snapshot.size() == stack.size()){
                    var a = new ArrayList<>(stack);
                    var b = new ArrayList<>(snapshot);
                    Deque<V> merged = new ArrayDeque<>();
                    for(int i = a.size() - 1; i >= 0; i--){
                        V x = a.get(i), y = b.get(i);
                        merged.push(x.e.equals(y.e) ? x : new V(x.e + "|" + y.e, x.size));
                    }
                    stack = merged;
                }
                continue;
            }
            if(!(e instanceof Instruction instruction)) continue;
            try{
                step(l, instruction, stack, names, labels, index, line);
            }catch(RuntimeException ex){
                stack.clear(); // an unknown stack shape: keep going, the op list is what matters
            }
            switch(instruction){
                case BranchInstruction branch -> {
                    saved.putIfAbsent(branch.target(), new ArrayDeque<>(stack));
                    if(branch.opcode() == Opcode.GOTO || branch.opcode() == Opcode.GOTO_W) dead = true;
                }
                case LookupSwitchInstruction sw -> {
                    saved.putIfAbsent(sw.defaultTarget(), new ArrayDeque<>(stack));
                    for(var c : sw.cases()) saved.putIfAbsent(c.target(), new ArrayDeque<>(stack));
                    dead = true;
                }
                case TableSwitchInstruction sw -> {
                    saved.putIfAbsent(sw.defaultTarget(), new ArrayDeque<>(stack));
                    for(var c : sw.cases()) saved.putIfAbsent(c.target(), new ArrayDeque<>(stack));
                    dead = true;
                }
                case ReturnInstruction ignored -> dead = true;
                case ThrowInstruction ignored -> dead = true;
                default -> {}
            }
            index++;
        }
        return l;
    }

    private static V pop(Deque<V> stack){
        return stack.isEmpty() ? new V("?", 1) : stack.pop();
    }

    private static int size(ClassDesc d){
        String s = d.descriptorString();
        return s.equals("J") || s.equals("D") ? 2 : 1;
    }

    private void step(Lifted l, Instruction instruction, Deque<V> stack, Map<Integer, String> names, Map<Label, Integer> labels, int index, int line){
        switch(instruction){
            case LoadInstruction load -> stack.push(new V(names.getOrDefault(load.slot(), "v" + load.slot()), load.typeKind().slotSize()));
            case StoreInstruction store -> {
                V v = pop(stack);
                names.putIfAbsent(store.slot(), "v" + store.slot());
                l.ops.add(new Op("let", names.get(store.slot()), v.e, index, line));
            }
            case ConstantInstruction c -> stack.push(new V(c.constantValue() instanceof String s ? "\"" + s + "\"" : String.valueOf(c.constantValue()), c.typeKind().slotSize()));
            case FieldInstruction field -> {
                // an inner class's outer instance is its block
                String name = field.name().stringValue().equals("this$0") ? "block" : field.name().stringValue();
                int sz = size(field.typeSymbol());
                switch(field.opcode()){
                    case GETFIELD -> {
                        V owner = pop(stack);
                        stack.push(new V(owner.e.equals("this") ? name : owner.e + "." + name, sz));
                    }
                    case GETSTATIC -> stack.push(new V(name.equals("READ") && field.typeSymbol().descriptorString().equals("L" + R + ";") ? "R" : simple(field.owner().asInternalName()) + "." + name, sz));
                    case PUTFIELD -> {
                        V v = pop(stack), owner = pop(stack);
                        l.ops.add(new Op("set", (owner.e.equals("this") ? "" : owner.e + ".") + name, v.e, index, line));
                    }
                    default -> l.ops.add(new Op("set", name, pop(stack).e, index, line));
                }
            }
            case InvokeInstruction invoke -> invoke(l, invoke, stack, index, line);
            case InvokeDynamicInstruction indy -> {
                MethodTypeDesc t = indy.typeSymbol();
                for(int i = 0; i < t.parameterCount(); i++) pop(stack);
                if(!t.returnType().descriptorString().equals("V")) stack.push(new V("λ" + indy.name().stringValue(), size(t.returnType())));
            }
            case OperatorInstruction operator -> {
                String op = operator.opcode().name();
                if(op.endsWith("NEG")){
                    V a = pop(stack);
                    stack.push(new V("-" + a, a.size));
                }else if(op.equals("ARRAYLENGTH")){
                    stack.push(new V(pop(stack) + ".length", 1));
                }else{
                    V b = pop(stack), a = pop(stack);
                    String symbol = switch(op.substring(1)){
                        case "ADD" -> "+";
                        case "SUB" -> "-";
                        case "MUL" -> "*";
                        case "DIV" -> "/";
                        case "REM" -> "%";
                        case "SHL" -> "<<";
                        case "SHR" -> ">>";
                        case "USHR" -> ">>>";
                        case "AND" -> "&";
                        case "OR" -> "|";
                        case "XOR" -> "^";
                        default -> "cmp";
                    };
                    stack.push(new V("(" + a + " " + symbol + " " + b + ")", symbol.equals("cmp") ? 1 : operator.typeKind().slotSize()));
                }
            }
            case ConvertInstruction convert -> stack.push(new V("(" + convert.toType().name().toLowerCase(Locale.ROOT) + ")" + pop(stack), convert.toType().slotSize()));
            case StackInstruction s -> {
                switch(s.opcode()){
                    case POP -> pop(stack);
                    case POP2 -> {
                        if(pop(stack).size == 1) pop(stack);
                    }
                    case DUP -> {
                        V a = pop(stack);
                        stack.push(a);
                        stack.push(a);
                    }
                    case DUP_X1 -> {
                        V a = pop(stack), b = pop(stack);
                        stack.push(a);
                        stack.push(b);
                        stack.push(a);
                    }
                    case DUP_X2 -> {
                        V a = pop(stack), b = pop(stack), c = pop(stack);
                        stack.push(a);
                        stack.push(c);
                        stack.push(b);
                        stack.push(a);
                    }
                    case DUP2 -> {
                        V a = pop(stack);
                        if(a.size == 2){
                            stack.push(a);
                            stack.push(a);
                        }else{
                            V b = pop(stack);
                            stack.push(b);
                            stack.push(a);
                            stack.push(b);
                            stack.push(a);
                        }
                    }
                    case SWAP -> {
                        V a = pop(stack), b = pop(stack);
                        stack.push(a);
                        stack.push(b);
                    }
                    default -> stack.clear();
                }
            }
            case ArrayLoadInstruction load -> {
                V i = pop(stack), a = pop(stack);
                stack.push(new V(a + "[" + i + "]", load.typeKind().slotSize()));
            }
            case ArrayStoreInstruction ignored -> {
                pop(stack);
                pop(stack);
                pop(stack);
            }
            case NewObjectInstruction n -> {
                l.news.add(n.className().asInternalName());
                stack.push(new V("new " + simple(n.className().asInternalName()), 1));
            }
            case NewPrimitiveArrayInstruction n -> stack.push(new V("new " + n.typeKind().name().toLowerCase(Locale.ROOT) + "[" + pop(stack) + "]", 1));
            case NewReferenceArrayInstruction n -> stack.push(new V("new " + simple(n.componentType().asInternalName()) + "[" + pop(stack) + "]", 1));
            case NewMultiArrayInstruction n -> {
                for(int i = 0; i < n.dimensions(); i++) pop(stack);
                stack.push(new V("new [][]", 1));
            }
            case TypeCheckInstruction check -> {
                V a = pop(stack);
                String type = check.type().asInternalName();
                if(check.opcode() == Opcode.CHECKCAST && a.e.equals("TypeIO.readEntity()")){
                    //the cast is part of the read: an entity of another type fails it
                    entityCasts.put(simple(type), type);
                    l.ops.add(new Op("cast", simple(type), "", index, line));
                }
                stack.push(check.opcode() == Opcode.CHECKCAST ? a : new V(a + " instanceof " + simple(type), 1));
            }
            case BranchInstruction branch -> {
                String op = branch.opcode().name();
                String condition;
                if(op.startsWith("IF_")){
                    V b = pop(stack), a = pop(stack);
                    condition = a + " " + op.substring(5).toLowerCase(Locale.ROOT) + " " + b;
                }else if(op.startsWith("IF")){
                    condition = pop(stack) + " " + op.substring(2).toLowerCase(Locale.ROOT);
                }else{
                    condition = "goto";
                }
                int target = labels.getOrDefault(branch.target(), index + 1);
                l.branches.add(new int[]{index, target});
                l.ops.add(new Op(condition.equals("goto") ? (target <= index ? "loop" : "goto") : "if", condition, String.valueOf(target), index, line));
            }
            case LookupSwitchInstruction ignored -> {
                pop(stack);
                l.hasSwitch = true;
                l.ops.add(new Op("switch", "lookup", "", index, line));
            }
            case TableSwitchInstruction ignored -> {
                pop(stack);
                l.hasSwitch = true;
                l.ops.add(new Op("switch", "table", "", index, line));
            }
            case ReturnInstruction ret -> {
                if(ret.typeKind() != TypeKind.VOID) l.ops.add(new Op("return", "", pop(stack).e, index, line));
                stack.clear();
            }
            case ThrowInstruction ignored -> {
                pop(stack);
                stack.clear();
            }
            case MonitorInstruction ignored -> pop(stack);
            default -> {}
        }
    }

    /** Writes calls by name and descriptor, as codec names. */
    private static final Map<String, String> WRITES = new TreeMap<>(Map.of(
        "b(I)V", "i8", "s(I)V", "i16", "i(I)V", "i32", "l(J)V", "i64", "f(F)V", "f32", "d(D)V", "f64",
        "bool(Z)V", "bool", "str(Ljava/lang/String;)V", "utf", "b([B)V", "raw", "b([BII)V", "raw"));
    private static final Map<String, String> READS = new TreeMap<>(Map.ofEntries(
        Map.entry("b()B", "i8"), Map.entry("ub()I", "u8"), Map.entry("s()S", "i16"), Map.entry("us()I", "u16"),
        Map.entry("i()I", "i32"), Map.entry("l()J", "i64"), Map.entry("f()F", "f32"), Map.entry("d()D", "f64"),
        Map.entry("bool()Z", "bool"), Map.entry("str()Ljava/lang/String;", "utf"), Map.entry("b(I)[B", "raw"),
        Map.entry("b([B)[B", "raw"), Map.entry("skip(I)V", "skip")));

    private void invoke(Lifted l, InvokeInstruction invoke, Deque<V> stack, int index, int line){
        MethodTypeDesc t = invoke.typeSymbol();
        List<V> args = new ArrayList<>();
        for(int i = 0; i < t.parameterCount(); i++) args.addFirst(pop(stack));
        V receiver = invoke.opcode() == Opcode.INVOKESTATIC ? null : pop(stack);
        String owner = invoke.owner().asInternalName(), name = invoke.name().stringValue();
        String key = name + t.descriptorString();
        String ret = t.returnType().descriptorString();
        String joined = args.stream().map(V::e).collect(Collectors.joining(", "));
        boolean takesW = args.stream().anyMatch(a -> a.e.equals("W")), takesR = args.stream().anyMatch(a -> a.e.equals("R"));

        if(receiver != null && receiver.e.equals("W") && owner.equals(W) && WRITES.containsKey(key)){
            l.ops.add(new Op("emit", WRITES.get(key), joined, index, line));
        }else if(receiver != null && receiver.e.equals("R") && owner.equals(R) && READS.containsKey(key)){
            l.ops.add(new Op("emit", READS.get(key), "", index, line));
            if(!ret.equals("V")) stack.push(new V(READS.get(key) + "()", size(t.returnType())));
            return;
        }else if((name.equals("send") || name.equals("sendExcept")) && (owner.contains("Net") || owner.contains("NetConnection")) && !args.isEmpty()){
            l.ops.add(new Op("send", owner.contains("NetConnection") ? "one" : name.equals("sendExcept") ? "except" : "all", args.getLast().e, index, line));
        }else if(takesW || takesR){
            boolean typeIO = owner.equals("mindustry/io/TypeIO");
            String who = typeIO ? "TypeIO." + name
                : invoke.opcode() == Opcode.INVOKESPECIAL && receiver != null && receiver.e.equals("this") ? "super." + name + "@" + simple(owner)
                : (receiver == null ? simple(owner) : receiver.e) + "." + name;
            String value = args.stream().filter(a -> !a.e.equals("W") && !a.e.equals("R")).map(V::e).collect(Collectors.joining(", "));
            l.ops.add(new Op(typeIO ? "emit" : "delegate", who, value, index, line));
            l.hasCall |= !typeIO;
            if(!ret.equals("V")) stack.push(new V(who + "()", size(t.returnType())));
            return;
        }
        if(receiver != null && !receiver.e.equals("this") && receiver.e.matches("[A-Za-z_]\\w*")) l.ops.add(new Op("call", receiver.e + "." + name, joined, index, line));
        if(!ret.equals("V")){
            String r = (receiver == null ? simple(owner) + "." : receiver.e.equals("this") ? "" : receiver.e + ".") + name + "(" + joined + ")";
            stack.push(new V(r, size(t.returnType())));
        }
    }

    //endregion
    //region rendering

    /** One-line form: {@code i32 x, f32 y, [if net.server() eq], ?Entity player}. */
    static String compact(Lifted l){
        List<String> parts = new ArrayList<>();
        for(Op op : l.ops){
            String flag = (l.looped(op.index) ? "*" : "") + (l.conditional(op.index) ? "?" : "");
            switch(op.kind){
                case "emit" -> parts.add(flag + op.codec.replace("TypeIO.write", "").replace("TypeIO.read", "") + (op.arg.isEmpty() ? "" : " " + op.arg));
                case "delegate" -> parts.add(flag + "→" + op.codec + (op.arg.isEmpty() ? "" : "(" + op.arg + ")"));
                case "if" -> parts.add("[if " + op.codec.replace("Vars.", "") + "]");
                case "loop" -> parts.add("[loop]");
                case "cast" -> {
                    // a read entity cast to a type: Entity<Player>
                    int last = parts.size() - 1;
                    if(last >= 0 && parts.get(last).endsWith("Entity")) parts.set(last, parts.get(last) + "<" + op.codec + ">");
                }
                case "set", "let" -> {
                    // a read lands in a field or local: name the last emitted read after it
                    if(!parts.isEmpty() && op.arg.matches(".*(TypeIO\\.read\\w+|\\b[iuf](8|16|32|64)|bool|utf)\\(\\).*")){
                        int last = parts.size() - 1;
                        if(!parts.get(last).contains(" ") && !parts.get(last).startsWith("[")) parts.set(last, parts.get(last) + " " + op.codec);
                    }
                }
                default -> {}
            }
        }
        return parts.isEmpty() ? "(empty)" : String.join(", ", parts);
    }

    /** One op per line with its source line: {@code L123 *? i32  x}. */
    static String render(Lifted l){
        StringBuilder sb = new StringBuilder();
        for(Op op : l.ops){
            String prefix = "L" + op.line + " " + (l.looped(op.index) ? "*" : " ") + (l.conditional(op.index) ? "?" : " ") + " ";
            switch(op.kind){
                case "emit" -> sb.append(prefix).append(op.codec).append(op.arg.isEmpty() ? "" : "  " + op.arg).append('\n');
                case "delegate" -> sb.append(prefix).append("→ ").append(op.codec).append(op.arg.isEmpty() ? "" : "(" + op.arg + ")").append('\n');
                case "if" -> sb.append(prefix).append("if ").append(op.codec).append(" → @").append(op.arg).append('\n');
                case "loop" -> sb.append(prefix).append("loop ↺ @").append(op.arg).append('\n');
                case "switch" -> sb.append(prefix).append("switch\n");
                default -> {}
            }
        }
        return sb.toString().stripTrailing();
    }

    static String indent(String s, String prefix){
        return s.isEmpty() ? prefix + "(empty)" : s.lines().map(x -> prefix + x).collect(Collectors.joining("\n"));
    }

    //endregion
    //region class helpers

    MethodModel method(String cls, String name, String descriptor){
        ClassModel model = classes.get(cls);
        if(model == null) return null;
        for(MethodModel m : model.methods()){
            if(m.methodName().stringValue().equals(name) && (descriptor == null || m.methodType().stringValue().equals(descriptor))) return m;
        }
        return null;
    }

    /** The method as {@code cls} inherits it. */
    MethodModel resolve(String cls, String name, String descriptor){
        for(String c = cls; c != null; c = superOf(c)){
            MethodModel m = method(c, name, descriptor);
            if(m != null) return m;
        }
        return null;
    }

    /** The class that declares the method {@code cls} inherits. */
    String owner(String cls, String name, String descriptor){
        for(String c = cls; c != null; c = superOf(c)){
            if(method(c, name, descriptor) != null) return c;
        }
        return cls;
    }

    String superOf(String cls){
        ClassModel model = classes.get(cls);
        return model == null ? null : model.superclass().map(c -> c.asInternalName()).orElse(null);
    }

    /** Whether {@code cls} is {@code type}, extends it or implements it. */
    boolean assignable(String cls, String type){
        Deque<String> pending = new ArrayDeque<>(List.of(cls));
        Set<String> seen = new HashSet<>();
        while(!pending.isEmpty()){
            String c = pending.pop();
            if(c.equals(type)) return true;
            ClassModel model = classes.get(c);
            if(model == null || !seen.add(c)) continue;
            model.superclass().ifPresent(sup -> pending.push(sup.asInternalName()));
            model.interfaces().forEach(i -> pending.push(i.asInternalName()));
        }
        return false;
    }

    boolean isSubclass(String cls, String base){
        for(String c = superOf(cls); c != null; c = superOf(c)){
            if(c.equals(base)) return true;
        }
        return false;
    }

    /** The descriptor of a field as {@code cls} inherits it, or {@code ?}. */
    String fieldDescriptor(String cls, String name){
        for(String c = cls; c != null; c = superOf(c)){
            ClassModel model = classes.get(c);
            if(model == null) break;
            for(FieldModel f : model.fields()){
                if(f.fieldName().stringValue().equals(name)) return f.fieldType().stringValue();
            }
        }
        return "?";
    }

    /**
     * What the no-argument constructors of {@code cls} and its superclasses store in its fields, superclasses first: the
     * number for {@code this.f = NUMBER}, and {@code ?} for any other value.
     */
    Map<String, String> constructorStores(String cls){
        Deque<String> chain = new ArrayDeque<>();
        for(String c = cls; c != null && classes.containsKey(c); c = superOf(c)) chain.push(c);
        Map<String, String> stores = new TreeMap<>();
        for(String c : chain){
            MethodModel init = method(c, "<init>", "()V");
            if(init == null || init.code().isEmpty()) continue;
            List<Instruction> code = init.code().get().elementStream().filter(e -> e instanceof Instruction).map(e -> (Instruction)e).toList();
            for(int i = 0; i < code.size(); i++){
                if(!(code.get(i) instanceof FieldInstruction f) || f.opcode() != Opcode.PUTFIELD || !f.owner().asInternalName().equals(c)) continue;
                boolean number = i >= 2 && code.get(i - 1) instanceof ConstantInstruction k && k.constantValue() instanceof Number
                    && code.get(i - 2) instanceof LoadInstruction l && l.slot() == 0;
                stores.put(f.name().stringValue(), number ? String.valueOf(((ConstantInstruction)code.get(i - 1)).constantValue()) : "?");
            }
        }
        return stores;
    }

    static String simple(String cls){
        return cls.substring(cls.lastIndexOf('/') + 1);
    }

    static boolean concrete(ClassModel model){
        return !model.flags().has(AccessFlag.INTERFACE) && !model.flags().has(AccessFlag.ABSTRACT);
    }

    //endregion
}
