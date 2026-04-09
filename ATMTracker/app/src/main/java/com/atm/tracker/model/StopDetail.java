package com.atm.tracker.model;

import com.google.gson.annotations.SerializedName;
import java.util.List;

public class StopDetail {
    @SerializedName("Code")         public String code;
    @SerializedName("Description")  public String description;
    @SerializedName("CustomerCode") public String customerCode;
    @SerializedName("Address")      public String address;
    @SerializedName("Location")     public Location location;
    @SerializedName("Lines")        public List<LineEntry> lines;

    public static class Location {
        @SerializedName("X") public double x;
        @SerializedName("Y") public double y;
    }

    public static class LineEntry {
        @SerializedName("Line")             public LineInfo line;
        @SerializedName("Direction")        public String direction;
        @SerializedName("WaitMessage")      public String waitMessage;
        @SerializedName("JourneyPatternId") public String journeyPatternId;
        @SerializedName("BookletUrl")       public String bookletUrl;   // PDF orari
        @SerializedName("TrafficBulletins") public List<TrafficBulletin> trafficBulletins;
        @SerializedName("Links")            public List<EntryLink> links;

        /** Restituisce l'href del link timetable se presente */
        public String getTimetableHref() {
            if (links == null) return null;
            for (EntryLink l : links)
                if ("timetable".equals(l.rel)) return l.href;
            return null;
        }
    }

    public static class EntryLink {
        @SerializedName("Rel")  public String rel;
        @SerializedName("Href") public String href;
    }

    public static class LineInfo {
        @SerializedName("LineCode")        public String lineCode;
        @SerializedName("LineDescription") public String lineDescription;
        @SerializedName("TransportMode")   public int transportMode;
    }

    public static class TrafficBulletin {
        @SerializedName("Title") public String title;
        @SerializedName("Body")  public String body;
    }
}
