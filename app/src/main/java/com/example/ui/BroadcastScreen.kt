package com.example.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.RadioSettings
import com.example.streaming.StreamState
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BroadcastScreen(
    viewModel: RadioViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()

    // ViewModel State bindings
    val radioSettings by viewModel.settingsState.collectAsStateWithLifecycle()
    val broadcastHistory by viewModel.historyState.collectAsStateWithLifecycle()
    val streamState by viewModel.streamState.collectAsStateWithLifecycle()
    val liveAmplitude by viewModel.liveAmplitude.collectAsStateWithLifecycle()
    val isStreamingSpeech by viewModel.isStreamingSpeech.collectAsStateWithLifecycle()
    val streamerError by viewModel.streamerError.collectAsStateWithLifecycle()

    val typedText by viewModel.typedText.collectAsStateWithLifecycle()
    val pitch by viewModel.pitch.collectAsStateWithLifecycle()
    val speed by viewModel.speed.collectAsStateWithLifecycle()

    val isTtsInitialized by viewModel.isTtsInitialized.collectAsStateWithLifecycle()
    val availableLocales by viewModel.availableLocales.collectAsStateWithLifecycle()
    val currentLocale by viewModel.currentLocale.collectAsStateWithLifecycle()

    val isLiveMonitoring by viewModel.isLiveMonitoring.collectAsStateWithLifecycle()
    val localMonitorError by viewModel.localMonitorError.collectAsStateWithLifecycle()
    val synthesizedClips by viewModel.synthesizedClips.collectAsStateWithLifecycle()

    // local UI states
    var showSettings by remember { mutableStateOf(false) }
    var tempHost by remember { mutableStateOf(radioSettings.host) }
    var tempPort by remember { mutableStateOf(radioSettings.port.toString()) }
    var tempMountpoint by remember { mutableStateOf(radioSettings.mountpoint) }
    var tempUsername by remember { mutableStateOf(radioSettings.username) }
    var tempPassword by remember { mutableStateOf(radioSettings.password) }
    var tempAutoStream by remember { mutableStateOf(radioSettings.autoStreamOnSpace) }
    var tempLocalMonitor by remember { mutableStateOf(radioSettings.localVoiceMonitor) }

    var selectedLocaleText by remember(currentLocale) { mutableStateOf(currentLocale.displayName) }
    var localeSelectorExpanded by remember { mutableStateOf(false) }

    // Sync settings form once loaded
    LaunchedEffect(radioSettings) {
        tempHost = radioSettings.host
        tempPort = radioSettings.port.toString()
        tempMountpoint = radioSettings.mountpoint
        tempUsername = radioSettings.username
        tempPassword = radioSettings.password
        tempAutoStream = radioSettings.autoStreamOnSpace
        tempLocalMonitor = radioSettings.localVoiceMonitor
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column(
                        modifier = Modifier.padding(start = 4.dp),
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = "Live Broadcaster",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = (-0.5).sp,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.padding(top = 2.dp)
                        ) {
                            val dotColor = when (streamState) {
                                StreamState.CONNECTED -> Color(0xFFB3261E)
                                StreamState.CONNECTING -> Color(0xFFFF9800)
                                StreamState.ERROR -> Color(0xFFB3261E)
                                StreamState.DISCONNECTED -> Color(0xFF757575)
                            }
                            val subLabel = when (streamState) {
                                StreamState.CONNECTED -> "Zeno.fm • Connected"
                                StreamState.CONNECTING -> "Handshaking..."
                                StreamState.ERROR -> "Zeno.fm • Error"
                                StreamState.DISCONNECTED -> "Zeno.fm • Offline"
                            }
                            // Status Dot
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .background(color = dotColor, shape = CircleShape)
                            )
                            Text(
                                text = subLabel,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.secondary,
                                letterSpacing = 0.2.sp
                            )
                        }
                    }
                },
                actions = {
                    IconButton(
                        onClick = { showSettings = !showSettings },
                        modifier = Modifier
                            .padding(end = 12.dp)
                            .size(44.dp)
                            .background(
                                color = if (showSettings) MaterialTheme.colorScheme.primaryContainer else Color(0xFFF3F4F9),
                                shape = CircleShape
                            )
                    ) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Server Configuration",
                            tint = if (showSettings) MaterialTheme.colorScheme.onPrimaryContainer else Color(0xFF49454F),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        modifier = modifier
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(MaterialTheme.colorScheme.background)
                .verticalScroll(scrollState)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 1. Connection Status Badge & Controls
            StatusControlCard(
                streamState = streamState,
                error = streamerError,
                onConnectClick = { viewModel.connectStream() },
                onDisconnectClick = { viewModel.disconnectStream() }
            )

            // 2. Waveform Meter Indicator
            SignalWaveformCard(
                amplitude = liveAmplitude,
                isSpeaking = isStreamingSpeech,
                streamState = streamState
            )

            // 3. Settings Drawer Expandable Component
            AnimatedVisibility(visible = showSettings) {
                ServerSettingsCard(
                    host = tempHost,
                    port = tempPort,
                    mount = tempMountpoint,
                    username = tempUsername,
                    password = tempPassword,
                    autoStream = tempAutoStream,
                    localMonitor = tempLocalMonitor,
                    onHostChange = { tempHost = it },
                    onPortChange = { tempPort = it },
                    onMountChange = { tempMountpoint = it },
                    onUserChange = { tempUsername = it },
                    onPasswordChange = { tempPassword = it },
                    onAutoStreamToggle = { tempAutoStream = it },
                    onLocalMonitorToggle = { tempLocalMonitor = it },
                    onSave = {
                        val parsedPort = tempPort.toIntOrNull() ?: 80
                        viewModel.saveSettings(
                            RadioSettings(
                                host = tempHost.trim(),
                                port = parsedPort,
                                mountpoint = tempMountpoint.trim(),
                                username = tempUsername.trim(),
                                password = tempPassword,
                                autoStreamOnSpace = tempAutoStream,
                                localVoiceMonitor = tempLocalMonitor
                            )
                        )
                        showSettings = false
                    },
                    onPreFillDemo = {
                        tempHost = "stream.zeno.fm"
                        tempPort = "80"
                        tempMountpoint = "demo_mnt"
                        tempUsername = "source"
                        tempPassword = "password123"
                    }
                )
            }

            // 4. Voice Controls Drawer (Language selector / speech speed)
            VoiceSettingsCard(
                isTtsInitialized = isTtsInitialized,
                availableLocales = availableLocales,
                currentLocale = currentLocale,
                pitch = pitch,
                speed = speed,
                expanded = localeSelectorExpanded,
                selectedLocaleText = selectedLocaleText,
                onLanguageChange = { locale ->
                    selectedLocaleText = locale.displayName
                    viewModel.setLanguage(locale)
                    localeSelectorExpanded = false
                },
                onLocaleSelectorToggle = { localeSelectorExpanded = !localeSelectorExpanded },
                onTuningChange = { p, r -> viewModel.updateVoiceTuning(p, r) }
            )

            // 5. Typing Input & Broadcast Trigger Action
            TypingConsoleCard(
                typedText = typedText,
                isTtsReady = isTtsInitialized,
                streamState = streamState,
                autoStream = radioSettings.autoStreamOnSpace,
                onValueChange = { viewModel.updateTypedText(it) },
                onBroadcastClick = { viewModel.triggerManualSpeech() }
            )

            // 5b. Synthesized Audio Review & Transmit Queue
            TtsClipReviewBoard(
                clips = synthesizedClips,
                onPlayLocally = { viewModel.playClipLocally(it) },
                onTransmit = { viewModel.transmitClip(it) },
                onDelete = { viewModel.deleteClip(it) },
                onClearAll = { viewModel.clearAllClips() }
            )

            // 6. Broadcast History and Common Presets List
            HistoryAndPresetsBoard(
                history = broadcastHistory,
                onPhraseClick = { viewModel.triggerPhraseSpeech(it) },
                onClearHistory = { viewModel.clearHistory() }
            )

            // 7. Playback Monitoring Player (Direct listen loop back)
            ZenoLiveMonitorCard(
                settings = radioSettings,
                isMonitoring = isLiveMonitoring,
                streamState = streamState,
                localMonitorError = localMonitorError,
                onToggleMonitor = {
                    // Build public listening streaming Url: http://{host}:{port}{mountpoint}
                    // For Zeno.fm: usually hosted on stream.zeno.fm/MOUNTPOINT
                    val streamPlayUrl = if (radioSettings.port == 80 || radioSettings.port == 443) {
                        "http://${radioSettings.host}${if (radioSettings.mountpoint.startsWith("/")) "" else "/"}${radioSettings.mountpoint}"
                    } else {
                        "http://${radioSettings.host}:${radioSettings.port}${if (radioSettings.mountpoint.startsWith("/")) "" else "/"}${radioSettings.mountpoint}"
                    }
                    viewModel.toggleLiveNetworkMonitor(streamPlayUrl)
                }
            )
        }
    }
}

