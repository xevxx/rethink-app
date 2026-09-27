/*
 * Copyright 2026 ezelab
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 */
package com.celzero.bravedns.tv.ui.proxy

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import com.celzero.bravedns.tv.ui.common.Surface
import androidx.tv.material3.Text
import com.celzero.bravedns.service.WireguardManager
import com.celzero.bravedns.wireguard.Config
import com.celzero.bravedns.tv.ui.common.TvScreenScaffold
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.StringReader
import java.nio.charset.StandardCharsets
import androidx.compose.material3.Text as M3Text

/**
 * WireGuard tunnel import screen.
 *
 * Phone build supports three sources: QR code, file picker, paste.
 * On TV we expose paste + SAF file picker (no camera). Both paths
 * funnel into [Config.parse] and then
 * [WireguardManager.addConfig].
 *
 * Paste flow:
 *  * Edit-field where the user pastes a `wg0.conf`-style multiline
 *    blob (Interface + Peer sections). Compose's
 *    `OutlinedTextField` handles this — Bluetooth keyboards on TV
 *    can paste with the standard shortcut, and remote-clipboard
 *    apps (e.g. Google TV's app launcher) inject text the same way.
 *  * Optional name field — defaults to upstream's `${WG}{id}`.
 *
 * SAF flow:
 *  * Tap "Open .conf file" — fires an `ACTION_GET_CONTENT` chooser.
 *    This lets users select an installed, remote-friendly file manager
 *    instead of forcing the phone-oriented system DocumentsUI.
 *
 * Save calls `addConfig(parsed, name)`. On success we pop back to
 * Proxy; on parse / save error we render the message inline.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun WgImportScreen(navController: NavController? = null) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var tunnelName by remember { mutableStateOf("") }
    var configText by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var openingFile by remember { mutableStateOf(false) }
    var loadedFileName by remember { mutableStateOf<String?>(null) }

    // Material text fields do not inherit the TV Material color scheme. Supply
    // the TV colors explicitly so text remains legible on the dark TV surface.
    val textFieldColors = OutlinedTextFieldDefaults.colors(
        focusedTextColor = MaterialTheme.colorScheme.onSurface,
        unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
        disabledTextColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
        errorTextColor = MaterialTheme.colorScheme.onSurface,
        cursorColor = MaterialTheme.colorScheme.primary,
        errorCursorColor = MaterialTheme.colorScheme.error,
        focusedBorderColor = MaterialTheme.colorScheme.primary,
        unfocusedBorderColor = MaterialTheme.colorScheme.onSurfaceVariant,
        disabledBorderColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
        errorBorderColor = MaterialTheme.colorScheme.error,
        focusedPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant,
        unfocusedPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant,
        disabledPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
        errorPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    val openDocLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val uri = result.data?.data.takeIf { result.resultCode == Activity.RESULT_OK }
        if (uri == null) {
            openingFile = false
            return@rememberLauncherForActivityResult
        }

        error = null
        loadedFileName = null
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { readTunnelFile(context, uri) }
            }
            result.onSuccess { file ->
                configText = file.contents
                if (tunnelName.isBlank()) {
                    tunnelName = file.name.removeSuffix(".conf")
                }
                loadedFileName = file.name
            }.onFailure { cause ->
                error = "Could not open tunnel file: ${cause.message ?: "unknown error"}"
            }
            openingFile = false
        }
    }

    TvScreenScaffold(
        title = "Add WireGuard tunnel",
        subtitle = "Paste a tunnel .conf or pick a file from storage.",
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Source row — paste-from-clipboard + open-file shortcuts.
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SecondaryButton(
                    label = "Paste from clipboard",
                    onClick = {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                        val pasted = cm?.primaryClip?.getItemAt(0)?.text?.toString().orEmpty()
                        if (pasted.isNotBlank()) {
                            configText = pasted
                            error = null
                            loadedFileName = null
                        } else {
                            error = "Clipboard is empty or not text."
                        }
                    },
                )
                SecondaryButton(
                    label = if (openingFile) "Opening…" else "Open .conf file",
                    onClick = {
                        if (openingFile) return@SecondaryButton
                        // GET_CONTENT allows installed file managers to
                        // participate, unlike OPEN_DOCUMENT which routes
                        // directly through the system DocumentsUI.
                        openingFile = true
                        error = null
                        loadedFileName = null
                        runCatching {
                            val fileIntent = Intent(Intent.ACTION_GET_CONTENT).apply {
                                addCategory(Intent.CATEGORY_OPENABLE)
                                type = "*/*"
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            openDocLauncher.launch(
                                Intent.createChooser(fileIntent, "Choose a file manager"),
                            )
                        }.onFailure {
                            openingFile = false
                            error = "No file picker available on this device."
                        }
                    },
                )
            }

            // Name + body fields.
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                M3Text(
                    text = "Tunnel name (optional)",
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontWeight = FontWeight.SemiBold,
                    ),
                )
                OutlinedTextField(
                    value = tunnelName,
                    onValueChange = { tunnelName = it },
                    placeholder = { M3Text("e.g. mullvad-us-nyc") },
                    singleLine = true,
                    colors = textFieldColors,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                M3Text(
                    text = "Tunnel configuration",
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontWeight = FontWeight.SemiBold,
                    ),
                )
                OutlinedTextField(
                    value = configText,
                    onValueChange = { configText = it },
                    placeholder = {
                        M3Text(
                            "[Interface]\nPrivateKey = …\nAddress = 10.0.0.2/32\n\n[Peer]\nPublicKey = …\nEndpoint = host:port\nAllowedIPs = 0.0.0.0/0",
                        )
                    },
                    colors = textFieldColors,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 220.dp, max = 360.dp),
                )
            }

            loadedFileName?.let { fileName ->
                M3Text(
                    text = "Loaded $fileName. Review it, then select Import tunnel.",
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            error?.let { msg ->
                M3Text(
                    text = msg,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PrimaryButton(
                    label = if (saving) "Saving…" else "Import tunnel",
                    enabled = !saving && configText.isNotBlank(),
                    onClick = {
                        saving = true
                        error = null
                        scope.launch(Dispatchers.IO) {
                            try {
                                WireguardManager.load(false)
                                val parsed = Config.parse(BufferedReader(StringReader(configText)))
                                WireguardManager.addConfig(parsed, name = tunnelName.trim())
                                withContext(Dispatchers.Main) {
                                    navController?.popBackStack()
                                }
                            } catch (e: Exception) {
                                withContext(Dispatchers.Main) {
                                    error = e.message ?: "Could not import tunnel"
                                }
                            } finally {
                                withContext(Dispatchers.Main) { saving = false }
                            }
                        }
                    },
                )
                SecondaryButton(
                    label = "Cancel",
                    onClick = { navController?.popBackStack() },
                )
            }

            Spacer(Modifier.height(20.dp))
        }
    }
}

