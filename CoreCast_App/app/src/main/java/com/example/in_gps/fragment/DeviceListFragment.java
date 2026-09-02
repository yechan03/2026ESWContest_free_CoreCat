package com.example.in_gps.fragment;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.in_gps.R;
import com.example.in_gps.adapter.DeviceAdapter;
import com.example.in_gps.viewmodel.DeviceListViewModel;

public class DeviceListFragment extends Fragment {

    private DeviceListViewModel viewModel;
    private DeviceAdapter adapter;

     @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_device_list, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        RecyclerView rvDevices = view.findViewById(R.id.rv_devices);
        rvDevices.setLayoutManager(new LinearLayoutManager(requireContext()));

        adapter = new DeviceAdapter(device -> {
            getParentFragmentManager().beginTransaction()
                    .replace(R.id.fragment_container,
                            SensorDetailFragment.newInstance(device.deviceId))
                    .addToBackStack(null)
                    .commit();
        });
        rvDevices.setAdapter(adapter);

        viewModel = new ViewModelProvider(this).get(DeviceListViewModel.class);
        viewModel.getDevices().observe(getViewLifecycleOwner(), devices ->
                adapter.setDevices(devices));
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
}
