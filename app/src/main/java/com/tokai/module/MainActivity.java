package com.tokai.module;

import com.tokai.module.R;
import com.tokai.module.i18n.UiText;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import com.tokai.module.config.FrameworkConfigSync;
import com.tokai.module.config.ModuleConfigRuntime;
import com.tokai.module.diagnostics.ScanDiagnosticsChannel;
import com.tokai.module.diagnostics.HostAdaptationDiagnostics;
import com.tokai.module.diagnostics.DebugLog;
import com.tokai.module.ui.ModuleStatusScreen;
import com.tokai.module.ui.ScanStatus;

import androidx.activity.ComponentActivity;

import io.github.libxposed.service.XposedService;
import java.util.concurrent.atomic.AtomicLong;

public final class MainActivity extends ComponentActivity {
    @Override protected void attachBaseContext(android.content.Context context) {
        com.tokai.module.i18n.ModuleLocale.bind(context, new com.tokai.module.config.SettingsRepository(context));
        super.attachBaseContext(com.tokai.module.i18n.ModuleLocale.wrap(context));
    }
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicLong diagnosticsRequestId = new AtomicLong();
    private final Runnable diagnosticsPoll = new Runnable() {
        @Override
        public void run() {
            if (resumed && activeService != null && !destroyed) readScanDiagnostics();
        }
    };

    private ModuleConfigRuntime configRuntime;
    private FrameworkConfigSync.Listener configStatusListener;
    private ModuleConfigRuntime.ServiceListener configServiceListener;
    private ModuleStatusScreen settingsScreen;
    private XposedService activeService;
    private boolean destroyed;
    private boolean resumed;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        DebugLog.initialize(getApplicationContext());

        configRuntime = ModuleConfigRuntime.get(this);
        configStatusListener = (connected, message, error) -> {
            if (!destroyed && settingsScreen != null) {
                settingsScreen.setFrameworkSyncStatus(connected, message, error, configRuntime.sync().presentation());
            }
        };
        configRuntime.addStatusListener(configStatusListener);

        settingsScreen = new ModuleStatusScreen(this);
        setContentView(settingsScreen.createView());
        settingsScreen.setScanStatus(ScanStatus.pending());
        boolean serviceConnected = configRuntime.sync().isConnected();
        settingsScreen.setFrameworkSyncStatus(serviceConnected,
                serviceConnected ? UiText.get(R.string.common_ui_m122)
                        : UiText.get(R.string.common_ui_m123), null);

