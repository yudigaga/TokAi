package com.tokai.module.ui;

import com.tokai.module.R;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import com.tokai.module.config.AiModelConfig;
import com.tokai.module.config.AiModelSettings;
import com.tokai.module.config.AutomationHistoryStore;
import com.tokai.module.config.AutomationMessageSettings;
import com.tokai.module.config.LocalOptions;
import com.tokai.module.config.SettingsStore;
import com.tokai.module.feature.AutomationScopePolicy;
import com.tokai.module.feature.ContentScopePolicy;
import com.tokai.module.feature.FollowListSnapshot;
import com.tokai.module.feature.GroupReplyPolicy;
import com.tokai.module.feature.NativeFollowLists;
import com.tokai.module.feature.NativeMessagingHooks;
import com.tokai.module.feature.ProfileListEntry;
import com.tokai.module.feature.ReplyScopePolicy;
import com.tokai.module.ui.miuix.MiuixDialogBuilder;
import com.tokai.module.ui.miuix.MiuixExtraControls;
import com.tokai.module.ui.miuix.MiuixFormControls;
import com.tokai.module.ui.miuix.MiuixSettingsViews;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

/** Owns reply/comment forms and their editing session; the parent owns dialog navigation. */
final class AutomationMessageSettingsPage implements AutoCloseable {
    private static final int TEXT = Color.rgb(31, 39, 45);
    private static final int MUTED = Color.rgb(105, 115, 122);
    private static final int LINE = Color.rgb(226, 231, 232);
    private final Context context;
    private final AutomationMessageSettings repository;
    private final boolean useMiuix;
    private final Context rootRowContext;
    private final Context dialogRowContext;
    private final LinearLayout root;
    private final java.util.Map<LinearLayout, Dialog> panels = new java.util.IdentityHashMap<>();
    private final BiConsumer<Dialog, Runnable> presenter;
    private final Runnable showModels;
    private boolean closed;

    AutomationMessageSettingsPage(Context context, SettingsStore store, boolean useMiuix,
                                  Context rootRowContext, Context dialogRowContext, LinearLayout root,
                                  String kind, BiConsumer<Dialog, Runnable> presenter,
                                  Runnable showModels, Runnable changed) {
        this.context = ModuleTheme.themedContext(context);
        this.repository = new AutomationMessageSettings(store, changed);
        this.useMiuix = useMiuix;
        this.rootRowContext = rootRowContext;
        this.dialogRowContext = dialogRowContext;
        this.root = root;
        this.presenter = presenter;
        this.showModels = showModels;
        if ("reply".equals(kind)) buildAutoReplyControls(root);
        else if ("comment".equals(kind)) buildAutoCommentControls(root);
        else throw new IllegalArgumentException("未知自动化类型");
    }

    @Override public void close() { closed = true; repository.close(); panels.clear(); }

    private boolean isActive(LinearLayout panel) {
        if (closed) return false;
        if (panel == root) return true;
        Dialog owner = panels.get(panel);
        return owner != null && owner.isShowing();
    }

    private Context rowContext(LinearLayout panel) {
        return useMiuix ? panel == root ? rootRowContext : dialogRowContext : null;
    }

    private Context rowContext() { return useMiuix ? dialogRowContext : null; }

    private void rebuildAutomationPanel(LinearLayout panel, Runnable build) {
        if (closed) return;
        panel.removeAllViews();
        build.run();
    }

    private void showAutomationPanel(String title, LinearLayout panel, Runnable build) {
        rebuildAutomationPanel(panel, build);
        Dialog owner = new MiuixDialogBuilder(context, useMiuix).setTitle(title)
                .setView(scrolling(panel)).setNegativeButton(com.tokai.module.i18n.UiText.get(R.string.automation_ui_back), null).create();
        panels.put(panel, owner);
        showOptionDialog(owner, () -> panels.remove(panel));
    }

    private void showOptionDialog(Dialog dialog) { showOptionDialog(dialog, null); }

    private void showOptionDialog(Dialog dialog, Runnable onDismiss) {
        if (closed) { if (onDismiss != null) onDismiss.run(); return; }
        presenter.accept(dialog, onDismiss);
    }

    private void buildAutoReplyControls(LinearLayout panel) {
        ToggleControl[] commentReplies = new ToggleControl[1];
        OptionRow[] media = new OptionRow[1];
        addSwitch(panel, com.tokai.module.i18n.UiText.get(R.string.automation_ui_enable_reply), "",
                repository.getBoolean("native_auto_reply", false), true, value -> {
                    repository.setEnabled("reply", value);
                    if (commentReplies[0] != null) commentReplies[0].setSummary(commentReplySummary());
                }, "开启自动回复");
        addChoiceOption(panel, com.tokai.module.i18n.UiText.get(R.string.automation_ui_reply_mode), "reply_mode", new String[]{com.tokai.module.i18n.UiText.get(R.string.automation_ui_fixed_content), com.tokai.module.i18n.UiText.get(R.string.automation_ui_ai_model), com.tokai.module.i18n.UiText.get(R.string.automation_ui_keyword_rules), com.tokai.module.i18n.UiText.get(R.string.automation_ui_random_content)},
                new String[]{"fixed", "ai", "keyword", "random"}, "fixed", () -> {
                    media[0].setValue(replyMediaPreview());
                    media[0].setEnabled("ai".equals(repository.getString("reply_mode", "fixed")));
                }, "回复模式");
        addOption(panel, com.tokai.module.i18n.UiText.get(R.string.automation_ui_reply_content), "", null, true, this::showReplyContentSettings, "回复内容");
        media[0] = addOption(panel, com.tokai.module.i18n.UiText.get(R.string.automation_ui_media_recognition), "", replyMediaPreview(),
                "ai".equals(repository.getString("reply_mode", "fixed")), () -> showReplyMediaSettings(media[0]), "素材识别");
        addSwitch(panel, com.tokai.module.i18n.UiText.get(R.string.automation_ui_voice_reply), "", repository.getBoolean(LocalOptions.REPLY_VOICE, false), true,
                value -> repository.putBoolean(LocalOptions.REPLY_VOICE, value), "语音回复");
        OptionRow[] privateRange = new OptionRow[1];
        addSwitch(panel, com.tokai.module.i18n.UiText.get(R.string.automation_ui_receive_private), "", repository.getBoolean("reply_private_chats", true), true,
                value -> { repository.putBoolean("reply_private_chats", value); privateRange[0].setValue(privateReplyPreview()); }, "接收私聊");
        privateRange[0] = addOption(panel, com.tokai.module.i18n.UiText.get(R.string.automation_ui_private_scope), "", privateReplyPreview(), true, () -> showPrivateReplySettings(privateRange[0]), "私聊范围");
        OptionRow[] groupRange = new OptionRow[1];
        addSwitch(panel, com.tokai.module.i18n.UiText.get(R.string.automation_ui_receive_groups), "", repository.getBoolean("reply_group_chats", false), true,
                value -> { repository.putBoolean("reply_group_chats", value); groupRange[0].setValue(groupReplyPreview()); }, "接收群聊");
        groupRange[0] = addOption(panel, com.tokai.module.i18n.UiText.get(R.string.automation_ui_group_scope), "", groupReplyPreview(), true, () -> showGroupReplySettings(groupRange[0]), "群聊范围");
        commentReplies[0] = addSwitch(panel, com.tokai.module.i18n.UiText.get(R.string.automation_ui_receive_comment_replies), commentReplySummary(),
                repository.getBoolean(LocalOptions.COMMENT_REPLY_ENABLED, false), true, value -> {
                    repository.setEnabled("comment_reply", value);
                    commentReplies[0].setSummary(commentReplySummary());
                }, "接收评论回复");
        addAutomationLimitOption(panel, com.tokai.module.i18n.UiText.get(R.string.automation_ui_daily_reply_limit), "", "reply_daily_limit", "每日最大回复数");
        addAutomationLimitOption(panel, com.tokai.module.i18n.UiText.get(R.string.automation_ui_reply_user_interval), "", "reply_user_cooldown_minutes", "同一用户回复间隔（分钟）");
        addOption(panel, com.tokai.module.i18n.UiText.get(R.string.automation_ui_delivery_records), "", null, true, this::showReplyDeliverySettings, "发送与记录");
    }

