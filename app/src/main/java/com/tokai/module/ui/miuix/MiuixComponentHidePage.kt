package com.tokai.module.ui.miuix

import com.tokai.module.R
import com.tokai.module.i18n.UiText

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Color as AndroidColor
import android.graphics.drawable.ColorDrawable
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tokai.module.config.ComponentHideSettings
import com.tokai.module.config.ComponentHideSettings.Component
import com.tokai.module.config.CustomViewLocator
import com.tokai.module.config.SettingsStore
import com.tokai.module.ui.HostDialogBounds
import com.tokai.module.ui.ModuleTheme
import com.tokai.module.ui.ComponentHideSettingsPage
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.ArrowRight
import top.yukonga.miuix.kmp.icon.basic.Close
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.roundToInt

/** A separate window keeps the parent settings page and its scroll position intact. */
class MiuixComponentHidePage(
    private val context: Context,
    private val store: SettingsStore,
) {
    private var dialog: Dialog? = null
    private var session: HostComposeEnvironment? = null
    private var pendingDelete by mutableStateOf<CustomViewLocator?>(null)
    private var customDetails: Dialog? = null

    fun show() {
        if (isShowing()) return
        val activity = ownerActivity(context) ?: return
        if (activity.isFinishing || activity.isDestroyed) return
        val nextSession = HostComposeEnvironment(activity, Runnable(::requestBack))
        val view = ComposeView(nextSession.context())
        nextSession.attach(view)
        val next = Dialog(nextSession.context(), ModuleTheme.dialogTheme(nextSession.context()))
        val editor = ComponentHideEditor(store)
        next.setOwnerActivity(activity)
        next.setCanceledOnTouchOutside(true)
        next.setOnDismissListener {
            dismissCustomDetails()
            nextSession.close()
            if (dialog === next) {
                dialog = null
                session = null
                pendingDelete = null
            }
        }
        view.setContent {
            HostSettingsTheme { Content(editor) }
        }
        next.setContentView(view)
        next.window?.apply {
            setBackgroundDrawable(ColorDrawable(AndroidColor.TRANSPARENT))
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            attributes = attributes.apply { dimAmount = 0.45f }
        }
        dialog = next
        session = nextSession
        try {
            next.show()
            nextSession.bindBackNavigation(next)
            HostDialogBounds.install(activity, next, false)
        } catch (_: RuntimeException) {
            dismiss()
        }
    }

    fun isShowing(): Boolean = dialog?.isShowing == true

    fun dismiss() {
        dismissCustomDetails()
        val current = dialog
        dialog = null
        pendingDelete = null
        try {
            current?.dismiss()
        } finally {
            session?.close()
            session = null
        }
    }

    private fun requestBack() {
        if (customDetails?.isShowing == true) dismissCustomDetails()
        else if (pendingDelete != null) pendingDelete = null else dismiss()
    }

    private fun dismissCustomDetails() {
        customDetails?.dismiss()
        customDetails = null
    }

    @Composable
    private fun Content(editor: ComponentHideEditor) {
        val settings = editor.settings
        Scaffold(
            modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(20.dp))
                .background(MiuixTheme.colorScheme.background, RoundedCornerShape(20.dp))
                .testTag("component.page"),
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                Row(
                    Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = ::dismiss, modifier = Modifier.size(48.dp)) {
                        Icon(MiuixIcons.Basic.ArrowRight, contentDescription = UiText.get(R.string.common_ui_m199),
                            modifier = Modifier.rotate(180f))
                    }
                    Text(
                        UiText.get(R.string.common_ui_m071), modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                        fontSize = 20.sp, fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.width(48.dp))
                }
                LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("component.list")) {
                    item(key = ComponentHideSettings.ENABLED) {
                        Toggle(UiText.get(R.string.common_ui_m200), ComponentHideSettings.ENABLED, settings.enabled, editor)
                    }
                    item(key = ComponentHideSettings.UNIFORM_ENABLED) {
                        Toggle(UiText.get(R.string.common_ui_m201), ComponentHideSettings.UNIFORM_ENABLED,
                            settings.uniformEnabled, editor)
                    }
                    item(key = ComponentHideSettings.UNIFORM_ALPHA) {
                        Alpha(UiText.get(R.string.common_ui_m202), null, ComponentHideSettings.UNIFORM_ALPHA,
                            settings.uniformAlpha, editor)
                    }
                    item(key = ComponentHideSettings.HIDE_ON_SWITCH) {
                        Toggle(UiText.get(R.string.common_ui_m203), ComponentHideSettings.HIDE_ON_SWITCH,
                            settings.hideOnSwitch, editor)
                    }
                    item(key = ComponentHideSettings.OPEN_COMMENTS_ON_SHOW) {
                        Toggle(UiText.get(R.string.common_ui_m204), ComponentHideSettings.OPEN_COMMENTS_ON_SHOW,
                            settings.openCommentsOnShow, editor)
                    }
                    item(key = ComponentHideSettings.SHOW_ON_PAUSE) {
                        Toggle(UiText.get(R.string.common_ui_m205), ComponentHideSettings.SHOW_ON_PAUSE,
                            settings.showOnPause, editor)
                    }
                    items(Component.values().toList(), key = { it.key }) { component ->
                        Alpha(componentTitle(component), if (component == Component.CARD) UiText.get(R.string.common_ui_m206) else null,
                            component.key, settings.individualAlpha(component), editor)
                    }
                    item(key = "component.custom.header") {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) { SmallTitle(UiText.get(R.string.common_ui_m207)) }
                            Text(UiText.get(R.string.common_ui_m068, settings.customViews.size),
                                modifier = Modifier.padding(end = 24.dp).testTag("component.custom.count"),
                                fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                        }
                    }
                    if (settings.customViews.isEmpty()) {
                        item(key = "component.custom.empty") {
                            BasicComponent(title = UiText.get(R.string.common_ui_m208))
                        }
                    }
                    items(settings.customViews, key = { "custom.${it.identityKey()}" }) { locator ->
                        BasicComponent(
                            title = locator.label,
                            summary = locatorSummary(locator),
                            modifier = Modifier.testTag("component.custom.${locator.identityKey()}"),
                            onClick = {
                                dismissCustomDetails()
                                customDetails = ComponentHideSettingsPage.showCustomViewDetails(context, locator, true)
                            },
                            endActions = {
                                IconButton(
                                    onClick = { pendingDelete = locator },
                                    modifier = Modifier.size(48.dp)
                                        .testTag("component.custom.delete.${locator.identityKey()}"),
                                ) {
                                    Icon(MiuixIcons.Basic.Close, contentDescription = UiText.get(R.string.common_ui_m209))
                                }
                            },
                        )
                    }
                    item(key = "component.bottom") { Spacer(Modifier.height(20.dp)) }
                }
            }
            OverlayDialog(
                show = pendingDelete != null,
                title = UiText.get(R.string.common_ui_m209),
                summary = UiText.get(R.string.common_ui_m210),
                onDismissRequest = { pendingDelete = null },
            ) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TextButton(UiText.get(R.string.module_action_cancel), onClick = { pendingDelete = null }, modifier = Modifier.weight(1f)
                        .testTag("component.custom.confirm_cancel"))
                    TextButton(UiText.get(R.string.common_ui_m211), onClick = {
                        pendingDelete?.let(editor::deleteCustomView)
                        pendingDelete = null
                    }, modifier = Modifier.weight(1f).testTag("component.custom.confirm_delete"))
                }
            }
        }
    }

    @Composable
    private fun Toggle(title: String, key: String, checked: Boolean, editor: ComponentHideEditor) {
        SwitchPreference(
            checked = checked,
            onCheckedChange = { editor.setBoolean(key, it) },
            title = title,
            modifier = Modifier.background(MiuixTheme.colorScheme.background).testTag(key),
        )
    }

    @Composable
    private fun Alpha(
        title: String, subtitle: String?, key: String, initial: Int, editor: ComponentHideEditor,
    ) {
        var alpha by remember(key, initial) { mutableIntStateOf(initial) }
        var touching by remember(key) { mutableStateOf(false) }
        var dirty by remember(key) { mutableStateOf(false) }
        val commit by rememberUpdatedState {
            if (dirty) {
                editor.setAlpha(key, alpha)
                dirty = false
            }
        }
        Column(Modifier.fillMaxWidth().background(MiuixTheme.colorScheme.background)
            .padding(horizontal = 24.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(end = 8.dp)) {
                    Text(title, fontSize = 16.sp)
                    if (subtitle != null) {
                        Text(subtitle, fontSize = 12.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    }
                }
                Text(UiText.get(R.string.common_ui_m069, alpha), modifier = Modifier.width(98.dp), fontSize = 14.sp)
            }
            Slider(
                value = (alpha + 2).toFloat(),
                onValueChange = {
                    val changed = it.roundToInt().coerceIn(0, 257) - 2
                    if (alpha != changed) {
                        alpha = changed
                        dirty = true
                        if (!touching) commit()
                    }
                },
                valueRange = 0f..257f,
                steps = 256,
                onValueChangeFinished = { commit() },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp).testTag(key)
                    .semantics {
                        contentDescription = title
                        stateDescription = UiText.get(R.string.common_ui_m069, alpha)
                    }
                    .pointerInput(key) {
                        // Miuix accessibility changes do not invoke onValueChangeFinished.
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                            touching = true
                            try {
                                do {
                                    val event = awaitPointerEvent(PointerEventPass.Initial)
                                } while (event.changes.any { it.pressed })
                            } finally {
                                touching = false
                                commit()
                            }
                        }
                    },
            )
        }
    }

    private fun componentTitle(component: Component): String = UiText.get(when (component) {
            Component.AVATAR -> R.string.common_ui_component_avatar
            Component.LIKE -> R.string.common_ui_component_like
            Component.COMMENT -> R.string.common_ui_component_comment
            Component.COLLECT -> R.string.common_ui_component_collect
            Component.SHARE -> R.string.common_ui_component_share
            Component.MUSIC_DISC -> R.string.common_ui_component_music_disc
            Component.CARD -> R.string.common_ui_component_card
            Component.AUTHOR -> R.string.common_ui_component_author
            Component.CO_CREATION -> R.string.common_ui_component_co_creation
            Component.DESCRIPTION -> R.string.common_ui_component_description
            Component.MUSIC_INFO -> R.string.common_ui_component_music_info
            Component.FULLSCREEN -> R.string.common_ui_component_fullscreen
            Component.TOP_NAV -> R.string.common_ui_component_top_nav
            Component.BOTTOM_NAV -> R.string.common_ui_component_bottom_nav
            Component.TAKO -> R.string.common_ui_component_tako
            Component.FEEDBACK -> R.string.common_ui_component_feedback
            Component.PROGRESS -> R.string.common_ui_component_progress
            Component.BOTTOM_TOOLS -> R.string.common_ui_component_bottom_tools
            Component.TIME_LOCATION -> R.string.common_ui_component_time_location
        })

    private fun locatorSummary(locator: CustomViewLocator): String {
        val scope = UiText.get(if (locator.scope == CustomViewLocator.Scope.FEED) R.string.common_ui_m098 else R.string.common_ui_m099)
        val mode = UiText.get(when (locator.matchingMode()) {
            CustomViewLocator.MatchingMode.RESOURCE_ID -> R.string.common_ui_m100
            CustomViewLocator.MatchingMode.ANCHORED_PATH -> R.string.common_ui_m101
            CustomViewLocator.MatchingMode.STRUCTURAL_PATH -> R.string.common_ui_m102
        })
        return scope + " · " + mode + "\n" + locator.viewClassName
    }

    private fun ownerActivity(source: Context): Activity? {
        var current = source
        while (current is ContextWrapper) {
            if (current is Activity) return current
            val next = current.baseContext
            if (next === current) return null
            current = next
        }
        return null
    }
}

