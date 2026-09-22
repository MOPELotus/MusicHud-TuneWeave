package indi.mopelotus.musichud.client.ui.components;

import icyllis.modernui.core.Context;
import icyllis.modernui.view.MeasureSpec;
import icyllis.modernui.view.View;
import icyllis.modernui.view.ViewGroup;
import icyllis.modernui.widget.LinearLayout;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

import static icyllis.modernui.view.ViewGroup.LayoutParams.WRAP_CONTENT;

/** Upstream shortest-column layout; cached routes retain their children on detach. */
public class WaterfallLayout extends LinearLayout {
    private final List<View> allChildren = new ArrayList<>();
    private final List<LinearLayout> columns = new ArrayList<>();
    private final List<Integer> columnHeights = new ArrayList<>();
    private int rowMinWidth;
    private int measuredContentWidth;
    private boolean reflowPending = true;

    public WaterfallLayout(Context context) {
        super(context);
        setOrientation(HORIZONTAL);
    }

    public void setRowMinWidth(int width) {
        if (width <= 0) throw new IllegalArgumentException("Column minimum width must be positive");
        if (rowMinWidth != width) { rowMinWidth = width; reflow(); }
    }

    @Override public void addView(@NotNull View view) {
        if (allChildren.contains(view) || view.getParent() != null) throw new IllegalStateException("View already has a parent");
        allChildren.add(view);
        reflow();
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int width = Math.max(0, MeasureSpec.getSize(widthSpec) - getPaddingLeft() - getPaddingRight());
        if (rowMinWidth > 0 && width > 0 && (reflowPending || measuredContentWidth != width)) {
            measuredContentWidth = width;
            rebuildColumns(Math.max(1, width / rowMinWidth));
            reflowPending = false;
        }
        super.onMeasure(widthSpec, heightSpec);
    }

    private void rebuildColumns(int count) {
        for (LinearLayout column : columns) column.removeAllViews();
        super.removeAllViews();
        columns.clear();
        columnHeights.clear();
        for (int i = 0; i < count; i++) {
            LinearLayout column = new LinearLayout(getContext());
            column.setOrientation(VERTICAL);
            super.addView(column, new LayoutParams(0, WRAP_CONTENT, 1f));
            columns.add(column);
            columnHeights.add(0);
        }
        for (View child : allChildren) addToShortestColumn(child);
    }

    private void addToShortestColumn(View child) {
        int shortest = 0;
        for (int i = 1; i < columns.size(); i++) {
            if (columnHeights.get(i) < columnHeights.get(shortest)) shortest = i;
        }
        ViewGroup.LayoutParams params = child.getLayoutParams();
        int horizontalMargins = 0, verticalMargins = 0;
        if (params instanceof ViewGroup.MarginLayoutParams margins) {
            horizontalMargins = margins.leftMargin + margins.rightMargin;
            verticalMargins = margins.topMargin + margins.bottomMargin;
        }
        int width = Math.max(0, measuredContentWidth / columns.size() - horizontalMargins);
        int childWidth = params != null && params.width >= 0 ? Math.min(width, params.width) : width;
        child.measure(MeasureSpec.makeMeasureSpec(childWidth,
                        params != null && params.width == WRAP_CONTENT ? MeasureSpec.AT_MOST : MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(params != null && params.height >= 0 ? params.height : 0,
                        params != null && params.height >= 0 ? MeasureSpec.EXACTLY : MeasureSpec.UNSPECIFIED));
        int height = child.getMeasuredHeight() + verticalMargins;
        columns.get(shortest).addView(child);
        columnHeights.set(shortest, columnHeights.get(shortest) + height);
    }

    public void reflow() { reflowPending = true; requestLayout(); }

    @Override public void removeView(@NotNull View view) {
        if (!allChildren.remove(view)) return;
        for (LinearLayout column : columns) column.removeView(view);
        reflow();
    }

    @Override public void removeAllViews() {
        allChildren.clear();
        for (LinearLayout column : columns) column.removeAllViews();
        columns.clear();
        columnHeights.clear();
        super.removeAllViews();
        reflow();
    }
}
