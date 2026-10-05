package roj.audio.qoa;

import roj.util.ByteList;
import roj.util.DynByteBuf;

/**
 * Might OK™ CBR/VBR Lossy audio codec (WIP)
 * Currently a modified {@link https://github.com/phoboslab/qoa QOA} format with syncword (32-bit header)
 * @author Roj234
 * @since 2025/2/9 22:12
 */
class MOA {
	public static final int SamplesPerSlice = 20;
	public static final int MaxSlicesPerFrame = 256;
	public static final int SamplesPerFrame = MaxSlicesPerFrame * SamplesPerSlice;
	public static final int LmsHistorySize = 4;
	public static int MaxFrameSize(int channels) {
		return 4 + LmsHistorySize * 4 * channels + 8 * MaxSlicesPerFrame * channels;
	}

	int channels;
	Predictor[] predictors;

	final ByteList inputBuffer = new ByteList();
	final ByteList outputBuffer = new ByteList();

	static final class Predictor {
		final int[] history = new int[LmsHistorySize];
		final int[] weights = new int[] {0, 0, -(1 << 13), (1 << 14)};

		final int predict() {
			int predict = 0;
			for (int i = 0; i < LmsHistorySize; i++) {
				predict += weights[i] * history[i];
			}
			return predict >> 13;
		}

		final void update(int sample, int residual) {
			int delta = residual >> 4;
			for (int i = 0; i < LmsHistorySize; i++) {
				weights[i] += history[i] < 0 ? -delta : delta;
			}

			System.arraycopy(history, 1, history, 0, LmsHistorySize-1);
			history[LmsHistorySize-1] = sample;
		}

		public void write(DynByteBuf ob) {
			for (int i = 0; i < LmsHistorySize; i++) {
				ob.putShort(history[i]).putShort(weights[i]);
			}
		}

		public void read(DynByteBuf ob) {
			for (int i = 0; i < LmsHistorySize; i++) {
				history[i] = ob.readShort();
				weights[i] = ob.readShort();
			}
		}

		public void copyTo(Predictor testPredictor) {
			System.arraycopy(history, 0, testPredictor.history, 0, LmsHistorySize);
			System.arraycopy(weights, 0, testPredictor.weights, 0, LmsHistorySize);
		}
	}

	// 同步字定义
	// 32bits
	// Checksum:8 | VBR:1 | SampleRate:3 | LMSSize:2 | Channels:4 | End:1 | Version:2 | Data:1 | Block:1 | Reserved:1 | SampleCount:8
	static final int[] SampleRates = {48000, 44100, 32000, 24000, 16000, 12000, 8000, 0};

	static final int[] ScaleFactors = {1, 7, 21, 45, 84, 138, 211, 304, 421, 562, 731, 928, 1157, 1419, 1715, 2048};
	static final int[] ReciprocalTab = {65536, 9363, 3121, 1457, 781, 475, 311, 216, 156, 117, 90, 71, 57, 47, 39, 32};
	static {
		for (int i = 0; i < ScaleFactors.length; i++) {

		}
	}
	static int scaledDiv(int v, int scalefactor) {
		int reciprocal = ReciprocalTab[scalefactor];
		int n = (v * reciprocal + (1 << 15)) >> 16;
		n = n + (Integer.compare(v, 0)) - (Integer.compare(n, 0)); // round away from 0
		return n;
	}

	static final int[] QuantizeTab = {
			7, 7, 7, 5, 5, 3, 3, 1, // -8..-1
			0,                      //  0
			0, 2, 2, 4, 4, 6, 6, 6  //  1.. 8
	};
	static final int[][][] DequantizeTab = {
			null,
			{
				{   1,    -1,    3,    -3,    5,    -5,     7,     -7},
				{   5,    -5,   18,   -18,   32,   -32,    49,    -49},
				{  16,   -16,   53,   -53,   95,   -95,   147,   -147},
				{  34,   -34,  113,  -113,  203,  -203,   315,   -315},
				{  63,   -63,  210,  -210,  378,  -378,   588,   -588},
				{ 104,  -104,  345,  -345,  621,  -621,   966,   -966},
				{ 158,  -158,  528,  -528,  950,  -950,  1477,  -1477},
				{ 228,  -228,  760,  -760, 1368, -1368,  2128,  -2128},
				{ 316,  -316, 1053, -1053, 1895, -1895,  2947,  -2947},
				{ 422,  -422, 1405, -1405, 2529, -2529,  3934,  -3934},
				{ 548,  -548, 1828, -1828, 3290, -3290,  5117,  -5117},
				{ 696,  -696, 2320, -2320, 4176, -4176,  6496,  -6496},
				{ 868,  -868, 2893, -2893, 5207, -5207,  8099,  -8099},
				{1064, -1064, 3548, -3548, 6386, -6386,  9933,  -9933},
				{1286, -1286, 4288, -4288, 7718, -7718, 12005, -12005},
				{1536, -1536, 5120, -5120, 9216, -9216, 14336, -14336},
			},
			null
	};

	static int clampS16(int v) {
		if (v < -32768) return -32768;
		if (v > 32767) return 32767;
		return v;
	}
}
