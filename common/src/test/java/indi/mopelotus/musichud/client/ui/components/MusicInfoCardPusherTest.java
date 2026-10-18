package indi.mopelotus.musichud.client.ui.components;

import icyllis.modernui.core.Context;
import icyllis.modernui.view.View;
import icyllis.modernui.widget.TextView;
import indi.mopelotus.musichud.beans.music.MusicDetail;
import indi.mopelotus.musichud.beans.music.PusherInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.*;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/** Runs the real card constructor, bind and clear methods with resource-free image/service endpoints. */
class MusicInfoCardPusherTest {
    private static final String ROOT = "indi.mopelotus.musichud.";
    private static final String COMPONENT = ROOT + "client.ui.components.";
    @TempDir Path fixtures;
    private final LayoutTestContext context = new LayoutTestContext();

    @Test void emptyAndBlankPushersRemoveTheWholeRowAcrossRealBindings() throws Exception {
        try (var e = environment()) {
            Object card = card(e);
            assertHidden(card);
            bind(card, music(PusherInfo.EMPTY)); assertHidden(card);
            bind(card, music(new PusherInfo(UUID.randomUUID(), "   "))); assertHidden(card);
            bind(card, music(new PusherInfo(new UUID(0, 0), "placeholder"))); assertHidden(card);
            bind(card, music(new PusherInfo(UUID.randomUUID(), "player")));
            assertEquals(View.VISIBLE, view(card, "getPusherRow").getVisibility());
            assertEquals("player", ((TextView) call(card, "getPusherText")).getText().toString());
            bind(card, MusicDetail.NONE); assertHidden(card);
            bind(card, music(new PusherInfo(UUID.randomUUID(), "again")));
            call(card, "clear"); assertHidden(card);
        }
    }

    @Test void oldSuppliersCannotResolveSkinAfterRapidClearAndRebind() throws Exception {
        try (var e = environment()) {
            Object card = card(e);
            UUID first = UUID.randomUUID(), second = UUID.randomUUID();
            bind(card, music(new PusherInfo(first, "first")));
            var old = supplier(card);
            assertNotNull(old.get());
            bind(card, music(PusherInfo.EMPTY));
            assertNull(old.get()); assertHidden(card);
            bind(card, music(new PusherInfo(second, "second")));
            assertNull(old.get());
            assertNotNull(supplier(card).get());
            assertEquals(second, e.loadClass(ROOT + "client.utils.PlayerInfoUtil").getField("last").get(null));
            call(card, "clear"); assertNull(old.get()); assertHidden(card);
        }
    }

    @Test void skinCompletionThatReentersAnEmptyBindCannotReviveThePusher() throws Exception {
        try (var e = environment()) {
            Object card = card(e);
            bind(card, music(new PusherInfo(UUID.randomUUID(), "player")));
            var old = supplier(card);
            e.loadClass(ROOT + "client.utils.PlayerInfoUtil").getField("duringResolve").set(null, (Runnable) () -> {
                try { bind(card, music(PusherInfo.EMPTY)); } catch (Exception ex) { throw new AssertionError(ex); }
            });
            assertNull(old.get());
            assertHidden(card);
        }
    }

    @Test void sharedWidgetHidesMissingSkinsAndRestoresThemWithoutChangingTheBoundPlayer() throws Exception {
        try (var e = environment()) {
            Object card = card(e);
            UUID player = UUID.randomUUID();
            bind(card, music(new PusherInfo(player, "player")));
            var lookup = e.loadClass(ROOT + "client.utils.PlayerInfoUtil");
            lookup.getField("missing").setBoolean(null, true);
            assertNull(supplier(card).get());
            assertEquals(View.GONE, view(card, "getPusherHeadView").getVisibility());
            assertEquals(View.VISIBLE, view(card, "getPusherRow").getVisibility());
            lookup.getField("missing").setBoolean(null, false);
            assertNotNull(supplier(card).get());
            assertEquals(View.VISIBLE, view(card, "getPusherHeadView").getVisibility());
            assertEquals(player, lookup.getField("last").get(null));
            assertTrue(((icyllis.modernui.view.ViewGroup)view(card, "getPusherRow")).getLayoutTransition()
                    .isTransitionTypeEnabled(icyllis.modernui.animation.LayoutTransition.CHANGING));
            bind(card, null); assertHidden(card);
        }
    }

