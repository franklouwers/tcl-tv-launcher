#!/usr/bin/env python3
"""Tiny ADB client for the TV, built on the pure-Python `adb-shell` package.

Used instead of the stock `adb` binary because macOS Local Network privacy can
block the adb server daemon ("No route to host") even when port 5555 is open.

Usage:
  tvadb.py shell "<command>"           run one shell command, print output
  tvadb.py key DOWN DOWN OK BACK HOME  inject remote key presses (sleep:1 pauses)
  tvadb.py shot out.png                take a screenshot from the TV
  tvadb.py push local.apk /data/local/tmp/x.apk
  tvadb.py install app.apk             push + pm install -r
  tvadb.py pull /sdcard/file local

Env: TV_IP (default 192.168.160.128), TV_PORT (default 5555)
"""
import base64
import hashlib
import os
import sys
import time

from adb_shell.adb_device import AdbDeviceTcp
from adb_shell.auth.keygen import keygen
from adb_shell.auth.sign_pythonrsa import PythonRSASigner

TV_IP = os.environ.get("TV_IP", "192.168.160.128")
# Friendly names for remote keys.
KEY_ALIASES = {"UP": "DPAD_UP", "DOWN": "DPAD_DOWN", "LEFT": "DPAD_LEFT", "RIGHT": "DPAD_RIGHT",
               "OK": "DPAD_CENTER", "CENTER": "DPAD_CENTER", "INPUT": "TV_INPUT"}
TV_PORT = int(os.environ.get("TV_PORT", "5555"))
KEY = os.path.expanduser("~/.android/adbkey")


def connect(auth_timeout=120):
    if not os.path.exists(KEY):
        os.makedirs(os.path.dirname(KEY), exist_ok=True)
        keygen(KEY)
    with open(KEY) as f:
        priv = f.read()
    with open(KEY + ".pub") as f:
        pub = f.read()
    dev = AdbDeviceTcp(TV_IP, TV_PORT, default_transport_timeout_s=15.0)
    dev.connect(rsa_keys=[PythonRSASigner(pub, priv)], auth_timeout_s=auth_timeout)
    return dev


def shell(dev, cmd, timeout=60):
    return dev.shell(cmd, timeout_s=timeout, read_timeout_s=timeout, transport_timeout_s=timeout)


def push_b64(dev, local, remote, chunk=48000):
    """Fallback push: base64 chunks through the shell, then verify md5."""
    data = open(local, "rb").read()
    b64 = base64.b64encode(data).decode()
    tmp = remote + ".b64"
    shell(dev, f"rm -f {tmp} {remote}")
    for i in range(0, len(b64), chunk):
        shell(dev, f"echo -n '{b64[i:i + chunk]}' >> {tmp}")
    shell(dev, f"base64 -d {tmp} > {remote} && rm -f {tmp}")
    return verify_md5(dev, local, remote)


def verify_md5(dev, local, remote):
    want = hashlib.md5(open(local, "rb").read()).hexdigest()
    got = shell(dev, f"md5sum {remote}").split()[0] if remote else ""
    if want != got:
        raise SystemExit(f"md5 mismatch for {remote}: local {want} remote {got}")
    return want


def push(dev, local, remote):
    size = os.path.getsize(local)
    if size < 512 * 1024:
        try:
            dev.push(local, remote, read_timeout_s=60, transport_timeout_s=60)
            return verify_md5(dev, local, remote)
        except Exception as e:  # noqa: BLE001 - fall back on any sync failure
            print(f"sync push failed ({e}); using base64 chunks", file=sys.stderr)
    return push_b64(dev, local, remote)


def pull(dev, remote, local):
    try:
        dev.pull(remote, local, read_timeout_s=60, transport_timeout_s=60)
    except Exception:  # noqa: BLE001
        b64 = shell(dev, f"base64 {remote}", timeout=120)
        open(local, "wb").write(base64.b64decode("".join(b64.split())))


def shot(dev, local):
    shell(dev, "screencap -p /sdcard/tvshot.png")
    pull(dev, "/sdcard/tvshot.png", local)
    shell(dev, "rm -f /sdcard/tvshot.png")


def main(argv):
    if len(argv) < 2:
        print(__doc__)
        return 1
    op, args = argv[1], argv[2:]
    dev = connect()
    try:
        if op == "shell":
            out = shell(dev, " ".join(args), timeout=float(os.environ.get("TV_TIMEOUT", "60")))
            sys.stdout.write(out if out.endswith("\n") or not out else out + "\n")
        elif op == "key":
            for k in args:
                if k.startswith("sleep:"):
                    time.sleep(float(k.split(":", 1)[1]))
                    continue
                k = KEY_ALIASES.get(k.upper(), k)
                code = k if k.isdigit() or k.startswith("KEYCODE_") else "KEYCODE_" + k
                shell(dev, f"input keyevent {code}")
                time.sleep(float(os.environ.get("TV_KEY_DELAY", "0.35")))
        elif op == "shot":
            time.sleep(float(os.environ.get("TV_SHOT_DELAY", "0.6")))
            shot(dev, args[0])
            print(args[0])
        elif op == "push":
            print(push(dev, args[0], args[1]))
        elif op == "pull":
            pull(dev, args[0], args[1])
        elif op == "install":
            remote = "/data/local/tmp/" + os.path.basename(args[0])
            push(dev, args[0], remote)
            print(shell(dev, f"pm install -r -t {remote}", timeout=180).strip())
            shell(dev, f"rm -f {remote}")
        else:
            print(__doc__)
            return 1
    finally:
        dev.close()
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
