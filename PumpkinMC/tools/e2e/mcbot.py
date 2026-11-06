"""Minimal offline-mode Minecraft client for end-to-end tests (from the Friends repository's tools/e2e, plus chat
lines for the party chat lock): logs in, answers keep-alives, sends chat
(commands) and records the chat it receives. Classic protocols: 1.8 (47), 1.12.2 (340), 1.16.5 (754); modern
(configuration phase, NBT chat): 1.20.6 (766), 1.21.11 (774), 26.1 (775) and 26.3 (777). Proxies accept these too.
Packet ids from PrismarineJS minecraft-data, and minecraft.wiki for 26.3 (not in minecraft-data yet)."""
import hashlib
import json
import socket
import struct
import threading
import time
import uuid
import zlib

PROTOCOLS = {
    47: dict(ka_in=0x00, ka_out=0x00, ka_long=False, chat_in=0x02, chat_out=0x01, kick=0x40),
    340: dict(ka_in=0x1F, ka_out=0x0B, ka_long=True, chat_in=0x0F, chat_out=0x02, kick=0x1A),
    754: dict(ka_in=0x1F, ka_out=0x10, ka_long=True, chat_in=0x0E, chat_out=0x03, kick=0x19),
}
_CONFIG = dict(cfg_ka=0x04, cfg_ping=0x05, cfg_finish=0x03, cfg_packs_in=0x0E, cfg_packs_out=0x07, cfg_kick=0x02)
MODERN = {
    766: dict(_CONFIG, coc=None, coc_accept=None, ka_in=0x26, ka_out=0x18, chat_in=0x6C, cmd_out=0x04, kick=0x1D,
              ping=0x35, pong=0x27, reconfig=0x69, reconfig_ack=0x0C, loaded=None),
    774: dict(_CONFIG, coc=0x13, coc_accept=0x09, ka_in=0x2B, ka_out=0x1B, chat_in=0x77, cmd_out=0x06, kick=0x20,
              ping=0x3B, pong=0x2C, reconfig=0x74, reconfig_ack=0x0F, loaded=0x2B),
    775: dict(_CONFIG, coc=0x13, coc_accept=0x09, ka_in=0x2C, ka_out=0x1C, chat_in=0x79, cmd_out=0x07, kick=0x20,
              ping=0x3D, pong=0x2D, reconfig=0x76, reconfig_ack=0x10, loaded=0x2C),
}
# 26.3 isn't in minecraft-data yet: ids from minecraft.wiki's "Java Edition protocol" (documents 26.3, protocol 777).
MODERN[777] = dict(_CONFIG, cfg_packs_in=0x0F, coc=0x14, coc_accept=0x09, ka_in=0x2D, ka_out=0x1C, chat_in=0x7C,
                   cmd_out=0x07, kick=0x20, ping=0x3E, pong=0x2D, reconfig=0x78, reconfig_ack=0x10, loaded=0x2C,
                   # Tab completion (request / response), from Pumpkin's generated 26.3 packet ids.
                   suggest_out=0x0F, suggest_in=0x0F,
                   # Serverbound chat message (unsigned), right after the two chat command packets.
                   chat_out=0x09)


def read_nbt(buf, i):
    """Network NBT (1.20.2+: nameless root) -> Python value."""
    tag = buf[i]
    return (None, i + 1) if tag == 0 else _nbt(buf, i + 1, tag)


def _nbt(buf, i, tag):
    fixed = {1: ">b", 2: ">h", 3: ">i", 4: ">q", 5: ">f", 6: ">d"}
    if tag in fixed:
        size = struct.calcsize(fixed[tag])
        return struct.unpack(fixed[tag], buf[i:i + size])[0], i + size
    if tag in (7, 11, 12):
        n = struct.unpack(">i", buf[i:i + 4])[0]
        width = {7: 1, 11: 4, 12: 8}[tag]
        return list(buf[i + 4:i + 4 + n * width]), i + 4 + n * width
    if tag == 8:
        n = struct.unpack(">H", buf[i:i + 2])[0]
        return buf[i + 2:i + 2 + n].decode("utf-8", "replace"), i + 2 + n
    if tag == 9:
        inner, n = buf[i], struct.unpack(">i", buf[i + 1:i + 5])[0]
        i, out = i + 5, []
        for _ in range(n):
            value, i = _nbt(buf, i, inner)
            out.append(value)
        return out, i
    if tag == 10:
        out = {}
        while buf[i] != 0:
            inner = buf[i]
            name, i = _nbt(buf, i + 1, 8)
            out[name], i = _nbt(buf, i, inner)
        return out, i + 1
    raise ValueError(f"bad NBT tag {tag}")


