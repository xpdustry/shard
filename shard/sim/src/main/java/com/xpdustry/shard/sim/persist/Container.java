package com.xpdustry.shard.sim.persist;

import java.nio.charset.*;
import java.util.*;
import java.util.zip.*;

/**
 * Shard's file layout for checkpoints and crash-bundle logs, little-endian throughout:
 *
 * <pre>
 * magic "SHRDSNAP"   u16 format version   u32 header length, header ({@link Header})
 * u32 section count
 * per section: u16 name length, ASCII name, u8 codec, u32 raw length, u32 stored length, u32 CRC32C of the raw bytes
 * the sections' stored bytes, in directory order
 * u32 CRC32C of everything above   magic "SHRD-END"
 * </pre>
 *
 * Decoding checks the footer first, so a truncated or partly written file fails before anything is inflated, then
 * inflates each section and checks its CRC. Lengths are checked against the file before anything is allocated.
 */
final class Container{
    /**
     * The format version, written right after the magic and checked before anything else is read. A file of another
     * version is refused at once, naming both: the PoC doesn't migrate checkpoints between builds (the lead, wave 4c).
     * Bump it with any change to what a checkpoint stores or how it reads it: a field added to a schema (W5.3's
     * {@code paused}, which made it 2; W6.1's unit grid fill hitboxes, 3; W6.8 dropping the grid's {@code gridBuilt}, 4), a section, or a part. Same version,
     * different layout is what the schema's field checks would otherwise catch, late and obscurely.
     */
    static final int FORMAT = 4;
    private static final byte[] MAGIC = "SHRDSNAP".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] END = "SHRD-END".getBytes(StandardCharsets.US_ASCII);
    private static final int MAX_SECTIONS = 1024;

    /** One section's raw bytes: {@code bytes[0 .. length)}. */
    record Section(String name, byte[] bytes, int length){
        Section{
            if(name.isEmpty() || name.length() > 255 || !StandardCharsets.US_ASCII.newEncoder().canEncode(name)){
                throw new IllegalArgumentException("bad section name " + name);
            }
        }
    }

    /** A decoded file: its header and its sections' raw bytes, checked. */
    record Decoded(Header header, SortedMap<String, byte[]> sections, int fileLength){
        byte[] section(String name){
            byte[] bytes = sections.get(name);
            if(bytes == null) throw new CheckpointException("the " + header.kind() + " has no section " + name);
            return bytes;
        }
    }

    static byte[] encode(Header header, List<Section> sections, Compression compression){
        var headerBytes = new Sink(256);
        header.write(headerBytes);
        var stored = new ArrayList<byte[]>(sections.size());
        var out = new Sink(1024);
        out.bytes(MAGIC, 0, MAGIC.length);
        out.s(FORMAT);
        out.i(headerBytes.size());
        out.bytes(headerBytes.buffer(), 0, headerBytes.size());
        out.i(sections.size());
        for(Section section : sections){
            byte[] compressed = compression.compress(section.bytes(), section.length());
            stored.add(compressed);
            byte[] name = section.name().getBytes(StandardCharsets.US_ASCII);
            out.s(name.length);
            out.bytes(name, 0, name.length);
            out.b(compressed == null ? Compression.RAW : compression.codec());
            out.i(section.length());
            out.i(compressed == null ? section.length() : compressed.length);
            out.i(crc(section.bytes(), 0, section.length()));
        }
        for(int i = 0; i < sections.size(); i++){
            byte[] compressed = stored.get(i);
            if(compressed == null) out.bytes(sections.get(i).bytes(), 0, sections.get(i).length());
            else out.bytes(compressed, 0, compressed.length);
        }
        out.i(crc(out.buffer(), 0, out.size()));
        out.bytes(END, 0, END.length);
        return out.toByteArray();
    }

    /** Reads only the header, after checking the footer: for listings and the store's verification. */
    static Header header(byte[] file){
        checkFooter(file);
        var source = new Source("header", file, 0, file.length - 12);
        prelude(source);
        return readHeader(source);
    }

    static Decoded decode(byte[] file){
        checkFooter(file);
        var source = new Source("directory", file, 0, file.length - 12);
        prelude(source);
        Header header = readHeader(source);
        int count = source.i();
        if(count < 0 || count > MAX_SECTIONS) throw new CheckpointException("the file claims " + count + " sections");
        var names = new String[count];
        var codecs = new int[count];
        var raw = new int[count];
        var lengths = new int[count];
        var crcs = new int[count];
        for(int i = 0; i < count; i++){
            int nameLength = source.s() & 0xFFFF;
            var name = new byte[source.length(nameLength, 1)];
            source.bytes(name, 0, nameLength);
            names[i] = new String(name, StandardCharsets.US_ASCII);
            codecs[i] = source.b() & 0xFF;
            raw[i] = source.i();
            lengths[i] = source.i();
            crcs[i] = source.i();
            if(raw[i] < 0 || lengths[i] < 0) throw new CheckpointException("section " + names[i] + " has a negative length");
        }
        int offset = file.length - 12 - source.remaining();
        var sections = new TreeMap<String, byte[]>();
        for(int i = 0; i < count; i++){
            if(lengths[i] > source.remaining()) throw new CheckpointException("section " + names[i] + " runs past the end of the file");
            byte[] bytes = Compression.decompress(codecs[i], file, offset, lengths[i], raw[i], names[i]);
            if(crc(bytes, 0, bytes.length) != crcs[i]) throw new CheckpointException("section " + names[i] + " fails its checksum");
            if(sections.put(names[i], bytes) != null) throw new CheckpointException("section " + names[i] + " appears twice");
            source.skip(lengths[i]);
            offset += lengths[i];
        }
        if(source.remaining() != 0) throw new CheckpointException(source.remaining() + " stray bytes before the footer");
        return new Decoded(header, sections, file.length);
    }

    private static void prelude(Source source){
        var magic = new byte[MAGIC.length];
        source.bytes(magic, 0, magic.length);
        if(!Arrays.equals(magic, MAGIC)) throw new CheckpointException("not a Shard checkpoint (bad magic)");
        int format = source.s() & 0xFFFF;
        if(format != FORMAT){
            throw new CheckpointException("this checkpoint has format version " + format + "; this build reads version " + FORMAT
                + " only (checkpoints aren't migrated between builds)");
        }
    }

    private static Header readHeader(Source source){
        int length = source.i();
        var bytes = new byte[source.length(length, 1)];
        source.bytes(bytes, 0, length);
        return Header.read(new Source("header", bytes));
    }

    private static void checkFooter(byte[] file){
        int minimum = MAGIC.length + 2 + 4 + 4 + 12;
        if(file.length < minimum) throw new CheckpointException("truncated: " + file.length + " bytes");
        if(!Arrays.equals(file, file.length - END.length, file.length, END, 0, END.length)){
            throw new CheckpointException("truncated or not a Shard checkpoint: the end marker is missing");
        }
        var footer = new Source("footer", file, file.length - 12, 4);
        if(footer.i() != crc(file, 0, file.length - 12)) throw new CheckpointException("the file fails its checksum");
    }

    static int crc(byte[] bytes, int offset, int length){
        var crc = new CRC32C();
        crc.update(bytes, offset, length);
        return (int)crc.getValue();
    }

    private Container(){}
}
