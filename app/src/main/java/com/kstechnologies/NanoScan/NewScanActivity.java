package com.kstechnologies.NanoScan;

import android.app.ActionBar;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.FragmentTransaction;
import android.app.ProgressDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.content.pm.PackageManager;
import android.Manifest;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.viewpager.widget.PagerAdapter;
import androidx.viewpager.widget.ViewPager;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ToggleButton;

import com.github.mikephil.charting.animation.Easing;
import com.github.mikephil.charting.charts.LineChart;
import com.github.mikephil.charting.components.Legend;
import com.github.mikephil.charting.components.LimitLine;
import com.github.mikephil.charting.components.XAxis;
import com.github.mikephil.charting.components.YAxis;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.data.LineData;
import com.github.mikephil.charting.data.LineDataSet;
import com.github.mikephil.charting.components.Description;
import com.github.mikephil.charting.interfaces.datasets.ILineDataSet;
import com.github.mikephil.charting.formatter.IndexAxisValueFormatter;


import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import com.opencsv.CSVWriter;
import com.kstechnologies.nirscannanolibrary.KSTNanoSDK;
import com.kstechnologies.nirscannanolibrary.SettingsManager;

/**
 * Activity controlling the Nano once it is connected
 * This activity allows a user to initiate a scan, as well as access other "connection-only"
 * settings. When first launched, the app will scan for a preferred device
 * for {@link NanoBLEService#SCAN_PERIOD}, if it is not found, then it will start another "open"
 * scan for any Nano.
 *
 * If a preferred Nano has not been set, it will start a single scan. If at the end of scanning, a
 * Nano has not been found, a message will be presented to the user indicating and error, and the
 * activity will finish
 *
 * WARNING: This activity uses JNI function calls for communicating with the Spectrum C library, It
 * is important that the name and file structure of this activity remain unchanged, or the functions
 * will NOT work
 *
 * @author collinmast
 */
public class NewScanActivity extends Activity {

    private static Context mContext;

    private ProgressDialog barProgressDialog;

    private ViewPager mViewPager;
    private String fileName;
    private ArrayList<String> mXValues;

    private ArrayList<Entry> mIntensityFloat;
    private ArrayList<Entry> mAbsorbanceFloat;
    private ArrayList<Entry> mReflectanceFloat;
    private ArrayList<Float> mWavelengthFloat;

    private final BroadcastReceiver scanDataReadyReceiver = new scanDataReadyReceiver();
    private final BroadcastReceiver refReadyReceiver = new refReadyReceiver();
    private final BroadcastReceiver notifyCompleteReceiver = new notifyCompleteReceiver();
    private final BroadcastReceiver scanStartedReceiver = new ScanStartedReceiver();
    private final BroadcastReceiver requestCalCoeffReceiver = new requestCalCoeffReceiver();
    private final BroadcastReceiver requestCalMatrixReceiver = new requestCalMatrixReceiver();
    private final BroadcastReceiver disconnReceiver = new DisconnReceiver();

    private final IntentFilter scanDataReadyFilter = new IntentFilter(KSTNanoSDK.SCAN_DATA);
    private final IntentFilter refReadyFilter = new IntentFilter(KSTNanoSDK.REF_CONF_DATA);
    private final IntentFilter notifyCompleteFilter = new IntentFilter(KSTNanoSDK.ACTION_NOTIFY_DONE);
    private final IntentFilter requestCalCoeffFilter = new IntentFilter(KSTNanoSDK.ACTION_REQ_CAL_COEFF);
    private final IntentFilter requestCalMatrixFilter = new IntentFilter(KSTNanoSDK.ACTION_REQ_CAL_MATRIX);
    private final IntentFilter disconnFilter = new IntentFilter(KSTNanoSDK.ACTION_GATT_DISCONNECTED);
    private final IntentFilter scanStartedFilter = new IntentFilter(NanoBLEService.ACTION_SCAN_STARTED);

    private final BroadcastReceiver scanConfReceiver = new ScanConfReceiver();
    private final IntentFilter scanConfFilter = new IntentFilter(KSTNanoSDK.SCAN_CONF_DATA);

    private ProgressBar calProgress;
    private KSTNanoSDK.ScanResults results;
    private EditText filePrefix;
    private ToggleButton btn_os;
    private ToggleButton btn_sd;
    private ToggleButton btn_continuous;
    private Button btn_scan;

    private NanoBLEService mNanoBLEService;
    private BluetoothAdapter mBluetoothAdapter;
    private BluetoothLeScanner mBluetoothLeScanner;
    private Handler mHandler;
    private boolean connected;
    private AlertDialog alertDialog;
    private TextView tv_scan_conf;
    private String preferredDevice;
    private LinearLayout ll_conf;
    private KSTNanoSDK.ScanConfiguration activeConf;
    private boolean mScanning; // Added flag to track scanning state

    private Menu mMenu;
    private boolean serviceBound = false;
    private static final int PERMISSION_REQ_CODE = 1;

