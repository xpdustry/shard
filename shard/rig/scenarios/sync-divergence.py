#!/usr/bin/env python3
"""The router-field divergence of sync-routers.rig runs: how far the field alpha simulated drifted from the server's.

  python3 rig/scenarios/sync-divergence.py <run dir> [<run dir>...]

Each run has field-1.tsv and field-5.tsv in alpha's and bravo's directories: `sample js` lines of `<ms>\\t<value>`,
the value the 200 routers' items in row order (an item id, -1 for none). Alpha joined before the field was fed and
simulated its fill itself; bravo had just rejoined, so its field is the server's, from the world stream. The field
has no sink, but it never jams completely: its few empty routers keep items moving, so two samples a second apart
differ router by router whatever the sync. Its item mix (how many routers hold each item) moves only as the sources
refill an empty router, so it is the measure: per run and minute, the share of the field's items whose type alpha
and bravo disagree on (half the L1 distance of their item counts, over the larger total), next to the same between
each client's own samples half a second apart (the noise). The router-by-router mismatch is printed too.
"""
import pathlib
import sys


def samples(path):
    lines = [line.split("\t", 1) for line in pathlib.Path(path).read_text().splitlines() if "\t" in line]
    if not lines:
        sys.exit(f"{path}: no samples")
    return [[int(x) for x in v.split(",")] for _, v in lines]


def mix(field):
    counts = {}
    for item in field:
        if item >= 0:
            counts[item] = counts.get(item, 0) + 1
    return counts


def mix_distance(a, b):
    ca, cb = mix(a), mix(b)
    total = max(sum(ca.values()), sum(cb.values()), 1)
    return sum(abs(ca.get(k, 0) - cb.get(k, 0)) for k in set(ca) | set(cb)) / 2 / total


def main():
    print(f"{'run':28} {'min':>3} {'mix diff':>8} {'noise a':>8} {'noise b':>8} {'routers differ':>14} {'full a/b':>9}")
    for run in sys.argv[1:]:
        for minute in (1, 5):
            alpha = samples(pathlib.Path(run) / "alpha" / f"field-{minute}.tsv")
            bravo = samples(pathlib.Path(run) / "bravo" / f"field-{minute}.tsv")
            a, b = alpha[-1], bravo[-1]
            noise_a = max(mix_distance(x, y) for x, y in zip(alpha, alpha[1:])) if len(alpha) > 1 else 0
            noise_b = max(mix_distance(x, y) for x, y in zip(bravo, bravo[1:])) if len(bravo) > 1 else 0
            differ = sum(1 for x, y in zip(a, b) if x != y)
            print(f"{pathlib.Path(run).name:28} {minute:>3} {mix_distance(a, b):>8.3f} {noise_a:>8.3f} {noise_b:>8.3f} "
                  f"{differ:>10}/{len(a)} {sum(x >= 0 for x in a):>4}/{sum(x >= 0 for x in b)}")


if __name__ == "__main__":
    main()
