package com.xpdustry.shard.oracle.dump;

import arc.struct.*;
import mindustry.gen.*;
import mindustry.net.*;
import com.xpdustry.shard.oracle.*;

import java.lang.classfile.*;
import java.lang.classfile.instruction.*;
import java.lang.reflect.*;
import java.util.*;

/**
 * packets.json: every packet id and class, its fields in wire order, and for generated {@code Call} packets the
 * {@code Call} methods that send it, recovered from {@code mindustry.gen.Call}'s bytecode.
 */
final class PacketDump{
    static final String writeDesc = "(Larc/util/io/Writes;)V";

    @SuppressWarnings("unchecked")
    static Object dump() throws ReflectiveOperationException{
        Field classesField = Net.class.getDeclaredField("packetClasses");
        classesField.setAccessible(true);
        var classes = (Seq<Class<? extends Packet>>)classesField.get(null);
        Map<String, List<Object>> calls = calls();

        List<Object> packets = new ArrayList<>();
        for(int id = 0; id < classes.size; id++){
            Packet packet = Net.newPacket((byte)id);
            Class<?> type = packet.getClass();
            boolean generated = type.getName().startsWith("mindustry.gen.");
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", id);
            entry.put("class", type.getName());
            entry.put("kind", generated ? "call" : "framework");
            entry.put("priority", switch(packet.getPriority()){
                case Packet.priorityLow -> "low";
                case Packet.priorityHigh -> "high";
                default -> "normal";
            });
            List<String> accepted = new ArrayList<>();
            if(packet.allow(true)) accepted.add("server");
            if(packet.allow(false)) accepted.add("client");
            entry.put("acceptedBy", accepted);
            List<String> handled = new ArrayList<>();
            if(Layouts.declares(type, "handleServer", "(Lmindustry/net/NetConnection;)V")) handled.add("server");
            if(Layouts.declares(type, "handleClient", "()V")) handled.add("client");
            entry.put("handledBy", handled);

            Layouts.Layout write = Layouts.declared(type, "write", writeDesc);
            Layouts.Layout read = Layouts.declared(type, "handled", "()V");
            entry.put("fields", write == null ? List.of() : write.view());
            if(read != null) entry.put("read", read.view());
            if(generated) entry.put("calls", calls.getOrDefault(type.getName().replace('.', '/'), List.of()));
            packets.add(entry);
        }
        return Views.map(
            "note", "Ids are positions in Net.packetClasses: 7 framework packets, then Call.registerPackets() (sorted @Remote methods). "
                + "fields: what write() puts in the body, in order ('if' marks the net.server()/net.client() checks of Loc.both packets). "
                + "read: what handled() reads, in order. calls: the Call methods that send the packet: 'variant' all (net.send), "
                + "one (to a NetConnection), forward (server relays a client's call to the others); 'reliable' false means UDP; "
                + "'sentBy' is the side check before sending; 'local' is the method also run locally by the sender.",
            "count", packets.size(),
            "packets", packets);
    }

    /** Call methods by the packet class they create. */
    static Map<String, List<Object>> calls(){
        ClassModel call = Layouts.model(Call.class);
        Map<String, List<Object>> out = new TreeMap<>();
        for(MethodModel method : call.methods()){
            String name = method.methodName().stringValue();
            if(name.equals("registerPackets") || name.equals("<init>") || name.equals("<clinit>") || method.code().isEmpty()) continue;
            String descriptor = method.methodType().stringValue();

            String packet = null, local = null;
            Boolean reliable = null;
            String send = null;
            //side checks: the ones after the local call (if any) guard the send
            Set<String> sentBy = new LinkedHashSet<>();
            Instruction previous = null;
            for(CodeElement e : method.code().get()){
                switch(e){
                    case NewObjectInstruction n -> packet = n.className().asInternalName();
                    case InvokeInstruction inv -> {
                        String owner = inv.owner().asInternalName();
                        String target = inv.name().stringValue();
                        if(owner.equals("mindustry/net/Net") && (target.equals("server") || target.equals("client")) && packet == null){
                            sentBy.add(target);
                        }else if(inv.opcode() == Opcode.INVOKESTATIC && !owner.startsWith("mindustry/gen/") && target.equals(name.replace("__forward", ""))){
                            local = owner.replace('/', '.') + "." + target;
                            sentBy.clear();
                        }else if((owner.equals("mindustry/net/Net") || owner.equals("mindustry/net/NetConnection")) && target.startsWith("send")){
                            send = owner.substring(owner.lastIndexOf('/') + 1) + "." + target;
                            if(previous instanceof ConstantInstruction c) reliable = ((Number)c.constantValue()).intValue() != 0;
                        }
                    }
                    default -> {}
                }
                if(e instanceof Instruction i) previous = i;
            }
            if(packet == null) continue;

            List<String> params = Layouts.parameterTypes(descriptor);
            String variant = name.endsWith("__forward") ? "forward" : !params.isEmpty() && params.getFirst().equals("mindustry.net.NetConnection") ? "one" : "all";
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("method", name);
            entry.put("params", params);
            entry.put("variant", variant);
            entry.put("sentBy", new ArrayList<>(sentBy));
            entry.put("send", send);
            entry.put("reliable", reliable);
            entry.put("local", local);
            out.computeIfAbsent(packet.replace('.', '/'), k -> new ArrayList<>()).add(entry);
        }
        return out;
    }

    private PacketDump(){}
}
