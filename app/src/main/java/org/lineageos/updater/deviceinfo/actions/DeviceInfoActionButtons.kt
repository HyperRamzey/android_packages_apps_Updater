/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.updater.deviceinfo.actions

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.android.settingslib.spa.debug.UiModePreviews
import com.android.settingslib.spa.framework.theme.SettingsDimension
import com.android.settingslib.spa.framework.theme.SettingsShape.CornerExtraLarge1
import com.android.settingslib.spa.framework.theme.SettingsShape.CornerExtraSmall2
import com.android.settingslib.spa.framework.theme.SettingsSpace.small1
import com.android.settingslib.spa.framework.theme.SettingsTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import org.lineageos.updater.R
import org.lineageos.updater.deviceinfo.DeviceInfoUtils
import java.io.IOException
import java.util.concurrent.TimeUnit

@Suppress("PROVIDED_RUNTIME_TOO_LOW")
@Serializable
private data class GithubRelease(val body: String = "")

private val changelogClient = OkHttpClient.Builder()
    .callTimeout(15, TimeUnit.SECONDS)
    .build()

private val changelogJson = Json { ignoreUnknownKeys = true }

/**
 * The update feed lives on raw.githubusercontent.com. Derive the GitHub API
 * release endpoint from it, so the changelog shown in the app is the release
 * the feed was generated from, without introducing another URL to configure.
 */
private fun githubReleaseApi(context: Context): String? {
    val uri = Uri.parse(context.getString(R.string.updater_server_url))
    if (uri.host != "raw.githubusercontent.com") return null
    val path = uri.pathSegments
    if (path.size < 3) return null
    return "https://api.github.com/repos/${path[0]}/${path[1]}/releases/latest"
}

private fun fetchReleaseBody(url: String): String {
    val request = Request.Builder()
        .url(url)
        .header("Accept", "application/vnd.github+json")
        .header("User-Agent", "lineage-updater")
        .build()

    changelogClient.newCall(request).execute().use { response ->
        if (!response.isSuccessful) {
            throw IOException("HTTP ${response.code}")
        }
        val body = response.body?.string() ?: throw IOException("Empty response")
        return changelogJson.decodeFromString<GithubRelease>(body).body
    }
}

/**
 * Release notes are Markdown and the dialog is plain Compose text; flatten the
 * markup instead of shipping a renderer. Everything from the assets table on
 * is release plumbing rather than a changelog.
 */
private fun markdownToText(markdown: String): String {
    var text = markdown.replace("\r\n", "\n").substringBefore("## Assets")
    text = text.replace(Regex("(?s)<!--.*?-->"), "")
    text = text.replace(Regex("^#{1,6}\\s*", RegexOption.MULTILINE), "")
    text = text.replace(Regex("^\\s*\\|?\\s*:?-{3,}.*$", RegexOption.MULTILINE), "")
    text = text.replace(Regex("\\*\\*(.*?)\\*\\*"), "$1")
    text = text.replace(Regex("__(.*?)__"), "$1")
    text = text.replace(Regex("`([^`]*)`"), "$1")
    text = text.replace(Regex("\\[([^]]+)]\\(([^)]+)\\)"), "$1 ($2)")
    text = text.replace(Regex("\\s*\\|\\s*"), " · ")
    text = text.replace(Regex("\\n{3,}"), "\n\n")
    return text.trim()
}

@Composable
fun DeviceInfoActionButtons(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var showChangelog by remember { mutableStateOf(false) }
    var changelog by remember { mutableStateOf<String?>(null) }
    var changelogError by remember { mutableStateOf<String?>(null) }
    var changelogLoading by remember { mutableStateOf(false) }

    fun openChangelog() {
        val api = githubReleaseApi(context)
        if (api == null) {
            // Not a GitHub-hosted feed: keep the old behavior.
            val url = context.getString(R.string.menu_changelog_url, DeviceInfoUtils.device)
            val intent = Intent(Intent.ACTION_VIEW, url.toUri())
            context.startActivity(intent)
            return
        }

        showChangelog = true
        if (changelogLoading || changelog != null) return

        changelogLoading = true
        changelogError = null
        scope.launch {
            try {
                changelog = withContext(Dispatchers.IO) {
                    markdownToText(fetchReleaseBody(api))
                }
            } catch (e: Exception) {
                changelogError = e.message ?: e.toString()
            } finally {
                changelogLoading = false
            }
        }
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(
                CornerExtraLarge1.copy(
                    topStart = CornerExtraSmall2.topStart,
                    topEnd = CornerExtraSmall2.topEnd,
                )
            )
            .background(MaterialTheme.colorScheme.surfaceBright)
            .padding(SettingsDimension.itemPaddingAround),
        horizontalArrangement = Arrangement.spacedBy(small1),
    ) {
        val buttonColors = ButtonDefaults.textButtonColors(
            contentColor = MaterialTheme.colorScheme.onSurface,
        )
        TextButton(
            onClick = { openChangelog() },
            colors = buttonColors,
        ) {
            Text(text = stringResource(R.string.show_changelog))
        }
        TextButton(
            onClick = {
                val url = context.getString(R.string.report_issue_url)
                val intent = Intent(Intent.ACTION_VIEW, url.toUri())
                context.startActivity(intent)
            },
            colors = buttonColors,
        ) {
            Text(text = stringResource(R.string.report_issues))
        }
    }

    if (showChangelog) {
        AlertDialog(
            onDismissRequest = { showChangelog = false },
            title = { Text(text = stringResource(R.string.show_changelog)) },
            text = {
                val error = changelogError
                val body = changelog
                when {
                    changelogLoading -> CircularProgressIndicator()

                    error != null -> Text(text = error)

                    else -> Column(
                        modifier = Modifier
                            .heightIn(max = 420.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        Text(text = body ?: "")
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showChangelog = false }) {
                    Text(text = stringResource(android.R.string.ok))
                }
            },
        )
    }
}

@UiModePreviews
@Composable
private fun DeviceInfoActionButtonsPreview() {
    SettingsTheme {
        DeviceInfoActionButtons(
            modifier = Modifier.padding(SettingsDimension.itemPadding),
        )
    }
}