    private String commentReplySummary() {
        return repository.getBoolean(LocalOptions.COMMENT_REPLY_ENABLED, false) && !repository.getBoolean("native_auto_reply", false)
                ? com.tokai.module.i18n.UiText.get(R.string.automation_ui_reply_paused) : "";
    }

    private String replyMediaPreview() {
        if (!"ai".equals(repository.getString("reply_mode", "fixed"))) return com.tokai.module.i18n.UiText.get(R.string.automation_ui_not_enabled);
        boolean images = repository.getBoolean(LocalOptions.REPLY_RECOGNIZE_IMAGES, true);
        boolean video = repository.getBoolean(LocalOptions.REPLY_RECOGNIZE_VIDEO, false);
        return images && video ? com.tokai.module.i18n.UiText.get(R.string.automation_ui_images_video) : images ? com.tokai.module.i18n.UiText.get(R.string.automation_ui_images) : video ? com.tokai.module.i18n.UiText.get(R.string.automation_ui_video) : com.tokai.module.i18n.UiText.get(R.string.automation_ui_no_recognition);
    }

    private void showReplyMediaSettings(OptionRow preview) {
        LinearLayout panel = form();
        showAutomationPanel(com.tokai.module.i18n.UiText.get(R.string.automation_ui_media_recognition), panel, () -> {
            addSwitch(panel, com.tokai.module.i18n.UiText.get(R.string.automation_ui_recognize_images), "",
                    repository.getBoolean(LocalOptions.REPLY_RECOGNIZE_IMAGES, true), true,
                    value -> { repository.putBoolean(LocalOptions.REPLY_RECOGNIZE_IMAGES, value); preview.setValue(replyMediaPreview()); }, "识别图片");
            addSwitch(panel, com.tokai.module.i18n.UiText.get(R.string.automation_ui_recognize_video), "",
                    repository.getBoolean(LocalOptions.REPLY_RECOGNIZE_VIDEO, false), true,
                    value -> { repository.putBoolean(LocalOptions.REPLY_RECOGNIZE_VIDEO, value); preview.setValue(replyMediaPreview()); }, "识别视频");
        });
    }

    private void addAutomationLimitOption(LinearLayout panel, String title, String summary, String key, String stableTitle) {
        addEditableOption(panel, title, summary, repository.getString(key, "0"), true, true, value -> {
            repository.putString(key, value);
        }, stableTitle);
    }

    private static String scopeLabel(String mode) {
        return "whitelist".equals(mode) ? com.tokai.module.i18n.UiText.get(R.string.automation_ui_allowlist) : "blacklist".equals(mode) ? com.tokai.module.i18n.UiText.get(R.string.automation_ui_blocklist) : com.tokai.module.i18n.UiText.get(R.string.automation_ui_all);
    }

    private String privateReplyPreview() {
        return repository.getBoolean("reply_private_chats", true)
                ? scopeLabel(ReplyScopePolicy.scopeMode(repository.scope("reply"))) : com.tokai.module.i18n.UiText.get(R.string.automation_ui_not_enabled);
    }

    private String groupReplyPreview() {
        return repository.getBoolean("reply_group_chats", false)
                ? scopeLabel(repository.getString("reply_group_scope_mode", "all")) : com.tokai.module.i18n.UiText.get(R.string.automation_ui_not_enabled);
    }

    private void showReplyContentSettings() {
        String mode = repository.getString("reply_mode", "fixed");
        if ("keyword".equals(mode) || "random".equals(mode)) { showReplyPool("keyword".equals(mode)); return; }
        LinearLayout panel = form();
        showAutomationPanel(com.tokai.module.i18n.UiText.get(R.string.automation_ui_reply_content), panel, () -> {
            if ("ai".equals(mode)) {
                addAutomationModelOption(panel, "reply");
                addPromptOption(panel, com.tokai.module.i18n.UiText.get(R.string.automation_ui_secondary_prompt), repository.getString("reply_prompt", "自然简洁地回复对方，只输出回复正文。"),
                        value -> repository.putString("reply_prompt", value), "二级提示词");
            } else {
                addEditableOption(panel, com.tokai.module.i18n.UiText.get(R.string.automation_ui_fixed_reply), "", repository.getString("auto_message_text", ""), false, true,
                        value -> repository.putString("auto_message_text", value), "固定回复内容");
            }
        });
    }

    private void showPrivateReplySettings(OptionRow preview) {
        repository.prepareContentFilter("reply");
        LinearLayout panel = form();
        Runnable[] build = new Runnable[1];
        build[0] = () -> {
            LocalOptions scopeOptions = repository.scope("reply");
            String scope = ReplyScopePolicy.scopeMode(scopeOptions);
            addChoiceOption(panel, com.tokai.module.i18n.UiText.get(R.string.automation_ui_user_scope), "reply_scope_mode", new String[]{com.tokai.module.i18n.UiText.get(R.string.automation_ui_all), com.tokai.module.i18n.UiText.get(R.string.automation_ui_allowlist), com.tokai.module.i18n.UiText.get(R.string.automation_ui_blocklist)},
                    new String[]{"all", "whitelist", "blacklist"}, scope, () -> {
                        preview.setValue(privateReplyPreview());
                        rebuildAutomationPanel(panel, build[0]);
                    }, "用户范围");
            if (!"all".equals(scope)) {
                addMultiChoiceCsvOption(panel, com.tokai.module.i18n.UiText.get(R.string.automation_ui_follow_relationship), "reply_scope_relations", new String[]{com.tokai.module.i18n.UiText.get(R.string.automation_ui_following), com.tokai.module.i18n.UiText.get(R.string.automation_ui_followers), com.tokai.module.i18n.UiText.get(R.string.automation_ui_strangers)},
                        new String[]{AutomationScopePolicy.FOLLOWING, AutomationScopePolicy.FOLLOWERS, AutomationScopePolicy.STRANGERS}, "关注关系");
                addOption(panel, com.tokai.module.i18n.UiText.get(R.string.automation_ui_specified_users), "", repository.getString("reply_targets", ""), true,
                        () -> showTargetSelection(com.tokai.module.i18n.UiText.get(R.string.automation_ui_specified_users), "reply_targets", "指定用户"), "指定用户");
            }
            addFollowListsOption(panel);
            String filter = ReplyScopePolicy.filterMode(scopeOptions);
            addChoiceOption(panel, com.tokai.module.i18n.UiText.get(R.string.automation_ui_message_filter), "reply_filter_mode", new String[]{com.tokai.module.i18n.UiText.get(R.string.automation_ui_unrestricted), com.tokai.module.i18n.UiText.get(R.string.automation_ui_include_keywords), com.tokai.module.i18n.UiText.get(R.string.automation_ui_exclude_keywords)},
                    new String[]{"off", "include", "exclude"}, filter, () -> rebuildAutomationPanel(panel, build[0]), "消息筛选");
            if (!ReplyScopePolicy.OFF.equals(filter)) {
                addEditableOption(panel, com.tokai.module.i18n.UiText.get(R.string.automation_ui_filter_keywords), "", ReplyScopePolicy.keywords(scopeOptions), false, true,
                        value -> repository.putString("reply_filter_keywords", value), "筛选关键词");
                addMultiChoiceCsvOption(panel, com.tokai.module.i18n.UiText.get(R.string.automation_ui_match_fields), "reply_filter_fields", new String[]{com.tokai.module.i18n.UiText.get(R.string.automation_ui_sender), com.tokai.module.i18n.UiText.get(R.string.automation_ui_message_content)},
                        new String[]{AutomationScopePolicy.AUTHOR, AutomationScopePolicy.CAPTION}, "匹配对象");
            }
        };
        showAutomationPanel(com.tokai.module.i18n.UiText.get(R.string.automation_ui_private_scope), panel, build[0]);
    }

