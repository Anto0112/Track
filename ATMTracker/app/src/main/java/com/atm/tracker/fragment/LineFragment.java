package com.atm.tracker.fragment;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.atm.tracker.R;
import com.atm.tracker.api.ApiClient;
import com.atm.tracker.model.StopDetail;
import com.atm.tracker.model.StopsResponse;
import com.atm.tracker.ui.LineStopsAdapter;
import com.atm.tracker.ui.StopDetailBottomSheet;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

public class LineFragment extends Fragment {

    private EditText         lineInput;
    private ImageButton      btnClearLine;
    private ProgressBar      progressLine;
    private TextView         tvLineStatus;
    private RecyclerView     recycler;
    private LineStopsAdapter adapter;
    private final List<LineStopsAdapter.StopItem> items = new ArrayList<>();

    // Fermata da evidenziare (provenienza dallo StopDetailBottomSheet); null = nessuna
    private String highlightStopCode = null;
    // Richiesta arrivata prima che la view fosse pronta (es. ricreazione fragment)
    private String pendingLineCode = null;
    private String pendingHighlight = null;

    // Dati per ciascuna direzione (solo le fermate, senza header)
    private final List<LineStopsAdapter.StopItem> dir0Stops = new ArrayList<>();
    private final List<LineStopsAdapter.StopItem> dir1Stops = new ArrayList<>();
    // Capolinea (primo e ultimo stop di ciascuna direzione)
    private String dir0First = "", dir0Last = "";
    private String dir1First = "", dir1Last = "";
    // Ordine attuale: true = dir0 in cima, false = dir1 in cima
    private boolean dir0OnTop = true;

