package com.tokai.module.ui;

import com.tokai.module.R;
import com.tokai.module.i18n.UiText;

/** A small presentation model; scanner details stay owned by the scan package. */
public final class ScanStatus {
    public final String title;
    public final String detail;
    public final boolean ready;
    public final boolean restartRequired;
    private final int titleRes;
    private final int detailRes;

    public ScanStatus(String title, String detail, boolean ready, boolean restartRequired) {
        this.titleRes = title == null ? R.string.common_ui_scan_waiting : 0;
        this.detailRes = detail == null ? R.string.common_ui_scan_waiting_detail : 0;
        this.title = title == null ? "等待扫描" : title;
        this.detail = detail == null ? "启动TikTok后会检查当前版本的适配情况。" : detail;
        this.ready = ready;
        this.restartRequired = restartRequired;
    }

    public String displayTitle() { return titleRes == 0 ? title : UiText.get(titleRes); }
    public String displayDetail() { return detailRes == 0 ? detail : UiText.get(detailRes); }

    public static ScanStatus pending() {
        return new ScanStatus(null, null, false, false);
    }
}
