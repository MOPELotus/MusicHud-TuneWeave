package indi.mopelotus.musichud.client.ui.pages.search;

import icyllis.modernui.core.Context;
import indi.mopelotus.musichud.beans.music.Album;
import indi.mopelotus.musichud.client.ui.components.MusicCollectionCard;
import indi.mopelotus.musichud.client.ui.components.CollectionPreviewCard;
import indi.mopelotus.musichud.client.ui.components.RouterContainer;
import indi.mopelotus.musichud.client.ui.pages.account.PersonalLibraryView;
import indi.mopelotus.musichud.client.services.tuneweave.TuneWeavePersonalLibrary;
import indi.mopelotus.musichud.client.ui.layouts.VirtualizedCardGrid;
import lombok.Getter;
import java.util.List;

public class SearchAlbumResultView extends VirtualizedCardGrid<Object> {
    @Getter private static SearchAlbumResultView instance;
    private static final SearchResultBuffer<Object> results = new SearchResultBuffer<>(item -> item instanceof Album album
            ? "album:" + album.getSourceRef() : "digital_album:" + ((TuneWeavePersonalLibrary.Entry) item).reference());
    public SearchAlbumResultView(Context context) {
        super(context, 174,
                280, item -> {
                    if (item instanceof Album album) return new MusicCollectionCard(context, album);
                    var entry = (TuneWeavePersonalLibrary.Entry) item;
                    var card = new CollectionPreviewCard(context, entry.coverUrl(), entry.name(), "", net.minecraft.client.resources.language.I18n.get(indi.mopelotus.musichud.MusicHud.MOD_ID + ".library.digital_albums"));
                    card.setOnClickListener(view -> {
                        if (!view.isAttachedToWindow()) return;
                        var router = RouterContainer.getInstance();
                        if (router != null) router.pushNavigate(new PersonalLibraryView(context,
                                entry.platform(), TuneWeavePersonalLibrary.Kind.DIGITAL_ALBUMS, entry));
                    });
                    return card;
                });
        instance = this; refresh();
    }
    public static void setResult(List<?> values) {
        results.replace(values == null ? null : new java.util.ArrayList<>(values)); if (instance != null) instance.refresh();
    }
    public void refresh() { setItems(results.snapshot()); }
    public void append(List<?> page) { results.append(new java.util.ArrayList<>(page)); refresh(); }
    @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); instance = this; refresh(); }
    @Override protected void onDetachedFromWindow() { if (instance == this) instance = null; super.onDetachedFromWindow(); }
}
