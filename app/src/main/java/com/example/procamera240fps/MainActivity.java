package com.example.procamera240fps;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.ContentValues;
import android.content.pm.PackageManager;
import android.hardware.camera2.*;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.*;
import android.provider.MediaStore;
import android.util.Range;
import android.view.Surface;
import android.widget.*;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.io.FileDescriptor;
import java.util.Arrays;

public class MainActivity extends AppCompatActivity {

    private TextView statusText, isoValue, shutterValue;
    private SeekBar isoSeek, shutterSeek;
    private Button startBtn, stopBtn;

    private CameraDevice cameraDevice;
    private CameraCaptureSession session;
    private CameraManager cameraManager;
    private CaptureRequest.Builder builder;

    private HandlerThread camThread;
    private Handler camHandler;

    private MediaRecorder recorder;
    private Uri savedVideoUri;

    private int ISO = 100;
    private long SHUTTER = 8000000L; // nanoseconds (~1/120)

    private final int WIDTH = 1280;
    private final int HEIGHT = 720;
    private final int TARGET_FPS = 120;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        statusText = findViewById(R.id.statusText);
        isoSeek = findViewById(R.id.isoSeek);
        shutterSeek = findViewById(R.id.shutterSeek);
        isoValue = findViewById(R.id.isoValue);
        shutterValue = findViewById(R.id.shutterValue);
        startBtn = findViewById(R.id.startBtn);
        stopBtn = findViewById(R.id.stopBtn);

        startThread();

        isoSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                ISO = Math.max(100, progress);
                isoValue.setText("ISO: " + ISO);
            }
            public void onStartTrackingTouch(SeekBar seekBar) {}
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        shutterSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                SHUTTER = 1000000L * (progress + 1); // microseconds
                shutterValue.setText("Exposure: " + SHUTTER/1000000 + " ms");
            }
            public void onStartTrackingTouch(SeekBar seekBar) {}
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        startBtn.setOnClickListener(v -> startRecording());
        stopBtn.setOnClickListener(v -> stopRecording());

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.CAMERA}, 100);
        }
    }

    private void startThread() {
        camThread = new HandlerThread("CamThread");
        camThread.start();
        camHandler = new Handler(camThread.getLooper());
    }

    @Override
    protected void onResume() {
        super.onResume();
        openCamera();
    }

    @SuppressLint("MissingPermission")
    private void openCamera() {
        try {
            cameraManager = (CameraManager) getSystemService(CAMERA_SERVICE);
            String cameraId = cameraManager.getCameraIdList()[0];
            cameraManager.openCamera(cameraId, stateCallback, camHandler);
        } catch (Exception e) {}
    }

    private final CameraDevice.StateCallback stateCallback =
            new CameraDevice.StateCallback() {
                @Override
                public void onOpened(CameraDevice camera) {
                    cameraDevice = camera;
                    statusText.setText("READY");
                }
                @Override public void onDisconnected(CameraDevice camera) {}
                @Override public void onError(CameraDevice camera, int error) {}
            };

    private FileDescriptor getFD(Uri uri) {
        try {
            return getContentResolver().openFileDescriptor(uri, "rw").getFileDescriptor();
        } catch (Exception e) { return null; }
    }

    private void prepareRecorder() throws Exception {

        recorder = new MediaRecorder();

        ContentValues values = new ContentValues();
        values.put(MediaStore.Video.Media.DISPLAY_NAME,
                "ManualCinema_" + System.currentTimeMillis() + ".mp4");
        values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
        values.put(MediaStore.Video.Media.RELATIVE_PATH, "DCIM/ProCamera240fps");
        values.put(MediaStore.Video.Media.IS_PENDING, 1);

        savedVideoUri = getContentResolver().insert(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);

        recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
        recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
        recorder.setOutputFile(getFD(savedVideoUri));
        recorder.setVideoEncodingBitRate(20000000);
        recorder.setVideoFrameRate(TARGET_FPS);
        recorder.setVideoSize(WIDTH, HEIGHT);
        recorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);

        recorder.prepare();
    }

    private void startRecording() {

        try {
            prepareRecorder();

            Surface recSurface = recorder.getSurface();

            builder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
            builder.addTarget(recSurface);

            // 🔥 FULL MANUAL CONTROL
            builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_OFF);
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF);

            builder.set(CaptureRequest.SENSOR_SENSITIVITY, ISO);
            builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, SHUTTER);

            builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                    new Range<>(TARGET_FPS, TARGET_FPS));

            cameraDevice.createCaptureSession(
                    Arrays.asList(recSurface),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(CameraCaptureSession s) {
                            session = s;
                            try {
                                session.setRepeatingRequest(builder.build(), null, camHandler);
                                recorder.start();
                                statusText.setText("🔴 RECORDING MANUAL MODE");
                            } catch (Exception e) {}
                        }
                        @Override public void onConfigureFailed(CameraCaptureSession s) {}
                    }, camHandler);

        } catch (Exception e) {}
    }

    private void stopRecording() {

        try {
            session.stopRepeating();
            session.close();
            recorder.stop();

            ContentValues values = new ContentValues();
            values.put(MediaStore.Video.Media.IS_PENDING, 0);
            getContentResolver().update(savedVideoUri, values, null, null);

            recorder.reset();
            statusText.setText("Saved ✔");

        } catch (Exception e) {}
    }
}