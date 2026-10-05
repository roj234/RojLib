package roj.audio.qoa;

import roj.audio.AudioDecoder;
import roj.audio.AudioMetadata;
import roj.audio.AudioSink;
import roj.crypt.CryptoFactory;
import roj.io.CorruptedInputException;
import roj.io.source.Source;
import roj.math.MathUtils;
import roj.util.JVM;

import javax.sound.sampled.AudioFormat;
import java.io.IOException;

import static roj.reflect.Unsafe.U;

/**
 * @author Roj234-N
 * @since 2025/5/12 17:31
 */
public class MOADecoder extends MOA implements AudioDecoder {
	private Source in;
	private long sample, samples;
	private int samplerate;

	@Override
	public AudioMetadata open(Source in, boolean parseMetadata) throws IOException {
		if (getState() != READY) throw new IllegalStateException("not READY state");

		in.read(inputBuffer, 4);
		int checksum = CryptoFactory.CRC8(inputBuffer, 3);
		int syncword = inputBuffer.readMedium();
		if (checksum != inputBuffer.readUnsignedByte()) throw new CorruptedInputException("checksum error");

		//int reserved = (syncword >>> 8) & 1;
		int block = (syncword >>> 9) & 1;
		int data = (syncword >>> 10) & 1;
		int version = (syncword >>> 11) & 3;
		//int end = (syncword >>> 13) & 1;
		channels = ((syncword >>> 14) & 15);
		//int bitsPerSample = ((syncword >>> 18) & 3);
		samplerate = SampleRates[((syncword >>> 20) & 7)];
		//int vbr = (syncword >>> 23) & 1;
		if (channels == 0 || samplerate == 0 || version != 1 || data != 0)
			throw new CorruptedInputException("syncword error: "+Integer.toHexString(syncword));

		if (block != 0) {
			in.read(inputBuffer, 4);
			samples = inputBuffer.readInt();
		} else {
			samples = 0;
		}

		this.in = in;
		this.sample = 0;

		predictors = new Predictor[channels];
		for (int i = 0; i < channels; i++) predictors[i] = new Predictor();

		inputBuffer.clear();
		return null;
	}

	@Override
	public void connect(AudioSink sink) throws IOException {
		if (getState() != OPENED) throw new IllegalStateException("Not opened");

		AudioFormat audioFormat = new AudioFormat(samplerate, 16, channels, true, JVM.BIG_ENDIAN);
		sink.open(audioFormat);

		int qoaMaxFrameSize = MaxFrameSize(channels);
		inputBuffer.ensureCapacity(qoaMaxFrameSize);

		outputBuffer.ensureCapacity(SamplesPerFrame * channels * 2);

		Source in = this.in;
		while (this.in != null) {
			in.read(inputBuffer, qoaMaxFrameSize);
			if (!inputBuffer.isReadable()) break;

			try {
				int samplesDecoded = decodeFrame();
				sink.write(outputBuffer.list, 0, samplesDecoded * channels * 2);
				sample += samplesDecoded;
			} catch (Exception e) {
				sink.flush();
				throw e;
			}

			inputBuffer.compact();
		}
		in = null;
	}

	public int decodeFrame() throws CorruptedInputException {
		var ib = inputBuffer;

		int checksum = CryptoFactory.CRC8(ib, 3);
		int syncword = ib.readMedium();
		if (checksum != ib.readUnsignedByte())
			throw new CorruptedInputException("syncword error: "+Integer.toHexString(syncword));

		int sampleCount = (syncword) & 0xFF;
		//int reserved = (syncword >>> 8) & 1;
		//int block = (syncword >>> 9) & 1;
		int data = (syncword >>> 10) & 1;
		int version = (syncword >>> 11) & 3;
		//int end = (syncword >>> 13) & 1;
		int channels = ((syncword >>> 14) & 15);
		//int bps = ((syncword >>> 18) & 3);
		int samplerate = SampleRates[((syncword >>> 20) & 7)];
		int lastFrame = ((syncword >>> 13) & 1);
		//int vbr = ((syncword >>> 23) & 1);
		int samples = (lastFrame == 0 ? MaxSlicesPerFrame : sampleCount) * SamplesPerSlice;

		if (version != 1 || channels != this.channels || samplerate != this.samplerate || data == 0)
			throw new CorruptedInputException("syncword error: "+Integer.toHexString(syncword));

		for (int c = 0; c < channels; c++) predictors[c].read(ib);

		Object obstart1 = outputBuffer.array();
		long obstart2 = outputBuffer._unsafeAddr();

		for (int sampleId = 0; sampleId < samples; sampleId += SamplesPerSlice) {
			for (int c = 0; c < channels; c++) {
				long slice = ib.readLong();
				int scalefactor = (int) ((slice >>> 60) & 0xf);
				int sliceStart = sampleId * channels + c;
				int sliceEnd = MathUtils.clamp(sampleId + SamplesPerSlice, 0, samples) * channels + c;

				int bits = 3;
				int mask = (1 << bits) - 1;
				for (int si = sliceStart; si < sliceEnd; si += channels) {
					int predicted = predictors[c].predict();
					int quantized = (int) ((slice >>> (60 - bits)) & mask);
					int dequantized = DequantizeTab[bits-2][scalefactor][quantized];
					int reconstructed = clampS16(predicted + dequantized);

					U.putShort(obstart1, obstart2 + si*2L, (short) reconstructed);
					slice <<= bits;

					predictors[c].update(reconstructed, dequantized);
				}
			}
		}

		return samples;
	}

	@Override public void disconnect() {
		in = null;
	}

	@Override public int getState() {
		if (in == null) return samplerate == 0 ? READY : FINISHED;
		return sample == 0 ? OPENED : DECODING;
	}

	@Override public boolean isSeekable() {return true;}
	@Override public void seek(double timeSec) throws IOException {
		var duration = getDuration();

		if (timeSec < 0) timeSec = 0;
		else if (timeSec > duration) timeSec = duration;

		long frame = (long)Math.floor(timeSec * samplerate / SamplesPerFrame);

		samples = frame * SamplesPerFrame;
		long seekPos = (samples == 0 ? 4 : 8) + frame / MaxFrameSize(channels);
		in.seek(seekPos);
	}
	@Override public double getCurrentTime() {return (double) sample / samplerate;}
	@Override public double getDuration() {
		if (samples != 0 || in == null) return (double) samples / samplerate;
		int maxFrameSize = MaxFrameSize(channels);
		try {
			long frameCount = ((in.length() - (samples == 0 ? 4 : 8)) + maxFrameSize-1) / maxFrameSize;
			return (double) (frameCount * SamplesPerFrame) / samplerate;
		} catch (Exception e) {
			return 0;
		}
	}
}