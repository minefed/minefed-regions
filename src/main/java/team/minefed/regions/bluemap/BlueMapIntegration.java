package team.minefed.regions.bluemap;

import com.flowpowered.math.vector.Vector2d;
import com.flowpowered.math.vector.Vector3d;
import de.bluecolored.bluemap.api.BlueMapAPI;
import de.bluecolored.bluemap.api.BlueMapMap;
import de.bluecolored.bluemap.api.markers.HtmlMarker;
import de.bluecolored.bluemap.api.markers.MarkerSet;
import de.bluecolored.bluemap.api.markers.ShapeMarker;
import de.bluecolored.bluemap.api.math.Color;
import de.bluecolored.bluemap.api.math.Shape;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import team.minefed.regions.model.Region;
import team.minefed.regions.model.Snapshot;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Optional integration: callers must guard loading this class with isModLoaded("bluemap"). */
public final class BlueMapIntegration {
    private static final Logger LOGGER = LoggerFactory.getLogger("minefed-regions-bluemap");
    private static final String SET_ID = "minefed-regions";
    private static final Object LOCK = new Object();
    private static final Consumer<BlueMapAPI> ENABLE = BlueMapIntegration::enabled;
    private static final Consumer<BlueMapAPI> DISABLE = BlueMapIntegration::disabled;
    private static Supplier<Snapshot> supplier;
    private static BlueMapAPI currentApi;
    private static boolean running;

    private BlueMapIntegration() {}

    /** Register during mod initialization, before BlueMap builds its web settings. */
    public static void start(Supplier<Snapshot> snapshots) {
        synchronized (LOCK) {
            if (running) return;
            supplier = snapshots;
            running = true;
        }
        // BlueMap callbacks hold the API monitor. Never register/unregister while holding LOCK.
        BlueMapAPI.onDisable(DISABLE);
        BlueMapAPI.onEnable(ENABLE);
    }

    public static void refresh(Snapshot snapshot) {
        synchronized (LOCK) {
            if (running && currentApi != null && snapshot != null) publish(currentApi, snapshot);
        }
    }

    public static void stop() {
        BlueMapAPI.unregisterListener(ENABLE);
        BlueMapAPI.unregisterListener(DISABLE);
        synchronized (LOCK) {
            running = false;
            if (currentApi != null) removeOwnedMarkers(currentApi);
            currentApi = null;
            supplier = null;
        }
    }

    private static void enabled(BlueMapAPI api) {
        synchronized (LOCK) {
            if (!running) return;
            currentApi = api;
            try {
                installAsset(api, "regions-map.js");
                installAsset(api, "regions-map.css");
                api.getWebApp().registerScript("minefed-regions/regions-map.js");
                api.getWebApp().registerStyle("minefed-regions/regions-map.css");
            } catch (IOException exception) {
                LOGGER.error("Could not install BlueMap region assets", exception);
                removeOwnedMarkers(api);
                currentApi = null;
                return;
            }
            Snapshot snapshot = supplier.get();
            if (snapshot != null) publish(api, snapshot);
        }
    }

    private static void disabled(BlueMapAPI api) {
        synchronized (LOCK) {
            removeOwnedMarkers(api);
            if (currentApi == api) currentApi = null;
        }
    }

    private static void installAsset(BlueMapAPI api, String name) throws IOException {
        Path directory = api.getWebApp().getWebRoot().resolve("minefed-regions");
        Files.createDirectories(directory);
        try (InputStream source = BlueMapIntegration.class.getResourceAsStream("/assets/minefed-regions/" + name)) {
            if (source == null) throw new IOException("Missing region web asset: " + name);
            Files.copy(source, directory.resolve(name), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void publish(BlueMapAPI api, Snapshot snapshot) {
        Set<String> mapIds = new HashSet<>();
        api.getWorld("minecraft:overworld").ifPresent(world -> {
            for (BlueMapMap map : world.getMaps()) {
                mapIds.add(map.getId());
                map.getMarkerSets().put(SET_ID, buildMarkers(snapshot));
            }
        });
        for (BlueMapMap map : api.getMaps()) {
            if (!mapIds.contains(map.getId())) map.getMarkerSets().remove(SET_ID);
        }
    }

    private static MarkerSet buildMarkers(Snapshot snapshot) {
        MarkerSet result = new MarkerSet("행정구역", true, false);
        for (Region region : snapshot.regions()) {
            if (region.geometry() == null) continue;
            int level = region.displayLevel() == null ? snapshot.depth(region) : region.displayLevel();
            String key = RegionMapData.markerPrefix(region.id(), region.parentId(), level);
            String name = RegionMapData.escapeHtml(region.name());
            String address = RegionMapData.escapeHtml(snapshot.address(region));
            double[][][][] polygons = region.geometry().coordinates();
            for (int p = 0; p < polygons.length; p++) {
                double[][][] polygon = polygons[p];
                double[] anchor = RegionMapData.labelPosition(polygon);
                Vector3d position = new Vector3d(anchor[0], 64, anchor[1]);
                // The web script draws each casing under its line and hides levels by camera distance.
                ShapeMarker casing = boundary(region.name(), position, polygon,
                        new Color(255, 255, 255, 0.78f), RegionMapData.casingWidth(level), address);
                result.put(key + "casing." + p, casing);
                ShapeMarker shape = boundary(region.name(), position, polygon,
                        level == 0 ? new Color(94, 53, 177, 1f) : new Color(126, 87, 194, 0.92f),
                        RegionMapData.lineWidth(level), address);
                result.put(key + "shape." + p, shape);
                HtmlMarker label = new HtmlMarker(region.name(), position, RegionMapData.labelHtml(name, address, level));
                label.setMaxDistance(Double.MAX_VALUE);
                label.setListed(false);
                result.put(key + "label." + p, label);
            }
        }
        return result;
    }

    private static ShapeMarker boundary(String label, Vector3d position, double[][][] polygon,
                                        Color lineColor, int lineWidth, String escapedAddress) {
        ShapeMarker shape = new ShapeMarker(label, position, shape(polygon[0]), 64);
        for (int r = 1; r < polygon.length; r++) shape.getHoles().add(shape(polygon[r]));
        shape.setLineColor(lineColor);
        shape.setLineWidth(lineWidth);
        shape.setFillColor(new Color(0, 0, 0, 0f));
        shape.setMaxDistance(Double.MAX_VALUE);
        shape.setDepthTestEnabled(false);
        shape.setDetail("<div class=\"minefed-region-detail\">" + escapedAddress + "</div>");
        shape.setListed(false);
        return shape;
    }

    private static Shape shape(double[][] closedRing) {
        double[][] points = RegionMapData.openRing(closedRing);
        Vector2d[] vectors = new Vector2d[points.length];
        for (int i = 0; i < points.length; i++) vectors[i] = new Vector2d(points[i][0], points[i][1]);
        return new Shape(vectors);
    }

    private static void removeOwnedMarkers(BlueMapAPI api) {
        for (BlueMapMap map : api.getMaps()) map.getMarkerSets().remove(SET_ID);
    }
}