    private MusicDetail music(PusherInfo info) {
        var detail = MusicDetail.fromTuneWeave(UUID.randomUUID().getLeastSignificantBits(), "qq:test", "video", "track", 3000, null, List.of());
        detail.setPusherInfo(info); return detail;
    }
    private Object card(StaggeredLyricLifecycleTest.Environment e) throws Exception {
        return e.loadClass(COMPONENT + "MusicInfoCard").getConstructor(Context.class, int.class).newInstance(context, 160);
    }
    private void bind(Object card, MusicDetail detail) throws Exception { card.getClass().getMethod("bind", MusicDetail.class, int.class).invoke(card, detail, 160); }
    private Object call(Object card, String name) throws Exception { return card.getClass().getMethod(name).invoke(card); }
    private View view(Object card, String getter) throws Exception { return (View) call(card, getter); }
    private Supplier<?> supplier(Object card) throws Exception { return (Supplier<?>) call(call(card, "getPusherHeadView"), "getPlayerSkinSupplier"); }
    private void assertHidden(Object card) throws Exception {
        assertEquals(View.GONE, view(card, "getPusherRow").getVisibility());
        assertEquals(View.GONE, view(card, "getPusherHeadView").getVisibility());
        assertEquals("", ((TextView) call(card, "getPusherText")).getText().toString());
        assertNull(supplier(card));
    }
    private StaggeredLyricLifecycleTest.Environment environment() throws Exception {
        Map<String, String> s = new LinkedHashMap<>();
        s.put(ROOT + "MusicHud", "public class MusicHud { public static final String ICON_BASE64=\"icon\", MOD_ID=\"musichud_tuneweave\"; public static org.apache.logging.log4j.Logger getLogger(Class<?> t) { return org.apache.logging.log4j.LogManager.getLogger(t); } }");
        s.put("net.minecraft.client.resources.language.I18n", "public class I18n { public static String get(String key, Object... values) { return key; } }");
        s.put("net.minecraft.resources.Identifier", "public record Identifier(String value) {}");
        s.put("net.minecraft.client.multiplayer.PlayerInfo", "public class PlayerInfo {}");
        s.put(ROOT + "client.utils.PlayerInfoUtil", """
                public class PlayerInfoUtil {
                    public static java.util.UUID last; public static Runnable duringResolve; public static boolean missing;
                    public static net.minecraft.client.multiplayer.PlayerInfo getPlayerInfoByUUID(java.util.UUID uuid) {
                        last=uuid; Runnable next=duringResolve; duringResolve=null; if(next!=null) next.run();
                        return new net.minecraft.client.multiplayer.PlayerInfo();
                    }
                    public static net.minecraft.resources.Identifier getPlayerSkin(net.minecraft.client.multiplayer.PlayerInfo info) { return missing ? null : new net.minecraft.resources.Identifier("skin"); }
                }
                """);
        s.put(COMPONENT + "PlayerHeadView", """
                public class PlayerHeadView extends icyllis.modernui.widget.FrameLayout {
                    private java.util.function.Supplier<net.minecraft.resources.Identifier> supplier;
                    public PlayerHeadView(icyllis.modernui.core.Context c) { super(c); }
                    public void setPlayerSkinSupplier(java.util.function.Supplier<net.minecraft.resources.Identifier> s) { supplier=s; if(s!=null) s.get(); }
                    public java.util.function.Supplier<net.minecraft.resources.Identifier> getPlayerSkinSupplier() { return supplier; }
                }
                """);
        s.put(COMPONENT + "UrlImageView", "public class UrlImageView extends icyllis.modernui.widget.FrameLayout { public UrlImageView(icyllis.modernui.core.Context c) { super(c); } public void loadUrl(String s) {} public void loadUrl(String s,int w,int h) {} public void clear() {} public void setCornerRadius(int r) {} }");
        s.put(COMPONENT + "PlaybackSourceLink", "public class PlaybackSourceLink extends icyllis.modernui.widget.Button { public PlaybackSourceLink(icyllis.modernui.core.Context c) { super(c); } public void bind(indi.mopelotus.musichud.beans.music.PlaybackSource s) {} }");
        s.put(COMPONENT + "ToggleTrackLikeStateButton", "public class ToggleTrackLikeStateButton extends icyllis.modernui.widget.Button { public ToggleTrackLikeStateButton(icyllis.modernui.core.Context c) { super(c); } public void bindMusicList(indi.mopelotus.musichud.beans.state.IMusicTrackState.IPlaylistSubState s) {} }");
        s.put(COMPONENT + "ModifyPlaylistTrackModalButton", "public class ModifyPlaylistTrackModalButton extends icyllis.modernui.widget.Button { public ModifyPlaylistTrackModalButton(icyllis.modernui.core.Context c) { super(c); } public void bindMusicDetail(indi.mopelotus.musichud.beans.music.MusicDetail s) {} }");
        s.put(COMPONENT + "VoteSkipButton", "public class VoteSkipButton extends icyllis.modernui.widget.Button { public VoteSkipButton(icyllis.modernui.core.Context c) { super(c); } public void reset() {} }");
        s.put(ROOT + "client.utils.image.PlatformIconUtils", "public class PlatformIconUtils { public static indi.mopelotus.musichud.server.api.tuneweave.TuneWeavePlatform platform(indi.mopelotus.musichud.beans.music.MusicDetail d) { return null; } public static icyllis.modernui.graphics.Image image(indi.mopelotus.musichud.server.api.tuneweave.TuneWeavePlatform p,int size) { return null; } }");
        s.put(ROOT + "client.utils.ui.InsetBackgroundFactory", """
                public class InsetBackgroundFactory {
                    public record Padding(int left,int top,int right,int bottom) {}
                    public static InsetBackgroundFactoryBuilder builder() { return new InsetBackgroundFactoryBuilder(); }
                    public void applyBackgroundTo(icyllis.modernui.view.View view) { view.setStateListAnimator(null); }
                    public static class InsetBackgroundFactoryBuilder {
                        public InsetBackgroundFactoryBuilder backgroundColor(icyllis.modernui.util.ColorStateList c) { return this; }
                        public InsetBackgroundFactoryBuilder padding(Padding p) { return this; }
                        public InsetBackgroundFactoryBuilder cornerRadius(int r) { return this; }
                        public InsetBackgroundFactoryBuilder inset(int r) { return this; }
                        public InsetBackgroundFactory build() { return new InsetBackgroundFactory(); }
                    }
                }
                """);
        s.put(ROOT + "client.audio.NowPlayingInfo", """
                public class NowPlayingInfo {
                    static final NowPlayingInfo INSTANCE=new NowPlayingInfo();
                    public indi.mopelotus.musichud.beans.music.MusicDetail detail=indi.mopelotus.musichud.beans.music.MusicDetail.NONE;
                    public static NowPlayingInfo getInstance() { return INSTANCE; }
                    public indi.mopelotus.musichud.beans.music.MusicDetail getCurrentlyPlayingMusicDetail() { return detail; }
                    public indi.mopelotus.musichud.client.ui.dto.LyricLine getCurrentLyricLine() { return null; }
                    public java.util.Set<java.util.function.Consumer<indi.mopelotus.musichud.client.ui.dto.LyricLine>> getLyricLineUpdateListener() { return java.util.Set.of(); }
                    public record PlaybackSnapshot(indi.mopelotus.musichud.beans.music.MusicDetail musicDetail,java.time.Duration duration,java.time.ZonedDateTime startedAt) {}
                    public PlaybackSnapshot snapshot() { return new PlaybackSnapshot(detail,java.time.Duration.ZERO,null); }
                }
                """);
        return new StaggeredLyricLifecycleTest.Environment(fixtures, context, s);
    }
}