@Composable
fun StatusControlCard(
    streamState: StreamState,
    error: String?,
    onConnectClick: () -> Unit,
    onDisconnectClick: () -> Unit
) {
    val statusColor by animateColorAsState(
        targetValue = when (streamState) {
            StreamState.CONNECTED -> Color(0xFFB3261E)
            StreamState.CONNECTING -> Color(0xFFFF9800)
            StreamState.ERROR -> Color(0xFFB3261E)
            StreamState.DISCONNECTED -> Color(0xFF757575)
        }
    )

    val statusLabel = when (streamState) {
        StreamState.CONNECTED -> "CONNECTED • LIVE"
        StreamState.CONNECTING -> "ESTABLISHING HANDSHAKE..."
        StreamState.ERROR -> "CONNECTION FAULT"
        StreamState.DISCONNECTED -> "STREAM OFFLINE"
    }

    Card(
        colors = CardDefaults.cardColors(
            containerColor = Color.White
        ),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFE1E3E1)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        shape = RoundedCornerShape(28.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Status Header
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Pulse Animation Indicator Light
                val infiniteTransition = rememberInfiniteTransition()
                val pulseAlpha by infiniteTransition.animateFloat(
                    initialValue = 0.4f,
                    targetValue = if (streamState == StreamState.CONNECTED) 0.9f else 0.4f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(1000, easing = EaseInOut),
                        repeatMode = RepeatMode.Reverse
                    )
                )

                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .background(color = statusColor.copy(alpha = pulseAlpha), shape = CircleShape)
                )

                Column {
                    Text(
                        text = statusLabel,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp,
                        color = if (streamState == StreamState.CONNECTED) Color(0xFFB3261E) else Color(0xFF49454F)
                    )
                    Text(
                        text = "Zeno.fm Icecast Broadcasting Server",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Normal,
                        color = Color(0xFF938F99)
                    )
                }
            }

            // Error display
            if (error != null) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.8f),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = "Error",
                            tint = Color(0xFFB3261E)
                        )
                        Text(
                            text = error,
                            color = Color(0xFF21005D),
                            fontSize = 12.sp,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            // Connection Switch Trigger Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (streamState == StreamState.DISCONNECTED || streamState == StreamState.ERROR) {
                    Button(
                        onClick = onConnectClick,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF6750A4),
                            contentColor = Color.White
                        ),
                        modifier = Modifier.weight(1f).height(56.dp),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = "Connect",
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text("Connect Broadcast", fontWeight = FontWeight.ExtraBold, fontSize = 15.sp)
                    }
                } else {
                    Button(
                        onClick = onDisconnectClick,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFFB3261E),
                            contentColor = Color.White
                        ),
                        modifier = Modifier.weight(1f).height(56.dp),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Stop",
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text("Stop Streaming", fontWeight = FontWeight.ExtraBold, fontSize = 15.sp)
                    }
                }
            }
        }
    }
}

