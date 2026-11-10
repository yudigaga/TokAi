package com.tokai.module.feature;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.View;

import com.tokai.module.config.AiModelSettings;
import com.tokai.module.scan.HostScanReport;

import org.json.JSONObject;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;

import io.github.libxposed.api.XposedInterface;

/** Connects the existing native Tako stream subscription to the selected module AI model. */
public final class TikTokTakoChatHook implements AutoCloseable {
    private final TikTokTakoAiSession session;
    private final Supplier<AiModelSettings> models;
    private final TikTokTakoNativeChat nativeChat;
    private final Consumer<String> log;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<XposedInterface.HookHandle> handles = new ArrayList<>();
    private final Map<Object, Page> pages = new WeakHashMap<>();
    private volatile Active active;
    private volatile boolean closed;

    private TikTokTakoChatHook(TikTokTakoAiSession session, Supplier<AiModelSettings> models,
                              TikTokTakoNativeChat nativeChat, Consumer<String> log) {
        this.session = session;
        this.models = models;
        this.nativeChat = nativeChat;
        this.log = log == null ? ignored -> { } : log;
    }

    public static TikTokTakoChatHook install(XposedInterface framework, HostScanReport report,
            ClassLoader loader, Context context, Supplier<AiModelSettings> models, Consumer<String> log) throws Exception {
        Handler main = new Handler(Looper.getMainLooper());
        return install(framework, report, loader, models, log,
                new TikTokTakoAiSession(task -> main.post(task), log));
    }

    static TikTokTakoChatHook install(XposedInterface framework, HostScanReport report,
            ClassLoader loader, Supplier<AiModelSettings> models, Consumer<String> log,
            TikTokTakoAiSession session) throws Exception {
        TikTokTakoChatHook hook = null;
        try {
            TikTokTakoNativeChat contracts = new TikTokTakoNativeChat(report, loader);
            hook = new TikTokTakoChatHook(session, models, contracts, log);
            TikTokTakoChatHook installed = hook;
            hook.add(framework, contracts.pageCreated, "PageCreated", chain -> {
                Object result = chain.proceed();
                installed.track(chain.getThisObject(), (View) chain.getArg(0), false);
                return result;
            });
            hook.add(framework, contracts.pageResume, "PageResume", chain -> {
                Object result = chain.proceed();
                Object page = chain.getThisObject();
                installed.track(page, (View) TikTokTakoNativeChat.invoke(contracts.pageView, page), true);
                return result;
            });
            for (Method target : new Method[]{contracts.pageStop, contracts.pageDestroy}) {
                hook.add(framework, target, target == contracts.pageStop ? "PageStop" : "PageDestroy", chain -> {
                    installed.leave(chain.getThisObject());
                    return chain.proceed();
                });
            }
            hook.add(framework, contracts.vmClear, "VmClear", chain -> {
                installed.clearVm(chain.getThisObject());
                return chain.proceed();
            });
            hook.add(framework, contracts.dispatch, "StopDispatch", chain -> {
                if (installed.stop(chain.getThisObject(), chain.getArg(0))) return null;
                return chain.proceed();
            });
            hook.add(framework, contracts.request, "NativeRequest", chain -> {
                Object dto = chain.getArg(0), operation = chain.getArg(1);
                if (installed.closed || !contracts.standard(dto)) return chain.proceed();
                AiModelSettings snapshot = null;
                Exception invalid = null;
                try { snapshot = models.get(); }
                catch (RuntimeException failure) { invalid = failure; }
                if (invalid == null && (snapshot == null || snapshot.activeId.isEmpty())) return chain.proceed();
                // Once selected, an invalid/unsupported request is a native error, never a server fallback.
                AiModelSettings selected = snapshot;
                Exception settingFailure = invalid;
                AtomicBoolean subscribed = new AtomicBoolean();
                return contracts.flow(observer -> {
                    if (!subscribed.compareAndSet(false, true)) {
                        installed.failObserver(observer, new IllegalStateException("Tako 请求已订阅"));
                        return;
                    }
                    installed.onMain(() -> installed.begin(dto, operation, observer, selected, settingFailure));
                });
            });
            return hook;
        } catch (Exception | LinkageError failure) {
            if (hook != null) hook.close(); else session.close();
            throw failure;
        }
    }

