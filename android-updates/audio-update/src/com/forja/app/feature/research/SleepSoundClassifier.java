package com.forja.app.feature.research;

import android.content.Context;
import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Collections;
import org.json.JSONArray;
import org.json.JSONObject;

/** YAMNet acoustic events from completed AAC chunks. Scores are not clinical probabilities. */
public final class SleepSoundClassifier {
    public static final String MODEL_VERSION = "yamnet-audioset-1";
    public static final String MODEL_SHA256 = "453caa355b98c860cd5e30780dd5543c8cfbb42b1e376af1126d645c6381de67";
    private static final int SAMPLE_RATE = 16_000, PATCH_SAMPLES = 15_600, HOP_SAMPLES = 7_680;
    private static final int MAX_MS = 122_000, SNORING_INDEX = 38;
    private static final float EVENT_THRESHOLD = 0.5f;
    private static OrtSession sharedSession;
    private static final double[] HANN = new double[400];
    private static final double[][] MEL = new double[64][257];
    private static final int[] MEL_START = new int[64], MEL_END = new int[64];
    static {
        for (int i = 0; i < HANN.length; i++) HANN[i] = 0.5 - 0.5 * Math.cos(2 * Math.PI * i / 400);
        double low = mel(125), high = mel(7500), step = (high - low) / 65;
        for (int band = 0; band < 64; band++) {
            MEL_START[band] = 257;
            for (int bin = 1; bin < 257; bin++) {
                double position = mel(bin * 16000.0 / 512);
                double weight = Math.max(0, Math.min((position - low - band * step) / step,
                    (low + (band + 2) * step - position) / step));
                MEL[band][bin] = weight;
                if (weight > 0) { MEL_START[band] = Math.min(MEL_START[band], bin); MEL_END[band] = bin + 1; }
            }
        }
    }
    private SleepSoundClassifier() {}
    public interface Cancellation { boolean cancelled(); }

    public static JSONObject analyze(Context context, File recording) {
        return analyze(context, recording, null);
    }

    /** The caller also revalidates session ownership and consent before uploading this result. */
    public static JSONObject analyze(Context context, File recording, Cancellation cancellation) {
        String stage = "decode_failed";
        try {
            checkCancelled(cancellation);
            float[] pcm = decode(recording, cancellation);
            stage = "model_unavailable";
            OrtSession session = session(context);
            stage = "inference_failed";
            int durationMs = pcm.length * 1000 / SAMPLE_RATE;
            JSONArray events = new JSONArray();
            long eventStart = -1, eventEnd = 0;
            float eventScore = 0;
            int windows = 1 + Math.max(0, (pcm.length - PATCH_SAMPLES + HOP_SAMPLES - 1) / HOP_SAMPLES);
            for (int window = 0; window < windows; window++) {
                checkCancelled(cancellation);
                int offset = window * HOP_SAMPLES;
                float[] input = logMel(pcm, offset);
                float score;
                try (OnnxTensor tensor = OnnxTensor.createTensor(OrtEnvironment.getEnvironment(),
                        FloatBuffer.wrap(input), new long[]{1, 96, 64});
                     OrtSession.Result result = session.run(Collections.singletonMap("log_mel", tensor))) {
                    float[][] scores = (float[][]) result.get(0).getValue();
                    if (scores.length != 1 || scores[0].length != 521) throw new IllegalStateException("Unexpected model output");
                    score = scores[0][SNORING_INDEX];
                    if (!Float.isFinite(score) || score < 0 || score > 1) throw new IllegalStateException("Invalid model score");
                }
                long start = offset * 1000L / SAMPLE_RATE;
                long end = Math.min(durationMs, start + 975);
                if (score >= EVENT_THRESHOLD) {
                    if (eventStart < 0) eventStart = start;
                    eventEnd = end;
                    eventScore = Math.max(eventScore, score);
                } else if (eventStart >= 0) {
                    addEvent(events, eventStart, eventEnd, eventScore);
                    eventStart = -1; eventScore = 0;
                }
            }
            if (eventStart >= 0) addEvent(events, eventStart, eventEnd, eventScore);
            checkCancelled(cancellation);
            return base().put("status", "complete").put("analyzed_ms", durationMs)
                .put("analyzed_ranges", new JSONArray().put(new JSONObject().put("start_ms", 0).put("end_ms", durationMs)))
                .put("events", events);
        } catch (Cancelled e) { return unavailable("cancelled"); }
        catch (Exception | LinkageError e) { return unavailable(stage); }
    }

