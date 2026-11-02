package com.xpdustry.shard.tools.indexer;

import javax.annotation.processing.*;
import javax.lang.model.*;
import javax.lang.model.element.*;
import javax.lang.model.type.*;
import javax.lang.model.util.*;
import javax.tools.*;
import java.io.*;
import java.lang.annotation.*;
import java.nio.charset.*;
import java.util.*;

/**
 * Writes the registration index as Java. A class is registered when it carries {@code @Register}, or an annotation
 * that is itself annotated {@code @Register} (such as {@code :sim}'s {@code @Kind("conveyor")}). For each registered
 * class, the processor finds every {@code @Registry} type among its supertypes. It writes one class per compiled source
 * set, named by the {@value #OPTION} option ({@code shard.java} sets it, such as
 * {@code com.xpdustry.shard.sim.SimRegistryIndex}): a {@code RegistryIndex} that declares the source set's
 * {@code @Registry} types and registered classes, sorted, each with a constructor reference and its registration
 * annotations' {@code value()}s. It lists that class in {@code META-INF/services}, where {@code Registries} finds every
 * source set's with {@code ServiceLoader}, so neither run time nor a browser build needs reflection or class names.
 *
 * <p>It also shapes every record that implements a {@code @Shaped} type: per package, a class next to the records
 * declares each one's component accessors and canonical constructor, and the index calls it.
 *
 * <p>The annotations are matched by name, so this processor depends on nothing but the JDK. It claims no annotations
 * and accepts {@code *}, because registration annotations are defined in other modules. It is aggregating for
 * Gradle's incremental compilation; with {@code *}, Gradle reprocesses every source file on each run.
 */
@SupportedAnnotationTypes("*")
@SupportedOptions(RegisterProcessor.OPTION)
public final class RegisterProcessor extends AbstractProcessor{
    static final String REGISTER = "com.xpdustry.shard.common.registry.Register";
    static final String REGISTRY = "com.xpdustry.shard.common.registry.Registry";
    static final String SHAPED = "com.xpdustry.shard.common.registry.Shaped";
    static final String INDEX_TYPE = "com.xpdustry.shard.common.registry.RegistryIndex";
    static final String SERVICES = "META-INF/services/" + INDEX_TYPE;
    /** The binary name of the class to generate. */
    static final String OPTION = "shard.registry.index";

    /** Binary name → a registered class: its source name, registries and registration annotations' values. */
    private final Map<String, Registered> registered = new TreeMap<>();
    /** The binary names of the {@code @Registry} types declared in this source set. */
    private final Set<String> registries = new TreeSet<>();
    /** Package → binary name → each record to shape there. */
    private final Map<String, SortedMap<String, TypeElement>> shaped = new TreeMap<>();
    private final List<Element> origins = new ArrayList<>();
    private final Set<String> checked = new TreeSet<>();
    private boolean written;

    /** A registered class: its canonical name for the source, its registries' and its annotations' binary names. */
    private record Registered(String source, SortedSet<String> registries, SortedMap<String, String> keys){
    }

    @Override
    public SourceVersion getSupportedSourceVersion(){
        return SourceVersion.latestSupported();
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment round){
        for(TypeElement annotation : annotations){
            if(annotation.getQualifiedName().contentEquals(REGISTRY)){
                for(Element element : round.getElementsAnnotatedWith(annotation)){
                    if(element instanceof TypeElement type) registries.add(binaryName(type));
                    origins.add(element);
                }
                continue;
            }
            String via;
            if(annotation.getQualifiedName().contentEquals(REGISTER)){
                via = "@Register";
            }else if(annotated(annotation, REGISTER)){
                via = "@" + annotation.getSimpleName();
                checkRetention(annotation);
            }else{
                continue;
            }
            for(Element element : round.getElementsAnnotatedWith(annotation)){
                // @Register on an annotation type makes it a registration annotation; it is not itself registered.
                if(via.equals("@Register") && element.getKind() == ElementKind.ANNOTATION_TYPE) continue;
                collect(element, via);
            }
        }
        // The index is written once, in the first round: a source file written in the last round isn't compiled
        // without a warning, and nothing generates registered classes in later rounds.
        if(!written && !round.processingOver()){
            written = true;
            for(Element root : round.getRootElements()) shapes(root);
            write();
        }else if(written && !round.getRootElements().isEmpty() && annotations.stream().anyMatch(this::registration)){
            error(round.getRootElements().iterator().next(), "a registered class was generated after the first round");
        }
        return false;
    }

