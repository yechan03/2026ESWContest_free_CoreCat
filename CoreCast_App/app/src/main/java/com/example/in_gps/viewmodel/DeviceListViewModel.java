package com.example.in_gps.viewmodel;

import android.os.Handler;
import android.os.Looper;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import com.example.in_gps.model.DeviceModel;
import com.example.in_gps.repository.DeviceRepository;

import java.util.List;

public class DeviceListViewModel extends ViewModel {

    private static final long POLL_INTERVAL_MS = 3_000;

    private final MutableLiveData<List<DeviceModel>> devices = new MutableLiveData<>();
    private final DeviceRepository repository = new DeviceRepository();
    private final Handler handler = new Handler(Looper.getMainLooper());

    private boolean polling = false;

    private final Runnable pollRunnable = new Runnable() {
        @Override
        public void run() {
            if (!polling) return;
            repository.fetchDevices(result -> devices.postValue(result));
            handler.postDelayed(this, POLL_INTERVAL_MS);
        }
    };

    /** 화면이 보일 때만 폴링 — Fragment onStart/onStop에서 호출.
        (SystemHealth와 같은 /devices를 쓰지만, 탭 전환 시 한쪽만 돌게 되어 중복 해소) */
    public void startPolling() {
        if (polling) return;
        polling = true;
        handler.post(pollRunnable);
    }

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
        handler.removeCallbacksAndMessages(null);
    }
}
