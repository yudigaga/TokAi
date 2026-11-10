package com.tokai.module.feature;

import android.content.ContentValues;
import com.tokai.module.config.LocalOptions;
import java.util.HashMap;
import java.util.Map;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public final class TikTokRecallPolicyTest {
    @Test public void numericRecallMarkerUsesTheOriginalActorAndSelfOtherPreferences() {
        Map<String,String> ext = new HashMap<>(); ext.put("s:is_recalled", "1"); ext.put("s:recall_uid", "200");
        assertTrue(TikTokRecallPolicy.protects(ext, 100, options(false, true)));
        ext.put("s:recall_uid", "100");
        assertFalse(TikTokRecallPolicy.protects(ext, 100, options(false, true)));
        ext.remove("s:recall_uid");
        assertFalse("Unknown actor must retain native behavior", TikTokRecallPolicy.protects(ext, 100, options(true, true)));
    }

    @Test public void remoteRecallPatchKeepsUnrelatedVersionAndVisibilityMetadata() {
        Map<String,String> ext = new HashMap<>(); ext.put("s:is_recalled", "1"); ext.put("s:recall_uid", "200");
        ext.put("s:visible", "100"); ext.put("server_version", "88");
        Map<Object,Object> filtered = TikTokRecallPolicy.clear(ext);
        assertEquals("0", filtered.get("s:is_recalled"));
        assertEquals("100", filtered.get("s:visible"));
        assertEquals("88", filtered.get("server_version"));
        assertFalse(filtered.containsKey("s:recall_uid"));
        assertEquals("1", ext.get("s:is_recalled"));
        assertEquals("200", ext.get("s:recall_uid"));
    }

    @Test public void databasePatchPreservesStoredContentAndUnrelatedUpdatesPassThrough() throws Exception {
        ContentValues update = new ContentValues();
        update.put("ext", "{\"s:is_recalled\":\"1\",\"s:recall_uid\":\"200\",\"other\":\"kept\"}");
        update.put("content", "recall replacement"); update.put("type", 99); update.put("version", 88L);
        ContentValues protectedUpdate = TikTokRecallPolicy.protect(update, 100, options(true, true));
        assertNotSame(update, protectedUpdate);
        assertFalse(protectedUpdate.containsKey("content"));
        assertFalse(protectedUpdate.containsKey("type"));
        assertEquals(Long.valueOf(88), protectedUpdate.getAsLong("version"));
        assertEquals("kept", new JSONObject(protectedUpdate.getAsString("ext")).getString("other"));
        assertTrue(update.containsKey("content"));
        ContentValues ordinary = new ContentValues(); ordinary.put("ext", "{\"other\":\"kept\"}");
        assertSame(ordinary, TikTokRecallPolicy.protect(ordinary, 100, options(true, true)));
    }

    private static LocalOptions options(boolean self, boolean other) {
        Map<String,Object> values = new HashMap<>(); values.put("prevent_message_recall", true);
        values.put("recall_self", self); values.put("recall_other", other); return new LocalOptions(values);
    }
}
