package indi.mopelotus.musichud.beans.music;

import com.google.gson.annotations.SerializedName;
import indi.mopelotus.musichud.network.ByteBufCodec;
import indi.mopelotus.musichud.network.Codecs;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;
import java.util.Objects;

@NoArgsConstructor(access = AccessLevel.PUBLIC)
public class MusicDetail implements IdentifiedBeans {
    public static final ByteBufCodec<MusicDetail> CODEC = ByteBufCodec.composite(
            Codecs.LONG, MusicDetail::getId,
            Codecs.STRING_UTF8, MusicDetail::getName,
            Codecs.INT, MusicDetail::getDurationMillis,
            Codecs.ofEnum(Fee.class), MusicDetail::getFee,
            Album.CODEC, MusicDetail::getAlbum,
            Codecs.ofList(() -> Codecs.STRING_UTF8), MusicDetail::getAlias,
            Codecs.ofList(() -> Codecs.STRING_UTF8), MusicDetail::getTranslations,
            Codecs.ofList(() -> Artist.CODEC), MusicDetail::getArtists,
            PusherInfo.CODEC, MusicDetail::getPusherInfo,
            LyricInfo.CODEC, MusicDetail::getLyricInfo,
            MusicSourceSelector.CODEC, MusicDetail::getSourceSelector,
            PlaybackSource.CODEC, MusicDetail::getPlaybackSource,
            MusicDetail::new
    );
    public static final MusicDetail NONE = new MusicDetail();
    String name = "";
    @Getter
    long id;
    @SerializedName("ar")
    List<Artist> artists = List.of();
    @SerializedName("alia")
    List<String> alias = List.of();
    @SerializedName("pop")
    @Getter
    int popularity;
    @SerializedName("al")
    @Setter
    Album album = Album.NONE;
    @SerializedName("dt")
    @Getter
    int durationMillis;
    @Getter
    long mark; // bit mask
    @SerializedName("tns")
    List<String> translations = List.of();
    @Getter
    Fee fee = Fee.UNSET;
    MusicSourceSelector sourceSelector = MusicSourceSelector.DEFAULT;
    private PlaybackSource playbackSource = PlaybackSource.NONE;

    public PlaybackSource getPlaybackSource() { return Objects.requireNonNullElse(playbackSource, PlaybackSource.NONE); }

    public MusicDetail withPlaybackSource(PlaybackSource source) {
        MusicDetail copy = new MusicDetail(id, getName(), durationMillis, getFee(), getAlbum(), getAlias(), getTranslations(),
                getArtists(), getPusherInfo(), getLyricInfo(), getSourceSelector(), source);
        copy.extraInfo = extraInfo;
        return copy;
    }

    protected MusicDetail(long id, String name, int durationMillis, Fee fee, Album album,
                          List<String> alias, List<String> translations, List<Artist> artists,
                          PusherInfo pusherInfo, LyricInfo lyricInfo, MusicSourceSelector sourceSelector, PlaybackSource playbackSource) {
        this(id, name, durationMillis, fee, album, alias, translations, artists, pusherInfo, lyricInfo, sourceSelector);
        this.playbackSource = Objects.requireNonNull(playbackSource);
    }

    // only useful for server, and its a optional api field
    @SerializedName("privilege")
    @Setter
    @Getter
    ExtraInfo extraInfo;
    // Not contained in the original API response, set separately
    @Setter
    PusherInfo pusherInfo = PusherInfo.EMPTY;
    @Setter
    LyricInfo lyricInfo = LyricInfo.NONE;

    protected MusicDetail(
            long id,
            String name,
            int durationMillis,
            Fee fee,
            Album album,
            List<String> alias,
            List<String> translations,
            List<Artist> artists,
            PusherInfo pusherInfo,
            LyricInfo lyricInfo,
            MusicSourceSelector sourceSelector
    ) {
        this.name = name;
        this.id = id;
        this.artists = artists;
        this.alias = alias;
        this.fee = fee;
        this.album = album;
        this.durationMillis = durationMillis;
        this.translations = translations;
        this.pusherInfo = pusherInfo;
        this.lyricInfo = lyricInfo;
        this.sourceSelector = Objects.requireNonNullElse(sourceSelector, MusicSourceSelector.DEFAULT);
    }

    public static MusicDetail fromTuneWeave(long id, String sourceRef, String sourceKind, String name,
                                            int durationMillis, Album album, List<Artist> artists) {
        MusicDetail detail = new MusicDetail();
        detail.id = id;
        detail.sourceSelector = new MusicSourceSelector(sourceRef, sourceKind, "", false, false);
        detail.name = Objects.requireNonNullElse(name, "");
        detail.durationMillis = durationMillis;
        detail.album = Objects.requireNonNullElse(album, Album.NONE);
        detail.artists = artists == null ? List.of() : artists;
        return detail;
    }

    public String getName() {
        return Objects.requireNonNullElse(name, "");
    }

    public List<Artist> getArtists() {
        if (artists == null || artists.isEmpty()) {
            return List.of();
        }
        return artists.stream().filter(Objects::nonNull).toList();
    }

    public List<String> getAlias() {
        if (alias == null || alias.isEmpty()) {
            return List.of();
        }
        return alias.stream().filter(Objects::nonNull).toList();
    }

    public Album getAlbum() {
        return Objects.requireNonNullElse(album, Album.NONE);
    }

    public List<String> getTranslations() {
        if (translations == null || translations.isEmpty()) {
            return List.of();
        }
        return translations.stream().filter(Objects::nonNull).toList();
    }

    public PusherInfo getPusherInfo() {
        return Objects.requireNonNullElse(pusherInfo, PusherInfo.EMPTY);
    }

    public LyricInfo getLyricInfo() {
        return Objects.requireNonNullElse(lyricInfo, LyricInfo.NONE);
    }

    public String getSourceRef() {
        return getSourceSelector().reference();
    }

    public String getSourceKind() {
        return getSourceSelector().kind();
    }

    public String getSourcePartRef() {
        return getSourceSelector().partReference();
    }

    public MusicSourceSelector getSourceSelector() {
        return Objects.requireNonNullElse(sourceSelector, MusicSourceSelector.DEFAULT);
    }

    public void setSourceRef(String sourceRef) {
        sourceSelector = getSourceSelector().withReference(sourceRef);
    }

    public void setSourceKind(String sourceKind) {
        sourceSelector = getSourceSelector().withKind(sourceKind);
    }

    public void setSourcePartRef(String sourcePartRef) {
        sourceSelector = getSourceSelector().withPartReference(sourcePartRef);
    }

    public boolean isClientHostedUni() {
        return getSourceSelector().clientHostedUni();
    }

    public void setClientHostedUni(boolean clientHostedUni) {
        sourceSelector = getSourceSelector().withClientHostedUni(clientHostedUni);
    }

    public boolean isCloudSource() {
        return getSourceSelector().cloudSource();
    }

    public void setCloudSource(boolean cloudSource) {
        sourceSelector = getSourceSelector().withCloudSource(cloudSource);
    }

    @Override
    public boolean equals(Object obj) {
        return this == obj || (obj instanceof MusicDetail other && this.id == other.id);
    }

    @Override
    public int hashCode() {
        return Long.hashCode(id);
    }

    public record ExtraInfo(
            @SerializedName("cs")
            boolean cloudSource,
            @SerializedName("st")
            int copyrightStatus,//0 is normal, less than 0 means no copyright
            @SerializedName("toast")
            boolean disabledAsCopyrightProtect
    ) {
    }
}
