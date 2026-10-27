package com.droiddeck.launcher.ui

import android.widget.Toast
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester
import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PriorityHigh
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.VideogameAsset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.text.font.FontFamily
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import kotlinx.coroutines.flow.first
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.droiddeck.launcher.core.privileged.PrivilegedBridge
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import com.droiddeck.launcher.core.AppLanguage
import com.droiddeck.launcher.core.DeviceSupport
import com.droiddeck.launcher.core.DownloadSource
import com.droiddeck.launcher.core.PhantomProcessLimit
import com.droiddeck.launcher.core.PhantomProcessStatus
import com.droiddeck.launcher.session.SessionPrefs
import com.droiddeck.launcher.stores.download.StoreDownloadTier
import com.droiddeck.launcher.R


private enum class CheckState { OK, WARN, BUSY }

private const val OVERVIEW_TAB = 0
private const val ADVANCED_TAB = 1
private const val CONTROLLER_TAB = 2
private const val LAUNCHER_TAB = 3
private const val STORAGE_TAB = 4
private const val DIAGNOSTICS_TAB = 5
private const val GLASSES_TAB = 6

internal object SetupTabs {
    var openLauncher = false
    var openAdvanced = false
}

@Composable
private fun CheckRow(state: CheckState, title: String, detail: String?, divider: Boolean = true, action: (@Composable () -> Unit)? = null) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val tint = when (state) {
        CheckState.OK -> pal.good
        CheckState.WARN -> AttentionAmber
        CheckState.BUSY -> pal.signal
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier.fillMaxWidth()
            .background(if (state == CheckState.WARN) AttentionAmber.copy(alpha = 0.07f) else Color.Transparent)
            .heightIn(min = 60.dp)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(28.dp).clip(CircleShape).background(tint.copy(alpha = 0.16f))) {
            Icon(
                when (state) {
                    CheckState.OK -> Icons.Filled.Check
                    CheckState.WARN -> Icons.Filled.PriorityHigh
                    CheckState.BUSY -> Icons.Filled.Refresh
                },
                contentDescription = when (state) {
                    CheckState.OK -> stringResource(R.string.setup_check_done)
                    CheckState.WARN -> stringResource(R.string.setup_check_warn)
                    CheckState.BUSY -> stringResource(R.string.setup_check_busy)                },
                tint = tint, modifier = Modifier.size(16.dp),
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = colors.onBackground)
            if (detail != null) Text(detail, fontSize = 13.sp, color = colors.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (action != null) action()
    }
    if (divider) Box(Modifier.fillMaxWidth().height(1.dp).background(pal.line))
}

