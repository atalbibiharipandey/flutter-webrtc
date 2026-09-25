package com.cloudwebrtc.webrtc.audio;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import io.flutter.plugin.common.BinaryMessenger;
import io.flutter.plugin.common.BasicMessageChannel;
import io.flutter.plugin.common.StandardMessageCodec;
import io.flutter.plugin.common.StringCodec;
import org.webrtc.audio.JavaAudioDeviceModule;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import java.io.File;
import java.io.FileInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

public class AiAgentAudioProcessor {
    private static final String TAG = "AiAgentAudioProcessor";
    private final BinaryMessenger messenger;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final List<byte[]> ttsQueue = new ArrayList<>();
    private final BasicMessageChannel<Object> speakerConfigChannel;
    private final BasicMessageChannel<Object> speakerAudioChannel;
    private final BasicMessageChannel<String> dtmfChannel;
    private final DtmfDetector dtmfDetector;
    private volatile boolean isEnabled = false;
    private volatile int currentSampleRate = 16000;
    private volatile int currentChannels = 1;
    private volatile int lastSourceSampleRate = 16000;
    private volatile int lastSourceChannels = 1;
    private volatile int lastSentRenderSampleRate = 0;
    private volatile int lastSentRenderChannels = 0;
    private long lastProcessLogTime = 0;
    private volatile long lastTtsWriteTime = 0;
    private int renderDebugCounter = 0;

    public boolean isAiSpeaking() {
        if (!isEnabled) return false;
        synchronized (ttsQueue) {
            if (!ttsQueue.isEmpty()) return true;
        }
        return (System.currentTimeMillis() - lastTtsWriteTime) < 2000;
    }

    public final AudioProcessingAdapter.ExternalAudioFrameProcessing captureProcessor = 
        new AudioProcessingAdapter.ExternalAudioFrameProcessing() {
            @Override
            public void initialize(int sampleRateHz, int numChannels) {
                Log.d(TAG, "captureProcessor initialize: sampleRate = " + sampleRateHz + ", channels = " + numChannels);
                currentSampleRate = sampleRateHz;
                currentChannels = numChannels;
            }

            @Override
            public void reset(int newRate) {
                Log.d(TAG, "captureProcessor reset: newRate = " + newRate);
                currentSampleRate = newRate;
            }

            @Override
            public void process(int numBands, int numFrames, ByteBuffer buffer) {
                if (!isEnabled) return;
                
                int size = buffer.remaining();
                if (numFrames > 0) {
                    int totalSamples = size / 4; // 32-bit float samples (4 bytes each)
                    int detectedChannels = totalSamples / numFrames;
                    int detectedSampleRate = numFrames * 100;
                    
                    if (detectedSampleRate != currentSampleRate || detectedChannels != currentChannels) {
                        Log.d(TAG, "captureProcessor dynamic format detected: sampleRate = " + detectedSampleRate + ", channels = " + detectedChannels);
                        currentSampleRate = detectedSampleRate;
                        currentChannels = detectedChannels;
                    }
                }

                int numFloats = size / 4;
                int pcmBytesNeeded = numFloats * 2; // 16-bit PCM (2 bytes per sample)
                byte[] pcmChunk = new byte[pcmBytesNeeded];
                int written = 0;
                int queueSizeBefore = 0;

                synchronized (ttsQueue) {
                    queueSizeBefore = ttsQueue.size();
                    while (written < pcmBytesNeeded && !ttsQueue.isEmpty()) {
                        byte[] chunk = ttsQueue.get(0);
                        int toCopy = Math.min(chunk.length, pcmBytesNeeded - written);
                        System.arraycopy(chunk, 0, pcmChunk, written, toCopy);
                        written += toCopy;
                        if (toCopy == chunk.length) {
                            ttsQueue.remove(0);
                        } else {
                            byte[] remaining = new byte[chunk.length - toCopy];
                            System.arraycopy(chunk, toCopy, remaining, 0, remaining.length);
                            ttsQueue.set(0, remaining);
                        }
                    }
                }

                if (written > 0) {
                    lastTtsWriteTime = System.currentTimeMillis();
                }

                long now = System.currentTimeMillis();
                if (now - lastProcessLogTime > 1000) {
                    lastProcessLogTime = now;
                    Log.d(TAG, "captureProcessor process: size = " + size + ", written = " + written + ", ttsQueueSize = " + queueSizeBefore);
                }

                // Fill WebRTC audio buffer with 32-bit float samples in native byte order
                buffer.clear();
                buffer.order(ByteOrder.nativeOrder());
                for (int i = 0; i < numFloats; i++) {
                    int byteIdx = i * 2;
                    if (byteIdx + 1 < written) {
                        short sample = (short) ((pcmChunk[byteIdx] & 0xFF) | ((pcmChunk[byteIdx + 1] & 0xFF) << 8));
                        buffer.putFloat((float) sample);
                    } else {
                        buffer.putFloat(0.0f); // Mute / Silence
                    }
                }
                buffer.flip();
            }
        };

