package be.kiwazo.tvhome;

import android.view.View;

import androidx.dynamicanimation.animation.DynamicAnimation;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;

/** Reusable per-view scale springs (slightly bouncy, retargetable). */
final class Springs {

    private Springs() {}

    static void scale(View v, float to, boolean animate) {
        SpringAnimation sx = spring(v, R.id.spring_scale_x, DynamicAnimation.SCALE_X);
        SpringAnimation sy = spring(v, R.id.spring_scale_y, DynamicAnimation.SCALE_Y);
        if (!animate) {
            sx.cancel();
            sy.cancel();
            v.setScaleX(to);
            v.setScaleY(to);
            return;
        }
        sx.animateToFinalPosition(to);
        sy.animateToFinalPosition(to);
    }

    private static SpringAnimation spring(View v, int key, DynamicAnimation.ViewProperty property) {
        Object tag = v.getTag(key);
        if (tag instanceof SpringAnimation) return (SpringAnimation) tag;
        SpringAnimation a = new SpringAnimation(v, property);
        a.setSpring(new SpringForce(1f).setStiffness(650f).setDampingRatio(0.6f));
        v.setTag(key, a);
        return a;
    }
}
