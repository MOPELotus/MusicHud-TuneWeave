package indi.mopelotus.musichud.client.ui.pages;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ActiveViewReferenceTest {
    @Test
    void lateDetachFromOldViewDoesNotClearNewView() {
        ActiveViewReference<Object> activeView = new ActiveViewReference<>();
        Object oldView = new Object();
        Object newView = new Object();

        activeView.attach(oldView);
        activeView.attach(newView);

        assertFalse(activeView.detach(oldView));
        assertSame(newView, activeView.get());
        assertTrue(activeView.isCurrent(newView));
    }

    @Test
    void currentViewCanDetach() {
        ActiveViewReference<Object> activeView = new ActiveViewReference<>();
        Object view = new Object();

        activeView.attach(view);

        assertTrue(activeView.detach(view));
        assertFalse(activeView.isCurrent(view));
    }
}
