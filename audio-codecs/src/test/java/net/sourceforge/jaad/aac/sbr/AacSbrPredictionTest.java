package net.sourceforge.jaad.aac.sbr;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import net.sourceforge.jaad.aac.Decoder;
import org.junit.jupiter.api.Test;

class AacSbrPredictionTest {
  private static float[] coefficients(float[][][] input) throws Exception {
    var sbr = new SBR1(Decoder.create(new byte[] {0x13, 0x08}).getConfig());
    Method method = HFGeneration.class.getDeclaredMethod("calc_prediction_coef", SBR.class,
        float[][][].class, float[][].class, float[][].class, int.class);
    method.setAccessible(true);
    float[][] a0 = new float[64][2], a1 = new float[64][2];
    method.invoke(null, sbr, input, a0, a1, 5);
    return new float[] {a0[5][0], a0[5][1], a1[5][0], a1[5][1]};
  }

  @Test
  void nearSingularCapturedInputsMatchIndependentDecimal70Oracle() throws Exception {
    // Expected values use direct 38-term Decimal inner products, not production endpoint updates.
    int[] frames = {54, 58, 60};
    double[][] expected = {
        {1.461893431139084968665185426338266486474421683476349646924845638230844, 0.5795963970887739685433000555206148020480081116940109795412261412170467, 0.4028552899792754795795634607863306556795200218235685145804038829042824, 0.4134951599643361289674486306416377483475891761094620284579017077985838},
        {1.556376459323114401408125449668231760961596228145501692526831085703953, 0.6886670340424567082751070909167691868725256402883432548345910387501619, 0.4585485425966820327601041299675169921192117022409627186104027144902843, 0.5466152875226814273507676799660022992792155880950687165272679855832735},
        {1.241411884218502437080058874331830829534943649047247953529593886703666, 0.5589292396542465795968526809513585242292504401949519072333199875276545, 0.2001349389433093836479567269855392051744934652817612170369816988311522, 0.3247625287345607741754868337689527164040709353077332341726697491966594}
    };
    for (int i = 0; i < frames.length; i++) {
      String path = "/codecs/aac-sbr-prediction/au" + frames[i] + ".hex";
      String[] bits;
      try (var stream = getClass().getResourceAsStream(path)) {
        assertNotNull(stream);
        bits = new String(stream.readAllBytes(), StandardCharsets.US_ASCII).strip().split("\\s+");
      }
      assertEquals(80, bits.length);
      float[][][] input = new float[40][64][2];
      for (int n = 0; n < bits.length; n++) {
        input[n / 2][5][n % 2] = Float.intBitsToFloat(Integer.parseUnsignedInt(bits[n], 16));
      }
      float[] actual = coefficients(input);
      for (int n = 0; n < 4; n++) assertEquals(expected[i][n], actual[n], 0.0000002, path);
    }
  }

  @Test
  void zeroEnergyHasZeroPrediction() throws Exception {
    assertArrayEquals(new float[4], coefficients(new float[40][64][2]), 0);
  }

  @Test
  void exactComplexToneRetainsScaleInvariantFinitePrediction() throws Exception {
    for (float amplitude : new float[] {Float.MIN_VALUE, Math.scalb(1f, -60), 1,
        Math.scalb(1f, 60), Float.MAX_VALUE}) {
      float[][][] input = new float[40][64][2];
      for (int n = 0; n < 40; n++) {
        input[n][5][n % 2] = n % 4 < 2 ? amplitude : -amplitude;
      }
      // x[n] = i*x[n-1]: a0 = -i, a1 = 0, independent of amplitude.
      assertArrayEquals(new float[] {0, -1, 0, 0}, coefficients(input), 0.000002f);
    }
  }

  @Test
  void analyticGrowthBelowLimitRetainsItsPredictor() throws Exception {
    float[][][] input = new float[40][64][2];
    for (int n = 0; n < 40; n++) input[n][5][0] = Math.scalb(1f, n);
    assertArrayEquals(new float[] {-2, 0, 0, 0}, coefficients(input), 0.000002f);
  }

  @Test
  void clampUsesStoredFloatCoefficientsAtTheOriginalBoundary() throws Exception {
    float[][][] input = new float[40][64][2];
    for (int n = 0; n < 40; n++) input[n][5][0] = Math.scalb(1f, 2 * n);
    // Solving in double can yield just under 4, then round to 4 on storage.
    // The original float norm >=16 guard must still reject that stored coefficient.
    assertArrayEquals(new float[4], coefficients(input), 0);
  }

  @Test
  void secondCoefficientAboveLimitAlsoClearsBothPredictors() throws Exception {
    float[][][] input = new float[40][64][2];
    for (int n = 0; n < 40; n += 2) input[n][5][0] = Math.scalb(1f, 3 * (n / 2));
    // x[n] = 8*x[n-2]: a0 = 0, a1 = -8.
    assertArrayEquals(new float[4], coefficients(input), 0);
  }
}
