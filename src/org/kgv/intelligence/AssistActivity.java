package org.kgv.intelligence;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * The chat sheet: a floating card over the current app, blurred behind, with the Siri-like
 * glow around the screen edges. Opened by holding Home.
 */
public class AssistActivity extends Activity implements Conversation.Listener {
    static final String EXTRA_PREFILL = "org.kgv.intelligence.extra.PREFILL";

    private SiriGlow mGlow;
    private KgvOrb mOrb;
    private boolean mListening;
    private LinearLayout mMessages;
    private ScrollView mScroll;
    private EditText mInput;
    private ImageButton mSend;
    private ImageButton mMic;
    private Voice mVoice;
    private LinearLayout mChips;

    /** When the first phone action runs: get out of the way so it sees the app underneath. */
    private final Agent.Callbacks mActing = () -> runOnUiThread(() -> {
        mInput.clearFocus();
        moveTaskToBack(true);
        finish();
    });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND);
        setContentView(R.layout.activity_assist);
        // The glow goes on the decor view, under the content, so it reaches the screen
        // edges behind the status and navigation bars.
        mGlow = new SiriGlow(this);
        ((ViewGroup) getWindow().getDecorView()).addView(mGlow, 0, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        mOrb = new KgvOrb(this);
        ((ViewGroup) findViewById(R.id.orb)).addView(mOrb);
        mOrb.setOnClickListener(v -> mVoice.toggle());
        mMessages = findViewById(R.id.messages);
        mScroll = findViewById(R.id.scroll);
        mInput = findViewById(R.id.input);
        mSend = findViewById(R.id.send);
        mChips = findViewById(R.id.chips);

        findViewById(R.id.root).setOnClickListener(v -> finish());
        findViewById(R.id.settings).setOnClickListener(v ->
                startActivity(new Intent(this, SettingsActivity.class)));
        // Send, or while a task runs, Stop (e.g. when the model goes the wrong way).
        mSend.setOnClickListener(v -> {
            if (Conversation.get().isBusy()) Agent.cancel();
            else send();
        });
        mMic = findViewById(R.id.mic);
        mVoice = new Voice(this, new Voice.Listener() {
            @Override
            public void onListening(boolean listening) {
                mListening = listening;
                updateGlow();
                mMic.setColorFilter(listening ? getColor(R.color.kgv_red) : 0xFFFFFFFF);
                mInput.setHint(listening ? getString(R.string.listening)
                        : getString(R.string.hint));
            }

            @Override
            public void onText(String text) {
                mInput.setText(text);
                send();
            }

            @Override
            public void onError(String message) {
                Toast.makeText(AssistActivity.this, message, Toast.LENGTH_LONG).show();
            }

            @Override
            public void onLevel(float rmsDb) {
                mGlow.setVoiceLevel(rmsDb);
                mOrb.setVoiceLevel(rmsDb);
            }
        });
        mMic.setColorFilter(0xFFFFFFFF);
        mMic.setOnClickListener(v -> mVoice.toggle());
        mInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEND || (event != null
                    && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                    && event.getAction() == KeyEvent.ACTION_DOWN)) {
                send();
                return true;
            }
            return false;
        });
        // Fit the chat to the space the window has (less with the keyboard up).
        findViewById(R.id.root).addOnLayoutChangeListener(
                (v, l, t, r, b, ol, ot, or, ob) -> v.post(this::fitChat));
        mMessages.addOnLayoutChangeListener(
                (v, l, t, r, b, ol, ot, or, ob) -> v.post(this::fitChat));
        if (new Prefs(this).automationWanted() && !Prefs.isAutomationEnabled(this)) {
            Prefs.setAutomationEnabled(this, true);
        }
        McpService.sync(this);
        Routines.listen(this);
        prefill(getIntent());
        runShortcut(getIntent());
    }

    /** A launcher shortcut (the app icon's long-press menu) runs a saved task. */
    private void runShortcut(Intent intent) {
        String name = intent != null ? intent.getStringExtra(Shortcuts.EXTRA_RUN) : null;
        if (name == null) return;
        intent.removeExtra(Shortcuts.EXTRA_RUN);
        if (!Shortcuts.start(this, name, mActing)) {
            Toast.makeText(this, Agent.isRunning() ? "The assistant is busy."
                    : "No shortcut \"" + name + "\".", Toast.LENGTH_SHORT).show();
        }
    }

    /** KGV Browser's Ask KGV starts the question with the page it is about. */
    private void prefill(Intent intent) {
        String text = intent != null ? intent.getStringExtra(EXTRA_PREFILL) : null;
        if (text == null) return;
        mInput.setText(text);
        mInput.setSelection(text.length());
    }

    @Override
    protected void onDestroy() {
        mVoice.release();
        super.onDestroy();
    }

    @Override
    protected void onStart() {
        super.onStart();
        Conversation.get().addListener(this);
        onChanged();
        mInput.requestFocus();
    }

    @Override
    protected void onStop() {
        Conversation.get().removeListener(this);
        super.onStop();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        onChanged();
        prefill(intent);
        runShortcut(intent);
    }

    private void send() {
        String text = mInput.getText().toString().trim();
        if (TextUtils.isEmpty(text) || Agent.isRunning()) return;
        mInput.setText("");
        Agent.start(this, text, mActing);
    }

    @Override
    public void onChanged() {
        mMessages.removeAllViews();
        if (Conversation.get().items().isEmpty()) {
            mMessages.addView(bubble(Conversation.ASSISTANT, getString(R.string.greeting)));
        }
        for (Conversation.Item item : Conversation.get().items()) {
            mMessages.addView(item.kind == Conversation.APPROVAL ? approval(item)
                    : bubble(item.kind, item.text));
        }
        boolean busy = Conversation.get().isBusy();
        if (busy) mMessages.addView(bubble(Conversation.STEP, "Thinking…"));
        showChips(busy);
        updateGlow();
        mSend.setImageResource(busy ? R.drawable.ic_stop : R.drawable.ic_send);
        mSend.setContentDescription(getString(busy ? R.string.stop : R.string.send));
        mScroll.post(() -> mScroll.fullScroll(View.FOCUS_DOWN));
    }

    /**
     * Keeps the chat to the lower part of the screen, like an assistant, and always leaves
     * room for the input row: with the keyboard up and a long chat, the history would
     * otherwise push it out of the card. The orb gives way to the keyboard.
     */
    private void fitChat() {
        View root = findViewById(R.id.root);
        int rootH = root.getHeight() - root.getPaddingTop() - root.getPaddingBottom();
        if (rootH <= 0) return;
        boolean keyboard = rootH < getResources().getDisplayMetrics().heightPixels * 3 / 4;
        View orb = findViewById(R.id.orb);
        int orbVisibility = keyboard ? View.GONE : View.VISIBLE;
        if (orb.getVisibility() != orbVisibility) orb.setVisibility(orbVisibility);
        View sheet = findViewById(R.id.sheet);
        View header = (View) findViewById(R.id.title).getParent();
        View inputRow = (View) mInput.getParent();
        View chips = findViewById(R.id.chips_scroll);
        int unspecified = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED);
        int width = View.MeasureSpec.makeMeasureSpec(sheet.getWidth(), View.MeasureSpec.AT_MOST);
        header.measure(width, unspecified);
        inputRow.measure(width, unspecified);
        int chipsH = 0;
        if (chips.getVisibility() == View.VISIBLE) {
            chips.measure(width, unspecified);
            chipsH = chips.getMeasuredHeight() + dp(8);
        }
        // Everything in the card but the history, with the margins: the card's 10 + 10,
        // the history's 8 and the input row's 10 dp.
        int fixed = sheet.getPaddingTop() + sheet.getPaddingBottom() + dp(38)
                + (keyboard ? 0 : orb.getLayoutParams().height)
                + header.getMeasuredHeight() + inputRow.getMeasuredHeight() + chipsH;
        int cap = Math.max(dp(80), Math.min(keyboard ? rootH : rootH * 11 / 20, rootH - fixed));
        int want = mMessages.getHeight() > cap ? cap : ViewGroup.LayoutParams.WRAP_CONTENT;
        ViewGroup.LayoutParams lp = mScroll.getLayoutParams();
        if (lp.height != want) {
            lp.height = want;
            mScroll.setLayoutParams(lp);
            mScroll.post(() -> mScroll.fullScroll(View.FOCUS_DOWN));
        }
    }

    private void updateGlow() {
        boolean active = mListening || Conversation.get().isBusy();
        mGlow.setActive(active);
        mOrb.setActive(active);
    }

    private View bubble(int kind, String text) {
        TextView tv = new TextView(this);
        tv.setText(kind == Conversation.USER || kind == Conversation.STEP ? text
                : Markdown.render(text));
        tv.setTextIsSelectable(kind != Conversation.STEP);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, kind == Conversation.STEP ? 12 : 15);
        tv.setLineSpacing(0, 1.15f);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(18));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(kind == Conversation.STEP ? 3 : 8);
        switch (kind) {
            case Conversation.USER:
                bg.setColor(getColor(R.color.bubble_user));
                tv.setTextColor(0xFFFFFFFF);
                lp.gravity = Gravity.END;
                lp.setMarginStart(dp(48));
                tv.setTextIsSelectable(false);
                tv.setOnLongClickListener(v -> {
                    saveShortcut(text);
                    return true;
                });
                break;
            case Conversation.STEP:
                bg.setColor(getColor(R.color.bubble_step));
                tv.setTextColor(getColor(R.color.text_dim));
                tv.setText("⚡ " + text);
                break;
            case Conversation.ERROR:
                bg.setColor(getColor(R.color.bubble_ai));
                bg.setStroke(dp(1), getColor(R.color.kgv_red));
                tv.setTextColor(getColor(R.color.text));
                lp.setMarginEnd(dp(48));
                break;
            default:
                bg.setColor(getColor(R.color.bubble_ai));
                tv.setTextColor(getColor(R.color.text));
                lp.setMarginEnd(dp(48));
        }
        int h = dp(kind == Conversation.STEP ? 10 : 14), v = dp(kind == Conversation.STEP ? 4 : 10);
        tv.setPadding(h, v, h, v);
        tv.setBackground(bg);
        tv.setLayoutParams(lp);
        return tv;
    }

    /** A risky action waiting for the user: Approve / Deny, then what they chose. */
    private View approval(Conversation.Item item) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(18));
        bg.setColor(getColor(R.color.bubble_ai));
        bg.setStroke(dp(1), getColor(R.color.kgv_red));
        card.setBackground(bg);
        card.setPadding(dp(14), dp(10), dp(14), dp(10));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        card.setLayoutParams(lp);
        TextView title = new TextView(this);
        title.setText(item.state == Conversation.WAITING ? "Allow this?"
                : item.state == Conversation.APPROVED ? "✓ Approved" : "✕ Declined");
        title.setTextColor(getColor(item.state == Conversation.APPROVED ? R.color.text
                : R.color.kgv_red));
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        card.addView(title);
        TextView what = new TextView(this);
        what.setText(item.text);
        what.setTextColor(getColor(R.color.text));
        what.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        what.setPadding(0, dp(4), 0, dp(4));
        card.addView(what);
        if (item.state == Conversation.WAITING) {
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.END);
            row.addView(pill("Deny", false, v -> Approvals.answer(item.approvalId, false)));
            row.addView(pill("Approve", true, v -> Approvals.answer(item.approvalId, true)));
            card.addView(row);
        }
        return card;
    }

    private Button pill(String text, boolean primary, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextColor(primary ? 0xFFFFFFFF : getColor(R.color.text));
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(18));
        if (primary) bg.setColor(getColor(R.color.kgv_red));
        else bg.setStroke(dp(1), getColor(R.color.text_dim));
        b.setBackground(bg);
        b.setMinHeight(dp(36));
        b.setMinimumHeight(dp(36));
        b.setPadding(dp(16), 0, dp(16), 0);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(36));
        lp.setMarginStart(dp(8));
        b.setLayoutParams(lp);
        b.setOnClickListener(l);
        return b;
    }

    /** The saved shortcuts as chips above the input; hidden while a task runs. */
    private void showChips(boolean busy) {
        View scroll = findViewById(R.id.chips_scroll);
        mChips.removeAllViews();
        java.util.List<Shortcuts.Shortcut> all = busy ? java.util.Collections.emptyList()
                : Shortcuts.all(this);
        for (int i = all.size() - 1; i >= 0; i--) {
            Shortcuts.Shortcut s = all.get(i);
            Button chip = pill("⚡ " + Skills.clip(s.name, 28), false, v -> {
                if (!Shortcuts.start(this, s.name, mActing)) {
                    Toast.makeText(this, "The assistant is busy.", Toast.LENGTH_SHORT).show();
                }
            });
            chip.setOnLongClickListener(v -> {
                new AlertDialog.Builder(this)
                        .setTitle(s.name)
                        .setMessage(s.request)
                        .setPositiveButton("Run", (d, w) -> Shortcuts.start(this, s.name, mActing))
                        .setNegativeButton("Delete", (d, w) -> {
                            try {
                                Shortcuts.delete(this, s.name);
                            } catch (java.io.IOException e) {
                                Toast.makeText(this, e.getMessage(), Toast.LENGTH_SHORT).show();
                            }
                            onChanged();
                        })
                        .setNeutralButton(android.R.string.cancel, null)
                        .show();
                return true;
            });
            ((LinearLayout.LayoutParams) chip.getLayoutParams()).setMarginStart(
                    i == all.size() - 1 ? 0 : dp(8));
            mChips.addView(chip);
        }
        int vis = all.isEmpty() ? View.GONE : View.VISIBLE;
        if (scroll.getVisibility() != vis) scroll.setVisibility(vis);
    }

    /** Long-press on one of the user's messages: save it as a shortcut. */
    private void saveShortcut(String request) {
        Agent.Run last = Agent.lastFinished();
        java.util.List<String> steps = last != null && request.equals(last.request)
                ? last.steps : new java.util.ArrayList<>();
        EditText name = new EditText(this);
        name.setSingleLine(true);
        name.setHint("Name, e.g. Book my usual Uber");
        name.setText(Skills.clip(request, 30).replace("…", ""));
        name.selectAll();
        int p = dp(20);
        LinearLayout box = new LinearLayout(this);
        box.setPadding(p, dp(8), p, 0);
        box.addView(name, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        new AlertDialog.Builder(this)
                .setTitle("Save as shortcut")
                .setMessage(Skills.clip(request, 200))
                .setView(box)
                .setPositiveButton("Save", (d, w) -> {
                    String n = name.getText().toString().trim();
                    if (n.isEmpty()) return;
                    try {
                        Shortcuts.save(this, n, request, steps);
                        Toast.makeText(this, "Saved \"" + n + "\"", Toast.LENGTH_SHORT).show();
                    } catch (Exception e) {
                        Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
                    }
                    onChanged();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
