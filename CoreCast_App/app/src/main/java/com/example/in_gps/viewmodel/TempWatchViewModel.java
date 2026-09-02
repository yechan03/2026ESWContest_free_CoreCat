package com.example.in_gps.viewmodel;

import android.os.Handler;
import android.os.Looper;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import com.example.in_gps.model.DeviceModel;
import com.example.in_gps.repository.DeviceRepository;

import java.util.List;

/**
 * 온도 임계 감시용 전역 폴러 (Activity 스코프).
 *
 * <p>구조는 {@link DeviceListViewModel}을 그대로 따르되 소유자가 Fragment가 아니라
 * {@code MainActivity}다. 이 앱의 Activity는 MainActivity 하나뿐이므로
 * "MainActivity가 started 상태" ≡ "앱이 포그라운드"가 성립하고,
 * onStart/onStop에 폴링을 묶으면 화면 독립적 감시와 백그라운드 요청 정지를
 * 동시에 만족한다. (ProcessLifecycleOwner 신규 의존성 불필요)
 *
 * <p>{@code RealtimeDetailDialogFragment}는 DialogFragment이지 Activity가 아니므로
 * 다이얼로그가 떠 있어도 이 폴러는 계속 돈다.
 *
 * <p><b>Context를 필드로도 인자로도 받지 않는다.</b> 알림 발행은 Context를 가진
 * MainActivity의 observe 콜백에서만 일어난다.
 */
public class TempWatchViewModel extends ViewModel {

    /**
     * 5초. DeviceListFragment가 보이는 동안에는 DeviceListViewModel(3초)과 같은
     * /devices를 각각 폴링하게 되는데, 이번 스코프에서는 중복을 허용하되
     * 목록 폴링보다 느슨하게 두어 서버 부하 증가를 억제한다.
     * (통합하려면 SystemHealthViewModel까지 건드리는 별도 리팩터링이 된다)
     */
    private static final long WATCH_INTERVAL_MS = 5_000;

    private final MutableLiveData<List<DeviceModel>> devices = new MutableLiveData<>();
    private final DeviceRepository repository = new DeviceRepository();
    private final Handler handler = new Handler(Looper.getMainLooper());

    private boolean polling = false;

    private final Runnable pollRunnable = new Runnable() {
        @Override
        public void run() {
            if (!polling) return;
            repository.fetchDevices(result -> devices.postValue(result));
            handler.postDelayed(this, WATCH_INTERVAL_MS);
        }
    };

    /** MainActivity.onStart()에서 호출. */
    public void startPolling() {
        if (polling) return;
        polling = true;
        handler.post(pollRunnable);
    }

    /** MainActivity.onStop()에서 호출 — 이후 네트워크 요청이 나가지 않는다. */
    public void stopPolling() {
        polling = false;
        handler.removeCallbacksAndMessages(null);
    }

    public LiveData<List<DeviceModel>> getDevices() {
        return devices;
    }

    @Override
    protected void onCleared() {
        super.onCleared();
        polling = false;
        handler.removeCallbacksAndMessages(null);
    }
}
