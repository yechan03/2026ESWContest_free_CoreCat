package com.example.in_gps.viewmodel;

import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;

import com.example.in_gps.model.TemperatureModel;
import com.example.in_gps.repository.TemperatureRepository;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * SensorDetailTestFragment(실시간 상세) 용 ViewModel.
 *
 * 폴링 전략 — 증분(since) 방식:
 *  - 시작 시 1회만 최근 WINDOW_SIZE건 전체를 받고(fetchRecent),
 *  - 이후 1초마다 마지막 수신 시각 이후의 '새 행만' 받는다(fetchSince).
 *    기존(매초 60행 전체 재수신) 대비 페이로드 ~98% 절감.
 *  - 새 행이 없으면 LiveData를 갱신하지 않아 차트 재렌더도 생략된다.
 *  - 구버전 서버가 since를 무시해도 created_at 필터로 안전(중복 유입 차단).
 *
 * 생명주기: Fragment onStart/onStop에서 startPolling/stopPolling 호출.
 * 재시작 시 윈도우를 리셋해 정지 동안의 공백을 따라잡는 비용을 없앤다.
 */
public class SensorDetailTestViewModel extends ViewModel {

    /** 차트에 표시할 sliding window 크기 (= 직전 N초). */
    public static final int WINDOW_SIZE = 60;

    private static final long POLL_INTERVAL_MS = 1000L;

    private final String deviceId;
    private final TemperatureRepository repository = new TemperatureRepository();
    private final Handler handler = new Handler(Looper.getMainLooper());

    /** 최근 WINDOW_SIZE 건. created_at DESC(최신 먼저) — 기존 화면 계약 유지. */
    private final MutableLiveData<List<TemperatureModel>> recentData = new MutableLiveData<>();

    /** 로컬 윈도우(ASC, 오래된 것부터). 증분 결과를 여기 병합한다. */
    private final ArrayDeque<TemperatureModel> window = new ArrayDeque<>();
    private String lastTs = null;
    private boolean polling = false;

    private final Runnable pollRunnable = new Runnable() {
        @Override
        public void run() {
            if (!polling) return;
            if (lastTs == null) {
                repository.fetchRecent(deviceId, WINDOW_SIZE, SensorDetailTestViewModel.this::onInitial);
            } else {
                repository.fetchSince(deviceId, lastTs, WINDOW_SIZE, SensorDetailTestViewModel.this::onDelta);
            }
            handler.postDelayed(this, POLL_INTERVAL_MS);
        }
    };

    public SensorDetailTestViewModel(String deviceId) {
        this.deviceId = deviceId;
    }

    public void startPolling() {
        if (polling) return;
        polling = true;
        // 정지 중 쌓인 공백을 증분으로 따라잡는 대신 전체 리로드(1회)로 시작
        lastTs = null;
        window.clear();
        handler.post(pollRunnable);
    }

    public void stopPolling() {
        polling = false;
        handler.removeCallbacksAndMessages(null);
    }

    /** 초기 로드: 서버는 DESC로 주므로 뒤집어 ASC 윈도우 구성. */
    private void onInitial(List<TemperatureModel> descItems) {
        if (descItems == null || descItems.isEmpty()) return;
        window.clear();
        for (int i = descItems.size() - 1; i >= 0; i--) {
            TemperatureModel m = descItems.get(i);
            if (m.createdAt == null) continue;
            window.addLast(m);
        }
        if (!window.isEmpty()) lastTs = window.peekLast().createdAt;
        publish();
    }

    /** 증분 병합: lastTs 이후 행만 append (구버전 서버 응답도 필터로 방어). */
    private void onDelta(List<TemperatureModel> items) {
        if (items == null || items.isEmpty()) return;
        boolean changed = false;
        for (TemperatureModel m : items) {
            if (m.createdAt == null) continue;
            // "YYYY-MM-DD HH:MM:SS" 형식이라 문자열 비교 = 시간 비교
            if (lastTs != null && m.createdAt.compareTo(lastTs) <= 0) continue;
            window.addLast(m);
            lastTs = m.createdAt;
            changed = true;
        }
        while (window.size() > WINDOW_SIZE) window.removeFirst();
        if (changed) publish();   // 새 행 없으면 재렌더 생략
    }

    private void publish() {
        List<TemperatureModel> desc = new ArrayList<>(window);
        Collections.reverse(desc);
        recentData.setValue(desc);
    }

    public LiveData<List<TemperatureModel>> getRecentData() {
        return recentData;
    }

    public String getDeviceId() {
        return deviceId;
    }

    @Override
    protected void onCleared() {
        super.onCleared();
        handler.removeCallbacksAndMessages(null);
    }

    public static class Factory implements ViewModelProvider.Factory {
        private final String deviceId;

        public Factory(String deviceId) {
            this.deviceId = deviceId;
        }

        @NonNull
        @Override
        @SuppressWarnings("unchecked")
        public <T extends ViewModel> T create(@NonNull Class<T> modelClass) {
            return (T) new SensorDetailTestViewModel(deviceId);
        }
    }
}