@Composable
fun SignalWaveformCard(
    amplitude: Float,
    isSpeaking: Boolean,
    streamState: StreamState
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFF1C1B1F) // Hardcoded dark theme style background matching the design html
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        shape = RoundedCornerShape(28.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Broadcast Monitor",
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    color = Color(0xFFD0BCFF),
                    letterSpacing = 0.5.sp
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Column(horizontalAlignment = Alignment.End) {
                        Text("Latency", fontSize = 9.sp, color = Color(0xFF938F99), fontWeight = FontWeight.Light)
                        Text("142ms", fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = Color.White)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("Bitrate", fontSize = 9.sp, color = Color(0xFF938F99), fontWeight = FontWeight.Light)
                        Text("128kbps", fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = Color.White)
                    }
                }
            }

            // Interactive dynamic Audio wave drawing canvas
            val infiniteTransition = rememberInfiniteTransition()
            val animatedPhase by infiniteTransition.animateFloat(
                initialValue = 0f,
                targetValue = 2 * Math.PI.toFloat(),
                animationSpec = infiniteRepeatable(
                    animation = tween(1200, easing = LinearEasing),
                    repeatMode = RepeatMode.Restart
                )
            )

            // Dynamic volume signal level
            val smoothAmplitude by animateFloatAsState(
                targetValue = if (isSpeaking) amplitude.coerceAtLeast(0.15f) else if (streamState == StreamState.CONNECTED) 0.04f else 0.01f,
                animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow)
            )

            val waveformColor = Color(0xFFD0BCFF)

            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF2B2930)) // Dark Overlay background matching the design html
                    .padding(vertical = 6.dp)
            ) {
                val width = size.width
                val height = size.height
                val centerY = height / 2f
                val numBars = 36
                val barWidth = width / (numBars * 1.5f)
                val gap = barWidth * 0.5f

                for (i in 0 until numBars) {
                    val t = i.toFloat() / numBars.toFloat()
                    val waveFactor = Math.sin((t * 2 * Math.PI + animatedPhase).toDouble()).toFloat()
                    val scale = (0.3f + 0.7f * waveFactor * waveFactor) * smoothAmplitude
                    val barHeight = (height * 0.8f) * scale

                    val x = i * (barWidth + gap) + (width - (numBars * (barWidth + gap) - gap)) / 2f
                    val y1 = centerY - barHeight / 2f

                    drawRoundRect(
                        color = waveformColor.copy(alpha = if (isSpeaking) 1.0f else 0.5f),
                        topLeft = Offset(x, y1),
                        size = Size(barWidth, barHeight.coerceAtLeast(4f)),
                        cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f)
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("ZENO MOUNT POINT", fontSize = 9.sp, color = Color(0xFF938F99), letterSpacing = 0.5.sp)
                    Text("/live_stream_9921", fontSize = 13.sp, color = Color.White, fontWeight = FontWeight.Medium)
                }

                Surface(
                    color = Color(0xFF2B2930),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .background(color = if (streamState == StreamState.CONNECTED) Color(0xFFB3261E) else Color.Gray, shape = CircleShape)
                        )
                        Text(
                            text = if (streamState == StreamState.CONNECTED) "LIVE" else "UNLINKED",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (streamState == StreamState.CONNECTED) Color(0xFFF2B8B5) else Color.Gray,
                            letterSpacing = 0.5.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ServerSettingsCard(
    host: String,
    port: String,
    mount: String,
    username: String,
    password: String,
    autoStream: Boolean,
    localMonitor: Boolean,
    onHostChange: (String) -> Unit,
    onPortChange: (String) -> Unit,
    onMountChange: (String) -> Unit,
    onUserChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onAutoStreamToggle: (Boolean) -> Unit,
    onLocalMonitorToggle: (Boolean) -> Unit,
    onSave: () -> Unit,
    onPreFillDemo: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = Color.White
        ),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFE1E3E1)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        shape = RoundedCornerShape(28.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Server Configuration",
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    color = Color(0xFF1C1B1F)
                )
                TextButton(onClick = onPreFillDemo) {
                    Text(
                        text = "Pre-fill Demo Link",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF6750A4)
                    )
                }
            }

            OutlinedTextField(
                value = host,
                onValueChange = onHostChange,
                label = { Text("Broadcasting Host / Server Domain") },
                placeholder = { Text("e.g. stream.zeno.fm") },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedBorderColor = Color(0xFFE1E3E1),
                    focusedBorderColor = Color(0xFF6750A4)
                ),
                singleLine = true
            )

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = port,
                    onValueChange = onPortChange,
                    label = { Text("Port") },
                    placeholder = { Text("80") },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        unfocusedBorderColor = Color(0xFFE1E3E1),
                        focusedBorderColor = Color(0xFF6750A4)
                    ),
                    singleLine = true
                )

                OutlinedTextField(
                    value = mount,
                    onValueChange = onMountChange,
                    label = { Text("Mountpoint") },
                    placeholder = { Text("e.g. demo_mnt") },
                    modifier = Modifier.weight(2f),
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        unfocusedBorderColor = Color(0xFFE1E3E1),
                        focusedBorderColor = Color(0xFF6750A4)
                    ),
                    singleLine = true
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = username,
                    onValueChange = onUserChange,
                    label = { Text("Source User") },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        unfocusedBorderColor = Color(0xFFE1E3E1),
                        focusedBorderColor = Color(0xFF6750A4)
                    ),
                    singleLine = true
                )

                OutlinedTextField(
                    value = password,
                    onValueChange = onPasswordChange,
                    label = { Text("Broadcast Password") },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        unfocusedBorderColor = Color(0xFFE1E3E1),
                        focusedBorderColor = Color(0xFF6750A4)
                    ),
                    singleLine = true
                )
            }

            HorizontalDivider(color = Color(0xFFE1E3E1), thickness = 1.dp)

            // Option switches
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Auto Stream Word-by-Word", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1C1B1F))
                    Text("Speak and stream as you write (triggers on spaces & punctuation)", fontSize = 10.sp, color = Color(0xFF49454F))
                }
                Switch(
                    checked = autoStream,
                    onCheckedChange = onAutoStreamToggle,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = Color(0xFF6750A4)
                    )
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Local Speaker Monitor", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1C1B1F))
                    Text("Hear the spoken phrase from your device speaker simultaneously", fontSize = 10.sp, color = Color(0xFF49454F))
                }
                Switch(
                    checked = localMonitor,
                    onCheckedChange = onLocalMonitorToggle,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = Color(0xFF6750A4)
                    )
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            Button(
                onClick = onSave,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF6750A4),
                    contentColor = Color.White
                ),
                shape = RoundedCornerShape(24.dp)
            ) {
                Icon(imageVector = Icons.Default.Check, contentDescription = "Save settings", modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Apply Connection Parameters", fontWeight = FontWeight.Bold, fontSize = 14.sp)
            }
        }
    }
}

