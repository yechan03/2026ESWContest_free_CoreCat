package com.example.in_gps.screen;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.lifecycle.ViewModelProvider;

import com.example.in_gps.R;
import com.example.in_gps.fragment.DeviceListFragment;
import com.example.in_gps.fragment.SettingsFragment;
import com.example.in_gps.fragment.SystemHealthFragment;
import com.example.in_gps.util.TempAlertNotifier;
import com.example.in_gps.viewmodel.TempWatchViewModel;
import com.google.android.material.bottomnavigation.BottomNavigationView;

public class MainActivity extends AppCompatActivity {

    private TempWatchViewModel tempWatchViewModel;

    /**
     * POST_NOTIFICATIONS 런타임 요청 (API 33+).
     * 거부돼도 아무 처리를 하지 않는다 — 알림만 발행되지 않고 목록/차트/설정은 정상 동작한다.
     */
    private final ActivityResultLauncher<String> notificationPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(),
                    granted -> { /* 거부해도 앱 기능에 영향 없음 */ });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Application 서브클래스가 없으므로 채널 생성은 여기서 한다. 멱등이라 반복 호출 안전.
        TempAlertNotifier.ensureChannel(this);
        requestNotificationPermissionIfNeeded();

        // 온도 임계 전역 감시. 판정/알림 발행 지점은 이 observe 콜백 한 곳뿐이다
        // (SensorDetailFragment에도 붙이면 같은 초과를 두 경로가 판정해 중복·경합이 생긴다).
        tempWatchViewModel = new ViewModelProvider(this).get(TempWatchViewModel.class);
        tempWatchViewModel.getDevices().observe(this, list ->
                TempAlertNotifier.check(MainActivity.this, list));

        if (savedInstanceState == null) {
            loadFragment(new DeviceListFragment());
        }

        BottomNavigationView bottomNav = findViewById(R.id.bottom_nav);
        bottomNav.setOnItemSelectedListener(item -> {
            getSupportFragmentManager().popBackStack(null, FragmentManager.POP_BACK_STACK_INCLUSIVE);

            int id = item.getItemId();
            if (id == R.id.nav_device_list) {
                loadFragment(new DeviceListFragment());
                return true;
            } else if (id == R.id.nav_system_health) {
                loadFragment(new SystemHealthFragment());
                return true;
            } else if (id == R.id.nav_settings) {
                loadFragment(new SettingsFragment());
                return true;
            }
            return false;
        });
    }

    /** 이 Activity가 유일한 Activity이므로 started 구간 ≡ 앱 포그라운드 구간이다. */
    @Override
    protected void onStart() {
        super.onStart();
        if (tempWatchViewModel != null) tempWatchViewModel.startPolling();
    }

    /** 홈 버튼·화면 꺼짐·앱 전환 시 감시 폴링을 완전히 멈춘다(배터리 회귀 방지). */
    @Override
    protected void onStop() {
        if (tempWatchViewModel != null) tempWatchViewModel.stopPolling();
        super.onStop();
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return;   // API 33 미만은 요청 불필요
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED) {
            return;
        }
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
    }

    private void loadFragment(Fragment fragment) {
        getSupportFragmentManager().beginTransaction()
                .replace(R.id.fragment_container, fragment)
                .commit();
    }
}
