package io.dualmesh.app

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import io.dualmesh.app.ui.DualMeshApp

class MainActivity : ComponentActivity() {
    private val viewModel by viewModels<MainViewModel>()

    private val bluetoothPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { permissions ->
        if (permissions.values.all { it }) viewModel.startScan()
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent {
            DualMeshApp(
                viewModel = viewModel,
                requestPermissions = ::requestBluetoothPermissions,
            )
        }
    }

    private fun requestBluetoothPermissions() {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
            )
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        bluetoothPermissionLauncher.launch(permissions)
    }
}
