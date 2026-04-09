package com.atm.tracker.fragment;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.location.Location;
import android.location.LocationManager;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ProgressBar;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.content.res.AppCompatResources;
import androidx.core.app.ActivityCompat;
import androidx.fragment.app.Fragment;

import com.atm.tracker.R;
import com.atm.tracker.api.ApiClient;
import com.atm.tracker.api.StopDetailCache;
import com.atm.tracker.model.NearestResponse;
import com.atm.tracker.model.StopDetail;
import com.atm.tracker.model.StopsResponse;
import com.atm.tracker.ui.FavoritesFragment;
import com.atm.tracker.ui.OsmTrustAllTileSource;
import com.atm.tracker.ui.StopDetailBottomSheet;

import org.osmdroid.config.Configuration;
import org.osmdroid.events.MapEventsReceiver;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.MapEventsOverlay;
import org.osmdroid.views.overlay.Marker;
import org.osmdroid.views.overlay.Polygon;
import org.osmdroid.views.overlay.Polyline;
import org.osmdroid.views.overlay.gestures.RotationGestureOverlay;
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider;
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

public class MapFragment extends Fragment {

    private static final String   TAG      = "MapFragment";
    private static final int      RADIUS_M = 200;
    private static final GeoPoint MILAN    = new GeoPoint(45.4654, 9.1866);

    private MapView              mapView;
    private ProgressBar          progressBar;
    private BitmapDrawable       stopIcon;
    private MyLocationNewOverlay myLocationOverlay;

    // ── Overlay attivi ───────────────────────────────────────────────────
    private final Map<String, Marker>   stopMarkers = new HashMap<>();
    private final List<Polyline>        routeLines  = new ArrayList<>();
    private Polygon radiusCircle;

    // Ogni nuova ricerca incrementa questo ID; le callback con ID vecchio vengono scartate
    private volatile int searchId = 0;

    // ── Cache dati già scaricati (evita API call ripetute) ───────────────
    // patternId → GeoPoint ordinati (percorso completo)
    private final Map<String, List<GeoPoint>>          patternRouteCache = new HashMap<>();
    // patternId → lista fermate complete (per ripiazzare marker senza rete)
    private final Map<String, List<StopsResponse.Stop>> patternStopsCache = new HashMap<>();
    // customerCode → lista di patternId che servono quella fermata
    private final Map<String, List<String>>   stopPatternIds    = new HashMap<>();
    // patternId → lineCode (per il colore)
    private final Map<String, String>         patternLineCode   = new HashMap<>();

