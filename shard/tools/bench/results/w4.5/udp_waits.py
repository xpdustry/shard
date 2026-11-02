"""W4.5: in each bench recording, the waits on the send path and the send path's sampled allocation, by thread kind.
Waits: jdk.ThreadPark and jdk.JavaMonitorEnter events (profile settings: 10 ms and 20 ms thresholds) whose stack has
DatagramChannelImpl.send. Allocation: jdk.ObjectAllocationSample weight whose stack has a com.xpdustry.shard.net frame.
Run on the VM: python3 udp_waits.py ~/bench-out/*/server.jfr"""
import json, subprocess, sys, collections

for path in sys.argv[1:]:
    out = subprocess.run(['/opt/jdk27/bin/jfr', 'print', '--json', '--stack-depth', '24',
                          '--events', 'jdk.ThreadPark,jdk.JavaMonitorEnter,jdk.ObjectAllocationSample', path],
                         capture_output=True, text=True).stdout
    events = json.loads(out)['recording']['events']
    waits = collections.defaultdict(lambda: [0, 0.0, 0.0])
    alloc = collections.Counter()
    for e in events:
        v = e['values']
        frames = [f['method']['type']['name'] + '.' + f['method']['name'] for f in ((v.get('stackTrace') or {}).get('frames') or [])]
        thread = (v.get('eventThread') or {}).get('javaName') or '?'
        kind = 'tick' if thread.startswith('tick-') else ('tcp-writer' if thread.startswith('tcp-w') else ('virtual' if not thread or thread == '?' else thread.split('-')[0]))
        if e['type'] in ('jdk.ThreadPark', 'jdk.JavaMonitorEnter'):
            if any('DatagramChannelImpl.send' in f for f in frames):
                d = v['duration']
                ms = float(d.rstrip('s')) * 1000 if d.endswith('s') and not d.endswith('ms') else float(d.rstrip('ms'))
                w = waits[kind]; w[0] += 1; w[1] += ms; w[2] = max(w[2], ms)
        else:
            # the innermost Shard frame: a site in net, or a send path caller's
            shard = [f for f in frames if f.startswith('com.xpdustry.shard.')]
            inner = shard[0] if shard else '(no Shard frame) ' + (frames[0] if frames else '?')
            if inner.startswith('com.xpdustry.shard.net.') or 'Outbox' in inner or 'Intents.decode' in inner or 'WireException' in inner \
                    or (not shard and kind == 'tcp-writer'):
                alloc[inner.replace('com.xpdustry.shard.', '') + ' @' + kind] += v['weight']
    print(path.split('/')[-2])
    if not waits: print('  waits under DatagramChannelImpl.send: none over the thresholds')
    for kind, (n, total, worst) in sorted(waits.items()):
        print(f'  waits under DatagramChannelImpl.send on {kind}: {n}, total {total:.1f} ms, longest {worst:.1f} ms')
    total = sum(alloc.values())
    print(f'  sampled allocation at net, Outbox and decode sites: {total / 1048576:.2f} MiB; top: ' +
          ', '.join(f'{s} {w / 1048576:.2f}' for s, w in alloc.most_common(8)))
