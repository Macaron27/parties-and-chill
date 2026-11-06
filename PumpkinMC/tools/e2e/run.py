#!/usr/bin/env python3
"""End-to-end tests of the Pumpkin plugins on a real Pumpkin server, with 26.3 bot players (mcbot.py).

    python3 PumpkinMC/tools/e2e/run.py <dir> [pumpkin] [bridge] [bungee]

<dir> holds the Pumpkin binary as `pumpkin` (the `pumpkin-X64-Linux` asset of
https://github.com/Pumpkin-MC/Pumpkin/releases/tag/nightly); servers run in throwaway folders inside it. Build the
plugins first (`cargo plugins` in PumpkinMC/).

- `pumpkin`: partiesandchill_pumpkin.wasm alone: commands, chat lock, settings, roles, warp, disconnect grace, spy.
- `bridge`: partiesandchill_bridge.wasm behind Velocity running PartiesAndChill-velocity.jar (also in <dir>, with
  `velocity.jar`; Java runs in the gradle:jdk25 Docker image): hello, snapshots, the chat lock through the proxy.
- `bungee`: the same behind BungeeCord running PartiesAndChill-bungee.jar (with `BungeeCord.jar`), IP forwarding.
"""
import os
import re
import shutil
import subprocess
import sys
import threading
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from mcbot import Bot  # noqa: E402

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))
WASM = os.path.join(ROOT, "PumpkinMC", "target", "wasm32-wasip2", "release")
PROTOCOL = 777  # Minecraft 26.3


class Server:
    """A process whose output is collected (and written to <folder>/e2e.log)."""

    def __init__(self, name, folder, command, ready):
        self.name, self.folder, self.command, self.ready = name, folder, command, re.compile(ready)
        self.lines, self.proc = [], None

    def start(self, timeout=120):
        self.proc = subprocess.Popen(self.command, cwd=self.folder, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                     stderr=subprocess.STDOUT, text=True, bufsize=1)
        threading.Thread(target=self._pump, daemon=True).start()
        deadline = time.time() + timeout
        while time.time() < deadline:
            if any(self.ready.search(line) for line in self.lines):
                return self
            if self.proc.poll() is not None:
                break
            time.sleep(0.2)
        raise RuntimeError(f"{self.name} did not start:\n" + "".join(self.lines[-40:]))

    def _pump(self):
        with open(os.path.join(self.folder, "e2e.log"), "w") as log:
            for line in self.proc.stdout:
                line = re.sub(r"\x1b\[[0-9;]*m", "", line)
                self.lines.append(line)
                log.write(line)
                log.flush()

    def console(self, command):
        self.proc.stdin.write(command + "\n")
        self.proc.stdin.flush()

    def wait_log(self, pattern, since=0, timeout=15):
        rx = re.compile(pattern)
        deadline = time.time() + timeout
        while time.time() < deadline:
            for line in self.lines[since:]:
                if rx.search(line):
                    return line
            time.sleep(0.1)
        raise AssertionError(f"{self.name}: no log line matching {pattern!r}; last lines:\n" + "".join(self.lines[-25:]))

    def problems(self):
        """Errors, panics, traps and our plugins' warnings (not Pumpkin's notes about unsigned plugins)."""
        known = re.compile(r"is unsigned|uses the following permissions")
        return [l for l in self.lines
                if re.search(r"ERROR|panicked|[Tt]rap|WARN.*[Pp]art(y|ies)", l) and not known.search(l)]

    def stop(self):
        if self.proc and self.proc.poll() is None:
            try:
                self.console("stop")
                self.proc.wait(timeout=20)
            except (OSError, subprocess.TimeoutExpired):
                self.proc.kill()


def pumpkin(work, name, port, plugins, proxy=""):
    """A fresh Pumpkin server in <work>/<name>, offline mode, on 127.0.0.1:<port>. `proxy`: its forwarding settings."""
    folder = os.path.join(work, name)
    shutil.rmtree(folder, ignore_errors=True)
    os.makedirs(os.path.join(folder, "plugins"))
    for plugin in plugins:
        shutil.copy(os.path.join(WASM, plugin), os.path.join(folder, "plugins"))
    with open(os.path.join(folder, "pumpkin.toml"), "w") as f:
        f.write(f"""[networking.java]
enabled = true
address = "127.0.0.1:{port}"
encryption = false
online_mode = false
view_distance = 2
simulation_distance = 2

[networking.bedrock]
enabled = false

[chat.anti_spam]
enabled = false

[commands]
use_tty = false

[plugins]
ask_permission_confirmation = false
allowed_permissions = ["fs.write.data"]

[telemetry]
enabled = false
{proxy}""")
    return Server(name, folder, [os.path.join(work, "pumpkin")], r"Server is now running").start()