@Composable
internal fun SetupPanel(
    s: FrontEndState,
    a: FrontEndActions,
    onOpenDeveloperOptions: () -> Unit,
    onRequestWirelessAdb: (Boolean) -> Unit,
    onSelect: (String) -> Unit = {},
) {
    val host = rememberMenuHost()
    val ctx = LocalContext.current
    var processLimitBusy by remember { mutableStateOf(false) }
    var processLimitMessage by remember { mutableStateOf<String?>(null) }
    val setProcessLimit: (Boolean) -> Unit = { enabled ->
        processLimitBusy = true
        processLimitMessage = null
        a.onSetPhantomProcessLimit(enabled) { error ->
            processLimitBusy = false
            if (error == null) {
                a.onRefreshPhantomStatus()
            } else {
                processLimitMessage = ctx.getString(R.string.setup_limit_adb_failed)
                onRequestWirelessAdb(enabled)
            }
        }
    }
    val runtime = when {
        s.busy -> stringResource(R.string.common_working)
        s.removalPending -> stringResource(R.string.setup_retry_removal)
        !s.ready -> stringResource(R.string.setup_install)
        s.available != null && s.available != s.installed -> stringResource(R.string.common_update)
        else -> stringResource(R.string.setup_manage)
    }
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val gpu = remember { com.droiddeck.launcher.gpu.GpuInfo.detect() }
    val gpuOk = gpu.support == com.droiddeck.launcher.gpu.GpuInfo.Support.TESTED
    val gpuName = remember { DeviceSupport.gpuName(ctx) }
    val limitBlocks = PhantomProcessLimit.blocksSteam(s.phantomProcessStatus)
    val signedIn = s.offlineAccount != null
    var showLimitDetails by rememberSaveable { mutableStateOf(false) }
    var editSgdbKey by remember { mutableStateOf(false) }
    var editDownloadPrefix by remember { mutableStateOf(false) }
    if (editSgdbKey) SgdbKeyDialog(s.sgdbUserKey, onSave = { a.onSgdbKey(it); editSgdbKey = false }, onDismiss = { editSgdbKey = false })

    if (editDownloadPrefix) TextEntryDialog(
        stringResource(R.string.setup_download_prefix), s.downloadPrefix,
        onDismiss = { editDownloadPrefix = false },
        onSave = { a.onDownloadPrefix(it); editDownloadPrefix = false },
    )
    val tabs = listOf(
        stringResource(R.string.setup_tab_overview),
        stringResource(R.string.setup_tab_advanced),
        stringResource(R.string.setup_tab_controller),
        stringResource(R.string.setup_tab_launcher),
        stringResource(R.string.setup_tab_storage),
        stringResource(R.string.setup_tab_diagnostics),
        stringResource(R.string.setup_tab_glasses),
    )
    var tab by rememberSaveable {
        mutableStateOf(
            when {
                SetupTabs.openAdvanced -> ADVANCED_TAB
                SetupTabs.openLauncher -> LAUNCHER_TAB
                else -> 0
            }
        )
    }
    SetupTabs.openLauncher = false
    SetupTabs.openAdvanced = false
    val chipFocus = remember { List(tabs.size) { FocusRequester() } }
    var tabTurned by remember { mutableStateOf(false) }
    val pick: (Int) -> Unit = { i -> tab = i; tabTurned = true }
    val inputModeManager = LocalInputModeManager.current
    LaunchedEffect(com.droiddeck.launcher.storage.StorageState.openRequests) {
        if (com.droiddeck.launcher.storage.StorageState.openPending) {
            com.droiddeck.launcher.storage.StorageState.openPending = false
            tab = STORAGE_TAB
        }
    }
    LaunchedEffect(tab) {
        if (tabTurned && inputModeManager.inputMode == InputMode.Keyboard) {
            androidx.compose.runtime.withFrameNanos { }
            runCatching { chipFocus[tab].requestFocus() }
        }
    }
    val wide = !LocalNarrowPane.current
    val runtimeOk = s.ready && !s.busy && !s.removalPending
    val checks = listOf(gpuOk, runtimeOk, !limitBlocks, signedIn)
    val passing = checks.count { it }
    var showChecks by rememberSaveable { mutableStateOf(false) }
    Rise(0, Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().bumpers(
                onPrevious = { pick((tab + tabs.size - 1) % tabs.size) },
                onNext = { pick((tab + 1) % tabs.size) },
            ),
        ) {
            PageHeader(stringResource(R.string.setup_title)) {
                Box(Modifier.weight(1f)) { SheetChips(tabs, tab, chipFocus, onPick = pick) }
            }
            Column(modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
                when (tab) {
                    OVERVIEW_TAB -> {
                        val gpuCheck: @Composable (Boolean) -> Unit = { divider ->
                            CheckRow(
                                if (gpuOk) CheckState.OK else CheckState.WARN,
                                when (gpu.support) {
                                    com.droiddeck.launcher.gpu.GpuInfo.Support.TESTED -> stringResource(R.string.setup_gpu_supported)
                                    com.droiddeck.launcher.gpu.GpuInfo.Support.UNTESTED -> stringResource(R.string.setup_gpu_untested)
                                    else -> stringResource(R.string.setup_gpu_unsupported)
                                },
                                when (gpu.support) {
                                    com.droiddeck.launcher.gpu.GpuInfo.Support.TESTED -> stringResource(R.string.setup_gpu_detail, gpu.displayName(ctx), gpuName)
                                    com.droiddeck.launcher.gpu.GpuInfo.Support.UNTESTED -> stringResource(R.string.setup_gpu_untested_detail, gpu.displayName(ctx), gpu.supportText(ctx).replaceFirstChar { it.lowercase() })
                                    else -> stringResource(R.string.setup_gpu_unsupported_detail, gpuName)
                                },
                                divider = divider,
                            )
                        }
                        val runtimeCheck: @Composable (Boolean) -> Unit = { divider ->
                            CheckRow(
                                when { s.busy -> CheckState.BUSY; !s.ready -> CheckState.WARN; else -> CheckState.OK },
                                stringResource(R.string.setup_runtime),
                                when {
                                    s.busy -> null
                                    s.removalPending -> stringResource(R.string.runtime_removal_incomplete)
                                    !s.ready -> stringResource(R.string.setup_runtime_missing)
                                    s.available != null && s.available != s.installed -> stringResource(R.string.setup_runtime_update, s.installed ?: stringResource(R.string.setup_installed))
                                    else -> stringResource(R.string.setup_runtime_current, s.installed ?: stringResource(R.string.setup_installed))
                                },
                                divider = divider && !s.busy,
                            ) { SecondaryButton(runtime, enabled = !s.busy && !s.runtimeActionsBlocked && !s.sessionRunning, compact = true, onClick = a.onRuntime) }
                            if (s.busy) {
                                Column(modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 10.dp)) {
                                    Text(
                                        if (s.percent >= 0) stringResource(R.string.setup_runtime_progress, s.stage, s.percent) else s.stage,
                                        fontSize = 13.sp, color = colors.onSurfaceVariant, modifier = Modifier.padding(bottom = 5.dp),
                                    )
                                    if (s.percent >= 0) LinearProgressIndicator(progress = { s.percent / 100f }, modifier = Modifier.fillMaxWidth().height(4.dp))
                                    else LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(4.dp))
                                }
                                if (divider) Box(Modifier.fillMaxWidth().height(1.dp).background(pal.line))
                            }
                        }
                        val limitCheck: @Composable (Boolean) -> Unit = { divider ->
                            CheckRow(
                                if (limitBlocks) CheckState.WARN else CheckState.OK,
                                stringResource(R.string.setup_limit),
                                when (s.phantomProcessStatus) {
                                    PhantomProcessStatus.ENABLED -> stringResource(R.string.setup_limit_on)
                                    PhantomProcessStatus.UNSET -> stringResource(R.string.setup_limit_unset)
                                    PhantomProcessStatus.UNREADABLE -> stringResource(R.string.setup_limit_unknown)
                                    else -> PhantomProcessLimit.title(ctx, s.phantomProcessStatus)
                                },
                                divider = divider && !showLimitDetails,
                            ) {
                                if (limitBlocks) PrimaryButton(if (showLimitDetails) stringResource(R.string.common_hide) else stringResource(R.string.setup_fix_it), compact = true) { showLimitDetails = !showLimitDetails }
                                else if (s.phantomProcessStatus != PhantomProcessStatus.NOT_APPLICABLE) {
                                    SecondaryButton(if (showLimitDetails) stringResource(R.string.common_hide) else stringResource(R.string.setup_details), compact = true) { showLimitDetails = !showLimitDetails }
                                }
                            }
                            AnimatedVisibility(showLimitDetails, enter = expandVertically(Motion.sp(1f)) + fadeIn(Motion.sp(1f)), exit = shrinkVertically(Motion.sp(1f)) + fadeOut(Motion.sp(1f))) {
                                Column(modifier = Modifier.fillMaxWidth().padding(start = 56.dp, end = 14.dp, top = 4.dp, bottom = 10.dp)) {
                                    Text(
                                        if (limitBlocks) PhantomProcessLimit.gateInstructions(ctx, s.phantomProcessStatus)
                                        else PhantomProcessLimit.instructions(ctx, s.phantomProcessStatus),
                                        fontSize = 14.sp, color = colors.onSurfaceVariant, modifier = Modifier.padding(vertical = 4.dp),
                                    )
                                    Actions {
                                        if (limitBlocks) {
                                            PrimaryButton(stringResource(R.string.setup_dev_options), compact = true, onClick = onOpenDeveloperOptions)
                                            SecondaryButton(stringResource(R.string.setup_use_wireless), compact = true, enabled = !processLimitBusy) { setProcessLimit(false) }
                                            SecondaryButton(stringResource(R.string.setup_check_again), compact = true, onClick = a.onRefreshPhantomStatus)
                                        } else if (s.phantomProcessStatus == PhantomProcessStatus.DISABLED) {
                                            SecondaryButton(stringResource(R.string.setup_limit_turn_on), compact = true, enabled = !processLimitBusy) { setProcessLimit(true) }
                                        }
                                    }
                                    if (processLimitBusy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp))
                                    processLimitMessage?.let { Text(it, fontSize = 14.sp, color = colors.onSurfaceVariant, modifier = Modifier.padding(vertical = 4.dp)) }
                                }
                            }
                            if (divider && showLimitDetails) Box(Modifier.fillMaxWidth().height(1.dp).background(pal.line))
                        }
                        val accountCheck: @Composable (Boolean) -> Unit = { divider ->
                            CheckRow(
                                if (signedIn) CheckState.OK else CheckState.WARN,
                                stringResource(R.string.setup_account),
                                s.offlineAccount?.let {
                                    when {
                                        it.isEmpty() -> stringResource(if (s.offline) R.string.setup_signed_in_offline_unnamed else R.string.setup_signed_in_unnamed)
                                        s.offline -> stringResource(R.string.setup_signed_in_offline, it)
                                        else -> stringResource(R.string.setup_signed_in, it)
                                    }
                                } ?: stringResource(R.string.setup_sign_in),
                                divider = divider,
                            )
                        }
                        val all = listOf(gpuCheck, runtimeCheck, limitCheck, accountCheck)
                        ReadinessCard(
                            passing, checks.size,
                            listOfNotNull(
                                gpu.displayName(ctx),
                                s.installed?.let { stringResource(R.string.setup_ready_runtime, it) },
                                stringResource(R.string.setup_ready_limit_off).takeIf { !limitBlocks },
                                s.offlineAccount?.takeIf { it.isNotEmpty() }?.let { stringResource(R.string.setup_ready_signed_in, it) },
                            ).joinToString(" · "),
                            open = showChecks, onDetails = { showChecks = !showChecks },
                        ) {
                            val shownChecks = all.filterIndexed { i, _ -> checks[i] }
                            shownChecks.forEachIndexed { i, check -> check(i < shownChecks.lastIndex) }
                        }
                        if (passing < checks.size) {
                            SectionTitle(stringResource(R.string.setup_needs_you), null)
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                all.forEachIndexed { i, check ->
                                    if (!checks[i]) Column(Modifier.fillMaxWidth().clip(Shape14).background(colors.surface).border(1.dp, AttentionAmber.copy(alpha = 0.5f), Shape14)) { check(false) }
                                }
                            }
                        }
                        SettingsGroup(stringResource(R.string.setup_runtime)) { runtimeCheck(false) }
                        PhantomDisableCard(host, s.phantomProcessStatus, a.onSetPhantomProcessLimit, a.onRefreshPhantomStatus) { limitEnabled -> onRequestWirelessAdb(limitEnabled) }
                        val privStatus by PrivilegedBridge.statusFlow.collectAsState()
                        SettingsGroup("Privileged Engine (ADB / Shizuku)") {
                            CheckRow(
                                if (privStatus.isActive) CheckState.OK else CheckState.WARN,
                                "Privileged Mode: ${privStatus.shortLabel}",
                                if (privStatus.isActive) "Active (${privStatus.modeLabel}) · Full background protection & unlimited child processes"
                                else "Inactive · Tap Manage to activate embedded daemon or connect Shizuku",
                                divider = false,
                            ) {
                                SecondaryButton("Manage", compact = true) { onSelect("privileged") }
                            }
                        }
                    }
                    ADVANCED_TAB -> {
                        AdvancedSetupTab(host, s, a, wide, onSelect)
                    }
                    CONTROLLER_TAB -> {
                        val controller = s.controller
                        if (controller != null && a.controller != null) SettingsGroup(stringResource(R.string.setup_controller)) {
                            ControllerRows(host, s.oscMode, controller, a.controller)
                        }
                        if (s.controller == null || a.controller == null) Note(stringResource(R.string.setup_controller_unavailable))
                    }
                    LAUNCHER_TAB -> {
                        val look: @Composable () -> Unit = {
                            SettingsGroup(stringResource(R.string.setup_group_look)) {
                                SettingsRow(stringResource(R.string.setup_theme), stringResource(R.string.setup_theme_hint)) {
                                    Box {
                                        ValueChip(stringResource(Themes.byId(s.theme).label), host.open == "theme") { host.open = if (host.open == "theme") null else "theme" }
                                        AnchoredMenu(host.open == "theme", onDismiss = { if (host.open == "theme") host.open = null }, title = stringResource(R.string.setup_theme)) { firstItemFocus ->
                                            Themes.all.forEachIndexed { index, theme ->
                                                MenuItem(stringResource(theme.label), checked = s.theme == theme.id, focusRequester = if (index == 0) firstItemFocus else null) {
                                                    a.onTheme(theme.id)
                                                    host.open = null
                                                }
                                            }
                                        }
                                    }
                                }
                                if (s.theme == Themes.INNER_GAMER) RgbSpeedRow()
                                if (s.theme == Themes.GRADIENT) GradientColorRows()
                                ChoiceRow(
                                    host, "app-scale", stringResource(R.string.setup_app_scale), stringResource(R.string.setup_app_scale_hint),
                                    com.droiddeck.launcher.core.AppUiPrefs.scales.map { percent ->
                                        percent to stringResource(
                                            if (percent == com.droiddeck.launcher.core.AppUiPrefs.DEFAULT_SCALE) R.string.setup_app_scale_default
                                            else R.string.ctrl_percent, percent,
                                        )
                                    }, s.appScale, onPick = a.onAppScale,
                                )
                                ChoiceRow(
                                    host, "launcher-icon", stringResource(R.string.setup_launcher_icon), null,
                                    com.droiddeck.launcher.LauncherIcon.options.map { (style, label) -> style to stringResource(label) },
                                    s.launcherIcon, onPick = a.onLauncherIcon,
                                )
                                ToggleRow(host, "launcher-animations", stringResource(R.string.setup_animations), null,
                                    s.animationsEnabled, onChange = a.onAnimationsEnabled)
                                LanguageRow(host, s.language, a.onLanguage)
                                ToggleRow(host, "show-stores", stringResource(R.string.setup_stores), null, s.gameStoresEnabled, onChange = a.onGameStoresEnabled)
                            }
                        }
                        val device: @Composable () -> Unit = {
                            SettingsGroup(stringResource(R.string.setup_group_device)) {
                                ToggleRow(
                                    host, "home-screen", stringResource(R.string.setup_home),
                                    if (s.homeScreenEnabled) stringResource(R.string.setup_home_on) else stringResource(R.string.setup_home_off),
                                    s.homeScreenEnabled,
                                ) { a.onHomeScreen(it) }
                                if (s.homeScreenEnabled) {
                                    ActionRow(stringResource(R.string.setup_default_home), s.defaultHomeLabel ?: stringResource(R.string.setup_choose_home), stringResource(R.string.setup_choose), a.onHomeApp)
                                }
                                ChoiceRow(
                                    host, "orientation", stringResource(R.string.setup_orientation), null,
                                    SessionPrefs.orientationOptions.map { (value, label) -> value to stringResource(label) },
                                    s.orientation, onPick = a.onOrientation,
                                )
                                ToggleRow(
                                    host, "launcher-fullscreen", stringResource(R.string.setup_fullscreen),
                                    if (s.launcherFullscreen) stringResource(R.string.setup_fullscreen_on) else stringResource(R.string.setup_fullscreen_off),
                                    s.launcherFullscreen,
                                ) { a.onLauncherFullscreen(it) }
                            }
                        }
                        val art: @Composable () -> Unit = {
                            SettingsGroup(stringResource(R.string.setup_group_art)) {
                                ToggleRow(host, "added-art", stringResource(R.string.mode_added_art), stringResource(R.string.mode_added_art_hint), s.addedGamesArt) { a.onAddedGamesArt(it) }
                                ActionRow(
                                    stringResource(R.string.mode_sgdb_key), if (s.sgdbUserKey) stringResource(R.string.mode_sgdb_key_yours) else null,
                                    stringResource(R.string.mode_sgdb_edit), onClick = { editSgdbKey = true },
                                )
                            }
                        }
                        val download: @Composable () -> Unit = {
                            SettingsGroup(stringResource(R.string.setup_group_download)) {
                                ChoiceRow(
                                    host, "download-source", stringResource(R.string.setup_download_source), null,
                                    listOf(
                                        DownloadSource.MODE_AUTO to stringResource(R.string.setup_download_source_auto),
                                        DownloadSource.MODE_DIRECT to stringResource(R.string.setup_download_source_direct),
                                        DownloadSource.MODE_MIRROR to stringResource(R.string.setup_download_source_mirror),
                                        DownloadSource.MODE_CUSTOM to stringResource(R.string.setup_download_source_custom),
                                    ),
                                    s.downloadSource, onPick = a.onDownloadSource,
                                )
                                if (s.downloadSource == DownloadSource.MODE_MIRROR) {
                                    ChoiceRow(
                                        host, "download-mirror", stringResource(R.string.setup_download_mirror), null,
                                        DownloadSource.MIRRORS.map { it.host to it.host },
                                        s.downloadMirror, onPick = a.onDownloadMirror,
                                    )
                                }
                                if (s.downloadSource == DownloadSource.MODE_CUSTOM) {
                                    ActionRow(
                                        stringResource(R.string.setup_download_prefix),
                                        s.downloadPrefix.takeIf { it.isNotEmpty() },
                                        stringResource(R.string.mode_sgdb_edit),
                                        onClick = { editDownloadPrefix = true },
                                    )
                                }
                            }
                        }
                        if (wide) Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Top) {
                            Column(Modifier.weight(1f)) { look() }
                            Column(Modifier.weight(1f)) { device(); art(); download() }
                        } else { look(); device(); art(); download() }
                    }
                    STORAGE_TAB -> StoragePanel(s, a)
                    DIAGNOSTICS_TAB -> {
                        SettingsGroup(stringResource(R.string.setup_tab_diagnostics)) {
                            ToggleRow(host, "logs", stringResource(R.string.setup_logs), stringResource(R.string.setup_logs_hint), s.logsEnabled) { a.onLogs() }
                            ToggleRow(host, "vacdiag", stringResource(R.string.setup_vac_diag), stringResource(R.string.setup_vac_diag_hint), s.vacDiagnostics) { a.onVacDiagnostics() }
                            ToggleRow(host, "agent-commands", stringResource(R.string.setup_agent_commands), null, s.agentCommands) { a.onAgentCommands() }
                            ActionRow(stringResource(R.string.setup_latest_logs), stringResource(R.string.drawer_logs_hint), stringResource(R.string.drawer_share_logs), a.onShareLogs,
                                progress = com.droiddeck.launcher.session.SessionLogShare.progress)
                            ActionRow(stringResource(R.string.setup_saved_logs), stringResource(R.string.setup_saved_logs_hint, com.droiddeck.launcher.session.SessionPaths.KEEP_SESSIONS), stringResource(R.string.setup_clear_logs), a.onClearLogs)
                            ActionRow(stringResource(R.string.setup_reset_settings), stringResource(R.string.setup_reset_settings_hint),
                                stringResource(R.string.setup_reset_settings_button), a.onResetSettings)
                        }
                    }
                    GLASSES_TAB -> GlassesSetupPanel()
                }
            }
        }
    }
}

