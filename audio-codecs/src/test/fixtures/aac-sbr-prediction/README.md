# SBR inverse-filter arithmetic vectors

The three 40-complex-sample QMF histories are captured from this project's existing
original synthetic `seek-hev2-period72.m4a` regression fixture (band 5, frames
54, 58 and 60). They contain no private account audio. Hexadecimal words preserve
exact IEEE float input bits. The regression uses independent 70-digit Decimal
inner products and the existing Java float relaxation value; it does not use
FFmpeg's float predictor as a numerical oracle. `oracle.py` recomputes the golden
coefficients without the production endpoint-update recurrence.

The analytic tests additionally cover zero energy, scale invariance from the
smallest float subnormal to the largest finite float, and the original stored
float coefficient norm >=16 guard. These test arithmetic, not playback timing.

The covariance formula and LGPL upstream attribution remain those of JAAD;
this change adds no external code or dependency.
