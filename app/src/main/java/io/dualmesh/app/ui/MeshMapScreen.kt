package io.dualmesh.app.ui

import androidx.activity.compose.BackHandler
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.content.Context
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.dualmesh.app.DualMeshManager
import io.dualmesh.app.backend.MultiDeviceBackend
import io.dualmesh.app.model.ConnectionState
import io.dualmesh.app.model.MeshNetwork
import io.dualmesh.app.model.MeshNode
import kotlinx.coroutines.delay
import org.maplibre.android.annotations.Icon
import org.maplibre.android.annotations.IconFactory
import org.maplibre.android.annotations.MarkerOptions
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style

private data class NodeActionTarget(val network: MeshNetwork, val deviceId: String, val radioName: String, val nodeId: String? = null)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MeshMapDialog(
    manager: DualMeshManager,
    initialDirectory: Boolean = true,
    embedded: Boolean = false,
    onNavigateMap: ((MeshNode) -> Unit)? = null,
    onNavigateNodes: (() -> Unit)? = null,
    focusNodeKey: String? = null,
    onDismiss: () -> Unit,
) {
    val nodes by manager.nodes.collectAsStateWithLifecycle()
    val mcConnections by manager.meshCore.deviceConnections.collectAsStateWithLifecycle()
    val mtConnections by manager.meshtastic.deviceConnections.collectAsStateWithLifecycle()
    val traces by manager.traceRouteResults.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val preferences = remember(context) { NodeMapPreferences(context) }
    val favoriteStore = remember(context) { NodeFavorites(context) }
    var favorites by remember { mutableStateOf(favoriteStore.load()) }
    var routeNode by remember { mutableStateOf<MeshNode?>(null) }
    var filters by remember { mutableStateOf(preferences.load()) }
    var query by rememberSaveable { mutableStateOf("") }
    var showFilters by rememberSaveable { mutableStateOf(false) }
    var showDirectory by rememberSaveable { mutableStateOf(initialDirectory) }
    val listState = rememberLazyListState()
    var sort by rememberSaveable { mutableStateOf(NodeSort.LAST_HEARD) }
    var sortMenu by remember { mutableStateOf(false) }
    var radioMenu by remember { mutableStateOf(false) }
    var actionTarget by remember { mutableStateOf<NodeActionTarget?>(null) }
    var showNodeDetails by rememberSaveable { mutableStateOf(false) }
    var selectedKey by rememberSaveable { mutableStateOf<String?>(null) }
    var fitRequest by remember { mutableIntStateOf(0) }
    var traceError by remember { mutableStateOf<String?>(null) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000)
            now = System.currentTimeMillis()
        }
    }
    val visible = remember(nodes, filters, query, now, favorites) { filterMapNodes(nodes, filters, query, now, favorites) }
    val sortedNodes = remember(visible, sort, favorites) { sortNodeList(visible, sort, favorites) }
    val positioned = remember(visible) { visible.filter { it.hasValidMapPosition() } }
    val selected = nodes.firstOrNull { mapNodeKey(it) == selectedKey }
    val connections = mcConnections + mtConnections
    val radios = remember(nodes, connections) {
        (nodes.map { it.deviceId to it.deviceName.ifBlank { "${it.network.label} radio" } } +
            connections.map { MultiDeviceBackend.targetId(it.profile) to it.profile.name })
            .filter { it.first.isNotBlank() }.distinctBy { it.first }.sortedBy { it.second }
    }
    val roles = remember(nodes) {
        nodes.filter { !it.role.isNullOrBlank() }.distinctBy(::mapRoleKey)
            .sortedWith(compareBy({ it.network.name }, { it.role }))
    }
    fun update(value: NodeMapFilters) {
        filters = value
        preferences.save(value)
    }
    fun traceEnabled(node: MeshNode): Boolean = node.messageable != false && connections.any {
        it.profile.network == node.network && MultiDeviceBackend.targetId(it.profile) == node.deviceId &&
            it.state == ConnectionState.CONNECTED
    }
    fun toggleFavorite(node: MeshNode) {
        val key = mapNodeKey(node)
        favorites = if (key in favorites) favorites - key else favorites + key
        favoriteStore.save(favorites)
    }
    fun connected(node: MeshNode): Boolean = connections.any {
        it.profile.network == node.network && MultiDeviceBackend.targetId(it.profile) == node.deviceId && it.state == ConnectionState.CONNECTED
    }
    @Composable fun NodeDetails(node: MeshNode) {
        NodeDetailContent(
            node = node, favorite = mapNodeKey(node) in favorites, onFavorite = { toggleFavorite(node) },
            traces = traces.filter { it.network == node.network && it.deviceId == node.deviceId && it.targetId == node.id },
            traceEnabled = traceEnabled(node),
            onTrace = { traceError = manager.traceRoute(node.network, node.deviceId, node.id).exceptionOrNull()?.message },
            onMap = {
                showNodeDetails = false
                if (onNavigateMap != null) onNavigateMap(node)
                else { selectedKey = mapNodeKey(node); showDirectory = false }
            },
            onStats = if (node.network == MeshNetwork.MESHTASTIC && connected(node)) ({ actionTarget = NodeActionTarget(node.network, node.deviceId, node.deviceName, node.id) }) else null,
            onRoute = if (node.network == MeshNetwork.MESHCORE && connected(node)) ({ routeNode = node }) else null,
        )
    }

    LaunchedEffect(focusNodeKey) {
        if (focusNodeKey != null) selectedKey = focusNodeKey
    }
    BackHandler(embedded && !showDirectory && selected != null && !showNodeDetails && !showFilters && routeNode == null && actionTarget == null) { selectedKey = null }
    ScreenPresentation(embedded, { if (selected != null) selectedKey = null else onDismiss() }) {
        MaterialTheme(typography = Typography()) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().then(if (embedded) Modifier else Modifier.safeDrawingPadding())) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (showDirectory) "Nodes (${visible.size})" else "Mesh map", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    TextButton(onClick = { showFilters = true }) { Text("Filters") }
                    if (!embedded) TextButton(onClick = onDismiss) { Text("Close") }
                }
                OutlinedTextField(
                    value = query, onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    placeholder = { Text("Search nodes, IDs, roles, or channels") },
                    singleLine = true,
                    trailingIcon = {
                        if (query.isNotEmpty()) TextButton(onClick = { query = "" }) { Text("Clear") }
                    },
                )
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = filters.meshCore && filters.meshtastic,
                        onClick = { update(filters.copy(meshCore = true, meshtastic = true)) }, label = { Text("Both") },
                    )
                    FilterChip(selected = filters.onlyFavorites, onClick = { update(filters.copy(onlyFavorites = !filters.onlyFavorites)) }, label = { Text("★ Favorites") })
                    FilterChip(selected = filters.meshCore, onClick = { update(filters.copy(meshCore = !filters.meshCore)) }, label = { Text("MC · MeshCore") })
                    FilterChip(selected = filters.meshtastic, onClick = { update(filters.copy(meshtastic = !filters.meshtastic)) }, label = { Text("MT · Meshtastic") })
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (!embedded) {
                        TextButton(onClick = { showDirectory = true; selectedKey = null }, enabled = !showDirectory) { Text("Nodes") }
                        TextButton(onClick = { showDirectory = false; selectedKey = null }, enabled = showDirectory) { Text("Map") }
                    }
                    Spacer(Modifier.weight(1f))
                    if (showDirectory) Box {
                        TextButton(onClick = { sortMenu = true }) { Text("Sort") }
                        DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                            NodeSort.entries.forEach { value -> DropdownMenuItem(text = { Text(if (sort == value) "✓ ${value.label}" else value.label) }, onClick = { sort = value; sortMenu = false }) }
                        }
                    }
                    Box {
                        TextButton(onClick = { radioMenu = true }) { Text("Radio actions") }
                        DropdownMenu(expanded = radioMenu, onDismissRequest = { radioMenu = false }) {
                            if (connections.isEmpty()) DropdownMenuItem(text = { Text("Connect a radio first") }, onClick = {}, enabled = false)
                            connections.forEach { connection ->
                                DropdownMenuItem(text = { Text("${connection.profile.network.label} · ${connection.profile.name}") },
                                    enabled = connection.state == ConnectionState.CONNECTED,
                                    onClick = {
                                        radioMenu = false
                                        actionTarget = NodeActionTarget(connection.profile.network, MultiDeviceBackend.targetId(connection.profile), connection.profile.name)
                                    })
                            }
                        }
                    }
                }
                if (showDirectory) {
                    LazyColumn(Modifier.fillMaxWidth().weight(1f), state = listState) {
                        items(sortedNodes, key = ::mapNodeKey) { node ->
                            CompactNodeRow(node, mapNodeKey(node) in favorites, onFavorite = { toggleFavorite(node) }) { selectedKey = mapNodeKey(node); showNodeDetails = true; traceError = null }
                            HorizontalDivider(Modifier.padding(start = 72.dp), thickness = 0.5.dp)
                        }
                        if (visible.isEmpty()) item {
                            Column(Modifier.padding(24.dp)) {
                                Text("No nodes match these filters.")
                                TextButton(onClick = { query = ""; update(NodeMapFilters()) }) { Text("Reset filters") }
                            }
                        }
                    }
                } else {
                Box(Modifier.fillMaxWidth().weight(1f)) {
                    MeshNodeMap(
                        nodes = positioned, labels = filters.showLabels, darkTiles = filters.darkTiles,
                        fitRequest = fitRequest, selected = selected, onSelect = { selectedKey = mapNodeKey(it); traceError = null },
                    )
                    Surface(
                        modifier = Modifier.align(Alignment.TopStart).padding(12.dp),
                        shape = RoundedCornerShape(14.dp), tonalElevation = 4.dp,
                    ) {
                        Text(
                            "${positioned.size} mapped · ${visible.size} / ${nodes.size} nodes",
                            Modifier.padding(horizontal = 12.dp, vertical = 8.dp), style = MaterialTheme.typography.labelMedium,
                        )
                    }
                    Column(
                        Modifier.align(Alignment.BottomEnd).padding(end = 12.dp, bottom = 48.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.End,
                    ) {
                        FilledTonalButton(onClick = { fitRequest++ }, enabled = positioned.isNotEmpty()) { Text("Fit nodes") }
                        FilledTonalButton(onClick = { if (onNavigateNodes != null) onNavigateNodes() else showDirectory = true }) { Text("Nodes (${visible.size})") }
                    }
                    if (positioned.isEmpty()) {
                        Surface(
                            modifier = Modifier.align(Alignment.Center).padding(32.dp),
                            shape = RoundedCornerShape(20.dp), tonalElevation = 4.dp,
                        ) {
                            Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(if (visible.isEmpty()) "No nodes match these filters" else "No positions for these nodes")
                                TextButton(onClick = { query = ""; update(NodeMapFilters()) }) { Text("Reset filters") }
                            }
                        }
                    }
                }
                }
                if (selected != null && !showDirectory) {
                    Column(Modifier.fillMaxWidth().heightIn(max = 240.dp).verticalScroll(rememberScrollState()).padding(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Selected node", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                            TextButton(onClick = { selectedKey = null }) { Text("Dismiss") }
                        }
                        CompactNodeRow(selected, mapNodeKey(selected) in favorites, onFavorite = { toggleFavorite(selected) }) { showNodeDetails = true }
                        TextButton(onClick = { showNodeDetails = true }) { Text("Node details") }
                    }
                }
            }
        }
        if (showFilters) {
            ModalBottomSheet(
                onDismissRequest = { showFilters = false },
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            ) {
                Column(Modifier.fillMaxWidth().fillMaxHeight(0.85f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).navigationBarsPadding()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Filter nodes", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                        TextButton(onClick = { update(NodeMapFilters()); query = "" }) { Text("Reset") }
                        TextButton(onClick = { showFilters = false }) { Text("Done") }
                    }
                    Text("Applies to the map and node directory.", style = MaterialTheme.typography.bodySmall)
                    MapFilterSwitch("Include unknown", "MT: no short name yet. MC: no advertised name.", filters.includeUnknown) { update(filters.copy(includeUnknown = it)) }
                    MapFilterSwitch("Exclude MQTT", "Hide nodes reported via MQTT.", filters.excludeMqtt) { update(filters.copy(excludeMqtt = it)) }
                    MapFilterSwitch("Only show direct nodes", "Known zero-hop RF nodes; unknown hop counts are hidden.", filters.onlyDirect) { update(filters.copy(onlyDirect = it)) }
                    MapFilterSwitch("Hide offline nodes", "Keep nodes heard within the last two hours.", filters.onlyOnline) { update(filters.copy(onlyOnline = it)) }
                    MapFilterSwitch("Hide unheard nodes", "Require a recorded last-heard time.", filters.hideUnheard) { update(filters.copy(hideUnheard = it)) }
                    MapFilterSwitch("Exclude infrastructure", "Hide routers, repeaters, and room servers.", filters.excludeInfrastructure) { update(filters.copy(excludeInfrastructure = it)) }
                    HorizontalDivider(Modifier.padding(vertical = 10.dp))
                    Text("Last heard", style = MaterialTheme.typography.titleMedium)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(0 to "Any time", 1 to "1 hour", 24 to "24 hours", 168 to "7 days").forEach { (hours, label) ->
                            FilterChip(selected = filters.lastHeardHours == hours, onClick = { update(filters.copy(lastHeardHours = hours)) }, label = { Text(label) })
                        }
                    }
                    Text("Radio", style = MaterialTheme.typography.titleMedium)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = filters.radioId == null, onClick = { update(filters.copy(radioId = null)) }, label = { Text("All radios") })
                        radios.forEach { (id, name) ->
                            FilterChip(selected = filters.radioId == id, onClick = { update(filters.copy(radioId = id)) }, label = { Text(name) })
                        }
                    }
                    if (roles.isNotEmpty()) {
                        Text("Node roles", style = MaterialTheme.typography.titleMedium)
                        Text("Selected roles are shown.", style = MaterialTheme.typography.bodySmall)
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(selected = filters.excludedRoles.isEmpty(), onClick = { update(filters.copy(excludedRoles = emptySet())) }, label = { Text("All roles") })
                            roles.forEach { node ->
                                val key = mapRoleKey(node)
                                FilterChip(selected = key !in filters.excludedRoles, onClick = {
                                    update(filters.copy(excludedRoles = if (key in filters.excludedRoles) filters.excludedRoles - key else filters.excludedRoles + key))
                                }, label = { Text("${if (node.network == MeshNetwork.MESHCORE) "MC" else "MT"} · ${node.role}") })
                            }
                        }
                    }
                    HorizontalDivider(Modifier.padding(vertical = 10.dp))
                    MapFilterSwitch("Node labels", "Show short names on the map.", filters.showLabels) { update(filters.copy(showLabels = it)) }
                    MapFilterSwitch("Dark map", "Use darker street tiles.", filters.darkTiles) { update(filters.copy(darkTiles = it)) }
                    Spacer(Modifier.height(20.dp))
                }
            }
        }
        if (selected != null && showNodeDetails) {
            Dialog(onDismissRequest = { showNodeDetails = false; if (showDirectory) selectedKey = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = { showNodeDetails = false; if (showDirectory) selectedKey = null }) { Text("‹ Back") }
                            Text("Node details", style = MaterialTheme.typography.titleLarge)
                        }
                        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                            NodeDetails(selected)
                            traceError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        }
                    }
                }
            }
        }
        routeNode?.let { node ->
            val access = manager.meshCore.contactRoutesFor(node.deviceId)
            if (access != null) ContactRouteDialog(node, nodes, access, onDismiss = { routeNode = null })
            else AlertDialog(onDismissRequest = { routeNode = null }, text = { Text("The source radio is no longer connected") }, confirmButton = { TextButton(onClick = { routeNode = null }) { Text("Done") } })
        }
        actionTarget?.let { target ->
            val backend = if (target.network == MeshNetwork.MESHCORE) manager.meshCore else manager.meshtastic
            val access = backend.nodeActionsFor(target.deviceId)
            if (access != null) NodeActionsDialog(access, target.network, target.radioName, target.nodeId, onDismiss = { actionTarget = null })
            else AlertDialog(onDismissRequest = { actionTarget = null }, text = { Text("This radio is no longer connected") }, confirmButton = { TextButton(onClick = { actionTarget = null }) { Text("Done") } })
        }
        }

    }
}

