package com.atm.tracker.ui;

import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.FragmentManager;
import androidx.annotation.Nullable;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.atm.tracker.R;
import com.atm.tracker.api.ApiClient;
import com.atm.tracker.api.StopDetailCache;
import com.atm.tracker.model.StopDetail;
import com.atm.tracker.model.TimetableResponse;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;

import java.io.IOException;

import okhttp3.ResponseBody;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

public class StopDetailBottomSheet extends BottomSheetDialogFragment {

    private static final String ARG_CODE      = "customer_code";
    private static final String ARG_NAME      = "stop_name";
    private static final long   AUTO_REFRESH_MS = 30_000;
    private static final String TAG           = "StopDetailBS";

    private TextView     tvStopName, tvStopCode, tvStopAddress, tvNoLines, tvLastUpdate;
    private ProgressBar  progressStop;
    private LinearLayout linesContainer;
    private SwipeRefreshLayout swipeRefresh;
    private ImageButton  btnFavorite;

    /** Nome risolto dal server (può differire dall'ARG_NAME iniziale) */
    private String resolvedName;

    private final Handler  handler             = new Handler(Looper.getMainLooper());
    private final Runnable autoRefreshRunnable = this::reload;

    // ── Factory ──────────────────────────────────────────────────────────

    public static StopDetailBottomSheet newInstance(String customerCode, String name) {
        StopDetailBottomSheet f = new StopDetailBottomSheet();
        Bundle args = new Bundle();
        args.putString(ARG_CODE, customerCode);
        args.putString(ARG_NAME, name);
        f.setArguments(args);
        return f;
    }

    /**
     * Mostra il bottom sheet in modo sicuro: se ne esiste già uno aperto con lo stesso tag
     * lo chiude prima di aprire il nuovo, evitando che si sovrappongano più sheet.
     */
    public static void showSafely(FragmentManager fm, String tag,
                                  String customerCode, String name) {
        // Chiudi qualsiasi sheet già aperto con questo tag
        androidx.fragment.app.Fragment existing = fm.findFragmentByTag(tag);
        if (existing instanceof DialogFragment) {
            ((DialogFragment) existing).dismissAllowingStateLoss();
        }
        // Breve commit sincrono per assicurarsi che il dismiss sia processato
        fm.executePendingTransactions();
        // Apri il nuovo
        newInstance(customerCode, name).show(fm, tag);
    }

    // ── Lifecycle ────────────────────────────────────────────────────────

