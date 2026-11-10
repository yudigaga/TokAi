package com.tokai.module.feature;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import static org.junit.Assert.*;

public final class TikTokCommentImageSaveTest {
    @Test public void dispatchesOnlyTheActualNativeSavePhotoActionAndKeepsItsPermissionCallback() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        Action action = new Action();
        action.LIZIZ = "save_photo";
        action.LJ = () -> { calls.incrementAndGet(); return null; };
        assertEquals(0, calls.get());
        CommentToolsHook.invokeTikTokSaveAction(action);
        assertEquals(1, calls.get());
        action.LIZIZ = "delete";
        assertThrows(IllegalStateException.class, () -> CommentToolsHook.invokeTikTokSaveAction(action));
        assertEquals(1, calls.get());
    }
    @Test public void unavailableNativeActionCannotStartADownload() {
        Action action = new Action(); action.LIZIZ = "save_photo";
        assertThrows(IllegalStateException.class, () -> CommentToolsHook.invokeTikTokSaveAction(action));
    }
    public interface Callback { Object invoke(); }
    public static final class Action { public String LIZIZ; public Callback LJ; }
}
