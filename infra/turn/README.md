# Personal Nawslk TURN deployment

This is an activation kit, **not a running production relay**. It leaves the existing Railway API and PostgreSQL in place. Checked 2026-10-12: Railway's public networking tool exposes HTTP and a raw TCP proxy; it does not expose the publicly reachable UDP relay range this coturn deployment requires. TURN-over-TCP is the client-to-server transport, not a replacement for those UDP relay sockets. A public Linux host with direct UDP ingress, or an approved managed TURN service, is needed.

References: https://docs.railway.com/networking/tcp-proxy and https://github.com/coturn/coturn/blob/master/examples/etc/turnserver.conf . Coturn requires relay ports behind NAT to map to the same public port. Private Railway UDP networking is not public ingress for Android phones.

## Host requirements

- Operator-owned Linux server, maintained coturn package and systemd; public IPv4; DNS hostname pointing directly to it, without HTTP proxying.
- Valid TLS certificate/key for that hostname, readable by the coturn service account. Certificate renewal must reload/restart coturn using the distribution's service name; plan active-call interruption.
- Inbound 3478 UDP and TCP, 5349 TCP, and 49160–49260 UDP at both provider firewall and host firewall. NAT must map the relay range one-to-one. Outbound UDP must work. Open administrative SSH only to authorized operators.
- Clock synchronization for expiring credentials. No storage/database or audio recording is required.

## Prepare and activate

Run on that host, outside the Git checkout. Replace all placeholders with real values:

```sh
sudo python3 /path/to/Nawslk/infra/turn/prepare.py \
  --public-ip PUBLIC_IPV4 --bind-ip LOCAL_IPV4 --realm turn.YOUR_DOMAIN \
  --cert-file /absolute/path/fullchain.pem --key-file /absolute/path/privkey.pem \
  --output-dir /etc/nawslk-turn-private
```

The script requires existing certificate/key paths, creates three files with mode 0600 in a new mode-0700 directory, and prints no secret. It refuses to overwrite an existing directory, avoiding accidental secret rotation. Path existence does not validate certificate contents or DNS; the external probe is required. Install the generated `turnserver.conf` into the distribution's coturn configuration location, root-owned and group-readable by its actual service account (usually `turnserver`); preserve mode 0640. Make the TLS files readable by that account, then enable/start the distribution's coturn service. Do not use CI's `allow-loopback-peers` in production. The generated configuration limits allocations/bandwidth and blocks private, loopback, multicast and link-local peer destinations, including metadata addresses.

Enter the two values in `railway.env` into **the existing API service's Railway Variables UI**, without pasting them into chat, issues, logs or GitHub. The same shared secret must be used by API and coturn. Never set random TURN_URLS merely to make `/health` report configured. Redeploy the existing API, then check HTTPS `/health`; `turnConfigured=true` is a configuration-presence check, **not evidence that media passes**.

## External audio verification

On an authorized test machine with Node dependencies and Playwright Chromium installed:

```sh
cd backend
TURN_URLS='turn:REAL_HOST:3478?transport=udp' \
TURN_SECRET_FILE=/protected/path/shared-secret npm run probe:turn
```

Repeat with only the TCP URL, then only the TLS URL to verify each advertised transport independently. Use a second network as well. This probe creates expiring HMAC credentials, forces relay on two independent PeerConnections, measures inbound RTP and non-zero audio energy in both directions, and closes tracks/peers/audio contexts. It never calls bootstrap, edits PostgreSQL or touches owner/device sessions; it prints only success or a safe error. A failed probe must not be counted as an activated relay. CI exercises the probe against disposable coturn, not an actual public server.

Then perform the authenticated two-device call acceptance in STAGE_3_ACCEPTANCE.md. This probe sends generated audio, not a physical Android microphone, and does not replace hardware, Bluetooth, lock-screen or interruption tests.

## Firebase and Stage 4

Firebase is optional for a personal foreground-only receiving mode: both devices must keep Nawslk open and authenticated to receive calls through polling. This does not satisfy the original background incoming-call acceptance requirement. Background notifications still need configured FCM or another implemented push transport, notification permission, and real device validation; delivery after force-stop is not guaranteed. No Firebase configuration or secrets have been created here.

Stage 4's offline/demo messaging foundation is deployed. Real SMS, receiving and number assignment remain disabled until a licensed provider account and vendor adapter exist. Personal use does not make demo messages into phone SMS.
