package com.xpdustry.shard.server.intent;

import com.xpdustry.shard.common.geom.*;
import com.xpdustry.shard.common.io.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.world.*;
import com.xpdustry.shard.wire.*;
import com.xpdustry.shard.wire.v160_7.AdminAction;
import com.xpdustry.shard.wire.v160_7.Packets;
import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Client packets into intents, with bounded decoding: every client Call has a handler; a body as vanilla's client
 * writes it decodes; any prefix or seeded mutation of one is an intent or a {@link WireException} and nothing else;
 * and the intents of mutated bodies admit without failing, go into a crash bundle and replay to the same world.
 */
class IntentDecodeTest{
    /** Client Calls the connection handles, not Intents. */
    static final Map<Integer, String> ELSEWHERE = Map.of(
        Packets.CONNECT_PACKET, "ConnectFlow: the handshake",
        Packets.CONNECT_CONFIRM, "ConnectFlow: the session's confirm",
        Packets.SEND_CHAT_MESSAGE, "ConnectFlow: chat",
        Packets.PING, "ConnectFlow: answered on the connection",
        Packets.REQUEST_ASSETS, "the world stream: asset negotiation before the world is sent",
        Packets.REQUEST_WORLD, "the world stream: a client asking for the world again");
    static final int CASES = Integer.getInteger("com.xpdustry.shard.server.intent.fuzzCases", 400);
    static final Policy POLICY = new Policy(true, false, 6_000, 25, 60);

    IntentWorld w;
    Player alpha, beta;
    Building router, container;
    Intents.Context context;

    @BeforeEach
    void world(){
        w = new IntentWorld();
        alpha = w.joinWith(7, "alpha", "alpha", 100f, 100f);
        beta = w.joinWith(8, "beta", "dagger", 140f, 100f);
        router = w.place("router", 14, 12, alpha.team, 0);
        container = w.place("container", 10, 10, alpha.team, 0);
        container.items.add(IntentWorld.item("copper").id(), 100);
        // copper ore by alpha, for the item seeds' mining snapshot
        w.world.tiles().overlay(w.world.tiles().pos(13, 13), IntentWorld.VANILLA.block("ore-copper").id());
        w.world.tick();
        w.drain();
        context = Intents.Context.of(w.world.config(), POLICY);
    }

