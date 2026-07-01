package com.atm.tracker;

import android.os.Bundle;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.FragmentTransaction;

import com.atm.tracker.fragment.LineFragment;
import com.atm.tracker.fragment.MapFragment;
import com.atm.tracker.fragment.SearchFragment;
import com.google.android.material.bottomnavigation.BottomNavigationView;

import org.osmdroid.util.GeoPoint;

public class MainActivity extends AppCompatActivity
        implements SearchFragment.OnSearchResultListener {

    private static final String TAG_MAP    = "map";
    private static final String TAG_SEARCH = "search";
    private static final String TAG_LINE   = "line";

    private MapFragment    mapFragment;
    private SearchFragment searchFragment;
    private LineFragment   lineFragment;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        if (savedInstanceState == null) {
            mapFragment    = new MapFragment();
            searchFragment = new SearchFragment();
            lineFragment   = new LineFragment();

            getSupportFragmentManager().beginTransaction()
                    .add(R.id.container, lineFragment,   TAG_LINE).hide(lineFragment)
                    .add(R.id.container, searchFragment, TAG_SEARCH).hide(searchFragment)
                    .add(R.id.container, mapFragment,    TAG_MAP)
                    .commit();
        } else {
            mapFragment    = (MapFragment)    getSupportFragmentManager().findFragmentByTag(TAG_MAP);
            searchFragment = (SearchFragment) getSupportFragmentManager().findFragmentByTag(TAG_SEARCH);
            lineFragment   = (LineFragment)   getSupportFragmentManager().findFragmentByTag(TAG_LINE);
            if (mapFragment    == null) mapFragment    = new MapFragment();
            if (searchFragment == null) searchFragment = new SearchFragment();
            if (lineFragment   == null) lineFragment   = new LineFragment();
        }

        BottomNavigationView nav = findViewById(R.id.bottom_nav);
        nav.setSelectedItemId(R.id.nav_map);

        nav.setOnItemSelectedListener(item -> {
            int id = item.getItemId();
            FragmentTransaction ft = getSupportFragmentManager().beginTransaction();
            ft.hide(mapFragment).hide(searchFragment).hide(lineFragment);
            if      (id == R.id.nav_map)    ft.show(mapFragment);
            else if (id == R.id.nav_search) ft.show(searchFragment);
            else                            ft.show(lineFragment);
            ft.commit();
            // Abilita il back callback solo se NON siamo già sulla mappa
            backCallback.setEnabled(id != R.id.nav_map);
            return true;
        });

        // Gestione back gesture/tasto: se siamo su Cerca o Linea → torna alla Mappa
        backCallback = new OnBackPressedCallback(false /* inizialmente disabilitato */) {
            @Override
            public void handleOnBackPressed() {
                nav.setSelectedItemId(R.id.nav_map);
                // setEnabled(false) viene gestito dal listener sopra
            }
        };
        getOnBackPressedDispatcher().addCallback(this, backCallback);
    }

    private OnBackPressedCallback backCallback;

    @Override
    public void onPlaceFound(GeoPoint pos, String displayName) {
        if (mapFragment != null) mapFragment.navigateTo(pos);
        BottomNavigationView nav = findViewById(R.id.bottom_nav);
        nav.setSelectedItemId(R.id.nav_map);
    }

    /**
     * Apre la sezione "Linea" cercando direttamente la linea indicata, senza
     * obbligare l'utente a digitarla. Se {@code highlightStopCode} è valorizzato,
     * quella fermata viene evidenziata e portata in vista a caricamento ultimato.
     *
     * Chiamato dalle card linea dello StopDetailBottomSheet: tap su "12 → ..."
     * apre la linea 12 ed evidenzia la fermata di provenienza.
     */
    public void openLine(String lineCode, String highlightStopCode) {
        BottomNavigationView nav = findViewById(R.id.bottom_nav);
        nav.setSelectedItemId(R.id.nav_line);
        if (lineFragment != null) {
            lineFragment.openLine(lineCode, highlightStopCode);
        }
    }
}
