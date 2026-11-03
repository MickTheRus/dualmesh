# Android Auto

DualMesh supplies these Android Auto functions:

1. Message notifications.
2. A message dashboard.
3. A node map for a private stationary head unit.

The dashboard uses AndroidX Car App Library `1.7.0`. It needs Car API level 7 or a newer level.
DualMesh does not support Apple CarPlay.

The phone **Settings > Phone layout** setting does not change Android Auto. Android Auto uses its own conversation
list and map.

## Message dashboard

`DualMeshCarAppService` supplies the MESSAGING and POI categories. It creates one
`ConversationItem` for each configured channel.

Example titles:

```text
MeshCore / field-radio-a / #0 General
MeshCore / field-radio-a / #1 Team
Meshtastic / gateway-b / #0 General
Meshtastic / handheld-c / #1 Team
```

All values in this document are test values.

A direct-message conversation appears after the app sends or receives a direct message. Start a
new direct message on the phone. You can then use that conversation in Android Auto.

Each conversation has these functions:

- Show a maximum of five messages.
- Keep the network, radio, and channel identity.
- Use **Play aloud**.
- Use **Reply**.
- Use **Mark read**.
- Send a reply through the radio that owns the conversation.

The Car App Library does not permit an empty message list. DualMesh adds this read placeholder to
an empty channel:

```text
No messages received on this channel yet
```

A Meshtastic reaction does not appear as a separate car message.

## Node map

Select **Nodes** to open `MapWithContentTemplate`. Android Auto supplies the draw surface. DualMesh
draws a MapLibre and OpenStreetMap view on that surface.

The overlay shows these values:

- radio name
- mesh technology
- saved node count
- position count.

Use **Previous radio** and **Next radio** to change the map. This action does not change the phone
send radio.

The host can send pan, fling, scale, and tap events to the map. The app combines rapid node updates
into fewer map redraw operations.

The app releases the map surface, presentation, and virtual display when the car session ends.

WARNING: This map is for a stationary, user-controlled head unit. It is not an in-motion driver
interface.

## Manifest data

The app declares these permissions and components:

```xml
<manifest>
    <uses-permission android:name="androidx.car.app.ACCESS_SURFACE" />
    <uses-permission android:name="androidx.car.app.MAP_TEMPLATES" />

    <application>
        <meta-data
            android:name="com.google.android.gms.car.application"
            android:resource="@xml/automotive_app_desc" />
        <meta-data
            android:name="androidx.car.app.minCarApiLevel"
            android:value="7" />

        <service
            android:name=".car.DualMeshCarAppService"
            android:exported="true"
            android:intentMatchingFlags="allowNullAction">
            <intent-filter>
                <action android:name="androidx.car.app.CarAppService" />
                <category android:name="androidx.car.app.category.MESSAGING" />
                <category android:name="androidx.car.app.category.POI" />
            </intent-filter>
        </service>
    </application>
</manifest>
```

`res/xml/automotive_app_desc.xml` enables `notification` and `template`.

Debug builds accept all hosts. Release builds use the AndroidX sample host list. Review the host
list before a release.

## Standard phone setup

1. Install DualMesh.
2. Give the app access to Nearby devices and notifications.
3. Open Android Auto settings.
4. Select the Android Auto version entry many times.
5. Make sure that developer mode is on.
6. Open **Developer settings**.
7. Enable **Unknown sources**.
8. Open **Customize launcher**.
9. Make sure that DualMesh is enabled.
10. Open DualMesh.
11. Connect each radio one time.

Android Auto can hide a private app. This result depends on the Android Auto version and its host
validation rules.

## Private root setup

Use this procedure only on a user-controlled test phone.

The test device needs these items:

- Android 9 or a newer version
- a root-capable ADB shell
- Android Auto developer mode
- an Android Auto host with Car API level 7 or newer.

Enable root ADB in Android developer options. Then reconnect ADB.

Check root access:

```shell
adb root
adb shell id
```

The result must include:

```text
uid=0(root)
```

Install the APK with the Google Play installer name:

```shell
adb install -r -i com.android.vending \
  app/build/outputs/apk/debug/app-debug.apk
```

