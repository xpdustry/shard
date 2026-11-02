#!/usr/bin/env python3
"""A Gradle HTTP build cache: GET/HEAD/PUT of /cache/<key>, entries as files, least-recently-used eviction.

    server.py --host 127.0.0.1 --port 8123 [--dir ~/.cache/shard/gradle-cache/entries] [--max-gb 15]

Stdlib only. Run it through run.sh, which shares it with sloppa; sloppa asks for the password, so this server has
no authentication of its own and refuses to bind anywhere but loopback. Entries are written to a temp file and
renamed, so a reader never sees a half-written one. A hit touches the entry's mtime; when the total passes the cap,
the oldest-touched entries go first. Nothing about a request except its method, key and size is logged (no headers).
"""
import argparse, ipaddress, os, re, shutil, sys, threading, time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

KEY = re.compile(r'^[0-9a-zA-Z]{8,128}$')
MIN_FREE = 1 << 30  # refuse stores when the disk has less than this left


class Store:
    def __init__(self, root, cap):
        self.root, self.cap = root, cap
        self.tmp = os.path.join(root, '.tmp')
        self.lock = threading.Lock()
        os.makedirs(self.root, exist_ok=True)
        shutil.rmtree(self.tmp, ignore_errors=True)  # half-written uploads of a previous run
        os.makedirs(self.tmp)
        self.sizes = {}
        for e in os.scandir(root):
            if e.is_file() and KEY.match(e.name):
                self.sizes[e.name] = e.stat().st_size
        self.total = sum(self.sizes.values())
        self.hits = self.misses = self.stores = self.evicted = 0

    def path(self, key):
        return os.path.join(self.root, key)

    def open(self, key):
        try:
            f = open(self.path(key), 'rb')
        except FileNotFoundError:
            with self.lock:
                self.misses += 1
            return None
        try:
            os.utime(f.fileno())  # LRU: a read refreshes the entry
        except OSError:
            pass
        with self.lock:
            self.hits += 1
        return f

    def room_for(self, size):
        return size <= self.cap // 4 and shutil.disk_usage(self.root).free - size > MIN_FREE

    def commit(self, key, tmp_path, size):
        with self.lock:
            os.replace(tmp_path, self.path(key))  # atomic: readers see the old or the new entry
            self.total += size - self.sizes.get(key, 0)
            self.sizes[key] = size
            self.stores += 1
            if self.total > self.cap:
                self.evict(keep=key)

    def evict(self, keep):
        entries = []
        for key in self.sizes:
            try:
                entries.append((os.stat(self.path(key)).st_mtime, key))
            except FileNotFoundError:
                pass
        entries.sort()
        for _, key in entries:
            if self.total <= self.cap * 0.9:  # evict to 90%, so the next store doesn't evict again
                break
            if key == keep:
                continue
            try:
                os.unlink(self.path(key))
            except FileNotFoundError:
                pass
            self.total -= self.sizes.pop(key)
            self.evicted += 1


def make_handler(store):
    class Handler(BaseHTTPRequestHandler):
        protocol_version = 'HTTP/1.1'  # keep-alive, and Expect: 100-continue

        def log_message(self, fmt, *args):
            pass

        def log_line(self, status, size=0):
            print(f'{time.strftime("%H:%M:%S")} {self.command} {self.key or "-"} {status} {size}', flush=True)

        def parse(self):
            self.key = None
            path = self.path.split('?', 1)[0]
            if path in ('/', '/stats'):
                body = (f'entries {len(store.sizes)} bytes {store.total} cap {store.cap} hits {store.hits} '
                        f'misses {store.misses} stores {store.stores} evicted {store.evicted}\n').encode()
                self.reply(200, body)
                return False
            m = re.fullmatch(r'/cache/([^/]+)', path)
            if not m or not KEY.match(m.group(1)):
                self.reply(400)
                return False
            self.key = m.group(1)
            return True

        def reply(self, status, body=b'', length=None):
            self.send_response(status)
            self.send_header('Content-Length', str(len(body) if length is None else length))
            self.send_header('Content-Type', 'application/octet-stream')
            self.end_headers()
            if body and self.command != 'HEAD':
                self.wfile.write(body)

        def do_GET(self):
            if not self.parse():
                return
            f = store.open(self.key)
            if f is None:
                self.log_line(404)
                return self.reply(404)
            with f:
                size = os.fstat(f.fileno()).st_size
                self.send_response(200)
                self.send_header('Content-Length', str(size))
                self.send_header('Content-Type', 'application/octet-stream')
                self.end_headers()
                shutil.copyfileobj(f, self.wfile, 1 << 20)
            self.log_line(200, size)

        def do_HEAD(self):
            if not self.parse():
                return
            try:
                size = os.stat(store.path(self.key)).st_size
            except FileNotFoundError:
                return self.reply(404)
            self.reply(200, length=size)

        def do_PUT(self):
            if not self.parse():
                return
            chunked = 'chunked' in self.headers.get('Transfer-Encoding', '').lower()
            declared = int(self.headers.get('Content-Length') or -1)
            if not chunked and declared < 0:
                return self.reply(411)
            if declared >= 0 and not store.room_for(declared):
                self.log_line(413, declared)
                self.close_connection = True  # the body is unread
                return self.reply(413)
            tmp_path = os.path.join(store.tmp, f'{self.key}.{threading.get_ident()}')
            size = 0
            try:
                with open(tmp_path, 'wb') as out:
                    if chunked:
                        while True:
                            n = int(self.rfile.readline().split(b';')[0].strip() or b'0', 16)
                            if n == 0:
                                while self.rfile.readline().strip():  # trailers
                                    pass
                                break
                            size += n
                            if size > store.cap // 4:
                                raise ValueError('too large')
                            out.write(self.rfile.read(n))
                            self.rfile.readline()
                    else:
                        left = declared
                        while left:
                            block = self.rfile.read(min(left, 1 << 20))
                            if not block:
                                raise ConnectionError('short body')
                            out.write(block)
                            left -= len(block)
                        size = declared
                store.commit(self.key, tmp_path, size)
            except (ValueError, ConnectionError, OSError):
                self.close_connection = True
                try:
                    os.unlink(tmp_path)
                except FileNotFoundError:
                    pass
                self.log_line(500, size)
                return
            self.log_line(201, size)
            self.reply(201)

        def do_DELETE(self):
            self.reply(405)

    return Handler


def main():
    p = argparse.ArgumentParser()
    p.add_argument('--host', default='127.0.0.1')
    p.add_argument('--port', type=int, required=True)
    p.add_argument('--dir', default=os.path.expanduser('~/.cache/shard/gradle-cache/entries'))
    p.add_argument('--max-gb', type=float, default=15.0)
    a = p.parse_args()
    if not ipaddress.ip_address(a.host).is_loopback:
        sys.exit(f'gradle-cache: refusing to bind {a.host}: loopback only (this is a shared machine)')
    store = Store(a.dir, int(a.max_gb * (1 << 30)))
    class Server(ThreadingHTTPServer):
        daemon_threads = True
        request_queue_size = 128  # many VMs connect at once

    server = Server((a.host, a.port), make_handler(store))
    print(f'gradle-cache: {a.host}:{a.port} dir {a.dir} entries {len(store.sizes)} bytes {store.total} '
          f'cap {store.cap}', flush=True)
    server.serve_forever()


if __name__ == '__main__':
    main()
