"""The test map: which test classes a change can affect, so a row's inner loop reruns only those (W4.12).

    python3 scripts/testmap.py update --since <epoch s> [--full]  # after a Gradle run: fold build/testmap/**/*.log in
    python3 scripts/testmap.py select                     # print the Gradle arguments that run the affected tests
    python3 scripts/testmap.py stats                      # what the map holds

scripts/vm-gradle.sh runs these itself (`--affected`, and `update` after every Gradle run); run them from the Gradle
root on a VM. The map lives outside the checkout (~/.shard-testmap/map.json.gz, or SHARD_TESTMAP_FILE), because
vm-gradle.sh syncs the checkout with rsync --delete.

What it records. With SHARD_TESTMAP=1, every test JVM logs the classes it loads and initializes (build-logic's
TestMap). For each test class that ran in a Gradle run, `update` keeps an entry: the Shard classes it depends on, each
with the SHA-256 of its class file at that moment, plus digests of the files that aren't Java sources (see below) and
whether it failed. A JVM that ran several classes (`test` tasks) gives each of them its whole set: more than they
need, never less.

Which classes a test depends on: every class its JVM initialized. A JVM runs a class's code (or reads its statics, or
makes an instance to reflect over) only after initializing it, so a class that was never initialized ran no code in
the test. Loading isn't enough to count: the registration index links every kind's constructor, so every test that
builds a World loads every kind (a one-line edit to PayloadVoid selected 286 of 375 classes by loading, 198 with
`test` tasks by loading and oracle tests by initialization, 2 oracle classes by initialization).
A test task that hands class directories to its tests in a system property (:sim:test's shard.sim.classes, which
CoverageTest and DeterminismTest walk) scans them: build-logic writes them to scans.txt, and its tests also depend on
every class file in them, and run again when one is added or removed (rule 5). A test that discovers classes some other
way (a classpath scan) breaks this rule.

Why that is enough. If none of those classes' bytes changed, the run executes the same bytecode. A new or changed class
it would now reach must be reached from a class whose bytes changed. Class-file bytes, not sources, are compared: a
constant that javac inlined into a caller changes the caller's bytes. So a test class is selected when:
 1. a class it depends on has other bytes now, or is gone;
 2. it is not in the map (new, renamed, never ran with the map on), or it failed last time;
 3. a file under src/*/resources of a module it loaded classes from changed;
 4. any other non-Java file changed (build scripts, build-logic, gradle/, data/, rig/, schema/...): everything runs.
    Documentation (*.md, docs/) and scripts/ don't count;
 5. its task scans class directories (above) and a class file was added to or removed from one.
A test class that ran no test in a full run (all its tests tag-excluded: slow, client) gets an entry with only its own
bytes: it runs again when they change.
A test that walks class directories must stay in a `test` task for rule 5 to cover it.

Outside this rule: the vanilla jar (~/.cache/shard/vanilla) is covered by rule 4's digest of its path list only if it
moves; a different jar at the same path needs a full check, which is the gate anyway (scripts/vm-gradle.sh --help).
"""
import glob, gzip, hashlib, json, os, re, sys, time

ROOT = os.getcwd()
MAP = os.environ.get('SHARD_TESTMAP_FILE') or os.path.expanduser('~/.shard-testmap/map.json.gz')
VANILLA = os.path.expanduser('~/.cache/shard/vanilla/v160.7/server-release.jar')
# the same annotations as build-logic's TestCandidates (@Nested too: a class of nested test classes has no test method)
TEST_MARKS = ('Lorg/junit/jupiter/api/Test;', 'Lorg/junit/jupiter/params/ParameterizedTest;',
              'Lorg/junit/jupiter/api/RepeatedTest;', 'Lorg/junit/jupiter/api/TestFactory;',
              'Lorg/junit/jupiter/api/TestTemplate;', 'Lorg/junit/jupiter/api/Nested;')
PLATFORM = ('java/', 'javax/', 'jdk/', 'sun/')
SKIP_DIRS = {'build', '.gradle', '.kotlin', '.git', 'node_modules'}


def sha(data):
    return hashlib.sha256(data).hexdigest()[:20]


def project_of(module_dir):
    """'tools/oracle' -> ':tools:oracle'"""
    return ':' + module_dir.replace(os.sep, ':')


