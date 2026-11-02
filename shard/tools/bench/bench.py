#!/usr/bin/env python3
"""One run of the five-world allocation bench (W4.6), on an 8-vCPU boat VM, from the shard/ directory.

    python3 tools/bench/bench.py OUT TAG [--server BIN]

Hosts activebench.msav in five worlds (w00..w04) on one Shard server, joins 20 walking, shooting and building bots
(shard-bots --walk; four per world, the server's `join: fewest`), waits WARMUP seconds, then records JFR's `profile`
settings and the console's `perf` summary for MEASURE seconds. The worlds tick only once their first bot joins
(simulate-when-empty: false). The server is pinned to CPUs 0-3 and the bots to 4-7, as the load gate does.
Writes OUT/TAG/: server.log, bots.log, gc.log, server.jfr, and the run's line from analyze.py (analysis.txt).

Env: JAVA (/opt/jdk27/bin/java), VANILLA_JAR (~/vanilla/v160.7/server-release.jar), BOTS (20), WORLDS (5),
WARMUP (30), MEASURE (60), HEAP_MIN (512m), HEAP_MAX (1g), SHARD_GC (g1), SERVER_CPUS (0-3), BOT_CPUS (4-7).
"""
import argparse, os, pathlib, shutil, subprocess, sys, time

here = pathlib.Path(__file__).resolve().parent
shard_dir = here.parent.parent
env = os.environ

ap = argparse.ArgumentParser()
ap.add_argument('out'); ap.add_argument('tag')
ap.add_argument('--server', default=str(shard_dir / 'server/build/install/shard/bin/shard'))
ap.add_argument('--bots-bin', default=str(shard_dir / 'tools/bots/build/install/shard-bots/bin/shard-bots'))
a = ap.parse_args()

java = env.get('JAVA', '/opt/jdk27/bin/java'); jdk = pathlib.Path(java).parent
jar = env.get('VANILLA_JAR', str(pathlib.Path.home() / 'vanilla/v160.7/server-release.jar'))
bots, worlds = int(env.get('BOTS', 20)), int(env.get('WORLDS', 5))
warmup, measure = int(env.get('WARMUP', 30)), int(env.get('MEASURE', 60))
base = pathlib.Path(a.out).resolve() / a.tag
shutil.rmtree(base, ignore_errors=True)
conf = base / 'server'; (conf / 'maps').mkdir(parents=True)
shutil.copy(here / 'activebench.msav', conf / 'maps/activebench.msav')
names = ', '.join(f'"w{i:02}=activebench"' for i in range(worlds))
(conf / 'shard.yml').write_text(f'''net:
  bind: 127.0.0.1
  port: 6567
  player-limit: 0
ops:
  enabled: false
log:
  format: text
admin:
  uuid-change-limit: 0
host:
  worlds: [{names}]
  join: fewest
maps:
  vanilla-jar: "{jar}"
  directory: "{conf}/maps"
storage:
  database: "{conf}/data/shard.db"
  backup-dir: "{conf}/data/backups"
persistence:
  directory: "{conf}/data/worlds"
game:
  simulate-when-empty: false
''')

files, procs = [], []
def launch(cmd, log, extra, stdin=False):
    f = open(log, 'w'); files.append(f)
    p = subprocess.Popen(cmd, cwd=base, env=dict(env, **extra), stdout=f, stderr=subprocess.STDOUT,
                         stdin=subprocess.PIPE if stdin else subprocess.DEVNULL, text=True)
    procs.append(p); return p
def send(p, line):
    p.stdin.write(line + '\n'); p.stdin.flush()
def jcmd(p, *args):
    r = subprocess.run([str(jdk / 'jcmd'), str(p.pid), *args], capture_output=True, text=True)
    if r.returncode: raise RuntimeError(f'jcmd {args}: {r.stdout}{r.stderr}')

log = base / 'server.log'
try:
    server = launch(['taskset', '-c', env.get('SERVER_CPUS', '0-3'), a.server, '--config', str(conf)], log, {
        'JAVA_HOME': str(jdk.parent), 'SHARD_AOT': 'off', 'SHARD_GC': env.get('SHARD_GC', 'g1'), 'SHARD_DATA': str(conf / 'data'),
        'JAVA_OPTS': f"-Xms{env.get('HEAP_MIN', '512m')} -Xmx{env.get('HEAP_MAX', '1g')} -Xlog:gc:file={base / 'gc.log'}"}, stdin=True)
    for _ in range(180):
        if 'ready:' in log.read_text(errors='replace'): break
        if server.poll() is not None: raise RuntimeError('the server exited before it was ready')
        time.sleep(0.5)
    else: raise RuntimeError('the server was not ready in 90 s')
    launch(['taskset', '-c', env.get('BOT_CPUS', '4-7'), a.bots_bin, '--port', '6567', '--bots', str(bots),
            '--duration', str(warmup + measure + 30), '--ramp-ms', '200', '--report', '10', '--chat-s', '20', '--walk'],
           base / 'bots.log', {'JAVA_HOME': str(jdk.parent), 'JAVA_OPTS': '-Xms256m -Xmx512m'})
    time.sleep(warmup)
    if server.poll() is not None: raise RuntimeError('the server exited during the warmup')
    send(server, 'perf mark')
    jcmd(server, 'JFR.start', 'name=bench', 'settings=profile', f'filename={base / "server.jfr"}')
    time.sleep(measure)
    jcmd(server, 'JFR.stop', 'name=bench')
    for line in ('perf summary', 'perf profile', 'status'): send(server, line)
    time.sleep(2)
finally:
    for p in procs:
        if p.poll() is None: p.terminate()
    for p in procs:
        try: p.wait(timeout=10)
        except subprocess.TimeoutExpired: p.kill(); p.wait()
    for f in files: f.close()

r = subprocess.run([sys.executable, str(here / 'analyze.py'), str(base)], capture_output=True, text=True)
(base / 'analysis.txt').write_text(r.stdout + r.stderr)
print(r.stdout + r.stderr, end='')
sys.exit(r.returncode)
