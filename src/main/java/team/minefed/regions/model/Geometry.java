package team.minefed.regions.model;

/** GeoJSON MultiPolygon, using Minecraft X and Z as the two coordinates. */
public record Geometry(String type, double[][][][] coordinates) {
    public Geometry {
        coordinates = copy(coordinates);
    }

    /** Never expose mutable arrays from a validated snapshot to editors or integrations. */
    @Override public double[][][][] coordinates() { return copy(coordinates); }

    private static double[][][][] copy(double[][][][] source) {
        if (source == null) return null;
        double[][][][] result = new double[source.length][][][];
        for (int p = 0; p < source.length; p++) {
            if (source[p] == null) continue;
            result[p] = new double[source[p].length][][];
            for (int r = 0; r < source[p].length; r++) {
                if (source[p][r] == null) continue;
                result[p][r] = new double[source[p][r].length][];
                for (int v = 0; v < source[p][r].length; v++) {
                    if (source[p][r][v] != null) result[p][r][v] = source[p][r][v].clone();
                }
            }
        }
        return result;
    }

    public boolean covers(double x, double z) {
        if (coordinates == null) return false;
        for (double[][][] polygon : coordinates) {
            if (polygon.length == 0) continue;
            int exterior = inRing(polygon[0], x, z);
            if (exterior == 0) continue;
            boolean excluded = false;
            for (int i = 1; i < polygon.length; i++) {
                if (inRing(polygon[i], x, z) == 1) { excluded = true; break; }
            }
            if (!excluded) return true;
        }
        return false;
    }

    // 0 outside, 1 inside, 2 on the boundary. Shared edges are resolved by region ID.
    static int inRing(double[][] ring, double x, double z) {
        boolean inside = false;
        for (int i = 0, j = ring.length - 1; i < ring.length; j = i++) {
            double ax = ring[j][0], az = ring[j][1], bx = ring[i][0], bz = ring[i][1];
            double cross = (x - ax) * (bz - az) - (z - az) * (bx - ax);
            if (Math.abs(cross) <= 1e-9 && x >= Math.min(ax, bx) && x <= Math.max(ax, bx)
                    && z >= Math.min(az, bz) && z <= Math.max(az, bz)) return 2;
            if ((az > z) != (bz > z) && x < (bx - ax) * (z - az) / (bz - az) + ax) inside = !inside;
        }
        return inside ? 1 : 0;
    }
}
