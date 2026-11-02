#!/usr/bin/env python3
"""Compares what a server sends one client, from two loopback captures (vanilla's and Shard's): the snapshot cadence,
the 800-byte batching and the payload sizes, over a window after the client's first state snapshot (WP25's L5).

  tcpdump -Z root -i lo -U -s 0 -w lo.pcap port 6567     # in --server, beside the server (TCP and UDP)
  python3 rig/scenarios/sync-cadence.py vanilla.pcap shard.pcap [--port 6567] [--window 60] [--json out.json]

Each capture's first TCP connection is the client. Its server-to-client TCP stream is reassembled into Arc frames
(u16 length, then the frame); UDP datagrams to the client's UDP port are frames as they are. A frame is a packet id,
a u16 length, a compression flag and the body (LZ4 when flagged). Prints a table per capture and the checks, and
exits 1 when a check fails:
  - state snapshots every 200 ms (median within a tick, 17 ms), each followed by its entity snapshots within 20 ms;
  - block snapshots every 6 s (median within 100 ms), the same number of blocks and chunks per batch;
  - every snapshot chunk closes once past 800 bytes: no chunk but the last of a batch at 800 bytes or under;
  - each packet type on the same transport;
  - the first state snapshot's fields equal (tps within 2: vanilla's is its measured frame rate), but for the logic
    random (vanilla seeds it from the clock) and the core data, which are printed: they follow what the world holds;
  - median payload sizes of entity and block snapshots within 10%.
"""
import argparse
import json
import pathlib
import re
import statistics
import struct
import sys

PACKETS = pathlib.Path(__file__).resolve().parent.parent.parent / "wire/src/generated/java/com/xpdustry/shard/wire/v160_7/Packets.java"


def names():
    out = {}
    for name, value in re.findall(r"public static final int (\w+) = (\d+);", PACKETS.read_text()):
        out.setdefault(int(value), name)
    return out


NAMES = names()
ID = {name: value for value, name in NAMES.items()}
STATE, ENTITY, BLOCK, HEALTH = ID["STATE_SNAPSHOT"], ID["ENTITY_SNAPSHOT"], ID["BLOCK_SNAPSHOT"], ID["BUILD_HEALTH_UPDATE"]


def records(path):
    """(time, proto, sport, dport, seq, flags, payload) for each IPv4 TCP segment or UDP datagram."""
    data = pathlib.Path(path).read_bytes()
    magic = struct.unpack("<I", data[:4])[0]
    endian = "<" if magic in (0xA1B2C3D4, 0xA1B23C4D) else ">"
    nanos = magic in (0xA1B23C4D, 0x4D3CB2A1)
    link = struct.unpack(endian + "I", data[20:24])[0]
    skip = {1: 14, 113: 16, 0: 4, 276: 20}[link]
    pos = 24
    while pos + 16 <= len(data):
        sec, frac, incl, _ = struct.unpack(endian + "IIII", data[pos:pos + 16])
        frame = data[pos + 16:pos + 16 + incl]
        pos += 16 + incl
        ip = frame[skip:]
        if len(ip) < 20 or ip[0] >> 4 != 4 or ip[9] not in (6, 17):
            continue
        ihl = (ip[0] & 15) * 4
        total = struct.unpack(">H", ip[2:4])[0]
        body = ip[ihl:total]
        time = sec + frac / (1e9 if nanos else 1e6)
        if ip[9] == 6:
            sport, dport, seq = struct.unpack(">HHI", body[:8])
            yield time, "tcp", sport, dport, seq, body[13], body[(body[12] >> 4) * 4:]
        else:
            sport, dport = struct.unpack(">HH", body[:4])
            yield time, "udp", sport, dport, 0, 0, body[8:]


def lz4(src, size):
    out, i = bytearray(), 0
    while i < len(src):
        token = src[i]
        i += 1
        literals = token >> 4
        if literals == 15:
            while True:
                b = src[i]
                i += 1
                literals += b
                if b != 255:
                    break
        out += src[i:i + literals]
        i += literals
        if i >= len(src):
            break
        offset = src[i] | src[i + 1] << 8
        i += 2
        match = token & 15
        if match == 15:
            while True:
                b = src[i]
                i += 1
                match += b
                if b != 255:
                    break
        for _ in range(match + 4):
            out.append(out[-offset])
    return bytes(out[:size])


def packet(time, proto, frame):
    """(time, proto, id, payload) of a serialized frame, or None for a framework one."""
    if not frame or frame[0] == 0xFE or len(frame) < 4:
        return None
    length = struct.unpack(">H", frame[1:3])[0]
    payload = lz4(frame[4:], length) if frame[3] == 1 else frame[4:4 + length]
    return time, proto, frame[0], payload


