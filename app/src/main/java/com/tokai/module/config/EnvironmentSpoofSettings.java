package com.tokai.module.config;

import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** One immutable process snapshot of Toki's four independently enabled environment features. */
public final class EnvironmentSpoofSettings {
    public static final String SIM_ENABLED = "sim_spoof_enabled";
    public static final String REGION = "target_region";
    public static final String OPERATOR_CODE = "sim_operator_code";
    public static final String OPERATOR_NAME = "sim_operator_name";
    public static final String LANGUAGE_ENABLED = "language_spoof_enabled";
    public static final String LANGUAGE_FOLLOW_REGION = "language_follow_region";
    public static final String LANGUAGE = "custom_language";
    public static final String TIME_ZONE_ENABLED = "timezone_spoof_enabled";
    public static final String TIME_ZONE_FOLLOW_REGION = "timezone_follow_region";
    public static final String TIME_ZONE = "custom_timezone";
    public static final String GPS_ENABLED = "gps_spoof_enabled";
    public static final String GPS_FOLLOW_REGION = "gps_follow_region";
    public static final String LATITUDE = "custom_latitude";
    public static final String LONGITUDE = "custom_longitude";
    public static final Set<String> BOOLEAN_KEYS = keys(SIM_ENABLED, LANGUAGE_ENABLED,
            LANGUAGE_FOLLOW_REGION, TIME_ZONE_ENABLED, TIME_ZONE_FOLLOW_REGION,
            GPS_ENABLED, GPS_FOLLOW_REGION);
    public static final Set<String> STRING_KEYS = keys(REGION, OPERATOR_CODE, OPERATOR_NAME,
            LANGUAGE, TIME_ZONE, LATITUDE, LONGITUDE);

    public final boolean simEnabled;
    public final boolean languageEnabled;
    public final boolean timeZoneEnabled;
    public final boolean gpsEnabled;
    public final boolean languageFollowRegion;
    public final boolean timeZoneFollowRegion;
    public final boolean gpsFollowRegion;
    public final String regionCode;
    public final RegionPreset regionPreset;
    public final String operatorCode;
    public final String operatorName;
    public final String languageTag;
    public final String timeZoneId;
    public final double latitude;
    public final double longitude;
    private final Map<String, Object> configuration;

