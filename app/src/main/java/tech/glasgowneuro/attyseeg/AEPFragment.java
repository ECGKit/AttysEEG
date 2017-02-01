package tech.glasgowneuro.attyseeg;

import android.Manifest;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Paint;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.net.Uri;
import android.os.Bundle;
import android.support.v4.app.ActivityCompat;
import android.support.v4.app.Fragment;
import android.support.v7.app.AlertDialog;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ToggleButton;

import com.androidplot.xy.BoundaryMode;
import com.androidplot.xy.LineAndPointFormatter;
import com.androidplot.xy.SimpleXYSeries;
import com.androidplot.xy.StepMode;
import com.androidplot.xy.XYPlot;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;

import tech.glasgowneuro.attyscomm.AttysComm;
import uk.me.berndporr.iirj.Butterworth;

/**
 * Created by Bernd Porr on 20/01/17.
 * <p>
 * Heartrate Plot
 */

public class AEPFragment extends Fragment {

    String TAG = "AEPFragment";

    final float highpassFreq = 10;

    private SimpleXYSeries epHistorySeries = null;

    private XYPlot aepPlot = null;

    private TextView sweepNoText = null;

    private ToggleButton toggleButtonDoSweep;

    private Button resetButton;

    private Button saveButton;

    View view = null;

    // in secs
    int sweep_duration_us = 500000;

    int nSamples = 0;

    int samplingRate = 250;

    long samplingInterval_ns = 1;

    int index = 0;

    int nSweeps = 1;

    boolean ready = false;

    boolean doSweeps = false;

    boolean acceptData = false;

    private String dataFilename = null;

    private byte dataSeparator = AttysComm.DATA_SEPARATOR_TAB;

    long nanoTime = 0;

    long prev_nano_time = 0;

    long dt_avg = 2000000;

    private final long CONST1E9 = 1000000000;

    int ignoreCtr = 100;

    Butterworth highpass;

    class StimulusGenerator implements Runnable {

        long period_nano = 0;
        boolean doRun = true;

        StimulusGenerator() {
            initSound();
        }

        void set_period_ns(long _period_nano) {
            period_nano = _period_nano;
        }

