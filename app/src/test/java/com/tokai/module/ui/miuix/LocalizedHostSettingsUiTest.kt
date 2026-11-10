package com.tokai.module.ui.miuix

import android.app.AlertDialog
import android.content.res.Configuration
import android.content.res.Resources
import android.os.LocaleList
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextReplacement
import com.tokai.module.config.AiModelConfig
import com.tokai.module.config.AiModelSettings
import com.tokai.module.config.HostSettingsStore
import com.tokai.module.config.SettingsRepository
import com.tokai.module.i18n.ModuleLanguage
import com.tokai.module.ui.ModuleSettingsScreen
import java.util.Locale
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en-w360dp-h720dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LocalizedHostSettingsUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var store: HostSettingsStore
    private lateinit var screen: ModuleSettingsScreen
    private lateinit var saved: Map<String, *>
    private lateinit var hostConfiguration: Configuration
    private lateinit var systemLocales: LocaleList
    private lateinit var javaDefault: Locale

    @After fun closeSettings() {
        if (::screen.isInitialized) compose.runOnUiThread { screen.close() }
    }

    @Test fun japaneseHomeOpensSixLanguageChoicesAndCancelPreservesHostAndUserContent() {
        open("ja")
        compose.onNodeWithText("表示言語").assertIsDisplayed()
        compose.onNodeWithTag("settings.language").performScrollTo().performClick()
        compose.onNodeWithTag("miuix.dialog.title").assertTextEquals("表示言語")
        lateinit var dialog: AlertDialog
        compose.runOnIdle {
            dialog = ShadowDialog.getLatestDialog() as AlertDialog
            assertTrue(dialog.isShowing)
            val names = listOf("システムに合わせる", "简体中文", "繁體中文", "한국어", "日本語", "English")
            assertEquals(names, (0 until dialog.listView.count).map { dialog.listView.getItemAtPosition(it).toString() })
            assertEquals(4, dialog.listView.checkedItemPosition)
        }
        for (index in 0..5) {
            compose.onNodeWithTag("miuix.dialog.choices").performScrollToIndex(index)
            compose.onNodeWithTag("miuix.dialog.choice.$index").assertIsDisplayed()
        }
        compose.onNodeWithText("キャンセル").assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertFalse(dialog.isShowing)
            assertEquals("ja", store.getString(ModuleLanguage.KEY, "system"))
            assertUnchanged()
        }
    }

    @Test fun englishCaptionSearchOpensTranslatedControlsWithStableChineseTags() {
        open("en")
        compose.onNodeWithText("Interface language").assertIsDisplayed()
        compose.onNodeWithTag("settings.search").performTextReplacement("captions")
        compose.onNodeWithText("Captions and automatic recognition").assertIsDisplayed()
        compose.onNodeWithTag("settings.search.result.captions").performScrollTo().performClick()
        compose.onNodeWithTag("miuix.category.page").assertIsDisplayed()
        compose.onNodeWithTag("settings.toggle.强制展示字幕").assertIsDisplayed()
        compose.onNodeWithText("Force captions").assertIsDisplayed()
        compose.onNodeWithTag("settings.toggle.关闭字幕").assertExists()
        compose.runOnIdle {
            val dialog = ShadowDialog.getLatestDialog()
            assertTrue(dialog.isShowing)
            assertNotNull(dialog.window!!.decorView.findViewWithTag<View>("settings.toggle.强制展示字幕"))
            assertNotNull(dialog.window!!.decorView.findViewWithTag<View>("settings.toggle.关闭字幕"))
            assertUnchanged()
        }
    }

    private fun open(language: String) {
        compose.runOnUiThread {
            val activity = compose.activity
            activity.getSharedPreferences(SettingsRepository.PREFERENCES_NAME, 0).edit().clear().commit()
            store = HostSettingsStore(activity)
            store.putString(ModuleLanguage.KEY, language)
            store.putString("reply_prompt", USER_PROMPT)
            val model = AiModelConfig.fromJson(JSONObject().put("id", "localized-ui")
                .put("name", USER_NAME).put("systemPrompt", USER_PROMPT))
            AiModelSettings(listOf(model), "localized-ui").save(store)
            saved = store.snapshot()
            hostConfiguration = Configuration(activity.resources.configuration)
            systemLocales = Resources.getSystem().configuration.locales
            javaDefault = Locale.getDefault()
            screen = ModuleSettingsScreen(activity, store)
            activity.setContentView(screen.createMiuixView())
        }
    }

    private fun assertUnchanged() {
        assertEquals(saved, store.snapshot())
        val model = AiModelSettings.read(store).configs.single()
        assertEquals(USER_NAME, model.name)
        assertEquals(USER_PROMPT, model.systemPrompt)
        assertEquals(USER_PROMPT, store.getString("reply_prompt", ""))
        assertEquals(hostConfiguration, compose.activity.resources.configuration)
        assertEquals(systemLocales, Resources.getSystem().configuration.locales)
        assertEquals(javaDefault, Locale.getDefault())
    }

    companion object {
        private const val USER_NAME = "用户名字 日本語 한국어 %2\$d"
        private const val USER_PROMPT = "用户原文：保持日本語와 한국어\n{{target}} %1\$s"
    }
}
