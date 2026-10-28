package indi.mopelotus.musichud.client.ui.components;

import icyllis.modernui.view.MeasureSpec;
import icyllis.modernui.view.View;
import icyllis.modernui.view.ViewGroup;
import indi.mopelotus.musichud.client.ui.layouts.VirtualizedListLayout;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.stream.IntStream;
import static org.junit.jupiter.api.Assertions.*;

class VirtualizedListLayoutTest {
    private final LayoutTestContext context = new LayoutTestContext();

    @Test void tenThousandRowsOnlyCreateViewsNearTheViewportAndReuseThemAfterScrolling() {
        var adapter = new Rows();
        var list = new TestList(adapter);
        list.updateWindow(0, 200);
        list.resetItems(IntStream.range(0, 10_000).mapToObj(i -> new Row(i, 20)).toList());
        measure(list);
        assertEquals(200_000, list.getMeasuredHeight());
        assertTrue(adapter.created < 20);
        list.updateWindow(100_000, 200);
        measure(list);
        assertTrue(adapter.created < 25);
        assertTrue(adapter.cleared > 0);
        assertTrue(IntStream.range(0, list.getChildCount()).mapToObj(i -> (Cell) list.getChildAt(i))
                .anyMatch(cell -> cell.row != null && cell.row.id() == 5000));
    }

    @Test void replacingDataAndClearingDuringDeferredRecyclingNeverLeaveOldRows() {
        var adapter = new Rows();
        var list = new TestList(adapter);
        list.updateWindow(0, 200);
        list.resetItems(List.of(new Row(1, 20), new Row(2, 20)));
        list.updateItems(List.of(new Row(2, 40)));
        measure(list);
        assertEquals(40, list.getMeasuredHeight());
        list.resetItems(List.of());
        measure(list);
        assertEquals(0, list.getChildCount());
        assertEquals(0, list.getMeasuredHeight());
        list.resetItems(List.of(new Row(3, 20)));
        measure(list);
        assertEquals(3, ((Cell) list.getChildAt(0)).row.id());
    }

    @Test void cachedRouteDetachAndReattachKeepRowsAndNegativeIdsHaveMeasuredHeights() {
        var list = new TestList(new Rows());
        list.updateWindow(0, 200);
        list.resetItems(List.of(new Row(-3, 37), new Row(-2, 24)));
        measure(list);
        assertEquals(61, list.getMeasuredHeight());
        View first = list.getChildAt(0);
        list.detach();
        list.updateWindow(0, 200);
        measure(list);
        assertEquals(2, list.getChildCount());
        assertSame(first, list.getChildAt(0));
        assertEquals(61, list.getMeasuredHeight());
    }

    @Test void duplicateIdsAreRejectedBeforeReplacingTheVisibleData() {
        var list = new TestList(new Rows());
        list.resetItems(List.of(new Row(1, 20)));
        assertThrows(IllegalArgumentException.class,
                () -> list.resetItems(List.of(new Row(2, 20), new Row(2, 30))));
        measure(list);
        assertEquals(1, ((Cell) list.getChildAt(0)).row.id());
        assertThrows(IllegalArgumentException.class, () -> list.setDefaultItemHeight(0));
    }

    @Test void shorterRowsFillTheViewportWithoutAnotherScrollEvent() {
        var list = new TestList(new Rows());
        list.updateWindow(0, 200);
        list.resetItems(IntStream.range(0, 100).mapToObj(i -> new Row(i, 60)).toList());
        measure(list);
        list.updateItems(IntStream.range(0, 100).mapToObj(i -> new Row(i, 5)).toList());
        measure(list);
        int bottom = IntStream.range(0, list.getChildCount()).mapToObj(i -> (Cell)list.getChildAt(i))
                .filter(cell -> cell.row != null).mapToInt(Cell::getBottom).max().orElse(0);
        assertTrue(bottom >= 200, "measured row shrink must extend the active window");
        assertTrue(list.getChildCount() < 100, "must still virtualize");
    }

