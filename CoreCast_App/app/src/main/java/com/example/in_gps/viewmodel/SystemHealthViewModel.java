package com.example.in_gps.viewmodel;

import android.os.Handler;
import android.os.Looper;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import com.example.in_gps.repository.DeviceRepository;

public class SystemHealthViewModel extends ViewModel {

    private static final long POLL_INTERVAL_MS = 3_000;

    public static class DeviceStats {
        public final int normal;
        public final int warning;
        public final int critical;
        public final int disconnected;
        public final int total;

        public DeviceStats(int normal, int warning, int critical, int disconnected) {
            this.normal = normal;
            this.warning = warning;
            this.critical = critical;
            this.disconnected = disconnected;
            this.total = normal + warning + critical + disconnected;
        }
    }

    private final MutableLiveData<DeviceStats> deviceStats = new MutableLiveData<>();
    private final DeviceRepository repository = new DeviceRepository();
    private final Handler handler = new Handler(Looper.getMainLooper());

    private boolean polling = false;

    private final Runnable pollRunnable = new Runnable() {
        @Override
        public void run() {
            if (!polling) return;
            repository.fetchDevices(devices -> {
                int normal = 0, warning = 0, critical = 0, disconnected = 0;
                for (com.example.in_gps.model.DeviceModel d : devices) {
                    if ("normal".equalsIgnoreCase(d.status)) normal++;
                    else if ("warning".equalsIgnoreCase(d.status)) warning++;
                    else if ("critical".equalsIgnoreCase(d.status)) critical++;
                    else if ("disconnected".equalsIgnoreCase(d.status)) disconnected++;
                }
                deviceStats.postValue(new DeviceStats(normal, warning, critical, disconnected));
            });
            handler.postDelayed(this, POLL_INTERVAL_MS);
        }
    };

    /** 화면이 보일 때만 폴링 — Fragment onStart/onStop에서 호출. */
    public void startPolling() {
        if (polling) return;
        polling = true;
        handler.post(pollRunnable);
    }

    public void stopPolling() {
        polling = false;
        handler.removeCallbacksAndMessages(null);
    }

    public LiveData<DeviceStats> getDeviceStats() {
        return deviceStats;
    }

    @Override
    protected void onCleared() {
        super.onCleared();
        handler.removeCallbacksAndMessages(null);
    }
}
