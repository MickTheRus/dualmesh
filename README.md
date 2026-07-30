# DualMesh

DualMesh is an experimental Android message app. It connects to MeshCore companion radios and
Meshtastic nodes.

DualMesh keeps the two mesh protocols separate. It does not forward traffic from one mesh to the
other mesh.

## Screenshots

The screenshots contain test data. They do not contain data from a real deployment.

<p>
  <img src="docs/screenshots/tablet-dual-pane-redacted.png"
       alt="DualMesh tablet with Meshtastic and MeshCore side by side" width="860">
</p>
<p>
  <img src="docs/screenshots/phone-inbox-redacted.png" alt="DualMesh phone inbox" width="280">
  <img src="docs/screenshots/phone-node-map-redacted.png" alt="DualMesh phone node map" width="280">
</p>
<p>
  <img src="docs/screenshots/android-auto-messages-redacted.png"
       alt="DualMesh Android Auto messages" width="420">
  <img src="docs/screenshots/android-auto-node-map-redacted.png"
       alt="DualMesh Android Auto node map" width="420">
</p>

## Quick start

1. Install the debug APK.
2. Give the app access to Nearby devices and notifications.
3. Select **Manage** in the applicable network pane.
4. Add a BLE radio.
5. For Meshtastic TCP, enter a host name or `host:port`.
6. If you do not enter a port, the app uses port `4403`.
7. Select a radio.
8. Select one of its channels or direct-message conversations.
9. Select **Nodes / Map** to see the node directory.

Enable **Packets** only when you must record protocol traffic.

WARNING: A packet log can contain keys, messages, node IDs, and locations.

## Functions

- Connect a maximum of four radios for each mesh technology.
- Keep each radio, channel, contact, message, and map in its own data scope.
- Connect to MeshCore with BLE.
- Connect to Meshtastic with BLE or TCP.
- Find the channels that each radio supplies.
- Send and receive channel messages.
- Send and receive direct messages.
- Show Meshtastic reactions below the related message.
- Save the newest 5,000 messages in SQLite.
- Save node details and the latest node positions for each radio.
- Restore enabled radio connections after an app restart or device restart.
- Record optional protocol logs in JSONL files.
- Show one network or two stacked networks on a phone.
- Show both networks side by side on a tablet.
- Show message conversations in Android Auto.
- Show a node map on a private, stationary Android Auto head unit.

## Limits

- A radio can discard a queued packet before DualMesh receives it.
- Meshtastic keeps its normal app queue in node RAM.
- A node restart or deep sleep can remove queued Meshtastic packets.
- A sent state only means that the app gave the message to the radio.
- The app does not show a final mesh delivery receipt.
- Android or the device vendor can stop background start operations.
- Open DualMesh if Android does not restore the service after device start.
- The app saves a maximum of 12 known targets for each mesh technology.
- The app permits a maximum of four active targets for each mesh technology.
- A node map shows only positions that the mesh supplies.
- Map tiles require an Internet connection.
- The app does not include offline map files.
- Android Auto voice input depends on the phone, host, account, and microphone.
- The private Android Auto setup is not a Google Play distribution method.
- The Android Auto node map is not an in-motion driver interface.

## Build

Install these tools:

- JDK 21
- Android SDK 37
- Android platform tools

Run:

```shell
export JAVA_HOME=/path/to/jdk-21
export PATH="$JAVA_HOME/bin:$PATH"

./gradlew testDebugUnitTest assembleDebug lintDebug
```

The build creates this file:

```text
app/build/outputs/apk/debug/app-debug.apk
```

Install the APK:

```shell
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

A MeshCore radio must use companion firmware. A Meshtastic radio must supply its BLE service or
its TCP Stream API.

## Documents

- [Visual design](docs/DESIGN.md)
- [Protocol design](docs/PROTOCOLS.md)
- [Nodes, contacts, and maps](docs/NODES_AND_MAPS.md)
- [Packet logs](docs/PACKET_LOGGING.md)
- [Privacy and security](docs/PRIVACY_AND_SECURITY.md)
- [Android Auto](docs/ANDROID_AUTO.md)
- [Tests](docs/TESTING.md)

## Source projects

- [meshcore-dev/MeshCore](https://github.com/meshcore-dev/MeshCore)
- [zjs81/meshcore-open](https://github.com/zjs81/meshcore-open)
- [meshtastic/firmware](https://github.com/meshtastic/firmware)
- [meshtastic/Meshtastic-Android](https://github.com/meshtastic/Meshtastic-Android)

DualMesh uses the official `org.meshtastic:protobufs` Wire artifact. Refer to each source project
for its license and protocol documents.

## License

GPL-3.0-or-later.