    @Nullable @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedState) {
        return inflater.inflate(R.layout.bottom_sheet_stop, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedState) {
        super.onViewCreated(view, savedState);

        tvStopName    = view.findViewById(R.id.tv_stop_name);
        tvStopCode    = view.findViewById(R.id.tv_stop_code);
        tvStopAddress = view.findViewById(R.id.tv_stop_address);
        tvNoLines     = view.findViewById(R.id.tv_no_lines);
        tvLastUpdate  = view.findViewById(R.id.tv_last_update);
        progressStop  = view.findViewById(R.id.progress_stop);
        linesContainer = view.findViewById(R.id.lines_container);
        swipeRefresh  = view.findViewById(R.id.swipe_refresh);
        btnFavorite   = view.findViewById(R.id.btn_favorite);

        swipeRefresh.setColorSchemeColors(Color.parseColor("#0266ad"));
        swipeRefresh.setOnRefreshListener(this::reload);
        view.findViewById(R.id.btn_refresh).setOnClickListener(v -> reload());

        String code = code();
        String name = name();
        resolvedName = name;

        tvStopName.setText(name);
        tvStopCode.setText("Fermata " + code);

        // ── Cuoricino ────────────────────────────────────────────────────
        updateHeartIcon(code);
        btnFavorite.setOnClickListener(v -> {
            FavoritesManager.toggle(requireContext(), code, resolvedName);
            updateHeartIcon(code);
        });

        loadStop(code, name);
        scheduleAutoRefresh();
    }

    // ── Cuore ────────────────────────────────────────────────────────────

    private void updateHeartIcon(String code) {
        if (btnFavorite == null) return;
        boolean fav = FavoritesManager.isFavorite(requireContext(), code);
        btnFavorite.setImageResource(fav
                ? R.drawable.ic_heart_filled
                : R.drawable.ic_heart_outline);
    }

    // ── Carica dati fermata ──────────────────────────────────────────────

    private void loadStop(String code, String fallbackName) {
        linesContainer.removeAllViews();
        tvNoLines.setVisibility(View.GONE);

        // ── Controlla la cache prima: risposta istantanea se già pre-caricato ──
        StopDetail cached = StopDetailCache.get(code);
        if (cached != null) {
            progressStop.setVisibility(View.GONE);
            renderStop(cached, code, fallbackName);
            scheduleAutoRefresh(); // continua a refreshare in background
            return;
        }

        // Cache miss: carica dalla rete normalmente
        progressStop.setVisibility(View.VISIBLE);
        ApiClient.get().getStopDetail(code).enqueue(new Callback<StopDetail>() {
            @Override
            public void onResponse(@NonNull Call<StopDetail> call,
                                   @NonNull Response<StopDetail> response) {
                if (!isAdded()) return;
                progressStop.setVisibility(View.GONE);
                swipeRefresh.setRefreshing(false);

                StopDetail body = response.body();
                if (body == null) { tvNoLines.setVisibility(View.VISIBLE); return; }

                StopDetailCache.put(code, body); // salva in cache per i prossimi accessi
                renderStop(body, code, fallbackName);
            }

            @Override
            public void onFailure(@NonNull Call<StopDetail> call, @NonNull Throwable t) {
                if (!isAdded()) return;
                progressStop.setVisibility(View.GONE);
                swipeRefresh.setRefreshing(false);
                tvNoLines.setText("Errore rete — scorri per riprovare");
                tvNoLines.setVisibility(View.VISIBLE);
            }
        });
    }

    /** Renderizza i dati fermata nell'UI (usato sia da cache che da rete) */
    private void renderStop(StopDetail body, String code, String fallbackName) {
        if (!isAdded()) return;
        linesContainer.removeAllViews();

        if (body.description != null && !body.description.isEmpty())
            resolvedName = body.description;

        tvStopName.setText(resolvedName);
        tvStopCode.setText("Fermata " + code + "  ·  cod. " + body.code);

        if (body.address != null && !body.address.isEmpty()) {
            tvStopAddress.setText(body.address);
            tvStopAddress.setVisibility(View.VISIBLE);
        }

        java.text.SimpleDateFormat sdf =
                new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.ITALY);
        tvLastUpdate.setText("Agg. " + sdf.format(new java.util.Date()));
        tvLastUpdate.setVisibility(View.VISIBLE);

        if (body.lines == null || body.lines.isEmpty()) {
            tvNoLines.setVisibility(View.VISIBLE);
            return;
        }
        for (StopDetail.LineEntry entry : body.lines)
            addLineCard(entry);
    }

    private void reload() {
        // Rimuove dalla cache: forza una chiamata di rete fresca
        StopDetailCache.remove(code());
        loadStop(code(), name());
        scheduleAutoRefresh();
    }

    private void scheduleAutoRefresh() {
        handler.removeCallbacks(autoRefreshRunnable);
        handler.postDelayed(autoRefreshRunnable, AUTO_REFRESH_MS);
    }

    @Override
    public void onDestroyView() {
        handler.removeCallbacks(autoRefreshRunnable);
        super.onDestroyView();
    }

    // ── Card singola linea ────────────────────────────────────────────────

    private void addLineCard(StopDetail.LineEntry entry) {
        View card = LayoutInflater.from(requireContext())
                .inflate(R.layout.item_line_card, linesContainer, false);

        TextView tvCode     = card.findViewById(R.id.tv_line_code);
        TextView tvDesc     = card.findViewById(R.id.tv_line_desc);
        TextView tvDir      = card.findViewById(R.id.tv_line_direction);
        TextView tvWait     = card.findViewById(R.id.tv_wait);
        TextView tvSchedule = card.findViewById(R.id.tv_schedule);
        LinearLayout alerts = card.findViewById(R.id.layout_alerts);

        String rawCode  = entry.line != null ? entry.line.lineCode : "?";
        String lineCode = formatLineCode(rawCode);
        tvCode.setText(lineCode);
        tvCode.getBackground().setTint(badgeColor(lineCode));
        tvDesc.setText(entry.line != null ? entry.line.lineDescription : "");
        tvDir.setText("→ " + capolinea(
                entry.line != null ? entry.line.lineDescription : "", entry.direction));

        if (isGpsData(entry.waitMessage)) {
            tvWait.setText(entry.waitMessage);
            tvWait.setTextColor(Color.parseColor("#00C853"));
            tvSchedule.setVisibility(View.GONE);
        } else {
            tvWait.setText("—");
            tvWait.setTextColor(Color.BLACK);
            tvSchedule.setVisibility(View.VISIBLE);
            tvSchedule.setText("Caricamento orari…");
            tvSchedule.setTextColor(Color.parseColor("#666666"));
            String timetableUrl = resolveTimetableUrl(entry);
            if (timetableUrl != null) fetchTimetable(timetableUrl, tvSchedule);
            else tvSchedule.setText("Orari non disponibili");
        }

        if (entry.trafficBulletins != null && !entry.trafficBulletins.isEmpty()) {
            alerts.setVisibility(View.VISIBLE);
            for (StopDetail.TrafficBulletin b : entry.trafficBulletins) {
                TextView tv = new TextView(requireContext());
                tv.setText("⚠ " + b.title);
                tv.setTextColor(Color.parseColor("#E8001D"));
                tv.setTextSize(11f);
                tv.setPadding(0, 6, 0, 0);
                alerts.addView(tv);
            }
        }

        // ── Tap sulla card → apre la linea nella sezione "Linea", evidenziando
        //    la fermata corrente, senza doverla cercare a mano ───────────────
        final String lineForOpen = lineCode;
        card.setClickable(true);
        card.setFocusable(true);
        card.setOnClickListener(v -> {
            androidx.fragment.app.FragmentActivity act = getActivity();
            if (act instanceof com.atm.tracker.MainActivity) {
                ((com.atm.tracker.MainActivity) act).openLine(lineForOpen, code());
            }
            dismissAllowingStateLoss();
        });

        linesContainer.addView(card);
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private boolean isGpsData(String msg) {
        return msg != null && !msg.trim().isEmpty();
    }

    private String resolveTimetableUrl(StopDetail.LineEntry entry) {
        String href = entry.getTimetableHref();
        if (href != null && !href.isEmpty()) return ApiClient.BASE_URL + href;
        if (entry.line == null) return null;
        return ApiClient.BASE_URL + "tpl/stops/TODO/timetable/line/"
                + entry.line.lineCode + "/dir/" + (entry.direction != null ? entry.direction : "0");
    }

    private void fetchTimetable(String url, TextView tvSchedule) {
        Log.i(TAG, "Fetching timetable: " + url);
        ApiClient.get().getTimetable(url).enqueue(new Callback<ResponseBody>() {
            @Override
            public void onResponse(@NonNull Call<ResponseBody> call,
                                   @NonNull Response<ResponseBody> response) {
                if (!isAdded()) return;
                try {
                    String json = response.body() != null ? response.body().string() : "";
                    TimetableResponse tr = TimetableResponse.parse(json, 4);
                    requireActivity().runOnUiThread(() -> {
                        if (!tr.nextTimes.isEmpty()) {
                            tvSchedule.setText(String.join("  •  ", tr.nextTimes));
                            tvSchedule.setTextColor(Color.BLACK);
                        } else {
                            tvSchedule.setText("Orari non disponibili");
                            tvSchedule.setTextColor(Color.parseColor("#888888"));
                        }
                        tvSchedule.setVisibility(View.VISIBLE);
                    });
                } catch (IOException e) {
                    showNoSchedule(tvSchedule);
                }
            }
            @Override
            public void onFailure(@NonNull Call<ResponseBody> call, @NonNull Throwable t) {
                if (!isAdded()) return;
                showNoSchedule(tvSchedule);
            }
        });
    }

    private void showNoSchedule(TextView tv) {
        requireActivity().runOnUiThread(() -> {
            tv.setText("Orari non disponibili");
            tv.setTextColor(Color.parseColor("#888888"));
            tv.setVisibility(View.VISIBLE);
        });
    }

    private String formatLineCode(String code) {
        if (code == null) return "?";
        try {
            int n = Integer.parseInt(code.trim());
            if (n < 0) return "M" + Math.abs(n);
        } catch (NumberFormatException ignored) {}
        return code;
    }

    private int badgeColor(String displayCode) {
        if (displayCode == null) return Color.parseColor("#0266ad");
        switch (displayCode) {
            case "M1": return Color.parseColor("#E8001D");
            case "M2": return Color.parseColor("#008B00");
            case "M3": return Color.parseColor("#F5A800");
            case "M4": return Color.parseColor("#1565C0");
            case "M5": return Color.parseColor("#7B1FA2");
            default:   return Color.parseColor("#0266ad");
        }
    }

    private String capolinea(String desc, String direction) {
        String[] p = desc.split(" - ", 2);
        if (p.length < 2) return desc;
        return p[1].trim();
    }

    private String code() {
        return getArguments() != null ? getArguments().getString(ARG_CODE, "") : "";
    }

    private String name() {
        return getArguments() != null ? getArguments().getString(ARG_NAME, "") : "";
    }
}