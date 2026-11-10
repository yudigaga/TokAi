package com.tokai.module.scan;

import org.junit.Test;
import java.io.*;
import java.util.*;
import static org.junit.Assert.*;

/** Product isolation and exact binary identity are required before a TikTok rule can be installed. */
public final class TikTokHostProfileTest {
    @Test public void onlyTheExactTikTokProductAndVersionCanSelectTheProfile() {
        assertSame(HostAdaptation.PROFILE_TIKTOK, HostAdaptation.forVersion(
                "com.zhiliaoapp.musically", "47.5.3", 2024705030L));
        for (String pkg : new String[]{"com.ss.android.ugc.aweme", "com.ss.android.ugc.trill", "io.github.yudigaga.tokai", "", null})
            assertNull(HostAdaptation.forVersion(pkg, "47.5.3", 2024705030L));
        assertNull(HostAdaptation.forVersion("com.zhiliaoapp.musically", "47.5.3", 2024705031L));
        assertNull(HostAdaptation.forVersion("com.zhiliaoapp.musically", "47.5.4", 2024705030L));
        assertNull(HostAdaptation.forVersion("com.zhiliaoapp.musically", "40.8.0", 400801L));
    }

    @Test public void binaryManifestSelectsTheSameProfileForBothStringEncodings() throws Exception {
        for (boolean utf8 : new boolean[]{true, false}) {
            byte[] bytes = BinaryHostManifestFixture.manifest("com.zhiliaoapp.musically", "47.5.3", 2024705030,
                    utf8, HostAdaptation.VERIFIED_TIKTOK_UPDATE_VERSION_CODE);
            HostApkProfile.Identity identity = HostApkProfile.parseManifest(bytes);
            assertEquals("com.zhiliaoapp.musically", identity.packageName);
            assertEquals(2024705030L, identity.versionCode);
            assertEquals(HostAdaptation.VERIFIED_TIKTOK_UPDATE_VERSION_CODE, identity.updateVersionCode);
            assertSame(HostAdaptation.PROFILE_TIKTOK, HostAdaptation.forVersion(identity.packageName, identity.versionName, identity.versionCode));
        }
        assertSame(HostAdaptation.PROFILE_TIKTOK, HostApkProfile.read(BinaryHostManifestFixture.apk(
                "com.zhiliaoapp.musically", "47.5.3", 2024705030,
                HostAdaptation.VERIFIED_TIKTOK_UPDATE_VERSION_CODE)));
        assertNull(HostApkProfile.read(BinaryHostManifestFixture.apk(
                "com.zhiliaoapp.musically", "47.5.3", 2024705030, -1)));
        assertNull(HostApkProfile.read(BinaryHostManifestFixture.apk(
                "com.zhiliaoapp.musically", "47.5.3", 2024705030, 40809900)));
    }

    @Test public void packagedRulesBelongToTikTokAndMatchTheProfileFingerprint() throws Exception {
        try (InputStream in = new FileInputStream(HostApkTestProfile.assetFile(HostAdaptation.RULES_ASSET))) {
            HostRuleSet rules = HostRuleSet.parse(in);
            assertEquals("com.zhiliaoapp.musically", rules.getTargetPackage());
            assertFalse(rules.getRules().isEmpty());
            assertEquals(HostAdaptation.PROFILE_TIKTOK.fingerprint, rules.getFingerprint());
            Set<String> ids = new HashSet<>();
            for (HostRule rule : rules.getRules()) assertTrue(ids.add(rule.getId()));
        }
    }
}
