package com.tokai.module.ui;

import android.app.Activity;
import android.content.Intent;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public final class HostActivityResultsTest {
    @Test public void readsInlinedTikTokActivityResultFieldsWithoutGetters() throws Exception {
        Intent data = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        InlinedResult result = new InlinedResult(Activity.RESULT_OK, data);
        assertEquals(Activity.RESULT_OK, HostActivityResults.resultCode(result));
        assertSame(data, HostActivityResults.resultData(result));
    }

    @Test public void retainsTheUnobfuscatedAndroidXResultContract() throws Exception {
        Intent data = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        androidx.activity.result.ActivityResult result =
                new androidx.activity.result.ActivityResult(Activity.RESULT_CANCELED, data);
        assertEquals(Activity.RESULT_CANCELED, HostActivityResults.resultCode(result));
        assertSame(data, HostActivityResults.resultData(result));
    }

    @Test public void refusesAnIncompatibleInlinedResultField() throws Exception {
        try {
            HostActivityResults.resultCode(new WrongResult());
            fail("Wrong field type must not be treated as a result code");
        } catch (NoSuchMethodException expected) { }
    }

    public static final class InlinedResult {
        public final int resultCode;
        public final Intent data;
        InlinedResult(int code, Intent data) { resultCode = code; this.data = data; }
    }
    public static final class WrongResult { public final String resultCode = "-1"; }
}
