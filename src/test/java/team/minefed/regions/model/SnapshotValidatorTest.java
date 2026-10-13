package team.minefed.regions.model;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class SnapshotValidatorTest {
    private static String id(int number) { return new UUID(0, number).toString(); }
    private static Geometry square() {
        return new Geometry("MultiPolygon", new double[][][][]{{{{0,0},{10,0},{10,10},{0,10},{0,0}}}});
    }
    private static Region region(int number, String parent, Geometry geometry) {
        return new Region(id(number), parent, "지역 " + number, null, geometry, List.of(id(10000)));
    }
    private static Snapshot snapshot(Region... regions) { return new Snapshot(2, Arrays.asList(regions), List.of(id(10001))); }

    @Test void acceptsMaximumDepthAndSupplementaryUnicodeNames() {
        List<Region> regions = new ArrayList<>();
        String name = "😀".repeat(80);
        for (int i = 0; i < 64; i++) regions.add(new Region(id(i), i == 0 ? null : id(i - 1),
                name, i, square(), List.of(id(10000))));
        Snapshot result = SnapshotValidator.validate(new Snapshot(1, regions, List.of(id(10001))));
        assertEquals(63, result.depth(result.find(id(63))));
        assertEquals(10303, result.address(result.find(id(63))).length());
        assertEquals(id(63), result.locate(5, 5).id());
        regions.add(region(64, id(63), square()));
        assertThrows(IllegalArgumentException.class, () -> SnapshotValidator.validate(new Snapshot(1, regions, List.of())));
    }

    @Test void rejectsMissingParentsCyclesAndDuplicateIdentities() {
        Region a = region(1, id(2), square());
        Region b = region(2, id(1), square());
        assertThrows(IllegalArgumentException.class, () -> SnapshotValidator.validate(snapshot(a, b)));
        assertThrows(IllegalArgumentException.class, () -> SnapshotValidator.validate(snapshot(a)));
        assertThrows(IllegalArgumentException.class, () -> SnapshotValidator.validate(snapshot(region(1, null, square()), region(1, null, square()))));
        Region sameName = new Region(id(2), null, "지역 1", null, square(), List.of());
        assertThrows(IllegalArgumentException.class, () -> SnapshotValidator.validate(snapshot(region(1, null, square()), sameName)));
    }

    @Test void normalizesEmptyRootParentWithoutChangingInput() {
        Region raw = region(1, "", square());
        Snapshot copy = SnapshotValidator.validate(snapshot(raw));
        assertEquals("", raw.parentId());
        assertNull(copy.regions().get(0).parentId());
        assertEquals(id(1), copy.locate(1,1).id());
    }

    @Test void rejectsMalformedShapesBeforePublishingAnyState() {
        List<Geometry> malformed = Arrays.asList(null,
                new Geometry("Polygon", square().coordinates()),
                new Geometry("MultiPolygon", null),
                new Geometry("MultiPolygon", new double[0][][][]),
                new Geometry("MultiPolygon", new double[][][][]{null}),
                new Geometry("MultiPolygon", new double[][][][]{{null}}),
                new Geometry("MultiPolygon", new double[][][][]{{{{0,0},{1,0},{0,0}}}}),
                new Geometry("MultiPolygon", new double[][][][]{{{{0,0},{1},{1,1},{0,0}}}}),
                new Geometry("MultiPolygon", new double[][][][]{{{{0,0},null,{1,1},{0,0}}}}),
                new Geometry("MultiPolygon", new double[][][][]{{{{0,0},{1,0},{1,1},{0,2}}}}));
        Snapshot previous = SnapshotValidator.validate(snapshot(region(1, null, square())));
        AtomicReference<Snapshot> active = new AtomicReference<>(previous);
        for (Geometry geometry : malformed) {
            Snapshot candidate = snapshot(region(2, null, square()), region(3, null, geometry));
            assertThrows(IllegalArgumentException.class, () -> active.set(SnapshotValidator.validate(candidate)));
            assertSame(previous, active.get());
            assertEquals(2, candidate.regions().size());
        }
    }

    @Test void rejectsNonFiniteAndOutOfWorldCoordinates() {
        for (double invalid : new double[]{Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, 30000001, -30000001}) {
            double[][][][] coordinates = square().coordinates();
            coordinates[0][0][1][0] = invalid;
            assertThrows(IllegalArgumentException.class, () -> SnapshotValidator.validate(snapshot(
                    region(1, null, new Geometry("MultiPolygon", coordinates)))));
        }
        double[][][][] coordinates = square().coordinates();
        coordinates[0][0][1][0] = 30000000;
        assertDoesNotThrow(() -> SnapshotValidator.validate(snapshot(region(1, null, new Geometry("MultiPolygon", coordinates)))));
    }

    @Test void validatesCanonicalUuidNamesAndDisplayLevel() {
        for (String invalid : Arrays.asList(null, "", " padded", "trailing\u00a0", "a\n", "x§y", "a\u200db", "a".repeat(81), "e\u0301", "\ud800")) {
            assertThrows(IllegalArgumentException.class, () -> SnapshotValidator.validate(snapshot(
                    new Region(id(1), null, invalid, 0, square(), List.of()))));
        }
        for (Integer invalid : List.of(-1, 64)) assertThrows(IllegalArgumentException.class,
                () -> SnapshotValidator.validate(snapshot(new Region(id(1), null, "이름", invalid, square(), List.of()))));
        assertThrows(IllegalArgumentException.class, () -> SnapshotValidator.validate(snapshot(
                new Region("not-a-uuid", null, "이름", null, square(), List.of()))));
        assertThrows(IllegalArgumentException.class, () -> SnapshotValidator.validate(snapshot(
                new Region(id(1), null, "이름", null, square(), List.of("00000000-0000-0000-0000-00000000000A")))));
        assertThrows(IllegalArgumentException.class, () -> SnapshotValidator.validate(new Snapshot(0, List.of(), List.of(id(1),id(1)))));
        assertThrows(IllegalArgumentException.class, () -> SnapshotValidator.validate(new Snapshot(-1, List.of(), List.of())));
        assertThrows(IllegalArgumentException.class, () -> SnapshotValidator.validate(new Snapshot(0, null, List.of())));
        assertThrows(IllegalArgumentException.class, () -> SnapshotValidator.validate(new Snapshot(0, List.of(), null)));
        assertThrows(IllegalArgumentException.class, () -> SnapshotValidator.validate(snapshot((Region) null)));
    }

    @Test void detachedSnapshotCannotBeChangedThroughArraysOrLists() {
        double[][][][] coordinates = square().coordinates();
        List<String> managers = new ArrayList<>(List.of(id(10000)));
        List<Region> regions = new ArrayList<>(List.of(new Region(id(1), null, "나라", null,
                new Geometry("MultiPolygon", coordinates), managers)));
        List<String> global = new ArrayList<>(List.of(id(10001)));
        Snapshot result = SnapshotValidator.validate(new Snapshot(1, regions, global));
        coordinates[0][0][0][0] = 500;
        managers.clear(); regions.clear(); global.clear();
        result.regions().get(0).geometry().coordinates()[0][0][0][0] = 600;
        assertEquals(id(1), result.locate(1,1).id());
        assertEquals(List.of(id(10000)), result.regions().get(0).managers());
        assertEquals(List.of(id(10001)), result.globalAdmins());
        assertThrows(UnsupportedOperationException.class, () -> result.regions().clear());
        assertThrows(UnsupportedOperationException.class, () -> result.regions().get(0).managers().clear());
        assertThrows(UnsupportedOperationException.class, () -> result.globalAdmins().clear());
    }

    @Test void enforcesGeometryAndWorldVertexLimitsIncludingClosingPoint() {
        List<Region> regions = new ArrayList<>();
        Geometry maximum = circle(4096);
        for (int i = 0; i < 16; i++) regions.add(region(i, null, maximum));
        assertDoesNotThrow(() -> SnapshotValidator.validate(new Snapshot(0, regions, List.of())));
        regions.add(region(16, null, square()));
        assertThrows(IllegalArgumentException.class, () -> SnapshotValidator.validate(new Snapshot(0, regions, List.of())));
        assertThrows(IllegalArgumentException.class, () -> SnapshotValidator.validate(snapshot(region(1, null, circle(4097)))));
    }

    @Test void enforcesRegionAndAdministratorCounts() {
        List<Region> regions = new ArrayList<>();
        for (int i = 0; i < 4097; i++) regions.add(region(i, null, square()));
        assertThrows(IllegalArgumentException.class, () -> SnapshotValidator.validate(new Snapshot(0, regions, List.of())));
        List<String> managers = new ArrayList<>();
        for (int i = 0; i < 257; i++) managers.add(id(i));
        assertThrows(IllegalArgumentException.class, () -> SnapshotValidator.validate(new Snapshot(0, List.of(), managers)));
        assertThrows(IllegalArgumentException.class, () -> SnapshotValidator.validate(snapshot(
                new Region(id(1), null, "이름", null, square(), managers))));
    }

    private static Geometry circle(int pointCount) {
        double[][] ring = new double[pointCount][2];
        for (int i = 0; i < pointCount - 1; i++) {
            double angle = 2 * Math.PI * i / (pointCount - 1);
            ring[i] = new double[]{Math.cos(angle) * 100, Math.sin(angle) * 100};
        }
        ring[pointCount - 1] = ring[0].clone();
        return new Geometry("MultiPolygon", new double[][][][]{{ring}});
    }
}
