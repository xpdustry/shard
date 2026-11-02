/**
 * Operations (note 34 "Operations and cheap multi-tenant hosting").
 *
 * <ul>
 *   <li>{@link com.xpdustry.shard.server.ops.OpsServer}: the loopback-only REST/SSE API on the JDK
 *       {@code HttpServer}.</li>
 *   <li>{@link com.xpdustry.shard.server.ops.Metrics}: counters, gauges and histograms in the Prometheus text
 *       format, with bounded label sets.</li>
 *   <li>{@link com.xpdustry.shard.server.ops.Health}: liveness and readiness checks.</li>
 *   <li>{@link com.xpdustry.shard.server.ops.Events}: the bounded, resumable event stream behind SSE.</li>
 *   <li>{@link com.xpdustry.shard.server.ops.Logging} and {@link com.xpdustry.shard.server.ops.JsonLogEncoder}:
 *       structured JSON logs on Logback.</li>
 *   <li>{@link com.xpdustry.shard.server.ops.Instrumentation}: metrics and checks for the JVM, the config and the
 *       store.</li>
 * </ul>
 *
 * <p>Crash bundles come later, with the world lifecycle.
 *
 * <p>Owner: the ops work package (note 34).
 */
package com.xpdustry.shard.server.ops;
