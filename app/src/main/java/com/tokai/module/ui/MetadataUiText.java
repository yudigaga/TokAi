package com.tokai.module.ui;
import com.tokai.module.R;
import com.tokai.module.i18n.UiText;
import com.tokai.module.i18n.ModuleLocale;
import com.tokai.module.feature.VoiceCatalog;
import com.tokai.module.feature.VoiceCatalogLabels;
import com.tokai.module.feature.VoiceNameTranslationCache;
import com.tokai.module.feature.NativeFollowLists;
import com.tokai.module.feature.FollowListSnapshot;
import com.tokai.module.feature.DownloadTasks;
import com.tokai.module.analytics.AnalyticsBackup;
import java.text.SimpleDateFormat;
import java.text.Normalizer;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Module UI projection of structured facts; raw contracts and user/provider content stay intact. */
public final class MetadataUiText {
    private MetadataUiText() { }
    public static List<String> displayVoiceLabels(VoiceCatalog.Entry entry) {
        if (entry == null) return Collections.emptyList();
        List<String> result = new ArrayList<>(entry.labels);
        for (VoiceCatalogLabels.CodeLabel item : entry.displayMetadata) {
            if (item == null || item.index < 0 || item.index >= result.size() || item.field == null || item.code == null) continue;
            int field = fieldResource(item.field), value = valueResource(item.field, item.code);
            if (field != 0 && value != 0) result.set(item.index,
                UiText.get(R.string.metadata_ui_voice_label, UiText.get(field), UiText.get(value)));
        }
        return Collections.unmodifiableList(result);
    }
    private static int fieldResource(String field) {
        switch (field) {
            case "language": return R.string.metadata_ui_field_language;
            case "gender": return R.string.metadata_ui_field_gender;
            case "age": return R.string.metadata_ui_field_age;
            case "tone": return R.string.metadata_ui_field_tone;
            case "accent": return R.string.metadata_ui_field_accent;
            default: return 0;
        }
    }
    private static int valueResource(String field, String code) {
        switch (field) {
            case "language": switch (code) {
                case "zh": return R.string.speech_language_chinese;
                case "en": return R.string.speech_language_english;
                case "ja": return R.string.speech_language_japanese;
                case "ko": return R.string.speech_language_korean;
                case "fr": return R.string.speech_language_french;
                case "de": return R.string.speech_language_german;
                case "es": return R.string.speech_language_spanish;
                case "ru": return R.string.speech_language_russian;
                case "pt": return R.string.speech_language_portuguese;
                case "ar": return R.string.speech_language_arabic;
                case "it": return R.string.speech_language_italian;
                case "th": return R.string.speech_language_thai;
                case "vi": return R.string.speech_language_vietnamese;
                case "id": return R.string.speech_language_indonesian;
                case "hi": return R.string.speech_language_hindi;
                case "af": return R.string.speech_language_afrikaans;
                case "bg": return R.string.speech_language_bulgarian;
                case "bn": return R.string.speech_language_bengali;
                case "bs": return R.string.speech_language_bosnian;
                case "ca": return R.string.speech_language_catalan;
                case "cs": return R.string.speech_language_czech;
                case "da": return R.string.speech_language_danish;
                case "el": return R.string.speech_language_greek;
                case "et": return R.string.speech_language_estonian;
                case "eu": return R.string.speech_language_basque;
                case "fa": return R.string.speech_language_persian;
                case "fi": return R.string.speech_language_finnish;
                case "gl": return R.string.speech_language_galician;
                case "he": return R.string.speech_language_hebrew;
                case "hu": return R.string.speech_language_hungarian;
                case "is": return R.string.speech_language_icelandic;
                case "kn": return R.string.speech_language_kannada;
                case "lt": return R.string.speech_language_lithuanian;
                case "mr": return R.string.speech_language_marathi;
                case "ms": return R.string.speech_language_malay;
                case "ne": return R.string.speech_language_nepali;
                case "nl": return R.string.speech_language_dutch;
                case "no": return R.string.speech_language_norwegian;
                case "pl": return R.string.speech_language_polish;
                case "ro": return R.string.speech_language_romanian;
                case "sk": return R.string.speech_language_slovak;
                case "sl": return R.string.speech_language_slovenian;
                case "sq": return R.string.speech_language_albanian;
                case "sv": return R.string.speech_language_swedish;
                case "sw": return R.string.speech_language_swahili;
                case "ta": return R.string.speech_language_tamil;
                case "te": return R.string.speech_language_telugu;
                case "tl": return R.string.metadata_ui_language_tl;
                case "tr": return R.string.speech_language_turkish;
                case "uk": return R.string.speech_language_ukrainian;
                case "ur": return R.string.speech_language_urdu;
                case "zh-cn": return R.string.metadata_ui_lang_zh_cn;
                case "zh-tw": return R.string.metadata_ui_lang_zh_tw;
                case "zh-hk": return R.string.metadata_ui_lang_zh_hk;
                case "zh-hans": return R.string.metadata_ui_lang_zh_hans;
                case "zh-hant": return R.string.metadata_ui_lang_zh_hant;
                default: return 0;
            }
            case "gender": switch (code) {
                case "male": return R.string.speech_gender_male;
                case "female": return R.string.speech_gender_female;
                case "neutral": return R.string.speech_gender_neutral;
                default: return 0;
            }
            case "age": switch (code) {
                case "child": return R.string.speech_age_child;
                case "young": return R.string.speech_age_young;
                case "middle_aged": return R.string.speech_age_middle;
                case "old": return R.string.speech_age_senior;
                default: return 0;
            }
            case "tone": switch (code) {
                case "warm": return R.string.speech_tone_warm;
                case "calm": return R.string.speech_tone_calm;
                case "bright": return R.string.speech_tone_bright;
                case "gentle": return R.string.speech_tone_gentle;
                case "energetic": return R.string.speech_tone_energetic;
                case "authoritative": return R.string.speech_tone_authoritative;
                case "sincere": return R.string.speech_tone_sincere;
                case "weary": return R.string.speech_tone_tired;
                case "precise": return R.string.speech_tone_precise;
                case "refined": return R.string.speech_tone_elegant;
                case "urgent": return R.string.speech_tone_urgent;
                case "friendly": return R.string.speech_tone_friendly;
                case "articulate": return R.string.speech_tone_clear;
                case "steady": return R.string.speech_tone_steady;
                case "playful": return R.string.speech_tone_playful;
                case "compassionate": return R.string.speech_tone_caring;
                case "reflective": return R.string.speech_tone_thoughtful;
                case "measured": return R.string.speech_tone_composed;
                case "rhythmic": return R.string.speech_tone_rhythmic;
                case "passionate": return R.string.speech_tone_passionate;
                default: return 0;
            }
            case "accent": switch (code) {
                case "american": return R.string.speech_accent_american;
                case "british": return R.string.speech_accent_british;
                case "scottish": return R.string.speech_accent_scottish;
                case "irish": return R.string.speech_accent_irish;
                case "australian": return R.string.speech_accent_australian;
                case "canadian": return R.string.speech_accent_canadian;
                case "us_southern": return R.string.speech_accent_southern_us;
                case "us_new_york": return R.string.speech_accent_new_york;
                case "indian": return R.string.speech_accent_indian;
                case "south_african": return R.string.speech_accent_south_african;
                case "russian": return R.string.speech_accent_russian;
                case "japanese": return R.string.speech_accent_japanese;
                case "korean": return R.string.speech_accent_korean;
                case "chinese": return R.string.metadata_ui_accent_chinese;
                case "mandarin_northeastern": return R.string.speech_accent_northeast_mandarin;
                case "mandarin_shaanxi": return R.string.speech_accent_shaanxi_mandarin;
                case "mandarin_shanghai": return R.string.speech_accent_shanghai_mandarin;
                case "mandarin_sichuan": return R.string.speech_accent_sichuan_mandarin;
                case "mandarin_henan": return R.string.speech_accent_henan_mandarin;
                case "mandarin_beijing": return R.string.speech_accent_beijing_mandarin;
                case "mandarin_taiwan": return R.string.speech_accent_taiwan_mandarin;
                case "cantonese": return R.string.speech_language_cantonese;
                case "mandarin_guangdong": return R.string.speech_accent_guangdong_mandarin;
                case "mandarin_yunnan": return R.string.speech_accent_yunnan_mandarin;
                default: return 0;
            }
            default: return 0;
        }
    }
    public static boolean matchesVoice(String scope, VoiceNameTranslationCache names, VoiceCatalog.Entry entry, String query) {
        if (names == null || entry == null || query != null && query.length() > 256) return false;
        String needle = normalize(query == null ? "" : query).trim();
        if (needle.length() > 256) return false;
        if (needle.isEmpty()) return true;
        String haystack = normalize(entry.id + " " + entry.name + " " + names.alias(scope, entry)
            + " " + String.join(" ", entry.labels) + " " + String.join(" ", displayVoiceLabels(entry)));
        int start = 0;
        for (int index = 0; index <= needle.length(); index++) {
            if (index == needle.length() || Character.isWhitespace(needle.charAt(index)) || Character.isSpaceChar(needle.charAt(index))) {
                if (start < index && !haystack.contains(needle.substring(start, index))) return false;
                start = index + 1;
            }
        }
        return true;
    }
    private static String normalize(String value) { return Normalizer.normalize(value, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT); }
    public static String displayFollowStatus(NativeFollowLists.Status status) {
        if (status == null || "host_unavailable".equals(status.code)) return UiText.get(R.string.metadata_ui_follow_host_unavailable);
        if ("signed_out".equals(status.code)) return UiText.get(R.string.metadata_ui_follow_signed_out);
        List<String> lines = new ArrayList<>();
        for (NativeFollowLists.ListStatus list : status.lists) {
            String title = UiText.get(list.kind == FollowListSnapshot.Kind.FOLLOWING ? R.string.metadata_ui_following : R.string.metadata_ui_followers);
            String value = list.known ? UiText.get(R.string.metadata_ui_follow_known, title, list.count,
                new SimpleDateFormat("HH:mm", ModuleLocale.locale()).format(new Date(list.updatedAt)))
                : UiText.get(R.string.metadata_ui_follow_not_loaded, title);
            if (list.loading) value = UiText.get(R.string.metadata_ui_follow_loading, value);
            else if ("fetch_failed".equals(list.errorCode)) value = UiText.get(R.string.metadata_ui_follow_failed, value);
            else if (!list.rawError.isEmpty()) value = UiText.get(R.string.metadata_ui_raw_detail, value, list.rawError);
            lines.add(value);
        }
        return UiText.get(R.string.metadata_ui_follow_account, status.accountUid, String.join("\n", lines));
    }
    public static String displayAnalyticsSummary(AnalyticsBackup.Summary data, String rawFallback) {
        if (data == null) return rawFallback == null ? "" : rawFallback;
        String dates = data.firstDate.isEmpty() ? UiText.get(R.string.metadata_ui_backup_no_dates)
            : UiText.get(R.string.metadata_ui_date_range, date(data.firstDate), date(data.lastDate));
        return UiText.get(R.string.metadata_ui_backup_summary, dates, data.views, data.featureUses, data.visibleSegments, data.rows,
            UiText.get(data.hasContext ? R.string.metadata_ui_backup_has_context : R.string.metadata_ui_backup_no_context));
    }
    private static String date(String value) {
        try { return LocalDate.parse(value).format(DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT).withLocale(ModuleLocale.locale())); }
        catch (RuntimeException ignored) { return value; }
    }
    public static String displayPhase(DownloadTasks.Snapshot task) {
        if (task == null) return "";
        if (task.phaseCode == null) return task.phase;
        int id;
        switch (task.phaseCode) {
            case "waiting": id = R.string.metadata_ui_phase_waiting; break;
            case "preparing": id = R.string.metadata_ui_phase_preparing; break;
            case "downloading": id = R.string.metadata_ui_phase_downloading; break;
            case "saving": id = R.string.metadata_ui_phase_saving; break;
            case "encoding_live": id = R.string.metadata_ui_phase_encoding_live; break;
            case "saving_live": id = R.string.metadata_ui_phase_saving_live; break;
            case "parsing": id = R.string.metadata_ui_phase_parsing; break;
            case "completed": id = R.string.metadata_ui_phase_completed; break;
            case "cancelled": id = R.string.metadata_ui_phase_cancelled; break;
            case "timed_out": id = R.string.metadata_ui_phase_timed_out; break;
            case "failed": id = R.string.metadata_ui_phase_failed; break;
            default: return task.phase;
        }
        return UiText.get(id);
    }
}
