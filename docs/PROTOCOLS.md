# Protocol design

## Design limit

DualMesh uses one user interface for two protocol backends. It is not a protocol converter. It
does not forward messages between the two mesh technologies.

```text
                    +----------------------+
                    | Phone user interface |
                    | Android Auto         |
                    | Notifications        |
                    +----------+-----------+
                               |
                       DualMeshManager
                    +----------+----------+
                    |                     |
              SQLite history       MultiDeviceBackend
                                          |
                              One backend for each radio
```

`UnifiedMessage` contains these fields:

- network
- radio ID and radio name
- conversation ID
- sender
- text
- time
- direction
- delivery state
- optional reaction emoji
- optional related message ID.

A conversation key has these parts:

```text
(network, device_id, conversation_id)
```

The key keeps equal channel numbers on different radios separate.

## Main source files

| Function | Source file |
| --- | --- |
| App data control | `DualMeshManager.kt` |
| Multiple radio control | `backend/MultiDeviceBackend.kt` |
| Data models | `model/Models.kt` |
| MeshCore connection | `backend/MeshCoreBackend.kt` |
| MeshCore protocol | `protocol/MeshCoreProtocol.kt` |
| Meshtastic connection | `backend/MeshtasticBackend.kt` |
| Meshtastic TCP frames | `protocol/MeshtasticStreamCodec.kt` |
| Shared companion TCP socket | `transport/MeshtasticTcpSession.kt` |
| Meshtastic text metadata | `protocol/MeshtasticTextMetadata.kt` |
| Message database | `history/MessageHistoryStore.kt` |
| Node database | `history/NodeHistoryStore.kt` |
| Traceroute database | `history/TraceRouteHistoryStore.kt` |
| Packet logs | `logging/PacketLogger.kt` |
| Android Auto messages | `car/DualMeshCarAppService.kt` |
| Android Auto map | `car/CarNodeMap.kt` |

## MeshCore

### BLE service

MeshCore uses these BLE UUIDs:

- Service: `BleRadioScanner.MESHCORE_SERVICE`
- App to radio: `6e400002-b5a3-f393-e0a9-e50e24dcca9e`
- Radio to app: `6e400003-b5a3-f393-e0a9-e50e24dcca9e`

### Connection sequence

1. Connect to GATT.
2. Enable radio notifications.
3. Send the companion `appStart` command.
4. Read the local radio data.
5. Read the protocol version and channel capacity.
6. Read each channel slot.
7. Read the contact list.
8. Set the radio clock.
9. Read queued messages until the queue is empty.

If the radio does not support the new device command, the app uses eight channel slots. A timeout
stops an unsupported request.

### Messages

- The app permits a maximum of 127 UTF-8 bytes in a MeshCore message.
- The app reads legacy and version 3 channel and contact frames.
- A direct message uses `CMD_SEND_TXT_MSG`.
- The destination is the first six bytes of the contact public key.
- The app uses the contact list to resolve an incoming key prefix.
- A raw key prefix stays available when the contact list has no match.
- A stable message ID prevents duplicate messages after a queue replay.
- `CMD_SYNC_NEXT_MESSAGE` reads one queued message.
- The app sends the next queue request after each queued message.
- `PUSH_CODE_MSG_WAITING` also starts the next queue request.

### Traceroute

- `RESP_CODE_CONTACT` supplies the saved path, path length, and path-hash size.
- `CMD_SEND_TRACE_PATH` starts a trace with a random tag.
- `PUSH_CODE_TRACE_DATA` returns the same tag, path hashes, and SNR values.
- A contact with no saved path uses the first byte of its public-key hash as the direct trace path.
- A contact with a saved path adds the contact hash as the turn-around hop and mirrors the saved
  path for the return.
- The app supports 1-byte, 2-byte, and 4-byte trace hashes.
- The app shortens a saved 3-byte path hash to 2 bytes because the trace flag does not define a
  3-byte size.
- The app correlates each result with its tag and the radio that sent it.
- A 60-second timer marks an unanswered request as timed out.

## Meshtastic

### Transports

Meshtastic can use BLE, USB serial, or the TCP Stream API. USB and TCP share the Meshtastic stream framing.

TCP input has one of these forms:

```text
mesh-radio.local
192.0.2.10
192.0.2.10:5500
[fe80::1]:4403
```

`192.0.2.10` is a documentation address. Port `4403` is the default.

The TCP frame starts with bytes `0x94 0xc3`. A two-byte big-endian value gives the payload length.
The decoder accepts split input and wake bytes. It finds the next header after invalid input.

### Connection sequence

1. Connect to BLE, USB serial, or TCP.
2. Create a nonzero random configuration ID.
3. Send the ID in `ToRadio.want_config_id`.
4. Read `FromRadio` configuration records.
5. Save local-node data.
6. Save node, position, and channel data.
7. Wait for the matching configuration-complete ID.
8. Set the backend state to connected.
9. Continue to read `FromRadio.packet` data.

### MQTT client proxy

After configuration completes, each Meshtastic backend starts its own MQTT client when
`moduleConfig.mqtt.enabled` and `proxy_to_client_enabled` are both true. It uses the radio's
broker, credentials, TLS setting, and root topic. It subscribes to binary topics for enabled
downlink channels, JSON topics when JSON is enabled, and the binary PKI topic. Blank channel
names resolve from the radio's LoRa modem preset, using firmware topic names.

`FromRadio.mqttClientProxyMessage` requests are published with QoS 1 and their retain flag.
Binary downlinks are returned in `ToRadio.mqttClientProxyMessage.data_`; JSON downlinks use
`text`. The firmware processes these envelopes and decides whether to transmit on RF. Own
uplinks of other nodes' packets and packets claiming our node ID through another gateway are
filtered; our own origin returning through our gateway is preserved for the firmware's
implicit acknowledgement. Payloadless non-PKI downlinks are discarded unless from our gateway.

