package org.kgv.intelligence;

import android.animation.TimeAnimator;
import android.content.Context;
import android.graphics.BlendMode;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.view.View;

/**
 * KGV X Intelligence's orb: a breathing red energy core in a glass sphere, with crimson,
 * ruby and amber wisps orbiting its edge and a soft halo fading into black. The motion
 * loops seamlessly every LOOP seconds; it runs faster while listening or working, and the
 * voice makes it swell.
 */
final class KgvOrb extends View {
    static final int CRIMSON = 0xFFDC143C;
    static final int RUBY = 0xFFE0115F;
    static final int RED = 0xFFFF2D3A;
    static final int AMBER = 0xFFFFB347;
    static final int WARM = 0xFFFFE3C2;
    /** The orb's colours, for the edge glow and buttons. */
    static final int[] PALETTE = {RED, CRIMSON, AMBER, RUBY, WARM};

    private static final float LOOP = 8f;
    private static final float TAU = (float) (2 * Math.PI);
    private static final int[] WISP = {CRIMSON, AMBER, RUBY, RED, AMBER, CRIMSON};
    // Whole turns per loop, so the loop is seamless.
    private static final int[] TURNS = {1, -1, 2, 1, -2, 1};
    private static final int STRANDS = 7;
    private static final int POINTS = 140;
    // Wisp dots by brightness: alpha and size (x dot) of each level, plus warm highlights.
    private static final int LEVELS = 4;
    private static final int[] LEVEL_ALPHA = {22, 55, 100, 150};
    private static final float[] LEVEL_SIZE = {0.6f, 0.75f, 0.9f, 1f};

    private final Paint mDots = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mCore = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mBody = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mHalo = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TimeAnimator mClock = new TimeAnimator();
    // Per wisp: the plane's two axes and its normal, and a phase.
    private final float[][] mAxes = new float[WISP.length][9];
    private final float[] mPhase = new float[WISP.length];
    private final float[][] mBuf = new float[LEVELS + 1][STRANDS * POINTS * 2];
    private final int[] mLen = new int[LEVELS + 1];
    private final float mDensity;
    private float mTime;
    private float mRate = 1f;
    private float mTargetRate = 1f;
    private float mLevel;
    private float mSwell;

    KgvOrb(Context context) {
        super(context);
        mDensity = context.getResources().getDisplayMetrics().density;
        mDots.setStrokeCap(Paint.Cap.ROUND);
        for (Paint p : new Paint[] {mDots, mCore, mBody, mHalo}) p.setBlendMode(BlendMode.PLUS);
        java.util.Random r = new java.util.Random(11);
        for (int k = 0; k < WISP.length; k++) {
            float[] n = unit(r.nextFloat() * 2 - 1, r.nextFloat() * 2 - 1, r.nextFloat() * 2 - 1);
            float[] u = unit(cross(n, Math.abs(n[0]) < 0.9f ? new float[] {1, 0, 0}
                    : new float[] {0, 1, 0}));
            float[] v = cross(n, u);
            System.arraycopy(u, 0, mAxes[k], 0, 3);
            System.arraycopy(v, 0, mAxes[k], 3, 3);
            System.arraycopy(n, 0, mAxes[k], 6, 3);
            mPhase[k] = r.nextFloat() * TAU;
        }
        mClock.setTimeListener((animation, total, delta) -> {
            mRate += (mTargetRate - mRate) * 0.05f;
            mSwell += (mLevel - mSwell) * 0.25f;
            mLevel *= 0.88f;
            mTime = (mTime + delta / 1000f * mRate) % LOOP;
            invalidate();
        });
    }

    /** Calm (idle) or lively (listening, thinking or working). */
    void setActive(boolean active) {
        mTargetRate = active ? 2.2f : 1f;
    }

    /** The voice level in dB (about -2 to 10), from Voice. */
    void setVoiceLevel(float rmsDb) {
        mLevel = Math.max(mLevel, Math.max(0f, Math.min(1f, (rmsDb + 2f) / 12f)));
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        mClock.start();
    }

    @Override
    protected void onDetachedFromWindow() {
        mClock.cancel();
        super.onDetachedFromWindow();
    }

