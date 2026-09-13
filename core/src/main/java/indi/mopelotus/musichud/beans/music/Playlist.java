package indi.mopelotus.musichud.beans.music;

import com.google.gson.annotations.SerializedName;
import indi.mopelotus.musichud.MusicHud;
import indi.mopelotus.musichud.beans.user.Profile;
import indi.mopelotus.musichud.network.ByteBufCodec;
import indi.mopelotus.musichud.network.Codecs;
import indi.mopelotus.musichud.utils.collections.ObservableSequencedSet;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.*;

@NoArgsConstructor(access = AccessLevel.PUBLIC)
public class Playlist implements MusicCollection {
    public static final ByteBufCodec<Playlist> CODEC = ByteBufCodec.composite(
            Codecs.LONG, Playlist::getId,
            Codecs.STRING_UTF8, Playlist::getName,
            Codecs.LONG, Playlist::getCoverImgId,
            Codecs.STRING_UTF8, Playlist::getCoverImgId_str,
            Codecs.STRING_UTF8, Playlist::getCoverImgUrl,
            Codecs.INT, Playlist::getMusicTrackCount,
            Codecs.INT, Playlist::getPlayedCount,
            Profile.CODEC, Playlist::getCreator,
            Codecs.ofEnum(Privacy.class), Playlist::getPrivacy,
            Codecs.ofCollection(ObservableSequencedSet::new, () -> MusicDetail.CODEC), Playlist::getTracks,
            PusherInfo.CODEC, Playlist::getPusherInfo,
            Codecs.STRING_UTF8, Playlist::getSourceRef,
            Playlist::new
    );

    public static final Playlist EMPTY = new Playlist();

    @Getter
    long id = -1;
    String name = "";
    @Getter
    long coverImgId = -1;
    @SerializedName("trackCount")
    @Getter
    @Setter
    int musicTrackCount;
    @SerializedName("playCount")
    @Getter
    int playedCount;
    String coverImgId_str = "";
    String coverImgUrl = MusicHud.ICON_BASE64;
    Profile creator = Profile.ANONYMOUS;
    Privacy privacy = Privacy.PUBLIC;

    public void setPrivacy(Privacy privacy) { this.privacy = Objects.requireNonNull(privacy); }
    @Setter
    ObservableSequencedSet<MusicDetail> tracks = new ObservableSequencedSet<>(0);

    // Not contained in the original API response, set separately
    @Getter
    PusherInfo pusherInfo = PusherInfo.EMPTY;
    @Setter
    String sourceRef = "";

    private boolean nullFiltered = false;

    protected Playlist(
            long id,
            String name,
            long coverImgId,
            String coverImgId_str,
            String coverImgUrl,
            int musicTrackCount,
            int playedCount,
            Profile creator,
            Privacy privacy,
            ObservableSequencedSet<MusicDetail> tracks,
            PusherInfo pusherInfo,
            String sourceRef
    ) {
        this.id = id;
        this.name = name;
        this.coverImgId = coverImgId;
        this.coverImgId_str = coverImgId_str;
        this.coverImgUrl = coverImgUrl;
        this.musicTrackCount = musicTrackCount;
        this.playedCount = playedCount;
        this.creator = creator;
        this.privacy = privacy;
        this.tracks = tracks;
        this.pusherInfo = pusherInfo;
        this.sourceRef = sourceRef;
    }

    public static Playlist privacyBlocked(long id, Profile creator) {
        Playlist playlist = new Playlist();
        playlist.id = id;
        playlist.privacy = Privacy.PRIVATE;
        playlist.creator = creator;
        return playlist;
    }

    public static Playlist empty(long id) {
        Playlist playlist = new Playlist();
        playlist.id = id;
        return playlist;
    }

