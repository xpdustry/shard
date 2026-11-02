package com.xpdustry.shard.server.ops;

import com.xpdustry.shard.server.ops.Metrics.*;
import org.junit.jupiter.api.*;

import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

class MetricsTest{
    @Test
    void exposition(){
        var metrics = new Metrics();
        var requests = metrics.counter("http_requests_total", "Requests.", "method", "code");
        requests.labels("GET", "200").inc();
        requests.labels("GET", "200").inc(2);
        requests.labels("GET", "200").inc(-5); // ignored
        requests.labels("PO\"ST\n\\", "500").inc();
        metrics.gauge("temperature", "Temp \\ with\nnewline.").set(-1.5);
        var latency = metrics.histogram("latency_seconds", "Latency.", 0.1, 1);
        latency.observe(0.0625);
        latency.observe(0.5);
        latency.observe(4.1875); // dyadic, so the sum is exact
        latency.observe(Double.NaN); // ignored
        metrics.gauge("callback", "Computed.", () -> 42);
        metrics.collector("queue_depth", "By queue.", Type.GAUGE, List.of("queue"),
            () -> List.of(new Sample(List.of("urgent"), 2), new Sample(List.of("normal"), 7)));
        metrics.gauge("special", "Specials.", "kind").labels("inf").set(Double.POSITIVE_INFINITY);

        assertEquals("""
            # HELP callback Computed.
            # TYPE callback gauge
            callback 42
            # HELP http_requests_total Requests.
            # TYPE http_requests_total counter
            http_requests_total{method="GET",code="200"} 3
            http_requests_total{method="PO\\"ST\\n\\\\",code="500"} 1
            # HELP latency_seconds Latency.
            # TYPE latency_seconds histogram
            latency_seconds_bucket{le="0.1"} 1
            latency_seconds_bucket{le="1"} 2
            latency_seconds_bucket{le="+Inf"} 3
            latency_seconds_sum 4.75
            latency_seconds_count 3
            # HELP queue_depth By queue.
            # TYPE queue_depth gauge
            queue_depth{queue="normal"} 7
            queue_depth{queue="urgent"} 2
            # HELP shard_metrics_series_dropped_total Label sets refused because their family had 128 series.
            # TYPE shard_metrics_series_dropped_total counter
            shard_metrics_series_dropped_total 0
            # HELP special Specials.
            # TYPE special gauge
            special{kind="inf"} +Inf
            # HELP temperature Temp \\\\ with\\nnewline.
            # TYPE temperature gauge
            temperature -1.5
            """, metrics.scrape());
    }

    @Test
    void labelledHistograms(){
        var metrics = new Metrics();
        var ticks = metrics.histogram("tick_seconds", "Ticks.", new double[]{0.01}, "world");
        ticks.labels("alpha").observeNanos(5_000_000);
        String text = metrics.scrape();
        assertTrue(text.contains("tick_seconds_bucket{world=\"alpha\",le=\"0.01\"} 1\n"), text);
        assertTrue(text.contains("tick_seconds_bucket{world=\"alpha\",le=\"+Inf\"} 1\n"), text);
        assertTrue(text.contains("tick_seconds_sum{world=\"alpha\"} 0.005\n"), text);
        assertTrue(text.contains("tick_seconds_count{world=\"alpha\"} 1\n"), text);
    }

    @Test
    void seriesAreBounded(){
        var metrics = new Metrics();
        var family = metrics.counter("ids_total", "Bad idea.", "id");
        for(int i = 0; i <= Metrics.MAX_SERIES; i++) family.labels("id" + i).inc();
        family.labels("id0").inc(); // existing series still work
        String text = metrics.scrape();
        assertEquals(Metrics.MAX_SERIES, text.lines().filter(line -> line.startsWith("ids_total{")).count());
        assertTrue(text.contains("ids_total{id=\"id0\"} 2\n"), text);
        assertTrue(text.contains("shard_metrics_series_dropped_total 1\n"), text);
    }

    @Test
    void registrationIsChecked(){
        var metrics = new Metrics();
        metrics.counter("a_total", "A.");
        assertThrows(IllegalArgumentException.class, () -> metrics.counter("a_total", "Again."));
        assertThrows(IllegalArgumentException.class, () -> metrics.counter("no_suffix", "X."));
        assertThrows(IllegalArgumentException.class, () -> metrics.gauge("bad-name", "X."));
        assertThrows(IllegalArgumentException.class, () -> metrics.gauge("g", "X.", "__reserved"));
        assertThrows(IllegalArgumentException.class, () -> metrics.histogram("h", "X.", new double[]{1}, "le"));
        assertThrows(IllegalArgumentException.class, () -> metrics.histogram("h2", "X.", 2, 1));
        var labelled = metrics.gauge("g2", "X.", "a", "b");
        assertThrows(IllegalArgumentException.class, () -> labelled.labels("only one"));
    }

    @Test
    void aBrokenCollectorLosesOnlyItsSamples(){
        var metrics = new Metrics();
        metrics.collector("broken", "Throws.", Type.GAUGE, List.of(), () -> {
            throw new IllegalStateException();
        });
        metrics.gauge("fine", "Fine.").set(1);
        assertTrue(metrics.scrape().contains("fine 1\n"));
    }

    @Test
    void concurrentUpdatesAreExact() throws Exception{
        var metrics = new Metrics();
        var counter = metrics.counter("hits_total", "Hits.");
        var histogram = metrics.histogram("sizes", "Sizes.", 10, 100);
        try(var executor = Executors.newFixedThreadPool(8)){
            for(int t = 0; t < 8; t++){
                executor.execute(() -> {
                    for(int i = 0; i < 100_000; i++){
                        counter.inc();
                        histogram.observe(i % 200);
                    }
                });
            }
        }
        assertEquals(800_000, counter.get());
        assertEquals(800_000, histogram.count());
    }
}