    private static Set<String> keys(String... values) {
        return Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList(values)));
    }

    private EnvironmentSpoofSettings(LocalOptions options) {
        simEnabled = options.enabled(SIM_ENABLED);
        languageEnabled = options.enabled(LANGUAGE_ENABLED);
        timeZoneEnabled = options.enabled(TIME_ZONE_ENABLED);
        gpsEnabled = options.enabled(GPS_ENABLED);
        languageFollowRegion = options.enabled(LANGUAGE_FOLLOW_REGION, true);
        timeZoneFollowRegion = options.enabled(TIME_ZONE_FOLLOW_REGION, true);
        gpsFollowRegion = options.enabled(GPS_FOLLOW_REGION, true);
        String region = validOrDefault(REGION, options.text(REGION, "JP"), "JP");
        regionCode = region.toUpperCase(Locale.ROOT);
        regionPreset = RegionPreset.find(regionCode);
        String carrierCode = regionPreset == null ? "00000" : regionPreset.operatorCode;
        String carrierName = regionPreset == null ? regionCode + " Carrier" : regionPreset.operatorName;
        operatorCode = overrideOrDefault(OPERATOR_CODE, options.text(OPERATOR_CODE, ""), carrierCode);
        operatorName = overrideOrDefault(OPERATOR_NAME, options.text(OPERATOR_NAME, ""), carrierName);
        languageTag = languageFollowRegion ? regionPreset == null ? "en-US" : regionPreset.languageTag
                : validOrDefault(LANGUAGE, options.text(LANGUAGE, "ja-JP"), "ja-JP").replace('_', '-');
        timeZoneId = timeZoneFollowRegion ? regionPreset == null ? "UTC" : regionPreset.timeZoneId
                : validOrDefault(TIME_ZONE, options.text(TIME_ZONE, "Asia/Tokyo"), "Asia/Tokyo");
        latitude = gpsFollowRegion ? regionPreset == null ? 35.6762d : regionPreset.latitude
                : Double.parseDouble(validOrDefault(LATITUDE, options.text(LATITUDE, "35.6762"), "35.6762"));
        longitude = gpsFollowRegion ? regionPreset == null ? 139.6503d : regionPreset.longitude
                : Double.parseDouble(validOrDefault(LONGITUDE, options.text(LONGITUDE, "139.6503"), "139.6503"));

        // Retain dormant custom values too: changing a region-follow setting must not hide a
        // future custom-mode change from the process restart indicator.
        Map<String, Object> values = new LinkedHashMap<>();
        values.put(SIM_ENABLED, simEnabled);
        values.put(LANGUAGE_ENABLED, languageEnabled);
        values.put(TIME_ZONE_ENABLED, timeZoneEnabled);
        values.put(GPS_ENABLED, gpsEnabled);
        values.put(LANGUAGE_FOLLOW_REGION, languageFollowRegion);
        values.put(TIME_ZONE_FOLLOW_REGION, timeZoneFollowRegion);
        values.put(GPS_FOLLOW_REGION, gpsFollowRegion);
        values.put(REGION, options.text(REGION, "JP"));
        values.put(OPERATOR_CODE, options.text(OPERATOR_CODE, ""));
        values.put(OPERATOR_NAME, options.text(OPERATOR_NAME, ""));
        values.put(LANGUAGE, options.text(LANGUAGE, "ja-JP"));
        values.put(TIME_ZONE, options.text(TIME_ZONE, "Asia/Tokyo"));
        values.put(LATITUDE, options.text(LATITUDE, "35.6762"));
        values.put(LONGITUDE, options.text(LONGITUDE, "139.6503"));
        configuration = Collections.unmodifiableMap(values);
    }

    public static EnvironmentSpoofSettings defaults() {
        return read(new LocalOptions(Collections.emptyMap()));
    }

    public static EnvironmentSpoofSettings read(LocalOptions options) {
        return new EnvironmentSpoofSettings(options == null ? new LocalOptions(Collections.emptyMap()) : options);
    }

    /** Shared by interactive editors and configuration import. Empty carrier overrides inherit a preset. */
    public enum ValidationIssue {
        NULL("配置值不能为空"),
        LENGTH("配置内容过长"),
        COUNTRY("国家代码必须为两个英文字母"),
        CARRIER("运营商代码必须为五至六位数字，或留空跟随地区"),
        LANGUAGE("语言代码格式不正确"),
        TIMEZONE("时区标识不受支持"),
        LATITUDE("纬度必须为 -90 至 90 的有限数值"),
        LONGITUDE("经度必须为 -180 至 180 的有限数值");
        public final String legacyMessage;
        ValidationIssue(String legacyMessage) { this.legacyMessage = legacyMessage; }
    }

    public static String validate(String key, String value) {
        ValidationIssue issue = validateIssue(key, value);
        return issue == null ? null : issue.legacyMessage;
    }

    public static ValidationIssue validateIssue(String key, String value) {
        if (!STRING_KEYS.contains(key)) return null;
        if (value == null) return ValidationIssue.NULL;
        if (value.length() > 4096) return ValidationIssue.LENGTH;
        String text = value.trim();
        switch (key) {
            case REGION:
                return text.matches("[A-Za-z]{2}") ? null : ValidationIssue.COUNTRY;
            case OPERATOR_CODE:
                return text.isEmpty() || text.matches("[0-9]{5,6}") ? null : ValidationIssue.CARRIER;
            case OPERATOR_NAME:
                return null;
            case LANGUAGE:
                String tag = text.replace('_', '-');
                if (!tag.matches("[A-Za-z]{2,8}(-[A-Za-z0-9]{1,8})*")) return ValidationIssue.LANGUAGE;
                try {
                    Locale locale = new Locale.Builder().setLanguageTag(tag).build();
                    return locale.getLanguage().isEmpty() ? ValidationIssue.LANGUAGE : null;
                } catch (java.util.IllformedLocaleException invalid) {
                    return ValidationIssue.LANGUAGE;
                }
            case TIME_ZONE:
                // The original custom editor accepts fixed-offset IDs as well as named zones.
                // availableIDs() omits valid forms such as GMT+08:00 and UTC+08:00.
                try { ZoneId.of(text); return null; }
                catch (DateTimeException invalid) { return ValidationIssue.TIMEZONE; }
            case LATITUDE:
                return coordinateError(text, -90d, 90d, ValidationIssue.LATITUDE);
            case LONGITUDE:
                return coordinateError(text, -180d, 180d, ValidationIssue.LONGITUDE);
            default:
                return null;
        }
    }

    private static ValidationIssue coordinateError(String value, double min, double max, ValidationIssue error) {
        try {
            double coordinate = Double.parseDouble(value);
            return Double.isFinite(coordinate) && coordinate >= min && coordinate <= max ? null : error;
        } catch (NumberFormatException invalid) { return error; }
    }

    private static String validOrDefault(String key, String value, String fallback) {
        return validate(key, value) == null ? value.trim() : fallback;
    }

    private static String overrideOrDefault(String key, String value, String fallback) {
        return value.trim().isEmpty() ? fallback : validOrDefault(key, value, fallback);
    }

    public boolean anyEnabled() { return simEnabled || languageEnabled || timeZoneEnabled || gpsEnabled; }

    public boolean sameConfiguration(EnvironmentSpoofSettings other) { return equals(other); }

    @Override public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof EnvironmentSpoofSettings)) return false;
        EnvironmentSpoofSettings value = (EnvironmentSpoofSettings) other;
        return configuration.equals(value.configuration) && regionCode.equals(value.regionCode)
                && operatorCode.equals(value.operatorCode) && operatorName.equals(value.operatorName)
                && languageTag.equals(value.languageTag) && timeZoneId.equals(value.timeZoneId)
                && Double.compare(latitude, value.latitude) == 0
                && Double.compare(longitude, value.longitude) == 0;
    }

    @Override public int hashCode() {
        return Objects.hash(configuration, regionCode, operatorCode, operatorName, languageTag,
                timeZoneId, latitude, longitude);
    }
}
