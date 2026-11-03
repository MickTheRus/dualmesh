# Radio settings

Open the **Settings** tab and select a connected radio. The editor is scoped to that radio's backend,
transport, and connection. Reading or editing another radio does not change the outbound
message target. This is local companion configuration, not mesh remote administration.

The Settings tab also contains **Phone layout** (Focused/Stacked) and **Packet logs**.

## Navigation and layout

Settings use compact preference rows. Tap a value to edit it; switches change the local draft.
The back gesture and toolbar go up one level: field editor, section, settings list, then the
main screen. Returning to the settings list preserves its search and scroll position. **Done**
accepts a field edit into the draft; **Save** writes the section to the radio.

## Read, edit, save

The first open reads configuration and caches it for that radio connection. Reopening the
editor reuses the cache or joins the read already in progress. Only **Refresh** starts another
full read; reopening after a failed read keeps the cached values and error without retrying.
Reconnecting clears this connection cache. Save still reads and verifies only the section being
edited (plus the Meshtastic admin session key). Sections are disabled
while reads or writes are pending. Drafts live only in the open editor and are not persisted.
Closing the editor does not cancel an already submitted save.

Each save validates all edits before sending a write. It then reads the target section again,
applies edited fields to that fresh record, waits for acknowledgement, and reads the section
back. Unedited fields are preserved. A stale draft must be reopened. A timeout or rejected
write stops subsequent writes; previously acknowledged changes are not rolled back. Refresh
after an unverified save. RF or Bluetooth changes may disconnect the radio before verification.

Secrets are masked and can be revealed explicitly. They are held in memory, not editor
preferences or saved-instance state. Optional protocol captures can still include them.

## Meshtastic

The editor uses firmware-reported Config, ModuleConfig, Channel, and owner records. It offers
booleans, numbers, enums, strings, Base64 byte fields, and repeated scalar fields (one per line).
Nested configuration messages are supported. Repeated structured records are read-only.
Identity editing is limited to long and short names; channel indexes cannot be changed.

Reads use ADMIN_APP requests addressed to the local node with hop limit zero. A fresh owner
request supplies the current session passkey before a write. Responses must match both the
local node number and outstanding request ID. Routing errors end the operation. Reads ignore
ordinary routing ACKs and require the requested admin response. Writes require a successful
routing ACK before readback. Unsupported reads do not fabricate default configuration.

The schema editor modifies protobuf fields while retaining all unedited and unknown fields.
Unsigned integers, packed/repeated values, enums, and nested fields retain their wire types.
Validation checks numeric parsing, finite values, owner/channel name lengths, channel PSK
lengths, hop limits, Bluetooth PIN range, and the radio payload limit.

## MeshCore

The editor reads SELF_INFO and DEVICE_INFO, channel records, tuning parameters, sensor/custom
variables, and supported automatic-contact configuration. It preserves the current client-repeat
flag when updating RF parameters. Privacy changes preserve unedited telemetry and reserved bits.
GPS and sensor controls are populated from the radio's reported custom variables.

MeshCore acknowledgements have no request IDs. Settings therefore use one outstanding command
at a time, after startup synchronization and pending traces finish. Message and trace sends
wait while settings run. Settings responses are consumed before the normal startup handler,
so an APP_START read does not restart contact/channel synchronization. Unsupported optional
reads are skipped. Write errors and timeouts stop the transaction and require a refresh.

## Sources reviewed

This implementation uses the protocol behavior documented in these source trees; it does not
embed or launch either external app:

- [Meshtastic Android admin controller](https://github.com/meshtastic/Meshtastic-Android/blob/b01d128/core/service/src/commonMain/kotlin/org/meshtastic/core/service/AdminControllerImpl.kt)
- [Meshtastic Android response validation](https://github.com/meshtastic/Meshtastic-Android/blob/b01d128/core/data/src/commonMain/kotlin/org/meshtastic/core/data/manager/AdminPacketHandlerImpl.kt)
- [MeshCore open client settings](https://github.com/zjs81/meshcore-open/blob/bfaa00b/documentation/settings.md)
- [MeshCore open client frame encoding](https://github.com/zjs81/meshcore-open/blob/bfaa00b/lib/connector/meshcore_protocol.dart)
- [MeshCore companion firmware](https://github.com/meshcore-dev/MeshCore/blob/81dbae5/examples/companion_radio/MyMesh.cpp)

## Validation

Unit tests exercise actual encoded command packets and simulated radio responses, without RF
traffic. They cover successful read/write/readback, preservation of credentials and unknown
fields, forged or stale admin responses, validation failures, negative acknowledgements,
timeouts, mismatched readback, and serial MeshCore command handling. Real radio changes should
be verified with deliberate settings edits on user-controlled hardware.
