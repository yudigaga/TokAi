package com.tokai.module.feature;

import android.content.ContentValues;
import com.tokai.module.scan.HostCandidate;
import com.tokai.module.scan.HostScanReport;
import com.tokai.module.scan.RuleScanResult;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public final class TikTokIncomingTemplatesTest {
    private static final String IMAGE = "https://p16-sign-va.tiktokcdn.com/image.jpg";
    private IncomingMessageSnapshot snapshot;
    @Before public void setUp() throws Exception {
        Decoder.calls = 0;
        snapshot = new IncomingMessageSnapshot(Message.class, report(), getClass().getClassLoader());
    }

    @Test public void nativeLiteralTextNormalizesOnlyPolicyInputWhileKeepingReceiptIdentity() throws Exception {
        Bubble bubble = new Bubble(); bubble.textComponent = new Text(); bubble.textComponent.text = "received literal";
        Message nativeMessage = new Message(1810, bubble);
        ContentValues copy = snapshot.copy(nativeMessage);
        assertEquals(Integer.valueOf(7), copy.getAsInteger("type"));
        assertEquals(Integer.valueOf(1810), copy.getAsInteger("tokai_native_template_type"));
        assertEquals("received literal", new JSONObject(copy.getAsString("content")).getString("text"));
        assertEquals("conversation", copy.getAsString("conversation_id"));
        assertEquals("12345", copy.getAsString("msg_server_id"));
        assertEquals(Long.valueOf(200), copy.getAsLong("sender"));
        assertEquals(1810, nativeMessage.type);
        assertEquals("{}", nativeMessage.getContent());
        assertSame(bubble, nativeMessage.pb.value);
    }

    @Test public void nativeUnencryptedPictureMapsToTheExistingImagePolicyShape() throws Exception {
        Picture picture = new Picture(); picture.imageComponent = new Image();
        picture.imageComponent.urls = Arrays.asList("https://untrusted.invalid/image.jpg", IMAGE);
        ContentValues copy = snapshot.copy(new Message(1802, picture));
        assertEquals(Integer.valueOf(27), copy.getAsInteger("type"));
        List<String> urls = AutomationReplyMedia.incomingImages(new JSONObject(copy.getAsString("content")));
        assertEquals(Collections.singletonList(IMAGE), urls);
    }

    @Test public void encryptedPictureAndThumbnailCannotBecomeAnUnprovedImageInput() throws Exception {
        Picture picture = new Picture(); picture.imageComponent = new Image();
        picture.imageComponent.decryptKey = "native-key"; picture.imageComponent.urls = Collections.singletonList(IMAGE);
        picture.thumbnailComponent = new Image(); picture.thumbnailComponent.urls = Collections.singletonList(IMAGE);
        ContentValues encrypted = snapshot.copy(new Message(1802, picture));
        assertEquals(Integer.valueOf(1802), encrypted.getAsInteger("type"));
        assertEquals("{}", encrypted.getAsString("content"));
        picture.imageComponent.decryptKey = ""; picture.imageComponent.urls = Collections.emptyList();
        ContentValues thumbnailOnly = snapshot.copy(new Message(1802, picture));
        assertEquals(Integer.valueOf(1802), thumbnailOnly.getAsInteger("type"));
    }

    @Test public void localizedFallbackAndWrongNativeTemplateRemainUnsupported() throws Exception {
        Bubble bubble = new Bubble(); bubble.textComponent = new Text(); bubble.textComponent.text = "fallback text";
        bubble.textComponent.receiverStarlingKey = "system_key";
        assertEquals(Integer.valueOf(1810), snapshot.copy(new Message(1810, bubble)).getAsInteger("type"));
        assertEquals(Integer.valueOf(1802), snapshot.copy(new Message(1802, bubble)).getAsInteger("type"));
        assertEquals(Integer.valueOf(1810), snapshot.copy(new Message(1810, new Object())).getAsInteger("type"));
    }

    @Test public void legacyTextAndUnadaptedVoiceNeverEnterTemplateDecoder() throws Exception {
        Message legacy = new Message(7, new Object());
        legacy.body = "{\"text\":\"legacy literal\"}";
        ContentValues original = snapshot.copy(legacy);
        assertEquals(Integer.valueOf(7), original.getAsInteger("type"));
        assertEquals(legacy.body, original.getAsString("content"));
        assertEquals(Integer.valueOf(1813), snapshot.copy(new Message(1813, new Bubble())).getAsInteger("type"));
        assertEquals(0, Decoder.calls);
    }

    private static HostScanReport report() throws Exception {
        List<RuleScanResult> rows = new ArrayList<>(); Method decode = Decoder.class.getMethod("decode", Bytes.class, int.class);
        rows.add(new RuleScanResult("tiktok_im_template_decode", "message", Collections.singletonList(new HostCandidate(
                "fixture.apk", "classes.dex", HostTargets.descriptor(Decoder.class), decode.getName(), HostTargets.descriptor(decode)))));
        for (Object[] pair : new Object[][]{{"tiktok_im_text_template", Bubble.class}, {"tiktok_im_picture_template", Picture.class},
                {"tiktok_im_text_component", Text.class}, {"tiktok_im_image_component", Image.class}}) {
            rows.add(new RuleScanResult((String) pair[0], "message", Collections.singletonList(new HostCandidate(
                    "fixture.apk", "classes.dex", HostTargets.descriptor((Class<?>) pair[1]), null, null))));
        }
        return new HostScanReport("fixture", rows, false);
    }
    public static final class Bytes { final Object value; Bytes(Object value) { this.value = value; } }
    public static final class Decoder {
        static int calls;
        public static Object decode(Bytes bytes, int type) { calls++; return bytes.value; }
    }
    public static final class Text { public String text, senderStarlingKey, receiverStarlingKey; }
    public static final class Image { public String decryptKey; public List<String> urls; }
    public static final class Bubble { public Text textComponent; }
    public static final class Picture { public Image imageComponent, thumbnailComponent; }
    public static final class Message {
        int type; final Bytes pb; String body = "{}";
        Message(int type, Object value) { this.type = type; this.pb = new Bytes(value); }
        public long getSender() { return 200; }
        public long getMsgId() { return 12345; }
        public long getCreatedAt() { return 1_800_000_000_000L; }
        public String getContent() { return body; }
        public int getMsgType() { return type; }
        public String getConversationId() { return "conversation"; }
        public int getConversationType() { return 1; }
        public Map<String, String> getExt() { return Collections.emptyMap(); }
        public int getDeleted() { return 0; }
        public Bytes getContentPB() { return pb; }
    }
}
