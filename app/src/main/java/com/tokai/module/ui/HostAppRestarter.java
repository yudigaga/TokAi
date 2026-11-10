package com.tokai.module.ui;

import com.tokai.module.R;
import com.tokai.module.i18n.UiText;

import com.tokai.module.diagnostics.TokAiLog;

import android.app.Activity;
import android.content.Intent;
import android.content.ComponentName;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.ResultReceiver;
import com.tokai.module.BuildConfig;

import java.util.function.Consumer;

/** Hands restart to a foreground module relay before terminating the host process. */
final class HostAppRestarter {
    static final String HOST_PACKAGE = "com.zhiliaoapp.musically";
    static final String RELAY_PACKAGE = BuildConfig.APPLICATION_ID;
    static final String RELAY_CLASS = "com.tokai.module.ui.HostRestartActivity";
    static final String ACTION_RESTART_RELAY = BuildConfig.APPLICATION_ID + ".action.RESTART_HOST";
    static final String EXTRA_TARGET_PACKAGE = "tokai.restart.target_package";
    static final String EXTRA_RESULT_RECEIVER = "tokai.restart.result_receiver";
    static final String EXTRA_IDENTITY = "tokai.restart.identity";
    private final Scheduler scheduler;

    interface AcknowledgmentWriter {
        boolean commit(String identity);
    }

    interface Scheduler {
        PreparedRestart prepare(Activity activity) throws Exception;
    }

    interface PreparedRestart {
        void terminate();
        void cancel();
        default void start(String identity) { }
        default void setFailureListener(Consumer<String> listener) { }
    }

    interface Terminator {
        void terminate(Activity activity);
    }

    HostAppRestarter() {
        this(new AndroidScheduler(activity -> {
            activity.finishAffinity();
            Process.killProcess(Process.myPid());
        }));
    }

    HostAppRestarter(Scheduler scheduler) {
        if (scheduler == null) throw new IllegalArgumentException("Scheduler is required");
        this.scheduler = scheduler;
    }

    Result restart(Activity activity, String identity, AcknowledgmentWriter acknowledgment) {
        return restart(activity, identity, acknowledgment, ignored -> { });
    }

    Result restart(Activity activity, String identity, AcknowledgmentWriter acknowledgment,
                   Consumer<String> failureListener) {
        TokAiLog.Operation trace = TokAiLog.begin("restart", "adaptation_confirmation");
        if (identity == null || identity.isEmpty() || acknowledgment == null) {
            trace.finish(TokAiLog.Outcome.SKIPPED, "missing_identity_or_writer", "restart_not_requested=true");
            return Result.failed("无法保存适配确认，请重试。", R.string.common_ui_confirmation_save_failed, "");
        }
        boolean saved;
        try {
            saved = acknowledgment.commit(identity);
        } catch (RuntimeException failure) {
            trace.error("confirmation_commit", failure); trace.finish(TokAiLog.Outcome.FAILED, "confirmation_write_failed", "");
            return Result.failed("无法保存适配确认，请重试：" + reason(failure), R.string.common_ui_confirmation_save_failed, reason(failure));
        }
        if (!saved) {
            trace.finish(TokAiLog.Outcome.FAILED, "confirmation_write_failed", "commit=false");
            return Result.failed("无法保存适配确认，请重试。", R.string.common_ui_confirmation_save_failed, "");
        }
        // Restart availability must not invalidate a successfully saved scan confirmation.
        PreparedRestart prepared;
        try {
            prepared = scheduler.prepare(activity);
            if (prepared == null) throw new IllegalStateException("Restart scheduling returned no handle");
        } catch (Exception failure) {
            trace.error("restart_prepare", failure); trace.finish(TokAiLog.Outcome.FAILED, "restart_prepare_failed", "confirmation_saved=true");
            return Result.restartFailed("无法安排TikTok重启：" + reason(failure), R.string.common_ui_m011, reason(failure));
        }
        prepared.setFailureListener(message -> {
            trace.finish(TokAiLog.Outcome.FAILED, "restart_relay_failed", "confirmation_saved=true;detail_ref=" + TokAiLog.reference(message));
            if (failureListener != null) failureListener.accept(message);
        });
        try {
            trace.stage("relay_start", "confirmation_saved=true");
            prepared.start(identity);
        } catch (RuntimeException failure) {
            trace.error("relay_start", failure); trace.finish(TokAiLog.Outcome.FAILED, "relay_start_failed", "");
            cancel(prepared);
            return Result.restartFailed("无法打开重启中转页面：" + reason(failure), R.string.common_ui_m012, reason(failure));
        }
        try {
            trace.stage("termination_request", "relay_requested=true;restart_completion=unobserved");
            prepared.terminate();
            trace.finish(TokAiLog.Outcome.UNCONFIRMED, "restart_requested", "process_relaunch=unobserved");
            return Result.success();
        } catch (RuntimeException failure) {
            trace.error("termination_request", failure); trace.finish(TokAiLog.Outcome.FAILED, "termination_request_failed", "");
            cancel(prepared);
            return Result.restartFailed("无法结束TikTok进程：" + reason(failure), R.string.common_ui_m013, reason(failure));
        }
    }

