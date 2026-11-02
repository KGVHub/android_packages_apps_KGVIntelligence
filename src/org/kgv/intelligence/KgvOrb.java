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

/**
 * KGV X Intelligence's orb: a glass sphere of flowing, dotted ribbons in KGV red, turning
 * in 3D. Calm when idle; faster and brighter while listening or working, and it swells
 * with the voice.
 */
final class KgvOrb extends View {
    // Crimson, scarlet, rose, magenta-red, and a pale highlight.
    static final int[] PALETTE = {0xFFFF2D3A, 0xFFFF5A4E, 0xFFFF7A90, 0xFFE0185C, 0xFFFFD6DA};

    private static final int RIBBONS = 6;
    private static final int STRANDS = 9;
    private static final int POINTS = 120;

    private final Paint mDots = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mRim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mCore = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix mMatrix = new Matrix();
    private final TimeAnimator mClock = new TimeAnimator();
    // Per ribbon: the plane's two axes and its normal, a phase, and a speed.
    private final float[][] mAxes = new float[RIBBONS][9];
    private final float[] mPhase = new float[RIBBONS];
    private final float[] mSpeed = new float[RIBBONS];
    private final float[][] mBuf = new float[3][STRANDS * POINTS * 2];
    private final int[] mBufLen = new int[3];
    private final float mDensity;
    private SweepGradient mRimShader;
    private float mTime;
    private float mRate = 1f;
    private float mTargetRate = 1f;
    private float mLevel;
    private float mSwell;

    KgvOrb(Context context) {
        super(context);
        mDensity = context.getResources().getDisplayMetrics().density;
        mDots.setStrokeCap(Paint.Cap.ROUND);
        mDots.setBlendMode(BlendMode.PLUS);
        mRim.setStyle(Paint.Style.STROKE);
        mRim.setBlendMode(BlendMode.PLUS);
        java.util.Random r = new java.util.Random(7);
        for (int k = 0; k < RIBBONS; k++) {
            // A random plane through the centre.
            float[] n = unit(r.nextFloat() * 2 - 1, r.nextFloat() * 2 - 1, r.nextFloat() * 2 - 1);
            float[] u = unit(cross(n, Math.abs(n[0]) < 0.9f ? new float[] {1, 0, 0}
                    : new float[] {0, 1, 0}));
            float[] v = cross(n, u);
            System.arraycopy(u, 0, mAxes[k], 0, 3);
            System.arraycopy(v, 0, mAxes[k], 3, 3);
            System.arraycopy(n, 0, mAxes[k], 6, 3);
            mPhase[k] = r.nextFloat() * 6.283f;
            mSpeed[k] = 0.35f + r.nextFloat() * 0.5f;
        }
        mClock.setTimeListener((animation, total, delta) -> {
            mRate += (mTargetRate - mRate) * 0.06f;
            mSwell += (mLevel - mSwell) * 0.25f;
            mLevel *= 0.88f;
            mTime += delta / 1000f * mRate;
            invalidate();
        });
    }

