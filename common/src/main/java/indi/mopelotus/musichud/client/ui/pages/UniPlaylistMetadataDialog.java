package indi.mopelotus.musichud.client.ui.pages;

import icyllis.modernui.R;
import icyllis.modernui.core.Context;
import icyllis.modernui.view.Gravity;
import icyllis.modernui.widget.EditText;
import icyllis.modernui.widget.LinearLayout;
import icyllis.modernui.widget.TextView;
import indi.mopelotus.musichud.MusicHud;
import indi.mopelotus.musichud.client.ui.components.Modal;
import net.minecraft.client.resources.language.I18n;

import java.util.function.BiConsumer;

import static icyllis.modernui.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static icyllis.modernui.view.ViewGroup.LayoutParams.WRAP_CONTENT;

/** Edits the client-owned name and description of a local Uni Playlist. */
final class UniPlaylistMetadataDialog {
    private UniPlaylistMetadataDialog() {
    }

    static void show(Context context, String titleKey, String initialName, String initialDescription,
                     BiConsumer<String, String> onConfirm) {
        LinearLayout form = new LinearLayout(context);
        form.setOrientation(LinearLayout.VERTICAL);

        EditText name = new EditText(context, null, R.attr.editTextOutlinedStyle);
        name.setSingleLine(true);
        name.setHint(text(".text.uniPlaylist.metadataNameHint"));
        name.setText(initialName == null ? "" : initialName);
        form.addView(name, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));

        EditText description = new EditText(context, null, R.attr.editTextOutlinedStyle);
        description.setMinLines(3);
        description.setMaxLines(6);
        description.setGravity(Gravity.TOP | Gravity.LEFT);
        description.setHint(text(".text.uniPlaylist.descriptionHint"));
        description.setText(initialDescription == null ? "" : initialDescription);
        LinearLayout.LayoutParams descriptionParams = new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT);
        descriptionParams.setMargins(0, form.dp(8), 0, 0);
        form.addView(description, descriptionParams);

        TextView title = new TextView(context);
        title.setText(text(titleKey));
        new Modal(context, title, form,
                new Modal.ActionButton(text(".button.confirm"), (button, dialog) -> {
                    String playlistName = name.getText().toString().trim();
                    if (playlistName.isBlank()) return;
                    dialog.dismiss();
                    onConfirm.accept(playlistName, description.getText().toString().trim());
                }),
                new Modal.ActionButton(text(".button.cancel"),
                        (button, dialog) -> dialog.dismiss())).show();
    }

    private static String text(String suffix) {
        return I18n.get(MusicHud.MOD_ID + suffix);
    }
}
