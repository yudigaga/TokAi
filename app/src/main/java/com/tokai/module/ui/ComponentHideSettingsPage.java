package com.tokai.module.ui;

import com.tokai.module.R;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ImageButton;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.tokai.module.config.ComponentHideSettings;
import com.tokai.module.config.ComponentHideSettings.Component;
import com.tokai.module.config.CustomViewLocator;
import com.tokai.module.config.SettingsStore;
import com.tokai.module.ui.miuix.MiuixDialogBuilder;

import java.util.ArrayList;
import java.util.List;

/** The component controls share the same store in the module and host settings. */
public final class ComponentHideSettingsPage {
    private static final int TEXT = Color.rgb(31, 39, 45);
    private static final int MUTED = Color.rgb(105, 115, 122);
    private static final int ACCENT = Color.rgb(0, 133, 225);
    private static final int LINE = Color.rgb(226, 231, 232);

    private final Context context;
    private final SettingsStore store;
    private Dialog dialog;
    private Dialog customDetails;
    private LinearLayout content;
    private LinearLayout customSection;
    private Switch uniformToggle;

    public ComponentHideSettingsPage(Context context, SettingsStore store) {
        this.context = ModuleTheme.themedContext(context);
        this.store = store;
    }

    public void show() {
        if (isShowing()) return;
        Activity activity = ownerActivity(context);
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        Dialog next = new Dialog(context, ModuleTheme.dialogTheme(context));
        next.setOwnerActivity(activity);
        next.setCanceledOnTouchOutside(true);
        next.setOnDismissListener(ignored -> {
            dismissCustomDetails();
            if (dialog == next) dialog = null;
        });
        next.setContentView(createView());
        Window window = next.getWindow();
        if (window != null) {
            window.setBackgroundDrawableResource(android.R.color.transparent);
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            WindowManager.LayoutParams attributes = window.getAttributes();
            attributes.dimAmount = 0.45f;
            window.setAttributes(attributes);
        }
        dialog = next;
        try {
            next.show();
            if (window != null) {
                DisplayMetrics metrics = context.getResources().getDisplayMetrics();
                View decor = activity.getWindow().getDecorView();
                int availableWidth = decor.getWidth() > 0 ? decor.getWidth() : metrics.widthPixels;
                int availableHeight = decor.getHeight() > 0 ? decor.getHeight() : metrics.heightPixels;
                int width = Math.min(dp(560), Math.max(1, availableWidth - dp(24)));
                int height = Math.max(1, Math.round(availableHeight * 0.82f));
                window.setLayout(width, height);
            }
        } catch (RuntimeException failure) {
            dismiss();
        }
    }

    public boolean isShowing() {
        return dialog != null && dialog.isShowing();
    }

    public void dismiss() {
        dismissCustomDetails();
        Dialog current = dialog;
        dialog = null;
        if (current != null) current.dismiss();
    }

