package com.vuzix.helloworld;

import android.Manifest;
import android.app.SearchManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.os.Handler;
import android.os.Looper;
import android.os.Bundle;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.util.Log;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.mediapipe.framework.image.BitmapImageBuilder;
import com.google.mediapipe.framework.image.MPImage;
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark;
import com.google.mediapipe.tasks.core.BaseOptions;
import com.google.mediapipe.tasks.vision.core.RunningMode;
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker;
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "FingerCounter";
    private static final int APP_PERMISSION_REQUEST_CODE = 1001;
    private static final int REQUIRED_STABLE_FRAMES = 2;
    private static final int COUNT_SMOOTHING_WINDOW = 5;
    private static final long VOICE_RESET_COOLDOWN_MS = 1500L;
    private static final long RESET_VISIBILITY_HOLD_MS = 2000L;
    private static final int FIST_STABLE_FRAMES = 3;
    private static final long FIST_RESET_COOLDOWN_MS = 3000L;
    private static final String VUZIX_SPEECH_PACKAGE = "com.vuzix.speechrecognitionservice";

    private PreviewView cameraPreview;
    private TextView fingerCountText;
    private TextView statusText;
    private TextView voiceDebugText;
    private Button resetChecklistButton;

    private final Map<Integer, CheckBox> fingerChecklistMap = new HashMap<>();

    private ProcessCameraProvider processCameraProvider;
    private HandLandmarker handLandmarker;
    private ExecutorService cameraExecutor;
    private SpeechRecognizer speechRecognizer;
    private Intent speechRecognizerIntent;
    private final Handler voiceHandler = new Handler(Looper.getMainLooper());
    private boolean isVoiceListening = false;
    private int consecutiveVoiceErrors = 0;

    private int currentFingerCount = 0;
    private int lastRawFingerCount = -1;
    private int stableFrameCount = 0;
    private long lastVoiceResetTimestampMs = 0L;
    private long ignoreFingerUpdatesUntilMs = 0L;
    private int stableFistFrameCount = 0;
    private long lastFistResetTimestampMs = 0L;
    private final ArrayDeque<Integer> recentFingerCounts = new ArrayDeque<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        cameraPreview = findViewById(R.id.cameraPreview);
        fingerCountText = findViewById(R.id.fingerCountText);
        statusText = findViewById(R.id.statusText);
        voiceDebugText = findViewById(R.id.voiceDebugText);
        resetChecklistButton = findViewById(R.id.resetChecklistButton);
        initializeChecklistMap();
        initializeTouchActions();

        cameraExecutor = Executors.newSingleThreadExecutor();

        initializeHandLandmarker();
        initializeVoiceRecognizer();

        updateCounterText();

        if (hasCameraPermission() && hasAudioPermission()) {
            startCamera();
            startVoiceListening();
        } else {
            ActivityCompat.requestPermissions(
                    this,
                    new String[]{Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO},
                    APP_PERMISSION_REQUEST_CODE
            );
        }

        handleIncomingVoiceIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIncomingVoiceIntent(intent);
    }

    private void handleIncomingVoiceIntent(Intent intent) {
        if (intent == null) {
            return;
        }

        String action = intent.getAction();
        String phrase = extractVoicePhraseFromIntent(intent);
        if (phrase == null || phrase.isEmpty()) {
            return;
        }

        String normalized = phrase.toLowerCase(Locale.US)
                .replace("-", " ")
                .replaceAll("[^a-z0-9 ]", "")
                .replaceAll("\\s+", " ")
                .trim();

        updateVoiceDebugText(getString(
                R.string.voice_debug_intent,
                action == null ? "(no-action)" : action,
                normalized
        ));

        if (isResetChecklistCommand(normalized)) {
            lastVoiceResetTimestampMs = System.currentTimeMillis();
            resetChecklist();
        }
    }

    private String extractVoicePhraseFromIntent(Intent intent) {
        List<String> candidates = new ArrayList<>();

        addCandidateIfPresent(candidates, intent.getStringExtra("query"));
        addCandidateIfPresent(candidates, intent.getStringExtra(SearchManager.QUERY));
        addCandidateIfPresent(candidates, intent.getStringExtra(Intent.EXTRA_TEXT));
        addCandidateIfPresent(candidates, intent.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL));
        addCandidateIfPresent(candidates, intent.getStringExtra("android.intent.extra.TEXT"));
        addCandidateIfPresent(candidates, intent.getStringExtra("voice_query"));

        ArrayList<String> speechResults = intent.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
        if (speechResults != null) {
            candidates.addAll(speechResults);
        }

        ArrayList<String> directResults = intent.getStringArrayListExtra("results");
        if (directResults != null) {
            candidates.addAll(directResults);
        }

        for (String candidate : candidates) {
            if (candidate != null && !candidate.trim().isEmpty()) {
                return candidate.trim();
            }
        }

        return null;
    }

    private void addCandidateIfPresent(List<String> candidates, String value) {
        if (value != null && !value.trim().isEmpty()) {
            candidates.add(value);
        }
    }

    private void initializeTouchActions() {
        if (resetChecklistButton != null) {
            resetChecklistButton.setOnClickListener(view -> {
                updateVoiceDebugText(getString(R.string.voice_debug_manual));
                resetChecklist();
            });
        }
    }

    private boolean hasCameraPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasAudioPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void initializeChecklistMap() {
        fingerChecklistMap.put(1, findViewById(R.id.checkOne));
        fingerChecklistMap.put(2, findViewById(R.id.checkTwo));
        fingerChecklistMap.put(3, findViewById(R.id.checkThree));
        fingerChecklistMap.put(4, findViewById(R.id.checkFour));
        fingerChecklistMap.put(5, findViewById(R.id.checkFive));
    }

    private void initializeHandLandmarker() {
        try {
            BaseOptions baseOptions = BaseOptions.builder()
                    .setModelAssetPath("hand_landmarker.task")
                    .build();

            HandLandmarker.HandLandmarkerOptions options = HandLandmarker.HandLandmarkerOptions.builder()
                    .setBaseOptions(baseOptions)
                    .setRunningMode(RunningMode.IMAGE)
                    .setNumHands(1)
                    .setMinHandDetectionConfidence(0.5f)
                    .setMinHandPresenceConfidence(0.5f)
                    .setMinTrackingConfidence(0.5f)
                    .build();

            handLandmarker = HandLandmarker.createFromOptions(this, options);
        } catch (Exception exception) {
            statusText.setText(R.string.status_detection_error);
            Log.e(TAG, "Failed to initialize hand landmarker", exception);
        }
    }

    private void initializeVoiceRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            statusText.setText(R.string.status_voice_unavailable);
            return;
        }

        ComponentName serviceComponent = findSpeechRecognitionService();
        if (serviceComponent != null) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this, serviceComponent);
            Log.i(TAG, "Using speech service: " + serviceComponent.flattenToShortString());
        } else {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this);
            Log.w(TAG, "No explicit speech service found; using default recognizer");
        }

        speechRecognizer.setRecognitionListener(new RecognitionListener() {
            @Override
            public void onReadyForSpeech(Bundle params) {
                isVoiceListening = true;
                consecutiveVoiceErrors = 0;
                updateVoiceDebugText(getString(R.string.status_voice_listening));
            }

            @Override
            public void onBeginningOfSpeech() {
            }

            @Override
            public void onRmsChanged(float rmsdB) {
            }

            @Override
            public void onBufferReceived(byte[] buffer) {
            }

            @Override
            public void onEndOfSpeech() {
                isVoiceListening = false;
            }

            @Override
            public void onError(int error) {
                isVoiceListening = false;
                consecutiveVoiceErrors++;
                Log.w(TAG, "Speech recognizer error: " + error);
                updateVoiceDebugText(getString(R.string.voice_debug_error, formatSpeechError(error)));

                if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                    Toast.makeText(MainActivity.this, R.string.status_voice_permission_needed, Toast.LENGTH_SHORT).show();
                    return;
                }

                if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) {
                    scheduleVoiceRestart(700L);
                    return;
                }

                if (error == SpeechRecognizer.ERROR_CLIENT) {
                    try {
                        speechRecognizer.cancel();
                    } catch (Exception ignored) {
                    }
                }

                long restartDelayMs = consecutiveVoiceErrors > 5 ? 1200L : 450L;
                scheduleVoiceRestart(restartDelayMs);
            }

            @Override
            public void onResults(Bundle results) {
                isVoiceListening = false;
                handleVoiceResults(results);
                scheduleVoiceRestart(250L);
            }

            @Override
            public void onPartialResults(Bundle partialResults) {
                handleVoiceResults(partialResults);
            }

            @Override
            public void onEvent(int eventType, Bundle params) {
            }
        });

        speechRecognizerIntent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        speechRecognizerIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        speechRecognizerIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault());
        speechRecognizerIntent.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, getPackageName());
        speechRecognizerIntent.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false);
        speechRecognizerIntent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        speechRecognizerIntent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3);
        speechRecognizerIntent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1200L);
        speechRecognizerIntent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 800L);
    }

    private ComponentName findSpeechRecognitionService() {
        Intent queryIntent = new Intent("android.speech.RecognitionService");
        List<ResolveInfo> resolveInfos = getPackageManager().queryIntentServices(queryIntent, 0);
        if (resolveInfos == null || resolveInfos.isEmpty()) {
            return null;
        }

        for (ResolveInfo resolveInfo : resolveInfos) {
            if (resolveInfo.serviceInfo != null
                    && VUZIX_SPEECH_PACKAGE.equals(resolveInfo.serviceInfo.packageName)) {
                return new ComponentName(resolveInfo.serviceInfo.packageName, resolveInfo.serviceInfo.name);
            }
        }

        ResolveInfo fallback = resolveInfos.get(0);
        if (fallback.serviceInfo == null) {
            return null;
        }
        return new ComponentName(fallback.serviceInfo.packageName, fallback.serviceInfo.name);
    }

    private void startVoiceListening() {
        if (speechRecognizer == null || speechRecognizerIntent == null || !hasAudioPermission()) {
            return;
        }
        if (isVoiceListening) {
            return;
        }
        try {
            speechRecognizer.startListening(speechRecognizerIntent);
        } catch (Exception exception) {
            isVoiceListening = false;
            scheduleVoiceRestart(500L);
        }
    }

    private void scheduleVoiceRestart(long delayMs) {
        voiceHandler.removeCallbacksAndMessages(null);
        voiceHandler.postDelayed(this::startVoiceListening, delayMs);
    }

    private void handleVoiceResults(Bundle results) {
        ArrayList<String> matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        if (matches == null || matches.isEmpty()) {
            return;
        }

        long nowMs = System.currentTimeMillis();
        if (nowMs - lastVoiceResetTimestampMs < VOICE_RESET_COOLDOWN_MS) {
            return;
        }

        for (String phrase : matches) {
            if (phrase == null) {
                continue;
            }

            String normalized = phrase.toLowerCase(Locale.US)
                    .replace("-", " ")
                    .replaceAll("[^a-z0-9 ]", "")
                    .replaceAll("\\s+", " ")
                    .trim();
            Log.i(TAG, "Voice phrase: " + normalized);
            updateVoiceDebugText(getString(R.string.voice_debug_heard, normalized));

            if (isResetChecklistCommand(normalized)) {
                lastVoiceResetTimestampMs = nowMs;
                resetChecklist();
                break;
            }
        }
    }

    private void updateVoiceDebugText(String message) {
        if (voiceDebugText != null) {
            voiceDebugText.setText(message);
        }
    }

    private String formatSpeechError(int error) {
        switch (error) {
            case SpeechRecognizer.ERROR_AUDIO:
                return "AUDIO";
            case SpeechRecognizer.ERROR_CLIENT:
                return "CLIENT";
            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:
                return "INSUFFICIENT_PERMISSIONS";
            case SpeechRecognizer.ERROR_NETWORK:
                return "NETWORK";
            case SpeechRecognizer.ERROR_NETWORK_TIMEOUT:
                return "NETWORK_TIMEOUT";
            case SpeechRecognizer.ERROR_NO_MATCH:
                return "NO_MATCH";
            case SpeechRecognizer.ERROR_RECOGNIZER_BUSY:
                return "RECOGNIZER_BUSY";
            case SpeechRecognizer.ERROR_SERVER:
                return "SERVER";
            case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
                return "SPEECH_TIMEOUT";
            default:
                return "CODE_" + error;
        }
    }

    private boolean isResetChecklistCommand(String normalizedPhrase) {
        return normalizedPhrase.contains("reset checklist")
                || normalizedPhrase.contains("reset check list")
                || normalizedPhrase.contains("reset the checklist")
                || normalizedPhrase.contains("clear checklist")
                || normalizedPhrase.contains("clear check list")
                || normalizedPhrase.contains("vuzix reset checklist")
                || normalizedPhrase.contains("vuzix clear checklist")
                || (normalizedPhrase.contains("reset") && normalizedPhrase.contains("checklist"));
    }

    private void resetChecklist() {
        for (CheckBox checkBox : fingerChecklistMap.values()) {
            checkBox.setChecked(false);
        }
        currentFingerCount = 0;
        lastRawFingerCount = -1;
        stableFrameCount = 0;
        stableFistFrameCount = 0;
        recentFingerCounts.clear();
        ignoreFingerUpdatesUntilMs = System.currentTimeMillis() + RESET_VISIBILITY_HOLD_MS;
        updateCounterText();
        Toast.makeText(this, R.string.status_voice_reset_done, Toast.LENGTH_SHORT).show();
    }

    private void startCamera() {
        statusText.setText(R.string.status_detecting);

        ListenableFuture<ProcessCameraProvider> cameraProviderFuture =
                ProcessCameraProvider.getInstance(this);

        cameraProviderFuture.addListener(() -> {
            try {
                processCameraProvider = cameraProviderFuture.get();
                bindCameraUseCases();
            } catch (ExecutionException | InterruptedException exception) {
                statusText.setText(R.string.status_camera_error);
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void bindCameraUseCases() {
        if (processCameraProvider == null) {
            return;
        }

        Preview preview = new Preview.Builder().build();
        preview.setSurfaceProvider(cameraPreview.getSurfaceProvider());

        ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build();

        imageAnalysis.setAnalyzer(cameraExecutor, this::analyzeImage);

        CameraSelector cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA;

        processCameraProvider.unbindAll();
        try {
            processCameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalysis);
        } catch (Exception exception) {
            statusText.setText(R.string.status_camera_error);
        }
    }

    private void analyzeImage(@NonNull ImageProxy imageProxy) {
        if (handLandmarker == null) {
            imageProxy.close();
            return;
        }

        Bitmap bitmap = imageProxyToBitmap(imageProxy);
        if (bitmap == null) {
            imageProxy.close();
            return;
        }

        Bitmap rotatedBitmap = rotateBitmap(bitmap, imageProxy.getImageInfo().getRotationDegrees());
        MPImage mpImage = new BitmapImageBuilder(rotatedBitmap).build();

        try {
            HandLandmarkerResult result = handLandmarker.detect(mpImage);
            int rawFingerCount = extractFingerCount(result);
            boolean fist = isFistGesture(result);
            runOnUiThread(() -> updateFingerCountState(rawFingerCount, fist));
        } catch (Exception exception) {
            runOnUiThread(() -> statusText.setText(R.string.status_detection_error));
        } finally {
            imageProxy.close();
        }
    }

    private void updateFingerCountState(int rawFingerCount, boolean isFist) {
        if (System.currentTimeMillis() < ignoreFingerUpdatesUntilMs) {
            stableFistFrameCount = 0;
            return;
        }

        long nowMs = System.currentTimeMillis();
        if (isFist) {
            if (stableFistFrameCount < FIST_STABLE_FRAMES) {
                stableFistFrameCount++;
            }
            if (stableFistFrameCount >= FIST_STABLE_FRAMES
                    && nowMs - lastFistResetTimestampMs >= FIST_RESET_COOLDOWN_MS) {
                lastFistResetTimestampMs = nowMs;
                stableFistFrameCount = 0;
                resetChecklist();
                return;
            }
        } else {
            stableFistFrameCount = 0;
        }

        int filteredFingerCount = getSmoothedFingerCount(rawFingerCount);

        if (filteredFingerCount > 0) {
            statusText.setText(R.string.status_tracking);
        } else {
            statusText.setText(R.string.status_no_hand);
        }

        if (filteredFingerCount == lastRawFingerCount) {
            stableFrameCount++;
        } else {
            lastRawFingerCount = filteredFingerCount;
            stableFrameCount = 1;
        }

        if (stableFrameCount < REQUIRED_STABLE_FRAMES) {
            return;
        }

        if (filteredFingerCount != currentFingerCount) {
            currentFingerCount = filteredFingerCount;
            updateCounterText();
            checkOffFingerNumber(currentFingerCount);
        }
    }

    private int getSmoothedFingerCount(int rawFingerCount) {
        recentFingerCounts.addLast(rawFingerCount);
        while (recentFingerCounts.size() > COUNT_SMOOTHING_WINDOW) {
            recentFingerCounts.removeFirst();
        }

        int[] bins = new int[6];
        for (int count : recentFingerCounts) {
            int clamped = Math.max(0, Math.min(5, count));
            bins[clamped]++;
        }

        int bestCount = 0;
        int bestVotes = -1;
        for (int count = 0; count <= 5; count++) {
            if (bins[count] > bestVotes) {
                bestVotes = bins[count];
                bestCount = count;
            }
        }

        return bestCount;
    }

    private void updateCounterText() {
        fingerCountText.setText(getString(R.string.finger_count_format, currentFingerCount));
    }

    private void checkOffFingerNumber(int fingerCount) {
        CheckBox targetCheckBox = fingerChecklistMap.get(fingerCount);
        if (targetCheckBox != null) {
            targetCheckBox.setChecked(true);
        }
    }

    private int extractFingerCount(@NonNull HandLandmarkerResult result) {
        if (result.landmarks().isEmpty()) {
            return 0;
        }

        List<NormalizedLandmark> handLandmarks = result.landmarks().get(0);
        if (handLandmarks.size() < 21) {
            return 0;
        }

        boolean thumbExtended = isThumbExtended(handLandmarks);
        boolean indexExtended = isFingerExtended(handLandmarks, 8, 6);
        boolean middleExtended = isFingerExtended(handLandmarks, 12, 10);
        boolean ringExtended = isFingerExtended(handLandmarks, 16, 14);
        boolean pinkyExtended = isFingerExtended(handLandmarks, 20, 18);

        if (indexExtended && !middleExtended && !ringExtended && !pinkyExtended && !thumbExtended) {
            return 1;
        }

        int fingerCount = 0;
        if (thumbExtended) {
            fingerCount++;
        }
        if (indexExtended) {
            fingerCount++;
        }
        if (middleExtended) {
            fingerCount++;
        }
        if (ringExtended) {
            fingerCount++;
        }
        if (pinkyExtended) {
            fingerCount++;
        }

        return fingerCount;
    }

    private boolean isFistGesture(@NonNull HandLandmarkerResult result) {
        if (result.landmarks().isEmpty()) {
            return false;
        }
        List<NormalizedLandmark> handLandmarks = result.landmarks().get(0);
        if (handLandmarks.size() < 21) {
            return false;
        }
        return !isThumbExtended(handLandmarks)
                && !isFingerExtended(handLandmarks, 8, 6)
                && !isFingerExtended(handLandmarks, 12, 10)
                && !isFingerExtended(handLandmarks, 16, 14)
                && !isFingerExtended(handLandmarks, 20, 18);
    }

    private boolean isFingerExtended(List<NormalizedLandmark> landmarks, int tipIndex, int pipIndex) {
        return landmarks.get(tipIndex).y() < landmarks.get(pipIndex).y();
    }

    private boolean isThumbExtended(List<NormalizedLandmark> landmarks) {
        float tipToMcp = distance(landmarks.get(4), landmarks.get(2));
        float ipToMcp = distance(landmarks.get(3), landmarks.get(2));
        float tipToIndexMcp = distance(landmarks.get(4), landmarks.get(5));
        float ipToIndexMcp = distance(landmarks.get(3), landmarks.get(5));

        boolean stretchedFromPalm = tipToMcp > (ipToMcp + 0.03f);
        boolean separatedFromIndex = tipToIndexMcp > (ipToIndexMcp + 0.02f);
        return stretchedFromPalm && separatedFromIndex;
    }

    private float distance(NormalizedLandmark first, NormalizedLandmark second) {
        float dx = first.x() - second.x();
        float dy = first.y() - second.y();
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    private Bitmap rotateBitmap(Bitmap bitmap, int rotationDegrees) {
        if (rotationDegrees == 0) {
            return bitmap;
        }

        Matrix matrix = new Matrix();
        matrix.postRotate(rotationDegrees);
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
    }

    private Bitmap imageProxyToBitmap(ImageProxy imageProxy) {
        ImageProxy.PlaneProxy[] planes = imageProxy.getPlanes();
        if (planes.length < 3) {
            return null;
        }

        byte[] nv21 = yuv420ToNv21(imageProxy);
        if (nv21 == null) {
            return null;
        }

        YuvImage yuvImage = new YuvImage(nv21, ImageFormat.NV21, imageProxy.getWidth(), imageProxy.getHeight(), null);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        yuvImage.compressToJpeg(new Rect(0, 0, imageProxy.getWidth(), imageProxy.getHeight()), 80, out);
        byte[] imageBytes = out.toByteArray();
        return BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.length);
    }

    private byte[] yuv420ToNv21(ImageProxy imageProxy) {
        int width = imageProxy.getWidth();
        int height = imageProxy.getHeight();
        int ySize = width * height;
        int uvSize = width * height / 4;
        byte[] nv21 = new byte[ySize + uvSize * 2];

        ImageProxy.PlaneProxy yPlane = imageProxy.getPlanes()[0];
        ImageProxy.PlaneProxy uPlane = imageProxy.getPlanes()[1];
        ImageProxy.PlaneProxy vPlane = imageProxy.getPlanes()[2];

        copyPlane(yPlane.getBuffer(), width, height, yPlane.getRowStride(), yPlane.getPixelStride(), nv21, 0, 1);
        copyPlane(vPlane.getBuffer(), width / 2, height / 2, vPlane.getRowStride(), vPlane.getPixelStride(), nv21, ySize, 2);
        copyPlane(uPlane.getBuffer(), width / 2, height / 2, uPlane.getRowStride(), uPlane.getPixelStride(), nv21, ySize + 1, 2);

        return nv21;
    }

    private void copyPlane(
            ByteBuffer buffer,
            int width,
            int height,
            int rowStride,
            int pixelStride,
            byte[] out,
            int offset,
            int outPixelStride
    ) {
        byte[] rowData = new byte[rowStride];
        int outputIndex = offset;

        for (int row = 0; row < height; row++) {
            int length;
            if (pixelStride == 1 && outPixelStride == 1) {
                length = width;
                buffer.get(out, outputIndex, length);
                outputIndex += length;
            } else {
                length = (width - 1) * pixelStride + 1;
                buffer.get(rowData, 0, length);
                for (int column = 0; column < width; column++) {
                    out[outputIndex] = rowData[column * pixelStride];
                    outputIndex += outPixelStride;
                }
            }

            if (row < height - 1) {
                buffer.position(buffer.position() + rowStride - length);
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != APP_PERMISSION_REQUEST_CODE) {
            return;
        }

        if (hasCameraPermission()) {
            startCamera();
        } else {
            statusText.setText(R.string.status_need_camera_permission);
        }

        if (hasAudioPermission()) {
            startVoiceListening();
        } else {
            Toast.makeText(this, R.string.status_voice_permission_needed, Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        handleIncomingVoiceIntent(getIntent());
        if (hasAudioPermission()) {
            startVoiceListening();
        }
    }

    @Override
    protected void onPause() {
        if (speechRecognizer != null) {
            speechRecognizer.stopListening();
            isVoiceListening = false;
        }
        voiceHandler.removeCallbacksAndMessages(null);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (handLandmarker != null) {
            handLandmarker.close();
        }
        if (cameraExecutor != null) {
            cameraExecutor.shutdown();
        }
        if (speechRecognizer != null) {
            speechRecognizer.destroy();
            speechRecognizer = null;
        }
        voiceHandler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}