package com.atm.tracker.model;

import android.util.Log;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

/**
 * Parser flessibile per la risposta dell'endpoint timetable ATM.
 * Formato atteso (TpPortal):
 * {
 *   "PdfUrl": "https://...",
 *   "Timetable": {
 *     "WorkingDays": [ { "Times": [ {"Time":"06:05"}, ... ] } ],
 *     "Saturday":    [ { "Times": [ ... ] } ],
 *     "Sunday":      [ { "Times": [ ... ] } ]
 *   }
 * }
 * Supporta anche array flat di stringhe e varianti con/senza wrapper.
 */
public class TimetableResponse {

    private static final String TAG = "TimetableResponse";

    public String pdfUrl;
    public List<String> nextTimes = new ArrayList<>();

    /** Parsa il JSON grezzo e riempie nextTimes con i prossimi max orari dopo adesso */
    public static TimetableResponse parse(String json, int maxTimes) {
        TimetableResponse r = new TimetableResponse();
        try {
            JSONObject root = new JSONObject(json);
            Log.d(TAG, "Timetable root keys: " + root.keys().toString());

            // PDF url
            r.pdfUrl = root.optString("PdfUrl", null);
            if (r.pdfUrl != null && r.pdfUrl.isEmpty()) r.pdfUrl = null;

            // Cerca il blocco timetable
            JSONObject tbl = root.optJSONObject("Timetable");
            if (tbl == null) tbl = root; // fallback: root stesso

            // Seleziona il slot del giorno corrente
            Calendar cal = Calendar.getInstance();
            int dow = cal.get(Calendar.DAY_OF_WEEK);
            JSONArray times = null;

            if (dow == Calendar.SATURDAY) {
                times = extractTimes(tbl, "Saturday", "Sabato");
            } else if (dow == Calendar.SUNDAY) {
                times = extractTimes(tbl, "Sunday", "Domenica");
            }
            if (times == null || times.length() == 0) {
                times = extractTimes(tbl, "WorkingDays", "Weekday", "Feriale",
                        "LunVen", "WeekDays");
            }
            if (times == null || times.length() == 0) {
                // Prova a cercare qualsiasi array di stringhe orario
                times = findAnyTimeArray(tbl);
            }

            if (times == null) {
                Log.w(TAG, "Nessun array di orari trovato nel JSON");
                return r;
            }

            // Ora corrente in minuti dalla mezzanotte
            int nowMin = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE);

            for (int i = 0; i < times.length() && r.nextTimes.size() < maxTimes; i++) {
                String t = parseTimeEntry(times.get(i));
                if (t == null) continue;
                int tMin = timeToMin(t);
                if (tMin >= nowMin) r.nextTimes.add(t);
            }
            // Se non bastano orari oggi (fine giornata), aggiungi dall'inizio
            if (r.nextTimes.size() < maxTimes) {
                for (int i = 0; i < times.length() && r.nextTimes.size() < maxTimes; i++) {
                    String t = parseTimeEntry(times.get(i));
                    if (t != null) r.nextTimes.add(t);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Errore parsing timetable: " + e.getMessage());
        }
        return r;
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static JSONArray extractTimes(JSONObject obj, String... keys) {
        for (String key : keys) {
            JSONArray arr = obj.optJSONArray(key);
            if (arr != null && arr.length() > 0) {
                // Potrebbe essere array di oggetti {Times:[...]} o array flat di stringhe
                try {
                    Object first = arr.get(0);
                    if (first instanceof JSONObject) {
                        // array di slot-orario, prendi il primo (o uniscili)
                        JSONArray merged = new JSONArray();
                        for (int i = 0; i < arr.length(); i++) {
                            JSONArray inner = arr.getJSONObject(i).optJSONArray("Times");
                            if (inner != null)
                                for (int j = 0; j < inner.length(); j++)
                                    merged.put(inner.get(j));
                        }
                        if (merged.length() > 0) return merged;
                    } else {
                        return arr; // array flat di stringhe
                    }
                } catch (Exception ignored) {}
            }
            // Prova come oggetto con chiave Times
            JSONObject slot = obj.optJSONObject(key);
            if (slot != null) {
                JSONArray inner = slot.optJSONArray("Times");
                if (inner != null && inner.length() > 0) return inner;
            }
        }
        return null;
    }

    private static JSONArray findAnyTimeArray(JSONObject obj) {
        try {
            java.util.Iterator<String> keys = obj.keys();
            while (keys.hasNext()) {
                String k = keys.next();
                JSONArray arr = obj.optJSONArray(k);
                if (arr != null && arr.length() > 0) {
                    // Verifica che sembri un array di orari
                    String first = parseTimeEntry(arr.get(0));
                    if (first != null) return arr;
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    private static String parseTimeEntry(Object entry) {
        try {
            if (entry instanceof String) {
                String s = (String) entry;
                if (s.matches("\\d{1,2}:\\d{2}")) return s;
                return null;
            }
            if (entry instanceof JSONObject) {
                JSONObject o = (JSONObject) entry;
                // "Time", "DepartureTime", "Ora", ecc.
                for (String f : new String[]{"Time","DepartureTime","Ora","Departure","Value"}) {
                    String v = o.optString(f, null);
                    if (v != null && v.matches("\\d{1,2}:\\d{2}.*")) {
                        // Tronca a HH:MM
                        return v.length() > 5 ? v.substring(0, 5) : v;
                    }
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    private static int timeToMin(String t) {
        try {
            String[] p = t.split(":");
            return Integer.parseInt(p[0]) * 60 + Integer.parseInt(p[1]);
        } catch (Exception e) { return 0; }
    }
}