@Composable
private fun MapFilterSwitch(label: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().toggleable(value = checked, role = Role.Switch, onValueChange = onChange).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun MeshNodeMap(
    nodes: List<MeshNode>, labels: Boolean, darkTiles: Boolean, fitRequest: Int,
    selected: MeshNode?, onSelect: (MeshNode) -> Unit,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var styleRevision by remember { mutableIntStateOf(0) }
    var camera by rememberSaveable { mutableStateOf<ArrayList<Double>?>(null) }
    var hasFitted by remember { mutableStateOf(camera != null) }
    val onSelectCurrent by rememberUpdatedState(onSelect)
    val markerNodes = remember { mutableMapOf<Long, MeshNode>() }
    val icons = remember(context) { mutableMapOf<String, Icon>() }
    val mapView = remember(context) {
        MapView(context).apply {
            onCreate(null)
            getMapAsync { ready ->
                ready.setOnMarkerClickListener { marker ->
                    markerNodes[marker.id]?.let(onSelectCurrent)
                    true
                }
                camera?.let { saved ->
                    ready.cameraPosition = org.maplibre.android.camera.CameraPosition.Builder()
                        .target(LatLng(saved[0], saved[1])).zoom(saved[2]).bearing(saved[3]).tilt(saved[4]).build()
                }
                ready.addOnCameraIdleListener {
                    val position = ready.cameraPosition
                    position.target?.let { target ->
                        camera = arrayListOf(target.latitude, target.longitude, position.zoom, position.bearing, position.tilt)
                    }
                }
                map = ready
            }
        }
    }
    DisposableEffect(mapView, lifecycle) {
        var started = false
        var resumed = false
        fun sync() {
            val wantsStart = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
            val wantsResume = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            if (!wantsResume && resumed) { mapView.onPause(); resumed = false }
            if (!wantsStart && started) { mapView.onStop(); started = false }
            if (wantsStart && !started) { mapView.onStart(); started = true }
            if (wantsResume && !resumed) { mapView.onResume(); resumed = true }
        }
        val observer = LifecycleEventObserver { _, _ -> sync() }
        lifecycle.addObserver(observer)
        sync()
        onDispose {
            lifecycle.removeObserver(observer)
            if (resumed) mapView.onPause()
            if (started) mapView.onStop()
            mapView.onDestroy()
        }
    }
    LaunchedEffect(map, darkTiles) {
        map?.setStyle(Style.Builder().fromJson(mapStyle(darkTiles))) { styleRevision++ }
    }
    LaunchedEffect(map, nodes, labels, styleRevision) {
        val ready = map ?: return@LaunchedEffect
        if (styleRevision == 0) return@LaunchedEffect
        ready.clear()
        markerNodes.clear()
        nodes.forEach { node ->
            val label = if (labels) node.shortName?.takeIf { it.isNotBlank() } ?: node.name.take(12) else ""
            val key = "${node.network}:$label"
            val icon = icons.getOrPut(key) { mapMarkerIcon(context, node.network, label) }
            val marker = ready.addMarker(MarkerOptions().position(LatLng(node.latitude!!, node.longitude!!)).icon(icon).title(node.name))
            markerNodes[marker.id] = node
        }
        if (!hasFitted && nodes.isNotEmpty()) {
            fitMapNodes(ready, nodes)
            hasFitted = true
        }
    }
    LaunchedEffect(map, fitRequest) {
        if (fitRequest > 0) map?.let { fitMapNodes(it, nodes) }
    }
    var lastSelectionKey by rememberSaveable { mutableStateOf<String?>(null) }
    val selectionKey = selected?.let(::mapNodeKey)
    LaunchedEffect(map, selectionKey) {
        val ready = map ?: return@LaunchedEffect
        if (selectionKey != lastSelectionKey && selected?.hasValidMapPosition() == true) {
            ready.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(selected.latitude!!, selected.longitude!!), 13.0))
        }
        lastSelectionKey = selectionKey
    }
    AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())
}

