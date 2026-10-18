package be.kiwazo.tvhome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

import androidx.dynamicanimation.animation.FloatValueHolder;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;

/**
 * The single translucent "glass" focus highlight. Its edges and corner radius
 * are five independent springs, so it glides and morphs from tile to pill to
 * menu row and can be retargeted mid-flight without stutter. No blur: the
 * glass look is a soft vertical fill, a diagonal sheen and a gradient hairline.
 */
public class GlassView extends View {

    private static final int L = 0, T = 1, R = 2, B = 3, RAD = 4;

    private final float[] v = new float[5];
    private final FloatValueHolder[] holders = new FloatValueHolder[5];
    private final SpringAnimation[] springs = new SpringAnimation[5];
    private boolean shown;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sheen = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final LinearGradient fillShader, sheenShader, strokeShader;
    private final Matrix matrix = new Matrix();
    private final RectF rect = new RectF();
    private final float density;

    public GlassView(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = getResources().getDisplayMetrics().density;
        for (int i = 0; i < 5; i++) {
            final int k = i;
            holders[i] = new FloatValueHolder();
            springs[i] = new SpringAnimation(holders[i]);
            springs[i].setSpring(new SpringForce(0f).setStiffness(560f).setDampingRatio(0.74f));
            springs[i].addUpdateListener((anim, value, velocity) -> {
                v[k] = value;
                invalidate();
            });
        }
        // Unit-space shaders; a local matrix maps them onto the current rect each frame.
        fillShader = new LinearGradient(0, 0, 0, 1,
                new int[]{0x30FFFFFF, 0x10FFFFFF, 0x06FFFFFF}, new float[]{0f, 0.5f, 1f},
                Shader.TileMode.CLAMP);
        sheenShader = new LinearGradient(0, 0, 1, 1,
                new int[]{0x2EFFFFFF, 0x00FFFFFF, 0x00FFFFFF}, new float[]{0f, 0.42f, 1f},
                Shader.TileMode.CLAMP);
        strokeShader = new LinearGradient(0, 0, 0, 1,
                new int[]{0xB3FFFFFF, 0x40FFFFFF, 0x26FFFFFF}, new float[]{0f, 0.5f, 1f},
                Shader.TileMode.CLAMP);
        fill.setShader(fillShader);
        sheen.setShader(sheenShader);
        stroke.setShader(strokeShader);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(1.5f * density);
    }

    /** Move to {@code r}; jumps instead of springing the first time or when !animate. */
    public void moveTo(RectF r, float radius, boolean animate) {
        float[] t = {r.left, r.top, r.right, r.bottom, radius};
        for (int i = 0; i < 5; i++) {
            if (!shown || !animate) {
                springs[i].cancel();
                holders[i].setValue(t[i]);
                springs[i].getSpring().setFinalPosition(t[i]);
                v[i] = t[i];
            } else {
                springs[i].animateToFinalPosition(t[i]);
            }
        }
        shown = true;
        invalidate();
    }

    /** Little bounce toward a direction we can't move in. */
    public void bump(int dx, int dy) {
        if (!shown) return;
        float speed = 420f * density;
        if (dx != 0) kick(L, R, dx * speed);
        if (dy != 0) kick(T, B, dy * speed);
    }

    private void kick(int a, int b, float velocity) {
        if (springs[a].isRunning() || springs[b].isRunning()) return;
        springs[a].setStartVelocity(velocity);
        springs[b].setStartVelocity(velocity);
        springs[a].start();
        springs[b].start();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (!shown) return;
        float l = v[L], t = v[T], r = v[R], b = v[B];
        float rad = Math.max(0f, v[RAD]);
        float w = r - l, h = b - t;
        if (w <= 1 || h <= 1) return;

        rect.set(l, t, r, b);
        matrix.setScale(1f, h);
        matrix.postTranslate(0f, t);
        fillShader.setLocalMatrix(matrix);
        canvas.drawRoundRect(rect, rad, rad, fill);

        matrix.setScale(w, h);
        matrix.postTranslate(l, t);
        sheenShader.setLocalMatrix(matrix);
        canvas.drawRoundRect(rect, rad, rad, sheen);

        float sw = stroke.getStrokeWidth() / 2f;
        rect.inset(sw, sw);
        matrix.setScale(1f, h);
        matrix.postTranslate(0f, t);
        strokeShader.setLocalMatrix(matrix);
        canvas.drawRoundRect(rect, Math.max(0f, rad - sw), Math.max(0f, rad - sw), stroke);
    }
}
