# homeostat-app

The Android companion app for [homeostat](https://github.com/freol35241/homeostat).

**Status: early.** It provisions from the QR code the house repo renders
and holds the persistent MQTT session — birth message, last will,
reconnect with backoff. No geofence and no notification channels yet;
incoming `message`/`alert` are subscribed to but only logged.

## What it is

A family phone is a device the house binds, like a lamp or a heat pump.
This app is the dialect it speaks — over MQTT, to the broker the house
already runs, across the WireGuard tunnel. It says two things and hears
one:

- **presence** — one geofence, `home`, registered with the platform's
  Geofencing API, published as enter/leave. This is why the app exists:
  background geofencing is the one thing no web page and no existing tool
  does well against this house. Continuous position is opt-in per phone
  and off by default.
- **acknowledged** — that the person actually saw the notification, which
  no delivery service can report.
- **notifications** — `message` and `alert` as two channels, `alert`
  bypassing Do Not Disturb.

The house side is the `companion` adapter in the homeostat repo. The wire
contract between them is
[docs/companion-protocol.md](https://github.com/freol35241/homeostat/blob/main/docs/companion-protocol.md),
which is normative and lives there, not here. Topics, payloads, session
and last-will rules: implement against that file, and take a change to it
through an issue on homeostat.

## What it is not

- **Not a dashboard.** The app never renders house state; the dashboard
  owns rendering. A WebView of the dashboard is the eventual answer to
  the missing home-screen icon — the same page, in a standalone window,
  without the private CA a PWA would need. Not a second UI.
- **Not an owner surface.** Family tier, like the dashboard,
  structurally.
- **Not a WireGuard client.** The tunnel is the WireGuard app's job; this
  app assumes it is on the house network.
- **Not on the bus.** The phone speaks MQTT to an adapter. The reasons
  are in homeostat's design record, under "The companion app".

## Building

```
./gradlew test lint assembleDebug
```

The devcontainer carries the JDK and the Android SDK; open the repo in it
and the first build works. Builds are headless — the emulator wants KVM,
which is a fight under WSL2, and the parts that matter (reconnect under
Doze, real geofence transitions, DND bypass) need a real phone anyway.
`adb connect` to one on the LAN.

The unit tests cover the config blob parser and the session's reconnect
logic, both pure JVM. What Paho does with the session — `clean_session`,
the will, the keepalive — and how it all behaves under Doze is not
covered by them.

Targets API 31+ (the family's Galaxy S22 and S25).

## License

Apache 2.0, like homeostat.
