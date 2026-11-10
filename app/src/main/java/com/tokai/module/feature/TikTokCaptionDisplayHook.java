package com.tokai.module.feature;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.TextView;
import com.tokai.module.config.CaptionVisibilitySettings;
import com.tokai.module.config.LocalOptions;
import com.tokai.module.diagnostics.TokAiLog;
import com.tokai.module.scan.HostScanReport;
import io.github.libxposed.api.XposedInterface;
import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/** Viewer-only ASR. Its layer never changes native caption models, settings, or author state. */
public final class TikTokCaptionDisplayHook implements Application.ActivityLifecycleCallbacks, AutoCloseable {
    interface Access {
        Object aweme(Object holder) throws Exception;
        View root(Object holder) throws Exception;
        boolean isCurrent(Object holder) throws Exception;
        long positionMillis(Object holder) throws Exception;
        boolean hasNativeCaptions(Object aweme) throws Exception;
        boolean unblocked(Object holder) throws Exception;
    }
    interface Recognition {
        CaptionTranscript recognize(CaptionAudioInput.Source source, MediaReadCancellation token) throws Exception;
    }
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Application application;
    private final Access access;
    private final Consumer<String> log;
    private final CaptionRecognitionSession session;
    private final AtomicLong selectionRevision = new AtomicLong();
    private final Runnable tick = this::pulse;
    private final View.OnAttachStateChangeListener attach = new View.OnAttachStateChangeListener() {
        @Override public void onViewAttachedToWindow(View v) { }
        @Override public void onViewDetachedFromWindow(View v) {
            if (candidate != null && candidate.root.get() == v) discard();
        }
    };
    private final ViewTreeObserver.OnWindowFocusChangeListener focus = focused -> {
        if (!focused) suspend(); else refresh();
    };
    private final ViewTreeObserver.OnGlobalLayoutListener layout = this::refresh;
    private BehaviorHookGroup group;
    private TikTokCaptionRecognizer recognizer;
    private CaptionPlaybackAccess playbackBinding;
    private volatile LocalOptions options;
    private volatile CaptionAudioInput.Source input;
    private volatile boolean closed;
    private WeakReference<Activity> resumed = new WeakReference<>(null);
    private Candidate candidate;
    private TextView layer;
    private long generation;
    private volatile String state = "idle";
    private volatile long lifecycleRevision;

    TikTokCaptionDisplayHook(Application application, LocalOptions options, Access access,
                            Recognition recognition, Consumer<String> log) {
        this(application, options, access, recognition, log, null);
    }

    TikTokCaptionDisplayHook(Application application, LocalOptions options, Access access,
                            Recognition recognition, Consumer<String> log,
                            java.util.concurrent.ThreadPoolExecutor worker) {
        this.application = application;
        this.options = options;
        this.access = access;
        this.log = log == null ? ignored -> { } : log;
        CaptionRecognitionSession.Backend backend = (aid, token) -> {
            CaptionAudioInput.Source captured = input;
            token.check();
            if (captured == null || !aid.equals(captured.aid)) throw new CancellationException();
            return recognition.recognize(captured, token);
        };
        CaptionRecognitionSession.Listener listener = new CaptionRecognitionSession.Listener() {
            @Override public void completed(String aid, long revision, CaptionTranscript transcript) {
                Candidate current = candidate;
                if (!accepts(current, aid, revision)) return;
                current.transcript = transcript;
                state = transcript.cues().isEmpty() ? "no_speech" : "ready";
                TokAiLog.event("force_native_captions", "recognition", state, "content_logged=false;server_result_received=true");
                render();
            }
            @Override public void failed(String aid, long revision, Throwable failure) {
                if (!accepts(candidate, aid, revision)) return;
                state = failure instanceof java.util.concurrent.TimeoutException ? "timed_out" : "failed";
                // Errors may contain signed URLs or server data. Only the class enters the log.
                TokAiLog.event("force_native_captions", "recognition", state,
                        "error_type=" + failure.getClass().getSimpleName() + ";content_logged=false");
                hideLayer();
                try { TikTokCaptionDisplayHook.this.log.accept("Caption recognition " + state); }
                catch (RuntimeException ignored) { }
            }
        };
        session = worker == null
                ? new CaptionRecognitionSession(backend, command -> main.post(command), listener)
                : new CaptionRecognitionSession(backend, command -> main.post(command), listener, worker);
    }