def offline_uuid(name):
    h = bytearray(hashlib.md5(("OfflinePlayer:" + name).encode()).digest())
    h[6] = (h[6] & 0x0F) | 0x30
    h[8] = (h[8] & 0x3F) | 0x80
    return uuid.UUID(bytes=bytes(h))


def varint(n):
    n &= 0xFFFFFFFF
    out = b""
    while True:
        b = n & 0x7F
        n >>= 7
        out += bytes([b | (0x80 if n else 0)])
        if not n:
            return out


def read_varint(buf, i):
    n = shift = 0
    while True:
        b = buf[i]
        i += 1
        n |= (b & 0x7F) << shift
        shift += 7
        if not b & 0x80:
            return n, i


def string(s):
    b = s.encode()
    return varint(len(b)) + b


def read_string(buf, i):
    n, i = read_varint(buf, i)
    return buf[i:i + n].decode(), i + n


def plain(component):
    """Flatten chat JSON to text (text + extra, recursively)."""
    if isinstance(component, str):
        return component
    if isinstance(component, list):
        return "".join(plain(c) for c in component)
    if "" in component:  # NBT wraps elements of mixed-type lists as {"": value}
        return plain(component[""])
    text = component.get("text", "")
    return text + "".join(plain(c) for c in component.get("extra", []))


