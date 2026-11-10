package com.tokai.module.ui

import android.content.ActivityNotFoundException
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.tokai.module.BuildConfig
import com.tokai.module.R
import com.tokai.module.config.FrameworkConfigSync
import com.tokai.module.config.SettingsRepository
import com.tokai.module.i18n.LanguageSettingsDialog
import com.tokai.module.i18n.ModuleLocale
import com.tokai.module.i18n.UiText
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme
import top.yukonga.miuix.kmp.preference.SwitchPreference

/** Used only in the module process; host dialogs retain their own UI and settings store. */
class ModuleStatusScreen(private val activity: ComponentActivity) {
    init {
        ModuleLocale.ensureInitialized(activity)
    }

    private var currentScan by mutableStateOf(ScanStatus.pending())
    private var frameworkConnected by mutableStateOf(false)
    private var frameworkMessage by mutableStateOf("")
    private var frameworkError by mutableStateOf<String?>(null)
    private var frameworkPresentation by mutableStateOf<FrameworkConfigSync.Presentation?>(null)
    private var hiddenLauncher by mutableStateOf(LauncherVisibility.isHidden(activity))

    fun createView(): View = ComposeView(ModuleLocale.wrap(activity)).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent {
            TokAiTheme {
                SyncWindowTheme()
                StatusContent()
            }
        }
    }

    fun setScanStatus(status: ScanStatus) {
        currentScan = status
    }

    fun setFrameworkSyncStatus(connected: Boolean, message: String?, error: String?) {
        frameworkPresentation = null
        frameworkConnected = connected
        frameworkMessage = message.orEmpty()
        frameworkError = error
    }

    fun setFrameworkSyncStatus(connected: Boolean, message: String?, error: String?, presentation: FrameworkConfigSync.Presentation) {
        setFrameworkSyncStatus(connected, message, error)
        frameworkPresentation = presentation
    }

    private fun frameworkDisplayMessage(): String = frameworkPresentation?.let { presentation ->
        UiText.get(when (presentation.stage) {
            FrameworkConfigSync.Stage.CONNECTED -> R.string.common_ui_m113
            FrameworkConfigSync.Stage.DISCONNECTED -> R.string.common_ui_m114
            FrameworkConfigSync.Stage.UNAVAILABLE -> R.string.common_ui_m115
            FrameworkConfigSync.Stage.WAITING_CONNECTION -> R.string.common_ui_m116
            FrameworkConfigSync.Stage.OPERATION_FAILED -> R.string.common_ui_m117
        })
    } ?: frameworkMessage

    private fun frameworkDisplayError(): String? = frameworkPresentation?.let { presentation ->
        val message = when (presentation.errorReason) {
            FrameworkConfigSync.ErrorReason.NONE -> return@let null
            FrameworkConfigSync.ErrorReason.NULL_SERVICE -> UiText.get(R.string.common_ui_m118)
            FrameworkConfigSync.ErrorReason.CONNECTION_FAILED -> UiText.get(R.string.common_ui_m119)
            FrameworkConfigSync.ErrorReason.REMOTE_WRITE_FAILED -> UiText.get(R.string.common_ui_m120)
            FrameworkConfigSync.ErrorReason.RAW -> return@let presentation.errorDetail
        }
        if (presentation.errorDetail.isEmpty()) message else UiText.get(R.string.common_ui_m121, message, presentation.errorDetail)
    } ?: frameworkError?.takeIf { frameworkPresentation == null }

    @Suppress("DEPRECATION")
    @Composable
    private fun SyncWindowTheme() {
        val dark = isSystemInDarkTheme()
        val surface = MiuixTheme.colorScheme.surface.toArgb()
        SideEffect {
            // This screen only belongs to the module activity; never theme a host window.
            val window = activity.window
            window.setBackgroundDrawable(ColorDrawable(surface))
            window.statusBarColor = surface
            window.navigationBarColor = surface
            WindowCompat.getInsetsController(window, window.decorView).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }

    @Composable
    private fun StatusContent() {
        Scaffold(modifier = Modifier.fillMaxSize()) { insets ->
            Column(
                modifier = Modifier.fillMaxSize()
                    .padding(insets)
                    .consumeWindowInsets(insets)
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(modifier = Modifier.padding(horizontal = 28.dp)) {
                    Text(
                        text = stringResource(R.string.module_brand),
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = stringResource(R.string.module_version, BuildConfig.VERSION_NAME),
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        fontSize = 14.sp,
                    )
                    Text(
                        text = stringResource(R.string.module_host_version),
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        fontSize = 14.sp,
                    )
                }
                Spacer(Modifier.height(8.dp))
                SmallTitle(stringResource(R.string.module_status))
                SelectionContainer {
                    Column(Modifier.background(MiuixTheme.colorScheme.background)) {
                        BasicComponent(
                            title = stringResource(
                                if (frameworkConnected) R.string.module_framework_connected
                                else R.string.module_framework_disconnected,
                            ),
                            summary = listOfNotNull(
                                frameworkDisplayMessage().ifBlank {
                                    stringResource(R.string.module_framework_waiting)
                                },
                                frameworkDisplayError()?.takeIf { it.isNotBlank() },
                            ).joinToString("\n"),
                        )
                        BasicComponent(
                            title = currentScan.displayTitle(),
                            summary = currentScan.displayDetail() + if (currentScan.restartRequired) {
                                "\n" + stringResource(R.string.module_restart_required)
                            } else "",
                        )
                    }
                }
                SmallTitle(stringResource(R.string.module_settings_title))
                BasicComponent(
                    title = stringResource(R.string.module_language_title),
                    summary = LanguageSettingsDialog.label(ModuleLocale.choice()),
                    modifier = Modifier.testTag("settings.language"),
                    onClick = { LanguageSettingsDialog.show(activity, SettingsRepository(activity), activity::recreate) },
                )
                Text(
                    text = stringResource(R.string.module_settings_location),
                    modifier = Modifier.padding(horizontal = 28.dp),
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    fontSize = 14.sp,
                )
                TextButton(
                    text = stringResource(R.string.module_open_host),
                    onClick = ::openHost,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 8.dp),
                )
                SwitchPreference(
                    title = UiText.get(R.string.common_ui_m270),
                    checked = hiddenLauncher,
                    onCheckedChange = { hidden ->
                        try {
                            LauncherVisibility.setHidden(activity, hidden)
                            hiddenLauncher = LauncherVisibility.isHidden(activity)
                        } catch (_: RuntimeException) {
                            Toast.makeText(activity, UiText.get(R.string.common_ui_m271), Toast.LENGTH_SHORT).show()
                        }
                    },
                )
                BasicComponent(title = UiText.get(R.string.common_ui_m272), summary = "TokAi ${BuildConfig.VERSION_NAME}",
                    onClick = { AboutDialog.create(activity, true).show() })
            }
        }
    }

    private fun openHost() {
        try {
            val intent = activity.packageManager.getLaunchIntentForPackage("com.zhiliaoapp.musically")
            if (intent != null) {
                activity.startActivity(intent)
                return
            }
        } catch (_: ActivityNotFoundException) {
            // The host may have been removed between resolving and launching it.
        } catch (_: SecurityException) {
            // Restricted profiles may expose the package but disallow its launch.
        }
        Toast.makeText(activity, R.string.module_host_unavailable, Toast.LENGTH_SHORT).show()
    }
}

@Composable
fun TokAiTheme(content: @Composable () -> Unit) {
    MiuixTheme(
        colors = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme(),
        content = content,
    )
}