    private static void cancel(PreparedRestart prepared) {
        try {
            prepared.cancel();
        } catch (RuntimeException ignored) {
            // Cancellation must not hide the original failure.
        }
    }

    private static String reason(Exception failure) {
        String message = failure.getMessage();
        return message == null || message.isEmpty() ? failure.getClass().getSimpleName() : message;
    }

    static final class Result {
        private final boolean successful;
        private final boolean confirmationSaved;
        private final String message;
        private final int displayRes;
        private final String displayDetail;

        private Result(boolean successful, boolean confirmationSaved, String message, int displayRes, String displayDetail) {
            this.successful = successful;
            this.confirmationSaved = confirmationSaved;
            this.message = message;
            this.displayRes = displayRes;
            this.displayDetail = displayDetail;
        }

        static Result success() { return new Result(true, true, "", 0, ""); }
        static Result failed(String message) { return failed(message, 0, ""); }
        static Result failed(String message, int displayRes, String detail) { return new Result(false, false, message, displayRes, detail); }
        static Result restartFailed(String message) { return restartFailed(message, 0, ""); }
        static Result restartFailed(String message, int displayRes, String detail) { return new Result(false, true, message, displayRes, detail); }
        boolean isSuccessful() { return successful; }
        boolean isConfirmationSaved() { return confirmationSaved; }
        String getMessage() { return message; }
        String getDisplayMessage() {
            if (displayRes == 0) return message;
            String display = UiText.get(displayRes);
            return displayDetail.isEmpty() ? display : display + "\n" + displayDetail;
        }
    }

    static final class AndroidScheduler implements Scheduler {
        private final Terminator terminator;

        AndroidScheduler(Terminator terminator) {
            if (terminator == null) throw new IllegalArgumentException("Terminator is required");
            this.terminator = terminator;
        }

        @Override public PreparedRestart prepare(Activity activity) {
            if (activity == null || activity.isFinishing() || activity.isDestroyed()) {
                throw new IllegalStateException("当前TikTok页面不可用");
            }
            String hostPackage = activity.getPackageName();
            Intent launcher = activity.getPackageManager().getLaunchIntentForPackage(hostPackage);
            if (launcher == null || launcher.getComponent() == null
                    || !hostPackage.equals(launcher.getComponent().getPackageName())) {
                throw new IllegalStateException("未找到TikTok启动页面");
            }
            ComponentName relay = new ComponentName(RELAY_PACKAGE, RELAY_CLASS);
            Handler callbackHandler = new Handler(Looper.getMainLooper());
            RelayState state = new RelayState(activity, callbackHandler, terminator);
            return new PreparedRestart() {
                @Override public void start(String identity) {
                    if (identity == null || identity.isEmpty()) {
                        throw new IllegalStateException("适配身份无效");
                    }
                    Intent handoff = new Intent(ACTION_RESTART_RELAY).setComponent(relay)
                            .putExtra(EXTRA_TARGET_PACKAGE, hostPackage)
                            .putExtra(EXTRA_IDENTITY, identity)
                            .putExtra(EXTRA_RESULT_RECEIVER, state.receiver);
                    try {
                        activity.startActivity(handoff);
                    } catch (RuntimeException failure) {
                        throw new IllegalStateException("无法打开重启中转页面", failure);
                    }
                }
                @Override public void terminate() { state.requestTermination(); }
                @Override public void cancel() { state.cancel(); }
                @Override public void setFailureListener(Consumer<String> listener) {
                    state.failureListener = listener;
                }
            };
        }

        private static final class RelayState {
            private static final long READY_TIMEOUT_MILLIS = 5000L;
            private final Activity activity;
            private final Handler handler;
            private final Terminator terminator;
            private boolean ready;
            private boolean requested;
            private boolean cancelled;
            private boolean terminated;
            private Consumer<String> failureListener = ignored -> { };
            private final ResultReceiver receiver;
            private final Runnable timeout = () -> fail("等待重启中转页面超时。");

            RelayState(Activity activity, Handler handler, Terminator terminator) {
                this.activity = activity;
                this.handler = handler;
                this.terminator = terminator;
                receiver = new ResultReceiver(handler) {
                    @Override protected void onReceiveResult(int resultCode, android.os.Bundle data) {
                        if (resultCode != 0) {
                            fail("TikTok重启中转失败。");
                            return;
                        }
                        ready = true;
                        terminateIfReady();
                    }
                };
            }

            void requestTermination() {
                if (cancelled || requested) return;
                requested = true;
                handler.postDelayed(timeout, READY_TIMEOUT_MILLIS);
                terminateIfReady();
            }

            void terminateIfReady() {
                if (!ready || !requested || cancelled || terminated) return;
                terminated = true;
                handler.removeCallbacks(timeout);
                try { terminator.terminate(activity); }
                catch (RuntimeException failure) { fail("无法结束TikTok进程：" + reason(failure)); }
            }

            void fail(String message) {
                if (cancelled) return;
                cancel();
                failureListener.accept(message);
            }

            void cancel() {
                cancelled = true;
                handler.removeCallbacks(timeout);
            }
        }
    }
}
