package com.xpdustry.shard.server.ops;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import java.util.regex.*;

/**
 * A small metrics registry with Prometheus text exposition, format 0.0.4 (note 34 "Logs, metrics, and health").
 * Counters, gauges and histograms, plain or with labels, and collectors that compute samples at scrape time.
 *
 * <p>Label values must stay bounded (Prometheus warns against ids as labels): a family keeps at most
 * {@link #MAX_SERIES} series, and past that hands out a detached series that is never exported, counting it in
 * {@code shard_metrics_series_dropped_total}. Recording never throws.
 */
public final class Metrics{
    public static final int MAX_SERIES = 128;
    static final Pattern NAME = Pattern.compile("[a-zA-Z_:][a-zA-Z0-9_:]*");
    static final Pattern LABEL = Pattern.compile("[a-zA-Z_][a-zA-Z0-9_]*");

    private final ConcurrentSkipListMap<String, Family> families = new ConcurrentSkipListMap<>();
    private final Counter dropped;

    public Metrics(){
        dropped = counter("shard_metrics_series_dropped_total", "Label sets refused because their family had " + MAX_SERIES + " series.");
    }

    public enum Type{ COUNTER, GAUGE, HISTOGRAM }

    // registration

    public Counter counter(String name, String help){
        return counter(name, help, new String[0]).labels();
    }

    /** A counter family; names end in {@code _total}. */
    public Labeled<Counter> counter(String name, String help, String... labels){
        if(!name.endsWith("_total")) throw new IllegalArgumentException("counter names end in _total: " + name);
        return register(new Family(name, help, Type.COUNTER, labels, Counter::new, null)).labeled();
    }

    public Gauge gauge(String name, String help){
        return gauge(name, help, new String[0]).labels();
    }

    public Labeled<Gauge> gauge(String name, String help, String... labels){
        return register(new Family(name, help, Type.GAUGE, labels, Gauge::new, null)).labeled();
    }

    /** A gauge read at scrape time. */
    public void gauge(String name, String help, DoubleSupplier value){
        collector(name, help, Type.GAUGE, List.of(), () -> List.of(new Sample(List.of(), value.getAsDouble())));
    }

    /** {@code buckets} are upper bounds, ascending; {@code +Inf} is implied. */
    public Histogram histogram(String name, String help, double... buckets){
        return histogram(name, help, buckets, new String[0]).labels();
    }

    public Labeled<Histogram> histogram(String name, String help, double[] buckets, String... labels){
        double[] bounds = buckets.clone();
        for(int i = 0; i < bounds.length; i++){
            if(!Double.isFinite(bounds[i]) || i > 0 && bounds[i] <= bounds[i - 1]) throw new IllegalArgumentException("buckets must be finite and ascending");
        }
        for(String label : labels) if(label.equals("le")) throw new IllegalArgumentException("le is reserved for histogram buckets");
        return register(new Family(name, help, Type.HISTOGRAM, labels, () -> new Histogram(bounds), null)).labeled();
    }

    /**
     * A family whose samples come from {@code samples} at each scrape: for values that already live elsewhere (queue
     * depths, JVM counters). A counter collector's name ends in {@code _total}.
     */
    public void collector(String name, String help, Type type, List<String> labels, Supplier<List<Sample>> samples){
        if(type == Type.HISTOGRAM) throw new IllegalArgumentException("collectors export counters or gauges");
        if(type == Type.COUNTER && !name.endsWith("_total")) throw new IllegalArgumentException("counter names end in _total: " + name);
        register(new Family(name, help, type, labels.toArray(String[]::new), null, samples));
    }

    /** One computed sample: label values in the family's label order. */
    public record Sample(List<String> labels, double value){
    }

    private Family register(Family family){
        if(!NAME.matcher(family.name).matches()) throw new IllegalArgumentException("bad metric name " + family.name);
        for(String label : family.labels){
            if(!LABEL.matcher(label).matches() || label.startsWith("__")) throw new IllegalArgumentException("bad label name " + label);
        }
        if(families.putIfAbsent(family.name, family) != null) throw new IllegalArgumentException("metric " + family.name + " is already registered");
        return family;
    }

    // exposition

    /** Every family in the Prometheus text format, sorted by name. */
    public String scrape(){
        var out = new StringBuilder(4096);
        for(var family : families.values()) family.write(out);
        return out.toString();
    }

    private final class Family{
        final String name, help;
        final Type type;
        final String[] labels;
        final Supplier<Object> factory;
        final Supplier<List<Sample>> samples;
        final ConcurrentHashMap<List<String>, Object> series = new ConcurrentHashMap<>();

        Family(String name, String help, Type type, String[] labels, Supplier<Object> factory, Supplier<List<Sample>> samples){
            this.name = name;
            this.help = help;
            this.type = type;
            this.labels = labels.clone();
            this.factory = factory;
            this.samples = samples;
        }

