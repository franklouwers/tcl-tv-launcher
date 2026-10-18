package be.kiwazo.tvhome;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

/** All non-dock apps, 4 per row. Focus is driven by HomeActivity, not by RecyclerView. */
final class GridAdapter extends RecyclerView.Adapter<GridAdapter.Holder> {

    static final class Holder extends RecyclerView.ViewHolder {
        Holder(View v) {
            super(v);
        }
    }

    private final LayoutInflater inflater;
    private final int cardW, cardH, rowGap;
    private final List<AppEntry> items = new ArrayList<>();

    GridAdapter(LayoutInflater inflater, int cardW, int cardH, int rowGap) {
        this.inflater = inflater;
        this.cardW = cardW;
        this.cardH = cardH;
        this.rowGap = rowGap;
    }

    @SuppressWarnings("NotifyDataSetChanged")
    void submit(List<AppEntry> list) {
        items.clear();
        items.addAll(list);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = TileViews.create(inflater, parent, cardW, cardH);
        v.setLayoutParams(new RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        v.setPadding(0, 0, 0, rowGap);
        return new Holder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        TileViews.bind(holder.itemView, items.get(position));
    }

    @Override
    public int getItemCount() {
        return items.size();
    }
}
