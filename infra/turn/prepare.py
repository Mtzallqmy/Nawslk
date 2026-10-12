"""Prepare private coturn configuration on an operator-owned Linux host.

Does not install software, open firewalls, change Railway, or start a service.
"""
import argparse
import ipaddress
import os
from pathlib import Path
import re
import secrets


def prepare(args):
    public = ipaddress.ip_address(args.public_ip)
    bind = ipaddress.ip_address(args.bind_ip or args.public_ip)
    if public.version != 4 or not public.is_global or bind.version != 4:
        raise ValueError("A public IPv4 address and IPv4 bind address are required")
    if not re.fullmatch(r"(?=.{1,253}$)[a-zA-Z0-9]+(?:[.-][a-zA-Z0-9]+)*", args.realm):
        raise ValueError("Invalid realm/hostname")
    for name in (args.cert_file, args.key_file):
        if not re.fullmatch(r"/[a-zA-Z0-9_./-]+", name) or not Path(name).is_file():
            raise ValueError("Certificate and key must exist at absolute paths")
    target = Path(args.output_dir)
    # Fail on a second invocation rather than accidentally rotate a live secret.
    target.mkdir(mode=0o700, parents=False, exist_ok=False)
    secret = secrets.token_hex(32)
    config = [
        "listening-port=3478", "tls-listening-port=5349",
        f"listening-ip={bind}", f"relay-ip={bind}",
        f"external-ip={public}/{bind}", f"realm={args.realm}",
        "fingerprint", "use-auth-secret", f"static-auth-secret={secret}",
        f"cert={args.cert_file}", f"pkey={args.key_file}",
        "no-tlsv1", "no-tlsv1_1", "no-dtls", "no-tcp-relay",
        "min-port=49160", "max-port=49260", "user-quota=4", "total-quota=20",
        "max-bps=128000", "bps-capacity=2560000",
        "no-cli", "no-multicast-peers", "no-software-attribute",
        "denied-peer-ip=0.0.0.0-0.255.255.255",
        "denied-peer-ip=10.0.0.0-10.255.255.255",
        "denied-peer-ip=100.64.0.0-100.127.255.255",
        "denied-peer-ip=127.0.0.0-127.255.255.255",
        "denied-peer-ip=169.254.0.0-169.254.255.255",
        "denied-peer-ip=172.16.0.0-172.31.255.255",
        "denied-peer-ip=192.168.0.0-192.168.255.255",
        "denied-peer-ip=224.0.0.0-255.255.255.255",
        "denied-peer-ip=::1", "denied-peer-ip=fc00::-fdff:ffff:ffff:ffff:ffff:ffff:ffff:ffff",
        "denied-peer-ip=fe80::-febf:ffff:ffff:ffff:ffff:ffff:ffff:ffff",
    ]
    files = {
        "turnserver.conf": "\n".join(config) + "\n",
        "shared-secret": secret + "\n",
        "railway.env": f"TURN_URLS=turn:{args.realm}:3478?transport=udp,turn:{args.realm}:3478?transport=tcp,turns:{args.realm}:5349?transport=tcp\nTURN_SHARED_SECRET={secret}\n",
    }
    for name, content in files.items():
        fd = os.open(target / name, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
        with os.fdopen(fd, "w") as stream:
            stream.write(content)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    for option in ("public-ip", "realm", "cert-file", "key-file", "output-dir"):
        parser.add_argument("--" + option, required=True)
    parser.add_argument("--bind-ip")
    try:
        prepare(parser.parse_args())
    except (ValueError, OSError):
        parser.exit(1, "Configuration not written: invalid input, existing output directory, or filesystem error.\n")
    print("Private configuration prepared. Service and Railway activation are still required.")
