package indi.mopelotus.musichud.beans.music;

import com.google.gson.annotations.SerializedName;
import indi.mopelotus.musichud.network.ByteBufCodec;
import indi.mopelotus.musichud.network.Codecs;
import lombok.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Getter
@AllArgsConstructor
@NoArgsConstructor(access = AccessLevel.PUBLIC)
public class Artist implements IdentifiedBeans {
    public static final ByteBufCodec<Artist> CODEC = ByteBufCodec.composite(
            Codecs.LONG, Artist::getId,
            Codecs.STRING_UTF8, Artist::getName,
            Codecs.STRING_UTF8, Artist::getAvatarUrl,
            Codecs.INT, Artist::getAlbumCount,
            Codecs.INT, Artist::getMusicCount,
            Codecs.STRING_UTF8, Artist::getDescription,
            Codecs.ofList(() -> MusicDetail.CODEC), Artist::getMusicDetails,
            Codecs.INT, Artist::getTotalMusicCount,
            Codecs.STRING_UTF8, Artist::getSourceRef,
            Artist::new
    );
    long id;
    String name = "";
    @SerializedName(value = "avatar", alternate = "img1v1Url")
    String avatarUrl = "";
    @SerializedName("albumSize")
    int albumCount;
    @SerializedName("musicSize")
    int musicCount;
    @SerializedName("briefDesc")
    String description = "";
    List<MusicDetail> musicDetails = new ArrayList<>();
    @Setter
    int totalMusicCount;
    @Setter
    String sourceRef = "";

    public String getName() {
        return Objects.requireNonNullElse(name, "");
    }
    public String getAvatarUrl() {return Objects.requireNonNullElse(avatarUrl, "");}
    public String getSourceRef() {return Objects.requireNonNullElse(sourceRef, "");}
    public String getAvatarThumbnailUrl(int size) {return MusicCollection.thumbnailUrl(getAvatarUrl(), size);}
    public String getDescription() {
        return Objects.requireNonNullElse(description, "");
    }
    public List<MusicDetail> getMusicDetails() {
        if (musicDetails == null) {
            musicDetails = new ArrayList<>();
        }
        return musicDetails;
    }
}
