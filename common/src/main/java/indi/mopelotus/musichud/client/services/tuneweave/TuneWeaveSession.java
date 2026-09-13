package indi.mopelotus.musichud.client.services.tuneweave;

import indi.mopelotus.musichud.beans.user.Profile;
import indi.mopelotus.musichud.beans.user.VipType;
import indi.mopelotus.musichud.server.api.tuneweave.TuneWeavePlatform;

public record TuneWeaveSession(TuneWeavePlatform platform, String userId, String nickname,
                               String avatarUrl, boolean authenticated) {
    public Profile toMusicHudProfile() {
        String displayName = nickname == null || nickname.isBlank() ? platform.apiName() : nickname;
        return new Profile(displayName, avatarUrl == null ? "" : avatarUrl,
                TuneWeaveIdentity.stableId(platform,
                        TuneWeaveIdentity.userIdFromReference(platform, userId)),
                VipType.NORMAL);
    }
}
