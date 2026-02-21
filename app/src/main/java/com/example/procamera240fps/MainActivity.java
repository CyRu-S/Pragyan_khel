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

    private TextView errorText, isoValue, shutterValue;
    private com.google.android.material.slider.Slider isoSlider, shutterSlider;
    private com.google.android.material.button.MaterialButton recordButton;
    private RadioGroup fpsGroup, resolutionGroup;

    private CameraDevice cameraDevice;
    private CameraCaptureSession session;
    private CameraManager cameraManager;
    private CaptureRequest.Builder builder;

    private HandlerThread camThread;
    private Handler camHandler;

    private MediaRecorder recorder;
    private Uri savedVideoUri;

    // Defaults (overridden by UI)
    private int ISO = 100;
    private long SHUTTER = 8_000_000L;

    // 🔥 VARIABLES (NOT CONSTANTS)
    private int WIDTH = 1280;
    private int HEIGHT = 720;
    private int TARGET_FPS = 120;

    private void applyUiSelections() {

        // FPS
        int fpsId = fpsGroup.getCheckedRadioButtonId();
        if (fpsId != -1) {
            RadioButton rb = findViewById(fpsId);
            if (rb != null) {
                try {
                    TARGET_FPS = Integer.parseInt(
                            rb.getText().toString().replaceAll("[^0-9]", ""));
                } catch (Exception ignored) {}
            }
        }

        // Resolution
        int resId = resolutionGroup.getCheckedRadioButtonId();
        if (resId != -1) {
            RadioButton rb = findViewById(resId);
            if (rb != null) {
                String txt = rb.getText().toString().toLowerCase().trim();
                if (txt.contains("x")) {
                    String[] parts = txt.replace(" ", "").split("x");
                    if (parts.length == 2) {
                        try {
                            WIDTH = Integer.parseInt(parts[0]);
                            HEIGHT = Integer.parseInt(parts[1]);
                        } catch (Exception ignored) {}
                    }
                } else if (txt.contains("720")) {
                    WIDTH = 1280; HEIGHT = 720;
                } else if (txt.contains("1080")) {
                    WIDTH = 1920; HEIGHT = 1080;
                } else if (txt.contains("480")) {
                    WIDTH = 640; HEIGHT = 480;
                }
            }
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        errorText = findViewById(R.id.errorText);
        isoSlider = findViewById(R.id.isoSlider);
        shutterSlider = findViewById(R.id.shutterSlider);
        isoValue = findViewById(R.id.isoValue);
        shutterValue = findViewById(R.id.shutterValue);
        recordButton = findViewById(R.id.recordButton);
        fpsGroup = findViewById(R.id.fpsGroup);
        resolutionGroup = findViewById(R.id.resolutionGroup);

        startThread();

        isoSlider.addOnChangeListener((s, v, f) -> {
            ISO = (int) v;
            isoValue.setText("ISO: " + ISO);
        });

        shutterSlider.addOnChangeListener((s, v, f) -> {
            SHUTTER = 1_000_000L * ((int) v + 1);
            shutterValue.setText("Exposure: " + SHUTTER / 1_000_000 + " ms");
        });

        recordButton.setOnClickListener(v -> {
            if (recordButton.getText().toString()
                    .equals(getString(R.string.record))) {
                startRecording();
                recordButton.setText("STOP");
            } else {
                stopRecording();
                recordButton.setText(getString(R.string.record));
            }
        });

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
            cameraManager.openCamera(
                    cameraManager.getCameraIdList()[0],
                    stateCallback, camHandler);
        } catch (Exception ignored) {}
    }

    private final CameraDevice.StateCallback stateCallback =
            new CameraDevice.StateCallback() {
                @Override public void onOpened(CameraDevice c) {
                    cameraDevice = c;
                    errorText.setVisibility(android.view.View.GONE);
                }
                @Override public void onDisconnected(CameraDevice c) {}
                @Override public void onError(CameraDevice c, int e) {}
            };

    private FileDescriptor getFD(Uri uri) {
        try {
            return getContentResolver()
                    .openFileDescriptor(uri, "rw")
                    .getFileDescriptor();
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

        // 🔥 METADATA APPLIED HERE
        recorder.setVideoFrameRate(TARGET_FPS);
        recorder.setVideoSize(WIDTH, HEIGHT);
        recorder.setVideoEncodingBitRate(20_000_000);
        recorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);

        recorder.prepare();
    }

    private void startRecording() {
        try {
            applyUiSelections();
            prepareRecorder();

            Surface recSurface = recorder.getSurface();

            if (TARGET_FPS >= 240) {
                // 🔥 HIGH SPEED SESSION (REQUIRED FOR 240 FPS)
                builder = cameraDevice.createCaptureRequest(
                        CameraDevice.TEMPLATE_RECORD);
                builder.addTarget(recSurface);

                builder.set(CaptureRequest.CONTROL_MODE,
                        CaptureRequest.CONTROL_MODE_AUTO);

                cameraDevice.createConstrainedHighSpeedCaptureSession(
                        Arrays.asList(recSurface),
                        new CameraCaptureSession.StateCallback() {
                            @Override
                            public void onConfigured(CameraCaptureSession s) {
                                session = s;
                                try {
                                    CameraConstrainedHighSpeedCaptureSession hs =
                                            (CameraConstrainedHighSpeedCaptureSession) s;

                                    for (CaptureRequest req :
                                            hs.createHighSpeedRequestList(builder.build())) {
                                        hs.setRepeatingBurst(
                                                hs.createHighSpeedRequestList(req),
                                                null,
                                                camHandler);
                                    }

                                    recorder.start();
                                } catch (Exception e) {}
                            }

                            @Override
                            public void onConfigureFailed(CameraCaptureSession s) {}
                        }, camHandler
                );

            } else {
                // ✅ NORMAL SESSION (≤120 FPS)
                builder = cameraDevice.createCaptureRequest(
                        CameraDevice.TEMPLATE_RECORD);
                builder.addTarget(recSurface);

                builder.set(CaptureRequest.CONTROL_MODE,
                        CaptureRequest.CONTROL_MODE_OFF);
                builder.set(CaptureRequest.CONTROL_AE_MODE,
                        CaptureRequest.CONTROL_AE_MODE_OFF);

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
                                    session.setRepeatingRequest(
                                            builder.build(), null, camHandler);
                                    recorder.start();
                                } catch (Exception e) {}
                            }

                            @Override
                            public void onConfigureFailed(CameraCaptureSession s) {}
                        }, camHandler
                );
            }

        } catch (Exception e) {}
    }

    private void stopRecording() {
        try {
            session.stopRepeating();
            session.close();
            recorder.stop();

            ContentValues v = new ContentValues();
            v.put(MediaStore.Video.Media.IS_PENDING, 0);
            getContentResolver().update(savedVideoUri, v, null, null);

            recorder.reset();

            errorText.setText("Saved ✔");
            errorText.setVisibility(android.view.View.VISIBLE);

        } catch (Exception ignored) {}
    }
}