    private void showGroupReplySettings(OptionRow preview) {
        LinearLayout panel = form();
        Runnable[] build = new Runnable[1];
        build[0] = () -> {
            String scope = repository.getString("reply_group_scope_mode", "all");
            addChoiceOption(panel, com.tokai.module.i18n.UiText.get(R.string.automation_ui_group_scope_mode), "reply_group_scope_mode",
                    new String[]{com.tokai.module.i18n.UiText.get(R.string.automation_ui_all), com.tokai.module.i18n.UiText.get(R.string.automation_ui_allowlist), com.tokai.module.i18n.UiText.get(R.string.automation_ui_blocklist)}, new String[]{"all", "whitelist", "blacklist"}, "all", () -> {
                        preview.setValue(groupReplyPreview());
                        rebuildAutomationPanel(panel, build[0]);
                    }, "群聊作用域");
            if (!"all".equals(scope)) {
                OptionRow[] groups = new OptionRow[1];
                groups[0] = addOption(panel, com.tokai.module.i18n.UiText.get(R.string.automation_ui_choose_groups), "", selectedGroupPreview(), true, () -> showGroupSelection(groups[0]), "选择群聊");
            }
            addChoiceOption(panel, com.tokai.module.i18n.UiText.get(R.string.automation_ui_trigger_condition), "reply_group_mode", new String[]{com.tokai.module.i18n.UiText.get(R.string.automation_ui_all_members), com.tokai.module.i18n.UiText.get(R.string.automation_ui_specified_members), com.tokai.module.i18n.UiText.get(R.string.automation_ui_mentions_only)},
                    new String[]{"all", "selected", "mentions"}, "all", () -> rebuildAutomationPanel(panel, build[0]), "触发条件");
            if ("selected".equals(repository.getString("reply_group_mode", "all"))) {
                addOption(panel, com.tokai.module.i18n.UiText.get(R.string.automation_ui_specified_members), "", repository.getString("reply_group_members", ""), true, this::showGroupMemberSelection, "指定成员");
            }
        };
        showAutomationPanel(com.tokai.module.i18n.UiText.get(R.string.automation_ui_group_scope), panel, build[0]);
    }

    private void showReplyDeliverySettings() {
        showDeliverySettings(AutomationHistoryStore.REPLY);
    }

    private void showDeliverySettings(String kind) {
        boolean reply = AutomationHistoryStore.REPLY.equals(kind);
        LinearLayout panel = form();
        Runnable[] build = new Runnable[1];
        build[0] = () -> {
            addEditableOption(panel, reply ? com.tokai.module.i18n.UiText.get(R.string.automation_ui_reply_delay) : com.tokai.module.i18n.UiText.get(R.string.automation_ui_comment_delay), "", repository.getString(kind + "_delay_seconds", reply ? "2" : "3"), true, true, value -> {
                repository.putString(kind + "_delay_seconds", value);
            }, reply ? "延迟回复" : "延迟评论");
            addSwitch(panel, com.tokai.module.i18n.UiText.get(R.string.automation_ui_floating_notice), "", repository.getBoolean(kind + "_tip", false), true, value -> {
                repository.putBoolean(kind + "_tip", value);
                rebuildAutomationPanel(panel, build[0]);
            }, "悬浮提示");
            if (repository.getBoolean(kind + "_tip", false)) {
                addEditableOption(panel, com.tokai.module.i18n.UiText.get(R.string.automation_ui_notice_timeout), "", repository.getString(kind + "_notice_seconds",
                        Integer.toString(AutomationMessageSettings.DEFAULT_NOTICE_SECONDS)), true, true, value -> {
                    repository.putString(kind + "_notice_seconds", value);
                }, "提示超时（秒）");
            }
            addLocalSwitch(panel, com.tokai.module.i18n.UiText.get(R.string.automation_ui_save_history), "", kind + "_save_history", "保存历史记录");
            addOption(panel, reply ? com.tokai.module.i18n.UiText.get(R.string.automation_ui_reply_history) : com.tokai.module.i18n.UiText.get(R.string.automation_ui_comment_history), "", null, true, () -> {
                AutomationHistoryDialog history = new AutomationHistoryDialog(context, kind, useMiuix);
                showOptionDialog(history.dialog(), history::closeDetails);
            }, reply ? "回复历史记录" : "评论历史记录");
        };
        showAutomationPanel(com.tokai.module.i18n.UiText.get(R.string.automation_ui_delivery_records), panel, build[0]);
    }

    private String selectedGroupPreview() {
        int selected = GroupReplyPolicy.entries(repository.getString("reply_group_targets", "")).size();
        return selected == 0 ? com.tokai.module.i18n.UiText.get(R.string.automation_ui_not_selected) : com.tokai.module.i18n.UiText.get(R.string.automation_ui_group_count, selected);
    }

    private void showGroupSelection(OptionRow preview) {
        withNativeContacts(false, () -> showGroupSelectionLoaded(preview));
    }

