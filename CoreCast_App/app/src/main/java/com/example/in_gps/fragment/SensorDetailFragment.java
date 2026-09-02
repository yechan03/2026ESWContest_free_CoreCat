package com.example.in_gps.fragment;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import com.example.in_gps.R;
import com.example.in_gps.model.TemperatureModel;
import com.example.in_gps.util.ChartInterpolation;
import com.example.in_gps.util.TempMarkerView;
import com.example.in_gps.viewmodel.SensorDetailViewModel;
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
import com.github.mikephil.charting.listener.ChartTouchListener;
import com.github.mikephil.charting.listener.OnChartGestureListener;
import com.github.mikephil.charting.utils.Transformer;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.chip.Chip;
import com.google.android.material.datepicker.CalendarConstraints;
import com.google.android.material.datepicker.MaterialDatePicker;

import androidx.core.util.Pair;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;

public class SensorDetailFragment extends Fragment {

    private static final String ARG_DEVICE_ID = "device_id";

    private static final SimpleDateFormat FMT_DATE  = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
    private static final SimpleDateFormat FMT_MONTH = new SimpleDateFormat("yyyy-MM",    Locale.US);

    private static final int COLOR_SURFACE   = Color.parseColor("#FF5722");
    private static final int COLOR_EXTERNAL = Color.parseColor("#2196F3");
    private static final int COLOR_AXIS    = Color.parseColor("#9E9E9E");
    private static final int COLOR_DANGER  = Color.parseColor("#E53935");
    private static final int COLOR_WARN    = Color.parseColor("#FF6D00");
    private static final int COLOR_DISC    = Color.parseColor("#757575");

    /** monotone cubic densify: 인접 버킷 점 사이 보간 분할 수(클수록 부드러움) */
    private static final int CURVE_SUBDIVIDE = 8;
    /** 분단위(1분 버킷) 곡선용 — 점이 이미 촘촘해 분할 수를 줄임 */
    private static final int CURVE_SUBDIVIDE_MINUTE = 2;

    /** 분단위 뷰 버킷 크기(분) 및 선 끊김 판정 간격(x=시 단위).
        서버가 1일 뷰에 이미 1m(분단위) 버킷으로 응답하므로(TemperatureRepository.BUCKET_MINUTE),
        여기서 5분으로 재집계하면 서버가 보내준 해상도의 4/5를 클라이언트가 그냥 버리는 셈이었다.
        1로 낮춰 서버 해상도를 그대로 사용 — 핀치줌으로 확대했을 때 실제로 더 자세히 보인다. */
    private static final int MINUTE_SLOT_MIN = 1;
    private static final float MINUTE_GAP_X = 0.2f;

    /** true = 분단위 뷰(x축 단위가 '시'인 float). 스크럽 시각 표기 등에 사용 */
    private boolean minuteAxis = false;

    private static final String[] MONTH_NAMES = {
        "1월","2월","3월","4월","5월","6월","7월","8월","9월","10월","11월","12월"
    };

    private SensorDetailViewModel viewModel;
    private LineChart chart;
    private RangeOverlay rangeOverlay;
    private TextView tvSurfaceTemp, tvExternalTemp, tvAiTemp, tvChartPeriodLabel, tvChartLegend;
    private Chip chipEventStatus;

    private final ArrayList<String> xLabels = new ArrayList<>();
    private int currentDays = 1;

    // 캘린더 기간(start~end) 모드
    private boolean rangeMode = false;
    private String rangeStartStr, rangeEndStr;
    private String rangeLabel = "";
    /** rangeMode일 때 조회 구간 일수(1 = 단일 날짜 → 시간별 뷰) */
    private int rangeSpanDays = 1;

    /** 집계뷰(C안) 센서 선택 — true=표면(temp1), false=외부(temp2) */
    private boolean selectedSensorSurface = true;
    private com.google.android.material.chip.ChipGroup chipGroupSensor;
    /** 센서 칩 전환 시 재조회 없이 다시 그리기 위한 마지막 데이터 캐시 */
    private List<TemperatureModel> lastChartItems;
    private final List<String> availableDates = new ArrayList<>();

    public static SensorDetailFragment newInstance(String deviceId) {
        SensorDetailFragment fragment = new SensorDetailFragment();
        Bundle args = new Bundle();
        args.putString(ARG_DEVICE_ID, deviceId);
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_sensor_detail, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        chart              = view.findViewById(R.id.line_chart);
        tvSurfaceTemp      = view.findViewById(R.id.tv_surface_temp);
        tvExternalTemp     = view.findViewById(R.id.tv_external_temp);
        tvAiTemp           = view.findViewById(R.id.tv_ai_temp);
        chipEventStatus    = view.findViewById(R.id.chip_ai_status);
        tvChartPeriodLabel = view.findViewById(R.id.tv_chart_period_label);
        tvChartLegend      = view.findViewById(R.id.tv_chart_legend);

        setupChart();
        setupRangeOverlay();

        String deviceId = requireArguments().getString(ARG_DEVICE_ID);
        viewModel = new ViewModelProvider(this, new SensorDetailViewModel.Factory(deviceId))
                .get(SensorDetailViewModel.class);

        viewModel.getTemperatureData().observe(getViewLifecycleOwner(), data -> {
            tvSurfaceTemp.setText(String.format(Locale.getDefault(), "%.1f°C", data.temp1));
            tvExternalTemp.setText(String.format(Locale.getDefault(), "%.1f°C", data.temp2));
            // AI 예측 코어 온도 — 서버가 null을 내려주면 값 없음으로 표시(0°C로 렌더링 금지)
            if (data.coreTemp != null) {
                tvAiTemp.setText(String.format(Locale.getDefault(), "%.1f°C", data.coreTemp));
            } else {
                tvAiTemp.setText("측정 불가");
            }
            updateEventChip(data.event);
        });

        viewModel.getPeriodData().observe(getViewLifecycleOwner(), this::reloadChart);

        view.findViewById(R.id.chip_1d).setOnClickListener(v -> loadPeriod(1));
        view.findViewById(R.id.chip_1w).setOnClickListener(v -> loadPeriod(7));
        view.findViewById(R.id.chip_1m).setOnClickListener(v -> loadPeriod(30));
        view.findViewById(R.id.chip_1y).setOnClickListener(v -> loadPeriod(365));

        MaterialButton btnPickRange = view.findViewById(R.id.btn_pick_range);
        if (btnPickRange != null) btnPickRange.setOnClickListener(v -> showRangePicker());

        // 실시간 상세보기(1초 polling 차트) — 다이얼로그로 표시.
        // 다이얼로그가 떠 있는 동안 이 화면의 5초 폴링은 정지(불필요한 중복 요청 방지),
        // 닫히면 fragment result로 재개한다.
        MaterialButton btnRealtime = view.findViewById(R.id.btn_realtime_detail);
        if (btnRealtime != null) btnRealtime.setOnClickListener(v -> {
            viewModel.stopPolling();
            RealtimeDetailDialogFragment.newInstance(deviceId)
                    .show(getChildFragmentManager(), "realtime_detail");
        });
        getChildFragmentManager().setFragmentResultListener(
                RealtimeDetailDialogFragment.RESULT_CLOSED, getViewLifecycleOwner(),
                (key, bundle) -> viewModel.startPolling());

        // 집계뷰(C안) 센서 선택 칩 — 캡슐 바를 한 센서씩 표시(둘 다 그리면 지저분)
        chipGroupSensor = view.findViewById(R.id.chip_group_sensor);
        Chip chipSurface  = view.findViewById(R.id.chip_sensor_surface);
        Chip chipExternal = view.findViewById(R.id.chip_sensor_external);
        if (chipSurface != null) chipSurface.setOnClickListener(v -> {
            selectedSensorSurface = true;
            if (lastChartItems != null) reloadChart(lastChartItems);
        });
        if (chipExternal != null) chipExternal.setOnClickListener(v -> {
            selectedSensorSurface = false;
            if (lastChartItems != null) reloadChart(lastChartItems);
        });

        // 운영 정보 카드: 측정 주기 = 펌웨어 광고 주기(1초 고정)
        TextView tvInterval = view.findViewById(R.id.tv_interval);
        if (tvInterval != null) tvInterval.setText("1초");
        TextView tvPeriod = view.findViewById(R.id.tv_period);

        // 캘린더 비활성 표시용 보유 날짜 목록 로드.
        // 운영 기간도 여기서 계산 — device.installed_on(시드 넣은 날짜)이 아니라
        // '실제 데이터가 존재하는 첫 날짜' 기준이라 데이터와 항상 일치한다.
        viewModel.getAvailableDates().observe(getViewLifecycleOwner(), dates -> {
            availableDates.clear();
            if (dates != null) availableDates.addAll(dates);
            if (tvPeriod != null) tvPeriod.setText(formatOperatingPeriod(availableDates));
        });
        viewModel.loadAvailableDates();

        loadPeriod(1);
    }

