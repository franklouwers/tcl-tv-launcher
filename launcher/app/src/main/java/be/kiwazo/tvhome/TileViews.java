package be.kiwazo.tvhome;

import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

/** Creates and binds app tiles (rounded banner card + name shown on focus). */
final class TileViews {

    private TileViews() {}

    static View create(LayoutInflater inflater, ViewGroup parent, int cardW, int cardH) {
        View v = inflater.inflate(R.layout.item_tile, parent, false);
        View card = v.findViewById(R.id.card);
        ViewGroup.LayoutParams lp = card.getLayoutParams();
        lp.width = cardW;
        lp.height = cardH;
        card.setLayoutParams(lp);
        card.setClipToOutline(true);
        return v;
    }

    static void bind(View v, AppEntry e) {
        v.setTag(R.id.tag_entry, e);
        ImageView banner = v.findViewById(R.id.banner);
        ImageView icon = v.findViewById(R.id.icon);
        View card = v.findViewById(R.id.card);
        GradientDrawable bg = (GradientDrawable) card.getBackground().mutate();
        if (e.banner != null) {
            banner.setImageDrawable(e.banner);
            banner.setVisibility(View.VISIBLE);
            icon.setVisibility(View.GONE);
            bg.setColor(0xFF1C1C1F);
        } else {
            // Icon-only app: icon on a deep tint of its own colour.
            banner.setVisibility(View.GONE);
            icon.setImageDrawable(e.icon);
            icon.setVisibility(View.VISIBLE);
            float[] hsv = new float[3];
            Color.colorToHSV(e.color, hsv);
            hsv[1] = Math.min(hsv[1], 0.55f);
            hsv[2] = 0.22f;
            bg.setColor(Color.HSVToColor(hsv));
        }
        TextView label = v.findViewById(R.id.label);
        label.setText(e.label);
        label.setAlpha(0f);
        v.setScaleX(1f);
        v.setScaleY(1f);
    }
}
