package com.xpdustry.shard.wire;

import com.xpdustry.shard.common.io.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.*;
import org.junit.jupiter.params.provider.*;

import java.io.*;
import java.nio.charset.*;
import java.util.*;
import java.util.stream.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The connect packets v146, v159 and v160.7 clients write (fixtures recorded by
 * {@code tools/wirelift/fixtures/bootstrap/record.sh}), with the fields each build's server reads back.
 */
class BootstrapTest{
    /** One recorded case: the fields as {@code key=value} lines, mods in order. */
    record Fixture(String version, String name, Map<String, String> fields, List<String> mods, byte[] body){
        @Override
        public String toString(){
            return version + " " + name;
        }

        String string(String key){
            return unquote(fields.get(key));
        }
    }

    static List<Fixture> load(String version){
        var in = BootstrapTest.class.getResourceAsStream("/bootstrap/v" + version + ".txt");
        assertNotNull(in, "fixture " + version);
        List<Fixture> out = new ArrayList<>();
        Map<String, String> fields = null;
        List<String> mods = null;
        try(var reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.US_ASCII))){
            for(String line; (line = reader.readLine()) != null; ){
                if(line.isEmpty() || line.startsWith("#")) continue;
                int eq = line.indexOf('=');
                String key = line.substring(0, eq), value = line.substring(eq + 1);
                switch(key){
                    case "case" -> {
                        fields = new LinkedHashMap<>();
                        mods = new ArrayList<>();
                        fields.put(key, value);
                    }
                    case "mod" -> mods.add(unquote(value));
                    case "body" -> out.add(new Fixture(version, fields.get("case"), fields, mods, HexFormat.of().parseHex(value)));
                    default -> fields.put(key, value);
                }
            }
        }catch(IOException e){
            throw new UncheckedIOException(e);
        }
        return out;
    }

    /** {@code null}, or a quoted string with backslash escapes for quotes, backslashes and UTF-16 units. */
    static String unquote(String s){
        if(s.equals("null")) return null;
        assertTrue(s.length() >= 2 && s.startsWith("\"") && s.endsWith("\""), s);
        StringBuilder out = new StringBuilder();
        for(int i = 1; i < s.length() - 1; i++){
            char c = s.charAt(i);
            if(c != '\\'){
                out.append(c);
            }else if(s.charAt(i + 1) == 'u'){
                out.append((char)Integer.parseInt(s.substring(i + 2, i + 6), 16));
                i += 5;
            }else{
                out.append(s.charAt(++i));
            }
        }
        return out.toString();
    }

    static Stream<Fixture> fixtures(){
        return Stream.of("146", "159", "160.7").flatMap(v -> load(v).stream());
    }

    @Test
    void everyBuildHasItsCases(){
        assertEquals(9, fixtures().count());
        assertEquals(Set.of(146, 159, 160), fixtures().map(f -> Integer.parseInt(f.fields.get("build"))).collect(Collectors.toSet()));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("fixtures")
    void readsAsThatBuildsServer(Fixture f){
        assertEquals(Integer.parseInt(f.fields.get("build")), Bootstrap.build(f.body));
        Reads read = new Reads(f.body);
        Bootstrap.Connect c = Bootstrap.read(read);
        assertEquals(f.body.length, read.position());
        assertEquals(Integer.parseInt(f.fields.get("build")), c.build());
        assertEquals(f.string("versionType"), c.versionType());
        assertEquals(f.string("name"), c.name());
        assertEquals(f.string("locale"), c.locale());
        assertEquals(f.string("usid"), c.usid());
        assertEquals(f.string("uuid"), c.uuidString());
        assertTrue(c.crcMatches());
        assertEquals(Boolean.parseBoolean(f.fields.get("mobile")), c.mobile());
        assertEquals(Integer.parseInt(f.fields.get("color")), c.color());
        assertEquals(Integer.parseInt(f.fields.get("mods")), f.mods.size());
        assertEquals(f.mods, c.mods());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("fixtures")
    void writesAsThatBuildsClient(Fixture f){
        Bootstrap.Connect c = Bootstrap.read(new Reads(f.body));
        Writes write = new Writes();
        Bootstrap.write(write, Bootstrap.Connect.of(c.build(), c.versionType(), c.name(), c.locale(), c.usid(), Arrays.copyOf(c.uuid(), 8),
            c.mobile(), c.color(), c.mods()));
        assertArrayEquals(f.body, write.toByteArray());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("fixtures")
    void aCutBodyFails(Fixture f){
        for(int length = 0; length < f.body.length; length++){
            byte[] cut = Arrays.copyOf(f.body, length);
            assertThrows(WireException.class, () -> Bootstrap.read(new Reads(cut)), "cut at " + length);
        }
        assertThrows(WireException.class, () -> Bootstrap.build(new byte[3]));
    }

    @Test
    void trailingBytesAreLeft(){
        byte[] body = load("160.7").getFirst().body;
        byte[] longer = Arrays.copyOf(body, body.length + 5);
        Reads read = new Reads(longer);
        Bootstrap.read(read);
        assertEquals(body.length, read.position());
    }

    @Test
    void modCounts(){
        //the count is a signed byte: vanilla's Seq allocation fails below 0, and a client can't send over 127
        byte[] body = load("160.7").getFirst().body.clone();
        body[body.length - 1] = (byte)0x80;
        var e = assertThrows(WireException.class, () -> Bootstrap.read(new Reads(body)));
        assertTrue(e.getMessage().contains("-128"), e.getMessage());
        var c = Bootstrap.read(new Reads(load("160.7").getFirst().body));
        List<String> mods = Collections.nCopies(128, "m");
        var many = new Bootstrap.Connect(c.build(), c.versionType(), c.name(), c.locale(), c.usid(), c.uuid(), c.mobile(), c.color(), mods);
        Writes write = new Writes();
        assertThrows(WireException.class, () -> Bootstrap.write(write, many));
        assertEquals(0, write.size());
    }

    @Test
    void anyMobileByteButOneIsDesktop(){
        //vanilla reads mobile as b() == 1
        byte[] body = load("160.7").get(1).body.clone();
        Bootstrap.Connect c = Bootstrap.read(new Reads(body));
        assertTrue(c.mobile());
        int at = indexOf(body, new byte[]{1, (byte)0xFF, (byte)0xFF, (byte)0xFF, (byte)0xFF, 3});
        body[at] = 2;
        assertFalse(Bootstrap.read(new Reads(body)).mobile());
    }

    @Test
    void uuidsAndTheirCrc(){
        byte[] id = {1, 2, 3, 4, 5, 6, 7, (byte)0xF8};
        var c = Bootstrap.Connect.of(160, "official", "frog", "en", "", id, false, 0, List.of());
        assertEquals("AQIDBAUGB/gAAAAAgnd62Q==", c.uuidString());
        assertTrue(c.crcMatches());
        byte[] forged = c.uuid().clone();
        forged[15] ^= 1;
        assertFalse(new Bootstrap.Connect(160, null, null, null, null, forged, false, 0, List.of()).crcMatches());
        assertThrows(IllegalArgumentException.class, () -> Bootstrap.Connect.of(160, null, null, null, null, new byte[16], false, 0, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new Bootstrap.Connect(160, null, null, null, null, new byte[8], false, 0, List.of()));
    }

    static int indexOf(byte[] haystack, byte[] needle){
        outer:
        for(int i = 0; i + needle.length <= haystack.length; i++){
            for(int j = 0; j < needle.length; j++){
                if(haystack[i + j] != needle[j]) continue outer;
            }
            return i;
        }
        throw new AssertionError("not found");
    }
}
