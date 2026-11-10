package com.tokai.module.ui;

import com.tokai.module.R;
import com.tokai.module.i18n.UiText;
import com.tokai.module.i18n.ModuleLocale;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Offline, searchable help shared by the host settings and the module application. */
public final class HelpContent {
    private static String cachedLanguageTag;
    private static List<Section> cachedSections;

    private HelpContent() { }

    public static synchronized List<Section> sections() {
        String languageTag = ModuleLocale.locale().toLanguageTag();
        if (cachedSections == null || !languageTag.equals(cachedLanguageTag)) {
            cachedSections = createSections();
            cachedLanguageTag = languageTag;
        }
        return cachedSections;
    }

    public static List<Section> search(String query) {
        List<Section> sections = sections();
        String normalized = normalize(query);
        if (normalized.isEmpty()) return sections;
        String[] terms = normalized.split(" ");
        List<Section> result = new ArrayList<>();
        for (Section section : sections) {
            boolean matches = true;
            for (String term : terms) {
                if (!section.searchable.contains(term)) {
                    matches = false;
                    break;
                }
            }
            if (matches) result.add(section);
        }
        return Collections.unmodifiableList(result);
    }

    private static String normalize(String text) {
        if (text == null) return "";
        StringBuilder normalized = new StringBuilder();
        boolean space = false;
        for (int index = 0; index < text.length(); index++) {
            char value = text.charAt(index);
            if (Character.isWhitespace(value) || Character.isSpaceChar(value)) {
                space = normalized.length() > 0;
            } else {
                if (space) normalized.append(' ');
                normalized.append(value);
                space = false;
            }
        }
        return normalized.toString().toLowerCase(Locale.ROOT);
    }

    public static final class Section {
        public final String id;
        public final String stableTitle;
        public final String title;
        public final List<Part> parts;
        private final String searchable;

        private Section(String id, String stableTitle, String title, String keywords, Part... parts) {
            this.id = id;
            this.stableTitle = stableTitle;
            this.title = title;
            this.parts = Collections.unmodifiableList(new ArrayList<>(Arrays.asList(parts)));
            StringBuilder text = new StringBuilder(id).append(' ').append(title).append(' ').append(keywords);
            for (Part part : this.parts) text.append(' ').append(part.title).append(' ').append(part.text);
            searchable = normalize(text.toString());
        }
    }

    public static final class Part {
        public final String title;
        public final String text;

        private Part(String title, String text) {
            this.title = title;
            this.text = text;
        }
    }

    private static Section section(String id, String stableTitle, String title, String keywords, Part... parts) {
        return new Section(id, stableTitle, title, keywords, parts);
    }

    private static Part part(String title, String text) {
        return new Part(title, text);
    }