    /** One body per decoded Call, as vanilla's client writes it, aimed at this world's things. */
    Map<Integer, Packet> samples(){
        int unit = alpha.unit().id(), other = beta.unit().id(), pos = router.pos();
        int conveyor = IntentWorld.VANILLA.block("conveyor").id(), copper = IntentWorld.item("copper").id();
        var values = new LinkedHashMap<String, Object>();
        values.put("name", "alpha");
        values.put("ok", true);
        values.put("x", 2.5f);
        List<Packet> all = List.of(
            new Packets.ClientSnapshot(3, unit, false, 102f, 100f, 140f, 100f, 10f, 0f, 1f, 0f, -1, false, true, false, true, (short)conveyor, 1,
                List.of(new Codecs.Plan(false, Point2.pack(20, 20), conveyor, 1, 2), Codecs.Plan.breaking(router.pos()),
                    new Codecs.Plan(false, Point2.pack(21, 20), IntentWorld.VANILLA.block("sorter").id(), 0, new WireObjects.ContentRef(0, copper))),
                90f, 90f, 400f, 300f),
            new Packets.TileConfig(-1, pos, 2),
            new Packets.RotateBlock(-1, pos, true),
            new Packets.TileTap(-1, pos),
            new Packets.RequestBlockSnapshot(pos),
            new Packets.DeletePlans(-1, new int[]{Point2.pack(20, 20), Point2.pack(3, 3)}),
            new Packets.ClientPlanSnapshot(5, List.of(new Codecs.ClientPlan(20, 20, conveyor, 1, null), new Codecs.ClientPlan(21, 20, conveyor, 0, 3))),
            new Packets.UnitControl(-1, new Codecs.UnitRef(2, other)),
            new Packets.UnitClear(-1),
            new Packets.CommandUnits(-1, new int[]{other}, -1, Codecs.UnitRef.NONE, new Codecs.Vec2(160f, 120f), false, true),
            new Packets.SetUnitCommand(-1, new int[]{other}, 0),
            new Packets.SetUnitStance(-1, new int[]{other}, 1, true),
            new Packets.CommandBuilding(-1, new int[]{pos}, new Codecs.Vec2(10f, 10f)),
            new Packets.BuildingControlSelect(-1, pos),
            new Packets.RequestItem(-1, container.pos(), (short)copper, 10),
            new Packets.TransferInventory(-1, container.pos()),
            new Packets.DropItem(45f),
            new Packets.PingLocation(-1, 100f, 100f, "here"),
            new Packets.SetPlayerTeamEditor(-1, 2),
            new Packets.AdminRequest(beta.id(), AdminAction.trace, null),
            new Packets.RequestUnitPayload(-1, new Codecs.UnitRef(2, other)),
            new Packets.RequestBuildPayload(-1, pos),
            new Packets.RequestDropPayload(-1, 100f, 100f),
            new Packets.MenuChoose(-1, 3, 1),
            new Packets.TextInputResult(-1, 4, "hi"),
            new Packets.MenuBuilderChoose(-1, 5, new Codecs.MenuResult(9L, "ok", values)),
            new Packets.ServerPacketReliable("shard:t", "text"),
            new Packets.ServerPacketUnreliable("shard:t", "text"),
            new Packets.ServerBinaryPacketReliable("shard:b", new byte[]{1, 2, 3}),
            new Packets.ServerBinaryPacketUnreliable("shard:b", new byte[]{4}),
            new Packets.ClientLogicDataReliable("channel", 5),
            new Packets.ClientLogicDataUnreliable("channel", "five"),
            new Packets.RequestDebugStatus());
        var out = new TreeMap<Integer, Packet>();
        for(Packet packet : all) assertNull(out.put(packet.packetId(), packet), "two samples of " + packet.packetId());
        return out;
    }

    /**
     * Review 39: a {@code tileConfig} of every tag {@code readObjectSafe} admits, nested in an object array too, aimed
     * at the router and the players' units, besides the sample's int.
     */
    List<Packet> configSeeds(){
        int pos = router.pos(), unit = alpha.unit().id(), copper = IntentWorld.item("copper").id();
        Object[] values = {null, 1, 2L, 3f, "four", new WireObjects.ContentRef(0, copper), new WireObjects.IntSeqValue(new int[]{6}),
            new Point2(7, 7), new Point2[]{new Point2(8, 8)}, new WireObjects.TechNodeRef(0, copper), true, 11d, new WireObjects.BuildingRef(pos),
            new WireObjects.LAccessRef(13), new byte[]{14}, new boolean[]{true}, new WireObjects.UnitIdRef(unit),
            new Codecs.Vec2[]{new Codecs.Vec2(18f, 18f)}, new Codecs.Vec2(19f, 19f), new WireObjects.TeamRef(2), new int[]{21},
            new Object[]{null, 22, new WireObjects.BuildingRef(container.pos()), new WireObjects.UnitIdRef(beta.unit().id()), new WireObjects.TeamRef(1),
                new WireObjects.TechNodeRef(0, copper), new int[]{22}, new WireObjects.UnitCommandRef(1)},
            new WireObjects.UnitCommandRef(0)};
        var out = new ArrayList<Packet>();
        for(Object value : values) out.add(new Packets.TileConfig(-1, pos, value));
        return out;
    }

    /**
     * W3.7: item Calls whose effects run, as seeds: a snapshot of alpha mining the copper ore by it, a request for more
     * than its stack holds, a deposit into a router (which takes none) and one into the container, a drop.
     */
    List<Packet> itemSeeds(){
        int copper = IntentWorld.item("copper").id();
        return List.of(
            mining(4, Point2.pack(13, 13)),
            new Packets.RequestItem(-1, container.pos(), (short)copper, 1000),
            new Packets.TransferInventory(-1, router.pos()),
            new Packets.TransferInventory(-1, container.pos()),
            new Packets.DropItem(90f));
    }

