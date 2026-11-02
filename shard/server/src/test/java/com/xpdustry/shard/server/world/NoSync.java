package com.xpdustry.shard.server.world;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * The real file system without {@code fsync}: a test VM's disk takes about 90 ms per sync, and these tests check the
 * order of the steps, not the disk. {@code CheckpointStoreTest} runs the syncing {@link FileOps#SYSTEM} once.
 */
final class NoSync implements FileOps{
    static final NoSync INSTANCE = new NoSync();

    @Override
    public void write(Path file, byte[] bytes) throws IOException{
        Files.write(file, bytes);
    }

    @Override
    public byte[] read(Path file) throws IOException{
        return SYSTEM.read(file);
    }

    @Override
    public void move(Path from, Path to) throws IOException{
        SYSTEM.move(from, to);
    }

    @Override
    public void forceDirectory(Path dir){
    }

    @Override
    public void delete(Path file) throws IOException{
        SYSTEM.delete(file);
    }

    @Override
    public List<String> list(Path dir) throws IOException{
        return SYSTEM.list(dir);
    }

    @Override
    public void createDirectories(Path dir) throws IOException{
        SYSTEM.createDirectories(dir);
    }
}
