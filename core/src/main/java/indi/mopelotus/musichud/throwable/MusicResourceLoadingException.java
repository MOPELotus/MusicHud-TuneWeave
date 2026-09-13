package indi.mopelotus.musichud.throwable;

import indi.mopelotus.musichud.beans.music.MusicDetail;
import lombok.Getter;

@Getter
public class MusicResourceLoadingException extends RuntimeException {
    private final MusicDetail musicDetail;
    private final boolean usingSubstitute;

    public MusicResourceLoadingException(Throwable cause, MusicDetail musicDetail, boolean usingSubstitute) {
        super("Failed to load music resource for music: " + musicDetail.getName() + "(ID: " + musicDetail.getId() + ")", cause);
        this.musicDetail = musicDetail;
        this.usingSubstitute = usingSubstitute;
    }
}
