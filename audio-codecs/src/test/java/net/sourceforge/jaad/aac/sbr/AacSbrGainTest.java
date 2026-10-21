package net.sourceforge.jaad.aac.sbr;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Field;
import net.sourceforge.jaad.aac.Decoder;
import org.junit.jupiter.api.Test;

class AacSbrGainTest {
  private record Model(SBR sbr, Channel channel) {}

  private static Model model() {
    var sbr = new SBR1(Decoder.create(new byte[] {0x12, 0x10}).getConfig());
    sbr.hdr = new Header();
    sbr.hdr.bs_limiter_bands = 0;
    sbr.hdr.bs_limiter_gains = 3; // No limiting: isolate energy compensation.
    sbr.kx = 10; sbr.M = 1; sbr.N_L[0] = 1;
    sbr.f_table_lim[0][1] = 1;
    sbr.f_table_res[0][0] = sbr.f_table_res[1][0] = 10;
    sbr.f_table_res[0][1] = sbr.f_table_res[1][1] = 11;
    sbr.f_table_noise[0] = 10; sbr.f_table_noise[1] = 11;
    var ch = new Channel(sbr);
    ch.L_E = 1; ch.L_Q = 1; ch.t_E[1] = 2; ch.t_Q[1] = 32;
    ch.E_orig[0][0] = 100; ch.E_curr[0][0] = 9;
    ch.Q_div[0][0] = ch.Q_div2[0][0] = .5f;
    ch.l_A = -1;
    return new Model(sbr, ch);
  }

  private static float[] gains(Model model) throws Exception {
    return gains(model, 0);
  }

  private static float[] gains(Model model, int envelope) throws Exception {
    var constructor = HFAdjustment.class.getDeclaredConstructor();
    constructor.setAccessible(true);
    Object adjustment = constructor.newInstance();
    var calculate = HFAdjustment.class.getDeclaredMethod("calculate_gain", SBR.class, Channel.class);
    calculate.setAccessible(true);
    calculate.invoke(adjustment, model.sbr, model.channel);
    float[] result = new float[2];
    String[] names = {"G_lim_boost", "Q_M_lim_boost"};
    for (int i = 0; i < names.length; i++) {
      Field field = HFAdjustment.class.getDeclaredField(names[i]);
      field.setAccessible(true);
      result[i] = ((float[][]) field.get(adjustment))[envelope][0];
    }
    return result;
  }

  @Test
  void currentAndInheritedShortEnvelopesHaveIdenticalNoNoiseCompensation() throws Exception {
    for (boolean current : new boolean[] {false, true}) {
      for (boolean inherited : new boolean[] {false, true}) {
        var model = model();
        model.channel.l_A = current ? 0 : -1;
        model.channel.prevEnvIsShort = inherited ? 0 : -1;
        float[] gains = gains(model);
        boolean noNoise = current || inherited;
        // Independent energy balance: output signal + actually emitted noise = E_orig.
        double emittedEnergy = 9 * gains[0] * gains[0]
            + (noNoise ? 0 : gains[1] * gains[1]);
        assertEquals(100, emittedEnergy, .00003,
            "current=" + current + ", inherited=" + inherited);
        if (noNoise) assertEquals(10.0 / 3, gains[0], .000001);
      }
    }
  }

  @Test
  void inheritedSuppressionComesFromPreviousFinalShortEnvelopeAndExpires() throws Exception {
    var model = model();
    var ch = model.channel;
    ch.L_E = 2; ch.l_A = 2;
    model.sbr.sbr_save_prev_data(ch);
    assertEquals(0, ch.prevEnvIsShort);
    ch.L_E = 1; ch.l_A = -1;
    assertEquals(10.0 / 3, gains(model)[0], .000001);
    model.sbr.sbr_save_prev_data(ch);
    assertEquals(-1, ch.prevEnvIsShort);
    float[] normal = gains(model);
    assertEquals(100, 9 * normal[0] * normal[0] + normal[1] * normal[1], .00003);
    assertTrue(normal[0] < 3, "The following ordinary envelope emits noise again");
  }

  @Test
  void noNoiseEnvelopeWithZeroNoiseLevelRetainsOrdinarySignalGain() throws Exception {
    var ordinary = model(); var inherited = model();
    for (var model : new Model[] {ordinary, inherited}) {
      model.channel.Q_div[0][0] = 1;
      model.channel.Q_div2[0][0] = 0;
    }
    inherited.channel.prevEnvIsShort = 0;
    assertArrayEquals(gains(ordinary), gains(inherited), 0);
  }

  @Test
  void inheritedShortFlagOnlySuppressesTheFirstEnvelope() throws Exception {
    var model = model();
    var ch = model.channel;
    ch.L_E = 2; ch.t_E[2] = 4; ch.prevEnvIsShort = 0;
    ch.E_orig[0][1] = 100; ch.E_curr[0][1] = 9;
    assertEquals(10.0 / 3, gains(model, 0)[0], .000001);
    float[] following = gains(model, 1);
    assertArrayEquals(gains(model()), following, 0);
    assertEquals(100, 9 * following[0] * following[0] + following[1] * following[1], .00003);
  }
}
