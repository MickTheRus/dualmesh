# Tests

## Automated tests

Run:

```shell
export JAVA_HOME=/path/to/jdk-21
export PATH="$JAVA_HOME/bin:$PATH"

./gradlew testDebugUnitTest assembleDebug lintDebug
```

The tests examine these functions:

- MeshCore protocol version selection.
- MeshCore channel-message encoding.
- MeshCore direct-message encoding.
- MeshCore contact-list parsing.
- MeshCore trace request encoding and multi-byte trace response parsing.
- Device-side traceroute database save, update, load, and interrupted-run handling.
- MeshCore legacy and version 3 message parsing.
- MeshCore channel and device capacity.
- Stable message IDs.
- Meshtastic queue/relay/recipient ACK distinctions, routing failures, late ACKs, timeout/reset,
  and per-radio delivery isolation.
- MeshCore send-command ownership, ACK-code matching, suggested timeouts,
  channel-send semantics, and stale timeout callbacks.
- Message hop metadata, signed MeshCore SNR, direct-route unknown hops, and delivery/metadata
  preservation across radio echoes.
- Device-side message history metadata round trips, delivery updates without duplicate rows,
  migrations from versions 1/2/3, and interrupted confirmation handling.
- 10,000 random invalid MeshCore frames.
- Meshtastic Stream API frames.
- Split Meshtastic frames and wake bytes.
- Meshtastic frame recovery after invalid data.
- Meshtastic TCP endpoint parsing.
- A local TCP socket in both directions for both companion protocols.
- MeshCore USB/TCP framing fragmentation, coalescing, invalid-length recovery and bounded payloads.
- Retry continuation when failures do not emit a new connection state, cancellation and backoff reset.
- USB serial identities across replug, multi-port isolation, missing-serial fallback and saved profiles.
- MQTT broker defaults, TLS and endpoint parsing, and per-channel downlink subscriptions.
- MQTT binary/JSON envelopes, retained flags, implicit ACKs, and radio API size limits.
- Local MQTT broker publishing, downlink, reconnect, session isolation, and teardown.
- Local radio settings commands and readback for both technologies, admin response matching,
  session keys, validation, negative ACKs, timeouts, and preserving unedited/unknown fields.
- Node actions: serialized MeshCore stats and advert ACKs, legacy/unsigned counters, signed
  radio metrics, Meshtastic local/remote telemetry correlation, routing failures, timeout/reset,
  and exact zero-hop/flood packets without broadcast ACKs or reply requests.
- Node list sorting with missing hop and signal values, favorites-first ordering, and favorites
  filtering scoped to network/source radio.
- MeshCore route encoding and capacity, capability checks, fresh contact preservation, ACK and
  readback matching, direct/flood behavior, discovery response matching and parsing, timeout/reset,
  rejection/mismatch handling, and refusing to truncate three-byte hashes for traceroute.
- Map filters across both technologies and source radios, unknown-name rules, MQTT and direct
  filters, last-heard boundaries, infrastructure roles, and coordinate validity.
- Connection-profile storage.
- Meshtastic reaction detection.
- Meshtastic reaction attachment and duplicate removal.

The lint task creates these files:

```text
app/build/reports/lint-results-debug.html
app/build/reports/lint-results-debug.sarif
```

Do not run `connectedDebugAndroidTest` on a phone that contains the user's DualMesh data. The
Android test task can uninstall the debug application after the test and erase its private
databases. Use a disposable emulator or test phone for instrumented tests. Use `adb install -r`
when replacing an APK on a phone whose local history must remain.

## Phone test

1. Install the APK.
2. Give the app access to Nearby devices and notifications.
3. Connect a MeshCore companion.
4. Make sure that its state is `Connected`.
5. Make sure that all configured MeshCore channels are present.
6. Connect a Meshtastic radio with BLE or TCP.
7. Make sure that all enabled Meshtastic channels are present.
8. Connect a second Meshtastic radio.
9. Make sure that the header shows two devices.
10. Use the **Radio** selector to change the selected radio.
11. Make sure that both radios stay connected.
12. Make sure that each radio shows its own channels and contacts.
13. Stop and restart the app.
14. Make sure that the enabled radios reconnect.
15. Make sure that the last selected radio returns.
16. Send a unique message on a non-primary test channel.
17. Repeat the send test for each radio.
18. Make sure that each message stays in the correct radio and channel.
19. Send a MeshCore direct message.
20. Send a Meshtastic direct message.
21. Make sure that each direct message stays in the correct conversation.
22. Receive a Meshtastic reaction.
23. Make sure that the reaction appears below its related message.
24. Send an emoji-only message without a reaction reference.
25. Make sure that the emoji stays as a normal message.
26. Put the app in the background.
27. Make sure that the foreground notification shows the radio count.
28. Stop one radio.
29. Restore that radio.
30. Make sure that its reconnect operation is independent.
31. Stop and restart the app after a unique message.
32. Make sure that the message returns without a duplicate.
33. Make sure that old history does not cause a new notification.

WARNING: A message on a real mesh channel is public to that channel. Use a test channel. Tell other
channel users before you transmit test data.

## Traceroute test

Use a private test mesh or coordinate the test with its users. A traceroute uses RF airtime.