    /** Calm (idle) or lively (listening, thinking or working). */
    void setActive(boolean active) {
        mTargetRate = active ? 2.4f : 1f;
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

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        float cx = w / 2f, cy = h / 2f, r = Math.min(w, h) / 2f;
        mRimShader = new SweepGradient(cx, cy, new int[] {PALETTE[4], PALETTE[0], PALETTE[3],
                PALETTE[2], PALETTE[1], PALETTE[4]}, null);
        mCore.setShader(new RadialGradient(cx, cy, r, new int[] {0x33FF2D3A, 0x14E0185C,
                0x00000000}, new float[] {0f, 0.7f, 1f}, Shader.TileMode.CLAMP));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float cx = getWidth() / 2f, cy = getHeight() / 2f;
        float scale = 1f + 0.08f * mSwell;
        float R = Math.min(getWidth(), getHeight()) / 2f * 0.80f * scale;
        float t = mTime;

        canvas.drawCircle(cx, cy, R * 1.1f, mCore);

        // The whole sphere tumbles slowly.
        float ay = t * 0.45f, ax = 0.5f + 0.35f * (float) Math.sin(t * 0.3f);
        float cyw = (float) Math.cos(ay), syw = (float) Math.sin(ay);
        float cxw = (float) Math.cos(ax), sxw = (float) Math.sin(ax);

        float dot = Math.max(1.2f, 1.5f * mDensity * Math.min(1f, R / (60 * mDensity)));
        for (int k = 0; k < RIBBONS; k++) {
            float[] a = mAxes[k];
            float ph = mPhase[k] + t * mSpeed[k];
            mBufLen[0] = mBufLen[1] = mBufLen[2] = 0;
            for (int j = 0; j < STRANDS; j++) {
                float off = (j - (STRANDS - 1) / 2f) / STRANDS;
                for (int i = 0; i < POINTS; i++) {
                    float th = (float) (i * 2 * Math.PI / POINTS);
                    // A ring in the ribbon's plane, breathing, lifted into a twisting band.
                    float rad = R * (0.80f + 0.13f * (float) Math.sin(3 * th + ph)
                            + 0.05f * mSwell * (float) Math.sin(5 * th - 2 * ph));
                    // The band narrows and widens along the ring, like a ribbon of mesh.
                    float lift = R * (0.22f * (float) Math.sin(2 * th + ph * 1.3f)
                            + off * 0.26f * (0.35f + 0.65f
                            * Math.abs((float) Math.sin(th * 1.5f - ph * 0.7f))));
                    float c = (float) Math.cos(th) * rad, s = (float) Math.sin(th) * rad;
                    float x = a[0] * c + a[3] * s + a[6] * lift;
                    float y = a[1] * c + a[4] * s + a[7] * lift;
                    float z = a[2] * c + a[5] * s + a[8] * lift;
                    // Keep it inside the glass.
                    float len = (float) Math.sqrt(x * x + y * y + z * z);
                    if (len > R * 0.97f) {
                        float f = R * 0.97f / len;
                        x *= f;
                        y *= f;
                        z *= f;
                    }
                    // Tumble: around y, then x.
                    float x1 = x * cyw + z * syw, z1 = -x * syw + z * cyw;
                    float y2 = y * cxw - z1 * sxw, z2 = y * sxw + z1 * cxw;
                    // Depth bucket: back, middle, front.
                    int b = z2 < -R * 0.33f ? 0 : z2 < R * 0.33f ? 1 : 2;
                    float[] buf = mBuf[b];
                    buf[mBufLen[b]++] = cx + x1;
                    buf[mBufLen[b]++] = cy + y2;
                }
            }
            int color = PALETTE[k % 4];
            for (int b = 0; b < 3; b++) {
                if (mBufLen[b] == 0) continue;
                mDots.setColor(b == 2 && k == 0 ? PALETTE[4] : color);
                // Faint dots; where strands overlap they add up to bright highlights.
                mDots.setAlpha(b == 0 ? 45 : b == 1 ? 95 : 170);
                mDots.setStrokeWidth(dot * (b == 0 ? 0.5f : b == 1 ? 0.65f : 0.8f));
                canvas.drawPoints(mBuf[b], 0, mBufLen[b], mDots);
            }
        }

        // The glass rim: soft glow to a bright edge, the colours slowly turning.
        if (mRimShader != null) {
            mMatrix.setRotate(-t * 40f, cx, cy);
            mRimShader.setLocalMatrix(mMatrix);
            mRim.setShader(mRimShader);
            float[] widths = {10, 5, 1.6f};
            int[] alphas = {50, 110, 255};
            for (int i = 0; i < widths.length; i++) {
                mRim.setStrokeWidth(widths[i] * mDensity * (R / (80 * mDensity) * 0.5f + 0.5f));
                mRim.setAlpha(alphas[i]);
                canvas.drawCircle(cx, cy, R, mRim);
            }
        }
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
