package team.minefed.regions.bluemap;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RegionMapDataTest {
    @Test
    void labelStaysInsideConcavePolygon() {
        double[][][] polygon = {{{0, 0}, {10, 0}, {10, 2}, {2, 2}, {2, 10}, {0, 10}, {0, 0}}};
        double[] point = RegionMapData.labelPosition(polygon);
        assertTrue(point[0] > 0 && point[1] > 0);
        assertTrue(point[0] < 2 || point[1] < 2, "Label must not land in the missing corner");
    }

    @Test
    void labelAvoidsEnclaveHole() {
        double[][][] polygon = {
                {{0, 0}, {20, 0}, {20, 20}, {0, 20}, {0, 0}},
                {{4, 4}, {16, 4}, {16, 16}, {4, 16}, {4, 4}}
        };
        double[] point = RegionMapData.labelPosition(polygon);
        assertTrue(point[0] > 0 && point[0] < 20 && point[1] > 0 && point[1] < 20);
        assertFalse(point[0] >= 4 && point[0] <= 16 && point[1] >= 4 && point[1] <= 16);
    }

    @Test
    void escapedNamesCannotInjectMarkupOrAttributes() {
        assertEquals("&lt;img src=&quot;x&quot; onerror=&#39;x&#39;&gt;&amp;",
                RegionMapData.escapeHtml("<img src=\"x\" onerror='x'>&"));
    }

    @Test
    void markerMetadataIsDelimiterSafeAndDoesNotContainRawNames() {
        String key = RegionMapData.markerPrefix("a.b/지역", "부모.\"", 2);
        assertTrue(key.matches("r\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.2\\."));
        assertEquals("r.YQ..0.", RegionMapData.markerPrefix("a", null, 0));
    }

    @Test
    void topLevelBoundariesAreBolderAndAlwaysCased() {
        assertTrue(RegionMapData.lineWidth(0) > RegionMapData.lineWidth(1));
        assertEquals(RegionMapData.lineWidth(1), RegionMapData.lineWidth(5));
        for (int level = 0; level < 4; level++) {
            assertTrue(RegionMapData.casingWidth(level) > RegionMapData.lineWidth(level));
        }
    }

    @Test
    void labelMarkupKeepsEscapedTextAndClampsLevelClass() {
        String html = RegionMapData.labelHtml("&lt;중앙구&gt;", "시 &amp; 구", 9);
        assertTrue(html.contains("minefed-region-level-3"));
        assertTrue(html.contains("<span class=\"minefed-region-name\">&lt;중앙구&gt;</span>"));
        assertTrue(html.contains("<span class=\"minefed-region-address\">시 &amp; 구</span>"));
        assertTrue(RegionMapData.labelHtml("a", "b", -1).contains("minefed-region-level-0"));
    }

    @Test
    void closesExactlyOnceWithoutMutatingSnapshot() {
        double[][] ring = {{0, 0}, {10, 0}, {0, 10}, {0, 0}};
        double[][] open = RegionMapData.openRing(ring);
        assertEquals(3, open.length);
        open[0][0] = 42;
        assertEquals(0, ring[0][0]);
        assertEquals(3, RegionMapData.openRing(new double[][]{{0, 0}, {10, 0}, {0, 10}}).length);
    }
}