    public final AudioProcessingAdapter.ExternalAudioFrameProcessing renderProcessor = 
        new AudioProcessingAdapter.ExternalAudioFrameProcessing() {
            @Override
            public void initialize(int sampleRateHz, int numChannels) {
                Log.d(TAG, "renderProcessor initialize: sampleRate = " + sampleRateHz + ", channels = " + numChannels);
                
                // Dispatch format config to Dart on the UI thread using a direct ByteBuffer
                mainHandler.post(() -> {
                    try {
                        ByteBuffer configBuffer = ByteBuffer.allocateDirect(8).order(ByteOrder.nativeOrder());
                        configBuffer.putInt(sampleRateHz);
                        configBuffer.putInt(numChannels);
                        configBuffer.flip();
                        messenger.send("FlutterWebRTC/SpeakerConfig", configBuffer);
                    } catch (Exception e) {
                        Log.e(TAG, "Error sending SpeakerConfig to Dart", e);
                    }
                });
            }

            @Override
            public void reset(int newRate) {}

            @Override
            public void process(int numBands, int numFrames, ByteBuffer buffer) {
                if (!isEnabled) return;

                int size = buffer.remaining();
                renderDebugCounter++;
                if (renderDebugCounter % 100 == 0) {
                    Log.d(TAG, "renderProcessor process #" + renderDebugCounter + ": size = " + size + ", numFrames = " + numFrames + ", isAiSpeaking = " + isAiSpeaking());
                }

                int totalFloats = size / 4;
                int detectedSampleRate = (numFrames > 0) ? (numFrames * 100) : currentSampleRate;

                // 1. Extract incoming 32-bit float samples from the remote client for DTMF detection
                float[] floatSamples = new float[totalFloats];
                int pos = buffer.position();
                ByteOrder oldOrder = buffer.order();
                buffer.order(ByteOrder.nativeOrder());
                for (int i = 0; i < totalFloats; i++) {
                    floatSamples[i] = buffer.getFloat();
                }
                buffer.position(pos); // restore position
                buffer.order(oldOrder);

                // 2. Real-time Goertzel DTMF detection on remote client's incoming audio
                dtmfDetector.processSamples(floatSamples, totalFloats, detectedSampleRate);

                if (isAiSpeaking()) {
                    // Mute physical speaker during broadcast playback so caller hears nothing
                    buffer.clear();
                    buffer.order(ByteOrder.nativeOrder());
                    for (int i = 0; i < totalFloats; i++) {
                        buffer.putFloat(0.0f);
                    }
                    buffer.flip();
                    return;
                }

                if (numFrames > 0) {
                    int detectedChannels = totalFloats / numFrames;
                    
                    if (detectedSampleRate != lastSentRenderSampleRate || detectedChannels != lastSentRenderChannels) {
                        Log.d(TAG, "renderProcessor dynamic format detected: sampleRate = " + detectedSampleRate + ", channels = " + detectedChannels);
                        lastSentRenderSampleRate = detectedSampleRate;
                        lastSentRenderChannels = detectedChannels;
                        
                        mainHandler.post(() -> {
                            try {
                                List<Integer> config = new ArrayList<>();
                                config.add(detectedSampleRate);
                                config.add(detectedChannels);
                                speakerConfigChannel.send(config);
                            } catch (Exception e) {
                                Log.e(TAG, "Error sending SpeakerConfig to Dart", e);
                            }
                        });
                    }
                }

                // Convert 32-bit float samples to 16-bit linear PCM (shorts) for STT
                byte[] pcm16 = new byte[totalFloats * 2];
                for (int i = 0; i < totalFloats; i++) {
                    float val = floatSamples[i];
                    if (val > 32767.0f) val = 32767.0f;
                    else if (val < -32768.0f) val = -32768.0f;
                    
                    short shortVal = (short) val;
                    pcm16[i * 2] = (byte) (shortVal & 0xFF);
                    pcm16[i * 2 + 1] = (byte) ((shortVal >> 8) & 0xFF);
                }

                // Dispatch speaker PCM bytes to Dart on the UI thread using BasicMessageChannel
                mainHandler.post(() -> {
                    try {
                        if (renderDebugCounter % 100 == 0) {
                            Log.d(TAG, "Sending SpeakerAudio byte array (16-bit): length = " + pcm16.length);
                        }
                        speakerAudioChannel.send(pcm16);
                    } catch (Exception e) {
                        Log.e(TAG, "Error sending SpeakerAudio to Dart", e);
                    }
                });

                // Write the audio back to the buffer
                buffer.clear();
                buffer.order(ByteOrder.nativeOrder());
                for (int i = 0; i < totalFloats; i++) {
                    buffer.putFloat(floatSamples[i]);
                }
                buffer.flip();
            }
        };

