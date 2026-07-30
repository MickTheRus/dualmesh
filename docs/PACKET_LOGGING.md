# Packet logs

DualMesh can record MeshCore and Meshtastic protocol traffic. Packet logs are off by default.

## Recorded data

| Network and transport | Record |
| --- | --- |
| MeshCore BLE | One complete command, response, or push frame |
| Meshtastic BLE | One complete `ToRadio` or `FromRadio` payload |
| Meshtastic TCP | One complete payload without its Stream API frame |

The app records companion API traffic. It does not record raw LoRa RF traffic, Bluetooth
link-layer traffic, IP packets, or TCP packets.

## Start a packet log

1. Select **Packets**.
2. Enable Meshtastic, MeshCore, or both networks.
3. Connect a radio or wait for traffic.
4. Select a row to see its full hexadecimal payload.
5. Disable the network to stop its log.

The network pane shows `REC` while its log is on. The app saves this setting. A background
connection can continue the log after an app restart.

## JSONL files

Android 10 and newer versions use this directory:

```text
/storage/emulated/0/Download/DualMesh/
```

The file name has this form:

```text
DualMesh-packets-YYYYMMDD-HHMMSS.jsonl
```

Android 8 and Android 9 use the app external Documents directory.

The first line identifies the file format:

```json
{"format":"dualmesh-packet-log","version":1,"created_utc":"2024-03-09T16:00:00Z"}
```

Each other line contains one API frame:

```json
{"timestamp_ms":1710000000000,"timestamp_utc":"2024-03-09T16:00:00.000Z","network":"MESHCORE","direction":"TX","transport":"BLE example-radio","summary":"CMD_APP_START","length":17,"payload_hex":"01010000000000004475616c4d65736800"}
```

Fields:

- `timestamp_ms`: Phone time in milliseconds.
- `timestamp_utc`: Phone time in UTC.
- `network`: `MESHCORE` or `MESHTASTIC`.
- `direction`: `RX` is radio to app. `TX` is app to radio.
- `transport`: Transport type and radio label.
- `summary`: Frame type and route data when the app can decode them.
- `length`: Payload length in bytes.
- `payload_hex`: Complete payload as hexadecimal text.

The app writes each line immediately. A process stop can remove the final incomplete line. All
complete lines stay valid.

The SQLite viewer keeps a maximum of 20,000 records. It shows the newest 1,000 records. The
**Clear all** control removes only the SQLite records. It does not remove JSONL files.

## Remove packet logs

1. Disable both packet logs.
2. Select **Clear all** to remove the SQLite viewer records.
3. Use a file manager to remove JSONL files from `Downloads/DualMesh/`.

## Security

WARNING: A packet log can contain these items:

- message text
- node IDs and contact keys
- locations and telemetry
- channel settings
- encryption keys.

Do not publish a packet log before you inspect and sanitize it. Redaction of the summary is not
sufficient. The hexadecimal payload can contain the same private data.

Read [PRIVACY_AND_SECURITY.md](PRIVACY_AND_SECURITY.md) before you share a packet log.