    private String currentLineCode = "";
    private final List<Call<?>> activeCalls = new ArrayList<>();

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             ViewGroup container, Bundle savedState) {
        return inflater.inflate(R.layout.fragment_line, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, Bundle savedState) {
        super.onViewCreated(view, savedState);

        lineInput    = view.findViewById(R.id.line_input);
        btnClearLine = view.findViewById(R.id.btn_clear_line);
        progressLine = view.findViewById(R.id.progress_line);
        tvLineStatus = view.findViewById(R.id.tv_line_status);
        recycler     = view.findViewById(R.id.recycler_line);
        Button       btnSearch = view.findViewById(R.id.btn_search_line);

        adapter = new LineStopsAdapter(items, item ->
                StopDetailBottomSheet.showSafely(getChildFragmentManager(), "stop_detail",
                        item.customerCode, item.description));

        adapter.setOnStopVisibleListener(this::loadWaitMessage);

        // Swap: inverte l'ordine delle due direzioni
        adapter.setOnHeaderSwapListener(() -> {
            dir0OnTop = !dir0OnTop;
            rebuildItems();
            adapter.notifyDataSetChanged();
        });

        currentLineCode = "";

        recycler.setLayoutManager(new LinearLayoutManager(requireContext()));
        recycler.setAdapter(adapter);

        // ── Clear button ────────────────────────────────────────────────
        lineInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {}
            @Override public void onTextChanged(CharSequence s, int x, int y, int z) {
                boolean hasText = s.length() > 0;
                btnClearLine.setVisibility(hasText ? View.VISIBLE : View.GONE);
                if (s.length() == 0) clearResults();
            }
        });

        btnClearLine.setOnClickListener(v -> {
            lineInput.setText("");
            clearResults();
        });

        // ── Ricerca ─────────────────────────────────────────────────────
        lineInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                highlightStopCode = null; // ricerca manuale: nessuna evidenziazione
                searchLine(lineInput.getText().toString().trim().toUpperCase());
                return true;
            }
            return false;
        });
        btnSearch.setOnClickListener(v -> {
            highlightStopCode = null; // ricerca manuale: nessuna evidenziazione
            searchLine(lineInput.getText().toString().trim().toUpperCase());
        });

        // Se una richiesta openLine è arrivata prima che la view fosse pronta, applicala ora.
        if (pendingLineCode != null) {
            String code = pendingLineCode;
            String hl   = pendingHighlight;
            pendingLineCode = null;
            pendingHighlight = null;
            openLine(code, hl);
        }
    }

    @Override
    public void onHiddenChanged(boolean hidden) {
        super.onHiddenChanged(hidden);
        if (hidden) cancelAllCalls();
    }

    @Override
    public void onDestroyView() {
        cancelAllCalls();
        super.onDestroyView();
    }

    private void cancelAllCalls() {
        synchronized (activeCalls) {
            for (Call<?> call : activeCalls) {
                if (!call.isCanceled()) call.cancel();
            }
            activeCalls.clear();
        }
    }

    private void clearResults() {
        cancelAllCalls();
        items.clear();
        dir0Stops.clear();
        dir1Stops.clear();
        dir0First = dir0Last = "";
        dir1First = dir1Last = "";
        dir0OnTop = true;
        if (adapter != null) adapter.notifyDataSetChanged();
        if (tvLineStatus != null) tvLineStatus.setText("");
        if (progressLine != null) progressLine.setVisibility(View.GONE);
    }

    // ── Ricerca linea ────────────────────────────────────────────────────

    private void searchLine(String lineCode) {
        if (lineCode.isEmpty()) return;
        cancelAllCalls();
        currentLineCode = lineCode;
        dir0OnTop = true;
        dir0Stops.clear();
        dir1Stops.clear();
        dir0First = dir0Last = "";
        dir1First = dir1Last = "";
        progressLine.setVisibility(View.VISIBLE);
        tvLineStatus.setText("Carico fermate linea " + lineCode + "…");
        items.clear();
        adapter.notifyDataSetChanged();

        AtomicInteger pending = new AtomicInteger(2);
        loadDirection(lineCode, "0", pending);
        loadDirection(lineCode, "1", pending);
    }

    /**
     * Apre direttamente una linea (senza digitazione manuale) ed eventualmente
     * evidenzia la fermata di provenienza. Chiamato da MainActivity quando si
     * tocca una card linea nello StopDetailBottomSheet.
     */
    public void openLine(String lineCode, String highlightStop) {
        if (lineCode == null) return;
        String code = lineCode.trim().toUpperCase();
        if (code.isEmpty()) return;

        // View non ancora pronta (es. fragment ricreato): salva e applica in onViewCreated
        if (lineInput == null || adapter == null) {
            pendingLineCode  = code;
            pendingHighlight = highlightStop;
            return;
        }

        highlightStopCode = (highlightStop == null || highlightStop.isEmpty())
                ? null : highlightStop;

        lineInput.setText(code);
        lineInput.setSelection(code.length());
        searchLine(code);
    }

    /** Porta in vista la prima fermata evidenziata, se presente. */
    private void scrollToHighlight() {
        if (highlightStopCode == null || recycler == null) return;
        for (int i = 0; i < items.size(); i++) {
            LineStopsAdapter.StopItem it = items.get(i);
            if (!it.isHeader && it.highlight) {
                final int pos = i;
                recycler.post(() -> {
                    androidx.recyclerview.widget.RecyclerView.LayoutManager lm =
                            recycler.getLayoutManager();
                    if (lm instanceof LinearLayoutManager) {
                        // Offset così la fermata non finisce incollata in cima
                        ((LinearLayoutManager) lm).scrollToPositionWithOffset(pos, 80);
                    } else {
                        recycler.scrollToPosition(pos);
                    }
                });
                return;
            }
        }
    }

    private void loadDirection(String lineCode, String dir, AtomicInteger pending) {
        String url = ApiClient.stopsUrl(lineCode + "|" + dir);
        Call<StopsResponse> call = ApiClient.get().getPatternStops(url);
        synchronized (activeCalls) { activeCalls.add(call); }

        call.enqueue(new Callback<StopsResponse>() {
            @Override
            public void onResponse(@NonNull Call<StopsResponse> c,
                                   @NonNull Response<StopsResponse> response) {
                synchronized (activeCalls) { activeCalls.remove(c); }
                if (!isAdded()) return;
                StopsResponse body = response.body();
                if (body != null && body.getStops() != null && !body.getStops().isEmpty()) {
                    List<StopsResponse.Stop> stops = body.getStops();
                    List<LineStopsAdapter.StopItem> dirStops = new ArrayList<>();

                    for (StopsResponse.Stop s : stops) {
                        LineStopsAdapter.StopItem item = new LineStopsAdapter.StopItem();
                        item.customerCode = s.customerCode;
                        item.description  = s.description != null ? s.description
                                : (s.address != null ? s.address : s.customerCode);
                        item.lineCode     = lineCode;
                        item.highlight    = highlightStopCode != null
                                && highlightStopCode.equals(s.customerCode);
                        dirStops.add(item);
                    }

                    // Nomi capolinea: prima e ultima fermata
                    String firstStop = dirStops.get(0).description;
                    String lastStop  = dirStops.get(dirStops.size() - 1).description;

                    requireActivity().runOnUiThread(() -> {
                        if ("0".equals(dir)) {
                            dir0Stops.clear();
                            dir0Stops.addAll(dirStops);
                            dir0First = firstStop;
                            dir0Last  = lastStop;
                        } else {
                            dir1Stops.clear();
                            dir1Stops.addAll(dirStops);
                            dir1First = firstStop;
                            dir1Last  = lastStop;
                        }
                        rebuildItems();
                        adapter.notifyDataSetChanged();
                    });
                }
                if (pending.decrementAndGet() == 0 && isAdded()) {
                    requireActivity().runOnUiThread(() -> {
                        progressLine.setVisibility(View.GONE);
                        tvLineStatus.setText(items.isEmpty()
                                ? "Nessuna fermata trovata per questa linea" : "");
                        scrollToHighlight();
                    });
                }
            }

            @Override
            public void onFailure(@NonNull Call<StopsResponse> c, @NonNull Throwable t) {
                synchronized (activeCalls) { activeCalls.remove(c); }
                if (c.isCanceled()) return;
                if (pending.decrementAndGet() == 0 && isAdded()) {
                    requireActivity().runOnUiThread(() -> {
                        progressLine.setVisibility(View.GONE);
                        tvLineStatus.setText("Errore: " + t.getMessage());
                    });
                }
            }
        });
    }

    /**
     * Ricostruisce la lista items ordinando le due direzioni in base a dir0OnTop.
     * Il tasto ⇅ appare solo sul primo header (quello in cima).
     */
    private void rebuildItems() {
        items.clear();

        List<LineStopsAdapter.StopItem> first  = dir0OnTop ? dir0Stops : dir1Stops;
        List<LineStopsAdapter.StopItem> second = dir0OnTop ? dir1Stops : dir0Stops;
        String firstCapo  = dir0OnTop ? (dir0First + " → " + dir0Last) : (dir1First + " → " + dir1Last);
        String secondCapo = dir0OnTop ? (dir1First + " → " + dir1Last) : (dir0First + " → " + dir0Last);

        if (!first.isEmpty()) {
            LineStopsAdapter.StopItem header1 = new LineStopsAdapter.StopItem();
            header1.isHeader       = true;
            header1.headerShowSwap = !second.isEmpty(); // mostra swap solo se c'è anche l'altra dir
            header1.headerText     = currentLineCode + " — " + firstCapo
                    + " (" + first.size() + " fermate)";
            items.add(header1);
            items.addAll(first);
        }

        if (!second.isEmpty()) {
            LineStopsAdapter.StopItem header2 = new LineStopsAdapter.StopItem();
            header2.isHeader       = true;
            header2.headerShowSwap = false;
            header2.headerText     = currentLineCode + " — " + secondCapo
                    + " (" + second.size() + " fermate)";
            items.add(header2);
            items.addAll(second);
        }
    }

    // ── Caricamento tempo attesa ─────────────────────────────────────────

    private void loadWaitMessage(LineStopsAdapter.StopItem item) {
        if (item.customerCode == null) return;
        Call<StopDetail> call = ApiClient.get().getStopDetail(item.customerCode);
        synchronized (activeCalls) { activeCalls.add(call); }

        call.enqueue(new Callback<StopDetail>() {
            @Override
            public void onResponse(@NonNull Call<StopDetail> c,
                                   @NonNull Response<StopDetail> response) {
                synchronized (activeCalls) { activeCalls.remove(c); }
                if (!isAdded()) return;
                StopDetail body = response.body();
                requireActivity().runOnUiThread(() -> {
                    item.loading = false;
                    item.loaded  = true;
                    if (body != null && body.lines != null) {
                        String target = formatLineCode(item.lineCode);
                        for (StopDetail.LineEntry le : body.lines) {
                            if (le.line == null) continue;
                            if (!formatLineCode(le.line.lineCode).equalsIgnoreCase(target)) continue;
                            item.waitMessage = le.waitMessage;
                            break;
                        }
                    }
                    int idx = items.indexOf(item);
                    if (idx >= 0) adapter.notifyItemChanged(idx);
                });
            }

            @Override
            public void onFailure(@NonNull Call<StopDetail> c, @NonNull Throwable t) {
                synchronized (activeCalls) { activeCalls.remove(c); }
                if (c.isCanceled()) return;
                if (!isAdded()) return;
                requireActivity().runOnUiThread(() -> {
                    item.loading = false;
                    item.loaded  = true;
                    int idx = items.indexOf(item);
                    if (idx >= 0) adapter.notifyItemChanged(idx);
                });
            }
        });
    }

    private String formatLineCode(String code) {
        if (code == null) return "";
        try {
            int n = Integer.parseInt(code.trim());
            if (n < 0) return "M" + Math.abs(n);
        } catch (NumberFormatException ignored) {}
        return code.trim();
    }
}
