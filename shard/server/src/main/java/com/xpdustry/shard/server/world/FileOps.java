package com.xpdustry.shard.server.world;

import java.io.*;
import java.nio.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.util.*;
import java.util.stream.*;

/**
 * The file operations {@link CheckpointStore} needs, each durable when it returns. An interface so tests can fail any
 * step: a short write, a failed rename, a lost fsync.
 */
public interface FileOps{
    /** Creates or replaces {@code file} with {@code bytes} and forces them to disk. */
    void write(Path file, byte[] bytes) throws IOException;

    byte[] read(Path file) throws IOException;

    /** Renames {@code from} to {@code to} atomically, replacing {@code to}. */
    void move(Path from, Path to) throws IOException;

    /** Forces a directory's entries to disk, so a rename in it survives a crash. */
    void forceDirectory(Path dir) throws IOException;

    void delete(Path file) throws IOException;

    /** The names in {@code dir}, sorted; empty if it doesn't exist. */
    List<String> list(Path dir) throws IOException;

    void createDirectories(Path dir) throws IOException;

    /** The real file system, with {@code fsync} through {@link FileChannel#force}. */
    FileOps SYSTEM = new FileOps(){
        @Override
        public void write(Path file, byte[] bytes) throws IOException{
            try(FileChannel channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)){
                var buffer = ByteBuffer.wrap(bytes);
                while(buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
        }

        @Override
        public byte[] read(Path file) throws IOException{
            return Files.readAllBytes(file);
        }

        @Override
        public void move(Path from, Path to) throws IOException{
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        }

        @Override
        public void forceDirectory(Path dir) throws IOException{
            // Linux fsyncs a directory through a read-only descriptor; other systems may refuse, which is harmless there.
            try(FileChannel channel = FileChannel.open(dir, StandardOpenOption.READ)){
                channel.force(true);
            }catch(IOException e){
                if(System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("linux")) throw e;
            }
        }

        @Override
        public void delete(Path file) throws IOException{
            Files.deleteIfExists(file);
        }

        @Override
        public List<String> list(Path dir) throws IOException{
            if(!Files.isDirectory(dir)) return List.of();
            try(Stream<Path> files = Files.list(dir)){
                return files.map(path -> path.getFileName().toString()).sorted().toList();
            }
        }

        @Override
        public void createDirectories(Path dir) throws IOException{
            Files.createDirectories(dir);
        }
    };
}
