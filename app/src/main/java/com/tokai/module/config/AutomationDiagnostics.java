package com.tokai.module.config;

import com.tokai.module.R;

/** Local labels for a closed set of diagnostic codes and bounded elapsed times. */
public final class AutomationDiagnostics {
    private AutomationDiagnostics() { }

    public static String details(AutomationHistoryStore.Entry entry) {
        StringBuilder text = new StringBuilder("原因：").append(reason(entry.reason))
                .append("\n阶段：").append(stage(entry.stage));
        duration(text, "排队", entry.queueMillis);
        duration(text, "媒体读取", entry.mediaMillis);
        duration(text, "生成", entry.generationMillis);
        duration(text, "发送确认等待", entry.sendMillis);
        return text.toString();
    }

    private static void duration(StringBuilder text, String label, long millis) {
        if (millis >= 0) text.append('\n').append(label).append("耗时：").append(millis).append(" ms");
    }

    public static String reason(AutomationHistoryStore.Reason reason) {
        switch (reason) {
            case QUEUE_FULL: return "队列已满";
            case QUEUE_EXPIRED: return "排队已过期";
            case INVALIDATED: return "任务条件已失效";
            case CANCELLED: return "任务已取消";
            case SETTINGS_CHANGED: return "设置已变更";
            case MEDIA_FAILED: return "媒体读取失败";
            case GENERATION_TIMEOUT: return "生成超时";
            case GENERATION_FAILED: return "生成失败";
            case SPEECH_FAILED: return "语音生成失败";
            case SEND_FAILED: return "发送失败";
            case SUBMITTED_UNCONFIRMED: return "已提交，结果未确认";
            case LEDGER_WRITE_FAILED:
            case PERSISTENCE_FAILED: return "提交记录保存失败";
            case ALLOWANCE_EXHAUSTED: return "发送额度已用完";
            case DUPLICATE: return "重复任务";
            case LEDGER_FULL: return "发送记录已满，请在历史记录中核对发送状态";
            case AI_HTTP_ERROR: return "AI 接口请求失败";
            case AI_RATE_LIMITED: return "AI 接口限流";
            case AI_AUTH_FAILED: return "AI 接口认证失败";
            case INVALID_RESPONSE: return "接口响应无效";
            default: return "未记录";
        }
    }

    public static String stage(AutomationHistoryStore.Stage stage) {
        switch (stage) {
            case QUEUE: return "排队";
            case MEDIA: return "媒体读取";
            case GENERATION: return "生成";
            case SPEECH: return "语音生成";
            case SEND: return "发送";
            case COMPLETE: return "完成";
            default: return "未记录";
        }
    }
    public static String displayDetails(AutomationHistoryStore.Entry entry) {
        StringBuilder text = new StringBuilder(com.tokai.module.i18n.UiText.get(R.string.automation_ui_diagnostic_details, displayReason(entry.reason), displayStage(entry.stage)));
        displayDuration(text, com.tokai.module.i18n.UiText.get(R.string.automation_ui_stage_queue), entry.queueMillis);
        displayDuration(text, com.tokai.module.i18n.UiText.get(R.string.automation_ui_stage_media), entry.mediaMillis);
        displayDuration(text, com.tokai.module.i18n.UiText.get(R.string.automation_ui_stage_generation), entry.generationMillis);
        displayDuration(text, com.tokai.module.i18n.UiText.get(R.string.automation_ui_stage_send_wait), entry.sendMillis);
        return text.toString();
    }

    private static void displayDuration(StringBuilder text, String label, long millis) {
        if (millis >= 0) text.append(com.tokai.module.i18n.UiText.get(R.string.automation_ui_diagnostic_duration, label, millis));
    }

    public static String displayReason(AutomationHistoryStore.Reason reason) {
        switch (reason) {
            case QUEUE_FULL: return com.tokai.module.i18n.UiText.get(R.string.automation_ui_reason_queue_full);
            case QUEUE_EXPIRED: return com.tokai.module.i18n.UiText.get(R.string.automation_ui_reason_queue_expired);
            case INVALIDATED: return com.tokai.module.i18n.UiText.get(R.string.automation_ui_reason_invalidated);
            case CANCELLED: return com.tokai.module.i18n.UiText.get(R.string.automation_ui_reason_cancelled);
            case SETTINGS_CHANGED: return com.tokai.module.i18n.UiText.get(R.string.automation_ui_reason_settings_changed);
            case MEDIA_FAILED: return com.tokai.module.i18n.UiText.get(R.string.automation_ui_reason_media_failed);
            case GENERATION_TIMEOUT: return com.tokai.module.i18n.UiText.get(R.string.automation_ui_reason_generation_timeout);
            case GENERATION_FAILED: return com.tokai.module.i18n.UiText.get(R.string.automation_ui_reason_generation_failed);
            case SPEECH_FAILED: return com.tokai.module.i18n.UiText.get(R.string.automation_ui_reason_speech_failed);
            case SEND_FAILED: return com.tokai.module.i18n.UiText.get(R.string.automation_ui_reason_send_failed);
            case SUBMITTED_UNCONFIRMED: return com.tokai.module.i18n.UiText.get(R.string.automation_ui_reason_unconfirmed);
            case LEDGER_WRITE_FAILED:
            case PERSISTENCE_FAILED: return com.tokai.module.i18n.UiText.get(R.string.automation_ui_reason_persistence_failed);
            case ALLOWANCE_EXHAUSTED: return com.tokai.module.i18n.UiText.get(R.string.automation_ui_reason_allowance_exhausted);
            case DUPLICATE: return com.tokai.module.i18n.UiText.get(R.string.automation_ui_reason_duplicate);
            case LEDGER_FULL: return com.tokai.module.i18n.UiText.get(R.string.automation_ui_reason_ledger_full);
            case AI_HTTP_ERROR: return com.tokai.module.i18n.UiText.get(R.string.automation_ui_reason_ai_http);
            case AI_RATE_LIMITED: return com.tokai.module.i18n.UiText.get(R.string.automation_ui_reason_ai_rate_limit);
            case AI_AUTH_FAILED: return com.tokai.module.i18n.UiText.get(R.string.automation_ui_reason_ai_auth);
            case INVALID_RESPONSE: return com.tokai.module.i18n.UiText.get(R.string.automation_ui_reason_invalid_response);
            default: return com.tokai.module.i18n.UiText.get(R.string.automation_ui_not_recorded);
        }
    }

    public static String displayStage(AutomationHistoryStore.Stage stage) {
        switch (stage) {
            case QUEUE: return com.tokai.module.i18n.UiText.get(R.string.automation_ui_stage_queue);
            case MEDIA: return com.tokai.module.i18n.UiText.get(R.string.automation_ui_stage_media);
            case GENERATION: return com.tokai.module.i18n.UiText.get(R.string.automation_ui_stage_generation);
            case SPEECH: return com.tokai.module.i18n.UiText.get(R.string.automation_ui_stage_speech);
            case SEND: return com.tokai.module.i18n.UiText.get(R.string.automation_ui_stage_send);
            case COMPLETE: return com.tokai.module.i18n.UiText.get(R.string.automation_ui_stage_complete);
            default: return com.tokai.module.i18n.UiText.get(R.string.automation_ui_not_recorded);
        }
    }
}
