package com.atm.tracker.api;

import android.annotation.SuppressLint;
import android.util.Log;

import com.atm.tracker.model.StopsResponse;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import okhttp3.ConnectionPool;
import okhttp3.Dispatcher;
import okhttp3.OkHttpClient;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;

public class ApiClient {

    public static final String BASE_URL =
            "https://giromilano.atm.it/proxy.tpportal/api/tpPortal/";
    private static final String TAG = "ApiClient";

    private static AtmApiService instance;
    private static OkHttpClient  okHttpClient;

    @SuppressLint("TrustAllX509TrustManager")
    public static X509TrustManager buildTrustAll() {
        return new X509TrustManager() {
            @Override public void checkClientTrusted(X509Certificate[] c, String a)
                    throws CertificateException {}
            @Override public void checkServerTrusted(X509Certificate[] c, String a)
                    throws CertificateException {}
            @Override public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        };
    }

    public static OkHttpClient getOkHttpClient() {
        if (okHttpClient != null) return okHttpClient;
        try {
            X509TrustManager trustAll = buildTrustAll();
            SSLContext sslCtx = SSLContext.getInstance("TLS");
            sslCtx.init(null, new TrustManager[]{trustAll}, null);

            HttpsURLConnection.setDefaultSSLSocketFactory(sslCtx.getSocketFactory());
            HttpsURLConnection.setDefaultHostnameVerifier((hostname, session) -> true);

            // ── Dispatcher: max 20 richieste parallele per host (default = 5) ──
            Dispatcher dispatcher = new Dispatcher();
            dispatcher.setMaxRequests(64);
            dispatcher.setMaxRequestsPerHost(20);

            // ── Pool: 10 connessioni tenute vive per 60s ──────────────────────
            ConnectionPool pool = new ConnectionPool(10, 60, TimeUnit.SECONDS);

            okHttpClient = new OkHttpClient.Builder()
                    .dispatcher(dispatcher)
                    .connectionPool(pool)
                    .sslSocketFactory(sslCtx.getSocketFactory(), trustAll)
                    .hostnameVerifier((host, session) -> true)
                    .connectTimeout(10, TimeUnit.SECONDS)
                    .readTimeout(15, TimeUnit.SECONDS)
                    // NESSUN logging: Level.BODY legge ogni risposta due volte → lento
                    .addInterceptor(chain -> chain.proceed(
                            chain.request().newBuilder()
                                    .addHeader("Accept", "application/json")
                                    .addHeader("Referer", "https://giromilano.atm.it/")
                                    .addHeader("User-Agent", "Mozilla/5.0 (Android 14; Mobile)")
                                    .build()))
                    .build();
        } catch (Exception e) {
            Log.e(TAG, "SSL setup error", e);
            okHttpClient = new OkHttpClient();
        }
        return okHttpClient;
    }

    public static AtmApiService get() {
        if (instance != null) return instance;

        Gson gson = new GsonBuilder()
                .registerTypeAdapter(StopsResponse.class, new StopsDeserializer())
                .create();

        instance = new Retrofit.Builder()
                .baseUrl(BASE_URL)
                .client(getOkHttpClient())
                .addConverterFactory(GsonConverterFactory.create(gson))
                .build()
                .create(AtmApiService.class);

        return instance;
    }

    public static String stopsUrl(String journeyPatternId) {
        return BASE_URL + "tpl/journeyPatterns/" + journeyPatternId + "/stops";
    }
}