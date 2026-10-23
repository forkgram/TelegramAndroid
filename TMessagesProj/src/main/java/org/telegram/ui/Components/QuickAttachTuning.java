package org.telegram.ui.Components;

public class QuickAttachTuning {
    public static float xStiffness = 320.0f;
    public static float xDamping = 0.88f;
    public static float yStiffness = 500.0f;
    public static float yDamping = 0.60f;
    public static float birthScale = 0.42f;
    public static int staggerMs = 28;
    public static float birthYOffset = 0.0f;
    public static float yOvershootStep = 0.06f;

    public static class SpringEvaluator {
        private final float stiffness;
        private final float dampingRatio;
        private final float omega0;
        private final float gamma;
        private final float omegaD;
        private final boolean underDamped;

        public SpringEvaluator(float stiffness, float dampingRatio) {
            this.stiffness = stiffness;
            this.dampingRatio = dampingRatio;
            this.omega0 = (float) Math.sqrt(stiffness);
            this.gamma = dampingRatio * this.omega0;
            if (dampingRatio < 1.0f) {
                this.underDamped = true;
                this.omegaD = (float) (this.omega0 * Math.sqrt(1.0f - dampingRatio * dampingRatio));
            } else {
                this.underDamped = false;
                this.omegaD = 0.0f;
            }
        }

        public float evaluate(float start, float dest, float tSeconds) {
            if (tSeconds <= 0.0f) {
                return start;
            }
            if (underDamped) {
                float exp = (float) Math.exp(-gamma * tSeconds);
                float cos = (float) Math.cos(omegaD * tSeconds);
                float sin = (float) Math.sin(omegaD * tSeconds);
                return dest + (start - dest) * exp * (cos + (gamma / omegaD) * sin);
            } else {
                float exp = (float) Math.exp(-omega0 * tSeconds);
                return dest + (start - dest) * exp * (1.0f + omega0 * tSeconds);
            }
        }
    }
}
