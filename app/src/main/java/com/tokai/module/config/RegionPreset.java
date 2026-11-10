package com.tokai.module.config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Region, carrier, language, time zone and coordinate presets recovered from Toki 1.0.4. */
public final class RegionPreset {
    public final String chineseName;
    public final String displayName;
    public final String englishName;
    public final String code;
    public final String operatorCode;
    public final String operatorName;
    public final String languageTag;
    public final String timeZoneId;
    public final double latitude;
    public final double longitude;
    private static final List<RegionPreset> PRESETS;
    private static final Map<String, RegionPreset> BY_CODE;

    private RegionPreset(String chineseName, String englishName, String code,
                         String operatorCode, String operatorName, String languageTag,
                         String timeZoneId, double latitude, double longitude) {
        this.chineseName = chineseName;
        this.displayName = chineseName;
        this.englishName = englishName;
        this.code = code;
        this.operatorCode = operatorCode;
        this.operatorName = operatorName;
        this.languageTag = languageTag;
        this.timeZoneId = timeZoneId;
        this.latitude = latitude;
        this.longitude = longitude;
    }

    static {
        List<RegionPreset> presets = new ArrayList<>(Arrays.asList(
                new RegionPreset("日本", "Japan", "JP", "44010", "NTT DOCOMO", "ja-JP", "Asia/Tokyo", 35.6762d, 139.6503d),
                new RegionPreset("韩国", "South Korea", "KR", "45005", "SKTelecom", "ko-KR", "Asia/Seoul", 37.5665d, 126.978d),
                new RegionPreset("中国台湾", "Taiwan", "TW", "46692", "Chunghwa Telecom", "zh-TW", "Asia/Taipei", 25.033d, 121.5654d),
                new RegionPreset("中国香港", "Hong Kong", "HK", "45412", "CMHK", "zh-HK", "Asia/Hong_Kong", 22.3193d, 114.1694d),
                new RegionPreset("中国澳门", "Macau", "MO", "45501", "CTM", "zh-MO", "Asia/Macau", 22.1987d, 113.5439d),
                new RegionPreset("蒙古", "Mongolia", "MN", "42899", "Mobicom", "mn-MN", "Asia/Ulaanbaatar", 47.8864d, 106.9057d),
                new RegionPreset("美国", "United States", "US", "310410", "AT&T", "en-US", "America/New_York", 40.7128d, -74.006d),
                new RegionPreset("加拿大", "Canada", "CA", "302720", "Rogers", "en-CA", "America/Toronto", 43.6532d, -79.3832d),
                new RegionPreset("墨西哥", "Mexico", "MX", "334020", "Telcel", "es-MX", "America/Mexico_City", 19.4326d, -99.1332d),
                new RegionPreset("新加坡", "Singapore", "SG", "52501", "Singtel", "en-SG", "Asia/Singapore", 1.3521d, 103.8198d),
                new RegionPreset("马来西亚", "Malaysia", "MY", "50212", "Maxis", "ms-MY", "Asia/Kuala_Lumpur", 3.139d, 101.6869d),
                new RegionPreset("泰国", "Thailand", "TH", "52001", "AIS", "th-TH", "Asia/Bangkok", 13.7563d, 100.5018d),
                new RegionPreset("越南", "Vietnam", "VN", "45204", "Viettel", "vi-VN", "Asia/Ho_Chi_Minh", 10.8231d, 106.6297d),
                new RegionPreset("印度尼西亚", "Indonesia", "ID", "51010", "Telkomsel", "id-ID", "Asia/Jakarta", -6.2088d, 106.8456d),
                new RegionPreset("菲律宾", "Philippines", "PH", "51502", "Globe", "en-PH", "Asia/Manila", 14.5995d, 120.9842d),
                new RegionPreset("柬埔寨", "Cambodia", "KH", "45601", "Smart", "km-KH", "Asia/Phnom_Penh", 11.5564d, 104.9282d),
                new RegionPreset("老挝", "Laos", "LA", "45701", "LaoTelecom", "lo-LA", "Asia/Vientiane", 17.9757d, 102.6331d),
                new RegionPreset("缅甸", "Myanmar", "MM", "41401", "MPT", "my-MM", "Asia/Yangon", 16.8661d, 96.1951d),
                new RegionPreset("印度", "India", "IN", "40445", "Airtel", "en-IN", "Asia/Kolkata", 28.6139d, 77.209d),
                new RegionPreset("巴基斯坦", "Pakistan", "PK", "41001", "Jazz", "ur-PK", "Asia/Karachi", 33.6844d, 73.0479d),
                new RegionPreset("孟加拉国", "Bangladesh", "BD", "47001", "Grameenphone", "bn-BD", "Asia/Dhaka", 23.8103d, 90.4125d),
                new RegionPreset("哈萨克斯坦", "Kazakhstan", "KZ", "40102", "Kcell", "kk-KZ", "Asia/Almaty", 43.222d, 76.8512d),
                new RegionPreset("乌兹别克斯坦", "Uzbekistan", "UZ", "43404", "Beeline UZ", "uz-UZ", "Asia/Tashkent", 41.2995d, 69.2401d),
                new RegionPreset("白俄罗斯", "Belarus", "BY", "257002", "MTS", "be-BY", "Europe/Minsk", 53.9006d, 27.559d),
                new RegionPreset("英国", "United Kingdom", "GB", "23410", "O2", "en-GB", "Europe/London", 51.5074d, -0.1278d),
                new RegionPreset("德国", "Germany", "DE", "26201", "Telekom", "de-DE", "Europe/Berlin", 52.52d, 13.405d),
                new RegionPreset("法国", "France", "FR", "20801", "Orange", "fr-FR", "Europe/Paris", 48.8566d, 2.3522d),
                new RegionPreset("荷兰", "Netherlands", "NL", "20408", "KPN", "nl-NL", "Europe/Amsterdam", 52.3676d, 4.9041d),
                new RegionPreset("比利时", "Belgium", "BE", "20601", "Proximus", "nl-BE", "Europe/Brussels", 50.8503d, 4.3517d),
                new RegionPreset("爱尔兰", "Ireland", "IE", "27201", "Vodafone IE", "en-IE", "Europe/Dublin", 53.3498d, -6.2603d),
                new RegionPreset("瑞士", "Switzerland", "CH", "22801", "Swisscom", "de-CH", "Europe/Zurich", 47.3769d, 8.5417d),
                new RegionPreset("奥地利", "Austria", "AT", "23201", "A1", "de-AT", "Europe/Vienna", 48.2082d, 16.3738d),
                new RegionPreset("瑞典", "Sweden", "SE", "24001", "Telia", "sv-SE", "Europe/Stockholm", 59.3293d, 18.0686d),
                new RegionPreset("挪威", "Norway", "NO", "24201", "Telenor", "nb-NO", "Europe/Oslo", 59.9139d, 10.7522d),
                new RegionPreset("丹麦", "Denmark", "DK", "23801", "TDC", "da-DK", "Europe/Copenhagen", 55.6761d, 12.5683d),
                new RegionPreset("芬兰", "Finland", "FI", "24405", "Elisa", "fi-FI", "Europe/Helsinki", 60.1699d, 24.9384d),
                new RegionPreset("冰岛", "Iceland", "IS", "27401", "Siminn", "is-IS", "Atlantic/Reykjavik", 64.1466d, -21.9426d),
                new RegionPreset("意大利", "Italy", "IT", "22201", "TIM", "it-IT", "Europe/Rome", 41.9028d, 12.4964d),
                new RegionPreset("西班牙", "Spain", "ES", "21407", "Movistar", "es-ES", "Europe/Madrid", 40.4168d, -3.7038d),
                new RegionPreset("葡萄牙", "Portugal", "PT", "26801", "Vodafone PT", "pt-PT", "Europe/Lisbon", 38.7223d, -9.1393d),
                new RegionPreset("希腊", "Greece", "GR", "20201", "Cosmote", "el-GR", "Europe/Athens", 37.9838d, 23.7275d),
                new RegionPreset("波兰", "Poland", "PL", "26001", "Plus", "pl-PL", "Europe/Warsaw", 52.2297d, 21.0122d),
                new RegionPreset("捷克", "Czech Republic", "CZ", "23001", "T-Mobile CZ", "cs-CZ", "Europe/Prague", 50.0755d, 14.4378d),
                new RegionPreset("匈牙利", "Hungary", "HU", "21630", "Telekom HU", "hu-HU", "Europe/Budapest", 47.4979d, 19.0402d),
                new RegionPreset("罗马尼亚", "Romania", "RO", "22601", "Vodafone RO", "ro-RO", "Europe/Bucharest", 44.4268d, 26.1025d),
                new RegionPreset("土耳其", "Turkey", "TR", "28601", "Turkcell", "tr-TR", "Europe/Istanbul", 41.0082d, 28.9784d),
                new RegionPreset("俄罗斯", "Russia", "RU", "25001", "MTS", "ru-RU", "Europe/Moscow", 55.7558d, 37.6173d),
                new RegionPreset("乌克兰", "Ukraine", "UA", "25501", "Vodafone UA", "uk-UA", "Europe/Kyiv", 50.4501d, 30.5234d),
                new RegionPreset("澳大利亚", "Australia", "AU", "50501", "Telstra", "en-AU", "Australia/Sydney", -33.8688d, 151.2093d),
                new RegionPreset("新西兰", "New Zealand", "NZ", "53001", "Vodafone NZ", "en-NZ", "Pacific/Auckland", -36.8485d, 174.7633d),
                new RegionPreset("阿联酋", "United Arab Emirates", "AE", "42402", "du", "ar-AE", "Asia/Dubai", 25.2048d, 55.2708d),
                new RegionPreset("沙特阿拉伯", "Saudi Arabia", "SA", "42001", "STC", "ar-SA", "Asia/Riyadh", 24.7136d, 46.6753d),
                new RegionPreset("卡塔尔", "Qatar", "QA", "42701", "Ooredoo", "ar-QA", "Asia/Qatar", 25.2854d, 51.531d),
                new RegionPreset("科威特", "Kuwait", "KW", "41902", "Zain KW", "ar-KW", "Asia/Kuwait", 29.3759d, 47.9774d),
                new RegionPreset("以色列", "Israel", "IL", "42501", "Partner", "he-IL", "Asia/Jerusalem", 32.0853d, 34.7818d),
                new RegionPreset("巴西", "Brazil", "BR", "72405", "Claro BR", "pt-BR", "America/Sao_Paulo", -23.5505d, -46.6333d),
                new RegionPreset("阿根廷", "Argentina", "AR", "722310", "Claro AR", "es-AR", "America/Argentina/Buenos_Aires", -34.6037d, -58.3816d),
                new RegionPreset("智利", "Chile", "CL", "73001", "Entel", "es-CL", "America/Santiago", -33.4489d, -70.6693d),
                new RegionPreset("哥伦比亚", "Colombia", "CO", "732101", "Claro CO", "es-CO", "America/Bogota", 4.711d, -74.0721d),
                new RegionPreset("秘鲁", "Peru", "PE", "71610", "Claro PE", "es-PE", "America/Lima", -12.0464d, -77.0428d),
                new RegionPreset("南非", "South Africa", "ZA", "65501", "Vodacom", "en-ZA", "Africa/Johannesburg", -26.2041d, 28.0473d),
                new RegionPreset("埃及", "Egypt", "EG", "60202", "Vodafone EG", "ar-EG", "Africa/Cairo", 30.0444d, 31.2357d),
                new RegionPreset("尼日利亚", "Nigeria", "NG", "62120", "MTN NG", "en-NG", "Africa/Lagos", 6.5244d, 3.3792d),
                new RegionPreset("肯尼亚", "Kenya", "KE", "63902", "Safaricom", "en-KE", "Africa/Nairobi", -1.2921d, 36.8219d),
                new RegionPreset("摩洛哥", "Morocco", "MA", "60401", "Maroc Telecom", "ar-MA", "Africa/Casablanca", 33.5731d, -7.5898d)));
        presets.sort(Comparator.comparing(value -> value.englishName, String.CASE_INSENSITIVE_ORDER));
        PRESETS = Collections.unmodifiableList(presets);
        Map<String, RegionPreset> byCode = new LinkedHashMap<>();
        for (RegionPreset preset : PRESETS) byCode.put(preset.code, preset);
        BY_CODE = Collections.unmodifiableMap(byCode);
    }

    public static List<RegionPreset> all() { return PRESETS; }

    /** Unknown two-letter regions remain available through custom settings. */
    public static RegionPreset find(String code) {
        return code == null ? null : BY_CODE.get(code.trim().toUpperCase(Locale.ROOT));
    }

    public static List<RegionPreset> search(String query) {
        String term = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        if (term.isEmpty()) return PRESETS;
        List<RegionPreset> result = new ArrayList<>();
        for (RegionPreset preset : PRESETS) {
            if (preset.chineseName.toLowerCase(Locale.ROOT).contains(term)
                    || preset.englishName.toLowerCase(Locale.ROOT).contains(term)
                    || preset.code.toLowerCase(Locale.ROOT).contains(term)) result.add(preset);
        }
        return Collections.unmodifiableList(result);
    }
}
