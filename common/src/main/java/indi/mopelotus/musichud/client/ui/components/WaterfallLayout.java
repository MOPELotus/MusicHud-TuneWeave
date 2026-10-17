package indi.mopelotus.musichud.client.ui.components;

import icyllis.modernui.core.Context;
import icyllis.modernui.view.MeasureSpec;
import icyllis.modernui.view.View;
import icyllis.modernui.view.ViewGroup;
import icyllis.modernui.widget.LinearLayout;
import java.util.ArrayList;
import java.util.List;

/** Shortest-column placement without reparenting rows during measurement or resize. */
public class WaterfallLayout extends LinearLayout {
    private record Cell(View child, int offset, int top, int rightMargin, int leftMargin) {}
    private final List<Cell> cells = new ArrayList<>();
    private int rowMinWidth;

    public WaterfallLayout(Context context) {
        super(context);
        setOrientation(HORIZONTAL);
    }

    public void setRowMinWidth(int width) {
        if (width <= 0) throw new IllegalArgumentException("Column minimum width must be positive");
        if (rowMinWidth != width) { rowMinWidth = width; reflow(); }
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec);
        if (MeasureSpec.getMode(widthSpec) == MeasureSpec.UNSPECIFIED && width == 0) {
            width = Math.max(getSuggestedMinimumWidth(), Math.max(1, rowMinWidth) + getPaddingLeft() + getPaddingRight());
        }
        int available = Math.max(0, width - getPaddingLeft() - getPaddingRight());
        int count = rowMinWidth > 0 ? Math.max(1, available / rowMinWidth) : 1;
        int[] heights = new int[count];
        int columnWidth = available / count, remainder = available % count;
        cells.clear();
        int contentHeight = 0;
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child.getVisibility() == GONE) continue;
            int column = 0;
            for (int j = 1; j < count; j++) if (heights[j] < heights[column]) column = j;
            var params = child.getLayoutParams();
            int left = 0, top = 0, right = 0, bottom = 0;
            if (params instanceof ViewGroup.MarginLayoutParams margins) {
                left = margins.leftMargin; top = margins.topMargin;
                right = margins.rightMargin; bottom = margins.bottomMargin;
            }
            int space = Math.max(0, columnWidth + (column < remainder ? 1 : 0) - left - right);
            int childWidth = params.width >= 0 ? Math.min(space, params.width) : space;
            child.measure(MeasureSpec.makeMeasureSpec(childWidth,
                            params.width == LayoutParams.WRAP_CONTENT ? MeasureSpec.AT_MOST : MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(params.height >= 0 ? params.height : 0,
                            params.height >= 0 ? MeasureSpec.EXACTLY : MeasureSpec.UNSPECIFIED));
            int offset = column * columnWidth + Math.min(column, remainder);
            cells.add(new Cell(child, offset, getPaddingTop() + heights[column] + top, right, left));
            heights[column] += child.getMeasuredHeight() + top + bottom;
            contentHeight = Math.max(contentHeight, heights[column]);
        }
        setMeasuredDimension(resolveSize(width, widthSpec),
                resolveSize(contentHeight + getPaddingTop() + getPaddingBottom(), heightSpec));
    }

    @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        for (Cell cell : cells) {
            View child = cell.child();
            if (child.getParent() != this || child.getVisibility() == GONE) continue;
            int x = isLayoutRtl() ? right - left - getPaddingRight() - cell.offset() - cell.rightMargin() - child.getMeasuredWidth()
                    : getPaddingLeft() + cell.offset() + cell.leftMargin();
            child.layout(x, cell.top(), x + child.getMeasuredWidth(), cell.top() + child.getMeasuredHeight());
        }
    }

    public void reflow() { requestLayout(); }
}