    /** 데이터 보유 날짜 목록의 최솟값 기준 "N일째 (yyyy.M.d~)". 데이터 없으면 "--". */
    private String formatOperatingPeriod(List<String> dates) {
        if (dates == null || dates.isEmpty()) return "--";
        String first = null;
        for (String d : dates) {
            if (d == null || d.length() < 10) continue;
            if (first == null || d.compareTo(first) < 0) first = d;   // "yyyy-MM-dd" 문자열 비교
        }
        if (first == null) return "--";
        try {
            Date d = FMT_DATE.parse(first.substring(0, 10));
            if (d == null) return "--";
            long days = (System.currentTimeMillis() - d.getTime()) / 86400000L + 1;
            SimpleDateFormat md = new SimpleDateFormat("yyyy.M.d", Locale.getDefault());
            return days + "일째 (" + md.format(d) + "~)";
        } catch (Exception e) {
            return "--";
        }
    }

    // 폴링은 화면이 보이는 동안만 (백그라운드 네트워크/배터리 낭비 방지)
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

    private void loadPeriod(int days) {
        currentDays = days;
        rangeMode = false;
        viewModel.loadPeriod(days);
    }

    // ── 차트 초기 설정 ──────────────────────────────────────────────

    private void setupChart() {
        chart.setBackgroundColor(Color.TRANSPARENT);
        chart.getDescription().setEnabled(false);
        chart.getLegend().setEnabled(false);      // 색상 안내는 아래 온도 카드가 대신함
        chart.setTouchEnabled(true);
        chart.setDragEnabled(true);
        chart.setScaleEnabled(true);
        chart.setPinchZoom(true);                 // 두 손가락 확대 시 정밀 확인
        chart.setDoubleTapToZoomEnabled(true);
        chart.setDrawBorders(false);
        chart.setExtraBottomOffset(8f);
        chart.setNoDataText("데이터를 불러오는 중...");

        // 벤치마킹 B안(Netatmo/Nest): 터치·드래그 스크럽 — 해당 지점 값 말풍선 표시
        chart.setHighlightPerTapEnabled(true);
        chart.setHighlightPerDragEnabled(true);
        TempMarkerView marker = new TempMarkerView(requireContext(), (e, h) -> {
            String x = "";
            if (minuteAxis) {
                // 분단위 뷰: x = 시(float) → HH:mm으로 정확한 시각 표기
                int hr = (int) e.getX();
                int mn = Math.round((e.getX() - hr) * 60f);
                if (mn == 60) { hr++; mn = 0; }
                x = String.format(Locale.getDefault(), "%02d:%02d", hr, mn);
            } else {
                int i = Math.round(e.getX());
                if (i >= 0 && i < xLabels.size()) x = xLabels.get(i);
            }
            String name = "";
            if (chart.getData() != null && h.getDataSetIndex() >= 0
                    && h.getDataSetIndex() < chart.getData().getDataSetCount()) {
                name = chart.getData().getDataSetByIndex(h.getDataSetIndex()).getLabel();
            }
            return String.format(Locale.getDefault(), "%s %.1f°C%s",
                    name, e.getY(), x.isEmpty() ? "" : " · " + x);
        });
        marker.setChartView(chart);
        chart.setMarker(marker);

        XAxis xAxis = chart.getXAxis();
        xAxis.setPosition(XAxis.XAxisPosition.BOTTOM);
        xAxis.setGranularity(1f);
        xAxis.setDrawGridLines(false);
        xAxis.setDrawAxisLine(false);
        xAxis.setLabelRotationAngle(0f);          // 수평 라벨(대각선 X) — 읽기 쉬움
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
        yAxis.setDrawGridLines(false);
        yAxis.setDrawAxisLine(false);
        yAxis.setTextColor(COLOR_AXIS);
        yAxis.setTextSize(10f);
        yAxis.setValueFormatter(new ValueFormatter() {
            @Override
            public String getFormattedValue(float value) {
                return (int) value + "°C";
            }
        });

        chart.getAxisRight().setEnabled(false);
    }

    // ── 차트 전체 재구성 ────────────────────────────────────────────

