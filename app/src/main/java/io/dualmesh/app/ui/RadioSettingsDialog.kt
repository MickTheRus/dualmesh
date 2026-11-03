package io.dualmesh.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.dualmesh.app.settings.RadioSetting
import io.dualmesh.app.settings.RadioSettingsAccess
import io.dualmesh.app.settings.RadioSettingsSection
import io.dualmesh.app.settings.SettingKind

@Composable
internal fun RadioSettingsDialog(
    access: RadioSettingsAccess,
    radioName: String,
    connected: Boolean,
    onDismiss: () -> Unit,
) {
    val state by access.radioSettings.collectAsStateWithLifecycle()
    var selected by remember(access) { mutableStateOf<RadioSettingsSection?>(null) }
    var editRevision by remember(access) { mutableLongStateOf(0) }
    var query by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val navigateBack: () -> Unit = {
        if (selected != null) { selected = null; error = null } else onDismiss()
    }
    LaunchedEffect(access) { access.ensureSettingsLoaded().onFailure { error = it.message } }
    LaunchedEffect(state.busy, state.status) {
        if (saving && !state.busy) {
            saving = false
            if (state.error == null) selected = null
        }
    }
    // The dialog owns Android's back callback. Both the edge gesture and toolbar navigate
    // up one settings page; only backing out of the section list closes settings.
    Dialog(
        onDismissRequest = navigateBack,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false),
    ) {
        MaterialTheme(
            typography = Typography(),
            shapes = Shapes(small = RoundedCornerShape(8.dp), medium = RoundedCornerShape(12.dp), large = RoundedCornerShape(16.dp)),
        ) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = navigateBack, modifier = Modifier.semantics { contentDescription = "Back" }) {
                            Text("‹", fontSize = 32.sp)
                        }
                        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                            Text(selected?.title ?: "Radio settings", style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(radioName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        if (selected == null) TextButton(enabled = connected && !state.busy, onClick = {
                            error = null
                            access.refreshSettings().onFailure { error = it.message }
                        }) { Text("Refresh") }
                        Spacer(Modifier.width(8.dp))
                    }
                    HorizontalDivider()
                    if (!connected || state.busy || state.status.startsWith("Saved")) {
                        Text(
                            if (connected) state.status else "Disconnected. Reconnect and refresh to save.",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                        )
                    }
                    if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    (error ?: state.error)?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                    }
                    if (selected == null) {
                        OutlinedTextField(
                            value = query, onValueChange = { query = it }, placeholder = { Text("Search settings") },
                            singleLine = true, textStyle = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                            trailingIcon = { if (query.isNotEmpty()) TextButton(onClick = { query = "" }) { Text("Clear") } },
                        )
                        val sections = state.sections.filter { section ->
                            query.isBlank() || section.title.contains(query, true) || section.fields.any { it.label.contains(query, true) }
                        }
                        LazyColumn(Modifier.weight(1f), state = listState, contentPadding = PaddingValues(bottom = 16.dp)) {
                            itemsIndexed(sections, key = { _, section -> section.id }) { index, section ->
                                val group = sectionGroup(section.id)
                                if (index == 0 || sectionGroup(sections[index - 1].id) != group) {
                                    Text(group, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium,
                                        modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp))
                                }
                                SettingsPreferenceRow(
                                    title = section.title.removePrefix("Module · "),
                                    summary = "${section.fields.count { it.kind != SettingKind.READ_ONLY }} settings",
                                    enabled = connected && !state.busy,
                                    onClick = { selected = section; editRevision = state.revision; error = null },
                                ) { Text("›", fontSize = 24.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            }
                            if (sections.isEmpty() && !state.busy) item {
                                Text("No settings available. Connect the radio and tap Refresh.", Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    } else {
                        key(selected!!.id, editRevision) {
                            RadioSettingsEditor(
                                section = selected!!,
                                enabled = connected && !state.busy && !saving,
                                onSave = { changes ->
                                    error = null
                                    access.saveSettings(selected!!.id, changes, editRevision)
                                        .onSuccess { saving = true }
                                        .onFailure { error = it.message ?: "Could not save settings" }
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun sectionGroup(id: String): String = when {
    id.startsWith("module:") -> "Modules"
    id.startsWith("channel:") -> "Channels"
    else -> "Radio configuration"
}

@Composable
private fun ColumnScope.RadioSettingsEditor(section: RadioSettingsSection, enabled: Boolean, onSave: (Map<String, String>) -> Unit) {
    var values by remember(section) { mutableStateOf(section.fields.associate { it.id to it.value }) }
    var editing by remember(section) { mutableStateOf<RadioSetting?>(null) }
    val changes = values.filter { (id, value) -> section.fields.first { it.id == id }.value != value }
    LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(vertical = 4.dp)) {
        items(section.fields, key = { it.id }) { field ->
            val value = values[field.id].orEmpty()
            if (field.kind == SettingKind.BOOLEAN) {
                val checked = value == "true"
                SettingsPreferenceRow(
                    title = field.label, summary = field.help.ifEmpty { if (checked) "Enabled" else "Disabled" },
                    enabled = enabled, checked = checked,
                    onToggle = { values = values + (field.id to it.toString()) },
                ) { Switch(checked = checked, onCheckedChange = null, enabled = enabled) }
            } else {
                val summary = when {
                    field.kind == SettingKind.SECRET && value.isNotEmpty() -> "••••••••"
                    value.isEmpty() -> "Not set"
                    else -> value.replace('\n', ' ')
                }
                SettingsPreferenceRow(title = field.label, summary = summary,
                    enabled = enabled && field.kind != SettingKind.READ_ONLY,
                    onClick = if (field.kind == SettingKind.READ_ONLY) null else { { editing = field } },
                ) { if (field.kind != SettingKind.READ_ONLY) Text("›", fontSize = 22.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
        item {
            Text("Changes apply when you tap Save. Radio changes may disconnect the node.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp))
        }
    }
    HorizontalDivider()
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        TextButton(enabled = enabled && changes.isNotEmpty(), onClick = { values = section.fields.associate { it.id to it.value } }) { Text("Reset edits") }
        Spacer(Modifier.weight(1f))
        Button(enabled = enabled && changes.isNotEmpty(), onClick = { onSave(changes) }) { Text("Save") }
    }
    editing?.let { field ->
        RadioFieldEditor(field, values[field.id].orEmpty(), onDismiss = { editing = null }) { value ->
            values = values + (field.id to value)
            editing = null
        }
    }
}

@Composable
private fun SettingsPreferenceRow(
    title: String,
    summary: String,
    enabled: Boolean,
    onClick: (() -> Unit)? = null,
    checked: Boolean? = null,
    onToggle: ((Boolean) -> Unit)? = null,
    trailing: @Composable () -> Unit,
) {
    val interaction = when {
        checked != null && onToggle != null -> Modifier.toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onToggle)
        onClick != null -> Modifier.clickable(enabled = enabled, onClick = onClick)
        else -> Modifier
    }
    Column {
        Row(Modifier.fillMaxWidth().then(interaction).heightIn(min = 60.dp).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = 16.dp)) {
                Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (summary.isNotBlank()) Text(summary, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            trailing()
        }
        HorizontalDivider(Modifier.padding(start = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
    }
}

@Composable
private fun RadioFieldEditor(field: RadioSetting, initialValue: String, onDismiss: () -> Unit, onDone: (String) -> Unit) {
    var value by remember(field.id) { mutableStateOf(initialValue) }
    var revealed by remember(field.id) { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(field.label, style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (field.kind == SettingKind.CHOICE) {
                    LazyColumn(Modifier.heightIn(max = 360.dp)) {
                        items(field.choices) { choice ->
                            Row(Modifier.fillMaxWidth().toggleable(value == choice, role = Role.RadioButton, onValueChange = { value = choice }).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(selected = value == choice, onClick = null)
                                Text(choice, Modifier.padding(start = 12.dp), style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                } else {
                    OutlinedTextField(
                        value = value, onValueChange = { value = it }, modifier = Modifier.fillMaxWidth(),
                        singleLine = !field.help.startsWith("One value per line"),
                        visualTransformation = if (field.kind == SettingKind.SECRET && !revealed) PasswordVisualTransformation() else VisualTransformation.None,
                        keyboardOptions = KeyboardOptions(keyboardType = when (field.kind) {
                            SettingKind.NUMBER -> KeyboardType.Text
                            SettingKind.SECRET -> KeyboardType.Password
                            else -> KeyboardType.Text
                        }),
                        trailingIcon = { if (field.kind == SettingKind.SECRET) TextButton(onClick = { revealed = !revealed }) { Text(if (revealed) "Hide" else "Show") } },
                    )
                }
                if (field.help.isNotBlank()) Text(field.help, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = { onDone(value) }) { Text("Done") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
