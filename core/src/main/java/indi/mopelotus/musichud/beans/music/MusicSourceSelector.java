package indi.mopelotus.musichud.beans.music;

import indi.mopelotus.musichud.network.ByteBufCodec;
import indi.mopelotus.musichud.network.Codecs;

import java.util.Objects;

/**
 * Stable TuneWeave selector for resolving a playable entity on a client.
 * It deliberately contains no credentials or resolved media URLs.
 */
public record MusicSourceSelector(String reference, String kind, String partReference,
                                  boolean clientHostedUni, boolean cloudSource) {
    public static final MusicSourceSelector DEFAULT = new MusicSourceSelector("", "track", "", false, false);

    public static final ByteBufCodec<MusicSourceSelector> CODEC = ByteBufCodec.composite(
            Codecs.STRING_UTF8, MusicSourceSelector::reference,
            Codecs.STRING_UTF8, MusicSourceSelector::kind,
            Codecs.STRING_UTF8, MusicSourceSelector::partReference,
            Codecs.BOOL, MusicSourceSelector::clientHostedUni,
            Codecs.BOOL, MusicSourceSelector::cloudSource,
            MusicSourceSelector::new
    );

    public MusicSourceSelector {
        reference = Objects.requireNonNullElse(reference, "");
        kind = Objects.requireNonNullElse(kind, "track");
        partReference = Objects.requireNonNullElse(partReference, "");
    }

    public MusicSourceSelector withReference(String value) {
        return new MusicSourceSelector(value, kind, partReference, clientHostedUni, cloudSource);
    }

    public MusicSourceSelector withKind(String value) {
        return new MusicSourceSelector(reference, value, partReference, clientHostedUni, cloudSource);
    }

    public MusicSourceSelector withPartReference(String value) {
        return new MusicSourceSelector(reference, kind, value, clientHostedUni, cloudSource);
    }

    public MusicSourceSelector withClientHostedUni(boolean value) {
        return new MusicSourceSelector(reference, kind, partReference, value, cloudSource);
    }

    public MusicSourceSelector withCloudSource(boolean value) {
        return new MusicSourceSelector(reference, kind, partReference, clientHostedUni, value);
    }
}