    private void reloadChart(List<TemperatureModel> items) {
        lastChartItems = items;   // 센서 칩 전환 시 재조회 없이 다시 그리기 위한 캐시
        chart.clear();
        xLabels.clear();

        LineDataSet set1    = makeLineDataSet("", COLOR_SURFACE);
        LineDataSet set1Max = makeLineDataSet("", COLOR_SURFACE);
        LineDataSet set1Min = makeLineDataSet("", COLOR_SURFACE);
        LineDataSet set2    = makeLineDataSet("", COLOR_EXTERNAL);
        LineDataSet set2Max = makeLineDataSet("", COLOR_EXTERNAL);
        LineDataSet set2Min = makeLineDataSet("", COLOR_EXTERNAL);
        LineDataSet setWarn = makeEventDataSet("경고",     COLOR_WARN);
        LineDataSet setDisc = makeEventDataSet("연결 끊김", COLOR_DISC);

        XAxis xAxis = chart.getXAxis();
        xAxis.removeAllLimitLines();

        // '현재 시각' 점선 인디케이터는 제거됨 — 위험 기준선 라벨과 겹쳐 가독성을 해쳤음.
        int totalSlots;
        if (rangeMode) {
            totalSlots = buildRangeSlots(items, set1, set1Max, set1Min, set2, set2Max, set2Min, setWarn, setDisc);
            xAxis.setLabelCount(Math.min(Math.max(totalSlots, 2), 7), false);
            if (tvChartPeriodLabel != null) tvChartPeriodLabel.setText(rangeLabel);
        } else if (currentDays == 1) {
            totalSlots = 24;
            buildMinuteSlots(items, set1, set1Max, set1Min, set2, set2Max, set2Min, setWarn, setDisc);
            xAxis.setLabelCount(7, false);
        } else if (currentDays == 7) {
            totalSlots = 7;
            buildDailySlots(items, set1, set1Max, set1Min, set2, set2Max, set2Min, setWarn, setDisc);
            xAxis.setLabelCount(7, false);
        } else if (currentDays <= 31) {
            totalSlots = 5;
            buildWeeklySlots(items, set1, set1Max, set1Min, set2, set2Max, set2Min, setWarn, setDisc);
            xAxis.setLabelCount(5, false);
        } else {
            totalSlots = 12;
            buildMonthlySlots(items, set1, set1Max, set1Min, set2, set2Max, set2Min, setWarn, setDisc);
            xAxis.setLabelCount(12, false);
        }
        // force=false: 라벨을 축 위에 '균등 분배'하지 않고 정수 슬롯 위치에만 찍는다.
        // (force=true는 라벨이 버킷과 어긋난 위치에 그려져 기간을 오독하게 만들던 원인)

        if (!rangeMode && tvChartPeriodLabel != null) {
            String label;
            switch (currentDays) {
                case 1:   label = "오늘 24시간 · 1분 평균·최대";        break;
                case 7:   label = "최근 7일 · 일별 평균·최대";          break;
                case 30:  label = "최근 30일 · 주별 평균·최대";         break;
                default:  label = "최근 12개월 · 월별 평균·최대";       break;
            }
            tvChartPeriodLabel.setText(label);
        }

        // 뷰 구분: 집계뷰(주/월/년, 기간>1일) = C안, 분단위 뷰(1일/단일날짜) = A+B
        boolean aggregate = rangeMode ? rangeSpanDays > 1 : currentDays >= 7;
        minuteAxis = !aggregate;

        if (minuteAxis) {
            // 분단위 뷰: x 단위 = 시(hour, float 0~24)
            chart.getXAxis().setAxisMinimum(-0.3f);
            chart.getXAxis().setAxisMaximum(24f);
        } else {
            chart.getXAxis().setAxisMinimum(-0.5f);
            chart.getXAxis().setAxisMaximum(totalSlots - 0.5f);
        }

        boolean surface = selectedSensorSurface;
        LineDataSet selAvg = surface ? set1    : set2;
        LineDataSet selMax = surface ? set1Max : set2Max;
        LineDataSet selMin = surface ? set1Min : set2Min;

        float threshold = getDangerThresholdC();
        // Y축 스케일: 집계뷰는 '선택된 센서'만 기준 — 숨겨진 센서의 이상값
        // (예: 외부 0.0°C 글리치)이 축을 0까지 끌어내려 캡슐이 눌려 보이는 것 방지.
        if (aggregate) {
            applyYAxisRange(selMax, selMin, selMax, selMin);
        } else {
            applyYAxisRange(set1Max, set1Min, set2Max, set2Min);
        }

        // 위험 기준선은 데이터가 임계 '근처'(5°C 이내)일 때만 축에 포함.
        // 항상 포함하면(예: 데이터 23°C·임계 50°C) 상단이 늘어나 데이터가 눌림.
        // 임계에서 먼 평상시엔 축을 데이터에 맞추고, 초과는 빨간 마커/캡슐 상단이 알림.
        YAxis yA = chart.getAxisLeft();
        if (yA.getAxisMaximum() >= threshold - 5f && yA.getAxisMaximum() < threshold + 1.5f) {
            yA.setAxisMaximum(threshold + 2f);
        }

        yA.removeAllLimitLines();
        boolean thresholdVisible = yA.getAxisMaximum() >= threshold;
        if (thresholdVisible) {
            LimitLine dangerLine = new LimitLine(threshold,
                    String.format(Locale.US, "위험 %.0f°", threshold));
            dangerLine.setLineColor(COLOR_DANGER);
            dangerLine.setLineWidth(1.5f);
            dangerLine.enableDashedLine(6f, 5f, 0f);
            dangerLine.setLabelPosition(LimitLine.LimitLabelPosition.RIGHT_TOP);
            dangerLine.setTextColor(COLOR_DANGER);
            dangerLine.setTextSize(9f);
            yA.addLimitLine(dangerLine);
        }

        List<ILineDataSet> dataSets = new ArrayList<>();

        // A안(SensorPush): 임계선 위 영역을 옅은 빨강 면으로 — 곡선/바 뒤에 깔리도록 먼저 추가
        if (thresholdVisible) {
            dataSets.add(dangerZone(chart.getXAxis().getAxisMinimum(),
                    chart.getXAxis().getAxisMaximum(), yA.getAxisMaximum(), threshold));
        }

        // 센서 선택 칩은 집계뷰에서만 노출 (캡슐 바 2세트는 UI가 지저분해지므로 1개씩 표시)
        if (chipGroupSensor != null) {
            chipGroupSensor.setVisibility(aggregate ? View.VISIBLE : View.GONE);
        }

        if (aggregate) {
            // C안(Apple 건강): 버킷별 min~max 캡슐 바(RangeOverlay가 그림) + 평균 점.
            // 선 보간이 없어 뾰족함·오버슈트 문제가 원천적으로 없음.
            // 써미스터가 2개라 캡슐을 겹쳐 그리면 지저분 → 선택 칩으로 한 센서씩 표시.
            int color = surface ? COLOR_SURFACE : COLOR_EXTERNAL;

            dataSets.add(bucketDots(selAvg.getValues(), color));
            rangeOverlay.update(selMax, selMin, color, threshold);

            LineDataSet danger = exceedanceMarkers(selMax, threshold);
            if (danger.getEntryCount() > 0) dataSets.add(danger);
            // 스크럽은 '최대'에 스냅 — 위험 판단 기준은 평균이 아니라 피크이므로
            dataSets.add(scrubSet(selMax, surface ? "표면 최대" : "외부 최대"));
        } else {
            // 평균(굵은 곡선) = 주 트렌드 · 최대(연한 얇은 곡선) = 범위/피크 참고.
            // 분단위(1분 버킷) 데이터라 gap 판정은 시간 단위(MINUTE_GAP_X)로.
            rangeOverlay.clearBars();
            for (LineDataSet seg : splitLine(set1Max, faint(COLOR_SURFACE),  1f,   false, MINUTE_GAP_X, CURVE_SUBDIVIDE_MINUTE)) dataSets.add(seg);
            for (LineDataSet seg : splitLine(set2Max, faint(COLOR_EXTERNAL), 1f,   false, MINUTE_GAP_X, CURVE_SUBDIVIDE_MINUTE)) dataSets.add(seg);
            for (LineDataSet seg : splitLine(set1,    COLOR_SURFACE,         2.5f, false, MINUTE_GAP_X, CURVE_SUBDIVIDE_MINUTE)) dataSets.add(seg);
            for (LineDataSet seg : splitLine(set2,    COLOR_EXTERNAL,        2.5f, false, MINUTE_GAP_X, CURVE_SUBDIVIDE_MINUTE)) dataSets.add(seg);

            LineDataSet danger1 = exceedanceMarkers(set1Max, threshold);
            LineDataSet danger2 = exceedanceMarkers(set2Max, threshold);
            if (danger1.getEntryCount() > 0) dataSets.add(danger1);
            if (danger2.getEntryCount() > 0) dataSets.add(danger2);

            // B안(Netatmo): 스크럽 전용 투명 데이터셋 — 원본 버킷 값에만 스냅.
            // 평균선·최대선 각각 스냅되며, 최대가 위험 판단 기준이라 최대도 반드시 포함.
            dataSets.add(scrubSet(set1,    "표면"));
            dataSets.add(scrubSet(set2,    "외부"));
            dataSets.add(scrubSet(set1Max, "표면 최대"));
            dataSets.add(scrubSet(set2Max, "외부 최대"));
        }

        // 이벤트 마커: 경고=표면(temp1) 임계 기반이고 마커 y좌표도 표면 평균이라,
        // 집계뷰에서 '외부' 선택 시 그리면 엉뚱한 높이에 떠 보인다 → 표면 선택 시에만 표시.
        // (시간별 뷰는 두 센서 곡선이 모두 있으므로 항상 표시)
        boolean showEvents = !aggregate || surface;
        if (showEvents && setWarn.getEntryCount() > 0) dataSets.add(setWarn);
        if (showEvents && setDisc.getEntryCount() > 0) dataSets.add(setDisc);

        chart.setData(new LineData(dataSets));
        chart.invalidate();
        rangeOverlay.invalidate();

        updateChartLegend(aggregate, surface);
    }

    /** 범례 캡션의 심볼을 실제 마커 색으로 채색.
        전부 회색이면 어떤 점이 어떤 의미인지 구분이 안 되던 문제 보정.
        집계뷰 = 선택 센서 색 1개, 분단위 뷰 = 표면·외부 2색. */
    private void updateChartLegend(boolean aggregate, boolean surface) {
        if (tvChartLegend == null) return;
        android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder();
        if (aggregate) {
            appendColored(sb, "● ", surface ? COLOR_SURFACE : COLOR_EXTERNAL);
        } else {
            appendColored(sb, "● ", COLOR_SURFACE);
            appendColored(sb, "● ", COLOR_EXTERNAL);
        }
        sb.append("평균  ·  ");
        appendColored(sb, "● ", COLOR_DANGER);
        sb.append("임계 초과  ·  ");
        appendColored(sb, "○ ", COLOR_WARN);
        sb.append("경고  ·  ");
        appendColored(sb, "○ ", COLOR_DISC);
        sb.append("연결 끊김");
        tvChartLegend.setText(sb);
    }

