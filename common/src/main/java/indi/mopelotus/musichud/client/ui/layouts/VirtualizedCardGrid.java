package indi.mopelotus.musichud.client.ui.layouts;

import icyllis.modernui.core.Context;
import icyllis.modernui.view.View;
import icyllis.modernui.view.ViewGroup;
import icyllis.modernui.widget.FrameLayout;
import icyllis.modernui.widget.LinearLayout;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/** Windows rows of existing upstream-style cards; offscreen cards release their own listeners/images. */
public class VirtualizedCardGrid<T> extends FrameLayout {
    private record Row<T>(long index, List<T> items) {}
    private final ViewportListLayout<Row<T>, LinearLayout> rows;
    private final int cellWidth;
    private List<T> items = List.of();
    private int columns = 1;

    public VirtualizedCardGrid(Context context, int cellWidthDp, int rowHeightDp, Function<T, View> createCard) {
        super(context);
        this.cellWidth = dp(cellWidthDp);
        rows = new ViewportListLayout<>(context, new VirtualizedListLayout.Adapter<>() {
            public long idOf(Row<T> row) { return row.index(); }
            public LinearLayout createItem(ViewGroup parent) {
                var view = new LinearLayout(context); view.setOrientation(LinearLayout.HORIZONTAL); return view;
            }
            public void clearItem(LinearLayout view) { view.removeAllViews(); view.setTag(null); }
            public void bindItem(LinearLayout view, Row<T> row) {
                view.removeAllViews(); view.setTag(row.index());
                for (T item : row.items()) {
                    View card = createCard.apply(item);
                    var params = new LinearLayout.LayoutParams(cellWidth, ViewGroup.LayoutParams.WRAP_CONTENT);
                    params.setMargins(0, 0, 0, dp(12));
                    view.addView(card, params);
                }
            }
            public long boundIdOf(LinearLayout view) { return view.getTag() instanceof Long id ? id : -1; }
        });
        rows.setDefaultItemHeight(dp(rowHeightDp));
        rows.setAnimationsEnabled(false);
        addView(rows, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
    }

    public void setItems(List<T> items) {
        this.items = items == null ? List.of() : List.copyOf(items);
        rebuild();
    }
    private void rebuild() {
        var data = new ArrayList<Row<T>>();
        for (int i = 0; i < items.size(); i += columns)
            data.add(new Row<>(i / columns, items.subList(i, Math.min(items.size(), i + columns))));
        rows.resetItems(data);
    }
    private final Runnable resizeColumns = this::resizeColumns;
    private void resizeColumns() {
        int nextColumns = Math.max(1, (getWidth() - getPaddingLeft() - getPaddingRight()) / cellWidth);
        if (columns != nextColumns) { columns = nextColumns; rebuild(); }
    }
    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        // Weighted/wrapped parents may measure several provisional widths in one pass.
        // Rebuild only after the actual layout width settles, never from onMeasure.
        if (width != oldWidth) { removeCallbacks(resizeColumns); post(resizeColumns); }
    }
    @Override protected void onDetachedFromWindow() {
        removeCallbacks(resizeColumns);
        super.onDetachedFromWindow();
    }
    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        post(resizeColumns);
    }
}
