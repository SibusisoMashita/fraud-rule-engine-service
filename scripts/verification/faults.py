"""Local, disposable PostgreSQL wire fault. No SQL, credentials or payloads are logged."""
import argparse
import json
from pathlib import Path
import signal
import socket
import struct
import threading


def receive(sock, size):
    parts = bytearray()
    while len(parts) < size:
        chunk = sock.recv(size - len(parts))
        if not chunk:
            raise EOFError()
        parts.extend(chunk)
    return bytes(parts)


class CommitProxy:
    """Drop one server CommandComplete(COMMIT) after PostgreSQL has committed.

    Only used with sslmode=disable in the private test stack. Frontend bytes pass
    through untouched; backend messages use PostgreSQL's type + int32 framing.
    https://www.postgresql.org/docs/17/protocol-message-formats.html
    """
    def __init__(self, upstream, listen=('127.0.0.1', 0), armed=None, dropped=None):
        self.upstream = upstream
        self.armed = armed if armed is not None else threading.Event()
        self.dropped = dropped if dropped is not None else threading.Event()
        self.closed = threading.Event()
        self.connections = set()
        self.lock = threading.Lock()
        self.listener = socket.socket()
        self.listener.bind(listen)
        self.listener.listen()
        self.listener.settimeout(0.2)
        self.port = self.listener.getsockname()[1]
        threading.Thread(target=self._accept, daemon=True).start()

    def _accept(self):
        while not self.closed.is_set():
            client = None
            try:
                client, _ = self.listener.accept()
                # Short PostgreSQL frames must not wait for Nagle/delayed-ACK cycles.
                client.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
                server = socket.create_connection(self.upstream, timeout=5)
                server.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
                server.settimeout(None)
            except socket.timeout:
                if client is not None:
                    client.close()
                continue
            except OSError:
                if client is not None:
                    client.close()
                if self.closed.is_set():
                    return
                continue
            with self.lock:
                if self.closed.is_set():
                    client.close()
                    server.close()
                    return
                self.connections.update((client, server))
            threading.Thread(target=self._frontend, args=(client, server), daemon=True).start()
            threading.Thread(target=self._backend, args=(server, client), daemon=True).start()

    def _dispose(self, *sockets):
        for sock in sockets:
            try:
                sock.shutdown(socket.SHUT_RDWR)
            except OSError:
                pass
            sock.close()
            with self.lock:
                self.connections.discard(sock)

    def _frontend(self, client, server):
        try:
            while chunk := client.recv(65536):
                server.sendall(chunk)
        except OSError:
            pass
        finally:
            self._dispose(client, server)

    def _backend(self, server, client):
        try:
            while True:
                kind = receive(server, 1)
                length = receive(server, 4)
                size = struct.unpack('!I', length)[0]
                if not 4 <= size <= 16 * 1024 * 1024:
                    raise EOFError()
                payload = receive(server, size - 4)
                if kind == b'C' and payload == b'COMMIT\0':
                    with self.lock:
                        if self.armed.is_set():
                            self.armed.clear()
                            self.dropped.set()
                            return
                client.sendall(kind + length + payload)
        except (OSError, EOFError):
            pass
        finally:
            self._dispose(server, client)

    def close(self):
        self.closed.set()
        self.listener.close()
        with self.lock:
            sockets = list(self.connections)
        self._dispose(*sockets)


class FileFlag:
    """Small process-shared flags in the sidecar's private tmpfs; never payloads."""
    def __init__(self, path):
        self.path = Path(path)

    def is_set(self):
        return self.path.exists()

    def set(self):
        self.path.touch()

    def clear(self):
        self.path.unlink(missing_ok=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=('serve', 'ready', 'arm', 'status'))
    parser.add_argument('--upstream', default='db')
    args = parser.parse_args()
    flags = {name: FileFlag('/tmp/commit-fault-' + name) for name in ('ready', 'armed', 'dropped')}
    if args.action == 'serve':
        for flag in flags.values():
            flag.clear()
        proxy = CommitProxy((args.upstream, 5432), listen=('0.0.0.0', 5432),
                            armed=flags['armed'], dropped=flags['dropped'])
        signal.signal(signal.SIGTERM, lambda *_: proxy.closed.set())
        signal.signal(signal.SIGINT, lambda *_: proxy.closed.set())
        flags['ready'].set()
        try:
            proxy.closed.wait()
        finally:
            flags['ready'].clear()
            proxy.close()
    elif args.action == 'ready':
        if not flags['ready'].is_set():
            raise SystemExit(1)
    elif args.action == 'arm':
        if not flags['ready'].is_set() or flags['dropped'].is_set():
            raise SystemExit('Proxy is unavailable or its one-shot fault was already used')
        flags['armed'].set()
    else:
        print(json.dumps({name: flag.is_set() for name, flag in flags.items()}))


if __name__ == '__main__':
    main()