    /** Collects the records under {@code element} that implement a {@code @Shaped} type. */
    private void shapes(Element element){
        if(element instanceof TypeElement type && type.getKind() == ElementKind.RECORD && implementsShaped(type.asType(), new HashSet<>())){
            boolean reachable = type.getNestingKind() == NestingKind.TOP_LEVEL || type.getNestingKind() == NestingKind.MEMBER;
            for(Element at = type; reachable && at instanceof TypeElement enclosing; at = at.getEnclosingElement()){
                reachable = !enclosing.getModifiers().contains(Modifier.PRIVATE);
            }
            if(!reachable){
                error(type, "a record of a @Shaped type must not be private or local: its shape is generated in its package");
            }else{
                String pkg = processingEnv.getElementUtils().getPackageOf(type).getQualifiedName().toString();
                shaped.computeIfAbsent(pkg, p -> new TreeMap<>()).put(binaryName(type), type);
                origins.add(type);
            }
        }
        for(Element nested : element.getEnclosedElements()){
            if(nested instanceof TypeElement) shapes(nested);
        }
    }

    private boolean implementsShaped(TypeMirror type, Set<String> seen){
        for(TypeMirror parent : processingEnv.getTypeUtils().directSupertypes(type)){
            if(!(parent instanceof DeclaredType declared)) continue;
            var element = (TypeElement)declared.asElement();
            if(!seen.add(binaryName(element))) continue;
            if(annotated(element, SHAPED) || implementsShaped(parent, seen)) return true;
        }
        return false;
    }

    private boolean registration(TypeElement annotation){
        return annotation.getQualifiedName().contentEquals(REGISTER) || annotated(annotation, REGISTER);
    }

    private void collect(Element element, String via){
        if(element.getKind() != ElementKind.CLASS && element.getKind() != ElementKind.RECORD){
            error(element, via + " goes on a class, not on a " + element.getKind().toString().toLowerCase(Locale.ROOT));
            return;
        }
        var type = (TypeElement)element;
        boolean valid = true;
        if(type.getModifiers().contains(Modifier.ABSTRACT)){
            error(type, "a " + via + " class must be concrete");
            valid = false;
        }
        for(Element at = type; at instanceof TypeElement enclosing; at = at.getEnclosingElement()){
            if(!enclosing.getModifiers().contains(Modifier.PUBLIC)){
                error(type, "a " + via + " class must be public" + (at == type ? "" : ", and so must " + enclosing));
                valid = false;
                break;
            }
            if(at != type || enclosing.getNestingKind() != NestingKind.MEMBER) continue;
            if(!enclosing.getModifiers().contains(Modifier.STATIC) && enclosing.getKind() == ElementKind.CLASS){
                error(type, "a nested " + via + " class must be static");
                valid = false;
            }
        }
        boolean constructor = ElementFilter.constructorsIn(type.getEnclosedElements()).stream()
            .anyMatch(c -> c.getParameters().isEmpty() && c.getModifiers().contains(Modifier.PUBLIC));
        if(!constructor){
            error(type, "a " + via + " class needs a public no-argument constructor");
            valid = false;
        }
        var registries = new TreeSet<String>();
        registries(type.asType(), registries, new HashSet<>());
        if(registries.isEmpty()){
            error(type, "a " + via + " class must extend or implement a @Registry type");
            valid = false;
        }
        if(!valid) return;
        var keys = new TreeMap<String, String>();
        for(AnnotationMirror mirror : type.getAnnotationMirrors()){
            var annotation = (TypeElement)mirror.getAnnotationType().asElement();
            if(!annotated(annotation, REGISTER)) continue;
            for(var value : processingEnv.getElementUtils().getElementValuesWithDefaults(mirror).entrySet()){
                if(value.getKey().getSimpleName().contentEquals("value") && value.getValue().getValue() instanceof String key){
                    keys.put(binaryName(annotation), key);
                }
            }
        }
        var previous = registered.putIfAbsent(binaryName(type),
            new Registered(sourceName(type), registries, keys));
        if(previous == null) origins.add(type);
    }