@Composable
fun VoiceSettingsCard(
    isTtsInitialized: Boolean,
    availableLocales: List<Locale>,
    currentLocale: Locale,
    pitch: Float,
    speed: Float,
    expanded: Boolean,
    selectedLocaleText: String,
    onLanguageChange: (Locale) -> Unit,
    onLocaleSelectorToggle: () -> Unit,
    onTuningChange: (Float, Float) -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = Color.White
        ),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFE1E3E1)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        shape = RoundedCornerShape(28.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "Voice Synthesis Adjustments",
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                color = Color(0xFF1C1B1F)
            )

            if (!isTtsInitialized) {
                Text(
                    text = "Initialising System Voice Engine...",
                    fontSize = 12.sp,
                    color = Color(0xFF938F99),
                    fontWeight = FontWeight.Medium
                )
            } else {
                // Dropdown Language field
                Box(modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = onLocaleSelectorToggle,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFE1E3E1)),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = Color(0xFF1C1B1F)
                        )
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Language: $selectedLocaleText",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium
                            )
                            Icon(
                                imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                contentDescription = "Language toggle",
                                tint = Color(0xFF49454F)
                            )
                        }
                    }

                    DropdownMenu(
                        expanded = expanded,
                        onDismissRequest = onLocaleSelectorToggle,
                        modifier = Modifier
                            .fillMaxWidth(0.85f)
                            .heightIn(max = 280.dp)
                            .background(Color.White)
                    ) {
                        availableLocales.forEach { locale ->
                            DropdownMenuItem(
                                text = { Text(locale.displayName, fontSize = 14.sp, fontWeight = FontWeight.Normal, color = Color(0xFF1C1B1F)) },
                                onClick = { onLanguageChange(locale) }
                            )
                        }
                    }
                }

                HorizontalDivider(color = Color(0xFFF1F1F1), thickness = 1.dp)

                // Tuning Sliders
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Speech Pitch: ${String.format("%.1fx", pitch)}", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1C1B1F))
                        Text(
                            text = when {
                                pitch < 0.8f -> "Deep voice"
                                pitch > 1.3f -> "High-pitched"
                                else -> "Standard"
                            },
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF6750A4)
                        )
                    }
                    Slider(
                        value = pitch,
                        onValueChange = { onTuningChange(it, speed) },
                        valueRange = 0.5f..2.0f,
                        steps = 15,
                        colors = SliderDefaults.colors(
                            thumbColor = Color(0xFF6750A4),
                            activeTrackColor = Color(0xFF6750A4),
                            inactiveTrackColor = Color(0xFFEADDFF)
                        )
                    )
                }

                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Speaking Speed: ${String.format("%.1fx", speed)}", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1C1B1F))
                        Text(
                            text = when {
                                speed < 0.8f -> "Slow speed"
                                speed > 1.3f -> "Fast speaking"
                                else -> "Normal tempo"
                            },
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF6750A4)
                        )
                    }
                    Slider(
                        value = speed,
                        onValueChange = { onTuningChange(pitch, it) },
                        valueRange = 0.5f..2.0f,
                        steps = 15,
                        colors = SliderDefaults.colors(
                            thumbColor = Color(0xFF6750A4),
                            activeTrackColor = Color(0xFF6750A4),
                            inactiveTrackColor = Color(0xFFEADDFF)
                        )
                    )
                }
            }
        }
    }
}

