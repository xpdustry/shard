#!/usr/bin/env python3
"""Reads bench.py's run directories and prints, for each, one RUN line and its top allocation sites.

    python3 tools/bench/analyze.py [--sites N] [--depth D] RUN_DIR...

RUN line, over the measured window:
  ticks, dropped, tick p50/p99   the console's `perf summary` (every world's ticks together)
  total, tick MiB/s              jdk.ThreadAllocationStatistics: threads present at both ends of the recording; tick
                                 is the tick-N threads (the watchdog excluded)
  tick KiB/wtick                 the tick threads' allocation per world tick, so runs that dropped different numbers of
                                 ticks compare
  gc, pause                      jdk.GarbageCollection events in the recording, and their summed duration
Sites: jdk.ObjectAllocationSample weight (an estimate, not a count) by the first D Shard frames of the stack (default 1;
--depth 3 shows who calls the allocating method) and the allocated class, in MiB over the recording and as a share of
all sampled weight.
Needs the JDK's `jfr` tool: JFR_BIN, else next to JAVA (/opt/jdk27/bin/java), else `jfr` on the PATH.
"""
import collections, datetime, json, os, pathlib, re, shutil, subprocess, sys

args = sys.argv[1:]; sites, depth = 15, 1
while args[:1] in (['--sites'], ['--depth']):
    if args[0] == '--sites': sites = int(args[1])
    else: depth = int(args[1])
    args = args[2:]
jfr = os.environ.get('JFR_BIN') or str(pathlib.Path(os.environ.get('JAVA', '/opt/jdk27/bin/java')).parent / 'jfr')
if not os.path.exists(jfr): jfr = shutil.which('jfr') or 'jfr'
EVENTS = 'jdk.ThreadAllocationStatistics,jdk.ObjectAllocationSample,jdk.GarbageCollection'
MiB = 1048576

def when(s): return datetime.datetime.fromisoformat(s.replace('Z', '+00:00'))
def dur(s):  # ISO-8601 duration as JFR prints it: PT0.073435751S, PT1M2.5S
    m = re.fullmatch(r'PT(?:(\d+)H)?(?:(\d+)M)?(?:([\d.]+)S)?', s)
    return int(m[1] or 0) * 3600 + int(m[2] or 0) * 60 + float(m[3] or 0) if m else 0.0
def cls(name): return re.sub(r'/0x[0-9a-f]+$', '', name).replace('/', '.')  # hidden classes: Foo$$Lambda/0x...

for run in map(pathlib.Path, args):
    out = subprocess.run([jfr, 'print', '--json', '--stack-depth', '24', '--events', EVENTS, str(run / 'server.jfr')],
                         capture_output=True, text=True, check=True).stdout
    events = json.loads(out)['recording']['events']
    alloc = collections.defaultdict(list); weight = collections.Counter(); gcs = []
    for e in events:
        v, t = e['values'], e['type']
        if t == 'jdk.ThreadAllocationStatistics' and v.get('thread'):
            th = v['thread']; alloc[(th['javaThreadId'], th['javaName'])].append((when(v['startTime']), v['allocated']))
        elif t == 'jdk.ObjectAllocationSample':
            frames = (v.get('stackTrace') or {}).get('frames') or []
            own = [f"{cls(f['method']['type']['name']).rsplit('.', 1)[-1]}.{f['method']['name']}:{f.get('lineNumber', '?')}"
                   for f in frames if cls(f['method']['type']['name']).startswith('com.xpdustry.')]
            site = ' < '.join(own[:depth]) or '(no Shard frame)'

            weight[(site, cls(v['objectClass']['name']).rsplit('.', 1)[-1])] += v['weight']
        elif t == 'jdk.GarbageCollection':
            gcs.append(dur(v['duration']))
    total = tick = 0.0
    for (_, name), rows in alloc.items():
        rows.sort()
        secs = (rows[-1][0] - rows[0][0]).total_seconds()
        if len(rows) < 2 or secs <= 0: continue
        rate = (rows[-1][1] - rows[0][1]) / secs; total += rate
        if name and name.startswith('tick-') and name != 'tick-watchdog': tick += rate
    log = (run / 'server.log').read_text(errors='replace')
    m = re.search(r'SUMMARY seconds=(\d+) .*?tick\[n=(\d+) p50=(\d+)us p99=(\d+)us.*?dropped=(\d+)', log)
    secs, n, p50, p99, dropped = map(int, m.groups()) if m else (0, 0, 0, 0, -1)
    per_tick = tick * secs / n / 1024 if n else 0
    print(f'RUN {run.name:<10} ticks={n} dropped={dropped} tick p50={p50 / 1000:.3f} p99={p99 / 1000:.3f} ms '
          f'total={total / MiB:.3f} tick={tick / MiB:.3f} MiB/s tick={per_tick:.2f} KiB/wtick gc={len(gcs)} pause={sum(gcs) * 1000:.0f} ms')
    all_w = sum(weight.values()) or 1
    for (site, klass), w in weight.most_common(sites):
        print(f'  {w / MiB:8.2f} MiB {100 * w / all_w:5.1f}%  {site}  {klass}')