class Bot:
    def __init__(self, name, port, protocol=47, host="127.0.0.1"):
        self.name, self.port, self.protocol, self.host = name, port, protocol, host
        self.modern = protocol in MODERN
        self.ids = MODERN[protocol] if self.modern else PROTOCOLS[protocol]
        self.messages = []  # (plain text, raw json)
        self.suggestions = {}  # tab-completion request id -> (start, length, [values])
        self.config_packets = []  # (id, size) seen while configuring, for debugging
        self.kicked = None
        self.threshold = -1
        self.lock = threading.Lock()
        self.sock = None

    # --- framing ---
    def _read_exact(self, n):
        data = b""
        while len(data) < n:
            chunk = self.sock.recv(n - len(data))
            if not chunk:
                raise EOFError("connection closed")
            data += chunk
        return data

    def _recv(self):
        n = shift = 0
        while True:
            b = self._read_exact(1)[0]
            n |= (b & 0x7F) << shift
            shift += 7
            if not b & 0x80:
                break
        data = self._read_exact(n)
        if self.threshold >= 0:
            size, i = read_varint(data, 0)
            data = zlib.decompress(data[i:]) if size else data[i:]
        pid, i = read_varint(data, 0)
        return pid, data[i:]

    def _send(self, pid, payload=b""):
        body = varint(pid) + payload
        if self.threshold >= 0:
            body = varint(0) + body  # our packets are tiny: always below the threshold
        with self.lock:
            self.sock.sendall(varint(len(body)) + body)

    # --- lifecycle ---
    def connect(self, timeout=15):
        self.messages.clear()
        self.kicked = None
        self.threshold = -1
        self.sock = socket.create_connection((self.host, self.port), timeout=timeout)
        self._send(0x00, varint(self.protocol) + string(self.host) + struct.pack(">H", self.port) + varint(2))
        self._send(0x00, string(self.name) + (offline_uuid(self.name).bytes if self.modern else b""))
        while True:
            pid, data = self._recv()
            if pid == 0x03:
                self.threshold, _ = read_varint(data, 0)
            elif pid == 0x02:
                break
            elif pid == 0x04:  # login plugin request (1.13+): "not understood"
                mid, _ = read_varint(data, 0)
                self._send(0x02, varint(mid) + b"\x00")
            elif pid == 0x00:
                raise RuntimeError(f"{self.name}: login refused: {read_string(data, 0)[0]}")
            else:
                raise RuntimeError(f"{self.name}: unexpected login packet {pid:#x} (online mode?)")
        if self.modern:
            self._send(0x03)  # login acknowledged -> configuration
            self._configure()
            if self.ids["loaded"] is not None:
                self._send(self.ids["loaded"])  # 1.21.4+: "player loaded"
        self.sock.settimeout(None)
        threading.Thread(target=self._loop, daemon=True).start()
        return self

    def _configure(self):
        """Configuration phase (1.20.2+): answer until the server finishes it."""
        ids = self.ids
        deadline = time.time() + 20
        while True:
            if time.time() > deadline:
                raise RuntimeError(f"{self.name}: configuration never finished (protocol {self.protocol} ids wrong?)")
            pid, data = self._recv()
            self.config_packets.append((pid, len(data)))
            if pid == ids["cfg_packs_in"]:
                self._send(ids["cfg_packs_out"], varint(0))  # know no packs: the server sends everything
            elif pid in (ids["cfg_ka"], ids["cfg_ping"]):
                self._send(pid, data)  # keep-alive / ping: echo (same ids both ways)
            elif ids["coc"] is not None and pid == ids["coc"]:
                self._send(ids["coc_accept"])
            elif pid == ids["cfg_finish"]:
                self._send(ids["cfg_finish"])
                return
            elif pid == ids["cfg_kick"]:
                raise RuntimeError(f"{self.name}: kicked while configuring: {plain(read_nbt(data, 0)[0])}")

    def _chat(self, raw_text):
        try:
            text = plain(json.loads(raw_text))
        except ValueError:
            text = raw_text
        self.messages.append((text, raw_text))

    def _loop(self):
        try:
            while True:
                pid, data = self._recv()
                if self.modern:
                    if pid == self.ids["ka_in"]:
                        self._send(self.ids["ka_out"], data[:8])
                    elif pid == self.ids["ping"]:
                        self._send(self.ids["pong"], data[:4])
                    elif pid == self.ids["chat_in"]:
                        component, _ = read_nbt(data, 0)
                        self.messages.append((plain(component), json.dumps(component)))
                    elif pid == self.ids["reconfig"]:  # e.g. a proxy switching servers
                        self._send(self.ids["reconfig_ack"])
                        self._configure()
                    elif pid == self.ids["kick"]:
                        self.kicked = plain(read_nbt(data, 0)[0])
                        return
                    elif pid == self.ids.get("suggest_in"):
                        self._suggestions(data)
                    continue
                if pid == self.ids["ka_in"]:
                    self._send(self.ids["ka_out"], data[:8] if self.ids["ka_long"] else varint(read_varint(data, 0)[0]))
                elif pid == self.ids["chat_in"]:
                    self._chat(read_string(data, 0)[0])
                elif pid == self.ids["kick"]:
                    self.kicked = read_string(data, 0)[0]
                    return
        except (EOFError, OSError):
            if self.kicked is None:
                self.kicked = "connection closed"

    def _suggestions(self, data):
        rid, i = read_varint(data, 0)
        start, i = read_varint(data, i)
        length, i = read_varint(data, i)
        count, i = read_varint(data, i)
        values = []
        for _ in range(count):
            value, i = read_string(data, i)
            has_tooltip = data[i]
            i += 1
            if has_tooltip:
                _, i = read_nbt(data, i)
            values.append(value)
        self.suggestions[rid] = (start, length, values)

    def complete(self, text, timeout=5):
        """Tab completion of `text` (e.g. "/f add B"): (start, length, values). Protocols with suggest ids only."""
        rid = len(self.suggestions) + 1000 + int(time.time()) % 1000
        self._send(self.ids["suggest_out"], varint(rid) + string(text))
        deadline = time.time() + timeout
        while time.time() < deadline:
            if rid in self.suggestions:
                return self.suggestions[rid]
            time.sleep(0.05)
        raise AssertionError(f"{self.name}: no completion for {text!r}")

    def say(self, text):
        if self.modern:
            assert text.startswith("/"), "modern bots only send commands"
            self._send(self.ids["cmd_out"], string(text[1:]))  # unsigned chat command (1.20.5+)
        else:
            self._send(self.ids["chat_out"], string(text))

    def chat(self, text):
        """A plain (unsigned) chat line, as typed without a slash (26.3 only)."""
        assert self.protocol == 777, "chat lines are only wired for 26.3"
        payload = string(text) + struct.pack(">qq", int(time.time() * 1000), 0) + b"\x00"  # no signature
        payload += varint(0) + b"\x00\x00\x00" + b"\x00"  # message count, acknowledged (20 bits), checksum
        self._send(self.ids["chat_out"], payload)

    def mark(self):
        return len(self.messages)

    def wait_for(self, needle, since=0, timeout=10):
        deadline = time.time() + timeout
        while time.time() < deadline:
            for text, raw in self.messages[since:]:
                if needle in text:
                    return text, raw
            if self.kicked:
                raise AssertionError(f"{self.name} was kicked ({self.kicked}) while waiting for {needle!r}")
            time.sleep(0.05)
        seen = "\n  ".join(t for t, _ in self.messages[since:]) or "(nothing)"
        raise AssertionError(f"{self.name} never got {needle!r}; got:\n  {seen}")

    def close(self):
        try:
            self.sock.close()
        except OSError:
            pass
