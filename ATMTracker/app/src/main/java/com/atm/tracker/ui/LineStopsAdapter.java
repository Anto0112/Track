package com.atm.tracker.ui;

import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.atm.tracker.R;

import java.util.List;

public class LineStopsAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private static final int TYPE_HEADER = 0;
    private static final int TYPE_STOP   = 1;

    public interface OnStopClickListener    { void onClick(StopItem item); }
    public interface OnStopVisibleListener  { void onVisible(StopItem item); }
    public interface OnHeaderSwapListener   { void onSwap(); }

    public static class StopItem {
        public boolean isHeader;
        public String  headerText;
        public boolean headerShowSwap; // true → mostra il tasto inverti su questo header
        public String  customerCode;
        public String  description;
        public String  waitMessage;
        public String  lineCode;
        public boolean loading = false;
        public boolean loaded  = false;
        /** true → questa fermata è quella di provenienza e va evidenziata */
        public boolean highlight = false;
    }

    private final List<StopItem>     items;
    private final OnStopClickListener   clickListener;
    private       OnStopVisibleListener visibleListener;
    private       OnHeaderSwapListener  swapListener;

    public LineStopsAdapter(List<StopItem> items, OnStopClickListener clickListener) {
        this.items         = items;
        this.clickListener = clickListener;
    }

    public void setOnStopVisibleListener(OnStopVisibleListener l) { this.visibleListener = l; }
    public void setOnHeaderSwapListener(OnHeaderSwapListener l)   { this.swapListener = l; }

    @Override public int getItemViewType(int pos) {
        return items.get(pos).isHeader ? TYPE_HEADER : TYPE_STOP;
    }

    @NonNull @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inf = LayoutInflater.from(parent.getContext());
        if (viewType == TYPE_HEADER)
            return new HeaderVH(inf.inflate(R.layout.item_line_header, parent, false));
        return new StopVH(inf.inflate(R.layout.item_stop_row, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        StopItem item = items.get(position);
        if (holder instanceof HeaderVH) {
            HeaderVH hvh = (HeaderVH) holder;
            hvh.tvHeader.setText(item.headerText);
            if (item.headerShowSwap && swapListener != null) {
                hvh.btnSwap.setVisibility(View.VISIBLE);
                hvh.btnSwap.setOnClickListener(v -> swapListener.onSwap());
            } else {
                hvh.btnSwap.setVisibility(View.GONE);
            }
            return;
        }

        StopVH vh = (StopVH) holder;
        vh.tvDesc.setText(item.description);
        vh.tvCode.setText(item.customerCode);

        if (item.loading) {
            vh.progress.setVisibility(View.VISIBLE);
            vh.tvWait.setVisibility(View.GONE);
        } else if (item.loaded) {
            vh.progress.setVisibility(View.GONE);
            vh.tvWait.setVisibility(View.VISIBLE);
            if (item.waitMessage != null && !item.waitMessage.isEmpty()) {
                vh.tvWait.setText(item.waitMessage);
                vh.tvWait.setTextColor(Color.parseColor("#00C853"));
            } else {
                vh.tvWait.setText("—");
                vh.tvWait.setTextColor(Color.GRAY);
            }
        } else {
            vh.progress.setVisibility(View.GONE);
            vh.tvWait.setVisibility(View.INVISIBLE);
            if (visibleListener != null && !item.loading && !item.loaded) {
                item.loading = true;
                visibleListener.onVisible(item);
            }
        }

        vh.itemView.setOnClickListener(v -> clickListener.onClick(item));

        // Evidenziazione fermata di provenienza (recycling-safe: ripristina sempre)
        if (item.highlight) {
            vh.itemView.setBackgroundColor(android.graphics.Color.parseColor("#330266AD"));
        } else {
            android.util.TypedValue tv = new android.util.TypedValue();
            vh.itemView.getContext().getTheme().resolveAttribute(
                    android.R.attr.selectableItemBackground, tv, true);
            vh.itemView.setBackgroundResource(tv.resourceId);
        }
    }

    @Override public int getItemCount() { return items.size(); }

    static class HeaderVH extends RecyclerView.ViewHolder {
        TextView    tvHeader;
        ImageButton btnSwap;
        HeaderVH(View v) {
            super(v);
            tvHeader = v.findViewById(R.id.tv_header);
            btnSwap  = v.findViewById(R.id.btn_swap_dir);
        }
    }

    static class StopVH extends RecyclerView.ViewHolder {
        TextView    tvDesc, tvCode, tvWait;
        ProgressBar progress;
        StopVH(View v) {
            super(v);
            tvDesc   = v.findViewById(R.id.tv_stop_desc);
            tvCode   = v.findViewById(R.id.tv_stop_code);
            tvWait   = v.findViewById(R.id.tv_stop_wait);
            progress = v.findViewById(R.id.progress_wait);
        }
    }
}
