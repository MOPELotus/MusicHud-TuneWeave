package indi.mopelotus.musichud.client.companion;

import icyllis.modernui.R;
import icyllis.modernui.core.Context;
import icyllis.modernui.mc.ui.PreferencesFragment;
import icyllis.modernui.view.Gravity;
import icyllis.modernui.view.View;
import icyllis.modernui.widget.*;
import indi.mopelotus.musichud.client.ui.Theme;
import indi.mopelotus.musichud.client.ui.components.Modal;
import indi.mopelotus.musichud.client.utils.ui.InsetBackgroundFactory;
import net.minecraft.client.resources.language.I18n;

/** Same rows and modal lifecycle as the standard edition's music-service controls. */
final class CompanionManagementUi {
    private CompanionManagementUi() {}
    static String tr(String key) { return I18n.get("musichud_tuneweave.companion.management." + key); }
    static void create(LinearLayout category, LinearLayout statusRow) {
        Context context = category.getContext();
        var path = input(category, tr("executable"), CompanionDistribution.executable());
        boolean[] editingPath = {false};
        path.setOnFocusChangeListener((v, focused) -> { editingPath[0] = focused; if (!focused) CompanionDistribution.executable(path.getText().toString()); });
        var serviceRow = statusRow;
        TextView version = new TextView(context);
        version.setTextSize(14);
        serviceRow.addView(version);
        var download = button(serviceRow, "download", () -> download(context));
        var stop = button(serviceRow, "stop", CompanionDistribution::stop);
        var restart = button(serviceRow, "restart", () -> {
            CompanionDistribution.executable(path.getText().toString());
            CompanionDistribution.restart();
        });
        TextView error = new TextView(context);
        error.setTextSize(12);
        error.setTextColor(Theme.ERROR_TEXT_COLOR);
        LinearLayout.LayoutParams errorParams = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        errorParams.setMargins(category.dp(6), 0, category.dp(6), 0);
        category.addView(error, errorParams);
        Runnable refresh = new Runnable() {
            public void run() {
                if (!editingPath[0] && !path.getText().toString().equals(CompanionDistribution.executable())) path.setText(CompanionDistribution.executable());
                version.setText(CompanionDistribution.version().isBlank() ? "—" : CompanionDistribution.version());
                download.setEnabled(CompanionDistribution.supported() && !CompanionDistribution.busy());
                stop.setEnabled(CompanionDistribution.busy() || CompanionDistribution.running());
                restart.setText(tr(CompanionDistribution.running() ? "restart" : "start"));
                restart.setEnabled(CompanionDistribution.supported() && CompanionBridge.enabled());
                error.setText(CompanionDistribution.failure());
                error.setVisibility(CompanionDistribution.failure().isBlank() ? View.GONE : View.VISIBLE);
                serviceRow.postDelayed(this, 250);
            }
        };
        serviceRow.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            public void onViewAttachedToWindow(View view) { serviceRow.post(refresh); }
            public void onViewDetachedFromWindow(View view) { serviceRow.removeCallbacks(refresh); }
        });
    }
    private static void download(Context context) {
        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        TextView title = new TextView(context);
        title.setText(tr("downloadTitle"));
        title.setTextSize(Theme.TEXT_SIZE_LARGE);
        TextView description = new TextView(context);
        description.setText(tr("downloadDescription"));
        content.addView(description);
        button(content, "releases", () -> net.minecraft.Util.getPlatform().openUri(java.net.URI.create(CompanionReleaseInstaller.DOWNLOAD_PAGE)));
        TextView latest = new TextView(context);
        content.addView(latest);
        button(content, "check", CompanionDistribution::checkLatest);
        CompanionDistribution.checkLatest();
        var directory = input(content, tr("directory"), CompanionDistribution.directory().toString());
        Button select = button(content, "selectDirectory", () -> {
            String selected = org.lwjgl.util.tinyfd.TinyFileDialogs.tinyfd_selectFolderDialog(tr("directory"), directory.getText().toString());
            if (selected != null) directory.setText(selected);
        });
        TextView progress = new TextView(context);
        content.addView(progress);
        final Modal.ActionButton[] actionHolder = {null};
        Runnable refresh = new Runnable() {
            public void run() {
                if (actionHolder[0] != null) actionHolder[0].setText(tr(CompanionDistribution.busy() ? "cancel" : "download"));
                latest.setText(tr("latest") + " " + (CompanionDistribution.latestVersion().isBlank() ? "—" : CompanionDistribution.latestVersion()));
                long total = CompanionDistribution.total(), done = CompanionDistribution.downloaded();
                progress.setText(CompanionDistribution.busy() ? tr("working") + " " + done / 1024 + " KiB" + (total > 0 ? " / " + total / 1024 + " KiB" : "") : CompanionDistribution.failure());
                content.postDelayed(this, 200);
            }
        };
        var action = new Modal.ActionButton(tr("download"), (button, modal) -> {
            if (CompanionDistribution.busy()) CompanionDistribution.stop();
            else {
                if (CompanionDistribution.directory(directory.getText().toString())) CompanionDistribution.download();
            }
        });
        actionHolder[0] = action;
        Modal modal = new Modal(context, title, content, action, new Modal.ActionButton(tr("close"), (button, dialog) -> dialog.dismiss()));
        content.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            public void onViewAttachedToWindow(View view) { content.post(refresh); }
            public void onViewDetachedFromWindow(View view) { content.removeCallbacks(refresh); }
        });
        modal.show();
    }
    private static EditText input(LinearLayout parent, String title, String value) {
        LinearLayout box = PreferencesFragment.createInputBox(parent.getContext(), title);
        box.setOrientation(LinearLayout.VERTICAL);
        for (int i = 0; i < box.getChildCount(); i++) {
            View child = box.getChildAt(i);
            LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) child.getLayoutParams();
            params.width = LinearLayout.LayoutParams.MATCH_PARENT;
            params.weight = 0;
            child.setLayoutParams(params);
        }
        EditText input = box.findViewById(R.id.input);
        input.setMinimumWidth(0);
        input.setSingleLine(true);
        input.setText(value);
        parent.addView(box);
        return input;
    }
    private static LinearLayout row(LinearLayout parent) {
        var row = new LinearLayout(parent.getContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        var params = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, parent.dp(44));
        params.setMargins(parent.dp(6), 0, parent.dp(6), 0);
        parent.addView(row, params);
        return row;
    }
    private static Button button(LinearLayout row, String key, Runnable click) {
        var button = new Button(row.getContext());
        button.setText(tr(key));
        button.setTextSize(14);
        button.setTextColor(Theme.PRIMARY_COLOR);
        InsetBackgroundFactory.builder().inset(0).padding(new InsetBackgroundFactory.Padding(row.dp(8), row.dp(4), row.dp(8), row.dp(4))).build().applyBackgroundTo(button);
        button.setOnClickListener(v -> click.run());
        row.addView(button);
        return button;
    }
}