private fun fitMapNodes(map: MapLibreMap, nodes: List<MeshNode>) {
    val coordinates = nodes.map { LatLng(it.latitude!!, it.longitude!!) }
    when (coordinates.size) {
        0 -> Unit
        1 -> map.animateCamera(CameraUpdateFactory.newLatLngZoom(coordinates.first(), 11.0))
        else -> {
            if (coordinates.distinct().size == 1) map.animateCamera(CameraUpdateFactory.newLatLngZoom(coordinates.first(), 11.0))
            else map.animateCamera(CameraUpdateFactory.newLatLngBounds(LatLngBounds.Builder().includes(coordinates).build(), 80))
        }
    }
}

private fun mapMarkerIcon(context: Context, network: MeshNetwork, label: String): Icon {
    val density = context.resources.displayMetrics.density
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 12 * density; typeface = android.graphics.Typeface.DEFAULT_BOLD }
    val textWidth = if (label.isEmpty()) 0f else paint.measureText(label) + 14 * density
    val width = (32 * density + textWidth).toInt().coerceAtLeast(32)
    val height = (44 * density).toInt().coerceAtLeast(44)
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val color = if (network == MeshNetwork.MESHCORE) android.graphics.Color.rgb(124, 58, 237) else android.graphics.Color.rgb(30, 120, 58)
    paint.color = android.graphics.Color.WHITE
    canvas.drawRoundRect(1f, 2 * density, width - 1f, 32 * density, 14 * density, 14 * density, paint)
    canvas.drawPath(android.graphics.Path().apply {
        moveTo(width / 2f - 6 * density, 30 * density)
        lineTo(width / 2f, height.toFloat())
        lineTo(width / 2f + 6 * density, 30 * density)
        close()
    }, paint)
    paint.color = color
    canvas.drawCircle(16 * density, 17 * density, 12 * density, paint)
    paint.color = android.graphics.Color.WHITE
    paint.textSize = 8 * density
    canvas.drawText(if (network == MeshNetwork.MESHCORE) "MC" else "MT", 9 * density, 20 * density, paint)
    if (label.isNotEmpty()) {
        paint.color = android.graphics.Color.rgb(24, 32, 30)
        paint.textSize = 12 * density
        canvas.drawText(label, 32 * density, 21 * density, paint)
    }
    return IconFactory.getInstance(context).fromBitmap(bitmap)
}

private fun mapStyle(dark: Boolean): String = """
    {"version":8,"name":"DualMesh streets","sources":{"osm":{"type":"raster",
    "tiles":["https://tile.openstreetmap.org/{z}/{x}/{y}.png"],"tileSize":256,"maxzoom":19,
    "attribution":"© OpenStreetMap contributors"}},"layers":[
    {"id":"background","type":"background","paint":{"background-color":"${if (dark) "#101820" else "#e8eee8"}"}},
    {"id":"streets","type":"raster","source":"osm","paint":{
    "raster-saturation":${if (dark) -0.5 else -0.1},"raster-brightness-max":${if (dark) 0.55 else 1.0}}}]}
""".trimIndent()
