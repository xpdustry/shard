package com.xpdustry.shard.oracle;

/**
 * Vanilla could not decode some bytes. Says where: the input ({@code "world stream"}, a packet name, ...), the section,
 * and the byte offset in the decoded (for world streams: inflated) bytes.
 */
public final class DecodeException extends RuntimeException{
    /** What was being decoded, e.g. {@code "world stream"} or {@code "packet SendMessageCallPacket2#107"}. */
    public final String input;
    /** The section or field, e.g. {@code "map"} or {@code "entity #2 (id 7, class 31)"}. */
    public final String section;
    /** Where the section starts, and where reading stopped, in bytes from the start of the input. */
    public final long sectionOffset, offset;

    public DecodeException(String input, String section, long sectionOffset, long offset, String detail, Throwable cause){
        super(input + ": " + section + " (starts at byte " + sectionOffset + "): " + detail + " at byte " + offset
            + " (+" + (offset - sectionOffset) + " into the section)" + (cause == null ? "" : ": " + describe(cause)), cause);
        this.input = input;
        this.section = section;
        this.sectionOffset = sectionOffset;
        this.offset = offset;
    }

    /** The cause chain in one line: vanilla often wraps the real error. */
    static String describe(Throwable t){
        StringBuilder out = new StringBuilder();
        for(Throwable c = t; c != null; c = c.getCause() == c ? null : c.getCause()){
            if(!out.isEmpty()) out.append(" <- ");
            out.append(c.getClass().getSimpleName());
            if(c.getMessage() != null) out.append(": ").append(c.getMessage());
        }
        return out.toString();
    }
}