    /** The sphere's radius at rest: room around it for the halo. */
    private float baseRadius() {
        return Math.min(getWidth(), getHeight()) / 2f * 0.62f;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        float cx = w / 2f, cy = h / 2f, R = baseRadius();
        if (R <= 0) return;
        // Hot centre: exp(-(d/0.42)^2).
        mCore.setShader(new RadialGradient(cx, cy, R, new int[] {0xFFFF4229, 0xCCFF4229,
                0x5EFF4229, 0x21FF4229, 0x05FF4229, 0x00FF4229},
                new float[] {0f, 0.2f, 0.42f, 0.6f, 0.84f, 1f}, Shader.TileMode.CLAMP));
        // Glass body: exp(-(d/0.85)^4), deep red.
        mBody.setShader(new RadialGradient(cx, cy, R * 1.2f, new int[] {0x38D90D12, 0x32D90D12,
                0x23D90D12, 0x15D90D12, 0x08D90D12, 0x00D90D12},
                new float[] {0f, 0.42f, 0.58f, 0.71f, 0.83f, 1f}, Shader.TileMode.CLAMP));
        // Halo around the shell, fading cleanly into black.
        mHalo.setShader(new RadialGradient(cx, cy, R * 1.45f, new int[] {0x00E6241A,
                0x12E6241A, 0x29E6241A, 0x12E6241A, 0x02E6241A, 0x00E6241A},
                new float[] {0.5f, 0.55f, 0.69f, 0.83f, 0.97f, 1f}, Shader.TileMode.CLAMP));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float cx = getWidth() / 2f, cy = getHeight() / 2f;
        float a0 = TAU * mTime / LOOP;
        float breathe = 0.5f - 0.5f * (float) Math.cos(a0 * 2);   // two breaths per loop
        float grow = 1f + 0.035f * breathe + 0.06f * mSwell;
        float R = baseRadius() * grow;

        canvas.save();
        canvas.scale(grow, grow, cx, cy);
        mCore.setAlpha(Math.round(255 * Math.min(1f, 0.55f + 0.45f * breathe + 0.3f * mSwell)));
        mHalo.setAlpha(Math.round(255 * (0.7f + 0.3f * breathe)));
        canvas.drawCircle(cx, cy, baseRadius() * 1.45f, mHalo);
        canvas.drawCircle(cx, cy, baseRadius() * 1.2f, mBody);
        canvas.drawCircle(cx, cy, baseRadius(), mCore);
        canvas.restore();

        // The sphere turns once per loop, tilting gently.
        float ay = a0, ax = 0.45f + 0.25f * (float) Math.sin(a0);
        float cyw = (float) Math.cos(ay), syw = (float) Math.sin(ay);
        float cxw = (float) Math.cos(ax), sxw = (float) Math.sin(ax);
        float dot = Math.max(1f, 1.3f * mDensity * Math.min(1f, R / (70 * mDensity)));

        for (int k = 0; k < WISP.length; k++) {
            float[] a = mAxes[k];
            float ph = mPhase[k] + TURNS[k] * a0;
            java.util.Arrays.fill(mLen, 0);
            for (int j = 0; j < STRANDS; j++) {
                float off = (j - (STRANDS - 1) / 2f) / STRANDS;
                for (int i = 0; i < POINTS; i++) {
                    float th = i * TAU / POINTS;
                    // Wisps hug the edge, undulating.
                    float rad = R * (0.90f + 0.07f * sin(3 * th + ph)
                            + 0.03f * breathe * sin(5 * th - ph));
                    float lift = R * (0.14f * sin(2 * th + ph)
                            + off * 0.18f * (0.3f + 0.7f * Math.abs(sin(1.5f * th - ph))));
                    float c = (float) Math.cos(th) * rad, s = sin(th) * rad;
                    float x = a[0] * c + a[3] * s + a[6] * lift;
                    float y = a[1] * c + a[4] * s + a[7] * lift;
                    float z = a[2] * c + a[5] * s + a[8] * lift;
                    float len = (float) Math.sqrt(x * x + y * y + z * z);
                    if (len > R * 1.02f) {
                        float f = R * 1.02f / len;
                        x *= f;
                        y *= f;
                        z *= f;
                    }
                    float x1 = x * cyw + z * syw, z1 = -x * syw + z * cyw;
                    float y2 = y * cxw - z1 * sxw, z2 = y * sxw + z1 * cxw;
                    float depth = (z2 / R + 1) / 2;           // 0 back .. 1 front
                    // Fade along the ring so each reads as a wisp, not a ring.
                    float tail = 0.5f + 0.5f * sin(th - ph);
                    float alpha = (0.10f + 0.55f * depth) * tail;
                    int b = depth > 0.85f && tail > 0.8f ? LEVELS
                            : Math.min(LEVELS - 1, (int) (alpha / 0.65f * LEVELS));
                    if (alpha < 0.03f) continue;
                    mBuf[b][mLen[b]++] = cx + x1;
                    mBuf[b][mLen[b]++] = cy + y2;
                }
            }
            for (int b = 0; b <= LEVELS; b++) {
                if (mLen[b] == 0) continue;
                boolean warm = b == LEVELS;
                mDots.setColor(warm ? WARM : WISP[k]);
                mDots.setAlpha(warm ? 170 : LEVEL_ALPHA[b]);
                mDots.setStrokeWidth(dot * (warm ? 1.1f : LEVEL_SIZE[b]));
                canvas.drawPoints(mBuf[b], 0, mLen[b], mDots);
            }
        }
    }

    private static float sin(float v) {
        return (float) Math.sin(v);
    }

    private static float[] cross(float[] a, float[] b) {
        return new float[] {a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2],
                a[0] * b[1] - a[1] * b[0]};
    }

    private static float[] unit(float... v) {
        float l = (float) Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
        return new float[] {v[0] / l, v[1] / l, v[2] / l};
    }
}
