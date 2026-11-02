package org.kgv.intelligence;

import android.animation.TimeAnimator;
import android.content.Context;
import android.graphics.BlendMode;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.SweepGradient;
import android.view.View;

import java.util.Arrays;
import java.util.Random;

/**
 * KGV X Intelligence's orb: in a glass sphere with a bright rim, a fluid core of glowing
 * dots wobbles like liquid while wide dotted sheets sweep and curl around it, white-hot
 * at their edges, and small electric sparks flicker over the core. Reds and pinks; the
 * motion loops seamlessly every LOOP seconds, runs faster while listening or working,
 * and swells with the voice.
 */
final class KgvOrb extends View {
    static final int RED = 0xFFD4101F;
    static final int ROSE = 0xFFE0485A;
    static final int MAGENTA = 0xFFB01040;
    static final int SCARLET = 0xFFD8302A;
    static final int HOT = 0xFFFFD2D7;
    /** The orb's colours, for the edge glow and buttons. */
    static final int[] PALETTE = {RED, ROSE, HOT, MAGENTA, SCARLET};

    private static final float LOOP = 10f;
    private static final float TAU = (float) (2 * Math.PI);
    private static final int CORE = 2400;
    private static final int[] SHEET_COLOR = {ROSE, MAGENTA, SCARLET, RED};
    // Whole turns per loop, so the loop is seamless.
    private static final int[] SHEET_TURNS = {1, -1, 1, 2};
    private static final int SU = 140;
    private static final int SV = 16;
    // Dots are drawn in batches of one colour and brightness.
    private static final int LEVELS = 4;
    private static final int[] LEVEL_ALPHA = {40, 80, 125, 175};
    private static final float SPARK_MS = 70;

    private final Paint mDots = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mRim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mHalo = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mSparkGlow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mSparkCore = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix mMatrix = new Matrix();
    private final TimeAnimator mClock = new TimeAnimator();
    private final float[] mCore = new float[CORE * 3];
    private final float[][] mSheet = new float[SHEET_COLOR.length][9];  // u, v, n
    private final float[] mSheetPhase = new float[SHEET_COLOR.length];
    private final float[] mSheetArc = new float[SHEET_COLOR.length];
    private final float[] mSheetWidth = new float[SHEET_COLOR.length];
    // Batches: [colour slot][level] -> x,y pairs. Slots: 0 base colour, 1 front, 2 hot.
    private final float[][] mBuf = new float[3 * LEVELS][SU * SV * 2 + CORE * 2];
    private final int[] mLen = new int[3 * LEVELS];
    private final float[] mSparks = new float[3 * 8 * 4];
    private final Random mSparkRandom = new Random();
    private final float mDensity;
    private SweepGradient mRimShader;
    private float mTime;
    private float mRate = 1f;
    private float mTargetRate = 1f;
    private float mLevel;
    private float mSwell;
    private float mSparkClock = SPARK_MS;
    private int mSparkLen;
    private final float[] mP = new float[3];
    private Canvas mDrawCanvas;
    // The current frame's rotation.
    private float mCy, mSy, mCx, mSx, mCxC, mCyC;

