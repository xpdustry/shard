"""The shared runner for a row's break scripts: apply one line, run its fixtures, expect a failure; N breaks at a time.

A break script lists its BREAKS and calls run() (see w4.4-payload-blocks.py). Run it on a VM, from the Gradle root:

    scripts/vm-gradle.sh --shell 'python3 scripts/breaks/w4.4-payload-blocks.py'             # jobs picked from the VM
    scripts/vm-gradle.sh --shell 'python3 scripts/breaks/w4.4-payload-blocks.py -j 1'         # one at a time, as before
    scripts/vm-gradle.sh --shell 'python3 scripts/breaks/w4.4-payload-blocks.py loader void'  # only breaks whose name has these

How it goes:
 1. The clean entry runs first, in the checkout itself. It must pass, and it fills the local build cache.
 2. Each job gets its own copy of the checkout (rsync, build directories included, under ../shard-breaks/laneN) and
    runs its breaks there, so no two edits meet. The copies share ~/.gradle, so the build cache the clean run filled
    serves every lane's compiles. The checkout itself is never edited after step 1.
 3. Results print in BREAKS order, in the format the serial scripts always wrote, whatever order they finish in.
    Timing goes to stderr.

Extra Gradle arguments for every run: SHARD_BREAK_ARGS (split on spaces), e.g. '-Pshard.testCandidates=off'.

Jobs: -j N, else SHARD_BREAK_JOBS, else min(CPUs / 2, memory / 3.5 GB): each lane is a Gradle daemon (1 GB heap) plus
a test JVM (up to ~1 GB) plus the kernel's page cache for the copy. 4 on a large VM (8 vCPU, 16 GB).

What to expect (w4.4-payload-blocks, 22 breaks, large VM, 4 jobs): 148 s (113 s with warm caches). Until W4.12 it was
842 s: each `--tests '*.Fixture'` run forked one JVM per class file of the source set (155 for :compat:oracleTest), all
but one empty, so a 3 s fixture took ~27 s. build-logic's TestCandidates now forks only the classes a pattern can match;
`SHARD_BREAK_ARGS=-Pshard.testCandidates=off` brings the old behaviour back, for comparison. Per-break times go to
stderr: a lane's first break pays its Gradle daemon's start (45-60 s), later ones take 7-20 s.
"""
import glob, html, os, re, shutil, subprocess, sys, threading, time
from concurrent.futures import ThreadPoolExecutor


