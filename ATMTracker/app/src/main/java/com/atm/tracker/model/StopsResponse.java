package com.atm.tracker.model;

import com.google.gson.annotations.SerializedName;
import java.util.List;

public class StopsResponse {

    @SerializedName("Stops")
    public List<Stop> stops;

    @SerializedName("stops")
    public List<Stop> stopsLower;

    public List<Stop> getStops() {
        if (stops != null && !stops.isEmpty()) return stops;
        if (stopsLower != null && !stopsLower.isEmpty()) return stopsLower;
        return null;
    }

    public static class Stop {
        // L'API ATM restituisce "Code", non "CustomerCode"
        @SerializedName("Code")
        public String customerCode;

        @SerializedName("Description")
        public String description;

        @SerializedName("Address")
        public String address;

        @SerializedName("Location")
        public Location location;

        public static class Location {
            @SerializedName("X") public double x; // lng
            @SerializedName("Y") public double y; // lat
        }
    }
}
