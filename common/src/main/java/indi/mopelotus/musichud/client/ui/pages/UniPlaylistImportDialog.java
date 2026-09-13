package indi.mopelotus.musichud.client.ui.pages;

import icyllis.modernui.R;
import indi.mopelotus.musichud.client.ui.ScopedViewTasks;
import indi.mopelotus.musichud.client.ui.ClientViewTasks;
import icyllis.modernui.core.Context;
import icyllis.modernui.graphics.Image;
import icyllis.modernui.graphics.drawable.InsetDrawable;
import icyllis.modernui.mc.MuiModApi;
import icyllis.modernui.view.Gravity;
import icyllis.modernui.view.View;
import icyllis.modernui.widget.ArrayAdapter;
import icyllis.modernui.widget.CheckBox;
import icyllis.modernui.widget.EditText;
import icyllis.modernui.widget.ImageButton;
import icyllis.modernui.widget.ImageView;
import icyllis.modernui.widget.LinearLayout;
import icyllis.modernui.widget.ScrollView;
import icyllis.modernui.widget.Spinner;
import icyllis.modernui.widget.TextView;
import indi.mopelotus.musichud.MusicHud;
import indi.mopelotus.musichud.beans.api.SearchType;
import indi.mopelotus.musichud.beans.music.Playlist;
import indi.mopelotus.musichud.client.services.tuneweave.TuneWeaveClientService;
import indi.mopelotus.musichud.client.services.tuneweave.TuneWeaveUniImportSource;
import indi.mopelotus.musichud.client.services.tuneweave.TuneWeaveUniPlaylist;
import indi.mopelotus.musichud.client.ui.Theme;
import indi.mopelotus.musichud.client.ui.components.Modal;
import indi.mopelotus.musichud.client.ui.components.PlatformSelector;
import indi.mopelotus.musichud.client.ui.drawable.ScaledImageDrawable;
import indi.mopelotus.musichud.client.utils.image.ImageUtils;
import indi.mopelotus.musichud.client.utils.ui.ButtonInsetBackgroundFactory;
import indi.mopelotus.musichud.server.api.tuneweave.TuneWeavePlatform;
import net.minecraft.client.resources.language.I18n;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static icyllis.modernui.view.View.GONE;
import static icyllis.modernui.view.View.VISIBLE;
import static icyllis.modernui.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static icyllis.modernui.view.ViewGroup.LayoutParams.WRAP_CONTENT;

/** Selects multiple collections across platforms and materializes them into a local Uni Playlist. */
final class UniPlaylistImportDialog {
    private static final TuneWeaveClientService TUNE_WEAVE = TuneWeaveClientService.getInstance();
    private static final String[] SOURCE_TYPES = {"playlist", "favorite_tracks", "season", "favorite_folder"};

    private UniPlaylistImportDialog() {
    }

    static void show(Context context, TuneWeaveUniPlaylist target,
                     Runnable onImported, Consumer<String> onError) {
        new Picker(context, target, onImported, onError).show();
    }

    static void showCreate(Context context, Runnable onImported, Consumer<String> onError) {
        new Picker(context, null, onImported, onError).show();
    }

    private static final class Picker {
        private final Context context;
        private final TuneWeaveUniPlaylist target;
        private final Runnable onImported;
        private final Consumer<String> onError;
        private final LinkedHashMap<String, SelectedSource> selectedSources = new LinkedHashMap<>();
        private final ScopedViewTasks tasks = ClientViewTasks.create();

        private final LinearLayout form;
        private final EditText name;
        private final EditText description;
        private final Spinner sourceMode;
        private final PlatformSelector platform;
        private final Spinner type;
        private final LinearLayout inputRow;
        private final EditText input;
        private final ImageButton inputAction;
        private final LinearLayout choices;
        private final TextView status;
        private final TextView selectedStatus;

