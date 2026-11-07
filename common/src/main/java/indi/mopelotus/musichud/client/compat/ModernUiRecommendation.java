package indi.mopelotus.musichud.client.compat;

import icyllis.modernui.fragment.Fragment;
import icyllis.modernui.util.DataSet;
import icyllis.modernui.view.Gravity;
import icyllis.modernui.view.LayoutInflater;
import icyllis.modernui.view.View;
import icyllis.modernui.view.ViewGroup;
import icyllis.modernui.widget.Button;
import icyllis.modernui.widget.LinearLayout;
import icyllis.modernui.widget.TextView;
import icyllis.modernui.widget.ScrollView;
import indi.mopelotus.musichud.client.interfaces.IClientEventService;
import indi.mopelotus.musichud.client.ui.screen.MusicHudScreen;
import indi.mopelotus.musichud.interfaces.Unregister;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.resources.language.I18n;

/** A dismissible recommendation, shown once per startup to supported official hosts. */
public final class ModernUiRecommendation {
    private static boolean registered;
    private ModernUiRecommendation() {}

    public static void initialize() {
        if (registered || ModernUiHost.isLumen()) return;
        registered = true;
        var registration = new Unregister[1];
        registration[0] = IClientEventService.getInstance().registerClientTickPost(() -> {
            var client = Minecraft.getInstance();
            if (client.getOverlay() != null || !(client.screen instanceof TitleScreen)) return;
            registration[0].unregister();
            var previous = client.screen;
            var screen = MusicHudScreen.createScreen(new RecommendationFragment(), null, previous, tr("title"));
            client.setScreen(screen);
        });
    }

    private static String tr(String key) { return I18n.get("musichud_tuneweave.modernUiRecommendation." + key); }

    public static final class RecommendationFragment extends Fragment {
        public RecommendationFragment() {}

        private void withCurrentScreen(java.util.function.Consumer<MusicHudScreen> action) {
            var client = Minecraft.getInstance();
            client.execute(() -> {
                if (client.screen instanceof MusicHudScreen screen && screen.getFragment() == this) {
                    action.accept(screen);
                }
            });
        }

        @Override public View onCreateView(LayoutInflater inflater, ViewGroup container, DataSet state) {
            var context = requireContext();
            var layout = new LinearLayout(context);
            layout.setOrientation(LinearLayout.VERTICAL);
            layout.setGravity(Gravity.CENTER);
            int padding = layout.dp(24);
            layout.setPadding(padding, padding, padding, padding);
            var title = new TextView(context);
            title.setText(tr("title"));
            title.setTextSize(24);
            title.setGravity(Gravity.CENTER);
            layout.addView(title, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            var body = new TextView(context);
            body.setText(tr("body"));
            body.setTextSize(16);
            body.setMaxWidth(layout.dp(560));
            body.setGravity(Gravity.CENTER);
            var bodyParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            bodyParams.topMargin = padding;
            bodyParams.bottomMargin = padding;
            layout.addView(body, bodyParams);
            var download = new Button(context);
            download.setText(tr("download"));
            download.setOnClickListener(view -> withCurrentScreen(screen ->
                    net.minecraft.util.Util.getPlatform().openUri(ModernUiHost.DOWNLOAD)));
            layout.addView(download);
            var proceed = new Button(context);
            proceed.setText(tr("continue"));
            proceed.setOnClickListener(view -> withCurrentScreen(screen ->
                    Minecraft.getInstance().setScreen(screen.getPreviousScreen())));
            layout.addView(proceed);
            var scroll = new ScrollView(context);
            scroll.setFillViewport(true);
            scroll.addView(layout, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return scroll;
        }
    }
}
