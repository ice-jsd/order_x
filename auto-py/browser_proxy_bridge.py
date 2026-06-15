import base64
import select
import socket
import socketserver
import threading
from dataclasses import dataclass
from typing import Dict, Optional, Tuple

from ticket_runtime import get_logger


_LOGGER_CACHE: Dict[str, object] = {}


def bridge_logger(platform_code: str = "ticket"):
    code = str(platform_code or "").strip()
    name = f"{code}.proxy-bridge" if code else "ticket.proxy-bridge"
    logger = _LOGGER_CACHE.get(name)
    if logger is None:
        logger = get_logger(name)
        _LOGGER_CACHE[name] = logger
    return logger


@dataclass(frozen=True)
class UpstreamProxyConfig:
    host: str
    port: int
    username: str = ""
    password: str = ""

    def authorization_header(self) -> str:
        if not self.username and not self.password:
            return ""
        token = f"{self.username}:{self.password}".encode("utf-8")
        return "Basic " + base64.b64encode(token).decode("ascii")


class _ThreadingTCPServer(socketserver.ThreadingMixIn, socketserver.TCPServer):
    allow_reuse_address = True
    daemon_threads = True


class _ProxyBridgeHandler(socketserver.StreamRequestHandler):
    server: "_ProxyBridgeServer"

    def handle(self) -> None:
        try:
            request_line = self.rfile.readline(65536)
            if not request_line:
                return
            method, target, version = self._parse_request_line(request_line)
            headers = self._read_headers()
            client_prefetched = self._read_prefetched_client_bytes()
            if method.upper() == "CONNECT":
                self._handle_connect(target, version, headers, client_prefetched)
                return
            self._handle_forward(method, target, version, headers)
        except Exception as exc:
            self.server.logger.warning(f"桥接处理异常: {exc}")
            try:
                self.connection.close()
            except Exception:
                pass

    def _parse_request_line(self, request_line: bytes) -> Tuple[str, str, str]:
        text = request_line.decode("iso-8859-1").strip()
        parts = text.split(" ", 2)
        if len(parts) != 3:
            raise ValueError("invalid proxy request line")
        return parts[0], parts[1], parts[2]

    def _read_headers(self) -> bytes:
        chunks = []
        while True:
            line = self.rfile.readline(65536)
            if not line:
                break
            chunks.append(line)
            if line in (b"\r\n", b"\n"):
                break
        return b"".join(chunks)

    def _connect_upstream(self) -> socket.socket:
        upstream = socket.create_connection((self.server.upstream.host, self.server.upstream.port), timeout=30)
        upstream.settimeout(None)
        return upstream

    def _read_prefetched_client_bytes(self) -> bytes:
        try:
            peeked = self.rfile.peek(65536)
        except Exception:
            return b""
        if not peeked:
            return b""
        try:
            return self.rfile.read(len(peeked)) or b""
        except Exception:
            return b""

    def _handle_connect(self, target: str, version: str, headers: bytes, client_prefetched: bytes = b"") -> None:
        upstream = self._connect_upstream()
        try:
            auth = self.server.upstream.authorization_header()
            self.server.logger.info(
                f"CONNECT 上游代理: local=127.0.0.1:{self.server.port}, target={target}, upstream={self.server.upstream.host}:{self.server.upstream.port}"
            )
            connect_lines = [f"CONNECT {target} {version}\r\n".encode("iso-8859-1")]
            connect_lines.append(f"Host: {target}\r\n".encode("iso-8859-1"))
            if auth:
                connect_lines.append(f"Proxy-Authorization: {auth}\r\n".encode("iso-8859-1"))
            connect_lines.append(b"Proxy-Connection: Keep-Alive\r\n")
            connect_lines.append(b"\r\n")
            upstream.sendall(b"".join(connect_lines))

            response_head = self._read_upstream_head(upstream)
            self.wfile.write(response_head)
            self.wfile.flush()
            status_line = response_head.split(b"\r\n", 1)[0]
            self.server.logger.info(
                f"CONNECT 上游响应: local=127.0.0.1:{self.server.port}, target={target}, status={status_line.decode('iso-8859-1', errors='replace') or '-'}"
            )
            if b" 200 " not in status_line:
                return
            if client_prefetched:
                self.server.logger.info(
                    f"CONNECT 转发预读客户端字节: local=127.0.0.1:{self.server.port}, target={target}, bytes={len(client_prefetched)}"
                )
                upstream.sendall(client_prefetched)
            self._tunnel(self.connection, upstream)
        finally:
            try:
                upstream.close()
            except Exception:
                pass

    def _handle_forward(self, method: str, target: str, version: str, headers: bytes) -> None:
        upstream = self._connect_upstream()
        try:
            auth = self.server.upstream.authorization_header()
            header_lines = headers.splitlines()
            normalized_headers = []
            has_auth = False
            for line in header_lines:
                if not line:
                    continue
                lower = line.lower()
                if lower.startswith(b"proxy-authorization:"):
                    has_auth = True
                normalized_headers.append(line)
            forwarded = bytearray()
            forwarded.extend(f"{method} {target} {version}\r\n".encode("iso-8859-1"))
            for line in normalized_headers:
                forwarded.extend(line.rstrip(b"\r\n"))
                forwarded.extend(b"\r\n")
            if auth and not has_auth:
                forwarded.extend(f"Proxy-Authorization: {auth}\r\n".encode("iso-8859-1"))
            forwarded.extend(b"\r\n")
            upstream.sendall(bytes(forwarded))
            self._relay_response(upstream, self.connection)
        finally:
            try:
                upstream.close()
            except Exception:
                pass

    def _read_upstream_head(self, upstream: socket.socket) -> bytes:
        buffer = bytearray()
        while b"\r\n\r\n" not in buffer and b"\n\n" not in buffer:
            chunk = upstream.recv(4096)
            if not chunk:
                break
            buffer.extend(chunk)
            if len(buffer) > 65536:
                break
        return bytes(buffer)

    def _relay_response(self, upstream: socket.socket, downstream: socket.socket) -> None:
        while True:
            chunk = upstream.recv(65536)
            if not chunk:
                break
            downstream.sendall(chunk)

    def _tunnel(self, client: socket.socket, upstream: socket.socket) -> None:
        sockets = [client, upstream]
        while True:
            readable, _, exceptional = select.select(sockets, [], sockets, 60)
            if exceptional:
                break
            if not readable:
                continue
            for sock in readable:
                peer = upstream if sock is client else client
                try:
                    data = sock.recv(65536)
                except OSError:
                    return
                if not data:
                    return
                peer.sendall(data)


