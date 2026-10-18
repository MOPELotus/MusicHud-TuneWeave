package indi.mopelotus.musichud.client.update;

import icyllis.modernui.core.Context;
import icyllis.modernui.widget.*;
import icyllis.modernui.view.View;
import icyllis.modernui.view.MeasureSpec;
import indi.mopelotus.musichud.MusicHud;
import indi.mopelotus.musichud.client.ui.Theme;
import indi.mopelotus.musichud.server.api.ApiServerFetcher;
import net.minecraft.client.resources.language.I18n;
import java.util.Arrays;

/** The same source choices are available for both direct update actions. */
final class DownloadSourceUi {
    private DownloadSourceUi() {}
    static Spinner create(Context context) {
        var spinner = new Spinner(context);
        var values = ApiServerFetcher.DownloadProxy.values();
        String[] labels = Arrays.stream(values).map(value -> I18n.get(MusicHud.MOD_ID + ".modal.downloadApiServer.proxy." + value.name())).toArray(String[]::new);
        spinner.setAdapter(new ArrayAdapter<>(context, labels));
        for (int i = 0; i < values.length; i++) if (values[i].name().equals("AUTO")) spinner.setSelection(i);
        return spinner;
    }
    static String selected(Spinner spinner) { return ApiServerFetcher.DownloadProxy.values()[spinner.getSelectedItemPosition()].name(); }
    static View content(View owner, TextView message, Spinner spinner) {
        Context context = owner.getContext();
        var content = new LinearLayout(context); content.setOrientation(LinearLayout.VERTICAL);
        content.addView(message);
        var label = new TextView(context);
        label.setText(I18n.get(MusicHud.MOD_ID + ".modal.downloadApiServer.proxy")); label.setTextSize(Theme.TEXT_SIZE_NORMAL);
        content.addView(label); content.addView(spinner);
        var scroll = new ScrollView(context) {
            @Override protected void onMeasure(int width, int height) {
                super.onMeasure(width, MeasureSpec.makeMeasureSpec(Math.max(dp(48), owner.getHeight() - dp(180)), MeasureSpec.AT_MOST));
            }
        };
        scroll.addView(content);
        return scroll;
    }
}
