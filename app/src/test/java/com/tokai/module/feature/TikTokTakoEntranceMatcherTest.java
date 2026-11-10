package com.tokai.module.feature;

import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;
import androidx.constraintlayout.widget.ConstraintLayout;
import com.bytedance.lighten.loader.SmartImageView;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public final class TikTokTakoEntranceMatcherTest {
    @Test public void matchesTheFixedEntranceRendererButNeverItsPlaceholderOrArbitraryIcons() {
        android.content.Context context = nativeResources("com.zhiliaoapp.musically", false);
        FrameLayout fixed = new FrameLayout(context);
        fixed.setId(2131395576); // Native fixed ViewStub assigns ylo after inflation.
        com.bytedance.tux.icon.TuxIconView icon = new com.bytedance.tux.icon.TuxIconView(context);
        icon.setId(2131379170);
        fixed.addView(icon);
        assertTrue(TikTokTakoEntranceMatcher.matches(fixed, true));
        fixed.setId(2131395575); // The in-cell variant assigns yln to the same fvn layout.
        assertTrue(TikTokTakoEntranceMatcher.matches(fixed, true));
        fixed.setId(View.NO_ID);
        assertFalse(TikTokTakoEntranceMatcher.matches(fixed, true));
        fixed.setId(2131395576);
        assertFalse(TikTokTakoEntranceMatcher.matches(icon, true));
        FrameLayout media = new FrameLayout(context);
        media.addView(fixed);
        assertFalse(TikTokTakoEntranceMatcher.matches(media, true));
        fixed.addView(new View(context));
        assertFalse("The verified one-icon shell cannot match an entire video or chat container",
                TikTokTakoEntranceMatcher.matches(fixed, true));
        fixed.removeAllViews();
        android.widget.ImageView unrelated = new android.widget.ImageView(context);
        unrelated.setId(2131379170); fixed.addView(unrelated);
        assertFalse(TikTokTakoEntranceMatcher.matches(fixed, true));
        FrameLayout foreign = new FrameLayout(nativeResources("other.package", false));
        foreign.setId(2131395576);
        com.bytedance.tux.icon.TuxIconView foreignIcon = new com.bytedance.tux.icon.TuxIconView(foreign.getContext());
        foreignIcon.setId(2131379170); foreign.addView(foreignIcon);
        assertFalse(TikTokTakoEntranceMatcher.matches(foreign, true));
    }

    @Test public void matchesOnlyTheCompleteNativeNormalAndBubbleEntranceRoots() {
        FrameLayout normal = normal();
        assertTrue(TikTokTakoEntranceMatcher.matches(normal, false));
        assertTrue(TikTokTakoEntranceMatcher.matches(bubble(), false));
        assertFalse(TikTokTakoEntranceMatcher.matches(normal.getChildAt(0), false));
        FrameLayout player = new FrameLayout(normal.getContext());
        player.addView(normal);
        assertFalse(TikTokTakoEntranceMatcher.matches(player, false));
    }

    @Test public void resourceCollisionTextAndIncompleteShapesCannotHideOtherSurfaces() {
        FrameLayout normal = normal(nativeResources("com.example.other", false));
        assertFalse(TikTokTakoEntranceMatcher.matches(normal, true));
        normal.removeAllViews();
        TextView label = new TextView(normal.getContext());
        label.setText("TikTok Tako");
        label.setTag("SHOP_MALL");
        label.setId(2131396564);
        normal.addView(label);
        assertFalse(TikTokTakoEntranceMatcher.matches(normal, false));
        assertFalse(TikTokTakoEntranceMatcher.matches(label, false));
        ConstraintLayout chat = bubble();
        chat.setId(View.NO_ID);
        assertFalse(TikTokTakoEntranceMatcher.matches(chat, false));
    }

    @Test public void refusesDuplicateIconIdentityAndOversizedEntranceContainers() {
        FrameLayout normal = normal();
        SmartImageView duplicate = new SmartImageView(normal.getContext());
        duplicate.setId(2131396564);
        normal.addView(duplicate);
        assertFalse(TikTokTakoEntranceMatcher.matches(normal, false));
        normal = normal();
        for (int i = 0; i < 20; i++) normal.addView(new View(normal.getContext()));
        assertFalse(TikTokTakoEntranceMatcher.matches(normal, false));
    }

    @Test public void verifiesTheNativePackageAndEveryOwnedResourceEntry() {
        android.content.Context nativeContext = nativeResources("com.zhiliaoapp.musically", false);
        assertTrue(TikTokTakoEntranceMatcher.matches(normal(nativeContext), true));
        assertTrue(TikTokTakoEntranceMatcher.matches(bubble(nativeContext), true));
        assertFalse(TikTokTakoEntranceMatcher.matches(normal(nativeResources("other.package", false)), true));
        assertFalse(TikTokTakoEntranceMatcher.matches(normal(nativeResources("com.zhiliaoapp.musically", true)), true));
        FrameLayout mixed = normal(nativeContext);
        mixed.removeAllViews();
        SmartImageView foreign = new SmartImageView(nativeResources("com.example.other", false));
        foreign.setId(2131396564);
        mixed.addView(foreign);
        assertFalse(TikTokTakoEntranceMatcher.matches(mixed, true));
    }

    static FrameLayout normal() { return normal(RuntimeEnvironment.getApplication()); }

    private static FrameLayout normal(android.content.Context context) {
        FrameLayout root = new FrameLayout(context);
        root.setId(2131396568); // ba5: zbh
        SmartImageView image = new SmartImageView(root.getContext());
        image.setId(2131396564); // zbd
        root.addView(image);
        return root;
    }

    static ConstraintLayout bubble() { return bubble(RuntimeEnvironment.getApplication()); }

    private static ConstraintLayout bubble(android.content.Context context) {
        ConstraintLayout root = new ConstraintLayout(context);
        root.setId(2131396573); // ba6: zbm
        FrameLayout body = normal(context);
        body.setId(2131396574); // zbn
        root.addView(body);
        View board = new View(root.getContext());
        board.setId(2131396572); // zbl
        root.addView(board);
        return root;
    }

    private static android.content.Context nativeResources(String packageName, boolean wrongIconName) {
        android.content.Context app = RuntimeEnvironment.getApplication();
        android.content.res.Resources resources = new android.content.res.Resources(app.getAssets(),
                app.getResources().getDisplayMetrics(), app.getResources().getConfiguration()) {
            @Override public String getResourcePackageName(int id) {
                return owned(id) ? packageName : super.getResourcePackageName(id);
            }
            @Override public String getResourceEntryName(int id) {
                if (id == 2131379170) return "mm8";
                if (id == 2131395575) return "yln";
                if (id == 2131395576) return "ylo";
                switch (id) {
                    case 2131396564: return wrongIconName ? "other_icon" : "zbd";
                    case 2131396568: return "zbh";
                    case 2131396572: return "zbl";
                    case 2131396573: return "zbm";
                    case 2131396574: return "zbn";
                    default: return super.getResourceEntryName(id);
                }
            }
            private boolean owned(int id) {
                return id == 2131379170 || id == 2131395575 || id == 2131395576
                        || id == 2131396564 || id == 2131396568 || id == 2131396572
                        || id == 2131396573 || id == 2131396574;
            }
        };
        return new android.content.ContextWrapper(app) {
            @Override public android.content.res.Resources getResources() { return resources; }
        };
    }
}
