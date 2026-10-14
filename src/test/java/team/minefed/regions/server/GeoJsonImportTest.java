package team.minefed.regions.server;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class GeoJsonImportTest {
    private static final Gson GSON = new Gson();
    private static final String RING = "[[0,0],[10.125,0],[10.125,10],[0,0]]";
    private static final String SIMPLE = "{\"type\":\"MultiPolygon\",\"coordinates\":[[" + RING + "]]}";

    @TempDir Path directory;

    @Test void preservesCoordinateOrderIslandsHolesAndFractionalNumbersAndIgnoresMetadata() throws Exception {
        String json = "{\"regionId\":\"forged\",\"name\":\"forged\",\"actorUuid\":\"forged\",\"console\":true,"
                + "\"managers\":[\"forged\"],\"type\":\"MultiPolygon\",\"coordinates\":[[" + RING
                + ",[[1,1],[1,2],[2,2],[1,1]]],[[[-3.125,-4.5],[-2,-4],[-2,-3],[-3.125,-4.5]]]]}";
        GeoJsonImport.Result result = read(json);
        assertEquals("input.geojson", result.filename());
        assertEquals(2, result.polygons());
        assertEquals(3, result.rings());
        assertEquals(12, result.verticesIncludingClosure());
        assertEquals(9, result.verticesExcludingClosure());
        assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.getBytes(StandardCharsets.UTF_8))),
                result.sha256());
        assertOriginalCoordinates(json, result);
        JsonObject serialized = GSON.toJsonTree(result.geometry()).getAsJsonObject();
        assertEquals(2, serialized.size());
        assertEquals("MultiPolygon", serialized.get("type").getAsString());
        result.geometry().coordinates()[0][0][1][0] = 999;
        assertEquals(10.125, result.geometry().coordinates()[0][0][1][0]);
    }

    @Test void rejectsMalformedJsonAndUnsupportedRootTypes() {
        for (String json : List.of("", "null", "[]", "{", SIMPLE + " true",
                SIMPLE.replace("\"type\"", "type"), SIMPLE.replace("\"MultiPolygon\"", "'MultiPolygon'"),
                SIMPLE.replace("\"coordinates\":", "\"type\":\"MultiPolygon\",\"coordinates\":"),
                SIMPLE.replace("\"coordinates\":", "/*comment*/\"coordinates\":"),
                SIMPLE.replace("MultiPolygon", "Polygon"), "{\"coordinates\":[]}",
                "{\"type\":\"MultiPolygon\"}", "{\"type\":17,\"coordinates\":[]}")) {
            assertThrows(IllegalArgumentException.class, () -> read(json), json);
        }
        for (String type : List.of("Feature", "FeatureCollection")) {
            IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                    () -> read("{\"type\":\"" + type + "\"}"));
            assertTrue(exception.getMessage().contains("Feature"));
            assertTrue(exception.getMessage().contains("지원하지 않습니다"));
        }
    }

    @Test void rejectsEmptyNullMalformedCoordinatesAndUnclosedRings() {
        for (String coordinates : List.of("null", "{}", "[]", "[null]", "[[]]", "[[null]]", "[[[]]]",
                "[[[null,[1,0],[1,1],[0,0]]]]", "[[[[0,0],[1,0],[0,0]]]]",
                "[[[[0,0],[1,0],[1,1],[0,1]]]]", "[[[[0,0],[1],[1,1],[0,0]]]]",
                "[[[[0,0],[1,0,5],[1,1],[0,0]]]]", "[[[[0,0],[1,0],[],[0,0]]]]",
                "[[[[0,0],[\"1\",0],[1,1],[0,0]]]]", "[[[[0,0],[true,0],[1,1],[0,0]]]]")) {
            assertThrows(IllegalArgumentException.class,
                    () -> read("{\"type\":\"MultiPolygon\",\"coordinates\":" + coordinates + "}"), coordinates);
        }
    }

    @Test void rejectsLegacyGsonJsonExtensionsEvenInIgnoredMetadata() {
        for (String metadata : List.of("TRUE", "False", "NULL", "\"literal\nnewline\"", "\"bad\\'escape\"",
                "\"bad\\uesca\"", "\"bad\tcontrol\"", "\"bad\\qescape\"")) {
            assertThrows(IllegalArgumentException.class,
                    () -> read(SIMPLE.substring(0, SIMPLE.length() - 1) + ",\"ignored\":" + metadata + "}"), metadata);
        }
    }

    @Test void ignoresDeepMetadataWithoutRecursiveParsingAndAcceptsValidStringEscapes() throws Exception {
        String metadata = "[".repeat(10000) + "{\"valid\":\"\\\"\\\\\\/\\b\\f\\n\\r\\t\\u1234\",\"flags\":[true,false,null]}" + "]".repeat(10000);
        assertEquals(4, read(SIMPLE.substring(0, SIMPLE.length() - 1) + ",\"ignored\":" + metadata + "}").verticesIncludingClosure());
    }

    @Test void rejectsNonFiniteOutOfRangeOrNonJsonNumbersAndAcceptsCoordinateBounds() throws Exception {
        for (String number : List.of("NaN", "Infinity", "-Infinity", "1e999", "-1e999", "30000000.01",
                "-30000000.01", "null", "\"NaN\"", "01", "+1", "0x10")) {
            assertThrows(IllegalArgumentException.class, () -> read(SIMPLE.replace("10.125", number)), number);
        }
        assertDoesNotThrow(() -> read(SIMPLE.replace("10.125", "30000000")));
        assertDoesNotThrow(() -> read(SIMPLE.replace("10.125", "-30000000")));
        assertDoesNotThrow(() -> read(SIMPLE.replace("10.125", "3e7")));
    }

    @Test void usesApiVertexLimitWithoutPlayerSelectionPolygonOrRingCaps() throws Exception {
        assertEquals(4096, read(circle(4096)).verticesIncludingClosure());
        assertThrows(IllegalArgumentException.class, () -> read(circle(4097)));
        String polygons = "[" + String.join(",", java.util.Collections.nCopies(33, "[" + RING + "]")) + "]";
        assertEquals(33, read("{\"type\":\"MultiPolygon\",\"coordinates\":" + polygons + "}").polygons());
        String rings = "[[" + String.join(",", java.util.Collections.nCopies(33, RING)) + "]]";
        assertEquals(33, read("{\"type\":\"MultiPolygon\",\"coordinates\":" + rings + "}").rings());
    }

    @Test void onlyChecksStructureAndDoesNotRepairOrClaimTopology() throws Exception {
        String crossing = "{\"type\":\"MultiPolygon\",\"coordinates\":[[[[0,0],[10,10],[0,10],[10,0],[0,0]]]]}";
        assertOriginalCoordinates(crossing, read(crossing));
    }

    @Test void rejectsInvalidUtf8AndBoundsActualFileBytes() throws Exception {
        Files.write(directory.resolve("input.geojson"), new byte[]{(byte) 0xc3, 0x28});
        assertThrows(IllegalArgumentException.class, () -> GeoJsonImport.read(directory, "input.geojson"));
        String maximum = SIMPLE + " ".repeat(GeoJsonImport.MAX_FILE_BYTES - SIMPLE.getBytes(StandardCharsets.UTF_8).length);
        assertEquals(4, read(maximum).verticesIncludingClosure());
        assertThrows(IllegalArgumentException.class, () -> read(maximum + " "));
    }

    @Test void onlyReadsRegularGeojsonBasenamesInsideImports() throws Exception {
        Files.writeString(directory.resolve("input.geojson"), SIMPLE);
        for (String filename : List.of("", " ", "..", "../input.geojson", "..\\input.geojson", "nested/input.geojson",
                "nested\\input.geojson", "/input.geojson", "C:\\input.geojson", "C:input.geojson", "input.geojson:stream",
                "input.json", "input.geojson\0", "\\\\server\\share\\input.geojson")) {
            assertThrows(IllegalArgumentException.class, () -> GeoJsonImport.read(directory, filename), filename);
        }
        assertThrows(IllegalArgumentException.class, () -> GeoJsonImport.read(directory, null));
        assertThrows(IOException.class, () -> GeoJsonImport.read(directory, "missing.geojson"));
        Files.createDirectory(directory.resolve("folder.geojson"));
        assertThrows(IllegalArgumentException.class, () -> GeoJsonImport.read(directory, "folder.geojson"));
    }

    @Test void rejectsExternalAndInternalFileSymlinksAndSymlinkImportsDirectories() throws Exception {
        Path outside = Files.createDirectory(directory.resolve("outside"));
        Path imports = Files.createDirectory(directory.resolve("imports"));
        Path external = Files.writeString(outside.resolve("external.geojson"), SIMPLE);
        Path internal = Files.writeString(imports.resolve("internal.geojson"), SIMPLE);
        createSymlink(imports.resolve("external-link.geojson"), external);
        createSymlink(imports.resolve("internal-link.geojson"), internal);
        createSymlink(directory.resolve("linked-imports"), imports);
        assertThrows(IllegalArgumentException.class, () -> GeoJsonImport.read(imports, "external-link.geojson"));
        assertThrows(IllegalArgumentException.class, () -> GeoJsonImport.read(imports, "internal-link.geojson"));
        assertThrows(IllegalArgumentException.class, () -> GeoJsonImport.read(directory.resolve("linked-imports"), "internal.geojson"));
    }

    /** Opt in with MINEFED_IMPORT_FIXTURES_DIR; private operational data never enters the repository. */
    @Test void verifiesUserSuppliedTerritoriesWithoutChangingOrSubmittingThem() throws Exception {
        String configured = System.getProperty("minefed.importFixtures", System.getenv("MINEFED_IMPORT_FIXTURES_DIR"));
        Assumptions.assumeTrue(configured != null && !configured.isBlank(), "Local territory fixtures were not configured");
        Path fixtures = Path.of(configured);
        verifyFixture(fixtures, "minefed-command-geometry.geojson", 1, 10, 3824, 3834);
        verifyFixture(fixtures, "free-union-command-geometry.geojson", 2, 4, 1480, 1484);
    }

    private static void verifyFixture(Path fixtures, String filename, int polygons, int rings,
                                      int excludingClosure, int includingClosure) throws Exception {
        byte[] original = Files.readAllBytes(fixtures.resolve(filename));
        GeoJsonImport.Result result = GeoJsonImport.read(fixtures, filename);
        assertEquals(polygons, result.polygons());
        assertEquals(rings, result.rings());
        assertEquals(excludingClosure, result.verticesExcludingClosure());
        assertEquals(includingClosure, result.verticesIncludingClosure());
        assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(original)), result.sha256());
        assertOriginalCoordinates(new String(original, StandardCharsets.UTF_8), result);
        assertArrayEquals(original, Files.readAllBytes(fixtures.resolve(filename)));
    }

    private static void assertOriginalCoordinates(String json, GeoJsonImport.Result result) {
        double[][][][] original = GSON.fromJson(JsonParser.parseString(json).getAsJsonObject().get("coordinates"), double[][][][].class);
        assertTrue(Arrays.deepEquals(original, result.geometry().coordinates()), "Every original coordinate, ring and polygon must retain its order and value");
    }

    private static void createSymlink(Path link, Path target) throws IOException {
        try {
            Files.createSymbolicLink(link, target);
        } catch (UnsupportedOperationException | IOException | SecurityException exception) {
            Assumptions.assumeTrue(false, "Symbolic links unavailable on this host: " + exception.getMessage());
        }
    }

    private GeoJsonImport.Result read(String json) throws IOException {
        Files.writeString(directory.resolve("input.geojson"), json, StandardCharsets.UTF_8);
        return GeoJsonImport.read(directory, "input.geojson");
    }

    private static String circle(int vertices) {
        double[][] ring = new double[vertices][2];
        for (int i = 0; i < vertices - 1; i++) {
            double angle = 2 * Math.PI * i / (vertices - 1);
            ring[i] = new double[]{Math.cos(angle) * 100, Math.sin(angle) * 100};
        }
        ring[vertices - 1] = ring[0].clone();
        return "{\"type\":\"MultiPolygon\",\"coordinates\":" + GSON.toJson(new double[][][][]{{ring}}) + "}";
    }
}