def bots(port, *names):
    return [Bot(name, port, PROTOCOL).connect() for name in names]


def quiet(bot, needle, since, wait=1.5):
    time.sleep(wait)
    leaked = [t for t, _ in bot.messages[since:] if needle in t]
    assert not leaked, f"{bot.name} should not have seen {needle!r}: {leaked}"


def scenario_pumpkin(work):
    server = pumpkin(work, "pnc-pumpkin", 25611, ["partiesandchill_pumpkin.wasm", "pnc_probe.wasm"])
    players = []
    try:
        server.wait_log(r"Parties & Chill ready: parties of 8 \(up to 24 by permission\)")
        data = os.path.join(server.folder, "plugins", "data", "partiesandchill")
        for name in ("config.yml", "messages.yml"):
            with open(os.path.join(data, name)) as written, \
                    open(os.path.join(ROOT, "core", "src", "main", "resources", name)) as bundled:
                assert written.read() == bundled.read(), f"{name} differs from the bundled file"
        print("  loaded; config.yml and messages.yml written as bundled")

        server.wait_log(r'probe: subscribe to partiesandchill: \{"ok":true,"result":true\}')
        alice, bob, carol, dave = players[:] = bots(25611, "Alice", "Bob", "Carol", "Dave")
        server.wait_log(r"Dave joined")

        # Invites, with clickable buttons.
        alice.say("/p Bob")
        _, raw = bob.wait_for("Alice invited you to their party!")
        assert "/party accept Alice" in raw and "/party deny Alice" in raw, raw
        alice.wait_for("Alice invited Bob to the party. They have 1m to accept.")
        bob.say("/party accept Alice")
        bob.wait_for("You joined Alice's party!")
        alice.wait_for("Bob joined the party.")
        alice.say("/p list")
        alice.wait_for("Party Members (2/8)")
        print("  invite, clickable accept, join, list")

        # The API over IPC (tools/probe): a cancelled join, a call from inside an event, requests.
        alice.say("/p invite Dave")
        dave.wait_for("Alice invited you to their party!")
        m = dave.mark()
        dave.say("/p accept Alice")
        server.wait_log(r"probe: cancelled Dave's join")
        quiet(dave, "You joined", m)
        log = len(server.lines)
        bob.say("/pc the api works")
        alice.wait_for("[Party] Bob » the api works")
        server.wait_log(r'probe: re-entrant isInParty -> \{"ok":true,"result":true\}', since=log)
        leader = re.search(r'"event":"PartyCreateEvent".*?"player":"([0-9a-f-]+)"', "".join(server.lines)).group(1)
        alice.say('/probe pnc {"method":"getParty","player":"@Bob"}')
        _, raw = alice.wait_for("probe: ")
        assert f'\\"leader\\":\\"{leader}\\"' in raw and '\\"server\\":\\"world\\"' in raw, raw
        alice.say('/probe pnc {"method":"isPartyLeader","player":"@Alice"}')
        alice.wait_for('"result":true')
        alice.say('/probe pnc {"method":"nope"}')
        alice.wait_for('"error":"bad_request"')
        print("  API: cancelled join, re-entrant call during an event, getParty, errors")

        # Party chat, then the chat lock (plain chat lines diverted, outsiders see nothing).
        m = carol.mark()
        bob.say("/pc hello there")
        _, raw = alice.wait_for("[Party] Bob » hello there")
        assert "Sent at" in raw and "/msg Bob " in raw, raw
        log = len(server.lines)
        bob.chat("public line")
        server.wait_log(r"public line", since=log)
        bob.say("/pc")
        bob.wait_for("Party chat locked on.")
        log = len(server.lines)
        bob.chat("secret plan")
        alice.wait_for("[Party] Bob » secret plan")
        quiet(carol, "secret plan", m)
        assert not any("<chat> Bob: secret plan" in l for l in server.lines[log:]), "the locked line was broadcast"
        bob.say("/pc toggle")
        bob.wait_for("Party chat lock off")
        print("  /pc, chat lock diverts plain chat (cancelled for everyone else)")

        # Settings, public join, roles, mentions, warp.
        alice.say("/p settings")
        _, raw = alice.wait_for("Party Settings")
        assert "/party settings allinvite" in raw, raw
        alice.say("/p settings public")
        bob.wait_for("Alice turned public on.")
        carol.say("/p join Alice")
        carol.wait_for("You joined Alice's party!")
        alice.say("/p promote Bob")
        carol.wait_for("Alice promoted Bob to moderator.")
        bob.say("/p kick Alice")
        bob.wait_for("You can only do that to members ranked below you.")
        carol.say("/pc hey @bob")
        _, raw = bob.wait_for("[Party] Carol » hey @Bob")
        alice.say("/p warp")
        alice.wait_for("Warping 2 member(s) to world.")
        bob.wait_for("Alice summoned you to world.")
        server.wait_log(r"probe: teleport Bob")
        server.wait_log(r"probe: teleport Carol")
        print("  settings menu, public join, promote, rank checks, @mention, warp")

        # Disconnect grace and reconnect.
        carol.close()
        alice.wait_for("Carol disconnected. They have 5m to rejoin before being removed.")
        carol = Bot("Carol", 25611, PROTOCOL).connect()
        players.append(carol)
        alice.wait_for("Carol reconnected.")
        log = len(server.lines)
        alice.say("/p kick Carol")
        carol.wait_for("You were removed from the party by Alice.")
        server.wait_log(r'"event":"PartyLeaveEvent"', since=log)
        print("  disconnect grace, reconnect, kick")

        # Staff spy (operators level 3+ by default).
        m = carol.mark()
        carol.say("/pc spy")
        carol.wait_for("You are not in a party.", since=m)
        server.console("op Carol")
        server.wait_log(r"Carol")
        time.sleep(0.5)
        carol.say("/pc spy")
        carol.wait_for("Party chat spy on.")
        bob.say("/pc gg")
        carol.wait_for("[Spy] Alice's party » Bob » gg")
        print("  /pc spy needs partiesandchill:parties.admin.spy")

        log = len(server.lines)
        server.console("party")
        server.wait_log(r"Only players can use this command", since=log)
        alice.say("/p disband")
        bob.wait_for("Alice disbanded the party.")
        print("  console refused, disband")
        problems = server.problems()
        assert not problems, "".join(problems)
    finally:
        for bot in players:
            bot.close()
        server.stop()