    private static List<Section> createSections() {
        return Collections.unmodifiableList(Arrays.asList(
                section("start", "开始使用与设置生效", UiText.get(R.string.help_start_title), UiText.get(R.string.help_start_keywords),
                        part(UiText.get(R.string.help_start_first_setup_title), UiText.get(R.string.help_start_first_setup_text)),
                        part(UiText.get(R.string.help_start_the_module_app_and_settings_inside_tiktok_title), UiText.get(R.string.help_start_the_module_app_and_settings_inside_tiktok_text)),
                        part(UiText.get(R.string.help_start_when_changes_take_effect_title), UiText.get(R.string.help_start_when_changes_take_effect_text)),
                        part(UiText.get(R.string.module_language_title), UiText.get(R.string.module_language_help))),
                section("backup", "配置备份：导入与导出", UiText.get(R.string.help_backup_title), UiText.get(R.string.help_backup_keywords),
                        part(UiText.get(R.string.help_backup_export_a_json_backup_title), UiText.get(R.string.help_backup_export_a_json_backup_text)),
                        part(UiText.get(R.string.help_backup_preview_and_import_title), UiText.get(R.string.help_backup_preview_and_import_text)),
                        part(UiText.get(R.string.help_backup_files_and_cancellation_title), UiText.get(R.string.help_backup_files_and_cancellation_text))),
                section("analytics", "本地数据分析", UiText.get(R.string.help_analytics_title), UiText.get(R.string.help_analytics_keywords),
                        part(UiText.get(R.string.help_analytics_start_recording_title), UiText.get(R.string.help_analytics_start_recording_text)),
                        part(UiText.get(R.string.help_analytics_what_the_statistics_count_title), UiText.get(R.string.help_analytics_what_the_statistics_count_text)),
                        part(UiText.get(R.string.help_analytics_view_and_clear_records_title), UiText.get(R.string.help_analytics_view_and_clear_records_text)),
                        part(UiText.get(R.string.help_analytics_profiles_and_combined_charts_title), UiText.get(R.string.help_analytics_profiles_and_combined_charts_text)),
                        part(UiText.get(R.string.help_analytics_choose_the_analysis_model_and_secondary_prompt_title), UiText.get(R.string.help_analytics_choose_the_analysis_model_and_secondary_prompt_text)),
                        part(UiText.get(R.string.help_analytics_request_ai_analysis_and_share_title), UiText.get(R.string.help_analytics_request_ai_analysis_and_share_text))),
                section("basic", "基本功能：启动与弹窗", UiText.get(R.string.help_basic_title), UiText.get(R.string.help_basic_keywords),
                        part(UiText.get(R.string.help_basic_reduce_startup_interruptions_title), UiText.get(R.string.help_basic_reduce_startup_interruptions_text)),
                        part(UiText.get(R.string.help_basic_how_this_differs_from_feed_filtering_title), UiText.get(R.string.help_basic_how_this_differs_from_feed_filtering_text))),
                section("interface", "界面与九宫格手势", UiText.get(R.string.help_interface_title), UiText.get(R.string.help_interface_keywords),
                        part(UiText.get(R.string.help_interface_organize_the_video_interface_title), UiText.get(R.string.help_interface_organize_the_video_interface_text)),
                        part(UiText.get(R.string.help_interface_assign_long_press_or_double_tap_actions_title), UiText.get(R.string.help_interface_assign_long_press_or_double_tap_actions_text)),
                        part(UiText.get(R.string.help_interface_avoid_conflicts_with_native_gestures_title), UiText.get(R.string.help_interface_avoid_conflicts_with_native_gestures_text)),
                        part(UiText.get(R.string.help_interface_top_tabs_full_screen_and_refresh_title), UiText.get(R.string.help_interface_top_tabs_full_screen_and_refresh_text))),
                section("filters", "视频筛选：过滤推荐内容", UiText.get(R.string.help_filters_title), UiText.get(R.string.help_filters_keywords),
                        part(UiText.get(R.string.help_filters_filter_by_type_title), UiText.get(R.string.help_filters_filter_by_type_text)),
                        part(UiText.get(R.string.help_filters_set_duration_and_like_thresholds_title), UiText.get(R.string.help_filters_set_duration_and_like_thresholds_text)),
                        part(UiText.get(R.string.help_filters_filter_by_text_title), UiText.get(R.string.help_filters_filter_by_text_text))),
                section("downloads", "下载相关：方式、目录与第三方 GET", UiText.get(R.string.help_downloads_title), UiText.get(R.string.help_downloads_keywords),
                        part(UiText.get(R.string.help_downloads_choose_a_method_and_save_location_title), UiText.get(R.string.help_downloads_choose_a_method_and_save_location_text)),
                        part(UiText.get(R.string.help_downloads_official_downloads_and_filename_templates_title), UiText.get(R.string.help_downloads_official_downloads_and_filename_templates_text)),
                        part(UiText.get(R.string.help_downloads_actions_in_the_download_menu_title), UiText.get(R.string.help_downloads_actions_in_the_download_menu_text)),
                        part(UiText.get(R.string.help_downloads_view_progress_or_cancel_title), UiText.get(R.string.help_downloads_view_progress_or_cancel_text)),
                        part(UiText.get(R.string.help_downloads_configure_a_general_get_request_and_arbitrary_parameters_title), UiText.get(R.string.help_downloads_configure_a_general_get_request_and_arbitrary_parameters_text)),
                        part(UiText.get(R.string.help_downloads_what_to_put_in_request_headers_title), UiText.get(R.string.help_downloads_what_to_put_in_request_headers_text)),
                        part(UiText.get(R.string.help_downloads_signing_method_title), UiText.get(R.string.help_downloads_signing_method_text)),
                        part(UiText.get(R.string.help_downloads_parse_responses_select_fields_and_name_files_title), UiText.get(R.string.help_downloads_parse_responses_select_fields_and_name_files_text))),
                section("playback", "自动播放与倍速", UiText.get(R.string.help_playback_title), UiText.get(R.string.help_playback_keywords),
                        part(UiText.get(R.string.help_playback_continuous_viewing_title), UiText.get(R.string.help_playback_continuous_viewing_text)),
                        part(UiText.get(R.string.help_playback_automatic_and_manual_speed_title), UiText.get(R.string.help_playback_automatic_and_manual_speed_text)),
                        part(UiText.get(R.string.help_playback_open_interaction_automation_title), UiText.get(R.string.help_playback_open_interaction_automation_text))),
                section("automation", "自动化总开关、预览与记录", UiText.get(R.string.help_automation_title), UiText.get(R.string.help_automation_keywords),
                        part(UiText.get(R.string.help_automation_enable_and_stop_now_title), UiText.get(R.string.help_automation_enable_and_stop_now_text)),
                        part(UiText.get(R.string.help_automation_limit_quantity_and_frequency_title), UiText.get(R.string.help_automation_limit_quantity_and_frequency_text)),
                        part(UiText.get(R.string.help_automation_sending_and_history_title), UiText.get(R.string.help_automation_sending_and_history_text)),
                        part(UiText.get(R.string.help_automation_understand_result_states_title), UiText.get(R.string.help_automation_understand_result_states_text))),
                section("auto_reply", "自动回复：私聊、群聊与评论回复", UiText.get(R.string.help_auto_reply_title), UiText.get(R.string.help_auto_reply_keywords),
                        part(UiText.get(R.string.help_auto_reply_choose_reply_content_title), UiText.get(R.string.help_auto_reply_choose_reply_content_text)),
                        part(UiText.get(R.string.help_auto_reply_set_direct_chat_scope_title), UiText.get(R.string.help_auto_reply_set_direct_chat_scope_text)),
                        part(UiText.get(R.string.help_auto_reply_set_group_chat_scope_title), UiText.get(R.string.help_auto_reply_set_group_chat_scope_text)),
                        part(UiText.get(R.string.help_auto_reply_enable_voice_replies_title), UiText.get(R.string.help_auto_reply_enable_voice_replies_text)),
                        part(UiText.get(R.string.help_auto_reply_receive_comment_replies_title), UiText.get(R.string.help_auto_reply_receive_comment_replies_text)),
                        part(UiText.get(R.string.help_auto_reply_foreground_processing_and_reopening_scans_title), UiText.get(R.string.help_auto_reply_foreground_processing_and_reopening_scans_text)),
                        part(UiText.get(R.string.help_auto_reply_comment_reply_quotas_notices_and_history_title), UiText.get(R.string.help_auto_reply_comment_reply_quotas_notices_and_history_text))),
                section("auto_comment", "自动评论：作品范围与素材识别", UiText.get(R.string.help_auto_comment_title), UiText.get(R.string.help_auto_comment_keywords),
                        part(UiText.get(R.string.help_auto_comment_configure_comment_content_title), UiText.get(R.string.help_auto_comment_configure_comment_content_text)),
                        part(UiText.get(R.string.help_auto_comment_limit_creators_and_post_content_title), UiText.get(R.string.help_auto_comment_limit_creators_and_post_content_text)),
                        part(UiText.get(R.string.help_auto_comment_let_ai_read_images_and_video_title), UiText.get(R.string.help_auto_comment_let_ai_read_images_and_video_text)),
                        part(UiText.get(R.string.help_auto_comment_queue_retries_and_checking_results_title), UiText.get(R.string.help_auto_comment_queue_retries_and_checking_results_text)),
                        part(UiText.get(R.string.help_auto_comment_enable_voice_comments_title), UiText.get(R.string.help_auto_comment_enable_voice_comments_text))),
                section("comments", "评论相关：后缀、长按与语音", UiText.get(R.string.help_comments_title), UiText.get(R.string.help_comments_keywords),
                        part(UiText.get(R.string.help_comments_suffixes_and_colored_comments_title), UiText.get(R.string.help_comments_suffixes_and_colored_comments_text)),
                        part(UiText.get(R.string.help_comments_long_press_comment_tools_title), UiText.get(R.string.help_comments_long_press_comment_tools_text)),
                        part(UiText.get(R.string.help_comments_replace_your_recorded_comment_audio_title), UiText.get(R.string.help_comments_replace_your_recorded_comment_audio_text)),
                        part(UiText.get(R.string.help_comments_global_hold_to_send_text_to_speech_title), UiText.get(R.string.help_comments_global_hold_to_send_text_to_speech_text)),
                        part(UiText.get(R.string.help_comments_manual_comment_text_to_speech_title), UiText.get(R.string.help_comments_manual_comment_text_to_speech_text))),
                section("streak", "自动续火花：每日发送与时间窗口", UiText.get(R.string.help_streak_title), UiText.get(R.string.help_streak_keywords),
                        part(UiText.get(R.string.help_streak_set_targets_and_content_title), UiText.get(R.string.help_streak_set_targets_and_content_text)),
                        part(UiText.get(R.string.help_streak_limit_sending_times_title), UiText.get(R.string.help_streak_limit_sending_times_text)),
                        part(UiText.get(R.string.help_streak_read_the_message_from_an_api_title), UiText.get(R.string.help_streak_read_the_message_from_an_api_text)),
                        part(UiText.get(R.string.help_streak_success_unknown_results_and_manual_retry_title), UiText.get(R.string.help_streak_success_unknown_results_and_manual_retry_text))),
                section("components", "组件隐藏、透明度与自定义视图", UiText.get(R.string.help_components_title), UiText.get(R.string.help_components_keywords),
                        part(UiText.get(R.string.help_components_captions_and_automatic_recognition_title), UiText.get(R.string.help_components_captions_and_automatic_recognition_text)
                                + "\n" + UiText.get(R.string.module_caption_restart_help)),
                        part(UiText.get(R.string.help_components_adjust_individual_components_title), UiText.get(R.string.help_components_adjust_individual_components_text)),
                        part(UiText.get(R.string.help_components_switching_pausing_and_gesture_display_title), UiText.get(R.string.help_components_switching_pausing_and_gesture_display_text)),
                        part(UiText.get(R.string.help_components_add_custom_views_title), UiText.get(R.string.help_components_add_custom_views_text))),
                section("messages", "消息管理：防撤回与手动语音", UiText.get(R.string.help_messages_title), UiText.get(R.string.help_messages_keywords),
                        part(UiText.get(R.string.help_messages_keep_local_messages_title), UiText.get(R.string.help_messages_keep_local_messages_text)),
                        part(UiText.get(R.string.help_messages_replace_your_direct_chat_recording_title), UiText.get(R.string.help_messages_replace_your_direct_chat_recording_text)),
                        part(UiText.get(R.string.help_messages_send_synthesized_audio_with_title), UiText.get(R.string.help_messages_send_synthesized_audio_with_text))),
                section("spoof", "本地伪装：主页数量与版本显示", UiText.get(R.string.help_spoof_title), UiText.get(R.string.help_spoof_keywords),
                        part(UiText.get(R.string.help_spoof_adjust_your_profile_display_title), UiText.get(R.string.help_spoof_adjust_your_profile_display_text)),
                        part(UiText.get(R.string.help_spoof_modify_version_information_read_by_the_process_title), UiText.get(R.string.help_spoof_modify_version_information_read_by_the_process_text))),
                section("models", "扩展功能：AI 模型与消息对话", UiText.get(R.string.help_models_title), UiText.get(R.string.help_models_keywords),
                        part(UiText.get(R.string.help_models_add_and_activate_a_model_title), UiText.get(R.string.help_models_add_and_activate_a_model_text)),
                        part(UiText.get(R.string.help_models_select_separate_reply_and_comment_models_title), UiText.get(R.string.help_models_select_separate_reply_and_comment_models_text)),
                        part(UiText.get(R.string.help_models_choose_a_shared_response_mode_title), UiText.get(R.string.help_models_choose_a_shared_response_mode_text)),
                        part(UiText.get(R.string.help_models_choose_reasoning_effort_title), UiText.get(R.string.help_models_choose_reasoning_effort_text)),
                        part(UiText.get(R.string.help_models_use_module_ai_on_the_tako_page_title), UiText.get(R.string.help_models_use_module_ai_on_the_tako_page_text)),
                        part(UiText.get(R.string.help_models_add_a_contact_to_tiktok_messages_title), UiText.get(R.string.help_models_add_a_contact_to_tiktok_messages_text)),
                        part(UiText.get(R.string.help_models_primary_and_secondary_prompts_title), UiText.get(R.string.help_models_primary_and_secondary_prompts_text)),
                        part(UiText.get(R.string.help_models_advanced_request_settings_title), UiText.get(R.string.help_models_advanced_request_settings_text)),
                        part(UiText.get(R.string.help_models_test_text_images_video_and_files_title), UiText.get(R.string.help_models_test_text_images_video_and_files_text))),
                section("speech", "扩展功能：文本转语音", UiText.get(R.string.help_speech_title), UiText.get(R.string.help_speech_keywords),
                        part(UiText.get(R.string.help_speech_choose_a_source_and_preview_title), UiText.get(R.string.help_speech_choose_a_source_and_preview_text)),
                        part(UiText.get(R.string.help_speech_configure_minimax_title), UiText.get(R.string.help_speech_configure_minimax_text)),
                        part(UiText.get(R.string.help_speech_minimax_model_lists_title), UiText.get(R.string.help_speech_minimax_model_lists_text)),
                        part(UiText.get(R.string.help_speech_minimax_advanced_settings_title), UiText.get(R.string.help_speech_minimax_advanced_settings_text)),
                        part(UiText.get(R.string.help_speech_configure_breeze_blue_title), UiText.get(R.string.help_speech_configure_breeze_blue_text)),
                        part(UiText.get(R.string.help_speech_voice_tags_search_and_chinese_name_translations_title), UiText.get(R.string.help_speech_voice_tags_search_and_chinese_name_translations_text)),
                        part(UiText.get(R.string.help_speech_breeze_blue_speech_instructions_title), UiText.get(R.string.help_speech_breeze_blue_speech_instructions_text)),
                        part(UiText.get(R.string.help_speech_breeze_blue_advanced_settings_title), UiText.get(R.string.help_speech_breeze_blue_advanced_settings_text)),
                        part(UiText.get(R.string.help_speech_breeze_blue_api_and_backup_boundaries_title), UiText.get(R.string.help_speech_breeze_blue_api_and_backup_boundaries_text)),
                        part(UiText.get(R.string.help_speech_configure_fish_audio_title), UiText.get(R.string.help_speech_configure_fish_audio_text)),
                        part(UiText.get(R.string.help_speech_fish_audio_multiple_speakers_and_reference_audio_title), UiText.get(R.string.help_speech_fish_audio_multiple_speakers_and_reference_audio_text)),
                        part(UiText.get(R.string.help_speech_fish_audio_reading_and_advanced_settings_title), UiText.get(R.string.help_speech_fish_audio_reading_and_advanced_settings_text)),
                        part(UiText.get(R.string.help_speech_fish_audio_pronunciation_dictionaries_title), UiText.get(R.string.help_speech_fish_audio_pronunciation_dictionaries_text)),
                        part(UiText.get(R.string.help_speech_fish_audio_api_and_backup_boundaries_title), UiText.get(R.string.help_speech_fish_audio_api_and_backup_boundaries_text)),
                        part(UiText.get(R.string.help_speech_generated_formats_and_sending_original_files_title), UiText.get(R.string.help_speech_generated_formats_and_sending_original_files_text)),
                        part(UiText.get(R.string.help_speech_system_engine_settings_title), UiText.get(R.string.help_speech_system_engine_settings_text)),
                        part(UiText.get(R.string.help_speech_configure_synthesis_get_and_parameters_title), UiText.get(R.string.help_speech_configure_synthesis_get_and_parameters_text)),
                        part(UiText.get(R.string.help_speech_sample_responses_and_advanced_voice_settings_title), UiText.get(R.string.help_speech_sample_responses_and_advanced_voice_settings_text)),
                        part(UiText.get(R.string.help_speech_request_voice_lists_separately_title), UiText.get(R.string.help_speech_request_voice_lists_separately_text)),
                        part(UiText.get(R.string.help_speech_sending_duration_and_troubleshooting_title), UiText.get(R.string.help_speech_sending_duration_and_troubleshooting_text))),
                section("troubleshooting", "常见问题与检查顺序", UiText.get(R.string.help_troubleshooting_title), UiText.get(R.string.help_troubleshooting_keywords),
                        part(UiText.get(R.string.help_troubleshooting_global_debugging_and_logs_title), UiText.get(R.string.help_troubleshooting_global_debugging_and_logs_text)),
                        part(UiText.get(R.string.help_troubleshooting_missing_settings_or_features_that_do_not_take_effect_title), UiText.get(R.string.help_troubleshooting_missing_settings_or_features_that_do_not_take_effect_text)),
                        part(UiText.get(R.string.help_troubleshooting_failed_downloads_or_missing_files_title), UiText.get(R.string.help_troubleshooting_failed_downloads_or_missing_files_text)),
                        part(UiText.get(R.string.help_troubleshooting_automatic_replies_or_comments_do_not_trigger_title), UiText.get(R.string.help_troubleshooting_automatic_replies_or_comments_do_not_trigger_text)),
                        part(UiText.get(R.string.help_troubleshooting_model_or_third_party_api_errors_title), UiText.get(R.string.help_troubleshooting_model_or_third_party_api_errors_text)),
                        part(UiText.get(R.string.help_troubleshooting_hidden_controls_or_unknown_send_outcomes_title), UiText.get(R.string.help_troubleshooting_hidden_controls_or_unknown_send_outcomes_text)))
        ));
    }
}
