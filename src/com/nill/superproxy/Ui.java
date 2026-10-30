package com.nill.superproxy;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Colour palette + small view builders, matching the reference app's look. */
public class Ui {
    public final int bg, card, topBar, text, subText, fieldBg, fieldText, navBg, navPill, divider;
    public final boolean dark;

    public static final int BLUE = Color.rgb(2, 119, 189);        // primary / top bar (matches reference)
    public static final int BLUE_BTN = Color.rgb(30, 136, 229);   // Add proxy button
    public static final int NAVY_BTN = Color.rgb(10, 77, 122);    // Buy proxy button
    public static final int BLUE_DARK = Color.rgb(13, 71, 161);
    public static final int GREEN = Color.rgb(67, 160, 71);      // Start
    public static final int RED = Color.rgb(229, 57, 53);        // Stop
    public static final int BADGE_HTTP = Color.rgb(66, 165, 245);
    public static final int BADGE_SOCKS = Color.rgb(236, 64, 122);

    private Ui(boolean dark) {
        this.dark = dark;
        if (dark) {
            bg = Color.rgb(38, 50, 56);
            card = Color.rgb(55, 71, 79);
            topBar = Color.rgb(21, 54, 89);
            text = Color.rgb(236, 239, 241);
            subText = Color.rgb(176, 190, 197);
            fieldBg = Color.rgb(69, 90, 100);
            fieldText = Color.rgb(236, 239, 241);
            navBg = Color.rgb(33, 43, 48);
            navPill = Color.rgb(55, 71, 79);
            divider = Color.rgb(84, 110, 122);
        } else {
            bg = Color.rgb(198, 216, 230);
            card = Color.WHITE;
            topBar = BLUE;
            text = Color.rgb(33, 33, 33);
            subText = Color.rgb(117, 117, 117);
            fieldBg = Color.rgb(187, 222, 251);
            fieldText = Color.rgb(33, 33, 33);
            navBg = Color.WHITE;
            navPill = Color.rgb(187, 222, 251);
            divider = Color.rgb(207, 216, 220);
        }
    }

    public static Ui of(Context ctx) { return new Ui(new Store(ctx).darkTheme()); }

    /** Blue on-state for switches (the framework default teal clashes with this UI). */
    public static void tintSwitch(android.widget.Switch sw) {
        try {
            sw.setThumbTintList(new android.content.res.ColorStateList(
                    new int[][]{{android.R.attr.state_checked}, {}},
                    new int[]{BLUE, Color.rgb(250, 250, 250)}));
            sw.setTrackTintList(new android.content.res.ColorStateList(
                    new int[][]{{android.R.attr.state_checked}, {}},
                    new int[]{Color.rgb(144, 202, 249), Color.rgb(189, 189, 189)}));
        } catch (Exception ignored) {}
    }

    private static android.graphics.Typeface logoFont = null;
    private static boolean logoFontTried = false;

    /** Rounded display font for the "Super Proxy" wordmark, like the reference app. */
    public static android.graphics.Typeface logoTypeface(Context ctx) {
        if (!logoFontTried) {
            logoFontTried = true;
            try { logoFont = android.graphics.Typeface.createFromAsset(ctx.getAssets(), "fonts/logo.ttf"); }
            catch (Exception e) { logoFont = null; }
        }
        return logoFont;
    }

    public static int dp(Context ctx, int v) {
        return (int) (v * ctx.getResources().getDisplayMetrics().density + 0.5f);
    }

    public static GradientDrawable rounded(int color, int radiusPx) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radiusPx);
        return g;
    }

    public TextView text(Context ctx, String s, float sp, int color, boolean bold) {
        TextView t = new TextView(ctx);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    public TextView label(Context ctx, String s) {
        TextView t = text(ctx, s, 13, subText, false);
        t.setPadding(dp(ctx, 4), dp(ctx, 10), dp(ctx, 4), dp(ctx, 4));
        return t;
    }

    public EditText field(Context ctx, String hint, String value) {
        EditText e = new EditText(ctx);
        e.setHint(hint);
        if (value != null) e.setText(value);
        e.setTextSize(15);
        e.setTextColor(fieldText);
        e.setHintTextColor(subText);
        e.setSingleLine(true);
        e.setBackground(rounded(fieldBg, dp(ctx, 6)));
        e.setPadding(dp(ctx, 12), dp(ctx, 10), dp(ctx, 12), dp(ctx, 10));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(ctx, 6);
        e.setLayoutParams(lp);
        return e;
    }

    public Button bigButton(Context ctx, String s, int color) {
        Button b = new Button(ctx);
        b.setText(s);
        b.setTextSize(17);
        b.setTextColor(Color.WHITE);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setAllCaps(false);
        b.setBackground(rounded(color, dp(ctx, 28)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(ctx, 54));
        lp.topMargin = dp(ctx, 14);
        b.setLayoutParams(lp);
        return b;
    }

    /** Coloured protocol pill like in the reference app (HTTP = blue, SOCKS5 = pink). */
    public TextView badge(Context ctx, String proto) {
        boolean http = "HTTP".equalsIgnoreCase(proto);
        TextView t = text(ctx, http ? "HTTP" : "SOCKS5", 11, Color.WHITE, true);
        t.setBackground(rounded(http ? BADGE_HTTP : BADGE_SOCKS, dp(ctx, 9)));
        t.setPadding(dp(ctx, 8), dp(ctx, 2), dp(ctx, 8), dp(ctx, 2));
        return t;
    }

    public LinearLayout cardRow(Context ctx) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setBackground(rounded(card, dp(ctx, 4)));
        row.setPadding(dp(ctx, 14), dp(ctx, 12), dp(ctx, 10), dp(ctx, 12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(ctx, 8);
        row.setLayoutParams(lp);
        return row;
    }
}