    View createView() {
        ComponentHideSettings settings = ComponentHideSettings.readFrom(store);
        LinearLayout panel = new LinearLayout(context);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(12), 0, dp(12), dp(12));
        panel.setBackground(rounded(Color.rgb(245, 246, 249), dp(8)));
        panel.addView(createHeader(), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(64)));

        ScrollView scroll = new ScrollView(context);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setBackground(rounded(Color.WHITE, dp(8)));
        scroll.addView(content, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        panel.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        addToggle(com.tokai.module.i18n.UiText.get(R.string.editor_ui_hide_master), ComponentHideSettings.ENABLED, settings.enabled);
        addToggle(com.tokai.module.i18n.UiText.get(R.string.editor_ui_uniform_alpha_enabled), ComponentHideSettings.UNIFORM_ENABLED, settings.uniformEnabled);
        addAlpha(com.tokai.module.i18n.UiText.get(R.string.editor_ui_uniform_alpha), null, ComponentHideSettings.UNIFORM_ALPHA, settings.uniformAlpha);
        addToggle(com.tokai.module.i18n.UiText.get(R.string.editor_ui_hide_on_switch), ComponentHideSettings.HIDE_ON_SWITCH,
                settings.hideOnSwitch);
        addToggle(com.tokai.module.i18n.UiText.get(R.string.editor_ui_show_open_comments), ComponentHideSettings.OPEN_COMMENTS_ON_SHOW,
                settings.openCommentsOnShow);
        addToggle(com.tokai.module.i18n.UiText.get(R.string.editor_ui_show_on_pause), ComponentHideSettings.SHOW_ON_PAUSE,
                settings.showOnPause);
        for (Component component : Component.values()) {
            addAlpha(componentLabel(component), component == Component.PROGRESS
                            ? com.tokai.module.scan.HostFeatureSupport.settingUnavailableReason("short_progress") : null,
                    component.key, settings.individualAlpha(component));
        }
        addCustomViewsSection(settings.customViews);
        ModuleTheme.bind(panel);
        return panel;
    }

    private void addCustomViewsSection(List<CustomViewLocator> locators) {
        customSection = new LinearLayout(context);
        customSection.setOrientation(LinearLayout.VERTICAL);
        customSection.setTag("component.custom.section");
        content.addView(customSection, matchWrap());
        renderCustomViews(locators);
    }

    private void renderCustomViews(List<CustomViewLocator> locators) {
        if (customSection == null) return;
        customSection.removeAllViews();
        addDividerTo(customSection);
        LinearLayout heading = new LinearLayout(context);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        heading.setBaselineAligned(false);
        heading.setPadding(dp(12), dp(14), dp(12), dp(12));
        TextView header = text(com.tokai.module.i18n.UiText.get(R.string.editor_ui_custom_views), 15, TEXT, true);
        heading.addView(header, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView count = text(com.tokai.module.i18n.UiText.get(R.string.editor_ui_custom_view_count, locators.size()), 13, MUTED, false);
        count.setTag("component.custom.count");
        heading.addView(count, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        customSection.addView(heading, matchWrap());
        if (locators.isEmpty()) {
            TextView empty = text(com.tokai.module.i18n.UiText.get(R.string.editor_ui_no_custom_views), 13, MUTED, false);
            empty.setPadding(dp(12), dp(4), dp(12), dp(16));
            customSection.addView(empty, matchWrap());
            return;
        }
        for (CustomViewLocator locator : locators) addCustomViewRow(locator);
    }

    private void addCustomViewRow(CustomViewLocator locator) {
        LinearLayout row = new LinearLayout(context);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), dp(8), dp(8), dp(8));
        row.setMinimumHeight(dp(58));
        row.setTag("component.custom." + locator.identityKey());
        row.setFocusable(true);
        row.setOnClickListener(ignored -> {
            dismissCustomDetails();
            customDetails = showCustomViewDetails(context, locator, false);
        });
        LinearLayout labels = new LinearLayout(context);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.addView(text(locator.label, 14, TEXT, true), matchWrap());
        labels.addView(text(scopeLabel(locator) + " · " + matchingLabel(locator),
                12, MUTED, false), matchWrap());
        labels.addView(text(locator.viewClassName, 11, MUTED, false), matchWrap());
        row.addView(labels, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        ImageButton remove = new ImageButton(context);
        remove.setImageResource(android.R.drawable.ic_menu_delete);
        remove.setBackgroundColor(Color.TRANSPARENT);
        remove.setContentDescription(com.tokai.module.i18n.UiText.get(R.string.editor_ui_delete_custom_view));
        remove.setTooltipText(com.tokai.module.i18n.UiText.get(R.string.editor_ui_delete_custom_view));
        remove.setTag("component.custom.delete." + locator.identityKey());
        remove.setOnClickListener(ignored -> confirmDelete(locator));
        row.addView(remove, new LinearLayout.LayoutParams(dp(48), dp(48)));
        customSection.addView(row, matchWrap());
        addDividerTo(customSection);
    }

    /** Shared by the native and Miuix component lists; this reads locator metadata only. */
    public static AlertDialog showCustomViewDetails(Context source, CustomViewLocator locator,
                                                   boolean useMiuix) {
        Context themed = ModuleTheme.themedContext(source);
        Activity activity = ownerActivity(themed);
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return null;
        String information = com.tokai.module.i18n.UiText.get(R.string.editor_ui_custom_view_metadata, locator.scope == CustomViewLocator.Scope.FEED ? com.tokai.module.i18n.UiText.get(R.string.editor_ui_scope_feed) : com.tokai.module.i18n.UiText.get(R.string.editor_ui_scope_window), matchingLabel(locator), locator.viewClassName, locator.resourceName.isEmpty() ? com.tokai.module.i18n.UiText.get(R.string.editor_ui_no_resource_id) : locator.resourceName, locator.rootClassName, locator.pathDescription());
        TextView detail = new TextView(themed);
        detail.setText(information);
        detail.setTextSize(13);
        detail.setTextIsSelectable(true);
        detail.setTag("component.custom.details");
        int padding = Math.round(16 * themed.getResources().getDisplayMetrics().density);
        detail.setPadding(padding, padding, padding, padding);
        ScrollView scroll = new ScrollView(themed);
        scroll.addView(detail);
        ModuleTheme.bind(scroll);
        AlertDialog next = new MiuixDialogBuilder(themed, useMiuix)
                .setTitle(com.tokai.module.i18n.UiText.get(R.string.editor_ui_custom_view_details)).setView(scroll)
                .setNegativeButton(com.tokai.module.i18n.UiText.get(R.string.editor_ui_close), null)
                .setPositiveButton(com.tokai.module.i18n.UiText.get(R.string.editor_ui_copy_rule_info), (dialog, which) -> {
                    try {
                        ClipboardManager clipboard = (ClipboardManager) themed.getSystemService(
                                Context.CLIPBOARD_SERVICE);
                        if (clipboard == null) throw new IllegalStateException("Clipboard unavailable");
                        clipboard.setPrimaryClip(ClipData.newPlainText(com.tokai.module.i18n.UiText.get(R.string.editor_ui_clipboard_custom_view), information));
                        Toast.makeText(themed, com.tokai.module.i18n.UiText.get(R.string.editor_ui_rule_copied), Toast.LENGTH_SHORT).show();
                    } catch (RuntimeException unavailable) {
                        Toast.makeText(themed, com.tokai.module.i18n.UiText.get(R.string.editor_ui_copy_failed), Toast.LENGTH_SHORT).show();
                    }
                }).create();
        next.setOwnerActivity(activity);
        // MiuixDialogBuilder owns its inset listener and Compose height limit.
        if (!useMiuix) next.setOnShowListener(ignored -> HostDialogBounds.install(activity, next, false));
        try {
            next.show();
            return next;
        } catch (RuntimeException unavailable) {
            next.dismiss();
            return null;
        }
    }

    private void dismissCustomDetails() {
        Dialog current = customDetails;
        customDetails = null;
        if (current != null) current.dismiss();
    }

    private void confirmDelete(CustomViewLocator locator) {
        new MiuixDialogBuilder(context, false).setTitle(com.tokai.module.i18n.UiText.get(R.string.editor_ui_delete_custom_view))
                .setMessage(com.tokai.module.i18n.UiText.get(R.string.editor_ui_delete_custom_view_hint))
                .setNegativeButton(com.tokai.module.i18n.UiText.get(R.string.editor_ui_cancel), null)
                .setPositiveButton(com.tokai.module.i18n.UiText.get(R.string.editor_ui_delete), (dialog, which) -> {
                    List<CustomViewLocator> updated = new ArrayList<>(
                            ComponentHideSettings.readFrom(store).customViews);
                    updated.removeIf(item -> item.identityKey().equals(locator.identityKey()));
                    store.putString(CustomViewLocator.SETTINGS_KEY, CustomViewLocator.serialize(updated));
                    renderCustomViews(updated);
                }).show();
    }

    private View createHeader() {
        LinearLayout header = new LinearLayout(context);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView back = text(com.tokai.module.i18n.UiText.get(R.string.editor_ui_root_back), 16, ACCENT, false);
        back.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        back.setContentDescription(com.tokai.module.i18n.UiText.get(R.string.editor_ui_back_root));
        back.setFocusable(true);
        back.setOnClickListener(view -> dismiss());
        header.addView(back, new LinearLayout.LayoutParams(dp(90), dp(48)));
        TextView title = text(com.tokai.module.i18n.UiText.get(R.string.editor_ui_component_hide), 20, TEXT, true);
        title.setGravity(Gravity.CENTER);
        title.setMaxLines(1);
        title.setAutoSizeTextTypeUniformWithConfiguration(14, 20, 1,
                TypedValue.COMPLEX_UNIT_SP);
        header.addView(title, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, 1));
        header.addView(new View(context), new LinearLayout.LayoutParams(dp(90), 1));
        return header;
    }

    private static String scopeLabel(CustomViewLocator locator) { return locator.scope == CustomViewLocator.Scope.FEED ? com.tokai.module.i18n.UiText.get(R.string.editor_ui_scope_feed) : com.tokai.module.i18n.UiText.get(R.string.editor_ui_scope_window); }

    private static String matchingLabel(CustomViewLocator locator) {
        switch (locator.matchingMode()) {
            case RESOURCE_ID: return com.tokai.module.i18n.UiText.get(R.string.editor_ui_match_unique_id);
            case ANCHORED_PATH: return com.tokai.module.i18n.UiText.get(R.string.editor_ui_match_ancestor_path);
            case STRUCTURAL_PATH: return com.tokai.module.i18n.UiText.get(R.string.editor_ui_match_strict_path);
            default: throw new AssertionError(locator.matchingMode());
        }
    }

    private static String componentLabel(Component component) {
        switch (component) {
            case AVATAR: return com.tokai.module.i18n.UiText.get(R.string.editor_ui_component_avatar);
            case LIKE: return com.tokai.module.i18n.UiText.get(R.string.editor_ui_component_like);
            case COMMENT: return com.tokai.module.i18n.UiText.get(R.string.editor_ui_component_comment);
            case COLLECT: return com.tokai.module.i18n.UiText.get(R.string.editor_ui_component_collect);
            case SHARE: return com.tokai.module.i18n.UiText.get(R.string.editor_ui_component_share);
            case MUSIC_DISC: return com.tokai.module.i18n.UiText.get(R.string.editor_ui_component_music_disc);
            case CARD: return com.tokai.module.i18n.UiText.get(R.string.editor_ui_component_card);
            case AUTHOR: return com.tokai.module.i18n.UiText.get(R.string.editor_ui_component_author);
            case CO_CREATION: return com.tokai.module.i18n.UiText.get(R.string.editor_ui_component_co_creation);
            case DESCRIPTION: return com.tokai.module.i18n.UiText.get(R.string.editor_ui_component_description);
            case MUSIC_INFO: return com.tokai.module.i18n.UiText.get(R.string.editor_ui_component_music_info);
            case FULLSCREEN: return com.tokai.module.i18n.UiText.get(R.string.editor_ui_component_fullscreen);
            case TOP_NAV: return com.tokai.module.i18n.UiText.get(R.string.editor_ui_component_top_nav);
            case BOTTOM_NAV: return com.tokai.module.i18n.UiText.get(R.string.editor_ui_component_bottom_nav);
            case TAKO: return com.tokai.module.i18n.UiText.get(R.string.editor_ui_component_tako);
            case FEEDBACK: return com.tokai.module.i18n.UiText.get(R.string.editor_ui_component_feedback);
            case PROGRESS: return com.tokai.module.i18n.UiText.get(R.string.editor_ui_component_progress);
            case BOTTOM_TOOLS: return com.tokai.module.i18n.UiText.get(R.string.editor_ui_component_bottom_tools);
            case TIME_LOCATION: return com.tokai.module.i18n.UiText.get(R.string.editor_ui_component_time_location);
            default: throw new AssertionError(component);
        }
    }

    private void addToggle(String title, String key, boolean checked) {
        LinearLayout row = new LinearLayout(context);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), dp(10), dp(12), dp(10));
        row.setMinimumHeight(dp(58));
        TextView label = text(title, 15, TEXT, false);
        row.addView(label, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Switch toggle = new Switch(context);
        if (ComponentHideSettings.UNIFORM_ENABLED.equals(key)) uniformToggle = toggle;
        toggle.setChecked(checked);
        toggle.setContentDescription(title);
        toggle.setTag(key);
        toggle.setMinimumWidth(dp(52));
        toggle.setOnCheckedChangeListener((button, value) -> {
            initializeMaster();
            store.putBoolean(key, value);
        });
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.leftMargin = dp(12);
        row.addView(toggle, params);
        content.addView(row, matchWrap());
        addDivider();
    }

    private void addAlpha(String title, String subtitle, String key, int initialValue) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(12), dp(14), dp(12), dp(10));
        LinearLayout heading = new LinearLayout(context);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout labels = new LinearLayout(context);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.addView(text(title, 15, TEXT, false), matchWrap());
        if (subtitle != null) labels.addView(text(subtitle, 12, MUTED, false), matchWrap());
        heading.addView(labels, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView value = text(com.tokai.module.i18n.UiText.get(R.string.editor_ui_alpha_value, initialValue), 14, TEXT, false);
        value.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        value.setMaxLines(1);
        value.setAutoSizeTextTypeUniformWithConfiguration(11, 14, 1,
                TypedValue.COMPLEX_UNIT_SP);
        LinearLayout.LayoutParams valueParams = new LinearLayout.LayoutParams(dp(98), dp(30));
        valueParams.leftMargin = dp(6);
        heading.addView(value, valueParams);
        row.addView(heading, matchWrap());

        SeekBar slider = new SeekBar(context);
        slider.setMax(257);
        slider.setProgress(initialValue + 2);
        slider.setTag(key);
        slider.setContentDescription(title);
        setAlphaStateDescription(slider, initialValue);
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            private boolean tracking;
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int alpha = progress - 2;
                value.setText(com.tokai.module.i18n.UiText.get(R.string.editor_ui_alpha_value, alpha));
                setAlphaStateDescription(seekBar, alpha);
                if (fromUser && !tracking) save(alpha);
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) { tracking = true; }
            @Override public void onStopTrackingTouch(SeekBar seekBar) {
                tracking = false;
                save(seekBar.getProgress() - 2);
            }
            private void save(int alpha) {
                initializeMaster();
                if (!ComponentHideSettings.UNIFORM_ALPHA.equals(key)) {
                    store.putBoolean(ComponentHideSettings.UNIFORM_ENABLED, false);
                    if (uniformToggle != null) uniformToggle.setChecked(false);
                }
                store.putString(key, Integer.toString(alpha));
            }
        });
        LinearLayout.LayoutParams sliderParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
        sliderParams.topMargin = dp(8);
        row.addView(slider, sliderParams);
        content.addView(row, matchWrap());
        addDivider();
    }

    private void initializeMaster() {
        ComponentHideSettings.initialize(store);
    }

    private static void setAlphaStateDescription(SeekBar slider, int alpha) {
        if (Build.VERSION.SDK_INT >= 30) slider.setStateDescription(com.tokai.module.i18n.UiText.get(R.string.editor_ui_alpha_value, alpha));
    }

    private void addDivider() {
        addDividerTo(content);
    }

    private void addDividerTo(LinearLayout target) {
        View divider = new View(context);
        divider.setBackgroundColor(LINE);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        params.leftMargin = dp(12);
        params.rightMargin = dp(12);
        target.addView(divider, params);
    }

    private TextView text(String value, float size, int color, boolean bold) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    private GradientDrawable rounded(int fill, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        drawable.setCornerRadius(radius);
        return drawable;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    private static Activity ownerActivity(Context context) {
        while (context instanceof ContextWrapper) {
            if (context instanceof Activity) return (Activity) context;
            Context next = ((ContextWrapper) context).getBaseContext();
            if (next == context) break;
            context = next;
        }
        return null;
    }
}