    public static TikTokCaptionDisplayHook install(XposedInterface framework, HostScanReport report,
                                                   ClassLoader loader, Context context,
                                                   LocalOptions options, Consumer<String> log) throws Exception {
        if (context == null || !(context.getApplicationContext() instanceof Application)) {
            throw new IllegalStateException("Caption Application unavailable");
        }
        CaptionPlaybackAccess binding = new CaptionPlaybackAccess(report, loader);
        TikTokCaptionRecognizer recognizer;
        try {
            binding.installVisibility(framework);
            recognizer = new TikTokCaptionRecognizer(report, loader);
        } catch (Exception failure) {
            binding.close();
            throw failure;
        }
        TikTokCaptionDisplayHook hook = new TikTokCaptionDisplayHook((Application) context.getApplicationContext(),
                options, new Access() {
                    @Override public Object aweme(Object holder) throws Exception { return binding.aweme(holder); }
                    @Override public View root(Object holder) throws Exception { return binding.root(holder); }
                    @Override public boolean isCurrent(Object holder) throws Exception { return binding.isCurrent(holder); }
                    @Override public long positionMillis(Object holder) throws Exception { return binding.positionMillis(holder); }
                    @Override public boolean hasNativeCaptions(Object aweme) throws Exception { return binding.hasNativeCaptions(aweme); }
                    @Override public boolean unblocked(Object holder) throws Exception { return binding.unblocked(holder); }
                }, (source, token) -> {
                    try (CaptionAudioInput.Prepared prepared = CaptionAudioInput.prepare(context, source, token)) {
                        return recognizer.recognize(prepared.file, prepared.durationMillis, token);
                    }
                }, log);
        hook.recognizer = recognizer;
        hook.playbackBinding = binding;
        try {
            hook.installCallbacks(framework, binding.selected, binding.pageSelected, binding.resumed, binding.paused, binding.unselected);
            hook.application.registerActivityLifecycleCallbacks(hook);
            return hook;
        } catch (Throwable failure) {
            hook.close();
            if (failure instanceof Exception) throw (Exception) failure;
            throw new IllegalStateException("Caption callback installation failed", failure);
        }
    }

    void installCallbacks(XposedInterface framework, Method selected, Method pageSelected,
                          Method resumed, Method paused, Method unselected) {
        group = new BehaviorHookGroup("caption_generation", log);
        for (Method callback : new Method[]{selected, pageSelected}) {
            group.add(framework, callback, chain -> {
                Object result = chain.proceed();
                selected(chain.getThisObject());
                return result;
            });
        }
        for (Method callback : new Method[]{resumed, paused}) {
            boolean isPause = callback == paused;
            group.add(framework, callback, chain -> {
                Object holder = chain.getThisObject();
                View capturedRoot = null;
                String capturedAid = "";
                try { capturedRoot = access.root(holder); capturedAid = aid(access.aweme(holder)); }
                catch (Exception ignored) { }
                Object argument = chain.getArg(0);
                String source = argument instanceof String ? (String) argument : null;
                Object result = chain.proceed();
                if (capturedRoot != null && !capturedAid.isEmpty()) {
                    playback(holder, capturedRoot, capturedAid, source, isPause);
                }
                return result;
            });
        }
        for (Method callback : new Method[]{unselected}) {
            group.add(framework, callback, chain -> {
                Object result = chain.proceed();
                unselected(chain.getThisObject());
                return result;
            });
        }
    }

    private void playback(Object holder, View capturedRoot, String capturedAid, String source, boolean paused) {
        onMain(() -> {
            if (closed || (isWorkSource(source) && isWorkSource(capturedAid) && !capturedAid.equals(source))) return;
            try {
                if (access.root(holder) != capturedRoot || !capturedAid.equals(aid(access.aweme(holder)))
                        || !access.isCurrent(holder)) return;
                if (candidate != null && candidate.holder.get() == holder && candidate.root.get() == capturedRoot
                        && candidate.aid.equals(capturedAid)) {
                    // A paused visible video retains its cue and uses the same clock when seeking.
                    refresh();
                } else if (!paused) selected(holder);
            } catch (Exception ignored) { }
        });
    }

    private static boolean isWorkSource(String value) {
        if (value == null || value.length() < 16 || value.length() > 20) return false;
        for (int index = 0; index < value.length(); index++) {
            char next = value.charAt(index);
            if (next < '0' || next > '9') return false;
        }
        return true;
    }

    public void setOptions(LocalOptions value) {
        if (closed) return;
        options = value;
        // Preference updates may arrive off the UI thread. Fence upload work immediately.
        if (!enabled()) { session.stop(); input = null; }
        onMain(() -> {
            if (!enabled()) {
                suspend();
                if (candidate != null) { candidate.attempted = false; candidate.transcript = null; }
            } else refresh();
        });
    }

    private boolean enabled() {
        LocalOptions snapshot = options;
        return !closed && snapshot != null && snapshot.enabled(CaptionVisibilitySettings.FORCE)
                && !snapshot.enabled(CaptionVisibilitySettings.HIDE);
    }

