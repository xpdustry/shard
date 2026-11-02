package com.xpdustry.shard.server.admin;

import org.junit.jupiter.api.*;

import java.lang.reflect.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Differential against Arc's {@code Strings.stripColors} from the vanilla jar (pure: only Arc's color table). */
class NamesTest{
    static Method arc;

    @BeforeAll
    static void arc() throws Exception{
        arc = Class.forName("arc.util.Strings").getMethod("stripColors", CharSequence.class);
    }

    static String arcStrip(String text) throws Exception{
        return (String)arc.invoke(null, text);
    }

    @Test
    void handPickedCasesMatchArc() throws Exception{
        var cases = List.of("", "plain", "[red]red[]", "[RED]x", "[Red]x", "[lightgray]a", "[light_gray]a", "[LIGHT_GRAY]a",
            "[#ff0000]a", "[#ff000080]a", "[#f]a", "[#ff00000000]a", "[#gg0000]a", "[[red]", "[[[red]x", "[]", "[", "]", "[red",
            "[unknown]x", "[accent]x", "a[#12]b", "[scarlet][[]", "[red]glyph", "[sky]", "trailing[");
        for(String text : cases) assertEquals(arcStrip(text), Names.stripColors(text), text);
        assertEquals("[accent]x", Names.stripColors("[accent]x"), "client-only colors aren't known to a server");
    }

    @Test
    void randomMarkupMatchesArc() throws Exception{
        var random = new Random(160_7);
        String[] pieces = {"[", "]", "[[", "red", "RED", "#", "ff", "0a", "x", " ", "[]", "[#", "light_gray", "lightgray", "é", ""};
        for(int i = 0; i < 20_000; i++){
            var text = new StringBuilder();
            int length = random.nextInt(8);
            for(int j = 0; j < length; j++) text.append(pieces[random.nextInt(pieces.length)]);
            assertEquals(arcStrip(text.toString()), Names.stripColors(text), text.toString());
        }
    }

    @Test
    void plainNames(){
        assertEquals("foobar", Names.plain("  [red]Foo[]BAR "));
    }

    /** Vanilla's {@code NetServer.fixName}, on an instance whose constructor never ran: the method reads no fields. */
    static String vanillaFix(String name) throws Exception{
        if(netServer == null){
            Field field = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");
            field.setAccessible(true);
            Object unsafe = field.get(null);
            Class<?> type = Class.forName("mindustry.core.NetServer");
            netServer = unsafe.getClass().getMethod("allocateInstance", Class.class).invoke(unsafe, type);
            fixName = type.getMethod("fixName", String.class);
        }
        return (String)fixName.invoke(netServer, name);
    }

    static Object netServer;
    static Method fixName;

    @Test
    void fixedNamesMatchVanilla() throws Exception{
        var cases = List.of("", " ", "[", "]", "  alpha  ", "a\tb\nc", "[red]alpha", "[clear]ghost", "[CLEAR]ghost", "[#ff000000]x",
            "[#ff0000fe]x", "[#ff0000ff]x", "[ff000000]x", "[#ff]x", "[]x", "[[clear]x", "x[clear]y", "[red][clear]z", "[clear][clear]",
            "[gg0000zz]x", "[red]x]y", "0123456789012345678901234567890123456789overflow", "éééééééééééééééééééééééé", "[");
        for(String name : cases) assertEquals(vanillaFix(name), Names.fix(name), name);
        var random = new Random(40);
        String[] pieces = {"[", "]", "clear", "CLEAR", "#", "ff", "00", "80", "red", "x", " ", "\t", "é", "[]", "zz"};
        for(int i = 0; i < 20_000; i++){
            var text = new StringBuilder();
            int length = random.nextInt(10);
            for(int j = 0; j < length; j++) text.append(pieces[random.nextInt(pieces.length)]);
            assertEquals(vanillaFix(text.toString()), Names.fix(text.toString()), text.toString());
        }
        assertEquals("", Names.fix(null));
    }
}
