package com.xpdustry.shard.wire;

/**
 * A packet in the registry: what the transport needs before it reads a body.
 *
 * @param id        the position in vanilla's registration order ({@code Net.getPacketId})
 * @param name      vanilla's class name without {@code CallPacket}; an overload's number goes last ({@code Kick2} is
 *                  {@code KickCallPacket2})
 * @param reliable  sent over TCP; false is UDP. Framework packets travel as TCP streams.
 * @param framework an Arc-level packet that parses itself in {@code read()} (the streams, {@code ConnectPacket}), with
 *                  no {@code Call} record: {@code :net} and {@link Bootstrap} read it
 */
public record PacketType(int id, String name, Direction direction, Priority priority, boolean reliable, boolean framework){
    /**
     * Who may read it, lifted from {@code Packet.allow(boolean server)}. Vanilla's serializer throws on any other
     * (ArcNetProvider.java:513), which drops the connection.
     */
    public enum Direction{
        /** Only a server reads it: a client's request. */
        toServer,
        /** Only a client reads it. */
        toClient,
        /** Both read it. A both-way {@code Call} packet's server copy carries the sender; a client's leaves it out. */
        both;

        /** {@code allow(true)}: whether a server reads it from a client. */
        public boolean toServerAllowed(){
            return this != toClient;
        }

        /** {@code allow(false)}: whether a client reads it from the server. */
        public boolean toClientAllowed(){
            return this != toServer;
        }
    }

    /**
     * {@code Packet.getPriority()}. Before a connection's {@code connectConfirm}, vanilla's server ignores everything
     * but {@link #high} packets (Net.java:346). While a client loads a world it handles {@link #high} ones, queues
     * {@link #normal} ones and drops {@link #low} ones (Net.java:325).
     */
    public enum Priority{
        low,
        normal,
        high
    }
}