private data class TunnelFile(val name: String, val contents: String)

private fun readTunnelFile(context: Context, uri: Uri): TunnelFile {
    val resolver = context.contentResolver
    runCatching {
        resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    val name = resolver.query(
        uri,
        arrayOf(OpenableColumns.DISPLAY_NAME),
        null,
        null,
        null,
    )?.use { cursor ->
        if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getString(0) else null
    } ?: Uri.decode(uri.lastPathSegment).orEmpty().substringAfterLast('/').ifBlank {
        "tunnel.conf"
    }

    val contents = resolver.openInputStream(uri)?.use { stream ->
        BufferedReader(InputStreamReader(stream, StandardCharsets.UTF_8)).readText()
    } ?: throw IOException("The file provider returned no data")

    if (contents.isBlank()) {
        throw IOException("The selected file is empty")
    }

    return TunnelFile(name = name, contents = contents)
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun PrimaryButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(12.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            focusedContainerColor = MaterialTheme.colorScheme.primaryContainer,
            focusedContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            pressedContainerColor = MaterialTheme.colorScheme.primaryContainer,
            pressedContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
        ),
    ) {
        Box(modifier = Modifier.padding(horizontal = 28.dp, vertical = 14.dp)) {
            Text(label, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SecondaryButton(label: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(12.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            focusedContainerColor = MaterialTheme.colorScheme.primary,
            focusedContentColor = MaterialTheme.colorScheme.onPrimary,
            pressedContainerColor = MaterialTheme.colorScheme.primary,
            pressedContentColor = MaterialTheme.colorScheme.onPrimary,
        ),
    ) {
        Box(modifier = Modifier.padding(horizontal = 22.dp, vertical = 12.dp)) {
            Text(label, style = MaterialTheme.typography.titleSmall)
        }
    }
}
