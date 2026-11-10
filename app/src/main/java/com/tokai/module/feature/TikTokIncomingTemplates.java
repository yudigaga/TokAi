package com.tokai.module.feature;

import android.content.ContentValues;
import com.tokai.module.scan.HostScanReport;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** Maps only native literal text and unencrypted picture templates to the existing reply policy inputs. */
final class TikTokIncomingTemplates {
    private final Method contentPB;
    private final Method decode;
    private final Class<?> bubble;
    private final Class<?> picture;
    private final Field textComponent;
    private final Field imageComponent;
    private final Field literalText;
    private final Field senderKey;
    private final Field receiverKey;
    private final Field urls;
    private final Field decryptKey;

    TikTokIncomingTemplates(Class<?> message, HostScanReport report, ClassLoader loader) throws Exception {
        this(message.getMethod("getContentPB"), HostTargets.method(report, "tiktok_im_template_decode", loader),
                HostTargets.type(report, "tiktok_im_text_template", loader),
                HostTargets.type(report, "tiktok_im_picture_template", loader),
                HostTargets.type(report, "tiktok_im_text_component", loader),
                HostTargets.type(report, "tiktok_im_image_component", loader));
    }

    TikTokIncomingTemplates(Method contentPB, Method decode, Class<?> bubble, Class<?> picture,
                             Class<?> text, Class<?> image) throws ReflectiveOperationException {
        this.contentPB = contentPB;
        this.decode = decode;
        this.bubble = bubble;
        this.picture = picture;
        if (!Modifier.isStatic(decode.getModifiers()) || decode.getParameterCount() != 2
                || decode.getParameterTypes()[0] != contentPB.getReturnType()
                || decode.getParameterTypes()[1] != int.class) {
            throw new IllegalStateException("TikTok native template decoder changed");
        }
        textComponent = HostTargets.field(bubble, "textComponent");
        imageComponent = HostTargets.field(picture, "imageComponent");
        literalText = HostTargets.field(text, "text");
        senderKey = HostTargets.field(text, "senderStarlingKey");
        receiverKey = HostTargets.field(text, "receiverStarlingKey");
        urls = HostTargets.field(image, "urls");
        decryptKey = HostTargets.field(image, "decryptKey");
        if (textComponent.getType() != text || imageComponent.getType() != image
                || literalText.getType() != String.class || decryptKey.getType() != String.class
                || senderKey.getType() != String.class || receiverKey.getType() != String.class
                || !List.class.isAssignableFrom(urls.getType())) {
            throw new IllegalStateException("TikTok native template component relation changed");
        }
    }

    void copy(Object message, ContentValues values) {
        Integer nativeType = values.getAsInteger("type");
        if (nativeType == null || nativeType != 1810 && nativeType != 1802) return;
        try {
            Object bytes = contentPB.invoke(message);
            if (bytes == null) return;
            Object template = decode.invoke(null, bytes, nativeType.intValue());
            JSONObject normalized = new JSONObject();
            int policyType;
            if (nativeType == 1810 && bubble.isInstance(template)) {
                Object text = textComponent.get(template);
                if (text == null || nonEmpty(senderKey.get(text)) || nonEmpty(receiverKey.get(text))) return;
                Object literal = literalText.get(text);
                if (!(literal instanceof String) || ((String) literal).trim().isEmpty()) return;
                normalized.put("text", literal);
                policyType = 7;
            } else if (nativeType == 1802 && picture.isInstance(template)) {
                Object image = imageComponent.get(template);
                if (image == null || nonEmpty(decryptKey.get(image))) return;
                Object candidates = urls.get(image);
                if (!(candidates instanceof List<?>)) return;
                String selected = null;
                for (Object url : (List<?>) candidates) {
                    if (url instanceof String && AutomationImages.isMediaUrl((String) url)) { selected = (String) url; break; }
                }
                if (selected == null) return;
                normalized.put("resource_url", new JSONObject().put("url_list", new JSONArray().put(selected)));
                policyType = 27;
            } else return;
            values.put("tokai_native_template_type", nativeType);
            values.put("type", policyType);
            values.put("content", normalized.toString());
        } catch (ReflectiveOperationException | RuntimeException | org.json.JSONException unavailable) {
            // Unknown/malformed/native encrypted content retains its original unsupported policy type.
        }
    }

    private static boolean nonEmpty(Object value) { return value instanceof String && !((String) value).trim().isEmpty(); }
}
