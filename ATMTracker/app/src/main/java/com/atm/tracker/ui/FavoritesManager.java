package com.atm.tracker.ui;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Gestisce la persistenza delle fermate preferite tramite SharedPreferences.
 * Ogni preferita è salvata come JSON: {"code":"12345","name":"Nome Fermata"}
 */
public class FavoritesManager {

    private static final String PREFS_NAME = "atm_favorites";
    private static final String KEY_LIST   = "favorites_list";

    public static class FavoriteStop {
        public final String code;
        public final String name;
        public FavoriteStop(String code, String name) {
            this.code = code;
            this.name = name;
        }
    }

    // ── Lettura ──────────────────────────────────────────────────────────

    public static List<FavoriteStop> getAll(Context ctx) {
        List<FavoriteStop> list = new ArrayList<>();
        String json = prefs(ctx).getString(KEY_LIST, "[]");
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.getJSONObject(i);
                list.add(new FavoriteStop(obj.getString("code"), obj.getString("name")));
            }
        } catch (Exception ignored) {}
        return list;
    }

    public static boolean isFavorite(Context ctx, String code) {
        for (FavoriteStop f : getAll(ctx))
            if (f.code.equals(code)) return true;
        return false;
    }

    // ── Scrittura ────────────────────────────────────────────────────────

    public static void add(Context ctx, String code, String name) {
        if (isFavorite(ctx, code)) return;
        List<FavoriteStop> list = getAll(ctx);
        list.add(0, new FavoriteStop(code, name)); // aggiunge in cima
        save(ctx, list);
    }

    public static void remove(Context ctx, String code) {
        List<FavoriteStop> list = getAll(ctx);
        list.removeIf(f -> f.code.equals(code));
        save(ctx, list);
    }

    public static void toggle(Context ctx, String code, String name) {
        if (isFavorite(ctx, code)) remove(ctx, code);
        else add(ctx, code, name);
    }

    // ── Interno ──────────────────────────────────────────────────────────

    private static void save(Context ctx, List<FavoriteStop> list) {
        try {
            JSONArray arr = new JSONArray();
            for (FavoriteStop f : list) {
                JSONObject obj = new JSONObject();
                obj.put("code", f.code);
                obj.put("name", f.name);
                arr.put(obj);
            }
            prefs(ctx).edit().putString(KEY_LIST, arr.toString()).apply();
        } catch (Exception ignored) {}
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }
}