# Nodes, contacts, and maps

## Shared phone and tablet map

Open **Map** in the app header or **Nodes / Map** in a network pane. The map includes saved nodes
from both technologies and all radios. **Both**, **MC**, and **MT** select which networks appear.
MC markers are purple; MT markers are green. Tap a marker for its details and traceroute action.
**Fit nodes** frames the current results; incoming updates preserve the camera you chose.
**Nodes** opens the same filtered directory, including nodes without valid positions. **Show on
map** centers a positioned node. Traces always use that node's network and source radio.

**Filters** contains Include unknown, Exclude MQTT, Only show direct nodes, Hide offline nodes,
Hide unheard nodes, Exclude infrastructure, last-heard windows, individual roles, and radio
selection. It also controls node labels and dark street tiles. Filters persist across app
restarts and apply to both the directory and markers. Search uses the same filters.

An unknown MT node has no short name; an unknown MC node has no advertised name. Direct requires
a measured zero-hop route over RF; missing hop data is not treated as direct. MeshCore adverts
currently do not supply measured hop counts, so Only show direct nodes hides those records.
Offline means no last-heard update within two hours. Nodes without a recorded last-heard time
are hidden by both offline and explicit last-heard limits. Infrastructure includes routers,
repeaters, and room servers. Invalid coordinates and the (0, 0) placeholder stay off the map,
but their nodes remain searchable in the directory.

DualMesh keeps a separate node directory for each radio. This separation is necessary when radios
use different frequencies, presets, regions, or channels.

## Node identity

The database key has three parts:

```text
(network, device_id, node_id)
```

- `network` is `MESHCORE` or `MESHTASTIC`.
- `device_id` identifies the BLE radio or TCP endpoint.
- `node_id` is the contact key or node number from the protocol.

The radio is part of the key. Thus, equal node IDs on different meshes stay separate. A direct
message always uses the radio that owns the selected conversation.

## Data sources

| Technology | Initial source | Live source |
| --- | --- | --- |
| MeshCore | Companion contact list | Contact adverts |
| Meshtastic | `FromRadio.node_info` | `NODEINFO_APP` and `POSITION_APP` packets |

A node record can contain these fields:

- long name and short name
- public key or node number
- hardware model and role
- advert type and flags
- channel index
- MQTT source state
- direct-message permission
- last-heard time
- SNR, RSSI, and hop count
- latitude, longitude, altitude, time, and precision bits.

DualMesh does not create missing data. A node without a position stays in the directory. It does
not appear on the map.

## Data merge

Each protocol backend supplies a node list for one radio. `MultiDeviceBackend` adds the radio
identity. `DualMeshManager` merges the live list with the saved list.

The merge uses these rules:

- A blank name does not replace a known name.
- Missing data does not remove saved data.
- The newest last-heard time replaces an older time.
- A newer position replaces an older position.
- A replay updates the saved row.
- The local Meshtastic node stays available for map context.
- The local Meshtastic node is not a direct-message target.

The app writes node changes in sequence. It does not write unchanged rows. The Android Auto map
combines rapid updates into fewer redraw operations.

## Storage

`NodeHistoryStore` uses this private SQLite file:

```text
dualmesh_nodes.db
```

The `nodes` table stores the latest record. The `node_positions` table stores a maximum of 20,000
position records. The app shows the latest position.

The app loads saved nodes before it saves live updates. This order prevents an empty startup list
from replacing saved nodes.

Android usually removes the private database when you uninstall the app. Android backup can
restore the database. Packet logs in Downloads are separate files.

## Phone and tablet map

Select **Nodes / Map** in a network pane.

The panel shows:

- the source radio name for each node
- the total node count
- the count of nodes with positions
- a MapLibre and OpenStreetMap map
- one marker for each positioned node
- a search field across the enabled networks and radios
- node identity and radio data
- a **Trace** control for each available remote node.

Search by node name, short name, ID, public key, role, hardware, hop count, or source. Use
`channel 2` or `ch 2` to find nodes on a channel. The result count changes as you type. The
**Trace** control stays next to each result.

Phones can use the Focused or Stacked layout. Tablets show both network panes. The shared map
uses its own network and radio filters independently of the radio selected for sending messages.

Map tiles require an Internet connection. The saved node rows and coordinates work offline. The
app does not include offline map files.

## Traceroute

Traceroute uses the source radio shown on the node row. It does not use another
connected radio, even when that radio can see the same node. Select **Nodes / Map**, then select
**Trace** on a node.

The node row shows the request state, the route toward the destination, the return route when the
protocol supplies one, and the SNR for each available link. A request times out after 60 seconds.
The **History** control shows older results for that node.

The app saves the newest 1,000 results in this private SQLite file:

```text
dualmesh_traceroutes.db
```

Each result keeps its network, radio, target, state, times, forward hops, return hops, node labels,
and SNR values. An unfinished request becomes a failed result after an app restart because the old
transport session cannot deliver its response to the new process.

Meshtastic uses `TRACEROUTE_APP` and `RouteDiscovery`. Results use Meshtastic node numbers. DualMesh
uses the selected radio node database to replace known numbers with names.

MeshCore uses `CMD_SEND_TRACE_PATH` and `PUSH_CODE_TRACE_DATA`. Results contain short public-key
hashes. DualMesh replaces a hash with a contact name only when that hash has one match in the
selected radio contact list. It shows the hash and match count when a prefix is not unique.

A MeshCore trace always uses the selected contact as its turn-around hop. When the contact has no
saved path, DualMesh sends a direct trace to that contact and back. When the contact has a saved
path, DualMesh follows that path to the contact and mirrors the path for the return. A repeater,
room, or chat contact can be the selected target.

The trace request is direct protocol traffic. It does not send a text message to a channel.
However, it does use RF airtime. Do not run repeated traces on a busy mesh.

## Android Auto map

The **Nodes** action opens the node map on the private stationary setup. Android Auto supplies the
map surface.

The overlay shows the radio name, technology, node count, and position count. Use **Previous
radio** or **Next radio** to select a different map. This action does not change the phone send
radio.

The map supports host pan, fling, scale, and tap events. The app releases the map, presentation,
and virtual display when the session ends.

WARNING: This map is for a stationary, user-controlled head unit. It is not an in-motion driver
interface.

## Position data

A marker means that the mesh supplied coordinates. A marker does not prove these conditions:

- The position is current.
- The node measured the position.
- The precision is high.
- The node is reachable now.
- An MQTT position came from local RF.

Examine the position time, last-heard time, precision, hop count, and MQTT state. DualMesh does not
use the phone position as a radio node position.

## Fault isolation

### A node is not on the map

The node has no valid latitude and longitude pair. Wait for a position packet. You can also examine
the node location settings.

### Two radios have different node counts

This is normal. Each radio has its own directory and packet history.

### A reconnect does not restore an old position

DualMesh can restore only data that it received and saved. It cannot restore a packet that the
radio discarded.

### The map has no background

Make sure that the device has Internet access. The directory continues to show saved coordinates.

### The Android Auto map does not open

Make sure that the host supports Car API level 7. Then examine the POI category, `MAP_TEMPLATES`
permission, and `ACCESS_SURFACE` permission. Read [ANDROID_AUTO.md](ANDROID_AUTO.md).
