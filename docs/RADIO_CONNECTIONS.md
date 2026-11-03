# Radio connections

Open **Radios** in the app header, or **Manage** in either network pane. The full-screen
connection manager stays open as radios connect, so multiple devices can be selected.

Filter by **All**, **Bluetooth**, **Wi-Fi / TCP**, or **USB / serial**, and by **Both meshes**,
**MC**, or **MT**. Search matches saved names and endpoints; USB serial numbers are searchable
when permission makes them available. Saved radios are kept separate from newly discovered
Bluetooth and attached USB devices.

## Picking radios

The checkbox enables a radio and automatic reconnect. Up to four radios per mesh can be enabled
at once; they receive independently. **Use for messages** selects the outbound radio for that
mesh without disconnecting the others. The connection manager shows each radio's state, last
error and next retry countdown. **Retry** reconnects one enabled radio immediately. Turning off
its checkbox disconnects it and disables automatic reconnect. **Forget** removes a disabled
saved connection; it does not delete saved messages, nodes, favorites or radio configuration.

## Transports

| Transport | MeshCore | Meshtastic |
| --- | --- | --- |
| Bluetooth | Companion NUS BLE | Meshtastic BLE, including bonding |
| Wi-Fi / TCP | Companion TCP framing, default port 5000 | Meshtastic stream framing, default port 4403 |
| USB / serial | Companion serial framing, 115200 baud | Meshtastic serial framing, 115200 baud |

**Scan Bluetooth** requests the Android Bluetooth permissions and shows nearby/bonded compatible
radios. **Add Wi-Fi radio** accepts a hostname, IPv4, or bracketed IPv6 with an optional port,
plus the firmware protocol and optional name. Wi-Fi endpoints are entered manually; DualMesh
does not scan the LAN for them. The radio must run compatible companion/TCP firmware.

For USB, attach a supported serial device to the **phone** using USB host/OTG. Choose the firmware
protocol, then approve Android's USB-access prompt. USB serial drivers support common CDC/ACM,
CP210x, FTDI, CH34x and related adapters. Bootloader/storage-only devices are not serial radios.
A USB port can belong to one mesh protocol at a time. USB serial is distinct from Bluetooth
Serial Port Profile; Classic Bluetooth SPP is not implemented.

A USB serial number, when available, supplies a stable connection identity across reattachment.
Without a serial number, the attached device path is used; if it changes, select the device again
instead of reconnecting an arbitrary adapter with matching vendor/product IDs. Ambiguous or
mismatched identities are rejected. Android may revoke USB permission on unplug; automatic
retries report that permission is needed and never repeatedly open permission dialogs. Tap
**Retry** or select the attached device to grant access again.

On Android 17+, Wi-Fi connections request local-network access when needed. Denied requests keep
the endpoint saved for another attempt. Background connected-device service support does not
depend on Bluetooth permission for a network/USB-only connection.

## Reconnect behavior

Enabled profiles are restored on startup. Failed/disconnected sessions retry after 1, 2, 5, 10,
then 30 seconds, retaining a 30-second maximum delay. A successful connection resets the delay.
The retry loop re-arms even when a failed attempt leaves the same error state. A Bluetooth
`connectGatt` failure that returns no session is reported immediately instead of leaving the UI
stuck at Connecting. Permission errors and unavailable devices remain visible per radio.

The connection manager does not change radio settings, select a mesh route, or send chat traffic.
Settings, node tools and messages continue to use their chosen source radio over each transport.
Packet logging remains opt-in and includes the transport and source radio label.

## Validation and references

Tests cover retry continuation without new state events, cancellation/reset, stream fragmentation,
invalid lengths, exact MeshCore framing over a real local TCP socket, IPv6/default ports, USB
identity and profile round trips. USB driver/permission behavior requires physical Android host
hardware testing.

- [MeshCore serial framing](https://github.com/meshcore-dev/MeshCore/blob/main/src/helpers/ArduinoSerialInterface.cpp)
- [USB serial drivers](https://github.com/mik3y/usb-serial-for-android)
- [Android local-network permission](https://developer.android.com/privacy-and-security/local-network-permission)
- [Connected-device foreground service requirements](https://developer.android.com/develop/background-work/services/fgs/service-types#connected-device)