def capture(path, port):
    """What the server sent the first client, in arrival order."""
    tcp, udp_port, out = None, None, []
    segments, isn = {}, None
    for time, proto, sport, dport, seq, flags, payload in records(path):
        if proto == "tcp":
            if dport == port and flags & 0x02 and tcp is None:
                tcp = sport
            if sport == port and dport == tcp:
                if flags & 0x02:
                    isn = seq
                elif payload and isn is not None:
                    segments.setdefault((seq - isn - 1) % (1 << 32), (time, payload))
        elif sport == port and payload and payload[0] != 0xFE:
            if udp_port is None:
                udp_port = dport
            if dport == udp_port:
                p = packet(time, "udp", payload)
                if p:
                    out.append(p)
    buffer, marks, expect = bytearray(), [], 0
    for offset in sorted(segments):
        time, payload = segments[offset]
        if offset + len(payload) <= expect:
            continue
        marks.append((len(buffer), time))
        buffer += payload[max(0, expect - offset):]
        expect = offset + len(payload)
    pos, m = 0, 0
    while pos + 2 <= len(buffer):
        while m + 1 < len(marks) and marks[m + 1][0] <= pos:
            m += 1
        length = struct.unpack(">H", buffer[pos:pos + 2])[0]
        if pos + 2 + length > len(buffer):
            break
        p = packet(marks[m][1], "tcp", bytes(buffer[pos + 2:pos + 2 + length]))
        if p:
            out.append(p)
        pos += 2 + length
    out.sort(key=lambda p: p[0])
    return out


def batches(packets, kind):
    """Runs of `kind` packets with nothing else between them: one batch each."""
    out, current = [], None
    for time, proto, pid, payload in packets:
        if pid == kind:
            if current is None:
                current = (time, [])
                out.append(current)
            current[1].append(payload)
        else:
            current = None
    return out


def state(payload):
    """A state snapshot's fields (NetClient.stateSnapshot)."""
    wavetime, wave, enemies = struct.unpack(">fii", payload[:12])
    rand0, rand1 = struct.unpack(">qq", payload[19:35])
    length = struct.unpack(">H", payload[35:37])[0]
    return {"wavetime": wavetime, "wave": wave, "enemies": enemies, "paused": payload[12], "gameOver": payload[13],
            "timeData": struct.unpack(">i", payload[14:18])[0], "tps": payload[18], "rand": [rand0, rand1],
            "cores": payload[37:37 + length].hex()}


