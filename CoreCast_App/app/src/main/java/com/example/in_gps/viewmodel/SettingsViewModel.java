package com.example.in_gps.viewmodel;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

public class SettingsViewModel extends ViewModel {

    private final MutableLiveData<Integer> hours = new MutableLiveData<>(0);
    private final MutableLiveData<Integer> minutes = new MutableLiveData<>(0);
    private final MutableLiveData<Integer> seconds = new MutableLiveData<>(30);
    private final MutableLiveData<String> intervalLabel = new MutableLiveData<>("00:00:30");

    public LiveData<Integer> getHours() { return hours; }
    public LiveData<Integer> getMinutes() { return minutes; }
    public LiveData<Integer> getSeconds() { return seconds; }
    public LiveData<String> getIntervalLabel() { return intervalLabel; }

    public void saveInterval(int h, int m, int s) {
        hours.setValue(h);
        minutes.setValue(m);
        seconds.setValue(s);
        intervalLabel.setValue(String.format("%02d:%02d:%02d", h, m, s));
    }
}
