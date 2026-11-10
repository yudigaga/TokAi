package com.ss.android.ugc.aweme.tako.botpage.toptitle;

import android.view.View;

/** Verified native callback and assistant identity seam. */
public final class TopTitleAssem {
    public boolean tako = true;
    public int nativeCalls;
    public View root;
    public boolean isTako() { return tako; }
    public void onViewCreated(View view) { nativeCalls++; root = view; }
}
