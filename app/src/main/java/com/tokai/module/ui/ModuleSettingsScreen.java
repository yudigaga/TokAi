package com.tokai.module.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import com.tokai.module.R;
import com.tokai.module.i18n.UiText;

import com.tokai.module.config.AiModelConfig;
import com.tokai.module.config.AiModelSettings;
import com.tokai.module.config.AiProviderPresets;
import com.tokai.module.config.FeatureSettings;
import com.tokai.module.config.ConfigurationCatalog;
import com.tokai.module.config.CaptionVisibilitySettings;
import com.tokai.module.config.SettingsStore;
import com.tokai.module.config.LocalOptions;
import com.tokai.module.config.PlaybackSpeedPresets;
import com.tokai.module.config.ThirdPartyDownloadSettings;
import com.tokai.module.feature.FeedGestureToolsHook;
import com.tokai.module.feature.TopTabFilterHook;
import com.tokai.module.feature.ProfileListEntry;
import com.tokai.module.feature.NativeMessagingHooks;
import com.tokai.module.ui.miuix.HostComposeEnvironment;
import com.tokai.module.ui.miuix.MiuixComponentHidePage;
import com.tokai.module.ui.miuix.MiuixDialogBuilder;
import com.tokai.module.ui.miuix.MiuixFormControls;
import com.tokai.module.ui.miuix.MiuixExtraControls;
import com.tokai.module.ui.miuix.MiuixSettingsViews;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Shared settings behavior with Miuix host UI and a native recovery UI. */
public final class ModuleSettingsScreen {
    private static final int TEXT = Color.rgb(31, 39, 45);
    private static final int MUTED = Color.rgb(105, 115, 122);
    private static final int ACCENT = Color.rgb(0, 151, 136);
    private static final int LINE = Color.rgb(226, 231, 232);

    private final Context context;
    private final SettingsStore repository;
    private final Runnable onClose;
    private Runnable onLanguageChanged;
    private final Consumer<String> analyticsDiagnostics;
    private FeatureSettings settings;
    private LinearLayout content;
    private TextView scanTitle;
    private TextView scanDetail;
    private View scanMark;
    private TextView syncTitle;
    private TextView syncDetail;
    private View syncMark;
    private ComponentHideSettingsPage componentHidePage;
    private Dialog categoryDialog;
    private HostDownloadDirectoryPicker downloadDirectoryPicker;
    private AutomationMessageSettingsPage automationMessagePage;
    private final List<Dialog> optionDialogs = new ArrayList<>();
    private final java.util.Map<Dialog, Runnable> optionDismissActions = new java.util.HashMap<>();
    private final List<EnabledControl> feedFilterControls = new ArrayList<>();
    private final List<CategoryRow> categoryRows = new ArrayList<>();
    private MiuixSettingsViews.Home miuixHome;
    private MiuixComponentHidePage miuixComponentHidePage;
    private Context miuixRowContext;
    private LinearLayout featureSearchResults;
    private boolean closed;
    private ScrollView categoryScroll;
    private ScrollView searchFocusHost;
    private View focusedSearchTarget;
    private Drawable focusedSearchForeground;
    private boolean focusedSearchActivated;
    private Runnable searchFocusAction, searchFocusClear;
    private View.OnLayoutChangeListener searchFocusLayout;
    private long searchFocusGeneration;
    private static final SearchEntry[] FEATURE_SEARCH = {
            new SearchEntry("ai", R.string.settings_search_ai_title, R.string.settings_search_ai_path, R.string.settings_search_ai_terms, SearchDestination.AI),
            new SearchEntry("speech", R.string.settings_search_speech_title, R.string.settings_search_speech_path, R.string.settings_search_speech_terms, SearchDestination.SPEECH),
            new SearchEntry("download-mode", R.string.settings_download_method, R.string.settings_search_download_mode_path, R.string.settings_search_download_mode_terms, SearchDestination.DOWNLOAD, "settings.option.下载方式"),
            new SearchEntry("download-directory", R.string.settings_download_directory, R.string.settings_search_download_directory_path, R.string.settings_search_download_directory_terms, SearchDestination.DOWNLOAD, "settings.option.保存位置"),
            new SearchEntry("download-tasks", R.string.settings_download_tasks, R.string.settings_search_download_tasks_path, R.string.settings_search_download_tasks_terms, SearchDestination.DOWNLOAD, "settings.option.下载任务"),
            new SearchEntry("download-name", R.string.settings_download_name_template, R.string.settings_search_download_name_path, R.string.settings_search_download_name_terms, SearchDestination.DOWNLOAD, "settings.option.文件名模板"),
            new SearchEntry("download-watermark", R.string.settings_search_download_watermark_title, R.string.settings_search_download_watermark_path, R.string.settings_search_download_watermark_terms, SearchDestination.DOWNLOAD, "settings.toggle.无水印下载图集或者视频"),
            new SearchEntry("auto-comment", R.string.settings_auto_comment, R.string.settings_search_auto_comment_path, R.string.settings_search_auto_comment_terms, SearchDestination.AUTO_COMMENT),
            new SearchEntry("auto-reply", R.string.settings_search_auto_reply_title, R.string.settings_search_auto_reply_path, R.string.settings_search_auto_reply_terms, SearchDestination.AUTO_REPLY),
            new SearchEntry("comment-reply", R.string.settings_search_comment_reply_title, R.string.settings_search_comment_reply_path, R.string.settings_search_comment_reply_terms, SearchDestination.AUTO_REPLY),
            new SearchEntry("backup", R.string.settings_search_backup_title, R.string.settings_search_backup_path, R.string.settings_search_backup_terms, SearchDestination.BACKUP),
            new SearchEntry("analytics", R.string.settings_search_analytics_title, R.string.settings_search_analytics_path, R.string.settings_search_analytics_terms, SearchDestination.ANALYTICS),
            new SearchEntry("debug-logs", R.string.settings_search_debug_title, R.string.settings_search_debug_path, R.string.settings_search_debug_terms, SearchDestination.DEBUG_LOGS),
            new SearchEntry("speed", R.string.settings_search_speed_title, R.string.settings_search_speed_path, R.string.settings_search_speed_terms, SearchDestination.AUTOPLAY),
            new SearchEntry("module-only-speed", R.string.settings_module_only_speed, R.string.settings_search_module_only_speed_path, R.string.settings_search_module_only_speed_terms, SearchDestination.AUTOPLAY,
                    "settings.toggle.仅模块控制倍速"),
            new SearchEntry("interaction", R.string.settings_interaction_automation, R.string.settings_search_interaction_path, R.string.settings_search_interaction_terms, SearchDestination.INTERACTION),
            new SearchEntry("feed-filter", R.string.settings_category_filter, R.string.settings_search_filter_path, R.string.settings_search_filter_terms, SearchDestination.FILTER),
            new SearchEntry("component-hide", R.string.settings_category_component_hide, R.string.settings_search_component_hide_path, R.string.settings_search_component_hide_terms, SearchDestination.COMPONENT_HIDE),
            new SearchEntry("streak", R.string.settings_streak_enabled, R.string.settings_search_streak_path, R.string.settings_search_streak_terms, SearchDestination.STREAK),
            new SearchEntry("message-recall", R.string.settings_prevent_message_recall, R.string.settings_search_message_recall_path, R.string.settings_search_message_recall_terms, SearchDestination.MESSAGE),
            new SearchEntry("environment", R.string.settings_category_environment, R.string.settings_category_environment, R.string.settings_search_environment_terms, SearchDestination.ENVIRONMENT),
            new SearchEntry("gestures", R.string.settings_search_gestures_title, R.string.settings_search_gestures_path, R.string.settings_search_gestures_terms, SearchDestination.GESTURES),
            new SearchEntry("captions", R.string.settings_search_captions_title, R.string.settings_search_captions_path, R.string.settings_search_captions_terms, SearchDestination.GESTURES,
                    "settings.toggle.强制展示字幕"),
            new SearchEntry("comment-tools", R.string.settings_search_comment_tools_title, R.string.settings_search_comment_tools_path, R.string.settings_search_comment_tools_terms, SearchDestination.COMMENT),
            new SearchEntry("comment-voice-file", R.string.settings_comment_voice_file, R.string.settings_search_comment_voice_file_path, R.string.settings_search_comment_voice_file_terms, SearchDestination.COMMENT, "settings.option.评论语音文件"),
            new SearchEntry("message-voice-file", R.string.settings_message_voice_file, R.string.settings_search_message_voice_file_path, R.string.settings_search_message_voice_file_terms, SearchDestination.MESSAGE, "settings.option.私聊语音文件"),
            new SearchEntry("comment-speech", R.string.settings_comment_speech, R.string.settings_search_comment_speech_path, R.string.settings_search_comment_speech_terms, SearchDestination.COMMENT, "settings.option.评论转语音"),
            new SearchEntry("hash-long-press-voice", R.string.settings_hash_long_press_voice, R.string.settings_search_hash_long_press_voice_path, R.string.settings_search_hash_long_press_voice_terms, SearchDestination.COMMENT, "settings.toggle.#长按发送转语音（全局）"),
            new SearchEntry("ai-chat", R.string.settings_ai_chat, R.string.settings_search_ai_chat_path, R.string.settings_search_ai_chat_terms, SearchDestination.AI_CHAT)
    };

    public ModuleSettingsScreen(Context context, SettingsStore repository) {
        this(context, repository, null);
    }

    public ModuleSettingsScreen(Context context, SettingsStore repository, Runnable onClose) {
        this(context, repository, onClose, null);
    }

    public ModuleSettingsScreen(Context context, SettingsStore repository, Runnable onClose,
                                Consumer<String> analyticsDiagnostics) {
        com.tokai.module.i18n.ModuleLocale.bind(context, repository);
        this.context = ModuleTheme.themedContext(context);
        this.repository = repository;
        this.onClose = onClose;
        this.analyticsDiagnostics = analyticsDiagnostics;
        this.settings = repository.load();
    }