    /** Capture identity before posting: a recycled holder must never adopt a pending selection. */
    void selected(Object holder) {
        if (closed || holder == null) return;
        try {
            String aid = aid(access.aweme(holder));
            if (aid.isEmpty()) return;
            long revision = selectionRevision.incrementAndGet();
            long lifecycle = lifecycleRevision;
            main.post(() -> {
                if (closed || selectionRevision.get() != revision || lifecycleRevision != lifecycle) return;
                try {
                    Object aweme = access.aweme(holder);
                    View root = access.root(holder);
                    if (!(root instanceof ViewGroup) || !aid.equals(aid(aweme)) || !access.isCurrent(holder)) return;
                    if (candidate == null || candidate.holder.get() != holder || !candidate.aid.equals(aid)
                            || candidate.root.get() != root) {
                        discard();
                        candidate = new Candidate(holder, (ViewGroup) root, aid);
                        root.addOnAttachStateChangeListener(attach);
                        ViewTreeObserver observer = root.getViewTreeObserver();
                        candidate.observer = new WeakReference<>(observer);
                        observer.addOnWindowFocusChangeListener(focus);
                        observer.addOnGlobalLayoutListener(layout);
                    }
                    refresh();
                } catch (Exception failure) { suspend(); }
            });
        } catch (Exception ignored) { /* Host metadata failure cannot affect selection. */ }
    }

    void unselected(Object holder) {
        onMain(() -> { if (candidate != null && candidate.holder.get() == holder) discard(); });
    }

    private void refresh() {
        if (closed) return;
        main.removeCallbacks(tick);
        Candidate current = candidate;
        if (!enabled()) { suspend(); return; }
        if (!visible(current)) {
            suspend();
            // Native block state may change without a layout when there is no native caption view.
            // Poll only this still-current foreground card; pause/focus/detach cancels this timer.
            if (onScreen(current)) {
                state = "blocked_or_unknown";
                main.postDelayed(tick, 250);
            }
            return;
        }
        try {
            Object model = access.aweme(current.holder.get());
            if (access.hasNativeCaptions(model)) { suspend(); state = "native_captions"; return; }
            if (current.transcript != null) {
                state = current.transcript.cues().isEmpty() ? "no_speech" : "ready";
                render();
            } else if (!current.attempted) {
                CaptionAudioInput.Source source = CaptionAudioInput.capture(model);
                if (!current.aid.equals(source.aid)) { discard(); return; }
                input = source;
                current.attempted = true;
                generation = session.start(source.aid);
                state = "recognizing";
            }
            main.postDelayed(tick, 100);
        } catch (Exception failure) {
            suspend();
            current.attempted = true;
            state = "unsupported_media";
        }
    }

    void pulse() { refresh(); }

    private boolean accepts(Candidate current, String aid, long revision) {
        if (current == null || revision != generation || !current.aid.equals(aid) || !enabled() || !visible(current)) return false;
        try { return !access.hasNativeCaptions(access.aweme(current.holder.get())); }
        catch (Exception failure) { return false; }
    }

    private boolean visible(Candidate current) {
        if (!onScreen(current)) return false;
        try { return access.unblocked(current.holder.get()); }
        catch (Exception failure) { return false; }
    }

    private boolean onScreen(Candidate current) {
        if (current == null) return false;
        Object holder = current.holder.get();
        ViewGroup root = current.root.get();
        Activity foreground = resumed.get();
        if (holder == null || root == null || foreground == null || foreground.isFinishing() || foreground.isDestroyed()
                || activity(root.getContext()) != foreground || !root.isAttachedToWindow() || !root.isShown()
                || root.getWindowVisibility() != View.VISIBLE || !root.hasWindowFocus()) return false;
        Rect shown = new Rect();
        if (!root.getGlobalVisibleRect(shown) || shown.width() <= 0 || shown.height() <= 0) return false;
        try { return access.isCurrent(holder) && current.aid.equals(aid(access.aweme(holder))); }
        catch (Exception failure) { return false; }
    }

