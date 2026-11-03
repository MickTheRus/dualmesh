# Privacy and security

DualMesh stores mesh messages, radio settings, node IDs, and locations. Some of this data is
private.

## Network connections

| Connection | Destination | Function |
| --- | --- | --- |
| MeshCore BLE | Selected companion radio | Commands, messages, contacts, and adverts |
| Meshtastic BLE | Selected Meshtastic radio | `ToRadio` and `FromRadio` API |
| Meshtastic TCP | Endpoint that the user enters | TCP Stream API, usually port `4403` |
| Meshtastic MQTT proxy | Broker configured on each radio | MQTT uplink and downlink over the phone's Internet connection |
| Map tiles | OpenStreetMap servers | Map background |
| Android Auto | Connected Android Auto host | Templates and map pixels |

DualMesh does not require an app account. It does not use a DualMesh cloud service. It does not
forward traffic between MeshCore and Meshtastic.

A map request gives the tile provider the public IP address and the requested map area. Do not open
the map if this data must stay private.

The Meshtastic TCP connection does not add TLS. Use it on a trusted network or through a trusted
VPN. Bluetooth protection depends on Android, the radio, and the radio settings.

When a radio enables MQTT and its client proxy, DualMesh connects to that radio's configured
broker automatically. The broker receives your public IP address, credentials, topics, and
published payloads. Radio encryption and channel uplink/downlink settings still apply. Custom
brokers use the radio's TLS setting or an explicit secure URI (`ssl://`, `mqtts://`, or `wss://`).
TLS uses the system trust store and verifies the broker hostname. An empty broker address uses
the Meshtastic public broker and its well-known credentials over TLS.

MQTT credentials are held in connection memory. Optional packet captures include the radio's
MQTT configuration and proxy envelopes, so captured bytes can contain broker passwords and
message data even though packet summaries omit these values.

## Data on the phone

The radio-settings editor keeps configuration records, credentials, session passkeys, and drafts
in connection memory. It does not persist configuration drafts or reveal secrets by default.
Settings apply only when **Save** is selected. Protocol logging, when enabled, can
capture configuration and admin payloads including credentials and key material.

DualMesh stores these items:

- BLE addresses and TCP endpoints
- selected radios and conversations
- phone layout settings
- a maximum of 5,000 messages in `dualmesh_messages.db`
- node data and a maximum of 20,000 positions in `dualmesh_nodes.db`
- a maximum of 20,000 packet index rows in `dualmesh_packets.db`
- packet-log settings
- JSONL packet logs in `Downloads/DualMesh/`.

SQLite files and preferences are private app data. JSONL files in Downloads are user files. Other
apps can read these files if Android gives them the necessary file access.

The manifest permits Android backup. Android can restore private app data after an uninstall and
reinstall. An uninstall does not remove files from Downloads.

## Packet-log risk

WARNING: A packet log can contain these items:

- public and private messages
- node IDs and contact keys
- radio names and endpoints
- channel settings and encryption material
- locations and telemetry
- timestamps and signal data.

Treat a packet log as a credential file.

The **Clear all** control removes only the private packet index. It does not remove JSONL files.

## Prepare a packet log for publication

1. Make a copy of the file.
2. Keep the source file private.
3. Remove all message payloads.
4. Remove all channel keys and configuration keys.
5. Replace node IDs, contact keys, radio names, and endpoints.
6. Remove or reduce the precision of coordinates.
7. Replace exact times.
8. Examine the summary text.
9. Examine the hexadecimal payload.
10. Parse each JSONL line after the changes.
11. Make sure that each payload length is correct.

## Notifications and Android Auto

An incoming message can appear in a notification or on an Android Auto host. Other persons can see
or hear the message preview, sender, title, or voice output.

The phone draws the stationary map on a surface from the host. The host receives the map pixels and
overlay data. Do not connect an untrusted host.

Debug builds accept all Android Auto hosts. Use this setting only for development and private
tests. A release build needs an approved host list and a distribution review.

## Permissions

DualMesh requests these permissions:

- Bluetooth scan and connect
- old Android Bluetooth and location compatibility
- Internet access
- notification access
- foreground service access
- device-start access
- Android Auto map and surface access.

On supported Android versions, `BLUETOOTH_SCAN` uses `neverForLocation`. DualMesh does not use the
phone GPS position as a node position.

## Remove local data

1. Disable packet logs.
2. Remove JSONL files from `Downloads/DualMesh/`.
3. Select **Clear all** in the packet viewer.
4. Use Android **Clear storage** to remove private app data.

An uninstall removes private app data during the normal Android app process. Android backup can
restore the data. An uninstall does not remove JSONL files from Downloads.

The app does not have one control that removes all messages, nodes, and profiles.

## Public test data

Do not put live deployment data in public documents or test files.

Use these test values:

- radio names such as `field-radio-a`
- contact names such as `Example Node`
- IPv4 addresses from `192.0.2.0/24`
- local test MAC addresses such as `02:00:00:00:00:01`
- test node IDs, messages, coordinates, packet IDs, and counts
- general topology text such as `one BLE session and one TCP session`.

Before publication, search for host names, IP addresses, BLE addresses, node IDs, channel names,
coordinates, messages, screenshots, counts, and device details.