    /**
     * Request required Bluetooth permissions and bind to the service once granted.
     */
    private void checkPermissionsAndBindService() {
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
            // Only bind service if not already bound
            if (!serviceBound) {
                Intent gattServiceIntent = new Intent(this, NanoBLEService.class);
                bindService(gattServiceIntent, mServiceConnection, BIND_AUTO_CREATE);
                serviceBound = true;

                //Register all needed broadcast receivers
                LocalBroadcastManager.getInstance(mContext).registerReceiver(scanDataReadyReceiver, scanDataReadyFilter);
                LocalBroadcastManager.getInstance(mContext).registerReceiver(refReadyReceiver, refReadyFilter);
                LocalBroadcastManager.getInstance(mContext).registerReceiver(notifyCompleteReceiver, notifyCompleteFilter);
                LocalBroadcastManager.getInstance(mContext).registerReceiver(requestCalCoeffReceiver, requestCalCoeffFilter);
                LocalBroadcastManager.getInstance(mContext).registerReceiver(requestCalMatrixReceiver, requestCalMatrixFilter);
                LocalBroadcastManager.getInstance(mContext).registerReceiver(disconnReceiver, disconnFilter);
                LocalBroadcastManager.getInstance(mContext).registerReceiver(scanConfReceiver, scanConfFilter);
                LocalBroadcastManager.getInstance(mContext).registerReceiver(scanStartedReceiver, scanStartedFilter);
            }
        }
    }


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_new_scan);

        mContext = this;

        calProgress = (ProgressBar) findViewById(R.id.calProgress);
        calProgress.setVisibility(View.VISIBLE);
        connected = false;
        mScanning = false; // Initialize scanning state

        ll_conf = (LinearLayout)findViewById(R.id.ll_conf);
        ll_conf.setClickable(false);
        ll_conf.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if(activeConf != null) {
                    Intent activeConfIntent = new Intent(mContext, ActiveScanActivity.class);
                    activeConfIntent.putExtra("conf",activeConf);
                    startActivity(activeConfIntent);
                }
            }
        });

        //Set the filename from the intent
        Intent intent = getIntent();
        fileName = intent.getStringExtra("file_name");

        //Set up action bar enable tab navigation
        ActionBar ab = getActionBar();
        if (ab != null) {
            ab.setDisplayHomeAsUpEnabled(true);
            ab.setTitle(getString(R.string.new_scan));
            ab.setNavigationMode(ActionBar.NAVIGATION_MODE_TABS);

            mViewPager = (ViewPager) findViewById(R.id.viewpager);
            mViewPager.setOffscreenPageLimit(2);

            // Create a tab listener that is called when the user changes tabs.
            ActionBar.TabListener tl = new ActionBar.TabListener() {
                @Override
                public void onTabSelected(ActionBar.Tab tab, FragmentTransaction fragmentTransaction) {
                    mViewPager.setCurrentItem(tab.getPosition());
                }

                @Override
                public void onTabUnselected(ActionBar.Tab tab, FragmentTransaction fragmentTransaction) {

                }

                @Override
                public void onTabReselected(ActionBar.Tab tab, FragmentTransaction fragmentTransaction) {

                }
            };

            // Add 3 tabs, specifying the tab's text and TabListener
            for (int i = 0; i < 3; i++) {
                ab.addTab(
                        ab.newTab()
                                .setText(getResources().getStringArray(R.array.graph_tab_index)[i])
                                .setTabListener(tl));
            }
        }

        //Set up UI elements and event handlers
        filePrefix = (EditText) findViewById(R.id.et_prefix);
        btn_os = (ToggleButton) findViewById(R.id.btn_saveOS);
        btn_sd = (ToggleButton) findViewById(R.id.btn_saveSD);
        btn_continuous = (ToggleButton) findViewById(R.id.btn_continuous);
        btn_scan = (Button) findViewById(R.id.btn_scan);
        tv_scan_conf = (TextView) findViewById(R.id.tv_scan_conf);

        btn_os.setChecked(SettingsManager.getBooleanPref(mContext, SettingsManager.SharedPreferencesKeys.saveOS, false));
        btn_sd.setChecked(SettingsManager.getBooleanPref(mContext, SettingsManager.SharedPreferencesKeys.saveSD, false));
        btn_continuous.setChecked(SettingsManager.getBooleanPref(mContext, SettingsManager.SharedPreferencesKeys.continuousScan, false));

        btn_sd.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton compoundButton, boolean b) {
                SettingsManager.storeBooleanPref(mContext, SettingsManager.SharedPreferencesKeys.saveSD, b);
            }
        });

        btn_scan.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                SettingsManager.storeStringPref(mContext, SettingsManager.SharedPreferencesKeys.prefix, filePrefix.getText().toString());
                LocalBroadcastManager.getInstance(mContext).sendBroadcast(new Intent(KSTNanoSDK.START_SCAN));
                calProgress.setVisibility(View.VISIBLE);
                btn_scan.setText(getString(R.string.scanning));
            }
        });

        btn_scan.setClickable(false);
        btn_scan.setBackgroundColor(ContextCompat.getColor(mContext, R.color.btn_unavailable));

        //Bind to the service after required permissions are granted
        checkPermissionsAndBindService();
    }

    @Override
    public void onResume() {
        super.onResume();

        //Initialize view pager
        CustomPagerAdapter pagerAdapter = new CustomPagerAdapter(this);
        mViewPager.setAdapter(pagerAdapter);
        mViewPager.invalidate();

        tv_scan_conf.setText(SettingsManager.getStringPref(mContext, SettingsManager.SharedPreferencesKeys.scanConfiguration, "Column 1"));

        mViewPager.setOnPageChangeListener(
                new ViewPager.SimpleOnPageChangeListener() {
                    @Override
                    public void onPageSelected(int position) {
                        // When swiping between pages, select the
                        // corresponding tab.
                        ActionBar ab = getActionBar();
                        if (ab != null) {
                            getActionBar().setSelectedNavigationItem(position);
                        }
                    }
                });

        mXValues = new ArrayList<>();
        mIntensityFloat = new ArrayList<>();
        mAbsorbanceFloat = new ArrayList<>();
        mReflectanceFloat = new ArrayList<>();
        mWavelengthFloat = new ArrayList<>();

        // Re-check permissions and potentially start scan if not already scanning
        // This handles cases where the app was paused and resumed
        if (!mScanning && mNanoBLEService != null) {
            // The service is already bound, so we can directly initiate the scan logic
            // based on preferred device or general scan.
            if (SettingsManager.getStringPref(mContext, SettingsManager.SharedPreferencesKeys.preferredDevice, null) != null) {
                preferredDevice = SettingsManager.getStringPref(mContext, SettingsManager.SharedPreferencesKeys.preferredDevice, null);
                scanPreferredLeDevice(true);
            } else {
                scanLeDevice(true);
            }
        }
    }

    /*
     * When the activity is destroyed, unregister all broadcast receivers, remove handler callbacks,
     * and store all user preferences
     */
    @Override
    public void onDestroy() {
        super.onDestroy();
        if(serviceBound){
            unbindService(mServiceConnection);
            serviceBound = false;
        }
        LocalBroadcastManager.getInstance(mContext).unregisterReceiver(scanDataReadyReceiver);
        LocalBroadcastManager.getInstance(mContext).unregisterReceiver(refReadyReceiver);
        LocalBroadcastManager.getInstance(mContext).unregisterReceiver(notifyCompleteReceiver);
        LocalBroadcastManager.getInstance(mContext).unregisterReceiver(requestCalCoeffReceiver);
        LocalBroadcastManager.getInstance(mContext).unregisterReceiver(requestCalMatrixReceiver);
        LocalBroadcastManager.getInstance(mContext).unregisterReceiver(disconnReceiver);
        LocalBroadcastManager.getInstance(mContext).unregisterReceiver(scanConfReceiver);
        LocalBroadcastManager.getInstance(mContext).unregisterReceiver(scanStartedReceiver); // Unregister scan started receiver

        mHandler.removeCallbacksAndMessages(null);

        SettingsManager.storeBooleanPref(mContext, SettingsManager.SharedPreferencesKeys.saveOS, btn_os.isChecked());
        SettingsManager.storeBooleanPref(mContext, SettingsManager.SharedPreferencesKeys.saveSD, btn_sd.isChecked());
        SettingsManager.storeBooleanPref(mContext, SettingsManager.SharedPreferencesKeys.continuousScan, btn_continuous.isChecked());
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Stop scanning when the activity is paused to save battery
        if (mScanning) {
            scanLeDevice(false);
            scanPreferredLeDevice(false); // Ensure both types of scans are stopped
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
                checkPermissionsAndBindService();
            } else {
                Toast.makeText(this, "Bluetooth permissions are required", Toast.LENGTH_SHORT).show();
                finish();
            }
        }
    }

    /*
     * Inflate the options menu so that user actions are present
     */
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.menu_new_scan, menu);
        mMenu = menu;
        mMenu.findItem(R.id.action_settings).setEnabled(false);

        return true;
    }

    /*
     * Handle the selection of a menu item.
     * In this case, the user has the ability to access settings while the Nano is connected
     */
    @Override
    public boolean onOptionsItemSelected(MenuItem item) {

        int id = item.getItemId();

        //noinspection SimplifiableIfStatement
        if (id == R.id.action_settings) {
            Intent configureIntent = new Intent(mContext, ConfigureActivity.class);
            startActivity(configureIntent);
        }

        if (id == android.R.id.home) {
            this.finish();
        }

        return super.onOptionsItemSelected(item);
    }

    /**
     * Pager enum to control tab tile and layout resource
     */
    public enum CustomPagerEnum {

        REFLECTANCE(R.string.reflectance, R.layout.page_graph_reflectance),
        ABSORBANCE(R.string.absorbance, R.layout.page_graph_absorbance),
        INTENSITY(R.string.intensity, R.layout.page_graph_intensity);

        private final int mTitleResId;
        private final int mLayoutResId;

        CustomPagerEnum(int titleResId, int layoutResId) {
            mTitleResId = titleResId;
            mLayoutResId = layoutResId;
        }

        public int getLayoutResId() {
            return mLayoutResId;
        }

    }

    /**
     * Custom pager adapter to handle changing chart data when pager tabs are changed
     */
    public class CustomPagerAdapter extends PagerAdapter {

        private final Context mContext;

        public CustomPagerAdapter(Context context) {
            mContext = context;
        }

        @Override
        public Object instantiateItem(ViewGroup collection, int position) {
            CustomPagerEnum customPagerEnum = CustomPagerEnum.values()[position];
            LayoutInflater inflater = LayoutInflater.from(mContext);
            ViewGroup layout = (ViewGroup) inflater.inflate(customPagerEnum.getLayoutResId(), collection, false);
            collection.addView(layout);

            LineChart mChart = null;
            ChartType chartType = null;
            ArrayList<Entry> chartData = null;

            int layoutResId = customPagerEnum.getLayoutResId();

            if (layoutResId == R.layout.page_graph_intensity) {
                mChart = (LineChart) layout.findViewById(R.id.lineChartInt);
                chartData = mIntensityFloat;
                chartType = ChartType.INTENSITY;
            } else if (layoutResId == R.layout.page_graph_absorbance) {
                mChart = (LineChart) layout.findViewById(R.id.lineChartAbs);
                chartData = mAbsorbanceFloat;
                chartType = ChartType.ABSORBANCE;
            } else if (layoutResId == R.layout.page_graph_reflectance) {
                mChart = (LineChart) layout.findViewById(R.id.lineChartRef);
                chartData = mReflectanceFloat;
                chartType = ChartType.REFLECTANCE;
            }

            if (mChart != null && chartData != null && chartType != null) {
                mChart.setDrawGridBackground(false);

                // Set empty description (required for MPAndroidChart v3+)
                Description desc = new Description();
                desc.setText("");
                mChart.setDescription(desc);

                // Enable chart interaction
                mChart.setTouchEnabled(true);
                mChart.setDragEnabled(true);
                mChart.setScaleEnabled(true);
                mChart.setPinchZoom(true);

                // X-axis limit line
                LimitLine llXAxis = new LimitLine(10f, "Index 10");
                llXAxis.setLineWidth(4f);
                llXAxis.enableDashedLine(10f, 10f, 0f);
                llXAxis.setLabelPosition(LimitLine.LimitLabelPosition.RIGHT_BOTTOM);
                llXAxis.setTextSize(10f);

                XAxis xAxis = mChart.getXAxis();
                xAxis.setPosition(XAxis.XAxisPosition.BOTTOM);
                xAxis.addLimitLine(llXAxis);

                YAxis leftAxis = mChart.getAxisLeft();
                leftAxis.removeAllLimitLines(); // reset all limit lines to avoid overlapping lines
                leftAxis.enableGridDashedLine(10f, 10f, 0f);
                leftAxis.setDrawLimitLinesBehindData(true);

                // Set axis minimum for intensity, leave as default otherwise
                if (chartType == ChartType.INTENSITY) {
                    leftAxis.setAxisMinimum(0f);
                } else {
                    leftAxis.setAxisMinimum(Float.NEGATIVE_INFINITY);
                }

                mChart.getAxisRight().setEnabled(false);
                mChart.setAutoScaleMinMaxEnabled(true);

                // Add data
                setData(mChart, mXValues, chartData, chartType);

                // Animate chart
                mChart.animateX(2500, Easing.EaseInOutQuart);

                // Customize legend
                Legend legend = mChart.getLegend();
                legend.setForm(Legend.LegendForm.LINE);
                legend.setEnabled(false);
            }

            return layout;
        }


        @Override
        public void destroyItem(ViewGroup collection, int position, Object view) {
            collection.removeView((View) view);
        }

        @Override
        public int getCount() {
            return CustomPagerEnum.values().length;
        }

        @Override
        public boolean isViewFromObject(View view, Object object) {
            return view == object;
        }

        @Override
        public CharSequence getPageTitle(int position) {
            switch (position) {
                case 0:
                    return getString(R.string.reflectance);
                case 1:
                    return getString(R.string.absorbance);
                case 2:
                    return getString(R.string.intensity);
            }
            return null;
        }

    }

    private void setData(LineChart mChart, ArrayList<String> xValues, ArrayList<Entry> yValues, ChartType type) {
        // Create a dataset and give it a type
        LineDataSet set1 = new LineDataSet(yValues, fileName);

        // Customize style (change color per chart type)
        switch (type) {
            case REFLECTANCE:
                set1.setCircleColor(Color.RED);
                set1.setFillColor(Color.RED);
                break;
            case ABSORBANCE:
                set1.setCircleColor(Color.GREEN);
                set1.setFillColor(Color.GREEN);
                break;
            case INTENSITY:
                set1.setCircleColor(Color.BLUE);
                set1.setFillColor(Color.BLUE);
                break;
            default:
                set1.setCircleColor(Color.BLACK);
                set1.setFillColor(Color.BLACK);
                break;
        }

        set1.enableDashedLine(10f, 5f, 0f);
        set1.enableDashedHighlightLine(10f, 5f, 0f);
        set1.setColor(Color.BLACK);
        set1.setLineWidth(1f);
        set1.setCircleRadius(3f);
        set1.setDrawCircleHole(true);
        set1.setValueTextSize(9f);
        set1.setFillAlpha(65);
        set1.setDrawFilled(true);

        // Create LineData with ILineDataSet (not with xValues!)
        List<ILineDataSet> iLineDataSets = new ArrayList<>();
        iLineDataSets.add(set1);
        LineData data = new LineData(iLineDataSets);

        mChart.setData(data);

        // Set custom X labels using a ValueFormatter if you want string labels
        XAxis xAxis = mChart.getXAxis();
        xAxis.setValueFormatter(new IndexAxisValueFormatter(xValues));

        mChart.setMaxVisibleValueCount(20);
        mChart.invalidate(); // refresh chart
    }

    /**
     * Custom enum for chart type
     */
    public enum ChartType {
        REFLECTANCE,
        ABSORBANCE,
        INTENSITY
    }

    /**
     * Custom receiver for handling scan data and setting up the graphs properly
     */
    public class scanDataReadyReceiver extends BroadcastReceiver {

        public void onReceive(Context context, Intent intent) {
            calProgress.setVisibility(View.GONE);
            btn_scan.setText(getString(R.string.scan));
            byte[] scanData = intent.getByteArrayExtra(KSTNanoSDK.EXTRA_DATA);

            String scanType = intent.getStringExtra(KSTNanoSDK.EXTRA_SCAN_TYPE);
            /*
             * 7 bytes representing the current data
             * byte0: uint8_t     year; //< years since 2000
             * byte1: uint8_t     month; /**< months since January [0-11]
             * byte2: uint8_t     day; /**< day of the month [1-31]
             * byte3: uint8_t     day_of_week; /**< days since Sunday [0-6]
             * byte3: uint8_t     hour; /**< hours since midnight [0-23]
             * byte5: uint8_t     minute; //< minutes after the hour [0-59]
             * byte6: uint8_t     second; //< seconds after the minute [0-60]
             */
            String scanDate = intent.getStringExtra(KSTNanoSDK.EXTRA_SCAN_DATE);

            KSTNanoSDK.ReferenceCalibration ref = KSTNanoSDK.ReferenceCalibration.currentCalibration.get(0);
            results = KSTNanoSDK.KSTNanoSDK_dlpSpecScanInterpReference(scanData, ref.getRefCalCoefficients(), ref.getRefCalMatrix());

            mXValues.clear();
            mIntensityFloat.clear();
            mAbsorbanceFloat.clear();
            mReflectanceFloat.clear();
            mWavelengthFloat.clear();

            int index;
            for (index = 0; index < results.getLength(); index++) {
                mXValues.add(String.format("%.02f", KSTNanoSDK.ScanResults.getSpatialFreq(mContext, results.getWavelength()[index])));
                mIntensityFloat.add(new Entry((float) results.getUncalibratedIntensity()[index], index));
                mAbsorbanceFloat.add(new Entry((-1) * (float) Math.log10((double) results.getUncalibratedIntensity()[index] / (double) results.getIntensity()[index]), index));
                mReflectanceFloat.add(new Entry((float) results.getUncalibratedIntensity()[index] / results.getIntensity()[index], index));
                mWavelengthFloat.add((float) results.getWavelength()[index]);
            }

            float minWavelength = mWavelengthFloat.get(0);
            float maxWavelength = mWavelengthFloat.get(0);

            for (Float f : mWavelengthFloat) {
                if (f < minWavelength) minWavelength = f;
                if (f > maxWavelength) maxWavelength = f;
            }

            float minAbsorbance = mAbsorbanceFloat.get(0).getY();
            float maxAbsorbance = mAbsorbanceFloat.get(0).getY();

            for (Entry e : mAbsorbanceFloat) {
                if (e.getY() < minAbsorbance) minAbsorbance = e.getY();
                if (e.getY() > maxAbsorbance) maxAbsorbance = e.getY();
            }

            float minReflectance = mReflectanceFloat.get(0).getY();
            float maxReflectance = mReflectanceFloat.get(0).getY();

            for (Entry e : mReflectanceFloat) {
                if (e.getY() < minReflectance) minReflectance = e.getY();
                if (e.getY() > maxReflectance) maxReflectance = e.getY();
            }

            float minIntensity = mIntensityFloat.get(0).getY();
            float maxIntensity = mIntensityFloat.get(0).getY();

            for (Entry e : mIntensityFloat) {
                if (e.getY() < minIntensity) minIntensity = e.getY();
                if (e.getY() > maxIntensity) maxIntensity = e.getY();
            }

            mViewPager.setAdapter(mViewPager.getAdapter());
            mViewPager.invalidate();

            if (scanType.equals("00")) {
                scanType = "Column 1";
            } else {
                scanType = "Hadamard";
            }

            SimpleDateFormat simpleDateFormat = new SimpleDateFormat("ddMMyyhhmmss", java.util.Locale.getDefault());
            String ts = simpleDateFormat.format(new Date());

            ActionBar ab = getActionBar();
            if (ab != null) {

                if (filePrefix.getText().toString().equals("")) {
                    ab.setTitle("Nano" + ts);
                } else {
                    ab.setTitle(filePrefix.getText().toString() + ts);
                }
                ab.setSelectedNavigationItem(0);
            }

            boolean saveOS = btn_os.isChecked();
            boolean continuous = btn_continuous.isChecked();

            writeCSV(ts, results, saveOS);
            writeCSVDict(ts, scanType, scanDate, String.valueOf(minWavelength), String.valueOf(maxWavelength), String.valueOf(results.getLength()), String.valueOf(results.getLength()), "1", "2.00", saveOS);

            SettingsManager.storeStringPref(mContext, SettingsManager.SharedPreferencesKeys.prefix, filePrefix.getText().toString());

            if (continuous) {
                calProgress.setVisibility(View.VISIBLE);
                btn_scan.setText(getString(R.string.scanning));
                LocalBroadcastManager.getInstance(mContext).sendBroadcast(new Intent(KSTNanoSDK.SEND_DATA));
            }
        }
    }

    /**
     * Custom receiver for returning the event that reference calibrations have been read
     */
    public class refReadyReceiver extends BroadcastReceiver {

        public void onReceive(Context context, Intent intent) {
            byte[] refCoeff = intent.getByteArrayExtra(KSTNanoSDK.EXTRA_REF_COEF_DATA);
            byte[] refMatrix = intent.getByteArrayExtra(KSTNanoSDK.EXTRA_REF_MATRIX_DATA);
            ArrayList<KSTNanoSDK.ReferenceCalibration> refCal = new ArrayList<>();
            refCal.add(new KSTNanoSDK.ReferenceCalibration(refCoeff, refMatrix));
            KSTNanoSDK.ReferenceCalibration.writeRefCalFile(mContext, refCal);
            calProgress.setVisibility(View.GONE);
        }
    }

    /**
     * Custom receiver for returning the event that a scan has been initiated from the button
     */
    public class ScanStartedReceiver extends BroadcastReceiver {

        public void onReceive(Context context, Intent intent) {
            calProgress.setVisibility(View.VISIBLE);
            btn_scan.setText(getString(R.string.scanning));
        }
    }

    /**
     * Custom receiver that will request the time once all of the GATT notifications have been
     * subscribed to
     */
    public class notifyCompleteReceiver extends BroadcastReceiver {

        public void onReceive(Context context, Intent intent) {
            LocalBroadcastManager.getInstance(mContext).sendBroadcast(new Intent(KSTNanoSDK.SET_TIME));
        }
    }

    /**
     * Write scan data to CSV file
     * @param currentTime the current time to save
     * @param scanResults the {@link KSTNanoSDK.ScanResults} structure to save
     * @param saveOS boolean indicating if the CSV file should be saved to the OS
     */
    private void writeCSV(String currentTime, KSTNanoSDK.ScanResults scanResults, boolean saveOS) {

        String prefix = filePrefix.getText().toString();
        if (prefix.equals("")) {
            prefix = "Nano";
        }

        if (saveOS) {
            File csvFile = new File(getExternalFilesDir(null), prefix + currentTime + ".csv");

            CSVWriter writer;
            try {
                writer = new CSVWriter(
                        new FileWriter(csvFile),
                        ',',
                        CSVWriter.NO_QUOTE_CHARACTER,
                        CSVWriter.DEFAULT_ESCAPE_CHARACTER,
                        CSVWriter.DEFAULT_LINE_END
                );

                List<String[]> data = new ArrayList<String[]>();
                data.add(new String[]{"Wavelength,Intensity,Absorbance,Reflectance"});

                int csvIndex;
                for (csvIndex = 0; csvIndex < scanResults.getLength(); csvIndex++) {
                    double waves = scanResults.getWavelength()[csvIndex];
                    int intens = scanResults.getUncalibratedIntensity()[csvIndex];
                    float absorb = (-1) * (float) Math.log10((double) scanResults.getUncalibratedIntensity()[csvIndex] / (double) scanResults.getIntensity()[csvIndex]);
                    float reflect = (float) results.getUncalibratedIntensity()[csvIndex] / results.getIntensity()[csvIndex];
                    data.add(new String[]{String.valueOf(waves), String.valueOf(intens), String.valueOf(absorb), String.valueOf(reflect)});
                }
                writer.writeAll(data);
                writer.close();
            } catch (IOException e) {
                Log.e("NewScanActivity", "Error writing CSV file: " + e.getMessage());
                e.printStackTrace();
            }
        }
    }

    /**
     * Write the dictionary for a CSV files
     * @param currentTime the current time to be saved
     * @param scanType the scan type to be saved
     * @param timeStamp the timestamp to be saved
     * @param spectStart the spectral range start
     * @param spectEnd the spectral range end
     * @param numPoints the number of data points
     * @param resolution the scan resolution
     * @param numAverages the number of scans to average
     * @param measTime the total measurement time
     * @param saveOS boolean indicating if this file should be saved to the OS
     */
    private void writeCSVDict(String currentTime, String scanType, String timeStamp, String spectStart, String spectEnd, String numPoints, String resolution, String numAverages, String measTime, boolean saveOS) {

        String prefix = filePrefix.getText().toString();
        if (prefix.equals("")) {
            prefix = "Nano";
        }

        if (saveOS) {
            File csv = new File(getExternalFilesDir(null), prefix + currentTime + ".dict");

            CSVWriter writer;
            try {
                writer = new CSVWriter(new FileWriter(csv));
                List<String[]> data = new ArrayList<String[]>();
                data.add(new String[]{"Method", scanType});
                data.add(new String[]{"Timestamp", timeStamp});
                data.add(new String[]{"Spectral Range Start (nm)", spectStart});
                data.add(new String[]{"Spectral Range End (nm)", spectEnd});
                data.add(new String[]{"Number of Wavelength Points", numPoints});
                data.add(new String[]{"Digital Resolution", resolution});
                data.add(new String[]{"Number of Scans to Average", numAverages});
                data.add(new String[]{"Total Measurement Time (s)", measTime});

                writer.writeAll(data);

                writer.close();
            } catch (IOException e) {
                Log.e("NewScanActivity", "Error writing CSV dictionary file: " + e.getMessage());
                e.printStackTrace();
            }
        }
    }

    // Code to manage Service lifecycle.
    private final ServiceConnection mServiceConnection = new ServiceConnection() {

        @Override
        public void onServiceConnected(ComponentName componentName, IBinder service) {

            //Get a reference to the service from the service connection
            mNanoBLEService = ((NanoBLEService.LocalBinder) service).getService();

            //initialize bluetooth, if BLE is not available, then finish
            if (!mNanoBLEService.initialize()) {
                finish();
            }

            //Start scanning for available Bluetooth LE devices
            final BluetoothManager bluetoothManager =
                    (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
            mBluetoothAdapter = bluetoothManager.getAdapter();
            mBluetoothLeScanner = mBluetoothAdapter.getBluetoothLeScanner();
            if(mBluetoothLeScanner == null){
                finish();
                Toast.makeText(NewScanActivity.this, "Please ensure Bluetooth is enabled and try again", Toast.LENGTH_SHORT).show();
            }
            mHandler = new Handler();
            // Only start scan if not already scanning
            if (!mScanning) {
                if (SettingsManager.getStringPref(mContext, SettingsManager.SharedPreferencesKeys.preferredDevice, null) != null) {
                    preferredDevice = SettingsManager.getStringPref(mContext, SettingsManager.SharedPreferencesKeys.preferredDevice, null);
                    scanPreferredLeDevice(true);
                } else {
                    scanLeDevice(true);
                }
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName componentName) {
            mNanoBLEService = null;
            serviceBound = false; // Update service bound state
            mScanning = false; // Reset scanning state on service disconnect
        }
    };

    /**
     * Callback function for Bluetooth scanning. This function provides the instance of the
     * Bluetooth device {@link BluetoothDevice} that was found, it's rssi, and advertisement
     * data (scanRecord).
     * <p>
     * When a Bluetooth device is found a connection attempt is made. The scan
     * will stop once a device is discovered even if the
     * {@link NanoBLEService#SCAN_PERIOD} has not expired.
     */
    private final ScanCallback mLeScanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            super.onScanResult(callbackType, result);
            BluetoothDevice device = result.getDevice();
            String name = null;
            if (ActivityCompat.checkSelfPermission(NewScanActivity.this,
                    Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                name = device.getName();
            }
            if (name == null && result.getScanRecord() != null) {
                name = result.getScanRecord().getDeviceName();
            }
            if (name != null) {
                // Connect to the first device discovered. Name filtering has
                // been removed to allow scanners with arbitrary names.
                // IMPORTANT: If your NIRScan device has a specific name prefix (e.g., "NIRScan-")
                // or a known service UUID, you might want to add a more specific check here
                // to ensure you connect to the correct device.
                // Example: if (name.startsWith("NIRScan")) { ... }
                mNanoBLEService.connect(device.getAddress());
                connected = true;
                scanLeDevice(false); // Stop scan after connecting
            }
        }

        @Override
        public void onScanFailed(int errorCode) {
            super.onScanFailed(errorCode);
            Log.e("NewScanActivity", "BLE Scan Failed (mLeScanCallback): " + errorCode);
            mScanning = false; // Ensure scanning state is updated on failure
            Toast.makeText(mContext, "BLE Scan Failed: " + errorCode, Toast.LENGTH_LONG).show();
            // Consider more robust error handling or user notification here
        }
    };

    /**
     * Callback function for preferred Nano scanning. This function provides the instance of the
     * Bluetooth device {@link BluetoothDevice} that was found, it's rssi, and advertisement
     * data (scanRecord).
     * <p>
     * * When the preferred device is found a connection attempt is made. The scan
     * will stop once the preferred device is discovered even if the
     * {@link NanoBLEService#SCAN_PERIOD} has not expired.
     */
    private final ScanCallback mPreferredLeScanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            super.onScanResult(callbackType, result);
            BluetoothDevice device = result.getDevice();
            String name = null;
            if (ActivityCompat.checkSelfPermission(NewScanActivity.this,
                    Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                name = device.getName();
            }
            if (name == null && result.getScanRecord() != null) {
                name = result.getScanRecord().getDeviceName();
            }
            if (name != null && device.getAddress().equals(preferredDevice)) {
                // Connect when the preferred device address is found regardless
                // of the advertised name.
                mNanoBLEService.connect(device.getAddress());
                connected = true;
                scanPreferredLeDevice(false); // Stop scan after connecting
            }
        }

        @Override
        public void onScanFailed(int errorCode) {
            super.onScanFailed(errorCode);
            Log.e("NewScanActivity", "BLE Scan Failed (mPreferredLeScanCallback): " + errorCode);
            mScanning = false; // Ensure scanning state is updated on failure
            Toast.makeText(mContext, "Preferred BLE Scan Failed: " + errorCode, Toast.LENGTH_LONG).show();
            // Consider more robust error handling or user notification here
        }
    };

    /**
     * Scans for Bluetooth devices on the specified interval {@link NanoBLEService#SCAN_PERIOD}.
     * This function uses the handler {@link NewScanActivity#mHandler} to delay call to stop
     * scanning until after the interval has expired. The start and stop functions take an
     * LeScanCallback parameter that specifies the callback function when a Bluetooth device
     * has been found {@link NewScanActivity#mLeScanCallback}
     *
     * @param enable Tells the Bluetooth adapter {@link KSTNanoSDK#mBluetoothAdapter} if
     * it should start or stop scanning
     */
    private void scanLeDevice(final boolean enable) {
        // Check permissions before attempting to scan
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED ||
                    ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Permissions not granted for BLE scan.", Toast.LENGTH_SHORT).show();
                return;
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Location permission not granted for BLE scan.", Toast.LENGTH_SHORT).show();
                return;
            }
        }

        if(mBluetoothLeScanner == null){
            Log.e("NewScanActivity", "Bluetooth LE scanner not initialized.");
            Toast.makeText(NewScanActivity.this, "Bluetooth LE scanner not initialized.", Toast.LENGTH_SHORT).show();
            finish(); // Exit if scanner is null
            return;
        }

        if (enable) {
            if (mScanning) { // Don't start if already scanning
                Log.d("NewScanActivity", "Scan already in progress, not starting new general scan.");
                return;
            }
            // Stops scanning after a pre-defined scan period.
            mHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if(mBluetoothLeScanner != null && mScanning) { // Check mScanning before stopping
                        try {
                            mBluetoothLeScanner.stopScan(mLeScanCallback);
                            mScanning = false; // Update scanning state
                            Log.d("NewScanActivity", "General scan stopped by timeout.");
                        } catch (SecurityException e) {
                            Log.e("NewScanActivity", "Security exception stopping general scan: " + e.getMessage());
                        } catch (IllegalStateException e) {
                            Log.e("NewScanActivity", "Illegal state stopping general scan: " + e.getMessage());
                        }
                        if (!connected) {
                            notConnectedDialog();
                        }
                    }
                }
            }, NanoBLEService.SCAN_PERIOD);
            try {
                mBluetoothLeScanner.startScan(mLeScanCallback);
                mScanning = true; // Update scanning state
                Log.d("NewScanActivity", "General scan started.");
            } catch (SecurityException e) {
                Log.e("NewScanActivity", "Security exception starting general scan: " + e.getMessage());
                mScanning = false; // Reset scanning state on error
            } catch (IllegalStateException e) {
                Log.e("NewScanActivity", "Illegal state starting general scan: " + e.getMessage());
                mScanning = false; // Reset scanning state on error
            }
        } else {
            if (!mScanning) { // Don't stop if not scanning
                Log.d("NewScanActivity", "No general scan active to stop.");
                return;
            }
            try {
                mBluetoothLeScanner.stopScan(mLeScanCallback);
                mScanning = false; // Update scanning state
                Log.d("NewScanActivity", "General scan stopped explicitly.");
            } catch (SecurityException e) {
                Log.e("NewScanActivity", "Security exception stopping general scan: " + e.getMessage());
            } catch (IllegalStateException e) {
                Log.e("NewScanActivity", "Illegal state stopping general scan: " + e.getMessage());
            }
        }
    }

    /**
     * Scans for preferred Nano devices on the specified interval {@link NanoBLEService#SCAN_PERIOD}.
     * This function uses the handler {@link NewScanActivity#mHandler} to delay call to stop
     * scanning until after the interval has expired. The start and stop functions take an
     * LeScanCallback parameter that specifies the callback function when a Bluetooth device
     * has been found {@link NewScanActivity#mPreferredLeScanCallback}
     *
     * @param enable Tells the Bluetooth adapter {@link KSTNanoSDK#mBluetoothAdapter} if
     * it should start or stop scanning
     */
    private void scanPreferredLeDevice(final boolean enable) {
        // Check permissions before attempting to scan
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED ||
                    ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Permissions not granted for BLE scan.", Toast.LENGTH_SHORT).show();
                return;
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Location permission not granted for BLE scan.", Toast.LENGTH_SHORT).show();
                return;
            }
        }

        if(mBluetoothLeScanner == null){
            Log.e("NewScanActivity", "Bluetooth LE scanner not initialized.");
            Toast.makeText(NewScanActivity.this, "Bluetooth LE scanner not initialized.", Toast.LENGTH_SHORT).show();
            finish(); // Exit if scanner is null
            return;
        }

        if (enable) {
            if (mScanning) { // Don't start if already scanning
                Log.d("NewScanActivity", "Scan already in progress, not starting new preferred scan.");
                return;
            }
            // Stops scanning after a pre-defined scan period.
            mHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if(mBluetoothLeScanner != null && mScanning) { // Check mScanning before stopping
                        try {
                            mBluetoothLeScanner.stopScan(mPreferredLeScanCallback);
                            mScanning = false; // Update scanning state
                            Log.d("NewScanActivity", "Preferred scan stopped by timeout.");
                        } catch (SecurityException e) {
                            Log.e("NewScanActivity", "Security exception stopping preferred scan: " + e.getMessage());
                        } catch (IllegalStateException e) {
                            Log.e("NewScanActivity", "Illegal state stopping preferred scan: " + e.getMessage());
                        }
                        if (!connected) {
                            scanLeDevice(true); // Fallback to general scan if preferred not found
                        }
                    }
                }
            }, NanoBLEService.SCAN_PERIOD);
            try {
                mBluetoothLeScanner.startScan(mPreferredLeScanCallback);
                mScanning = true; // Update scanning state
                Log.d("NewScanActivity", "Preferred scan started.");
            } catch (SecurityException e) {
                Log.e("NewScanActivity", "Security exception starting preferred scan: " + e.getMessage());
                mScanning = false; // Reset scanning state on error
            } catch (IllegalStateException e) {
                Log.e("NewScanActivity", "Illegal state starting preferred scan: " + e.getMessage());
                mScanning = false; // Reset scanning state on error
            }
        } else {
            if (!mScanning) { // Don't stop if not scanning
                Log.d("NewScanActivity", "No preferred scan active to stop.");
                return;
            }
            try {
                mBluetoothLeScanner.stopScan(mPreferredLeScanCallback);
                mScanning = false; // Update scanning state
                Log.d("NewScanActivity", "Preferred scan stopped explicitly.");
            } catch (SecurityException e) {
                Log.e("NewScanActivity", "Security exception stopping preferred scan: " + e.getMessage());
            } catch (IllegalStateException e) {
                Log.e("NewScanActivity", "Illegal state stopping preferred scan: " + e.getMessage());
            }
        }
    }

    /**
     * Dialog that tells the user that a Nano is not connected. The activity will finish when the
     * user selects ok
     */
    private void notConnectedDialog() {
        final AlertDialog.Builder alertDialogBuilder = new AlertDialog.Builder(mContext);
        alertDialogBuilder.setTitle(mContext.getResources().getString(R.string.not_connected_title));
        alertDialogBuilder.setCancelable(false);
        alertDialogBuilder.setMessage(mContext.getResources().getString(R.string.not_connected_message));

        alertDialogBuilder.setPositiveButton(getResources().getString(R.string.ok), new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface arg0, int arg1) {
                alertDialog.dismiss();
                finish();
            }
        });

        alertDialog = alertDialogBuilder.create();
        alertDialog.show();

    }

    /**
     * Custom receiver for receiving calibration coefficient data.
     */
    public class requestCalCoeffReceiver extends BroadcastReceiver {

        @Override
        public void onReceive(Context context, Intent intent) {
            intent.getIntExtra(KSTNanoSDK.EXTRA_REF_CAL_COEFF_SIZE, 0);
            Boolean size = intent.getBooleanExtra(KSTNanoSDK.EXTRA_REF_CAL_COEFF_SIZE_PACKET, false);
            if (size) {
                calProgress.setVisibility(View.INVISIBLE);
                barProgressDialog = new ProgressDialog(NewScanActivity.this);

                barProgressDialog.setTitle(getString(R.string.dl_ref_cal));
                barProgressDialog.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
                barProgressDialog.setProgress(0);
                barProgressDialog.setMax(intent.getIntExtra(KSTNanoSDK.EXTRA_REF_CAL_COEFF_SIZE, 0));
                barProgressDialog.setCancelable(false);
                barProgressDialog.show();
            } else {
                barProgressDialog.setProgress(barProgressDialog.getProgress() + intent.getIntExtra(KSTNanoSDK.EXTRA_REF_CAL_COEFF_SIZE, 0));
            }
        }
    }

    /**
     * Custom receiver for receiving calibration matrix data. When this receiver action complete, it
     * will request the active configuration so that it can be displayed in the listview
     */
    public class requestCalMatrixReceiver extends BroadcastReceiver {

        @Override
        public void onReceive(Context context, Intent intent) {
            intent.getIntExtra(KSTNanoSDK.EXTRA_REF_CAL_MATRIX_SIZE, 0);
            Boolean size = intent.getBooleanExtra(KSTNanoSDK.EXTRA_REF_CAL_MATRIX_SIZE_PACKET, false);
            if (size) {
                barProgressDialog.dismiss();
                barProgressDialog = new ProgressDialog(NewScanActivity.this);

                barProgressDialog.setTitle(getString(R.string.dl_cal_matrix));
                barProgressDialog.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
                barProgressDialog.setProgress(0);
                barProgressDialog.setMax(intent.getIntExtra(KSTNanoSDK.EXTRA_REF_CAL_MATRIX_SIZE, 0));
                barProgressDialog.setCancelable(false);
                barProgressDialog.show();
            } else {
                barProgressDialog.setProgress(barProgressDialog.getProgress() + intent.getIntExtra(KSTNanoSDK.EXTRA_REF_CAL_MATRIX_SIZE, 0));
            }
            if (barProgressDialog.getProgress() == barProgressDialog.getMax()) {

                LocalBroadcastManager.getInstance(mContext).sendBroadcast(new Intent(KSTNanoSDK.REQUEST_ACTIVE_CONF));
            }
        }
    }

    /**
     * Custom receiver for handling scan configurations
     */
    private class ScanConfReceiver extends BroadcastReceiver {

        @Override
        public void onReceive(Context context, Intent intent) {

            byte[] smallArray = intent.getByteArrayExtra(KSTNanoSDK.EXTRA_DATA);
            byte[] addArray = new byte[smallArray.length * 3];
            byte[] largeArray = new byte[smallArray.length + addArray.length];

            System.arraycopy(smallArray, 0, largeArray, 0, smallArray.length);
            System.arraycopy(addArray, 0, largeArray, smallArray.length, addArray.length);

            Log.w("_JNI","largeArray Size: "+ largeArray.length);
            KSTNanoSDK.ScanConfiguration scanConf = KSTNanoSDK.KSTNanoSDK_dlpSpecScanReadConfiguration(intent.getByteArrayExtra(KSTNanoSDK.EXTRA_DATA));
            //KSTNanoSDK.ScanConfiguration scanConf = KSTNanoSDK.KSTNanoSDK_dlpSpecScanReadConfiguration(largeArray);

            activeConf = scanConf;

            barProgressDialog.dismiss();
            btn_scan.setClickable(true);
            btn_scan.setBackgroundColor(ContextCompat.getColor(mContext, R.color.kst_red));
            mMenu.findItem(R.id.action_settings).setEnabled(true);

            SettingsManager.storeStringPref(mContext, SettingsManager.SharedPreferencesKeys.scanConfiguration, scanConf.getConfigName());
            tv_scan_conf.setText(scanConf.getConfigName());


        }
    }
    /**
     * Broadcast Receiver handling the disconnect event. If the Nano disconnects,
     * this activity should finish so that the user is taken back to the {@link ScanListActivity}
     * and display a toast message
     */
    public class DisconnReceiver extends BroadcastReceiver {

        @Override
        public void onReceive(Context context, Intent intent) {
            Toast.makeText(mContext, R.string.nano_disconnected, Toast.LENGTH_SHORT).show();
            finish();
        }
    }
}