    private static void appendColored(android.text.SpannableStringBuilder sb, String s, int color) {
        int start = sb.length();
        sb.append(s);
        sb.setSpan(new android.text.style.ForegroundColorSpan(color), start, sb.length(),
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    /** 위험 임계온도(°C). 설정에서 조절, 기본 40°C. */
    private float getDangerThresholdC() {
        return requireContext()
                .getSharedPreferences("in_gps_prefs", android.content.Context.MODE_PRIVATE)
                .getFloat("danger_threshold_c", 40f);
    }

    /** 최대 온도가 임계값 이상인 지점에 빨간 마커 — 데이터 값이므로 꽉 찬 점(●). */
    private LineDataSet exceedanceMarkers(LineDataSet maxSet, float threshold) {
        List<Entry> pts = new ArrayList<>();
        for (Entry e : maxSet.getValues()) {
            if (e.getY() >= threshold) pts.add(new Entry(e.getX(), e.getY()));
        }
        LineDataSet ds = new LineDataSet(pts, "");
        ds.setColor(Color.TRANSPARENT);
        ds.setLineWidth(0f);
        ds.setDrawCircles(true);
        ds.setCircleColor(COLOR_DANGER);
        ds.setCircleRadius(4.5f);
        ds.setDrawCircleHole(false);
        ds.setDrawValues(false);
        ds.setHighlightEnabled(false);
        return ds;
    }

    /** 공백(maxGapX 초과)에서 끊고, 연속 구간(run)별로 부드러운 곡선을 만든다.
        고립된 단일 포인트(1점 run)는 그리지 않는다 — 집계뷰는 캡슐 바가 담당. */
    private List<LineDataSet> splitLine(LineDataSet src, int color, float width, boolean dashed,
                                        float maxGapX, int subdivide) {
        List<LineDataSet> out = new ArrayList<>();
        List<Entry> pts = src.getValues();
        if (pts.isEmpty()) return out;
        List<Entry> run = new ArrayList<>();
        float prevX = pts.get(0).getX();
        for (Entry e : pts) {
            if (!run.isEmpty() && e.getX() - prevX > maxGapX) {
                if (run.size() >= 2) out.add(styledSeg(smoothRun(run, subdivide), color, width, dashed));
                run = new ArrayList<>();
            }
            run.add(e);
            prevX = e.getX();
        }
        if (run.size() >= 2) out.add(styledSeg(smoothRun(run, subdivide), color, width, dashed));
        return out;
    }

    /** 연속 run(2점 이상)을 Fritsch–Carlson monotone cubic으로 densify.
        곡선이 모든 원본 점을 지나고 인접 두 점의 범위를 벗어나지 않으므로
        (오버슈트 없음) 위험 임계선을 '거짓 초과'하는 일이 없다.
        결과는 LINEAR로 렌더 — CUBIC_BEZIER 렌더 버그(점만 남고 선 미연결)와 무관. */
    private List<Entry> smoothRun(List<Entry> run, int subdivide) {
        int n = run.size();
        float[] xs = new float[n];
        float[] ys = new float[n];
        for (int i = 0; i < n; i++) {
            xs[i] = run.get(i).getX();
            ys[i] = run.get(i).getY();
        }
        float[][] dense = ChartInterpolation.monotoneCubicDense(xs, ys, subdivide);
        List<Entry> out = new ArrayList<>(dense.length);
        for (float[] p : dense) out.add(new Entry(p[0], p[1]));
        return out;
    }

    private LineDataSet styledSeg(List<Entry> pts, int color, float width, boolean dashed) {
        LineDataSet ds = new LineDataSet(new ArrayList<>(pts), "");
        ds.setColor(color);
        ds.setLineWidth(width);
        ds.setDrawValues(false);
        ds.setDrawCircles(false);
        // 보간 점은 실제 측정값이 아니므로 탭 하이라이트로 읽히지 않게 한다.
        ds.setHighlightEnabled(false);
        ds.setMode(LineDataSet.Mode.LINEAR);
        if (dashed) ds.enableDashedLine(10f, 8f, 0f);
        return ds;
    }

    /** 버킷 위치에만 원을 찍는 점 전용 데이터셋(선 없음). 집계뷰의 '평균 점'.
        마커 의미 체계: 데이터 값(평균·초과) = 꽉 찬 점(●), 이벤트(경고·끊김) = 링(○).
        범례 캡션과 일치하도록 구멍 없이 그린다. */
    private LineDataSet bucketDots(List<Entry> pts, int color) {
        LineDataSet ds = new LineDataSet(new ArrayList<>(pts), "");
        ds.setColor(Color.TRANSPARENT);
        ds.setLineWidth(0f);
        ds.setDrawCircles(true);
        ds.setCircleColor(color);
        ds.setCircleRadius(3.5f);
        ds.setDrawCircleHole(false);
        ds.setDrawValues(false);
        ds.setHighlightEnabled(false);
        ds.setForm(Legend.LegendForm.NONE);
        return ds;
    }

    /** A안(SensorPush): 위험 임계선 위 영역을 옅은 빨강 면으로 채우는 데이터셋.
        y=top 수평선을 그리고 fillFormatter로 임계값까지 아래로 채운다(선 자체는 투명). */
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
        ds.setFillAlpha(26);                       // ~10% — 데이터를 가리지 않는 배경 톤
        ds.setFillFormatter((d, p) -> threshold);
        ds.setForm(Legend.LegendForm.NONE);
        return ds;
    }

    /** B안(Netatmo): 스크럽 전용 투명 데이터셋. 원본 버킷 값에만 하이라이트가 스냅되고,
        말풍선(TempMarkerView)에 센서명이 표시되도록 label을 갖는다. */
    private LineDataSet scrubSet(LineDataSet src, String label) {
        LineDataSet ds = new LineDataSet(new ArrayList<>(src.getValues()), label);
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

    /** 색에 ~40% 알파를 입혀 연하게(보조 최대선 등). */
    private static int faint(int color) {
        return (color & 0x00FFFFFF) | 0x66000000;
    }

    // ── 슬롯 빌더 ──────────────────────────────────────────────────

    /** 1일/단일날짜 뷰: 1분 버킷 avg/max/min — 분단위 곡선용.
        x 단위는 '시(hour)' float (예: 14:35 → 14.583). 서버 1m 버킷 응답을 전제.
        이벤트 마커는 상태가 바뀌는 시점(onset)에만 찍어 과밀을 막는다. */
    private void buildMinuteSlots(List<TemperatureModel> items,
                                  LineDataSet set1, LineDataSet set1Max, LineDataSet set1Min,
                                  LineDataSet set2, LineDataSet set2Max, LineDataSet set2Min,
                                  LineDataSet setWarn, LineDataSet setDisc) {
        final int slots = (24 * 60) / MINUTE_SLOT_MIN;   // 1440 (1분 버킷)
        float[]   sum1 = new float[slots], sum2 = new float[slots];
        float[]   max1 = new float[slots], max2 = new float[slots];
        float[]   min1 = new float[slots], min2 = new float[slots];
        int[]     cnt  = new int[slots];
        boolean[] hasData    = new boolean[slots];
        String[]  worstEvent = new String[slots];

        if (items != null) {
            for (TemperatureModel item : items) {
                if (item.createdAt == null || item.createdAt.length() < 16) continue;
                int h, m;
                try {
                    h = Integer.parseInt(item.createdAt.substring(11, 13));
                    m = Integer.parseInt(item.createdAt.substring(14, 16));
                } catch (NumberFormatException e) { continue; }
                if (h < 0 || h >= 24 || m < 0 || m >= 60) continue;
                int slot = (h * 60 + m) / MINUTE_SLOT_MIN;
                float v1max = item.temp1Max != null ? item.temp1Max : item.temp1;
                float v1min = item.temp1Min != null ? item.temp1Min : item.temp1;
                float v2max = item.temp2Max != null ? item.temp2Max : item.temp2;
                float v2min = item.temp2Min != null ? item.temp2Min : item.temp2;
                sum1[slot] += item.temp1;
                sum2[slot] += item.temp2;
                if (!hasData[slot] || v1max > max1[slot]) max1[slot] = v1max;
                if (!hasData[slot] || v1min < min1[slot]) min1[slot] = v1min;
                if (!hasData[slot] || v2max > max2[slot]) max2[slot] = v2max;
                if (!hasData[slot] || v2min < min2[slot]) min2[slot] = v2min;
                hasData[slot] = true;
                cnt[slot]++;
                worstEvent[slot] = worstOf(worstEvent[slot], item.event);
            }
        }

        // x축 라벨은 정수 '시' 위치에만 (granularity=1)
        for (int h = 0; h < 24; h++) {
            xLabels.add(String.format(Locale.getDefault(), "%02d:00", h));
        }

        String prevEvent = "normal";
        for (int i = 0; i < slots; i++) {
            if (!hasData[i]) continue;
            float x = i * MINUTE_SLOT_MIN / 60f;
            float avg1 = sum1[i] / cnt[i];
            float avg2 = sum2[i] / cnt[i];
            set1.addEntry(new Entry(x, avg1));
            set1Max.addEntry(new Entry(x, max1[i]));
            set1Min.addEntry(new Entry(x, min1[i]));
            set2.addEntry(new Entry(x, avg2));
            set2Max.addEntry(new Entry(x, max2[i]));
            set2Min.addEntry(new Entry(x, min2[i]));

            // 이벤트는 '시작 시점'만 마킹 (버킷마다 찍으면 과밀)
            String ev = worstEvent[i] == null ? "normal" : worstEvent[i];
            if (!ev.equals(prevEvent)) {
                if ("disconnected".equals(ev)) setDisc.addEntry(new Entry(x, avg1));
                else if ("warning".equals(ev)) setWarn.addEntry(new Entry(x, avg1));
            }
            prevEvent = ev;
        }
    }

    /** 1주 뷰: 일별 avg/max/min (raw 데이터) */
    private void buildDailySlots(List<TemperatureModel> items,
                                 LineDataSet set1, LineDataSet set1Max, LineDataSet set1Min,
                                 LineDataSet set2, LineDataSet set2Max, LineDataSet set2Min,
                                 LineDataSet setWarn, LineDataSet setDisc) {
        final int days = 7;
        float[]   sum1 = new float[days], sum2 = new float[days];
        float[]   max1 = new float[days], max2 = new float[days];
        float[]   min1 = new float[days], min2 = new float[days];
        int[]     cnt  = new int[days];
        boolean[] hasData    = new boolean[days];
        String[]  worstEvent = new String[days];

        Map<String, Integer> dateSlot = new HashMap<>();
        Calendar c = Calendar.getInstance();
        c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);      c.set(Calendar.MILLISECOND, 0);
        c.add(Calendar.DAY_OF_YEAR, -(days - 1));
        for (int i = 0; i < days; i++) {
            dateSlot.put(FMT_DATE.format(c.getTime()), i);
            c.add(Calendar.DAY_OF_YEAR, 1);
        }

        if (items != null) {
            for (TemperatureModel item : items) {
                if (item.createdAt == null || item.createdAt.length() < 10) continue;
                Integer slot = dateSlot.get(item.createdAt.substring(0, 10));
                if (slot == null) continue;
                float v1max = item.temp1Max != null ? item.temp1Max : item.temp1;
                float v1min = item.temp1Min != null ? item.temp1Min : item.temp1;
                float v2max = item.temp2Max != null ? item.temp2Max : item.temp2;
                float v2min = item.temp2Min != null ? item.temp2Min : item.temp2;
                sum1[slot] += item.temp1;
                sum2[slot] += item.temp2;
                if (!hasData[slot] || v1max > max1[slot]) max1[slot] = v1max;
                if (!hasData[slot] || v1min < min1[slot]) min1[slot] = v1min;
                if (!hasData[slot] || v2max > max2[slot]) max2[slot] = v2max;
                if (!hasData[slot] || v2min < min2[slot]) min2[slot] = v2min;
                hasData[slot] = true;
                cnt[slot]++;
                worstEvent[slot] = worstOf(worstEvent[slot], item.event);
            }
        }

        c = Calendar.getInstance();
        c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);      c.set(Calendar.MILLISECOND, 0);
        c.add(Calendar.DAY_OF_YEAR, -(days - 1));

        // 요일만으로는 어느 날짜인지 알 수 없어 M/d로 표기(기간이 명확히 드러나게)
        SimpleDateFormat dayFmt = new SimpleDateFormat("M/d", Locale.getDefault());
        for (int i = 0; i < days; i++) {
            xLabels.add(dayFmt.format(c.getTime()));
            if (hasData[i]) {
                float avg1 = sum1[i] / cnt[i];
                float avg2 = sum2[i] / cnt[i];
                set1.addEntry(new Entry(i, avg1));
                set1Max.addEntry(new Entry(i, max1[i]));
                set1Min.addEntry(new Entry(i, min1[i]));
                set2.addEntry(new Entry(i, avg2));
                set2Max.addEntry(new Entry(i, max2[i]));
                set2Min.addEntry(new Entry(i, min2[i]));
                if ("disconnected".equals(worstEvent[i])) {
                    setDisc.addEntry(new Entry(i, avg1));
                } else if ("warning".equals(worstEvent[i])) {
                    setWarn.addEntry(new Entry(i, avg1));
                }
            }
            c.add(Calendar.DAY_OF_YEAR, 1);
        }
    }

