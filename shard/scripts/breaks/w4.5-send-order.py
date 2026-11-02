"""W4.5's one-line breaks: the send path's order (Q215), its wakes and caps, the Outbox's bound and flush, and the spam
DOS ban. Each is applied alone to a clean checkout and run against the tests that gate it; a break must fail at least
one of them. A clean run comes first, and must pass all of them.

Run on a VM, from the Gradle root (scripts/vm-gradle.sh --shell 'python3 scripts/breaks/w4.5-send-order.py');
the results, as committed, are in w4.5-send-order.log.
"""
import glob, html, os, re, subprocess, sys

os.chdir(os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..'))

CONN = 'net/src/main/java/com/xpdustry/shard/net/Conn.java'
OUTBOX = 'server/src/main/java/com/xpdustry/shard/server/sync/Outbox.java'
FLOW = 'server/src/main/java/com/xpdustry/shard/server/connect/ConnectFlow.java'

# (module, test filter) pairs every run executes
TESTS = [('net', 'com.xpdustry.shard.net.SendOrderTest'),
         ('server', 'com.xpdustry.shard.server.sync.OutboxTest'),
         ('server', 'com.xpdustry.shard.server.console.ConsoleCommandsTest.packetAndChatSpamDosBanTheAddress')]

# (name, file, the original text (exactly once in the file), the break)
BREAKS = [
    ('clean', None, None, None),
    ("main's UDP: datagrams sent from the queueing thread at once", CONN,
     '                queue.add(frame, udp);\n',
     '                if(udp) transport.sendDatagram(frame.udp(), udpRemote, frame.bodySize()); else queue.add(frame, udp);\n'),
    ('flush wakes nothing', CONN,
     '''            if(unflushed){
                unflushed = false;
                unflushedBytes = 0;
                ready.signal();
            }''',
     '''            if(false){
                unflushed = false;
                unflushedBytes = 0;
                ready.signal();
            }'''),
    ('no wake past WAKE_BYTES', CONN,
     'if(wake || unflushedBytes >= WAKE_BYTES){', 'if(wake){'),
    ('a full UDP queue closes', CONN,
     '                transport.stats().udpShed.increment();\n',
     '                tryClose("udp full");\n'),
    ('the blacklist is never told', CONN,
     'if(broken.action() == RateLimits.Action.BLACKLIST && isOpen()) transport.blacklisted(this);',
     'if(false) transport.blacklisted(this);'),
    ('offer unbounded in frames', OUTBOX,
     'if(offeredFrames.incrementAndGet() > maxOfferedFrames){', 'if(offeredFrames.incrementAndGet() > Integer.MAX_VALUE){'),
    ('offer unbounded in bytes', OUTBOX,
     'if(offeredBytes.addAndGet(frame.size()) > maxOfferedBytes){', 'if(offeredBytes.addAndGet(frame.size()) > Long.MAX_VALUE){'),
    ('the Outbox never flushes', OUTBOX,
     '            transmit.flush(seat.session);\n', '            // transmit.flush(seat.session);\n'),
    ('chat spam only closes', FLOW,
     'new RateLimits.Limit(chat, chat / 2.0, RateLimits.Action.BLACKLIST)', 'new RateLimits.Limit(chat, chat / 2.0, RateLimits.Action.CLOSE)'),
]


def results():
    out = {}
    for module in {m for m, _ in TESTS}:
        for x in glob.glob(f'{module}/build/test-results/test/*.xml'):
            text = open(x).read()
            suite = re.search(r'<testsuite name="[^"]*\.(\w+)" tests="(\d+)"', text)
            fails = [html.unescape(m.group(1)).replace('\n', ' | ')[:260] for m in re.finditer(r'<failure message="(.*?)"', text, re.S)]
            out[suite.group(1)] = (int(suite.group(2)), fails)
    return out


only = sys.argv[1:]
for name, path, original, broken in BREAKS:
    if only and not any(o in name for o in only):
        continue
    src = None
    if path:
        src = open(path).read()
        if src.count(original) != 1:
            print(f'SKIP {name}: the original is in {path} {src.count(original)} times', flush=True)
            continue
        open(path, 'w').write(src.replace(original, broken))
    subprocess.run('rm -rf net/build/test-results/test server/build/test-results/test', shell=True)
    try:
        args = ['./gradlew', '-q', '--continue']
        for module in sorted({m for m, _ in TESTS}):
            args += [f':{module}:test'] + [a for m, t in TESTS if m == module for a in ('--tests', t)]
        r = subprocess.run(args, capture_output=True, text=True)
    finally:
        if src is not None:
            open(path, 'w').write(src)
    found = results()
    suites = sorted({t.split('.')[-1] if t.split('.')[-1][0].isupper() else t.split('.')[-2] for _, t in TESTS})
    failed = [s for s in suites if found.get(s, (0, []))[1]]
    missing = [s for s in suites if s not in found]
    if name == 'clean':
        verdict = 'OK' if r.returncode == 0 and not failed and not missing else 'BROKEN BASELINE'
    else:
        verdict = 'CAUGHT' if failed else 'MISSED'
    print(f'{verdict:8} {name}: fails {", ".join(failed) or "nothing"}' + (f' (no results: {", ".join(missing)})' if missing else ''), flush=True)
    for suite in failed:
        tests, fails = found[suite]
        print(f'           {suite}: {len(fails)} of {tests} failed; first: {fails[0]}', flush=True)
    if r.returncode != 0 and not found:
        print('           ' + ' / '.join(l.strip() for l in (r.stdout + r.stderr).splitlines() if l.strip())[-600:], flush=True)
