package com.niki914.demo.ui.compose

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.niki914.demo.ChatEffect
import com.niki914.demo.ChatIntent
import com.niki914.demo.ChatViewModel
import com.niki914.demo.DemoTurn
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DemoChatScreen(vm: ChatViewModel) {
    val state = vm.uiState
    val snackbarHostState = remember { SnackbarHostState() }

    var showConfig by rememberSaveable { mutableStateOf(false) }
    var isConfigured by rememberSaveable { mutableStateOf(false) }

    var endpoint by rememberSaveable { mutableStateOf("https://api.deepseek.com/v1/chat/completions") }
    var apiKey by rememberSaveable { mutableStateOf("sk-xxx") }
    var model by rememberSaveable { mutableStateOf("deepseek-v4-flash") }
    var systemPrompt by rememberSaveable { mutableStateOf("") }

    var input by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(vm) {
        vm.uiEffect
            .onEach { effect ->
                if (effect == ChatEffect.ConfigUnset) {
                    isConfigured = false
                }
            }
            .map { effect ->
                when (effect) {
                    ChatEffect.ConfigUnset -> "Configuration is not set or invalid"
                    is ChatEffect.ErrorOccurred -> effect.message
                    ChatEffect.NewRoomCreated -> "New session created"
                }
            }
            .collectLatest { message ->
                snackbarHostState.currentSnackbarData?.dismiss()
                snackbarHostState.showSnackbar(message)
            }
    }

    val listState = rememberLazyListState()
    val items = remember(state.pairs) { buildUiItems(state.pairs) }

    LaunchedEffect(items.size) {
        if (items.isNotEmpty()) {
            listState.animateScrollToItem(items.lastIndex)
        }
    }

    val applyConfig: () -> Unit = {
        vm.sendIntent(
            ChatIntent.SetConfig {
                this.endpoint = endpoint
                this.apiKey = apiKey
                this.model = model
                this.systemPrompt = systemPrompt
            }
        )
        isConfigured = true
        showConfig = false
    }

    val sendMessage: () -> Unit = send@{
        val msg = input.trim()
        if (msg.isEmpty()) return@send
        if (!isConfigured) {
            applyConfig()
        }
        vm.sendIntent(ChatIntent.Send(msg))
        input = ""
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("S3ss10n-Demo") },
                actions = {
                    IconButton(onClick = { showConfig = !showConfig }) {
                        Icon(imageVector = Icons.Default.Settings, contentDescription = null)
                    }
                    IconButton(
                        onClick = {
                            vm.sendIntent(ChatIntent.NewRoom)
                        }
                    ) {
                        Icon(imageVector = Icons.Default.Refresh, contentDescription = null)
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (state.isGenerating) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            if (showConfig) {
                ConfigPanel(
                    endpoint = endpoint,
                    apiKey = apiKey,
                    model = model,
                    systemPrompt = systemPrompt,
                    onEndpointChange = { endpoint = it },
                    onApiKeyChange = { apiKey = it },
                    onModelChange = { model = it },
                    onSystemPromptChange = { systemPrompt = it },
                    onApply = applyConfig,
                    onCancel = { showConfig = false }
                )
            }

            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                state = listState,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    Spacer(modifier = Modifier.height(12.dp))
                }
                itemsIndexed(items, key = { _, item -> item.key }) { _, item ->
                    when (item) {
                        is UiBubbleItem -> MessageBubble(item)
                        is UiToolStatusItem -> ToolStatusBubble(item)
                    }
                }
                item {
                    Spacer(modifier = Modifier.height(12.dp))
                }
            }

            Composer(
                value = input,
                enabled = !state.isGenerating,
                onValueChange = { input = it },
                onSend = sendMessage
            )
        }
    }
}

@Composable
private fun ConfigPanel(
    endpoint: String,
    apiKey: String,
    model: String,
    systemPrompt: String,
    onEndpointChange: (String) -> Unit,
    onApiKeyChange: (String) -> Unit,
    onModelChange: (String) -> Unit,
    onSystemPromptChange: (String) -> Unit,
    onApply: () -> Unit,
    onCancel: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(12.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                "Connection settings",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            OutlinedTextField(
                value = endpoint,
                onValueChange = onEndpointChange,
                label = { Text("Endpoint") },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = apiKey,
                onValueChange = onApiKeyChange,
                label = { Text("API Key") },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = model,
                onValueChange = onModelChange,
                label = { Text("Model") },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = systemPrompt,
                onValueChange = onSystemPromptChange,
                label = { Text("System Prompt") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onCancel) {
                    Text("Cancel")
                }
                Spacer(modifier = Modifier.width(8.dp))
                FilledTonalButton(onClick = onApply) {
                    Text("Apply")
                }
            }
        }
    }
}

@Composable
private fun Composer(
    value: String,
    enabled: Boolean,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(12.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.weight(1f),
            enabled = enabled,
            label = { Text("Message") },
            minLines = 1,
            maxLines = 6
        )
        Spacer(modifier = Modifier.width(8.dp))
        Button(onClick = onSend, enabled = enabled && value.isNotBlank()) {
            Icon(imageVector = Icons.Default.Send, contentDescription = null)
        }
    }
}

/**
 * Preview only.
 */
@Preview
@Composable
fun MessageBubblePreview() {
    val item = UiBubbleItem(
        key = "",
        role = UiBubbleRole.Assistant,
        title = "Asd",
        content = "asadsasdasd\n".repeat(5),
        stateLabel = "Generating"
    )
    MessageBubble(item)
}

@Composable
private fun MessageBubble(item: UiBubbleItem) {
    val isUser = item.role == UiBubbleRole.User
    val alignment = if (isUser) Alignment.End else Alignment.Start
    val containerColor =
        if (isUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer
    val contentColor =
        if (isUser) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
        horizontalAlignment = alignment
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(0.92f),
            colors = CardDefaults.cardColors(containerColor = containerColor)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = item.title,
                        style = MaterialTheme.typography.labelMedium,
                        color = contentColor,
                        fontWeight = FontWeight.SemiBold
                    )
                    if (item.stateLabel != null) {
                        Text(
                            text = item.stateLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = contentColor
                        )
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = item.content,
                    style = MaterialTheme.typography.bodyMedium,
                    color = contentColor
                )
            }
        }
    }
}

private sealed interface UiItem {
    val key: String
}

private enum class UiBubbleRole {
    User, Assistant
}

private data class UiBubbleItem(
    override val key: String,
    val role: UiBubbleRole,
    val title: String,
    val content: String,
    val stateLabel: String? = null
) : UiItem

private data class UiToolStatusItem(
    override val key: String,
    val name: String,
    val isRunning: Boolean
) : UiItem

@Composable
private fun ToolStatusBubble(item: UiToolStatusItem) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
        horizontalAlignment = Alignment.Start
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(0.92f),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (item.isRunning) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
                Text(
                    text = item.name,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = if (item.isRunning) "Running" else "Done",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private fun buildUiItems(pairs: List<DemoTurn>): List<UiItem> {
    val out = ArrayList<UiItem>(pairs.size * 2)
    pairs.forEachIndexed { pairIndex, pair ->
        out += UiBubbleItem(
            key = "p${pairIndex}-u",
            role = UiBubbleRole.User,
            title = "User",
            content = pair.userMsg
        )

        out += UiBubbleItem(
            key = "p${pairIndex}-a",
            role = UiBubbleRole.Assistant,
            title = "Assistant",
            content = pair.aiText,
            stateLabel = when {
                pair.isError -> "Failed"
                pair.isGenerating -> "Generating"
                else -> null
            }
        )
    }
    return out
}
