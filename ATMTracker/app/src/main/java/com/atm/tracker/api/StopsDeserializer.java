package com.atm.tracker.api;

import android.util.Log;

import com.atm.tracker.model.StopsResponse;
import com.google.gson.JsonArray;
import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

public class StopsDeserializer implements JsonDeserializer<StopsResponse> {
    private static final String TAG = "StopsDeserializer";

    @Override
    public StopsResponse deserialize(JsonElement json, Type typeOfT,
                                     JsonDeserializationContext ctx) throws JsonParseException {
        StopsResponse result = new StopsResponse();
        try {
            // Log la struttura del JSON per capire il formato reale
            if (json.isJsonObject()) {
                JsonObject obj = json.getAsJsonObject();
                Log.i(TAG, "JSON keys: " + obj.keySet().toString());
                // Cerca array con qualsiasi nome
                for (String key : obj.keySet()) {
                    if (obj.get(key).isJsonArray()) {
                        Log.i(TAG, "Array found at key: " + key +
                                " size=" + obj.getAsJsonArray(key).size());
                        result.stops = parseArray(obj.getAsJsonArray(key), ctx);
                        if (result.stops != null && !result.stops.isEmpty()) break;
                    }
                }
            } else if (json.isJsonArray()) {
                Log.i(TAG, "Direct array, size=" + json.getAsJsonArray().size());
                result.stops = parseArray(json.getAsJsonArray(), ctx);
            } else {
                Log.w(TAG, "JSON non è né oggetto né array: " + json.getClass().getSimpleName());
            }
        } catch (Exception e) {
            Log.e(TAG, "Errore parsing: " + e.getMessage(), e);
        }
        int count = result.stops != null ? result.stops.size() : 0;
        Log.i(TAG, "Totale stops deserializzati: " + count);
        return result;
    }

    private List<StopsResponse.Stop> parseArray(JsonArray arr,
                                                  JsonDeserializationContext ctx) {
        List<StopsResponse.Stop> list = new ArrayList<>();
        if (arr == null || arr.size() == 0) return list;

        // Logga il primo elemento per vedere la struttura
        Log.d(TAG, "Primo elemento: " + arr.get(0).toString().substring(0,
                Math.min(200, arr.get(0).toString().length())));

        for (JsonElement el : arr) {
            try {
                StopsResponse.Stop stop = ctx.deserialize(el, StopsResponse.Stop.class);
                if (stop != null) {
                    list.add(stop);
                    if (stop.customerCode == null)
                        Log.w(TAG, "Stop senza customerCode: " + el.toString().substring(0, Math.min(100, el.toString().length())));
                }
            } catch (Exception e) {
                Log.w(TAG, "Skip stop: " + e.getMessage());
            }
        }
        return list;
    }
}
