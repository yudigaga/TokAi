package com.tokai.module.ui;

import android.content.Context;
import android.widget.ImageView;
import com.tokai.module.scan.HostAdaptation;
import com.tokai.module.scan.HostResourceProfile;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** Reads the signed-in user's avatar model; the host image loader owns caching and loading. */
final class HostAccountAvatar {
    private final Object avatar;
    private final Method load;
    private android.net.Uri imageUri;

    private HostAccountAvatar(Object avatar, Method load) { this.avatar = avatar; this.load = load; }

    static HostAccountAvatar read(Context context) {
        try {
            HostAdaptation.Profile profile = HostNativeChatViews.profileFor(context);
            ClassLoader loader = context.getClassLoader();
            Object user = com.tokai.module.feature.HostAccountAccess.currentUser(loader);
            if (user == null) return null;
            Field thumb = user.getClass().getField("avatarThumb");
            Object avatar = thumb.get(user);
            if (avatar == null) return null;
            if (profile == HostAdaptation.PROFILE_TIKTOK) {
                Object urls = avatar.getClass().getMethod("getUrlList").invoke(avatar);
                if (!(urls instanceof java.util.List<?>) || ((java.util.List<?>) urls).isEmpty()) return null;
                Object first = ((java.util.List<?>) urls).get(0);
                if (!(first instanceof String)) return null;
                android.net.Uri uri = android.net.Uri.parse((String) first);
                if (!"https".equalsIgnoreCase(uri.getScheme()) && !"http".equalsIgnoreCase(uri.getScheme())) return null;
                HostAccountAvatar image = new HostAccountAvatar(avatar, null);
                image.imageUri = uri;
                return image;
            }
            Method load = Class.forName("com.ss.android.ugc.aweme.im.sdk.common.ImFrescoHelper", false, loader)
                    .getMethod(HostResourceProfile.avatarLoader(profile), Class.forName("com.ss.android.ugc.aweme.base.ui.AvatarImageView", false, loader),
                            thumb.getType(), boolean.class, int.class, String.class, int.class);
            return new HostAccountAvatar(avatar, load);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) { return null; }
    }

    boolean bind(ImageView view) {
        try {
            if (imageUri != null) view.getClass().getMethod("setImageURI", android.net.Uri.class).invoke(view, imageUri);
            else load.invoke(null, view, avatar, false, 0, "TokAi.NativeChat", 0);
            return true;
        }
        catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) { return false; }
    }
}
