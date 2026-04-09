package com.atm.tracker.ui;

import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.atm.tracker.R;

import java.util.List;

public class LineStopsAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private static final int TYPE_HEADER = 0;
    private static final int TYPE_STOP   = 1;

    public interface OnStopClickListener  { void onClick(StopItem item); }

    /** Chiamato quando una riga fermata diventa visibile per la prima volta → carica il tempo */
    public interface OnStopVisibleListener { void onVisible(StopItem item); }

    public static class StopItem {
        public boolean isHeader;
        public String  headerText;
        public String  customerCode;
        public String  description;
        public String  waitMessage;   // null = non ancora caricato
        public String  lineCode;         // linea cercata (per filtrare waitMessage)
        public boolean loading = false; // true = richiesta in corso
        public boolean loaded  = false; // true = dato già ricevuto (non ricaricare)
    }

    private final List<StopItem>    items;
    private final OnStopClickListener   clickListener;
    private       OnStopVisibleListener visibleListener;

    public LineStopsAdapter(List<StopItem> items, OnStopClickListener clickListener) {
        this.items         = items;
        this.clickListener = clickListener;
    }

    public void setOnStopVisibleListener(OnStopVisibleListener l) { this.visibleListener = l; }

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
            ((HeaderVH) holder).tvHeader.setText(item.headerText);
            return;
        }

        StopVH vh = (StopVH) holder;
        vh.tvDesc.setText(item.description);
        vh.tvCode.setText(item.customerCode);

        if (item.loading) {
            // Richiesta in volo: mostra spinner
            vh.progress.setVisibility(View.VISIBLE);
            vh.tvWait.setVisibility(View.GONE);
        } else if (item.loaded) {
            // Dato ricevuto: mostra risultato
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
            // Non ancora richiesto: nascondi e triggera il caricamento
            vh.progress.setVisibility(View.GONE);
            vh.tvWait.setVisibility(View.INVISIBLE);
            if (visibleListener != null && !item.loading && !item.loaded) {
                item.loading = true;          // subito: evita doppie richieste
                visibleListener.onVisible(item);
            }
        }

        vh.itemView.setOnClickListener(v -> clickListener.onClick(item));
    }

    @Override public int getItemCount() { return items.size(); }

    // ── ViewHolder ───────────────────────────────────────────────────────

    static class HeaderVH extends RecyclerView.ViewHolder {
        TextView tvHeader;
        HeaderVH(View v) { super(v); tvHeader = v.findViewById(R.id.tv_header); }
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