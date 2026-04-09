package com.atm.tracker.api;

import com.atm.tracker.model.NearestResponse;
import com.atm.tracker.model.StopDetail;
import com.atm.tracker.model.StopsResponse;

import okhttp3.ResponseBody;
import retrofit2.Call;
import retrofit2.http.GET;
import retrofit2.http.Path;
import retrofit2.http.Query;
import retrofit2.http.Url;

public interface AtmApiService {

    @GET("tpl/journeyPatterns/nearest")
    Call<NearestResponse> getNearestPatterns(
            @Query("radius") int radius,
            @Query("Point.Y") double lat,
            @Query("Point.X") double lng
    );

    @GET
    Call<StopsResponse> getPatternStops(@Url String url);

    @GET("geodata/pois/stops/{customerCode}")
    Call<StopDetail> getStopDetail(@Path("customerCode") String customerCode);

    /** Orari programmati per una fermata/linea/direzione. Risposta JSON grezza. */
    @GET
    Call<ResponseBody> getTimetable(@Url String url);
}
