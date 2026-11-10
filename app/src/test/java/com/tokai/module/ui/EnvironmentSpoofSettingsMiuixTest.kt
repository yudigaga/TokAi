package com.tokai.module.ui

import android.app.Dialog
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import com.tokai.module.config.HostSettingsStore
import com.tokai.module.config.RegionPreset
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "zh-rCN-w360dp-h720dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EnvironmentSpoofSettingsMiuixTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var page: EnvironmentSpoofSettingsDialog
    private lateinit var store: HostSettingsStore
    private val children = mutableListOf<Dialog>()

    @After fun closePage() {
        compose.runOnUiThread {
            if (::page.isInitialized) page.close()
            children.forEach { it.dismiss() }
        }
    }

    @Test fun miuixSwitchAndSearchableRegionPickerPreserveIndependentFeatureChoices() {
        compose.runOnUiThread {
            store = HostSettingsStore(compose.activity)
            page = EnvironmentSpoofSettingsDialog(compose.activity, store, true) { child, dismissed ->
                children.add(child)
                child.setOwnerActivity(compose.activity)
                child.setOnDismissListener { dismissed?.run() }
                child.show()
            }
            page.dialog().setOwnerActivity(compose.activity)
            page.dialog().show()
        }
        compose.runOnIdle { assertFalse(store.getBoolean("sim_spoof_enabled", false)) }
        compose.onNodeWithTag("settings.toggle.SIM 地区伪装").assertIsDisplayed().performClick()
        compose.runOnIdle { assertTrue(store.getBoolean("sim_spoof_enabled", false)) }
        compose.onNodeWithTag("settings.option.目标地区").performClick()
        compose.onNodeWithTag("environment.region.search").performTextReplacement("United States")
        val us = RegionPreset.find("US")!!
        compose.onNodeWithTag("settings.option.${us.displayName} · ${us.englishName}").performClick()
        compose.onNodeWithTag("settings.toggle.SIM 地区伪装").assertIsDisplayed()
        compose.runOnIdle {
            assertTrue(store.getBoolean("sim_spoof_enabled", false))
            assertEquals("US", store.getString("target_region", ""))
            assertFalse(store.getBoolean("language_spoof_enabled", false))
            assertFalse(store.getBoolean("timezone_spoof_enabled", false))
            assertFalse(store.getBoolean("gps_spoof_enabled", false))
        }
    }
}