    @Test void retainedRowsNeverReceiveTransientZeroSizedLayoutsDuringResize() {
        var list = new TestList(new Rows()); list.updateWindow(0, 200);
        list.resetItems(List.of(new Row(1, 20), new Row(2, 20))); measure(list);
        var zeroLayouts = new java.util.concurrent.atomic.AtomicInteger();
        for (int i = 0; i < list.getChildCount(); i++) list.getChildAt(i).addOnLayoutChangeListener(
                (view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
                    if (right == left || bottom == top) zeroLayouts.incrementAndGet();
                });
        list.measure(MeasureSpec.makeMeasureSpec(500, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
        list.layout(0, 0, 500, list.getMeasuredHeight());
        assertEquals(0, zeroLayouts.get(), "active image/card rows must retain valid drawable bounds");
    }

    @Test void provisionalGridMeasurementsDoNotRecreateCardsOrInvalidateImageCallbacks() {
        var created = new java.util.HashMap<Integer, Integer>();
        var grid = new indi.mopelotus.musichud.client.ui.layouts.VirtualizedCardGrid<Integer>(context, 100, 120, item -> {
            created.merge(item, 1, Integer::sum);
            return new View(context);
        });
        grid.setItems(IntStream.range(0, 1000).boxed().toList());
        measure(grid);
        var rowList = (ViewGroup)grid.getChildAt(0);
        View firstRow = rowList.getChildAt(0);
        for (int i = 0; i < 5; i++) {
            grid.measure(MeasureSpec.makeMeasureSpec(700, MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
            grid.measure(MeasureSpec.makeMeasureSpec(300, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
        }
        // Older ModernUI may fill more initially unmeasured rows on the next measure.
        // New visible cards are valid; recreating an existing item's image publication is not.
        assertTrue(created.values().stream().allMatch(count -> count == 1),
                "provisional widths must not recreate existing card image publications");
        assertSame(firstRow, rowList.getChildAt(0));
    }

    @Test void zeroWidthProbeDoesNotRecycleRowsBeforeTheWeightedFinalMeasurement() {
        var adapter = new Rows(); adapter.wrapAtZeroWidth = true;
        var list = new TestList(adapter); list.updateWindow(0, 200);
        list.resetItems(IntStream.range(0, 1000).mapToObj(i -> new Row(i, 20)).toList());
        measure(list);
        int cleared = adapter.cleared;
        for (int pass = 0; pass < 5; pass++) {
            list.measure(MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
                    MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
            measure(list);
        }
        assertEquals(cleared, adapter.cleared,
                "a parent's zero-width sizing probe must not cancel visible image bindings");
        assertTrue(list.getChildCount() < 30, "the settled viewport remains virtualized");
    }

    @Test void nonzeroIntrinsicWidthProbesKeepFinalViewportImageBindings() {
        var adapter = new Rows(); adapter.wrapAtNarrowWidth = true;
        var list = new TestList(adapter); list.updateWindow(0, 200);
        list.resetItems(IntStream.range(0, 1000).mapToObj(i -> new Row(i, 20)).toList());
        measure(list);
        var visible = IntStream.range(0, list.getChildCount()).mapToObj(list::getChildAt).toList();
        int cleared = adapter.cleared;
        for (int pass = 0; pass < 5; pass++) {
            // Weighted ancestor baseline/intrinsic measurement can reach the list as
            // a nonzero EXACT width, followed by its wider final weighted allocation.
            list.measure(MeasureSpec.makeMeasureSpec(100, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
            measure(list);
        }
        assertEquals(cleared, adapter.cleared,
                "temporary narrow probes must not clear/rebind the final visible image rows");
        assertTrue(visible.stream().allMatch(view -> view.getParent() == list));
        assertTrue(list.getChildCount() < 30, "preserving probe bindings must remain virtualized");
    }

    @Test void settledNarrowResizeStillRecyclesRowsOutsideTheFinalWindow() {
        var adapter = new Rows(); adapter.wrapAtNarrowWidth = true;
        var list = new TestList(adapter); list.updateWindow(0, 200);
        list.resetItems(IntStream.range(0, 1000).mapToObj(i -> new Row(i, 20)).toList());
        measure(list);
        int cleared = adapter.cleared;
        list.measure(MeasureSpec.makeMeasureSpec(100, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
        list.layout(0, 0, 100, list.getMeasuredHeight());
        assertTrue(adapter.cleared > cleared, "a committed resize must prune the retained probe union");
        long bound = IntStream.range(0, list.getChildCount()).mapToObj(i -> (Cell) list.getChildAt(i))
                .filter(cell -> cell.row != null).count();
        assertTrue(bound < 10, "narrow final rows must not accumulate outside the viewport");
        list.updateWindow(10000, 200);
        measure(list);
        assertTrue(list.getChildCount() < 30, "scrolling must continue to recycle a bounded window");
    }

    @Test void repeatedCommittedResizesAndLateDetachKeepOnlyTheActiveWindow() {
        var adapter = new Rows(); adapter.wrapAtNarrowWidth = true;
        var list = new TestList(adapter); list.updateWindow(0, 200);
        list.resetItems(IntStream.range(0, 1000).mapToObj(i -> new Row(i, 20)).toList());
        measure(list);
        for (int width : new int[]{100, 500, 120, 700, 100, 300}) {
            list.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
            list.layout(0, 0, width, list.getMeasuredHeight());
            var bound = IntStream.range(0, list.getChildCount()).mapToObj(i -> (Cell) list.getChildAt(i))
                    .filter(cell -> cell.row != null).toList();
            assertTrue(bound.size() <= 12, "committed resize must bound the active window");
            assertTrue(bound.stream().mapToInt(Cell::getBottom).max().orElse(0) >= 200,
                    "widening must refill the entire viewport before another scroll event");
        }
        list.measure(MeasureSpec.makeMeasureSpec(100, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
        list.layout(0, 0, 100, list.getMeasuredHeight());
        // Removal is posted: detach must flush it even if that post never ran.
        list.detach();
        assertTrue(IntStream.range(0, list.getChildCount()).mapToObj(i -> (Cell) list.getChildAt(i))
                .allMatch(cell -> cell.row != null), "late detached recycle must not retain cleared children");
        assertTrue(list.getChildCount() < 10);
        measure(list);
        assertTrue(list.getChildCount() < 30, "reattaching/reusing the settled rows stays bounded");
    }

    @Test void finalLayoutDoesNotRecycleARowOwnedByAnAnimation() throws Exception {
        var adapter = new Rows(); adapter.wrapAtNarrowWidth = true;
        var list = new TestList(adapter); list.updateWindow(0, 200);
        list.resetItems(IntStream.range(0, 1000).mapToObj(i -> new Row(i, 20)).toList());
        measure(list);
        Cell retained = IntStream.range(0, list.getChildCount()).mapToObj(i -> (Cell) list.getChildAt(i))
                .max(java.util.Comparator.comparingLong(cell -> cell.row.id())).orElseThrow();
        retained.getLayoutParams().height = retained.getMeasuredHeight();
        var field = VirtualizedListLayout.class.getDeclaredField("runningAnimations"); field.setAccessible(true);
        @SuppressWarnings("unchecked")
        var animations = (java.util.Map<Long, icyllis.modernui.animation.AnimatorSet>) field.get(list);
        animations.put(retained.row.id(), new icyllis.modernui.animation.AnimatorSet());
        list.measure(MeasureSpec.makeMeasureSpec(100, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
        list.layout(0, 0, 100, list.getMeasuredHeight());
        assertNotNull(retained.row, "final-window pruning must preserve an animation's owned row");
        assertSame(list, retained.getParent());
        // This ownership fixture was never started on a Looper; normal detach is
        // exercised separately after removing the stand-in animation owner.
        animations.clear();
        list.detach();
    }

    private static void measure(View view) {
        view.measure(MeasureSpec.makeMeasureSpec(300, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
        view.layout(0, 0, view.getMeasuredWidth(), view.getMeasuredHeight());
    }

    private record Row(long id, int height) {}
    private final class Cell extends View {
        Row row;
        final boolean wrapAtZeroWidth;
        final boolean wrapAtNarrowWidth;
        Cell(boolean wrapAtZeroWidth, boolean wrapAtNarrowWidth) {
            super(context); this.wrapAtZeroWidth = wrapAtZeroWidth; this.wrapAtNarrowWidth = wrapAtNarrowWidth;
        }
        @Override protected void onMeasure(int width, int height) {
            setMeasuredDimension(MeasureSpec.getSize(width), resolveSize(row == null ? 0 : row.height() * ((wrapAtZeroWidth && MeasureSpec.getSize(width) == 0)
                    || (wrapAtNarrowWidth && MeasureSpec.getSize(width) < 200) ? 2 : 1), height));
        }
    }
    private final class Rows implements VirtualizedListLayout.Adapter<Row, Cell> {
        int created, cleared;
        boolean wrapAtZeroWidth;
        boolean wrapAtNarrowWidth;
        public long idOf(Row row) { return row.id(); }
        public Cell createItem(ViewGroup parent) { created++; return new Cell(wrapAtZeroWidth, wrapAtNarrowWidth); }
        public void clearItem(Cell view) { cleared++; view.row = null; view.requestLayout(); }
        public void bindItem(Cell view, Row row) { view.row = row; view.requestLayout(); }
        public long boundIdOf(Cell view) { return view.row == null ? -1 : view.row.id(); }
    }
    private final class TestList extends VirtualizedListLayout<Row, Cell> {
        TestList(Rows adapter) {
            super(context, adapter);
            setDefaultItemHeight(20);
            setAnimationsEnabled(false);
        }
        void detach() { onDetachedFromWindow(); }
    }
}
