package com.xpdustry.shard.server.store;

import com.xpdustry.shard.server.config.*;
import org.sqlite.*;

import java.io.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.time.format.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * The host database: SQLite in WAL mode (note 34 "Pick SQLite for the first host").
 *
 * <p><b>Writes</b> go through one platform thread that owns the only writer connection, fed by two bounded queues:
 * {@link Priority#URGENT} (moderation) is always drained before {@link Priority#NORMAL} (sessions, chat). A full
 * queue fails the write at once with {@link RejectedExecutionException}: nothing waits unbounded, and no world tick
 * ever blocks on SQL. The writer commits in groups: it takes up to {@value #MAX_BATCH} queued jobs, runs each in its
 * own savepoint inside one transaction, and commits once, so a burst costs one sync. A job that throws rolls back
 * only its savepoint. A job's future completes after the commit, so a completed moderation write is durable (with
 * {@code synchronous=full}). Futures complete on virtual threads, never on the writer.
 *
 * <p><b>Reads</b> run on virtual threads, each borrowing one of a fixed set of read-only connections and running as
 * one snapshot. sqlite-jdbc is JNI, so a read pins its carrier while in native code; the reader count bounds that.
 *
 * <p>A job must not commit, roll back or change auto-commit itself.
 */
public final class Store implements AutoCloseable{
    static final int MAX_BATCH = 256;
    static final DateTimeFormatter BACKUP_NAME = DateTimeFormatter.ofPattern("'shard-'yyyyMMdd'-'HHmmss'Z.db'").withZone(ZoneOffset.UTC);

    private final Settings settings;
    private final InstantSource clock;
    private final String url;
    private final Connection writer;
    private final ArrayBlockingQueue<Connection> readers;
    private final List<Connection> allReaders = new ArrayList<>();
    private final ArrayBlockingQueue<Job<?>> urgent, normal;
    private final Semaphore queued = new Semaphore(0);
    private final ExecutorService virtual = Executors.newVirtualThreadPerTaskExecutor();
    private final Semaphore backups = new Semaphore(1);
    private final Thread writerThread;
    private volatile boolean closing;
    private final int schemaVersion;

    private final AtomicLong committed = new AtomicLong(), failed = new AtomicLong(), rejected = new AtomicLong();
    private final AtomicLong batches = new AtomicLong(), commitNanos = new AtomicLong();

    /**
     * What a store needs from the config.
     *
     * @param writerQueue the capacity of each write queue
     */
    public record Settings(Path database, ConfigKeys.Synchronous synchronous, int writerQueue, int readers,
                           Duration busyTimeout, Path backupDir){
        public static Settings from(Config config){
            return new Settings(Path.of(config.get(ConfigKeys.STORAGE_DATABASE)), config.get(ConfigKeys.STORAGE_SYNCHRONOUS),
                config.get(ConfigKeys.STORAGE_WRITER_QUEUE), config.get(ConfigKeys.STORAGE_READERS),
                config.get(ConfigKeys.STORAGE_BUSY_TIMEOUT), Path.of(config.get(ConfigKeys.STORAGE_BACKUP_DIR)));
        }
    }

    public enum Priority{ URGENT, NORMAL }

    /** Work on a connection. */
    @FunctionalInterface
    public interface Tx<T>{
        T run(Connection connection) throws SQLException;
    }

    /**
     * A finished backup.
     *
     * @param schemaVersion the migration version inside the backup
     */
    public record Backup(Path path, long bytes, int schemaVersion, Duration took){
    }

    /**
     * Counters for metrics.
     *
     * @param queued writes waiting, both queues
     * @param oldestQueuedNanos how long the oldest waiting write has waited; 0 when none
     * @param walBytes the size of the {@code -wal} file
     */
    public record Stats(int queued, long oldestQueuedNanos, long committed, long failed, long rejected, long batches,
                        long commitNanos, long walBytes){
    }

    private record Job<T>(Tx<T> tx, CompletableFuture<T> future, long enqueuedNanos){
    }

    private Store(Settings settings, InstantSource clock) throws SQLException, IOException{
        this.settings = settings;
        this.clock = clock;
        Path database = settings.database().toAbsolutePath();
        if(database.getParent() != null) Files.createDirectories(database.getParent());
        this.url = "jdbc:sqlite:" + database;
        this.urgent = new ArrayBlockingQueue<>(settings.writerQueue());
        this.normal = new ArrayBlockingQueue<>(settings.writerQueue());

        writer = connect(false);
        try(var statement = writer.createStatement(); var rows = statement.executeQuery("PRAGMA journal_mode")){
            if(!rows.next() || !rows.getString(1).equalsIgnoreCase("wal")){
                throw new StoreException(database + " is not in WAL mode; it must be on a local file system");
            }
        }
        schemaVersion = migrate();
        writer.setAutoCommit(false);

        readers = new ArrayBlockingQueue<>(settings.readers());
        for(int i = 0; i < settings.readers(); i++){
            var reader = connect(true);
            reader.setAutoCommit(false);
            allReaders.add(reader);
            readers.add(reader);
        }
        writerThread = Thread.ofPlatform().name("store-writer").start(this::writeLoop);
    }

    /** Opens (creating if needed) and migrates the database. */
    public static Store open(Settings settings, InstantSource clock){
        try{
            return new Store(settings, clock);
        }catch(SQLException | IOException e){
            throw new StoreException("cannot open " + settings.database() + ": " + e.getMessage(), e);
        }
    }

    private Connection connect(boolean readOnly) throws SQLException{
        var config = new SQLiteConfig();
        config.setJournalMode(SQLiteConfig.JournalMode.WAL);
        config.setSynchronous(settings.synchronous() == ConfigKeys.Synchronous.FULL ? SQLiteConfig.SynchronousMode.FULL : SQLiteConfig.SynchronousMode.NORMAL);
        config.enforceForeignKeys(true);
        config.setBusyTimeout((int)Math.min(Integer.MAX_VALUE, settings.busyTimeout().toMillis()));
        config.setTransactionMode(readOnly ? SQLiteConfig.TransactionMode.DEFERRED : SQLiteConfig.TransactionMode.IMMEDIATE);
        config.setReadOnly(readOnly);
        return config.createConnection(url);
    }

    private int migrate() throws SQLException{
        var applied = Migrations.applied(writer);
        var pending = Migrations.pending(applied);
        if(!pending.isEmpty() && !applied.isEmpty()){
            Path target = settings.backupDir().resolve("pre-migration-v" + applied.lastKey() + "-" + BACKUP_NAME.format(clock.instant()));
            vacuumInto(writer, target, applied.lastKey());
        }
        for(var migration : pending) Migrations.apply(writer, migration, clock.millis());
        return Migrations.latest();
    }

    /** The schema version after migration. */
    public int schemaVersion(){
        return schemaVersion;
    }

    // writes

    /** Queues a {@link Priority#NORMAL} write. */
    public <T> CompletableFuture<T> write(Tx<T> tx){
        return write(Priority.NORMAL, tx);
    }

    /** Queues a write; the future fails with {@link RejectedExecutionException} if its queue is full or the store closed. */
    public <T> CompletableFuture<T> write(Priority priority, Tx<T> tx){
        var job = new Job<>(tx, new CompletableFuture<>(), System.nanoTime());
        boolean accepted;
        synchronized(this){ // against close(): once closing is set, no write gets past the writer's last drain
            accepted = !closing && (priority == Priority.URGENT ? urgent : normal).offer(job);
        }
        if(!accepted){
            rejected.incrementAndGet();
            return CompletableFuture.failedFuture(new RejectedExecutionException(closing ? "the store is closed" : "the " + priority.name().toLowerCase(Locale.ROOT) + " write queue is full"));
        }
        queued.release();
        return job.future;
    }

    private void writeLoop(){
        var batch = new ArrayList<Job<?>>(MAX_BATCH);
        while(true){
            try{
                if(!queued.tryAcquire(50, TimeUnit.MILLISECONDS)){
                    if(closing && urgent.isEmpty() && normal.isEmpty()) return;
                    continue;
                }
            }catch(InterruptedException e){
                continue; // close() waits for the queues to drain; it never interrupts
            }
            batch.add(next());
            while(batch.size() < MAX_BATCH && queued.tryAcquire()) batch.add(next());
            runBatch(batch);
            batch.clear();
        }
    }

    /** Urgent first. A permit always has a job behind it, though maybe in the other queue. */
    private Job<?> next(){
        var job = urgent.poll();
        return job != null ? job : normal.poll();
    }

    private void runBatch(List<Job<?>> batch){
        long start = System.nanoTime();
        var results = new Object[batch.size()];
        var errors = new Throwable[batch.size()];
        try{
            for(int i = 0; i < batch.size(); i++){
                Savepoint savepoint = writer.setSavepoint();
                try{
                    results[i] = batch.get(i).tx.run(writer);
                    writer.releaseSavepoint(savepoint);
                }catch(Throwable e){
                    errors[i] = e;
                    writer.rollback(savepoint);
                    writer.releaseSavepoint(savepoint);
                }
            }
            writer.commit();
        }catch(SQLException e){
            try{
                writer.rollback();
            }catch(SQLException ignored){
            }
            for(int i = 0; i < batch.size(); i++) if(errors[i] == null) errors[i] = new StoreException("commit failed: " + e.getMessage(), e);
        }
        batches.incrementAndGet();
        commitNanos.addAndGet(System.nanoTime() - start);
        for(int i = 0; i < batch.size(); i++){
            complete(batch.get(i), results[i], errors[i]);
        }
    }

    @SuppressWarnings("unchecked")
    private <T> void complete(Job<T> job, Object result, Throwable error){
        if(error == null){
            committed.incrementAndGet();
            virtual.execute(() -> job.future.complete((T)result));
        }else{
            failed.incrementAndGet();
            virtual.execute(() -> job.future.completeExceptionally(error));
        }
    }

    // reads

    /** Runs a read on a virtual thread, as one snapshot. */
    public <T> CompletableFuture<T> read(Tx<T> tx){
        var future = new CompletableFuture<T>();
        if(closing){
            future.completeExceptionally(new RejectedExecutionException("the store is closed"));
            return future;
        }
        virtual.execute(() -> {
            Connection connection;
            try{
                connection = readers.take();
            }catch(InterruptedException e){
                future.completeExceptionally(e);
                return;
            }
            try{
                future.complete(tx.run(connection));
            }catch(Throwable e){
                future.completeExceptionally(e);
            }finally{
                try{
                    connection.rollback(); // ends the read snapshot, so the WAL can be checkpointed
                }catch(SQLException ignored){
                }
                readers.add(connection);
            }
        });
        return future;
    }

    // backup

    /** An online backup into {@code storage.backup-dir}, named by the time. */
    public CompletableFuture<Backup> backup(){
        return backup(settings.backupDir().resolve(BACKUP_NAME.format(clock.instant())));
    }

    /**
     * An online backup to {@code target}, which must not exist: {@code VACUUM INTO} from a consistent snapshot (writes
     * keep going), then sync, an integrity check, and an atomic rename. One backup runs at a time.
     */
    public CompletableFuture<Backup> backup(Path target){
        var future = new CompletableFuture<Backup>();
        if(closing){
            future.completeExceptionally(new RejectedExecutionException("the store is closed"));
            return future;
        }
        virtual.execute(() -> {
            try{
                backups.acquire();
            }catch(InterruptedException e){
                future.completeExceptionally(e);
                return;
            }
            try(var connection = connect(false)){
                future.complete(vacuumInto(connection, target, schemaVersion));
            }catch(Throwable e){
                future.completeExceptionally(e);
            }finally{
                backups.release();
            }
        });
        return future;
    }

    private Backup vacuumInto(Connection connection, Path target, int expectedVersion) throws SQLException{
        long start = System.nanoTime();
        target = target.toAbsolutePath();
        Path partial = target.resolveSibling(target.getFileName() + ".partial");
        try{
            if(Files.exists(target)) throw new StoreException("backup target " + target + " exists");
            Files.createDirectories(target.getParent());
            Files.deleteIfExists(partial);
            try(var vacuum = connection.prepareStatement("VACUUM INTO ?")){
                vacuum.setString(1, partial.toString());
                vacuum.execute();
            }
            try(var channel = FileChannel.open(partial, StandardOpenOption.WRITE)){
                channel.force(true);
            }
            int version = verify(partial);
            if(version != expectedVersion) throw new StoreException("backup has schema version " + version + ", expected " + expectedVersion);
            Files.move(partial, target, StandardCopyOption.ATOMIC_MOVE);
            try(var directory = FileChannel.open(target.getParent(), StandardOpenOption.READ)){
                directory.force(true);
            }
            return new Backup(target, Files.size(target), version, Duration.ofNanos(System.nanoTime() - start));
        }catch(IOException e){
            throw new StoreException("backup to " + target + " failed: " + e.getMessage(), e);
        }finally{
            try{
                Files.deleteIfExists(partial);
            }catch(IOException ignored){
            }
        }
    }

    /** Checks a database file's integrity; returns its schema version. */
    static int verify(Path file) throws SQLException{
        var config = new SQLiteConfig();
        config.setReadOnly(true);
        try(var connection = config.createConnection("jdbc:sqlite:" + file.toAbsolutePath());
            var statement = connection.createStatement()){
            try(var rows = statement.executeQuery("PRAGMA integrity_check")){
                if(!rows.next() || !rows.getString(1).equals("ok")) throw new StoreException(file + " failed its integrity check");
            }
            try(var rows = statement.executeQuery("SELECT coalesce(max(version), 0) FROM schema_migrations")){
                rows.next();
                return rows.getInt(1);
            }
        }
    }

    // lifecycle

    /** False once {@link #close()} began. */
    public boolean isOpen(){
        return !closing;
    }

    public Stats stats(){
        long now = System.nanoTime(), oldest = 0;
        for(var queue : List.of(urgent, normal)){
            var head = queue.peek();
            if(head != null) oldest = Math.max(oldest, now - head.enqueuedNanos);
        }
        long wal;
        try{
            wal = Files.size(Path.of(settings.database().toAbsolutePath() + "-wal"));
        }catch(IOException e){
            wal = 0;
        }
        return new Stats(urgent.size() + normal.size(), oldest, committed.get(), failed.get(), rejected.get(), batches.get(), commitNanos.get(), wal);
    }

    /** Stops taking work, finishes every queued write and running read, then closes the connections. */
    @Override
    public void close(){
        synchronized(this){
            if(closing) return;
            closing = true;
        }
        try{
            writerThread.join();
            for(int i = 0; i < allReaders.size(); i++) readers.take(); // every read has returned its connection
            backups.acquire();
        }catch(InterruptedException e){
            Thread.currentThread().interrupt();
        }
        virtual.close();
        for(var connection : allReaders) closeQuietly(connection);
        closeQuietly(writer);
    }

    private static void closeQuietly(Connection connection){
        try{
            connection.close();
        }catch(SQLException ignored){
        }
    }
}