    private static JSONObject base() throws Exception {
        return new JSONObject().put("model", "yamnet").put("model_version", MODEL_VERSION).put("model_sha256", MODEL_SHA256);
    }
    private static JSONObject unavailable(String reason) {
        try { return base().put("status", "unavailable").put("reason", reason).put("analyzed_ms", 0)
            .put("analyzed_ranges", new JSONArray()).put("events", new JSONArray()); }
        catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }
    private static void addEvent(JSONArray events, long start, long end, float score) throws Exception {
        if (events.length() > 0) {
            JSONObject previous = events.getJSONObject(events.length() - 1);
            if (start <= previous.getLong("end_ms")) {
                previous.put("end_ms", Math.max(end, previous.getLong("end_ms")))
                    .put("score", Math.max(score, previous.getDouble("score")));
                return;
            }
        }
        if (end > start) events.put(new JSONObject().put("start_ms", start).put("end_ms", end)
            .put("kind", "possible_snoring").put("score", (double) score));
    }
    private static final class Cancelled extends Exception {}
    private static void checkCancelled(Cancellation cancellation) throws Cancelled {
        if (Thread.currentThread().isInterrupted() || (cancellation != null && cancellation.cancelled())) throw new Cancelled();
    }

    private static synchronized OrtSession session(Context context) throws Exception {
        if (sharedSession != null) return sharedSession;
        File directory = new File(context.getNoBackupFilesDir(), "forja_audio");
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IllegalStateException("Model directory unavailable");
        File model = new File(directory, "yamnet-" + MODEL_SHA256 + ".onnx");
        if (!model.isFile() || !MODEL_SHA256.equals(sha256(model))) {
            File temporary = new File(directory, "yamnet.tmp");
            try (InputStream input = context.getAssets().open("forja/yamnet.onnx"); FileOutputStream output = new FileOutputStream(temporary)) {
                byte[] bytes = new byte[65536]; int read;
                while ((read = input.read(bytes)) != -1) output.write(bytes, 0, read);
            }
            if (!MODEL_SHA256.equals(sha256(temporary))) { temporary.delete(); throw new IllegalStateException("Model checksum mismatch"); }
            if (!temporary.renameTo(model)) { temporary.delete(); throw new IllegalStateException("Cannot store model"); }
        }
        try (OrtSession.SessionOptions options = new OrtSession.SessionOptions()) {
            options.setIntraOpNumThreads(2); options.setInterOpNumThreads(1);
            sharedSession = OrtEnvironment.getEnvironment().createSession(model.getAbsolutePath(), options);
        }
        return sharedSession;
    }
    private static String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = new FileInputStream(file)) {
            byte[] bytes = new byte[65536]; int read;
            while ((read = input.read(bytes)) != -1) digest.update(bytes, 0, read);
        }
        StringBuilder result = new StringBuilder();
        for (byte value : digest.digest()) result.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
        return result.toString();
    }

    private static float[] decode(File file, Cancellation cancellation) throws Exception {
        if (!file.isFile() || file.length() < 64 || file.length() > 2 * 1024 * 1024) throw new IllegalArgumentException("Invalid audio chunk");
        MediaExtractor extractor = new MediaExtractor(); MediaCodec decoder = null;
        try {
            extractor.setDataSource(file.getAbsolutePath());
            if (extractor.getTrackCount() != 1) throw new IllegalArgumentException("Single audio track required");
            MediaFormat format = extractor.getTrackFormat(0);
            if (!"audio/mp4a-latm".equals(format.getString(MediaFormat.KEY_MIME))) throw new IllegalArgumentException("AAC required");
            int rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE), channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
            long durationUs = format.getLong(MediaFormat.KEY_DURATION);
            if (rate < 16000 || rate > 48000 || channels < 1 || channels > 2 || durationUs < 500000 || durationUs > MAX_MS * 1000L)
                throw new IllegalArgumentException("Unsupported audio format");
            int limit = (int) Math.min(MAX_MS * (long) rate / 1000, (durationUs * rate + 999999) / 1000000);
            float[] mono = new float[limit]; int used = 0, encoding = AudioFormat.ENCODING_PCM_16BIT;
            extractor.selectTrack(0); decoder = MediaCodec.createDecoderByType("audio/mp4a-latm");
            decoder.configure(format, null, null, 0); decoder.start();
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo(); boolean sentEnd = false, ended = false;
            long deadline = android.os.SystemClock.elapsedRealtime() + 30000;
            while (!ended) {
                checkCancelled(cancellation);
                if (android.os.SystemClock.elapsedRealtime() > deadline) throw new IllegalStateException("Decode timeout");
                if (!sentEnd) {
                    int inputId = decoder.dequeueInputBuffer(10000);
                    if (inputId >= 0) {
                        ByteBuffer input = decoder.getInputBuffer(inputId); input.clear();
                        int size = extractor.readSampleData(input, 0);
                        if (size < 0) { decoder.queueInputBuffer(inputId, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); sentEnd = true; }
                        else { decoder.queueInputBuffer(inputId, 0, size, extractor.getSampleTime(), 0); extractor.advance(); }
                    }
                }
                int outputId = decoder.dequeueOutputBuffer(info, 10000);
                if (outputId == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat output = decoder.getOutputFormat();
                    if (output.getInteger(MediaFormat.KEY_SAMPLE_RATE) != rate || output.getInteger(MediaFormat.KEY_CHANNEL_COUNT) != channels)
                        throw new IllegalArgumentException("Audio format changed");
                    if (output.containsKey(MediaFormat.KEY_PCM_ENCODING)) encoding = output.getInteger(MediaFormat.KEY_PCM_ENCODING);
                    if (encoding != AudioFormat.ENCODING_PCM_16BIT && encoding != AudioFormat.ENCODING_PCM_FLOAT)
                        throw new IllegalArgumentException("Unsupported PCM encoding");
                } else if (outputId >= 0) {
                    if (info.size > 0 && Math.abs(info.presentationTimeUs - used * 1000000L / rate) > 100000)
                        throw new IllegalArgumentException("Discontinuous audio timestamps");
                    ByteBuffer output = decoder.getOutputBuffer(outputId).order(ByteOrder.LITTLE_ENDIAN);
                    output.position(info.offset); output.limit(info.offset + info.size);
                    int sampleBytes = encoding == AudioFormat.ENCODING_PCM_FLOAT ? 4 : 2;
                    if (info.size % (sampleBytes * channels) != 0) throw new IllegalArgumentException("Unaligned PCM");
                    while (output.remaining() >= sampleBytes * channels) {
                        float value = 0;
                        for (int channel = 0; channel < channels; channel++) value += encoding == AudioFormat.ENCODING_PCM_FLOAT ? output.getFloat() : output.getShort() / 32768f;
                        value /= channels;
                        if (!Float.isFinite(value)) throw new IllegalArgumentException("Invalid PCM");
                        if (used < limit) mono[used++] = Math.max(-1, Math.min(1, value));
                    }
                    ended = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                    decoder.releaseOutputBuffer(outputId, false);
                }
            }
            if (used < rate / 2) throw new IllegalArgumentException("Insufficient decoded audio");
            return resample(mono, used, rate, cancellation);
        } finally {
            if (decoder != null) { try { decoder.stop(); } catch (RuntimeException ignored) {} decoder.release(); }
            extractor.release();
        }
    }

    /** Windowed-sinc low-pass resampling; avoids folding high-frequency sound into the speech band. */
    private static float[] resample(float[] input, int length, int rate, Cancellation cancellation) throws Exception {
        int count = (int) (length * (long) SAMPLE_RATE / rate);
        if (rate == SAMPLE_RATE) return Arrays.copyOf(input, count);
        float[] output = new float[count];
        final int phases = 1024, taps = 64;
        double cutoff = 0.475 * SAMPLE_RATE / rate;
        float[][] kernels = new float[phases][taps];
        for (int phase = 0; phase < phases; phase++) {
            double sum = 0;
            for (int tap = 0; tap < taps; tap++) {
                double x = tap - 31 - phase / (double) phases;
                double weight = Math.abs(x) >= 32 ? 0 : (Math.abs(x) < 1e-12 ? 2 * cutoff : Math.sin(2 * Math.PI * cutoff * x) / (Math.PI * x))
                    * (0.5 + 0.5 * Math.cos(Math.PI * x / 32));
                kernels[phase][tap] = (float) weight; sum += weight;
            }
            for (int tap = 0; tap < taps; tap++) kernels[phase][tap] /= sum;
        }
        for (int i = 0; i < count; i++) {
            if ((i & 4095) == 0) checkCancelled(cancellation);
            double position = i * (double) rate / SAMPLE_RATE;
            int center = (int) position, phase = Math.min(phases - 1, (int) ((position - center) * phases));
            double value = 0;
            for (int tap = 0; tap < taps; tap++) {
                int source = center + tap - 31;
                if (source >= 0 && source < length) value += input[source] * kernels[phase][tap];
            }
            output[i] = (float) value;
        }
        return output;
    }

    /** The official YAMNet 25-ms periodic Hann / 10-ms hop / 64-band log-mel transform. */
    static float[] logMel(float[] waveform, int offset) {
        if (offset < 0 || offset >= waveform.length) throw new IllegalArgumentException("Invalid waveform offset");
        float[] result = new float[96 * 64];
        double[] real = new double[512], imaginary = new double[512], magnitude = new double[257];
        for (int frame = 0; frame < 96; frame++) {
            Arrays.fill(real, 0); Arrays.fill(imaginary, 0);
            for (int i = 0; i < 400; i++) {
                int index = offset + frame * 160 + i;
                real[i] = index < waveform.length ? waveform[index] * HANN[i] : 0;
            }
            fft(real, imaginary);
            for (int bin = 0; bin < 257; bin++) magnitude[bin] = Math.hypot(real[bin], imaginary[bin]);
            for (int band = 0; band < 64; band++) {
                double energy = 0;
                for (int bin = MEL_START[band]; bin < MEL_END[band]; bin++) energy += magnitude[bin] * MEL[band][bin];
                result[frame * 64 + band] = (float) Math.log(energy + 0.001);
            }
        }
        return result;
    }
    private static double mel(double hz) { return 1127 * Math.log1p(hz / 700); }
    private static void fft(double[] real, double[] imaginary) {
        for (int i = 1, j = 0; i < 512; i++) {
            int bit = 256; while ((j & bit) != 0) { j ^= bit; bit >>= 1; } j ^= bit;
            if (i < j) { double value = real[i]; real[i] = real[j]; real[j] = value; }
        }
        for (int length = 2; length <= 512; length <<= 1) {
            double stepReal = Math.cos(-2 * Math.PI / length), stepImaginary = Math.sin(-2 * Math.PI / length);
            for (int start = 0; start < 512; start += length) {
                double wr = 1, wi = 0;
                for (int j = 0; j < length / 2; j++) {
                    int a = start + j, b = a + length / 2;
                    double r = real[b] * wr - imaginary[b] * wi, im = real[b] * wi + imaginary[b] * wr;
                    real[b] = real[a] - r; imaginary[b] = imaginary[a] - im;
                    real[a] += r; imaginary[a] += im;
                    double next = wr * stepReal - wi * stepImaginary;
                    wi = wr * stepImaginary + wi * stepReal; wr = next;
                }
            }
        }
    }
}
