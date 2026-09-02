package com.example.in_gps.fragment;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.NumberPicker;
import android.widget.Toast;

import com.google.android.material.button.MaterialButton;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import com.example.in_gps.R;
import com.example.in_gps.viewmodel.SettingsViewModel;
import com.google.android.material.chip.Chip;

public class SettingsFragment extends Fragment {

    private SettingsViewModel viewModel;
    private NumberPicker pickerHours;
    private NumberPicker pickerMinutes;
    private NumberPicker pickerSeconds;
    private Chip chipCurrentInterval;

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_settings, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        pickerHours = view.findViewById(R.id.picker_hours);
        pickerMinutes = view.findViewById(R.id.picker_minutes);
        pickerSeconds = view.findViewById(R.id.picker_seconds);
        chipCurrentInterval = view.findViewById(R.id.chip_current_interval);

        pickerHours.setMinValue(0);
        pickerHours.setMaxValue(23);
        pickerMinutes.setMinValue(0);
        pickerMinutes.setMaxValue(59);
        pickerSeconds.setMinValue(0);
        pickerSeconds.setMaxValue(59);

        viewModel = new ViewModelProvider(this).get(SettingsViewModel.class);

        viewModel.getHours().observe(getViewLifecycleOwner(), h -> pickerHours.setValue(h));
        viewModel.getMinutes().observe(getViewLifecycleOwner(), m -> pickerMinutes.setValue(m));
        viewModel.getSeconds().observe(getViewLifecycleOwner(), s -> pickerSeconds.setValue(s));
        viewModel.getIntervalLabel().observe(getViewLifecycleOwner(), label ->
                chipCurrentInterval.setText(label));

        view.findViewById(R.id.btn_save_interval).setOnClickListener(v ->
                viewModel.saveInterval(
                        pickerHours.getValue(),
                        pickerMinutes.getValue(),
                        pickerSeconds.getValue()
                )
        );

        setupThreshold(view);
        setupCoreThreshold(view);
    }

    // 위험 임계온도: SharedPreferences("in_gps_prefs", "danger_threshold_c")에 저장. 기본 40°C.
    private void setupThreshold(View view) {
        NumberPicker pickerThreshold = view.findViewById(R.id.picker_threshold);
        MaterialButton btnSaveThreshold = view.findViewById(R.id.btn_save_threshold);
        if (pickerThreshold == null) return;

        pickerThreshold.setMinValue(20);
        pickerThreshold.setMaxValue(120);

        SharedPreferences prefs = requireContext()
                .getSharedPreferences("in_gps_prefs", Context.MODE_PRIVATE);
        pickerThreshold.setValue(Math.round(prefs.getFloat("danger_threshold_c", 40f)));

        if (btnSaveThreshold != null) {
            btnSaveThreshold.setOnClickListener(v -> {
                float val = pickerThreshold.getValue();
                prefs.edit().putFloat("danger_threshold_c", val).apply();
                Toast.makeText(requireContext(),
                        String.format(java.util.Locale.US, "위험 온도 %.0f°C 저장됨", val),
                        Toast.LENGTH_SHORT).show();
            });
        }
    }

    // AI 예측 코어 온도 임계값: SharedPreferences("in_gps_prefs", "core_threshold_c")에 저장. 기본 80°C.
    // danger_threshold_c(위 setupThreshold)와는 완전히 별개의 키다 — 서로 값을 덮어쓰지 않는다.
    // 범위 20~200: core_temp는 서버의 -20~80°C 유효범위 필터를 적용받지 않으며
    // SHF 모델 출력이 80°C를 넘는 것이 설계상 정상이다.
    private void setupCoreThreshold(View view) {
        NumberPicker pickerCoreThreshold = view.findViewById(R.id.picker_core_threshold);
        MaterialButton btnSaveCoreThreshold = view.findViewById(R.id.btn_save_core_threshold);
        if (pickerCoreThreshold == null) return;

        pickerCoreThreshold.setMinValue(20);
        pickerCoreThreshold.setMaxValue(200);

        SharedPreferences prefs = requireContext()
                .getSharedPreferences("in_gps_prefs", Context.MODE_PRIVATE);
        pickerCoreThreshold.setValue(Math.round(prefs.getFloat("core_threshold_c", 80f)));

        if (btnSaveCoreThreshold != null) {
            btnSaveCoreThreshold.setOnClickListener(v -> {
                float val = pickerCoreThreshold.getValue();
                prefs.edit().putFloat("core_threshold_c", val).apply();
                Toast.makeText(requireContext(),
                        String.format(java.util.Locale.US, "코어 온도 %.0f°C 저장됨", val),
                        Toast.LENGTH_SHORT).show();
            });
        }
    }
}
