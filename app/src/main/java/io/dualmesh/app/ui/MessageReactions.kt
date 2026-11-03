package io.dualmesh.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.dualmesh.app.history.hasOwnReaction
import io.dualmesh.app.model.DeliveryState
import io.dualmesh.app.model.UnifiedMessage

private val quickReactions = listOf("👍", "👎", "😂", "🔥", "❤️", "😮")
private val reactionEmoji = (quickReactions + listOf(
    "😀", "😃", "😄", "😁", "😆", "😅", "🤣", "🙂", "🙃", "😉", "😊", "🥰", "😍", "🤩", "😘", "😋", "😛", "🤪", "🤗", "🤔", "🤫", "🤐", "😐", "😑", "🙄", "😏", "😴", "😷", "🤒", "🤯", "🥳", "😎", "🤓", "🧐", "😕", "🙁", "😢", "😭", "😤", "😡", "🤬", "😱", "😨", "😰", "😥", "😓", "🤦", "🤷", "🫡", "✅", "❌", "❓", "❗", "⚠️", "🆘", "💯", "🎉", "🎊", "✨", "⭐", "🌟", "💥", "💤", "👀", "🧠", "💪", "🤝", "🙏", "👏", "🙌", "👐", "👌", "🤞", "✌️", "🤟", "🤘", "👋", "🖖", "🫶", "👍🏻", "👍🏼", "👍🏽", "👍🏾", "👍🏿", "💔", "🧡", "💛", "💚", "💙", "💜", "🖤", "🤍", "🤎", "💕", "💖", "☀️", "🌧️", "🌩️", "❄️", "🌈", "🌍", "🌲", "🌻", "🐱", "🐶", "🐻", "🦊", "🐸", "🐝", "🍕", "🍔", "☕", "🍺", "🥂", "🎂", "🚗", "🚲", "🏠", "🏕️", "⛺", "🗺️", "📍", "📡", "📻", "🔋", "🔌", "🔧", "🛠️", "💡", "🔒", "🚀", "🏁", "👨‍👩‍👧‍👦", "🏳️‍🌈",
)).distinct()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReactionPicker(parent: UnifiedMessage, reactions: List<UnifiedMessage>, onDismiss: () -> Unit,
    onReact: (String) -> Boolean, error: String?) {
    var more by rememberSaveable { mutableStateOf(false) }
    var custom by rememberSaveable { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(if (more) "Choose an emoji" else "React to message", style = MaterialTheme.typography.titleLarge)
                TextButton(onClick = { if (more) more = false else onDismiss() }) { Text(if (more) "Back" else "Close") }
            }
            Text(parent.text.take(120), maxLines = 2, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (!more) {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.SpaceEvenly) {
                    quickReactions.forEach { emoji ->
                        TextButton(onClick = { onReact(emoji) }, enabled = !hasOwnReaction(reactions, parent, emoji), modifier = Modifier.width(48.dp)) { Text(emoji, fontSize = 24.sp) }
                    }
                }
                TextButton(onClick = { more = true }) { Text("More emoji…") }
            } else {
                LazyVerticalGrid(columns = GridCells.Adaptive(48.dp), modifier = Modifier.fillMaxWidth().heightIn(max = 260.dp)) {
                    items(reactionEmoji, key = { it }) { emoji ->
                        TextButton(onClick = { onReact(emoji) }, enabled = !hasOwnReaction(reactions, parent, emoji)) { Text(emoji, fontSize = 24.sp) }
                    }
                }
                OutlinedTextField(custom, { custom = it }, label = { Text("Type or paste another emoji") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                TextButton(onClick = { onReact(custom.trim()) }, enabled = custom.isNotBlank() && !hasOwnReaction(reactions, parent, custom.trim())) { Text("Send reaction") }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
internal fun MessageReactionStrip(reactions: List<UnifiedMessage>, canReact: Boolean, onReact: (String) -> Unit, onAdd: () -> Unit) {
    val chips = remember(reactions) { summarizeReactions(reactions) }
    var details by rememberSaveable { mutableStateOf<String?>(null) }
    if (chips.isEmpty()) return
    Row(Modifier.padding(horizontal = 8.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        chips.forEach { chip ->
            val own = reactions.firstOrNull { it.outgoing && it.reactionEmoji == chip.emoji }
            val failed = own?.deliveryState in setOf(DeliveryState.ERROR, DeliveryState.UNCONFIRMED)
            Surface(shape = CircleShape, color = if (own != null && !failed) NeonBlue.copy(alpha = 0.2f) else GlassSurfaceStrong,
                border = BorderStroke(1.dp, if (failed) SignalRed else NeonBlue.copy(alpha = 0.42f)),
                modifier = Modifier.combinedClickable(onClick = { if (canReact) onReact(chip.emoji) else details = chip.emoji }, onLongClick = { details = chip.emoji }, onLongClickLabel = "Reaction details")) {
                Text(listOfNotNull(chip.emoji, chip.count.takeIf { it > 1 }?.toString(), "!".takeIf { failed }).joinToString(" "),
                    Modifier.padding(horizontal = 8.dp, vertical = 6.dp), style = MaterialTheme.typography.labelMedium)
            }
        }
        if (canReact) TextButton(onClick = onAdd, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("+") }
    }
    details?.let { emoji ->
        AlertDialog(onDismissRequest = { details = null }, title = { Text("$emoji Reactions") }, text = {
            Column { reactions.filter { it.reactionEmoji == emoji }.forEach { reaction ->
                Text(if (reaction.outgoing) "You · ${messageDeliveryLabel(reaction)}" else reaction.sender)
                if (reaction.outgoing) reaction.deliveryDetail?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            } }
        }, confirmButton = { TextButton(onClick = { details = null }) { Text("Done") } })
    }
}
