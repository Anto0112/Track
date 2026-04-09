package com.atm.tracker.fragment;

import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;

import com.atm.tracker.R;

import org.json.JSONArray;
import org.json.JSONObject;
import org.osmdroid.util.GeoPoint;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public class SearchFragment extends Fragment {

    public interface OnSearchResultListener {
        void onPlaceFound(GeoPoint pos, String displayName);
    }

    private static final String BASE = "https://nominatim.openstreetmap.org/search";
    private static final Handler UI = new Handler(Looper.getMainLooper());
    private static final ExecutorService POOL = Executors.newFixedThreadPool(2);

    private OnSearchResultListener listener;
    private EditText     searchInput;
    private ProgressBar  progressSearch;
    private TextView     tvStatus;
    private ListView     suggestionsList;

    private final List<String>   suggestionNames  = new ArrayList<>();
    private final List<GeoPoint> suggestionPoints = new ArrayList<>();
    private ArrayAdapter<String> suggestionsAdapter;

    private final Handler  debounceHandler = new Handler(Looper.getMainLooper());
    private       Runnable debounceRunnable;
    private       Future<?> lastSuggestFuture;
    private volatile boolean suggestCancelled = false;

    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);
        if (context instanceof OnSearchResultListener)
            listener = (OnSearchResultListener) context;
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             ViewGroup container, Bundle savedState) {
        return inflater.inflate(R.layout.fragment_search, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, Bundle savedState) {
        super.onViewCreated(view, savedState);
        searchInput     = view.findViewById(R.id.search_input);
        progressSearch  = view.findViewById(R.id.progress_search);
        tvStatus        = view.findViewById(R.id.tv_status);
        suggestionsList = view.findViewById(R.id.suggestions_list);
        Button btn      = view.findViewById(R.id.btn_search);

        suggestionsAdapter = new ArrayAdapter<>(requireContext(),
                android.R.layout.simple_list_item_1, suggestionNames);
        suggestionsList.setAdapter(suggestionsAdapter);

        suggestionsList.setOnItemClickListener((parent, v, pos, id) -> {
            GeoPoint point = suggestionPoints.get(pos);
            String   name  = suggestionNames.get(pos);
            searchInput.setText(name);
            hideSuggestions();
            hideKeyboard();
            navigateTo(point, name);
        });

        searchInput.setOnEditorActionListener((v, id, e) -> {
            if (id == EditorInfo.IME_ACTION_SEARCH) {
                doSearch(searchInput.getText().toString().trim());
                return true;
            }
            return false;
        });
        btn.setOnClickListener(v -> doSearch(searchInput.getText().toString().trim()));

        searchInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {}
            @Override public void onTextChanged(CharSequence s, int x, int y, int z) {
                String q = s.toString().trim();
                if (debounceRunnable != null) debounceHandler.removeCallbacks(debounceRunnable);
                if (q.length() < 3) { hideSuggestions(); cancelSuggest(); return; }
                debounceRunnable = () -> fetchSuggestions(q);
                debounceHandler.postDelayed(debounceRunnable, 400);
            }
        });
    }

    // ── Nominatim via HttpURLConnection (nessun OkHttp/interceptor) ──────

    private String nominatimGet(String url) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(8000);
        conn.setReadTimeout(12000);
        conn.setRequestProperty("User-Agent", "ATMTracker/1.0");
        conn.setRequestProperty("Accept", "application/json");
        try {
            int code = conn.getResponseCode();
            if (code != 200) return "[]";
            BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            return sb.toString();
        } finally {
            conn.disconnect();
        }
    }

    // ── Autocomplete ──────────────────────────────────────────────────────

    // Bounding box Milano città e provincia
    private static final String BOX_CITY     = "9.03,45.39,9.28,45.53"; // Milano comune
    private static final String BOX_PROVINCE = "8.70,45.25,9.50,45.70"; // Provincia MI


    private void fetchSuggestions(String query) {
        if (!isAdded()) return;
        cancelSuggest();
        suggestCancelled = false;
        String enc;
        try { enc = URLEncoder.encode(query, "UTF-8"); } catch (Exception e) { return; }

        // Due URL: città (bounded) e provincia (preferenza)
        String urlCity = BASE + "?q=" + enc
                + "%2C+Milano"          // aggiunge ", Milano" encodato
                + "&format=json&limit=15&dedupe=1&addressdetails=1&countrycodes=it"
                + "&viewbox=" + BOX_CITY + "&bounded=1";
        String urlProv = BASE + "?q=" + enc
                + "%2C+Milano"
                + "&format=json&limit=20&dedupe=1&addressdetails=1&countrycodes=it"
                + "&viewbox=" + BOX_PROVINCE;

        lastSuggestFuture = POOL.submit(() -> {
            try {
                // Prima richiesta: Milano città (bounded → risponde prima e più precisa)
                String bodyCity = nominatimGet(urlCity);
                if (suggestCancelled || !isAdded()) return;
                List<String>   names  = parseResults(bodyCity, 10);
                List<GeoPoint> points = parsePoints(bodyCity, 10);

                // Mostra subito i risultati città
                if (!names.isEmpty()) {
                    UI.post(() -> {
                        if (suggestCancelled || !isAdded()) return;
                        suggestionNames.clear(); suggestionNames.addAll(names);
                        suggestionPoints.clear(); suggestionPoints.addAll(points);
                        suggestionsAdapter.notifyDataSetChanged();
                        suggestionsList.setVisibility(View.VISIBLE);
                    });
                }

                // Seconda richiesta: provincia (aggiunge risultati mancanti)
                String bodyProv = nominatimGet(urlProv);
                if (suggestCancelled || !isAdded()) return;
                List<String>   namesP  = parseResults(bodyProv, 20);
                List<GeoPoint> pointsP = parsePoints(bodyProv, 20);

                UI.post(() -> {
                    if (suggestCancelled || !isAdded()) return;
                    // Unisci: città prima, poi provincia senza duplicati
                    Set<String> seen = new LinkedHashSet<>(names);
                    List<String>   merged  = new ArrayList<>(names);
                    List<GeoPoint> mergedP = new ArrayList<>(points);
                    for (int i = 0; i < namesP.size() && merged.size() < 15; i++) {
                        if (!seen.contains(namesP.get(i))) {
                            merged.add(namesP.get(i));
                            mergedP.add(pointsP.get(i));
                        }
                    }
                    suggestionNames.clear(); suggestionNames.addAll(merged);
                    suggestionPoints.clear(); suggestionPoints.addAll(mergedP);
                    suggestionsAdapter.notifyDataSetChanged();
                    suggestionsList.setVisibility(merged.isEmpty() ? View.GONE : View.VISIBLE);
                });
            } catch (Exception ignored) {}
        });
    }

    private List<String> parseResults(String body, int max) {
        List<String> list = new ArrayList<>();
        try {
            String t = body.trim();
            if (!t.startsWith("[")) return list;
            JSONArray arr = new JSONArray(t);
            Set<String> seen = new LinkedHashSet<>();
            for (int i = 0; i < arr.length() && list.size() < max; i++) {
                JSONObject obj = arr.getJSONObject(i);
                String cls = obj.optString("class", "");
                if (cls.equals("boundary") || cls.equals("waterway")) continue;
                String label = buildLabel(obj, obj.optJSONObject("address"));
                if (!label.isEmpty() && seen.add(label)) list.add(label);
            }
        } catch (Exception ignored) {}
        return list;
    }

    private List<GeoPoint> parsePoints(String body, int max) {
        List<GeoPoint> list = new ArrayList<>();
        try {
            String t = body.trim();
            if (!t.startsWith("[")) return list;
            JSONArray arr = new JSONArray(t);
            int count = 0;
            for (int i = 0; i < arr.length() && count < max; i++) {
                JSONObject obj = arr.getJSONObject(i);
                String cls = obj.optString("class", "");
                if (cls.equals("boundary") || cls.equals("waterway")) continue;
                String label = buildLabel(obj, obj.optJSONObject("address"));
                if (!label.isEmpty()) { list.add(new GeoPoint(obj.getDouble("lat"), obj.getDouble("lon"))); count++; }
            }
        } catch (Exception ignored) {}
        return list;
    }

    // ── Ricerca principale ────────────────────────────────────────────────

    private void doSearch(String query) {
        if (query.isEmpty()) return;
        cancelSuggest();
        hideSuggestions();
        progressSearch.setVisibility(View.VISIBLE);
        tvStatus.setText("Ricerca in corso…");

        String q = query.toLowerCase().contains("milan") ? query : query + ", Milano";
        String enc;
        try { enc = URLEncoder.encode(q, "UTF-8"); }
        catch (Exception e) { progressSearch.setVisibility(View.GONE); return; }
        String url = BASE + "?q=" + enc + "&format=json&limit=1&countrycodes=it";

        POOL.submit(() -> {
            try {
                String body = nominatimGet(url);
                if (!isAdded()) return;
                String t = body.trim();
                UI.post(() -> {
                    progressSearch.setVisibility(View.GONE);
                    try {
                        if (!t.startsWith("[")) { tvStatus.setText("Indirizzo non trovato"); return; }
                        JSONArray arr = new JSONArray(t);
                        if (arr.length() == 0) { tvStatus.setText("Indirizzo non trovato"); return; }
                        JSONObject first = arr.getJSONObject(0);
                        GeoPoint pos = new GeoPoint(first.getDouble("lat"), first.getDouble("lon"));
                        navigateTo(pos, first.optString("display_name", query));
                    } catch (Exception e) {
                        tvStatus.setText("Indirizzo non trovato");
                    }
                });
            } catch (Exception e) {
                UI.post(() -> {
                    if (!isAdded()) return;
                    progressSearch.setVisibility(View.GONE);
                    tvStatus.setText("Errore connessione");
                });
            }
        });
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private String buildLabel(JSONObject obj, JSONObject addr) {
        try {
            if (addr != null) {
                String road = addr.optString("road", "");
                String city = addr.optString("city",
                        addr.optString("town",
                                addr.optString("municipality",
                                        addr.optString("village", ""))));
                if (!road.isEmpty())
                    return road + (city.isEmpty() ? "" : ", " + city);
            }
        } catch (Exception ignored) {}
        String full = obj.optString("display_name", "");
        return full.contains(",") ? full.substring(0, full.indexOf(",")).trim() : full;
    }

    private void navigateTo(GeoPoint pos, String name) {
        tvStatus.setText("✓ " + name);
        if (listener != null) listener.onPlaceFound(pos, name);
    }

    private void cancelSuggest() {
        suggestCancelled = true;
        if (lastSuggestFuture != null) lastSuggestFuture.cancel(true);
    }

    private void hideSuggestions() {
        suggestionNames.clear();
        suggestionPoints.clear();
        if (suggestionsAdapter != null) suggestionsAdapter.notifyDataSetChanged();
        if (suggestionsList != null) suggestionsList.setVisibility(View.GONE);
    }

    private void hideKeyboard() {
        if (getView() == null) return;
        InputMethodManager imm = (InputMethodManager)
                requireContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        imm.hideSoftInputFromWindow(searchInput.getWindowToken(), 0);
    }

    @Override public void onDestroyView() {
        debounceHandler.removeCallbacksAndMessages(null);
        cancelSuggest();
        super.onDestroyView();
    }
}