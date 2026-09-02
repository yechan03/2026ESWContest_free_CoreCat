package com.example.in_gps.util;

/**
 * 차트용 곡선 보간 유틸.
 *
 * Fritsch–Carlson monotone cubic 보간으로 dense point를 생성한 뒤
 * MPAndroidChart의 LINEAR 모드로 잇는 방식에 사용한다.
 *
 * 왜 monotone cubic인가:
 *  - 모든 원본 점을 정확히 통과한다(피크 은폐 불가).
 *  - 곡선이 인접 두 점의 [min, max] 범위를 절대 벗어나지 않는다
 *    → Catmull-Rom/CUBIC_BEZIER처럼 데이터에 없는 값(오버슈트)으로
 *      위험 임계선을 '거짓 초과'하거나 최저 아래로 물결치는 일이 없다.
 *  - CUBIC_BEZIER 렌더 버그(점만 남고 선 미연결)와 무관 — 결과는 LINEAR로 그린다.
 *
 * 참고: F. N. Fritsch & R. E. Carlson, "Monotone Piecewise Cubic Interpolation",
 * SIAM J. Numer. Anal. 17(2), 1980.
 */
public final class ChartInterpolation {

    private ChartInterpolation() {}

    /**
     * x가 인덱스(0..N-1)인 균일 시퀀스용 오버로드.
     *
     * @return shape = [(N-1)*subdivide + 1][2], 각 원소 {x, y}
     */
    public static float[][] monotoneCubicDense(float[] ys, int subdivide) {
        float[] xs = new float[ys.length];
        for (int i = 0; i < ys.length; i++) xs[i] = i;
        return monotoneCubicDense(xs, ys, subdivide);
    }

    /**
     * Fritsch–Carlson monotone cubic 보간.
     * 인접 두 점 사이에 (subdivide - 1)개의 보간 점을 끼워 넣고,
     * 원본 점도 모두 포함한 (x, y) 쌍 시퀀스를 반환한다.
     *
     * @param xs        strictly increasing x 좌표
     * @param ys        y 값 (xs와 길이 동일)
     * @param subdivide 구간당 분할 수 (>= 1)
     * @return shape = [(N-1)*subdivide + 1][2], 각 원소 {x, y}
     */
    public static float[][] monotoneCubicDense(float[] xs, float[] ys, int subdivide) {
        int n = ys.length;
        if (n == 0) return new float[0][2];
        if (n == 1) return new float[][]{ {xs[0], ys[0]} };
        if (subdivide < 1) subdivide = 1;

        // 1) 구간 기울기(secant)
        float[] h = new float[n - 1];
        float[] d = new float[n - 1];
        for (int i = 0; i < n - 1; i++) {
            h[i] = xs[i + 1] - xs[i];
            d[i] = (ys[i + 1] - ys[i]) / h[i];
        }

        // 2) 접선 초기값: 끝점은 인접 secant, 내부는 평균.
        //    부호가 바뀌는 지점(국소 극값)은 0 → 극값에서 평평하게 통과.
        float[] m = new float[n];
        m[0] = d[0];
        m[n - 1] = d[n - 2];
        for (int i = 1; i < n - 1; i++) {
            m[i] = (d[i - 1] * d[i] <= 0f) ? 0f : (d[i - 1] + d[i]) / 2f;
        }

        // 3) Fritsch–Carlson 제한: α² + β² > 9 이면 접선을 줄여
        //    구간 내 단조성(= 오버슈트 없음)을 보장.
        for (int i = 0; i < n - 1; i++) {
            if (d[i] == 0f) {
                m[i] = 0f;
                m[i + 1] = 0f;
                continue;
            }
            float a = m[i] / d[i];
            float b = m[i + 1] / d[i];
            float s = a * a + b * b;
            if (s > 9f) {
                float t = 3f / (float) Math.sqrt(s);
                m[i] = t * a * d[i];
                m[i + 1] = t * b * d[i];
            }
        }

        // 4) 구간별 cubic Hermite 평가
        int outLen = (n - 1) * subdivide + 1;
        float[][] out = new float[outLen][2];
        int idx = 0;
        for (int i = 0; i < n - 1; i++) {
            for (int s = 0; s < subdivide; s++) {
                float t  = (float) s / subdivide;
                float t2 = t * t;
                float t3 = t2 * t;
                float h00 = 2f * t3 - 3f * t2 + 1f;
                float h10 = t3 - 2f * t2 + t;
                float h01 = -2f * t3 + 3f * t2;
                float h11 = t3 - t2;
                out[idx][0] = xs[i] + h[i] * t;
                out[idx][1] = h00 * ys[i] + h10 * h[i] * m[i]
                            + h01 * ys[i + 1] + h11 * h[i] * m[i + 1];
                idx++;
            }
        }
        out[outLen - 1][0] = xs[n - 1];
        out[outLen - 1][1] = ys[n - 1];
        return out;
    }
}