    private void render() {
        Candidate current = candidate;
        if (!enabled() || !visible(current) || current.transcript == null) { hideLayer(); return; }
        try {
            long position = access.positionMillis(current.holder.get());
            String cue = position < 0 ? "" : current.transcript.textAt(position);
            if (cue.isEmpty()) { hideLayer(); return; }
            ViewGroup root = current.root.get();
            if (layer == null) {
                layer = new TextView(root.getContext());
                layer.setTextColor(Color.WHITE);
                layer.setTextSize(16);
                layer.setGravity(Gravity.CENTER);
                layer.setMaxLines(3);
                layer.setEllipsize(TextUtils.TruncateAt.END);
                layer.setPadding(dp(root, 12), dp(root, 6), dp(root, 12), dp(root, 6));
                layer.setClickable(false);
                layer.setFocusable(false);
                layer.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                layer.setShadowLayer(2, 0, 1, Color.BLACK);
                GradientDrawable background = new GradientDrawable();
                background.setColor(0xB3000000);
                background.setCornerRadius(dp(root, 6));
                layer.setBackground(background);
                root.getOverlay().add(layer);
            }
            if (!cue.contentEquals(layer.getText())) layer.setText(cue);
            int width = Math.max(1, root.getWidth() - dp(root, 88));
            layer.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.AT_MOST),
                    View.MeasureSpec.makeMeasureSpec(Math.max(1, root.getHeight() / 2), View.MeasureSpec.AT_MOST));
            int left = Math.max(dp(root, 16), (root.getWidth() - dp(root, 40) - layer.getMeasuredWidth()) / 2);
            int bottom = Math.max(layer.getMeasuredHeight(), root.getHeight() - Math.max(dp(root, 100), root.getHeight() / 6));
            layer.layout(left, bottom - layer.getMeasuredHeight(), left + layer.getMeasuredWidth(), bottom);
        } catch (Exception failure) { suspend(); state = "playback_unavailable"; }
    }

    private void suspend() {
        main.removeCallbacks(tick);
        session.stop();
        input = null;
        if (candidate != null && candidate.transcript == null && "recognizing".equals(state)) candidate.attempted = false;
        hideLayer();
        if (!closed) state = "idle";
    }

    private void hideLayer() {
        if (layer == null) return;
        ViewGroup root = candidate == null ? null : candidate.root.get();
        if (root != null) root.getOverlay().remove(layer);
        layer.setText("");
        layer = null;
    }

    private void discard() {
        suspend();
        Candidate previous = candidate;
        candidate = null;
        if (previous == null) return;
        ViewGroup root = previous.root.get();
        if (root != null) root.removeOnAttachStateChangeListener(attach);
        removeObserver(previous.observer.get());
        if (root != null && root.getViewTreeObserver() != previous.observer.get()) removeObserver(root.getViewTreeObserver());
    }

    private void removeObserver(ViewTreeObserver observer) {
        if (observer == null || !observer.isAlive()) return;
        observer.removeOnWindowFocusChangeListener(focus);
        observer.removeOnGlobalLayoutListener(layout);
    }

    public void appendDiagnostics(Bundle snapshot) {
        snapshot.putBoolean("caption_generation_installed", !closed && group != null && group.isActive());
        snapshot.putString("caption_generation_state", state);
        snapshot.putBoolean("caption_generation_enabled", enabled());
        snapshot.putBoolean("caption_generation_server_verified", false);
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        selectionRevision.incrementAndGet();
        session.close();
        if (recognizer != null) recognizer.close();
        if (playbackBinding != null) playbackBinding.close();
        if (group != null) group.close();
        application.unregisterActivityLifecycleCallbacks(this);
        onMain(this::discard);
    }

    @Override public void onActivityResumed(Activity activity) { onMain(() -> { resumed = new WeakReference<>(activity); refresh(); }); }
    @Override public void onActivityPaused(Activity activity) {
        onMain(() -> { if (resumed.get() == activity) { lifecycleRevision++; resumed.clear(); suspend(); } });
    }
    @Override public void onActivityDestroyed(Activity activity) {
        onMain(() -> { if (candidate != null && candidate.root.get() != null
                && activity(candidate.root.get().getContext()) == activity) discard(); });
    }
    @Override public void onActivityCreated(Activity activity, Bundle state) { }
    @Override public void onActivityStarted(Activity activity) { }
    @Override public void onActivityStopped(Activity activity) { }
    @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) { }

    private static String aid(Object aweme) {
        Object value = AnalyticsAwemeReader.value(aweme, "getAid", "aid", "aweme_id");
        return value == null ? "" : value.toString().trim();
    }
    private static Activity activity(Context context) {
        for (int depth = 0; context instanceof ContextWrapper && depth < 12; depth++) {
            if (context instanceof Activity) return (Activity) context;
            Context next = ((ContextWrapper) context).getBaseContext();
            if (next == context) return null;
            context = next;
        }
        return context instanceof Activity ? (Activity) context : null;
    }
    private void onMain(Runnable action) { if (Looper.myLooper() == Looper.getMainLooper()) action.run(); else main.post(action); }
    private static int dp(View view, int value) { return Math.round(value * view.getResources().getDisplayMetrics().density); }
    private static final class Candidate {
        final WeakReference<Object> holder;
        final WeakReference<ViewGroup> root;
        final String aid;
        WeakReference<ViewTreeObserver> observer = new WeakReference<>(null);
        boolean attempted;
        CaptionTranscript transcript;
        Candidate(Object holder, ViewGroup root, String aid) {
            this.holder = new WeakReference<>(holder); this.root = new WeakReference<>(root); this.aid = aid;
        }
    }
}
