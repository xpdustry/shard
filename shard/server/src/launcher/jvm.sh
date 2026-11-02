# The unix launcher's own part, which server/build.gradle.kts puts in bin/shard after Gradle's DEFAULT_JVM_OPTS line
# (POSIX sh, with the build's id filled in). It requires JDK 27, picks the collector, keeps JFR recording and starts
# from the build's AOT cache, with all three under SHARD_DATA (default: data, beside the database), never /tmp.
#
# SHARD_GC is shenandoah (generational), g1 (small hosts) or unset: Shenandoah if this JDK has it (Temurin does,
# Oracle's builds don't), else G1.
#
# SHARD_AOT is auto (the default), train or off. The AOT cache (JEP 483, 514, 516) holds the classes the server
# loaded, linked, and its archived heap objects. It only matches the class path and JDK it was trained with, so each
# build, JDK and install has its own file. auto starts from it if there is one; train makes it on this run, written
# when the server stops. A cache trained under either collector serves both. The JVM records no JFR while it trains,
# so training is never automatic: `bin/shard --aot-train --config <dir>` (aot-train.sh) trains it on a run of bots
# beside the server, with the production collector and heap, and exits. SHARD_AOT_CACHE names another file.
JAVA_VERSION=$("$JAVACMD" -version 2>&1 | sed -n 's/.* version "\([^"]*\)".*/\1/p' | head -n 1)
JAVA_FEATURE=${JAVA_VERSION%%[!0-9]*}
[ "${JAVA_FEATURE:-0}" -ge 27 ] 2>/dev/null ||
    die "Shard needs Java 27 or later; $JAVACMD is Java ${JAVA_FEATURE:-unknown}. Set JAVA_HOME to a JDK 27 (Eclipse Temurin)."
if [ "${1-}" = --aot-train ]; then
    shift
    exec sh "$APP_HOME/libexec/aot-train.sh" "$APP_HOME" "$JAVACMD" "$@"
fi
SHARD_DATA=${SHARD_DATA:-data}
mkdir -p "$SHARD_DATA/jfr" "$SHARD_DATA/tmp" || die "can't create $SHARD_DATA/jfr and $SHARD_DATA/tmp"
if [ -z "${SHARD_GC-}" ]; then
    if "$JAVACMD" -XX:+UseShenandoahGC -XX:ShenandoahGCMode=generational -version >/dev/null 2>&1; then
        SHARD_GC=shenandoah
    else
        echo "shard: this JDK has no generational Shenandoah; using G1 (set SHARD_GC to choose)" >&2
        SHARD_GC=g1
    fi
fi
case $SHARD_GC in
    shenandoah) SHARD_GC_OPTS='"-XX:+UseShenandoahGC" "-XX:ShenandoahGCMode=generational"' ;;
    g1) SHARD_GC_OPTS='"-XX:+UseG1GC"' ;;
    *) die "SHARD_GC is shenandoah or g1, not $SHARD_GC" ;;
esac
SHARD_JFR_OPTS="\"-XX:FlightRecorderOptions=repository=$SHARD_DATA/jfr\" \"-XX:StartFlightRecording=name=shard,disk=true,maxage=6h,maxsize=1g,dumponexit=true,filename=$SHARD_DATA/jfr\" \"-Xlog:jfr+startup=off\""
SHARD_AOT_OPTS=
case ${SHARD_AOT:=auto} in
    auto|train|off) ;;
    *) die "SHARD_AOT is auto, train or off, not $SHARD_AOT" ;;
esac
# replay and --help aren't the server
case ${1-} in replay|--help) SHARD_AOT=off ;; esac
if [ "$SHARD_AOT" != off ]; then
    SHARD_AOT_CACHE=${SHARD_AOT_CACHE:-$SHARD_DATA/aot/shard-@BUILD_ID@-$(printf '%s\n' "$JAVA_VERSION" "$APP_HOME" | cksum | cut -d ' ' -f 1).aot}
    if [ "$SHARD_AOT" = train ]; then
        mkdir -p "$(dirname "$SHARD_AOT_CACHE")" || die "can't create $(dirname "$SHARD_AOT_CACHE")"
        # other builds' caches go
        for old in "$SHARD_DATA"/aot/shard-*.aot; do
            [ "$old" = "$SHARD_AOT_CACHE" ] || rm -f "$old"
        done
        echo "shard: this run trains the AOT cache $SHARD_AOT_CACHE, written when the server stops; no JFR until the next run" >&2
        # JFR's options stay, though the JVM records nothing while it trains: they add jdk.jfr to the module graph, and
        # a cache trained without it is refused by every run with them
        SHARD_AOT_OPTS="\"-XX:AOTCacheOutput=$SHARD_AOT_CACHE\""
    elif [ -f "$SHARD_AOT_CACHE" ]; then
        SHARD_AOT_OPTS="\"-XX:AOTCache=$SHARD_AOT_CACHE\""
    else
        echo "shard: no AOT cache for this build; SHARD_AOT=train makes one on the next run" >&2
    fi
fi
DEFAULT_JVM_OPTS="$DEFAULT_JVM_OPTS $SHARD_GC_OPTS $SHARD_JFR_OPTS $SHARD_AOT_OPTS \"-Djava.io.tmpdir=$SHARD_DATA/tmp\""
