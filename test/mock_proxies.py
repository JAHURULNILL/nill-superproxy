#!/usr/bin/env python3
"""Mock echo target + SOCKS5 (user/pass) + HTTP CONNECT (Basic) proxies for JVM engine tests."""
import socket, threading, base64, sys

def echo_server():
    srv = socket.socket(); srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind(("127.0.0.1", 7777)); srv.listen(50)
    while True:
        c, _ = srv.accept()
        threading.Thread(target=echo_loop, args=(c,), daemon=True).start()

def echo_loop(c):
    try:
        while True:
            d = c.recv(4096)
            if not d: break
            c.sendall(d)
    except OSError: pass
    finally:
        try: c.close()
        except OSError: pass

def pipe(a, b):
    try:
        while True:
            d = a.recv(4096)
            if not d: break
            b.sendall(d)
    except OSError: pass
    finally:
        try: b.shutdown(socket.SHUT_WR)
        except OSError: pass

def relay(client, remote):
    threading.Thread(target=pipe, args=(client, remote), daemon=True).start()
    threading.Thread(target=pipe, args=(remote, client), daemon=True).start()

def readn(c, n):
    buf = b""
    while len(buf) < n:
        d = c.recv(n - len(buf))
        if not d: raise ConnectionError("eof")
        buf += d
    return buf

def socks_server():
    srv = socket.socket(); srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind(("127.0.0.1", 1080)); srv.listen(50)
    while True:
        c, _ = srv.accept()
        threading.Thread(target=socks_loop, args=(c,), daemon=True).start()

def socks_loop(c):
    try:
        ver, n = readn(c, 2)
        methods = readn(c, n)
        assert ver == 5 and 0x02 in methods
        c.sendall(b"\x05\x02")
        aver, ulen = readn(c, 2)
        user = readn(c, ulen)
        (plen,) = readn(c, 1)
        pw = readn(c, plen)
        if user != b"nill" or pw != b"secret":
            c.sendall(b"\x01\x01"); c.close(); return
        c.sendall(b"\x01\x00")
        ver, cmd, rsv, atyp = readn(c, 4)
        assert cmd == 1
        if atyp == 1:
            host = socket.inet_ntoa(readn(c, 4))
        elif atyp == 3:
            (l,) = readn(c, 1); host = readn(c, l).decode()
        else:
            raise ValueError("atyp")
        port = int.from_bytes(readn(c, 2), "big")
        remote = socket.create_connection((host, port))
        c.sendall(b"\x05\x00\x00\x01\x00\x00\x00\x00\x00\x00")
        relay(c, remote)
    except Exception as e:
        print("socks err", e, flush=True)
        try: c.close()
        except OSError: pass

def http_server():
    srv = socket.socket(); srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind(("127.0.0.1", 8080)); srv.listen(50)
    while True:
        c, _ = srv.accept()
        threading.Thread(target=http_loop, args=(c,), daemon=True).start()

def http_loop(c):
    try:
        data = b""
        while b"\r\n\r\n" not in data:
            d = c.recv(4096)
            if not d: raise ConnectionError("eof")
            data += d
        head, rest = data.split(b"\r\n\r\n", 1)
        lines = head.decode("iso-8859-1").split("\r\n")
        method, target, _ = lines[0].split(" ", 2)
        assert method == "CONNECT"
        auth_ok = False
        for ln in lines[1:]:
            if ln.lower().startswith("proxy-authorization: basic "):
                cred = base64.b64decode(ln.split(" ", 2)[2]).decode()
                auth_ok = cred == "nill:secret"
        if not auth_ok:
            c.sendall(b"HTTP/1.1 407 Proxy Authentication Required\r\n\r\n"); c.close(); return
        host, port = target.rsplit(":", 1)
        remote = socket.create_connection((host, int(port)))
        c.sendall(b"HTTP/1.1 200 Connection Established\r\n\r\n")
        if rest: remote.sendall(rest)
        relay(c, remote)
    except Exception as e:
        print("http err", e, flush=True)
        try: c.close()
        except OSError: pass

if __name__ == "__main__":
    threading.Thread(target=echo_server, daemon=True).start()
    threading.Thread(target=socks_server, daemon=True).start()
    threading.Thread(target=http_server, daemon=True).start()
    print("mock proxies ready", flush=True)
    threading.Event().wait()