    /** Collects the binary names of every {@code @Registry} supertype of {@code type}, itself excluded. */
    private void registries(TypeMirror type, Set<String> out, Set<String> seen){
        Types types = processingEnv.getTypeUtils();
        for(TypeMirror parent : types.directSupertypes(type)){
            if(!(parent instanceof DeclaredType declared)) continue;
            var element = (TypeElement)declared.asElement();
            String name = binaryName(element);
            if(!seen.add(name)) continue;
            if(annotated(element, REGISTRY)) out.add(name);
            registries(parent, out, seen);
        }
    }

    private String binaryName(TypeElement type){
        return processingEnv.getElementUtils().getBinaryName(type).toString();
    }

    /** The name a source file uses for {@code type}: its canonical name, erased. */
    private static String sourceName(TypeElement type){
        return type.getQualifiedName().toString();
    }

    private static boolean annotated(Element element, String annotation){
        for(AnnotationMirror mirror : element.getAnnotationMirrors()){
            if(((TypeElement)mirror.getAnnotationType().asElement()).getQualifiedName().contentEquals(annotation)){
                return true;
            }
        }
        return false;
    }

    /**
     * A registration annotation stays readable at run time, and the index carries its {@code value()}: it has that
     * one {@code String} element or none.
     */
    private void checkRetention(TypeElement annotation){
        if(!checked.add(annotation.getQualifiedName().toString())) return;
        Retention retention = annotation.getAnnotation(Retention.class);
        if(retention == null || retention.value() != RetentionPolicy.RUNTIME){
            error(annotation, "a registration annotation (meta-annotated @Register) needs @Retention(RUNTIME)");
        }
        for(ExecutableElement element : ElementFilter.methodsIn(annotation.getEnclosedElements())){
            if(!element.getSimpleName().contentEquals("value") || !element.getReturnType().toString().equals("java.lang.String")){
                error(element, "a registration annotation's only element is String value(), which the index records");
            }
        }
    }

