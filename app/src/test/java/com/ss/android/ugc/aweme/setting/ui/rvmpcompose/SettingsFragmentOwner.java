package com.ss.android.ugc.aweme.setting.ui.rvmpcompose;

import android.app.Activity;

public class SettingsFragmentOwner {
    private final Activity owner;

    public SettingsFragmentOwner(Activity owner) { this.owner = owner; }
    public Activity getActivity() { return owner; }
}
