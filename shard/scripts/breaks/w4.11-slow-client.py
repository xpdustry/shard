"""W4.11's one-line breaks: the reliable queue's soft cap, its grace and its hard ceiling. Each is applied alone to a
clean checkout and run against SlowClientTest; a break must fail at least one of its tests. A clean run comes first,
and must pass.

Run on a VM, from the Gradle root (scripts/vm-gradle.sh --shell 'python3 scripts/breaks/w4.11-slow-client.py'). The
results, as committed, are in w4.11-slow-client.log.
"""
import os

import runner

os.chdir(os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..'))

NET = 'net/src/main/java/com/xpdustry/shard/net/'
CONN = NET + 'Conn.java'
TRANSPORT = NET + 'Transport.java'

FIXTURES = ['SlowClientTest']

# (name, file, the original text (exactly once in the file), the break)
BREAKS = [
    ('clean', None, None, None),
    ('the soft cap closes at once, as before', CONN,
     'long max = udp ? config.maxQueuedUdpBytes() : config.hardMaxQueuedBytes();',
     'long max = udp ? config.maxQueuedUdpBytes() : config.maxQueuedBytes();'),
    ('a backlog over the soft cap is never closed', TRANSPORT,
     '}else if(overCap != 0 && now - overCap > grace){', '}else if(false){'),
    ('no hard ceiling', CONN,
     'if(held + frame.size() > max){', 'if(udp && held + frame.size() > max){'),
    ('draining under the soft cap does not end the grace', CONN,
     'if(overCapNanos != 0 && queuedBytes <= transport.config().maxQueuedBytes()) overCapNanos = 0;',
     '// if(overCapNanos != 0 && queuedBytes <= transport.config().maxQueuedBytes()) overCapNanos = 0;'),
]

runner.run(BREAKS, FIXTURES, [':net:test'], ['net/build/test-results/test'])
