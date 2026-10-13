package team.minefed.regions.model;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Validates API/cache structure before it can reach a game tick. The API remains authoritative
 * for polygon topology, sibling overlap and parent coverage. Never repairs geometry silently.
 */
public final class SnapshotValidator {
    public static final int MAX_REGIONS = 4096;
    public static final int MAX_DEPTH = 64;
    public static final int MAX_GEOMETRY_VERTICES = 4096;
    public static final int MAX_TOTAL_VERTICES = 65536;
    public static final int MAX_MANAGERS = 256;
    public static final double MAX_COORDINATE = 30000000;
    private static final Pattern UUID = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

    private SnapshotValidator() {}

    /** Returns a detached, immutable snapshot, or rejects the complete input without modifying it. */
    public static Snapshot validate(Snapshot input) {
        require(input != null && input.revision() >= 0, "Invalid snapshot revision");
        require(input.regions() != null && input.regions().size() <= MAX_REGIONS, "Invalid region count");
        List<String> globalAdmins = validateUuids(input.globalAdmins());
        List<Region> regions = new ArrayList<>(input.regions().size());
        Map<String, Region> byId = new HashMap<>();
        Set<String> siblingNames = new HashSet<>();
        int totalVertices = 0;
        for (Region region : input.regions()) {
            require(region != null, "Null region");
            requireUuid(region.id());
            String parent = region.parentId();
            if (parent != null && parent.isEmpty()) parent = null;
            if (parent != null) requireUuid(parent);
            validateName(region.name());
            require(region.displayLevel() == null || region.displayLevel() >= 0 && region.displayLevel() < MAX_DEPTH,
                    "Invalid display level");
            require(siblingNames.add((parent == null ? "" : parent) + '\0' + region.name()), "Duplicate sibling name");
            List<String> managers = validateUuids(region.managers());
            require(region.geometry() != null && "MultiPolygon".equals(region.geometry().type()), "Invalid geometry type");
            double[][][][] coordinates = region.geometry().coordinates();
            int vertices = validateCoordinates(coordinates);
            totalVertices += vertices;
            require(totalVertices <= MAX_TOTAL_VERTICES, "World vertex limit exceeded");
            Region copy = new Region(region.id(), parent, region.name(), region.displayLevel(),
                    new Geometry("MultiPolygon", coordinates), managers);
            require(byId.putIfAbsent(copy.id(), copy) == null, "Duplicate region ID");
            regions.add(copy);
        }
        for (Region region : regions) {
            Set<String> visited = new HashSet<>();
            Region current = region;
            while (current != null) {
                require(visited.add(current.id()), "Cyclic hierarchy");
                require(visited.size() <= MAX_DEPTH, "Hierarchy exceeds 64 levels");
                if (current.parentId() == null) break;
                current = byId.get(current.parentId());
                require(current != null, "Missing parent region");
            }
        }
        return new Snapshot(input.revision(), List.copyOf(regions), globalAdmins);
    }

    private static List<String> validateUuids(List<String> values) {
        require(values != null && values.size() <= MAX_MANAGERS, "Invalid administrator list");
        Set<String> unique = new HashSet<>();
        for (String value : values) {
            requireUuid(value);
            require(unique.add(value), "Duplicate administrator UUID");
        }
        return List.copyOf(values);
    }

    private static void requireUuid(String value) {
        require(value != null && UUID.matcher(value).matches(), "Invalid canonical UUID");
    }

    private static void validateName(String name) {
        require(name != null && !name.isEmpty() && name.codePointCount(0, name.length()) <= 80,
                "Name must contain 1 to 80 Unicode characters");
        require(Normalizer.isNormalized(name, Normalizer.Form.NFC), "Name must use NFC normalization");
        int first = name.codePointAt(0), last = name.codePointBefore(name.length());
        require(!space(first) && !space(last), "Name must not start or end with whitespace");
        name.codePoints().forEach(point -> require(!Character.isISOControl(point)
                        && Character.getType(point) != Character.FORMAT
                        && Character.getType(point) != Character.SURROGATE && point != 0x00a7,
                "Unsupported name character"));
    }

    private static boolean space(int point) {
        return Character.isWhitespace(point) || Character.isSpaceChar(point);
    }

    private static int validateCoordinates(double[][][][] coordinates) {
        require(coordinates != null && coordinates.length > 0, "Empty MultiPolygon");
        int count = 0;
        for (double[][][] polygon : coordinates) {
            require(polygon != null && polygon.length > 0, "Empty polygon");
            for (double[][] ring : polygon) {
                require(ring != null && ring.length >= 4, "Ring requires at least four closed points");
                count += ring.length;
                require(count <= MAX_GEOMETRY_VERTICES, "Geometry vertex limit exceeded");
                for (double[] point : ring) {
                    require(point != null && point.length == 2, "Point must contain X and Z");
                    for (double coordinate : point) require(Double.isFinite(coordinate)
                            && Math.abs(coordinate) <= MAX_COORDINATE, "Invalid coordinate");
                }
                require(ring[0][0] == ring[ring.length - 1][0] && ring[0][1] == ring[ring.length - 1][1],
                        "Ring is not closed");
            }
        }
        return count;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }
}