    /** 1개월 뷰: 주별 avg/max/min (서버 일별 집계 → 클라이언트에서 주별 집계) */
    private void buildWeeklySlots(List<TemperatureModel> items,
                                  LineDataSet set1, LineDataSet set1Max, LineDataSet set1Min,
                                  LineDataSet set2, LineDataSet set2Max, LineDataSet set2Min,
                                  LineDataSet setWarn, LineDataSet setDisc) {
        final int weeks = 5;
        float[]   sumAvg1 = new float[weeks], sumAvg2 = new float[weeks];
        float[]   max1    = new float[weeks], max2    = new float[weeks];
        float[]   min1    = new float[weeks], min2    = new float[weeks];
        int[]     cnt     = new int[weeks];
        boolean[] hasData    = new boolean[weeks];
        String[]  worstEvent = new String[weeks];

        // date → day_index (0..29)
        Map<String, Integer> dateDay = new HashMap<>();
        Calendar c = Calendar.getInstance();
        c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);      c.set(Calendar.MILLISECOND, 0);
        c.add(Calendar.DAY_OF_YEAR, -29);
        for (int d = 0; d < 30; d++) {
            dateDay.put(FMT_DATE.format(c.getTime()), d);
            c.add(Calendar.DAY_OF_YEAR, 1);
        }

        if (items != null) {
            for (TemperatureModel item : items) {
                if (item.createdAt == null || item.createdAt.length() < 10) continue;
                Integer day = dateDay.get(item.createdAt.substring(0, 10));
                if (day == null) continue;
                int slot = Math.min(day / 7, weeks - 1);

                float v1avg = item.temp1;
                float v1max = item.temp1Max != null ? item.temp1Max : item.temp1;
                float v1min = item.temp1Min != null ? item.temp1Min : item.temp1;
                float v2avg = item.temp2;
                float v2max = item.temp2Max != null ? item.temp2Max : item.temp2;
                float v2min = item.temp2Min != null ? item.temp2Min : item.temp2;

                sumAvg1[slot] += v1avg;
                sumAvg2[slot] += v2avg;
                if (!hasData[slot] || v1max > max1[slot]) max1[slot] = v1max;
                if (!hasData[slot] || v1min < min1[slot]) min1[slot] = v1min;
                if (!hasData[slot] || v2max > max2[slot]) max2[slot] = v2max;
                if (!hasData[slot] || v2min < min2[slot]) min2[slot] = v2min;
                hasData[slot] = true;
                cnt[slot]++;
                worstEvent[slot] = worstOf(worstEvent[slot], item.event);
            }
        }