Check the installer and service:

```shell
adb shell pm list packages -i | grep io.dualmesh.app
adb shell cmd package query-services --brief \
  -a androidx.car.app.CarAppService | grep io.dualmesh.app
```

The result must include:

```text
package:io.dualmesh.app installer=com.android.vending
io.dualmesh.app/.car.DualMeshCarAppService
```

Close and open Android Auto settings. Then examine **Customize launcher**.

The test did not need these changes:

- Magisk, KernelSU, APatch, or LSPosed
- AA AIO Tweaker or AA Tweaker
- changes to Android Auto APK files
- changes to Google Play Services APK files
- changes to Google Play Services databases.

Do not apply old database changes from an online procedure. Such changes can damage Google Play
Services data.

The installer-name method is not a supported distribution method. Android Auto can remove this
behavior in a new version.

## Desktop Head Unit

Install the Desktop Head Unit:

```shell
sdkmanager "extras;google;auto"
```

On the phone, open the Android Auto developer menu. Select **Start head unit server**.

Run:

```shell
adb forward tcp:5277 tcp:5277
cd "$ANDROID_SDK_ROOT/extras/google/auto"
./desktop-head-unit --adb=5277
```

For a server without a display, run:

```shell
SDL_VIDEODRIVER=dummy SDL_AUDIODRIVER=dummy \
  ./desktop-head-unit --headless --adb=5277
```

The prompt accepts these commands:

```text
keycode home
tap 42 440
screenshot /tmp/android-auto.png
quit
```

Some Linux systems need `libc++.so.1` and `libc++abi.so.1`. Install the `libc++1` and `libc++abi1`
packages from the Linux distribution.

## Head-unit test

1. Complete the Android Auto welcome procedure.
2. Give Android Auto notification access.
3. Open the car app launcher.
4. Open DualMesh.
5. Test an empty channel.
6. Test a channel with messages.
7. Test a direct-message conversation.
8. Open **Nodes**.
9. Select each radio.
10. Compare the node counts and markers.
11. Return to messages.
12. Make sure that the app releases the map display.
13. Examine logcat for `FATAL EXCEPTION`.

Stop the server and remove the port forward:

```shell
adb shell am stopservice \
  -n com.google.android.projection.gearhead/.companion.DeveloperHeadUnitNetworkService
adb forward --remove tcp:5277
```

## Fault isolation

### DualMesh is not in Customize launcher

1. Make sure that Android Auto developer mode is on.
2. Make sure that **Unknown sources** is on.
3. Query the `CarAppService`.
4. Make sure that `automotive_app_desc.xml` contains `template`.
5. Install the APK again with the private root command.
6. Restart Android Auto settings.

Do not change Google Play Services databases before you complete these checks.

### Android Auto shows an unexpected error

Collect a new log:

```shell
adb logcat -c
# Open DualMesh on the car display.
adb logcat -d | grep -E \
  'io.dualmesh.app|CarAppService|CarApp.H.Tem|FATAL EXCEPTION'
```

This exception means that a conversation had no message:

```text
java.lang.IllegalStateException: Message list cannot be empty.
```

The app now supplies a read placeholder for an empty channel.

### Radios do not connect

Examine the DualMesh foreground notification. Open DualMesh one time if Android stopped the
device-start service.

**Disconnect** disables the target. Connect the target again to enable it.

Saved messages stay available while the radio reconnects.

### Voice input does not work

Examine Google Assistant, microphone access, network access, and the host microphone. A headless
Desktop Head Unit can have no audio input.

## References

- [Android Auto message apps](https://developer.android.com/training/cars/communication/templated-messaging)
- [Desktop Head Unit tests](https://developer.android.com/training/cars/testing/dhu)
- [AndroidX Car App releases](https://developer.android.com/jetpack/androidx/releases/car-app)
- [ConversationItem](https://developer.android.com/reference/androidx/car/app/messaging/model/ConversationItem)
- [ConversationCallback](https://developer.android.com/reference/androidx/car/app/messaging/model/ConversationCallback)
- [CarAppService](https://developer.android.com/reference/androidx/car/app/CarAppService)
