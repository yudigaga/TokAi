package com.tokai.module.config;

import org.junit.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.*;

public final class RegionPresetTest {
    @Test public void containsAllSixtyFiveOriginalRegionsWithValidCompleteProfiles() {
        Set<String> expected = Set.of("JP", "KR", "TW", "HK", "MO", "MN", "US", "CA", "MX",
                "SG", "MY", "TH", "VN", "ID", "PH", "KH", "LA", "MM", "IN", "PK", "BD",
                "KZ", "UZ", "BY", "GB", "DE", "FR", "NL", "BE", "IE", "CH", "AT", "SE",
                "NO", "DK", "FI", "IS", "IT", "ES", "PT", "GR", "PL", "CZ", "HU", "RO",
                "TR", "RU", "UA", "AU", "NZ", "AE", "SA", "QA", "KW", "IL", "BR", "AR",
                "CL", "CO", "PE", "ZA", "EG", "NG", "KE", "MA");
        Set<String> actual = new HashSet<>();
        String previousName = "";
        for (RegionPreset preset : RegionPreset.all()) {
            assertTrue(actual.add(preset.code));
            assertFalse(preset.chineseName.isBlank());
            assertFalse(preset.englishName.isBlank());
            assertEquals(preset.chineseName, preset.displayName);
            assertTrue(String.CASE_INSENSITIVE_ORDER.compare(previousName, preset.englishName) <= 0);
            previousName = preset.englishName;
            assertNull(preset.code, EnvironmentSpoofSettings.validate(EnvironmentSpoofSettings.REGION, preset.code));
            assertNull(preset.code, EnvironmentSpoofSettings.validate(EnvironmentSpoofSettings.OPERATOR_CODE, preset.operatorCode));
            assertNull(preset.code, EnvironmentSpoofSettings.validate(EnvironmentSpoofSettings.LANGUAGE, preset.languageTag));
            assertNull(preset.code, EnvironmentSpoofSettings.validate(EnvironmentSpoofSettings.TIME_ZONE, preset.timeZoneId));
            assertTrue(Double.isFinite(preset.latitude) && Math.abs(preset.latitude) <= 90d);
            assertTrue(Double.isFinite(preset.longitude) && Math.abs(preset.longitude) <= 180d);
        }
        assertEquals(expected, actual);
        assertEquals(65, RegionPreset.all().size());
    }

    @Test public void searchesByChineseEnglishAndCodeWithoutCaseSensitivity() {
        assertEquals(List.of(RegionPreset.find("JP")), RegionPreset.search("日本"));
        assertEquals(List.of(RegionPreset.find("JP")), RegionPreset.search("JAPAN"));
        assertEquals(List.of(RegionPreset.find("JP")), RegionPreset.search(" jp "));
        assertEquals(3, RegionPreset.search("中国").size());
        assertTrue(RegionPreset.search("does-not-exist").isEmpty());
        assertSame(RegionPreset.all(), RegionPreset.search(" "));
        assertNull(RegionPreset.find("XX"));
        assertNull(RegionPreset.find(null));
        assertSame(RegionPreset.find("US"), RegionPreset.find(" us "));
    }

    @Test public void profilesKeepOriginalCarrierAndRepresentativeCoordinates() {
        RegionPreset japan = RegionPreset.find("JP");
        assertEquals("44010", japan.operatorCode);
        assertEquals("NTT DOCOMO", japan.operatorName);
        assertEquals("ja-JP", japan.languageTag);
        assertEquals("Asia/Tokyo", japan.timeZoneId);
        assertEquals(35.6762d, japan.latitude, 0d);
        assertEquals(139.6503d, japan.longitude, 0d);
        RegionPreset unitedStates = RegionPreset.find("US");
        assertEquals("310410", unitedStates.operatorCode);
        assertEquals("AT&T", unitedStates.operatorName);
        assertEquals("America/New_York", unitedStates.timeZoneId);
        assertEquals(-74.006d, unitedStates.longitude, 0d);
        RegionPreset belarus = RegionPreset.find("BY");
        assertEquals("257002", belarus.operatorCode);
    }

    @Test(expected = UnsupportedOperationException.class)
    public void completePresetListCannotBeMutated() { RegionPreset.all().clear(); }

    @Test(expected = UnsupportedOperationException.class)
    public void filteredPresetListCannotBeMutated() { RegionPreset.search("Japan").clear(); }
}
