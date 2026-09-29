package indi.mopelotus.musichud.client.ui.layouts;

import icyllis.modernui.core.Context;
import icyllis.modernui.view.View;
import icyllis.modernui.view.ViewTreeObserver;
import icyllis.modernui.widget.ScrollView;

/** Binds the existing virtual list to its containing scroll viewport, including header offsets. */
public class ViewportListLayout<T, V extends View> extends VirtualizedListLayout<T, V> {
    private int lastTop = Integer.MIN_VALUE, lastHeight = -1;
    private boolean viewportUpdatePending;
    private final Runnable applyViewport = () -> {
        viewportUpdatePending = false;
        if (isAttachedToWindow()) updateWindow(lastTop, lastHeight);
    };
    private final int[] listPosition = new int[2], scrollPosition = new int[2];
    private final ViewTreeObserver.OnPreDrawListener viewportListener = () -> {
        var parent = getParent();
        while (parent instanceof View view) {
            if (view instanceof ScrollView scroll) {
                getLocationInWindow(listPosition);
                scroll.getLocationInWindow(scrollPosition);
                int top = scrollPosition[1] - listPosition[1];
                int height = scroll.getHeight();
                if (top != lastTop || height != lastHeight) {
                    lastTop = top; lastHeight = height;
                    if (!viewportUpdatePending) {
                        viewportUpdatePending = true;
                        post(applyViewport);
                    }
                }
                break;
            }
            parent = view.getParent();
        }
        return true;
    };

    public ViewportListLayout(Context context, Adapter<T, V> adapter) { super(context, adapter); }
    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        lastTop = Integer.MIN_VALUE; lastHeight = -1;
        getViewTreeObserver().addOnPreDrawListener(viewportListener);
    }
    @Override protected void onDetachedFromWindow() {
        getViewTreeObserver().removeOnPreDrawListener(viewportListener);
        removeCallbacks(applyViewport);
        viewportUpdatePending = false;
        super.onDetachedFromWindow();
    }
}