def java_proxy(work, name, jar, plugin, config):
    """A proxy in <work>/<name> (gradle:jdk25 image, host network) running <jar> with <plugin>; `config`: {file: text}."""
    folder = os.path.join(work, name)
    shutil.rmtree(folder, ignore_errors=True)
    os.makedirs(os.path.join(folder, "plugins"))
    shutil.copy(os.path.join(work, jar), folder)
    shutil.copy(os.path.join(work, plugin), os.path.join(folder, "plugins"))
    for file, text in config.items():
        with open(os.path.join(folder, file), "w") as f:
            f.write(text)
    subprocess.run(["docker", "rm", "-f", name], capture_output=True)
    return folder, ["docker", "run", "--rm", "-i", "--name", name, "--network", "host",
                    "-u", f"{os.getuid()}:{os.getgid()}", "-v", f"{folder}:/v", "-w", "/v",
                    "gradle:jdk25", "java", "-Xmx512m", "-jar", jar]


def velocity(work, port, backend_port):
    """Velocity with PartiesAndChill-velocity.jar, offline mode, modern forwarding. Returns it and Pumpkin's side."""
    secret = "pnc-e2e-secret"
    folder, command = java_proxy(work, "pnc-velocity", "velocity.jar", "PartiesAndChill-velocity.jar", {
        "forwarding.secret": secret,
        "velocity.toml": f"""config-version = "2.9"
bind = "127.0.0.1:{port}"
online-mode = false
force-key-authentication = false
player-info-forwarding-mode = "modern"
forwarding-secret-file = "forwarding.secret"

[servers]
pumpkin = "127.0.0.1:{backend_port}"
try = ["pumpkin"]

[forced-hosts]

[advanced]
login-ratelimit = 0
# Bots type faster than people: rate-limited commands would be forwarded to the backend instead of run here.
command-rate-limit = 0
"""})
    backend = f'\n[networking.proxy]\nenabled = true\n\n[networking.proxy.velocity]\nenabled = true\nsecret = "{secret}"\n'
    return Server("velocity", folder, command, r"Done \("), backend


def bungeecord(work, port, backend_port):
    """BungeeCord with PartiesAndChill-bungee.jar, offline mode, IP forwarding. Returns it and Pumpkin's side."""
    folder, command = java_proxy(work, "pnc-bungeecord", "BungeeCord.jar", "PartiesAndChill-bungee.jar", {
        "config.yml": f"""online_mode: false
ip_forward: true
connection_throttle: -1
log_commands: false
log_pings: false
prevent_proxy_connections: false
enforce_secure_profile: false
listeners:
- host: 127.0.0.1:{port}
  query_enabled: false
  motd: pnc
  max_players: 20
  tab_list: GLOBAL_PING
  force_default_server: true
  priorities:
  - pumpkin
  bind_local_address: true
  ping_passthrough: false
  forced_hosts: {{}}
  proxy_protocol: false
servers:
  pumpkin:
    address: 127.0.0.1:{backend_port}
    restricted: false
    motd: pumpkin
permissions:
  default: []
groups: {{}}
""",
        # No modules (/alert, /find, /server...): nothing to download.
        "modules.yml": "version: 99\nmodules: []\n",
    })
    backend = "\n[networking.proxy]\nenabled = true\n\n[networking.proxy.bungeecord]\nenabled = true\n"
    return Server("bungeecord", folder, command, r"Listening on /127\.0\.0\.1"), backend


