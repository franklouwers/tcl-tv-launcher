package be.kiwazo.tvhome;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

/** Soft elliptical glow in the focused app's banner colour. Static image, no blur. */
public class GlowView extends View {

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix matrix = new Matrix();
    private float cx, cy, rx = 1f, ry = 1f;
    private int color = Color.TRANSPARENT;
    private ValueAnimator anim;

    public GlowView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public void setGeometry(float cx, float cy, float rx, float ry) {
        this.cx = cx;
        this.cy = cy;
        this.rx = rx;
        this.ry = ry;
        apply(color);
    }

    public void setColor(int c) {
        if (anim != null) anim.cancel();
        apply(c);
    }

    public void animateTo(int c) {
        if (anim != null) anim.cancel();
        anim = ValueAnimator.ofArgb(color, c);
        anim.setDuration(450);
        anim.setInterpolator(new DecelerateInterpolator(1.6f));
        anim.addUpdateListener(a -> apply((int) a.getAnimatedValue()));
        anim.start();
    }

    private void apply(int c) {
        color = c;
        RadialGradient g = new RadialGradient(0f, 0f, 1f,
                new int[]{withAlpha(c, 0.50f), withAlpha(c, 0.18f), withAlpha(c, 0f)},
                new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP);
        matrix.setScale(rx, ry);
        matrix.postTranslate(cx, cy);
        g.setLocalMatrix(matrix);
        paint.setShader(g);
        invalidate();
    }

    private static int withAlpha(int c, float f) {
        return Color.argb(Math.round(Color.alpha(c) * f), Color.red(c), Color.green(c), Color.blue(c));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (Color.alpha(color) == 0) return;
        canvas.drawRect(0f, 0f, getWidth(), getHeight(), paint);
    }
}