    KgvOrb(Context context) {
        super(context);
        mDensity = context.getResources().getDisplayMetrics().density;
        mDots.setStrokeCap(Paint.Cap.ROUND);
        mRim.setStyle(Paint.Style.STROKE);
        mSparkGlow.setStyle(Paint.Style.STROKE);
        mSparkCore.setStyle(Paint.Style.STROKE);
        mSparkGlow.setStrokeCap(Paint.Cap.ROUND);
        mSparkCore.setStrokeCap(Paint.Cap.ROUND);
        mSparkGlow.setColor(RED);
        mSparkGlow.setAlpha(60);
        mSparkCore.setColor(HOT);
        mSparkCore.setAlpha(200);
        for (Paint p : new Paint[] {mDots, mRim, mHalo, mSparkGlow, mSparkCore}) {
            p.setBlendMode(BlendMode.PLUS);
        }
        // The core: an even dot lattice on a sphere (Fibonacci).
        for (int i = 0; i < CORE; i++) {
            float y = 1 - 2 * (i + 0.5f) / CORE;
            float r = (float) Math.sqrt(1 - y * y);
            float th = i * 2.399963f;
            mCore[i * 3] = r * (float) Math.cos(th);
            mCore[i * 3 + 1] = y;
            mCore[i * 3 + 2] = r * (float) Math.sin(th);
        }
        Random rnd = new Random(5);
        for (int k = 0; k < SHEET_COLOR.length; k++) {
            float[] n = unit(rnd.nextFloat() * 2 - 1, rnd.nextFloat() * 2 - 1,
                    rnd.nextFloat() * 2 - 1);
            float[] u = unit(cross(n, Math.abs(n[0]) < 0.9f ? new float[] {1, 0, 0}
                    : new float[] {0, 1, 0}));
            float[] v = cross(n, u);
            System.arraycopy(u, 0, mSheet[k], 0, 3);
            System.arraycopy(v, 0, mSheet[k], 3, 3);
            System.arraycopy(n, 0, mSheet[k], 6, 3);
            mSheetPhase[k] = rnd.nextFloat() * TAU;
            mSheetArc[k] = TAU * (0.45f + 0.2f * rnd.nextFloat());
            mSheetWidth[k] = 0.35f + 0.15f * rnd.nextFloat();
        }
        mClock.setTimeListener((animation, total, delta) -> {
            mRate += (mTargetRate - mRate) * 0.05f;
            mSwell += (mLevel - mSwell) * 0.25f;
            mLevel *= 0.88f;
            mTime = (mTime + delta / 1000f * mRate) % LOOP;
            mSparkClock += delta * mRate;
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

    private float radius() {
        return Math.min(getWidth(), getHeight()) / 2f * 0.84f;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        float cx = w / 2f, cy = h / 2f, R = radius();
        if (R <= 0) return;
        mRimShader = new SweepGradient(cx, cy, new int[] {HOT, RED, MAGENTA, ROSE, SCARLET, HOT},
                null);
        // A soft red glow inside the glass, falling off cleanly.
        mHalo.setShader(new RadialGradient(cx, cy, R * 1.05f, new int[] {0x29D20E18,
                0x1CD20E18, 0x0AD20E18, 0x02D20E18, 0x00D20E18},
                new float[] {0f, 0.32f, 0.55f, 0.8f, 1f}, Shader.TileMode.CLAMP));
    }

    /** Rotates the point by this frame's tumble and projects it: out = x, y, depth 0..1. */
    private void project(float x, float y, float z, float R, float[] out) {
        float x1 = x * mCy + z * mSy, z1 = -x * mSy + z * mCy;
        float y2 = y * mCx - z1 * mSx, z2 = y * mSx + z1 * mCx;
        out[0] = mCxC + x1;
        out[1] = mCyC + y2;
        out[2] = (z2 / R + 1) / 2;
    }

    private void put(int slot, float alpha, float x, float y) {
        int level = Math.min(LEVELS - 1, Math.max(0, (int) (alpha * LEVELS)));
        int b = slot * LEVELS + level;
        mBuf[b][mLen[b]++] = x;
        mBuf[b][mLen[b]++] = y;
    }

    private void flush(int[] slotColors, float size) {
        for (int slot = 0; slot < 3; slot++) {
            for (int level = 0; level < LEVELS; level++) {
                int b = slot * LEVELS + level;
                if (mLen[b] == 0) continue;
                mDots.setColor(slotColors[slot]);
                mDots.setAlpha(LEVEL_ALPHA[level]);
                mDots.setStrokeWidth(size * (0.75f + 0.1f * level));
                mDrawCanvas.drawPoints(mBuf[b], 0, mLen[b], mDots);
            }
        }
        Arrays.fill(mLen, 0);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        mDrawCanvas = canvas;
        float cx = getWidth() / 2f, cy = getHeight() / 2f;
        float R = radius() * (1f + 0.05f * mSwell);
        float a0 = TAU * mTime / LOOP;
        mCxC = cx;
        mCyC = cy;
        mCy = (float) Math.cos(a0);
        mSy = (float) Math.sin(a0);
        float ax = 0.35f * (float) Math.sin(a0);
        mCx = (float) Math.cos(ax);
        mSx = (float) Math.sin(ax);
        float dot = Math.max(1f, 1.1f * mDensity * Math.min(1f, R / (70 * mDensity)));

        canvas.drawCircle(cx, cy, R * 1.05f, mHalo);

        // Fluid core: travelling waves over the dot sphere.
        float rc = R * 0.62f;
        float wobble = 1f + 0.6f * mSwell;
        for (int i = 0; i < CORE; i++) {
            float x = mCore[i * 3], y = mCore[i * 3 + 1], z = mCore[i * 3 + 2];
            float d = wobble * (0.10f * sin(3 * x + a0 * 2 + 1) + 0.09f * sin(4 * y - a0 * 3 + 2)
                    + 0.08f * sin(3 * z + a0 * 2) + 0.06f * sin(2 * (x + y - z) + a0 * 4));
            float r = rc * (1 + d);
            project(x * r, y * r, z * r, rc, mP);
            float depth = Math.max(0f, Math.min(1f, mP[2]));
            int slot = d > 0.17f ? 2 : depth > 0.7f ? 1 : 0;
            put(slot, 0.16f + 0.6f * depth, mP[0], mP[1]);
        }
        flush(new int[] {RED, ROSE, HOT}, dot);

        // Sheets sweeping around the shell, curling, white-hot along their edges.
        for (int k = 0; k < SHEET_COLOR.length; k++) {
            float[] f = mSheet[k];
            float ph = mSheetPhase[k] + SHEET_TURNS[k] * a0;
            for (int i = 0; i < SU; i++) {
                float t = i / (SU - 1f);
                float ang = ph + t * mSheetArc[k];
                float taper = (float) Math.pow(Math.sin(Math.PI * t), 0.8);
                float ca = (float) Math.cos(ang), sa = sin(ang);
                float bx = ca * f[0] + sa * f[3], by = ca * f[1] + sa * f[4],
                        bz = ca * f[2] + sa * f[5];
                float ring = 0.93f + 0.05f * sin(3 * ang + a0);
                for (int j = 0; j < SV; j++) {
                    float w = (j / (SV - 1f) - 0.5f) * 2;
                    float tilt = mSheetWidth[k] * w * taper + 0.28f * sin(2 * ang - a0 + w);
                    float r = R * (ring - 0.10f * Math.abs(w) * taper);
                    float ct = (float) Math.cos(tilt), st = sin(tilt);
                    project((bx * ct + f[6] * st) * r, (by * ct + f[7] * st) * r,
                            (bz * ct + f[8] * st) * r, R, mP);
                    float depth = Math.max(0f, Math.min(1f, mP[2]));
                    boolean edge = Math.abs(w) > 0.85f;
                    float alpha = (0.10f + 0.55f * depth) * taper * (edge ? 1.6f : 1f);
                    put(edge && depth > 0.5f ? 2 : 0, alpha, mP[0], mP[1]);
                }
            }
            flush(new int[] {SHEET_COLOR[k], SHEET_COLOR[k], HOT}, dot);
        }

        drawSparks(canvas, rc);

        // The glass rim, colours slowly turning.
        if (mRimShader != null) {
            mMatrix.setRotate(-mTime / LOOP * 360f, cx, cy);
            mRimShader.setLocalMatrix(mMatrix);
            mRim.setShader(mRimShader);
            float[] widths = {6, 3, 1.1f};
            int[] alphas = {35, 80, 230};
            for (int i = 0; i < widths.length; i++) {
                mRim.setStrokeWidth(widths[i] * mDensity * Math.min(1f, R / (70 * mDensity)));
                mRim.setAlpha(alphas[i]);
                canvas.drawCircle(cx, cy, R, mRim);
            }
        }
    }

    /** Short jagged arcs over the core, re-drawn every SPARK_MS. */
    private void drawSparks(Canvas canvas, float rc) {
        if (mSparkClock >= SPARK_MS) {
            mSparkClock = 0;
            mSparkLen = 0;
            Random sr = mSparkRandom;
            int arcs = 2 + sr.nextInt(2);
            for (int a = 0; a < arcs; a++) {
                float[] p = unit(sr.nextFloat() * 2 - 1, sr.nextFloat() * 2 - 1,
                        sr.nextFloat() * 2 - 1);
                float[] q = unit(p[0] + (sr.nextFloat() - 0.5f) * 0.8f,
                        p[1] + (sr.nextFloat() - 0.5f) * 0.8f, p[2] + (sr.nextFloat() - 0.5f) * 0.8f);
                float px = 0, py = 0;
                for (int m = 0; m < 9; m++) {
                    float f = m / 8f, jit = 0.09f * (1 - Math.abs(2 * f - 1));
                    float[] d = unit(p[0] * (1 - f) + q[0] * f + (sr.nextFloat() - 0.5f) * jit,
                            p[1] * (1 - f) + q[1] * f + (sr.nextFloat() - 0.5f) * jit,
                            p[2] * (1 - f) + q[2] * f + (sr.nextFloat() - 0.5f) * jit);
                    project(d[0] * rc * 1.05f, d[1] * rc * 1.05f, d[2] * rc * 1.05f, rc, mP);
                    if (m > 0) {
                        mSparks[mSparkLen++] = px;
                        mSparks[mSparkLen++] = py;
                        mSparks[mSparkLen++] = mP[0];
                        mSparks[mSparkLen++] = mP[1];
                    }
                    px = mP[0];
                    py = mP[1];
                }
            }
        }
        if (mSparkLen == 0) return;
        mSparkGlow.setStrokeWidth(3.5f * mDensity);
        mSparkCore.setStrokeWidth(0.9f * mDensity);
        canvas.drawLines(mSparks, 0, mSparkLen, mSparkGlow);
        canvas.drawLines(mSparks, 0, mSparkLen, mSparkCore);
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
