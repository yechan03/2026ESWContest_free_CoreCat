package com.example.in_gps.fragment;

import android.graphics.Color;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import com.example.in_gps.R;
import com.example.in_gps.model.TemperatureModel;
import com.example.in_gps.util.ChartInterpolation;
import com.example.in_gps.util.TempMarkerView;
import com.example.in_gps.viewmodel.SensorDetailTestViewModel;
import com.github.mikephil.charting.charts.LineChart;
import com.github.mikephil.charting.components.Legend;
import com.github.mikephil.charting.components.LimitLine;
import com.github.mikephil.charting.components.XAxis;
import com.github.mikephil.charting.components.YAxis;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.data.LineData;
import com.github.mikephil.charting.data.LineDataSet;
import com.github.mikephil.charting.formatter.ValueFormatter;
import com.github.mikephil.charting.interfaces.datasets.ILineDataSet;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * "실시간 상세보기" 다이얼로그의 본문 화면.
 *
 * 진입 경로: SensorDetailFragment의 btn_realtime_detail
 *            → RealtimeDetailDialogFragment(DialogFragment) → 이 Fragment.
 * (디바이스 목록에서 디바이스를 클릭하면 SensorDetailFragment로 간다 — 이 화면이 아니다.)
 *
 * core_temp는 여기서 표시하지 않는다(SensorDetailFragment의 tvAiTemp 전용).
 *
 *  - /temperature?device_id=X&limit=N  (1초 주기 polling)
 *  - 차트 1: Thermistor 2개 (temp1 / temp2)
 *  - 차트 2: ADXL335 RMS 3개 (rms_x / rms_y / rms_z, mg)
 *  - 현재값 카드: 가장 최신 1건
 */
public class SensorDetailTestFragment extends Fragment {

    private static final String ARG_DEVICE_ID = "device_id";

    private static final int COLOR_TEMP1 = Color.parseColor("#FF5722");
    private static final int COLOR_TEMP2 = Color.parseColor("#2196F3");
    private static final int COLOR_RMSX  = Color.parseColor("#E53935");
    private static final int COLOR_RMSY  = Color.parseColor("#43A047");
    private static final int COLOR_RMSZ  = Color.parseColor("#1E88E5");
    private static final int COLOR_RMS_TOTAL = Color.parseColor("#8E24AA");
    private static final int COLOR_AXIS  = Color.parseColor("#9E9E9E");
    private static final int COLOR_DANGER = Color.parseColor("#E53935");
    /** 재연결(디바이스 전원 순환) 지점 마커 — 기간 차트의 '연결 끊김' 이벤트 색과 통일. */
    private static final int COLOR_DISC  = Color.parseColor("#757575");

    /** (구) Temperature SMA window. EMA 전환 후 미사용 — 되돌릴 때 참고용으로 남김. */
    private static final int MA_WINDOW = 3;

    /** Temperature EMA 평활 계수. y[n] = α·x[n] + (1−α)·y[n−1].
        α 클수록 반응적(지연↓·잡음↑), 작을수록 평활(지연↑·잡음↓).
        α=0.45 → 등가 SMA 윈도우 ≈ 3~4, 위상 지연 ≈ 1~1.5s.
        써미스터가 우레탄폼에 묻혀 물리적 열 시상수가 크므로(신호가 이미 부드러움)
        가벼운 EMA로도 ADC 지터는 충분히 억제됨. 근거: Docs/온도_응답지연_분석.md. */
    private static final float EMA_ALPHA = 0.45f;

