package com.tokai.module.config;

import org.junit.Test;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import static com.tokai.module.config.EnvironmentSpoofSettings.*;
import static org.junit.Assert.*;

public final class EnvironmentSpoofSettingsTest {
    @Test public void defaultSnapshotLeavesAllFourFeaturesOffWithJapaneseRegionDefaults() {
        EnvironmentSpoofSettings settings = defaults();
        assertFalse(settings.anyEnabled());
        assertFalse(settings.simEnabled);
        assertFalse(settings.languageEnabled);
        assertFalse(settings.timeZoneEnabled);
        assertFalse(settings.gpsEnabled);
        assertTrue(settings.languageFollowRegion);
        assertTrue(settings.timeZoneFollowRegion);
        assertTrue(settings.gpsFollowRegion);
        assertEquals("JP", settings.regionCode);
        assertEquals("44010", settings.operatorCode);
        assertEquals("NTT DOCOMO", settings.operatorName);
        assertEquals("ja-JP", settings.languageTag);
        assertEquals("Asia/Tokyo", settings.timeZoneId);
        assertEquals(35.6762d, settings.latitude, 0d);
        assertEquals(139.6503d, settings.longitude, 0d);
    }

    @Test public void selectedRegionResolvesCarrierLanguageTimeZoneAndCoordinatesTogether() {
        EnvironmentSpoofSettings settings = read(new LocalOptions(Map.of(REGION, " us ", SIM_ENABLED, true)));
        assertEquals("US", settings.regionCode);
        assertTrue(settings.simEnabled);
        assertFalse(settings.languageEnabled);
        assertFalse(settings.timeZoneEnabled);
        assertFalse(settings.gpsEnabled);
        assertEquals("310410", settings.operatorCode);
        assertEquals("AT&T", settings.operatorName);
        assertEquals("en-US", settings.languageTag);
        assertEquals("America/New_York", settings.timeZoneId);
        assertEquals(40.7128d, settings.latitude, 0d);
        assertEquals(-74.006d, settings.longitude, 0d);
    }

    @Test public void unknownTwoLetterRegionRetainsTokisFourIndependentFallbacks() {
        EnvironmentSpoofSettings settings = read(new LocalOptions(Map.of(REGION, "XX")));
        assertNull(settings.regionPreset);
        assertEquals("XX", settings.regionCode);
        assertEquals("00000", settings.operatorCode);
        assertEquals("XX Carrier", settings.operatorName);
        assertEquals("en-US", settings.languageTag);
        assertEquals("UTC", settings.timeZoneId);
        assertEquals(35.6762d, settings.latitude, 0d);
        assertEquals(139.6503d, settings.longitude, 0d);
    }

    @Test public void customValuesWorkIndependentlyAndEmptyCarrierOverridesInheritRegion() {
        EnvironmentSpoofSettings settings = read(new LocalOptions(Map.of(
                REGION, "DE", OPERATOR_CODE, " 310260 ", OPERATOR_NAME, " Custom Carrier ",
                LANGUAGE_FOLLOW_REGION, false, LANGUAGE, "zh_Hant_TW",
                TIME_ZONE_FOLLOW_REGION, false, TIME_ZONE, "Pacific/Auckland",
                GPS_FOLLOW_REGION, false, LATITUDE, "-90", LONGITUDE, "180")));
        assertEquals("DE", settings.regionCode);
        assertEquals("310260", settings.operatorCode);
        assertEquals("Custom Carrier", settings.operatorName);
        assertEquals("zh-Hant-TW", settings.languageTag);
        assertEquals("Pacific/Auckland", settings.timeZoneId);
        assertEquals(-90d, settings.latitude, 0d);
        assertEquals(180d, settings.longitude, 0d);
        settings = read(new LocalOptions(Map.of(REGION, "GB", OPERATOR_CODE, " ", OPERATOR_NAME, "")));
        assertEquals("23410", settings.operatorCode);
        assertEquals("O2", settings.operatorName);
    }

    @Test public void damagedPersistedFieldsFallBackWithoutBreakingOtherOverrides() {
        EnvironmentSpoofSettings settings = read(new LocalOptions(Map.of(
                REGION, "bad", OPERATOR_CODE, "oops", LANGUAGE_FOLLOW_REGION, false,
                LANGUAGE, "en--US", TIME_ZONE_FOLLOW_REGION, false, TIME_ZONE, "Not/A_Zone",
                GPS_FOLLOW_REGION, false, LATITUDE, "NaN", LONGITUDE, "181")));
        assertEquals("JP", settings.regionCode);
        assertEquals("44010", settings.operatorCode);
        assertEquals("ja-JP", settings.languageTag);
        assertEquals("Asia/Tokyo", settings.timeZoneId);
        assertEquals(35.6762d, settings.latitude, 0d);
        assertEquals(139.6503d, settings.longitude, 0d);
        assertEquals(defaults(), read(null));
    }