@Composable
internal fun PhantomDisableCard(
    host: MenuHost,
    status: PhantomProcessStatus,
    onSetLimit: (Boolean, (String?) -> Unit) -> Unit,
    onRefreshStatus: () -> Unit,
    onApplyFailed: ((Boolean) -> Unit)? = null,
) {
    val ctx = LocalContext.current
    val colors = MaterialTheme.colorScheme
    var enabled by remember { mutableStateOf(SessionPrefs.phantomDisableEnabled(ctx)) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var showMore by rememberSaveable { mutableStateOf(false) }
    val apply: (Boolean) -> Unit = { disable ->
        if (!busy) {
            busy = true
            message = null
            onSetLimit(!disable) { error ->
                // onSetLimit takes limitEnabled, the inverse of disable.
                busy = false
                if (error == null) {
                    SessionPrefs.setPhantomDisableEnabled(ctx, disable)
                    enabled = disable
                    message = ctx.getString(if (disable) R.string.phantom_card_disabled else R.string.phantom_card_reenabled)
                    com.droiddeck.launcher.core.privileged.DiagnosticsLog.log(
                        ctx, "phantom.apply", "succeeded",
                        fields = mapOf("action" to if (disable) "disable" else "re-enable"),
                    )
                    onRefreshStatus()
                } else {
                    message = ctx.getString(R.string.setup_limit_adb_failed)
                    com.droiddeck.launcher.core.privileged.DiagnosticsLog.log(
                        ctx, "phantom.apply", "failed",
                        fields = mapOf("action" to if (disable) "disable" else "re-enable"),
                    )
                    onApplyFailed?.invoke(!disable)
                }
            }
        }
    }
    LaunchedEffect(status) {
        if (!SessionPrefs.phantomDisablePreferenceSet(ctx)) {
            if (status == PhantomProcessStatus.DISABLED) {
                SessionPrefs.setPhantomDisableEnabled(ctx, true)
            } else if (status != PhantomProcessStatus.NOT_APPLICABLE && status != PhantomProcessStatus.UNREADABLE) {
                apply(true)
            }
        }
    }
    SettingsGroup(stringResource(R.string.phantom_card_title)) {
        ToggleRow(
            host = host,
            key = "phantom-disable",
            label = stringResource(R.string.phantom_card_toggle),
            hint = stringResource(R.string.phantom_card_short),
            checked = enabled,
            enabled = !busy && status != PhantomProcessStatus.NOT_APPLICABLE,
        ) { on ->
            if (on != enabled) apply(on)
        }
        Column(Modifier.padding(horizontal = 14.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (status == PhantomProcessStatus.NOT_APPLICABLE) {
                Text(stringResource(R.string.phantom_card_not_applicable), fontSize = 13.sp, color = colors.onSurfaceVariant)
            } else {
                Text(PhantomProcessLimit.title(ctx, status), fontSize = 13.sp, color = colors.onSurfaceVariant)
            }
            Actions {
                SecondaryButton(if (showMore) stringResource(R.string.common_hide) else stringResource(R.string.phantom_card_learn_more), compact = true) { showMore = !showMore }
                if (enabled && status != PhantomProcessStatus.NOT_APPLICABLE) {
                    SecondaryButton(stringResource(R.string.phantom_card_reenable), compact = true, enabled = !busy) { apply(false) }
                }
                SecondaryButton(stringResource(R.string.setup_check_again), compact = true, enabled = !busy) { onRefreshStatus() }
            }
            AnimatedVisibility(showMore, enter = expandVertically(Motion.sp(1f)) + fadeIn(Motion.sp(1f)), exit = shrinkVertically(Motion.sp(1f)) + fadeOut(Motion.sp(1f))) {
                Text(stringResource(R.string.phantom_card_learn_more_text), fontSize = 13.sp, color = colors.onSurfaceVariant)
            }
            if (busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp))
            message?.let { Text(it, fontSize = 13.sp, color = colors.onSurfaceVariant) }
        }
    }
}

private data class RepairRow(
    val key: String,
    val decision: com.droiddeck.launcher.core.privileged.RepairDecision,
    val original: String?,
    val current: String?,
)

@Composable
private fun AdvancedSetupTab(
    host: MenuHost,
    s: FrontEndState,
    a: FrontEndActions,
    wide: Boolean,
    onSelect: (String) -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val pal = LocalPalette.current
    val colors = MaterialTheme.colorScheme

    var masterEnabled by remember { mutableStateOf(SessionPrefs.advancedMasterEnabled(ctx)) }
    var gameModeEngine by remember { mutableStateOf(SessionPrefs.gameModeEngine(ctx)) }
    var gameModeAggressive by remember { mutableStateOf(SessionPrefs.gameModeAggressive(ctx)) }
    var dynamicGovernor by remember { mutableStateOf(SessionPrefs.dynamicGovernor(ctx)) }
    var deepSystemDoze by remember { mutableStateOf(SessionPrefs.deepSystemDoze(ctx)) }
    var freezeBloatware by remember { mutableStateOf(SessionPrefs.freezeBloatware(ctx)) }
    var freezeUserApps by remember { mutableStateOf(SessionPrefs.freezeUserApps(ctx)) }

    var restoring by remember { mutableStateOf(false) }
    var safeRestoreReport by remember { mutableStateOf<String?>(null) }

    var liveMetrics by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var verificationReport by remember { mutableStateOf<String?>(null) }
    var verifying by remember { mutableStateOf(false) }

    val privStatus by PrivilegedBridge.statusFlow.collectAsState()

    // Query live telemetry while tab is visible
    LaunchedEffect(Unit) {
        while (isActive) {
            withContext(Dispatchers.IO) {
                val metrics = com.droiddeck.launcher.core.privileged.GameModeEngine.runLiveVerification(ctx) +
                    com.droiddeck.launcher.core.privileged.AppFreezerEngine.runDiagnostic(ctx) +
                    com.droiddeck.launcher.core.privileged.DynamicAdaptiveGovernor.getDiagnosticReport(ctx)
                liveMetrics = metrics
            }
            delay(3_000)
        }
    }

    // ── Master Kill-Switch & Safe Restore Card ───────────────────
    val masterControlCard: @Composable () -> Unit = {
        SettingsGroup(stringResource(R.string.setup_adv_master_group)) {
            ToggleRow(
                host = host,
                key = "adv-master-enable",
                label = stringResource(R.string.setup_adv_master_toggle),
                hint = if (masterEnabled) stringResource(R.string.setup_adv_master_hint_on) else stringResource(R.string.setup_adv_master_hint_off),
                checked = masterEnabled,
            ) { on ->
                masterEnabled = on
                SessionPrefs.setAdvancedMasterEnabled(ctx, on)
                if (!on) {
                    com.droiddeck.launcher.core.privileged.DiagnosticsLog.log(
                        ctx, "advanced.action", "master-off-restore",
                    )
                    // Completely revert all modifications immediately
                    scope.launch(Dispatchers.IO) {
                        com.droiddeck.launcher.core.privileged.GameModeEngine.restore(ctx, 0)
                        com.droiddeck.launcher.core.privileged.AppFreezerEngine.restore(ctx, 0)
                        com.droiddeck.launcher.core.privileged.DynamicAdaptiveGovernor.stop(ctx)
                        liveMetrics = com.droiddeck.launcher.core.privileged.GameModeEngine.runLiveVerification(ctx) +
                            com.droiddeck.launcher.core.privileged.AppFreezerEngine.runDiagnostic(ctx) +
                            com.droiddeck.launcher.core.privileged.DynamicAdaptiveGovernor.getDiagnosticReport(ctx)
                        withContext(Dispatchers.Main) {
                            Toast.makeText(ctx, ctx.getString(R.string.setup_adv_master_disabled_toast), Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }

            // Safe Restore Action Row
            ActionRow(
                label = stringResource(R.string.setup_adv_safe_restore_title),
                hint = stringResource(R.string.setup_adv_safe_restore_hint),
                button = if (restoring) stringResource(R.string.setup_adv_restoring) else stringResource(R.string.setup_adv_safe_restore_btn),
                onClick = {
                    if (restoring) return@ActionRow
                    com.droiddeck.launcher.core.privileged.DiagnosticsLog.log(
                        ctx, "advanced.action", "safe-restore",
                    )
                    scope.launch {
                        restoring = true
                        safeRestoreReport = null
                        withContext(Dispatchers.IO) {
                            com.droiddeck.launcher.core.privileged.GameModeEngine.testRestoreNow(ctx)
                            com.droiddeck.launcher.core.privileged.AppFreezerEngine.restore(ctx, 0)
                            com.droiddeck.launcher.core.privileged.DynamicAdaptiveGovernor.stop(ctx)
                        }
                        liveMetrics = withContext(Dispatchers.IO) {
                            com.droiddeck.launcher.core.privileged.GameModeEngine.runLiveVerification(ctx) +
                                com.droiddeck.launcher.core.privileged.AppFreezerEngine.runDiagnostic(ctx) +
                                com.droiddeck.launcher.core.privileged.DynamicAdaptiveGovernor.getDiagnosticReport(ctx)
                        }
                        safeRestoreReport = ctx.getString(R.string.setup_adv_safe_restore_success)
                        restoring = false
                    }
                }
            )

            if (!safeRestoreReport.isNullOrEmpty()) {
                Box(
                    Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(pal.good.copy(alpha = 0.12f))
                        .border(1.dp, pal.good.copy(alpha = 0.40f), RoundedCornerShape(8.dp))
                        .padding(10.dp)
                ) {
                    Text(
                        safeRestoreReport.orEmpty(),
                        fontSize = 12.sp,
                        color = colors.onBackground,
                    )
                }
            }

            if (!masterEnabled) {
                Box(
                    Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(colors.error.copy(alpha = 0.12f))
                        .border(1.dp, colors.error.copy(alpha = 0.35f), RoundedCornerShape(8.dp))
                        .padding(10.dp)
                ) {
                    Text(
                        stringResource(R.string.setup_adv_master_disabled_banner),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.error,
                    )
                }
            }
        }
    }

    val phantomCard: @Composable () -> Unit = {
        PhantomDisableCard(host, s.phantomProcessStatus, a.onSetPhantomProcessLimit, a.onRefreshPhantomStatus)
    }

    val baselineCard: @Composable () -> Unit = {
        var info by remember { mutableStateOf(com.droiddeck.launcher.core.privileged.DeviceBaselineCapture.info(ctx)) }
        SettingsGroup(stringResource(R.string.baseline_title)) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    if (info.captured) {
                        stringResource(R.string.baseline_captured, java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date(info.capturedAtMs)))
                    } else {
                        stringResource(R.string.baseline_not_captured)
                    },
                    fontSize = 13.sp, color = colors.onSurfaceVariant,
                )
                val baselineFileName = info.fileName
                if (baselineFileName != null) {
                    Text(baselineFileName, fontSize = 13.sp, color = colors.onSurfaceVariant)
                }
                Actions {
                    SecondaryButton(stringResource(R.string.baseline_share), compact = true, enabled = info.captured) {
                        val file = com.droiddeck.launcher.core.privileged.DeviceBaselineCapture.baselineFile(ctx)
                        if (file != null) {
                            com.droiddeck.launcher.core.privileged.DeviceBaselineCapture.shareIntent(ctx, file)?.let { ctx.startActivity(it) }
                        }
                    }
                    SecondaryButton(stringResource(R.string.setup_check_again), compact = true) {
                        info = com.droiddeck.launcher.core.privileged.DeviceBaselineCapture.info(ctx)
                    }
                }
            }
        }
    }

    val diagnosticsCard: @Composable () -> Unit = {
        var summary by remember { mutableStateOf(com.droiddeck.launcher.core.privileged.DiagnosticsLog.summary(ctx)) }
        var hasFile by remember { mutableStateOf(com.droiddeck.launcher.core.privileged.DiagnosticsLog.file(ctx) != null) }
        var diagnosticsEnabled by remember { mutableStateOf(com.droiddeck.launcher.core.privileged.DiagnosticsLog.isEnabled(ctx)) }
        SettingsGroup(stringResource(R.string.diagnostics_title)) {
            ToggleRow(
                host = host,
                key = "diagnostics-enabled",
                label = stringResource(R.string.diagnostics_toggle),
                hint = null,
                checked = diagnosticsEnabled,
            ) { on ->
                diagnosticsEnabled = on
                if (on) {
                    com.droiddeck.launcher.core.privileged.DiagnosticsLog.setEnabled(ctx, true)
                    com.droiddeck.launcher.core.privileged.DiagnosticsLog.log(
                        ctx, "diagnostics.control", "enabled",
                    )
                } else {
                    com.droiddeck.launcher.core.privileged.DiagnosticsLog.log(
                        ctx, "diagnostics.control", "disabled",
                    )
                    com.droiddeck.launcher.core.privileged.DiagnosticsLog.setEnabled(ctx, false)
                }
            }
            Column(Modifier.padding(horizontal = 14.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    stringResource(
                        R.string.diagnostics_summary,
                        if (summary != null && summary!!.lastWallMs > 0) {
                            com.droiddeck.launcher.core.privileged.DiagnosticsLog.formatTime(summary!!.lastWallMs)
                        } else {
                            "—"
                        },
                        summary?.lines?.toInt() ?: 0,
                    ),
                    fontSize = 13.sp, color = colors.onSurfaceVariant,
                )
                Text(stringResource(R.string.diagnostics_note), fontSize = 13.sp, color = colors.onSurfaceVariant)
                Actions {
                    SecondaryButton(stringResource(R.string.diagnostics_share), compact = true, enabled = hasFile) {
                        com.droiddeck.launcher.core.privileged.DiagnosticsLog.shareIntent(ctx)?.let { ctx.startActivity(it) }
                    }
                    SecondaryButton(stringResource(R.string.diagnostics_delete), compact = true, enabled = hasFile) {
                        com.droiddeck.launcher.core.privileged.DiagnosticsLog.deleteCurrentFile(ctx)
                        summary = com.droiddeck.launcher.core.privileged.DiagnosticsLog.summary(ctx)
                        hasFile = com.droiddeck.launcher.core.privileged.DiagnosticsLog.file(ctx) != null
                    }
                    SecondaryButton(stringResource(R.string.setup_check_again), compact = true) {
                        summary = com.droiddeck.launcher.core.privileged.DiagnosticsLog.summary(ctx)
                        hasFile = com.droiddeck.launcher.core.privileged.DiagnosticsLog.file(ctx) != null
                    }
                }
            }
        }
    }

    val repairCard: @Composable () -> Unit = {
        var rows by remember { mutableStateOf<List<RepairRow>>(emptyList()) }
        var scanned by remember { mutableStateOf(false) }
        var bridgeActive by remember { mutableStateOf(false) }
        var busy by remember { mutableStateOf(false) }
        var report by remember { mutableStateOf<String?>(null) }
        val doScan: () -> Unit = {
            scope.launch(Dispatchers.IO) {
                val active = privStatus.isActive
                val snapshot = com.droiddeck.launcher.core.privileged.Tweaks.journalSnapshot(ctx)
                val scannedRows = snapshot.map { item ->
                    val verifyCmd = com.droiddeck.launcher.core.privileged.Tweaks.effectiveVerifyCmd(item)
                    val current = if (active && verifyCmd != null) {
                        val read = runCatching { com.droiddeck.launcher.core.privileged.PrivilegedBridge.executeChecked(verifyCmd) }.getOrNull()
                        if (read != null && read.exitCode == 0) read.output else null
                    } else {
                        null
                    }
                    RepairRow(
                        item.key,
                        com.droiddeck.launcher.core.privileged.JournalRepair.classify(item, current),
                        if (item.verifyMode == com.droiddeck.launcher.core.privileged.JournalVerifyMode.VALUE) item.original else null,
                        if (item.verifyMode == com.droiddeck.launcher.core.privileged.JournalVerifyMode.VALUE && current != null) {
                            com.droiddeck.launcher.core.privileged.SafetyChecks.parseBackgroundLimit(current)
                        } else {
                            null
                        },
                    )
                }
                scannedRows.forEach { row ->
                    com.droiddeck.launcher.core.privileged.DiagnosticsLog.log(
                        ctx, "device.repair", "scan",
                        fields = mapOf("key" to row.key, "outcome" to row.decision.name),
                    )
                }
                withContext(Dispatchers.Main) {
                    rows = scannedRows
                    scanned = true
                    bridgeActive = active
                    busy = false
                }
            }
        }
        val doRepair: () -> Unit = {
            scope.launch(Dispatchers.IO) {
                withContext(Dispatchers.Main) { busy = true }
                val backedUp = com.droiddeck.launcher.core.privileged.Tweaks.backupJournal(ctx)
                com.droiddeck.launcher.core.privileged.DiagnosticsLog.log(
                    ctx, "device.repair", "backup",
                    fields = mapOf("outcome" to if (backedUp) "ok" else "failed"),
                )
                if (!backedUp) {
                    withContext(Dispatchers.Main) {
                        report = ctx.getString(R.string.repair_backup_failed)
                        busy = false
                    }
                    return@launch
                }
                var quarantined = 0
                var resolved = 0
                var retried = 0
                var failed = 0
                var skipped = 0
                rows.forEach { row ->
                    val outcome = when (row.decision) {
                        com.droiddeck.launcher.core.privileged.RepairDecision.CORRUPT -> {
                            if (com.droiddeck.launcher.core.privileged.Tweaks.quarantineKey(ctx, row.key)) {
                                quarantined++
                                "quarantined"
                            } else {
                                failed++
                                "retry-failed"
                            }
                        }
                        com.droiddeck.launcher.core.privileged.RepairDecision.ALREADY_OK -> {
                            if (com.droiddeck.launcher.core.privileged.Tweaks.removeKey(ctx, row.key)) {
                                resolved++
                                "resolved"
                            } else {
                                failed++
                                "retry-failed"
                            }
                        }
                        com.droiddeck.launcher.core.privileged.RepairDecision.RETRY -> {
                            if (com.droiddeck.launcher.core.privileged.Tweaks.rollbackOne(ctx, row.key)) {
                                retried++
                                "retried"
                            } else {
                                failed++
                                "retry-failed"
                            }
                        }
                        com.droiddeck.launcher.core.privileged.RepairDecision.UNREADABLE -> {
                            skipped++
                            "skipped"
                        }
                    }
                    com.droiddeck.launcher.core.privileged.DiagnosticsLog.log(
                        ctx, "device.repair", outcome,
                        fields = mapOf("key" to row.key, "outcome" to outcome),
                    )
                }
                withContext(Dispatchers.Main) {
                    report = ctx.getString(R.string.repair_done) + " quarantined=$quarantined resolved=$resolved retried=$retried failed=$failed skipped=$skipped"
                }
                doScan()
            }
        }
        val doReapply: () -> Unit = {
            scope.launch(Dispatchers.IO) {
                withContext(Dispatchers.Main) { busy = true }
                com.droiddeck.launcher.core.privileged.DiagnosticsLog.log(
                    ctx, "device.repair", "reapply-started",
                )
                val ok = com.droiddeck.launcher.core.privileged.PrivilegedBridge.ensureBackgroundProtection(ctx)
                com.droiddeck.launcher.core.privileged.DiagnosticsLog.log(
                    ctx, "device.repair", "reapply-finished",
                    fields = mapOf("outcome" to ok.toString()),
                )
                withContext(Dispatchers.Main) {
                    report = ctx.getString(R.string.repair_done) + " reapply=$ok"
                    busy = false
                }
            }
        }
        SettingsGroup(stringResource(R.string.repair_title)) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (scanned && rows.isEmpty()) {
                    Text(stringResource(R.string.repair_no_issues), fontSize = 13.sp, color = colors.onSurfaceVariant)
                }
                if (scanned && !bridgeActive) {
                    Text(stringResource(R.string.repair_needs_bridge), fontSize = 13.sp, color = colors.onSurfaceVariant)
                }
                rows.forEach { row ->
                    Text("${row.key} · ${row.decision.name}", fontSize = 13.sp, color = colors.onSurfaceVariant)
                    if (row.decision != com.droiddeck.launcher.core.privileged.RepairDecision.CORRUPT && row.original != null) {
                        Text("${row.original} → ${row.current ?: "—"}", fontSize = 13.sp, color = colors.onSurfaceVariant)
                    }
                }
                Actions {
                    SecondaryButton(stringResource(R.string.repair_scan), compact = true, enabled = !busy) {
                        busy = true
                        report = null
                        doScan()
                    }
                    SecondaryButton(stringResource(R.string.repair_repair), compact = true, enabled = !busy && !s.sessionRunning && scanned && rows.isNotEmpty()) {
                        doRepair()
                    }
                    SecondaryButton(stringResource(R.string.repair_reapply), compact = true, enabled = !busy && !s.sessionRunning) {
                        doReapply()
                    }
                }
                if (busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp))
                if (s.sessionRunning) {
                    Text(stringResource(R.string.repair_session_running), fontSize = 13.sp, color = colors.onSurfaceVariant)
                }
                report?.let { Text(it, fontSize = 13.sp, color = colors.onSurfaceVariant) }
            }
        }
    }

    val privilegedCard: @Composable () -> Unit = {
        SettingsGroup(stringResource(R.string.setup_adv_group_privileged)) {
            CheckRow(
                if (privStatus.isActive) CheckState.OK else CheckState.WARN,
                "Privileged Mode: ${privStatus.shortLabel}",
                if (privStatus.isActive) stringResource(R.string.setup_adv_privileged_active, privStatus.modeLabel)
                else stringResource(R.string.setup_adv_privileged_inactive),
                divider = false,
            ) {
                SecondaryButton(stringResource(R.string.setup_adv_configure), compact = true) { onSelect("privileged") }
            }
        }
    }

    val gameModeGroup: @Composable () -> Unit = {
        SettingsGroup(stringResource(R.string.setup_adv_group_gamemode)) {
            ToggleRow(
                host = host,
                key = "adv-gamemode-engine",
                label = stringResource(R.string.setup_adv_gamemode),
                hint = stringResource(R.string.setup_adv_gamemode_hint),
                checked = gameModeEngine,
                enabled = masterEnabled,
            ) { on ->
                gameModeEngine = on
                SessionPrefs.setGameModeEngine(ctx, on)
            }
            ToggleRow(
                host = host,
                key = "adv-gamemode-aggressive",
                label = stringResource(R.string.setup_adv_aggressive),
                hint = stringResource(R.string.setup_adv_aggressive_hint),
                checked = gameModeAggressive,
                enabled = masterEnabled && gameModeEngine,
            ) { on ->
                gameModeAggressive = on
                SessionPrefs.setGameModeAggressive(ctx, on)
            }
            ToggleRow(
                host = host,
                key = "adv-dynamic-gov",
                label = stringResource(R.string.setup_adv_dynamic_gov),
                hint = stringResource(R.string.setup_adv_dynamic_gov_hint),
                checked = dynamicGovernor,
                enabled = masterEnabled && gameModeEngine,
            ) { on ->
                dynamicGovernor = on
                SessionPrefs.setDynamicGovernor(ctx, on)
            }
        }
    }

    val freezerGroup: @Composable () -> Unit = {
        SettingsGroup(stringResource(R.string.setup_adv_group_freezer)) {
            ToggleRow(
                host = host,
                key = "adv-deep-doze",
                label = stringResource(R.string.setup_adv_deep_doze),
                hint = stringResource(R.string.setup_adv_deep_doze_hint),
                checked = deepSystemDoze,
                enabled = masterEnabled,
            ) { on ->
                deepSystemDoze = on
                SessionPrefs.setDeepSystemDoze(ctx, on)
            }
            ToggleRow(
                host = host,
                key = "adv-freeze-bloat",
                label = stringResource(R.string.setup_adv_freeze_bloat),
                hint = stringResource(R.string.setup_adv_freeze_bloat_hint),
                checked = freezeBloatware,
                enabled = masterEnabled,
            ) { on ->
                freezeBloatware = on
                SessionPrefs.setFreezeBloatware(ctx, on)
            }
            ToggleRow(
                host = host,
                key = "adv-freeze-user-apps",
                label = stringResource(R.string.setup_adv_freeze_user_apps),
                hint = stringResource(R.string.setup_adv_freeze_user_apps_hint),
                checked = freezeUserApps,
                enabled = masterEnabled,
            ) { on ->
                freezeUserApps = on
                SessionPrefs.setFreezeUserApps(ctx, on)
            }
        }
    }

    val telemetryGroup: @Composable () -> Unit = {
        SettingsGroup(stringResource(R.string.setup_adv_group_telemetry)) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val active = com.droiddeck.launcher.core.privileged.GameModeEngine.isActive()
                val aggressive = com.droiddeck.launcher.core.privileged.GameModeEngine.isAggressiveActive()
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Active Profile", fontSize = 12.sp, color = colors.onSurfaceVariant)
                    Text(
                        if (!masterEnabled) "🔴 DISABLED (Master Off)"
                        else if (active) (if (aggressive) "🔥 TURBO (Aggressive)" else "❄️ COOL (Load Reducer)")
                        else "⚪ STANDBY (Engages with game)",
                        fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                        color = if (!masterEnabled) colors.error else if (active) (if (aggressive) colors.tertiary else pal.good) else colors.onSurfaceVariant,
                    )
                }

                liveMetrics.forEach { (label, value) ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(label, fontSize = 12.sp, color = colors.onSurfaceVariant)
                        Text(value, fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = colors.onBackground, maxLines = 1)
                    }
                }

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Affinity Target", fontSize = 12.sp, color = colors.onSurfaceVariant)
                    Text(
                        if (!masterEnabled) "Default (Android Scheduler)"
                        else if (aggressive) "Cores 2-7 (0xfc - All Perf + Prime)"
                        else "Cores 2-6 (0x7c - 5x A720 Perf, X4 Off)",
                        fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = colors.onBackground,
                    )
                }
            }
        }
    }

    val testGroup: @Composable () -> Unit = {
        SettingsGroup(stringResource(R.string.setup_adv_group_test)) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.setup_adv_test_desc),
                    fontSize = 12.sp,
                    color = colors.onSurfaceVariant,
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            scope.launch {
                                verifying = true
                                val r = withContext(Dispatchers.IO) {
                                    val gm = com.droiddeck.launcher.core.privileged.GameModeEngine.testApplyNow(ctx)
                                    val fz = com.droiddeck.launcher.core.privileged.AppFreezerEngine.apply(ctx, 999)
                                    "$gm\n\n=== APP FREEZER ===\n$fz"
                                }
                                verificationReport = r
                                liveMetrics = withContext(Dispatchers.IO) {
                                    com.droiddeck.launcher.core.privileged.GameModeEngine.runLiveVerification(ctx) +
                                        com.droiddeck.launcher.core.privileged.AppFreezerEngine.runDiagnostic(ctx) +
                                        com.droiddeck.launcher.core.privileged.DynamicAdaptiveGovernor.getDiagnosticReport(ctx)
                                }
                                verifying = false
                            }
                        },
                        modifier = Modifier.weight(1f),
                        enabled = masterEnabled && privStatus.isActive && !verifying,
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Text(if (verifying) stringResource(R.string.setup_adv_test_busy) else stringResource(R.string.setup_adv_test_apply), fontSize = 12.sp)
                    }
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                verifying = true
                                val r = withContext(Dispatchers.IO) {
                                    val gm = com.droiddeck.launcher.core.privileged.GameModeEngine.testRestoreNow(ctx)
                                    com.droiddeck.launcher.core.privileged.AppFreezerEngine.restore(ctx, 999)
                                    "$gm\n\n=== APP FREEZER ===\napp freezer: restored (all packages unsuspended)"
                                }
                                verificationReport = r
                                liveMetrics = withContext(Dispatchers.IO) {
                                    com.droiddeck.launcher.core.privileged.GameModeEngine.runLiveVerification(ctx) +
                                        com.droiddeck.launcher.core.privileged.AppFreezerEngine.runDiagnostic(ctx) +
                                        com.droiddeck.launcher.core.privileged.DynamicAdaptiveGovernor.getDiagnosticReport(ctx)
                                }
                                verifying = false
                            }
                        },
                        modifier = Modifier.weight(1f),
                        enabled = privStatus.isActive && !verifying,
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Text(stringResource(R.string.setup_adv_test_restore), fontSize = 12.sp)
                    }
                }

                if (!verificationReport.isNullOrEmpty()) {
                    Box(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                            .background(colors.surfaceVariant.copy(alpha = 0.6f)).border(1.dp, pal.line, RoundedCornerShape(8.dp)).padding(10.dp),
                    ) {
                        Text(
                            verificationReport.orEmpty(),
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = colors.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }

    val shortcutsGroup: @Composable () -> Unit = {
        SettingsGroup(stringResource(R.string.setup_adv_group_shortcuts)) {
            ActionRow(
                stringResource(R.string.setup_adv_debug_monitor),
                stringResource(R.string.setup_adv_debug_monitor_hint),
                stringResource(R.string.setup_adv_open_monitor),
                onClick = { onSelect("debug-monitor") },
            )
            ActionRow(
                stringResource(R.string.setup_adv_privileged_settings),
                stringResource(R.string.setup_adv_privileged_settings_hint),
                stringResource(R.string.setup_adv_configure),
                onClick = { onSelect("privileged") },
            )
        }
    }

    if (wide) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                masterControlCard()
                phantomCard()
                baselineCard()
                diagnosticsCard()
                repairCard()
                privilegedCard()
                gameModeGroup()
                freezerGroup()
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                telemetryGroup()
                testGroup()
                shortcutsGroup()
            }
        }
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            masterControlCard()
            phantomCard()
            baselineCard()
            diagnosticsCard()
            repairCard()
            privilegedCard()
            gameModeGroup()
            freezerGroup()
            telemetryGroup()
            testGroup()
            shortcutsGroup()
        }
    }
}