    /** Alpha's snapshot {@code id}, standing still, mining {@code tile} (-1 for none). */
    Packet mining(int id, int tile){
        return new Packets.ClientSnapshot(id, alpha.unit().id(), false, 100f, 100f, 104f, 104f, 0f, 0f, 0f, 0f, tile, false, false, false, false,
            (short)-1, 0, List.of(), 90f, 90f, 400f, 300f);
    }

    byte[] body(Packet packet){
        var write = new Writes();
        packet.write(write, false, context.wire());
        return write.toByteArray();
    }

    /** {@link Intents#decode}, which must throw nothing but {@link WireException}. */
    Intent decode(int id, byte[] body){
        try{
            return Intents.decode(id, body, context);
        }catch(WireException e){
            return null;
        }catch(Throwable t){
            throw new AssertionError(Packets.TYPES.get(id).name() + " threw " + t + " on " + HexFormat.of().formatHex(body), t);
        }
    }

    @Test
    void everyClientCallHasAHandler(){
        var decoded = new TreeSet<>(Intents.decoded());
        var expected = new TreeSet<Integer>();
        for(PacketType type : Packets.TYPES){
            if(type.direction().toServerAllowed() && !ELSEWHERE.containsKey(type.id())) expected.add(type.id());
        }
        assertEquals(expected, decoded);
        for(int id : ELSEWHERE.keySet()) assertNull(Intents.decode(id, new byte[0], context), "Intents leaves " + id + " alone");
    }

    @Test
    void everySampleDecodes(){
        Map<Integer, Packet> samples = samples();
        assertEquals(new TreeSet<>(Intents.decoded()), samples.keySet(), "a sample per decoder");
        samples.forEach((id, packet) -> assertNotNull(Intents.decode(id, body(packet), context), Packets.TYPES.get(id).name()));
    }

    @Test
    void aCustomPacketWithoutATypeDecodesToNothing(){
        // W5.5's fuzzer: a binary packet with a null type reached Ui.custom, whose handler map threw on the null key
        // every tick it came in; text packets already dropped it
        for(Packet packet : List.<Packet>of(new Packets.ServerBinaryPacketReliable(null, new byte[]{1}), new Packets.ServerBinaryPacketUnreliable(null, new byte[]{1}),
            new Packets.ServerPacketReliable(null, "x"), new Packets.ServerPacketUnreliable(null, "x"))){
            assertNull(decode(packet.packetId(), body(packet)), Packets.TYPES.get(packet.packetId()).name());
        }
    }

    @Test
    void anUnknownIdDecodesToNothing(){
        assertNull(Intents.decode(-1, new byte[8], context));
        assertNull(Intents.decode(Packets.SET_POSITION, new byte[8], context), "a server's call");
        assertNull(Intents.decode(4096, new byte[8], context));
    }

    @Test
    void everyPrefixIsAWireExceptionOrAnIntent(){
        samples().forEach((id, packet) -> {
            byte[] body = body(packet);
            for(int length = 0; length < body.length; length++) decode(id, Arrays.copyOf(body, length));
        });
        for(Packet packet : configSeeds()){
            byte[] body = body(packet);
            for(int length = 0; length < body.length; length++) decode(packet.packetId(), Arrays.copyOf(body, length));
        }
        for(Packet packet : itemSeeds()){
            byte[] body = body(packet);
            for(int length = 0; length < body.length; length++) decode(packet.packetId(), Arrays.copyOf(body, length));
        }
    }

