package com.tokai.module.ui;

import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import androidx.activity.result.ActivityResultRegistry;
import androidx.activity.result.contract.ActivityResultContract;
import androidx.core.app.ActivityOptionsCompat;
import com.tokai.module.scan.HostAdaptation;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35, packageName = "com.zhiliaoapp.musically")
public final class TikTokMediaPickerTest {
    private PickerActivity activity;
    private HostMediaPicker picker;
    @Before public void before() {
        HostAdaptation.setCurrentProfile(HostAdaptation.PROFILE_TIKTOK);
        activity = Robolectric.buildActivity(PickerActivity.class).setup().get();
    }
    @After public void after() {
        if (picker != null) picker.close();
        activity.finish();
        HostAdaptation.setCurrentProfile(null);
    }

    @Test public void keepsFourUniqueLocalVideoSelectionsAndNeverCreatesAPublishingIntent() {
        AtomicReference<List<HostMediaPicker.Selection>> result = new AtomicReference<>();
        picker = HostMediaPicker.launch(activity, true, result::set);
        assertNotNull(picker);
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, activity.registry.intent.getAction());
        assertEquals("video/*", activity.registry.intent.getType());
        assertTrue(activity.registry.intent.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false));
        assertNull(activity.registry.intent.getComponent());
        assertNull(activity.registry.intent.getSerializableExtra("extra_choose_parameters"));
        Uri first = Uri.parse("content://media/video/1");
        ClipData clips = ClipData.newRawUri("videos", first);
        clips.addItem(new ClipData.Item(first));
        clips.addItem(new ClipData.Item(Uri.parse("https://example.invalid/video")));
        for (int i = 2; i <= 6; i++) clips.addItem(new ClipData.Item(Uri.parse("content://media/video/" + i)));
        Intent selected = new Intent(); selected.setClipData(clips);
        activity.registry.dispatchResult(activity.registry.code, Activity.RESULT_OK, selected);
        assertEquals(4, result.get().size());
        assertEquals(first, result.get().get(0).uri);
        for (HostMediaPicker.Selection media : result.get()) {
            assertTrue(media.video);
            assertEquals("content", media.uri.getScheme());
        }
        assertEquals(1, activity.registry.launches);
        assertFalse(HostMediaPicker.isPending(activity));
    }

    @Test public void cancellationDeliversOnceAndAClosedPickerDoesNotConsumeLateResults() {
        AtomicInteger calls = new AtomicInteger();
        picker = HostMediaPicker.launch(activity, false, selected -> { assertNull(selected); calls.incrementAndGet(); });
        assertNotNull(picker);
        activity.registry.dispatchResult(activity.registry.code, Activity.RESULT_CANCELED, null);
        assertEquals(1, calls.get());
        picker.close();
        activity.registry.dispatchResult(activity.registry.code, Activity.RESULT_OK,
                new Intent().setData(Uri.parse("content://media/images/1")));
        assertEquals(1, calls.get());
        assertFalse(HostMediaPicker.isPending(activity));
    }

    public static final class PickerActivity extends Activity {
        final Registry registry = new Registry();
        @Override public String getPackageName() { return "com.zhiliaoapp.musically"; }
        public ActivityResultRegistry getActivityResultRegistry() { return registry; }
    }
    static final class Registry extends ActivityResultRegistry {
        int code, launches;
        Intent intent;
        @Override public <I,O> void onLaunch(int code, ActivityResultContract<I,O> contract, I input,
                                            ActivityOptionsCompat options) {
            this.code = code; launches++;
            intent = contract.createIntent(RuntimeEnvironment.getApplication(), input);
        }
    }
}