def ms(values):
    return {"median": round(statistics.median(values) * 1000, 1), "p5": round(sorted(values)[len(values) // 20] * 1000, 1),
            "p95": round(sorted(values)[len(values) * 19 // 20] * 1000, 1)} if values else None


def analyse(path, port, window):
    packets = capture(path, port)
    start = next((p[0] for p in packets if p[2] == STATE), None)
    if start is None:
        sys.exit(f"{path}: no state snapshot")
    packets = [p for p in packets if start <= p[0] < start + window]
    report = {"capture": str(path), "window_s": window, "types": {}}
    for time, proto, pid, payload in packets:
        t = report["types"].setdefault(NAMES.get(pid, str(pid)), {"count": 0, "transport": set(), "sizes": []})
        t["count"] += 1
        t["transport"].add(proto)
        t["sizes"].append(len(payload))
    for t in report["types"].values():
        t["transport"] = sorted(t["transport"])
        t["median_size"] = statistics.median(t["sizes"])
        t["max_size"] = max(t["sizes"])
        del t["sizes"]
    states = [p[0] for p in packets if p[2] == STATE]
    report["state_first"] = state(next(p[3] for p in packets if p[2] == STATE))
    report["state_interval_ms"] = ms([b - a for a, b in zip(states, states[1:])])
    # a snapshot's payload: i16 amount, then its data as u16 length and bytes
    for name, kind in (("entity", ENTITY), ("block", BLOCK)):
        runs = batches(packets, kind)
        report[name] = {
            "batches": len(runs),
            "interval_ms": ms([b[0] - a[0] for a, b in zip(runs, runs[1:])]),
            "chunks": sorted({len(r[1]) for r in runs}),
            "amounts": sorted({sum(struct.unpack(">h", c[:2])[0] for c in r[1]) for r in runs}),
            "data_sizes": sorted({len(c) - 4 for r in runs for c in r[1]}),
            # every chunk but a batch's last closed once past 800 bytes of entries
            "early_closes": sum(1 for r in runs for c in r[1][:-1] if len(c) - 4 <= 800),
        }
    # entity snapshots follow their state snapshot, within 20 ms (a TCP Call may land between them)
    entities = [p[0] for p in packets if p[2] == ENTITY]
    following = sum(1 for t in states if any(t <= e <= t + 0.02 for e in entities))
    report["states_followed_by_entities"] = following
    return report


def checks(vanilla, shard):
    out = []

    def check(name, ok, detail):
        out.append((name, ok, detail))

    for r, who in ((vanilla, "vanilla"), (shard, "shard")):
        si = r["state_interval_ms"]
        check(f"{who}: state snapshots every 200 ms", si and abs(si["median"] - 200) <= 17, si)
        states = r["types"]["STATE_SNAPSHOT"]["count"]
        # vanilla sends none while nothing is synced, as before the client's player is added
        check(f"{who}: an entity snapshot after each state snapshot", r["states_followed_by_entities"] >= states - 5,
              f"{r['states_followed_by_entities']} of {states}")
        bi = r["block"]["interval_ms"]
        check(f"{who}: block snapshots every 6 s", bi and abs(bi["median"] - 6000) <= 100, bi)
        check(f"{who}: chunks close only past 800 bytes", r["block"]["early_closes"] == 0 and r["entity"]["early_closes"] == 0,
              f"block {r['block']['early_closes']}, entity {r['entity']['early_closes']}")
    check("same blocks per block snapshot batch", vanilla["block"]["amounts"] == shard["block"]["amounts"],
          f"vanilla {vanilla['block']['amounts']}, shard {shard['block']['amounts']}")
    check("same chunks per block snapshot batch", vanilla["block"]["chunks"] == shard["block"]["chunks"],
          f"vanilla {vanilla['block']['chunks']}, shard {shard['block']['chunks']}")
    # tps is vanilla's measured frame rate: within 2
    fields = ("wavetime", "wave", "enemies", "paused", "gameOver", "timeData")
    v, s = vanilla["state_first"], shard["state_first"]
    differ = {f: (v[f], s[f]) for f in fields if v[f] != s[f]}
    if abs(v["tps"] - s["tps"]) > 2:
        differ["tps"] = (v["tps"], s["tps"])
    check("the first state snapshot's fields", not differ, differ or "equal")
    for name in ("STATE_SNAPSHOT", "ENTITY_SNAPSHOT", "BLOCK_SNAPSHOT"):
        v, s = vanilla["types"].get(name), shard["types"].get(name)
        if not v or not s:
            check(f"{name} sent by both", False, f"vanilla {bool(v)}, shard {bool(s)}")
            continue
        check(f"{name} on the same transport", v["transport"] == s["transport"], f"vanilla {v['transport']}, shard {s['transport']}")
        if name == "STATE_SNAPSHOT":
            continue
        ratio = s["median_size"] / v["median_size"]
        check(f"{name} median payload within 10%", abs(ratio - 1) <= 0.10,
              f"vanilla {v['median_size']} B, shard {s['median_size']} B ({ratio:.3f})")
    return out


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("vanilla")
    parser.add_argument("shard")
    parser.add_argument("--port", type=int, default=6567)
    parser.add_argument("--window", type=float, default=60)
    parser.add_argument("--json")
    args = parser.parse_args()
    vanilla, shard = analyse(args.vanilla, args.port, args.window), analyse(args.shard, args.port, args.window)
    names_ = sorted(set(vanilla["types"]) | set(shard["types"]))
    print(f"{'packet':28} {'vanilla n':>10} {'shard n':>8} {'vanilla B':>10} {'shard B':>8}  transport")
    for name in names_:
        v, s = vanilla["types"].get(name, {}), shard["types"].get(name, {})
        print(f"{name:28} {v.get('count', 0):>10} {s.get('count', 0):>8} {v.get('median_size', '-'):>10} {s.get('median_size', '-'):>8}  "
              f"{','.join(v.get('transport', []))} / {','.join(s.get('transport', []))}")
    for key in ("state_interval_ms", "state_first", "entity", "block"):
        print(f"{key}:\n  vanilla {vanilla[key]}\n  shard   {shard[key]}")
    failed = 0
    for name, ok, detail in checks(vanilla, shard):
        print(f"{'PASS' if ok else 'FAIL'}  {name}: {detail}")
        failed += not ok
    if args.json:
        pathlib.Path(args.json).write_text(json.dumps({"vanilla": vanilla, "shard": shard,
                                                       "checks": [{"name": n, "ok": bool(o), "detail": str(d)} for n, o, d in checks(vanilla, shard)]}, indent=2))
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