@Composable
fun TypingConsoleCard(
    typedText: String,
    isTtsReady: Boolean,
    streamState: StreamState,
    autoStream: Boolean,
    onValueChange: (String) -> Unit,
    onBroadcastClick: () -> Unit
) {
    var showSmartKeyboard by remember { mutableStateOf(true) }
    var activeTab by remember { mutableStateOf("Atalhos") } // "Atalhos", "Fórmulas", "Teclado Rápido"

    Card(
        colors = CardDefaults.cardColors(
            containerColor = Color.White
        ),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFE1E3E1)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        shape = RoundedCornerShape(28.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Header Badge bar & Smart Keyboard Toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "TEXT-TO-SPEECH ENGINE",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF6750A4),
                        letterSpacing = 1.sp
                    )
                    Surface(
                        color = Color(0xFFEADDFF),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(
                            text = if (isTtsReady) "Ready" else "Initializing",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF21005D),
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }
                }

                FilledTonalButton(
                    onClick = { showSmartKeyboard = !showSmartKeyboard },
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    modifier = Modifier.height(32.dp),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = if (showSmartKeyboard) Color(0xFFEADDFF) else Color(0xFFF3F4F9),
                        contentColor = if (showSmartKeyboard) Color(0xFF21005D) else Color(0xFF49454F)
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = "Smart Keyboard",
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (showSmartKeyboard) "Fechar Teclado" else "Teclado Inteligente",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            OutlinedTextField(
                value = typedText,
                onValueChange = onValueChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 110.dp),
                placeholder = {
                    Text(
                        text = "Type here to stream audio in real-time. With Autostream active, tap Space to synthesis...",
                        fontSize = 15.sp,
                        color = Color(0xFF938F99)
                    )
                },
                enabled = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = 16.sp, color = Color(0xFF1C1B1F)),
                shape = RoundedCornerShape(16.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedContainerColor = Color(0xFFF7F9FC),
                    focusedContainerColor = Color(0xFFF7F9FC),
                    unfocusedBorderColor = Color(0xFFE1E3E1),
                    focusedBorderColor = Color(0xFF6750A4)
                )
            )

            // Dynamic Inline Smart Keyboard Panel
            AnimatedVisibility(visible = showSmartKeyboard) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFFF7F9FC), shape = RoundedCornerShape(20.dp))
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Keyboard Tabs
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        val tabs = listOf("Atalhos", "Fórmulas", "Teclado Rápido")
                        tabs.forEach { tabName ->
                            val isSelected = activeTab == tabName
                            Button(
                                onClick = { activeTab = tabName },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (isSelected) Color(0xFF6750A4) else Color(0xFFE1E3E1),
                                    contentColor = if (isSelected) Color.White else Color(0xFF49454F)
                                ),
                                shape = RoundedCornerShape(12.dp),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(38.dp)
                            ) {
                                Text(
                                    text = tabName,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    }

                    when (activeTab) {
                        "Atalhos" -> {
                            // Large Broadcaster Quick Phrase Shortcut keys
                            val shortcutsList = listOf(
                                "📻 Ao Vivo" to "Rádio Ao Vivo! ",
                                "⏰ Hora Certa" to "Hora certa de acompanhar a sua rádio favorita! ",
                                "🎁 Promoção!" to "Participe já da nossa grande chuva de prêmios! ",
                                "🎵 Sucesso" to "Tocando agora o seu maior pedido de sucesso! ",
                                "💬 Ouvinte" to "Alô ouvinte! Mande agora sua mensagem no nosso chat! ",
                                "🔥 Nº 1" to "Você ligado na rádio que é o primeiro lugar absoluto na internet! ",
                                "🔊 Solta o Som!" to "Aumenta o som e sinta essa vibração positiva! ",
                                "👋 Olá Galera!" to "Olá galera sintonizada! O show começou! ",
                                "💖 No Coração" to "Programação especial feita direto no seu coração. ",
                                "💥 Sintonize" to "Deixe sintonizado no melhor áudio e curta! "
                            )

                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    text = "Frases de Efeito (Toque para Inserir):",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF6750A4)
                                )
                                
                                // Display in horizontal lazy rows or lists to make it touch friendly without breaking flow layout constraints
                                LazyRow(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    items(shortcutsList.chunked(5).getOrNull(0) ?: emptyList()) { (label, phrase) ->
                                        Surface(
                                            modifier = Modifier
                                                .clickable {
                                                    val currentText = typedText
                                                    val newText = if (currentText.endsWith(" ") || currentText.isEmpty()) {
                                                        currentText + phrase
                                                    } else {
                                                        "$currentText $phrase"
                                                    }
                                                    onValueChange(newText)
                                                },
                                            color = Color.White,
                                            shape = RoundedCornerShape(12.dp),
                                            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF6750A4).copy(alpha = 0.2f))
                                        ) {
                                            Text(
                                                text = label,
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = Color(0xFF21005D),
                                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)
                                            )
                                        }
                                    }
                                }

                                LazyRow(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    items(shortcutsList.chunked(5).getOrNull(1) ?: emptyList()) { (label, phrase) ->
                                        Surface(
                                            modifier = Modifier
                                                .clickable {
                                                    val currentText = typedText
                                                    val newText = if (currentText.endsWith(" ") || currentText.isEmpty()) {
                                                        currentText + phrase
                                                    } else {
                                                        "$currentText $phrase"
                                                    }
                                                    onValueChange(newText)
                                                },
                                            color = Color.White,
                                            shape = RoundedCornerShape(12.dp),
                                            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF6750A4).copy(alpha = 0.2f))
                                        ) {
                                            Text(
                                                text = label,
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = Color(0xFF21005D),
                                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        "Fórmulas" -> {
                            // Three parts of step constructor
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    text = "Fórmula Inteligente de Frases (Crie em 3 toques):",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF6750A4)
                                )
                                
                                val part1 = listOf("Atenção ouvintes, ", "A rádio número um ", "Estamos de volta ", "Sua melhor companhia ")
                                val part2 = listOf("traz as novidades ", "tocando os sucessos ", "conecta você ", "melhora o seu dia ")
                                val part3 = listOf("com muita alegria!", "direto no seu fone!", "em primeiro lugar!", "com sinal espetacular!")

                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    // Row 1
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Text("1. Início: ", fontSize = 10.sp, color = Color(0xFF6750A4), fontWeight = FontWeight.Bold, modifier = Modifier.width(55.dp))
                                        LazyRow(
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            items(part1) { phrase ->
                                                Surface(
                                                    modifier = Modifier.clickable {
                                                        onValueChange(typedText + phrase)
                                                    },
                                                    color = Color(0xFFEADDFF),
                                                    shape = RoundedCornerShape(8.dp)
                                                ) {
                                                    Text(
                                                        text = phrase.trim(),
                                                        fontSize = 11.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        color = Color(0xFF21005D),
                                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
                                                    )
                                                }
                                            }
                                        }
                                    }

                                    // Row 2
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Text("2. Meio: ", fontSize = 10.sp, color = Color(0xFF6750A4), fontWeight = FontWeight.Bold, modifier = Modifier.width(55.dp))
                                        LazyRow(
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            items(part2) { phrase ->
                                                Surface(
                                                    modifier = Modifier.clickable {
                                                        onValueChange(typedText + phrase)
                                                    },
                                                    color = Color(0xFFD0BCFF),
                                                    shape = RoundedCornerShape(8.dp)
                                                ) {
                                                    Text(
                                                        text = phrase.trim(),
                                                        fontSize = 11.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        color = Color(0xFF21005D),
                                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
                                                    )
                                                }
                                            }
                                        }
                                    }

                                    // Row 3
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Text("3. Fim: ", fontSize = 10.sp, color = Color(0xFF6750A4), fontWeight = FontWeight.Bold, modifier = Modifier.width(55.dp))
                                        LazyRow(
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            items(part3) { phrase ->
                                                Surface(
                                                    modifier = Modifier.clickable {
                                                        onValueChange(typedText + phrase)
                                                    },
                                                    color = Color(0xFFE8DEF8),
                                                    shape = RoundedCornerShape(8.dp)
                                                ) {
                                                    Text(
                                                        text = phrase.trim(),
                                                        fontSize = 11.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        color = Color(0xFF21005D),
                                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        "Teclado Rápido" -> {
                            val row1 = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0")
                            val row2 = listOf("!", "?", ",", ".", ":", "-", "@", "📻", "🇧🇷", "❤️")
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    text = "Sinais, Números & Emojis Rápidos:",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF6750A4)
                                )
                                
                                LazyRow(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    items(row1) { key ->
                                        Surface(
                                            modifier = Modifier
                                                .clickable {
                                                    onValueChange(typedText + key)
                                                },
                                            color = Color.White,
                                            shape = RoundedCornerShape(10.dp),
                                            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFE1E3E1))
                                        ) {
                                            Text(
                                                text = key,
                                                fontSize = 15.sp,
                                                fontWeight = FontWeight.ExtraBold,
                                                color = Color(0xFF1C1B1F),
                                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                                            )
                                        }
                                    }
                                }

                                LazyRow(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    items(row2) { key ->
                                        Surface(
                                            modifier = Modifier
                                                .clickable {
                                                    onValueChange(typedText + key)
                                                },
                                            color = Color.White,
                                            shape = RoundedCornerShape(10.dp),
                                            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFE1E3E1))
                                        ) {
                                            Text(
                                                text = key,
                                                fontSize = 15.sp,
                                                fontWeight = FontWeight.ExtraBold,
                                                color = Color(0xFF1C1B1F),
                                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Bottom auxiliary controls - BIG Space, Backspace, Clear
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Large Space Key
                        Button(
                            onClick = { onValueChange(typedText + " ") },
                            colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color(0xFF1C1B1F)),
                            shape = RoundedCornerShape(12.dp),
                            border = androidx.compose.foundation.BorderStroke(2.dp, Color(0xFF6750A4).copy(alpha = 0.3f)),
                            modifier = Modifier
                                .weight(2f)
                                .height(52.dp)
                        ) {
                            Text("📍 ESPAÇO", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        }

                        // Large Backspace Key
                        Button(
                            onClick = {
                                if (typedText.isNotEmpty()) {
                                    onValueChange(typedText.dropLast(1))
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF9DEDC), contentColor = Color(0xFF410E0B)),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .weight(1.2f)
                                .height(52.dp)
                        ) {
                            Text("⌫ Apagar", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }

                        // Large Clear Key
                        Button(
                            onClick = { onValueChange("") },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF2B8B5), contentColor = Color(0xFF601410)),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .weight(1f)
                                .height(52.dp)
                        ) {
                            Text("🗑 Limpar", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            HorizontalDivider(color = Color(0xFFF1F1F1), thickness = 1.dp)

            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                val buttonEnabled = typedText.trim().isNotEmpty() && isTtsReady
                Button(
                    onClick = onBroadcastClick,
                    enabled = buttonEnabled,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (streamState == StreamState.CONNECTED) Color(0xFF6750A4) else Color(0xFF21005D),
                        contentColor = Color.White,
                        disabledContainerColor = Color(0xFFF3F4F9),
                        disabledContentColor = Color(0xFF938F99)
                    ),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(58.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Send,
                        contentDescription = "Speak / Stream",
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = if (streamState == StreamState.CONNECTED) "STREAM TTS TO BROADCAST 📣" else "SPEAK LOCALLY NOW 🗣",
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 15.sp,
                        letterSpacing = 0.5.sp
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        val infiniteTransition = rememberInfiniteTransition()
                        val pulseFactor by infiniteTransition.animateFloat(
                            initialValue = 0.5f,
                            targetValue = 1.0f,
                            animationSpec = infiniteRepeatable(
                                animation = tween(1200, easing = EaseInOut),
                                repeatMode = RepeatMode.Reverse
                            )
                        )
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .background(
                                    color = if (streamState == StreamState.CONNECTED) Color(0xFFB3261E).copy(alpha = pulseFactor) else Color(0xFF6750A4).copy(alpha = pulseFactor),
                                    shape = CircleShape
                                )
                        )
                        Text(
                            text = if (streamState == StreamState.CONNECTED) "BROADCAST TRANSMIT ONLINE" else "OFFLINE LOCAL MONITOR",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (streamState == StreamState.CONNECTED) Color(0xFFB3261E) else Color(0xFF49454F)
                        )
                    }

                    Text(
                        text = "${typedText.length} characters typed",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color(0xFF938F99)
                    )
                }
            }
        }
    }
}