    private void add(XposedInterface framework, Method method, String id, XposedInterface.Hooker interceptor) {
        XposedInterface.HookHandle handle = framework.hook(method).setId("TokAi.Tako." + id)
                .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH).intercept(interceptor);
        if (handle == null) throw new IllegalStateException("Native Tako hook handle unavailable");
        handles.add(handle);
    }

    private void track(Object nativePage, View view, boolean resumed) {
        if (closed || nativePage == null || view == null) return;
        try {
            Object vm = TikTokTakoNativeChat.invoke(nativeChat.pageVm, nativePage);
            Object state = nativeChat.vmState.get(vm);
            synchronized (pages) {
                Page page = pages.get(vm);
                if (page == null || page.nativePage.get() != nativePage) {
                    if (active != null && active.page == page) cancelActive(false);
                    page = new Page(nativePage, vm, state, view);
                    pages.put(vm, page);
                }
                if (page.state.get() != state || page.view.get() != view || !resumed) {
                    page.generation++;
                    if (active != null && active.page == page) cancelActive(false);
                }
                page.state = new WeakReference<>(state);
                page.view = new WeakReference<>(view);
                page.resumed = resumed;
            }
        } catch (ReflectiveOperationException | RuntimeException failure) { diagnostic("page_unavailable", failure); }
    }

    private void leave(Object nativePage) {
        synchronized (pages) {
            for (Page page : pages.values()) if (page.nativePage.get() == nativePage) {
                page.resumed = false;
                if (active != null && active.page == page) cancelActive(false);
            }
        }
    }

    private void clearVm(Object vm) {
        synchronized (pages) {
            Page page = pages.remove(vm);
            if (page != null) {
                page.resumed = false;
                if (active != null && active.page == page) cancelActive(false);
            }
        }
    }

    private Page currentPage(Object state) {
        synchronized (pages) {
            for (Page page : pages.values()) {
                if (page.resumed && page.state.get() == state) return page;
            }
        }
        return null;
    }

    private void begin(Object dto, Object operation, Object observer, AiModelSettings snapshot, Exception settingFailure) {
        try {
            if (closed) throw new IllegalStateException("模块 Tako AI 已关闭");
            if (settingFailure != null) throw settingFailure;
            String key = settingsKey(snapshot); // Validate the global selected model before accepting the user message.
            if (!key.equals(settingsKey(models.get()))) {
                throw new CancellationException("模块 AI 模型已切换，请重新发送");
            }
            Object state = nativeChat.operationState.get(operation);
            Page page = currentPage(state);
            String conversation = TikTokTakoNativeChat.text(TikTokTakoNativeChat.read(dto, "LJ"));
            String account = nativeChat.account();
            if (page == null || account.isEmpty() || "0".equals(account) || conversation.isEmpty()
                    || !conversation.equals(nativeChat.conversation(state))) {
                throw new IllegalStateException("当前 Tako 页面、账号或原生会话尚未就绪，请重新打开 Tako");
            }
            String prompt = nativeChat.prompt(operation);
            Active previous = active;
            if (previous != null && !contextCurrent(previous)) cancelActive(false);
            if (active != null) throw new IllegalStateException("上一条模块 AI 回复仍在生成，请先停止或等待完成");
            Active request = new Active(page, state, account, conversation, key, operation, observer);
            if (!contextCurrent(request)) throw new IllegalStateException("当前 Tako 页面已离开，请重新打开 Tako");
            active = request;
            session.bind(account, conversation, snapshot);
            if (!session.request(prompt, new TikTokTakoAiSession.Sink() {
                @Override public boolean isCurrent() {
                    boolean current = requestCurrent(request);
                    if (!current) { request.terminal = true; if (active == request) active = null; }
                    return current;
                }
                @Override public void onComplete(String text) {
                    if (!requestCurrent(request)) return;
                    try {
                        nativeChat.answer(observer, operation, text);
                        request.terminal = true;
                        if (active == request) active = null;
                        diagnostic("native_reply_delivered;remote_tiktok_request=false", null);
                    } catch (Exception failure) { finishFailure(request, failure); }
                }
                @Override public void onFailure(Exception failure) {
                    if (requestCurrent(request)) finishFailure(request, failure);
                }
            })) {
                active = null;
                throw new IllegalStateException("模块 AI 正忙，请稍后重试");
            }
            nativeChat.acknowledgement(observer, operation);
            diagnostic("native_request_replaced;remote_tiktok_request=false", null);
        } catch (Exception failure) {
            if (active != null && active.observer == observer) { active.terminal = true; active = null; session.cancel(); }
            failObserver(observer, failure);
        }
    }

    private boolean contextCurrent(Active request) {
        if (closed || !request.page.resumed || request.generation != request.page.generation) return false;
        try {
            Object page = request.page.nativePage.get(), vm = request.page.vm.get();
            View view = request.page.view.get();
            return page != null && vm != null && view != null && view.isAttachedToWindow() && view.isShown()
                    && TikTokTakoNativeChat.invoke(nativeChat.pageView, page) == view
                    && TikTokTakoNativeChat.invoke(nativeChat.pageVm, page) == vm
                    && request.page.state.get() == request.state
                    && nativeChat.vmState.get(vm) == request.state
                    && request.conversation.equals(nativeChat.conversation(request.state))
                    && request.account.equals(nativeChat.account());
        } catch (ReflectiveOperationException | RuntimeException failure) { return false; }
    }

    private boolean requestCurrent(Active request) {
        if (active != request || request.terminal || !contextCurrent(request)) return false;
        try { return request.settingsKey.equals(settingsKey(models.get())); }
        catch (RuntimeException failure) { return false; }
    }

    private static String settingsKey(AiModelSettings settings) {
        JSONObject values = settings.selected().toJson();
        for (String name : new String[]{"name", "messageContactEnabled", "messageContactName", "messageContactAvatar"}) values.remove(name);
        return values.toString(); // In-memory equality only; contains transport settings and is never logged.
    }

    private void finishFailure(Active request, Exception failure) {
        request.terminal = true;
        if (active == request) active = null;
        failObserver(request.observer, failure);
    }

    private void failObserver(Object observer, Exception failure) {
        onMain(() -> {
            try { TikTokTakoNativeChat.invoke(nativeChat.failed, observer, failure); }
            catch (ReflectiveOperationException | RuntimeException nativeFailure) { diagnostic("native_error_delivery_failed", nativeFailure); }
            diagnostic("native_request_failed;remote_tiktok_request=false", failure);
        });
    }

    private void cancelActive(boolean notify) {
        Active request = active;
        active = null;
        boolean unfinished = request != null && !request.terminal;
        if (unfinished) request.terminal = true;
        session.cancel();
        if (unfinished && notify && contextCurrent(request)) {
            failObserver(request.observer, new CancellationException("模块 AI 请求已取消"));
        }
    }

    /** Native case 12 has already applied its stopped text and idle state before this dispatch. */
    private boolean stop(Object operator, Object operation) throws ReflectiveOperationException {
        Active request = active;
        if (closed || request == null || request.terminal
                || (Integer) TikTokTakoNativeChat.read(operation, "LIZIZ") != 12
                || nativeChat.operationState.get(operation) != request.state
                || nativeChat.stateOperator.get(request.state) != operator) return false;
        cancelActive(false);
        onMain(() -> {
            if (!contextCurrent(request)) return;
            try { TikTokTakoNativeChat.invoke(nativeChat.complete, request.observer); }
            catch (ReflectiveOperationException | RuntimeException failure) { diagnostic("native_stop_completion_failed", failure); }
        });
        diagnostic("native_stop_cancelled;remote_tiktok_request=false", null);
        return true;
    }

    private void onMain(Runnable action) {
        if (Looper.myLooper() == Looper.getMainLooper()) action.run(); else main.post(action);
    }

    private void diagnostic(String reason, Throwable failure) {
        try { log.accept("Tako AI " + reason + (failure == null ? "" : ";type=" + failure.getClass().getSimpleName())); }
        catch (RuntimeException ignored) { }
    }

    public boolean isConfigured() {
        AiModelSettings value = models.get();
        return value != null && !value.activeId.isEmpty();
    }

    public void refreshSettings() { onMain(() -> cancelActive(true)); }

    @Override public void close() {
        if (closed) return;
        closed = true;
        onMain(() -> { cancelActive(false); synchronized (pages) { pages.clear(); } });
        for (int index = handles.size() - 1; index >= 0; index--) {
            try { handles.get(index).unhook(); } catch (RuntimeException failure) { diagnostic("unhook_failed", failure); }
        }
        handles.clear();
        session.close();
    }

    private static final class Page {
        final WeakReference<Object> nativePage, vm;
        WeakReference<Object> state;
        WeakReference<View> view;
        boolean resumed;
        long generation;
        Page(Object page, Object vm, Object state, View view) {
            nativePage = new WeakReference<>(page); this.vm = new WeakReference<>(vm);
            this.state = new WeakReference<>(state); this.view = new WeakReference<>(view);
        }
    }
    private static final class Active {
        final Page page;
        final Object state, operation, observer;
        final String account, conversation, settingsKey;
        final long generation;
        boolean terminal;
        Active(Page page, Object state, String account, String conversation, String key, Object operation, Object observer) {
            this.page = page; this.state = state; this.account = account;
            this.conversation = conversation; settingsKey = key; this.operation = operation; this.observer = observer;
            generation = page.generation;
        }
    }
}
