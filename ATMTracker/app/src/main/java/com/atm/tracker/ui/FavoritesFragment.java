package com.atm.tracker.ui;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.atm.tracker.R;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;

import java.util.List;

public class FavoritesFragment extends BottomSheetDialogFragment {

    public static FavoritesFragment newInstance() {
        return new FavoritesFragment();
    }

    @Nullable @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedState) {
        return inflater.inflate(R.layout.fragment_favorites, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedState) {
        super.onViewCreated(view, savedState);

        RecyclerView recycler   = view.findViewById(R.id.recycler_favorites);
        LinearLayout layoutEmpty = view.findViewById(R.id.layout_empty);

        List<FavoritesManager.FavoriteStop> favorites =
                FavoritesManager.getAll(requireContext());

        if (favorites.isEmpty()) {
            layoutEmpty.setVisibility(View.VISIBLE);
            recycler.setVisibility(View.GONE);
            return;
        }

        recycler.setLayoutManager(new LinearLayoutManager(requireContext()));
        recycler.setAdapter(new FavAdapter(favorites));
    }

    // ── Adapter ──────────────────────────────────────────────────────────

    private class FavAdapter extends RecyclerView.Adapter<FavAdapter.VH> {

        private final List<FavoritesManager.FavoriteStop> items;

        FavAdapter(List<FavoritesManager.FavoriteStop> items) { this.items = items; }

        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_favorite, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int position) {
            FavoritesManager.FavoriteStop stop = items.get(position);
            h.tvName.setText(stop.name);
            h.tvCode.setText("Fermata " + stop.code);

            // Click sulla riga → apre StopDetailBottomSheet
            h.itemView.setOnClickListener(v -> {
                dismiss();
                StopDetailBottomSheet.showSafely(getParentFragmentManager(), "stop_detail_fav", stop.code, stop.name);
            });

            // Click sul cuore → rimuove dai preferiti
            h.btnRemove.setOnClickListener(v -> {
                FavoritesManager.remove(requireContext(), stop.code);
                int idx = h.getAdapterPosition();
                items.remove(idx);
                notifyItemRemoved(idx);
                // Se lista vuota, mostra stato empty
                if (items.isEmpty() && getView() != null) {
                    getView().findViewById(R.id.layout_empty).setVisibility(View.VISIBLE);
                    getView().findViewById(R.id.recycler_favorites).setVisibility(View.GONE);
                }
            });
        }

        @Override public int getItemCount() { return items.size(); }

        class VH extends RecyclerView.ViewHolder {
            TextView    tvName, tvCode;
            ImageButton btnRemove;
            VH(View v) {
                super(v);
                tvName    = v.findViewById(R.id.tv_fav_name);
                tvCode    = v.findViewById(R.id.tv_fav_code);
                btnRemove = v.findViewById(R.id.btn_remove_fav);
            }
        }
    }
}