        private Picker(Context context, TuneWeaveUniPlaylist target,
                       Runnable onImported, Consumer<String> onError) {
            this.context = context;
            this.target = target;
            this.onImported = onImported;
            this.onError = onError;

            form = new LinearLayout(context);
            form.setOrientation(LinearLayout.VERTICAL);

            name = new EditText(context, null, R.attr.editTextOutlinedStyle);
            description = new EditText(context, null, R.attr.editTextOutlinedStyle);
            if (target == null) addMetadataInputs();

            sourceMode = new Spinner(context);
            sourceMode.setAdapter(new ArrayAdapter<>(context, new String[]{
                    text(".text.uniPlaylist.source.account"),
                    text(".text.uniPlaylist.source.search"),
                    text(".text.uniPlaylist.source.manual")
            }));
            form.addView(sourceMode, fullWidth());

            platform = new PlatformSelector(context, TuneWeavePlatform.values());
            platform.setSelectedPlatform(TUNE_WEAVE.defaultPlatform());
            LinearLayout.LayoutParams platformParams = fullWidth();
            platformParams.setMargins(0, dp(8), 0, dp(8));
            form.addView(platform, platformParams);

            type = new Spinner(context);
            type.setAdapter(new ArrayAdapter<>(context, new String[]{
                    text(".uniPlaylist.source.playlist"),
                    text(".uniPlaylist.source.favoriteTracks"),
                    text(".uniPlaylist.source.season"),
                    text(".uniPlaylist.source.favoriteFolder")
            }));
            form.addView(type, fullWidth());

            inputRow = new LinearLayout(context);
            inputRow.setGravity(Gravity.CENTER_VERTICAL);
            input = new EditText(context, null, R.attr.editTextOutlinedStyle);
            input.setSingleLine(true);
            inputRow.addView(input, new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1));
            inputAction = commandButton("search.png", ".button.searchMusic", view -> onInputAction());
            LinearLayout.LayoutParams inputActionParams = new LinearLayout.LayoutParams(dp(36), dp(36));
            inputActionParams.setMargins(dp(6), 0, 0, 0);
            inputRow.addView(inputAction, inputActionParams);
            form.addView(inputRow, fullWidth());

            ScrollView choicesScroll = new ScrollView(context);
            choicesScroll.setFillViewport(true);
            choices = new LinearLayout(context);
            choices.setOrientation(LinearLayout.VERTICAL);
            choicesScroll.addView(choices, fullWidth());
            LinearLayout.LayoutParams choicesParams = new LinearLayout.LayoutParams(MATCH_PARENT, dp(220));
            choicesParams.setMargins(0, dp(8), 0, 0);
            form.addView(choicesScroll, choicesParams);

            status = new TextView(context);
            status.setTextSize(Theme.TEXT_SIZE_SMALL);
            status.setTextColor(Theme.SECONDARY_TEXT_COLOR);
            form.addView(status, fullWidth());

            selectedStatus = new TextView(context);
            selectedStatus.setTextSize(Theme.TEXT_SIZE_SMALL);
            selectedStatus.setTextColor(Theme.PRIMARY_COLOR);
            form.addView(selectedStatus, fullWidth());
            updateSelectedStatus();
        }

        private void addMetadataInputs() {
            name.setSingleLine(true);
            name.setHint(text(".text.uniPlaylist.nameHint"));
            form.addView(name, fullWidth());

            description.setMinLines(2);
            description.setMaxLines(4);
            description.setGravity(Gravity.TOP | Gravity.LEFT);
            description.setHint(text(".text.uniPlaylist.descriptionHint"));
            LinearLayout.LayoutParams descriptionParams = fullWidth();
            descriptionParams.setMargins(0, dp(8), 0, dp(8));
            form.addView(description, descriptionParams);
        }