    /** Writes the index class and its {@code META-INF/services} line, unless this source set declares nothing. */
    private void write(){
        if(registered.isEmpty() && registries.isEmpty() && shaped.isEmpty()) return;
        String name = processingEnv.getOptions().get(OPTION);
        if(name == null || name.isBlank()){
            processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, "this source set registers classes, but the"
                + " processor option -A" + OPTION + "=<index class binary name> is not set (shard.java sets it)");
            return;
        }
        Element[] from = origins.toArray(Element[]::new);
        try{
            JavaFileObject source = processingEnv.getFiler().createSourceFile(name, from);
            try(var out = new OutputStreamWriter(source.openOutputStream(), StandardCharsets.UTF_8)){
                out.write(source(name));
            }
            for(var pkg : shaped.entrySet()){
                String shapes = shapesName(name, pkg.getKey());
                JavaFileObject file = processingEnv.getFiler().createSourceFile(shapes, pkg.getValue().values().toArray(Element[]::new));
                try(var out = new OutputStreamWriter(file.openOutputStream(), StandardCharsets.UTF_8)){
                    out.write(shapesSource(shapes, pkg.getValue().values()));
                }
            }
            FileObject services = processingEnv.getFiler().createResource(StandardLocation.CLASS_OUTPUT, "", SERVICES, from);
            try(var out = new OutputStreamWriter(services.openOutputStream(), StandardCharsets.UTF_8)){
                out.write(name + "\n");
            }
        }catch(IOException e){
            processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, "cannot write the registration index " + name + ": " + e);
        }
    }

    /** The index class. Declarations are split into methods of {@value #CHUNK}, far below the JVM's method size cap. */
    private String source(String name){
        int dot = name.lastIndexOf('.');
        var out = new StringBuilder();
        if(dot > 0) out.append("package ").append(name, 0, dot).append(";\n\n");
        out.append("/** The registration index of one source set. Generated by ").append(RegisterProcessor.class.getName())
            .append(": do not edit. */\n")
            .append("@javax.annotation.processing.Generated(\"").append(RegisterProcessor.class.getName()).append("\")\n")
            .append("public final class ").append(name.substring(dot + 1)).append(" implements ").append(INDEX_TYPE).append("{\n")
            .append("    @Override\n    public void declare(").append(INDEX_TYPE).append(".Declarations index){\n");
        var lines = new ArrayList<String>();
        for(String registry : registries) lines.add("index.registry(\"" + registry + "\");");
        for(var entry : registered.entrySet()){
            Registered type = entry.getValue();
            lines.add("index.type(\"" + entry.getKey() + "\", " + type.source() + ".class, " + type.source() + "::new, "
                + String.join(", ", type.registries().stream().map(r -> "\"" + r + "\"").toList()) + ");");
            for(var key : type.keys().entrySet()){
                lines.add("index.key(" + type.source() + ".class, \"" + key.getKey() + "\", \"" + escape(key.getValue()) + "\");");
            }
        }
        for(String pkg : shaped.keySet()) lines.add(shapesName(name, pkg) + ".declare(index);");
        int chunks = (lines.size() + CHUNK - 1) / CHUNK;
        for(int i = 0; i < chunks; i++) out.append("        declare").append(i).append("(index);\n");
        out.append("    }\n");
        for(int i = 0; i < chunks; i++){
            out.append("\n    private static void declare").append(i).append("(").append(INDEX_TYPE).append(".Declarations index){\n");
            for(String line : lines.subList(i * CHUNK, Math.min(lines.size(), (i + 1) * CHUNK))){
                out.append("        ").append(line).append('\n');
            }
            out.append("    }\n");
        }
        return out.append("}\n").toString();
    }

    static final int CHUNK = 200;

    /** The class that shapes {@code pkg}'s records for index {@code index}: {@code <pkg>.<index simple name>Shapes}. */
    private static String shapesName(String index, String pkg){
        return (pkg.isEmpty() ? "" : pkg + ".") + index.substring(index.lastIndexOf('.') + 1) + "Shapes";
    }

    /** A class in the records' package (so package-private ones are reachable) that declares their shapes. */
    private String shapesSource(String name, Collection<TypeElement> records){
        Types types = processingEnv.getTypeUtils();
        int dot = name.lastIndexOf('.');
        String simple = name.substring(dot + 1);
        var out = new StringBuilder();
        if(dot > 0) out.append("package ").append(name, 0, dot).append(";\n\n");
        out.append("/** Record shapes for the registration index. Generated by ").append(RegisterProcessor.class.getName())
            .append(": do not edit. */\n")
            .append("@javax.annotation.processing.Generated(\"").append(RegisterProcessor.class.getName()).append("\")\n")
            .append("public final class ").append(simple).append("{\n")
            .append("    @SuppressWarnings({\"rawtypes\", \"unchecked\"})\n")
            .append("    public static void declare(").append(INDEX_TYPE).append(".Declarations index){\n");
        for(TypeElement record : records){
            String type = sourceName(record);
            var reads = new ArrayList<String>();
            var casts = new ArrayList<String>();
            boolean objects = false;
            List<? extends RecordComponentElement> components = record.getRecordComponents();
            for(int i = 0; i < components.size(); i++){
                RecordComponentElement component = components.get(i);
                TypeMirror t = component.asType();
                reads.add("r." + component.getSimpleName() + "()");
                String erased = t.getKind().isPrimitive() ? types.boxedClass((PrimitiveType)t).getQualifiedName().toString()
                    : types.erasure(t).toString();
                casts.add("(" + erased + ")v[" + i + "]");
                boolean simpleValue = t.getKind().isPrimitive() || erased.equals("java.lang.String")
                    || t instanceof DeclaredType declared && declared.asElement().getKind() == ElementKind.ENUM;
                objects |= !simpleValue;
            }
            out.append("        index.shape(").append(type).append(".class, (").append(type).append(" r) -> new Object[]{")
                .append(String.join(", ", reads)).append("}, v -> new ").append(type).append("(")
                .append(String.join(", ", casts)).append("), ").append(objects).append(");\n");
        }
        return out.append("    }\n\n    private ").append(simple).append("(){\n    }\n}\n").toString();
    }

    private static String escape(String value){
        var out = new StringBuilder();
        for(char c : value.toCharArray()){
            if(c == '"' || c == '\\') out.append('\\').append(c);
            else if(c < 0x20 || c > 0x7e) out.append(String.format("\\u%04x", (int)c));
            else out.append(c);
        }
        return out.toString();
    }

    private void error(Element element, String message){
        processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, message, element);
    }
}
