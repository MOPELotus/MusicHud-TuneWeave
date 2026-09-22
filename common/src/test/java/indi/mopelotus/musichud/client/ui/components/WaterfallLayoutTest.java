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
        assertEquals(2, layout.getChildCount());
        assertEquals(1, ((LinearLayout) layout.getChildAt(0)).getChildCount());
        assertEquals(2, ((LinearLayout) layout.getChildAt(1)).getChildCount());
        layout.detach();
        measure(layout, 120);
        assertEquals(1, layout.getChildCount());
        assertEquals(3, ((LinearLayout) layout.getChildAt(0)).getChildCount());
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
