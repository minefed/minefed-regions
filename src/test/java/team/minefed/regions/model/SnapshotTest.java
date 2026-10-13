package team.minefed.regions.model;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class SnapshotTest {
    private static double[][] square(double lo, double hi) {
        return new double[][]{{lo,lo},{hi,lo},{hi,hi},{lo,hi},{lo,lo}};
    }
    private static Geometry shape(double lo, double hi) {
        return new Geometry("MultiPolygon", new double[][][][]{{square(lo,hi)}});
    }
    @Test void holesIslandsAndSharedBoundary() {
        Geometry geometry = new Geometry("MultiPolygon", new double[][][][]{
                {square(0,10), square(3,7)}, {square(20,25)}});
        assertTrue(geometry.covers(1,1));
        assertFalse(geometry.covers(5,5));
        assertTrue(geometry.covers(3,5));
        assertTrue(geometry.covers(22,22));
        assertFalse(geometry.covers(15,15));
    }
    @Test void differentDepthAddressesAndStrictDescendantPermissions() {
        Region country = new Region("a", null, "마인연방공화국", null, shape(-100,100), List.of("national"));
        Region city = new Region("b", "a", "수인시", null, shape(0,50), List.of("mayor"));
        Region ward = new Region("c", "b", "중구", null, shape(10,20), List.of("ward"));
        Snapshot snapshot = new Snapshot(3,List.of(ward,country,city),List.of("global"));
        assertEquals("마인연방공화국 수인시 중구", snapshot.address(snapshot.locate(15,15)));
        assertEquals("마인연방공화국 수인시", snapshot.address(snapshot.locate(5,5)));
        assertNull(snapshot.locate(101,0));
        assertFalse(snapshot.canEdit("mayor",city));
        assertTrue(snapshot.canEdit("mayor",ward));
        assertTrue(snapshot.canEdit("national",ward));
        assertTrue(snapshot.canEdit("global",country));
        assertFalse(snapshot.canEdit("operator",country));
        assertFalse(snapshot.canEdit("ward",ward));
    }
    @Test void touchingCountriesProduceDeterministicCoherentPath() {
        Region b = new Region("b",null,"B",null,shape(0,10),List.of());
        Region a = new Region("a",null,"A",null,shape(-10,0),List.of());
        Snapshot snapshot = new Snapshot(1,List.of(b,a),List.of());
        assertEquals("a",snapshot.locate(0,0).id());
    }
}
