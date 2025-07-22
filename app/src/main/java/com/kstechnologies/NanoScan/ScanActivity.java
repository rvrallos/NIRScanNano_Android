package com.kstechnologies.NanoScan;

import android.app.ActionBar;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import android.content.pm.PackageManager;
import android.Manifest;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import com.kstechnologies.nirscannanolibrary.KSTNanoSDK;
import com.kstechnologies.nirscannanolibrary.SettingsManager;

public class ScanActivity extends Activity {

    private Handler mHandler;
    private BluetoothAdapter mBluetoothAdapter;
    private BluetoothLeScanner mBluetoothLeScanner;
    private ArrayList<KSTNanoSDK.NanoDevice> nanoDeviceList = new ArrayList<>();
    private NanoScanAdapter nanoScanAdapter;
    private static Context mContext;
    private AlertDialog alertDialog;
    private static final int PERMISSION_REQ_CODE = 1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_scan);
        mContext = this;

        // Set up action bar title and enable back button
        ActionBar ab = getActionBar();
        if (ab != null) {
            ab.setTitle(getString(R.string.select_nano));
            ab.setDisplayHomeAsUpEnabled(true);
        }
        ListView lv_nanoDevices = (ListView) findViewById(R.id.lv_nanoDevices);

        // Get Bluetooth manager and adapters
        final BluetoothManager bluetoothManager =
                (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        mBluetoothAdapter = bluetoothManager.getAdapter();

        if (mBluetoothAdapter == null) {
            Toast.makeText(this, "Bluetooth not supported on this device.", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        if (!mBluetoothAdapter.isEnabled()) {
            Toast.makeText(this, "Please enable Bluetooth to scan for devices!", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        mBluetoothLeScanner = mBluetoothAdapter.getBluetoothLeScanner();
        if (mBluetoothLeScanner == null) {
            Toast.makeText(this, "Could not access BLE scanner, try restarting your device.", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        // Create adapter for the NanoDevice objects returned from a BLE scan
        nanoScanAdapter = new NanoScanAdapter(this, nanoDeviceList);
        lv_nanoDevices.setAdapter(nanoScanAdapter);

        lv_nanoDevices.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> adapterView, View view, int i, long l) {
                confirmationDialog(nanoDeviceList.get(i).getNanoMac());
            }
        });

        mHandler = new Handler();
        checkPermissionsAndStartScan();
    }

    /**
     * Request Bluetooth and Location permissions and start scanning once granted.
     */
    private void checkPermissionsAndStartScan() {
        ArrayList<String> perms = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                perms.add(Manifest.permission.BLUETOOTH_SCAN);
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                perms.add(Manifest.permission.BLUETOOTH_CONNECT);
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                perms.add(Manifest.permission.ACCESS_FINE_LOCATION);
            }
        }

        if (!perms.isEmpty()) {
            ActivityCompat.requestPermissions(this, perms.toArray(new String[0]), PERMISSION_REQ_CODE);
        } else {
            // Check if Location is enabled (required for BLE scanning on Android < 12)
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                LocationManager lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
                boolean locationEnabled = false;
                if (lm != null) {
                    try {
                        locationEnabled = lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
                                || lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
                    } catch (Exception ex) {
                        locationEnabled = false;
                    }
                }
                if (!locationEnabled) {
                    Toast.makeText(this, "Please enable Location services (GPS) for Bluetooth scanning!", Toast.LENGTH_LONG).show();
                    // (You can finish() here if you want to force the user to enable location)
                }
            }
            scanLeDevice(true);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQ_CODE) {
            boolean granted = true;
            for (int r : grantResults) {
                if (r != PackageManager.PERMISSION_GRANTED) {
                    granted = false;
                    break;
                }
            }
            if (granted) {
                checkPermissionsAndStartScan();
            } else {
                Toast.makeText(this, "Bluetooth permissions are required", Toast.LENGTH_SHORT).show();
                finish();
            }
        }
    }

    /**
     * Confirmation dialog for setting a preferred Nano device.
     */
    public void confirmationDialog(String mac) {
        final AlertDialog.Builder alertDialogBuilder = new AlertDialog.Builder(mContext);
        final String deviceMac = mac;
        alertDialogBuilder.setTitle(mContext.getResources().getString(R.string.nano_confirmation_title));
        alertDialogBuilder.setMessage(mContext.getResources().getString(R.string.nano_confirmation_msg, mac));

        alertDialogBuilder.setPositiveButton(getResources().getString(R.string.ok), new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface arg0, int arg1) {
                if (alertDialog != null) alertDialog.dismiss();
                SettingsManager.storeStringPref(mContext, SettingsManager.SharedPreferencesKeys.preferredDevice, deviceMac);
                finish();
            }
        });

        alertDialogBuilder.setNegativeButton(getResources().getString(R.string.cancel), new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                if (alertDialog != null) alertDialog.dismiss();
            }
        });

        alertDialog = alertDialogBuilder.create();
        alertDialog.show();
    }

    /**
     * BLE scan callback.
     */
    private ScanCallback mLeScanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            super.onScanResult(callbackType, result);

            BluetoothDevice device = result.getDevice();
            if (device.getName() == null || device.getAddress() == null) return; // skip nameless
            // Filter for NIRScan Nano devices if you want (optional):
            // if (!device.getName().toLowerCase().contains("nano")) return;

            // Avoid duplicates
            boolean isDeviceInList = false;
            for (KSTNanoSDK.NanoDevice d : nanoDeviceList) {
                if (d.getNanoMac().equals(device.getAddress())) {
                    isDeviceInList = true;
                    d.setRssi(result.getRssi());
                    nanoScanAdapter.notifyDataSetChanged();
                    break;
                }
            }
            if (!isDeviceInList) {
                nanoDeviceList.add(new KSTNanoSDK.NanoDevice(device, result.getRssi(), result.getScanRecord().getBytes()));
                nanoScanAdapter.notifyDataSetChanged();
            }
        }
    };

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == android.R.id.home) {
            this.finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    /**
     * Scans for Bluetooth devices for a set interval.
     */
    private void scanLeDevice(final boolean enable) {
        if (mBluetoothLeScanner == null) {
            Toast.makeText(ScanActivity.this, "Could not open BLE scanner", Toast.LENGTH_SHORT).show();
        } else {
            if (enable) {
                // Stops scanning after a pre-defined scan period.
                mHandler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        mBluetoothLeScanner.stopScan(mLeScanCallback);
                    }
                }, NanoBLEService.SCAN_PERIOD);
                mBluetoothLeScanner.startScan(mLeScanCallback);
            } else {
                mBluetoothLeScanner.stopScan(mLeScanCallback);
            }
        }
    }

    /**
     * Custom adapter that holds NanoDevice objects.
     */
    private class NanoScanAdapter extends ArrayAdapter<KSTNanoSDK.NanoDevice> {
        private final ArrayList<KSTNanoSDK.NanoDevice> nanoDevices;

        public NanoScanAdapter(Context context, ArrayList<KSTNanoSDK.NanoDevice> values) {
            super(context, -1, values);
            this.nanoDevices = values;
        }

        @Override
        public View getView(final int position, View convertView, ViewGroup parent) {
            ViewHolder viewHolder;
            if (convertView == null) {
                convertView = LayoutInflater.from(this.getContext())
                        .inflate(R.layout.row_nano_scan_item, parent, false);

                viewHolder = new ViewHolder();
                viewHolder.nanoName = (TextView) convertView.findViewById(R.id.tv_nano_name);
                viewHolder.nanoMac = (TextView) convertView.findViewById(R.id.tv_nano_mac);
                viewHolder.nanoRssi = (TextView) convertView.findViewById(R.id.tv_rssi);

                convertView.setTag(viewHolder);
            } else {
                viewHolder = (ViewHolder) convertView.getTag();
            }

            final KSTNanoSDK.NanoDevice device = getItem(position);
            if (device != null) {
                viewHolder.nanoName.setText(device.getNanoName());
                viewHolder.nanoMac.setText(device.getNanoMac());
                viewHolder.nanoRssi.setText(device.getRssiString());
            }
            return convertView;
        }
    }

    /**
     * View holder for NanoDevice objects
     */
    private class ViewHolder {
        private TextView nanoName;
        private TextView nanoMac;
        private TextView nanoRssi;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // Always stop scan when leaving activity
        if (mBluetoothLeScanner != null) {
            mBluetoothLeScanner.stopScan(mLeScanCallback);
        }
    }
}
