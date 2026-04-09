package com.atm.tracker.fragment;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
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
    private ProgressBar      progressLine;
    private TextView         tvLineStatus;
    private LineStopsAdapter adapter;
    private final List<LineStopsAdapter.StopItem> items = new ArrayList<>();

    /** Linea attualmente cercata — usata per filtrare il waitMessage corretto */
    private String currentLineCode = "";

    /** Tiene traccia delle chiamate attive per poterle cancellare quando il tab è nascosto */
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
        progressLine = view.findViewById(R.id.progress_line);
        tvLineStatus = view.findViewById(R.id.tv_line_status);
        RecyclerView recycler  = view.findViewById(R.id.recycler_line);
        Button       btnSearch = view.findViewById(R.id.btn_search_line);

        adapter = new LineStopsAdapter(items, item ->
                StopDetailBottomSheet.showSafely(getChildFragmentManager(), "stop_detail", item.customerCode, item.description));

        // Lazy load: il tempo viene chiesto SOLO quando la riga diventa visibile
        adapter.setOnStopVisibleListener(this::loadWaitMessage);
        currentLineCode = "";

        recycler.setLayoutManager(new LinearLayoutManager(requireContext()));
        recycler.setAdapter(adapter);

        lineInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                searchLine(lineInput.getText().toString().trim().toUpperCase());
                return true;
            }
            return false;
        });
        btnSearch.setOnClickListener(v ->
                searchLine(lineInput.getText().toString().trim().toUpperCase()));
    }

    /** Quando il tab viene nascosto, cancella TUTTE le chiamate in volo
     *  così libera il pool HTTP per la mappa */
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

    // ── Ricerca linea ────────────────────────────────────────────────────

    private void searchLine(String lineCode) {
        if (lineCode.isEmpty()) return;
        cancelAllCalls(); // cancella eventuali richieste precedenti
        currentLineCode = lineCode; // salva per filtrare il waitMessage
        progressLine.setVisibility(View.VISIBLE);
        tvLineStatus.setText("Carico fermate linea " + lineCode + "…");
        items.clear();
        adapter.notifyDataSetChanged();

        AtomicInteger pending = new AtomicInteger(2);
        loadDirection(lineCode, "0", pending);
        loadDirection(lineCode, "1", pending);
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
                    String dirLabel = "0".equals(dir) ? "→ Direzione A" : "← Direzione B";
                    List<LineStopsAdapter.StopItem> dirItems = new ArrayList<>();

                    LineStopsAdapter.StopItem header = new LineStopsAdapter.StopItem();
                    header.isHeader   = true;
                    header.headerText = "Linea " + lineCode + " — " + dirLabel
                            + " (" + body.getStops().size() + " fermate)";
                    dirItems.add(header);

                    for (StopsResponse.Stop s : body.getStops()) {
                        LineStopsAdapter.StopItem item = new LineStopsAdapter.StopItem();
                        item.customerCode = s.customerCode;
                        item.description  = s.description != null ? s.description
                                : (s.address != null ? s.address : s.customerCode);
                        item.lineCode     = lineCode; // per filtrare il waitMessage corretto
                        dirItems.add(item);
                    }

                    requireActivity().runOnUiThread(() -> {
                        items.addAll(dirItems);
                        adapter.notifyDataSetChanged();
                    });
                }
                if (pending.decrementAndGet() == 0 && isAdded()) {
                    requireActivity().runOnUiThread(() -> {
                        progressLine.setVisibility(View.GONE);
                        tvLineStatus.setText(items.isEmpty()
                                ? "Nessuna fermata trovata per questa linea" : "");
                    });
                }
            }

            @Override
            public void onFailure(@NonNull Call<StopsResponse> c, @NonNull Throwable t) {
                synchronized (activeCalls) { activeCalls.remove(c); }
                if (c.isCanceled()) return; // cancellata intenzionalmente, ignora
                if (pending.decrementAndGet() == 0 && isAdded()) {
                    requireActivity().runOnUiThread(() -> {
                        progressLine.setVisibility(View.GONE);
                        tvLineStatus.setText("Errore: " + t.getMessage());
                    });
                }
            }
        });
    }

    /** Carica il tempo per UNA fermata visibile. Massimo ~12 chiamate simultanee. */
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
                            // Cerca SOLO la linea specifica cercata dall'utente
                            if (!formatLineCode(le.line.lineCode).equalsIgnoreCase(target)) continue;
                            // Trovata la linea giusta: prende il waitMessage (anche se null)
                            item.waitMessage = le.waitMessage; // null = nessun GPS → mostra "—"
                            break;
                        }
                        // NESSUN fallback: non mostrare mai i minuti di un'altra linea
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
    /** Converte il lineCode dell'API nel formato visibile: "-3" → "M3", "NM3" → "NM3" */
    private String formatLineCode(String code) {
        if (code == null) return "";
        try {
            int n = Integer.parseInt(code.trim());
            if (n < 0) return "M" + Math.abs(n);
        } catch (NumberFormatException ignored) {}
        return code.trim();
    }
}