package com.tokai.module.ui;

import com.tokai.module.R;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Outline;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Pure TikTok 47.5.3 Tux views and IM layouts, with module-owned conversation state. */
final class TikTokChatViews {
    final Activity activity;
    final FrameLayout root, messages;
    final LinearLayout inputContainer, left, right, actions;
    final TextView title;
    final EditText input;
    final ImageView back, avatar, more, tools, voice, emoji, plus, send;

    TikTokChatViews(Activity activity) throws ReflectiveOperationException {
        this.activity = activity;
        com.tokai.module.i18n.ModuleLocale.ensureInitialized(activity);
        root = new FrameLayout(activity);
        root.setBackgroundColor(ModuleTheme.isDark(activity) ? Color.rgb(18, 18, 18) : Color.WHITE);
        LinearLayout column = new LinearLayout(activity);
        column.setOrientation(LinearLayout.VERTICAL);
        root.addView(column, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout header = new LinearLayout(activity);
        header.setGravity(Gravity.CENTER_VERTICAL);
        back = icon("icon_chevron_left_ltr", com.tokai.module.i18n.UiText.get(R.string.editor_ui_back_messages));
        avatar = avatar();
        title = text();
        title.setTextSize(17);
        actions = new LinearLayout(activity);
        more = icon("icon_ellipsis_horizontal", com.tokai.module.i18n.UiText.get(R.string.editor_ui_more));
        tools = icon("icon_ai_sparkles", com.tokai.module.i18n.UiText.get(R.string.editor_ui_tools));
        actions.addView(tools, new LinearLayout.LayoutParams(dp(40), dp(44)));
        actions.addView(more, new LinearLayout.LayoutParams(dp(40), dp(44)));
        header.addView(back, new LinearLayout.LayoutParams(dp(44), dp(52)));
        header.addView(avatar, new LinearLayout.LayoutParams(dp(36), dp(36)));
        title.setPadding(dp(12), 0, 0, 0);
        header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        header.addView(actions, new LinearLayout.LayoutParams(-2, -2));
        column.addView(header, new LinearLayout.LayoutParams(-1, -2));
        messages = new FrameLayout(activity);
        column.addView(messages, new LinearLayout.LayoutParams(-1, 0, 1));
        inputContainer = new LinearLayout(activity);
        inputContainer.setGravity(Gravity.BOTTOM);
        inputContainer.setPadding(dp(4), dp(6), dp(4), dp(6));
        left = new LinearLayout(activity);
        right = new LinearLayout(activity);
        voice = icon("icon_microphone", com.tokai.module.i18n.UiText.get(R.string.editor_ui_speech_input));
        emoji = icon("icon_emoji", com.tokai.module.i18n.UiText.get(R.string.editor_ui_emoji));
        plus = icon("icon_plus", com.tokai.module.i18n.UiText.get(R.string.editor_ui_add_attachment));
        send = icon("icon_paperplane", com.tokai.module.i18n.UiText.get(R.string.editor_ui_send));
        send.setTag(R.id.module_ai_send_busy, Boolean.FALSE);
        left.addView(voice, new LinearLayout.LayoutParams(dp(40), dp(44)));
        View edit = LayoutInflater.from(activity).inflate(resource("layout", "cml"), inputContainer, false);
        if (!(edit instanceof EditText)
                || !"com.ss.android.ugc.aweme.im.sdk.chat.ui.view.SearchableEditText".equals(edit.getClass().getName())) {
            throw new ReflectiveOperationException("TikTok IM input layout changed");
        }
        input = (EditText) edit;
        input.setHint(com.tokai.module.i18n.UiText.get(R.string.editor_ui_message));
        right.addView(emoji, new LinearLayout.LayoutParams(dp(40), dp(44)));
        right.addView(plus, new LinearLayout.LayoutParams(dp(40), dp(44)));
        right.addView(send, new LinearLayout.LayoutParams(dp(40), dp(44)));
        inputContainer.addView(left, new LinearLayout.LayoutParams(-2, -2));
        inputContainer.addView(input, new LinearLayout.LayoutParams(0, -2, 1));
        inputContainer.addView(right, new LinearLayout.LayoutParams(-2, -2));
        column.addView(inputContainer, new LinearLayout.LayoutParams(-1, -2));
        update(false, false);
    }

    @android.annotation.SuppressLint("ResourceType") // Exact foreign APK identifiers, verified with its resource table.
    int resource(String type, String name) throws ReflectiveOperationException {
        int id = switch (type + "/" + name) {
            case "layout/cml" -> 0x7f0d10e6;
            case "layout/cft" -> 0x7f0d0feb;
            case "layout/cfu" -> 0x7f0d0fec;
            case "id/kt_" -> 0x7f0a3a35;
            case "id/kt8" -> 0x7f0a3a33;
            case "raw/icon_chevron_left_ltr" -> 0x7f0103c6;
            case "raw/icon_ellipsis_horizontal" -> 0x7f0106d3;
            case "raw/icon_ai_sparkles" -> 0x7f010141;
            case "raw/icon_microphone" -> 0x7f010966;
            case "raw/icon_emoji" -> 0x7f0106d7;
            case "raw/icon_plus" -> 0x7f010a41;
            case "raw/icon_paperplane" -> 0x7f0109b5;
            case "raw/icon_x_mark" -> 0x7f010c74;
            default -> 0;
        };
        if (id == 0 || !com.tokai.module.scan.HostAdaptation.TARGET_PACKAGE.equals(
                activity.getResources().getResourcePackageName(id))
                || !type.equals(activity.getResources().getResourceTypeName(id))
                || !name.equals(activity.getResources().getResourceEntryName(id)))
            throw new ReflectiveOperationException("TikTok resource missing or changed: " + type + "/" + name);
        return id;
    }

    private Class<?> type(String name) throws ClassNotFoundException {
        return Class.forName(name, false, activity.getClassLoader());
    }

    TextView text() throws ReflectiveOperationException {
        Object view = type("com.bytedance.tux.input.TuxTextView").getConstructor(Context.class).newInstance(activity);
        if (!(view instanceof TextView)) throw new ReflectiveOperationException("TikTok Tux text type");
        return (TextView) view;
    }

    ImageView avatar() throws ReflectiveOperationException {
        Object view = type("com.ss.android.ugc.aweme.base.ui.SmartAvatarImageView")
                .getConstructor(Context.class).newInstance(activity);
        if (!(view instanceof ImageView)) throw new ReflectiveOperationException("TikTok avatar type");
        ImageView image = (ImageView) view;
        image.setLayoutParams(new LinearLayout.LayoutParams(dp(32), dp(32)));
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        image.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View view, Outline outline) { outline.setOval(0, 0, view.getWidth(), view.getHeight()); }
        });
        image.setClipToOutline(true);
        return image;
    }

    ImageView icon(String icon, String description) throws ReflectiveOperationException {
        Class<?> type = type("com.bytedance.tux.icon.TuxIconView");
        ImageView image = (ImageView) type.getConstructor(Context.class).newInstance(activity);
        type.getMethod("setIconRes", int.class).invoke(image, resource("raw", icon));
        type.getMethod("setIconWidth", int.class).invoke(image, dp(24));
        type.getMethod("setIconHeight", int.class).invoke(image, dp(24));
        type.getMethod("setTintColor", int.class).invoke(image, textColor(false));
        image.setContentDescription(description);
        image.setPadding(dp(8), dp(8), dp(8), dp(8));
        image.setClickable(true);
        image.setFocusable(true);
        return image;
    }

    void update(boolean busy, boolean content) {
        send.setTag(R.id.module_ai_send_busy, busy ? Boolean.TRUE : Boolean.FALSE);
        try { send.getClass().getMethod("setIconRes", int.class)
                .invoke(send, resource("raw", busy ? "icon_x_mark" : "icon_paperplane")); }
        catch (ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
        boolean show = busy || content;
        send.setContentDescription(busy ? com.tokai.module.i18n.UiText.get(R.string.editor_ui_stop) : com.tokai.module.i18n.UiText.get(R.string.editor_ui_send));
        send.setVisibility(show ? View.VISIBLE : View.GONE);
        plus.setVisibility(show ? View.GONE : View.VISIBLE);
        voice.setVisibility(show ? View.GONE : View.VISIBLE);
    }

    int textColor(boolean user) { return ModuleTheme.isDark(activity) ? Color.WHITE : Color.rgb(22, 24, 35); }
    int maxWidth(int available) { return Math.max(1, Math.min(dp(270), available - dp(32))); }
    int dp(int value) { return Math.round(value * activity.getResources().getDisplayMetrics().density); }

    final class Bubble {
        final FrameLayout view = new FrameLayout(activity);
        TextView text;
        boolean role;

        Bubble(boolean user) throws ReflectiveOperationException { layout(user); }
        private void layout(boolean user) throws ReflectiveOperationException {
            View nativeRow = LayoutInflater.from(activity).inflate(resource("layout", user ? "cfu" : "cft"), view, false);
            View found = nativeRow.findViewById(resource("id", user ? "kt_" : "kt8"));
            if (!(found instanceof TextView) || !"com.ss.android.ugc.aweme.im.common.view.TightTuxTextView".equals(found.getClass().getName())) {
                throw new ReflectiveOperationException("TikTok IM text layout changed");
            }
            text = (TextView) found;
            ((ViewGroup) text.getParent()).removeView(text);
            view.removeAllViews();
            view.addView(text, new FrameLayout.LayoutParams(-2, -2));
            role = user;
        }
        void bind(CharSequence value, int color, int maxWidth, boolean user) {
            try { if (role != user) layout(user); }
            catch (ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
            text.setMaxWidth(Math.max(1, maxWidth));
            text.setTextColor(color);
            text.setText(value == null ? "" : value);
        }
    }
}