    public AiAgentAudioProcessor(BinaryMessenger messenger) {
        this.messenger = messenger;
        this.speakerConfigChannel = new BasicMessageChannel<>(messenger, "FlutterWebRTC/SpeakerConfig", StandardMessageCodec.INSTANCE);
        this.speakerAudioChannel = new BasicMessageChannel<>(messenger, "FlutterWebRTC/SpeakerAudio", StandardMessageCodec.INSTANCE);
        this.dtmfChannel = new BasicMessageChannel<>(messenger, "FlutterWebRTC/DtmfReceived", StringCodec.INSTANCE);
        this.dtmfDetector = new DtmfDetector((key) -> {
            Log.d(TAG, "Dispatching DTMF to Flutter: " + key);
            mainHandler.post(() -> dtmfChannel.send(String.valueOf(key)));
        });
        setupMessenger();
    }

    private void setupMessenger() {
        messenger.setMessageHandler("FlutterWebRTC/TtsAudio", (message, reply) -> {
            if (message != null) {
                byte[] data = new byte[message.remaining()];
                message.get(data);
                handleIncomingWav(data);
            }
            reply.reply(null);
        });

        messenger.setMessageHandler("FlutterWebRTC/SetTtsFormat", (message, reply) -> {
            if (message != null) {
                try {
                    message.order(ByteOrder.nativeOrder());
                    lastSourceSampleRate = message.getInt();
                    lastSourceChannels = message.getInt();
                    Log.d(TAG, "Set TTS Source Format: sampleRate = " + lastSourceSampleRate + ", channels = " + lastSourceChannels);
                } catch (Exception e) {
                    Log.e(TAG, "Error setting TTS source format", e);
                }
            }
            reply.reply(null);
        });
    }

    public void playAudioFile(String filePath) {
        Log.d(TAG, "playAudioFile requested: " + filePath);
        new Thread(() -> {
            try {
                File file = new File(filePath);
                if (!file.exists()) {
                    Log.e(TAG, "playAudioFile: file does not exist: " + filePath);
                    return;
                }

                synchronized (ttsQueue) {
                    ttsQueue.clear();
                }

                String lower = filePath.toLowerCase();
                if (lower.endsWith(".wav")) {
                    byte[] bytes = new byte[(int) file.length()];
                    try (FileInputStream fis = new FileInputStream(file)) {
                        int read = 0;
                        while (read < bytes.length) {
                            int r = fis.read(bytes, read, bytes.length - read);
                            if (r == -1) break;
                            read += r;
                        }
                    }
                    handleIncomingWav(bytes);
                } else {
                    decodeAndEnqueueAudioFile(filePath);
                }
            } catch (Exception e) {
                Log.e(TAG, "Error playing audio file: " + filePath, e);
            }
        }).start();
    }

