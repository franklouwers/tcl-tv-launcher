package be.kiwazo.tvhome;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.widget.FrameLayout;

/** FrameLayout that stretches its GlassView over the full (tall) content. */
public class ContentLayout extends FrameLayout {

    public ContentLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        super.onMeasure(widthSpec, heightSpec);
        int w = MeasureSpec.makeMeasureSpec(getMeasuredWidth(), MeasureSpec.EXACTLY);
        int h = MeasureSpec.makeMeasureSpec(getMeasuredHeight(), MeasureSpec.EXACTLY);
        for (int i = 0; i < getChildCount(); i++) {
            View c = getChildAt(i);
            if (c instanceof GlassView) c.measure(w, h);
        }
    }
}
