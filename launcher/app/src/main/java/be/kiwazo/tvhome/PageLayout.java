package be.kiwazo.tvhome;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;

/**
 * Screen-sized viewport whose child may be as tall as it likes. HomeActivity
 * scrolls by springing the child's translationY, so D-pad focus never triggers
 * any built-in scrolling behaviour.
 */
public class PageLayout extends ViewGroup {

    public PageLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int w = MeasureSpec.getSize(widthSpec);
        int h = MeasureSpec.getSize(heightSpec);
        int childW = MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY);
        int childH = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED);
        for (int i = 0; i < getChildCount(); i++) {
            getChildAt(i).measure(childW, childH);
        }
        setMeasuredDimension(w, h);
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        for (int i = 0; i < getChildCount(); i++) {
            View c = getChildAt(i);
            c.layout(0, 0, c.getMeasuredWidth(), c.getMeasuredHeight());
        }
    }
}