/** Reload after writes so migration defaults and edits from the host remain authoritative. */
internal class ComponentHideEditor(private val store: SettingsStore) {
    var settings: ComponentHideSettings by mutableStateOf(ComponentHideSettings.readFrom(store))
        private set

    fun setBoolean(key: String, enabled: Boolean) {
        require(ComponentHideSettings.isBooleanKey(key))
        ComponentHideSettings.initialize(store)
        store.putBoolean(key, enabled)
        settings = ComponentHideSettings.readFrom(store)
    }

    fun setAlpha(key: String, alpha: Int) {
        require(ComponentHideSettings.isAlphaKey(key))
        require(alpha in -2..255)
        ComponentHideSettings.initialize(store)
        if (key != ComponentHideSettings.UNIFORM_ALPHA) {
            store.putBoolean(ComponentHideSettings.UNIFORM_ENABLED, false)
        }
        store.putString(key, alpha.toString())
        settings = ComponentHideSettings.readFrom(store)
    }

    fun deleteCustomView(locator: CustomViewLocator) {
        val latest = ComponentHideSettings.readFrom(store).customViews
        val updated = latest.filterNot { it.identityKey() == locator.identityKey() }
        store.putString(CustomViewLocator.SETTINGS_KEY, CustomViewLocator.serialize(updated))
        settings = ComponentHideSettings.readFrom(store)
    }
}