    /** The item seeds reach their effects: alpha mines the ore into its stack, takes, deposits and drops. */
    @Test
    void itemSeedsReachTheirEffects(){
        List<Packet> seeds = itemSeeds();
        Unit unit = alpha.unit();
        int copper = IntentWorld.item("copper").id();
        assertEquals(Verdict.ADMITTED, w.admit(7, decode(seeds.get(0).packetId(), body(seeds.get(0))), 1_000));
        assertEquals(Point2.pack(13, 13), unit.mineTile, "the snapshot's tile");
        for(int i = 0; i < 300 && unit.stackAmount < 3; i++) w.world.tick();
        assertTrue(unit.stackAmount >= 3, "alpha mined copper: " + unit.stackAmount);
        assertEquals(copper, unit.item());
        // stop, and let the last item land
        Packet stop = mining(5, -1);
        assertEquals(Verdict.ADMITTED, w.admit(7, decode(stop.packetId(), body(stop)), 1_500));
        for(int i = 0; i < 15; i++) w.world.tick();
        Packet request = seeds.get(1), router = seeds.get(2), deposit = seeds.get(3), drop = seeds.get(4);
        long at = 2_000;
        for(Packet seed : List.of(request, router, deposit, request, drop)){
            assertEquals(Verdict.ADMITTED, w.admit(7, decode(seed.packetId(), body(seed)), at += 1_000), seed.toString());
            if(seed == request) assertEquals(unit.itemCapacity(), unit.stackAmount, "the request filled the stack");
            // a router takes no stacks (Router.java:76): the Call goes out with 0
            if(seed == router) assertEquals(unit.itemCapacity(), unit.stackAmount, "the router took none");
            if(seed == deposit || seed == drop) assertEquals(0, unit.stackAmount, seed + " emptied it");
        }
        w.drain();
    }

    /** Every safe config value admits, as vanilla's {@code tileConfig} does, and is forwarded once. */
    @Test
    void everySafeConfigValueIsAdmittedAndForwardedOnce(){
        for(Packet packet : configSeeds()){
            Intent intent = decode(packet.packetId(), body(packet));
            assertNotNull(intent, packet.toString());
            assertEquals(Verdict.ADMITTED, w.admit(7, intent, 1_000), packet.toString());
            List<IntentEvent> events = w.drain();
            assertEquals(1, events.size(), packet + ": " + events);
            assertInstanceOf(IntentCall.AllExcept.class, events.getFirst(), packet.toString());
        }
    }

    @Test
    void anOversizedCountIsRefusedBeforeItIsAllocated(){
        // DeletePlans and CommandUnits: a short count, then that many ints; 32,767 of them in 4 bytes of body
        var write = new Writes();
        write.s(Short.MAX_VALUE);
        write.i(1);
        byte[] body = write.toByteArray();
        assertThrows(WireException.class, () -> Intents.decode(Packets.DeletePlans.ID, body, context));
        assertThrows(WireException.class, () -> Intents.decode(Packets.CommandUnits.ID, body, context));
    }

    @Test
    void retainedBytesAreBoundedByTheBody(){
        var rnd = new Random(3);
        samples().forEach((id, packet) -> {
            byte[] body = body(packet);
            for(int i = 0; i < CASES; i++){
                byte[] mutated = mutate(rnd, body);
                Intent intent = decode(id, mutated);
                if(intent != null) assertTrue(retained(intent) <= mutated.length + 64,
                    () -> Packets.TYPES.get(id).name() + " kept " + retained(intent) + " bytes of a " + mutated.length + "-byte body");
            }
        });
    }

    /** The bytes and array slots an intent holds, roughly. */
    static long retained(Object value){
        return switch(value){
            case null -> 0;
            case byte[] bytes -> bytes.length;
            case int[] ints -> 4L * ints.length;
            case String s -> s.length();
            case Object[] array -> {
                long sum = array.length;
                for(Object element : array) sum += retained(element);
                yield sum;
            }
            case List<?> list -> {
                long sum = list.size();
                for(Object element : list) sum += retained(element);
                yield sum;
            }
            case BuildPlan plan -> 4 + retained(plan.config);
            case Record record -> {
                long sum = 0;
                for(var component : record.getClass().getRecordComponents()){
                    try{
                        var accessor = component.getAccessor();
                        accessor.setAccessible(true);
                        sum += retained(accessor.invoke(record));
                    }catch(ReflectiveOperationException e){
                        throw new AssertionError(e);
                    }
                }
                yield sum;
            }
            default -> 0;
        };
    }