MQTT uses clean sessions and bounded in-memory work. Oversized downlinks, including topic and
protobuf overhead beyond the 512-byte radio API limit, are discarded. Downlinks queued before
a stop, reconnect, or settings replacement cannot enter the new radio session. Broker loss
retries with backoff and restores subscriptions; radio disconnection tears down the broker.
Broker errors appear per radio in Manage and do not change the radio connection state.

The app does not use value `69420`. Meshtastic reserves this value for
`NODELESS_WANT_CONFIG_ID`.

### Messages

- `TEXT_MESSAGE_APP` supplies text messages and reactions.
- `MeshPacket.channel` gives the channel index.
- A broadcast packet uses a `channel:<index>` conversation.
- A direct packet uses a `contact:<node-id>` conversation.
- A sent direct message uses channel 0 and requests an ACK.
- A sent channel message uses a random nonzero packet ID and requests a mesh ACK.
- Queue status and routing ACK/NAKs update the same stored message. See [message delivery](MESSAGE_DELIVERY.md) for status semantics and receive metadata.
- The app permits a maximum message size of 220 bytes.
- The backend also checks `Constants.DATA_PAYLOAD_LEN`.

A text packet is a reaction only when `Data.reply_id` and `Data.emoji` are not zero. The UTF-8
payload contains the reaction. The app saves the related packet ID. Sending uses the same
fields with emoji indicator 1 and the original radio/channel or DM conversation partner.
See [message reactions](MESSAGE_DELIVERY.md#sending-reactions) for picker and retry behavior.

The user interface puts a reaction chip below the related message. It removes a duplicate reaction
from the same sender. It shows a count when more than one sender uses the same reaction.

If the related message is not in the saved history, the reaction stays as a separate message. This
rule prevents data loss. An emoji without the reaction fields stays a normal message.

Android Auto does not show a reaction as a separate car message. A phone notification identifies
the sender and reaction.

### Traceroute

- The request uses `TRACEROUTE_APP`, `want_response`, and a random packet ID.
- The request uses the channel index saved for the destination node.
- A response uses `RouteDiscovery`.
- `Data.request_id` correlates the response with the request.
- Forward and return node numbers stay separate.
- `snr_towards` and `snr_back` use quarter-dB units.
- A 60-second timer marks an unanswered request as timed out.

### Nodes and positions

- `NODEINFO_APP` updates node identity.
- `POSITION_APP` updates node position.
- Configuration `NodeInfo.position` also updates node position.
- Meshtastic latitude and longitude use a `1e-7` degree scale.
- The app saves altitude, position time, precision, SNR, RSSI, hops, role, and hardware.
- The app saves the MQTT source state when the packet supplies it.
- The local node is not a direct-message contact.

## Connection restore

The app saves BLE addresses, TCP endpoints, and USB identities for both protocols. It saves a maximum of 12 known targets
for each technology. It connects a maximum of four targets for each technology.

An unexpected disconnect uses these retry delays:

```text
1, 2, 5, 10, 30 seconds
```

A successful connection resets the delay. The connection-manager checkbox disables one target. **Disconnect** disables
all targets for that technology.

`BootReceiver` restores enabled profiles after device start or app replacement.
`MeshConnectionService` keeps active connections in a foreground service.

## Message storage

`MessageHistoryStore` saves the newest 5,000 messages. The primary key is:

```text
(network, device_id, message_id)
```

Database version 2 added the radio ID. Database version 3 added reaction fields. Version 4 adds
delivery detail, receive hop count, RF/MQTT source, signal readings, and route type. Version 5
retains the per-message channel index used for reaction replies, including DMs. All migrations
keep old message rows.

The app loads history before it saves new backend messages. A history load does not create a new
message notification.

## Node storage

`NodeHistoryStore` uses this key:

```text
(network, device_id, node_id)
```

It stores the latest node data and a maximum of 20,000 position records. Different radios do not
share node rows.

## Traceroute storage

`TraceRouteHistoryStore` saves the newest 1,000 traceroute results. The primary key is:

```text
(network, device_id, trace_id)
```

The `trace_runs` table stores request state and target data. The `trace_hops` table stores ordered
forward and return hops with optional SNR values. The app replaces a pending row when its response,
timeout, or connection error arrives. It changes pending rows to failed rows after an app restart.

## Packet-log limit

The packet logger records data at the companion API boundary:

- MeshCore commands and responses
- Meshtastic BLE protobuf payloads
- Meshtastic TCP protobuf payloads without the TCP frame.

The logger is not an LoRa RF receiver. It cannot record a packet that the radio does not send to
the app.

The private SQLite index has a size limit. The JSONL file continues until the user stops the log.
Read [PACKET_LOGGING.md](PACKET_LOGGING.md).

When packet logging is enabled, it records traceroute requests and responses at the same companion
API boundary. The log can contain route hashes, node numbers, and signal data.

## Connection manager and stream transports

The [radio connection manager](RADIO_CONNECTIONS.md) filters saved/discovered radios by transport
and protocol and keeps enabled radios independent of the selected outbound radio. The retry
controller re-arms when an attempt fails without a new StateFlow emission.

MeshCore USB/TCP uses host-to-radio `0x3c` plus a 16-bit little-endian length and response marker
`0x3e` with the same length format. BLE carries unwrapped companion frames. Meshtastic serial
and TCP use `0x94 0xc3` plus a 16-bit big-endian length and the standard wake sequence. Parsers
buffer fragmented reads and bound payload sizes. Each source radio keeps its own backend,
settings/action queues, framing, connection epoch, and packet-log label.