        SimpleDateFormat dateFmt = new SimpleDateFormat("M/d", Locale.getDefault());
        c = Calendar.getInstance();
        c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);      c.set(Calendar.MILLISECOND, 0);
        c.add(Calendar.DAY_OF_YEAR, -29);

        for (int i = 0; i < weeks; i++) {
            // "6/4~" = 6/4부터 시작하는 1주 버킷임을 표시
            xLabels.add(dateFmt.format(c.getTime()) + "~");
            if (hasData[i]) {
                float avg1 = sumAvg1[i] / cnt[i];
                float avg2 = sumAvg2[i] / cnt[i];
                set1.addEntry(new Entry(i, avg1));
                set1Max.addEntry(new Entry(i, max1[i]));
                set1Min.addEntry(new Entry(i, min1[i]));
                set2.addEntry(new Entry(i, avg2));
                set2Max.addEntry(new Entry(i, max2[i]));
                set2Min.addEntry(new Entry(i, min2[i]));
                if ("disconnected".equals(worstEvent[i])) {
                    setDisc.addEntry(new Entry(i, avg1));
                } else if ("warning".equals(worstEvent[i])) {
                    setWarn.addEntry(new Entry(i, avg1));
                }
            }
            c.add(Calendar.DAY_OF_YEAR, 7);
        }
    }

    /** 1년 뷰: 월별 avg/max/min (서버 일별 집계 → 클라이언트에서 월별 집계) */
    private void buildMonthlySlots(List<TemperatureModel> items,
                                   LineDataSet set1, LineDataSet set1Max, LineDataSet set1Min,
                                   LineDataSet set2, LineDataSet set2Max, LineDataSet set2Min,
                                   LineDataSet setWarn, LineDataSet setDisc) {
        float[]   sumAvg1 = new float[12], sumAvg2 = new float[12];
        float[]   max1    = new float[12], max2    = new float[12];
        float[]   min1    = new float[12], min2    = new float[12];
        int[]     cnt     = new int[12];
        boolean[] hasData    = new boolean[12];
        String[]  worstEvent = new String[12];

        Map<String, Integer> monthSlot = new HashMap<>();
        Calendar c = Calendar.getInstance();
        c.set(Calendar.DAY_OF_MONTH, 1);
        c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);      c.set(Calendar.MILLISECOND, 0);
        c.add(Calendar.MONTH, -11);
        for (int i = 0; i < 12; i++) {
            monthSlot.put(FMT_MONTH.format(c.getTime()), i);
            c.add(Calendar.MONTH, 1);
        }

        if (items != null) {
            for (TemperatureModel item : items) {
                if (item.createdAt == null || item.createdAt.length() < 7) continue;
                Integer slot = monthSlot.get(item.createdAt.substring(0, 7));
                if (slot == null) continue;

                float v1avg = item.temp1;
                float v1max = item.temp1Max != null ? item.temp1Max : item.temp1;
                float v1min = item.temp1Min != null ? item.temp1Min : item.temp1;
                float v2avg = item.temp2;
                float v2max = item.temp2Max != null ? item.temp2Max : item.temp2;
                float v2min = item.temp2Min != null ? item.temp2Min : item.temp2;

                sumAvg1[slot] += v1avg;
                sumAvg2[slot] += v2avg;
                if (!hasData[slot] || v1max > max1[slot]) max1[slot] = v1max;
                if (!hasData[slot] || v1min < min1[slot]) min1[slot] = v1min;
                if (!hasData[slot] || v2max > max2[slot]) max2[slot] = v2max;
                if (!hasData[slot] || v2min < min2[slot]) min2[slot] = v2min;
                hasData[slot] = true;
                cnt[slot]++;
                worstEvent[slot] = worstOf(worstEvent[slot], item.event);
            }
        }

        c = Calendar.getInstance();
        c.set(Calendar.DAY_OF_MONTH, 1);
        c.add(Calendar.MONTH, -11);
        for (int i = 0; i < 12; i++) {
            xLabels.add(MONTH_NAMES[c.get(Calendar.MONTH)]);
            if (hasData[i]) {
                float avg1 = sumAvg1[i] / cnt[i];
                float avg2 = sumAvg2[i] / cnt[i];
                set1.addEntry(new Entry(i, avg1));
                set1Max.addEntry(new Entry(i, max1[i]));
                set1Min.addEntry(new Entry(i, min1[i]));
                set2.addEntry(new Entry(i, avg2));
                set2Max.addEntry(new Entry(i, max2[i]));
                set2Min.addEntry(new Entry(i, min2[i]));
                if ("disconnected".equals(worstEvent[i])) {
                    setDisc.addEntry(new Entry(i, avg1));
                } else if ("warning".equals(worstEvent[i])) {
                    setWarn.addEntry(new Entry(i, avg1));
                }
            }
            c.add(Calendar.MONTH, 1);
        }
    }

    // ── Y축 범위 보정 ───────────────────────────────────────────────

    /**
     * 평균(avg)뿐 아니라 min/max를 모두 포함하도록 Y축 범위를 명시적으로 설정.
     * - 데이터 전체 범위가 너무 좁으면(예: 1°C 미만) 강제로 일정 높이를 확보해
     *   min/max 캡이 시각적으로 분리되어 보이도록 한다.
     * - 위아래로 패딩을 줘서 캡이 차트 가장자리에 붙지 않게 한다.
     */
    private void applyYAxisRange(LineDataSet set1Max, LineDataSet set1Min,
                                 LineDataSet set2Max, LineDataSet set2Min) {
        YAxis yAxis = chart.getAxisLeft();

        float globalMax = -Float.MAX_VALUE;
        float globalMin =  Float.MAX_VALUE;
        boolean hasAny = false;

        for (LineDataSet ds : new LineDataSet[]{set1Max, set2Max}) {
            for (Entry e : ds.getValues()) {
                if (e.getY() > globalMax) globalMax = e.getY();
                hasAny = true;
            }
        }
        for (LineDataSet ds : new LineDataSet[]{set1Min, set2Min}) {
            for (Entry e : ds.getValues()) {
                if (e.getY() < globalMin) globalMin = e.getY();
                hasAny = true;
            }
        }

        if (!hasAny) {
            yAxis.resetAxisMinimum();
            yAxis.resetAxisMaximum();
            return;
        }

        float range = globalMax - globalMin;
        // 데이터 변동 폭이 좁으면 최소 8°C 범위로 확장 (min/max 캡 시인성 확보)
        final float MIN_RANGE = 8f;
        if (range < MIN_RANGE) {
            float center = (globalMax + globalMin) / 2f;
            globalMax = center + MIN_RANGE / 2f;
            globalMin = center - MIN_RANGE / 2f;
            range = MIN_RANGE;
        }

        // 위아래 15% 여백
        float pad = range * 0.15f;
        yAxis.setAxisMinimum(globalMin - pad);
        yAxis.setAxisMaximum(globalMax + pad);
    }

    // ── 공용 유틸 ───────────────────────────────────────────────────

    private LineDataSet makeLineDataSet(String label, int color) {
        LineDataSet ds = new LineDataSet(new ArrayList<>(), label);
        ds.setColor(color);
        ds.setLineWidth(2f);
        ds.setDrawCircles(false);
        ds.setDrawValues(false);
        ds.setMode(LineDataSet.Mode.LINEAR);
        return ds;
    }

    /** 이벤트 마커 전용 데이터셋(경고=주황 링, 연결 끊김=회색 링).
        평균 점(3.5)과 초과 마커(4.5)보다 살짝 큰 5로 — 구분은 되되 점을 삼키지 않게. */
    private LineDataSet makeEventDataSet(String label, int color) {
        LineDataSet ds = new LineDataSet(new ArrayList<>(), label);
        ds.setColor(Color.TRANSPARENT);
        ds.setLineWidth(0f);
        ds.setDrawCircles(true);
        ds.setCircleColor(color);
        ds.setCircleRadius(5f);
        ds.setDrawCircleHole(true);
        ds.setCircleHoleRadius(2.5f);
        ds.setCircleHoleColor(Color.WHITE);
        ds.setDrawValues(false);
        ds.setHighlightEnabled(false);
        return ds;
    }

    /** 캘린더 기간(start~end) 뷰: span<=1이면 시간별(24슬롯), 아니면 일별 슬롯. 데이터 없는 날은 비움. */
    private int buildRangeSlots(List<TemperatureModel> items,
                                LineDataSet set1, LineDataSet set1Max, LineDataSet set1Min,
                                LineDataSet set2, LineDataSet set2Max, LineDataSet set2Min,
                                LineDataSet setWarn, LineDataSet setDisc) {
        Calendar sc = Calendar.getInstance();
        Calendar ec = Calendar.getInstance();
        try {
            Date sd = FMT_DATE.parse(rangeStartStr);
            Date ed = FMT_DATE.parse(rangeEndStr);
            if (sd != null) sc.setTime(sd);
            if (ed != null) ec.setTime(ed);
        } catch (Exception ignored) {}
        clearTime(sc); clearTime(ec);

        int span = 0;
        Calendar cc = (Calendar) sc.clone();
        while (!cc.after(ec) && span < 400) { span++; cc.add(Calendar.DAY_OF_YEAR, 1); }
        if (span <= 0) span = 1;

        if (span <= 1) {
            // 단일 날짜 → 분단위(1분 버킷) 집계 재사용
            buildMinuteSlots(items, set1, set1Max, set1Min, set2, set2Max, set2Min, setWarn, setDisc);
            return 24;
        }

        float[] sum1 = new float[span], sum2 = new float[span];
        float[] max1 = new float[span], max2 = new float[span];
        float[] min1 = new float[span], min2 = new float[span];
        int[]   cnt  = new int[span];
        boolean[] hasData    = new boolean[span];
        String[]  worstEvent = new String[span];

        Map<String, Integer> dateSlot = new HashMap<>();
        cc = (Calendar) sc.clone();
        for (int i = 0; i < span; i++) {
            dateSlot.put(FMT_DATE.format(cc.getTime()), i);
            cc.add(Calendar.DAY_OF_YEAR, 1);
        }

        if (items != null) {
            for (TemperatureModel item : items) {
                if (item.createdAt == null || item.createdAt.length() < 10) continue;
                Integer slot = dateSlot.get(item.createdAt.substring(0, 10));
                if (slot == null) continue;
                float v1max = item.temp1Max != null ? item.temp1Max : item.temp1;
                float v1min = item.temp1Min != null ? item.temp1Min : item.temp1;
                float v2max = item.temp2Max != null ? item.temp2Max : item.temp2;
                float v2min = item.temp2Min != null ? item.temp2Min : item.temp2;
                sum1[slot] += item.temp1; sum2[slot] += item.temp2;
                if (!hasData[slot] || v1max > max1[slot]) max1[slot] = v1max;
                if (!hasData[slot] || v1min < min1[slot]) min1[slot] = v1min;
                if (!hasData[slot] || v2max > max2[slot]) max2[slot] = v2max;
                if (!hasData[slot] || v2min < min2[slot]) min2[slot] = v2min;
                hasData[slot] = true; cnt[slot]++;
                worstEvent[slot] = worstOf(worstEvent[slot], item.event);
            }
        }

        SimpleDateFormat dateFmt = new SimpleDateFormat("M/d", Locale.getDefault());
        cc = (Calendar) sc.clone();
        for (int i = 0; i < span; i++) {
            xLabels.add(dateFmt.format(cc.getTime()));
            if (hasData[i]) {
                float avg1 = sum1[i] / cnt[i], avg2 = sum2[i] / cnt[i];
                set1.addEntry(new Entry(i, avg1));
                set1Max.addEntry(new Entry(i, max1[i]));
                set1Min.addEntry(new Entry(i, min1[i]));
                set2.addEntry(new Entry(i, avg2));
                set2Max.addEntry(new Entry(i, max2[i]));
                set2Min.addEntry(new Entry(i, min2[i]));
                if ("disconnected".equals(worstEvent[i])) setDisc.addEntry(new Entry(i, avg1));
                else if ("warning".equals(worstEvent[i])) setWarn.addEntry(new Entry(i, avg1));
            }
            cc.add(Calendar.DAY_OF_YEAR, 1);
        }
        return span;
    }

    private static void clearTime(Calendar c) {
        c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);      c.set(Calendar.MILLISECOND, 0);
    }

    // ── 캘린더 기간 선택 ─────────────────────────────────────────────

    private void showRangePicker() {
        SimpleDateFormat utc = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        utc.setTimeZone(TimeZone.getTimeZone("UTC"));

        HashSet<Long> validMs = new HashSet<>();
        long minMs = Long.MAX_VALUE, maxMs = Long.MIN_VALUE;
        for (String d : availableDates) {
            try {
                Date parsed = utc.parse(d);
                if (parsed == null) continue;
                long ms = parsed.getTime();
                validMs.add(ms);
                if (ms < minMs) minMs = ms;
                if (ms > maxMs) maxMs = ms;
            } catch (Exception ignored) {}
        }

        CalendarConstraints.Builder cc = new CalendarConstraints.Builder();
        if (!validMs.isEmpty()) {
            cc.setStart(minMs);
            cc.setEnd(maxMs);
            cc.setOpenAt(maxMs);
            cc.setValidator(new DateSetValidator(validMs));
        }

        MaterialDatePicker<Pair<Long, Long>> picker = MaterialDatePicker.Builder.dateRangePicker()
                .setTitleText("조회 기간 선택 · 하루만 보려면 같은 날짜를 두 번 탭")
                .setCalendarConstraints(cc.build())
                .build();

        picker.addOnPositiveButtonClickListener(sel -> {
            if (sel == null || sel.first == null) return;
            // 종료일을 안 골랐으면(단일 탭) 하루 조회로 처리 — 기존엔 조용히 무시돼
            // "캘린더에서 하루를 선택하면 아무것도 안 보이는" 문제의 원인이었다.
            long endMs = sel.second != null ? sel.second : sel.first;
            String s = utc.format(new Date(sel.first));
            String e = utc.format(new Date(endMs));
            onRangeSelected(s, e);
        });
        picker.show(getParentFragmentManager(), "range_picker");
    }

    private void onRangeSelected(String start, String end) {
        rangeMode = true;
        rangeStartStr = start;
        rangeEndStr = end;

        int span = 1;
        try {
            Calendar sc = Calendar.getInstance(); sc.setTime(FMT_DATE.parse(start)); clearTime(sc);
            Calendar ec = Calendar.getInstance(); ec.setTime(FMT_DATE.parse(end));   clearTime(ec);
            span = 0;
            Calendar cc = (Calendar) sc.clone();
            while (!cc.after(ec) && span < 400) { span++; cc.add(Calendar.DAY_OF_YEAR, 1); }
        } catch (Exception ignored) {}
        rangeSpanDays = Math.max(span, 1);

        rangeLabel = start + " ~ " + end
                + (span <= 1 ? " · 1분 평균·최대" : " · 일별 평균·최대");

        viewModel.loadRange(start, end);
    }

    /** MaterialDatePicker에서 데이터가 있는 날짜만 선택 가능하게 하는 검증기(없는 날짜는 비활성 표시). */
    public static class DateSetValidator implements CalendarConstraints.DateValidator {
        private final HashSet<Long> validDayMs;

        DateSetValidator(HashSet<Long> validDayMs) { this.validDayMs = validDayMs; }

        protected DateSetValidator(Parcel in) {
            validDayMs = new HashSet<>();
            long[] arr = in.createLongArray();
            if (arr != null) for (long v : arr) validDayMs.add(v);
        }

        @Override
        public boolean isValid(long date) {
            long day = date - (date % 86400000L);   // UTC 자정으로 정규화
            return validDayMs.contains(day);
        }

        @Override public int describeContents() { return 0; }

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            long[] arr = new long[validDayMs.size()];
            int i = 0;
            for (long v : validDayMs) arr[i++] = v;
            dest.writeLongArray(arr);
        }

        public static final Parcelable.Creator<DateSetValidator> CREATOR =
                new Parcelable.Creator<DateSetValidator>() {
            @Override public DateSetValidator createFromParcel(Parcel in) { return new DateSetValidator(in); }
            @Override public DateSetValidator[] newArray(int size) { return new DateSetValidator[size]; }
        };
    }

    /** 두 이벤트 중 더 심각한 것 반환 */
    private String worstOf(String a, String b) {
        if ("warning".equals(a)      || "warning".equals(b))      return "warning";
        if ("disconnected".equals(a) || "disconnected".equals(b)) return "disconnected";
        return "normal";
    }

    private void updateEventChip(String event) {
        if (event == null) return;
        int bgColor, textColor;
        String label;
        switch (event) {
            case "warning":
                bgColor   = R.color.color_warning_bg;
                textColor = R.color.color_warning_text;
                label     = "경고";
                break;
            case "critical":
                bgColor   = R.color.color_critical_bg;
                textColor = R.color.color_critical_text;
                label     = "위험";
                break;
            default:
                bgColor   = R.color.color_normal_bg;
                textColor = R.color.color_normal_text;
                label     = "정상";
                break;
        }
        chipEventStatus.setText(label);
        chipEventStatus.setChipBackgroundColor(
                ColorStateList.valueOf(ContextCompat.getColor(requireContext(), bgColor)));
        chipEventStatus.setTextColor(ContextCompat.getColor(requireContext(), textColor));
    }

    // ── 범위 오버레이 (회색 점선 + 가로 캡) ─────────────────────────

    /** LineChart 위에 RangeOverlay를 자식 뷰로 얹고 제스처 시 함께 invalidate */
    private void setupRangeOverlay() {
        rangeOverlay = new RangeOverlay(requireContext());
        chart.addView(rangeOverlay,
                new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT));

        chart.setOnChartGestureListener(new OnChartGestureListener() {
            @Override public void onChartGestureStart(MotionEvent me, ChartTouchListener.ChartGesture g) {}
            @Override public void onChartGestureEnd(MotionEvent me, ChartTouchListener.ChartGesture g) {}
            @Override public void onChartLongPressed(MotionEvent me) {}
            @Override public void onChartDoubleTapped(MotionEvent me) {}
            @Override public void onChartSingleTapped(MotionEvent me) {}
            @Override public void onChartFling(MotionEvent me1, MotionEvent me2, float vx, float vy) {
                if (rangeOverlay != null) rangeOverlay.invalidate();
            }
            @Override public void onChartScale(MotionEvent me, float sx, float sy) {
                if (rangeOverlay != null) rangeOverlay.invalidate();
            }
            @Override public void onChartTranslate(MotionEvent me, float dX, float dY) {
                if (rangeOverlay != null) rangeOverlay.invalidate();
            }
        });
    }

    /**
     * 차트 위에 얹는 투명 오버레이 — 벤치마킹 C안(Apple 건강식 범위 캡슐 바).
     * 집계뷰에서 버킷별 min~max를 라운드 캡 세로 바(캡슐)로 그리고,
     * 위험 임계값을 넘는 구간은 캡슐 상단을 빨강으로 덧그린다.
     * 평균 점(bucketDots)·초과 마커는 MPAndroidChart 데이터셋이 담당.
     */
    private class RangeOverlay extends View {

        private final List<Bar> bars = new ArrayList<>();
        private final Paint barPaint;
        private final Paint dangerPaint;
        private final float capRadius;
        private float threshold = Float.MAX_VALUE;

        RangeOverlay(Context ctx) {
            super(ctx);
            float density = getResources().getDisplayMetrics().density;
            float barWidth = density * 7f;
            capRadius = barWidth / 2f;

            barPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            barPaint.setStyle(Paint.Style.STROKE);
            barPaint.setStrokeWidth(barWidth);
            barPaint.setStrokeCap(Paint.Cap.ROUND);

            dangerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            dangerPaint.setStyle(Paint.Style.STROKE);
            dangerPaint.setStrokeWidth(barWidth);
            dangerPaint.setStrokeCap(Paint.Cap.ROUND);
            dangerPaint.setColor((COLOR_DANGER & 0x00FFFFFF) | 0xB3000000);

            setClickable(false);
            setFocusable(false);
        }

        /** 선택된 센서 하나의 min/max 캡슐 바 갱신. */
        void update(LineDataSet maxSet, LineDataSet minSet, int color, float threshold) {
            bars.clear();
            this.threshold = threshold;
            List<Entry> maxEs = maxSet.getValues();
            List<Entry> minEs = minSet.getValues();
            int n = Math.min(maxEs.size(), minEs.size());
            for (int i = 0; i < n; i++) {
                // hi==lo(샘플 1개 버킷)도 라운드 캡 덕에 점처럼 그려지므로 스킵하지 않음
                bars.add(new Bar(maxEs.get(i).getX(),
                        minEs.get(i).getY(), maxEs.get(i).getY(), color));
            }
            invalidate();
        }

        void clearBars() {
            bars.clear();
            invalidate();
        }

        /**
         * MPAndroidChart(Chart.onLayout)는 자식 뷰를 (0,0)이 아니라 차트가
         * '부모 안에서 갖는 위치(l,t)'만큼 밀어서 배치한다. 그러면 오버레이가
         * 차트 상단 t픽셀을 덮지 못해 캡슐 바 위쪽이 일괄적으로 잘린다
         * (번역(translate) 보정으로는 뷰 밖 영역이라 그릴 수 없음).
         * → 부모가 어떤 좌표를 주든 스스로를 (0, 0, 차트폭, 차트높이)에 재배치.
         */
        @Override
        public void layout(int l, int t, int r, int b) {
            super.layout(0, 0, r - l, b - t);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if (bars.isEmpty() || chart.getData() == null) return;

            // 플롯 영역(content rect) 밖(축 라벨 쪽)은 클리핑
            canvas.save();
            canvas.clipRect(chart.getViewPortHandler().getContentRect());

            Transformer t = chart.getTransformer(YAxis.AxisDependency.LEFT);
            float[] pts = new float[2];

            for (Bar bar : bars) {
                pts[0] = bar.x; pts[1] = bar.hi;
                t.pointValuesToPixel(pts);
                float sx = pts[0], syHi = pts[1];

                pts[0] = bar.x; pts[1] = bar.lo;
                t.pointValuesToPixel(pts);
                float syLo = pts[1];

                // 라운드 캡이 값 위치를 넘지 않게 캡 반지름만큼 안쪽으로 줄여 그린다.
                float top = Math.min(syHi + capRadius, syLo);
                float bot = Math.max(syLo - capRadius, top);

                barPaint.setColor((bar.color & 0x00FFFFFF) | 0x59000000);  // ~35% 알파
                canvas.drawLine(sx, bot, sx, top, barPaint);

                // 임계 초과 구간: 캡슐 상단을 빨강으로 덧그림
                if (bar.hi >= threshold) {
                    pts[0] = bar.x; pts[1] = Math.max(bar.lo, threshold);
                    t.pointValuesToPixel(pts);
                    float syThr = Math.max(pts[1] - capRadius, top);
                    canvas.drawLine(sx, syThr, sx, top, dangerPaint);
                }
            }

            canvas.restore();
        }

        private final class Bar {
            final float x, lo, hi;
            final int color;
            Bar(float x, float lo, float hi, int color) {
                this.x = x; this.lo = lo; this.hi = hi; this.color = color;
            }
        }
    }
}