    /**
     * Seeded mutations of every sample, admitted one per tick: none fails its admission, and the world's log of them,
     * stored in a crash bundle, replays to the same hash.
     */
    @Test
    void mutatedIntentsAdmitStoreAndReplay(){
        var rnd = new Random(14);
        Map<Integer, Packet> samples = samples();
        var bodies = new ArrayList<Map.Entry<Integer, byte[]>>();
        samples.forEach((id, packet) -> bodies.add(Map.entry(id, body(packet))));
        for(Packet packet : configSeeds()) bodies.add(Map.entry(packet.packetId(), body(packet)));
        for(Packet packet : itemSeeds()) bodies.add(Map.entry(packet.packetId(), body(packet)));
        byte[] checkpoint = Checkpoint.capture(w.world).encode(Compression.FAST);
        int admitted = 0, decoded = 0;
        for(int i = 0; i < CASES * bodies.size() / 4; i++){
            var seed = bodies.get(rnd.nextInt(bodies.size()));
            byte[] body = rnd.nextInt(4) == 0 ? seed.getValue() : mutate(rnd, seed.getValue());
            Intent intent = decode(seed.getKey(), body);
            if(intent == null) continue;
            decoded++;
            Verdict verdict = w.admit(rnd.nextBoolean() ? 7 : 8, intent, 1_000 + i * 16L);
            if(verdict instanceof Verdict.Rejected(String reason)){
                assertFalse(reason.startsWith("failed"), () -> intent + ": " + reason);
            }else{
                admitted++;
            }
            w.drain();
        }
        assertTrue(admitted > CASES, "admitted " + admitted + " of " + decoded);
        var marks = List.of(new CrashBundle.Mark(w.world.ticks(), w.world.hash()));
        CrashBundle bundle = CrashBundle.decode(CrashBundle.of(checkpoint, w.world, marks, -1, null, new TreeMap<>()).encode(), IntentWorld.VANILLA);
        var out = new StringBuilder();
        Replay.Result result = Replay.run(bundle, w.config(), Replay.Options.DEFAULT,
            line -> out.append(line).append('\n'));
        assertTrue(result.ok(), result.divergence() + "\n" + out);
        assertEquals(1, result.hashes());
    }

    static final int[] INTERESTING = {-1, 0, 1, 2, 127, 128, 255, 256, 1000, 32767, 32768, 65535, 65536, Integer.MAX_VALUE, Integer.MIN_VALUE};

    static byte[] mutate(Random rnd, byte[] seed){
        byte[] body = seed.clone();
        for(int round = 1 + rnd.nextInt(3); round > 0; round--){
            body = switch(rnd.nextInt(6)){
                case 0 -> {
                    if(body.length > 0) body[rnd.nextInt(body.length)] ^= (byte)(1 << rnd.nextInt(8));
                    yield body;
                }
                case 1 -> {
                    if(body.length > 0) body[rnd.nextInt(body.length)] = (byte)new int[]{0, 1, 0x7F, 0x80, 0xFE, 0xFF}[rnd.nextInt(6)];
                    yield body;
                }
                case 2 -> Arrays.copyOf(body, rnd.nextInt(body.length + 1));
                case 3 -> {
                    // a count, length or id: an interesting number over a 1-, 2- or 4-byte window
                    int width = new int[]{1, 2, 4}[rnd.nextInt(3)];
                    if(body.length < width) yield body;
                    int at = rnd.nextInt(body.length - width + 1), value = INTERESTING[rnd.nextInt(INTERESTING.length)];
                    for(int b = 0; b < width; b++) body[at + b] = (byte)(value >>> 8 * (width - 1 - b));
                    yield body;
                }
                case 4 -> {
                    byte[] longer = Arrays.copyOf(body, body.length + 1 + rnd.nextInt(8));
                    for(int b = body.length; b < longer.length; b++) longer[b] = (byte)rnd.nextInt(256);
                    yield longer;
                }
                default -> {
                    // a float or int field off the map, or not finite
                    if(body.length < 4) yield body;
                    int at = rnd.nextInt(body.length - 3);
                    int bits = Float.floatToRawIntBits(new float[]{Float.NaN, Float.POSITIVE_INFINITY, -1e30f, 1e9f, -0f}[rnd.nextInt(5)]);
                    for(int b = 0; b < 4; b++) body[at + b] = (byte)(bits >>> 8 * (3 - b));
                    yield body;
                }
            };
        }
        return body;
    }
}
