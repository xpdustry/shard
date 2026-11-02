package com.xpdustry.shard.server.admin;

import java.net.*;
import java.util.*;

/**
 * A CIDR block, IPv4 or IPv6. Vanilla's "subnet" bans are string prefixes ({@code ip::startsWith},
 * {@code Administration.java:136}), so {@code "1.2.3"} also bans {@code 1.2.30.4}; Shard matches real CIDR blocks
 * instead (note 34), and {@link #parse} rejects the prefix form with a hint.
 *
 * @param network the network address, host bits zero
 */
public record Subnet(InetAddress network, int prefix){
    public Subnet{
        int bits = network.getAddress().length * 8;
        if(prefix < 0 || prefix > bits) throw new IllegalArgumentException("prefix /" + prefix + " is out of range for " + network.getHostAddress());
        if(!Arrays.equals(mask(network.getAddress(), prefix), network.getAddress())){
            throw new IllegalArgumentException(network.getHostAddress() + "/" + prefix + " has host bits set");
        }
    }

    /**
     * Parses {@code 10.0.0.0/8} or {@code 2001:db8::/32}. With {@code lenient}, host bits are cleared instead of
     * refused ({@code 10.1.2.3/8} is {@code 10.0.0.0/8}).
     */
    public static Subnet parse(String text, boolean lenient){
        int slash = text.indexOf('/');
        if(slash < 0){
            throw new IllegalArgumentException("expected a CIDR block like 10.0.0.0/8, got '" + text + "'"
                + (text.endsWith(".") ? " (vanilla's prefix form; write it as CIDR)" : ""));
        }
        InetAddress address = Addresses.parse(text.substring(0, slash));
        int prefix;
        try{
            prefix = Integer.parseInt(text.substring(slash + 1));
        }catch(NumberFormatException e){
            throw new IllegalArgumentException("bad prefix length in '" + text + "'");
        }
        int bits = address.getAddress().length * 8;
        if(prefix < 0 || prefix > bits) throw new IllegalArgumentException("prefix /" + prefix + " is out of range in '" + text + "'");
        byte[] masked = mask(address.getAddress(), prefix);
        if(!lenient && !Arrays.equals(masked, address.getAddress())){
            throw new IllegalArgumentException("'" + text + "' has host bits set; the block is " + of(masked, prefix));
        }
        return of(masked, prefix);
    }

    public boolean contains(InetAddress address){
        byte[] bytes = Addresses.canonical(address).getAddress();
        if(bytes.length != network.getAddress().length) return false;
        return Arrays.equals(mask(bytes, prefix), network.getAddress());
    }

    /** {@code 10.0.0.0/8}, with the canonical address form. */
    @Override
    public String toString(){
        return Addresses.format(network) + "/" + prefix;
    }

    private static Subnet of(byte[] network, int prefix){
        try{
            return new Subnet(InetAddress.getByAddress(network), prefix);
        }catch(UnknownHostException e){
            throw new AssertionError(e);
        }
    }

    private static byte[] mask(byte[] address, int prefix){
        byte[] out = address.clone();
        for(int i = 0; i < out.length; i++){
            int keep = Math.clamp(prefix - i * 8, 0, 8);
            out[i] &= (byte)(0xff << (8 - keep));
        }
        return out;
    }
}
