package com.xpdustry.shard.server.admin;

import java.net.*;
import java.util.regex.*;

/**
 * Canonical IP addresses, so one address has one stored and compared form: IPv4 dotted, IPv6 in RFC 5952 short form,
 * and an IPv4-mapped IPv6 address ({@code ::ffff:1.2.3.4}) as its IPv4 address. Never resolves a host name.
 */
public final class Addresses{
    /** IPv4 in four dotted parts only: the JDK also takes {@code 1.2.3} as {@code 1.2.0.3}, a surprising ban target. */
    static final Pattern IPV4 = Pattern.compile("\\d{1,3}(\\.\\d{1,3}){3}");

    /** Parses an IP literal; throws {@link IllegalArgumentException} for anything else. */
    public static InetAddress parse(String text){
        String literal = text.strip();
        if(literal.startsWith("[") && literal.endsWith("]")) literal = literal.substring(1, literal.length() - 1);
        int zone = literal.indexOf('%');
        if(zone >= 0) literal = literal.substring(0, zone);
        if(literal.indexOf(':') < 0 && !IPV4.matcher(literal).matches()) throw new IllegalArgumentException("expected an IP address, got '" + text + "'");
        try{
            return canonical(InetAddress.ofLiteral(literal));
        }catch(IllegalArgumentException e){
            throw new IllegalArgumentException("expected an IP address, got '" + text + "'");
        }
    }

    /** Whether {@code text} is an IP literal {@link #parse} takes. */
    public static boolean isAddress(String text){
        try{
            parse(text);
            return true;
        }catch(IllegalArgumentException e){
            return false;
        }
    }

    /** The canonical string of an IP literal. */
    public static String canonical(String text){
        return format(parse(text));
    }

    static InetAddress canonical(InetAddress address){
        byte[] bytes = address.getAddress();
        if(bytes.length == 16 && isMapped(bytes)){
            try{
                return InetAddress.getByAddress(new byte[]{bytes[12], bytes[13], bytes[14], bytes[15]});
            }catch(UnknownHostException e){
                throw new AssertionError(e);
            }
        }
        if(address instanceof Inet6Address six && six.getScopeId() != 0){
            try{
                return InetAddress.getByAddress(bytes);
            }catch(UnknownHostException e){
                throw new AssertionError(e);
            }
        }
        return address;
    }

    /** IPv4 dotted, or IPv6 with the longest run of zero groups as {@code ::} and lower-case hex (RFC 5952). */
    public static String format(InetAddress address){
        InetAddress canonical = canonical(address);
        byte[] bytes = canonical.getAddress();
        if(bytes.length == 4) return canonical.getHostAddress();
        int[] groups = new int[8];
        for(int i = 0; i < 8; i++) groups[i] = (bytes[2 * i] & 0xff) << 8 | bytes[2 * i + 1] & 0xff;
        int bestStart = -1, bestLength = 0;
        for(int i = 0; i < 8; ){
            if(groups[i] != 0){
                i++;
                continue;
            }
            int start = i;
            while(i < 8 && groups[i] == 0) i++;
            if(i - start > bestLength && i - start >= 2){
                bestStart = start;
                bestLength = i - start;
            }
        }
        var out = new StringBuilder();
        for(int i = 0; i < 8; i++){
            if(i == bestStart){
                out.append("::");
                i += bestLength - 1;
                continue;
            }
            if(!out.isEmpty() && out.charAt(out.length() - 1) != ':') out.append(':');
            out.append(Integer.toHexString(groups[i]));
        }
        return out.toString();
    }

    private static boolean isMapped(byte[] bytes){
        for(int i = 0; i < 10; i++) if(bytes[i] != 0) return false;
        return bytes[10] == (byte)0xff && bytes[11] == (byte)0xff;
    }

    private Addresses(){}
}
