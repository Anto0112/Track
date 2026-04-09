package com.atm.tracker.ui;

import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase;
import org.osmdroid.util.MapTileIndex;

/**
 * Tile source che usa HTTPS diretto (no redirect HTTP→HTTPS).
 * Il trust-all SSL è applicato globalmente su HttpsURLConnection
 * in ApiClient.getOkHttpClient() — deve essere chiamato prima
 * di inizializzare qualsiasi MapView.
 */
public class OsmTrustAllTileSource extends OnlineTileSourceBase {

    private static final String[] TILE_URLS = {
            "https://a.tile.openstreetmap.org/",
            "https://b.tile.openstreetmap.org/",
            "https://c.tile.openstreetmap.org/"
    };

    public OsmTrustAllTileSource() {
        super("Mapnik-HTTPS", 0, 19, 256, ".png", TILE_URLS,
                "© OpenStreetMap contributors");
    }

    @Override
    public String getTileURLString(long pMapTileIndex) {
        int zoom = MapTileIndex.getZoom(pMapTileIndex);
        int x    = MapTileIndex.getX(pMapTileIndex);
        int y    = MapTileIndex.getY(pMapTileIndex);
        String base = TILE_URLS[(int)(Math.abs(x + y) % TILE_URLS.length)];
        return base + zoom + "/" + x + "/" + y + ".png";
    }
}
