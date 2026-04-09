package com.atm.tracker.model;

import com.google.gson.annotations.SerializedName;
import java.util.List;

public class NearestResponse {
    @SerializedName("JourneyPatterns") public List<JourneyPattern> journeyPatterns;

    public static class JourneyPattern {
        @SerializedName("Id")        public String id;
        @SerializedName("Code")      public String code;
        @SerializedName("Direction") public String direction;
        @SerializedName("Line")      public LineInfo line;
        @SerializedName("Links")     public List<Link> links;

        public String getStopsHref() {
            if (links == null) return null;
            for (Link l : links) {
                if ("stops".equals(l.rel)) return l.href;
            }
            return null;
        }
    }

    public static class LineInfo {
        @SerializedName("LineCode")        public String lineCode;
        @SerializedName("LineDescription") public String lineDescription;
        @SerializedName("TransportMode")   public int transportMode;
    }

    public static class Link {
        @SerializedName("Rel")  public String rel;
        @SerializedName("Href") public String href;
    }
}