        private void show() {
            sourceMode.setOnItemSelectedListener((parent, view, position, id) -> updateMode());
            platform.setOnPlatformSelectedListener(selected -> updatePlatform());
            form.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
                @Override public void onViewAttachedToWindow(View view) { tasks.attach(); updateMode(); }
                @Override public void onViewDetachedFromWindow(View view) { tasks.detach(); }
            });

            TextView title = new TextView(context);
            title.setText(text(target == null
                    ? ".text.uniPlaylist.importSource" : ".text.uniPlaylist.addPlatformPlaylists"));
            Modal modal = new Modal(context, title, form,
                    new Modal.ActionButton(text(target == null ? ".button.import" : ".button.add"),
                            (button, dialog) -> importSelected(button, dialog)),
                    new Modal.ActionButton(text(".button.cancel"),
                            (button, dialog) -> dialog.dismiss()));
            modal.show();
        }

        private void updateMode() {
            int mode = sourceMode.getSelectedItemPosition();
            if (mode != 0) tasks.<Boolean>load(progress -> true, ignored -> inputAction.setEnabled(true), error -> status.setText(message(error)));
            type.setVisibility(mode == 2 ? VISIBLE : GONE);
            inputRow.setVisibility(mode == 0 ? GONE : VISIBLE);
            if (mode == 1) {
                input.setHint(text(".field.hint.searchMusic"));
                setInputAction("search.png", ".button.searchMusic");
                choices.removeAllViews();
                status.setText(text(".text.uniPlaylist.searchPrompt"));
            } else if (mode == 2) {
                input.setHint(text(".text.uniPlaylist.sourceIdHint"));
                setInputAction("list_plus.png", ".button.add");
                renderSelectedSources();
            } else {
                loadAccountPlaylists();
            }
        }

        private void updatePlatform() {
            int mode = sourceMode.getSelectedItemPosition();
            if (mode != 0) tasks.<Boolean>load(progress -> true, ignored -> inputAction.setEnabled(true), error -> status.setText(message(error)));
            if (mode == 0) loadAccountPlaylists();
            else if (mode == 1) {
                choices.removeAllViews();
                status.setText(text(".text.uniPlaylist.searchPrompt"));
            } else renderSelectedSources();
        }

        private void onInputAction() {
            if (sourceMode.getSelectedItemPosition() == 1) search();
            else if (sourceMode.getSelectedItemPosition() == 2) addManualSource();
        }

        private void loadAccountPlaylists() {
            TuneWeavePlatform selected = platform.getSelectedPlatform();
            choices.removeAllViews(); status.setText(text(".text.platformPlaylist.loading"));
            tasks.<List<Playlist>>load(progress -> {
                var result = TUNE_WEAVE.loadAccountPlaylists(selected, false, values -> progress.accept(accountChoices(values)));
                return accountChoices(result);
            }, values -> renderPlaylists(values, selected), error -> status.setText(message(error)));
        }

        private static List<Playlist> accountChoices(indi.mopelotus.musichud.beans.music.UserCategoryPlaylists collections) {
            var result = new LinkedHashMap<String, Playlist>();
            if (collections.getLikeList() != null && collections.getLikeList().getId() >= 0)
                result.put(collections.getLikeList().getSourceRef(), collections.getLikeList());
            collections.getCreatedPlaylist().forEach(value -> result.put(value.getSourceRef(), value));
            collections.getSubscribedPlaylist().forEach(value -> result.put(value.getSourceRef(), value));
            return List.copyOf(result.values());
        }

        @SuppressWarnings("unchecked")
        private void search() {
            String query = input.getText().toString().trim();
            if (query.isBlank()) return;
            TuneWeavePlatform selected = platform.getSelectedPlatform();
            inputAction.setEnabled(false);
            status.setText(text(".text.uniPlaylist.searching"));
            tasks.<List<Playlist>>load(progress -> (List<Playlist>) TUNE_WEAVE.search(query, SearchType.PLAYLIST, 0, selected),
                    values -> { inputAction.setEnabled(true); renderPlaylists(values, selected); },
                    error -> { inputAction.setEnabled(true); status.setText(message(error)); });
        }

        private void renderPlaylists(List<Playlist> playlists, TuneWeavePlatform selectedPlatform) {
            choices.removeAllViews();
            if (playlists.isEmpty()) {
                status.setText(text(sourceMode.getSelectedItemPosition() == 0
                        ? ".text.platformPlaylist.empty" : ".text.searchNoMoreResult"));
                return;
            }
            status.setText(text(".text.uniPlaylist.selectMultiple"));
            for (Playlist playlist : playlists) {
                TuneWeaveUniImportSource source = source(playlist, selectedPlatform);
                String key = key(source);
                String label = playlist.getName() + " (" + playlist.getMusicTrackCount() + ")";
                CheckBox choice = choice(label, selectedSources.containsKey(key));
                choice.setOnCheckedChangeListener((button, checked) -> {
                    if (checked) selectedSources.put(key, new SelectedSource(source, label));
                    else selectedSources.remove(key);
                    updateSelectedStatus();
                });
                choices.addView(choice, fullWidth());
            }
        }

        private void addManualSource() {
            String value = input.getText().toString().trim();
            if (value.isBlank()) return;
            TuneWeavePlatform selectedPlatform = platform.getSelectedPlatform();
            TuneWeaveUniImportSource source = new TuneWeaveUniImportSource(
                    selectedPlatform.apiName(), SOURCE_TYPES[type.getSelectedItemPosition()], value);
            selectedSources.put(key(source), new SelectedSource(source,
                    text(".platform." + selectedPlatform.apiName()) + " · " + value));
            input.setText("");
            updateSelectedStatus();
            renderSelectedSources();
        }

        private void renderSelectedSources() {
            choices.removeAllViews();
            status.setText(selectedSources.isEmpty() ? text(".text.uniPlaylist.manualPrompt") : "");
            List<Map.Entry<String, SelectedSource>> values = new ArrayList<>(selectedSources.entrySet());
            for (Map.Entry<String, SelectedSource> entry : values) {
                CheckBox choice = choice(entry.getValue().label(), true);
                choice.setOnCheckedChangeListener((button, checked) -> {
                    if (!checked) {
                        selectedSources.remove(entry.getKey());
                        updateSelectedStatus();
                        renderSelectedSources();
                    }
                });
                choices.addView(choice, fullWidth());
            }
        }

        private CheckBox choice(String label, boolean checked) {
            CheckBox choice = new CheckBox(context);
            choice.setText(label);
            choice.setTextSize(Theme.TEXT_SIZE_NORMAL);
            choice.setTextColor(Theme.NORMAL_TEXT_COLOR);
            choice.setGravity(Gravity.CENTER_VERTICAL);
            choice.setMinHeight(dp(36));
            choice.setChecked(checked);
            return choice;
        }

        private void importSelected(Modal.ActionButton button, Modal dialog) {
            if (sourceMode.getSelectedItemPosition() == 2
                    && !input.getText().toString().trim().isBlank()) {
                addManualSource();
            }
            if (selectedSources.isEmpty()) {
                if (sourceMode.getSelectedItemPosition() == 1) search();
                else status.setText(text(".text.uniPlaylist.selectMultiple"));
                return;
            }
            List<TuneWeaveUniImportSource> sources = selectedSources.values().stream()
                    .map(SelectedSource::source).limit(50).toList();
            String requestedName = target == null ? name.getText().toString().trim() : "";
            String requestedDescription = target == null ? description.getText().toString().trim() : "";
            if (!tasks.canMutate()) return;
            button.setEnabled(false);
            status.setText(text(".text.uniPlaylist.importing"));
            tasks.mutate(tasks.capture(), () -> {
                if (target == null) TUNE_WEAVE.importUniPlaylistSources(requestedName, requestedDescription, sources);
                else TUNE_WEAVE.appendUniPlaylistSources(target.reference(), sources);
            }, () -> { dialog.dismiss(); onImported.run(); }, error -> {
                button.setEnabled(true); status.setText(message(error)); onError.accept(message(error));
            });
        }

        private void updateSelectedStatus() {
            selectedStatus.setText(text(".text.uniPlaylist.selectedCount")
                    .replace("{}", Integer.toString(selectedSources.size())));
        }

        private void setInputAction(String icon, String labelKey) {
            String label = text(labelKey);
            inputAction.setTooltipText(label);
            inputAction.setContentDescription(label);
            Image image = ImageUtils.getImageFromResource("/assets/musichud_tuneweave/textures/gui/icons/" + icon);
            inputAction.setImageDrawable(image == null ? null : new InsetDrawable(new ScaledImageDrawable(
                    context.getResources(), image, dp(16), dp(16)), dp(5)));
        }

        private ImageButton commandButton(String icon, String labelKey, View.OnClickListener listener) {
            ImageButton button = new ImageButton(context);
            button.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            button.setBackground(ButtonInsetBackgroundFactory.builder()
                    .cornerRadius(dp(4)).inset(dp(1)).build().newBackgroundDrawable());
            button.setOnClickListener(listener);
            inputActionImage(button, icon, labelKey);
            return button;
        }

        private void inputActionImage(ImageButton button, String icon, String labelKey) {
            String label = text(labelKey);
            button.setTooltipText(label);
            button.setContentDescription(label);
            Image image = ImageUtils.getImageFromResource("/assets/musichud_tuneweave/textures/gui/icons/" + icon);
            if (image != null) {
                button.setImageDrawable(new InsetDrawable(new ScaledImageDrawable(
                        context.getResources(), image, dp(16), dp(16)), dp(5)));
            }
        }

        private LinearLayout.LayoutParams fullWidth() {
            return new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT);
        }

        private int dp(int value) {
            return form.dp(value);
        }
    }

    private static TuneWeaveUniImportSource source(Playlist playlist,
                                                   TuneWeavePlatform platform) {
        String id = referenceId(playlist.getSourceRef(), platform);
        String type = "playlist";
        if (platform == TuneWeavePlatform.BILIBILI && id != null) {
            if (id.startsWith("favorite:")) type = "favorite_folder";
            if (id.startsWith("season:")) type = "season";
            int separator = id.indexOf(':');
            if (separator >= 0 && separator + 1 < id.length()) id = id.substring(separator + 1);
        }
        return new TuneWeaveUniImportSource(platform.apiName(), type, id);
    }

    private static String referenceId(String reference, TuneWeavePlatform platform) {
        String prefix = platform.apiName() + ':';
        return reference != null && reference.startsWith(prefix)
                ? reference.substring(prefix.length()) : reference;
    }

    private static String key(TuneWeaveUniImportSource source) {
        return source.platform() + '\n' + source.type() + '\n' + source.id();
    }

    private static String message(RuntimeException error) {
        return error.getMessage() == null || error.getMessage().isBlank()
                ? text(".button.loadingError") : error.getMessage();
    }

    private static String text(String suffix) {
        return I18n.get(MusicHud.MOD_ID + suffix);
    }

    private record SelectedSource(TuneWeaveUniImportSource source, String label) {
    }
}