    private void decodeAndEnqueueAudioFile(String filePath) {
        MediaExtractor extractor = new MediaExtractor();
        MediaCodec codec = null;
        try {
            extractor.setDataSource(filePath);
            int trackIndex = -1;
            MediaFormat format = null;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat mf = extractor.getTrackFormat(i);
                String mime = mf.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    trackIndex = i;
                    format = mf;
                    break;
                }
            }
            if (trackIndex == -1 || format == null) {
                Log.e(TAG, "No audio track found in file: " + filePath);
                return;
            }

            extractor.selectTrack(trackIndex);
            String mime = format.getString(MediaFormat.KEY_MIME);
            int sampleRate = format.containsKey(MediaFormat.KEY_SAMPLE_RATE) ? format.getInteger(MediaFormat.KEY_SAMPLE_RATE) : 44100;
            int channelCount = format.containsKey(MediaFormat.KEY_CHANNEL_COUNT) ? format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : 1;
            Log.d(TAG, "Decoding audio: mime=" + mime + ", sampleRate=" + sampleRate + ", channels=" + channelCount);

            codec = MediaCodec.createDecoderByType(mime);
            codec.configure(format, null, null, 0);
            codec.start();

            MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
            boolean isEOS = false;
            ByteArrayOutputStream pcmOutStream = new ByteArrayOutputStream();
            long timeoutUs = 5000;

