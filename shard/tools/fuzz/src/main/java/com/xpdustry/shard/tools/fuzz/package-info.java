/**
 * The client RPC fuzzer (WP93): {@link com.xpdustry.shard.tools.fuzz.Campaign} runs an in-process server with several
 * worlds, a well-behaved control client in each and many fuzzing clients sending mutated client packets, and checks the
 * server's invariants while they do. {@code tools/fuzz/README.md} has the commands and what each invariant means.
 *
 * <p>Owner: W5.5.
 */
package com.xpdustry.shard.tools.fuzz;