        configServiceListener = new ModuleConfigRuntime.ServiceListener() {
            @Override
            public void onServiceBound(XposedService service) {
                runOnUiThread(() -> handleServiceBound(service));
            }

            @Override
            public void onServiceDied(XposedService service) {
                runOnUiThread(() -> handleServiceDied(service));
            }
        };
        configRuntime.addServiceListener(configServiceListener);
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        if (activeService != null) readScanDiagnostics();
    }

    @Override
    protected void onPause() {
        resumed = false;
        diagnosticsRequestId.incrementAndGet();
        mainHandler.removeCallbacks(diagnosticsPoll);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        resumed = false;
        diagnosticsRequestId.incrementAndGet();
        mainHandler.removeCallbacks(diagnosticsPoll);
        settingsScreen = null;
        if (configRuntime != null) {
            configRuntime.removeStatusListener(configStatusListener);
            configRuntime.removeServiceListener(configServiceListener);
        }
        activeService = null;
        super.onDestroy();
    }

    private void handleServiceBound(XposedService service) {
        if (destroyed || service == null || configRuntime == null
                || configRuntime.service() != service) return;
        activeService = service;
        if (resumed) readScanDiagnostics();
    }

    private void handleServiceDied(XposedService service) {
        if (destroyed || service != activeService) return;
        activeService = null;
        diagnosticsRequestId.incrementAndGet();
        mainHandler.removeCallbacks(diagnosticsPoll);
    }

    private void readScanDiagnostics() {
        if (destroyed || activeService == null || settingsScreen == null) return;
        long requestId = diagnosticsRequestId.incrementAndGet();
        ScanDiagnosticsChannel.request(this, mainHandler, snapshot -> {
            if (destroyed || requestId != diagnosticsRequestId.get() || settingsScreen == null) return;
            if (snapshot == null) {
                settingsScreen.setScanStatus(new ScanStatus(UiText.get(R.string.common_ui_m124),
                        UiText.get(R.string.common_ui_m125), false, false));
                scheduleDiagnosticsPoll();
                return;
            }

            boolean complete = snapshot.getBoolean("complete", false);
            String failure = snapshot.getString("failure");
            String summary = snapshot.getString("summary");
            if (complete && summary != null && summary.startsWith("rules=0,")) {
                settingsScreen.setScanStatus(new ScanStatus(UiText.get(R.string.common_ui_m126),
                        UiText.get(R.string.common_ui_m127),
                        false, false));
            } else if (complete) {
                String detail = summary == null ? UiText.get(R.string.common_ui_m128) : summary;
                detail += "\n" + com.tokai.module.ui.CommonUiDiagnostics.describe(snapshot);
                boolean adaptationVerified = snapshot.getBoolean("host_adaptation_static_verified", false);
                boolean feedInstalled = snapshot.getBoolean("feed_hook_installed", false);
                boolean downloadInstalled = snapshot.getBoolean("download_hook_installed", false);
                boolean pendingHook = false;
                boolean hasFailure = snapshot.getInt("host_adaptation_feature_rule_gaps", 0) > 0
                        || snapshot.getInt("host_adaptation_features_uninstalled", 0) > 0
                        || snapshot.getInt("feed_ui_resource_mismatches", 0) > 0;
                if (feedInstalled) {
                    boolean enabled = snapshot.getBoolean("feed_filter_enabled", false);
                    int filteredPages = snapshot.getInt("feed_filtered_pages", 0);
                    detail += enabled
                            ? UiText.get(R.string.common_ui_m059, filteredPages)
                            : UiText.get(R.string.common_ui_m129);
                } else {
                    String hookFailure = snapshot.getString("feed_hook_failure");
                    detail += UiText.get(R.string.common_ui_m130);
                    if (hookFailure != null && !hookFailure.isEmpty()) {
                        detail += "\n" + hookFailure;
                        hasFailure = true;
                    } else pendingHook = true;
                }
                if (downloadInstalled) {
                    detail += snapshot.getBoolean("download_enabled", false)
                            ? UiText.get(R.string.common_ui_m131)
                            : UiText.get(R.string.common_ui_m132);
                } else {
                    String hookFailure = snapshot.getString("download_hook_failure");
                    detail += UiText.get(R.string.common_ui_m133);
                    if (hookFailure != null && !hookFailure.isEmpty()) {
                        detail += "\n" + hookFailure;
                        hasFailure = true;
                    } else pendingHook = true;
                }
                if (snapshot.getBoolean("version_hook_installed", false)) {
                    detail += snapshot.getBoolean("version_spoof_enabled", false)
                            ? UiText.get(R.string.common_ui_m134)
                            : UiText.get(R.string.common_ui_m135);
                } else {
                    String hookFailure = snapshot.getString("version_hook_failure");
                    detail += UiText.get(R.string.common_ui_m136);
                    if (hookFailure != null && !hookFailure.isEmpty()) {
                        detail += "\n" + hookFailure;
                        hasFailure = true;
                    } else pendingHook = true;
                }
                if (snapshot.getBoolean("top_tab_hook_installed", false)) {
                    detail += snapshot.getBoolean("top_tab_filter_enabled", false)
                            ? UiText.get(R.string.common_ui_m137)
                            : UiText.get(R.string.common_ui_m138);
                } else {
                    String hookFailure = snapshot.getString("top_tab_hook_failure");
                    detail += UiText.get(R.string.common_ui_m139);
                    if (hookFailure != null && !hookFailure.isEmpty()) {
                        detail += "\n" + hookFailure;
                        hasFailure = true;
                    } else pendingHook = true;
                }
                if (snapshot.getBoolean("feed_ui_hook_installed", false)) {
                    if (snapshot.getBoolean("feed_ui_active", false)) {
                        detail += UiText.get(R.string.common_ui_m073, snapshot.getBoolean("component_hide_configured", false) ? UiText.get(R.string.common_ui_m071) : UiText.get(R.string.common_ui_m072), snapshot.getInt("feed_ui_selected_callbacks", 0), snapshot.getInt("feed_ui_card_scans", 0), snapshot.getInt("feed_ui_view_searches", 0), snapshot.getInt("feed_ui_resource_mismatches", 0), snapshot.getInt("feed_ui_predraw_rechecks", 0), snapshot.getInt("feed_ui_reapplied_views", 0), snapshot.getInt("feed_ui_hidden_views", 0));
                        if (snapshot.getBoolean("component_hide_configured", false)) {
                            detail += snapshot.getBoolean("component_hide_temporarily_shown", false)
                                    ? UiText.get(R.string.common_ui_m140) : UiText.get(R.string.common_ui_m141);
                        } else {
                            detail += UiText.get(R.string.common_ui_m060, snapshot.getInt("feed_ui_alpha_percent", 100));
                        }
                    } else {
                        detail += UiText.get(R.string.common_ui_m142);
                    }
                } else {
                    String hookFailure = snapshot.getString("feed_ui_hook_failure");
                    detail += UiText.get(R.string.common_ui_m143);
                    if (hookFailure != null && !hookFailure.isEmpty()) {
                        detail += "\n" + hookFailure;
                        hasFailure = true;
                    }
                }
                if (snapshot.getBoolean("refresh_hook_installed", false)) {
                    detail += snapshot.getBoolean("refresh_confirm_enabled", false)
                            ? UiText.get(R.string.common_ui_m144)
                            : UiText.get(R.string.common_ui_m145);
                } else {
                    String hookFailure = snapshot.getString("refresh_hook_failure");
                    detail += UiText.get(R.string.common_ui_m146);
                    if (hookFailure != null && !hookFailure.isEmpty()) {
                        detail += "\n" + hookFailure;
                        hasFailure = true;
                    } else pendingHook = true;
                }
                if (snapshot.getBoolean("auto_like_hook_installed", false)) {
                    detail += snapshot.getBoolean("auto_like_enabled", false)
                            ? UiText.get(R.string.common_ui_m147)
                            : UiText.get(R.string.common_ui_m148);
                } else {
                    String hookFailure = snapshot.getString("auto_like_hook_failure");
                    detail += UiText.get(R.string.common_ui_m149);
                    if (hookFailure != null && !hookFailure.isEmpty()) {
                        detail += "\n" + hookFailure;
                        hasFailure = true;
                    } else pendingHook = true;
                }
                if (snapshot.getBoolean("auto_follow_hook_installed", false)) {
                    detail += snapshot.getBoolean("auto_follow_enabled", false)
                            ? UiText.get(R.string.common_ui_m150)
                            : UiText.get(R.string.common_ui_m151);
                } else {
                    String hookFailure = snapshot.getString("auto_follow_hook_failure");
                    detail += UiText.get(R.string.common_ui_m152);
                    if (hookFailure != null && !hookFailure.isEmpty()) {
                        detail += "\n" + hookFailure;
                        hasFailure = true;
                    } else pendingHook = true;
                }
                if (snapshot.getBoolean("auto_play_hook_installed", false)) {
                    detail += snapshot.getBoolean("auto_play_enabled", false)
                            ? UiText.get(R.string.common_ui_m153)
                            : UiText.get(R.string.common_ui_m154);
                    String status = snapshot.getString("auto_play_status");
                    if (status != null && !status.isEmpty()) detail += "\n" + status;
                } else {
                    String hookFailure = snapshot.getString("auto_play_hook_failure");
                    detail += UiText.get(R.string.common_ui_m155);
                    if (hookFailure != null && !hookFailure.isEmpty()) {
                        detail += "\n" + hookFailure;
                        hasFailure = true;
                    } else pendingHook = true;
                }
                if (snapshot.getBoolean("auto_comment_hook_installed", false)) {
                    detail += snapshot.getBoolean("auto_comment_enabled", false)
                            ? UiText.get(R.string.common_ui_m156)
                            : UiText.get(R.string.common_ui_m157);
                } else {
                    String hookFailure = snapshot.getString("auto_comment_hook_failure");
                    detail += UiText.get(R.string.common_ui_m158);
                    if (hookFailure != null && !hookFailure.isEmpty()) {
                        detail += "\n" + hookFailure;
                        hasFailure = true;
                    } else pendingHook = true;
                }
                int unknownStreakCount = snapshot.getInt("unknown_streak_count", 0);
                if (unknownStreakCount > 0) {
                    detail += UiText.get(R.string.common_ui_m061, unknownStreakCount);
                }
                if (snapshot.getBoolean("auto_message_hook_installed", false)) {
                    detail += snapshot.getBoolean("auto_message_enabled", false)
                            ? UiText.get(R.string.common_ui_m159)
                            : UiText.get(R.string.common_ui_m160);
                } else {
                    String hookFailure = snapshot.getString("auto_message_hook_failure");
                    detail += UiText.get(R.string.common_ui_m161);
                    if (hookFailure != null && !hookFailure.isEmpty()) {
                        detail += "\n" + hookFailure;
                        hasFailure = true;
                    } else pendingHook = true;
                }
                if (snapshot.getBoolean("comment_tools_hook_installed", false)) {
                    detail += UiText.get(R.string.common_ui_m162);
                } else {
                    String hookFailure = snapshot.getString("comment_tools_hook_failure");
                    detail += UiText.get(R.string.common_ui_m163);
                    if (hookFailure != null && !hookFailure.isEmpty()) {
                        detail += "\n" + hookFailure;
                        hasFailure = true;
                    } else pendingHook = true;
                }
                if (snapshot.getBoolean("immersive_hook_installed", false)) {
                    boolean statusHidden = snapshot.getBoolean("immersive_status_bar_enabled", false);
                    boolean navigationHidden = snapshot.getBoolean("immersive_navigation_bar_enabled", false);
                    if (statusHidden || navigationHidden) {
                        detail += UiText.get(R.string.common_ui_m076, statusHidden ? UiText.get(R.string.common_ui_m074) : "", navigationHidden ? UiText.get(R.string.common_ui_m075) : "");
                    } else {
                        detail += UiText.get(R.string.common_ui_m164);
                    }
                } else {
                    String hookFailure = snapshot.getString("immersive_hook_failure");
                    detail += UiText.get(R.string.common_ui_m165);
                    if (hookFailure != null && !hookFailure.isEmpty()) {
                        detail += "\n" + hookFailure;
                        hasFailure = true;
                    } else pendingHook = true;
                }
                if (snapshot.containsKey("additional_initialized")) {
                    if (!snapshot.getBoolean("additional_initialized")) pendingHook = true;
                    else {
                        String[][] features = {
                                {"remove_splash_ads", UiText.get(R.string.common_ui_m044)}, {"remove_update_dialog", UiText.get(R.string.common_ui_m166)},
                                {"hide_video_black_edges", UiText.get(R.string.common_ui_m167)},
                                {"hide_camera_plus", UiText.get(R.string.common_ui_m168)}, {"disable_double_tap_like", UiText.get(R.string.common_ui_m169)},
                                {"prevent_insert_video", UiText.get(R.string.common_ui_m170)},
                                {"block_long_press_speed", UiText.get(R.string.common_ui_m052)}, {"auto_speed", UiText.get(R.string.common_ui_m171)},
                                {"pause_auto_on_comments", UiText.get(R.string.common_ui_m172)}, {"smart_swipe", UiText.get(R.string.common_ui_m173)},
                                {"spoof_profile", UiText.get(R.string.common_ui_m053)}, {"prevent_message_recall", UiText.get(R.string.common_ui_m174)},
                                {"native_auto_reply", UiText.get(R.string.common_ui_m175)}, {"recall_background", UiText.get(R.string.common_ui_m054)},
                                {"media_model", UiText.get(R.string.common_ui_m176)}, {"comment_enhancements", UiText.get(R.string.common_ui_m055)},
                                {"comment_voice", UiText.get(R.string.common_ui_m056)}, {"comment_tts", UiText.get(R.string.common_ui_m177)},
                                {"dm_speech", UiText.get(R.string.common_ui_m178)}, {"toolbar_enabled", UiText.get(R.string.common_ui_m179)},
                                {"long_press_action", UiText.get(R.string.common_ui_m180)}, {"double_tap_action", UiText.get(R.string.common_ui_m181)}
                        };
                        int installed = 0;
                        StringBuilder missing = new StringBuilder();
                        for (String[] feature : features) {
                            if (snapshot.getBoolean("feature." + feature[0] + ".installed")) installed++;
                            else {
                                if (missing.length() > 0) missing.append("、");
                                missing.append(feature[1]);
                            }
                        }
                        detail += UiText.get(R.string.common_ui_m062, installed, features.length);
                        if (missing.length() > 0) {
                            detail += UiText.get(R.string.common_ui_m063, missing);
                            hasFailure = true;
                        }
                    }
                    detail += UiText.get(R.string.common_ui_m064, snapshot.getString("module_version", UiText.get(R.string.common_ui_m015)));
                }
                settingsScreen.setScanStatus(new ScanStatus(
                        !adaptationVerified ? UiText.get(R.string.common_ui_m182)
                                : hasFailure ? UiText.get(R.string.common_ui_m183) : UiText.get(R.string.common_ui_m184), detail,
                        adaptationVerified && !hasFailure && (feedInstalled || downloadInstalled
                                || snapshot.getBoolean("version_hook_installed", false)
                                || snapshot.getBoolean("top_tab_hook_installed", false)
                                || snapshot.getBoolean("feed_ui_hook_installed", false)
                                || snapshot.getBoolean("refresh_hook_installed", false)
                                || snapshot.getBoolean("auto_like_hook_installed", false)
                                || snapshot.getBoolean("auto_follow_hook_installed", false)
                                || snapshot.getBoolean("auto_play_hook_installed", false)
                                || snapshot.getBoolean("auto_comment_hook_installed", false)
                                || snapshot.getBoolean("auto_message_hook_installed", false)
                                || snapshot.getBoolean("comment_tools_hook_installed", false)), false));
                if (pendingHook) scheduleDiagnosticsPoll();
            } else if (failure != null && !failure.isEmpty()) {
                settingsScreen.setScanStatus(new ScanStatus(UiText.get(R.string.common_ui_m185), failure, false, false));
            } else {
                settingsScreen.setScanStatus(ScanStatus.pending());
                scheduleDiagnosticsPoll();
            }
        });
    }

    private void scheduleDiagnosticsPoll() {
        if (resumed && activeService != null && !destroyed) {
            mainHandler.removeCallbacks(diagnosticsPoll);
            mainHandler.postDelayed(diagnosticsPoll, 2000L);
        }
    }

}