def auto_jobs():
    cpus = os.cpu_count() or 1
    mem_gb = os.sysconf('SC_PAGE_SIZE') * os.sysconf('SC_PHYS_PAGES') / (1 << 30)
    return max(1, min(cpus // 2, int(mem_gb / 3.5)))


def results(root, globs):
    """{suite class name: (tests, [first lines of failure messages])} from the JUnit XML under root."""
    out = {}
    for pattern in globs:
        for x in glob.glob(os.path.join(root, pattern)):
            text = open(x).read()
            suite = re.search(r'<testsuite name="[^"]*\.(\w+)" tests="(\d+)"', text)
            fails = [html.unescape(m.group(1)).replace('\n', ' | ')[:260]
                     for m in re.finditer(r'<failure message="(.*?)"', text, re.S)]
            out[suite.group(1)] = (int(suite.group(2)), fails)
    return out


def run(breaks, fixtures, gradle_args, result_dirs, argv=None):
    """breaks: (name, file, original text, break) tuples, the first being ('clean', None, None, None).
    fixtures: test class names, each of which must be in the results. gradle_args: what follows `./gradlew -q
    --continue` with the fixtures' --tests appended. result_dirs: JUnit XML directories, relative to the root."""
    argv = sys.argv[1:] if argv is None else argv
    jobs = int(os.environ.get('SHARD_BREAK_JOBS') or 0) or auto_jobs()
    keep = False
    only = []
    i = 0
    while i < len(argv):
        if argv[i] in ('-j', '--jobs'):
            jobs = int(argv[i + 1]); i += 2
        elif argv[i] == '--keep-lanes':
            keep = True; i += 1
        else:
            only.append(argv[i]); i += 1

    root = os.getcwd()
    globs = [d.rstrip('/') + '/*.xml' for d in result_dirs]
    wanted = [b for b in breaks if not only or any(o in b[0] for o in only)]
    started = time.time()

    def execute(name, path, original, broken, tree):
        """One break in one tree: the text block the serial scripts printed for it."""
        src = None
        file = os.path.join(tree, path) if path else None
        if file:
            src = open(file).read()
            if src.count(original) != 1:
                return f'SKIP {name}: the original is in {path} {src.count(original)} times\n'
            open(file, 'w').write(src.replace(original, broken))
        for d in result_dirs:
            shutil.rmtree(os.path.join(tree, d), ignore_errors=True)
        try:
            args = ['./gradlew', '-q', '--continue'] + gradle_args + os.environ.get('SHARD_BREAK_ARGS', '').split()
            for fixture in fixtures:
                args += ['--tests', '*.' + fixture]
            for attempt in range(3):
                r = subprocess.run(args, cwd=tree, capture_output=True, text=True)
                # Gradle itself failed before any test ran: a lane daemon timed out on ~/.gradle's cache lock (seen with
                # a stale daemon holding it). That says nothing about the break, so run it again.
                if r.returncode == 0 or 'Timeout waiting to lock' not in r.stdout + r.stderr or results(tree, globs):
                    break
                print(f'breaks: {name}: Gradle timed out on a cache lock, running it again', file=sys.stderr, flush=True)
        finally:
            if src is not None:
                open(file, 'w').write(src)
        found = results(tree, globs)
        failed = [f for f in fixtures if found.get(f, (0, []))[1]]
        missing = [f for f in fixtures if f not in found]
        if name == 'clean':
            verdict = 'OK' if r.returncode == 0 and not failed and not missing else 'BROKEN BASELINE'
        else:
            verdict = 'CAUGHT' if failed else 'MISSED'
        text = f'{verdict:8} {name}: fails {", ".join(failed) or "nothing"}' + \
               (f' (no results: {", ".join(missing)})' if missing else '') + '\n'
        for fixture in failed:
            tests, fails = found[fixture]
            text += f'           {fixture}: {len(fails)} of {tests} failed; first: {fails[0]}\n'
        if r.returncode != 0 and not found:
            text += '           ' + ' / '.join(l.strip() for l in (r.stdout + r.stderr).splitlines() if l.strip())[-600:] + '\n'
        return text

    # Output in BREAKS order: a finished break waits for the ones before it.
    done, printed, lock = {}, [0], threading.Lock()

    def emit(index, text):
        with lock:
            done[index] = text
            while printed[0] in done:
                sys.stdout.write(done.pop(printed[0]))
                sys.stdout.flush()
                printed[0] += 1

    todo = list(enumerate(wanted))
    # 1. the clean run, in the checkout, before the lanes are copied (warm build directories, filled build cache)
    if todo and todo[0][1][1] is None:
        emit(0, execute(*todo[0][1], root))
        todo = todo[1:]
    if not todo:
        return
    jobs = max(1, min(jobs, len(todo)))
    print(f'breaks: {len(todo)} to run, {jobs} at a time', file=sys.stderr, flush=True)

    lanes_dir = os.path.join(os.path.dirname(root), 'shard-breaks')
    lanes = []
    lane_lock = threading.Lock()
    free = []

    def make_lane(n):
        lane = os.path.join(lanes_dir, f'lane{n}')
        os.makedirs(lane, exist_ok=True)
        subprocess.run(['rsync', '-a', '--delete', '--exclude', '.vm-id', root + '/', lane + '/'], check=True)
        return lane

    with ThreadPoolExecutor(jobs) as copy_pool:
        lanes = list(copy_pool.map(make_lane, range(jobs)))
    free.extend(lanes)

    def work(item):
        index, (name, path, original, broken) = item
        with lane_lock:
            lane = free.pop()
        t0 = time.time()
        try:
            text = execute(name, path, original, broken, lane)
        finally:
            with lane_lock:
                free.append(lane)
        print(f'breaks: {time.time() - t0:5.0f} s  {name}', file=sys.stderr, flush=True)
        emit(index, text)

    try:
        with ThreadPoolExecutor(jobs) as pool:
            for f in [pool.submit(work, t) for t in todo]:
                f.result()
    finally:
        if not keep:
            for lane in lanes:
                shutil.rmtree(lane, ignore_errors=True)
            try:
                os.rmdir(lanes_dir)
            except OSError:
                pass
    print(f'breaks: {len(wanted)} in {time.time() - started:.0f} s', file=sys.stderr, flush=True)
