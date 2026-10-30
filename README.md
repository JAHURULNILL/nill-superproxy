# Nill Super Proxy — personal build

A personal, single-user clone of the Super Proxy concept: tunnel all Android
apps through an HTTP or SOCKS5 proxy, no root. All "Pro" features included.

## UI (mirrors the reference app)
- Top blue bar, light blue-grey background, white profile cards with
  HTTP (blue) / SOCKS5 (pink) pills, favourite star
- Bottom tabs: **Proxies / Logging / Settings**
- Profile form: name, protocol, server, port, auth method, username,
  password, DNS resolution, TLS toggle, big green **Start** / red **Stop**
  button with a running timer on the top bar
- Logging tab: live up/down speeds + connection log (DNS names, CONNECT
  targets, firewall blocks, blocked UDP/IPv6 counters)
- Dark theme included

## Pro features included
- Per-app proxy bypass (Disallowed apps) with search
- Allowlist mode (only chosen apps use the proxy)
- App firewall (listed apps get no internet while connected, Android 10+)
- Quick Settings tile
- Home-screen widget
- Dark mode
- Unlimited profiles, JSON export/import
- Auto-connect on boot, auto-reconnect, connectivity check

## Engine — leak-proof by design
Stronger than a plain tun2proxy:
- Android `VpnService` TUN captures all IPv4 traffic; a built-in TCP stack
  relays every connection **only** through the configured proxy
  (SOCKS5 RFC 1928/1929 or HTTP CONNECT, optional TLS wrap to the proxy).
- **DNS**: in "Proxy" mode queries are answered on-device with fake IPs
  (198.18.0.0/15) and the name itself travels inside the CONNECT, so the
  *proxy* resolves it — no DNS packet ever leaves the phone, and the
  resolver the world sees always matches the exit IP's country/ISP.
  Other modes re-issue the query over TCP *through the proxy* instead.
  AAAA always returns empty, so there is no IPv6 to leak.
- **Leak shield**: Settings → *Privacy & leak shield* — an optional strict
  mode (bypass/allowlist ignored, everything tunnelled; off by default —
  per-app rules are yours), live counters of blocked UDP/IPv6 and
  shielded DNS, plus an honest privacy guide.
- **UDP**: dropped entirely except DNS → STUN/WebRTC cannot leak the real
  IP; QUIC falls back to TCP so it still goes through the proxy.
- **IPv6**: routed into the tunnel (ULA + ::/0) and dropped → no bypass.
- The proxy socket itself is `protect()`-ed so it never loops.
- Kill switch: use Android's system setting (Settings row deep-links
  there): Always-on VPN + "Block connections without VPN".

Honest limits:
- The app cannot hide *that* a proxy is used if the proxy IP itself is a
  flagged datacenter address — use a residential/mobile proxy for that.
- Timezone/locale/device fingerprint are outside any proxy app's control.
- ICMP (ping) does not work through HTTP/SOCKS5 proxies — same as the
  reference app.

## Build
`./build.sh` — manual pipeline (aapt2 → javac → d8 → zipalign → apksigner),
JDK 17 + Android SDK 34 in ~/workspace. Engine protocol logic is covered by
JVM tests in `test/` (13 checks against mock SOCKS5/HTTP proxies).

Package: `com.nill.superproxy` • minSdk 24 • targetSdk 33

## Updates
The app checks this repo's GitHub Releases on start (at most every 6 hours)
and offers a one-tap update when a newer version is published. Manual check:
Settings → Check for updates. Android shows its own install confirmation —
there is no silent install for sideloaded apps.

## Version
**1.10** — reference-matched UI (outlined fields, vector icons, launcher icon),
clean sheet-style Settings, in-app auto-update via GitHub Releases.
