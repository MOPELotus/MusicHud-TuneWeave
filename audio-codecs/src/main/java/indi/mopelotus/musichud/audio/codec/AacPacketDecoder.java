package indi.mopelotus.musichud.audio.codec;

/**
 * AAC decoder with read-only access-unit recovery observations. Recovery is codec configuration and
 * differential-parameter readiness, not a DSP-state snapshot or a guarantee of filter convergence.
 * The caller owns recovery anchors, source identity and preroll before presenting a target sample.
 */
public interface AacPacketDecoder extends PacketDecoder {
  /**
   * Output and extension requirements established by successful playback of the same source epoch.
   * Explicit ASC signaling and an output/core rate difference also establish requirements; implicit
   * equal output/core rates do not establish absence of SBR. This core explicitly rejects
   * single-rate SBR. Duplicated mono output alone does not establish PS.
   */
  record Expectation(int sampleRate, int channels, boolean sbr, boolean ps) {
    public Expectation {
      if (sampleRate < 1 || sampleRate > 768000 || channels < 1 || channels > 8 || ps && !sbr)
        throw new IllegalArgumentException("Invalid AAC recovery expectation");
    }
  }

  /**
   * Immutable observation of the latest complete access unit. Invalid before the first decode,
   * while an access unit is being decoded, after failure, and after close. All methods retain the
   * PacketDecoder single-caller contract. Observing this value never advances decoder state.
   *
   * <p>Independent flags are true only for extensions actually parsed in this access unit. They are
   * false for absent extensions, including ordinary LC. Header presence alone does not establish
   * independence of the first envelope or noise parameters. Readiness accounts for required
   * extensions and every active audio element; an unrelated element's header does not suffice.
   */
  record Recovery(
      long accessUnit,
      boolean valid,
      int observedRate,
      int observedChannels,
      boolean sbrPresent,
      boolean psPresent,
      boolean fullBandReady,
      boolean fullStereoReady,
      boolean currentAuSbrHeader,
      boolean currentAuPsHeader,
      boolean currentAuIndependentSbr,
      boolean currentAuIndependentPs,
      boolean allActiveElementsReady,
      boolean topologyChanged,
      boolean configurationChanged) {
    public boolean ready() {
      return valid
          && fullBandReady
          && fullStereoReady
          && allActiveElementsReady
          && !topologyChanged
          && !configurationChanged;
    }
  }

  Recovery recovery();
}
