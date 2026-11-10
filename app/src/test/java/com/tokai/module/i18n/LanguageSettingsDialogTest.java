package com.tokai.module.i18n;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import com.tokai.module.config.SettingsRepository;
import com.tokai.module.config.SettingsStore;
import com.tokai.module.ui.miuix.MiuixSettingsViews;
import com.tokai.module.ui.miuix.MiuixFormControls;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, qualifiers = "en")
public final class LanguageSettingsDialogTest {
    Activity activity;
    SettingsRepository store;
    AlertDialog dialog;
    @Before public void setUp() {
        activity = Robolectric.buildActivity(Activity.class).setup().get();
        activity.getSharedPreferences(SettingsRepository.PREFERENCES_NAME, 0).edit().clear().commit();
        store = new SettingsRepository(activity); ModuleLocale.bind(activity, store);
    }
    @After public void close() { if (dialog != null) dialog.dismiss(); activity.finish(); }

    @Test public void showsSixNativeNamesAndSelectingWritesBeforeTheReopenCallback() {
        AtomicInteger reopen = new AtomicInteger();
        dialog = (AlertDialog) LanguageSettingsDialog.show(activity, store, () -> {
            assertEquals("ko", store.getString(ModuleLanguage.KEY, "system")); reopen.incrementAndGet();
        });
        assertEquals(6, dialog.getListView().getAdapter().getCount());
        assertEquals("Follow system", dialog.getListView().getAdapter().getItem(0));
        assertEquals("简体中文", dialog.getListView().getAdapter().getItem(1));
        assertEquals("繁體中文", dialog.getListView().getAdapter().getItem(2));
        assertEquals("한국어", dialog.getListView().getAdapter().getItem(3));
        assertEquals("日本語", dialog.getListView().getAdapter().getItem(4));
        assertEquals("English", dialog.getListView().getAdapter().getItem(5));
        dialog.getListView().performItemClick(null, 3, 3);
        assertEquals(1, reopen.get()); assertFalse(dialog.isShowing());
    }

    @Test public void cancelDoesNotWriteOrReopen() {
        AtomicInteger reopen = new AtomicInteger();
        dialog = (AlertDialog) LanguageSettingsDialog.show(activity, store, reopen::incrementAndGet);
        dialog.getButton(DialogInterface.BUTTON_NEGATIVE).performClick();
        assertFalse(store.snapshot().containsKey(ModuleLanguage.KEY)); assertEquals(0, reopen.get());
    }

    @Test public void stableWidgetTagsDoNotContainTranslatedTitlesOrUserValues() {
        ModuleLocale.select(store, "ko");
        MiuixSettingsViews.Toggle toggle = new MiuixSettingsViews.Toggle(activity, "자막 강제 표시", "", false, true,
                ignored -> { }, "强制展示字幕");
        MiuixSettingsViews.Option option = new MiuixSettingsViews.Option(activity, "음성 설정", "", "用户内容", true,
                () -> { }, "文本转语音设置");
        assertEquals("settings.toggle.强制展示字幕", toggle.getView().getTag());
        assertEquals("settings.option.文本转语音设置", option.getView().getTag());
        android.widget.EditText field = MiuixFormControls.input(activity, "메시지", "用户原文", false, "消息");
        assertEquals("用户原文", field.getText().toString());
        assertEquals("메시지", field.getContentDescription().toString());
        android.widget.CheckBox check = MiuixFormControls.checkbox(activity, "선택", true, "选择");
        assertEquals("선택", check.getText().toString()); assertTrue(check.isChecked());
    }
}