@Composable
private fun ReadinessCard(passing: Int, total: Int, line: String, open: Boolean, onDetails: () -> Unit, checks: @Composable () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val ready = passing == total
    Column(Modifier.fillMaxWidth().padding(top = 8.dp).clip(Shape14).background(colors.surface).border(1.dp, pal.line, Shape14)) {
        Row(
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            val tint = if (ready) pal.good else AttentionAmber
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(52.dp)) {
                androidx.compose.material3.CircularProgressIndicator(
                    progress = { passing / total.toFloat().coerceAtLeast(1f) }, strokeWidth = 4.dp,
                    color = tint, trackColor = tint.copy(alpha = 0.18f), modifier = Modifier.size(52.dp),
                )
                Text("$passing/$total", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = colors.onBackground)
            }
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(if (ready) R.string.setup_ready else R.string.setup_not_ready),
                    fontSize = 17.sp, fontWeight = FontWeight.Bold, color = colors.onBackground,
                )
                if (line.isNotEmpty()) Text(line, fontSize = 13.sp, color = colors.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (passing > 0) SecondaryButton(stringResource(if (open) R.string.common_hide else R.string.setup_details), compact = true, onClick = onDetails)
        }
        AnimatedVisibility(open, enter = expandVertically(Motion.sp(1f)) + fadeIn(Motion.sp(1f)), exit = shrinkVertically(Motion.sp(1f)) + fadeOut(Motion.sp(1f))) {
            Column(Modifier.fillMaxWidth()) {
                Box(Modifier.fillMaxWidth().height(1.dp).background(pal.line))
                checks()
            }
        }
    }
}

