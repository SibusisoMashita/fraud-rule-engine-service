"""Local, disposable PostgreSQL wire fault. No SQL, credentials or payloads are logged."""
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
    def __init__(self, upstream):
        self.upstream = upstream
        self.armed = threading.Event()
        self.dropped = threading.Event()
        self.closed = threading.Event()
        self.connections = set()
        self.lock = threading.Lock()
        self.listener = socket.socket()
        self.listener.bind(('0.0.0.0', 0))
        self.listener.listen()
        self.listener.settimeout(0.2)
        self.port = self.listener.getsockname()[1]
        threading.Thread(target=self._accept, daemon=True).start()

    def _accept(self):
        while not self.closed.is_set():
            client = None
            try:
                client, _ = self.listener.accept()
                server = socket.create_connection(self.upstream, timeout=5)
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
                if kind == b'C' and payload == b'COMMIT\0' and self.armed.is_set():
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
