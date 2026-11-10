package com.tokai.module.scan;

import org.junit.Test;
import java.util.Collections;
import java.util.List;
import static org.junit.Assert.*;

public final class HostFeatureSupportTest {
    @Test public void undeclaredCapabilityIsDistinctFromADeclaredRuleThatFailedScanning() {
        HostScanReport absent = new HostScanReport("fixture", Collections.emptyList(), false);
        assertEquals("Unsupported host rules: missing", HostFeatureSupport.undeclaredRules(absent, "missing"));
        HostScanReport notFound = new HostScanReport("fixture", List.of(
                new RuleScanResult("declared", "fixture", Collections.emptyList())), false);
        assertEquals("Declared scan failures must still take the error path", "",
                HostFeatureSupport.undeclaredRules(notFound, "missing", "declared"));
        assertEquals("An absent report is not a known unsupported capability", "",
                HostFeatureSupport.undeclaredRules(null, "missing"));
    }

    @Test public void onlyKnownUnportedSettingsAreDisabledAndDoubleTapRemainsAvailable() {
        assertTrue(HostFeatureSupport.settingUnavailableReason("hide_camera_plus").isEmpty());
        assertTrue(HostFeatureSupport.settingUnavailableReason("prevent_insert_video").isEmpty());
        assertFalse(HostFeatureSupport.settingUnavailableReason("remove_teen_dialog").isEmpty());
        for (String key : new String[]{"prevent_skip_hidden", "hide_recommendation_merchants",
                "hide_product_cards", "hide_hotspot_rank_cards", "profile_mutual_count"}) {
            assertFalse(key, HostFeatureSupport.settingUnavailableReason(key).isEmpty());
        }
        assertTrue(HostFeatureSupport.settingUnavailableReason("hide_recommendation_cards").isEmpty());
        assertTrue(HostFeatureSupport.settingUnavailableReason("hide_native_captions").isEmpty());
        assertTrue(HostFeatureSupport.settingUnavailableReason("disable_data_saver").isEmpty());
        assertTrue(HostFeatureSupport.settingUnavailableReason("disable_double_tap_like").isEmpty());
        assertTrue(HostFeatureSupport.settingUnavailableReason("auto_play").isEmpty());
        assertFalse(HostFeatureSupport.isUnavailable("IllegalStateException: target changed"));
    }
}
