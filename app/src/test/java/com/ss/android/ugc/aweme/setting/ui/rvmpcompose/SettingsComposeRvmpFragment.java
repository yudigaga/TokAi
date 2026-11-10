package com.ss.android.ugc.aweme.setting.ui.rvmpcompose;

import android.app.Activity;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.compose.ui.platform.ComposeView;
import androidx.activity.ComponentActivity;
import androidx.lifecycle.ViewTreeLifecycleOwner;
import androidx.lifecycle.ViewTreeViewModelStoreOwner;
import androidx.savedstate.ViewTreeSavedStateRegistryOwner;

/** Exact host member names; getActivity is deliberately inherited, as in TikTok. */
public final class SettingsComposeRvmpFragment extends SettingsFragmentOwner {
    public SettingsComposeRvmpFragment(Activity owner) { super(owner); }

    public final View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle state) {
        ComposeView root = new ComposeView(getActivity());
        // TikTok initializes the native Compose root's owners before returning it to FragmentManager.
        if (getActivity() instanceof ComponentActivity) {
            ComponentActivity owner = (ComponentActivity) getActivity();
            ViewTreeLifecycleOwner.set(root, owner);
            ViewTreeViewModelStoreOwner.set(root, owner);
            ViewTreeSavedStateRegistryOwner.set(root, owner);
        }
        return root;
    }
}