@Composable
private fun FrameGenMenu(s: FrontEndState, a: FrontEndActions, host: MenuHost) {
    FrameGenMenu(host, s.frameGen, s.lossless, a.onFrameGenPick, a.onImportLossless)
}

@Composable
internal fun LaunchSettings(s: FrontEndState, a: FrontEndActions, host: MenuHost, game: com.droiddeck.launcher.frontend.Library.SteamGame? = null) {
    val columns = if (LocalNarrowPane.current) 2 else 3
    val controller = a.controller
    val context = androidx.compose.ui.platform.LocalContext.current
    val inputMode = androidx.compose.ui.platform.LocalInputModeManager.current
    val wincompKey = game?.takeIf { it.library == com.droiddeck.launcher.frontend.Library.ADDED || it.appId > 0 }?.profileKey
    var wincompOpen by remember(wincompKey) { mutableStateOf<Boolean?>(null) }
    val wincompPicks = remember(wincompKey, wincompOpen) { wincompKey?.let { com.droiddeck.launcher.session.WinComponents.picks(context, it) }.orEmpty() }
    val steamScope = game?.let { com.droiddeck.launcher.session.NoSteamLaunch.scope(it) }
    val steamCard: (@Composable (Modifier) -> Unit)? = if (game != null && steamScope != null) { m ->
        Box(m) { GameSettingsCard(host, game, steamScope) }
    } else null
    val cards = if (game?.library == com.droiddeck.launcher.frontend.Library.EPIC_LAUNCHER) listOfNotNull(steamCard) else buildList<@Composable (Modifier) -> Unit> {
        add { m ->
            val back = stringResource(R.string.hop_from_games)
            SettingCard(stringResource(R.string.setup_card_components), stringResource(R.string.setup_card_components_hint), "card:components", m.hopSource("card:components")) {
                val at = Hops.sources["card:components"]
                val open = { if (game == null) a.onComponents(true) else a.onGameComponents(game) }
                if (at == null) open()
                else Hops.go(HopOrigin("games", HOP_SURFACE_GAMES, null, "card:components", at, back, dest = "components", page = "components"), open)
            }
        }
        add { m ->
            Box(m) {
                SettingCard(stringResource(R.string.frame_gen_title), s.frameGenLabel, "card:fg", Modifier.fillMaxSize()) {
                    host.open = if (host.open == "fg") null else "fg"
                }
                FrameGenMenu(s, a, host)
            }
        }
        if (controller != null) add { m -> SettingCard(stringResource(R.string.setup_card_controls), stringResource(R.string.setup_card_controls_hint), "card:controls", m, controller.onMapping) }
        if (steamCard != null) add(steamCard)
        val cardStore = com.droiddeck.launcher.stores.Store.byId(game?.source.orEmpty())?.takeIf { it == com.droiddeck.launcher.stores.Store.EPIC || it == com.droiddeck.launcher.stores.Store.GOG }
        if (cardStore != null && game?.gameFiles != null) add { m ->
            Box(m) {
                StoreLaunchCard(host, game.gameFiles!!, cardStore)
            }
        }
        if (wincompKey != null) add { m ->
            SettingCard(
                stringResource(R.string.wincomp_title),
                if (wincompPicks.isEmpty()) stringResource(R.string.wincomp_card_none) else wincompPicks.joinToString(", ") { com.droiddeck.launcher.session.WinComponentNames.of(it) },
                "card:wincomp", m,
            ) { wincompOpen = inputMode.inputMode == androidx.compose.ui.input.InputMode.Keyboard }
        }
    }
    val opened = wincompOpen
    if (opened != null && wincompKey != null && game != null) WinComponentsDialog(
        wincompKey, game.name, game.gameFiles, opened, compat = game.protonPrefix,
        steamAppId = game.appId.takeIf { game.library != com.droiddeck.launcher.frontend.Library.ADDED },
        game = game,
    ) { wincompOpen = null }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
        for (row in cards.chunked(columns)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                for (card in row) card(Modifier.weight(1f).fillMaxHeight())
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun GameSettingsCard(host: MenuHost, game: com.droiddeck.launcher.frontend.Library.SteamGame, scope: String) {
    val appContext = androidx.compose.ui.platform.LocalContext.current.applicationContext
    val colors = MaterialTheme.colorScheme
    var config by remember(scope) { mutableStateOf(runCatching { com.droiddeck.launcher.session.GameEnvironmentStore.read(appContext) }.getOrNull()) }
    var saving by remember(scope) { mutableStateOf(false) }
    val current = config
    val noSteam = current != null && com.droiddeck.launcher.core.GameEnvironment.gameNoSteam(current, scope)
    val fex = current?.let { runCatching { com.droiddeck.launcher.core.GameEnvironment.gameFexPreset(it, scope) }.getOrNull() }
    val sync = current?.let { runCatching { com.droiddeck.launcher.core.GameEnvironment.gameSync(it, scope) }.getOrNull() }
    val ready = current != null && !saving
    val launcher = game.library == com.droiddeck.launcher.frontend.Library.EPIC_LAUNCHER
    fun save(change: (android.content.Context) -> com.droiddeck.launcher.core.GameEnvironment.Config?) {
        if (saving) return
        saving = true
        Thread({
            val written = runCatching { change(appContext) }
                .onFailure { android.util.Log.w("GameSettingsCard", "could not save the settings of $scope: ${it.message}") }.getOrNull()
            val next = written ?: runCatching { com.droiddeck.launcher.session.GameEnvironmentStore.read(appContext) }.getOrNull()
            com.droiddeck.launcher.stores.StoresState.post {
                config = next
                saving = false
            }
        }, "game-settings").start()
    }
    val key = "game-settings"
    val fexKey = "game-settings-fex"
    val syncKey = "game-settings-sync"
    val syncLabels = mapOf(
        com.droiddeck.launcher.session.SessionPrefs.SYNC_NTSYNC to stringResource(R.string.sync_backend_ntsync),
        com.droiddeck.launcher.session.SessionPrefs.SYNC_FSYNC to stringResource(R.string.sync_backend_fsync),
        com.droiddeck.launcher.session.SessionPrefs.SYNC_ESYNC to stringResource(R.string.sync_backend_esync),
        com.droiddeck.launcher.session.SessionPrefs.SYNC_WINESERVER to stringResource(R.string.sync_backend_wineserver),
    )
    val appSync = syncLabels[com.droiddeck.launcher.session.SessionPrefs.syncBackend(appContext)]
    val useDefault = stringResource(R.string.comp_game_use_general)
    val custom = stringResource(R.string.comp_game_fex_custom)
    val customSync = stringResource(R.string.game_sync_custom)
    val resetDone = stringResource(R.string.comp_game_reset_done, game.name)
    SettingCard(stringResource(R.string.game_settings_card), stringResource(if (noSteam) R.string.game_steam_without else R.string.game_steam_with), "card:$key", Modifier.fillMaxSize()) {
        host.open = if (host.open == key) null else key
    }
    AnchoredMenu(host.open == key, onDismiss = { if (host.open == key) host.open = null }, title = stringResource(R.string.game_settings_menu_title, game.name)) { first ->
        MenuItem(stringResource(R.string.game_no_steam_title), checked = noSteam, enabled = ready,
            detail = stringResource(R.string.game_no_steam_detail), focusRequester = first) {
            val on = !noSteam
            save { com.droiddeck.launcher.session.GameEnvironmentStore.setGameNoSteam(it, scope, on) }
        }
        if (!launcher) {
            val chosen = com.droiddeck.launcher.core.FexPreset.all.firstOrNull { it.id == fex }
            MenuItem(
                stringResource(R.string.fex_preset_title), checked = false, enabled = ready,
                detail = when {
                    fex == null -> useDefault
                    chosen == null -> custom
                    else -> stringResource(chosen.label)
                },
                trailing = { Text("›", fontSize = 16.sp, color = colors.onSurfaceVariant) },
            ) { host.open = fexKey }
            MenuItem(
                stringResource(R.string.sync_backend_title), checked = false, enabled = ready,
                detail = when (sync) {
                    null -> useDefault
                    com.droiddeck.launcher.core.GameEnvironment.CUSTOM_SYNC -> customSync
                    else -> syncLabels[sync]
                },
                trailing = { Text("›", fontSize = 16.sp, color = colors.onSurfaceVariant) },
            ) { host.open = syncKey }
        }
        MenuItem(stringResource(R.string.game_settings_default), checked = false, enabled = ready,
            detail = stringResource(R.string.game_settings_default_detail)) {
            save {
                val failure = runCatching { com.droiddeck.launcher.session.GameProfileManager.reset(it, scope) }.exceptionOrNull()
                com.droiddeck.launcher.stores.StoresState.post {
                    android.widget.Toast.makeText(
                        it,
                        if (failure == null) resetDone else it.getString(R.string.comp_game_reset_failed, failure.message.orEmpty()),
                        android.widget.Toast.LENGTH_SHORT,
                    ).show()
                }
                null
            }
        }
    }
    AnchoredMenu(host.open == fexKey, onDismiss = { if (host.open == fexKey) host.open = null }, title = stringResource(R.string.fex_preset_title)) { first ->
        MenuItem(useDefault, checked = fex == null, enabled = ready, focusRequester = first) {
            host.open = key
            save { com.droiddeck.launcher.session.GameEnvironmentStore.setGameFexPreset(it, scope, null) }
        }
        if (fex == com.droiddeck.launcher.core.GameEnvironment.CUSTOM_FEX_PRESET) {
            MenuItem(custom, checked = true, enabled = false) {}
        }
        com.droiddeck.launcher.core.FexPreset.all.forEach { option ->
            MenuItem(stringResource(option.label), checked = fex == option.id, enabled = ready, detail = stringResource(option.detail)) {
                host.open = key
                save { com.droiddeck.launcher.session.GameEnvironmentStore.setGameFexPreset(it, scope, option.id) }
            }
        }
    }
    AnchoredMenu(host.open == syncKey, onDismiss = { if (host.open == syncKey) host.open = null }, title = stringResource(R.string.sync_backend_title)) { first ->
        MenuItem(useDefault, checked = sync == null, enabled = ready, detail = appSync, focusRequester = first) {
            host.open = key
            save { com.droiddeck.launcher.session.GameEnvironmentStore.setGameSync(it, scope, null) }
        }
        if (sync == com.droiddeck.launcher.core.GameEnvironment.CUSTOM_SYNC) {
            MenuItem(customSync, checked = true, enabled = false) {}
        }
        com.droiddeck.launcher.core.GameEnvironment.syncBackends.forEach { option ->
            MenuItem(syncLabels.getValue(option), checked = sync == option, enabled = ready) {
                host.open = key
                save { com.droiddeck.launcher.session.GameEnvironmentStore.setGameSync(it, scope, option) }
            }
        }
    }
}

private const val EPIC_RESOLVE_URL = "https://www.epicgames.com/id/login?redirectUrl=https%3A%2F%2Fwww.epicgames.com%2Faccount%2Fpersonal"

@Composable
private fun StoreLaunchCard(host: MenuHost, folder: java.io.File, store: com.droiddeck.launcher.stores.Store) {
    val appContext = androidx.compose.ui.platform.LocalContext.current.applicationContext
    var sidecar by remember(folder) { mutableStateOf(com.droiddeck.launcher.stores.StoreGameSidecar.read(folder)) }
    val options = sidecar?.epic ?: com.droiddeck.launcher.stores.EpicOptions()
    val epic = store == com.droiddeck.launcher.stores.Store.EPIC
    val key = "store-" + store.id
    val value = if (!epic) stringResource(when {
        !com.droiddeck.launcher.stores.gog.GogComet.enabled(appContext) || sidecar?.achievements == false -> R.string.gog_achievements_off
        com.droiddeck.launcher.stores.gog.GogComet.status == "sign-in-needed" -> R.string.gog_achievements_signin
        com.droiddeck.launcher.stores.gog.GogComet.status in listOf("service-unavailable", "credentials-unavailable", "not-installed") -> R.string.gog_achievements_unavailable
        com.droiddeck.launcher.stores.gog.GogComet.status == "uploaded" -> R.string.gog_achievements_uploaded
        com.droiddeck.launcher.stores.gog.GogComet.status == "pending-upload" || com.droiddeck.launcher.stores.gog.GogComet.status == "unlocked-locally" -> R.string.gog_achievements_pending
        else -> R.string.gog_achievements_on
    }) else buildList {
        if (epic && options.offline) add(stringResource(R.string.epic_card_offline))
        else if (epic && options.eos) add(stringResource(R.string.epic_card_eos))
    }.ifEmpty { listOf(stringResource(R.string.epic_card_none)) }.joinToString(" · ")
    fun set(change: (com.droiddeck.launcher.stores.EpicOptions) -> com.droiddeck.launcher.stores.EpicOptions) {
        Thread({
            val written = runCatching { com.droiddeck.launcher.stores.StoreGameSidecar.updateEpic(folder, change) }
                .onFailure { android.util.Log.w("EpicLaunchCard", "could not write ${folder.name}: ${it.message}") }.getOrNull()
            if (written != null) android.util.Log.i("EpicLaunchCard", "epic options ${written.id} eos=${written.epic.eos} offline=${written.epic.offline}")
            else android.util.Log.w("EpicLaunchCard", "epic options not saved for ${folder.name}")
            com.droiddeck.launcher.stores.StoresState.post { sidecar = written ?: com.droiddeck.launcher.stores.StoreGameSidecar.read(folder) }
        }, "epic-options").start()
    }
    SettingCard(store.shortLabel, value, "card:$key", Modifier.fillMaxSize()) {
        host.open = if (host.open == key) null else key
    }
    AnchoredMenu(host.open == key, onDismiss = { if (host.open == key) host.open = null }, title = store.shortLabel) { first ->
        if (!epic) {
            MenuItem(stringResource(R.string.gog_achievements), checked = sidecar?.achievements != false, focusRequester = first,
                detail = if (!com.droiddeck.launcher.stores.gog.GogComet.enabled(appContext)) stringResource(R.string.gog_achievements_enable_store) else null) {
                val on = sidecar?.achievements == false
                Thread({
                    val written = runCatching { com.droiddeck.launcher.stores.StoreGameSidecar.updateAchievements(folder, on) }.getOrNull()
                    com.droiddeck.launcher.stores.StoresState.post { sidecar = written ?: sidecar }
                }, "gog-game-achievements").start()
            }
        }
        if (epic) {
            MenuItem(stringResource(R.string.epic_eos), checked = options.eos, focusRequester = first) { set { it.copy(eos = !it.eos) } }
            MenuItem(stringResource(R.string.epic_offline), checked = options.offline) { set { it.copy(offline = !it.offline) } }
        }
        if (epic) {
        MenuItem(stringResource(R.string.epic_resolve), checked = false) {
            host.open = null
            runCatching {
                appContext.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(EPIC_RESOLVE_URL)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
        }
    }
}

@Composable
private fun SettingCard(label: String, value: String, id: String, modifier: Modifier, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val src = remember { MutableInteractionSource() }
    val hot = rememberHot(src)
    val pressed by src.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.97f else 1f, Motion.sp(0.5f, Spring.StiffnessMedium), label = "cardScale")
    Column(
        verticalArrangement = Arrangement.spacedBy(3.dp),
        modifier = modifier.paneItem(id)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(Shape14)
            .background(if (hot) pal.signal.copy(alpha = 0.10f) else colors.surface)
            .glideBorder(hot, Shape14, pal.signal, pal.line2)
            .hoverable(src).clickable(interactionSource = src, indication = LocalIndication.current, role = Role.Button, onClick = onClick)
            .controllerConfirm(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Text(label, fontSize = 13.sp, color = if (hot) pal.signal else colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(value, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = colors.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun LanguageRow(host: MenuHost, chosen: String, onPick: (String) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val systemName = AppLanguage.displayName(AppLanguage.system(context))
    val systemLabel = stringResource(R.string.setup_language_system, systemName)
    SettingsRow(stringResource(R.string.setup_language), stringResource(R.string.setup_language_hint)) {
        Box {
            ValueChip(if (chosen == AppLanguage.SYSTEM) stringResource(R.string.setup_language_system_short) else AppLanguage.nativeName(chosen),
                host.open == "language") { host.open = if (host.open == "language") null else "language" }
            AnchoredMenu(host.open == "language", onDismiss = { if (host.open == "language") host.open = null }, title = stringResource(R.string.setup_language)) { firstItemFocus ->
                MenuItem(systemLabel, checked = chosen == AppLanguage.SYSTEM, focusRequester = firstItemFocus) {
                    host.open = null
                    onPick(AppLanguage.SYSTEM)
                }
                AppLanguage.supported.forEach { tag ->
                    MenuItem(AppLanguage.nativeName(tag), checked = chosen == tag) {
                        host.open = null
                        onPick(tag)
                    }
                }
            }
        }
    }
}

/** How fast Inner Gamer cycles its colours. */
@Composable
private fun RgbSpeedRow() {
    val ctx = androidx.compose.ui.platform.LocalContext.current.applicationContext
    SliderRow(stringResource(R.string.theme_rgb_speed), stringResource(R.string.theme_rgb_speed_hint), Themes.rgbSpeed, 1..Themes.RGB_SPEEDS) { Themes.setRgbSpeed(ctx, it) }
}

/** The two colours of the Gradient theme, each a small square that opens the colour picker. */
@Composable
private fun GradientColorRows() {
    val ctx = androidx.compose.ui.platform.LocalContext.current.applicationContext
    val (primary, secondary) = Themes.gradientColors
    var picking by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    val primaryLabel = stringResource(R.string.theme_gradient_primary)
    val secondaryLabel = stringResource(R.string.theme_gradient_secondary)
    SettingsRow(primaryLabel, null) { ColorCube(androidx.compose.ui.graphics.Color(primary), primaryLabel) { picking = 1 } }
    SettingsRow(secondaryLabel, null) { ColorCube(androidx.compose.ui.graphics.Color(secondary), secondaryLabel) { picking = 2 } }
    if (picking != 0) ColorPickerDialog(
        if (picking == 1) primaryLabel else secondaryLabel, if (picking == 1) primary else secondary,
        onDismiss = { picking = 0 },
    ) { argb ->
        if (picking == 1) Themes.setGradient(ctx, argb, secondary) else Themes.setGradient(ctx, primary, argb)
    }
}
