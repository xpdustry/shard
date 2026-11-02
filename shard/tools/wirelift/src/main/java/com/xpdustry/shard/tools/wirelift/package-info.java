/**
 * Lifts {@code wire/schema/v160.7.yaml} from the vanilla jar's bytecode ({@code WireLift}) and generates codecs from it
 * ({@code WireGen}). Port of note 25's prototype. CI re-lifts and fails on a diff.
 *
 * <p>Owner: WP14.
 */
package com.xpdustry.shard.tools.wirelift;