    // ── Lifecycle ────────────────────────────────────────────────────────

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             ViewGroup container, Bundle savedState) {
        ApiClient.getOkHttpClient();
        Configuration.getInstance().load(requireContext(),
                PreferenceManager.getDefaultSharedPreferences(requireContext()));
        Configuration.getInstance().setUserAgentValue("ATMTracker/1.0");
        return inflater.inflate(R.layout.fragment_map, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, Bundle savedState) {
        super.onViewCreated(view, savedState);
        progressBar = view.findViewById(R.id.progress_bar);
        mapView     = view.findViewById(R.id.map);
        stopIcon    = vectorToBitmap(R.drawable.ic_stop_marker, 32, 44);

        setupMap();

        view.findViewById(R.id.fab_locate).setOnClickListener(v -> locateMe());
        view.findViewById(R.id.fab_favorites).setOnClickListener(v ->
                showFavoritesSafely());

        locateMe();
    }

    // ── Conversione vector → Bitmap (fix osmdroid) ───────────────────────

    private BitmapDrawable vectorToBitmap(int drawableRes, int wDp, int hDp) {
        try {
            float d = requireContext().getResources().getDisplayMetrics().density;
            int w = Math.round(wDp * d), h = Math.round(hDp * d);
            Drawable dr = AppCompatResources.getDrawable(requireContext(), drawableRes);
            if (dr == null) return null;
            Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            dr.setBounds(0, 0, w, h);
            dr.draw(new Canvas(bmp));
            return new BitmapDrawable(getResources(), bmp);
        } catch (Exception e) {
            Log.e(TAG, "vectorToBitmap: " + e.getMessage());
            return null;
        }
    }

    // ── Setup mappa ──────────────────────────────────────────────────────


    /** Crea un bitmap con cerchio blu (bordo bianco) per la posizione corrente */
    private Bitmap createBlueDotBitmap() {
        float density = requireContext().getResources().getDisplayMetrics().density;
        int size   = Math.round(20 * density);  // 20dp totali
        float r    = size / 2f;
        float border = 2.5f * density;

        Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c   = new Canvas(bmp);
        Paint  p   = new Paint(Paint.ANTI_ALIAS_FLAG);

        // Alone bianco esterno
        p.setColor(Color.WHITE);
        c.drawCircle(r, r, r, p);

        // Nucleo blu ATM
        p.setColor(Color.parseColor("#0266ad"));
        c.drawCircle(r, r, r - border, p);

        return bmp;
    }

    private void setupMap() {
        mapView.setTileSource(new OsmTrustAllTileSource());
        mapView.setMultiTouchControls(true);
        mapView.getController().setZoom(14.0);
        mapView.getController().setCenter(MILAN);

        RotationGestureOverlay rot = new RotationGestureOverlay(mapView);
        rot.setEnabled(true);
        mapView.getOverlays().add(rot);

        myLocationOverlay = new MyLocationNewOverlay(
                new GpsMyLocationProvider(requireContext()), mapView);
        myLocationOverlay.enableMyLocation();
        myLocationOverlay.disableFollowLocation();
        // Rimpiazza l'omino con un semplice pallino blu
        Bitmap blueDot = createBlueDotBitmap();
        myLocationOverlay.setPersonIcon(blueDot);
        myLocationOverlay.setDirectionIcon(blueDot);
        // Ancora al centro esatto del bitmap (default è 0.5/1.0 = piede)
        myLocationOverlay.setPersonAnchor(0.5f, 0.5f);
        myLocationOverlay.setDirectionAnchor(0.5f, 0.5f);
        mapView.getOverlays().add(myLocationOverlay);

        mapView.getOverlays().add(new MapEventsOverlay(new MapEventsReceiver() {
            @Override public boolean singleTapConfirmedHelper(GeoPoint p) {
                clearAll();
                drawRadius(p);
                loadNearbyStops(p);
                return true;
            }
            @Override public boolean longPressHelper(GeoPoint p) { return false; }
        }));
    }

    // ── Posizione corrente ────────────────────────────────────────────────

    private void locateMe() {
        if (ActivityCompat.checkSelfPermission(requireContext(),
                Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, 100);
            return;
        }
        GeoPoint live = myLocationOverlay.getMyLocation();
        if (live != null) { centerAndLoad(live); return; }

        LocationManager lm = (LocationManager)
                requireContext().getSystemService(Context.LOCATION_SERVICE);
        Location best = null;
        for (String p : new String[]{LocationManager.GPS_PROVIDER,
                LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER}) {
            try {
                Location loc = lm.getLastKnownLocation(p);
                if (loc != null && (best == null || loc.getTime() > best.getTime())) best = loc;
            } catch (SecurityException ignored) {}
        }
        if (best != null) { centerAndLoad(new GeoPoint(best.getLatitude(), best.getLongitude())); return; }

        Toast.makeText(getContext(), "Acquisizione GPS…", Toast.LENGTH_SHORT).show();
        myLocationOverlay.runOnFirstFix(() -> {
            if (!isAdded()) return;
            GeoPoint pos = myLocationOverlay.getMyLocation();
            if (pos != null) requireActivity().runOnUiThread(() -> centerAndLoad(pos));
        });
    }

    private void centerAndLoad(GeoPoint pos) {
        mapView.getController().animateTo(pos);
        mapView.getController().setZoom(16.0);
        clearAll();
        drawRadius(pos);
        loadNearbyStops(pos);
    }

    public void navigateTo(GeoPoint pos) {
        centerAndLoad(pos);
    }

    // ── Carica fermate e POPOLA CACHE ────────────────────────────────────

    public void loadNearbyStops(GeoPoint center) {
        progressBar.setVisibility(View.VISIBLE);
        final int myId = searchId; // cattura l'ID di questa ricerca
        ApiClient.get().getNearestPatterns(RADIUS_M, center.getLatitude(), center.getLongitude())
                .enqueue(new Callback<NearestResponse>() {
                    @Override public void onResponse(@NonNull Call<NearestResponse> c,
                                                     @NonNull Response<NearestResponse> r) {
                        if (!isAdded() || myId != searchId) return; // risposta stale: ignora
                        NearestResponse body = r.body();
                        if (body == null || body.journeyPatterns == null) {
                            progressBar.setVisibility(View.GONE); return;
                        }
                        fetchStopsAndCache(body.journeyPatterns, center, myId);
                    }
                    @Override public void onFailure(@NonNull Call<NearestResponse> c,
                                                    @NonNull Throwable t) {
                        if (!isAdded()) return;
                        progressBar.setVisibility(View.GONE);
                        Toast.makeText(getContext(), "Errore: " + t.getMessage(),
                                Toast.LENGTH_SHORT).show();
                    }
                });
    }

    /**
     * Per ogni pattern: scarica le fermate, piazza i marker E popola la cache
     * patternRouteCache / stopPatternIds / patternLineCode.
     * Così quando l'utente clicca una fermata il percorso è già in memoria.
     */
    private void fetchStopsAndCache(List<NearestResponse.JourneyPattern> patterns,
                                    GeoPoint center, int myId) {
        Set<String> seen   = new HashSet<>();
        List<NearestResponse.JourneyPattern> unique = new ArrayList<>();
        for (NearestResponse.JourneyPattern p : patterns)
            if (seen.add(p.id)) unique.add(p);

        if (unique.isEmpty()) { progressBar.setVisibility(View.GONE); return; }

        // Separa pattern già in cache da quelli da scaricare
        List<NearestResponse.JourneyPattern> toFetch = new ArrayList<>();
        List<NearestResponse.JourneyPattern> cached  = new ArrayList<>();
        for (NearestResponse.JourneyPattern pattern : unique) {
            if (pattern.line != null && pattern.line.lineCode != null)
                patternLineCode.put(pattern.id, pattern.line.lineCode);
            if (patternStopsCache.containsKey(pattern.id)) cached.add(pattern);
            else toFetch.add(pattern);
        }

        // Pattern in cache → elabora tutto in UN SOLO runOnUiThread
        if (!cached.isEmpty()) {
            requireActivity().runOnUiThread(() -> {
                if (myId != searchId) return;
                Set<String> addedFromCache = new HashSet<>();
                for (NearestResponse.JourneyPattern pattern : cached) {
                    List<StopsResponse.Stop> stops = patternStopsCache.get(pattern.id);
                    if (stops == null) continue;
                    for (StopsResponse.Stop s : stops) {
                        if (s.customerCode == null || s.location == null) continue;
                        stopPatternIds.computeIfAbsent(s.customerCode, k -> new ArrayList<>())
                                .add(pattern.id);
                        if (distanceMeters(center.getLatitude(), center.getLongitude(),
                                s.location.y, s.location.x) > RADIUS_M) continue;
                        if (!addedFromCache.add(s.customerCode)) continue;
                        placeMarker(s);
                    }
                }
                mapView.invalidate();
            });
        }

        // Se non ci sono pattern da scaricare, chiudi subito
        if (toFetch.isEmpty()) {
            requireActivity().runOnUiThread(() -> {
                progressBar.setVisibility(View.GONE);
                for (String code : stopMarkers.keySet()) prefetchStopDetail(code);
            });
            return;
        }

        AtomicInteger pending = new AtomicInteger(toFetch.size());
        Set<String> addedMarkers = new HashSet<>();

        for (NearestResponse.JourneyPattern pattern : toFetch) {

            ApiClient.get().getPatternStops(ApiClient.stopsUrl(pattern.id))
                    .enqueue(new Callback<StopsResponse>() {
                        @Override public void onResponse(@NonNull Call<StopsResponse> c,
                                                         @NonNull Response<StopsResponse> r) {
                            if (!isAdded() || myId != searchId) return; // ricerca superata: ignora
                            StopsResponse body = r.body();
                            List<StopsResponse.Stop> stops = body != null ? body.getStops() : null;
                            if (stops != null) {
                                // ── Costruisce il percorso completo per questo pattern ────
                                List<GeoPoint> routePts = new ArrayList<>(stops.size());
                                for (StopsResponse.Stop s : stops)
                                    if (s.location != null)
                                        routePts.add(new GeoPoint(s.location.y, s.location.x));
                                patternRouteCache.put(pattern.id, routePts);  // CACHE ROUTE
                                patternStopsCache.put(pattern.id, stops);   // CACHE STOPS (evita re-download)

                                requireActivity().runOnUiThread(() -> {
                                    if (myId != searchId) return; // ricontrolliamo sul main thread
                                    for (StopsResponse.Stop s : stops) {
                                        if (s.customerCode == null || s.location == null) continue;

                                        // Cache: quale pattern serve questa fermata
                                        stopPatternIds
                                                .computeIfAbsent(s.customerCode,
                                                        k -> new ArrayList<>())
                                                .add(pattern.id);

                                        // Piazza marker solo se dentro il raggio
                                        if (distanceMeters(center.getLatitude(),
                                                center.getLongitude(),
                                                s.location.y, s.location.x) > RADIUS_M) continue;
                                        if (!addedMarkers.add(s.customerCode)) continue;
                                        placeMarker(s);
                                    }
                                    mapView.invalidate();
                                });
                            }
                            if (pending.decrementAndGet() == 0 && isAdded())
                                requireActivity().runOnUiThread(() -> {
                                    progressBar.setVisibility(View.GONE);
                                    // Prefetch DOPO che tutti i marker sono pronti:
                                    // non compete con le richieste di pattern
                                    for (String code : stopMarkers.keySet())
                                        prefetchStopDetail(code);
                                });
                        }
                        @Override public void onFailure(@NonNull Call<StopsResponse> c,
                                                        @NonNull Throwable t) {
                            if (pending.decrementAndGet() == 0 && isAdded())
                                requireActivity().runOnUiThread(() -> {
                                    progressBar.setVisibility(View.GONE);
                                    // Prefetch DOPO che tutti i marker sono pronti:
                                    // non compete con le richieste di pattern
                                    for (String code : stopMarkers.keySet())
                                        prefetchStopDetail(code);
                                });
                        }
                    });
        }
    }

    private void showFavoritesSafely() {
        androidx.fragment.app.Fragment existing =
                getChildFragmentManager().findFragmentByTag("favorites");
        if (existing instanceof androidx.fragment.app.DialogFragment)
            ((androidx.fragment.app.DialogFragment) existing).dismissAllowingStateLoss();
        getChildFragmentManager().executePendingTransactions();
        FavoritesFragment.newInstance().show(getChildFragmentManager(), "favorites");
    }

    // ── Prefetch dettagli fermata ─────────────────────────────────────────────

    private void prefetchStopDetail(String code) {
        if (code == null || StopDetailCache.has(code)) return;
        ApiClient.get().getStopDetail(code).enqueue(new Callback<StopDetail>() {
            @Override
            public void onResponse(@NonNull Call<StopDetail> call,
                                   @NonNull Response<StopDetail> response) {
                StopDetail body = response.body();
                if (body != null) StopDetailCache.put(code, body);
            }
            @Override public void onFailure(@NonNull Call<StopDetail> call,
                                            @NonNull Throwable t) {}
        });
    }

    // ── Marker goccia ────────────────────────────────────────────────────

    private void placeMarker(StopsResponse.Stop stop) {
        if (!isAdded() || mapView == null) return;
        GeoPoint pos = new GeoPoint(stop.location.y, stop.location.x);
        String label = stop.description != null ? stop.description
                : (stop.address != null ? stop.address : stop.customerCode);

        Marker marker = new Marker(mapView);
        marker.setPosition(pos);
        marker.setTitle(label);
        marker.setSubDescription("Fermata " + stop.customerCode);
        marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM);
        if (stopIcon != null) marker.setIcon(stopIcon);

        final String code = stop.customerCode;
        marker.setOnMarkerClickListener((m, mv) -> {
            // 1. Apre subito il BottomSheet (solo getStopDetail, già veloce)
            StopDetailBottomSheet.showSafely(getChildFragmentManager(), "stop_detail", code, label);
            // 2. Disegna il percorso DALLA CACHE — nessuna API call extra
            drawRouteFromCache(code);
            return true;
        });

        mapView.getOverlays().add(marker);
        stopMarkers.put(stop.customerCode, marker);
    }

    // ── Tratta dalla cache (istantanea) ──────────────────────────────────

    /**
     * Disegna i percorsi delle linee che passano per questa fermata
     * usando i dati già in memoria. Zero chiamate di rete.
     */
    private void drawRouteFromCache(String customerCode) {
        clearRoutes();
        List<String> patterns = stopPatternIds.get(customerCode);
        if (patterns == null || patterns.isEmpty()) {
            // Fallback: se la fermata non era nel raggio originale, fetcha dall'API
            drawRouteFromApi(customerCode);
            return;
        }
        for (String patternId : patterns) {
            List<GeoPoint> pts = patternRouteCache.get(patternId);
            if (pts == null || pts.size() < 2) continue;
            String lineCode = patternLineCode.getOrDefault(patternId, "0");
            renderRoute(pts, routeColor(lineCode));
        }
    }

    /**
     * Fallback: chiama getStopDetail per ottenere i journeyPatternId,
     * poi carica i percorsi. Usato raramente (solo fermate fuori dal raggio).
     */
    private void drawRouteFromApi(String customerCode) {
        ApiClient.get().getStopDetail(customerCode)
                .enqueue(new Callback<StopDetail>() {
                    @Override public void onResponse(@NonNull Call<StopDetail> call,
                                                     @NonNull Response<StopDetail> response) {
                        if (!isAdded()) return;
                        StopDetail body = response.body();
                        if (body == null || body.lines == null) return;
                        Set<String> drawn = new HashSet<>();
                        for (StopDetail.LineEntry entry : body.lines) {
                            if (entry.journeyPatternId == null) continue;
                            if (!drawn.add(entry.journeyPatternId)) continue;
                            String lc = entry.line != null ? entry.line.lineCode : "0";
                            int color = routeColor(lc);
                            // Usa la cache se già disponibile
                            List<GeoPoint> cached = patternRouteCache.get(entry.journeyPatternId);
                            if (cached != null && cached.size() > 1) {
                                requireActivity().runOnUiThread(() -> renderRoute(cached, color));
                                continue;
                            }
                            ApiClient.get()
                                    .getPatternStops(ApiClient.stopsUrl(entry.journeyPatternId))
                                    .enqueue(new Callback<StopsResponse>() {
                                        @Override public void onResponse(
                                                @NonNull Call<StopsResponse> c,
                                                @NonNull Response<StopsResponse> r) {
                                            if (!isAdded()) return;
                                            StopsResponse b = r.body();
                                            List<StopsResponse.Stop> s =
                                                    b != null ? b.getStops() : null;
                                            if (s != null && s.size() > 1) {
                                                List<GeoPoint> pts = new ArrayList<>();
                                                for (StopsResponse.Stop st : s)
                                                    if (st.location != null)
                                                        pts.add(new GeoPoint(st.location.y, st.location.x));
                                                patternRouteCache.put(entry.journeyPatternId, pts);
                                                requireActivity().runOnUiThread(
                                                        () -> renderRoute(pts, color));
                                            }
                                        }
                                        @Override public void onFailure(
                                                @NonNull Call<StopsResponse> c,
                                                @NonNull Throwable t) {}
                                    });
                        }
                    }
                    @Override public void onFailure(@NonNull Call<StopDetail> call,
                                                    @NonNull Throwable t) {}
                });
    }

    private void renderRoute(List<GeoPoint> pts, int color) {
        if (!isAdded() || mapView == null || pts.size() < 2) return;
        Polyline poly = new Polyline();
        poly.setPoints(pts);
        poly.getOutlinePaint().setColor(color);
        poly.getOutlinePaint().setStrokeWidth(7f);
        poly.getOutlinePaint().setAlpha(200);
        mapView.getOverlays().add(1, poly);
        routeLines.add(poly);
        mapView.invalidate();
    }

    private int routeColor(String lineCode) {
        if (lineCode == null) return Color.parseColor("#0266ad");
        try {
            int n = Integer.parseInt(lineCode.trim());
            if (n < 0) switch (Math.abs(n)) {
                case 1: return Color.parseColor("#E8001D");
                case 2: return Color.parseColor("#008B00");
                case 3: return Color.parseColor("#F5A800");
                case 4: return Color.parseColor("#1565C0");
                case 5: return Color.parseColor("#7B1FA2");
            }
        } catch (NumberFormatException ignored) {}
        return Color.parseColor("#0266ad");
    }

    // ── Cerchio raggio BLU ───────────────────────────────────────────────

    public void drawRadius(GeoPoint center) {
        if (radiusCircle != null) mapView.getOverlays().remove(radiusCircle);
        radiusCircle = new Polygon();
        radiusCircle.setPoints(Polygon.pointsAsCircle(center, RADIUS_M));
        radiusCircle.getOutlinePaint().setColor(Color.parseColor("#0266ad"));
        radiusCircle.getOutlinePaint().setStrokeWidth(3f);
        radiusCircle.getFillPaint().setColor(Color.argb(20, 2, 102, 173));
        mapView.getOverlays().add(0, radiusCircle);
        mapView.invalidate();
    }

    // ── Clear ────────────────────────────────────────────────────────────

    private void clearRoutes() {
        for (Polyline p : routeLines) mapView.getOverlays().remove(p);
        routeLines.clear();
        if (mapView != null) mapView.invalidate();
    }

    private void clearAll() {
        searchId++; // invalida tutte le risposte API in volo
        StopDetailCache.clear();
        clearRoutes();
        for (Marker m : stopMarkers.values()) mapView.getOverlays().remove(m);
        stopMarkers.clear();
        if (radiusCircle != null) {
            mapView.getOverlays().remove(radiusCircle);
            radiusCircle = null;
        }
        // NON svuotiamo patternRouteCache e patternLineCode:
        // i percorsi delle linee sono stabili → tap successivi riusano i dati già scaricati
        // Solo stopPatternIds viene svuotato perché dipende dall'area attiva
        stopPatternIds.clear();
        mapView.invalidate();
    }

    // ── Haversine ────────────────────────────────────────────────────────

    private double distanceMeters(double lat1, double lon1, double lat2, double lon2) {
        double R    = 6371000;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a    = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    // ── Lifecycle ────────────────────────────────────────────────────────

    /** Con show/hide il fragment non viene distrutto: gestisce resume/pause tramite onHiddenChanged */
    @Override
    public void onHiddenChanged(boolean hidden) {
        super.onHiddenChanged(hidden);
        if (hidden) {
            if (mapView != null) mapView.onPause();
            if (myLocationOverlay != null) myLocationOverlay.disableMyLocation();
        } else {
            if (mapView != null) mapView.onResume();
            if (myLocationOverlay != null) myLocationOverlay.enableMyLocation();
        }
    }

    @Override public void onResume() {
        super.onResume();
        if (mapView != null) mapView.onResume();
        if (myLocationOverlay != null) myLocationOverlay.enableMyLocation();
    }

    @Override public void onPause() {
        super.onPause();
        if (mapView != null) mapView.onPause();
        if (myLocationOverlay != null) myLocationOverlay.disableMyLocation();
    }

    @Override public void onRequestPermissionsResult(int req, @NonNull String[] p,
                                                     @NonNull int[] r) {
        if (req == 100 && r.length > 0 && r[0] == PackageManager.PERMISSION_GRANTED) {
            if (myLocationOverlay != null) myLocationOverlay.enableMyLocation();
            locateMe();
        }
    }
}