package com.atm.tracker.api;

import com.atm.tracker.model.StopDetail;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Cache in memoria per i dati di getStopDetail.
 * MapFragment pre-carica i dettagli delle fermate visibili in background;
 * StopDetailBottomSheet li trova già pronti e si apre istantaneamente.
 */
public class StopDetailCache {

    private static final int MAX_SIZE = 60; // max fermate in cache

    // LinkedHashMap con remove-eldest per tenere solo le ultime MAX_SIZE entrate
    private static final Map<String, StopDetail> cache =
            new LinkedHashMap<String, StopDetail>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, StopDetail> eldest) {
                    return size() > MAX_SIZE;
                }
            };

    public static synchronized void put(String code, StopDetail detail) {
        cache.put(code, detail);
    }

    public static synchronized StopDetail get(String code) {
        return cache.get(code);
    }

    public static synchronized boolean has(String code) {
        return cache.containsKey(code);
    }

    public static synchronized void remove(String code) {
        cache.remove(code);
    }

    public static synchronized void clear() {
        cache.clear();
    }
}