package com.xpdustry.shard.compat.v160.rules;

import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

class ArcJsonTest{
    @Test
    void quotesValuesAsMinimalOutput(){
        assertEquals("hello world", ArcJson.quoteValue("hello world"));
        assertEquals("\"\"", ArcJson.quoteValue(""));
        assertEquals("\"a,b\"", ArcJson.quoteValue("a,b"));
        assertEquals("\"true\"", ArcJson.quoteValue("true"));
        assertEquals("\"trailing \"", ArcJson.quoteValue("trailing "));
        assertEquals("\"a//b\"", ArcJson.quoteValue("a//b"));
        assertEquals("\"[x]\"", ArcJson.quoteValue("[x]"));
        // only the first character may not be a quote
        assertEquals("say \"hi\"", ArcJson.quoteValue("say \"hi\""));
        assertEquals("\"\\\"hi\\\"\"", ArcJson.quoteValue("\"hi\""));
        assertEquals("line\\nbreak", ArcJson.quoteValue("line\nbreak"));
    }

    @Test
    void quotesNamesAsMinimalOutput(){
        assertEquals("waveSpacing", ArcJson.quoteName("waveSpacing"));
        assertEquals("a b", ArcJson.quoteName("a b"));
        assertEquals("\"a:b\"", ArcJson.quoteName("a:b"));
        assertEquals("\" lead\"", ArcJson.quoteName(" lead"));
    }

    @Test
    void writesNumbersAsArc(){
        assertEquals("1", ArcJson.number(1f));
        assertEquals("0", ArcJson.number(-0f));
        assertEquals("0.1", ArcJson.number(0.1f));
        assertEquals("1.5E-5", ArcJson.number(1.5e-5f));
        assertEquals("7200", ArcJson.number(7200f));
        assertEquals("2147483648", ArcJson.number((float)Integer.MAX_VALUE));
    }

    @Test
    void parsesLenientInput(){
        JsonValue value = ArcJson.parse("""
            // a comment
            {name: hello world, n: 1.5, list: [a, 2, true, null], /* inline */ nested: {x: -3}
            "quoted name": "v\\u0041"
            neg: -1e3, hex: 0x10}
            """);
        assertEquals("hello world", value.getString("name", null));
        assertEquals(1.5f, value.getFloat("n", 0f));
        assertEquals(JsonValue.Type.LONG, value.get("list").children().get(1).type());
        assertTrue(value.get("list").children().get(3).isNull());
        assertEquals(-3, value.get("nested").getInt("x", 0));
        assertEquals("vA", value.getString("quoted name", null));
        assertEquals(JsonValue.Type.DOUBLE, value.get("neg").type());
        assertEquals(JsonValue.Type.STRING, value.get("hex").type());
        // Arc's get ignores case
        assertEquals("hello world", value.getString("NAME", null));
    }

    @Test
    void writesParsedTreesBack(){
        String json = "{a:1,b:[x,\"y,z\",2.5],c:{d:null,e:true},f:\"\"}";
        assertEquals(json, ArcJson.write(ArcJson.parse(json)));
    }

    @Test
    void rejectsBrokenInput(){
        assertThrows(IllegalArgumentException.class, () -> ArcJson.parse("{a:1"));
        assertThrows(IllegalArgumentException.class, () -> ArcJson.parse("[1,2] x"));
        assertThrows(IllegalArgumentException.class, () -> ArcJson.parse("\"open"));
    }
}
