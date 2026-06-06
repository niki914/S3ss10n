package com.niki914.demo.ui.compose

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.niki914.demo.ChatEffect
import com.niki914.demo.ChatIntent
import com.niki914.demo.ChatViewModel
import com.niki914.demo.DemoTurn
import com.niki914.demo.McpServerEntry
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DemoChatScreen(vm: ChatViewModel) {
    val state = vm.uiState
    val snackbarHostState = remember { SnackbarHostState() }

    var showConfig by rememberSaveable { mutableStateOf(false) }
    var isConfigured by rememberSaveable { mutableStateOf(false) }

    var endpoint by rememberSaveable { mutableStateOf("https://api.deepseek.com/v1/chat/completions") }
    var apiKey by rememberSaveable { mutableStateOf("sk-9961090b5ca3483681fd9f2912d30dc5") }
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

    val backdrop = rememberLayerBackdrop()

    Box(modifier = Modifier.fillMaxSize()) {
        // === Content layer (sampled by backdrop) ===
        Column(
            modifier = Modifier
                .fillMaxSize()
                .layerBackdrop(backdrop)
                .background(MaterialTheme.colorScheme.background)
        ) {
            // spacer for top bar height
            Spacer(modifier = Modifier.height(64.dp))

            // generating indicator
            if (state.isGenerating) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .background(Color.White.copy(alpha = 0.3f))
                ) {}
            }

            // Message list
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                state = listState,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item { Spacer(modifier = Modifier.height(12.dp)) }
                itemsIndexed(items, key = { _, item -> item.key }) { _, item ->
                    when (item) {
                        is UiBubbleItem -> MessageBubble(item)
                        is UiToolStatusItem -> ToolStatusBubble(item)
                    }
                }
                item { Spacer(modifier = Modifier.height(100.dp)) } // space for input bar
            }
        }

        // === Snackbar host ===
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 72.dp)
        )

        // === Floating glass top bar ===
        val topBarDragOffset = remember { Animatable(0f) }
        val topBarScope = rememberCoroutineScope()
        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 48.dp, start = 16.dp, end = 16.dp)
                .pointerInput(Unit) {
                    detectVerticalDragGestures(
                        onDragEnd = {
                            topBarScope.launch {
                                topBarDragOffset.animateTo(0f, spring())
                            }
                        },
                        onDragCancel = {
                            topBarScope.launch {
                                topBarDragOffset.animateTo(0f, spring())
                            }
                        },
                        onVerticalDrag = { _, dragAmount ->
                            topBarScope.launch {
                                val newValue = (topBarDragOffset.value + dragAmount * 0.3f)
                                    .coerceIn(-120f, 120f)
                                topBarDragOffset.snapTo(newValue)
                            }
                        }
                    )
                }
                .drawBackdrop(
                    backdrop = backdrop,
                    shape = { RoundedCornerShape(28.dp) },
                    effects = {
                        vibrancy()
                        blur(8.dp.toPx())
                        lens(
                            refractionHeight = 14.dp.toPx(),
                            refractionAmount = 28.dp.toPx(),
                            chromaticAberration = true
                        )
                    },
                    layerBlock = {
                        val offset = topBarDragOffset.value
                        scaleY = 1f + kotlin.math.abs(offset) * 0.0004f
                        scaleX = 1f - kotlin.math.abs(offset) * 0.0002f
                    },
                    onDrawSurface = { drawRect(Color.White.copy(alpha = 0.20f)) }
                )
                .height(48.dp)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "S3ss10n",
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 12.dp),
                color = Color.White,
                fontWeight = FontWeight.SemiBold
            )
            IconButton(onClick = { showConfig = !showConfig }) {
                Icon(Icons.Default.Settings, contentDescription = null, tint = Color.White)
            }
            IconButton(onClick = { vm.sendIntent(ChatIntent.NewRoom) }) {
                Icon(Icons.Default.Refresh, contentDescription = null, tint = Color.White)
            }
        }

        // === Floating glass input bar ===
        Composer(
            value = input,
            enabled = !state.isGenerating,
            onValueChange = { input = it },
            onSend = sendMessage,
            backdrop = backdrop,
            modifier = Modifier.align(Alignment.BottomCenter)
        )

        // === Config bottom sheet ===
        if (showConfig) {
            ConfigSheet(
                endpoint = endpoint,
                apiKey = apiKey,
                model = model,
                systemPrompt = systemPrompt,
                mcpServers = state.mcpServers,
                backdrop = backdrop,
                onEndpointChange = { endpoint = it },
                onApiKeyChange = { apiKey = it },
                onModelChange = { model = it },
                onSystemPromptChange = { systemPrompt = it },
                onAddMcpServer = { name, url, headersJson ->
                    vm.sendIntent(ChatIntent.AddMcpServer(name, url, headersJson))
                },
                onRemoveMcpServer = { id -> vm.sendIntent(ChatIntent.RemoveMcpServer(id)) },
                onApply = applyConfig,
                onDismiss = { showConfig = false }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConfigSheet(
    endpoint: String,
    apiKey: String,
    model: String,
    systemPrompt: String,
    mcpServers: List<McpServerEntry>,
    backdrop: Backdrop,
    onEndpointChange: (String) -> Unit,
    onApiKeyChange: (String) -> Unit,
    onModelChange: (String) -> Unit,
    onSystemPromptChange: (String) -> Unit,
    onAddMcpServer: (String, String, String) -> Unit,
    onRemoveMcpServer: (String) -> Unit,
    onApply: () -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color.Transparent,
        dragHandle = null
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .drawBackdrop(
                    backdrop = backdrop,
                    shape = { RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp) },
                    effects = {
                        vibrancy()
                        blur(16.dp.toPx())
                        lens(
                            refractionHeight = 20.dp.toPx(),
                            refractionAmount = 40.dp.toPx(),
                            chromaticAberration = true
                        )
                    },
                    onDrawSurface = { drawRect(Color(0xFF1a1a2e).copy(alpha = 0.85f)) }
                )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Handle
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Box(
                        Modifier
                            .width(40.dp)
                            .height(4.dp)
                            .background(Color.White.copy(alpha = 0.3f), RoundedCornerShape(2.dp))
                    )
                }

                Text(
                    "Configuration",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleMedium
                )

                // Text fields
                OutlinedTextField(
                    value = endpoint,
                    onValueChange = onEndpointChange,
                    label = { Text("Endpoint", color = Color.White.copy(alpha = 0.6f)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    colors = glassTextFieldColors()
                )
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = onApiKeyChange,
                    label = { Text("API Key", color = Color.White.copy(alpha = 0.6f)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    colors = glassTextFieldColors()
                )
                OutlinedTextField(
                    value = model,
                    onValueChange = onModelChange,
                    label = { Text("Model", color = Color.White.copy(alpha = 0.6f)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    colors = glassTextFieldColors()
                )
                OutlinedTextField(
                    value = systemPrompt,
                    onValueChange = onSystemPromptChange,
                    label = { Text("System Prompt", color = Color.White.copy(alpha = 0.6f)) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    colors = glassTextFieldColors()
                )

                // MCP Server list
                Text(
                    "MCP Servers",
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.titleSmall
                )
                mcpServers.forEach { server ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .drawBackdrop(
                                backdrop = backdrop,
                                shape = { RoundedCornerShape(12.dp) },
                                effects = {
                                    vibrancy()
                                    blur(2.dp.toPx())
                                    lens(
                                        refractionHeight = 4.dp.toPx(),
                                        refractionAmount = 8.dp.toPx(),
                                        chromaticAberration = true
                                    )
                                },
                                onDrawSurface = { drawRect(Color.White.copy(alpha = 0.10f)) }
                            )
                            .padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                server.name,
                                color = Color.White,
                                fontWeight = FontWeight.Medium,
                                style = MaterialTheme.typography.bodySmall
                            )
                            Text(
                                server.url,
                                color = Color.White.copy(alpha = 0.5f),
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1
                            )
                            if (server.headers.isNotEmpty()) {
                                Text(
                                    "Headers: ${server.headers.keys.joinToString()}",
                                    color = Color.White.copy(alpha = 0.4f),
                                    style = MaterialTheme.typography.labelSmall,
                                    maxLines = 1
                                )
                            }
                        }
                        IconButton(onClick = { onRemoveMcpServer(server.id) }) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = null,
                                tint = Color.White.copy(alpha = 0.5f),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
                // Add MCP form
                var newMcpName by remember { mutableStateOf("") }
                var newMcpUrl by remember { mutableStateOf("") }
                var newMcpHeaders by remember { mutableStateOf("") }
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TextButton(
                        onClick = {
                            newMcpName = "github"
                            newMcpUrl = "https://api.githubcopilot.com/mcp/"
                            newMcpHeaders = GITHUB_MCP_HEADERS_TEMPLATE
                        }
                    ) {
                        Text("GitHub MCP (SSE)", color = Color.White)
                    }
                    TextButton(
                        onClick = {
                            newMcpName = "aslocate"
                            newMcpUrl = "http://127.0.0.1:51338/mcp"
                            newMcpHeaders = ""
                        }
                    ) {
                        Text("aslocate MCP", color = Color.White)
                    }
                }
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = newMcpName,
                        onValueChange = { newMcpName = it },
                        label = { Text("Name", color = Color.White.copy(alpha = 0.5f)) },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        colors = glassTextFieldColors(),
                        textStyle = MaterialTheme.typography.bodySmall
                    )
                    OutlinedTextField(
                        value = newMcpUrl,
                        onValueChange = { newMcpUrl = it },
                        label = { Text("URL", color = Color.White.copy(alpha = 0.5f)) },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        colors = glassTextFieldColors(),
                        textStyle = MaterialTheme.typography.bodySmall
                    )
                    Box(
                        modifier = Modifier
                            .drawBackdrop(
                                backdrop = backdrop,
                                shape = { RoundedCornerShape(16.dp) },
                                effects = {
                                    vibrancy()
                                    blur(2.dp.toPx())
                                    lens(
                                        refractionHeight = 6.dp.toPx(),
                                        refractionAmount = 12.dp.toPx(),
                                        chromaticAberration = true
                                    )
                                },
                                onDrawSurface = { drawRect(Color.White.copy(alpha = 0.18f)) }
                            )
                            .clickable(role = androidx.compose.ui.semantics.Role.Button) {
                                if (newMcpName.isNotBlank() && newMcpUrl.isNotBlank()) {
                                    onAddMcpServer(newMcpName.trim(), newMcpUrl.trim(), newMcpHeaders.trim())
                                    newMcpName = ""
                                    newMcpUrl = ""
                                    newMcpHeaders = ""
                                }
                            }
                            .padding(8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.Add,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
                OutlinedTextField(
                    value = newMcpHeaders,
                    onValueChange = { newMcpHeaders = it },
                    label = { Text("Headers JSON", color = Color.White.copy(alpha = 0.5f)) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    colors = glassTextFieldColors(),
                    textStyle = MaterialTheme.typography.bodySmall
                )

                // Action buttons
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("Cancel", color = Color.White.copy(alpha = 0.6f))
                    }
                    Box(
                        modifier = Modifier
                            .drawBackdrop(
                                backdrop = backdrop,
                                shape = { RoundedCornerShape(24.dp) },
                                effects = {
                                    vibrancy()
                                    blur(2.dp.toPx())
                                    lens(
                                        refractionHeight = 10.dp.toPx(),
                                        refractionAmount = 20.dp.toPx(),
                                        chromaticAberration = true
                                    )
                                },
                                onDrawSurface = { drawRect(Color.White.copy(alpha = 0.24f)) }
                            )
                            .clickable(
                                role = androidx.compose.ui.semantics.Role.Button,
                                onClick = onApply
                            )
                            .padding(horizontal = 24.dp, vertical = 12.dp)
                    ) {
                        Text("Apply", color = Color.White, fontWeight = FontWeight.SemiBold)
                    }
                }

                // Bottom safe area spacer
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun Composer(
    value: String,
    enabled: Boolean,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    backdrop: Backdrop,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) 1.06f else 1f,
        label = "sendScale"
    )

    val composerDragOffset = remember { Animatable(0f) }
    val composerScope = rememberCoroutineScope()

    Row(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 12.dp, vertical = 12.dp)
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragEnd = {
                        composerScope.launch {
                            composerDragOffset.animateTo(0f, spring())
                        }
                    },
                    onDragCancel = {
                        composerScope.launch {
                            composerDragOffset.animateTo(0f, spring())
                        }
                    },
                    onVerticalDrag = { _, dragAmount ->
                        composerScope.launch {
                            val newValue = (composerDragOffset.value + dragAmount * 0.3f)
                                .coerceIn(-120f, 120f)
                            composerDragOffset.snapTo(newValue)
                        }
                    }
                )
            }
            .drawBackdrop(
                backdrop = backdrop,
                shape = { RoundedCornerShape(28.dp) },
                effects = {
                    vibrancy()
                    blur(8.dp.toPx())
                    lens(
                        refractionHeight = 14.dp.toPx(),
                        refractionAmount = 28.dp.toPx(),
                        chromaticAberration = true
                    )
                },
                layerBlock = {
                    val offset = composerDragOffset.value
                    scaleY = 1f + kotlin.math.abs(offset) * 0.0004f
                    scaleX = 1f - kotlin.math.abs(offset) * 0.0002f
                },
                onDrawSurface = { drawRect(Color.White.copy(alpha = 0.22f)) }
            )
            .padding(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier
                .weight(1f)
                .padding(start = 12.dp, end = 4.dp),
            enabled = enabled,
            label = { Text("Message", color = Color.White.copy(alpha = 0.6f)) },
            minLines = 1,
            maxLines = 4,
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                disabledTextColor = Color.White.copy(alpha = 0.4f),
                focusedBorderColor = Color.Transparent,
                unfocusedBorderColor = Color.Transparent,
                cursorColor = Color.White
            )
        )
        Box(
            modifier = Modifier
                .drawBackdrop(
                    backdrop = backdrop,
                    shape = { RoundedCornerShape(24.dp) },
                    effects = {
                        vibrancy()
                        blur(2.dp.toPx())
                        lens(
                            refractionHeight = 10.dp.toPx(),
                            refractionAmount = 20.dp.toPx(),
                            chromaticAberration = true
                        )
                    },
                    layerBlock = {
                        scaleX = pressScale
                        scaleY = pressScale
                    },
                    onDrawSurface = { drawRect(Color.White.copy(alpha = if (pressed) 0.34f else 0.22f)) }
                )
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    role = androidx.compose.ui.semantics.Role.Button,
                    onClick = onSend
                )
                .padding(12.dp),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.AutoMirrored.Filled.Send,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
private fun MessageBubble(item: UiBubbleItem) {
    val isUser = item.role == UiBubbleRole.User
    val alignment = if (isUser) Alignment.End else Alignment.Start
    val containerColor =
        if (isUser) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceVariant
    val contentColor =
        if (isUser) MaterialTheme.colorScheme.onPrimaryContainer
        else MaterialTheme.colorScheme.onSurfaceVariant

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
        horizontalAlignment = alignment
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .background(containerColor, RoundedCornerShape(20.dp))
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

@Composable
private fun ToolStatusBubble(item: UiToolStatusItem) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
        horizontalAlignment = Alignment.Start
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .background(
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    RoundedCornerShape(16.dp)
                )
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (item.isRunning) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Icon(
                    Icons.Default.Check,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                text = item.name,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = if (item.isRunning) "Running" else "Done",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )
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
private fun glassTextFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = Color.White,
    unfocusedTextColor = Color.White,
    disabledTextColor = Color.White.copy(alpha = 0.4f),
    focusedBorderColor = Color.White.copy(alpha = 0.3f),
    unfocusedBorderColor = Color.White.copy(alpha = 0.15f),
    focusedLabelColor = Color.White.copy(alpha = 0.6f),
    unfocusedLabelColor = Color.White.copy(alpha = 0.4f),
    cursorColor = Color.White
)

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

private const val GITHUB_MCP_HEADERS_TEMPLATE = """
{
  "Authorization": "Bearer <paste_github_token>",
  "Accept": "application/json, text/event-stream"
}
"""

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
