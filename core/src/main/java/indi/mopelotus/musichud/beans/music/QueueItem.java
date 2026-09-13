package indi.mopelotus.musichud.beans.music;

import indi.mopelotus.musichud.network.ByteBufCodec;
import indi.mopelotus.musichud.network.Codecs;

import java.util.UUID;

public record QueueItem(MusicDetail musicDetail, UUID queueUniqueID) {
    public static final ByteBufCodec<QueueItem> CODEC = ByteBufCodec.composite(
            MusicDetail.CODEC,
            QueueItem::musicDetail,
            Codecs.UUID,
            QueueItem::queueUniqueID,
            QueueItem::new
    );
}
