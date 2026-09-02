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
import com.example.in_gps.viewmodel.SystemHealthViewModel;
import com.github.mikephil.charting.charts.BarChart;
import com.github.mikephil.charting.components.XAxis;
import com.github.mikephil.charting.data.BarData;
import com.github.mikephil.charting.data.BarDataSet;
import com.github.mikephil.charting.data.BarEntry;
import com.github.mikephil.charting.formatter.IndexAxisValueFormatter;
import com.github.mikephil.charting.formatter.ValueFormatter;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class SystemHealthFragment extends Fragment {

    private static final int COLOR_NORMAL       = Color.parseColor("#34A853");
    private static final int COLOR_WARNING      = Color.parseColor("#FBBC04");
    private static final int COLOR_CRITICAL     = Color.parseColor("#EA4335");
    private static final int COLOR_DISCONNECTED = Color.parseColor("#9AA0A6");
    private static final int COLOR_AXIS         = Color.parseColor("#9E9E9E");

    private SystemHealthViewModel viewModel;
    private BarChart barChart;
    private TextView tvCountNormal;
    private TextView tvCountWarning;
    private TextView tvCountCritical;
    private TextView tvCountDisconnected;
    private TextView tvTotalDevices;

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_system_health, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        barChart            = view.findViewById(R.id.bar_chart);
        tvCountNormal       = view.findViewById(R.id.tv_count_normal);
        tvCountWarning      = view.findViewById(R.id.tv_count_warning);
        tvCountCritical     = view.findViewById(R.id.tv_count_critical);
        tvCountDisconnected = view.findViewById(R.id.tv_count_disconnected);
        tvTotalDevices      = view.findViewById(R.id.tv_total_devices);

        setupBarChart();

        viewModel = new ViewModelProvider(this).get(SystemHealthViewModel.class);
        viewModel.getDeviceStats().observe(getViewLifecycleOwner(), stats -> {
            tvCountNormal.setText(String.valueOf(stats.normal));
            tvCountWarning.setText(String.valueOf(stats.warning));
            tvCountCritical.setText(String.valueOf(stats.critical));
            tvCountDisconnected.setText(String.valueOf(stats.disconnected));
            tvTotalDevices.setText(String.valueOf(stats.total));
            updateBarChart(stats);
        });
    }

    // 폴링은 화면이 보이는 동안만
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

    private void setupBarChart() {
        barChart.getDescription().setEnabled(false);
        barChart.getLegend().setEnabled(false);
        barChart.setTouchEnabled(false);
        barChart.setDrawBorders(false);
        barChart.setDrawGridBackground(false);
        barChart.setExtraBottomOffset(8f);
        barChart.setNoDataText("데이터 없음");

        XAxis xAxis = barChart.getXAxis();
        xAxis.setPosition(XAxis.XAxisPosition.BOTTOM);
        xAxis.setDrawGridLines(false);
        xAxis.setDrawAxisLine(false);
        xAxis.setGranularity(1f);
        xAxis.setValueFormatter(new IndexAxisValueFormatter(new String[]{"정상", "경고", "위험", "끊김"}));
        xAxis.setTextColor(COLOR_AXIS);
        xAxis.setTextSize(12f);

        barChart.getAxisLeft().setDrawGridLines(false);
        barChart.getAxisLeft().setDrawAxisLine(false);
        barChart.getAxisLeft().setTextColor(COLOR_AXIS);
        barChart.getAxisLeft().setGranularity(1f);
        barChart.getAxisLeft().setAxisMinimum(0f);
        barChart.getAxisLeft().setValueFormatter(new ValueFormatter() {
            @Override public String getFormattedValue(float value) {
                return String.valueOf((int) value);
            }
        });
        barChart.getAxisRight().setEnabled(false);
    }

    private void updateBarChart(SystemHealthViewModel.DeviceStats stats) {
        List<BarEntry> entries = new ArrayList<>();
        entries.add(new BarEntry(0, stats.normal));
        entries.add(new BarEntry(1, stats.warning));
        entries.add(new BarEntry(2, stats.critical));
        entries.add(new BarEntry(3, stats.disconnected));

        BarDataSet dataSet = new BarDataSet(entries, "");
        dataSet.setColors(COLOR_NORMAL, COLOR_WARNING, COLOR_CRITICAL, COLOR_DISCONNECTED);
        dataSet.setValueTextSize(11f);
        dataSet.setValueTextColor(COLOR_AXIS);
        dataSet.setValueFormatter(new ValueFormatter() {
            @Override public String getFormattedValue(float value) {
                return String.valueOf((int) value);
            }
        });

        BarData barData = new BarData(dataSet);
        barData.setBarWidth(0.5f);

        barChart.setData(barData);
        barChart.invalidate();
    }
}
