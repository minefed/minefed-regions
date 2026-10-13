package team.minefed.regions.server;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SelectionTest {
    @Test void pollingCannotSilentlyRebaseAnOlderBoundaryDraft() {
        Selection draft=new Selection();draft.captureRevision(3);triangle(draft,0);
        draft.captureRevision(5);assertEquals(3,draft.baseRevision());
        draft.rebase(5);assertEquals(5,draft.baseRevision());
        draft.load(draft.geometry());draft.captureRevision(6);assertEquals(6,draft.baseRevision());
        draft.clear();draft.captureRevision(7);assertEquals(7,draft.baseRevision());
    }
    static void triangle(Selection draft,double offset) { draft.point(offset,0);draft.point(offset+2,0);draft.point(offset,2); }
    @Test void explicitClosingHoleAndDetachedIslandSurviveReload() {
        Selection draft=new Selection();triangle(draft,0);draft.hole();triangle(draft,0.5);draft.newPart();triangle(draft,10);
        var shape=draft.geometry();assertEquals(2,shape.coordinates().length);assertEquals(2,shape.coordinates()[0].length);
        assertArrayEquals(shape.coordinates()[0][0][0],shape.coordinates()[0][0][3]);
        Selection reloaded=new Selection();reloaded.load(shape);assertEquals(9,reloaded.count());
        reloaded.focus(2,1);reloaded.vertex(1,11,1);
        assertEquals(10,shape.coordinates()[1][0][0][0],"Editing a draft must not mutate the authoritative snapshot");
        reloaded.removeRing();assertEquals(1,reloaded.geometry().coordinates().length);
    }
    @Test void unfinishedDraftAndNonfinitePointsAreRejected() {
        Selection draft=new Selection();draft.point(0,0);draft.point(1,1);
        assertThrows(IllegalArgumentException.class,draft::geometry);
        assertThrows(IllegalArgumentException.class,draft::hole);
        assertThrows(IllegalArgumentException.class,()->draft.point(Double.NaN,0));
        assertThrows(IllegalArgumentException.class,()->draft.vertex(1,0,Double.POSITIVE_INFINITY));
        assertEquals(2,draft.count());draft.undo();assertEquals(1,draft.count());
    }
}
