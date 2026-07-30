package io.dualmesh.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.dualmesh.app.connection.ConnectionProfileStore

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            return
        }
        if (ConnectionProfileStore(context).hasEnabledProfiles()) {
            MeshConnectionService.start(context)
        }
    }
}
