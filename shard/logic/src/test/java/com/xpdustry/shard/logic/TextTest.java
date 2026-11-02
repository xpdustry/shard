package com.xpdustry.shard.logic;

import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/** {@code print}, {@code format}, {@code printchar} and {@code printflush}. */
class TextTest{
    final TestHost host = new TestHost();

    private String print(String code){
        Processor p = host.processor(0, 0, code + "\nstop", false, 1000, 1000);
        host.run(2, p);
        return p.text().toString();
    }

    @Test
    void values(){
        assertEquals("1", print("print 1.0"));
        assertEquals("1.5", print("print 1.5"));
        assertEquals("null", print("print null"));
        assertEquals("copper", print("print @copper"));
        assertEquals("logic-processor", print("print @this"));
        assertEquals("health", print("print @health"));
        assertEquals("a\nb", print("print \"a\\nb\""));
        assertEquals("1", print("print 1.000001"));
    }

    @Test
    void format(){
        assertEquals("1 x {5}", print("print \"{1} {0} {5}\"\nformat \"x\"\nformat 1"));
        assertEquals("1 x 2", print("print \"{1} {0} {5}\"\nformat \"x\"\nformat 1\nformat 2\nformat 3"));
    }

    @Test
    void printchar(){
        assertEquals(0xF838, TestHost.copper.emoji(), "the client's icon font");
        assertEquals("A" + (char)TestHost.copper.emoji() + (char)TestHost.lead.emoji(), print("printchar 65\nprintchar @copper\nprintchar @lead"));
        assertEquals("", print("printchar null\nprintchar @sharded\nprintchar \"x\""), "objects other than content print nothing");
    }

    @Test
    void bufferCapsAt400(){
        assertEquals(400, print("print \"0123456789\"\njump 0 lessThan @counter 900").length());
    }

    @Test
    void printflushSendsAndClears(){
        TestHost.Building message = host.place(TestHost.message, 2, 0);
        Processor p = host.processor(0, 0, "print \"hi\"\nprintflush message1\nprint \"after\"\nstop");
        p.toggleLink(message);
        host.run(2, p);
        assertEquals("hi", message.text.toString());
        assertEquals("after", p.text().toString());
    }

    @Test
    void localeprint(){
        Processor p = host.processor(0, 0, "localeprint \"hello\"\nlocaleprint \"missing\"\nstop", true, 8, 1000);
        host.run(2, p);
        assertEquals("Hello!null", p.text().toString());
    }
}
