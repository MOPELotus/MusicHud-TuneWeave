package indi.mopelotus.musichud.client.utils.text;

import icyllis.modernui.core.Core;
import icyllis.modernui.graphics.text.EmojiFont;
import indi.mopelotus.musichud.client.utils.image.ClientGraphicsResources;

/** Publishes completed resource reloads to the UI owner; never infers a generation from a stale getter. */
public final class WidgetEmojiResources {
    private static volatile EmojiImages.Generation generation = new EmojiImages.Generation(0, null);
    private static volatile EmojiImages images;

    private WidgetEmojiResources() {}

    public static synchronized void onResourceReload(EmojiFont font) {
        generation = new EmojiImages.Generation(generation.epoch() + 1, font);
        EmojiImages owned = images;
        if (owned != null) Core.getUiHandlerAsync().post(owned::synchronize);
    }

    static EmojiImages.Generation currentGeneration() { return generation; }

    static EmojiImages forCanvas() {
        Core.checkUiThread();
        if (images == null) {
            try {
                images = ClientGraphicsResources.createUiResource(EmojiImages::new);
            } catch (java.util.concurrent.CancellationException stopping) {
                // A final queued frame may reach this first after the owner has stopped allocations.
                EmojiImages stopped = new EmojiImages();
                stopped.close();
                images = stopped;
            }
        }
        return images;
    }
}
