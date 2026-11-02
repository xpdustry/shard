"""Top allocation stacks (weight) on threads whose name starts with a prefix: python3 stacks.py <jfr> <prefix> [n]."""
import json, subprocess, sys, collections
path, prefix = sys.argv[1], sys.argv[2]
n = int(sys.argv[3]) if len(sys.argv) > 3 else 5
out = subprocess.run(['/opt/jdk27/bin/jfr', 'print', '--json', '--stack-depth', '12', '--events', 'jdk.ObjectAllocationSample', path],
                     capture_output=True, text=True).stdout
stacks = collections.Counter()
for e in json.loads(out)['recording']['events']:
    v = e['values']
    thread = (v.get('eventThread') or {}).get('javaName') or ''
    if not thread.startswith(prefix): continue
    frames = [f"{f['method']['type']['name'].rsplit('.', 1)[-1]}.{f['method']['name']}:{f.get('lineNumber')}" for f in ((v.get('stackTrace') or {}).get('frames') or [])]
    stacks[(v['objectClass']['name'].rsplit('.', 1)[-1],) + tuple(frames[:7])] += v['weight']
total = sum(stacks.values())
print(f'{path.split("/")[-2]} {prefix}*: {total / 1048576:.2f} MiB sampled')
for key, w in stacks.most_common(n):
    print(f'  {w / 1048576:6.2f} MiB  {key[0]}  <- ' + ' < '.join(key[1:]))
