package indi.mopelotus.musichud.beans.music;

import indi.mopelotus.musichud.utils.collections.ObservableSequencedSet;

public interface MusicCollection extends IdentifiedBeans {
    long getId();
    String getName();
    String getNameI18nKey();
    String getImageThumbnailUrl(int size);

    static String thumbnailUrl(String source, int size) {
        if (source == null || source.isBlank() || source.startsWith("data:")) {
            return source == null ? "" : source;
        }
        if (source.contains(".music.126.net/")) {
            return source + (source.contains("?") ? "&" : "?") + "param=" + size + "y" + size;
        }
        return source;
    }
    int getMusicTrackCount();
    PusherInfo getPusherInfo();
    ObservableSequencedSet<MusicDetail> getMusicDetails();
    MusicCollection copyWithPusherInfo(PusherInfo pusherInfo);

    boolean equalsLoose(Object obj);
}