def class_dirs():
    """(module dir, source set, path) for every compiled Java source set of the build."""
    out = []
    for d in glob.glob(os.path.join(ROOT, '**', 'build', 'classes', 'java', '*'), recursive=True):
        rel = os.path.relpath(d, ROOT)
        module = rel.split(os.sep + 'build' + os.sep)[0]
        if module.split(os.sep)[0] == 'build-logic':
            continue  # an included build: a change there is a non-Java file change for this map (rule 4)
        out.append((module, os.path.basename(d), d))
    return out


def parse(data):
    """(name, super, interfaces, constant pool strings, annotation types) of a class file, or None. As TestCandidates.ClassInfo."""
    u2 = lambda i: int.from_bytes(data[i:i + 2], 'big')
    u4 = lambda i: int.from_bytes(data[i:i + 4], 'big')
    if data[:4] != b'\xca\xfe\xba\xbe':
        return None
    count, i, strings, classes, idx = u2(8), 10, {}, {}, 1
    while idx < count:
        tag = data[i]
        i += 1
        if tag == 1:
            n = u2(i)
            strings[idx] = data[i + 2:i + 2 + n].decode('utf-8', 'replace')
            i += 2 + n
        elif tag == 7:
            classes[idx] = u2(i)
            i += 2
        elif tag in (8, 16, 19, 20):
            i += 2
        elif tag == 15:
            i += 3
        elif tag in (3, 4, 9, 10, 11, 12, 17, 18):
            i += 4
        elif tag in (5, 6):
            i += 8
            idx += 1
        else:
            return None
        idx += 1
    name = strings.get(classes.get(u2(i + 2)))
    parent = strings.get(classes.get(u2(i + 4)))
    n = u2(i + 6)
    interfaces = [strings.get(classes.get(u2(i + 8 + 2 * k))) for k in range(n)]
    i += 8 + 2 * n
    annotations = set()

    def value(i):
        tag = chr(data[i])
        if tag == 'e':
            return i + 5
        if tag == '@':
            return annotation(i + 1)
        if tag == '[':
            n, i = u2(i + 1), i + 3
            for _ in range(n):
                i = value(i)
            return i
        return i + 3

    def annotation(i):
        annotations.add(strings.get(u2(i)))
        n, i = u2(i + 2), i + 4
        for _ in range(n):
            i = value(i + 2)
        return i

    def attributes(i, collect):
        n, i = u2(i), i + 2
        for _ in range(n):
            if collect and strings.get(u2(i)) == 'RuntimeVisibleAnnotations':
                j = i + 8
                for _ in range(u2(i + 6)):
                    j = annotation(j)
            i += 6 + u4(i + 2)
        return i

    for collect in (False, True):  # fields, then methods
        n, i = u2(i), i + 2
        for _ in range(n):
            i = attributes(i + 6, collect)
    attributes(i, True)
    types = {a[1:-1] for a in annotations if a and a.startswith('L') and a.endswith(';')}
    return name, parent, [t for t in interfaces if t], set(strings.values()), types


class Discovery:
    """Which classes may hold tests, as build-logic's TestCandidates decides: it or a supertype carries a JUnit test
    annotation, or an annotation meta-annotated with one. A supertype or an annotation type found in no class
    directory counts as a test, except the platform's and JUnit's own (jars aren't on this side): a class is never
    dropped for something unknown. A composed annotation that is a test makes its own class file a test too."""

    def __init__(self, paths):
        self.paths = paths  # class name (internal form) -> class file
        self.info = {}
        self.memo = {}

    def read(self, name):
        if name not in self.info:
            data = open(self.paths[name], 'rb').read() if name in self.paths else None
            self.info[name] = parse(data) if data else None
        return self.info[name]

    def tests(self, name, active=()):
        if name in self.memo:
            return self.memo[name]
        info = self.read(name)
        if info is None:
            return False
        _, parent, interfaces, pool, annotations = info
        found = any(m in pool for m in TEST_MARKS) or len(active) > 32
        for t in ([parent] if parent else []) + interfaces:
            found = found or self.reaches(t, active + (name,))
        for t in annotations:
            found = found or self.reaches(t, active + (name,))
        self.memo[name] = found
        return found

    def reaches(self, t, active):
        if t.startswith(PLATFORM) or t.startswith('org/junit/') or t in active:
            return False
        if t not in self.paths:
            return True
        return self.tests(t, active)