    private void showGroupSelectionLoaded(OptionRow preview) {
        java.util.Map<String, String> groups = NativeMessagingHooks.availableGroups();
        java.util.Set<String> selected = GroupReplyPolicy.entries(repository.getString("reply_group_targets", ""));
        if (groups.isEmpty() && selected.isEmpty()) {
            showOptionDialog(new MiuixDialogBuilder(context, useMiuix).setTitle(com.tokai.module.i18n.UiText.get(R.string.automation_ui_choose_groups))
                    .setMessage(com.tokai.module.i18n.UiText.get(R.string.automation_ui_no_groups))
                    .setNeutralButton(com.tokai.module.i18n.UiText.get(R.string.automation_ui_enter_conversation_ids), (dialog, which) ->
                            new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> showGroupConversationIds(preview)))
                    .setNegativeButton(com.tokai.module.i18n.UiText.get(R.string.automation_ui_back), null).create());
            return;
        }
        showProfileSelection(com.tokai.module.i18n.UiText.get(R.string.automation_ui_choose_groups), NativeMessagingHooks.availableGroupProfiles(), selected, next -> {
                    repository.putString("reply_group_targets", String.join(",", next));
                    preview.setValue(selectedGroupPreview());
                }, () -> showGroupConversationIds(preview), "选择群聊");
    }

    private void showGroupConversationIds(OptionRow preview) {
        LinearLayout form = form();
        EditText input = formInput(form, com.tokai.module.i18n.UiText.get(R.string.automation_ui_conversation_ids_hint), repository.getString("reply_group_targets", ""), false, "群会话 ID，以逗号分隔");
        showOptionDialog(new MiuixDialogBuilder(context, useMiuix).setTitle(com.tokai.module.i18n.UiText.get(R.string.automation_ui_enter_conversation_ids)).setView(form)
                .setNegativeButton(com.tokai.module.i18n.UiText.get(R.string.automation_ui_cancel), null).setPositiveButton(com.tokai.module.i18n.UiText.get(R.string.automation_ui_save), (dialog, which) -> {
                    if (closed) return;
                    repository.putString("reply_group_targets", String.join(",", GroupReplyPolicy.entries(input.getText().toString())));
                    preview.setValue(selectedGroupPreview());
                }).create());
    }

    private void showGroupMemberSelection() {
        withNativeContacts(true, () -> showGroupMemberSelectionLoaded());
    }

    private void showGroupMemberSelectionLoaded() {
        java.util.Map<String, ProfileListEntry> members = NativeMessagingHooks.availableGroupMembers();
        java.util.Set<String> selected = new java.util.LinkedHashSet<>(GroupReplyPolicy.entries(
                repository.getString("reply_group_members", "")));
        java.util.Set<String> configured = new java.util.LinkedHashSet<>(selected);
        for (ProfileListEntry entry : members.values()) {
            if (entry.name.isEmpty()) continue;
            for (String name : configured) if (name.equalsIgnoreCase(entry.name)) {
                selected.remove(name);
                selected.add(entry.uid);
            }
        }
        showProfileSelection(com.tokai.module.i18n.UiText.get(R.string.automation_ui_choose_members), members, selected, next ->
                repository.putString("reply_group_members", String.join(",", next)), this::showGroupMemberIds, "选择群成员");
    }

    private void showGroupMemberIds() {
        LinearLayout form = form();
        EditText input = formInput(form, com.tokai.module.i18n.UiText.get(R.string.automation_ui_member_ids_hint), repository.getString("reply_group_members", ""), false, "成员 UID 或昵称");
        showOptionDialog(new MiuixDialogBuilder(context, useMiuix).setTitle(com.tokai.module.i18n.UiText.get(R.string.automation_ui_specified_members)).setView(form)
                .setNegativeButton(com.tokai.module.i18n.UiText.get(R.string.automation_ui_cancel), null).setPositiveButton(com.tokai.module.i18n.UiText.get(R.string.automation_ui_save), (dialog, which) ->
                        repository.putString("reply_group_members", String.join(",", GroupReplyPolicy.entries(input.getText().toString())))).create());
    }

    private void showProfileSelection(String title, java.util.Map<String, ProfileListEntry> available,
                                      java.util.Set<String> selected, java.util.function.Consumer<java.util.Set<String>> saved,
                                      Runnable manual, String stableTitle) {
        java.util.Map<String, ProfileListEntry> entries = new java.util.LinkedHashMap<>(available);
        for (String uid : selected) if (!entries.containsKey(uid)) {
            entries.put(uid, "选择群成员".equals(stableTitle) && !uid.matches("[1-9][0-9]{0,19}")
                    ? ProfileListEntry.named(uid) : new ProfileListEntry(uid, "", ""));
        }
        MemberListView list = new MemberListView(useMiuix ? rowContext() : context, useMiuix,
                new ArrayList<>(entries.values()), selected, true);
        MiuixDialogBuilder builder = new MiuixDialogBuilder(context, useMiuix);
        builder.setTitle(title).setView(list).setNegativeButton(com.tokai.module.i18n.UiText.get(R.string.automation_ui_cancel), null)
                .setPositiveButton(com.tokai.module.i18n.UiText.get(R.string.automation_ui_save), (dialog, which) -> saved.accept(list.selected()));
        if (manual != null) builder.setNeutralButton("选择群聊".equals(stableTitle) ? com.tokai.module.i18n.UiText.get(R.string.automation_ui_enter_conversation_ids) : com.tokai.module.i18n.UiText.get(R.string.automation_ui_enter_uid), (dialog, which) ->
                new android.os.Handler(android.os.Looper.getMainLooper()).post(manual));
        showOptionDialog(builder.create(), list::close);
    }

    private void buildAutoCommentControls(LinearLayout panel) {
        addSwitch(panel, com.tokai.module.i18n.UiText.get(R.string.automation_ui_enable_comment), "", repository.getBoolean("native_auto_comment", false), true,
                value -> repository.setEnabled("comment", value), "开启自动评论");
        OptionRow[] media = new OptionRow[1];
        addChoiceOption(panel, com.tokai.module.i18n.UiText.get(R.string.automation_ui_comment_mode), "comment_mode", new String[]{com.tokai.module.i18n.UiText.get(R.string.automation_ui_ai_model), com.tokai.module.i18n.UiText.get(R.string.automation_ui_fixed_content)}, new String[]{"ai", "fixed"}, "ai", () -> {
            boolean ai = "ai".equals(repository.getString("comment_mode", "ai"));
            media[0].setValue(commentMediaPreview());
            media[0].setEnabled(ai);
        }, "评论模式");
        addOption(panel, com.tokai.module.i18n.UiText.get(R.string.automation_ui_comment_content), "", null, true, this::showCommentContentSettings, "评论内容");
        addSwitch(panel, com.tokai.module.i18n.UiText.get(R.string.automation_ui_voice_comment), com.tokai.module.i18n.UiText.get(R.string.automation_ui_voice_comment_hint),
                repository.getBoolean(LocalOptions.COMMENT_VOICE, false), true,
                value -> repository.putBoolean(LocalOptions.COMMENT_VOICE, value), "语音评论");
        OptionRow[] scope = new OptionRow[1];
        scope[0] = addOption(panel, com.tokai.module.i18n.UiText.get(R.string.automation_ui_post_scope), "", scopeLabel(ContentScopePolicy.scopeMode("comment", repository.scope("comment"))),
                true, () -> showCommentScopeSettings(scope[0]), "作品范围");
        boolean ai = "ai".equals(repository.getString("comment_mode", "ai"));
        media[0] = addOption(panel, com.tokai.module.i18n.UiText.get(R.string.automation_ui_media_recognition), "", commentMediaPreview(), ai, () -> showCommentMediaSettings(media[0]), "素材识别");
        addAutomationLimitOption(panel, com.tokai.module.i18n.UiText.get(R.string.automation_ui_daily_comment_limit), "", "comment_daily_limit", "每日最大评论数");
        addAutomationLimitOption(panel, com.tokai.module.i18n.UiText.get(R.string.automation_ui_comment_author_interval), "", "comment_user_cooldown_minutes", "同一作者评论间隔（分钟）");
        addOption(panel, com.tokai.module.i18n.UiText.get(R.string.automation_ui_delivery_records), "", null, true, () -> showDeliverySettings(AutomationHistoryStore.COMMENT), "发送与记录");
    }

    private void showCommentContentSettings() {
        boolean ai = "ai".equals(repository.getString("comment_mode", "ai"));
        LinearLayout panel = form();
        showAutomationPanel(com.tokai.module.i18n.UiText.get(R.string.automation_ui_comment_content), panel, () -> {
            if (ai) {
                addAutomationModelOption(panel, "comment");
                addPromptOption(panel, com.tokai.module.i18n.UiText.get(R.string.automation_ui_secondary_prompt), repository.getString("comment_prompt", "根据当前作品自然地写一条简短评论，只输出评论正文。"),
                        value -> repository.putString("comment_prompt", value), "二级提示词");
            } else {
                addEditableOption(panel, com.tokai.module.i18n.UiText.get(R.string.automation_ui_fixed_comment), "", repository.getString("auto_comment_text", ""), false, true,
                        value -> repository.putString("auto_comment_text", value), "固定评论内容");
            }
        });
    }

    private void showCommentScopeSettings(OptionRow preview) {
        repository.prepareContentFilter("comment");
        LinearLayout panel = form();
        Runnable[] build = new Runnable[1];
        build[0] = () -> {
            LocalOptions scopeOptions = repository.scope("comment");
            String mode = ContentScopePolicy.scopeMode("comment", scopeOptions);
            addChoiceOption(panel, com.tokai.module.i18n.UiText.get(R.string.automation_ui_author_scope), "comment_scope_mode", new String[]{com.tokai.module.i18n.UiText.get(R.string.automation_ui_all), com.tokai.module.i18n.UiText.get(R.string.automation_ui_allowlist), com.tokai.module.i18n.UiText.get(R.string.automation_ui_blocklist)},
                    new String[]{"all", "whitelist", "blacklist"}, mode, () -> {
                        preview.setValue(scopeLabel(repository.getString("comment_scope_mode", "all")));
                        rebuildAutomationPanel(panel, build[0]);
                    }, "作者范围");
            if (!"all".equals(mode)) {
                addMultiChoiceCsvOption(panel, com.tokai.module.i18n.UiText.get(R.string.automation_ui_follow_relationship), "comment_scope_relations", new String[]{com.tokai.module.i18n.UiText.get(R.string.automation_ui_following), com.tokai.module.i18n.UiText.get(R.string.automation_ui_followers), com.tokai.module.i18n.UiText.get(R.string.automation_ui_strangers)},
                        new String[]{AutomationScopePolicy.FOLLOWING, AutomationScopePolicy.FOLLOWERS, AutomationScopePolicy.STRANGERS}, "关注关系");
                addOption(panel, com.tokai.module.i18n.UiText.get(R.string.automation_ui_specified_authors), "", repository.getString("comment_scope_users", ""), true, this::showCommentAuthorSelection, "指定作者");
            }
            addFollowListsOption(panel);
            String filter = ContentScopePolicy.filterMode("comment", scopeOptions);
            addChoiceOption(panel, com.tokai.module.i18n.UiText.get(R.string.automation_ui_post_filter), "comment_filter_mode", new String[]{com.tokai.module.i18n.UiText.get(R.string.automation_ui_unrestricted), com.tokai.module.i18n.UiText.get(R.string.automation_ui_include_keywords), com.tokai.module.i18n.UiText.get(R.string.automation_ui_exclude_keywords)},
                    new String[]{"off", "include", "exclude"}, filter, () -> rebuildAutomationPanel(panel, build[0]), "作品筛选");
            if (!ContentScopePolicy.OFF.equals(filter)) {
                addEditableOption(panel, com.tokai.module.i18n.UiText.get(R.string.automation_ui_filter_keywords), "", ContentScopePolicy.keywords("comment", scopeOptions),
                        false, true, value -> repository.putString("comment_filter_keywords", value), "筛选关键词");
                addMultiChoiceCsvOption(panel, com.tokai.module.i18n.UiText.get(R.string.automation_ui_match_fields), "comment_filter_fields", new String[]{com.tokai.module.i18n.UiText.get(R.string.automation_ui_author), com.tokai.module.i18n.UiText.get(R.string.automation_ui_caption), com.tokai.module.i18n.UiText.get(R.string.automation_ui_title)},
                        new String[]{AutomationScopePolicy.AUTHOR, AutomationScopePolicy.CAPTION, AutomationScopePolicy.TITLE}, "匹配对象");
            }
        };
        showAutomationPanel(com.tokai.module.i18n.UiText.get(R.string.automation_ui_post_scope), panel, build[0]);
    }

    private void showCommentAuthorSelection() {
        withNativeContacts(false, () -> showCommentAuthorSelectionLoaded());
    }

    private void showCommentAuthorSelectionLoaded() {
        java.util.Map<String, ProfileListEntry> authors = new java.util.LinkedHashMap<>(NativeMessagingHooks.availableContactProfiles());
        authors.putAll(NativeFollowLists.memberDetails(FollowListSnapshot.Kind.FOLLOWING));
        authors.putAll(NativeFollowLists.memberDetails(FollowListSnapshot.Kind.FOLLOWERS));
        showProfileSelection(com.tokai.module.i18n.UiText.get(R.string.automation_ui_specified_authors), authors, AutomationScopePolicy.targets(repository.getString("comment_scope_users", "")),
                next -> repository.putString("comment_scope_users", String.join(",", next)),
                () -> showTargetIds(com.tokai.module.i18n.UiText.get(R.string.automation_ui_author_uid), "comment_scope_users"), "指定作者");
    }

    private String commentMediaPreview() {
        if (!"ai".equals(repository.getString("comment_mode", "ai"))) return com.tokai.module.i18n.UiText.get(R.string.automation_ui_not_enabled);
        boolean images = repository.getBoolean("comment_recognize_images", true);
        boolean video = repository.getBoolean("comment_recognize_video", false);
        return images && video ? com.tokai.module.i18n.UiText.get(R.string.automation_ui_images_video) : images ? com.tokai.module.i18n.UiText.get(R.string.automation_ui_images) : video ? com.tokai.module.i18n.UiText.get(R.string.automation_ui_video) : com.tokai.module.i18n.UiText.get(R.string.automation_ui_no_recognition);
    }

    private void showCommentMediaSettings(OptionRow preview) {
        LinearLayout panel = form();
        showAutomationPanel(com.tokai.module.i18n.UiText.get(R.string.automation_ui_media_recognition), panel, () -> {
            addSwitch(panel, com.tokai.module.i18n.UiText.get(R.string.automation_ui_recognize_images), "", repository.getBoolean("comment_recognize_images", true), true,
                    value -> { repository.putBoolean("comment_recognize_images", value); preview.setValue(commentMediaPreview()); }, "识别图片");
            addSwitch(panel, com.tokai.module.i18n.UiText.get(R.string.automation_ui_recognize_video), "", repository.getBoolean("comment_recognize_video", false), true,
                    value -> { repository.putBoolean("comment_recognize_video", value); preview.setValue(commentMediaPreview()); }, "识别视频");
        });
    }

    private void addFollowListsOption(LinearLayout panel) {
        OptionRow[] preview = new OptionRow[1];
        preview[0] = addOption(panel, com.tokai.module.i18n.UiText.get(R.string.automation_ui_follow_lists), "", MetadataUiText.displayFollowStatus(NativeFollowLists.status()), true,
                () -> showFollowLists(preview[0]), "关注与粉丝名单");
    }

    private void showFollowLists(OptionRow preview) {
        LinearLayout form = form();
        Button following = formButton();
        following.setText(com.tokai.module.i18n.UiText.get(R.string.automation_ui_following));
        following.setTag("settings.followLists.following");
        following.setOnClickListener(ignored -> showFollowListMembers(FollowListSnapshot.Kind.FOLLOWING));
        form.addView(MiuixFormControls.viewFor(following), matchWrap());
        Button followers = formButton();
        followers.setText(com.tokai.module.i18n.UiText.get(R.string.automation_ui_followers));
        followers.setTag("settings.followLists.followers");
        followers.setOnClickListener(ignored -> showFollowListMembers(FollowListSnapshot.Kind.FOLLOWERS));
        LinearLayout.LayoutParams followersParams = matchWrap();
        followersParams.topMargin = dp(8);
        form.addView(MiuixFormControls.viewFor(followers), followersParams);
        AlertDialog dialog = new MiuixDialogBuilder(context, useMiuix)
                .setTitle(com.tokai.module.i18n.UiText.get(R.string.automation_ui_follow_lists)).setMessage(MetadataUiText.displayFollowStatus(NativeFollowLists.status())).setView(form)
                .setNegativeButton(com.tokai.module.i18n.UiText.get(R.string.automation_ui_back), null).setPositiveButton(com.tokai.module.i18n.UiText.get(R.string.automation_ui_refresh_lists), null).create();
        showOptionDialog(dialog);
        Button refresh = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        refresh.setOnClickListener(ignored -> {
            if (closed || !dialog.isShowing()) return;
            refresh.setEnabled(false);
            boolean started = NativeFollowLists.refresh(() -> {
                if (!dialog.isShowing()) return;
                String status = MetadataUiText.displayFollowStatus(NativeFollowLists.status());
                dialog.setMessage(status);
                preview.setValue(status);
                refresh.setEnabled(true);
            });
            if (started) {
                dialog.setMessage(MetadataUiText.displayFollowStatus(NativeFollowLists.status()));
            } else {
                repository.putString(NativeFollowLists.REFRESH_KEY, Long.toString(System.currentTimeMillis()));
                String status = com.tokai.module.i18n.UiText.get(R.string.automation_ui_refresh_requested);
                dialog.setMessage(status);
                preview.setValue(status);
                refresh.setEnabled(true);
            }
        });
    }

    private void showFollowListMembers(FollowListSnapshot.Kind kind) {
        java.util.Map<String, String> members = NativeFollowLists.members(kind);
        FollowListSnapshot.State snapshot = FollowListSnapshot.current();
        String title = kind == FollowListSnapshot.Kind.FOLLOWING ? com.tokai.module.i18n.UiText.get(R.string.automation_ui_following) : com.tokai.module.i18n.UiText.get(R.string.automation_ui_followers);
        boolean known = kind == FollowListSnapshot.Kind.FOLLOWING
                ? snapshot.followingKnown : snapshot.followersKnown;
        java.util.Set<String> ids = kind == FollowListSnapshot.Kind.FOLLOWING
                ? snapshot.following : snapshot.followers;
        MiuixDialogBuilder builder = new MiuixDialogBuilder(context, useMiuix);
        builder.setTitle(known ? com.tokai.module.i18n.UiText.get(R.string.automation_ui_people_count, title, ids.size()) : title);
        if (!snapshot.active) {
            builder.setMessage(repository.isInHost() ? com.tokai.module.i18n.UiText.get(R.string.automation_ui_account_not_read) : com.tokai.module.i18n.UiText.get(R.string.automation_ui_view_lists_in_host));
        } else if (!known) {
            builder.setMessage(com.tokai.module.i18n.UiText.get(R.string.automation_ui_lists_loading, MetadataUiText.displayFollowStatus(NativeFollowLists.status())));
        } else if (ids.isEmpty()) {
            builder.setMessage(com.tokai.module.i18n.UiText.get(R.string.automation_ui_empty_list));
        } else {
            MemberListView list = new MemberListView(useMiuix ? rowContext() : context, useMiuix,
                    new ArrayList<>(NativeFollowLists.memberDetails(kind).values()), java.util.Collections.emptySet(), false);
            builder.setView(list).setNegativeButton(com.tokai.module.i18n.UiText.get(R.string.automation_ui_back), null);
            showOptionDialog(builder.create(), list::close);
            return;
        }
        builder.setNegativeButton(com.tokai.module.i18n.UiText.get(R.string.automation_ui_back), null);
        showOptionDialog(builder.create());
    }

    private void addPromptOption(LinearLayout panel, String title, String value, ValueTextListener listener, String stableTitle) {
        String[] current = {value};
        OptionRow[] preview = new OptionRow[1];
        OptionRow row = addOption(panel, title, "", promptPreview(value), true, () -> showOptionDialog(
                new PromptEditorDialog(context, title, current[0], candidate -> {
                    listener.onValue(candidate);
                    current[0] = candidate;
                    preview[0].setValue(promptPreview(candidate));
                }, useMiuix, stableTitle)), stableTitle);
        preview[0] = row;
    }

    private static String promptPreview(String value) {
        if (value.isEmpty()) return com.tokai.module.i18n.UiText.get(R.string.automation_ui_not_set);
        int points = value.codePointCount(0, value.length());
        return value.substring(0, value.offsetByCodePoints(0, Math.min(240, points)));
    }

    private void showReplyPool(boolean keyword) {
        String key = keyword ? "reply_keywords" : "reply_random";
        JSONArray saved;
        try { saved = new JSONArray(repository.getString(key, "[]")); }
        catch (org.json.JSONException ignored) { saved = new JSONArray(); }
        LinearLayout form = form();
        List<EditText[]> editors = new ArrayList<>();
        Runnable add = () -> {
            if (editors.size() >= 50) return;
            LinearLayout entry = form();
            EditText match = keyword ? formInput(entry, com.tokai.module.i18n.UiText.get(R.string.automation_ui_match_text), "", false, "匹配文字") : null;
            EditText response = formInput(entry, com.tokai.module.i18n.UiText.get(R.string.automation_ui_reply_content), "", false, "回复内容");
            ImageButton remove = new ImageButton(context);
            remove.setImageResource(android.R.drawable.ic_menu_delete);
            remove.setContentDescription(com.tokai.module.i18n.UiText.get(R.string.automation_ui_delete_reply));
            remove.setTooltipText(com.tokai.module.i18n.UiText.get(R.string.automation_ui_delete_reply));
            EditText[] values = {match, response};
            remove.setOnClickListener(ignored -> { editors.remove(values); form.removeView(entry); });
            entry.addView(!useMiuix ? remove : MiuixExtraControls.iconAction(rowContext(),
                    remove, android.R.drawable.ic_menu_delete), new LinearLayout.LayoutParams(dp(48), dp(48)));
            editors.add(values);
            form.addView(entry, matchWrap());
        };
        for (int index = 0; index < saved.length(); index++) {
            add.run();
            EditText[] entry = editors.get(editors.size() - 1);
            if (keyword) {
                JSONObject pair = saved.optJSONObject(index);
                if (pair != null) { entry[0].setText(pair.optString("keyword")); entry[1].setText(pair.optString("reply")); }
            } else entry[1].setText(saved.optString(index));
        }
        ImageButton addButton = new ImageButton(context);
        addButton.setImageResource(android.R.drawable.ic_input_add);
        addButton.setContentDescription(com.tokai.module.i18n.UiText.get(R.string.automation_ui_add_reply));
        addButton.setTooltipText(com.tokai.module.i18n.UiText.get(R.string.automation_ui_add_reply));
        addButton.setOnClickListener(ignored -> add.run());
        form.addView(!useMiuix ? addButton : MiuixExtraControls.iconAction(rowContext(),
                addButton, android.R.drawable.ic_input_add), new LinearLayout.LayoutParams(dp(48), dp(48)));
        showOptionDialog(new MiuixDialogBuilder(context, useMiuix).setTitle(keyword ? com.tokai.module.i18n.UiText.get(R.string.automation_ui_keyword_rules) : com.tokai.module.i18n.UiText.get(R.string.automation_ui_random_content))
                .setView(scrolling(form)).setNegativeButton(com.tokai.module.i18n.UiText.get(R.string.automation_ui_cancel), null).setPositiveButton(com.tokai.module.i18n.UiText.get(R.string.automation_ui_save), (dialog, which) -> {
                    JSONArray entries = new JSONArray();
                    for (EditText[] item : editors) {
                        String reply = item[1].getText().toString().trim();
                        if (reply.isEmpty()) continue;
                        try {
                            if (keyword) {
                                String match = item[0].getText().toString().trim();
                                if (!match.isEmpty()) entries.put(new JSONObject().put("keyword", match).put("reply", reply));
                            } else entries.put(reply);
                        } catch (org.json.JSONException ignored) { }
                    }
                    repository.putString(key, entries.toString());
                }).create());
    }

    private void addChoiceOption(LinearLayout panel, String title, String key, String[] labels, String[] values, String fallback, String stableTitle) {
        addChoiceOption(panel, title, key, labels, values, fallback, null, stableTitle);
    }

    private void addAutomationModelOption(LinearLayout panel, String kind) {
        OptionRow[] preview = new OptionRow[1];
        preview[0] = addOption(panel, com.tokai.module.i18n.UiText.get(R.string.automation_ui_model_config), "", automationModelPreview(kind), true, () -> {
            AiModelSettings settings;
            try { settings = AiModelSettings.read(repository); }
            catch (RuntimeException failure) { toast(safeError(failure)); return; }
            String key = AiModelSettings.automationModelKey(kind);
            List<String> ids = new ArrayList<>(), labels = new ArrayList<>();
            ids.add("");
            labels.add(com.tokai.module.i18n.UiText.get(R.string.automation_ui_follow_global_model));
            for (AiModelConfig config : settings.configs) {
                ids.add(config.id);
                labels.add(automationModelLabel(config));
            }
            int selected = ids.indexOf(repository.getString(key, ""));
            showOptionDialog(new MiuixDialogBuilder(context, useMiuix)
                    .setTitle("reply".equals(kind) ? com.tokai.module.i18n.UiText.get(R.string.automation_ui_reply_model) : com.tokai.module.i18n.UiText.get(R.string.automation_ui_comment_model))
                    .setSingleChoiceItems(labels.toArray(new String[0]), selected, (dialog, which) -> {
                        if (isActive(panel)) {
                            repository.putString(key, ids.get(which));
                            preview[0].setValue(automationModelPreview(kind));
                        }
                        dialog.dismiss();
                    }).setNeutralButton(com.tokai.module.i18n.UiText.get(R.string.automation_ui_manage_models), (dialog, which) ->
                            new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                                if (isActive(panel)) showModels.run();
                            }))
                    .setNegativeButton(com.tokai.module.i18n.UiText.get(R.string.automation_ui_cancel), null).create());
        }, "模型配置");
    }

    private String automationModelPreview(String kind) {
        String id = repository.getString(AiModelSettings.automationModelKey(kind), "");
        try {
            AiModelConfig selected = AiModelSettings.readForAutomation(repository, kind).selected();
            return id.isEmpty() ? com.tokai.module.i18n.UiText.get(R.string.automation_ui_global_model_label, automationModelLabel(selected)) : automationModelLabel(selected);
        } catch (RuntimeException ignored) {
            return id.isEmpty() ? com.tokai.module.i18n.UiText.get(R.string.automation_ui_global_model_disabled) : com.tokai.module.i18n.UiText.get(R.string.automation_ui_model_unavailable);
        }
    }

    private static String automationModelLabel(AiModelConfig config) {
        return config.name + " · " + config.modelName + " · " + (config.streaming() ? com.tokai.module.i18n.UiText.get(R.string.automation_ui_streaming) : com.tokai.module.i18n.UiText.get(R.string.automation_ui_non_streaming));
    }

    private void addChoiceOption(LinearLayout panel, String title, String key, String[] labels, String[] values, String fallback, Runnable changed, String stableTitle) {
        String selected = repository.getString(key, fallback);
        int initial = java.util.Arrays.asList(values).indexOf(selected);
        int selectedIndex = Math.max(0, initial);
        OptionRow[] preview = new OptionRow[1];
        OptionRow row = addOption(panel, title, "", labels[selectedIndex], true, () -> {
            int current = Math.max(0, java.util.Arrays.asList(values).indexOf(repository.getString(key, fallback)));
            showOptionDialog(new MiuixDialogBuilder(context, useMiuix).setTitle(title)
                    .setSingleChoiceItems(labels, current, (dialog, which) -> {
                        repository.putString(key, values[which]);
                        preview[0].setValue(labels[which]);
                        dialog.dismiss();
                        if (changed != null) changed.run();
                    }).setNegativeButton(com.tokai.module.i18n.UiText.get(R.string.automation_ui_cancel), null).create());
        }, stableTitle);
        preview[0] = row;
    }

    private void addMultiChoiceCsvOption(LinearLayout panel, String title, String key, String[] labels, String[] values, String stableTitle) {
        OptionRow[] preview = new OptionRow[1];
        preview[0] = addOption(panel, title, "", scopeLabels(labels, values, repository.getString(key, "")), true, () -> {
            java.util.Set<String> selected = AutomationScopePolicy.targets(repository.getString(key, ""));
            boolean[] checked = new boolean[values.length];
            for (int index = 0; index < values.length; index++) checked[index] = selected.contains(values[index]);
            showOptionDialog(new MiuixDialogBuilder(context, useMiuix).setTitle(title)
                    .setMultiChoiceItems(labels, checked, (dialog, which, value) -> checked[which] = value)
                    .setNegativeButton(com.tokai.module.i18n.UiText.get(R.string.automation_ui_cancel), null).setPositiveButton(com.tokai.module.i18n.UiText.get(R.string.automation_ui_save), (dialog, which) -> {
                        List<String> next = new ArrayList<>();
                        for (int index = 0; index < values.length; index++) if (checked[index]) next.add(values[index]);
                        String saved = String.join(",", next);
                        repository.putString(key, saved);
                        preview[0].setValue(scopeLabels(labels, values, saved));
                    }).create());
        }, stableTitle);
    }

    private static String scopeLabels(String[] labels, String[] values, String configured) {
        java.util.Set<String> selected = AutomationScopePolicy.targets(configured);
        List<String> result = new ArrayList<>();
        for (int index = 0; index < values.length; index++) if (selected.contains(values[index])) result.add(labels[index]);
        return result.isEmpty() ? com.tokai.module.i18n.UiText.get(R.string.automation_ui_not_selected) : String.join("、", result);
    }

    private void withNativeContacts(boolean includeMembers, Runnable showSelection) {
        if (closed) return;
        java.util.concurrent.atomic.AtomicBoolean waiting = new java.util.concurrent.atomic.AtomicBoolean(true);
        AutoCloseable[] request = new AutoCloseable[1];
        AlertDialog progress = new MiuixDialogBuilder(context, useMiuix).setTitle(com.tokai.module.i18n.UiText.get(R.string.automation_ui_load_contacts))
                .setMessage(includeMembers ? com.tokai.module.i18n.UiText.get(R.string.automation_ui_loading_members) : com.tokai.module.i18n.UiText.get(R.string.automation_ui_loading_conversations))
                .setNegativeButton(com.tokai.module.i18n.UiText.get(R.string.automation_ui_cancel), null).create();
        showOptionDialog(progress, () -> {
            if (waiting.getAndSet(false) && request[0] != null) {
                try { request[0].close(); } catch (Exception ignored) { }
            }
        });
        request[0] = NativeMessagingHooks.loadContacts(includeMembers, success -> {
            if (!waiting.getAndSet(false) || closed) return;
            progress.dismiss();
            if (success) showSelection.run();
            else showOptionDialog(new MiuixDialogBuilder(context, useMiuix).setTitle(com.tokai.module.i18n.UiText.get(R.string.automation_ui_contacts_unavailable))
                    .setMessage(com.tokai.module.i18n.UiText.get(R.string.automation_ui_contacts_failed))
                    .setPositiveButton(com.tokai.module.i18n.UiText.get(R.string.automation_ui_continue_selection), (dialog, which) -> { if (!closed) showSelection.run(); })
                    .setNegativeButton(com.tokai.module.i18n.UiText.get(R.string.automation_ui_back), null).create());
        });
    }

    private void showTargetSelection(String title, String key, String stableTitle) {
        withNativeContacts(false, () -> showTargetSelectionLoaded(title, key, stableTitle));
    }

    private void showTargetSelectionLoaded(String title, String key, String stableTitle) {
        java.util.Map<String, String> contacts = NativeMessagingHooks.availableContacts();
        if (contacts.isEmpty()) {
            showTargetIds(title, key);
            return;
        }
        java.util.Set<String> selected = com.tokai.module.feature.ReplyPolicy.targets(repository.getString(key, ""));
        showProfileSelection(title, NativeMessagingHooks.availableContactProfiles(), selected,
                next -> repository.putString(key, String.join(",", next)), () -> showTargetIds(title, key), stableTitle);
    }

    private void showTargetIds(String title, String key) {
        LinearLayout form = form();
        EditText input = formInput(form, com.tokai.module.i18n.UiText.get(R.string.automation_ui_user_ids_hint), repository.getString(key, ""), false, "用户 UID，以逗号分隔");
        showOptionDialog(new MiuixDialogBuilder(context, useMiuix).setTitle(title).setView(form)
                .setNegativeButton(com.tokai.module.i18n.UiText.get(R.string.automation_ui_cancel), null).setPositiveButton(com.tokai.module.i18n.UiText.get(R.string.automation_ui_save), (dialog, which) ->
                        repository.putString(key, String.join(",", com.tokai.module.feature.ReplyPolicy.targets(input.getText().toString())))).create());
    }

    private EditText formInput(LinearLayout parent, String label, String value, boolean secret, String stableTitle) {
        TextView heading = text(label, 14, TEXT, false);
        LinearLayout.LayoutParams headingParams = matchWrap();
        headingParams.topMargin = dp(10);
        parent.addView(heading, headingParams);
        EditText input = !useMiuix ? new EditText(context)
                : MiuixFormControls.input(context, label, value, secret, stableTitle);
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

    private Button formButton() {
        return !useMiuix ? new Button(context) : MiuixFormControls.button(context);
    }

    private View addEditableOption(LinearLayout panel, String title, String summary, String value, boolean numeric,
                                   boolean enabled, ValueTextListener listener, String stableTitle) {
        String[] current = {value};
        OptionRow[] preview = new OptionRow[1];
        OptionRow row = addOption(panel, title, summary, value, enabled, () -> {
            EditText input = !useMiuix ? new EditText(context)
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
            AlertDialog editor = new MiuixDialogBuilder(context, useMiuix).setTitle(title).setView(form)
                    .setNegativeButton(com.tokai.module.i18n.UiText.get(R.string.automation_ui_cancel), null).setPositiveButton(com.tokai.module.i18n.UiText.get(R.string.automation_ui_save), null).create();
            showOptionDialog(editor);
            editor.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(ignored -> {
                if (closed || !editor.isShowing()) return;
                String candidate = input.getText().toString().trim();
                try {
                    listener.onValue(candidate);
                    current[0] = candidate;
                    preview[0].setValue(candidate.isEmpty() ? com.tokai.module.i18n.UiText.get(R.string.automation_ui_not_set) : candidate);
                    editor.dismiss();
                } catch (RuntimeException failure) { toast(safeError(failure)); }
            });
        }, stableTitle);
        preview[0] = row;

        return row.view;
    }

    private OptionRow addOption(LinearLayout panel, String title, String summary, String value, boolean enabled, Runnable action, String stableTitle) {
        Runnable guarded = action == null ? null : () -> { if (isActive(panel)) action.run(); };
        Context rowContext = rowContext(panel);
        if (rowContext != null) {
            MiuixSettingsViews.Option option = new MiuixSettingsViews.Option(
                    rowContext, title, summary, value, enabled, guarded, stableTitle);
            panel.addView(option.getView(), matchWrap());
            return new OptionRow(option.getView(), option::setValue, option::setEnabled, option::setColor);
        }
        LinearLayout row = new LinearLayout(context);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(12), 0, dp(12));
        row.setMinimumHeight(dp(58));
        row.setTag("settings.option." + stableTitle);
        row.setContentDescription(title);
        row.setEnabled(enabled);
        row.setFocusable(action != null);
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
            preview = text(value.isEmpty() ? com.tokai.module.i18n.UiText.get(R.string.automation_ui_not_set) : value, 13, MUTED, false);
            preview.setTag("settings.value." + stableTitle);
            preview.setMaxLines(3);
            preview.setEllipsize(android.text.TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams params = matchWrap();
            params.topMargin = dp(5);
            labels.addView(preview, params);
        }
        row.addView(labels, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        if (guarded != null) {
            TextView arrow = text("›", 24, MUTED, false);
            arrow.setGravity(Gravity.CENTER);
            row.addView(arrow, new LinearLayout.LayoutParams(dp(32), dp(32)));
            row.setOnClickListener(view -> { if (view.isEnabled()) guarded.run(); });
        }
        panel.addView(row, matchWrap());
        addDividerTo(panel);
        TextView valuePreview = preview;
        View[] colorSwatch = new View[1];
        return new OptionRow(row, newValue -> {
            if (valuePreview != null) valuePreview.setText(newValue);
        }, row::setEnabled, color -> {
            if (colorSwatch[0] == null) {
                colorSwatch[0] = new View(context);
                labels.addView(colorSwatch[0], new LinearLayout.LayoutParams(dp(24), dp(24)));
            }
            colorSwatch[0].setBackground(makeCircle(color));
        });
    }

    private ToggleControl addSwitch(LinearLayout panel, String title, String summary, boolean checked, boolean enabled, ValueListener listener, String stableTitle) {
        ValueListener guarded = value -> { if (isActive(panel)) listener.onValue(value); };
        Context rowContext = rowContext(panel);
        if (rowContext != null) {
            MiuixSettingsViews.Toggle toggle = new MiuixSettingsViews.Toggle(
                    rowContext, title, summary, checked, enabled, guarded::onValue, stableTitle);
            ToggleControl control = new ToggleControl(toggle::setChecked, toggle::setEnabled,
                    toggle::setOnLongClickListener, toggle::setSummary);
            panel.addView(toggle.getView(), matchWrap());

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

        toggle.setOnCheckedChangeListener((button, value) -> guarded.onValue(value));
        row.addView(toggle, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        panel.addView(row, matchWrap());
        addDividerTo(panel);
        return new ToggleControl(toggle::setChecked, toggle::setEnabled, toggle::setOnLongClickListener, value -> {
            description.setText(value);
            description.setVisibility(value == null || value.isEmpty() ? View.GONE : View.VISIBLE);
        });
    }

    private void addLocalSwitch(LinearLayout panel, String title, String summary, String key, String stableTitle) {
        addSwitch(panel, title, summary, repository.getBoolean(key, false), true,
                value -> repository.putBoolean(key, value), stableTitle);
    }

    private void addDividerTo(LinearLayout parent) {
        View divider = new View(context);
        divider.setBackgroundColor(LINE);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        parent.addView(divider, p);
    }

    private GradientDrawable makeCircle(int color) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setShape(GradientDrawable.OVAL);
        return drawable;
    }

    private TextView text(String value, float size, int color, boolean bold) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    private void toast(String message) {
        android.widget.Toast.makeText(context, message == null ? com.tokai.module.i18n.UiText.get(R.string.automation_ui_operation_failed) : message,
                android.widget.Toast.LENGTH_SHORT).show();
    }

    private static String safeError(Throwable failure) {
        if (failure instanceof AutomationMessageSettings.ValidationException) {
            switch (((AutomationMessageSettings.ValidationException) failure).issue) {
                case DAILY_LIMIT: return com.tokai.module.i18n.UiText.get(R.string.automation_ui_validation_daily_limit);
                case COOLDOWN: return com.tokai.module.i18n.UiText.get(R.string.automation_ui_validation_cooldown);
                case DELAY: return com.tokai.module.i18n.UiText.get(R.string.automation_ui_validation_delay);
                case NOTICE: return com.tokai.module.i18n.UiText.get(R.string.automation_ui_validation_notice);
                case THREAD_LIMIT: return com.tokai.module.i18n.UiText.get(R.string.automation_ui_validation_thread_limit);
                case TIP: return com.tokai.module.i18n.UiText.get(R.string.automation_ui_validation_tip);
                case SCOPE: return com.tokai.module.i18n.UiText.get(R.string.automation_ui_validation_scope);
            }
        }
        String message = failure.getMessage();
        if (message == null) return failure.getClass().getSimpleName();
        return message.length() > 220 ? message.substring(0, 220) : message;
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

    private interface ValueListener { void onValue(boolean value); }
    private interface ValueTextListener { void onValue(String value); }
}
