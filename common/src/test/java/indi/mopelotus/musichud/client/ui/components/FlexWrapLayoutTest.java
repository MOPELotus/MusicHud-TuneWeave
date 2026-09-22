package indi.mopelotus.musichud.client.ui.components;

import icyllis.modernui.core.Context;
import icyllis.modernui.view.MeasureSpec;
import icyllis.modernui.view.View;
import icyllis.modernui.view.ViewGroup;
import icyllis.modernui.widget.LinearLayout;
import org.junit.jupiter.api.Test;

import static icyllis.modernui.view.ViewGroup.LayoutParams.*;
import static org.junit.jupiter.api.Assertions.*;

class FlexWrapLayoutTest {
    private final Context context = new LayoutTestContext();

    @Test void paddingAndMarginsAreIncludedExactlyOnce() {
        var layout = layout();
        layout.setPadding(7, 11, 13, 17);
        View a = child(40, 10), b = child(40, 10);
        var params = new ViewGroup.MarginLayoutParams(40, 10);
        params.setMargins(3, 5, 4, 6);
        layout.addView(a, params);
        layout.addView(b, new ViewGroup.MarginLayoutParams(40, 10));
        measureAndLayout(layout, 100);
        assertEquals(10, a.getLeft());
        assertEquals(16, a.getTop());
        assertEquals(32, b.getTop());
        assertEquals(59, layout.getMeasuredHeight());
        assertTrue(a.getRight() + 4 <= 87);
    }

    @Test void fixedDimensionsAndWeightedMarginsDoNotOverflowTheLine() {
        var layout = layout();
        View fixed = child(5, 5), weighted = child(5, 5), weighted2 = child(5, 5);
        layout.addView(fixed, new LinearLayout.LayoutParams(30, 20));
        var first = new LinearLayout.LayoutParams(0, 20, 1);
        first.setMargins(5, 0, 5, 0);
        var second = new LinearLayout.LayoutParams(0, 20, 1);
        second.setMargins(5, 0, 5, 0);
        layout.addView(weighted, first);
        layout.addView(weighted2, second);
        measureAndLayout(layout, 101);
        assertEquals(30, fixed.getWidth());
        assertEquals(20, fixed.getHeight());
        assertEquals(25, weighted.getWidth());
        assertEquals(26, weighted2.getWidth());
        assertEquals(101, weighted2.getRight() + 5);
    }

    @Test void matchParentHeightIsMeasuredAgainstItsLine() {
        var layout = layout();
        View tall = child(30, 40), fill = child(20, 5);
        layout.addView(tall, new ViewGroup.MarginLayoutParams(30, 40));
        var params = new ViewGroup.MarginLayoutParams(20, MATCH_PARENT);
        params.setMargins(0, 3, 0, 7);
        layout.addView(fill, params);
        measureAndLayout(layout, 100);
        assertEquals(30, fill.getMeasuredHeight());
        assertEquals(3, fill.getTop());
    }

    @Test void repeatedResizeAndDetachKeepTheSameChildrenInTheirCorrectFrames() {
        var layout = layout();
        View a = child(40, 20), b = child(40, 20);
        layout.addView(a); layout.addView(b);
        measureAndLayout(layout, 60);
        assertEquals(20, b.getTop());
        measureAndLayout(layout, 100);
        assertEquals(0, b.getTop());
        assertEquals(40, b.getLeft());
        layout.onDetachedFromWindow();
        assertEquals(2, layout.getChildCount());
        measureAndLayout(layout, 60);
        assertSame(a, layout.getChildAt(0));
        assertSame(b, layout.getChildAt(1));
        assertEquals(20, b.getTop());
        assertEquals(1f, b.getAlpha());
        assertEquals(0f, b.getTranslationY());
    }

    private FlexWrapLayout layout() {
        var layout = new FlexWrapLayout(context);
        layout.setAnimationsEnabled(false);
        return layout;
    }

    private View child(int width, int height) {
        return new View(context) {
            @Override protected void onMeasure(int w, int h) {
                setMeasuredDimension(resolveSize(width, w), resolveSize(height, h));
            }
        };
    }

    private static void measureAndLayout(View layout, int width) {
        layout.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
        layout.layout(0, 0, layout.getMeasuredWidth(), layout.getMeasuredHeight());
    }
}
