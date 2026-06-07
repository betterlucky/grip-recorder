package com.grippulltester;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentValues;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.speech.tts.TextToSpeech;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import org.vosk.LibVosk;
import org.vosk.LogLevel;
import org.vosk.Model;
import org.vosk.Recognizer;
import org.vosk.android.RecognitionListener;
import org.vosk.android.SpeechService;
import org.vosk.android.StorageService;

import java.io.IOException;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public class MainActivity extends Activity implements RecognitionListener {
    private static final int AUDIO_PERMISSION_REQUEST = 10;
    private static final int DEFAULT_REPS = 10;
    private static final int DEFAULT_PULL_SECONDS = 3;
    private static final int DEFAULT_REST_SECONDS = 5;
    private static final String DEFAULT_HAND = "left";
    private static final String DEFAULT_PROTOCOL = "custom";
    private static final String EXPORT_FOLDER = "GripRecorderData";
    private static final String[] HAND_OPTIONS = {"left", "right"};
    private static final String[] PROTOCOL_OPTIONS = {"check_1", "check_2", "short_evidence_5", "full_protocol_10", "custom"};
    private static final int COUNTDOWN_SECONDS = 3;
    private static final int MIN_SECONDS = 1;
    private static final int MAX_REPS = 100;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ArrayList<RepEntry> entries = new ArrayList<>();
    private final Map<String, Integer> smallNumbers = new HashMap<>();
    private final Map<String, Integer> tensNumbers = new HashMap<>();

    private SharedPreferences prefs;
    private Model voskModel;
    private SpeechService voskService;
    private TextToSpeech textToSpeech;
    private ToneGenerator toneGenerator;

    private TextView phaseText;
    private TextView timerText;
    private TextView repText;
    private TextView latestText;
    private TextView speechText;
    private TextView speechStatusText;
    private TextView valuesTitleText;
    private LinearLayout valuesList;
    private LinearLayout settingsPanel;
    private EditText repsInput;
    private EditText pullInput;
    private EditText restInput;
    private Spinner handSpinner;
    private Spinner protocolSpinner;
    private EditText setNumberInput;
    private EditText restGapInput;
    private Button advancedButton;
    private LinearLayout advancedPanel;
    private Switch showValuesSwitch;
    private Switch voiceSwitch;
    private Switch lowFeedbackSwitch;
    private Button startButton;
    private Button stopButton;
    private Button exportButton;
    private Button micCheckButton;

    private Phase phase = Phase.IDLE;
    private int totalReps = DEFAULT_REPS;
    private int pullSeconds = DEFAULT_PULL_SECONDS;
    private int restSeconds = DEFAULT_REST_SECONDS;
    private String hand = DEFAULT_HAND;
    private String protocolLabel = DEFAULT_PROTOCOL;
    private String setNumber = "1";
    private String restGapMinutes = "";
    private Date sessionStartedAt = null;
    private String sessionId = "";
    private int currentRep = 0;
    private int countdownRemaining = COUNTDOWN_SECONDS;
    private long phaseStartMs = 0L;
    private boolean listening = false;
    private boolean sessionActive = false;
    private boolean sessionComplete = false;
    private boolean advancedVisible = false;
    private boolean lowFeedbackMode = false;
    private boolean voskReady = false;
    private String lastVoiceStatus = "ready";

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!sessionActive) {
                return;
            }

            long elapsedMs = SystemClock.elapsedRealtime() - phaseStartMs;
            long durationMs = getPhaseDurationMs();
            if (elapsedMs >= durationMs) {
                advancePhase();
                return;
            }

            updateTimer(durationMs - elapsedMs);
            handler.postDelayed(this, 80);
        }
    };

    private final Runnable countdownTick = new Runnable() {
        @Override
        public void run() {
            if (!sessionActive || phase != Phase.COUNTDOWN) {
                return;
            }

            if (countdownRemaining <= 0) {
                startPullPhase();
                return;
            }

            timerText.setText(String.valueOf(countdownRemaining));
            phaseText.setText("READY");
            repText.setText("Starting soon");
            speak(String.valueOf(countdownRemaining));
            countdownRemaining--;
            handler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("grip_pull_tester", MODE_PRIVATE);
        seedNumberWords();
        loadSettings();
        toneGenerator = new ToneGenerator(AudioManager.STREAM_MUSIC, 85);
        textToSpeech = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                textToSpeech.setLanguage(Locale.UK);
            }
        });

        requestAudioPermissionIfNeeded();
        buildUi();
        initVoskIfPermitted();
        updateIdleUi();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (voskService != null) {
            voskService.stop();
            voskService.shutdown();
            voskService = null;
        }
        if (textToSpeech != null) {
            textToSpeech.shutdown();
        }
        if (toneGenerator != null) {
            toneGenerator.release();
        }
        super.onDestroy();
    }

    private void buildUi() {
        int background = Color.rgb(248, 247, 242);
        int text = Color.rgb(17, 20, 18);
        int secondary = Color.rgb(89, 99, 93);

        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);
        scrollView.setClipToPadding(false);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(14), dp(18), dp(140));
        root.setBackgroundColor(background);
        scrollView.addView(root, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT
        ));

        TextView title = new TextView(this);
        title.setText("Grip Recorder");
        title.setTextColor(text);
        title.setTextSize(24);
        title.setGravity(Gravity.CENTER_HORIZONTAL);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        root.addView(title, matchWrap());

        repText = makeText("Ready", 18, text, Gravity.CENTER_HORIZONTAL);
        root.addView(repText, topMargin(matchWrap(), 8));

        phaseText = makeText("IDLE", 42, text, Gravity.CENTER_HORIZONTAL);
        phaseText.setTypeface(null, android.graphics.Typeface.BOLD);
        root.addView(phaseText, topMargin(matchWrap(), 10));

        LinearLayout metricsRow = new LinearLayout(this);
        metricsRow.setOrientation(LinearLayout.HORIZONTAL);
        metricsRow.setGravity(Gravity.CENTER_VERTICAL);
        root.addView(metricsRow, topMargin(matchWrap(), 4));

        timerText = makeText("0.0", 64, text, Gravity.CENTER);
        timerText.setTypeface(null, android.graphics.Typeface.BOLD);
        metricsRow.addView(timerText, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.1f));

        latestText = makeText("Latest: -", 22, text, Gravity.CENTER);
        latestText.setTypeface(null, android.graphics.Typeface.BOLD);
        metricsRow.addView(latestText, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        speechStatusText = makeText("Voice: checking", 14, secondary, Gravity.CENTER_HORIZONTAL);
        root.addView(speechStatusText, topMargin(matchWrap(), 4));

        speechText = makeText("", 16, secondary, Gravity.CENTER_HORIZONTAL);
        speechText.setMinHeight(dp(26));
        root.addView(speechText, topMargin(matchWrap(), 2));

        LinearLayout buttonRow = new LinearLayout(this);
        buttonRow.setOrientation(LinearLayout.HORIZONTAL);
        buttonRow.setGravity(Gravity.CENTER);
        root.addView(buttonRow, topMargin(matchWrap(), 16));

        startButton = makeButton("Start");
        startButton.setOnClickListener(v -> getReady());
        buttonRow.addView(startButton, weightedButton());

        stopButton = makeButton("Stop");
        stopButton.setEnabled(false);
        stopButton.setOnClickListener(v -> stopSession(false));
        buttonRow.addView(stopButton, leftMargin(weightedButton(), 10));

        exportButton = makeButton("Export CSV");
        exportButton.setOnClickListener(v -> exportCsv());
        root.addView(exportButton, topMargin(matchWrap(), 10));

        settingsPanel = new LinearLayout(this);
        settingsPanel.setOrientation(LinearLayout.VERTICAL);
        root.addView(settingsPanel, topMargin(matchWrap(), 16));

        TextView settingsTitle = makeText("Settings", 18, text, Gravity.NO_GRAVITY);
        settingsTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        settingsPanel.addView(settingsTitle, matchWrap());

        LinearLayout fields = new LinearLayout(this);
        fields.setOrientation(LinearLayout.HORIZONTAL);
        settingsPanel.addView(fields, topMargin(matchWrap(), 8));

        repsInput = addNumberField(fields, "Reps", totalReps);
        pullInput = addNumberField(fields, "Pull s", pullSeconds);
        restInput = addNumberField(fields, "Rest s", restSeconds);

        LinearLayout metadataFields = new LinearLayout(this);
        metadataFields.setOrientation(LinearLayout.HORIZONTAL);
        settingsPanel.addView(metadataFields, topMargin(matchWrap(), 8));

        handSpinner = addSpinnerField(metadataFields, "Hand", HAND_OPTIONS, hand);
        protocolSpinner = addSpinnerField(metadataFields, "Protocol", PROTOCOL_OPTIONS, protocolLabel);
        protocolSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                applyProtocolPreset(PROTOCOL_OPTIONS[position]);
            }

            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {
            }
        });

        advancedButton = makeButton("Advanced settings");
        advancedButton.setOnClickListener(v -> toggleAdvancedPanel());
        settingsPanel.addView(advancedButton, topMargin(matchWrap(), 8));

        advancedPanel = new LinearLayout(this);
        advancedPanel.setOrientation(LinearLayout.VERTICAL);
        advancedPanel.setVisibility(View.GONE);
        settingsPanel.addView(advancedPanel, topMargin(matchWrap(), 4));

        LinearLayout setFields = new LinearLayout(this);
        setFields.setOrientation(LinearLayout.HORIZONTAL);
        advancedPanel.addView(setFields, matchWrap());

        setNumberInput = addTextField(setFields, "Set #", setNumber, InputType.TYPE_CLASS_NUMBER);
        restGapInput = addTextField(setFields, "Gap min", restGapMinutes, InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);

        showValuesSwitch = new Switch(this);
        showValuesSwitch.setText("Show values list");
        showValuesSwitch.setTextColor(text);
        showValuesSwitch.setTextSize(16);
        showValuesSwitch.setChecked(prefs.getBoolean("show_values", true));
        showValuesSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            prefs.edit().putBoolean("show_values", isChecked).apply();
            valuesList.setVisibility(isChecked ? View.VISIBLE : View.GONE);
        });
        settingsPanel.addView(showValuesSwitch, topMargin(matchWrap(), 10));

        voiceSwitch = new Switch(this);
        voiceSwitch.setText("Use voice capture");
        voiceSwitch.setTextColor(text);
        voiceSwitch.setTextSize(16);
        voiceSwitch.setChecked(prefs.getBoolean("voice_enabled", true));
        voiceSwitch.setOnCheckedChangeListener(this::onVoiceToggle);
        settingsPanel.addView(voiceSwitch, topMargin(matchWrap(), 2));

        lowFeedbackSwitch = new Switch(this);
        lowFeedbackSwitch.setText("Low feedback mode");
        lowFeedbackSwitch.setTextColor(text);
        lowFeedbackSwitch.setTextSize(16);
        lowFeedbackSwitch.setChecked(prefs.getBoolean("low_feedback", false));
        lowFeedbackSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            lowFeedbackMode = isChecked;
            prefs.edit().putBoolean("low_feedback", isChecked).apply();
        });
        settingsPanel.addView(lowFeedbackSwitch, topMargin(matchWrap(), 2));

        micCheckButton = makeButton("Mic Check");
        micCheckButton.setOnClickListener(v -> startMicCheck());
        settingsPanel.addView(micCheckButton, topMargin(matchWrap(), 8));

        valuesTitleText = makeText("Values", 18, text, Gravity.NO_GRAVITY);
        valuesTitleText.setTypeface(null, android.graphics.Typeface.BOLD);
        root.addView(valuesTitleText, topMargin(matchWrap(), 18));

        valuesList = new LinearLayout(this);
        valuesList.setOrientation(LinearLayout.VERTICAL);
        valuesList.setVisibility(showValuesSwitch.isChecked() ? View.VISIBLE : View.GONE);
        root.addView(valuesList, topMargin(matchWrap(), 6));

        setContentView(scrollView);
    }

    private void onVoiceToggle(CompoundButton buttonView, boolean enabled) {
        prefs.edit().putBoolean("voice_enabled", enabled).apply();
        if (!enabled) {
            stopListening();
        } else {
            requestAudioPermissionIfNeeded();
            initVoskIfPermitted();
        }
        updateSpeechStatus();
    }

    private void applyProtocolPreset(String protocol) {
        if (protocol == null || protocol.equals("custom")) {
            return;
        }

        int reps;
        switch (protocol) {
            case "check_1":
                reps = 1;
                break;
            case "check_2":
                reps = 2;
                break;
            case "short_evidence_5":
                reps = 5;
                break;
            case "full_protocol_10":
                reps = 10;
                break;
            default:
                return;
        }

        totalReps = reps;
        pullSeconds = DEFAULT_PULL_SECONDS;
        restSeconds = DEFAULT_REST_SECONDS;
        if (repsInput != null && pullInput != null && restInput != null) {
            repsInput.setText(String.valueOf(totalReps));
            pullInput.setText(String.valueOf(pullSeconds));
            restInput.setText(String.valueOf(restSeconds));
        }
    }

    private void startMicCheck() {
        if (sessionActive) {
            return;
        }
        if (!voiceSwitch.isChecked()) {
            Toast.makeText(this, "Turn on voice capture to run mic check.", Toast.LENGTH_LONG).show();
            return;
        }
        if (voiceSwitch.isChecked() && !hasAudioPermission()) {
            requestAudioPermissionIfNeeded();
            Toast.makeText(this, "Microphone permission is needed for mic check.", Toast.LENGTH_LONG).show();
            return;
        }
        if (voiceSwitch.isChecked() && !ensureVoskService()) {
            Toast.makeText(this, "Voice model is still loading. Try again in a moment.", Toast.LENGTH_LONG).show();
            return;
        }

        phase = Phase.MIC_CHECK;
        phaseText.setText("MIC");
        repText.setText("Say a test value");
        latestText.setText("Latest: -");
        speechText.setText("Listening for a number");
        startListening();
    }

    private void toggleAdvancedPanel() {
        advancedVisible = !advancedVisible;
        advancedPanel.setVisibility(advancedVisible ? View.VISIBLE : View.GONE);
        advancedButton.setText(advancedVisible ? "Hide advanced" : "Advanced settings");
    }

    private void initVoskIfPermitted() {
        if (!hasAudioPermission() || voskModel != null || !voiceSwitch.isChecked()) {
            updateSpeechStatus();
            return;
        }

        LibVosk.setLogLevel(LogLevel.WARNINGS);
        voskReady = false;
        lastVoiceStatus = "loading Vosk";
        updateSpeechStatus();
        StorageService.unpack(this, "model-en-us", "model",
                model -> {
                    voskModel = model;
                    voskReady = true;
                    lastVoiceStatus = "ready";
                    updateSpeechStatus();
                },
                exception -> {
                    voskReady = false;
                    lastVoiceStatus = "Vosk load failed";
                    speechText.setText("Voice: " + exception.getMessage());
                    updateSpeechStatus();
                });
    }

    private boolean ensureVoskService() {
        if (!voiceSwitch.isChecked()) {
            return false;
        }
        if (!voskReady || voskModel == null) {
            lastVoiceStatus = "loading Vosk";
            updateSpeechStatus();
            return false;
        }
        if (voskService != null) {
            return true;
        }

        try {
            Recognizer recognizer = new Recognizer(voskModel, 16000.0f, buildVoskGrammar());
            voskService = new SpeechService(recognizer, 16000.0f);
            voskService.startListening(this);
            voskService.setPause(true);
            listening = false;
            lastVoiceStatus = "ready";
            updateSpeechStatus();
            return true;
        } catch (IOException ex) {
            lastVoiceStatus = "Vosk start failed";
            speechText.setText("Voice: " + ex.getMessage());
            updateSpeechStatus();
            return false;
        }
    }

    private String buildVoskGrammar() {
        ArrayList<String> phrases = new ArrayList<>();
        phrases.add("wrong");
        phrases.add("incorrect");
        phrases.add("mistake");
        phrases.add("a half");
        phrases.add("one half");
        phrases.add("a quarter");
        phrases.add("one quarter");
        phrases.add("three quarters");
        for (int i = 0; i <= 200; i++) {
            String words = numberToWords(i);
            phrases.add(words);
            phrases.add(words + " kilograms");
            phrases.add(words + " kg");
            phrases.add(words + " and a half");
            phrases.add(words + " and one half");
            phrases.add(words + " and a quarter");
            phrases.add(words + " and one quarter");
            phrases.add(words + " and three quarters");
            for (int decimal = 0; decimal <= 9; decimal++) {
                phrases.add(words + " point " + numberToWords(decimal));
            }
        }
        phrases.add("[unk]");

        StringBuilder grammar = new StringBuilder("[");
        for (int i = 0; i < phrases.size(); i++) {
            if (i > 0) {
                grammar.append(',');
            }
            grammar.append('"').append(phrases.get(i)).append('"');
        }
        grammar.append(']');
        return grammar.toString();
    }

    private String numberToWords(int number) {
        if (number < 20) {
            String[] words = {
                    "zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine",
                    "ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen",
                    "seventeen", "eighteen", "nineteen"
            };
            return words[number];
        }
        if (number < 100) {
            String[] tens = {"", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety"};
            int ten = number / 10;
            int one = number % 10;
            return one == 0 ? tens[ten] : tens[ten] + " " + numberToWords(one);
        }
        if (number == 100) {
            return "one hundred";
        }
        if (number < 200) {
            return "one hundred " + numberToWords(number - 100);
        }
        return "two hundred";
    }

    private void getReady() {
        saveSettingsFromInputs();
        if (voiceSwitch.isChecked() && !hasAudioPermission()) {
            requestAudioPermissionIfNeeded();
            Toast.makeText(this, "Microphone permission is needed for voice capture.", Toast.LENGTH_LONG).show();
            return;
        }
        if (voiceSwitch.isChecked() && !ensureVoskService()) {
            Toast.makeText(this, "Voice model is still loading. Try again in a moment.", Toast.LENGTH_LONG).show();
            return;
        }

        entries.clear();
        for (int i = 0; i < totalReps; i++) {
            entries.add(new RepEntry(i + 1));
        }
        currentRep = 0;
        sessionStartedAt = new Date();
        sessionId = buildSessionId(sessionStartedAt);
        sessionActive = true;
        sessionComplete = false;
        startButton.setEnabled(false);
        stopButton.setEnabled(true);
        exportButton.setEnabled(false);
        settingsPanel.setEnabled(false);
        setSettingsEnabled(false);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        renderValuesList();
        applyActiveVisibility();
        phase = Phase.COUNTDOWN;
        phaseText.setText("READY");
        phaseText.setTextColor(Color.rgb(17, 20, 18));
        repText.setText("Starting soon");
        timerText.setText(String.valueOf(COUNTDOWN_SECONDS));
        latestText.setText("Latest: -");
        speechText.setText("Countdown started");
        beginCountdown();
    }

    private void stopSession(boolean completed) {
        sessionActive = false;
        sessionComplete = completed;
        handler.removeCallbacks(tick);
        handler.removeCallbacks(countdownTick);
        stopListening();
        startButton.setEnabled(true);
        stopButton.setEnabled(false);
        exportButton.setEnabled(!entries.isEmpty());
        settingsPanel.setEnabled(true);
        setSettingsEnabled(true);
        restoreIdleVisibility();
        renderValuesList();
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        if (completed) {
            phase = Phase.COMPLETE;
            phaseText.setText("DONE");
            phaseText.setTextColor(Color.rgb(14, 124, 102));
            timerText.setText("0.0");
            repText.setText("Session complete");
            speechText.setText("Complete. Export CSV when ready.");
            speak("Done");
            showCompletePrompt();
        } else {
            phase = Phase.IDLE;
            updateIdleUi();
        }
    }

    private void beginCountdown() {
        if (!sessionActive) {
            return;
        }
        phase = Phase.COUNTDOWN;
        countdownRemaining = COUNTDOWN_SECONDS;
        speechText.setText("Countdown started");
        handler.removeCallbacks(countdownTick);
        handler.post(countdownTick);
    }

    private void applyActiveVisibility() {
        if (!lowFeedbackMode) {
            return;
        }
        startButton.setVisibility(View.GONE);
        exportButton.setVisibility(View.GONE);
        settingsPanel.setVisibility(View.GONE);
        valuesTitleText.setVisibility(View.GONE);
        valuesList.setVisibility(View.GONE);
        speechStatusText.setVisibility(View.GONE);
        speechText.setVisibility(View.GONE);
    }

    private void restoreIdleVisibility() {
        startButton.setVisibility(View.VISIBLE);
        exportButton.setVisibility(View.VISIBLE);
        settingsPanel.setVisibility(View.VISIBLE);
        speechStatusText.setVisibility(View.VISIBLE);
        speechText.setVisibility(View.VISIBLE);
        valuesTitleText.setVisibility(View.VISIBLE);
        valuesList.setVisibility(showValuesSwitch.isChecked() ? View.VISIBLE : View.GONE);
    }

    private void showCompletePrompt() {
        if (isFinishing()) {
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Session complete")
                .setMessage("Export this grip session now?")
                .setPositiveButton("Export CSV", (dialog, which) -> exportCsv())
                .setNegativeButton("Later", null)
                .show();
    }

    private void startPullPhase() {
        phase = Phase.PULL;
        phaseStartMs = SystemClock.elapsedRealtime();
        phaseText.setText("PULL");
        phaseText.setTextColor(Color.rgb(201, 55, 44));
        repText.setText("Rep " + (currentRep + 1) + " of " + totalReps);
        speechText.setText("");
        signalPull();
        updateTimer(getPhaseDurationMs());
        handler.removeCallbacks(tick);
        handler.postDelayed(tick, 80);
    }

    private void startRestPhase() {
        phase = Phase.REST;
        phaseStartMs = SystemClock.elapsedRealtime();
        phaseText.setText("REST");
        phaseText.setTextColor(Color.rgb(36, 92, 168));
        repText.setText("Record rep " + (currentRep + 1));
        speechText.setText("Say the value, or say \"wrong\" to flag it");
        startListening();
        updateTimer(getPhaseDurationMs());
        handler.removeCallbacks(tick);
        handler.postDelayed(tick, 80);
    }

    private void advancePhase() {
        if (phase == Phase.PULL) {
            startRestPhase();
            return;
        }

        if (phase == Phase.REST) {
            markCurrentRepMissedIfNeeded();
            currentRep++;
            if (currentRep >= totalReps) {
                stopSession(true);
            } else {
                startPullPhase();
            }
        }
    }

    private void signalPull() {
        if (toneGenerator != null) {
            toneGenerator.startTone(ToneGenerator.TONE_PROP_BEEP, 180);
        }
        speak("Pull");
    }

    private void speak(String text) {
        if (textToSpeech != null) {
            textToSpeech.speak(text, TextToSpeech.QUEUE_FLUSH, null, "cue-" + SystemClock.elapsedRealtime());
        }
    }

    private void startListening() {
        if (!voiceSwitch.isChecked() || listening || !hasAudioPermission()) {
            if (!hasAudioPermission()) {
                lastVoiceStatus = "microphone permission needed";
            }
            updateSpeechStatus();
            return;
        }
        if (!ensureVoskService()) {
            return;
        }

        voskService.setPause(false);
        listening = true;
        lastVoiceStatus = "listening";
        updateSpeechStatus();
    }

    private void stopListening() {
        if (voskService != null) {
            voskService.setPause(true);
        }
        listening = false;
        if (voiceSwitch != null && voiceSwitch.isChecked() && voskReady) {
            lastVoiceStatus = "ready";
        }
        updateSpeechStatus();
    }

    private void handleSpeechText(String speech, boolean partial) {
        if (speech == null || speech.isEmpty()) {
            return;
        }

        lastVoiceStatus = partial ? "partial result" : "captured";
        speechText.setText(partial ? "Heard: " + speech : "Captured: " + speech);
        if (partial) {
            return;
        }
        if (phase == Phase.MIC_CHECK) {
            handleMicCheckSpeech(speech);
            return;
        }
        if (phase != Phase.REST || currentRep < 0 || currentRep >= entries.size()) {
            return;
        }

        RepEntry entry = entries.get(currentRep);

        if (containsWrong(speech)) {
            entry.flagged = true;
        }

        String numeric = extractNumber(speech);
        if (numeric != null && !numeric.isEmpty()) {
            entry.value = numeric;
            entry.missed = false;
            entry.rawSpeech = speech;
            latestText.setText("Latest: " + numeric + " kg" + (entry.flagged ? "  FLAGGED" : ""));
        } else if (entry.flagged) {
            entry.rawSpeech = speech;
            latestText.setText("Latest: rep " + entry.rep + " flagged");
        }

        renderValuesList();
    }

    private void handleMicCheckSpeech(String speech) {
        String numeric = extractNumber(speech);
        if (numeric != null && !numeric.isEmpty()) {
            latestText.setText("Mic: " + numeric + " kg");
            speechText.setText("Mic check heard: " + numeric);
            stopListening();
            phase = Phase.IDLE;
            updateSpeechStatus();
        }
    }

    private boolean containsWrong(String text) {
        String normalized = " " + text.toLowerCase(Locale.UK).replaceAll("[^a-z0-9. -]", " ") + " ";
        return normalized.contains(" wrong ") || normalized.contains(" incorrect ") || normalized.contains(" mistake ");
    }

    private void markCurrentRepMissedIfNeeded() {
        if (currentRep < 0 || currentRep >= entries.size()) {
            return;
        }
        RepEntry entry = entries.get(currentRep);
        if (entry.value == null || entry.value.trim().isEmpty()) {
            entry.missed = true;
            speechText.setText("Missed rep " + entry.rep);
            renderValuesList();
        }
    }

    private String extractNumber(String text) {
        String normalized = text.toLowerCase(Locale.UK).replace(',', '.');
        java.util.regex.Matcher numeric = java.util.regex.Pattern
                .compile("[-+]?\\d+(?:\\.\\d+)?")
                .matcher(normalized);
        if (numeric.find()) {
            return trimNumber(numeric.group());
        }

        Double fractionalWords = parseFractionalWords(normalized);
        if (fractionalWords != null) {
            return trimNumber(String.format(Locale.UK, "%.2f", fractionalWords));
        }

        Double words = parseNumberWords(normalized);
        if (words != null) {
            return trimNumber(String.format(Locale.UK, "%.2f", words));
        }
        return null;
    }

    private Double parseFractionalWords(String text) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("^(.*?)(?:\\band\\s+)?(?:(a|one|three)\\s+)?(half|quarter|quarters)\\b.*$")
                .matcher(text.replace('-', ' '));
        if (!matcher.matches()) {
            return null;
        }

        String wholePart = matcher.group(1).trim().replaceAll("\\band\\s*$", "").trim();
        String fractionCount = matcher.group(2);
        String fractionUnit = matcher.group(3);
        double fraction;
        if ("half".equals(fractionUnit)) {
            fraction = 0.5;
        } else if ("three".equals(fractionCount)) {
            fraction = 0.75;
        } else {
            fraction = 0.25;
        }

        if (wholePart.isEmpty()) {
            return fraction;
        }
        Double whole = parseNumberWords(wholePart);
        return whole == null ? null : whole + fraction;
    }

    private Double parseNumberWords(String text) {
        String cleaned = text.replace('-', ' ').replaceAll("[^a-z ]", " ");
        String[] tokens = cleaned.trim().split("\\s+");
        if (tokens.length == 0) {
            return null;
        }

        double total = 0;
        double current = 0;
        boolean found = false;
        boolean decimal = false;
        double decimalPlace = 0.1;

        for (String token : tokens) {
            if (token.isEmpty() || token.equals("and") || token.equals("kg") || token.equals("kilogram") || token.equals("kilograms")) {
                continue;
            }
            if (token.equals("point") || token.equals("dot")) {
                decimal = true;
                found = true;
                continue;
            }

            Integer digit = smallNumbers.get(token);
            Integer ten = tensNumbers.get(token);
            if (decimal) {
                Integer decimalDigit = digit;
                if (decimalDigit != null && decimalDigit >= 0 && decimalDigit <= 9) {
                    total += decimalDigit * decimalPlace;
                    decimalPlace /= 10;
                    found = true;
                }
                continue;
            }

            if (digit != null) {
                current += digit;
                found = true;
            } else if (ten != null) {
                current += ten;
                found = true;
            } else if (token.equals("hundred")) {
                current = Math.max(1, current) * 100;
                found = true;
            }
        }

        if (!found) {
            return null;
        }
        return total + current;
    }

    private String trimNumber(String value) {
        if (value == null) {
            return "";
        }
        if (value.contains(".")) {
            value = value.replaceAll("0+$", "").replaceAll("\\.$", "");
        }
        return value;
    }

    private void renderValuesList() {
        valuesList.removeAllViews();
        int text = Color.rgb(17, 20, 18);
        int secondary = Color.rgb(89, 99, 93);

        for (RepEntry entry : entries) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(7), 0, dp(7));

            TextView repLabel = makeText("Rep " + entry.rep, 15, secondary, Gravity.NO_GRAVITY);
            row.addView(repLabel, fixedWidth(72));

            EditText value = new EditText(this);
            value.setText(entry.value);
            value.setHint(entry.missed ? "missed" : "value");
            value.setSingleLine(true);
            value.setTextSize(17);
            value.setTextColor(text);
            value.setHintTextColor(Color.rgb(135, 142, 137));
            value.setPadding(dp(10), 0, dp(10), 0);
            value.setBackground(fieldBackground(entry.missed ? Color.rgb(255, 240, 238) : Color.WHITE, entry.missed ? Color.rgb(201, 55, 44) : Color.rgb(194, 199, 195)));
            value.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL | InputType.TYPE_NUMBER_FLAG_SIGNED);
            value.setSelectAllOnFocus(false);
            value.setFocusable(!sessionActive);
            value.setFocusableInTouchMode(!sessionActive);
            value.setCursorVisible(!sessionActive);
            value.setOnFocusChangeListener((v, hasFocus) -> {
                if (!hasFocus) {
                    entry.value = ((EditText) v).getText().toString().trim();
                    updateLatestFromEntries();
                }
            });
            value.addTextChangedListener(new TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence s, int start, int count, int after) {
                }

                @Override
                public void onTextChanged(CharSequence s, int start, int before, int count) {
                    entry.value = s.toString().trim();
                    if (!entry.value.isEmpty()) {
                        entry.missed = false;
                    }
                    if (!sessionActive) {
                        updateLatestFromEntries();
                    }
                }

                @Override
                public void afterTextChanged(Editable s) {
                }
            });
            row.addView(value, weightedButton());

            Button flag = makeButton(entry.flagged ? "Flagged" : "Flag");
            flag.setTextSize(14);
            flag.setEnabled(!sessionActive);
            flag.setOnClickListener(v -> {
                entry.flagged = !entry.flagged;
                updateLatestFromEntries();
                renderValuesList();
            });
            row.addView(flag, leftMargin(fixedWidth(96), 8));

            valuesList.addView(row, matchWrap());
        }
    }

    private void updateLatestFromEntries() {
        RepEntry latest = null;
        for (RepEntry entry : entries) {
            if ((entry.value != null && !entry.value.isEmpty()) || entry.flagged) {
                latest = entry;
            }
        }
        if (latest == null) {
            latestText.setText("Latest: -");
        } else if (latest.value != null && !latest.value.isEmpty()) {
            latestText.setText("Latest: " + latest.value + " kg" + (latest.flagged ? "  FLAGGED" : ""));
        } else {
            latestText.setText("Latest: rep " + latest.rep + " flagged");
        }
    }

    private void exportCsv() {
        if (entries.isEmpty()) {
            Toast.makeText(this, "No session values to export yet.", Toast.LENGTH_SHORT).show();
            return;
        }

        Date exportStartedAt = sessionStartedAt == null ? new Date() : sessionStartedAt;
        String exportSessionId = sessionId == null || sessionId.isEmpty() ? buildSessionId(exportStartedAt) : sessionId;
        String startedAt = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.UK).format(exportStartedAt);

        StringBuilder csv = new StringBuilder();
        csv.append("session_id,started_at,hand,protocol_label,pull_seconds,rest_seconds,rep_count,set_number,rest_gap_minutes,rep_index,value_kg\n");
        for (RepEntry entry : entries) {
            csv.append(csvEscape(exportSessionId)).append(',')
                    .append(csvEscape(startedAt)).append(',')
                    .append(csvEscape(hand)).append(',')
                    .append(csvEscape(protocolLabel)).append(',')
                    .append(pullSeconds).append(',')
                    .append(restSeconds).append(',')
                    .append(totalReps).append(',')
                    .append(csvEscape(setNumber)).append(',')
                    .append(csvEscape(restGapMinutes)).append(',')
                    .append(entry.rep).append(',')
                    .append(csvEscape(entry.value)).append('\n');
        }

        String fileName = buildExportFileName(exportStartedAt);
        try {
            writeCsvToDownloads(fileName, csv.toString());
            speechText.setText("Saved: Downloads/" + EXPORT_FOLDER + "/" + fileName);
            Toast.makeText(this, "Exported to Downloads/" + EXPORT_FOLDER + "/" + fileName, Toast.LENGTH_LONG).show();
        } catch (IOException ex) {
            speechText.setText("Export failed: " + ex.getMessage());
            Toast.makeText(this, "Export failed: " + ex.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void writeCsvToDownloads(String fileName, String csv) throws IOException {
        ContentValues values = new ContentValues();
        values.put(MediaStore.Downloads.DISPLAY_NAME, fileName);
        values.put(MediaStore.Downloads.MIME_TYPE, "text/csv");
        values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + EXPORT_FOLDER);
        values.put(MediaStore.Downloads.IS_PENDING, 1);

        android.net.Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        if (uri == null) {
            throw new IOException("Could not create export file");
        }

        try (OutputStream outputStream = getContentResolver().openOutputStream(uri)) {
            if (outputStream == null) {
                throw new IOException("Could not open export file");
            }
            outputStream.write(csv.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }

        ContentValues completed = new ContentValues();
        completed.put(MediaStore.Downloads.IS_PENDING, 0);
        getContentResolver().update(uri, completed, null, null);
    }

    private String buildExportFileName(Date startedAt) {
        String timestamp = new SimpleDateFormat("yyyy-MM-dd'T'HH-mm-ss", Locale.UK).format(startedAt);
        return "grip_session_" + timestamp + "_" + sanitizeFilePart(hand) + "_" + sanitizeFilePart(protocolLabel) + ".csv";
    }

    private String sanitizeFilePart(String value) {
        if (value == null || value.trim().isEmpty()) {
            return "unset";
        }
        return value.trim()
                .toLowerCase(Locale.UK)
                .replaceAll("[^a-z0-9]+", "_")
                .replaceAll("^_+", "")
                .replaceAll("_+$", "");
    }

    private String buildSessionId(Date startedAt) {
        String timestamp = new SimpleDateFormat("yyyy-MM-dd'T'HH-mm-ss", Locale.UK).format(startedAt);
        return timestamp + "-" + sanitizeIdPart(hand) + "-" + sanitizeIdPart(protocolLabel);
    }

    private String sanitizeIdPart(String value) {
        if (value == null || value.trim().isEmpty()) {
            return "unset";
        }
        return value.trim()
                .toLowerCase(Locale.UK)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+", "")
                .replaceAll("-+$", "");
    }

    private String csvEscape(String value) {
        if (value == null) {
            return "";
        }
        boolean needsQuotes = value.contains(",") || value.contains("\"") || value.contains("\n");
        String escaped = value.replace("\"", "\"\"");
        return needsQuotes ? "\"" + escaped + "\"" : escaped;
    }

    private void saveSettingsFromInputs() {
        totalReps = clamp(parseInt(repsInput.getText().toString(), DEFAULT_REPS), 1, MAX_REPS);
        pullSeconds = clamp(parseInt(pullInput.getText().toString(), DEFAULT_PULL_SECONDS), MIN_SECONDS, 60);
        restSeconds = clamp(parseInt(restInput.getText().toString(), DEFAULT_REST_SECONDS), MIN_SECONDS, 60);
        hand = handSpinner.getSelectedItem().toString();
        protocolLabel = protocolSpinner.getSelectedItem().toString();
        setNumber = cleanText(setNumberInput.getText().toString(), "1");
        restGapMinutes = restGapInput.getText().toString().trim();

        repsInput.setText(String.valueOf(totalReps));
        pullInput.setText(String.valueOf(pullSeconds));
        restInput.setText(String.valueOf(restSeconds));
        handSpinner.setSelection(indexOf(HAND_OPTIONS, hand, 0));
        protocolSpinner.setSelection(indexOf(PROTOCOL_OPTIONS, protocolLabel, PROTOCOL_OPTIONS.length - 1));
        setNumberInput.setText(setNumber);
        restGapInput.setText(restGapMinutes);

        prefs.edit()
                .putInt("reps", totalReps)
                .putInt("pull_seconds", pullSeconds)
                .putInt("rest_seconds", restSeconds)
                .putString("hand", hand)
                .putString("protocol_label", protocolLabel)
                .putString("set_number", setNumber)
                .putString("rest_gap_minutes", restGapMinutes)
                .apply();
    }

    private void loadSettings() {
        totalReps = prefs.getInt("reps", DEFAULT_REPS);
        pullSeconds = prefs.getInt("pull_seconds", DEFAULT_PULL_SECONDS);
        restSeconds = prefs.getInt("rest_seconds", DEFAULT_REST_SECONDS);
        hand = normalizeOption(prefs.getString("hand", DEFAULT_HAND), HAND_OPTIONS, DEFAULT_HAND);
        protocolLabel = normalizeOption(prefs.getString("protocol_label", DEFAULT_PROTOCOL), PROTOCOL_OPTIONS, DEFAULT_PROTOCOL);
        setNumber = prefs.getString("set_number", "1");
        restGapMinutes = prefs.getString("rest_gap_minutes", "");
        lowFeedbackMode = prefs.getBoolean("low_feedback", false);
    }

    private void setSettingsEnabled(boolean enabled) {
        repsInput.setEnabled(enabled);
        pullInput.setEnabled(enabled);
        restInput.setEnabled(enabled);
        handSpinner.setEnabled(enabled);
        protocolSpinner.setEnabled(enabled);
        advancedButton.setEnabled(enabled);
        setNumberInput.setEnabled(enabled);
        restGapInput.setEnabled(enabled);
        showValuesSwitch.setEnabled(enabled);
        voiceSwitch.setEnabled(enabled);
        lowFeedbackSwitch.setEnabled(enabled);
        micCheckButton.setEnabled(enabled);
    }

    private void updateIdleUi() {
        phase = Phase.IDLE;
        phaseText.setText("IDLE");
        phaseText.setTextColor(Color.rgb(17, 20, 18));
        repText.setText("Ready");
        timerText.setText("0.0");
        latestText.setText("Latest: -");
        speechText.setText("");
        updateSpeechStatus();
    }

    private void updateTimer(long remainingMs) {
        double seconds = Math.max(0, remainingMs) / 1000.0;
        timerText.setText(String.format(Locale.UK, "%.1f", seconds));
    }

    private long getPhaseDurationMs() {
        return (phase == Phase.PULL ? pullSeconds : restSeconds) * 1000L;
    }

    private void updateSpeechStatus() {
        if (speechStatusText == null) {
            return;
        }
        if (!voiceSwitch.isChecked()) {
            speechStatusText.setText("Voice: off");
        } else if (!hasAudioPermission()) {
            speechStatusText.setText("Voice: microphone permission needed");
        } else if (!voskReady) {
            speechStatusText.setText("Voice: " + lastVoiceStatus);
        } else if (listening) {
            speechStatusText.setText("Voice: " + lastVoiceStatus);
        } else {
            speechStatusText.setText("Voice: " + lastVoiceStatus);
        }
    }

    private void requestAudioPermissionIfNeeded() {
        if (!hasAudioPermission()) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, AUDIO_PERMISSION_REQUEST);
        } else {
            updateSpeechStatus();
        }
    }

    private boolean hasAudioPermission() {
        return checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onPartialResult(String hypothesis) {
        String text = extractJsonText(hypothesis, "partial");
        if (text != null && !text.isEmpty()) {
            handleSpeechText(text, true);
        }
    }

    @Override
    public void onResult(String hypothesis) {
        String text = extractJsonText(hypothesis, "text");
        if (text != null && !text.isEmpty()) {
            handleSpeechText(text, false);
        }
    }

    @Override
    public void onFinalResult(String hypothesis) {
        String text = extractJsonText(hypothesis, "text");
        if (text != null && !text.isEmpty()) {
            handleSpeechText(text, false);
        }
        listening = false;
        lastVoiceStatus = "ready";
        updateSpeechStatus();
    }

    @Override
    public void onError(Exception exception) {
        listening = false;
        lastVoiceStatus = "Vosk error";
        speechText.setText("Voice: " + exception.getMessage());
        updateSpeechStatus();
    }

    @Override
    public void onTimeout() {
        listening = false;
        lastVoiceStatus = "voice timeout";
        updateSpeechStatus();
    }

    private String extractJsonText(String json, String key) {
        if (json == null) {
            return "";
        }
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("\"" + key + "\"\\s*:\\s*\"([^\"]*)\"")
                .matcher(json);
        if (!matcher.find()) {
            return "";
        }
        return matcher.group(1).replace("\\\"", "\"").trim();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == AUDIO_PERMISSION_REQUEST) {
            initVoskIfPermitted();
            updateSpeechStatus();
        }
    }

    private EditText addNumberField(LinearLayout parent, String label, int value) {
        return addTextField(parent, label, String.valueOf(value), InputType.TYPE_CLASS_NUMBER);
    }

    private Spinner addSpinnerField(LinearLayout parent, String label, String[] options, String selectedValue) {
        LinearLayout field = new LinearLayout(this);
        field.setOrientation(LinearLayout.VERTICAL);

        TextView labelView = makeText(label, 13, Color.rgb(89, 99, 93), Gravity.NO_GRAVITY);
        field.addView(labelView, matchWrap());

        Spinner spinner = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, options);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        spinner.setSelection(indexOf(options, selectedValue, 0));
        field.addView(spinner, matchWrap());

        parent.addView(field, leftMargin(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f), parent.getChildCount() == 0 ? 0 : 8));
        return spinner;
    }

    private EditText addTextField(LinearLayout parent, String label, String value, int inputType) {
        LinearLayout field = new LinearLayout(this);
        field.setOrientation(LinearLayout.VERTICAL);

        TextView labelView = makeText(label, 13, Color.rgb(89, 99, 93), Gravity.NO_GRAVITY);
        field.addView(labelView, matchWrap());

        EditText input = new EditText(this);
        input.setText(value);
        input.setSingleLine(true);
        input.setTextSize(18);
        input.setInputType(inputType);
        field.addView(input, matchWrap());

        parent.addView(field, leftMargin(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f), parent.getChildCount() == 0 ? 0 : 8));
        return input;
    }

    private TextView makeText(String value, int sizeSp, int color, int gravity) {
        TextView textView = new TextView(this);
        textView.setText(value);
        textView.setTextSize(sizeSp);
        textView.setTextColor(color);
        textView.setGravity(gravity);
        textView.setIncludeFontPadding(true);
        return textView;
    }

    private Button makeButton(String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(16);
        button.setMinHeight(dp(48));
        return button;
    }

    private GradientDrawable fieldBackground(int fillColor, int strokeColor) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fillColor);
        drawable.setStroke(dp(1), strokeColor);
        drawable.setCornerRadius(dp(6));
        return drawable;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
    }

    private LinearLayout.LayoutParams weightedButton() {
        return new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
    }

    private LinearLayout.LayoutParams topMargin(LinearLayout.LayoutParams params, int dp) {
        params.topMargin = dp(dp);
        return params;
    }

    private LinearLayout.LayoutParams leftMargin(LinearLayout.LayoutParams params, int dp) {
        params.leftMargin = dp(dp);
        return params;
    }

    private LinearLayout.LayoutParams fixedWidth(int widthDp) {
        return new LinearLayout.LayoutParams(dp(widthDp), LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private int indexOf(String[] options, String value, int fallbackIndex) {
        for (int i = 0; i < options.length; i++) {
            if (options[i].equals(value)) {
                return i;
            }
        }
        return fallbackIndex;
    }

    private String normalizeOption(String value, String[] options, String fallback) {
        for (String option : options) {
            if (option.equals(value)) {
                return option;
            }
        }
        return fallback;
    }

    private String cleanText(String value, String fallback) {
        if (value == null || value.trim().isEmpty()) {
            return fallback;
        }
        return value.trim();
    }

    private void seedNumberWords() {
        smallNumbers.put("zero", 0);
        smallNumbers.put("oh", 0);
        smallNumbers.put("o", 0);
        smallNumbers.put("one", 1);
        smallNumbers.put("two", 2);
        smallNumbers.put("to", 2);
        smallNumbers.put("too", 2);
        smallNumbers.put("three", 3);
        smallNumbers.put("four", 4);
        smallNumbers.put("for", 4);
        smallNumbers.put("five", 5);
        smallNumbers.put("six", 6);
        smallNumbers.put("seven", 7);
        smallNumbers.put("eight", 8);
        smallNumbers.put("ate", 8);
        smallNumbers.put("nine", 9);
        smallNumbers.put("ten", 10);
        smallNumbers.put("eleven", 11);
        smallNumbers.put("twelve", 12);
        smallNumbers.put("thirteen", 13);
        smallNumbers.put("fourteen", 14);
        smallNumbers.put("fifteen", 15);
        smallNumbers.put("sixteen", 16);
        smallNumbers.put("seventeen", 17);
        smallNumbers.put("eighteen", 18);
        smallNumbers.put("nineteen", 19);

        tensNumbers.put("twenty", 20);
        tensNumbers.put("thirty", 30);
        tensNumbers.put("forty", 40);
        tensNumbers.put("fourty", 40);
        tensNumbers.put("fifty", 50);
        tensNumbers.put("sixty", 60);
        tensNumbers.put("seventy", 70);
        tensNumbers.put("eighty", 80);
        tensNumbers.put("ninety", 90);
    }

    private enum Phase {
        IDLE,
        COUNTDOWN,
        PULL,
        REST,
        MIC_CHECK,
        COMPLETE
    }

    private static class RepEntry {
        final int rep;
        String value = "";
        String rawSpeech = "";
        boolean flagged = false;
        boolean missed = false;

        RepEntry(int rep) {
            this.rep = rep;
        }
    }
}
