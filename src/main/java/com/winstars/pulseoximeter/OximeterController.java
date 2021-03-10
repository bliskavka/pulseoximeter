package com.winstars.pulseoximeter;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.IBinder;
import android.util.Log;

import java.util.List;

/**
 * Created by ZXX on 2017/4/28.
 */

public class OximeterController {
    private final String TAG = this.getClass().getName();


    private static OximeterController mOximeterController = null;
    private BluetoothAdapter mBtAdapter = null;
    private DataParser mDataParser;
    private Handler mHandler;

    public StateListener mStateListener;
    private BluetoothLeService mBluetoothLeService;
    private BluetoothGattCharacteristic chReceiveData;
    private BluetoothGattCharacteristic chModifyName;

    private boolean mIsConnected = false;


    private OximeterController(Context context, StateListener stateListener) {
        mBtAdapter = BluetoothAdapter.getDefaultAdapter();
        mStateListener = stateListener;
        mHandler = new Handler();

        mDataParser = new DataParser(new DataParser.onPackageReceivedListener() {
            @Override
            public void onOxiParamsChanged(final DataParser.OxiParams params) {
                mStateListener.onDataReceived(params.getSpo2(), params.getPulseRate(), params.getPi());
            }

            @Override
            public void onPlethWaveReceived(final int amp) {
                mStateListener.onWaveReceived(amp);
            }
        });

        mDataParser.start();
        enableBtAdapter();
        bindService(context);
    }

    public static OximeterController getController(Context context, StateListener stateListener) {
        if (mOximeterController == null) {
            mOximeterController = new OximeterController(context, stateListener);
        }
        return mOximeterController;
    }

    public boolean isConnected() {
        return mIsConnected;
    }

    public void connect(BluetoothDevice device) {
        mBluetoothLeService.connect(device.getAddress());
    }

    public void disconnect() {
        mBluetoothLeService.disconnect();
    }

    public void startScan(long timeout) {
        // Stops scanning after a pre-defined scan period.
        mHandler.postDelayed(this::stopScan, timeout);

        mBtAdapter.startLeScan(mLeScanCallback);
    }

    public void stopScan() {
        mBtAdapter.stopLeScan(mLeScanCallback);
        mStateListener.onScanStop();
    }

    public void onResume(Context context) {
        context.registerReceiver(mGattUpdateReceiver, makeGattUpdateIntentFilter());
    }

    public void onPause(Context context) {
        context.unregisterReceiver(mGattUpdateReceiver);
    }

    public void onDestroy(Context context) {
        mDataParser.stop();
        context.unbindService(mServiceConnection);
    }

    //************************ Private methods **************************

    private void bindService(Context context) {
        Intent gattServiceIntent = new Intent(context, BluetoothLeService.class);
        context.bindService(gattServiceIntent, mServiceConnection, context.BIND_AUTO_CREATE);
    }

    private void enableBtAdapter() {
        if (!mBtAdapter.isEnabled()) {
            mBtAdapter.enable();
        }
    }

    // Device scan callback.
    private BluetoothAdapter.LeScanCallback mLeScanCallback =
            new BluetoothAdapter.LeScanCallback() {
                @Override
                public void onLeScan(final BluetoothDevice device, int rssi, byte[] scanRecord) {
                    mStateListener.onFoundDevice(device);
                }
            };

    // Code to manage Service lifecycle.
    private final ServiceConnection mServiceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName componentName, IBinder service) {
            mBluetoothLeService = ((BluetoothLeService.LocalBinder) service).getService();
            if (!mBluetoothLeService.initialize()) {
                Log.e(TAG, "Unable to initialize Bluetooth");
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName componentName) {
            mBluetoothLeService = null;
        }
    };

    // Handles various events fired by the Service.
    // ACTION_GATT_CONNECTED: connected to a GATT server.
    // ACTION_GATT_DISCONNECTED: disconnected from a GATT server.
    // ACTION_GATT_SERVICES_DISCOVERED: discovered GATT services.
    // ACTION_DATA_AVAILABLE: received data from the device.  This can be a result of read
    //                        or notification operations.
    private final BroadcastReceiver mGattUpdateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            if (BluetoothLeService.ACTION_GATT_CONNECTED.equals(action)) {
                mStateListener.onConnected();
                mIsConnected = true;
            } else if (BluetoothLeService.ACTION_GATT_DISCONNECTED.equals(action)) {
                mStateListener.onDisconnected();
                chModifyName = null;
                chReceiveData = null;
                mIsConnected = false;
            } else if (BluetoothLeService.ACTION_GATT_SERVICES_DISCOVERED.equals(action)) {
                // Show all the supported services and characteristics on the user interface.
                initCharacteristic();
                mBluetoothLeService.setCharacteristicNotification(chReceiveData, true);
            } else if (BluetoothLeService.ACTION_DATA_AVAILABLE.equals(action)) {
                Log.e(TAG, "onReceive: " + intent.getStringExtra(BluetoothLeService.EXTRA_DATA));
            } else if (BluetoothLeService.ACTION_SPO2_DATA_AVAILABLE.equals(action)) {
                mDataParser.add(intent.getByteArrayExtra(BluetoothLeService.EXTRA_DATA));
            }
        }
    };

    private static IntentFilter makeGattUpdateIntentFilter() {
        final IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(BluetoothLeService.ACTION_GATT_CONNECTED);
        intentFilter.addAction(BluetoothLeService.ACTION_GATT_DISCONNECTED);
        intentFilter.addAction(BluetoothLeService.ACTION_GATT_SERVICES_DISCOVERED);
        intentFilter.addAction(BluetoothLeService.ACTION_DATA_AVAILABLE);
        intentFilter.addAction(BluetoothLeService.ACTION_SPO2_DATA_AVAILABLE);
        return intentFilter;
    }

    private void initCharacteristic() {
        List<BluetoothGattService> services =
                mBluetoothLeService.getSupportedGattServices();
        BluetoothGattService mInfoService = null;
        BluetoothGattService mDataService = null;

        if (services == null) return;

        for (BluetoothGattService service : services) {
            if (service.getUuid().equals(Const.UUID_SERVICE_DATA)) {
                mDataService = service;
            }
        }
        if (mDataService != null) {
            List<BluetoothGattCharacteristic> characteristics =
                    mDataService.getCharacteristics();
            for (BluetoothGattCharacteristic ch : characteristics) {
                if (ch.getUuid().equals(Const.UUID_CHARACTER_RECEIVE)) {
                    chReceiveData = ch;
                } else if (ch.getUuid().equals(Const.UUID_MODIFY_BT_NAME)) {
                    chModifyName = ch;
                }
            }
        }
    }


    public interface StateListener {
        void onDataReceived(int spo2, int pulse, int pi);

        void onWaveReceived(int amplitude);

        void onFoundDevice(BluetoothDevice device);

        void onConnected();

        void onDisconnected();

        void onScanStop();
    }
}
