package indi.mopelotus.musichud.client.ui.components;

import icyllis.modernui.view.MeasureSpec;
import icyllis.modernui.view.View;
import icyllis.modernui.widget.LinearLayout;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WaterfallLayoutTest {
    private final LayoutTestContext context = new LayoutTestContext();
    @Test void cachedRouteDetachmentRetainsContentAndReflowNeverDuplicatesChildren() {
        TestLayout layout = new TestLayout();
        View a = cell(100), b = cell(30), c = cell(40);
        layout.addView(a); layout.addView(b); layout.addView(c);
        measure(layout, 240);
        assertEquals(3, layout.getChildCount());
        assertEquals(0, a.getLeft()); assertEquals(0, a.getTop());
        assertEquals(120, b.getLeft()); assertEquals(0, b.getTop());
        assertEquals(120, c.getLeft()); assertEquals(30, c.getTop());
        layout.detach();
        measure(layout, 120);
        assertEquals(3, layout.getChildCount());
        assertEquals(0, c.getLeft()); assertEquals(130, c.getTop());
        assertEquals(170, layout.getMeasuredHeight());
        layout.removeView(b);
        measure(layout, 120);
        assertEquals(140, layout.getMeasuredHeight());
        assertNull(b.getParent());
        layout.removeAllViews();
        measure(layout, 120);
        assertNull(a.getParent()); assertNull(c.getParent());
        assertEquals(0, layout.getMeasuredHeight());
    }
    @Test void measurementUsesActualColumnWidthAndIncludesMargins() {
        TestLayout layout = new TestLayout();
        View wrapping = new View(context) {
            @Override protected void onMeasure(int width, int height) {
                setMeasuredDimension(MeasureSpec.getSize(width), MeasureSpec.getSize(width) < 100 ? 60 : 30);
            }
        };
        var params = new LinearLayout.LayoutParams(-1, -2);
        params.setMargins(10, 5, 10, 7);
        wrapping.setLayoutParams(params);
        layout.addView(wrapping);
        measure(layout, 110);
        assertEquals(72, layout.getMeasuredHeight());
    }
    @Test void alternatingWidthPassesKeepTheSameParentsEvenWhenColumnCountChanges() {
        TestLayout layout = new TestLayout();
        View first = cell(80), second = cell(60), third = cell(40);
        layout.addView(first); layout.addView(second); layout.addView(third);
        measure(layout, 350);
        var firstParent = first.getParent();
        var secondParent = second.getParent();
        var thirdParent = third.getParent();
        for (int i = 0; i < 20; i++) {
            layout.measure(MeasureSpec.makeMeasureSpec(230, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
            measure(layout, 350);
            assertSame(firstParent, first.getParent());
            assertSame(secondParent, second.getParent());
            assertSame(thirdParent, third.getParent());
            assertEquals(3, layout.getChildCount());
        }
    }
    @Test void paddingAndHiddenRowsDoNotConsumeColumnHeight() {
        TestLayout layout = new TestLayout();
        layout.setPadding(7, 11, 13, 17);
        View first = cell(10), hidden = cell(500), last = cell(20);
        hidden.setVisibility(View.GONE);
        layout.addView(first); layout.addView(hidden); layout.addView(last);
        measure(layout, 120);
        assertEquals(58, layout.getMeasuredHeight());
        assertEquals(7, last.getLeft()); assertEquals(21, last.getTop());
    }
    @Test void rightToLeftPlacesTheShortestColumnsFromTheRightEdge() {
        TestLayout layout = new TestLayout();
        layout.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        layout.setPadding(10, 0, 20, 0);
        View first = cell(100), second = cell(30), third = cell(40);
        layout.addView(first); layout.addView(second); layout.addView(third);
        measure(layout, 250);
        assertEquals(120, first.getLeft());
        assertEquals(10, second.getLeft()); assertEquals(10, third.getLeft());
        assertEquals(30, third.getTop()); assertEquals(100, layout.getMeasuredHeight());
    }
    private View cell(int height) {
        View view = new View(context);
        view.setLayoutParams(new LinearLayout.LayoutParams(-1, height));
        return view;
    }
    private static void measure(View layout, int width) {
        layout.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
        layout.layout(0, 0, width, layout.getMeasuredHeight());
    }
    private class TestLayout extends WaterfallLayout {
        TestLayout() { super(context); setRowMinWidth(100); }
        void detach() { onDetachedFromWindow(); }
    }
}
