package indi.mopelotus.musichud.beans.music;

import com.google.gson.annotations.SerializedName;
import indi.mopelotus.musichud.MusicHud;
import indi.mopelotus.musichud.network.ByteBufCodec;
import indi.mopelotus.musichud.network.Codecs;
import indi.mopelotus.musichud.utils.collections.ObservableSequencedSet;
import lombok.*;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

@NoArgsConstructor(access = AccessLevel.PUBLIC)
public class Album implements MusicCollection {
    public static final ByteBufCodec<Album> CODEC = ByteBufCodec.composite(
            Codecs.LONG, Album::getId,
            Codecs.STRING_UTF8, Album::getName,
            Codecs.STRING_UTF8, Album::getPicUrl,
            Codecs.STRING_UTF8, Album::getType,
            Codecs.STRING_UTF8, Album::getCompany,
            Codecs.INT, Album::getMusicTrackCount,
            Codecs.ofCollection(ObservableSequencedSet::new, () -> MusicDetail.CODEC), Album::getMusicDetails,
            Codecs.ofCollection(LinkedHashSet::new, () -> Artist.CODEC), Album::getArtists,
            PusherInfo.CODEC, Album::getPusherInfo,
            Codecs.STRING_UTF8, Album::getSourceRef,
            Album::new
    );
    public static final Album NONE = new Album();
    @Getter
    long id;
    String name = "";
    String picUrl = "";
    String type = "";
    String company = "";
    @SerializedName("size")
    @Getter
    int musicTrackCount;
    @SerializedName("songs")
    @Setter
    ObservableSequencedSet<MusicDetail> musicDetails = new ObservableSequencedSet<>(0);
    LinkedHashSet<Artist> artists = new LinkedHashSet<>();
    // Not contained in the original API response, set separately
    @Getter
    transient PusherInfo pusherInfo = PusherInfo.EMPTY;
    @Setter
    String sourceRef = "";

    private boolean nullFiltered = false;

    public Album(
            long id,
            String name,
            String picUrl,
            String type,
            String company,
            Integer musicTrackCount,
            ObservableSequencedSet<MusicDetail> musicDetails,
            LinkedHashSet<Artist> artists,
            PusherInfo pusherInfo,
            String sourceRef
    ) {
        this.id = id;
        this.name = name;
        this.picUrl = picUrl;
        this.type = type;
        this.company = company;
        this.musicTrackCount = musicTrackCount;
        this.musicDetails = musicDetails;
        this.artists = artists;
        this.pusherInfo = pusherInfo;
        this.sourceRef = sourceRef;
    }

    public String getThumbnailPicUrl(int size) {
        String source = getPicUrl();
        if (source.isBlank()) return MusicHud.ICON_BASE64;
        return MusicCollection.thumbnailUrl(source, size);
    }

    public String getName() {
        return Objects.requireNonNullElse(name, "");
    }

    @Override
    public String getNameI18nKey() {
        return MusicHud.MOD_ID + ".text.album";
    }

    public String getPicUrl() {
        return Objects.requireNonNullElse(picUrl, "");
    }

    public String getSourceRef() {
        return Objects.requireNonNullElse(sourceRef, "");
    }

    public String getType() {
        return Objects.requireNonNullElse(type, "");
    }

    public String getCompany() {
        return Objects.requireNonNullElse(company, "");
    }

    @Override
    public ObservableSequencedSet<MusicDetail> getMusicDetails() {
        if (musicDetails == null || musicDetails.isEmpty()) {
            return new ObservableSequencedSet<>(0);
        }
        if (!nullFiltered) {
            musicDetails = musicDetails.stream().filter(Objects::nonNull)
                    .collect(ObservableSequencedSet::new, Set::add, ObservableSequencedSet::addAll);
            nullFiltered = true;
        }
        return musicDetails;
    }

    @Override
    public String getImageThumbnailUrl(int size) {
        return getThumbnailPicUrl(size);
    }

    public LinkedHashSet<Artist> getArtists() {
        return Objects.requireNonNullElse(artists, new LinkedHashSet<>());
    }

    public Album shallowCopyBriefInfo() {
        Album album = new Album();
        album.id = this.id;
        album.name = this.name;
        album.picUrl = this.picUrl;
        album.musicTrackCount = this.musicTrackCount;
        album.company = this.company;
        album.type = this.type;
        album.sourceRef = this.sourceRef;
        return album;
    }

    @Override
    public Album copyWithPusherInfo(PusherInfo pusherInfo) {
        Album album = shallowCopyBriefInfo();
        album.pusherInfo = pusherInfo;
        album.sourceRef = sourceRef;
        return album;
    }

    @Override
    public boolean equals(Object obj) {
        return obj instanceof Album album
                && album.id == id
                && album.name.equals(name)
                && album.picUrl.equals(picUrl)
                && album.pusherInfo.equals(pusherInfo);
    }

    @Override
    public boolean equalsLoose(Object obj) {
        return obj instanceof Album album && id == album.id;
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, name, picUrl, pusherInfo.getPlayerUUID());
    }
}