            while (true) {
                if (!isEOS) {
                    int inIndex = codec.dequeueInputBuffer(timeoutUs);
                    if (inIndex >= 0) {
                        ByteBuffer inBuffer = codec.getInputBuffer(inIndex);
                        if (inBuffer != null) {
                            int sampleSize = extractor.readSampleData(inBuffer, 0);
                            if (sampleSize < 0) {
                                codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                                isEOS = true;
                            } else {
                                codec.queueInputBuffer(inIndex, 0, sampleSize, extractor.getSampleTime(), 0);
                                extractor.advance();
                            }
                        }
                    }
                }

                int outIndex = codec.dequeueOutputBuffer(bufferInfo, timeoutUs);
                if (outIndex >= 0) {
                    ByteBuffer outBuffer = codec.getOutputBuffer(outIndex);
                    if (outBuffer != null && bufferInfo.size > 0) {
                        byte[] chunk = new byte[bufferInfo.size];
                        outBuffer.position(bufferInfo.offset);
                        outBuffer.limit(bufferInfo.offset + bufferInfo.size);
                        outBuffer.get(chunk);
                        pcmOutStream.write(chunk);
                    }
                    codec.releaseOutputBuffer(outIndex, false);
                    if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        break;
                    }
                } else if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat newFormat = codec.getOutputFormat();
                    if (newFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                        sampleRate = newFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                    }
                    if (newFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                        channelCount = newFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                    }
                } else if (outIndex == MediaCodec.INFO_TRY_AGAIN_LATER && isEOS) {
                    break;
                }
            }

            byte[] decodedPcm = pcmOutStream.toByteArray();
            Log.d(TAG, "Decoded PCM total bytes: " + decodedPcm.length + " at " + sampleRate + "Hz, " + channelCount + "ch");

            byte[] resampled = resample(decodedPcm, sampleRate, currentSampleRate, channelCount, currentChannels);
            synchronized (ttsQueue) {
                ttsQueue.add(resampled);
                Log.d(TAG, "Decoded audio enqueued to ttsQueue. New size = " + ttsQueue.size());
            }
        } catch (Exception e) {
            Log.e(TAG, "Error decoding audio file: " + filePath, e);
        } finally {
            if (codec != null) {
                try { codec.stop(); } catch (Exception ignored) {}
                try { codec.release(); } catch (Exception ignored) {}
            }
            try { extractor.release(); } catch (Exception ignored) {}
        }
    }

    private void handleIncomingWav(byte[] wavBytes) {
        Log.d(TAG, "handleIncomingWav: received " + wavBytes.length + " bytes");
        try {
            if (wavBytes.length < 44) {
                Log.w(TAG, "WAV bytes too short: " + wavBytes.length);
                return;
            }
            
            // Check RIFF and WAVE signatures
            if (wavBytes[0] != 'R' || wavBytes[1] != 'I' || wavBytes[2] != 'F' || wavBytes[3] != 'F') {
                Log.d(TAG, "Not a WAV file, resampling raw PCM bytes: " + wavBytes.length + " (last source rate = " + lastSourceSampleRate + ", channels = " + lastSourceChannels + ")");
                byte[] resampled = resample(wavBytes, lastSourceSampleRate, currentSampleRate, lastSourceChannels, currentChannels);
                synchronized (ttsQueue) {
                    ttsQueue.add(resampled);
                }
                return;
            }

            // Read channels (offset 22)
            int channels = ((wavBytes[22] & 0xFF) | ((wavBytes[23] & 0xFF) << 8));
            // Read sample rate (offset 24)
            int sampleRate = ((wavBytes[24] & 0xFF) | ((wavBytes[25] & 0xFF) << 8) | 
                             ((wavBytes[26] & 0xFF) << 16) | ((wavBytes[27] & 0xFF) << 24));
            if (channels <= 0) channels = 1;
            if (sampleRate <= 0) sampleRate = 16000;
            Log.d(TAG, "WAV Header parsed: sampleRate = " + sampleRate + ", channels = " + channels);

            lastSourceSampleRate = sampleRate;
            lastSourceChannels = channels;

            // Locate "data" chunk
            int offset = 12; // Skip RIFF header
            int dataOffset = -1;
            int dataSize = -1;
            while (offset + 8 <= wavBytes.length) {
                if (wavBytes[offset] == 'd' && wavBytes[offset + 1] == 'a' && wavBytes[offset + 2] == 't' && wavBytes[offset + 3] == 'a') {
                    dataOffset = offset + 8;
                    int s = ((wavBytes[offset + 4] & 0xFF) | ((wavBytes[offset + 5] & 0xFF) << 8) | 
                             ((wavBytes[offset + 6] & 0xFF) << 16) | ((wavBytes[offset + 7] & 0xFF) << 24));
                    if (s > 0 && dataOffset + s <= wavBytes.length) {
                        dataSize = s;
                    }
                    break;
                }
                int chunkSize = ((wavBytes[offset + 4] & 0xFF) | ((wavBytes[offset + 5] & 0xFF) << 8) | 
                                 ((wavBytes[offset + 6] & 0xFF) << 16) | ((wavBytes[offset + 7] & 0xFF) << 24));
                if (chunkSize <= 0 || offset + 8 + chunkSize > wavBytes.length) {
                    break;
                }
                offset += 8 + chunkSize;
            }

            Log.d(TAG, "WAV Data Chunk location: offset = " + dataOffset + ", size = " + dataSize);

            if (dataOffset == -1 || dataSize <= 0) {
                Log.w(TAG, "data chunk not found or invalid size, falling back to 44-byte offset");
                dataOffset = 44;
                dataSize = Math.max(0, wavBytes.length - 44);
            }

            if (dataSize <= 0) {
                Log.w(TAG, "dataSize is 0, nothing to enqueue");
                return;
            }

            byte[] pcmData = new byte[dataSize];
            System.arraycopy(wavBytes, dataOffset, pcmData, 0, dataSize);

            // Resample from the WAV file's sample rate/channels to WebRTC's sample rate/channels
            byte[] resampled = resample(pcmData, sampleRate, currentSampleRate, channels, currentChannels);
            Log.d(TAG, "WAV Audio Resampled: original bytes = " + pcmData.length + ", resampled bytes = " + resampled.length);

            synchronized (ttsQueue) {
                ttsQueue.add(resampled);
                Log.d(TAG, "WAV enqueued to ttsQueue. New size = " + ttsQueue.size());
            }
        } catch (Exception e) {
            Log.e(TAG, "Error handling incoming TTS WAV", e);
        }
    }

    private byte[] resample(byte[] inputBytes, int sourceRate, int targetRate, int srcChannels, int targetChannels) {
        if (sourceRate == targetRate && srcChannels == targetChannels) {
            return inputBytes;
        }

        int numSamples = inputBytes.length / 2;
        short[] sourceSamples;

        // Convert stereo to mono or duplicate mono to stereo
        if (srcChannels == 2 && targetChannels == 1) {
            sourceSamples = new short[numSamples / 2];
            for (int i = 0; i < sourceSamples.length; i++) {
                int left = (short) ((inputBytes[i * 4] & 0xFF) | (inputBytes[i * 4 + 1] << 8));
                int right = (short) ((inputBytes[i * 4 + 2] & 0xFF) | (inputBytes[i * 4 + 3] << 8));
                sourceSamples[i] = (short) ((left + right) / 2);
            }
        } else if (srcChannels == 1 && targetChannels == 2) {
            sourceSamples = new short[numSamples * 2];
            for (int i = 0; i < numSamples; i++) {
                short val = (short) ((inputBytes[i * 2] & 0xFF) | (inputBytes[i * 2 + 1] << 8));
                sourceSamples[i * 2] = val;
                sourceSamples[i * 2 + 1] = val;
            }
        } else {
            sourceSamples = new short[numSamples];
            for (int i = 0; i < numSamples; i++) {
                sourceSamples[i] = (short) ((inputBytes[i * 2] & 0xFF) | (inputBytes[i * 2 + 1] << 8));
            }
        }

        // Apply linear interpolation resampling
        int srcLen = sourceSamples.length;
        double ratio = (double) targetRate / sourceRate;
        int targetLen;
        short[] targetSamples;

        if (targetChannels == 1) {
            targetLen = (int) (srcLen * ratio);
            targetSamples = new short[targetLen];
            for (int i = 0; i < targetLen; i++) {
                double srcIndex = i / ratio;
                int index = (int) srcIndex;
                double fraction = srcIndex - index;
                if (index + 1 < srcLen) {
                    targetSamples[i] = (short) (sourceSamples[index] * (1.0 - fraction) + sourceSamples[index + 1] * fraction);
                } else {
                    targetSamples[i] = sourceSamples[srcLen - 1];
                }
            }
        } else {
            int srcFrames = srcLen / 2;
            int targetFrames = (int) (srcFrames * ratio);
            targetLen = targetFrames * 2;
            targetSamples = new short[targetLen];
            for (int i = 0; i < targetFrames; i++) {
                double srcIndex = i / ratio;
                int index = (int) srcIndex;
                double fraction = srcIndex - index;
                for (int c = 0; c < 2; c++) {
                    int idx1 = index * 2 + c;
                    int idx2 = (index + 1) * 2 + c;
                    if (idx2 < srcLen) {
                        targetSamples[i * 2 + c] = (short) (sourceSamples[idx1] * (1.0 - fraction) + sourceSamples[idx2] * fraction);
                    } else {
                        targetSamples[i * 2 + c] = sourceSamples[srcLen - 2 + c];
                    }
                }
            }
        }

        // Convert short back to bytes
        byte[] outputBytes = new byte[targetLen * 2];
        for (int i = 0; i < targetLen; i++) {
            outputBytes[i * 2] = (byte) (targetSamples[i] & 0xFF);
            outputBytes[i * 2 + 1] = (byte) ((targetSamples[i] >> 8) & 0xFF);
        }
        return outputBytes;
    }

    public void enable() {
        isEnabled = true;
        synchronized (ttsQueue) {
            ttsQueue.clear();
        }
        dtmfDetector.reset();
        Log.d(TAG, "AiAgentAudioProcessor ENABLED");
    }

    public void disable() {
        isEnabled = false;
        synchronized (ttsQueue) {
            ttsQueue.clear();
        }
        dtmfDetector.reset();
        Log.d(TAG, "AiAgentAudioProcessor DISABLED");
    }

    /**
     * High-accuracy, real-time Goertzel Algorithm DTMF Detector for Telephony.
     * Evaluates incoming audio for standard DTMF dual-frequencies (697-941 Hz and 1209-1633 Hz).
     */
    public static class DtmfDetector {
        public interface DtmfListener {
            void onDtmfDetected(char key);
        }

        private static final double[] LOW_FREQS = {697.0, 770.0, 852.0, 941.0};
        private static final double[] HIGH_FREQS = {1209.0, 1336.0, 1477.0, 1633.0};
        private static final char[][] DTMF_KEYS = {
            {'1', '2', '3', 'A'},
            {'4', '5', '6', 'B'},
            {'7', '8', '9', 'C'},
            {'*', '0', '#', 'D'}
        };

        private final DtmfListener listener;
        private int sampleRate = 32000;
        private final List<Float> sampleBuffer = new ArrayList<>();

        private char lastDetectedCandidate = ' ';
        private int candidateHitCount = 0;
        private char lastConfirmedKey = ' ';
        private int silenceBlockCount = 0;

        private static final int CONSECUTIVE_HITS_REQUIRED = 2; // ~25-35 ms
        private static final int SILENCE_BLOCKS_TO_RESET = 2;   // ~25 ms

        public DtmfDetector(DtmfListener listener) {
            this.listener = listener;
        }

        public synchronized void reset() {
            sampleBuffer.clear();
            lastDetectedCandidate = ' ';
            candidateHitCount = 0;
            lastConfirmedKey = ' ';
            silenceBlockCount = 0;
        }

        public synchronized void processSamples(float[] incoming, int count, int currentSampleRate) {
            if (currentSampleRate > 0 && currentSampleRate != this.sampleRate) {
                this.sampleRate = currentSampleRate;
            }

            // Block duration ~25ms
            int blockSize = (int) (this.sampleRate * 0.025);
            if (blockSize < 160) blockSize = 160;
            if (blockSize > 2400) blockSize = 2400;

            for (int i = 0; i < count; i++) {
                sampleBuffer.add(incoming[i]);
            }

            // Cap sample buffer to prevent memory growth if called too fast
            if (sampleBuffer.size() > blockSize * 4) {
                sampleBuffer.subList(0, sampleBuffer.size() - blockSize * 2).clear();
            }

            while (sampleBuffer.size() >= blockSize) {
                float[] block = new float[blockSize];
                for (int i = 0; i < blockSize; i++) {
                    block[i] = sampleBuffer.get(i);
                }
                // 50% overlap for fast response
                int step = blockSize / 2;
                sampleBuffer.subList(0, step).clear();

                analyzeBlock(block, blockSize);
            }
        }

        private void analyzeBlock(float[] block, int N) {
            // Check sample range and normalize to [-32768, 32767] if input is [-1.0, 1.0]
            float maxAbs = 0.0f;
            for (int i = 0; i < N; i++) {
                float abs = Math.abs(block[i]);
                if (abs > maxAbs) maxAbs = abs;
            }
            if (maxAbs > 0.0f && maxAbs <= 1.05f) {
                for (int i = 0; i < N; i++) {
                    block[i] = block[i] * 32767.0f;
                }
            }

            // Total energy
            double totalEnergy = 0.0;
            for (int i = 0; i < N; i++) {
                totalEnergy += (double) block[i] * block[i];
            }
            double avgEnergy = totalEnergy / N;

            // Minimum energy threshold: RMS > 100 (silence rejection)
            if (avgEnergy < 10000.0) {
                handleSilence();
                return;
            }

            // Goertzel for 4 Low Frequencies
            double[] lowPowers = new double[4];
            for (int i = 0; i < 4; i++) {
                lowPowers[i] = goertzelNormalizedPower(block, N, LOW_FREQS[i], sampleRate);
            }

            // Goertzel for 4 High Frequencies
            double[] highPowers = new double[4];
            for (int i = 0; i < 4; i++) {
                highPowers[i] = goertzelNormalizedPower(block, N, HIGH_FREQS[i], sampleRate);
            }

            // Best and 2nd best low
            int bestLow = 0;
            double maxLowPower = lowPowers[0];
            for (int i = 1; i < 4; i++) {
                if (lowPowers[i] > maxLowPower) {
                    maxLowPower = lowPowers[i];
                    bestLow = i;
                }
            }
            double secondLowPower = 0.0;
            for (int i = 0; i < 4; i++) {
                if (i != bestLow && lowPowers[i] > secondLowPower) {
                    secondLowPower = lowPowers[i];
                }
            }

            // Best and 2nd best high
            int bestHigh = 0;
            double maxHighPower = highPowers[0];
            for (int i = 1; i < 4; i++) {
                if (highPowers[i] > maxHighPower) {
                    maxHighPower = highPowers[i];
                    bestHigh = i;
                }
            }
            double secondHighPower = 0.0;
            for (int i = 0; i < 4; i++) {
                if (i != bestHigh && highPowers[i] > secondHighPower) {
                    secondHighPower = highPowers[i];
                }
            }

            // Minimum individual tone power threshold (A > 100 => A^2 > 10000)
            if (maxLowPower < 10000.0 || maxHighPower < 10000.0) {
                handleSilence();
                return;
            }

            // Tone isolation: peak must be at least 1.8x higher than 2nd peak in group
            if (maxLowPower < 1.8 * secondLowPower || maxHighPower < 1.8 * secondHighPower) {
                handleSilence();
                return;
            }

            // Twist: ratio between low and high power (within telecom -8dB to +4dB standard)
            double twist = maxLowPower / (maxHighPower + 1e-6);
            if (twist < 0.15 || twist > 6.5) {
                handleSilence();
                return;
            }

            // Dual tone energy fraction of total block energy
            double dualToneEnergy = (maxLowPower + maxHighPower) / 2.0;
            if (dualToneEnergy / avgEnergy < 0.15) {
                handleSilence();
                return;
            }

            // Detected candidate!
            char candidate = DTMF_KEYS[bestLow][bestHigh];
            handleCandidate(candidate);
        }

        private double goertzelNormalizedPower(float[] samples, int N, double freq, int sampleRate) {
            double omega = 2.0 * Math.PI * freq / sampleRate;
            double coeff = 2.0 * Math.cos(omega);
            double s0 = 0.0;
            double s1 = 0.0;
            double s2 = 0.0;

            for (int i = 0; i < N; i++) {
                s0 = (double) samples[i] + coeff * s1 - s2;
                s2 = s1;
                s1 = s0;
            }

            double rawPower = s1 * s1 + s2 * s2 - coeff * s1 * s2;
            // Normalized power = (4 * rawPower) / (N * N) = A^2
            return (4.0 * rawPower) / ((double) N * N);
        }

        private void handleCandidate(char candidate) {
            silenceBlockCount = 0;
            if (candidate == lastDetectedCandidate) {
                candidateHitCount++;
                if (candidateHitCount >= CONSECUTIVE_HITS_REQUIRED) {
                    if (candidate != lastConfirmedKey) {
                        lastConfirmedKey = candidate;
                        Log.d(TAG, ">>> [GOERTZEL DTMF DETECTED]: Key = '" + candidate + "' <<<");
                        if (listener != null) {
                            listener.onDtmfDetected(candidate);
                        }
                    }
                }
            } else {
                lastDetectedCandidate = candidate;
                candidateHitCount = 1;
            }
        }

        private void handleSilence() {
            silenceBlockCount++;
            if (silenceBlockCount >= SILENCE_BLOCKS_TO_RESET) {
                lastDetectedCandidate = ' ';
                candidateHitCount = 0;
                lastConfirmedKey = ' ';
            }
        }
    }
}
