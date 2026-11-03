# Message delivery and reception metadata

Each outgoing message has a tappable status below its bubble. Open it for the acknowledgement
or failure detail and source radio. A confirmation belongs to that radio and message ID, so
simultaneous sessions cannot settle each other's sends. Status changes update the existing
message without creating a new conversation entry or notification.

| Status | Meaning |
| --- | --- |
| Sent to radio | Submitted to the phone-to-radio transport; no delivery confirmation yet |
| Sent | Radio accepted the send; acknowledgement may still be pending |
| Relayed | Meshtastic DM acknowledged by the mesh, but not by its recipient |
| Delivered to mesh | Meshtastic channel broadcast acknowledged by the mesh |
| Delivered | Direct message acknowledged by the recipient's radio |
| Unconfirmed | Acknowledgement timed out or tracking was interrupted |
| Failed | Radio rejected the send or returned a routing failure |

Delivery to a radio is not a read receipt. A channel acknowledgement cannot confirm reception
by every subscriber. MeshCore channel broadcasts have no delivery acknowledgements and remain
**Sent** after the radio accepts them. Direct messages use the expected ACK code from
`RESP_CODE_SENT` and `PUSH_CODE_SEND_CONFIRMED`; the detail includes the reported round-trip time.
The radio's suggested timeout is used, bounded to 1 second–10 minutes. Meshtastic requests ACKs
for channel messages and DMs, distinguishes transmit queue responses from routing ACK/NAKs,
and waits up to five minutes for confirmation. A late ACK can still settle a timed-out message
while the radio session's bounded delivery tracker retains it (256 messages).

Incoming bubbles show **RF** or **MQTT**, and **Direct · 0 hops**, **1 hop**, or the reported number
of hops. Meshtastic uses the packet's `via_mqtt` flag and valid `hop_start - hop_limit` metadata.
A missing original hop limit or an inconsistent hop limit is unknown, not zero. MeshCore's
companion messages arrive via RF; flood messages expose an encoded path hop count. Direct-route
MeshCore messages carry `0xff` instead of a receive hop count, so they show **Hops unknown**;
the saved outbound contact route is not used to guess it. Tap incoming metadata for signal
measurements, route type, and the receiving radio.

Metadata and delivery results persist in message history. Database versions 1–4 migrate without
removing messages or reactions. Existing messages without reception metadata show unknown
source/hops. Pending acknowledgements interrupted by an app restart become **Unconfirmed**;
confirmed results and MeshCore's unacknowledged channel-send status remain intact.

## Sending reactions

Long-press a Meshtastic message or tap **React**. The quick row offers 👍, 👎, 😂, 🔥, ❤️, and 😮.
**More emoji…** opens a larger grid and a field for another emoji from the phone keyboard.
Tap an existing chip to add the same reaction; long-press it for the participants and your
reaction's delivery status. Your reactions are highlighted. Failed/unconfirmed attempts show
an exclamation mark and can be retried. A retry replaces the displayed attempt without
increasing your count. Existing successful/pending reactions cannot be sent again.

Reactions use `TEXT_MESSAGE_APP` with `reply_id` set to the original unsigned packet ID,
`emoji = 1`, the UTF-8 emoji payload, and an ACK request. A channel reaction goes to the original
channel; a DM reaction goes to the conversation partner on its recorded channel. Older DM
records without channel metadata use the existing primary-channel default. Both use the original receiving/sending
radio, including when another outbound radio is selected. A disconnected source, unknown legacy
source, disabled original channel, or invalid packet ID cannot silently redirect a reaction.
Reactions and their delivery results use the existing persistent history and ACK tracker.
Database version 5 retains each Meshtastic message's channel index, including direct messages.

MeshCore companion messages do not expose this native reaction format. The reaction controls
are available for Meshtastic messages with a connected source radio. This does not add cross-mesh
forwarding or send a plain emoji message in place of a native reaction. Reaction removal is not
implemented.

## References inspected

Implementation follows the public clients and companion protocol without adding retransmissions:

- [Meshtastic Android native reaction sending](https://github.com/meshtastic/Meshtastic-Android/blob/c0c7aa3e30a4cd899a5b676c6d9178bbe95d1e83/core/service/src/commonMain/kotlin/org/meshtastic/core/service/MessagingControllerImpl.kt)
- [Meshtastic Android ACK/NAK handling](https://github.com/meshtastic/Meshtastic-Android/blob/c0c7aa3e30a4cd899a5b676c6d9178bbe95d1e83/core/data/src/commonMain/kotlin/org/meshtastic/core/data/manager/MeshDataHandlerImpl.kt)
- [Meshtastic Android message metadata display](https://github.com/meshtastic/Meshtastic-Android/blob/c0c7aa3e30a4cd899a5b676c6d9178bbe95d1e83/feature/messaging/src/commonMain/kotlin/org/meshtastic/feature/messaging/component/MessageItem.kt)
- [MeshCore author's public web client's send/ACK flow](https://github.com/liamcottle/meshcore-web/blob/ea624cf7b0977cb1120ba506ea182c01ca76a928/src/js/Connection.js)
- [MeshCore companion firmware responses and message metadata](https://github.com/meshcore-dev/MeshCore/blob/81dbae592ffad40b4212bc0b37e82b38da08f4bc/examples/companion_radio/MyMesh.cpp)
- [MeshCore companion protocol](https://github.com/meshcore-dev/MeshCore/wiki/Companion-Radio-Protocol)
