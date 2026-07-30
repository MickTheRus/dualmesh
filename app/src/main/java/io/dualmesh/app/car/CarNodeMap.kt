package io.dualmesh.app.car

import android.app.Presentation
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Point
import android.graphics.PointF
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Handler
import android.os.Looper
import androidx.car.app.AppManager
import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.Header
import androidx.car.app.model.Pane
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.MapController
import androidx.car.app.navigation.model.MapWithContentTemplate
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import io.dualmesh.app.DualMeshApplication
import io.dualmesh.app.DualMeshManager
import io.dualmesh.app.backend.MultiDeviceBackend
import io.dualmesh.app.model.MeshNetwork
import io.dualmesh.app.model.MeshNode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import org.maplibre.android.annotations.Icon
import org.maplibre.android.annotations.IconFactory
import org.maplibre.android.annotations.MarkerOptions
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import kotlin.math.ln

internal class DualMeshCarSession(
    private val application: DualMeshApplication,
) : Session() {
    private var initialized = false
    private val nodeMapSurface by lazy { CarNodeMapSurface(carContext) }

    override fun onCreateScreen(intent: Intent): Screen {
        if (!initialized) {
            initialized = true
            carContext.getCarService(AppManager::class.java)
                .setSurfaceCallback(nodeMapSurface)
            lifecycle.addObserver(
                object : DefaultLifecycleObserver {
                    override fun onDestroy(owner: LifecycleOwner) {
                        nodeMapSurface.close()
                    }
                },
            )
        }
        return DualMeshConversationsScreen(
            carContext = carContext,
            application = application,
            nodeMapSurface = nodeMapSurface,
        )
    }
}

internal class DualMeshNodeMapScreen(
    carContext: CarContext,
    private val manager: DualMeshManager,
    private val surface: CarNodeMapSurface,
) : Screen(carContext) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var selectedKey: RadioKey? = initialKey()
    private var renderedKey: RadioKey? = null

    init {
        combine(
            manager.nodes,
            manager.meshCore.deviceConnections,
            manager.meshtastic.deviceConnections,
        ) { _, _, _ -> Unit }
            .onEach {
                val available = radioTargets()
                if (selectedKey !in available.map(CarRadioTarget::key)) {
                    selectedKey = available.firstOrNull()?.key
                    renderedKey = null
                }
                updateSurface(fit = renderedKey != selectedKey)
                invalidate()
            }
            .launchIn(scope)

        lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onDestroy(owner: LifecycleOwner) {
                    scope.cancel()
                }
            },
        )
    }

    override fun onGetTemplate(): Template {
        val targets = radioTargets()
        val target = targets.firstOrNull { it.key == selectedKey } ?: targets.firstOrNull()
        if (target != null && selectedKey != target.key) {
            selectedKey = target.key
            renderedKey = null
        }
        updateSurface(fit = renderedKey != selectedKey)

        val nodes = target?.let(::nodesFor).orEmpty()
        val positioned = nodes.count(MeshNode::hasPosition)
        val pane = Pane.Builder()
            .addRow(
                Row.Builder()
                    .setTitle(target?.name ?: "No radios")
                    .addText(
                        if (target == null) {
                            "Connect or restore a radio to load its stored nodes"
                        } else {
                            "${target.network.label} · ${nodes.size} nodes · " +
                                "$positioned positioned"
                        },
                    )
                    .build(),
            )
        if (targets.size > 1) {
            pane.addAction(
                Action.Builder()
                    .setTitle("Previous radio")
                    .setOnClickListener { selectAdjacent(-1) }
                    .build(),
            )
            pane.addAction(
                Action.Builder()
                    .setTitle("Next radio")
                    .setOnClickListener { selectAdjacent(1) }
                    .build(),
            )
        }

        val content = PaneTemplate.Builder(pane.build())
            .setHeader(
                Header.Builder()
                    .setStartHeaderAction(Action.BACK)
                    .setTitle("DualMesh node map")
                    .build(),
            )
            .build()
        val mapController = MapController.Builder()
            .setMapActionStrip(
                ActionStrip.Builder()
                    .addAction(Action.PAN)
                    .build(),
            )
            .setPanModeListener { }
            .build()
        return MapWithContentTemplate.Builder()
            .setContentTemplate(content)
            .setMapController(mapController)
            .build()
    }

    private fun selectAdjacent(delta: Int) {
        val targets = radioTargets()
        if (targets.isEmpty()) return
        val current = targets.indexOfFirst { it.key == selectedKey }.coerceAtLeast(0)
        selectedKey = targets[(current + delta).mod(targets.size)].key
        renderedKey = null
        updateSurface(fit = true)
        invalidate()
    }

    private fun updateSurface(fit: Boolean) {
        val target = radioTargets().firstOrNull { it.key == selectedKey }
        val nodes = target?.let(::nodesFor).orEmpty()
        surface.showNodes(nodes, fit)
        if (target != null) renderedKey = target.key
    }

    private fun nodesFor(target: CarRadioTarget): List<MeshNode> =
        manager.nodes.value.filter {
            it.network == target.network && it.deviceId == target.deviceId
        }

    private fun initialKey(): RadioKey? {
        val network = manager.selectedNetwork.value
        val deviceId = manager.selectedDeviceIds.value[network]
        return deviceId?.let { RadioKey(network, it) }
            ?: radioTargets().firstOrNull()?.key
    }

    private fun radioTargets(): List<CarRadioTarget> {
        val fromNodes = manager.nodes.value
            .filter { it.deviceId.isNotBlank() }
            .groupBy { RadioKey(it.network, it.deviceId) }
            .map { (key, nodes) ->
                CarRadioTarget(key, nodes.firstNotNullOfOrNull { it.deviceName.takeIf(String::isNotBlank) }
                    ?: key.deviceId)
            }
        val active = buildList {
            manager.meshCore.deviceConnections.value.forEach { device ->
                add(
                    CarRadioTarget(
                        RadioKey(
                            MeshNetwork.MESHCORE,
                            MultiDeviceBackend.targetId(device.profile),
                        ),
                        device.profile.name,
                    ),
                )
            }
            manager.meshtastic.deviceConnections.value.forEach { device ->
                add(
                    CarRadioTarget(
                        RadioKey(
                            MeshNetwork.MESHTASTIC,
                            MultiDeviceBackend.targetId(device.profile),
                        ),
                        device.profile.name,
                    ),
                )
            }
        }
        return (fromNodes + active)
            .associateBy(CarRadioTarget::key)
            .values
            .sortedWith(compareBy({ it.key.network.ordinal }, { it.name.lowercase() }))
    }
}

