package io.dualmesh.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/** Embedded destinations inherit the Scaffold insets; modal entry points keep their own window. */
@Composable
internal fun ScreenPresentation(embedded: Boolean, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    if (embedded) content()
    else Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) { content() }
}