class _ProxyBridgeServer:
    def __init__(self, upstream: UpstreamProxyConfig, platform_code: str = "ticket") -> None:
        self.upstream = upstream
        self.platform_code = str(platform_code or "").strip()
        self.logger = bridge_logger(self.platform_code)
        self._server = _ThreadingTCPServer(("127.0.0.1", 0), _ProxyBridgeHandler)
        self._server.upstream = upstream
        self._server.logger = self.logger
        self._server.port = self.port
        self._thread = threading.Thread(target=self._server.serve_forever, name=f"proxy-bridge-{self.port}", daemon=True)

    @property
    def port(self) -> int:
        return int(self._server.server_address[1])

    def start(self) -> None:
        self._thread.start()

    def close(self) -> None:
        self._server.shutdown()
        self._server.server_close()

    def playwright_proxy(self) -> Dict[str, str]:
        return {"server": f"http://127.0.0.1:{self.port}"}


_BRIDGE_LOCK = threading.RLock()
_BRIDGES: Dict[str, _ProxyBridgeServer] = {}


def get_or_create_bridge(proxy: object, platform_code: str = "ticket") -> Optional[_ProxyBridgeServer]:
    if proxy is None:
        return None
    host = str(getattr(proxy, "host", "") or "").strip()
    port = str(getattr(proxy, "port", "") or "").strip()
    if not host or not port:
        return None
    key = str(getattr(proxy, "raw", "") or f"{host}:{port}")
    with _BRIDGE_LOCK:
        existing = _BRIDGES.get(key)
        if existing:
            return existing
        bridge = _ProxyBridgeServer(
            UpstreamProxyConfig(
                host=host,
                port=int(port),
                username=str(getattr(proxy, "username", "") or ""),
                password=str(getattr(proxy, "password", "") or ""),
            ),
            platform_code=platform_code,
        )
        bridge.start()
        _BRIDGES[key] = bridge
        bridge.logger.info(
            f"创建本地代理桥接: local=127.0.0.1:{bridge.port}, upstream={host}:{port}, hasAuth={bool(getattr(proxy, 'username', '') or getattr(proxy, 'password', ''))}"
        )
        return bridge