@Composable
fun TtsClipReviewBoard(
    clips: List<SynthesizedClip>,
    onPlayLocally: (String) -> Unit,
    onTransmit: (String) -> Unit,
    onDelete: (String) -> Unit,
    onClearAll: () -> Unit
) {
    if (clips.isEmpty()) {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = Color(0xFFF7F9FC)
            ),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFE1E3E1)),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = "No Clips",
                    tint = Color(0xFF938F99),
                    modifier = Modifier.size(28.dp)
                )
                Text(
                    text = "Pronto para gerar áudio",
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    color = Color(0xFF49454F)
                )
                Text(
                    text = "Digite o texto acima e clique no botão para gerar as faixas de voz. Elas aparecerão aqui para pré-escuta e transmissão detalhada.",
                    fontSize = 12.sp,
                    color = Color(0xFF938F99),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    lineHeight = 16.sp,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }
        }
        return
    }

    Card(
        colors = CardDefaults.cardColors(
            containerColor = Color.White
        ),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFE1E3E1)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "GERADOR DE ÁUDIO & MONITOR (PRÉ-ESCUTA)",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = Color(0xFF6750A4),
                        letterSpacing = 1.sp
                    )
                    Text(
                        text = "Lista de vozes geradas para testar e botar no ar",
                        fontSize = 11.sp,
                        color = Color(0xFF938F99)
                    )
                }

                TextButton(onClick = onClearAll) {
                    Text("Deletar Todos", fontSize = 12.sp, color = Color(0xFFB3261E), fontWeight = FontWeight.Bold)
                }
            }

            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                clips.reversed().forEach { clip ->
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = if (clip.isTransmitted) Color(0xFFF3Fbf5) else Color(0xFFFAF8FF)
                        ),
                        border = androidx.compose.foundation.BorderStroke(
                            width = 1.5.dp,
                            color = if (clip.isTransmitted) Color(0xFF4CAF50).copy(alpha = 0.4f) else Color(0xFF6750A4).copy(alpha = 0.2f)
                        ),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            // Clip Text
                            Text(
                                text = "\"${clip.text}\"",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color(0xFF1C1B1F),
                                lineHeight = 18.sp
                            )

                            // Status tags and metadata row
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Surface(
                                        color = if (clip.isTransmitted) Color(0xFFE8F5E9) else Color(0xFFFFF3E0),
                                        shape = RoundedCornerShape(8.dp)
                                    ) {
                                        Text(
                                            text = if (clip.isTransmitted) "📡 NO AR (STREAMED)" else "⏳ AGUARDANDO PRÉ-ESCUTA",
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.ExtraBold,
                                            color = if (clip.isTransmitted) Color(0xFF2E7D32) else Color(0xFFE65100),
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                        )
                                    }

                                    Surface(
                                        color = Color(0xFFEEEEEE),
                                        shape = RoundedCornerShape(8.dp)
                                    ) {
                                        Text(
                                            text = "${String.format("%.1fs", clip.durationSeconds)}",
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color(0xFF616161),
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp)
                                        )
                                    }
                                }

                                IconButton(
                                    onClick = { onDelete(clip.id) },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Delete,
                                        contentDescription = "Delete",
                                        tint = Color(0xFFB3261E),
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }

                            // Big visual review & transmit buttons designed with accessibility in mind (large text, generous height)
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(
                                    onClick = { onPlayLocally(clip.id) },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (clip.isPlayingLocally) Color(0xFFEADDFF) else Color(0xFFEADDFF).copy(alpha = 0.6f),
                                        contentColor = Color(0xFF21005D)
                                    ),
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(52.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.PlayArrow,
                                        contentDescription = "Ouvir",
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = if (clip.isPlayingLocally) "Tocando..." else "Ouvir Local 🔊",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp
                                    )
                                }

                                Button(
                                    onClick = { onTransmit(clip.id) },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = Color(0xFF6750A4),
                                        contentColor = Color.White
                                    ),
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier
                                        .weight(1.1f)
                                        .height(52.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Send,
                                        contentDescription = "Transmitir",
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "Botar no Ar 📡",
                                        fontWeight = FontWeight.ExtraBold,
                                        fontSize = 13.sp
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun HistoryAndPresetsBoard(
    history: List<com.example.data.BroadcastHistory>,
    onPhraseClick: (String) -> Unit,
    onClearHistory: () -> Unit
) {
    if (history.isEmpty()) return

    Card(
        colors = CardDefaults.cardColors(
            containerColor = Color.White
        ),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFE1E3E1)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        shape = RoundedCornerShape(28.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Broadcast Log & Quick Repeats",
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    color = Color(0xFF1C1B1F)
                )
                TextButton(onClick = onClearHistory) {
                    Text("Clear Log", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFFB3261E))
                }
            }

            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(history) { record ->
                    Surface(
                        color = Color(0xFFF3F4F9),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFE1E3E1)),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.clickable { onPhraseClick(record.text) }
                    ) {
                        Text(
                            text = record.text,
                            maxLines = 1,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color(0xFF49454F),
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ZenoLiveMonitorCard(
    settings: RadioSettings,
    isMonitoring: Boolean,
    streamState: StreamState,
    localMonitorError: String?,
    onToggleMonitor: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = Color.White
        ),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFE1E3E1)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        shape = RoundedCornerShape(28.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "Real-time Live Stream Listener Player",
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                color = Color(0xFF1C1B1F)
            )

            Text(
                text = "Broadcasting networks like Zeno.fm typically enforce a standard server buffer delay of 3–5 seconds. Use this listener player to hear exactly how listeners hear you.",
                fontSize = 12.sp,
                lineHeight = 16.sp,
                color = Color(0xFF49454F)
            )

            if (localMonitorError != null) {
                Text(
                    text = localMonitorError,
                    fontSize = 11.sp,
                    color = Color(0xFFB3261E),
                    fontWeight = FontWeight.Bold
                )
            }

            // Dynamic and clean layout: status row + full-width control button
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .background(
                            color = if (isMonitoring) Color(0xFF4CAF50) else Color(0xFF757575),
                            shape = CircleShape
                        )
                )
                Text(
                    text = if (isMonitoring) "Live Monitor Active" else "Monitor Player Ready",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isMonitoring) Color(0xFF4CAF50) else Color(0xFF49454F)
                )
            }

            Button(
                onClick = onToggleMonitor,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isMonitoring) Color(0xFFB3261E) else Color(0xFF6750A4),
                    contentColor = Color.White
                ),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
            ) {
                Icon(
                    imageVector = if (isMonitoring) Icons.Default.Refresh else Icons.Default.PlayArrow,
                    contentDescription = "Control",
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (isMonitoring) "Mute Listener Player" else "Listen Live Loop Stream",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}