    @Test public void validationRejectsNonFiniteAndOutOfRangeCoordinatesButAllowsExactBounds() {
        for (String value : new String[]{"NaN", "Infinity", "-Infinity", "1e9999", "91", "-90.001", ""}) {
            assertNotNull(value, validate(LATITUDE, value));
        }
        for (String value : new String[]{"NaN", "Infinity", "180.001", "-181", "none"}) {
            assertNotNull(value, validate(LONGITUDE, value));
        }
        for (String value : new String[]{"-90", "0", "90", "35.6762", "-0.0"}) {
            assertNull(value, validate(LATITUDE, value));
        }
        for (String value : new String[]{"-180", "0", "180", "139.6503"}) {
            assertNull(value, validate(LONGITUDE, value));
        }
    }

    @Test public void validationSharesCountryCarrierLanguageAndTimeZoneRules() {
        assertNull(validate(REGION, "us"));
        assertNull(validate(REGION, "XX"));
        assertNotNull(validate(REGION, "USA"));
        assertNotNull(validate(REGION, "中国"));
        assertNull(validate(OPERATOR_CODE, "44010"));
        assertNull(validate(OPERATOR_CODE, "310410"));
        assertNull(validate(OPERATOR_CODE, ""));
        assertNotNull(validate(OPERATOR_CODE, "3104"));
        assertNotNull(validate(OPERATOR_CODE, "310A10"));
        assertNull(validate(LANGUAGE, "zh-Hant-TW"));
        assertNull(validate(LANGUAGE, "en-US-u-nu-latn"));
        assertNotNull(validate(LANGUAGE, "en--US"));
        assertNotNull(validate(LANGUAGE, "und"));
        assertNotNull(validate(LANGUAGE, "123"));
        assertNull(validate(TIME_ZONE, "UTC"));
        assertNull(validate(TIME_ZONE, "Asia/Tokyo"));
        assertNotNull(validate(TIME_ZONE, "No/Such_Time_Zone"));
        assertNotNull(validate(REGION, null));
        assertNotNull(validate(OPERATOR_NAME, "x".repeat(4097)));
    }

    @Test public void equalityIncludesDormantSettingsButIgnoresUnrelatedAiOptions() {
        EnvironmentSpoofSettings original = defaults();
        EnvironmentSpoofSettings same = read(new LocalOptions(Map.of("comment_voice", true)));
        assertEquals(original, same);
        assertEquals(original.hashCode(), same.hashCode());
        assertTrue(original.sameConfiguration(same));
        assertNotEquals(original, read(new LocalOptions(Map.of(LANGUAGE, "en-GB"))));
        assertNotEquals(original, read(new LocalOptions(Map.of(SIM_ENABLED, true))));
        assertNotEquals(original, read(new LocalOptions(Map.of(GPS_FOLLOW_REGION, false))));
        assertNotEquals(original, read(new LocalOptions(Map.of(REGION, "KR"))));
    }

    @Test public void customTimeZoneAcceptsValidNamedAndFixedOffsetIdsWithoutSilentGmtFallback() {
        for (String id : new String[]{"GMT+08:00", "UTC+08:00", "+08:00", "UTC-03:30", "Z", "Asia/Tokyo"}) {
            assertNull(id, validate(TIME_ZONE, id));
            EnvironmentSpoofSettings settings = read(new LocalOptions(Map.of(
                    TIME_ZONE_FOLLOW_REGION, false, TIME_ZONE, id)));
            assertEquals(id, settings.timeZoneId);
        }
        for (String id : new String[]{"GMT+25:00", "UTC+08:99", "Mars/Olympus", "", "+19:00"}) {
            assertNotNull(id, validate(TIME_ZONE, id));
        }
    }

    @Test public void snapshotDoesNotChangeWhenSourceMapOrSystemLocaleChanges() {
        Map<String, Object> values = new HashMap<>();
        values.put(REGION, "KR");
        EnvironmentSpoofSettings original = read(new LocalOptions(values));
        values.put(REGION, "US");
        Locale before = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            assertEquals("KR", original.regionCode);
            assertEquals("ko-KR", original.languageTag);
            assertEquals("KR", read(new LocalOptions(Map.of(REGION, "kr"))).regionCode);
        } finally { Locale.setDefault(before); }
    }
}
