package com.xpdustry.shard.server.admin;

import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class SubnetTest{
    @Test
    void addressesAreCanonical(){
        assertEquals("1.2.3.4", Addresses.canonical("1.2.3.4"));
        assertEquals("1.2.3.4", Addresses.canonical("::ffff:1.2.3.4"));
        assertEquals("1.2.3.4", Addresses.canonical(" 1.2.3.4 "));
        assertEquals("2001:db8::1", Addresses.canonical("2001:0DB8:0000:0000:0000:0000:0000:0001"));
        assertEquals("2001:db8::1", Addresses.canonical("[2001:db8::1]"));
        assertEquals("fe80::1", Addresses.canonical("fe80::1%1"));
        assertEquals("::1", Addresses.canonical("0:0:0:0:0:0:0:1"));
        assertEquals("::", Addresses.canonical("::"));
        assertEquals("2001:db8::", Addresses.canonical("2001:db8:0:0:0:0:0:0"));
        assertEquals("2001:db8:0:1:1:1:1:1", Addresses.canonical("2001:db8:0:1:1:1:1:1"), "a single zero group stays");
        assertEquals("2001:0:0:1::1", Addresses.canonical("2001:0:0:1:0:0:0:1"), "the longest zero run is shortened");
        for(String bad : List.of("", "localhost", "1.2.3", "1.2.3.256", "example.com", "1.2.3.4/8")){
            assertThrows(IllegalArgumentException.class, () -> Addresses.canonical(bad), bad);
        }
    }

    @Test
    void cidrBlocks(){
        var ten = Subnet.parse("10.0.0.0/8", false);
        assertEquals("10.0.0.0/8", ten.toString());
        assertTrue(ten.contains(Addresses.parse("10.200.1.1")));
        assertTrue(ten.contains(Addresses.parse("::ffff:10.0.0.1")));
        assertFalse(ten.contains(Addresses.parse("11.0.0.0")));
        assertFalse(ten.contains(Addresses.parse("::a00:1")), "IPv6 never matches an IPv4 block");

        var odd = Subnet.parse("192.168.4.0/22", false);
        assertTrue(odd.contains(Addresses.parse("192.168.7.255")));
        assertFalse(odd.contains(Addresses.parse("192.168.8.0")));

        assertTrue(Subnet.parse("0.0.0.0/0", false).contains(Addresses.parse("8.8.8.8")));
        assertTrue(Subnet.parse("1.2.3.4/32", false).contains(Addresses.parse("1.2.3.4")));
        var six = Subnet.parse("2001:DB8::/33", false);
        assertEquals("2001:db8::/33", six.toString());
        assertTrue(six.contains(Addresses.parse("2001:db8:7fff::1")));
        assertFalse(six.contains(Addresses.parse("2001:db8:8000::1")));

        assertEquals("10.0.0.0/8", Subnet.parse("10.1.2.3/8", true).toString());
        for(String bad : List.of("10.1.2.3/8", "10.0.0.0", "192.168.", "10.0.0.0/33", "::/129", "10.0.0.0/x", "host/8")){
            assertThrows(IllegalArgumentException.class, () -> Subnet.parse(bad, false), bad);
        }
    }
}