    public View createView() {
        if (miuixHome != null) {
            miuixHome.close();
            miuixHome = null;
        }
        ScrollView scroll = new ScrollView(context);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.rgb(248, 250, 250));
        scroll.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View view) { }
            @Override public void onViewDetachedFromWindow(View view) {
                dismissChildPages();
            }
        });

        content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20), dp(24), dp(20), dp(32));
        scroll.addView(content, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        addTitle("TokAi", "TikTok 47.5.3");
        addOption("界面语言", UiText.get(R.string.module_language_title), UiText.get(R.string.module_language_summary),
                com.tokai.module.i18n.LanguageSettingsDialog.label(com.tokai.module.i18n.ModuleLocale.choice()), true,
                this::showLanguageSettings).view.setTag("settings.language");
        addScanStatus(ScanStatus.pending());
        if (repository.isInHost()) {
            addHostSettingsNotice();
        } else {
            addFrameworkSyncStatus();
            addIndependentSettingsNotice();
            addOption("关于", UiText.get(R.string.settings_about), "TokAi " + com.tokai.module.BuildConfig.VERSION_NAME, null, true,
                    () -> openCategory(Category.ABOUT));
            ModuleTheme.bind(scroll);
            return scroll;
        }
        addCategorySearch();
        addCategoryList();
        ModuleTheme.bind(scroll);
        return scroll;
    }

    public View createMiuixView() {
        Activity activity = findOwnerActivity(context);
        if (activity == null || !repository.isInHost()) return createView();
        Category[] categories = Category.values();
        String[] titles = new String[categories.length];
        int[] counts = new int[categories.length];
        for (int index = 0; index < categories.length; index++) {
            titles[index] = categories[index].title();
            counts[index] = categories[index].itemCount;
        }
        miuixHome = new MiuixSettingsViews.Home(activity, titles, counts,
                index -> openCategory(categories[index]), () -> { if (onClose != null) onClose.run(); });
        miuixHome.setLanguageAction(this::showLanguageSettings);
        String[] searchTerms = new String[categories.length];
        for (int index = 0; index < categories.length; index++) searchTerms[index] = categories[index].searchText();
        miuixHome.setSearchTerms(searchTerms);
        String[] ids = new String[FEATURE_SEARCH.length], featureTitles = new String[FEATURE_SEARCH.length];
        String[] paths = new String[FEATURE_SEARCH.length], terms = new String[FEATURE_SEARCH.length];
        for (int index = 0; index < FEATURE_SEARCH.length; index++) {
            SearchEntry entry = FEATURE_SEARCH[index];
            ids[index] = entry.id;
            featureTitles[index] = entry.title();
            paths[index] = entry.path();
            terms[index] = entry.searchText();
        }
        miuixHome.setFeatureSearch(ids, featureTitles, paths, terms, this::openFeatureSearch);
        View home = miuixHome.getView();
        home.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View view) { }
            @Override public void onViewDetachedFromWindow(View view) {
                dismissChildPages();
                miuixHome.close();
            }
        });
        return home;
    }

    public void bindMiuixBackNavigation(Dialog dialog) {
        if (miuixHome != null) miuixHome.bindBackNavigation(dialog);
    }

    public void setOnLanguageChanged(Runnable action) { onLanguageChanged = action; }

    private void showLanguageSettings() {
        Dialog dialog = com.tokai.module.i18n.LanguageSettingsDialog.show(context, repository, onLanguageChanged);
        showOptionDialog(dialog);
    }

    private void addCategorySearch() {
        EditText search = new EditText(context);
        search.setSingleLine(true);
        search.setTextSize(15);
        search.setHint(UiText.get(R.string.settings_search_hint));
        search.setContentDescription(UiText.get(R.string.settings_search_description));
        search.setTag("settings.search");
        search.setPadding(dp(16), dp(8), dp(16), dp(8));
        search.setBackground(makeRounded(Color.rgb(238, 239, 243), LINE, dp(24)));
        LinearLayout.LayoutParams params = matchWrap();
        params.topMargin = dp(18);
        content.addView(search, params);
        featureSearchResults = new LinearLayout(context);
        featureSearchResults.setOrientation(LinearLayout.VERTICAL);
        featureSearchResults.setTag("settings.search.results");
        featureSearchResults.setVisibility(View.GONE);
        content.addView(featureSearchResults, matchWrap());
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                String query = s.toString().trim().toLowerCase(java.util.Locale.ROOT);
                for (CategoryRow row : categoryRows) {
                    row.view.setVisibility(query.isEmpty() || row.category.searchText().toLowerCase(java.util.Locale.ROOT).contains(query)
                            ? View.VISIBLE : View.GONE);
                }
                showFeatureSearch(query);
            }
            @Override public void afterTextChanged(Editable s) { }
        });
    }

    private void showFeatureSearch(String query) {
        featureSearchResults.removeAllViews();
        featureSearchResults.setVisibility(query.isEmpty() ? View.GONE : View.VISIBLE);
        if (query.isEmpty()) return;
        for (int index = 0; index < FEATURE_SEARCH.length; index++) {
            SearchEntry entry = FEATURE_SEARCH[index];
            if (!entry.searchText().toLowerCase(java.util.Locale.ROOT).contains(query)) continue;
            TextView row = text(UiText.get(R.string.settings_search_result_format, entry.title(), entry.path()), 15, TEXT, false);
            row.setTag("settings.search.result." + entry.id);
            row.setContentDescription(UiText.get(R.string.settings_accessibility_pair, entry.title(), entry.path()));
            row.setPadding(dp(16), dp(12), dp(16), dp(12));
            row.setFocusable(true);
            final int selected = index;
            row.setOnClickListener(ignored -> openFeatureSearch(selected));
            featureSearchResults.addView(row, matchWrap());
        }
    }

    private void openFeatureSearch(int index) {
        if (closed || index < 0 || index >= FEATURE_SEARCH.length) return;
        SearchEntry entry = FEATURE_SEARCH[index];
        dismissChildPages();
        switch (entry.destination) {
            case AI: showAiModelSettings(); break;
            case SPEECH: showSpeechSettings(); break;
            case BACKUP: showConfigBackup(); break;
            case ANALYTICS: showLocalAnalytics(); break;
            case DEBUG_LOGS: showDebugLogs(); break;
            case INTERACTION: showAutomationSettings(); break;
            case AI_CHAT: showAiChat(); break;
            default:
                openCategory(entry.destination.category);
                if (entry.targetTag != null) focusSearchTarget(entry);
                break;
        }
    }

    /** Focus is owned by the current category dialog; it never changes the selected download source. */
    private void focusSearchTarget(SearchEntry entry) {
        clearSearchFocus();
        final Dialog dialog = categoryDialog;
        final ScrollView scroll = categoryScroll;
        if (dialog == null || scroll == null || !dialog.isShowing()) return;
        View target = scroll.findViewWithTag(entry.targetTag);
        String targetId = entry.id;
        if (target == null && entry.destination == SearchDestination.DOWNLOAD) {
            target = scroll.findViewWithTag("settings.option.下载方式");
            targetId = "download-mode";
            toast(UiText.get(R.string.settings_download_official_setting_notice));
        }
        if (target == null) return;
        // Native switches tag their inner control; Compose rows tag the outer view.
        if (target instanceof Switch && target.getParent() instanceof View) target = (View) target.getParent();
        final View selected = target;
        final long generation = searchFocusGeneration;
        selected.setTag(com.tokai.module.R.id.settings_search_target, "settings.target." + targetId);
        searchFocusHost = scroll;
        searchFocusClear = () -> { if (generation == searchFocusGeneration) clearSearchFocus(); };
        searchFocusAction = () -> {
            if (closed || generation != searchFocusGeneration || categoryDialog != dialog
                    || !dialog.isShowing() || !selected.isAttachedToWindow()) return;
            if (scroll.getHeight() == 0 || selected.getHeight() == 0) return;
            if (searchFocusLayout != null) scroll.removeOnLayoutChangeListener(searchFocusLayout);
            searchFocusLayout = null;
            scroll.removeCallbacks(searchFocusAction);
            Rect bounds = new Rect();
            selected.getDrawingRect(bounds);
            scroll.offsetDescendantRectToMyCoords(selected, bounds);
            scroll.scrollTo(0, Math.max(0, bounds.top - dp(12)));
            focusedSearchTarget = selected;
            focusedSearchForeground = selected.getForeground();
            focusedSearchActivated = selected.isActivated();
            selected.setActivated(true);
            selected.setForeground(makeRounded(Color.TRANSPARENT, ACCENT, dp(8)));
            selected.announceForAccessibility(entry.title());
            scroll.postDelayed(searchFocusClear, 1800L);
        };
        searchFocusLayout = (view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            if (searchFocusAction != null) scroll.post(searchFocusAction);
        };
        scroll.addOnLayoutChangeListener(searchFocusLayout);
        scroll.post(searchFocusAction);
    }

    private void clearSearchFocus() {
        searchFocusGeneration++;
        if (searchFocusHost != null) {
            if (searchFocusAction != null) searchFocusHost.removeCallbacks(searchFocusAction);
            if (searchFocusClear != null) searchFocusHost.removeCallbacks(searchFocusClear);
            if (searchFocusLayout != null) searchFocusHost.removeOnLayoutChangeListener(searchFocusLayout);
        }
        if (focusedSearchTarget != null) {
            focusedSearchTarget.setForeground(focusedSearchForeground);
            focusedSearchTarget.setActivated(focusedSearchActivated);
        }
        searchFocusHost = null;
        focusedSearchTarget = null;
        focusedSearchForeground = null;
        searchFocusAction = null;
        searchFocusClear = null;
        searchFocusLayout = null;
    }

    private void addCategoryList() {
        LinearLayout list = new LinearLayout(context);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setBackground(makeRounded(Color.WHITE, LINE, dp(8)));
        LinearLayout.LayoutParams listParams = matchWrap();
        listParams.topMargin = dp(14);
        content.addView(list, listParams);
        if (featureSearchResults != null) {
            content.removeView(featureSearchResults);
            content.addView(featureSearchResults, matchWrap());
        }
        categoryRows.clear();
        for (Category category : Category.values()) {
            LinearLayout row = new LinearLayout(context);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(16), dp(15), dp(14), dp(15));
            row.setMinimumHeight(dp(58));
            TextView title = text(category.title(), 16, TEXT, true);
            row.addView(title, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            TextView count = text(category.itemCount < 0 ? "" : category.itemCount == 0 ? UiText.get(R.string.settings_not_adapted) : UiText.get(R.string.settings_item_count, category.itemCount),
                    13, MUTED, false);
            if (category.itemCount < 0) count.setVisibility(View.GONE);
            count.setGravity(Gravity.CENTER);
            count.setPadding(dp(10), dp(4), dp(10), dp(4));
            count.setBackground(makeRounded(Color.rgb(242, 243, 247),
                    Color.rgb(242, 243, 247), dp(16)));
            row.addView(count, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            TextView arrow = text("›", 24, MUTED, false);
            arrow.setGravity(Gravity.CENTER);
            row.addView(arrow, new LinearLayout.LayoutParams(dp(32), dp(32)));
            row.setFocusable(true);
            row.setContentDescription(category.title());
            row.setTag("settings.category." + category.name());
            row.setOnClickListener(ignored -> openCategory(category));
            list.addView(row, matchWrap());
            categoryRows.add(new CategoryRow(category, row));
            if (category != Category.values()[Category.values().length - 1]) addDividerTo(list);
        }
    }

    private void openCategory(Category category) {
        if (closed || category == null) return;
        if (category == Category.ENVIRONMENT) {
            EnvironmentSpoofSettingsDialog page = new EnvironmentSpoofSettingsDialog(
                    context, repository, miuixHome != null, this::showOptionDialog);
            showOptionDialog(page.dialog(), page::close);
            return;
        }
        settings = repository.load();
        if (category == Category.ABOUT) {
            showOptionDialog(AboutDialog.create(context, miuixHome != null, this::showOptionDialog, repository));
            return;
        }
        if (category == Category.COMPONENT_HIDE) {
            if (miuixHome != null) {
                if (miuixComponentHidePage == null) {
                    miuixComponentHidePage = new MiuixComponentHidePage(context, repository);
                }
                miuixComponentHidePage.show();
                return;
            }
            if (componentHidePage != null && componentHidePage.isShowing()) return;
            componentHidePage = new ComponentHideSettingsPage(context, repository);
            componentHidePage.show();
            return;
        }
        if (categoryDialog != null && categoryDialog.isShowing()) return;
        Dialog dialog = new Dialog(context, ModuleTheme.dialogTheme(context));
        Activity owner = findOwnerActivity(context);
        if (owner == null || owner.isFinishing() || owner.isDestroyed()) return;
        dialog.setOwnerActivity(owner);
        dialog.setCanceledOnTouchOutside(true);
        HostComposeEnvironment session = miuixHome == null ? null
                : new HostComposeEnvironment(owner, dialog::dismiss);
        dialog.setOnDismissListener(ignored -> {
            if (categoryDialog == dialog) closeAutomationMessagePage();
            if (session != null) session.close();
            if (categoryDialog == dialog) {
                clearSearchFocus();
                categoryScroll = null;
                dismissOptionDialogs();
                closeDownloadDirectoryPicker();
                categoryDialog = null;
            }
        });
        ScrollView scroll = new ScrollView(context);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.setBackgroundColor(Color.TRANSPARENT);
        LinearLayout categoryContent = new LinearLayout(context);
        categoryContent.setOrientation(LinearLayout.VERTICAL);
        categoryContent.setPadding(dp(12), dp(4), dp(12), dp(12));
        categoryContent.setBackgroundColor(Color.TRANSPARENT);
        feedFilterControls.clear();
        miuixRowContext = session == null ? null : session.context();
        try {
            if (category == Category.AUTO_REPLY || category == Category.AUTO_COMMENT) {
                closeAutomationMessagePage();
                automationMessagePage = new AutomationMessageSettingsPage(context, repository, miuixHome != null,
                        rowContext(), miuixHome == null ? null : miuixHome.getView().getContext(), categoryContent,
                        category == Category.AUTO_REPLY ? "reply" : "comment", this::showOptionDialog,
                        this::showAiModelSettings, () -> settings = repository.load());
            } else {
                LinearLayout oldContent = content;
                content = categoryContent;
                try { buildCategoryControls(category); } finally { content = oldContent; }
            }
        } finally {
            miuixRowContext = null;
        }
        scroll.addView(categoryContent, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        if (session != null) {
            dialog.setContentView(MiuixSettingsViews.categoryPage(
                    session, category.title(), scroll, dialog::dismiss));
        } else {
            LinearLayout panel = new LinearLayout(context);
            panel.setOrientation(LinearLayout.VERTICAL);
            panel.setPadding(dp(12), 0, dp(12), dp(12));
            panel.setBackground(makeRounded(Color.rgb(245, 246, 249), LINE, dp(8)));
            panel.addView(createCategoryHeader(category), new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(58)));
            panel.addView(scroll, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
            ModuleTheme.bind(panel);
            dialog.setContentView(panel);
        }
        categoryDialog = dialog;
        categoryScroll = scroll;
        try {
            dialog.show();
            if (session != null) session.bindBackNavigation(dialog);
            Window window = dialog.getWindow();
            if (window != null) {
                window.setBackgroundDrawableResource(android.R.color.transparent);
            }
            HostDialogBounds.install(owner, dialog, false);
        } catch (RuntimeException failure) {
            closeAutomationMessagePage();
            if (session != null) session.close();
            categoryDialog = null;
            categoryScroll = null;
        }
    }

    private View createCategoryHeader(Category category) {
        LinearLayout header = new LinearLayout(context);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView back = text(UiText.get(R.string.settings_back_root), 16, Color.rgb(0, 133, 225), false);
        back.setContentDescription(UiText.get(R.string.settings_back_categories_description));
        back.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        back.setOnClickListener(ignored -> {
            if (categoryDialog != null) categoryDialog.dismiss();
        });
        header.addView(back, new LinearLayout.LayoutParams(dp(72), dp(48)));
        TextView title = text(category.title(), 20, TEXT, true);
        title.setGravity(Gravity.CENTER);
        title.setMaxLines(1);
        title.setAutoSizeTextTypeUniformWithConfiguration(14, 20, 1, android.util.TypedValue.COMPLEX_UNIT_SP);
        header.addView(title, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, 1));
        header.addView(new View(context), new LinearLayout.LayoutParams(dp(72), 1));
        return header;
    }

    private void buildCategoryControls(Category category) {
        switch (category) {
            case BASIC:
                addLocalSwitch("去除启动和闪屏广告", UiText.get(R.string.settings_remove_splash_ads), "", "remove_splash_ads");
                addLocalSwitch("去除更新弹窗", UiText.get(R.string.settings_remove_update_dialog), "", "remove_update_dialog");
                break;
            case INTERFACE:
                addLocalSwitch("悬浮条", UiText.get(R.string.settings_toolbar_enabled), "", "toolbar_enabled");
                addLocalSwitch("隐藏拍摄+号图标", UiText.get(R.string.settings_hide_camera_plus), UiText.get(R.string.settings_hide_camera_plus_summary), "hide_camera_plus");
                addLocalSwitch("隐藏上下黑边", UiText.get(R.string.settings_hide_video_black_edges), UiText.get(R.string.settings_hide_video_black_edges_summary),
                        "hide_video_black_edges");
                addLocalSwitch("禁用双击点赞", UiText.get(R.string.settings_disable_double_tap_like), "", "disable_double_tap_like");
                addLocalSwitch("不允许中途插入视频", UiText.get(R.string.settings_prevent_insert_video), UiText.get(R.string.settings_prevent_insert_video_summary), "prevent_insert_video");
                addNativeLongPressSwitch("拦截长按倍数启用场景", UiText.get(R.string.settings_block_long_press_speed), "block_long_press_speed");
                addNativeLongPressSwitch("拦截系统长按手势", UiText.get(R.string.settings_intercept_native_long_press), LocalOptions.INTERCEPT_NATIVE_LONG_PRESS);
                addOption("长按事件", UiText.get(R.string.settings_long_press_actions), "", null, true, () -> showGestureSettings(UiText.get(R.string.settings_long_press_actions), "long_press"));
                addOption("双击事件", UiText.get(R.string.settings_double_tap_actions), "", null, true, () -> showGestureSettings(UiText.get(R.string.settings_double_tap_actions), "double_tap"));
                addOption("沉浸式全屏", UiText.get(R.string.settings_fullscreen), "", null, true, this::showSystemBarSettings);
                addSwitch("隐藏顶部Tab开关", UiText.get(R.string.settings_hide_top_tabs), "",
                        repository.getBoolean("hide_top_tabs", false), true,
                        value -> repository.putBoolean("hide_top_tabs", value));
                addOption("设置顶部Tab", UiText.get(R.string.settings_configure_top_tabs), "", repository.getString("hidden_top_tabs", ""),
                        true, this::showTopTabs);
                addSwitch("首页刷新确认", UiText.get(R.string.settings_confirm_refresh), "",
                        repository.getBoolean("confirm_refresh", false), true,
                        value -> repository.putBoolean("confirm_refresh", value));
                buildCaptionControls();
                addLocalSwitch("关闭省流量模式", UiText.get(R.string.settings_disable_data_saver), UiText.get(R.string.settings_disable_data_saver_summary), "disable_data_saver");
                break;
            case FILTER:
                buildFeedFilters();
                break;
            case DOWNLOAD:
                buildDownloadControls();
                break;
            case AUTOPLAY:
                addSwitch("自动滑动到下一个视频", UiText.get(R.string.settings_auto_play), "",
                        repository.getBoolean("auto_play", false), true,
                        value -> repository.putBoolean("auto_play", value));
                buildPlaybackSpeedControls();
                addLocalSwitch("打开评论时,不滑动视频", UiText.get(R.string.settings_pause_auto_on_comments), "", "pause_auto_on_comments");
                addSwitch("滑动智能判断上下", UiText.get(R.string.settings_smart_swipe), "",
                        repository.getBoolean("smart_swipe", true), true,
                        value -> repository.putBoolean("smart_swipe", value));
                addOption("互动自动化", UiText.get(R.string.settings_interaction_automation), "", null, true, this::showAutomationSettings);
                break;
            case COMMENT:
                addLocalSwitch("评论自动添加后缀", UiText.get(R.string.settings_comment_append_suffix), "", "comment_append_suffix");
                addEditableOption("后缀内容", UiText.get(R.string.settings_comment_suffix), "", repository.getString("comment_suffix", ""),
                        false, true, false, value -> repository.putString("comment_suffix", value));
                addLocalSwitch("替换语音文件", UiText.get(R.string.settings_comment_voice_replace), "", "comment_voice_replace");
                addAudioPathOption("评论语音文件", UiText.get(R.string.settings_comment_voice_file), "comment_voice_path");
                addOption("评论转语音", UiText.get(R.string.settings_comment_speech), "", null, true, this::showCommentSpeechSettings);
                addOption("文本转语音设置", UiText.get(R.string.settings_speech_settings), UiText.get(R.string.settings_speech_settings_summary), null, true, this::showSpeechSettings);
                addLocalSwitch("#长按发送转语音（全局）", UiText.get(R.string.settings_hash_long_press_voice), UiText.get(R.string.settings_hash_long_press_voice_summary), "hash_long_press_voice");
                addCommentLongPressOption();
                addLocalSwitch("彩色评论", UiText.get(R.string.settings_colored_comment), "", "colored_comment");
                addChoiceOption("彩色评论类型", UiText.get(R.string.settings_colored_comment_type), "colored_comment_type", new String[]{UiText.get(R.string.settings_type_0), UiText.get(R.string.settings_type_33)},
                        new String[]{"0", "33"}, "0");
                break;
            case STREAK:
                addSwitch("自动续火花", UiText.get(R.string.settings_streak_enabled), "",
                        repository.getBoolean("streak_enabled", false), true, value -> {
                            repository.putBoolean("streak_enabled", value);
                            if (value) update(s -> withAutomation(s, true, null, null, null, null));
                        });
                addOption("选择用户", UiText.get(R.string.settings_select_users), "", repository.getString("streak_targets", ""),
                        true, () -> showTargetSelection("选择用户", UiText.get(R.string.settings_select_users), "streak_targets"));
                addEditableOption("续火花内容", UiText.get(R.string.settings_streak_content), "", repository.getString("streak_content", ""),
                        false, true, false, value -> repository.putString("streak_content", value));
                addLocalSwitch("续火花成功弹出Tip", UiText.get(R.string.settings_streak_tip), "", "streak_tip");
                addOption("重试结果未知的续火花", UiText.get(R.string.settings_streak_retry_unknown), "", null, true,
                        this::confirmUnknownStreakRetry);
                addOption("清空续火花记录", UiText.get(R.string.settings_streak_clear_records), "", null, true, () -> {
                    repository.putString("streak_reset_token", Long.toString(System.currentTimeMillis()));
                    android.widget.Toast.makeText(context, UiText.get(R.string.settings_streak_records_cleared), android.widget.Toast.LENGTH_SHORT).show();
                });
                addOption("续火花限制时间", UiText.get(R.string.settings_streak_window), "", null, true, this::showStreakWindow);
                addEditableOption("内容Api", UiText.get(R.string.settings_streak_content_api), "",
                        repository.getString("streak_content_api", ""), false, true, false,
                        value -> repository.putString("streak_content_api", value));
                break;
            case MESSAGE:
                addLocalSwitch("消息防撤回", UiText.get(R.string.settings_prevent_message_recall), "", "prevent_message_recall");
                addSwitch("自己撤回的消息", UiText.get(R.string.settings_recall_self), "", repository.getBoolean("recall_self", true), true,
                        value -> repository.putBoolean("recall_self", value));
                addSwitch("对方撤回的消息", UiText.get(R.string.settings_recall_other), "", repository.getBoolean("recall_other", true), true,
                        value -> repository.putBoolean("recall_other", value));
                addColorOption();
                addLocalSwitch("替换私聊语音", UiText.get(R.string.settings_dm_voice_replace), "", "dm_voice_replace");
                addAudioPathOption("私聊语音文件", UiText.get(R.string.settings_message_voice_file), "dm_voice_path");
                addLocalSwitch("自动文本转语音", UiText.get(R.string.settings_dm_tts),
                        UiText.get(R.string.settings_dm_tts_summary), "dm_tts");
                addOption("文本转语音设置", UiText.get(R.string.settings_speech_settings), UiText.get(R.string.settings_speech_settings_summary), null, true, this::showSpeechSettings);
                break;
            case SPOOF:
                addLocalSwitch("伪装总开关", UiText.get(R.string.settings_spoof_profile), "", "spoof_profile");
                addProfileCount("获赞数量", UiText.get(R.string.settings_profile_likes), "profile_like_count");
                addProfileCount("关注数量", UiText.get(R.string.settings_profile_following), "profile_following_count");
                addProfileCount("粉丝数量", UiText.get(R.string.settings_profile_followers), "profile_follower_count");
                addOption("版本信息", UiText.get(R.string.settings_version_info), "", null, true, this::showVersionSettings);
                break;
            case EXTENSION:
                addOption("AI 模型设置", UiText.get(R.string.settings_ai_settings), "", null, true, this::showAiModelSettings);
                addOption("文本转语音设置", UiText.get(R.string.settings_speech_settings), "", null, true, this::showSpeechSettings);
                addOption("本地数据分析", UiText.get(R.string.settings_local_analytics), "", null, true, this::showLocalAnalytics);
                addOption("配置备份", UiText.get(R.string.settings_config_backup), "", null, true, this::showConfigBackup);
                break;
            case ABOUT:
            case COMPONENT_HIDE:
            case ENVIRONMENT:
                break;
        }
    }

    private void showLocalAnalytics() {
        LocalAnalyticsDialog page = new LocalAnalyticsDialog(context, repository, miuixHome != null,
                analyticsDiagnostics);
        showOptionDialog(page.dialog(), page::close);
    }

    private void showConfigBackup() {
        ConfigTransferDialog page = new ConfigTransferDialog(context, repository, miuixHome != null,
                () -> settings = repository.load());
        showOptionDialog(page.dialog(), page::close);
    }

    private void showDebugLogs() {
        DebugLogDialog page = new DebugLogDialog(context, repository, miuixHome != null);
        showOptionDialog(page.dialog(), page::close);
    }

    private void showCommentSpeechSettings() {
        LinearLayout previous = content;
        LinearLayout panel = form();
        content = panel;
        try {
            addLocalSwitch("评论自动文本转语音", UiText.get(R.string.settings_comment_tts), UiText.get(R.string.settings_comment_tts_summary), "comment_tts");
        } finally { content = previous; }
        showOptionDialog(new MiuixDialogBuilder(context, miuixHome != null).setTitle(UiText.get(R.string.settings_comment_speech))
                .setView(scrolling(panel)).setPositiveButton(UiText.get(R.string.settings_done), null).create());
    }

    private void buildDownloadControls() {
        ToggleControl master = addSwitch("启用下载菜单", UiText.get(R.string.settings_download_enabled), "",
                settings.downloadEnabled, true, false, value -> update(s -> copy(s, value, null)));
        LinearLayout selectedControls = new LinearLayout(context);
        selectedControls.setOrientation(LinearLayout.VERTICAL);
        Context composeContext = miuixRowContext;
        Runnable refresh = () -> {
            LinearLayout previous = content;
            Context previousRowContext = miuixRowContext;
            content = selectedControls;
            miuixRowContext = composeContext;
            selectedControls.removeAllViews();
            try {
                if (ThirdPartyDownloadSettings.THIRD_PARTY.equals(repository.getString(
                        ThirdPartyDownloadSettings.METHOD, ConfigurationCatalog.DOWNLOAD_METHOD_DEFAULT))) {
                    buildThirdPartyDownloadControls(master);
                } else {
                    buildOfficialDownloadControls(master);
                }
            } finally {
                content = previous;
                miuixRowContext = previousRowContext;
            }
        };
        addChoiceOption("下载方式", UiText.get(R.string.settings_download_method), ThirdPartyDownloadSettings.METHOD,
                new String[]{UiText.get(R.string.settings_download_official), UiText.get(R.string.settings_download_third_party)},
                new String[]{"official", ThirdPartyDownloadSettings.THIRD_PARTY}, "official", refresh);
        OptionRow[] directory = new OptionRow[1];
        directory[0] = addOption("保存位置", UiText.get(R.string.settings_download_directory), "", HostDownloadDirectoryPicker.localizedDisplayName(
                repository.getString(HostDownloadDirectoryPicker.SETTINGS_KEY, "")), true,
                () -> showDownloadDirectory(directory[0]));
        addOption("下载任务", UiText.get(R.string.settings_download_tasks), "", null, true, () -> {
            DownloadTasksDialog tasks = new DownloadTasksDialog(context, miuixHome != null);
            showOptionDialog(tasks.dialog(), tasks::close);
        });
        content.addView(selectedControls, matchWrap());
        refresh.run();
    }

    private void showDownloadDirectory(OptionRow row) {
        showOptionDialog(new MiuixDialogBuilder(context, miuixHome != null).setTitle(UiText.get(R.string.settings_download_directory))
                .setItems(new String[]{UiText.get(R.string.settings_choose_folder), UiText.get(R.string.settings_reset_directory)}, (dialog, which) -> {
                    if (which == 1) {
                        repository.putString(HostDownloadDirectoryPicker.SETTINGS_KEY, "");
                        row.setValue(UiText.get(R.string.settings_default_directory));
                        return;
                    }
                    closeDownloadDirectoryPicker();
                    String configured = repository.getString(HostDownloadDirectoryPicker.SETTINGS_KEY, "");
                    downloadDirectoryPicker = HostDownloadDirectoryPicker.launch(findOwnerActivity(context),
                            configured.isEmpty() ? null : android.net.Uri.parse(configured), uri -> {
                                downloadDirectoryPicker = null;
                                if (uri == null) return;
                                repository.putString(HostDownloadDirectoryPicker.SETTINGS_KEY, uri.toString());
                                row.setValue(HostDownloadDirectoryPicker.localizedDisplayName(uri.toString()));
                            }, this::toast);
                    if (downloadDirectoryPicker == null) toast(UiText.get(R.string.settings_folder_picker_unavailable));
                }).setNegativeButton(UiText.get(R.string.settings_cancel), null).create());
    }

    private void closeDownloadDirectoryPicker() {
        if (downloadDirectoryPicker != null) downloadDirectoryPicker.close();
        downloadDirectoryPicker = null;
    }

    private void buildOfficialDownloadControls(ToggleControl master) {
        addSwitch("直接保存全部媒体", UiText.get(R.string.settings_download_direct), "",
                repository.getBoolean(LocalOptions.OFFICIAL_DOWNLOAD_DIRECT, false), true, value -> {
                    if (value) master.setChecked(true);
                    repository.putBoolean(LocalOptions.OFFICIAL_DOWNLOAD_DIRECT, value);
                });
        addSwitch("自定义文件名", UiText.get(R.string.settings_download_custom_name), "",
                repository.getBoolean("download_custom_name", false), true, value -> {
                    if (value) master.setChecked(true);
                    repository.putBoolean("download_custom_name", value);
                });
        addEditableOption("文件名模板", UiText.get(R.string.settings_download_name_template), "",
                repository.getString("download_name_template", ConfigurationCatalog.DOWNLOAD_NAME_DEFAULT),
                false, true, false, value -> repository.putString("download_name_template", value));
        addSwitch("去除下载限制", UiText.get(R.string.settings_remove_download_limits), "",
                repository.getBoolean("remove_download_limits", false), true, value -> {
                    if (value) master.setChecked(true);
                    repository.putBoolean("remove_download_limits", value);
                });
        addSwitch("无水印下载图集或者视频", UiText.get(R.string.settings_download_without_watermark),
                "",
                settings.removeWatermark, true, value -> update(s -> copy(s, null, value)));
    }

    private void buildThirdPartyDownloadControls(ToggleControl master) {
        addSwitch("直接保存全部媒体", UiText.get(R.string.settings_download_direct), "",
                repository.getBoolean(ThirdPartyDownloadSettings.DIRECT, false), true, value -> {
                    if (value) master.setChecked(true);
                    repository.putBoolean(ThirdPartyDownloadSettings.DIRECT, value);
                });
        ThirdPartyDownloadEditor editor = new ThirdPartyDownloadEditor(
                rowContext() == null ? context : rowContext(), repository,
                miuixHome != null, this::showOptionDialog);
        OptionRow[] request = new OptionRow[1];
        OptionRow[] parameters = new OptionRow[1];
        Runnable requestChanged = () -> {
            request[0].setValue(editor.valuePreview(ThirdPartyDownloadSettings.GET_TEMPLATE,
                    ThirdPartyDownloadSettings.DEFAULT_GET_TEMPLATE, false));
            parameters[0].setValue(editor.parametersPreview());
        };
        request[0] = addOption("GET 请求", UiText.get(R.string.settings_get_request), "", editor.valuePreview(ThirdPartyDownloadSettings.GET_TEMPLATE,
                ThirdPartyDownloadSettings.DEFAULT_GET_TEMPLATE, false), true,
                () -> editor.editValue(UiText.get(R.string.settings_get_request), ThirdPartyDownloadSettings.GET_TEMPLATE,
                        ThirdPartyDownloadSettings.DEFAULT_GET_TEMPLATE, false, requestChanged, "GET 请求"));
        parameters[0] = addOption("请求参数", UiText.get(R.string.settings_request_parameters), "", editor.parametersPreview(), true,
                () -> editor.editParameters(requestChanged));
        LinearLayout signatureControls = new LinearLayout(context);
        signatureControls.setOrientation(LinearLayout.VERTICAL);
        Context composeContext = miuixRowContext;
        Runnable refreshSignature = () -> {
            LinearLayout previous = content;
            Context previousRowContext = miuixRowContext;
            content = signatureControls;
            miuixRowContext = composeContext;
            signatureControls.removeAllViews();
            try {
                if (ThirdPartyDownloadSettings.SIGNATURE_MD5.equals(editor.signatureMode())) {
                    addThirdPartyValue(editor, "签名密钥", UiText.get(R.string.settings_signature_secret), ThirdPartyDownloadSettings.APP_SECRET, "", true);
                    addThirdPartyValue(editor, "签名请求头", UiText.get(R.string.settings_signature_header), ThirdPartyDownloadSettings.SIGNATURE_HEADER, "Sign", false);
                }
            } finally {
                content = previous;
                miuixRowContext = previousRowContext;
            }
        };
        OptionRow[] signature = new OptionRow[1];
        signature[0] = addOption("签名方式", UiText.get(R.string.settings_signature_method), "", editor.signaturePreview(), true,
                () -> editor.chooseSignature(() -> {
                    signature[0].setValue(editor.signaturePreview());
                    requestChanged.run();
                    refreshSignature.run();
                }));
        content.addView(signatureControls, matchWrap());
        refreshSignature.run();
        addThirdPartyValue(editor, "请求头", UiText.get(R.string.settings_request_headers), ThirdPartyDownloadSettings.HEADERS, "{}", false);
        OptionRow[] sample = new OptionRow[1];
        sample[0] = addOption("响应样本", UiText.get(R.string.settings_sample_response), "", editor.samplePreview(), true,
                () -> editor.editSample(() -> sample[0].setValue(editor.samplePreview())));
        OptionRow[] fields = new OptionRow[1];
        fields[0] = addOption("下载字段", UiText.get(R.string.settings_download_fields), "", editor.fieldsPreview(false), true,
                () -> editor.chooseDownloadFields(() -> fields[0].setValue(editor.fieldsPreview(false))));
        OptionRow[] names = new OptionRow[1];
        names[0] = addOption("文件命名字段", UiText.get(R.string.settings_filename_fields), "", editor.fieldsPreview(true), true,
                () -> editor.chooseNamingFields(() -> names[0].setValue(editor.fieldsPreview(true))));
        addOption("测试 GET", UiText.get(R.string.settings_test_get), "", null, true,
                () -> editor.testGet(() -> sample[0].setValue(editor.samplePreview())));
    }

    private void addThirdPartyValue(ThirdPartyDownloadEditor editor, String stableTitle, String title,
                                    String key, String fallback, boolean secret) {
        OptionRow[] preview = new OptionRow[1];
        preview[0] = addOption(stableTitle, title, "", editor.valuePreview(key, fallback, secret), true,
                () -> editor.editValue(title, key, fallback, secret,
                        () -> preview[0].setValue(editor.valuePreview(key, fallback, secret)), stableTitle));
    }

    private void buildFeedFilters() {
        addSwitch("过滤总开关", UiText.get(R.string.settings_filter_enabled), "", settings.feedFilterEnabled, true,
                false, value -> {
                    update(s -> withFeedEnabled(s, value));
                    setFeedFilterControlsEnabled(value);
                });
        addSwitch("整页筛空时保留原列表", UiText.get(R.string.settings_filter_keep_empty), "",
                repository.getBoolean("filter_interrupt", true), settings.feedFilterEnabled, true,
                value -> repository.putBoolean("filter_interrupt", value));
        addSwitch("去广告开关", UiText.get(R.string.settings_hide_ads), "", settings.hideAds, settings.feedFilterEnabled,
                true, value -> update(s -> withFilter(s, value, Filter.AD)));
        addSwitch("去直播开关", UiText.get(R.string.settings_hide_lives), "", settings.hideLives, settings.feedFilterEnabled,
                true, value -> update(s -> withFilter(s, value, Filter.LIVE)));
        addSwitch("去推荐卡片开关", UiText.get(R.string.settings_hide_cards), UiText.get(R.string.settings_hide_cards_summary), settings.hideRecommendationCards,
                settings.feedFilterEnabled, true, value -> update(s -> withFilter(s, value, Filter.CARD)));
        addSwitch("去小游戏开关", UiText.get(R.string.settings_hide_mini_games), UiText.get(R.string.settings_hide_mini_games_summary),
                repository.getBoolean("hide_mini_games", false), settings.feedFilterEnabled, true,
                value -> repository.putBoolean("hide_mini_games", value));
        addSwitch("去长视频开关", UiText.get(R.string.settings_hide_long_videos), UiText.get(R.string.settings_hide_long_videos_summary),
                repository.getBoolean("hide_long_videos", false), settings.feedFilterEnabled, true,
                value -> repository.putBoolean("hide_long_videos", value));
        addEditableOption("最长时长（秒）", UiText.get(R.string.settings_maximum_duration), "",
                repository.getString("maximum_duration_seconds", "120"), true,
                settings.feedFilterEnabled, true,
                value -> repository.putString("maximum_duration_seconds", value));
        addEditableOption("最低点赞数（0不限制）", UiText.get(R.string.settings_minimum_likes), "", settings.minimumLikesEnabled
                        ? Integer.toString(settings.minimumLikeCount) : "0",
                true, settings.feedFilterEnabled, true,
                value -> update(s -> withLikes(withFilter(s, parseNonNegative(value) > 0, Filter.LIKES),
                        parseNonNegative(value))));
        addSwitch("去图集开关", UiText.get(R.string.settings_hide_image_posts), "", settings.hideImagePosts,
                settings.feedFilterEnabled, true,
                value -> update(s -> withFilter(s, value, Filter.IMAGE)));
        addEditableOption("屏蔽关键词", UiText.get(R.string.settings_blocked_keywords), "",
                settings.keyword, false, settings.feedFilterEnabled, true,
                value -> update(s -> withKeyword(withFilter(s, !value.trim().isEmpty(), Filter.KEYWORD), value)));
        addSwitch("过滤短视频", UiText.get(R.string.settings_filter_short_videos), "", settings.minimumDurationEnabled,
                settings.feedFilterEnabled, true,
                value -> update(s -> withFilter(s, value, Filter.DURATION)));
        addEditableOption("最短时长（秒）", UiText.get(R.string.settings_minimum_duration), "", Integer.toString(settings.minimumDurationSeconds),
                true, settings.feedFilterEnabled, true,
                value -> update(s -> withDuration(s, parseNonNegative(value))));
        setFeedFilterControlsEnabled(settings.feedFilterEnabled);
    }

    private void showProfileSelection(String stableTitle, String title, java.util.Map<String, ProfileListEntry> available,
                                      java.util.Set<String> selected, java.util.function.Consumer<java.util.Set<String>> saved,
                                      Runnable manual) {
        java.util.Map<String, ProfileListEntry> entries = new java.util.LinkedHashMap<>(available);
        for (String uid : selected) if (!entries.containsKey(uid)) {
            entries.put(uid, "选择群成员".equals(stableTitle) && !uid.matches("[1-9][0-9]{0,19}")
                    ? ProfileListEntry.named(uid) : new ProfileListEntry(uid, "", ""));
        }
        MemberListView list = new MemberListView(miuixHome != null ? rowContext() : context, miuixHome != null,
                new ArrayList<>(entries.values()), selected, true);
        MiuixDialogBuilder builder = new MiuixDialogBuilder(context, miuixHome != null);
        builder.setTitle(title).setView(list).setNegativeButton(UiText.get(R.string.settings_cancel), null)
                .setPositiveButton(UiText.get(R.string.settings_save), (dialog, which) -> saved.accept(list.selected()));
        if (manual != null) builder.setNeutralButton(UiText.get(R.string.settings_enter_uid), (dialog, which) ->
                new android.os.Handler(android.os.Looper.getMainLooper()).post(manual));
        showOptionDialog(builder.create(), list::close);
    }

    private void addUnavailable(String detail) {
        TextView notice = text(detail, 14, MUTED, false);
        notice.setPadding(dp(8), dp(18), dp(8), dp(18));
        content.addView(notice, matchWrap());
    }

    private void addUnavailableSwitch(String stableTitle, String title, String summary) {
        addSwitch(stableTitle, title, summary, false, false, value -> { });
    }

    private void addLocalSwitch(String stableTitle, String title, String summary, String key) {
        String unavailable = com.tokai.module.scan.HostFeatureSupport.settingUnavailableReason(key);
        if (!unavailable.isEmpty()) {
            addSwitch(stableTitle, title, unavailable, repository.getBoolean(key, false), false, value -> { });
            return;
        }
        addSwitch(stableTitle, title, summary, repository.getBoolean(key, false), true,
                value -> repository.putBoolean(key, value));
    }

    private void buildPlaybackSpeedControls() {
        ToggleControl[] automatic = new ToggleControl[1];
        Runnable refresh = () -> {
            boolean moduleOnly = repository.getBoolean(LocalOptions.MODULE_ONLY_SPEED, false);
            automatic[0].setEnabled(!moduleOnly);
            automatic[0].setSummary(moduleOnly ? UiText.get(R.string.settings_module_only_manual_priority) : "");
        };
        addSwitch("仅模块控制倍速", UiText.get(R.string.settings_module_only_speed), UiText.get(R.string.settings_module_only_speed_summary),
                repository.getBoolean(LocalOptions.MODULE_ONLY_SPEED, false), true, value -> {
                    repository.putBoolean(LocalOptions.MODULE_ONLY_SPEED, value);
                    refresh.run();
                });
        automatic[0] = addSwitch("自动倍速播放", UiText.get(R.string.settings_auto_speed), "", repository.getBoolean("auto_speed", false), true,
                false, value -> {
                    if (!repository.getBoolean(LocalOptions.MODULE_ONLY_SPEED, false)) {
                        repository.putBoolean("auto_speed", value);
                    }
                });
        addChoiceOption("倍速数值", UiText.get(R.string.settings_playback_speed), ConfigurationCatalog.PLAYBACK_SPEED, PlaybackSpeedPresets.labels(),
                PlaybackSpeedPresets.values(), PlaybackSpeedPresets.DEFAULT, null,
                ModuleSettingsScreen::effectivePlaybackSpeed);
        refresh.run();
    }

    private void buildCaptionControls() {
        ToggleControl[] controls = new ToggleControl[2];
        boolean[] binding = {true};
        Runnable[] refresh = new Runnable[1];
        java.util.function.BiConsumer<String, Boolean> save = (key, value) -> {
            if (binding[0] || closed) return;
            try {
                java.util.Map<String, ?> before = repository.snapshot();
                java.util.Map<String, Object> next = new java.util.HashMap<>(before);
                LocalOptions effective = new LocalOptions(before);
                next.put(CaptionVisibilitySettings.HIDE, effective.enabled(CaptionVisibilitySettings.HIDE));
                next.put(CaptionVisibilitySettings.FORCE, effective.enabled(CaptionVisibilitySettings.FORCE));
                next.put(key, value);
                if (value) next.put(CaptionVisibilitySettings.HIDE.equals(key)
                        ? CaptionVisibilitySettings.FORCE : CaptionVisibilitySettings.HIDE, false);
                repository.replaceSnapshot(before, next);
            } catch (RuntimeException failure) {
                com.tokai.module.diagnostics.TokAiLog.failure("settings", "caption_visibility", failure);
                android.widget.Toast.makeText(context, UiText.get(R.string.settings_caption_save_failed), android.widget.Toast.LENGTH_SHORT).show();
            } finally {
                if (refresh[0] != null) refresh[0].run();
            }
        };
        boolean hiding = repository.getBoolean(CaptionVisibilitySettings.HIDE, false);
        controls[0] = addSwitch("强制展示字幕", UiText.get(R.string.settings_force_captions), UiText.get(R.string.settings_force_captions_summary),
                !hiding && repository.getBoolean(CaptionVisibilitySettings.FORCE, false), true, false,
                value -> save.accept(CaptionVisibilitySettings.FORCE, value));
        controls[1] = addSwitch("关闭字幕", UiText.get(R.string.settings_hide_captions), UiText.get(R.string.settings_hide_captions_summary),
                hiding, true, false, value -> save.accept(CaptionVisibilitySettings.HIDE, value));
        refresh[0] = () -> {
            binding[0] = true;
            try {
                boolean hide = repository.getBoolean(CaptionVisibilitySettings.HIDE, false);
                controls[0].setChecked(!hide && repository.getBoolean(CaptionVisibilitySettings.FORCE, false));
                controls[1].setChecked(hide);
            } finally { binding[0] = false; }
        };
        refresh[0].run();
    }

    private void addNativeLongPressSwitch(String stableTitle, String title, String key) {
        boolean moduleOnly = repository.getBoolean(LocalOptions.MODULE_ONLY_SPEED, false);
        addSwitch(stableTitle, title, moduleOnly ? UiText.get(R.string.settings_module_only_activates) : "",
                moduleOnly || repository.getBoolean(key, false), !moduleOnly,
                value -> {
                    if (!repository.getBoolean(LocalOptions.MODULE_ONLY_SPEED, false)) repository.putBoolean(key, value);
                });
    }

    private void addProfileCount(String stableTitle, String title, String key) {
        String unavailable = com.tokai.module.scan.HostFeatureSupport.settingUnavailableReason(key);
        addEditableOption(stableTitle, title, unavailable, repository.getString(key, ""),
                true, unavailable.isEmpty(), false, value -> {
                    if (unavailable.isEmpty()) repository.putString(key, value);
                });
    }

    private void addColorOption() {
        String value = repository.getString("recall_background", "#2051BA55");
        OptionRow[] colorRow = new OptionRow[1];
        OptionRow row = addOption("撤回的背景色", UiText.get(R.string.settings_recall_background), "", null, true, () -> {
            LinearLayout form = form();
            LinearLayout colors = new LinearLayout(context);
            for (String color : new String[]{"#2051BA55", "#2066A3FF", "#20F6C344", "#20F06A7D", "#00000000"}) {
                View swatch = new View(context);
                swatch.setBackground(makeCircle(Color.parseColor(color)));
                ModuleTheme.preserveColors(swatch);
                swatch.setContentDescription(color);
                swatch.setTooltipText(color);
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(40), 1);
                params.rightMargin = colors.getChildCount() < 4 ? dp(8) : 0;
                colors.addView(swatch, params);
                swatch.setOnClickListener(ignored -> {
                    repository.putString("recall_background", color);
                    colorRow[0].setColor(Color.parseColor(color));
                });
            }
            form.addView(colors, matchWrap());
            EditText input = formInput(form, "颜色值", UiText.get(R.string.settings_color_value), repository.getString("recall_background", "#2051BA55"), false);
            showOptionDialog(new MiuixDialogBuilder(context, miuixHome != null).setTitle(UiText.get(R.string.settings_recall_background)).setView(form)
                    .setNegativeButton(UiText.get(R.string.settings_cancel), null).setPositiveButton(UiText.get(R.string.settings_save), (dialog, which) -> {
                        try {
                            Color.parseColor(input.getText().toString().trim());
                            repository.putString("recall_background", input.getText().toString().trim());
                            colorRow[0].setColor(Color.parseColor(input.getText().toString().trim()));
                        } catch (IllegalArgumentException failure) {
                            android.widget.Toast.makeText(context, UiText.get(R.string.settings_invalid_color), android.widget.Toast.LENGTH_SHORT).show();
                        }
                    }).create());
        });
        colorRow[0] = row;
        try { row.setColor(Color.parseColor(value)); }
        catch (IllegalArgumentException ignored) { row.setColor(Color.parseColor("#2051BA55")); }
    }

    private void addCommentLongPressOption() {
        String[] labels = {UiText.get(R.string.settings_copy_comment), UiText.get(R.string.settings_save_comment_image), UiText.get(R.string.settings_copy_source_data)};
        String[] keys = {"copy_comment_text", "comment_media_tools", "copy_source_data"};
        java.util.function.Supplier<String> selection = () -> {
            List<String> selected = new ArrayList<>();
            for (int index = 0; index < keys.length; index++) {
                if (repository.getBoolean(keys[index], false)) selected.add(labels[index]);
            }
            return selected.isEmpty() ? UiText.get(R.string.settings_not_enabled) : String.join(UiText.get(R.string.settings_selection_separator), selected);
        };
        OptionRow[] row = new OptionRow[1];
        row[0] = addOption("长按操作", UiText.get(R.string.settings_comment_long_press), UiText.get(R.string.settings_comment_long_press_summary), selection.get(), true, () -> {
            boolean[] enabled = new boolean[keys.length];
            for (int index = 0; index < keys.length; index++) {
                enabled[index] = repository.getBoolean(keys[index], false);
            }
            AlertDialog choices = new MiuixDialogBuilder(context, miuixHome != null).setTitle(UiText.get(R.string.settings_comment_long_press))
                    .setMultiChoiceItems(labels, enabled, (dialog, which, value) -> {
                        if (!closed && ((Dialog) dialog).isShowing()) repository.putBoolean(keys[which], value);
                    })
                    .setPositiveButton(UiText.get(R.string.settings_done), null).create();
            showOptionDialog(choices, () -> row[0].setValue(selection.get()));
        });
    }

    private void addUnavailableOption(String stableTitle, String title, String summary) {
        View row = addOption(stableTitle, title, summary, null, false, () -> { }).view;
        row.setAlpha(0.65f);
        row.setContentDescription(UiText.get(R.string.settings_unavailable_label, title));
    }

    private void showAiModelSettings() {
        LinearLayout previous = content;
        LinearLayout panel = new LinearLayout(context);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(16), dp(8), dp(16), dp(8));
        content = panel;
        addOption("添加模型配置", UiText.get(R.string.settings_ai_add_config), "", null, true, this::showAiPresetPicker);
        addOption("测试当前配置", UiText.get(R.string.settings_ai_test_config), "", null, true, this::showAiChat);
        addSectionLabel(UiText.get(R.string.settings_ai_configured_models));
        AiModelSettings settings = aiSettings();
        java.util.Map<Button, String> activationButtons = new java.util.LinkedHashMap<>();
        if (settings.configs.isEmpty()) addUnavailable(UiText.get(R.string.settings_not_configured));
        for (AiModelConfig config : settings.configs) {
            LinearLayout row = new LinearLayout(context);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(8), dp(4), dp(4), dp(4));
            if (miuixHome != null) row.setOrientation(LinearLayout.VERTICAL);
            TextView title = text(config.name, 15, TEXT, true);
            title.setTag("ai.profile." + config.id);
            TextView summary = text(UiText.get(R.string.settings_pair_format, config.modelName.isEmpty() ? UiText.get(R.string.settings_ai_no_model) : config.modelName, protocolName(config.protocol)), 12, MUTED, false);
            LinearLayout details = new LinearLayout(context);
            details.setOrientation(LinearLayout.VERTICAL);
            details.setTag("ai.edit." + config.id);
            details.addView(title, matchWrap());
            details.addView(summary, matchWrap());
            details.setOnClickListener(ignored -> showAiModelEditor(config));
            View modelDetails = details;
            if (miuixHome != null) {
                modelDetails = new MiuixSettingsViews.Option(rowContext(), config.name,
                        summary.getText().toString(), null, true, () -> showAiModelEditor(config), config.name).getView();
                modelDetails.setTag("ai.edit." + config.id);
            }
            row.addView(modelDetails, miuixHome == null
                    ? new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1)
                    : matchWrap());
            Button activate = formButton();
            boolean active = config.id.equals(settings.activeId);
            activate.setText(active ? UiText.get(R.string.settings_enabled) : UiText.get(R.string.settings_enable));
            activate.setEnabled(!active);
            activate.setTag("ai.activate." + config.id);
            activationButtons.put(activate, config.id);
            activate.setOnClickListener(ignored -> {
                try {
                    AiModelSettings next = aiSettings().activate(config.id);
                    saveAiSettings(next);
                    for (java.util.Map.Entry<Button, String> entry : activationButtons.entrySet()) {
                        boolean selected = next.activeId.equals(entry.getValue());
                        entry.getKey().setText(selected ? UiText.get(R.string.settings_enabled) : UiText.get(R.string.settings_enable));
                        entry.getKey().setEnabled(!selected);
                    }
                } catch (RuntimeException failure) { toast(safeError(failure)); }
            });
            row.addView(MiuixFormControls.viewFor(activate), miuixHome == null
                    ? new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44))
                    : matchWrap());
            content.addView(row, matchWrap());
            addDivider();
        }
        content = previous;
        ScrollView scroll = new ScrollView(context);
        scroll.addView(panel);
        showOptionDialog(new MiuixDialogBuilder(context, miuixHome != null).setTitle(UiText.get(R.string.settings_ai_settings))
                .setView(scroll).setNegativeButton(UiText.get(R.string.settings_back), null).create());
    }

    private AiModelSettings aiSettings() {
        try { return AiModelSettings.read(repository); }
        catch (RuntimeException failure) {
            toast(UiText.get(R.string.settings_ai_read_failed, failure.getMessage()));
            return new AiModelSettings(java.util.Collections.emptyList(), "");
        }
    }

    private void saveAiSettings(AiModelSettings settings) {
        settings.save(repository);
    }

    private static String providerLabel(AiProviderPresets.Preset preset) {
        switch (preset.id) {
            case "custom": return UiText.get(R.string.settings_provider_custom);
            case "qwen": return UiText.get(R.string.settings_provider_qwen);
            case "siliconflow": return UiText.get(R.string.settings_provider_siliconflow);
            case "zhipu": return UiText.get(R.string.settings_provider_zhipu);
            case "doubao": return UiText.get(R.string.settings_provider_doubao);
            default: return preset.name;
        }
    }

    private void showAiPresetPicker() {
        String[] labels = new String[AiProviderPresets.ALL.size()];
        for (int i = 0; i < labels.length; i++) labels[i] = providerLabel(AiProviderPresets.ALL.get(i));
        showOptionDialog(new MiuixDialogBuilder(context, miuixHome != null).setTitle(UiText.get(R.string.settings_ai_choose_provider))
                .setItems(labels, (dialog, index) -> {
                    AiProviderPresets.Preset preset = AiProviderPresets.ALL.get(index);
                    try {
                        JSONObject json = new JSONObject().put("id", java.util.UUID.randomUUID().toString())
                                .put("name", preset.name).put("provider", preset.id)
                                .put("protocol", preset.protocol).put("apiEndpoint", preset.apiEndpoint);
                        AiModelConfig draft = AiModelConfig.fromJson(json);
                        new android.os.Handler(android.os.Looper.getMainLooper()).post(
                                () -> showAiModelEditor(draft));
                    } catch (org.json.JSONException failure) { toast(UiText.get(R.string.settings_ai_create_failed)); }
                }).setNegativeButton(UiText.get(R.string.settings_cancel), null).create());
    }

    private EditText formInput(LinearLayout parent, String stableLabel, String label, String value, boolean secret) {
        TextView heading = text(label, 14, TEXT, false);
        LinearLayout.LayoutParams headingParams = matchWrap();
        headingParams.topMargin = dp(10);
        parent.addView(heading, headingParams);
        EditText input = miuixHome == null ? new EditText(context)
                : MiuixFormControls.input(context, label, value, secret, stableLabel);
        input.setContentDescription(label);
        input.setText(value);
        input.setTextSize(14);
        input.setMaxLines(secret ? 1 : 4);
        input.setInputType(InputType.TYPE_CLASS_TEXT | (secret
                ? InputType.TYPE_TEXT_VARIATION_PASSWORD : InputType.TYPE_TEXT_FLAG_MULTI_LINE));
        input.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(4000)});
        parent.addView(MiuixFormControls.viewFor(input), matchWrap());
        return input;
    }

    private LinearLayout form() {
        LinearLayout form = new LinearLayout(context);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(20), dp(4), dp(20), dp(8));
        return form;
    }

    private ScrollView scrolling(View child) {
        ScrollView scroll = new ScrollView(context);
        scroll.addView(child);
        return scroll;
    }

    private void showAiModelEditor(AiModelConfig original) {
        AiModelEditor editor = new AiModelEditor(context, repository, original,
                miuixHome != null, this::showAiModelSettings);
        editor.show(this::showOptionDialog);
    }

    private static String protocolName(String protocol) {
        for (int i = 0; i < AiModelConfig.PROTOCOLS.length; i++) {
            if (AiModelConfig.PROTOCOLS[i].equals(protocol)) return AiModelConfig.PROTOCOL_LABELS[i];
        }
        return AiModelConfig.PROTOCOL_LABELS[0];
    }

    private static String safeError(Throwable failure) {
        String message = failure.getMessage();
        if (message == null) return failure.getClass().getSimpleName();
        return message.length() > 220 ? message.substring(0, 220) : message;
    }

    private void toast(String message) {
        android.widget.Toast.makeText(context, message == null ? UiText.get(R.string.settings_operation_failed) : message,
                android.widget.Toast.LENGTH_SHORT).show();
    }

    private void showAiChat() {
        try { showOptionDialog(new AiTestChatDialog(context, aiSettings().selected(), miuixHome != null)); }
        catch (RuntimeException failure) { toast(safeError(failure)); }
    }

    private void addChoiceOption(String stableTitle, String title, String key, String[] labels, String[] values, String fallback) {
        addChoiceOption(stableTitle, title, key, labels, values, fallback, null);
    }

    private void addChoiceOption(String stableTitle, String title, String key, String[] labels, String[] values, String fallback, Runnable changed) {
        addChoiceOption(stableTitle, title, key, labels, values, fallback, changed, value -> value);
    }

    private void addChoiceOption(String stableTitle, String title, String key, String[] labels, String[] values, String fallback,
                                 Runnable changed, java.util.function.UnaryOperator<String> effectiveValue) {
        String selected = effectiveValue.apply(repository.getString(key, fallback));
        int initial = java.util.Arrays.asList(values).indexOf(selected);
        int selectedIndex = Math.max(0, initial);
        OptionRow[] preview = new OptionRow[1];
        OptionRow row = addOption(stableTitle, title, "", labels[selectedIndex], true, () -> {
            int current = Math.max(0, java.util.Arrays.asList(values).indexOf(
                    effectiveValue.apply(repository.getString(key, fallback))));
            showOptionDialog(new MiuixDialogBuilder(context, miuixHome != null).setTitle(title)
                    .setSingleChoiceItems(labels, current, (dialog, which) -> {
                        repository.putString(key, values[which]);
                        preview[0].setValue(labels[which]);
                        dialog.dismiss();
                        if (changed != null) changed.run();
                    }).setNegativeButton(UiText.get(R.string.settings_cancel), null).create());
        });
        preview[0] = row;
    }

    private void withNativeContacts(boolean includeMembers, Runnable showSelection) {
        if (closed) return;
        java.util.concurrent.atomic.AtomicBoolean waiting = new java.util.concurrent.atomic.AtomicBoolean(true);
        AutoCloseable[] request = new AutoCloseable[1];
        AlertDialog progress = new MiuixDialogBuilder(context, miuixHome != null).setTitle(UiText.get(R.string.settings_loading_contacts_title))
                .setMessage(includeMembers ? UiText.get(R.string.settings_loading_group_members) : UiText.get(R.string.settings_loading_contacts))
                .setNegativeButton(UiText.get(R.string.settings_cancel), null).create();
        showOptionDialog(progress, () -> {
            if (waiting.getAndSet(false) && request[0] != null) {
                try { request[0].close(); } catch (Exception ignored) { }
            }
        });
        request[0] = NativeMessagingHooks.loadContacts(includeMembers, success -> {
            if (!waiting.getAndSet(false) || closed) return;
            progress.dismiss();
            if (success) showSelection.run();
            else showOptionDialog(new MiuixDialogBuilder(context, miuixHome != null).setTitle(UiText.get(R.string.settings_contacts_unavailable_title))
                    .setMessage(UiText.get(R.string.settings_contacts_unavailable_message))
                    .setPositiveButton(UiText.get(R.string.settings_continue_selection), (dialog, which) -> { if (!closed) showSelection.run(); })
                    .setNegativeButton(UiText.get(R.string.settings_back), null).create());
        });
    }

    private void showTargetSelection(String stableTitle, String title, String key) {
        withNativeContacts(false, () -> showTargetSelectionLoaded(stableTitle, title, key));
    }

    private void showTargetSelectionLoaded(String stableTitle, String title, String key) {
        java.util.Map<String, String> contacts = NativeMessagingHooks.availableContacts();
        if (contacts.isEmpty()) {
            showTargetIds(stableTitle, title, key);
            return;
        }
        java.util.Set<String> selected = com.tokai.module.feature.ReplyPolicy.targets(repository.getString(key, ""));
        showProfileSelection(stableTitle, title, NativeMessagingHooks.availableContactProfiles(), selected,
                next -> repository.putString(key, String.join(",", next)), () -> showTargetIds(stableTitle, title, key));
    }

    private void showTargetIds(String stableTitle, String title, String key) {
        LinearLayout form = form();
        EditText input = formInput(form, "用户 UID，以逗号分隔", UiText.get(R.string.settings_user_uids), repository.getString(key, ""), false);
        showOptionDialog(new MiuixDialogBuilder(context, miuixHome != null).setTitle(title).setView(form)
                .setNegativeButton(UiText.get(R.string.settings_cancel), null).setPositiveButton(UiText.get(R.string.settings_save), (dialog, which) ->
                        repository.putString(key, String.join(",", com.tokai.module.feature.ReplyPolicy.targets(input.getText().toString())))).create());
    }

    private void showStreakWindow() {
        LinearLayout form = form();
        EditText start = formInput(form, "开始时间", UiText.get(R.string.settings_start_time), repository.getString("streak_start", ""), false);
        EditText end = formInput(form, "结束时间", UiText.get(R.string.settings_end_time), repository.getString("streak_end", ""), false);
        AlertDialog dialog = new MiuixDialogBuilder(context, miuixHome != null).setTitle(UiText.get(R.string.settings_streak_window)).setView(form)
                .setNegativeButton(UiText.get(R.string.settings_cancel), null).setPositiveButton(UiText.get(R.string.settings_save), null).create();
        showOptionDialog(dialog);
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(ignored -> {
            String first = start.getText().toString().trim();
            String last = end.getText().toString().trim();
            try {
                if (!first.isEmpty() || !last.isEmpty()) {
                    java.time.LocalTime.parse(first);
                    java.time.LocalTime.parse(last);
                }
                repository.putString("streak_start", first);
                repository.putString("streak_end", last);
                dialog.dismiss();
            } catch (java.time.format.DateTimeParseException failure) { start.setError(UiText.get(R.string.settings_time_format_error)); }
        });
    }

    private void confirmUnknownStreakRetry() {
        Dialog dialog = new MiuixDialogBuilder(context, miuixHome != null)
                .setTitle(UiText.get(R.string.settings_streak_retry_unknown))
                .setMessage(UiText.get(R.string.settings_streak_retry_warning))
                .setNegativeButton(UiText.get(R.string.settings_cancel), null)
                .setPositiveButton(UiText.get(R.string.settings_retry_anyway), (ignored, which) -> repository.putString(
                        "streak_retry_unknown_token", java.util.UUID.randomUUID().toString()))
                .create();
        showOptionDialog(dialog);
    }

    private void addAudioPathOption(String stableTitle, String title, String key) {
        OptionRow[] row = new OptionRow[1];
        row[0] = addOption(stableTitle, title, UiText.get(R.string.settings_audio_path_summary), repository.getString(key, ""), true,
                () -> showAudioPathEditor(title, key, () -> {
                    String value = repository.getString(key, "");
                    row[0].setValue(value.isEmpty() ? UiText.get(R.string.settings_not_set) : value);
                }));
    }

    private void showAudioPathEditor(String title, String key, Runnable onSaved) {
        if (closed) return;
        LinearLayout form = form();
        EditText path = formInput(form, "文件路径", UiText.get(R.string.settings_file_path), repository.getString(key, ""), false);
        List<String> importedPaths = new ArrayList<>();
        boolean[] importing = {false};
        HostImagePicker[] picker = new HostImagePicker[1];
        Button pick = formButton();
        pick.setText(UiText.get(R.string.settings_choose_audio_file));
        AlertDialog editor = new MiuixDialogBuilder(context, miuixHome != null).setTitle(title).setView(form)
                .setNegativeButton(UiText.get(R.string.settings_cancel), null).setPositiveButton(UiText.get(R.string.settings_save), null).create();
        pick.setOnClickListener(ignored -> {
            if (!editor.isShowing() || closed || importing[0]) return;
            importing[0] = true;
            pick.setEnabled(false);
            pick.setText(UiText.get(R.string.settings_importing_audio));
            editor.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
            picker[0] = HostAudioPicker.launch(findOwnerActivity(context), imported -> {
                importing[0] = false;
                if (!editor.isShowing() || closed) {
                    if (imported != null) deleteImportedAudio(imported);
                    return;
                }
                pick.setEnabled(true);
                pick.setText(UiText.get(R.string.settings_choose_audio_file));
                editor.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
                if (imported == null) return;
                importedPaths.add(imported);
                path.setText(imported);
            });
            if (picker[0] == null) {
                importing[0] = false;
                pick.setEnabled(true);
                pick.setText(UiText.get(R.string.settings_choose_audio_file));
                editor.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
                android.widget.Toast.makeText(context, UiText.get(R.string.settings_audio_picker_unavailable), android.widget.Toast.LENGTH_SHORT).show();
            }
        });
        form.addView(MiuixFormControls.viewFor(pick), matchWrap());
        showOptionDialog(editor, () -> {
            if (picker[0] != null) picker[0].close();
            String saved = repository.getString(key, "");
            for (String imported : importedPaths) if (!imported.equals(saved)) deleteImportedAudio(imported);
        });
        editor.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(ignored -> {
            if (closed || !editor.isShowing() || importing[0]) return;
            repository.putString(key, path.getText().toString().trim());
            onSaved.run();
            editor.dismiss();
        });
    }

    private static void deleteImportedAudio(String path) {
        try { java.nio.file.Files.deleteIfExists(java.nio.file.Paths.get(path)); }
        catch (java.io.IOException | RuntimeException ignored) { }
    }

    private void showSpeechSettings() {
        SpeechSettingsDialog page = new SpeechSettingsDialog(context, repository, miuixHome != null, this::showOptionDialog);
        showOptionDialog(page.dialog(), page::close);
    }

    private static String[] gestureActionLabels() {
        return new String[]{UiText.get(R.string.settings_gesture_none), UiText.get(R.string.settings_gesture_menu), UiText.get(R.string.settings_gesture_copy_text), UiText.get(R.string.settings_gesture_save_media), UiText.get(R.string.settings_gesture_open_comments), UiText.get(R.string.settings_gesture_toggle_components), UiText.get(R.string.settings_gesture_speed), UiText.get(R.string.settings_gesture_view_finder)};
    }

    private static String gestureRegionLabel(int region) {
        int[] labels = {R.string.settings_region_top_left, R.string.settings_region_top_center, R.string.settings_region_top_right, R.string.settings_region_middle_left, R.string.settings_region_middle_center, R.string.settings_region_middle_right, R.string.settings_region_bottom_left, R.string.settings_region_bottom_center, R.string.settings_region_bottom_right};
        return UiText.get(labels[region]);
    }

    private void showGestureSettings(String title, String prefix) {
        if (miuixHome != null) {
            String[] initial = new String[9];
            for (int region = 0; region < initial.length; region++) {
                String action = FeedGestureToolsHook.regionAction(gestureOptions(prefix), prefix, region);
                int index = Math.max(0, java.util.Arrays.asList(FeedGestureToolsHook.ACTION_IDS).indexOf(action));
                initial[region] = gestureActionLabels()[index];
            }
            MiuixExtraControls.GestureGrid[] grid = new MiuixExtraControls.GestureGrid[1];
            grid[0] = MiuixExtraControls.gestureGrid(rowContext(), prefix, initial, region -> {
                String key = prefix + "_" + FeedGestureToolsHook.REGION_IDS[region];
                String action = FeedGestureToolsHook.regionAction(gestureOptions(prefix), prefix, region);
                int selected = Math.max(0, java.util.Arrays.asList(FeedGestureToolsHook.ACTION_IDS).indexOf(action));
                showOptionDialog(new MiuixDialogBuilder(context, true)
                        .setTitle(UiText.get(R.string.settings_pair_format, title, gestureRegionLabel(region)))
                        .setSingleChoiceItems(gestureActionLabels(), selected, (dialog, which) -> {
                            repository.putString(key, FeedGestureToolsHook.ACTION_IDS[which]);
                            grid[0].setAction(region, gestureActionLabels()[which]);
                            dialog.dismiss();
                        }).setNegativeButton(UiText.get(R.string.settings_cancel), null).create());
            });
            showOptionDialog(new MiuixDialogBuilder(context, true).setTitle(title).setView(scrolling(grid[0].getView()))
                    .setNegativeButton(UiText.get(R.string.settings_back), null).create());
            return;
        }
        LinearLayout panel = form();
        panel.setPadding(dp(12), dp(8), dp(12), dp(8));
        LinearLayout grid = new LinearLayout(context);
        grid.setOrientation(LinearLayout.VERTICAL);
        grid.setTag("settings.gesture.grid." + prefix);
        int cellHeight = dp(Math.round(96 * Math.max(1f,
                context.getResources().getConfiguration().fontScale)));
        for (int row = 0; row < 3; row++) {
            LinearLayout cells = new LinearLayout(context);
            cells.setOrientation(LinearLayout.HORIZONTAL);
            for (int column = 0; column < 3; column++) {
                int region = row * 3 + column;
                String key = prefix + "_" + FeedGestureToolsHook.REGION_IDS[region];
                String label = gestureRegionLabel(region);
                LinearLayout cell = new LinearLayout(context);
                cell.setOrientation(LinearLayout.VERTICAL);
                cell.setGravity(Gravity.CENTER);
                cell.setPadding(dp(4), dp(8), dp(4), dp(8));
                cell.setBackground(makeRounded(Color.WHITE, LINE, dp(5)));
                cell.setTag("settings.gesture." + key);
                cell.setFocusable(true);
                TextView name = text(label, 14, TEXT, true);
                name.setGravity(Gravity.CENTER);
                cell.addView(name, matchWrap());
                TextView value = text("", 13, MUTED, false);
                value.setGravity(Gravity.CENTER);
                value.setTag("settings.gesture.value." + key);
                LinearLayout.LayoutParams valueParams = matchWrap();
                valueParams.topMargin = dp(5);
                cell.addView(value, valueParams);
                updateGestureCell(cell, value, label, prefix, region);
                cell.setOnClickListener(ignored -> {
                    String action = FeedGestureToolsHook.regionAction(gestureOptions(prefix), prefix, region);
                    int current = java.util.Arrays.asList(FeedGestureToolsHook.ACTION_IDS).indexOf(action);
                    showOptionDialog(new MiuixDialogBuilder(context, miuixHome != null).setTitle(UiText.get(R.string.settings_pair_format, title, label))
                            .setSingleChoiceItems(gestureActionLabels(), Math.max(0, current),
                                    (dialog, which) -> {
                                        repository.putString(key, FeedGestureToolsHook.ACTION_IDS[which]);
                                        updateGestureCell(cell, value, label, prefix, region);
                                        dialog.dismiss();
                                    }).setNegativeButton(UiText.get(R.string.settings_cancel), null).create());
                });
                LinearLayout.LayoutParams cellParams = new LinearLayout.LayoutParams(0, cellHeight, 1);
                if (column != 2) cellParams.rightMargin = dp(6);
                cells.addView(cell, cellParams);
            }
            LinearLayout.LayoutParams rowParams = matchWrap();
            if (row != 2) rowParams.bottomMargin = dp(6);
            grid.addView(cells, rowParams);
        }
        panel.addView(grid, matchWrap());
        showOptionDialog(new MiuixDialogBuilder(context, miuixHome != null).setTitle(title).setView(scrolling(panel))
                .setNegativeButton(UiText.get(R.string.settings_back), null).create());
    }

    private LocalOptions gestureOptions(String prefix) {
        java.util.Map<String, Object> values = new java.util.HashMap<>();
        List<String> suffixes = new ArrayList<>(java.util.Arrays.asList(FeedGestureToolsHook.REGION_IDS));
        java.util.Collections.addAll(suffixes, "left", "center", "right", "action");
        for (String suffix : suffixes) {
            String key = prefix + "_" + suffix;
            String value = repository.getString(key, null);
            if (value != null) values.put(key, value);
        }
        return new LocalOptions(values);
    }

    private void updateGestureCell(View cell, TextView preview, String label, String prefix, int region) {
        String action = FeedGestureToolsHook.regionAction(gestureOptions(prefix), prefix, region);
        int index = Math.max(0, java.util.Arrays.asList(FeedGestureToolsHook.ACTION_IDS).indexOf(action));
        String value = gestureActionLabels()[index];
        preview.setText(value);
        cell.setContentDescription(UiText.get(R.string.settings_accessibility_pair, label, value));
    }

    private void showTopTabs() {
        java.util.Map<String, String> tabs = TopTabFilterHook.availableTabs();
        if (tabs.isEmpty()) {
            LinearLayout form = form();
            EditText input = formInput(form, "要隐藏的标签名称", UiText.get(R.string.settings_hidden_tab_names), repository.getString("hidden_top_tabs", ""), false);
            showOptionDialog(new MiuixDialogBuilder(context, miuixHome != null).setTitle(UiText.get(R.string.settings_configure_top_tabs)).setView(form)
                    .setNegativeButton(UiText.get(R.string.settings_cancel), null).setPositiveButton(UiText.get(R.string.settings_save), (dialog, which) ->
                            repository.putString("hidden_top_tabs", input.getText().toString().trim())).create());
            return;
        }
        String[] ids = tabs.keySet().toArray(new String[0]);
        String[] titles = tabs.values().toArray(new String[0]);
        java.util.Set<String> hidden = new java.util.HashSet<>();
        for (String value : repository.getString("hidden_top_tabs", "").split("[,，\\n]+")) hidden.add(value.trim());
        boolean[] checked = new boolean[ids.length];
        for (int index = 0; index < ids.length; index++) checked[index] = hidden.contains(ids[index]) || hidden.contains(titles[index]);
        AlertDialog dialog = new MiuixDialogBuilder(context, miuixHome != null).setTitle(UiText.get(R.string.settings_configure_top_tabs))
                .setMultiChoiceItems(titles, checked, (ignored, which, value) -> checked[which] = value)
                .setNegativeButton(UiText.get(R.string.settings_cancel), null).setPositiveButton(UiText.get(R.string.settings_save), null).create();
        showOptionDialog(dialog);
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(ignored -> {
            List<String> selected = new ArrayList<>();
            for (int index = 0; index < ids.length; index++) if (checked[index]) selected.add(ids[index]);
            if (selected.size() == ids.length) {
                android.widget.Toast.makeText(context, UiText.get(R.string.settings_keep_one_top_tab), android.widget.Toast.LENGTH_SHORT).show();
                return;
            }
            repository.putString("hidden_top_tabs", String.join(",", selected));
            dialog.dismiss();
        });
    }

    private void addSectionLabel(String title) {
        TextView label = text(title, 13, MUTED, false);
        label.setPadding(0, dp(18), 0, dp(8));
        content.addView(label, matchWrap());
    }

    private void showAutomationSettings() {
        AutomationSettingsDialog automation = new AutomationSettingsDialog(context, repository, miuixHome != null,
                () -> settings = repository.load());
        showOptionDialog(automation.dialog(), automation::close);
    }

    private void showSystemBarSettings() {
        LinearLayout previous = content;
        LinearLayout panel = form();
        content = panel;
        addSwitch("隐藏状态栏", UiText.get(R.string.settings_hide_status_bar), "", repository.getBoolean("remove_black_edges", false), true,
                value -> repository.putBoolean("remove_black_edges", value));
        addSwitch("隐藏导航栏", UiText.get(R.string.settings_hide_navigation_bar), "", repository.getBoolean("hide_navigation_bar", false), true,
                value -> repository.putBoolean("hide_navigation_bar", value));
        content = previous;
        showOptionDialog(new MiuixDialogBuilder(context, miuixHome != null).setTitle(UiText.get(R.string.settings_fullscreen)).setView(panel)
                .setNegativeButton(UiText.get(R.string.settings_back), null).create());
    }

    private void showVersionSettings() {
        LinearLayout previous = content;
        LinearLayout panel = form();
        content = panel;
        addSwitch("版本信息修改", UiText.get(R.string.settings_spoof_version), "",
                repository.getBoolean("spoof_version", false), true,
                value -> repository.putBoolean("spoof_version", value));
        addEditableOption("版本名称", UiText.get(R.string.settings_version_name), "", repository.getString("fake_version_name", ""),
                false, true, false, value -> repository.putString("fake_version_name", value));
        addEditableOption("版本号", UiText.get(R.string.settings_version_code), "", repository.getString("fake_version_code", ""),
                true, true, false, value -> repository.putString("fake_version_code", value));
        content = previous;
        showOptionDialog(new MiuixDialogBuilder(context, miuixHome != null).setTitle(UiText.get(R.string.settings_version_info)).setView(scrolling(panel))
                .setNegativeButton(UiText.get(R.string.settings_back), null).create());
    }

    private View addEditableOption(String stableTitle, String title, String summary, String value, boolean numeric,
                                   boolean enabled, boolean feedControl, ValueTextListener listener) {
        String[] current = {value};
        OptionRow[] preview = new OptionRow[1];
        OptionRow row = addOption(stableTitle, title, summary, value, enabled, () -> {
            EditText input = miuixHome == null ? new EditText(context)
                    : MiuixFormControls.input(context, title, current[0], false, stableTitle);
            input.setContentDescription(title);
            input.setTag("settings.input." + stableTitle);
            input.setText(current[0]);
            input.setInputType(numeric ? InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL
                    : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
            input.setMaxLines(numeric ? 1 : 6);
            input.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(400)});
            LinearLayout form = new LinearLayout(context);
            form.setPadding(dp(20), dp(8), dp(20), 0);
            form.addView(MiuixFormControls.viewFor(input), matchWrap());
            AlertDialog editor = new MiuixDialogBuilder(context, miuixHome != null).setTitle(title).setView(form)
                    .setNegativeButton(UiText.get(R.string.settings_cancel), null).setPositiveButton(UiText.get(R.string.settings_save), null).create();
            showOptionDialog(editor);
            editor.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(ignored -> {
                String candidate = input.getText().toString().trim();
                try {
                    listener.onValue(candidate);
                    current[0] = candidate;
                    preview[0].setValue(candidate.isEmpty() ? UiText.get(R.string.settings_not_set) : candidate);
                    editor.dismiss();
                } catch (RuntimeException failure) { toast(safeError(failure)); }
            });
        });
        preview[0] = row;
        if (feedControl) feedFilterControls.add(row);
        return row.view;
    }

    private OptionRow addOption(String stableTitle, String title, String summary, String value, boolean enabled, Runnable action) {
        Context rowContext = rowContext();
        if (rowContext != null) {
            MiuixSettingsViews.Option option = new MiuixSettingsViews.Option(
                    rowContext, title, summary, value, enabled, action, stableTitle);
            content.addView(option.getView(), matchWrap());
            return new OptionRow(option.getView(), option::setValue, option::setEnabled, option::setColor);
        }
        LinearLayout row = new LinearLayout(context);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(12), 0, dp(12));
        row.setMinimumHeight(dp(58));
        row.setTag("settings.option." + stableTitle);
        row.setContentDescription(title);
        row.setEnabled(enabled);
        row.setFocusable(true);
        LinearLayout labels = new LinearLayout(context);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.addView(text(title, 15, TEXT, false), matchWrap());
        if (summary != null && !summary.isEmpty()) {
            TextView detail = text(summary, 12, MUTED, false);
            LinearLayout.LayoutParams params = matchWrap();
            params.topMargin = dp(3);
            labels.addView(detail, params);
        }
        TextView preview = null;
        if (value != null) {
            preview = text(value.isEmpty() ? UiText.get(R.string.settings_not_set) : value, 13, MUTED, false);
            preview.setTag("settings.value." + stableTitle);
            preview.setMaxLines(3);
            preview.setEllipsize(android.text.TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams params = matchWrap();
            params.topMargin = dp(5);
            labels.addView(preview, params);
        }
        row.addView(labels, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView arrow = text("›", 24, MUTED, false);
        arrow.setGravity(Gravity.CENTER);
        row.addView(arrow, new LinearLayout.LayoutParams(dp(32), dp(32)));
        row.setOnClickListener(view -> { if (view.isEnabled()) action.run(); });
        content.addView(row, matchWrap());
        addDivider();
        TextView valuePreview = preview;
        View[] colorSwatch = new View[1];
        return new OptionRow(row, newValue -> {
            if (valuePreview != null) valuePreview.setText(newValue);
        }, row::setEnabled, color -> {
            if (colorSwatch[0] == null) {
                colorSwatch[0] = new View(context);
                ModuleTheme.preserveColors(colorSwatch[0]);
                labels.addView(colorSwatch[0], new LinearLayout.LayoutParams(dp(24), dp(24)));
            }
            colorSwatch[0].setBackground(makeCircle(color));
        });
    }

    private void showOptionDialog(Dialog dialog) {
        showOptionDialog(dialog, null);
    }

    private void showOptionDialog(Dialog dialog, Runnable onDismiss) {
        if (closed) { if (onDismiss != null) onDismiss.run(); return; }
        discardDismissedOptionDialogs();
        optionDialogs.add(dialog);
        if (onDismiss != null) optionDismissActions.put(dialog, onDismiss);
        dialog.setOwnerActivity(findOwnerActivity(context));
        dialog.setOnDismissListener(ignored -> {
            int index = optionDialogs.indexOf(dialog);
            if (index >= 0) {
                for (int child = optionDialogs.size() - 1; child > index; child--) {
                    Dialog childDialog = optionDialogs.get(child);
                    finishOptionDialog(childDialog);
                    childDialog.dismiss();
                }
                optionDialogs.remove(dialog);
            }
            finishOptionDialog(dialog);
        });
        dialog.show();
    }

    private void discardDismissedOptionDialogs() {
        // Dismiss callbacks can arrive after a sibling has already been opened.
        for (int index = 0; index < optionDialogs.size(); index++) {
            if (optionDialogs.get(index).isShowing()) continue;
            List<Dialog> closedBranch = new ArrayList<>(optionDialogs.subList(index, optionDialogs.size()));
            optionDialogs.removeAll(closedBranch);
            for (Dialog child : closedBranch) {
                finishOptionDialog(child);
                if (child.isShowing()) child.dismiss();
            }
            return;
        }
    }

    private void dismissOptionDialogs() {
        for (Dialog dialog : new ArrayList<>(optionDialogs)) {
            finishOptionDialog(dialog);
            dialog.dismiss();
        }
        optionDialogs.clear();
    }

    private void finishOptionDialog(Dialog dialog) {
        Runnable action = optionDismissActions.remove(dialog);
        if (action != null) action.run();
    }

    private void closeAutomationMessagePage() {
        if (automationMessagePage != null) automationMessagePage.close();
        automationMessagePage = null;
    }

    public void dismissChildPages() {
        clearSearchFocus();
        closeAutomationMessagePage();
        closeDownloadDirectoryPicker();
        dismissOptionDialogs();
        if (miuixComponentHidePage != null) miuixComponentHidePage.dismiss();
        miuixComponentHidePage = null;
        if (componentHidePage != null) componentHidePage.dismiss();
        componentHidePage = null;
        if (categoryDialog != null) categoryDialog.dismiss();
        categoryDialog = null;
    }

    public void close() {
        closed = true;
        dismissChildPages();
        if (miuixHome != null) miuixHome.close();
    }

    public void setScanStatus(ScanStatus status) {
        if (miuixHome != null) miuixHome.setScanStatus(status);
        if (scanTitle == null) return;
        scanTitle.setText(status.displayTitle());
        String detail = status.displayDetail();
        if (status.restartRequired) detail = UiText.get(R.string.settings_restart_required_detail, detail);
        scanDetail.setText(detail);
        scanMark.setBackground(makeCircle(status.ready ? ACCENT : Color.rgb(231, 151, 40)));
    }

    public void setFrameworkSyncStatus(boolean connected, String message, String error) {
        if (syncTitle == null) return;
        syncTitle.setText(connected ? UiText.get(R.string.settings_lsposed_connected) : UiText.get(R.string.settings_lsposed_disconnected));
        String detail = UiText.get(R.string.settings_settings_separate_notice);
        if (error != null && !error.isEmpty()) detail = UiText.get(R.string.settings_detail_with_error, detail, error);
        syncDetail.setText(detail);
        syncMark.setBackground(makeCircle(connected ? Color.rgb(0, 146, 117)
                : Color.rgb(198, 84, 67)));
    }

    private void addTitle(String title, String subtitle) {
        LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout labels = new LinearLayout(context);
        labels.setOrientation(LinearLayout.VERTICAL);
        TextView heading = text(title, 25, TEXT, true);
        labels.addView(heading, matchWrap());
        TextView sub = text(subtitle, 14, MUTED, false);
        LinearLayout.LayoutParams p = matchWrap();
        p.topMargin = dp(5);
        labels.addView(sub, p);
        header.addView(labels, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        if (onClose != null) {
            Button close = new Button(context);
            close.setText(UiText.get(R.string.settings_close));
            close.setOnClickListener(view -> onClose.run());
            header.addView(close, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        content.addView(header, matchWrap());
    }

    private void addHostSettingsNotice() {
        TextView notice = text(UiText.get(R.string.settings_host_settings_notice),
                13, MUTED, false);
        LinearLayout.LayoutParams p = matchWrap();
        p.topMargin = dp(12);
        content.addView(notice, p);
    }

    private void addIndependentSettingsNotice() {
        TextView notice = text(UiText.get(R.string.settings_independent_settings_notice),
                14, TEXT, false);
        notice.setPadding(dp(14), dp(14), dp(14), dp(14));
        notice.setBackground(makeRounded(Color.WHITE, LINE, dp(7)));
        LinearLayout.LayoutParams params = matchWrap();
        params.topMargin = dp(12);
        content.addView(notice, params);
    }

    private void addScanStatus(ScanStatus status) {
        LinearLayout box = new LinearLayout(context);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setPadding(dp(14), dp(14), dp(14), dp(14));
        box.setBackground(makeRounded(Color.WHITE, LINE, dp(7)));

        scanMark = new View(context);
        scanMark.setBackground(makeCircle(Color.rgb(231, 151, 40)));
        LinearLayout.LayoutParams markParams = new LinearLayout.LayoutParams(dp(9), dp(9));
        markParams.rightMargin = dp(12);
        box.addView(scanMark, markParams);

        LinearLayout labels = new LinearLayout(context);
        labels.setOrientation(LinearLayout.VERTICAL);
        scanTitle = text("", 15, TEXT, true);
        scanDetail = text("", 13, MUTED, false);
        labels.addView(scanTitle, matchWrap());
        LinearLayout.LayoutParams detailParams = matchWrap();
        detailParams.topMargin = dp(3);
        labels.addView(scanDetail, detailParams);
        box.addView(labels, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        LinearLayout.LayoutParams p = matchWrap();
        p.topMargin = dp(20);
        content.addView(box, p);
        setScanStatus(status);
    }

    private void addFrameworkSyncStatus() {
        LinearLayout box = new LinearLayout(context);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setPadding(dp(14), dp(13), dp(14), dp(13));
        box.setBackground(makeRounded(Color.WHITE, LINE, dp(7)));

        syncMark = new View(context);
        syncMark.setBackground(makeCircle(Color.rgb(198, 84, 67)));
        LinearLayout.LayoutParams markParams = new LinearLayout.LayoutParams(dp(9), dp(9));
        markParams.rightMargin = dp(12);
        box.addView(syncMark, markParams);

        LinearLayout labels = new LinearLayout(context);
        labels.setOrientation(LinearLayout.VERTICAL);
        syncTitle = text(UiText.get(R.string.settings_configuration_disconnected), 14, TEXT, true);
        syncDetail = text(UiText.get(R.string.settings_local_settings_notice), 12, MUTED, false);
        labels.addView(syncTitle, matchWrap());
        LinearLayout.LayoutParams detailParams = matchWrap();
        detailParams.topMargin = dp(3);
        labels.addView(syncDetail, detailParams);
        box.addView(labels, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        LinearLayout.LayoutParams p = matchWrap();
        p.topMargin = dp(8);
        content.addView(box, p);
    }

    private void addSwitch(String stableTitle, String title, String summary, boolean checked, boolean enabled,
                           ValueListener listener) {
        addSwitch(stableTitle, title, summary, checked, enabled, false, listener);
    }

    private ToggleControl addSwitch(String stableTitle, String title, String summary, boolean checked, boolean enabled,
                             boolean isFeedFilterControl, ValueListener listener) {
        Context rowContext = rowContext();
        if (rowContext != null) {
            MiuixSettingsViews.Toggle toggle = new MiuixSettingsViews.Toggle(
                    rowContext, title, summary, checked, enabled, listener::onValue, stableTitle);
            ToggleControl control = new ToggleControl(toggle::setChecked, toggle::setEnabled,
                    toggle::setOnLongClickListener, toggle::setSummary);
            content.addView(toggle.getView(), matchWrap());
            if (isFeedFilterControl) feedFilterControls.add(control);
            return control;
        }
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(10), 0, dp(10));

        LinearLayout labels = new LinearLayout(context);
        labels.setOrientation(LinearLayout.VERTICAL);
        TextView name = text(title, 15, TEXT, false);
        labels.addView(name, matchWrap());
        TextView description = text(summary == null ? "" : summary, 12, MUTED, false);
        description.setVisibility(summary == null || summary.isEmpty() ? View.GONE : View.VISIBLE);
        LinearLayout.LayoutParams descriptionParams = matchWrap();
        descriptionParams.topMargin = dp(3);
        labels.addView(description, descriptionParams);
        row.addView(labels, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        Switch toggle = new Switch(context);
        toggle.setChecked(checked);
        toggle.setEnabled(enabled);
        toggle.setTag("settings.toggle." + stableTitle);
        toggle.setContentDescription(title);
        if (isFeedFilterControl) feedFilterControls.add(toggle::setEnabled);
        toggle.setOnCheckedChangeListener((button, value) -> listener.onValue(value));
        row.addView(toggle, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        content.addView(row, matchWrap());
        addDivider();
        return new ToggleControl(toggle::setChecked, toggle::setEnabled, toggle::setOnLongClickListener, value -> {
            description.setText(value);
            description.setVisibility(value == null || value.isEmpty() ? View.GONE : View.VISIBLE);
        });
    }

    private void addTextSetting(String title, String value, String hint, boolean numeric,
                                boolean enabled, ValueTextListener listener) {
        addTextSetting(title, value, hint, numeric, enabled, false, listener);
    }

    private void addTextSetting(String title, String value, String hint, boolean numeric,
                                boolean enabled, boolean isFeedFilterControl,
                                ValueTextListener listener) {
        LinearLayout row = new LinearLayout(context);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(8), 0, dp(8));
        TextView label = text(title, 14, TEXT, false);
        row.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        EditText input = new EditText(context);
        input.setSingleLine(true);
        input.setContentDescription(title);
        input.setText(value);
        input.setHint(hint);
        input.setTextSize(14);
        input.setPadding(dp(10), dp(4), dp(10), dp(4));
        input.setBackground(makeRounded(Color.WHITE, LINE, dp(5)));
        input.setEnabled(enabled);
        if (isFeedFilterControl) feedFilterControls.add(input::setEnabled);
        input.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        input.setInputType(numeric ? InputType.TYPE_CLASS_NUMBER : InputType.TYPE_CLASS_TEXT);
        input.setEms(numeric ? 6 : 12);
        input.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(40)});
        input.setOnFocusChangeListener((view, focused) -> {
            if (!focused) listener.onValue(input.getText().toString());
        });
        input.setOnEditorActionListener((view, actionId, event) -> {
            listener.onValue(input.getText().toString());
            input.clearFocus();
            return false;
        });
        row.addView(input, new LinearLayout.LayoutParams(dp(numeric ? 105 : 170), dp(42)));
        content.addView(row, matchWrap());
    }

    private void addDivider() {
        addDividerTo(content);
    }

    private void addDividerTo(LinearLayout parent) {
        View divider = new View(context);
        divider.setBackgroundColor(LINE);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        parent.addView(divider, p);
    }

    private static Activity findOwnerActivity(Context context) {
        while (context instanceof ContextWrapper) {
            if (context instanceof Activity) return (Activity) context;
            Context next = ((ContextWrapper) context).getBaseContext();
            if (next == context) break;
            context = next;
        }
        return null;
    }

    private void update(SettingsTransform transform) {
        settings = transform.apply(settings);
        repository.save(settings);
    }

    private void setFeedFilterControlsEnabled(boolean enabled) {
        for (EnabledControl control : feedFilterControls) control.setEnabled(enabled);
    }

    private TextView text(String value, float size, int color, boolean bold) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    private Context rowContext() {
        if (miuixHome == null) return null;
        return miuixRowContext != null ? miuixRowContext : miuixHome.getView().getContext();
    }

    private Button formButton() {
        return miuixHome == null ? new Button(context) : MiuixFormControls.button(context);
    }

    private GradientDrawable makeRounded(int fill, int stroke, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        drawable.setCornerRadius(radius);
        drawable.setStroke(dp(1), stroke);
        return drawable;
    }

    private GradientDrawable makeCircle(int color) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setShape(GradientDrawable.OVAL);
        return drawable;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    private static String effectivePlaybackSpeed(String value) {
        return PlaybackSpeedPresets.normalize(value);
    }

    private static int parseNonNegative(String value) {
        try {
            return Math.max(0, Integer.parseInt(value.trim()));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private static FeatureSettings copy(FeatureSettings s, Boolean download, Boolean watermark) {
        return make(download == null ? s.downloadEnabled : download,
                watermark == null ? s.removeWatermark : watermark, s.feedFilterEnabled,
                s.hideAds, s.hideLives, s.hideImagePosts, s.hideRecommendationCards,
                s.hideRecommendationMerchants,
                s.keywordFilterEnabled, s.keyword, s.minimumDurationEnabled,
                s.minimumDurationSeconds, s.minimumLikesEnabled, s.minimumLikeCount,
                s.automationEnabled, s.autoLikeEnabled, s.autoFollowEnabled,
                s.autoCommentEnabled, s.autoMessageEnabled);
    }

    private static FeatureSettings withFeedEnabled(FeatureSettings s, boolean value) {
        return make(s.downloadEnabled, s.removeWatermark, value, s.hideAds, s.hideLives,
                s.hideImagePosts, s.hideRecommendationCards, s.hideRecommendationMerchants,
                s.keywordFilterEnabled, s.keyword,
                s.minimumDurationEnabled, s.minimumDurationSeconds, s.minimumLikesEnabled,
                s.minimumLikeCount, s.automationEnabled, s.autoLikeEnabled, s.autoFollowEnabled,
                s.autoCommentEnabled, s.autoMessageEnabled);
    }

    private static FeatureSettings withFilter(FeatureSettings s, boolean value, Filter filter) {
        boolean ads = filter == Filter.AD ? value : s.hideAds;
        boolean lives = filter == Filter.LIVE ? value : s.hideLives;
        boolean images = filter == Filter.IMAGE ? value : s.hideImagePosts;
        boolean cards = filter == Filter.CARD ? value : s.hideRecommendationCards;
        boolean keywordEnabled = filter == Filter.KEYWORD ? value : s.keywordFilterEnabled;
        boolean durationEnabled = filter == Filter.DURATION ? value : s.minimumDurationEnabled;
        boolean likesEnabled = filter == Filter.LIKES ? value : s.minimumLikesEnabled;
        return make(s.downloadEnabled, s.removeWatermark, s.feedFilterEnabled,
                ads, lives, images, cards, s.hideRecommendationMerchants, keywordEnabled, s.keyword, durationEnabled,
                s.minimumDurationSeconds, likesEnabled, s.minimumLikeCount,
                s.automationEnabled, s.autoLikeEnabled, s.autoFollowEnabled,
                s.autoCommentEnabled, s.autoMessageEnabled);
    }

    private static FeatureSettings withKeyword(FeatureSettings s, String value) {
        return make(s.downloadEnabled, s.removeWatermark, s.feedFilterEnabled,
                s.hideAds, s.hideLives, s.hideImagePosts, s.hideRecommendationCards,
                s.hideRecommendationMerchants,
                s.keywordFilterEnabled, value, s.minimumDurationEnabled, s.minimumDurationSeconds,
                s.minimumLikesEnabled, s.minimumLikeCount, s.automationEnabled, s.autoLikeEnabled,
                s.autoFollowEnabled, s.autoCommentEnabled, s.autoMessageEnabled);
    }

    private static FeatureSettings withDuration(FeatureSettings s, int value) {
        return make(s.downloadEnabled, s.removeWatermark, s.feedFilterEnabled,
                s.hideAds, s.hideLives, s.hideImagePosts, s.hideRecommendationCards,
                s.hideRecommendationMerchants,
                s.keywordFilterEnabled, s.keyword, s.minimumDurationEnabled, value,
                s.minimumLikesEnabled, s.minimumLikeCount, s.automationEnabled, s.autoLikeEnabled,
                s.autoFollowEnabled, s.autoCommentEnabled, s.autoMessageEnabled);
    }

    private static FeatureSettings withLikes(FeatureSettings s, int value) {
        return make(s.downloadEnabled, s.removeWatermark, s.feedFilterEnabled,
                s.hideAds, s.hideLives, s.hideImagePosts, s.hideRecommendationCards,
                s.hideRecommendationMerchants,
                s.keywordFilterEnabled, s.keyword, s.minimumDurationEnabled, s.minimumDurationSeconds,
                s.minimumLikesEnabled, value, s.automationEnabled, s.autoLikeEnabled,
                s.autoFollowEnabled, s.autoCommentEnabled, s.autoMessageEnabled);
    }

    private static FeatureSettings withAutomation(FeatureSettings s, Boolean master, Boolean like,
                                                  Boolean follow, Boolean comment, Boolean message) {
        return make(s.downloadEnabled, s.removeWatermark, s.feedFilterEnabled,
                s.hideAds, s.hideLives, s.hideImagePosts, s.hideRecommendationCards,
                s.hideRecommendationMerchants,
                s.keywordFilterEnabled, s.keyword, s.minimumDurationEnabled, s.minimumDurationSeconds,
                s.minimumLikesEnabled, s.minimumLikeCount,
                master == null ? s.automationEnabled : master,
                like == null ? s.autoLikeEnabled : like,
                follow == null ? s.autoFollowEnabled : follow,
                comment == null ? s.autoCommentEnabled : comment,
                message == null ? s.autoMessageEnabled : message);
    }

    private static FeatureSettings make(boolean download, boolean watermark, boolean feed,
                                       boolean ads, boolean lives, boolean images,
                                       boolean cards, boolean merchants, boolean keywordEnabled, String keyword,
                                       boolean durationEnabled, int duration, boolean likesEnabled,
                                       int likes, boolean automation, boolean autoLike,
                                       boolean autoFollow, boolean autoComment, boolean autoMessage) {
        return new FeatureSettings(download, watermark, feed, ads, lives, images, cards, merchants,
                keywordEnabled, keyword, durationEnabled, duration, likesEnabled, likes,
                automation, autoLike, autoFollow, autoComment, autoMessage);
    }

    private static FeatureSettings withRecommendationMerchants(FeatureSettings s, boolean value) {
        return make(s.downloadEnabled, s.removeWatermark, s.feedFilterEnabled,
                s.hideAds, s.hideLives, s.hideImagePosts, s.hideRecommendationCards, value,
                s.keywordFilterEnabled, s.keyword, s.minimumDurationEnabled, s.minimumDurationSeconds,
                s.minimumLikesEnabled, s.minimumLikeCount, s.automationEnabled, s.autoLikeEnabled,
                s.autoFollowEnabled, s.autoCommentEnabled, s.autoMessageEnabled);
    }

    private enum Category {
        BASIC(R.string.settings_category_basic, 2),
        INTERFACE(R.string.settings_category_interface, 16),
        FILTER(R.string.settings_category_filter, 13),
        DOWNLOAD(R.string.settings_category_download, 14),
        AUTOPLAY(R.string.settings_category_autoplay, 7),
        AUTO_REPLY(R.string.settings_category_auto_reply, 13),
        AUTO_COMMENT(R.string.settings_auto_comment, 8),
        COMMENT(R.string.settings_category_comment, 11),
        STREAK(R.string.settings_streak_enabled, 7),
        COMPONENT_HIDE(R.string.settings_category_component_hide, 24),
        MESSAGE(R.string.settings_category_message, 8),
        SPOOF(R.string.settings_category_spoof, 5),
        ENVIRONMENT(R.string.settings_category_environment, 14),
        EXTENSION(R.string.settings_category_extension, 4),
        ABOUT(R.string.settings_about, -1);

        final int titleRes;
        final int itemCount;

        Category(int titleRes, int itemCount) {
            this.titleRes = titleRes;
            this.itemCount = itemCount;
        }

        String title() { return UiText.get(titleRes); }

        String searchText() {
            if (this == ABOUT) return title() + " " + UiText.get(R.string.settings_category_about_terms);
            return this == EXTENSION ? title() + " " + UiText.get(R.string.settings_category_extension_terms) : title();
        }
    }

    private enum SearchDestination {
        AI(null), SPEECH(null), BACKUP(null), ANALYTICS(null), DEBUG_LOGS(null), INTERACTION(null), AI_CHAT(null),
        DOWNLOAD(Category.DOWNLOAD), AUTO_COMMENT(Category.AUTO_COMMENT), AUTO_REPLY(Category.AUTO_REPLY),
        AUTOPLAY(Category.AUTOPLAY), FILTER(Category.FILTER), COMPONENT_HIDE(Category.COMPONENT_HIDE),
        STREAK(Category.STREAK), MESSAGE(Category.MESSAGE), GESTURES(Category.INTERFACE), COMMENT(Category.COMMENT),
        ENVIRONMENT(Category.ENVIRONMENT);
        final Category category;
        SearchDestination(Category category) { this.category = category; }
    }

    /** Shared navigation index for native and Miuix homes; IDs do not depend on translated labels. */
    private static final class SearchEntry {
        final String id, targetTag;
        final int titleRes, pathRes, termsRes;
        final SearchDestination destination;
        SearchEntry(String id, int titleRes, int pathRes, int termsRes, SearchDestination destination) {
            this(id, titleRes, pathRes, termsRes, destination, null);
        }
        SearchEntry(String id, int titleRes, int pathRes, int termsRes, SearchDestination destination, String targetTag) {
            this.id = id; this.titleRes = titleRes; this.pathRes = pathRes; this.termsRes = termsRes;
            this.destination = destination; this.targetTag = targetTag;
        }
        String title() { return UiText.get(titleRes); }
        String path() { return UiText.get(pathRes); }
        String searchText() { return title() + " " + path() + " " + UiText.get(termsRes); }
    }

    private static final class CategoryRow {
        final Category category;
        final View view;

        CategoryRow(Category category, View view) {
            this.category = category;
            this.view = view;
        }
    }

    private interface EnabledControl { void setEnabled(boolean enabled); }

    private static final class ToggleControl implements EnabledControl {
        private final java.util.function.Consumer<Boolean> checked;
        private final java.util.function.Consumer<Boolean> enabled;
        private final java.util.function.Consumer<View.OnLongClickListener> longClick;
        private final java.util.function.Consumer<String> summary;

        ToggleControl(java.util.function.Consumer<Boolean> checked,
                      java.util.function.Consumer<Boolean> enabled,
                      java.util.function.Consumer<View.OnLongClickListener> longClick,
                      java.util.function.Consumer<String> summary) {
            this.checked = checked;
            this.enabled = enabled;
            this.longClick = longClick;
            this.summary = summary;
        }

        void setChecked(boolean value) { checked.accept(value); }
        @Override public void setEnabled(boolean value) { enabled.accept(value); }
        void setOnLongClickListener(View.OnLongClickListener listener) { longClick.accept(listener); }
        void setSummary(String value) { summary.accept(value); }
    }

    private static final class OptionRow implements EnabledControl {
        final View view;
        private final java.util.function.Consumer<String> value;
        private final java.util.function.Consumer<Boolean> enabled;
        private final java.util.function.IntConsumer color;

        OptionRow(View view, java.util.function.Consumer<String> value,
                  java.util.function.Consumer<Boolean> enabled, java.util.function.IntConsumer color) {
            this.view = view;
            this.value = value;
            this.enabled = enabled;
            this.color = color;
        }

        void setValue(String text) { value.accept(text); }
        void setColor(int newColor) { color.accept(newColor); }
        @Override public void setEnabled(boolean newEnabled) { enabled.accept(newEnabled); }
    }

    private enum Filter { AD, LIVE, IMAGE, CARD, KEYWORD, DURATION, LIKES }
    private interface ValueListener { void onValue(boolean value); }
    private interface ValueTextListener { void onValue(String value); }
    private interface SettingsTransform { FeatureSettings apply(FeatureSettings current); }
}
