package indi.mopelotus.musichud.client.audio.decoder.javaaudio;

import static org.junit.jupiter.api.Assertions.*;

import indi.mopelotus.musichud.audio.codec.PacketSource;
import java.util.List;
import org.junit.jupiter.api.Test;

class PresentationPlanTest {
  private static PacketSource.Track track(PacketSource.Time end, List<PacketSource.Edit> edits) {
    return new PacketSource.Track(
        new PacketSource.Format("mp4a", new byte[0]),
        end,
        edits,
        new PacketSource.Time(1024, 48000),
        PacketSource.Time.ZERO);
  }

  private static PacketSource.Edit edit(PacketSource.Time start, long millis) {
    return new PacketSource.Edit(start, new PacketSource.Time(millis, 1000), PacketSource.Rate.ONE);
  }

  @Test
  void zeroClassicEditsRemainEmptyWithoutFragmentedProvenance() {
    var track =
        track(new PacketSource.Time(63424, 48000), List.of(edit(PacketSource.Time.ZERO, 0)));
    assertEquals(0, PresentationPlan.from(track, 48000, false).totalFrames());
    assertEquals(63424, PresentationPlan.from(track, 48000, false, true).totalFrames());
  }

  @Test
  void openEndedFinalEditPreservesLeadingSilenceAndAppliesPrimingOnce() {
    var track =
        track(
            new PacketSource.Time(63424, 48000),
            List.of(edit(null, 100), edit(new PacketSource.Time(1024, 48000), 0)));
    var plan = PresentationPlan.from(track, 48000, false, true);
    assertEquals(
        List.of(new PresentationPlan.Segment(-1, 4800), new PresentationPlan.Segment(1024, 62400)),
        plan.segments());
    assertEquals(67200, plan.totalFrames());
  }

  @Test
  void unknownFragmentExtentKeepsTheUnknownLengthSentinel() {
    var plan =
        PresentationPlan.from(
            track(null, List.of(edit(new PacketSource.Time(1024, 48000), 0))), 48000, false, true);
    assertEquals(Long.MAX_VALUE, plan.totalFrames());
    assertEquals(List.of(new PresentationPlan.Segment(1024, Long.MAX_VALUE)), plan.segments());
  }

  @Test
  void invalidFinalEditSignalsAreRejected() {
    for (var edit :
        List.of(
            edit(null, 0),
            edit(PacketSource.Time.ZERO, 1),
            edit(new PacketSource.Time(70000, 48000), 0)))
      assertThrows(
          IllegalArgumentException.class,
          () ->
              PresentationPlan.from(
                  track(new PacketSource.Time(63424, 48000), List.of(edit)), 48000, false, true));
    assertThrows(
        IllegalArgumentException.class,
        () -> PresentationPlan.from(track(null, List.of()), 48000, false, true));
  }
}