1. Select one connected radio.
2. Open **Nodes / Map**.
3. Select **Trace** on one remote node.
4. Make sure that the row shows `Trace in progress`.
5. Make sure that a response changes the state to `Trace complete`.
6. Examine the forward route, return route, node names, and SNR values.
7. Select another connected radio for the same technology.
8. Run a trace and make sure that the result stays with that radio.
9. Disconnect a radio during a trace and make sure that the row shows `Trace failed`.
10. For Meshtastic, compare the result with the official app.
11. For MeshCore, test a contact with no saved repeater path and make sure that a direct trace is
    sent to that contact.
12. For MeshCore, test a repeater or room contact with a saved multi-hop path.
13. Enable packet logging and run one trace.
14. Make sure that the JSONL file contains the request and response summaries.
15. Stop and restart the app.
16. Open the same node and make sure that **History** contains the saved result.
17. Start a trace, stop the app before the response, and restart it.
18. Make sure that the interrupted trace is stored as failed.

Do not use repeated traceroutes as a load test on a shared mesh.

## Packet-log test

1. Open **Packets**.
2. Enable both networks.
3. Reconnect the radios.
4. Do not transmit during the initial capture.
5. Make sure that each session has a different label.
6. Make sure that a JSONL file is in `Downloads/DualMesh/`.
7. Parse each line as one JSON object.
8. Make sure that each payload byte has two hexadecimal characters.
9. Disable both logs.
10. Make sure that the file does not increase in size.
11. Make sure that the file stays in Downloads.

## Layout test

1. Open **View**.
2. Select **Focused**.
3. Change the selected network.
4. Stop and restart the app.
5. Make sure that the layout and network return.
6. Select **Stacked**.
7. Make sure that Meshtastic is above MeshCore.
8. Set the display width to 720 dp or more.
9. Make sure that both networks are side by side.
10. Restore the normal display size and density.

## Node test

1. Select **Nodes / Map** for each radio.
2. Use **Filters > Radio** to compare the node count with one radio, then select **All radios**.
3. Toggle MC and MT independently and compare the position count with the visible markers.
4. Examine marker names, node IDs, hop data, SNR, and coordinates.
5. Stop and restart the app.
6. Make sure that saved nodes appear while the radios reconnect.

## Android Auto test

Use the setup in [ANDROID_AUTO.md](ANDROID_AUTO.md).

1. Make sure that DualMesh is enabled in **Customize launcher**.
2. Open DualMesh in the Desktop Head Unit.
3. Make sure that the host shows a `ListTemplate`.
4. Make sure that an empty channel does not cause an error.
5. Connect MeshCore.
6. Make sure that its channels appear.
7. Connect Meshtastic.
8. Make sure that its channels appear.
9. Send a channel message.
10. Make sure that only the correct conversation changes.
11. Send a direct message.
12. Make sure that one contact conversation appears.
13. Test **Play aloud** and **Reply**.
14. Test **Mark read**.
15. Make sure that only the correct notification closes.
16. Receive a Meshtastic reaction.
17. Make sure that it does not appear as a separate car message.
18. Open **Nodes**.
19. Make sure that the first map uses the selected radio.
20. Select each other radio.
21. Make sure that the counts and markers change.
22. Return to messages.
23. Make sure that the app releases the map display.
24. Examine logcat for a fatal error or a rejected template.

Use these commands:

```shell
adb shell cmd package query-services --brief \
  -a androidx.car.app.CarAppService

adb logcat -d | grep -E \
  'io.dualmesh.app|CarAppService|CarApp.H.Tem|FATAL EXCEPTION'

adb shell dumpsys activity services io.dualmesh.app
adb shell dumpsys power | grep -E 'mWakefulness=|mStayOn='
```

## Test environment

Use test names and test addresses in all reports.

| Part | Test configuration |
| --- | --- |
| Android device | User-controlled Android test phone |
| MeshCore | One BLE companion with more than one channel |
| Meshtastic | One BLE session and one TCP session |
| Mesh | An isolated test mesh |
| Android Auto host | Desktop Head Unit 2.0 with Car API level 7 |
| Layouts | Phone and 2560 x 1600 test display |

The last hardware test gave these results:

- MeshCore BLE, Meshtastic BLE, and Meshtastic TCP connected at the same time.
- Each radio kept its own channel and contact data.
- Sends used a non-primary test channel.
- Messages stayed in the correct network, radio, and conversation.
- Saved profiles restored after app replacement.
- Direct-message contacts appeared for both protocols.
- Message history returned without duplicate notifications.
- New Meshtastic reactions appeared below the related message.
- Packet logs passed JSON and byte-length checks.
- Phone and tablet layouts operated correctly.
- Node data and positions returned after an app restart.
- The Android Auto map showed small and large node sets.
- The Android Auto reply panel opened.

The headless host had no microphone. Thus, the test did not examine speech input.

## Fixed Android Auto fault

The Car App Library does not permit an empty `ConversationItem` message list. An empty list caused
this exception:

```text
IllegalStateException: Message list cannot be empty
```

DualMesh now adds one read placeholder message to an empty channel. Always include an empty channel
in the Android Auto test.
