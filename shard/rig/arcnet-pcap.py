#!/usr/bin/env python3
"""Checks the Arc (Mindustry) TCP framing in a loopback capture, one connection at a time, and lists its frames.

Each TCP frame is a 2-byte big-endian length and that many bytes. The vanilla client reads frames into a
25,000-byte buffer and fails a longer one with "Unable to read object larger than read buffer" (the "mismatch"
dialog), so a server's stream is well framed when every length is positive and at most 25,000 and the frames
chain to the end of the stream. Packet names come from wire's generated Packets.java.

  tcpdump -Z root -i lo -U -s 0 -w lo.pcap tcp port 6567   # on the VM, beside the server (in --server: no sudo)
  python3 rig/arcnet-pcap.py lo.pcap [--port 6567] [--show N] [--conn CLIENT_PORT]

Prints one line per connection (frames, bytes, bad frames) and, with --show, its first and last N frames each way.
"""
import argparse
import pathlib
import re
import struct
import sys

CLIENT_BUFFER = 25_000
PACKETS = pathlib.Path(__file__).resolve().parent.parent / "wire/src/generated/java/com/xpdustry/shard/wire/v160_7/Packets.java"
FRAMEWORK = {0: "fw:ping", 1: "fw:discoverHost", 2: "fw:keepAlive", 3: "fw:registerUDP", 4: "fw:registerTCP"}


def names():
    out = {}
    if PACKETS.exists():
        for name, value in re.findall(r"public static final int (\w+) = (\d+);", PACKETS.read_text()):
            out.setdefault(int(value), name.lower())
    return out


def packets(path):
    """(time, src port, dst port, seq, flags, payload) for each TCP segment over IPv4."""
    data = pathlib.Path(path).read_bytes()
    magic = struct.unpack("<I", data[:4])[0]
    if magic in (0xA1B2C3D4, 0xA1B23C4D):
        endian = "<"
    elif magic in (0xD4C3B2A1, 0x4D3CB2A1):
        endian = ">"
    else:
        sys.exit(f"{path}: not a pcap file (pcapng isn't read)")
    nanos = magic in (0xA1B23C4D, 0x4D3CB2A1)
    link = struct.unpack(endian + "I", data[20:24])[0]
    skip = {1: 14, 113: 16, 0: 4}.get(link)
    if skip is None:
        sys.exit(f"{path}: link type {link} isn't read")
    pos = 24
    while pos + 16 <= len(data):
        sec, frac, incl, _ = struct.unpack(endian + "IIII", data[pos:pos + 16])
        frame = data[pos + 16:pos + 16 + incl]
        pos += 16 + incl
        ip = frame[skip:]
        if len(ip) < 20 or ip[0] >> 4 != 4 or ip[9] != 6:
            continue
        ihl = (ip[0] & 15) * 4
        total = struct.unpack(">H", ip[2:4])[0]
        tcp = ip[ihl:total]
        sport, dport, seq = struct.unpack(">HHI", tcp[:8])
        offset = (tcp[12] >> 4) * 4
        yield sec + frac / (1e9 if nanos else 1e6), sport, dport, seq, tcp[13], tcp[offset:]


def streams(path, port):
    """{client port: {"down"/"up": (start time, [(time, offset, bytes)])}} from each connection's SYN onwards."""
    conns = {}
    for time, sport, dport, seq, flags, payload in packets(path):
        if sport == port:
            key, way = dport, "down"
        elif dport == port:
            key, way = sport, "up"
        else:
            continue
        conn = conns.setdefault(key, {})
        if flags & 0x02:  # SYN: a new connection on a reused port starts over
            if way == "up":
                conns[key] = conn = {}
            conn[way] = {"isn": seq, "start": time, "segments": {}}
            continue
        side = conn.get(way)
        if side is None or not payload:
            continue
        offset = (seq - side["isn"] - 1) % (1 << 32)
        side["segments"].setdefault(offset, (time, payload))
    return conns


def frames(side):
    """[(time, length, payload)] and the bytes left over at the end, from the reassembled segments."""
    buffer, times, expect = bytearray(), [], 0
    for offset in sorted(side["segments"]):
        time, payload = side["segments"][offset]
        if offset + len(payload) <= expect:
            continue  # a retransmit
        start = max(0, expect - offset)
        times.append((len(buffer), time))
        buffer += payload[start:]
        expect = offset + len(payload)
    out, pos, t = [], 0, 0
    while pos + 2 <= len(buffer):
        while t + 1 < len(times) and times[t + 1][0] <= pos:
            t += 1
        length = struct.unpack(">H", buffer[pos:pos + 2])[0]
        if pos + 2 + length > len(buffer):
            out.append((times[t][1], length, None))
            break
        out.append((times[t][1], length, bytes(buffer[pos + 2:pos + 2 + length])))
        pos += 2 + length
    return out


def label(payload, table):
    if payload is None:
        return "(cut off by the close)"
    if not payload:
        return "(empty)"
    if payload[0] == 0xFE:
        return FRAMEWORK.get(payload[1] if len(payload) > 1 else -1, "fw:?")
    return table.get(payload[0], f"packet {payload[0]}")


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("pcap")
    parser.add_argument("--port", type=int, default=6567)
    parser.add_argument("--show", type=int, default=0, help="list the first and last N frames each way")
    parser.add_argument("--conn", type=int, help="only the connection from this client port")
    args = parser.parse_args()
    table = names()
    conns = streams(args.pcap, args.port)
    bad_total = 0
    for key in sorted(conns, key=lambda k: conns[k].get("up", conns[k].get("down", {"start": 0}))["start"]):
        if args.conn and key != args.conn:
            continue
        conn = conns[key]
        line, shown_lines = [f"conn {key}"], []
        for way in ("down", "up"):
            side = conn.get(way)
            if side is None:
                continue
            list_ = frames(side)
            bad = [(i, f) for i, f in enumerate(list_) if f[2] is not None and not 0 < f[1] <= CLIENT_BUFFER]
            bad_total += len(bad) if way == "down" else 0
            line.append(f"{way}: {len(list_)} frames, {sum(f[1] + 2 for f in list_)} B, max {max((f[1] for f in list_), default=0)}"
                        + (f", {len(bad)} BAD (first #{bad[0][0]}: length {bad[0][1]})" if bad else ""))
            if args.show:
                start = side["start"]
                shown = list_ if len(list_) <= 2 * args.show else list_[:args.show] + [None] + list_[-args.show:]
                for f in shown:
                    shown_lines.append(f"    {way:4} ..." if f is None else f"    {way:4} +{f[0] - start:8.3f}s {f[1]:6} {label(f[2], table)}")
        print("  ".join(line))
        for text in shown_lines:
            print(text)
    print(f"{len(conns)} connections, {bad_total} badly framed server frames")
    return 1 if bad_total else 0


if __name__ == "__main__":
    sys.exit(main())