        @Override
        public void run() {

            long t0 = System.nanoTime();

            while (doRun) {
                doClickSound();
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        doClickSound();
                    }
                });
                while ((t0-System.nanoTime())>0) {
                    try {
                        Thread.sleep(0,(int)(0.5E9));
                    } catch (Exception e) {
                    }
                }
                t0 = t0 + period_nano;
            }
        }

        public synchronized void cancel() {
            doRun = false;
            sound.release();
        }

        // audio
        private AudioTrack sound;
        private byte[] rawAudio;
        int audioSamplingRate = 44100;
        int clickduration = audioSamplingRate / 1000; // 1ms
        int nAudioSamples = clickduration * 3;

        public void initSound() {
            sound = new AudioTrack(AudioManager.STREAM_MUSIC,
                    audioSamplingRate,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_8BIT,
                    nAudioSamples,
                    AudioTrack.MODE_STATIC);
            rawAudio = new byte[nAudioSamples];
            for (int i = 0; i < nAudioSamples; i++) {
                rawAudio[i] = (byte) 0x80;
            }
            for (int i = 0; i < clickduration; i++) {
                rawAudio[i] = (byte) 0x00;
                rawAudio[i + clickduration] = (byte) 0xff;
            }
            sound.write(rawAudio, 0, rawAudio.length);
        }


        public synchronized void doClickSound() {
            switch (sound.getPlayState()) {
                case AudioTrack.PLAYSTATE_PAUSED:
                    sound.stop();
                    sound.reloadStaticData();
                    sound.reloadStaticData();
                    sound.play();
                    break;
                case AudioTrack.PLAYSTATE_PLAYING:
                    sound.stop();
                    sound.reloadStaticData();
                    sound.play();
                    break;
                case AudioTrack.PLAYSTATE_STOPPED:
                    sound.reloadStaticData();
                    sound.play();
                    break;
                default:
                    break;
            }
        }

    }


    StimulusGenerator stimulusGenerator;
    Thread stimulusThread;


    public void setSamplingrate(int _samplingrate) {
        samplingRate = _samplingrate;
        samplingInterval_ns = CONST1E9 / _samplingrate;
        dt_avg = samplingInterval_ns;
    }

    public void startSweeps() {
        acceptData = true;
        doSweeps = true;
        acceptData = true;
        stimulusGenerator = new StimulusGenerator();
        stimulusGenerator.set_period_ns(sweep_duration_us*1000);
        stimulusThread = new Thread(stimulusGenerator);
        stimulusThread.start();
    }

    public void stopSweeps() {
        if (stimulusGenerator != null) {
            stimulusGenerator.cancel();
        }
        if (toggleButtonDoSweep != null) {
            toggleButtonDoSweep.setChecked(false);
        }
        doSweeps = false;
        acceptData = false;
    }

    private void reset() {
        ready = false;
        highpass.highPass(2, samplingRate, highpassFreq);
        nSamples = (int) (samplingRate * sweep_duration_us / 1000000);
        float tmax = nSamples * (1.0F / ((float) samplingRate));
        //aepPlot.setRangeBoundaries(-10, 10, BoundaryMode.FIXED);
        aepPlot.setDomainBoundaries(0, tmax * 1000, BoundaryMode.FIXED);

        aepPlot.addSeries(epHistorySeries,
                new LineAndPointFormatter(
                        Color.rgb(100, 255, 255), null, null, null));
        aepPlot.setDomainLabel("t/msec");
        aepPlot.setRangeLabel("");

        for (int i = 0; i < nSamples; i++) {
            epHistorySeries.addLast(1000.0F * (float) i * (1.0F / ((float) samplingRate)), 0.0);
        }

        index = 0;
        nSweeps = 1;

        DisplayMetrics metrics = new DisplayMetrics();
        getActivity().getWindowManager().getDefaultDisplay().getMetrics(metrics);
        int width = metrics.widthPixels;
        int height = metrics.heightPixels;
        if ((height > 1000) && (width > 1000)) {
            aepPlot.setDomainStep(StepMode.INCREMENT_BY_VAL, 50);
        } else {
            aepPlot.setDomainStep(StepMode.INCREMENT_BY_VAL, 100);
        }

        stimulusGenerator = new StimulusGenerator();

        nanoTime = System.nanoTime() + samplingInterval_ns;
        prev_nano_time = System.nanoTime();
        ignoreCtr = 100;

        ready = true;
    }


    private void resetAEP() {
        for (int i = 0; i < nSamples; i++) {
            epHistorySeries.setY(0, i);
        }
        nSweeps = 1;
    }


    /**
     * Called when the activity is first created.
     */
    @Override
    public View onCreateView(LayoutInflater inflater,
                             ViewGroup container,
                             Bundle savedInstanceState) {

        Log.d(TAG, "onCreate, creating Fragment");

        if (container == null) {
            return null;
        }

        highpass = new Butterworth();

        view = inflater.inflate(R.layout.eapfragment, container, false);

        // setup the APR Levels plot:
        aepPlot = (XYPlot) view.findViewById(R.id.bpmPlotView);
        sweepNoText = (TextView) view.findViewById(R.id.nsweepsTextView);
        sweepNoText.setText(String.format("%04d sweeps", 0));
        toggleButtonDoSweep = (ToggleButton) view.findViewById(R.id.doSweeps);
        toggleButtonDoSweep.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (isChecked) {
                    startSweeps();
                } else {
                    stopSweeps();
                }
            }
        });
        resetButton = (Button) view.findViewById(R.id.aepReset);
        resetButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                resetAEP();
            }
        });
        saveButton = (Button) view.findViewById(R.id.aepSave);
        saveButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                saveAEP();
            }
        });

        epHistorySeries = new SimpleXYSeries("AEP/uV");
        if (epHistorySeries == null) {
            if (Log.isLoggable(TAG, Log.ERROR)) {
                Log.e(TAG, "epHistorySeries == null");
            }
        }

        Paint paint = new Paint();
        paint.setColor(Color.argb(128, 0, 255, 0));
        aepPlot.getGraph().setDomainGridLinePaint(paint);
        aepPlot.getGraph().setRangeGridLinePaint(paint);


        reset();

        return view;

    }


    public void tick() {
        prev_nano_time = nanoTime;
        nanoTime = System.nanoTime();
        long dt_real = nanoTime - prev_nano_time;
        if (ignoreCtr > 0) {
            ignoreCtr--;
            return;
        }
        dt_avg = dt_avg + ((dt_real - dt_avg) / samplingRate / 100);
        stimulusGenerator.set_period_ns(dt_avg * nSamples);
    }


    private void writeAEPfile() throws IOException {

        PrintWriter aepdataFileStream;

        if (dataFilename == null) return;

        File file;

        try {
            file = new File(AttysEEG.ATTYSDIR, dataFilename.trim());
            file.createNewFile();
            Log.d(TAG, "Saving AEP to " + file.getAbsolutePath());
            aepdataFileStream = new PrintWriter(file);
        } catch (java.io.FileNotFoundException e) {
            throw e;
        }

        char s = ' ';
        switch (dataSeparator) {
            case AttysComm.DATA_SEPARATOR_SPACE:
                s = ' ';
                break;
            case AttysComm.DATA_SEPARATOR_COMMA:
                s = ',';
                break;
            case AttysComm.DATA_SEPARATOR_TAB:
                s = 9;
                break;
        }

        for (int i = 0; i < nSamples; i++) {
            aepdataFileStream.format("%e%c%e%c\n",
                    epHistorySeries.getX(i), s,
                    epHistorySeries.getY(i), s);
            if (aepdataFileStream.checkError()) {
                throw new IOException("AEP write error");
            }
        }

        aepdataFileStream.close();

        Intent mediaScanIntent = new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE);
        Uri contentUri = Uri.fromFile(file);
        mediaScanIntent.setData(contentUri);
        getActivity().sendBroadcast(mediaScanIntent);
    }


    private void saveAEP() {

        final EditText filenameEditText = new EditText(getContext());
        filenameEditText.setSingleLine(true);

        final int REQUEST_EXTERNAL_STORAGE = 1;
        String[] PERMISSIONS_STORAGE = {
                Manifest.permission.WRITE_EXTERNAL_STORAGE
        };

        int permission = ActivityCompat.checkSelfPermission(getContext(), Manifest.permission.WRITE_EXTERNAL_STORAGE);

        if (permission != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                    getActivity(),
                    PERMISSIONS_STORAGE,
                    REQUEST_EXTERNAL_STORAGE
            );
        }

        filenameEditText.setHint("");
        filenameEditText.setText(dataFilename);

        new AlertDialog.Builder(getContext())
                .setTitle("Saving AEP data")
                .setMessage("Enter the filename of the data textfile")
                .setView(filenameEditText)
                .setPositiveButton("OK", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int whichButton) {
                        dataFilename = filenameEditText.getText().toString();
                        dataFilename = dataFilename.replaceAll("[^a-zA-Z0-9.-]", "_");
                        if (!dataFilename.contains(".")) {
                            switch (dataSeparator) {
                                case AttysComm.DATA_SEPARATOR_COMMA:
                                    dataFilename = dataFilename + ".csv";
                                    break;
                                case AttysComm.DATA_SEPARATOR_SPACE:
                                    dataFilename = dataFilename + ".dat";
                                    break;
                                case AttysComm.DATA_SEPARATOR_TAB:
                                    dataFilename = dataFilename + ".tsv";
                            }
                        }
                        try {
                            writeAEPfile();
                            Toast.makeText(getActivity(),
                                    "Successfully written '" + dataFilename + "' to the external memory",
                                    Toast.LENGTH_SHORT).show();
                        } catch (IOException e) {
                            Toast.makeText(getActivity(),
                                    "Write Error while saving '" + dataFilename + "' to the external memory",
                                    Toast.LENGTH_SHORT).show();
                            Log.d(TAG, "Error saving AEP file: ", e);
                        }
                        ;
                    }
                })
                .setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int whichButton) {
                    }
                })
                .show();
    }


    public synchronized void addValue(final float v) {

        if (!ready) return;

        if (!acceptData) return;

        if (epHistorySeries == null) {
            if (Log.isLoggable(TAG, Log.VERBOSE)) {
                Log.v(TAG, "epHistorySeries == null");
            }
            return;
        }

        double avg = epHistorySeries.getY(index).doubleValue();
        double v2 = highpass.filter(v * 1E6);
        //avg = (avg + new_value)/2;
        double nSweepsD = (double) nSweeps;
        avg = ((nSweepsD - 1) / nSweepsD) * avg + (1 / nSweepsD) * v2;
        // Log.d(TAG,"avg="+avg);
        if (index < epHistorySeries.size()) {
            epHistorySeries.setY(avg, index);
        }
        index++;
        if (index == nSamples) {
            nSweeps++;
            index = 0;

            if (getActivity() != null) {
                getActivity().runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (sweepNoText != null) {
                            sweepNoText.setText(String.format("%04d sweeps", nSweeps));
                        }
                    }
                });
            }
        }
    }

    public void redraw() {
        if (aepPlot != null) {
            aepPlot.redraw();
        }
    }
}