package com.example.in_gps.fragment;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;

import com.example.in_gps.R;

/**
 * '실시간 상세보기' 다이얼로그.
 * SensorDetailTestFragment(1초 polling 실시간 차트)를 전체 화면에 가까운
 * 다이얼로그로 호스팅한다. 닫으면 polling ViewModel도 함께 정리된다
 * (child fragment 생명주기에 묶임).
 */
public class RealtimeDetailDialogFragment extends DialogFragment {

    /** 닫힘 알림 키 — 부모 화면이 폴링을 재개하는 신호 */
    public static final String RESULT_CLOSED = "realtime_detail_closed";

    private static final String ARG_DEVICE_ID = "device_id";

    public static RealtimeDetailDialogFragment newInstance(String deviceId) {
        RealtimeDetailDialogFragment f = new RealtimeDetailDialogFragment();
        Bundle args = new Bundle();
        args.putString(ARG_DEVICE_ID, deviceId);
        f.setArguments(args);
        return f;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.dialog_realtime_detail, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        view.findViewById(R.id.btn_close_realtime).setOnClickListener(v -> dismiss());

        if (savedInstanceState == null) {
            String deviceId = requireArguments().getString(ARG_DEVICE_ID, "--");
            getChildFragmentManager().beginTransaction()
                    .replace(R.id.fl_realtime_container,
                            SensorDetailTestFragment.newInstance(deviceId))
                    .commit();
        }
    }

    /** 차트가 많아 전체 화면으로 확장. */
    @Override
    public void onStart() {
        super.onStart();
        if (getDialog() != null && getDialog().getWindow() != null) {
            getDialog().getWindow().setLayout(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT);
        }
    }

    @Override
    public void onDismiss(@NonNull android.content.DialogInterface dialog) {
        super.onDismiss(dialog);
        // 부모(SensorDetailFragment)가 정지해 둔 자기 폴링을 재개하도록 알림
        getParentFragmentManager().setFragmentResult(RESULT_CLOSED, Bundle.EMPTY);
    }
}