        @SuppressWarnings("unchecked")
        <T> Labeled<T> labeled(){
            return values -> {
                if(values.length != labels.length) throw new IllegalArgumentException(name + " takes labels " + Arrays.toString(labels));
                var key = List.of(values);
                var existing = series.get(key);
                if(existing != null) return (T)existing;
                if(series.size() >= MAX_SERIES){
                    dropped.inc();
                    return (T)factory.get();
                }
                return (T)series.computeIfAbsent(key, k -> factory.get());
            };
        }

        void write(StringBuilder out){
            out.append("# HELP ").append(name).append(' ').append(escapeHelp(help)).append('\n');
            out.append("# TYPE ").append(name).append(' ').append(type.name().toLowerCase(Locale.ROOT)).append('\n');
            if(samples != null){
                List<Sample> computed;
                try{
                    computed = samples.get();
                }catch(RuntimeException e){
                    return; // a broken collector loses its samples, not the whole scrape
                }
                var sorted = new ArrayList<>(computed);
                sorted.sort(Comparator.comparing(sample -> String.join("\u0000", sample.labels())));
                for(var sample : sorted) line(out, name, sample.labels(), null, sample.value());
                return;
            }
            var keys = new ArrayList<>(series.keySet());
            keys.sort(Comparator.comparing(key -> String.join("\u0000", key)));
            for(var key : keys){
                switch(series.get(key)){
                    case Counter counter -> line(out, name, key, null, counter.get());
                    case Gauge gauge -> line(out, name, key, null, gauge.get());
                    case Histogram histogram -> {
                        long[] cumulative = histogram.cumulative();
                        for(int i = 0; i < cumulative.length; i++){
                            line(out, name + "_bucket", key, i < histogram.bounds.length ? format(histogram.bounds[i]) : "+Inf", cumulative[i]);
                        }
                        line(out, name + "_sum", key, null, histogram.sum());
                        line(out, name + "_count", key, null, cumulative[cumulative.length - 1]);
                    }
                    default -> throw new AssertionError();
                }
            }
        }

        void line(StringBuilder out, String metric, List<String> values, String le, double value){
            out.append(metric);
            if(!values.isEmpty() || le != null){
                out.append('{');
                for(int i = 0; i < values.size(); i++){
                    if(i > 0) out.append(',');
                    out.append(labels[i]).append("=\"").append(escapeLabel(values.get(i))).append('"');
                }
                if(le != null) out.append(values.isEmpty() ? "" : ",").append("le=\"").append(le).append('"');
                out.append('}');
            }
            out.append(' ').append(format(value)).append('\n');
        }
    }

    /** A family's series by label values. */
    @FunctionalInterface
    public interface Labeled<T>{
        T labels(String... values);
    }

    public static final class Counter{
        private final DoubleAdder value = new DoubleAdder();

        public void inc(){
            value.add(1);
        }

        /** Adds {@code amount}; a negative or non-finite amount is ignored (counters only go up). */
        public void inc(double amount){
            if(amount > 0 && Double.isFinite(amount)) value.add(amount);
        }

        public double get(){
            return value.sum();
        }
    }

    public static final class Gauge{
        private final AtomicLong bits = new AtomicLong(Double.doubleToLongBits(0));

        public void set(double value){
            bits.set(Double.doubleToLongBits(value));
        }

        public void add(double delta){
            bits.accumulateAndGet(0, (old, ignored) -> Double.doubleToLongBits(Double.longBitsToDouble(old) + delta));
        }

        public double get(){
            return Double.longBitsToDouble(bits.get());
        }
    }

    public static final class Histogram{
        private final double[] bounds;
        private final LongAdder[] counts;
        private final DoubleAdder sum = new DoubleAdder();

        Histogram(double[] bounds){
            this.bounds = bounds;
            counts = new LongAdder[bounds.length + 1];
            for(int i = 0; i < counts.length; i++) counts[i] = new LongAdder();
        }

        public void observe(double value){
            if(Double.isNaN(value)) return;
            int index = Arrays.binarySearch(bounds, value);
            counts[index >= 0 ? index : -index - 1].increment();
            sum.add(value);
        }

        /** Observes nanoseconds as seconds. */
        public void observeNanos(long nanos){
            observe(nanos / 1e9);
        }

        public long count(){
            long total = 0;
            for(var count : counts) total += count.sum();
            return total;
        }

        /** Cumulative bucket counts, {@code +Inf} last. */
        long[] cumulative(){
            long[] out = new long[counts.length];
            long total = 0;
            for(int i = 0; i < counts.length; i++){
                total += counts[i].sum();
                out[i] = total;
            }
            return out;
        }

        double sum(){
            return sum.sum();
        }
    }

    static String format(double value){
        if(Double.isNaN(value)) return "NaN";
        if(value == Double.POSITIVE_INFINITY) return "+Inf";
        if(value == Double.NEGATIVE_INFINITY) return "-Inf";
        if(value == Math.rint(value) && Math.abs(value) < 1e15) return Long.toString((long)value);
        return Double.toString(value);
    }

    static String escapeLabel(String value){
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }

    static String escapeHelp(String help){
        return help.replace("\\", "\\\\").replace("\n", "\\n");
    }
}
