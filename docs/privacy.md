# Privacy policy — Homeostat (Android app)

Homeostat is the companion app for [homeostat](https://github.com/freol35241/homeostat),
a home automation system a household runs on its own hardware. The app is
open source; this repository is its complete source.

## Where your data goes

The app talks to exactly two addresses, both taken from the configuration
your household gives it when you scan its QR code: your house's MQTT
broker, and, if configured, your house's dashboard page. Both are your
household's own server, reached over your own network tunnel.

The developer runs no server for this app and receives nothing from it.
The app contains no analytics, no advertising, and no crash reporting.

## What the app sends to your house

- **Whether you are home.** When you enter or leave the area around your
  home that your household configured, the app sends `home` or `away`.
- **Your position, only if you turn it on.** With "Share position while
  away" switched on, and only while you are away from home, the app sends
  your location (latitude, longitude and accuracy), your battery level and
  the time of the fix, at most every few minutes. It is off by default.
- **That you saw an alert.** When you open or dismiss an alert, the app
  sends back that alert's send time, so the house knows it was seen.
- **Whether the app is connected**, so the house knows whether it can
  reach you.

Location is used even when the app is closed or not in use; that is what
lets the house know you came home without you opening anything.

## Google Play services

Home presence uses Android's geofencing, and position sharing uses its
location service; both are provided by Google Play services, under
[Google's privacy policy](https://policies.google.com/privacy). Scanning
the configuration QR code uses Google's code scanner, which runs on the
phone.

## What is stored, and for how long

On the phone: the configuration (including the broker password), your
settings, and anything waiting to be sent while the app has no connection.
Uninstalling the app removes all of it.

On your house's server: what the app sends is kept and used by your
household's installation, under your household's control. The developer has
no access to it.

## Contact

Questions or concerns: open an issue at
<https://github.com/freol35241/homeostat-app/issues>.
