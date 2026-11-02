package com.xpdustry.shard.compat.v160.rules;

import java.io.*;
import java.nio.charset.*;
import java.util.*;

/**
 * Arc's UBJSON, as {@code JsonIO.writeBytes} and {@code readBytes} use it for the markers of saves and world streams
 * ({@code MapMarkers.write}): {@link Writer} is {@code UBJsonWriter}, {@link #parse} is {@code UBJsonReader}, into
 * the same {@link JsonValue}s the text reader makes, numbers without text.
 */
public final class UbJson{
    private UbJson(){
    }

    /** {@code UBJsonWriter}: values by boxed type, names and strings with the shortest of the {@code i/I/l} lengths. */
    public static final class Writer implements JsonSink{
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();
        private final ArrayDeque<Boolean> stack = new ArrayDeque<>(); // isArray
        private boolean named;

        @Override
        public Writer name(String name){
            if(stack.isEmpty() || stack.peek()) throw new IllegalStateException("current item must be an object");
            length(name.getBytes(StandardCharsets.UTF_8));
            named = true;
            return this;
        }

        @Override
        public Writer object(){
            checkName();
            out.write('{');
            stack.push(false);
            return this;
        }

        @Override
        public Writer array(){
            checkName();
            out.write('[');
            stack.push(true);
            return this;
        }

        @Override
        public Writer pop(){
            if(named) throw new IllegalStateException("expected a value after a name");
            out.write(stack.pop() ? ']' : '}');
            return this;
        }

        @Override
        public Writer value(Object value){
            checkName();
            switch(value){
                case null -> out.write('Z');
                case Byte b -> {
                    out.write('i');
                    out.write(b);
                }
                case Short s -> {
                    out.write('I');
                    short16(s);
                }
                case Integer i -> {
                    out.write('l');
                    int32(i);
                }
                case Long l -> {
                    out.write('L');
                    int64(l);
                }
                case Float f -> {
                    out.write('d');
                    int32(Float.floatToIntBits(f));
                }
                case Double d -> {
                    out.write('D');
                    int64(Double.doubleToLongBits(d));
                }
                case Boolean b -> out.write(b ? 'T' : 'F');
                case Character c -> {
                    out.write('I');
                    short16(c);
                }
                default -> {
                    out.write('S');
                    length(value.toString().getBytes(StandardCharsets.UTF_8));
                }
            }
            return this;
        }

        public byte[] toBytes(){
            return out.toByteArray();
        }

        private void checkName(){
            if(!stack.isEmpty() && !stack.peek()){
                if(!named) throw new IllegalStateException("a name must be set");
                named = false;
            }
        }

        private void length(byte[] bytes){
            if(bytes.length <= Byte.MAX_VALUE){
                out.write('i');
                out.write(bytes.length);
            }else if(bytes.length <= Short.MAX_VALUE){
                out.write('I');
                short16(bytes.length);
            }else{
                out.write('l');
                int32(bytes.length);
            }
            out.write(bytes, 0, bytes.length);
        }

        private void short16(int value){
            out.write(value >>> 8);
            out.write(value);
        }

        private void int32(int value){
            short16(value >>> 16);
            short16(value);
        }

        private void int64(long value){
            int32((int)(value >>> 32));
            int32((int)value);
        }
    }

    /** {@code UBJsonReader.parseWihoutClosing}: one value from the start of {@code bytes}; containers end at the bytes' end. */
    public static JsonValue parse(byte[] bytes){
        var in = new Reader(bytes);
        return in.value(in.u8());
    }

    private static final class Reader{
        final byte[] bytes;
        int position;

        Reader(byte[] bytes){
            this.bytes = bytes;
        }

        boolean available(){
            return position < bytes.length;
        }

        int u8(){
            if(position >= bytes.length) throw new IllegalArgumentException("UBJSON ends at byte " + position);
            return bytes[position++] & 0xff;
        }

        int s16(){
            return (short)(u8() << 8 | u8());
        }

        int s32(){
            return u8() << 24 | u8() << 16 | u8() << 8 | u8();
        }

        long s64(){
            return (long)s32() << 32 | s32() & 0xffffffffL;
        }

        JsonValue value(int type){
            return switch(type){
                case '[' -> array();
                case '{' -> object();
                case 'Z' -> JsonValue.NULL;
                case 'T' -> JsonValue.bool(true);
                case 'F' -> JsonValue.bool(false);
                case 'B', 'U' -> JsonValue.number((long)u8(), null);
                case 'i' -> JsonValue.number((long)(byte)u8(), null);
                case 'I' -> JsonValue.number((long)s16(), null);
                case 'l' -> JsonValue.number((long)s32(), null);
                case 'L' -> JsonValue.number(s64(), null);
                case 'd' -> JsonValue.number((double)Float.intBitsToFloat(s32()), null);
                case 'D' -> JsonValue.number(Double.longBitsToDouble(s64()), null);
                case 's', 'S' -> JsonValue.string(string(false, type));
                case 'C' -> JsonValue.number((long)(char)s16(), null);
                default -> throw new IllegalArgumentException("unrecognized UBJSON type " + type + " at byte " + (position - 1));
            };
        }

        JsonValue array(){
            var result = JsonValue.array();
            int type = u8(), valueType = 0;
            if(type == '$'){
                valueType = u8();
                type = u8();
            }
            long size = -1;
            if(type == '#'){
                size = size(u8(), false);
                if(size < 0) throw new IllegalArgumentException("unrecognized UBJSON container size");
                if(size == 0) return result;
                type = valueType == 0 ? u8() : valueType;
            }
            long count = 0;
            while(available() && type != ']'){
                result.add(value(type));
                if(size > 0 && ++count >= size) break;
                type = valueType == 0 ? u8() : valueType;
            }
            return result;
        }

        JsonValue object(){
            var result = JsonValue.object();
            int type = u8(), valueType = 0;
            if(type == '$'){
                valueType = u8();
                type = u8();
            }
            long size = -1;
            if(type == '#'){
                size = size(u8(), false);
                if(size < 0) throw new IllegalArgumentException("unrecognized UBJSON container size");
                if(size == 0) return result;
                type = u8();
            }
            long count = 0;
            while(available() && type != '}'){
                String key = string(true, type);
                result.add(key, value(valueType == 0 ? u8() : valueType));
                if(size > 0 && ++count >= size) break;
                type = u8();
            }
            return result;
        }

        String string(boolean sOptional, int type){
            long size = -1;
            if(type == 'S') size = size(u8(), true);
            else if(type == 's') size = u8();
            else if(sOptional) size = size(type, false);
            if(size < 0) throw new IllegalArgumentException("UBJSON string expected at byte " + position);
            if(size > bytes.length - position) throw new IllegalArgumentException("UBJSON string past the end at byte " + position);
            String out = new String(bytes, position, (int)size, StandardCharsets.UTF_8);
            position += (int)size;
            return out;
        }

        long size(int type, boolean intOnError){
            if(type == 'i') return u8();
            if(type == 'I') return s16() & 0xffff;
            if(type == 'l') return s32();
            if(type == 'L') return s64();
            if(intOnError) return (long)type << 24 | (long)u8() << 16 | (long)u8() << 8 | u8();
            return -1;
        }
    }
}