def classes():
    """{class name: (hash, module, source set, is a test class, its class directory)} for every compiled class."""
    found = []
    for module, source_set, d in class_dirs():
        for base, _, files in os.walk(d):
            for f in files:
                if f.endswith('.class') and f not in ('module-info.class', 'package-info.class'):
                    found.append((module, source_set, d, os.path.join(base, f)))
    discovery = Discovery({os.path.relpath(p, d)[:-6].replace(os.sep, '/'): p for _, _, d, p in found})
    out = {}
    for module, source_set, d, p in found:
        internal = os.path.relpath(p, d)[:-6].replace(os.sep, '/')
        data = open(p, 'rb').read()
        is_test = source_set in ('test', 'oracleTest') and discovery.tests(internal)
        out[internal.replace('/', '.')] = (sha(data), module, source_set, is_test, d)
    return out


def other_files():
    """(digest of every non-Java file that isn't a resource, {module: digest of its resources})."""
    env, res = [], {}
    for base, dirs, files in os.walk(ROOT):
        dirs[:] = sorted(d for d in dirs if d not in SKIP_DIRS)
        rel_base = os.path.relpath(base, ROOT)
        parts = [] if rel_base == '.' else rel_base.split(os.sep)
        if parts[:1] in (['docs'], ['scripts']):
            dirs[:] = []
            continue
        if 'src' in parts:
            i = parts.index('src')
            kind = parts[i + 2] if len(parts) > i + 2 else None
            if kind == 'java':
                dirs[:] = []
                continue
            if kind == 'resources':
                module = os.sep.join(parts[:i])
                for f in sorted(files):
                    p = os.path.join(base, f)
                    res.setdefault(module, []).append(os.path.relpath(p, ROOT) + ':' + sha(open(p, 'rb').read()))
                continue
        for f in sorted(files):
            if f.endswith('.md') or f in ('.vm-id',) or f.startswith('.vm-id'):
                continue
            p = os.path.join(base, f)
            env.append(os.path.relpath(p, ROOT) + ':' + sha(open(p, 'rb').read()))
    env.append('vanilla:' + (str(os.path.getsize(VANILLA)) if os.path.exists(VANILLA) else 'missing'))
    return sha('\n'.join(env).encode()), {m: sha('\n'.join(v).encode()) for m, v in res.items()}


def load():
    if not os.path.exists(MAP):
        return {'entries': {}, 'classes': []}
    with gzip.open(MAP, 'rt') as f:
        return json.load(f)


def save(m):
    os.makedirs(os.path.dirname(MAP), exist_ok=True)
    tmp = MAP + '.tmp'
    with gzip.open(tmp, 'wt') as f:
        json.dump(m, f, separators=(',', ':'))
    os.replace(tmp, MAP)


LOADED = re.compile(r'\]\[class,load\] (com\.xpdustry\.shard\.[\w.$]+) source:')
INITIALIZED = re.compile(r"\]\[class,init\] \d+ Initializing '(com/xpdustry/shard/[\w/$]+)'")


def ran(module, task, since):
    """{test class: failed?} for the classes whose JUnit XML the task wrote since `since`."""
    out = {}
    for x in glob.glob(os.path.join(ROOT, module, 'build', 'test-results', task, 'TEST-*.xml')):
        if os.path.getmtime(x) < since:
            continue
        head = open(x, errors='replace').read(2000)
        m = re.search(r'<testsuite name="([^"]+)" tests="\d+" skipped="\d+" failures="(\d+)" errors="(\d+)"', head)
        if m:
            out[m.group(1)] = m.group(2) != '0' or m.group(3) != '0'
    return out


def names_in(current, dirs):
    """A digest of which classes the directories hold (rule 5)."""
    return sha('\n'.join(sorted(n for n, v in current.items() if v[4] in dirs)).encode())


