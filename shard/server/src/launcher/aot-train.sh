# bin/shard --aot-train [shard's arguments]: trains this build's AOT cache (jvm.sh) on one run of the server that does
# what a server does, then exits. Run it as the server runs (same user, working directory, --config, JAVA_OPTS,
# SHARD_OPTS, SHARD_GC and SHARD_DATA), so the cache is trained with the production collector and heap and lands where
# the next start looks for it. The run hosts the config's worlds, but on 127.0.0.1:$SHARD_AOT_TRAIN_PORT (6599), with
# a throwaway database, checkpoints and log file and no ops API, so it leaves the server's own state alone and can run
# beside it. tools/bots' load generator (the install's tools/bots/, or $SHARD_BOTS: a shard-bots install) joins
# $SHARD_AOT_TRAIN_BOTS (40) players over 4 s for $SHARD_AOT_TRAIN_SECONDS (20): they chat, move between the worlds,
# and one is kicked from the console. The server's log and the bots' report go to $SHARD_DATA/aot/train.log and
# train-bots.txt.
#
# POSIX sh, run by bin/shard with its APP_HOME and JAVACMD first.
set -u
APP_HOME=$1; JAVACMD=$2; shift 2
SHARD_DATA=${SHARD_DATA:-data}
SHARD_BOTS=${SHARD_BOTS:-$APP_HOME/tools/bots}
port=${SHARD_AOT_TRAIN_PORT:-6599}; bots=${SHARD_AOT_TRAIN_BOTS:-40}; seconds=${SHARD_AOT_TRAIN_SECONDS:-20}
if ! ls "$SHARD_BOTS"/lib/bots.jar > /dev/null 2>&1; then
    echo "shard: --aot-train needs the load generator in $SHARD_BOTS/lib (SHARD_BOTS names a shard-bots install)" >&2
    exit 2
fi
mkdir -p "$SHARD_DATA/aot" || exit 1
work=$(mktemp -d "$SHARD_DATA/aot/train.XXXXXX") || exit 1
trap 'rm -rf "$work"' EXIT
log=$SHARD_DATA/aot/train.log
# the console must stay ours
for arg in "$@"; do
    shift
    [ "$arg" = --no-console ] || set -- "$@" "$arg"
done

mkfifo "$work/console" || exit 1
SHARD_AOT=train SHARD__NET__BIND=127.0.0.1 SHARD__NET__PORT=$port SHARD__NET__PLAYER_LIMIT=0 SHARD__OPS__ENABLED=false \
    SHARD__STORAGE__DATABASE=$work/shard.db SHARD__STORAGE__BACKUP_DIR=$work/backups SHARD__PERSISTENCE__DIRECTORY=$work/worlds \
    SHARD__LOG__FILE=$work/shard.log SHARD__ADMIN__KICK_COOLDOWN=0s SHARD__ADMIN__UUID_CHANGE_LIMIT=0 \
    "$APP_HOME/bin/shard" "$@" --quiet < "$work/console" > "$log" 2>&1 &
server=$!
exec 3> "$work/console"
echo "shard: training the AOT cache on 127.0.0.1:$port, $bots bots for $seconds s; the server's log is $log" >&2
until grep -q 'ready: ' "$log" 2>/dev/null; do
    if ! kill -0 "$server" 2>/dev/null; then
        echo "shard: the training run stopped before it was ready; see $log" >&2
        exit 1
    fi
    sleep 1
done
worlds=$(sed -n 's/.*ready: \([0-9]*\) worlds hosted.*/\1/p' "$log" | head -n 1)
"$JAVACMD" -Xmx512m -cp "$SHARD_BOTS/lib/*" com.xpdustry.shard.tools.bots.LoadGen --port "$port" --bots "$bots" \
    --duration "$seconds" --ramp-ms 100 --report 60 --chat-s 4 --transfer-s 5 --worlds "${worlds:-1}" > "$SHARD_DATA/aot/train-bots.txt" 2>&1 &
generator=$!
sleep $((seconds / 2))
echo "kick bot3" >&3
wait "$generator"
sleep 2
echo exit >&3
exec 3>&-
# the JVM writes the cache as it exits
wait "$server"
cache=$(sed -n 's/^shard: this run trains the AOT cache \(.*\), written when the server stops.*/\1/p' "$log" | head -n 1)
if [ -n "$cache" ] && [ -s "$cache" ]; then
    echo "shard: trained $cache ($(du -h "$cache" | cut -f 1)); the next start uses it. $(grep -o 'joined=[0-9]*' "$SHARD_DATA/aot/train-bots.txt" | tail -n 1) players" >&2
else
    echo "shard: no AOT cache came out of the training run; see $log" >&2
    exit 1
fi