    /** 재연결 판정용 데이터 공백 임계값(ms). 정상 주기는 1초이므로 5초 이상이면 재부팅/캘리브레이션 공백으로 본다. */
    private static final long RECONNECT_GAP_MS = 5000L;
    private static final SimpleDateFormat TS_FMT =
            new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);

    /** 온도 차트 Y축 최소 표시 폭(°C). 데이터 변동이 이보다 작으면 이 폭으로 확장해
        써미스터 jitter가 자동 스케일에 의해 과장돼 보이지 않게 한다. */
    private static final float TEMP_MIN_RANGE = 10f;

    /** ADXL335 진동 차트 Y축 범위(mg). 기본 천장을 고정해 진동이 작을 때 자동 스케일이
        미세 노이즈를 과장하는 문제를 막는다. 단, 데이터가 천장을 넘으면 그때만 상한을
        데이터에 맞춰 확장한다(아래로는 항상 0mg 시작).
        축별(X/Y/Z)은 150mg, 합성 √(X²+Y²+Z²)은 세 축 합이라 더 큰 250mg를 기본 천장으로 둔다. */
    private static final float VIB_Y_MIN = 0f;
    private static final float VIB_Y_BASE_MAX_AXIS  = 150f;
    private static final float VIB_Y_BASE_MAX_TOTAL = 250f;
    /** 데이터가 천장을 넘었을 때 상단 여백 비율. */
    private static final float VIB_Y_PAD = 0.10f;

    /** monotone cubic 보간 분할 수. 인접 raw 점 사이에 SPLINE_SUBDIVIDE개의 보간 점을 끼움. */
    private static final int SPLINE_SUBDIVIDE = 4;

    /** 차트 X축 고정 폭(=sliding window 크기). ViewModel의 limit과 동일해야 함. */
    private static final int CHART_X_RANGE = SensorDetailTestViewModel.WINDOW_SIZE;

    private SensorDetailTestViewModel viewModel;

    private LineChart chartTemperature;
    private LineChart chartVibrationTotal;
    private LineChart chartVibrationX, chartVibrationY, chartVibrationZ;
    private TextView tvDeviceId, tvLastUpdate;
    private TextView tvNowTemp1, tvNowTemp2;
    private TextView tvNowRmsX, tvNowRmsY, tvNowRmsZ;

    private final ArrayList<String> xLabels = new ArrayList<>();

    public static SensorDetailTestFragment newInstance(String deviceId) {
        SensorDetailTestFragment fragment = new SensorDetailTestFragment();
        Bundle args = new Bundle();
        args.putString(ARG_DEVICE_ID, deviceId);
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_sensor_detail_test, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        tvDeviceId       = view.findViewById(R.id.tv_device_id);
        tvLastUpdate     = view.findViewById(R.id.tv_last_update);
        tvNowTemp1       = view.findViewById(R.id.tv_now_temp1);
        tvNowTemp2       = view.findViewById(R.id.tv_now_temp2);
        tvNowRmsX        = view.findViewById(R.id.tv_now_rms_x);
        tvNowRmsY        = view.findViewById(R.id.tv_now_rms_y);
        tvNowRmsZ        = view.findViewById(R.id.tv_now_rms_z);
        chartTemperature    = view.findViewById(R.id.chart_temperature);
        chartVibrationTotal = view.findViewById(R.id.chart_vibration_total);
        chartVibrationX     = view.findViewById(R.id.chart_vibration_x);
        chartVibrationY     = view.findViewById(R.id.chart_vibration_y);
        chartVibrationZ     = view.findViewById(R.id.chart_vibration_z);

        setupChart(chartTemperature, "°C");
        setupChart(chartVibrationTotal, "mg");
        setupChart(chartVibrationX, "mg");
        setupChart(chartVibrationY, "mg");
        setupChart(chartVibrationZ, "mg");

        // 벤치마킹 B안(Netatmo): 온도 차트 터치 스크럽 — 시각·온도 말풍선
        chartTemperature.setHighlightPerTapEnabled(true);
        chartTemperature.setHighlightPerDragEnabled(true);
        TempMarkerView marker = new TempMarkerView(requireContext(), (e, h) -> {
            String time = "";
            int i = Math.round(e.getX());
            if (i >= 0 && i < xLabels.size()) time = xLabels.get(i);
            String name = "";
            if (chartTemperature.getData() != null && h.getDataSetIndex() >= 0
                    && h.getDataSetIndex() < chartTemperature.getData().getDataSetCount()) {
                name = chartTemperature.getData().getDataSetByIndex(h.getDataSetIndex()).getLabel();
            }
            return String.format(Locale.US, "%s %.1f°C%s",
                    name, e.getY(), time.isEmpty() ? "" : " · " + time);
        });
        marker.setChartView(chartTemperature);
        chartTemperature.setMarker(marker);

        String deviceId = requireArguments().getString(ARG_DEVICE_ID, "--");
        tvDeviceId.setText(deviceId);

        viewModel = new ViewModelProvider(this,
                new SensorDetailTestViewModel.Factory(deviceId))
                .get(SensorDetailTestViewModel.class);

        viewModel.getRecentData().observe(getViewLifecycleOwner(), this::onDataChanged);
    }

    // 폴링은 화면이 보이는 동안만 (다이얼로그가 닫히면 ViewModel과 함께 정리)
    @Override
    public void onStart() {
        super.onStart();
        if (viewModel != null) viewModel.startPolling();
    }

    @Override
    public void onStop() {
        super.onStop();
        if (viewModel != null) viewModel.stopPolling();
    }

    // ── 차트 공용 설정 ──────────────────────────────────────────────
    private void setupChart(LineChart chart, String yUnit) {
        chart.setBackgroundColor(Color.TRANSPARENT);
        chart.getDescription().setEnabled(false);
        chart.setNoDataText("데이터를 불러오는 중...");
        chart.setTouchEnabled(true);
        chart.setDragEnabled(true);
        chart.setScaleEnabled(true);
        chart.setDrawBorders(false);
        chart.setExtraBottomOffset(8f);

        chart.getLegend().setTextColor(COLOR_AXIS);
        chart.getLegend().setTextSize(11f);

        XAxis xAxis = chart.getXAxis();
        xAxis.setPosition(XAxis.XAxisPosition.BOTTOM);
        xAxis.setGranularity(1f);
        xAxis.setDrawGridLines(false);
        xAxis.setDrawAxisLine(false);
        xAxis.setLabelRotationAngle(0f);          // 수평 라벨(대각선 X)
        xAxis.setLabelCount(5, false);            // 수평에서 겹치지 않게 라벨 수 제한
        xAxis.setAvoidFirstLastClipping(true);
        xAxis.setTextColor(COLOR_AXIS);
        xAxis.setTextSize(10f);
        xAxis.setValueFormatter(new ValueFormatter() {
            @Override
            public String getFormattedValue(float value) {
                int i = Math.round(value);
                return (i >= 0 && i < xLabels.size()) ? xLabels.get(i) : "";
            }
        });

        YAxis yAxis = chart.getAxisLeft();
        yAxis.setDrawGridLines(true);
        yAxis.setGridColor(Color.parseColor("#22000000"));
        yAxis.setDrawAxisLine(false);
        yAxis.setTextColor(COLOR_AXIS);
        yAxis.setTextSize(10f);
        final String unit = yUnit;
        yAxis.setValueFormatter(new ValueFormatter() {
            @Override
            public String getFormattedValue(float value) {
                return String.format(Locale.US, "%.0f%s", value, unit);
            }
        });

        chart.getAxisRight().setEnabled(false);
    }

    // ── 데이터 갱신 ────────────────────────────────────────────────
    private void onDataChanged(List<TemperatureModel> recentDesc) {
        if (recentDesc == null || recentDesc.isEmpty()) return;

        // 서버는 created_at DESC로 반환. 차트에는 시간순 ASC로 그려야 자연스러움.
        List<TemperatureModel> ordered = new ArrayList<>(recentDesc);
        Collections.reverse(ordered);

        // 재연결 감지(디바이스 재부팅 등으로 5초 이상 데이터 공백): 예전엔 공백 이전 구간을
        // 통째로 버리고 x=0부터 새로 그렸음 — 그러면 재부팅 직전 60초를 다시 볼 방법이 없었다.
        // 지금은 버리지 않고 유지하되, renderTemperatureChart에서 공백 지점마다 선을 끊어
        // 그린다(오도 연결 방지) — 창(window) 안에 남아 있는 한 스크럽/확대로 계속 확인 가능.
        if (ordered.isEmpty()) return;

        // 현재값(가장 최신) — DESC 첫 row
        TemperatureModel latest = recentDesc.get(0);
        tvNowTemp1.setText(String.format(Locale.US, "%.2f °C", latest.temp1));
        tvNowTemp2.setText(String.format(Locale.US, "%.2f °C", latest.temp2));
        tvNowRmsX.setText(formatMg(latest.rmsX));
        tvNowRmsY.setText(formatMg(latest.rmsY));
        tvNowRmsZ.setText(formatMg(latest.rmsZ));
        tvLastUpdate.setText("최근 갱신: " + extractHHmmss(latest.createdAt));

        // X축 라벨 재구축 (HH:mm:ss).
        // X축은 [0, CHART_X_RANGE-1]로 고정. 좌측 정렬 — 데이터는 x=0부터 채우고
        // 부족한 만큼(n<60) 뒤쪽을 빈 라벨로 패딩한다. 그래야 데이터가 차오르는 동안에도
        // 축 눈금이 흔들리지 않음. 최신값은 우측이 아니라 "현재까지 채워진 데이터의 끝"에 위치.
        xLabels.clear();
        for (TemperatureModel m : ordered) {
            xLabels.add(extractHHmmss(m.createdAt));
        }
        int pad = Math.max(0, CHART_X_RANGE - ordered.size());
        for (int i = 0; i < pad; i++) xLabels.add("");

        renderTemperatureChart(ordered);
        renderVibrationCharts(ordered);
    }

    private void renderTemperatureChart(List<TemperatureModel> ordered) {
        int n = ordered.size();
        float[] temp1 = new float[n];
        float[] temp2 = new float[n];
        for (int i = 0; i < n; i++) {
            TemperatureModel m = ordered.get(i);
            temp1[i] = m.temp1;
            temp2[i] = m.temp2;
        }

        // 재연결(디바이스 전원 순환) 지점에서 run을 나눈다. 예전엔 마지막 run만 남기고
        // 이전 구간을 버렸는데, 그러면 재부팅 직전 60초는 다시 볼 방법이 없었다.
        // 이제는 모든 run을 유지하고, run 사이에서만 선을 끊어 그려 오도 연결(시간축을
        // 무시한 채 공백 전후를 이어버리는 것)을 막는다.
        List<int[]> runs = splitRuns(ordered);

        // Thermistor raw에는 ADC 양자화 + 환경 잡음에 의한 jitter가 섞여 시각적으로 떠 보임.
        // EMA(지수이동평균)로 고주파 성분을 누른다. 최신값 가중이라 SMA보다 반응적.
        // 위상 지연 ≈ (1−α)/α 샘플 ≈ 1.2s (α=0.45). run별로 독립 적용(공백을 건너뛰어
        // 평활하지 않도록) — 자세한 분석은 Docs/온도_응답지연_분석.md.
        float[] temp1Smoothed = new float[n];
        float[] temp2Smoothed = new float[n];
        for (int[] run : runs) {
            int s = run[0], e = run[1];
            float[] ema1 = ema(java.util.Arrays.copyOfRange(temp1, s, e), EMA_ALPHA);
            float[] ema2 = ema(java.util.Arrays.copyOfRange(temp2, s, e), EMA_ALPHA);
            System.arraycopy(ema1, 0, temp1Smoothed, s, e - s);
            System.arraycopy(ema2, 0, temp2Smoothed, s, e - s);
        }

        // 좌측 정렬: 데이터는 x=0부터 채우고 우측은 데이터 수가 부족할 때 비워둔다.
        // 최신값(=ordered의 마지막 원소)은 x = n - 1 에 위치.
        int offset = 0;

        /* ── Monotone cubic (Fritsch–Carlson) 보간 ─────────────────────────────────
         * EMA 결과 점 사이를 보간해 dense points를 만들고 LINEAR로 잇는다. run별로 따로
         * 보간해 공백 너머 두 점을 하나의 곡선으로 잇지 않는다(그러면 재부팅으로 몇 분 빈
         * 구간이 1초짜리 급경사처럼 그려져 오해를 부름).
         *
         * Catmull-Rom → monotone cubic 교체 이유: Catmull-Rom은 피크·평탄 경계에서
         * 인접 점 범위를 벗어나는 오버슈트/물결(ripple)이 생겨 데이터에 없는 온도값이
         * 그려질 수 있다. monotone cubic은 모든 원본 점을 정확히 지나면서 인접 두 점의
         * [min, max]를 절대 벗어나지 않는다(위험 임계선 거짓 초과 불가).
         *
         * LineDataSet.Mode.CUBIC_BEZIER를 안 쓰는 이유: 렌더 버그(점만 남고 선 미연결)
         * + 오버슈트. dense 점을 직접 만들어 LINEAR로 그리는 방식은 이와 무관. */
        List<LineDataSet> seg1List = new ArrayList<>();
        List<LineDataSet> seg2List = new ArrayList<>();
        for (int ri = 0; ri < runs.size(); ri++) {
            int[] run = runs.get(ri);
            int s = run[0], e = run[1];
            int len = e - s;
            // legend에는 첫 run에만 라벨을 달아 중복 항목이 생기지 않게 한다.
            String label1 = (ri == 0) ? "표면" : "";
            String label2 = (ri == 0) ? "외부" : "";
            LineDataSet seg1 = makeLineDataSet(label1, COLOR_TEMP1);
            LineDataSet seg2 = makeLineDataSet(label2, COLOR_TEMP2);
            seg1.setDrawCircles(false);
            seg2.setDrawCircles(false);
            // 보간 점은 실제 측정값이 아니므로 스크럽은 별도 scrubSet(정수 x)에만 스냅
            seg1.setHighlightEnabled(false);
            seg2.setHighlightEnabled(false);
            if (len == 1) {
                seg1.addEntry(new Entry(offset + s, temp1Smoothed[s]));
                seg2.addEntry(new Entry(offset + s, temp2Smoothed[s]));
            } else {
                float[] xs = new float[len];
                float[] ys1 = new float[len];
                float[] ys2 = new float[len];
                for (int k = 0; k < len; k++) {
                    xs[k] = s + k;
                    ys1[k] = temp1Smoothed[s + k];
                    ys2[k] = temp2Smoothed[s + k];
                }
                float[][] d1 = ChartInterpolation.monotoneCubicDense(xs, ys1, SPLINE_SUBDIVIDE);
                float[][] d2 = ChartInterpolation.monotoneCubicDense(xs, ys2, SPLINE_SUBDIVIDE);
                for (float[] p : d1) seg1.addEntry(new Entry(offset + p[0], p[1]));
                for (float[] p : d2) seg2.addEntry(new Entry(offset + p[0], p[1]));
            }
            seg1List.add(seg1);
            seg2List.add(seg2);
        }

        // 현재 시점 마커: spline 곡선은 점이 모두 꺼져 있어 "지금 값이 어디인지" 직관적으로
        // 보이지 않음. 별도 single-point LineDataSet으로 가장 최신 raw 위치(=좌측 정렬에선
        // 데이터가 차오른 끝, 즉 x = n - 1)에 큰 원만 찍어 현재값을 강조한다. 선과 legend는 숨김.
        float latestX = offset + (n - 1);
        LineDataSet marker1 = makeLatestMarker(COLOR_TEMP1, latestX, temp1Smoothed[n - 1]);
        LineDataSet marker2 = makeLatestMarker(COLOR_TEMP2, latestX, temp2Smoothed[n - 1]);

        // Y축 범위를 먼저 확정 — 위험구역(A안) 음영의 상단(y축 최대)이 필요함
        applyTempYAxisRange(temp1Smoothed, temp2Smoothed);

        List<ILineDataSet> sets = new ArrayList<>();

        // 벤치마킹 A안(SensorPush): 위험 임계선 위 영역 옅은 빨강 음영.
        // 실시간 뷰는 y축이 데이터 기준이라, 데이터가 임계 근처일 때만 구역이 보인다.
        float threshold = getDangerThresholdC();
        YAxis yAxis = chartTemperature.getAxisLeft();
        if (yAxis.getAxisMaximum() > threshold) {
            sets.add(dangerZone(-0.5f, CHART_X_RANGE - 0.5f, yAxis.getAxisMaximum(), threshold));
        }
        addDangerLimitLine(yAxis, threshold);

        sets.addAll(seg1List);
        sets.addAll(seg2List);
        sets.add(marker1);
        sets.add(marker2);

        // 재연결 지점 표시: 기간 차트의 '연결 끊김' 이벤트와 같은 회색 링 마커.
        // run이 2개 이상일 때만(=창 안에서 실제로 재연결이 있었을 때만) 찍는다.
        for (int ri = 1; ri < runs.size(); ri++) {
            int s = runs.get(ri)[0];
            sets.add(reconnectMarker(offset + s, temp1Smoothed[s]));
        }

        // 벤치마킹 B안(Netatmo): 터치 스크럽 — EMA 결과(정수 x)에만 스냅
        sets.add(scrubSet(temp1Smoothed, "표면"));
        sets.add(scrubSet(temp2Smoothed, "외부"));

        chartTemperature.setData(new LineData(sets));
        chartTemperature.getXAxis().setAxisMinimum(-0.5f);
        chartTemperature.getXAxis().setAxisMaximum(CHART_X_RANGE - 0.5f);
        chartTemperature.notifyDataSetChanged();
        chartTemperature.invalidate();
    }

    /** 재연결(디바이스 전원 순환) 지점 마커 — 데이터 값이 아니라 이벤트이므로 링(○)으로. */
    private LineDataSet reconnectMarker(float x, float y) {
        List<Entry> pts = new ArrayList<>(1);
        pts.add(new Entry(x, y));
        LineDataSet ds = new LineDataSet(pts, "");
        ds.setColor(Color.TRANSPARENT);
        ds.setLineWidth(0f);
        ds.setDrawCircles(true);
        ds.setCircleColor(COLOR_DISC);
        ds.setCircleRadius(5f);
        ds.setDrawCircleHole(true);
        ds.setCircleHoleRadius(2.5f);
        ds.setCircleHoleColor(Color.WHITE);
        ds.setDrawValues(false);
        ds.setHighlightEnabled(false);
        ds.setForm(Legend.LegendForm.NONE);
        return ds;
    }

    /** 위험 임계온도(°C). 설정에서 조절, 기본 40°C. */
    private float getDangerThresholdC() {
        return requireContext()
                .getSharedPreferences("in_gps_prefs", android.content.Context.MODE_PRIVATE)
                .getFloat("danger_threshold_c", 40f);
    }

    /** 위험 임계선(가는 빨강 점선). y축 범위 안에 있을 때만 그려진다. */
    private void addDangerLimitLine(YAxis yAxis, float threshold) {
        yAxis.removeAllLimitLines();
        LimitLine line = new LimitLine(threshold,
                String.format(Locale.US, "위험 %.0f°", threshold));
        line.setLineColor(COLOR_DANGER);
        line.setLineWidth(1.2f);
        line.enableDashedLine(6f, 5f, 0f);
        line.setLabelPosition(LimitLine.LimitLabelPosition.RIGHT_TOP);
        line.setTextColor(COLOR_DANGER);
        line.setTextSize(9f);
        yAxis.addLimitLine(line);
    }

    /** A안: 임계선 위 영역을 옅은 빨강 면으로 채우는 데이터셋(선 투명). */
    private LineDataSet dangerZone(float xMin, float xMax, float top, float threshold) {
        List<Entry> pts = new ArrayList<>(2);
        pts.add(new Entry(xMin, top));
        pts.add(new Entry(xMax, top));
        LineDataSet ds = new LineDataSet(pts, "");
        ds.setColor(Color.TRANSPARENT);
        ds.setLineWidth(0f);
        ds.setDrawCircles(false);
        ds.setDrawValues(false);
        ds.setHighlightEnabled(false);
        ds.setDrawFilled(true);
        ds.setFillColor(COLOR_DANGER);
        ds.setFillAlpha(26);
        ds.setFillFormatter((d, p) -> threshold);
        ds.setForm(Legend.LegendForm.NONE);
        return ds;
    }

    /** B안: 스크럽 전용 투명 데이터셋 — EMA 평활값의 정수 x 위치에만 스냅. */
    private LineDataSet scrubSet(float[] values, String label) {
        List<Entry> pts = new ArrayList<>(values.length);
        for (int i = 0; i < values.length; i++) pts.add(new Entry(i, values[i]));
        LineDataSet ds = new LineDataSet(pts, label);
        ds.setColor(Color.TRANSPARENT);
        ds.setLineWidth(0f);
        ds.setDrawCircles(false);
        ds.setDrawValues(false);
        ds.setHighlightEnabled(true);
        ds.setDrawHorizontalHighlightIndicator(false);
        ds.setDrawVerticalHighlightIndicator(true);
        ds.setHighLightColor(COLOR_AXIS);
        ds.setHighlightLineWidth(1f);
        ds.setForm(Legend.LegendForm.NONE);
        return ds;
    }

    /**
     * 온도 차트 Y축 범위를 최소 TEMP_MIN_RANGE(°C) 폭으로 보정.
     * 데이터 변동이 그보다 작으면 평균을 중심으로 폭을 강제 확장해, 작은 jitter가
     * 자동 스케일에 의해 화면 가득 확대돼 노이즈처럼 보이는 것을 막는다.
     */
    private void applyTempYAxisRange(float[] a, float[] b) {
        float max = -Float.MAX_VALUE, min = Float.MAX_VALUE;
        boolean has = false;
        for (float[] arr : new float[][]{a, b}) {
            for (float v : arr) {
                if (v > max) max = v;
                if (v < min) min = v;
                has = true;
            }
        }

        YAxis yAxis = chartTemperature.getAxisLeft();
        if (!has) {
            yAxis.resetAxisMinimum();
            yAxis.resetAxisMaximum();
            return;
        }

        float range = max - min;
        if (range < TEMP_MIN_RANGE) {
            float center = (max + min) / 2f;
            max = center + TEMP_MIN_RANGE / 2f;
            min = center - TEMP_MIN_RANGE / 2f;
            range = TEMP_MIN_RANGE;
        }
        float pad = range * 0.15f;   // 위아래 15% 여백
        yAxis.setAxisMinimum(min - pad);
        yAxis.setAxisMaximum(max + pad);
    }

    /** ADXL335 RMS를 축별로 분리된 3개 차트에 각각 렌더링.
        축마다 Y축을 독립적으로 auto-scale하므로 진폭이 작은 축도 또렷이 보인다. */
    private void renderVibrationCharts(List<TemperatureModel> ordered) {
        renderTotalChart(ordered);
        renderOneAxis(chartVibrationX, ordered, Axis.X, "RMS X", COLOR_RMSX);
        renderOneAxis(chartVibrationY, ordered, Axis.Y, "RMS Y", COLOR_RMSY);
        renderOneAxis(chartVibrationZ, ordered, Axis.Z, "RMS Z", COLOR_RMSZ);
    }

    /** 합성 진동 크기 = √(X² + Y² + Z²). 방향 무관한 종합 진동 레벨(overall level)을
        한 줄로 보여준다. X/Y/Z 중 하나라도 없는 샘플은 건너뛴다. */
    private void renderTotalChart(List<TemperatureModel> ordered) {
        LineDataSet ds = makeLineDataSet("RMS √(X²+Y²+Z²)", COLOR_RMS_TOTAL);

        Float lastVal = null;
        int lastIdx = -1;
        float dataMax = 0f;
        for (int i = 0; i < ordered.size(); i++) {
            TemperatureModel m = ordered.get(i);
            if (m.rmsX == null || m.rmsY == null || m.rmsZ == null) continue;
            double x = m.rmsX, y = m.rmsY, z = m.rmsZ;
            float mag = (float) Math.sqrt(x * x + y * y + z * z);
            ds.addEntry(new Entry(i, mag));
            if (mag > dataMax) dataMax = mag;
            lastVal = mag;
            lastIdx = i;
        }

        List<ILineDataSet> sets = new ArrayList<>();
        sets.add(ds);
        if (lastVal != null) {
            sets.add(makeLatestMarker(COLOR_RMS_TOTAL, lastIdx, lastVal));
        }

        chartVibrationTotal.setData(new LineData(sets));
        chartVibrationTotal.getXAxis().setAxisMinimum(-0.5f);
        chartVibrationTotal.getXAxis().setAxisMaximum(CHART_X_RANGE - 0.5f);
        applyVibYAxis(chartVibrationTotal, dataMax, VIB_Y_BASE_MAX_TOTAL);
        chartVibrationTotal.notifyDataSetChanged();
        chartVibrationTotal.invalidate();
    }

    private enum Axis { X, Y, Z }

    private void renderOneAxis(LineChart chart, List<TemperatureModel> ordered,
                               Axis axis, String label, int color) {
        LineDataSet ds = makeLineDataSet(label, color);

        // 좌측 정렬: 데이터는 x=0부터 채움. n < CHART_X_RANGE인 동안은 우측이 비어 있다.
        Integer lastVal = null;
        int lastIdx = -1;
        float dataMax = 0f;
        for (int i = 0; i < ordered.size(); i++) {
            TemperatureModel m = ordered.get(i);
            Integer v;
            switch (axis) {
                case X:  v = m.rmsX; break;
                case Y:  v = m.rmsY; break;
                default: v = m.rmsZ; break;
            }
            if (v != null) {
                ds.addEntry(new Entry(i, v));
                if (v > dataMax) dataMax = v;
                lastVal = v;
                lastIdx = i;
            }
        }

        List<ILineDataSet> sets = new ArrayList<>();
        sets.add(ds);
        // 현재값(가장 최신) 강조 마커
        if (lastVal != null) {
            sets.add(makeLatestMarker(color, lastIdx, lastVal.floatValue()));
        }

        chart.setData(new LineData(sets));
        chart.getXAxis().setAxisMinimum(-0.5f);
        chart.getXAxis().setAxisMaximum(CHART_X_RANGE - 0.5f);
        applyVibYAxis(chart, dataMax, VIB_Y_BASE_MAX_AXIS);
        chart.notifyDataSetChanged();
        chart.invalidate();
    }

    /** 진동 차트 Y축: 항상 0mg 시작. 기본 상한은 baseMax로 고정하되,
        데이터가 그 천장을 넘으면 그때만 상한을 dataMax + 여백으로 확장한다. */
    private void applyVibYAxis(LineChart chart, float dataMax, float baseMax) {
        float top = baseMax;
        if (dataMax > baseMax) {
            top = dataMax * (1f + VIB_Y_PAD);
        }
        chart.getAxisLeft().setAxisMinimum(VIB_Y_MIN);
        chart.getAxisLeft().setAxisMaximum(top);
    }

    // ── 헬퍼 ───────────────────────────────────────────────────────
    private LineDataSet makeLineDataSet(String label, int color) {
        LineDataSet ds = new LineDataSet(new ArrayList<>(), label);
        ds.setColor(color);
        ds.setCircleColor(color);
        ds.setCircleRadius(2.5f);
        ds.setLineWidth(2f);
        ds.setDrawCircles(true);
        ds.setDrawCircleHole(false);
        ds.setDrawValues(false);
        ds.setMode(LineDataSet.Mode.LINEAR);
        return ds;
    }

    /**
     * 현재 시점(=가장 최신값) 위치에만 점 하나를 찍는 LineDataSet.
     * 선/값 라벨/하이라이트/legend는 모두 끄고 큰 원 1개만 표시.
     */
    private LineDataSet makeLatestMarker(int color, float x, float y) {
        List<Entry> single = new ArrayList<>(1);
        single.add(new Entry(x, y));
        LineDataSet marker = new LineDataSet(single, "");
        marker.setColor(Color.TRANSPARENT);
        marker.setLineWidth(0f);
        marker.setCircleColor(color);
        marker.setCircleRadius(5f);
        marker.setDrawCircles(true);
        marker.setDrawCircleHole(true);
        marker.setCircleHoleColor(Color.WHITE);
        marker.setCircleHoleRadius(2.5f);
        marker.setDrawValues(false);
        marker.setHighlightEnabled(false);
        marker.setForm(Legend.LegendForm.NONE);
        return marker;
    }

    private static String formatMg(Integer mg) {
        return mg == null ? "-- mg" : (mg + " mg");
    }

    /**
     * 길이 N의 시퀀스에 대한 trailing SMA. out[i] = mean(values[i-w+1 .. i]).
     * 시작 구간(i < w-1)은 가용한 점들만으로 평균(=ramp-up). 시퀀스 길이는 보존된다.
     */
    private static float[] movingAverage(float[] values, int window) {
        int n = values.length;
        float[] out = new float[n];
        if (n == 0 || window <= 1) {
            System.arraycopy(values, 0, out, 0, n);
            return out;
        }
        float sum = 0f;
        for (int i = 0; i < n; i++) {
            sum += values[i];
            if (i >= window) sum -= values[i - window];
            int cnt = Math.min(i + 1, window);
            out[i] = sum / cnt;
        }
        return out;
    }

    /**
     * 지수이동평균(EMA). out[0]=values[0], out[i]=α·values[i]+(1−α)·out[i−1].
     * SMA와 달리 최신값에 더 큰 가중치를 줘 반응이 빠르고, α 하나로 반응성↔평활을 조절.
     * 위상 지연 ≈ (1−α)/α 샘플. 시퀀스 길이는 보존된다.
     */
    private static float[] ema(float[] values, float alpha) {
        int n = values.length;
        float[] out = new float[n];
        if (n == 0) return out;
        if (alpha <= 0f || alpha >= 1f) {   // 방어: 범위 밖이면 평활 없이 통과
            System.arraycopy(values, 0, out, 0, n);
            return out;
        }
        out[0] = values[0];
        for (int i = 1; i < n; i++) {
            out[i] = alpha * values[i] + (1f - alpha) * out[i - 1];
        }
        return out;
    }

    /**
     * ASC 정렬 리스트를 5초 이상 공백(디바이스 재부팅 등) 기준으로 연속 구간(run)들로 나눈다.
     * 각 원소는 [startIndex, endIndexExclusive]. 예전의 trimToLastContiguousRun과 달리
     * 공백 이전 구간을 버리지 않는다 — 창(window)에 남아 있는 한 모든 run을 그대로 반환해,
     * renderTemperatureChart가 run 사이에서만 선을 끊어 그리도록 한다.
     */
    private List<int[]> splitRuns(List<TemperatureModel> asc) {
        List<int[]> runs = new ArrayList<>();
        if (asc.isEmpty()) return runs;
        int start = 0;
        for (int i = 1; i < asc.size(); i++) {
            long prev = parseEpochMs(asc.get(i - 1).createdAt);
            long cur  = parseEpochMs(asc.get(i).createdAt);
            if (prev > 0 && cur > 0 && (cur - prev) > RECONNECT_GAP_MS) {
                runs.add(new int[]{start, i});
                start = i;
            }
        }
        runs.add(new int[]{start, asc.size()});
        return runs;
    }

    private static long parseEpochMs(String ts) {
        if (ts == null || ts.length() < 19) return -1;
        try {
            return TS_FMT.parse(ts.replace('T', ' ').substring(0, 19)).getTime();
        } catch (Exception e) {
            return -1;
        }
    }

    /** "YYYY-MM-DD HH:MM:SS" 또는 ISO 형식에서 HH:MM:SS만 추출. */
    private static String extractHHmmss(String ts) {
        if (ts == null) return "";
        // 형식: "YYYY-MM-DDTHH:MM:SS" 또는 "YYYY-MM-DD HH:MM:SS"
        int len = ts.length();
        if (len >= 19) {
            return ts.substring(11, 19);
        }
        return ts;
    }
}