def update(since, full):
    m = load()
    current = classes()
    env, res = other_files()
    names = m['classes']
    index = {n: i for i, n in enumerate(names)}
    updated = 0
    ran_tasks = set()  # (module, task) whose JVMs logged in this run
    for logdir in glob.glob(os.path.join(ROOT, '**', 'build', 'testmap', '*'), recursive=True):
        module = os.path.relpath(logdir, ROOT).split(os.sep + 'build' + os.sep)[0]
        task = os.path.basename(logdir)
        logs = sorted(glob.glob(os.path.join(logdir, '*.log')))
        if logs:
            ran_tasks.add((module, task))
        outcome = ran(module, task, since)
        sets = {}
        scans = []
        scans_file = os.path.join(logdir, 'scans.txt')
        if os.path.exists(scans_file):
            scans = [l for l in open(scans_file).read().splitlines() if l]
        scanned = {n for n, v in current.items() if v[4] in scans}
        for log in logs:
            text = open(log, errors='replace').read()
            loaded = set(LOADED.findall(text))
            depends = {n.replace('/', '.') for n in INITIALIZED.findall(text)} | scanned
            for test in loaded & outcome.keys():
                sets.setdefault(test, set()).update(depends | {test})
            os.remove(log)
        for test, loaded in sets.items():
            ids = []
            for n in sorted(loaded):
                if n not in current:
                    continue  # a class from a jar outside this build's class dirs: covered by rule 4
                if n not in index:
                    index[n] = len(names)
                    names.append(n)
                ids.append([index[n], current[n][0]])
            modules = sorted({current[n][1] for n in loaded if n in current})
            m['entries'][f'{project_of(module)}:{task}|{test}'] = {
                'loaded': ids, 'failed': outcome[test], 'env': env, 'scans': scans, 'names': names_in(current, scans),
                'res': {mod: res.get(mod, '') for mod in modules}, 'at': int(time.time())}
            updated += 1
    if full:
        # an unfiltered run asked for every test class of the tasks that ran; one with no results ran no test
        for name, (h, module, source_set, is_test, _) in current.items():
            key = f'{project_of(module)}:{source_set}|{name}'
            if is_test and (module, source_set) in ran_tasks and key not in m['entries'] and not glob.glob(os.path.join(ROOT, module, 'build', 'test-results', source_set, f'TEST-{name}.xml')):
                if name not in index:
                    index[name] = len(names)
                    names.append(name)
                m['entries'][key] = {'loaded': [[index[name], h]], 'failed': False, 'env': env, 'res': {}, 'at': int(time.time()), 'ranNothing': True}
                updated += 1
    save(m)
    print(f'testmap: {updated} test classes updated, {len(m["entries"])} in the map ({MAP})', file=sys.stderr)


def select():
    """{(project, task): [test classes]} that the changes since the map can affect, and the reasons, by rule."""
    m = load()
    current = classes()
    env, res = other_files()
    names = m['classes']
    tests = {}  # (project, task) -> [test class]
    for name, (_, module, source_set, is_test, _) in current.items():
        if is_test:
            tests.setdefault((project_of(module), source_set), []).append(name)
    chosen, why = {}, {}
    for (project, task), classes_ in sorted(tests.items()):
        for test in sorted(classes_):
            e = m['entries'].get(f'{project}:{task}|{test}')
            reason = None
            if e is None:
                reason = 'not in the map'
            elif e['failed']:
                reason = 'failed last time'
            elif e['env'] != env:
                reason = 'a non-Java file changed'
            elif any(res.get(mod, '') != d for mod, d in e['res'].items()):
                reason = 'a resource changed'
            elif e.get('scans') and e['names'] != names_in(current, e['scans']):
                reason = 'a class file was added to or removed from a directory it scans'
            else:
                for i, h in e['loaded']:
                    n = names[i]
                    if n not in current or current[n][0] != h:
                        reason = 'changed: ' + n
                        break
            if reason:
                chosen.setdefault((project, task), []).append(test)
                why[f'{project}:{task} {test}'] = reason
    return chosen, why, tests


def main():
    cmd = sys.argv[1] if len(sys.argv) > 1 else 'select'
    if cmd == 'update':
        since = float(sys.argv[sys.argv.index('--since') + 1]) if '--since' in sys.argv else 0
        update(since, '--full' in sys.argv)
    elif cmd == 'select':
        chosen, why, tests = select()
        args = []
        for (project, task), all_ in sorted(tests.items()):
            picked = chosen.get((project, task), [])
            if not picked:
                args += ['-x', f'{project}:{task}']
            else:
                args.append(f'{project}:{task}')
                if len(picked) < len(all_):
                    for t in picked:
                        args += ['--tests', t]
        total = sum(len(v) for v in tests.values())
        print(f'testmap: {sum(len(v) for v in chosen.values())} of {total} test classes affected', file=sys.stderr)
        for k, v in sorted(why.items())[:int(os.environ.get('SHARD_TESTMAP_WHY', '40'))]:
            print(f'  {k}: {v}', file=sys.stderr)
        print(' '.join(args))
    elif cmd == 'stats':
        m = load()
        print(f'{len(m["entries"])} entries, {len(m["classes"])} classes, '
              f'{sum(e["failed"] for e in m["entries"].values())} failed, map {MAP}')
    else:
        sys.exit(__doc__)


if __name__ == '__main__':
    main()
