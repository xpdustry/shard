# Wire, codecs and the content pack

Read before touching `wire/**`, `content/**`, `tools/content-extract`, `tools/wirelift`, Patchgen, old-revision entity
decoding, or a typed `:content` getter. Rows: W1.1, W1.5, W2.5. Their owned paths and WPs are in
[`history.md`](history.md). Index, ownership rules and live contracts: [`WAVES.md`](../../WAVES.md). Paths are relative
to `shard/`.

**Wire and content (W2.5 done)**
- Done: `Reads.str`'s own exception; all 151 old entity save revisions decode, 33 of them checked against saves
  from 11 old vanilla servers (`wire/scripts/old-saves.sh`); server→client fuzz, the `UiBuilder` depth bound,
  unknown unit and status ids rejected (`contentExists`, answered by `WorldWireContext` and `ReadDraws`);
  `scripts/nightly-l4.sh` (not scheduled); client tables from content-extract; the vanilla `ContentSource`; typed
  unit getters.
- Old bodies no release server writes (`PayloadUnitLegacyQuad.0`, `Fire.0`; v117 has no server jar) are checked
  only by the synthesized `OldRevisionsTest.everyOldBody`. `WeatherState`'s random wind is zeroed before comparing.
  Config types aren't extracted.
- `ReadDraws.topUnit`'s hand parsing of units one or two revisions behind is redundant now that
  `EntityClasses.readSave` decodes every revision.
- `Checkpoint.config` restores with `KindRegistry.builtIn()`, but worlds on vanilla content use `withPassthrough()`,
  and the checkpoint hashes its kind list: record the kind mode in the checkpoint.
- Patchgen refuses added content, new bullets, inline effects, bullet reference changes, lambdas, path costs and
  entity class changes; it can't add mirrored weapons, keeps one consumer per `readBlockConsumers` key, and removing
  every item or liquid consumer leaves the client's `hasItems`/`hasLiquids` true.

**From the review 38 routing (W2.6 ✓: content-valued configs, `ContentRef`)**
  Open: `ContentRef.Foreign` ids (unit commands and so on) aren't mapped through a file's header.
