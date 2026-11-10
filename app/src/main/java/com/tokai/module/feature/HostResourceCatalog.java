package com.tokai.module.feature;

import android.annotation.SuppressLint;
import android.content.res.Resources;
import android.view.View;

import com.tokai.module.scan.HostAdaptation;
import com.tokai.module.scan.HostResourceProfile;
import com.tokai.module.scan.TikTokFeedResources;

/** Resource entry names and IDs are bound to exactly one supported host table. */
final class HostResourceCatalog {
    static final String VERIFIED_PACKAGE = HostAdaptation.TARGET_PACKAGE;
    static final String VERIFIED_VERSION_NAME = HostAdaptation.VERIFIED_VERSION_NAME;
    private static final int[] PROFILE_SENTINELS = {2131374347, 2131371414, 2131415727};
    private static final int CHAPTER_MOUNT = 0x7f0a3fd6;

    private HostResourceCatalog() { }

    static HostAdaptation.Profile profile(Resources resources) {
        if (resources == null) return null;
        return identifies(resources, HostAdaptation.PROFILE_TIKTOK) ? HostAdaptation.PROFILE_TIKTOK : null;
    }

    private static boolean identifies(Resources resources, HostAdaptation.Profile profile) {
        for (int canonical : PROFILE_SENTINELS) {
            int actual = TikTokFeedResources.id(canonical);
            try {
                if (!VERIFIED_PACKAGE.equals(resources.getResourcePackageName(actual))
                        || !expectedName(profile, canonical).equals(resources.getResourceEntryName(actual))) return false;
            } catch (Resources.NotFoundException ignored) { return false; }
        }
        return true;
    }

    static int runtimeId(int canonicalId, Resources resources) {
        HostAdaptation.Profile profile = profile(resources);
        return profile == null || expectedName(profile, canonicalId) == null
                ? View.NO_ID : TikTokFeedResources.id(canonicalId);
    }

    static int canonicalId(int actualId, Resources resources) {
        HostAdaptation.Profile profile = profile(resources);
        if (profile == null) return View.NO_ID;
        int canonical = TikTokFeedResources.canonicalId(actualId);
        if (TikTokFeedResources.id(canonical) != actualId) return View.NO_ID;
        return matches(canonical, resources, profile) ? canonical : View.NO_ID;
    }

    static boolean matches(int canonicalId, Resources resources) {
        HostAdaptation.Profile profile = profile(resources);
        return profile != null && matches(canonicalId, resources, profile);
    }

    private static boolean matches(int canonicalId, Resources resources, HostAdaptation.Profile profile) {
        String expected = expectedName(profile, canonicalId);
        if (expected == null) return false;
        int actual = TikTokFeedResources.id(canonicalId);
        try {
            return VERIFIED_PACKAGE.equals(resources.getResourcePackageName(actual))
                    && expected.equals(resources.getResourceEntryName(actual));
        } catch (Resources.NotFoundException ignored) { return false; }
    }

    static boolean matches(int id, String resourceName) {
        return resourceName != null && resourceName.equals(expectedName(id));
    }

    static boolean matches(int id, String packageName, String resourceName) {
        return VERIFIED_PACKAGE.equals(packageName) && matches(id, resourceName);
    }

    static String expectedName(int canonicalId) { return TikTokFeedResources.name(canonicalId); }

    private static String expectedName(HostAdaptation.Profile profile, int canonicalId) {
        return TikTokFeedResources.name(canonicalId);
    }

    @SuppressLint("ResourceType")
    static View findVerifiedView(View root, int canonicalId) {
        if (root == null) return null;
        int actual = runtimeId(canonicalId, root.getResources());
        if (actual == View.NO_ID) return null;
        View view = root.findViewById(actual);
        return view != null && canonicalId(view.getId(), view.getResources()) == canonicalId ? view : null;
    }
}