internal class CarNodeMapSurface(
    private val context: CarContext,
) : SurfaceCallback {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var virtualDisplay: VirtualDisplay? = null
    private var presentation: Presentation? = null
    private var mapView: MapView? = null
    private var map: MapLibreMap? = null
    private var markerIcon: Icon? = null
    private var nodes: List<MeshNode> = emptyList()
    private var fitRequested = false
    private val renderRunnable = Runnable(::renderNodes)

    fun showNodes(nodes: List<MeshNode>, fit: Boolean) {
        mainHandler.post {
            val positioned = nodes.filter(MeshNode::hasPosition)
            if (positioned == this.nodes && !fit) return@post
            this.nodes = positioned
            fitRequested = fitRequested || fit
            mainHandler.removeCallbacks(renderRunnable)
            mainHandler.postDelayed(renderRunnable, MAP_RENDER_DEBOUNCE_MILLIS)
        }
    }

    override fun onSurfaceAvailable(surfaceContainer: SurfaceContainer) {
        mainHandler.post {
            releaseDisplay()
            val displayManager = context.getSystemService(DisplayManager::class.java)
            val display = displayManager.createVirtualDisplay(
                "DualMesh car node map",
                surfaceContainer.width,
                surfaceContainer.height,
                surfaceContainer.dpi,
                surfaceContainer.surface,
                0,
            )
            val overlay = Presentation(context, display.display)
            val view = MapView(overlay.context)
            view.onCreate(null)
            view.onStart()
            view.onResume()
            overlay.setContentView(view)
            overlay.show()
            virtualDisplay = display
            presentation = overlay
            mapView = view
            view.getMapAsync { readyMap ->
                readyMap.uiSettings.apply {
                    isCompassEnabled = false
                    isLogoEnabled = true
                    isAttributionEnabled = true
                }
                readyMap.setStyle(Style.Builder().fromJson(CAR_NODE_MAP_STYLE_JSON)) {
                    map = readyMap
                    markerIcon = createMarkerIcon(view.context)
                    fitRequested = true
                    renderNodes()
                }
            }
        }
    }

    override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) {
        mainHandler.post(::releaseDisplay)
    }

    override fun onScroll(distanceX: Float, distanceY: Float) {
        mainHandler.post {
            map?.scrollBy(distanceX, distanceY)
        }
    }

    override fun onFling(velocityX: Float, velocityY: Float) {
        mainHandler.post {
            map?.scrollBy(-velocityX * 0.12f, -velocityY * 0.12f, 300)
        }
    }

    override fun onScale(focusX: Float, focusY: Float, scaleFactor: Float) {
        if (scaleFactor <= 0f) return
        mainHandler.post {
            map?.moveCamera(
                CameraUpdateFactory.zoomBy(
                    ln(scaleFactor.toDouble()) / LN_2,
                    Point(focusX.toInt(), focusY.toInt()),
                ),
            )
        }
    }

    override fun onClick(x: Float, y: Float) {
        mainHandler.post {
            val readyMap = map ?: return@post
            val tapped = readyMap.projection.fromScreenLocation(PointF(x, y))
            val nearest = nodes.minByOrNull { node ->
                val latitude = node.latitude ?: 0.0
                val longitude = node.longitude ?: 0.0
                val dLat = latitude - tapped.latitude
                val dLon = longitude - tapped.longitude
                dLat * dLat + dLon * dLon
            } ?: return@post
            CarToast.makeText(
                context,
                "${nearest.name} · ${formatCoordinate(nearest)}",
                CarToast.LENGTH_SHORT,
            ).show()
        }
    }

    fun close() {
        mainHandler.post(::releaseDisplay)
    }

    private fun renderNodes() {
        val readyMap = map ?: return
        val icon = markerIcon ?: return
        readyMap.clear()
        val coordinates = nodes.mapNotNull { node ->
            val latitude = node.latitude ?: return@mapNotNull null
            val longitude = node.longitude ?: return@mapNotNull null
            LatLng(latitude, longitude).also { coordinate ->
                readyMap.addMarker(
                    MarkerOptions()
                        .position(coordinate)
                        .title(node.name)
                        .snippet(formatCoordinate(node))
                        .icon(icon),
                )
            }
        }
        if (!fitRequested) return
        fitRequested = false
        when (coordinates.size) {
            0 -> Unit
            1 -> readyMap.moveCamera(
                CameraUpdateFactory.newLatLngZoom(coordinates.first(), 11.0),
            )
            else -> readyMap.moveCamera(
                CameraUpdateFactory.newLatLngBounds(
                    LatLngBounds.Builder().includes(coordinates).build(),
                    80,
                ),
            )
        }
    }

    private fun releaseDisplay() {
        mainHandler.removeCallbacks(renderRunnable)
        map = null
        markerIcon = null
        mapView?.apply {
            onPause()
            onStop()
            onDestroy()
        }
        mapView = null
        presentation?.dismiss()
        presentation = null
        virtualDisplay?.release()
        virtualDisplay = null
    }

    private fun createMarkerIcon(context: Context): Icon {
        val size = (18 * context.resources.displayMetrics.density).toInt().coerceAtLeast(24)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val center = size / 2f
        paint.color = Color.argb(45, 40, 210, 255)
        canvas.drawCircle(center, center, size * 0.39f, paint)
        paint.color = Color.rgb(40, 210, 255)
        canvas.drawCircle(center, center, size * 0.34f, paint)
        paint.color = Color.rgb(2, 7, 20)
        canvas.drawCircle(center, center, size * 0.13f, paint)
        return IconFactory.getInstance(context).fromBitmap(bitmap)
    }

    private fun formatCoordinate(node: MeshNode): String =
        "%.5f, %.5f".format(node.latitude, node.longitude)

    private companion object {
        private val LN_2 = ln(2.0)
        private const val MAP_RENDER_DEBOUNCE_MILLIS = 250L
    }
}

private data class RadioKey(
    val network: MeshNetwork,
    val deviceId: String,
)

private data class CarRadioTarget(
    val key: RadioKey,
    val name: String,
) {
    val network: MeshNetwork
        get() = key.network

    val deviceId: String
        get() = key.deviceId
}

private val CAR_NODE_MAP_STYLE_JSON = """
    {
      "version": 8,
      "name": "DualMesh car node map",
      "sources": {
        "osm": {
          "type": "raster",
          "tiles": ["https://tile.openstreetmap.org/{z}/{x}/{y}.png"],
          "tileSize": 256,
          "maxzoom": 19,
          "attribution": "© OpenStreetMap contributors"
        }
      },
      "layers": [
        {
          "id": "space",
          "type": "background",
          "paint": {"background-color": "#020714"}
        },
        {
          "id": "osm",
          "type": "raster",
          "source": "osm",
          "paint": {
            "raster-saturation": -0.35,
            "raster-contrast": 0.20,
            "raster-brightness-max": 0.78
          }
        }
      ]
    }
""".trimIndent()
