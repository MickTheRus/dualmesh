# Visual design

DualMesh uses a dark Android theme. The design uses a clear hierarchy, round shapes, and a small
set of accent colors. It does not copy iOS controls.

## Colors

| Name | Value | Function |
| --- | --- | --- |
| Deep space | `#040818` to `#02050E` | Background |
| Neon blue | `#45D8FF` | Meshtastic and selected controls |
| Neon violet | `#8B7CFF` | MeshCore and sent messages |
| Signal green | `#47F7B1` | Connected state |
| Warning amber | `#FFC857` | Connection work and privacy warnings |
| Signal red | `#FF6482` | Errors and active packet logs |
| Starlight | `#F2F7FF` and `#9FB3CE` | Main text and secondary text |

Cards use dark blue surfaces and thin blue borders. The packet-log panel uses an opaque surface.
This surface makes long text easy to read.

## Layout

- Persistent bottom tabs and each destination's heading provide navigation; there is no extra
  global logo/header above the content.
- Channels has one read-only radio/status line; connection actions are in Connections.
- The entire chat heading opens the channel/contact selector using a small chevron.
- Nodes, Map, Settings, and Connections each have their own destination.
- Settings contains layout preferences and packet logs; there is no separate View menu.
- Sent messages use a blue and violet gradient.
- Received messages use a dark blue surface.
- Reactions use small chips below the related message.
- Green, amber, and red show status only.

Phones have **Focused** and **Stacked** layouts. A display width of 720 dp or more uses the
two-pane tablet layout.

The node directory has its own tab, with full-height node details. The map uses markers colored
by mesh technology. The directory
shows node identity, signal data, route data, and position data.

## Android Auto

Android Auto controls the dashboard text, space, color, and template actions. DualMesh supplies
the conversation data and the launcher icon.

The private stationary setup uses a host map surface. It does not copy the phone layout.
DualMesh does not support Apple CarPlay.

## Source files

- Theme: `app/src/main/java/io/dualmesh/app/ui/DualMeshTheme.kt`
- App surfaces: `app/src/main/java/io/dualmesh/app/ui/DualMeshApp.kt`
- System bars: `app/src/main/res/values/styles.xml`
- Android 8.1 system bars: `app/src/main/res/values-v27/styles.xml`
- Launcher icon: `app/src/main/res/drawable/ic_launcher.xml`

## References

- [Apple Human Interface Guidelines](https://developer.apple.com/design/human-interface-guidelines)
- [Apple material guidance](https://developer.apple.com/design/human-interface-guidelines/materials)
- [Apple color guidance](https://developer.apple.com/design/human-interface-guidelines/color)

## Current screenshots

See the [gallery](SCREENSHOTS.md) for current phone/tablet layouts and reproducible, fictional-data
capture instructions. The screenshot build is isolated from the installed app and real radios.