def scenario_bridge(work, kind=velocity):
    """A Pumpkin backend with the bridge behind a proxy running Parties & Chill: the proxy owns the parties."""
    proxy, forwarding = kind(work, 25613, 25612)
    backend = pumpkin(work, "pnc-bridge", 25612, ["partiesandchill_bridge.wasm", "pnc_probe.wasm"], forwarding)
    players = []
    try:
        backend.wait_log(r"Parties & Chill bridge ready \(protocol 3, channel pnc:main\)")
        backend.wait_log(r'probe: subscribe to partiesandchillbridge: \{"ok":true,"result":true\}')
        proxy.start(timeout=180)
        proxy.wait_log(r"Parties & Chill ready \(single-proxy mode\)")
        alice, bob, carol, dave = players[:] = bots(25613, "Alice", "Bob", "Carol", "Dave")
        backend.wait_log(r"Dave joined")
        print(f"  {proxy.name} + Parties & Chill in front of the bridge")

        # The proxy runs /party; the bridge learns the party from its snapshots.
        alice.say("/p Bob")
        bob.wait_for("Alice invited you to their party!")
        bob.say("/party accept Alice")
        bob.wait_for("You joined Alice's party!")
        time.sleep(0.5)
        bob.say('/probe bridge {"method":"isInParty","player":"@Bob"}')
        bob.wait_for('"result":true')
        bob.say('/probe bridge {"method":"isPartyLeader","player":"@Alice"}')
        bob.wait_for('"result":true')
        print("  hello + snapshots: the bridge's party cache follows the proxy")

        # The chat lock: only possible on 1.19.1+ clients because the bridge cancels the signed chat here.
        bob.say("/pc")
        bob.wait_for("Party chat locked on.")
        m, log = carol.mark(), len(backend.lines)
        bob.chat("secret plan")
        alice.wait_for("[Party] Bob » secret plan")
        quiet(carol, "secret plan", m)
        assert not any("<chat> Bob: secret plan" in l for l in backend.lines[log:]), "the locked line was broadcast"
        bob.say("/pc toggle")
        bob.wait_for("Party chat lock off")
        bob.say("/pc hey @alice")
        alice.wait_for("[Party] Bob » hey @Alice")
        print("  chat lock through the bridge (diverted to the proxy, cancelled here), mention")

        # Developer API events (checks and leave notices) and requests answered by the proxy.
        alice.say("/p invite Dave")
        dave.wait_for("Alice invited you to their party!")
        m = dave.mark()
        dave.say("/p accept Alice")
        backend.wait_log(r"probe: cancelled Dave's join")
        quiet(dave, "You joined", m)
        log = len(backend.lines)
        alice.say('/probe bridge {"method":"getParty","player":"@Alice"}')
        alice.wait_for('"request"')
        line = backend.wait_log(r'from partiesandchillbridge: \{"ok":true,"reply"', since=log)
        assert '"leader"' in line and '"server":"pumpkin"' in line, line
        log = len(backend.lines)
        alice.say('/probe bridge {"method":"removeMember","leader":"@Alice","target":"@Bob"}')
        bob.wait_for("You were removed from the party by Alice.")
        backend.wait_log(r'from partiesandchillbridge: \{"ok":true,"reply":-?\d+,"result":true\}', since=log)
        backend.wait_log(r'"event":"PartyLeaveEvent"', since=log)
        print("  API: cancelled join (check/verdict), getParty and removeMember answered by the proxy, leave event")
        problems = backend.problems() + [l for l in proxy.lines if re.search(r"ERROR|SEVERE|Exception", l)]
        assert not problems, "".join(problems)
    finally:
        for bot in players:
            bot.close()
        if proxy.proc:
            proxy.console("end")
            try:
                proxy.proc.wait(timeout=30)
            except subprocess.TimeoutExpired:
                subprocess.run(["docker", "rm", "-f", os.path.basename(proxy.folder)], capture_output=True)
        backend.stop()


SCENARIOS = {"pumpkin": scenario_pumpkin, "bridge": scenario_bridge,
             "bungee": lambda work: scenario_bridge(work, bungeecord)}


def main():
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    work = os.path.abspath(sys.argv[1])
    names = sys.argv[2:] or ["pumpkin"]
    for name in names:
        print(f"== {name}")
        SCENARIOS[name](work)
        print(f"== {name}: OK")


if __name__ == "__main__":
    main()
