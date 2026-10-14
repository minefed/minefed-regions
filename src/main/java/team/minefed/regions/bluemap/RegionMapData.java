package team.minefed.regions.bluemap;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.TreeSet;

/** Pure geometry and encoding helpers shared by the map publisher and its tests. */
final class RegionMapData {
    private RegionMapData() {}

    static String escapeHtml(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }

    /** Top-level boundaries stay bold; subdivisions that appear when zooming in are thinner. */
    static int lineWidth(int level) {
        return level <= 0 ? 4 : 2;
    }

    /** Width of the light casing drawn under each boundary so it stays visible on any terrain. */
    static int casingWidth(int level) {
        return lineWidth(level) + (level <= 0 ? 5 : 3);
    }

    /** Label markup; callers pass already escaped text. Deeper levels use smaller label styles. */
    static String labelHtml(String escapedName, String escapedAddress, int level) {
        return "<div class=\"minefed-region-label minefed-region-level-" + Math.max(0, Math.min(level, 3)) + "\">"
                + "<span class=\"minefed-region-name\">" + escapedName + "</span>"
                + "<span class=\"minefed-region-address\">" + escapedAddress + "</span></div>";
    }

    static String markerPrefix(String id, String parentId, int level) {
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        return "r." + encoder.encodeToString(id.getBytes(StandardCharsets.UTF_8)) + "."
                + (parentId == null ? "" : encoder.encodeToString(parentId.getBytes(StandardCharsets.UTF_8)))
                + "." + Math.max(0, level) + ".";
    }

    /**
     * Finds an interior point, including concave polygons and holes. The widest scanline interval
     * is preferable to a bounding-box center, which can lie outside the region entirely.
     */
    static double[] labelPosition(double[][][] polygon) {
        TreeSet<Double> heights = new TreeSet<>();
        for (double[][] ring : polygon) for (double[] point : ring) heights.add(point[1]);
        List<Double> sorted = new ArrayList<>(heights);
        double bestWidth = -1;
        double[] best = polygon[0][0].clone();
        for (int h = 0; h + 1 < sorted.size(); h++) {
            double z = (sorted.get(h) + sorted.get(h + 1)) / 2;
            List<Double> intersections = new ArrayList<>();
            for (double[][] ring : polygon) {
                for (int i = 1; i < ring.length; i++) {
                    double[] a = ring[i - 1], b = ring[i];
                    if ((a[1] > z) != (b[1] > z)) {
                        intersections.add(a[0] + (z - a[1]) * (b[0] - a[0]) / (b[1] - a[1]));
                    }
                }
            }
            intersections.sort(Double::compare);
            for (int i = 0; i + 1 < intersections.size(); i += 2) {
                double width = intersections.get(i + 1) - intersections.get(i);
                if (width > bestWidth) {
                    bestWidth = width;
                    best = new double[]{(intersections.get(i) + intersections.get(i + 1)) / 2, z};
                }
            }
        }
        return best;
    }

    static double[][] openRing(double[][] closedRing) {
        int size = closedRing.length;
        if (size > 1 && closedRing[0][0] == closedRing[size - 1][0]
                && closedRing[0][1] == closedRing[size - 1][1]) size--;
        double[][] result = new double[size][];
        for (int i = 0; i < size; i++) result[i] = closedRing[i].clone();
        return result;
    }
}
