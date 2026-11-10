package com.tokai.module.feature;

import android.content.ContentValues;
import com.tokai.module.config.LocalOptions;
import java.util.LinkedHashMap;
import java.util.Map;
import org.json.JSONObject;

/** TikTok uses numeric s:is_recalled=1 and textual s:recalled=true. */
final class TikTokRecallPolicy {
    private TikTokRecallPolicy() { }
    static boolean protects(Map<?, ?> extension, long account, LocalOptions options) {
        if (extension == null) return false;
        try {
            JSONObject values = new JSONObject(extension);
            if ("1".equals(values.optString("s:is_recalled"))) values.put("s:is_recalled", "true");
            return MessageRecallPolicy.shouldProtect(values, account, options);
        } catch (org.json.JSONException invalid) { return false; }
    }
    static Map<Object, Object> clear(Map<?, ?> source) {
        Map<Object, Object> values = new LinkedHashMap<>(); values.putAll(source);
        values.put("s:is_recalled", "0"); values.put("s:recalled", "false");
        values.remove("s:recall_uid"); values.remove("s:recall_role"); values.remove("s:ignore_visible");
        return values;
    }
    static ContentValues protect(ContentValues original, long account, LocalOptions options) {
        try {
            String raw = original.getAsString("ext");
            if (raw == null) return original;
            JSONObject values = new JSONObject(raw);
            if ("1".equals(values.optString("s:is_recalled"))) values.put("s:is_recalled", "true");
            if (!MessageRecallPolicy.shouldProtect(values, account, options)) return original;
            ContentValues normalized = new ContentValues(original);
            normalized.put("ext", values.toString());
            return MessageRecallPolicy.protect(normalized, account, options);
        } catch (Exception invalid) { return original; }
    }
}