    public static Playlist fromTuneWeave(long id, String sourceRef, String name, String coverUrl,
                                         int trackCount, int playedCount, Profile creator) {
        Playlist playlist = new Playlist();
        playlist.id = id;
        playlist.sourceRef = Objects.requireNonNullElse(sourceRef, "");
        playlist.name = Objects.requireNonNullElse(name, "");
        playlist.coverImgUrl = Objects.requireNonNullElse(coverUrl, MusicHud.ICON_BASE64);
        playlist.musicTrackCount = Math.max(0, trackCount);
        playlist.playedCount = Math.max(0, playedCount);
        playlist.creator = Objects.requireNonNullElse(creator, Profile.ANONYMOUS);
        return playlist;
    }

    public String getName() {
        return Objects.requireNonNullElse(name, "");
    }

    @Override
    public String getNameI18nKey() {
        return MusicHud.MOD_ID + ".text.playlist";
    }

    @Override
    public String getImageThumbnailUrl(int size) {
        return getThumbnailCoverUrl(size);
    }

    @Override
    public ObservableSequencedSet<MusicDetail> getMusicDetails() {
        return getTracks();
    }

    public String getCoverImgId_str() {
        return Objects.requireNonNullElse(coverImgId_str, "");
    }

    public String getSourceRef() {
        return Objects.requireNonNullElse(sourceRef, "");
    }

    public String getCoverImgUrl() {
        return Objects.requireNonNullElse(coverImgUrl, "");
    }

    public String getThumbnailCoverUrl(int size) {
        if (coverImgUrl.startsWith("data:image")) {
            return coverImgUrl;
        } else {
            return MusicCollection.thumbnailUrl(coverImgUrl, size);
        }
    }

    public Profile getCreator() {
        return Objects.requireNonNullElse(creator, Profile.ANONYMOUS);
    }

    public Privacy getPrivacy() {
        return Objects.requireNonNullElse(privacy, Privacy.PUBLIC);
    }

    public ObservableSequencedSet<MusicDetail> getTracks() {
        if (tracks == null) {
            tracks = new ObservableSequencedSet<>(0);
            return tracks;
        }
        if (!nullFiltered) {
            filterTracksNullItem();
            nullFiltered = true;
        }
        return tracks;
    }

    private void filterTracksNullItem() {
        tracks = tracks.stream().filter(Objects::nonNull)
                .collect(ObservableSequencedSet::new, Set::add, ObservableSequencedSet::addAll);
    }

    @Override
    public boolean equals(Object obj) {
        return obj instanceof Playlist playlist
                && playlist.id == id
                && playlist.name.equals(name)
                && playlist.coverImgUrl.equals(coverImgUrl)
                && playlist.pusherInfo.equals(pusherInfo);
    }

    @Override
    public boolean equalsLoose(Object obj) {
        return obj instanceof Playlist playlist
                && playlist.id == id;
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, name, coverImgUrl, pusherInfo.getPlayerUUID());
    }

    @Override
    public Playlist copyWithPusherInfo(PusherInfo pusherInfo) {
        Playlist playlist = new Playlist();
        playlist.id = id;
        playlist.name = name;
        playlist.coverImgId = coverImgId;
        playlist.coverImgUrl = coverImgUrl;
        playlist.tracks = tracks;
        playlist.creator = creator;
        playlist.privacy = privacy;
        playlist.musicTrackCount = musicTrackCount;
        playlist.playedCount = playedCount;
        playlist.pusherInfo = pusherInfo;
        playlist.sourceRef = sourceRef;
        return playlist;
    }

    public Playlist copyWithSensitiveErased() {
        if (privacy == Privacy.PRIVATE) {
            Playlist playlist = new Playlist();
            playlist.id = id;
            playlist.name = "Private Playlist";
            playlist.coverImgId = -1;
            playlist.coverImgUrl = MusicHud.ICON_BASE64;
            playlist.creator = creator == Profile.ANONYMOUS ? Profile.PRIVATE_MASK : creator;
            playlist.privacy = privacy;
            playlist.pusherInfo = pusherInfo;
            return playlist;
        } else {
            return copyWithPusherInfo(pusherInfo);
        }
    }
}
