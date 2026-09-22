package indi.mopelotus.musichud.client.ui.components;

import icyllis.modernui.view.View;
import org.junit.jupiter.api.Test;
import java.util.ArrayDeque;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class RouterContainerTest {
    private final LayoutTestContext context = new LayoutTestContext();

    @Test void reselectingIncomingRootCancelsTheQueuedThirdRootWithoutConstructingIt() {
        var router = new Router();
        var third = new AtomicInteger();
        router.registerPage("a", View::new);
        router.registerPage("b", View::new);
        router.registerPage("c", c -> { third.incrementAndGet(); return new View(c); });
        router.navigateToRoot("a"); router.drain();
        View first = router.getCurrentPage();
        router.navigateToRoot("b");
        router.navigateToRoot("c");
        router.navigateToRoot("b");
        router.drain();
        assertEquals("b", router.getCurrentPageKey());
        assertEquals(0, third.get());
        assertEquals(View.GONE, first.getVisibility());
        assertEquals(1f, router.getCurrentPage().getAlpha());
    }

    @Test void clearingCacheInvalidatesAlreadyPostedEntryAndKeepsTheNewRoute() {
        var router = new Router();
        router.registerPage("a", View::new);
        router.registerPage("b", View::new);
        router.navigateToRoot("a");
        router.clearAllPageCache();
        router.navigateToRoot("b");
        router.drain();
        assertEquals("b", router.getCurrentPageKey());
        assertEquals(1, router.getChildCount());
        assertSame(router.getCurrentPage(), router.getChildAt(0));
    }

    @Test void rapidPushThenPopSettlesOnCachedParentAndReleasesDynamicPage() {
        var router = new Router();
        router.registerPage("a", View::new);
        router.navigateToRoot("a"); router.drain();
        View first = router.getCurrentPage(), detail = new View(context);
        router.pushNavigate(detail, RouterContainer.AnimationStyle.NONE);
        router.popNavigate(RouterContainer.AnimationStyle.NONE);
        router.drain();
        assertEquals("a", router.getCurrentPageKey());
        assertSame(first, router.getCurrentPage());
        assertNull(detail.getParent());
        assertEquals(1f, first.getAlpha());
    }

    private final class Router extends RouterContainer {
        final ArrayDeque<Runnable> frames = new ArrayDeque<>();
        Router() { super(context); setAnimationStyle(AnimationStyle.NONE); }
        @Override void deferEnter(Runnable action) { frames.addLast(action); }
        void drain() { for (int i = 0; !frames.isEmpty() && i < 30; i++) frames.removeFirst().run(); assertTrue(frames.isEmpty()); }
    }
}
