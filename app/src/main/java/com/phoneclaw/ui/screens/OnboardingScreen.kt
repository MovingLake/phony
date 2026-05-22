package com.phoneclaw.ui.screens

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phoneclaw.data.AIProvider
import com.phoneclaw.data.ClaudeModel
import com.phoneclaw.data.GeminiModel
import com.phoneclaw.data.OpenAIModel
import com.phoneclaw.service.PhoneclawAccessibilityService

@Composable
fun OnboardingScreen(
    // Provider
    selectedProvider: AIProvider,
    onProviderChange: (AIProvider) -> Unit,
    // Gemini
    geminiApiKey: String,
    onGeminiApiKeyChange: (String) -> Unit,
    onSaveGeminiApiKey: () -> Unit,
    selectedGeminiModel: String,
    onGeminiModelChange: (String) -> Unit,
    // Claude
    claudeApiKey: String,
    onClaudeApiKeyChange: (String) -> Unit,
    onSaveClaudeApiKey: () -> Unit,
    selectedClaudeModel: String,
    onClaudeModelChange: (String) -> Unit,
    // OpenAI
    openaiApiKey: String,
    onOpenaiApiKeyChange: (String) -> Unit,
    onSaveOpenaiApiKey: () -> Unit,
    selectedOpenaiModel: String,
    onOpenaiModelChange: (String) -> Unit,
    // Permissions
    hasOverlayPermission: Boolean,
    hasMicPermission: Boolean,
    isAccessibilityEnabled: Boolean,
) {
    val context = LocalContext.current
    val micPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    val activeKey = when (selectedProvider) {
        AIProvider.GEMINI -> geminiApiKey
        AIProvider.CLAUDE -> claudeApiKey
        AIProvider.OPENAI -> openaiApiKey
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(colors = listOf(Color(0xFF0D0D0F), Color(0xFF1A1030))))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(48.dp))

            // Logo
            Box(
                modifier = Modifier
                    .size(96.dp)
                    .clip(CircleShape)
                    .background(Brush.radialGradient(colors = listOf(Color(0xFF9B81E8), Color(0xFF4527A0)))),
                contentAlignment = Alignment.Center
            ) { Text("🦀", fontSize = 48.sp) }

            Spacer(Modifier.height(20.dp))

            Text("Phony", style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold, color = Color(0xFF9B81E8))
            Text("Your AI assistant, running on your phone",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp, bottom = 32.dp))

            // ── AI Provider card ───────────────────────────────────────────
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(Modifier.padding(20.dp)) {
                    Text("AI Provider", style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold)
                    Text("Choose which AI model powers Phony.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp, bottom = 16.dp))

                    // Provider tab row
                    TabRow(
                        selectedTabIndex = selectedProvider.ordinal,
                        containerColor = MaterialTheme.colorScheme.surface,
                        contentColor = Color(0xFF9B81E8),
                        modifier = Modifier.clip(RoundedCornerShape(12.dp))
                    ) {
                        AIProvider.entries.forEachIndexed { index, provider ->
                            Tab(
                                selected = selectedProvider == provider,
                                onClick = { onProviderChange(provider) },
                                text = { Text(provider.displayName, fontSize = 13.sp) }
                            )
                        }
                    }

                    Spacer(Modifier.height(16.dp))

                    // Per-provider content
                    when (selectedProvider) {
                        AIProvider.GEMINI -> ProviderKeySection(
                            key = geminiApiKey,
                            onKeyChange = onGeminiApiKeyChange,
                            onSave = onSaveGeminiApiKey,
                            keyLabel = "AIza…",
                            getKeyUrl = "https://aistudio.google.com/app/apikey",
                            getKeyLabel = "Get a free key at aistudio.google.com",
                            models = GeminiModel.entries.map { it.modelId to it.displayName },
                            selectedModel = selectedGeminiModel,
                            onModelChange = onGeminiModelChange,
                        )
                        AIProvider.CLAUDE -> ProviderKeySection(
                            key = claudeApiKey,
                            onKeyChange = onClaudeApiKeyChange,
                            onSave = onSaveClaudeApiKey,
                            keyLabel = "sk-ant-…",
                            getKeyUrl = "https://console.anthropic.com/settings/keys",
                            getKeyLabel = "Get a key at console.anthropic.com",
                            models = ClaudeModel.entries.map { it.modelId to it.displayName },
                            selectedModel = selectedClaudeModel,
                            onModelChange = onClaudeModelChange,
                        )
                        AIProvider.OPENAI -> ProviderKeySection(
                            key = openaiApiKey,
                            onKeyChange = onOpenaiApiKeyChange,
                            onSave = onSaveOpenaiApiKey,
                            keyLabel = "sk-…",
                            getKeyUrl = "https://platform.openai.com/api-keys",
                            getKeyLabel = "Get a key at platform.openai.com",
                            models = OpenAIModel.entries.map { it.modelId to it.displayName },
                            selectedModel = selectedOpenaiModel,
                            onModelChange = onOpenaiModelChange,
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // ── Permissions ────────────────────────────────────────────────
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(Modifier.padding(20.dp)) {
                    Text("Required Permissions", style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 12.dp))

                    PermissionRow(Icons.Default.Accessibility, "Accessibility Service",
                        "See your screen and perform actions on your behalf",
                        isAccessibilityEnabled) {
                        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                            .apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK })
                    }
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp),
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                    PermissionRow(Icons.Default.Layers, "Draw over apps",
                        "Show the floating Phony button", hasOverlayPermission) {
                        context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:${context.packageName}"))
                            .apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK })
                    }
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp),
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                    PermissionRow(Icons.Default.Mic, "Microphone",
                        "Listen to your voice commands", hasMicPermission) {
                        micPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // ── Status banner ──────────────────────────────────────────────
            val allReady = isAccessibilityEnabled && hasOverlayPermission &&
                           hasMicPermission && activeKey.isNotBlank()
            AnimatedVisibility(visible = allReady, enter = fadeIn() + slideInVertically()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1B5E20)),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.CheckCircle, null, tint = Color(0xFF81C784))
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text("Phony is active!", fontWeight = FontWeight.Bold, color = Color(0xFF81C784))
                            Text("Tap the floating 🦀 button on your screen to get started.",
                                style = MaterialTheme.typography.bodySmall, color = Color(0xFFA5D6A7))
                        }
                    }
                }
            }

            Spacer(Modifier.height(24.dp))

            // ── Try saying… ────────────────────────────────────────────────
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(Modifier.padding(20.dp)) {
                    Text("Try saying…", style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 10.dp))
                    listOf(
                        "🗺️" to "Open Maps and find good taco spots nearby",
                        "📅" to "What meetings do I have today?",
                        "📝" to "Open my notes and add a reminder to call mom",
                        "🎵" to "Open Spotify and play something chill",
                        "📱" to "Open Settings and check my storage",
                    ).forEach { (emoji, text) ->
                        Row(modifier = Modifier.padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text(emoji, fontSize = 18.sp)
                            Spacer(Modifier.width(10.dp))
                            Text("\"$text\"", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProviderKeySection(
    key: String,
    onKeyChange: (String) -> Unit,
    onSave: () -> Unit,
    keyLabel: String,
    getKeyUrl: String,
    getKeyLabel: String,
    models: List<Pair<String, String>>,   // modelId to displayName
    selectedModel: String,
    onModelChange: (String) -> Unit,
) {
    val context = LocalContext.current
    var showKey by remember { mutableStateOf(false) }
    var keySaved by remember { mutableStateOf(false) }
    var modelExpanded by remember { mutableStateOf(false) }
    val selectedDisplayName = models.firstOrNull { it.first == selectedModel }?.second
        ?: models.firstOrNull()?.second ?: ""

    OutlinedTextField(
        value = key,
        onValueChange = { onKeyChange(it); keySaved = false },
        label = { Text(keyLabel) },
        singleLine = true,
        visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        trailingIcon = {
            IconButton(onClick = { showKey = !showKey }) {
                Icon(if (showKey) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                    contentDescription = if (showKey) "Hide key" else "Show key")
            }
        },
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp)
    )

    Spacer(Modifier.height(10.dp))

    Button(
        onClick = { onSave(); keySaved = true },
        enabled = key.length > 10,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp)
    ) {
        Icon(if (keySaved) Icons.Default.Check else Icons.Default.Save, null,
            modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(if (keySaved) "Key saved ✓" else "Save API Key")
    }

    Spacer(Modifier.height(10.dp))

    // Model selector
    ExposedDropdownMenuBox(
        expanded = modelExpanded,
        onExpandedChange = { modelExpanded = it },
        modifier = Modifier.fillMaxWidth()
    ) {
        OutlinedTextField(
            value = selectedDisplayName,
            onValueChange = {},
            readOnly = true,
            label = { Text("Model") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = modelExpanded) },
            modifier = Modifier.menuAnchor().fillMaxWidth(),
            shape = RoundedCornerShape(12.dp)
        )
        ExposedDropdownMenu(expanded = modelExpanded, onDismissRequest = { modelExpanded = false }) {
            models.forEach { (id, name) ->
                DropdownMenuItem(
                    text = { Text(name, style = MaterialTheme.typography.bodyMedium) },
                    onClick = { onModelChange(id); modelExpanded = false }
                )
            }
        }
    }

    TextButton(onClick = {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(getKeyUrl))
                .apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK })
    }) {
        Icon(Icons.Default.OpenInNew, null, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(4.dp))
        Text(getKeyLabel, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun PermissionRow(
    icon: ImageVector,
    title: String,
    description: String,
    granted: Boolean,
    onGrant: () -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null,
            tint = if (granted) Color(0xFF81C784) else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(description, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (granted) {
            Text("✓", color = Color(0xFF81C784), fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 8.dp))
        } else {
            TextButton(onClick = onGrant) {
                Text("Grant", color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}
