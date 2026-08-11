package roj.ecc;

import org.jetbrains.annotations.Nullable;
import roj.reflect.Unsafe;
import roj.util.ArrayUtil;
import roj.util.FastFailException;

import java.util.Arrays;

import static roj.reflect.Unsafe.U;

/**
 * 不要用C语言谢谢喵，灌注Java谢谢喵
 * @author Roj234
 * @since 2024/12/19 14:02
 */
public final class ReedSolomonCodec {
	private final LinearAllocator localAlloc;
	private final int memorySize;
	private final byte[] premult;
	private final short dataBytes, ecBytes;
	public ReedSolomonCodec(int dataBytes, int ecBytes) {
		if (dataBytes+ecBytes > 255) throw new IllegalArgumentException("Max chunk=255 (not 256!)");
		if (ecBytes <= 0) throw new IllegalArgumentException("EC bytes must > 0");
		if (dataBytes <= 0) throw new IllegalArgumentException("Data bytes must > 0");
		var generator = polyNewGenerator(ecBytes);
		this.premult = new byte[256 * ecBytes];
		for (int fb = 1; fb < 256; fb++) {
			int logFeedback = LOG[fb];
			int rowOffset = fb * ecBytes;
			for (int i = 0; i < ecBytes; i++) {
				this.premult[rowOffset + i] = EXP[LOG[generator[i + 1] & 255] + logFeedback];
			}
		}
		this.memorySize = ecBytes * 9 + (ecBytes / 2) + 6;
		this.localAlloc = new LinearAllocator(memorySize);
		this.dataBytes = (short) dataBytes;
		this.ecBytes = (short) ecBytes;
	}
	public int chunkSize() {return ecBytes+dataBytes;}
	public int dataBytes() {return dataBytes;}
	public int ecBytes() {return ecBytes;}
	public int maxError() {return ecBytes/2;}
	public int getMemorySize() {return memorySize;}

	private byte[] createVandermondeMatrix() {
		int cols = dataBytes;
		int rows = (cols + ecBytes);
		var matrix = new byte[rows];
		for (int r = 0; r < rows; r++) {
			for (int c = 0; c < cols; c++) {
				matrix[r + cols * c] = EXP[(LOG[r] * c) % 255];
			}
		}

		return matrix;
	}

	/**
	 * 输入一个chunkSize大小的数组，根据前dataBytes个字节填入后ecBytes数据
	 */
	public void generateCode(byte[] buf) {
		byte[] state = localAlloc.MEM;
		Arrays.fill(state, 0, ecBytes, (byte)0);

		for (int i = 0; i < dataBytes; i++)
			lfsrUpdate(state, 0, buf[i]);

		System.arraycopy(state, 0, buf, dataBytes, ecBytes);
	}

	/**
	 * 根据 input 字节更新 state[off, off + ecBytes] 状态.
	 * 警告：输入错误可能导致JVM崩溃，使用 -ea 以调试
	 */
	public void lfsrUpdate(byte[] state, int off, byte input) {
		assert off + ecBytes <= state.length;

		int feedback = (state[off] ^ input) & 0xFF;
		int tableOff = feedback * ecBytes;
		byte[] tab = premult;
		int len = ecBytes - 1;

		long sAddr = U.ARRAY_BYTE_BASE_OFFSET + off;
		long tAddr = U.ARRAY_BYTE_BASE_OFFSET + tableOff;

		int i = 0;

		int len8 = len & ~7;
		for (; i < len8; i += 8) {
			long sVal = U.getLong(state, sAddr + i + 1);
			long tVal = U.getLong(tab, tAddr + i);
			U.putLong(state, sAddr + i, sVal ^ tVal);
		}

		if (i + 4 <= len) {
			int sVal = U.getInt(state, sAddr + i + 1);
			int tVal = U.getInt(tab, tAddr + i);
			U.putInt(state, sAddr + i, sVal ^ tVal);
			i += 4;
		}

		if (i + 2 <= len) {
			int sVal = U.getShort(state, sAddr + i + 1);
			int tVal = U.getShort(tab, tAddr + i);
			U.putShort(state, sAddr + i, (short) (sVal ^ tVal));
			i += 2;
		}

		if (i < len) {
			state[off + i] = (byte) (state[off + i + 1] ^ tab[tableOff + i]);
		}

		// 设置最后一个校验字节
		state[off + len] = tab[tableOff + len];
	}

	/**
	 * 检查 buf 是否有错误
	 */
	public boolean hasError(byte[] buf) {
		byte[] state = localAlloc.MEM;
		Arrays.fill(state, 0, ecBytes, (byte)0);
		for (int i = 0; i < dataBytes; i++) lfsrUpdate(state, 0, buf[i]);
		return ArrayUtil.mismatch(state, Unsafe.ARRAY_BYTE_BASE_OFFSET, buf, Unsafe.ARRAY_BYTE_BASE_OFFSET + dataBytes, ecBytes, ArrayUtil.LOG2_ARRAY_BYTE_INDEX_SCALE) >= 0;
	}

	/**
	 * 修复错误
	 * @return 发现并修复了n个错误
	 */
	public int errorCorrection(byte[] buf) {return errorCorrection(buf, null);}

	/**
	 * 纠正错误
	 * @param erasureLocations 部分已知错误（擦除）的索引，可为空
	 */
	public int errorCorrection(byte[] buf, @Nullable byte[] erasureLocations) {return hasError(buf) ? errorCorrection(buf, localAlloc, erasureLocations) : 0;}
	public int errorCorrection(byte[] buf, LinearAllocator m, @Nullable byte[] erasureLocations) {
		var ecBytes = this.ecBytes;

		m.clear();
		var MEM = m.MEM;

		int pSyndrome = m.alloc(ecBytes);
		Arrays.fill(MEM, pSyndrome, pSyndrome+ecBytes, (byte) 0);

		for (int b : buf) {
			int off = pSyndrome+ecBytes - 1;
			for (int i = 0; i < ecBytes; i++) {
				int cur = MEM[off];
				MEM[off] = (byte) (mul(EXP[i], cur) ^ b);
				off--;
			}
		}

		int error = 0;
		for (int i = pSyndrome; i < pSyndrome + ecBytes; i++) {
			error |= MEM[i];
		}
		if (error == 0) return 0;

		int pSigma;
		int pErrorLocations;
		int errorCount;

		if (erasureLocations != null && erasureLocations.length != 0) {
			errorCount = erasureLocations.length;
			// X_j = α^{n-1-pos}
			pErrorLocations = m.alloc(ecBytes);

			int pLambdaA = m.alloc(errorCount + 1);
			int pLambdaB = m.alloc(errorCount + 1);
			int pLambda = pLambdaA;

			MEM[pLambda] = 1;
			int lambdaLen = 1;

			int pPolyGen = m.alloc(2);
			MEM[pPolyGen + 1] = 1;

			// 计算擦除定位多项式 Λ(x) = Π_j (1 + X_j * x)
			for (int i = 0; i < errorCount; i++) {
				int p = erasureLocations[i] & 0xFF;
				if (p >= buf.length) throw new FastFailException("[ECC]Bad erasure pos "+p);
				byte X_j = EXP[buf.length - 1 - p];
				MEM[pErrorLocations + i] = X_j;
				MEM[pPolyGen] = X_j;

				int pDest = (pLambda == pLambdaA) ? pLambdaB : pLambdaA;
				polyMul(MEM, pLambda, lambdaLen, pPolyGen, 2, pDest);
				lambdaLen++;
				pLambda = pDest;
			}

			m.ptr -= 2;

			// T(x) = S(x) * Λ(x) mod x^ecBytes
			int pForneySyn = m.alloc(ecBytes);
			int pForneySynInit = pForneySyn;

			polyMulCapped(MEM, pSyndrome, ecBytes, pLambda, lambdaLen, pForneySyn);

			int forneySynLength = pForneySyn + ecBytes;
			while (pForneySyn < forneySynLength && MEM[pForneySyn] == 0) pForneySyn++;
			forneySynLength -= pForneySyn;

			int pTau, tauLength;
			int synRemaining = ecBytes - errorCount;
			if (forneySynLength <= (ecBytes + errorCount) / 2) {
				// 无未知错误
				pTau = m.alloc(1);
				tauLength = MEM[pTau] = 1;
			} else {
				// Sugiyama
				pTau = berlekampMassey(m, pForneySynInit, synRemaining);
				tauLength = MEM[pTau-1];
			}

			// σ(x) = Λ(x) * τ(x)
			pSigma = m.alloc(tauLength + lambdaLen - 1);
			polyMul(MEM, pTau, tauLength, pLambda, lambdaLen, pSigma);

			int unknownCount = tauLength - 1;
			if (2 * unknownCount + errorCount > ecBytes)
				throw new FastFailException("[ECC]超出纠错能力 erasures="+errorCount+" errors="+unknownCount+" ecBytes="+ecBytes);

			if (unknownCount > 0)
				chienSearch(MEM, pTau, tauLength, pErrorLocations + errorCount, pErrorLocations + (errorCount += unknownCount));
		} else {
			// 2. 求错误定位多项式 sigma(x)
			pSigma = berlekampMassey(m, pSyndrome, ecBytes);
			int sigmaLength = MEM[pSigma-1];

			errorCount = sigmaLength-1;
			if (errorCount > ecBytes / 2)
				throw new FastFailException("[ECC]超出纠错能力 errors="+errorCount+" ecBytes="+ecBytes);

			// use Chien's search to find errored locations
			if (errorCount == 1) {
				pErrorLocations = pSigma;
			} else {
				assert errorCount != 0;
				pErrorLocations = m.alloc(errorCount);
				chienSearch(MEM, pSigma, sigmaLength, pErrorLocations, pErrorLocations + errorCount);
			}
		}

		int pOmega = m.alloc(ecBytes);

		// 计算 Ω(x) = S(x) * σ(x) mod x^ecBytes
		polyMulCapped(MEM, pSyndrome, ecBytes, pSigma, errorCount+1, pOmega);

		int omegaLength = pOmega + ecBytes;
		while (pOmega < omegaLength && MEM[pOmega] == 0) pOmega++;
		omegaLength -= pOmega;

		// use Forney's Formula to get magnitude diff
		for (var i = 0; i < errorCount; i++) {
			byte loc = MEM[pErrorLocations + i];
			var pos = buf.length-1 - log(loc);
			if (pos < 0 || pos >= buf.length) throw new FastFailException("[ECC]Bad location "+(loc&0xFF));

			var invX = inv(loc);
			var denominator = 1;

			for (var j = 0; j < errorCount; j++) {
				if (i != j) {
					denominator = mul(denominator, 1 ^ mul(MEM[pErrorLocations + j], invX));
				}
			}

			if (denominator == 0) throw new FastFailException("[ECC]Forney denominator=0");
			buf[pos] ^= mul(polyEval(MEM, pOmega, omegaLength, invX), inv(denominator));
		}
		return errorCount;
	}

	private static void chienSearch(byte[] MEM, int tau, int tauLength, int i, int end) {
		int Byte = 1;
		while (true) {
			if (polyEval(MEM, tau, tauLength, Byte) == 0) {
				MEM[i] = inv(Byte);
				if (++i >= end) return;
			}
			if (++Byte > 255) throw new FastFailException("[ECC]未知错误位置查找失败, 剩 "+(end - i));
		}
	}

	private static int berlekampMassey(LinearAllocator m, int pSyndromes, int ecSize) {
		// C 与 B 采用低次项在前的自然阶数存储: C[k] 对应 x^k
		byte[] MEM = m.MEM;
		int pC = m.alloc(ecSize + 1);
		int pB = m.alloc(ecSize + 1);
		int pT = m.alloc(ecSize + 1);

		MEM[pC] = 1;
		MEM[pB] = 1;
		Arrays.fill(MEM, pC + 1, pC + ecSize + 1, (byte) 0);
		Arrays.fill(MEM, pB + 1, pB + ecSize + 1, (byte) 0);

		int degrees = 0;
		int step = 1;
		int discrepancy = 1;

		for (int i = 0; i < ecSize; i++) {
			// d = S_i ^ sum_{j=1}^degrees (C_j * S_{i-j})
			int off = pSyndromes + ecSize - 1 - i;
			int d = MEM[off] & 0xFF;
			for (int j = 1; j <= degrees; j++) {
				if (MEM[pC + j] != 0) {
					d ^= 0xFF & mul(MEM[pC + j], MEM[off + j]);
				}
			}

			if (d == 0) {
				step++;
			} else {
				System.arraycopy(MEM, pC, MEM, pT, ecSize + 1);
				int scale = mul(d, inv(discrepancy));

				// C(x) = C(x) ^ scale * x^step * B(x)
				for (int j = 0; j + step <= ecSize; j++) {
					byte b = MEM[pB + j];
					if (b != 0) MEM[pC + j + step] ^= mul(b, scale);
				}

				if (2 * degrees <= i) {
					degrees = i + 1 - degrees;

					// swap
					var tmp = pB;
					pB = pT;
					pT = tmp;

					discrepancy = d;
					step = 1;
				} else {
					step++;
				}
			}
		}

		m.ptr -= (ecSize + 1) * 2;

		MEM[m.ptr++] = (byte) (degrees + 1);

		int pSigma = m.alloc(degrees + 1);

		for (int i = 0; i <= degrees; i++) {
			MEM[pSigma + degrees - i] = MEM[pC + i];
		}

		return pSigma;
	}

	//region GF(p = x^8 + x^4 + x^3 + x^2 + 1, n = 8)
	private static final byte[] EXP = new byte[511 + 512];
	private static final short[] LOG = new short[256];
	static {
		for (var i = 0; i < 8; i++) EXP[i] = (byte) (1 << i);
		for (var i = 8; i < 256; i++) EXP[i] = (byte) (EXP[i - 4] ^ EXP[i - 5] ^ EXP[i - 6] ^ EXP[i - 8]);
		for (var i = 0; i < 255; i++) LOG[EXP[i]&255] = (short) i;

		// mul 的分支预测优化
		for (var i = 255; i < 511; i++) EXP[i] = EXP[i - 255];
		LOG[0] = 511;
	}
	private static int log(int v) {return LOG[v&255]&255;}
	private static byte mul(int a, int b) {return EXP[LOG[a&255] + LOG[b&255]];}
	private static byte inv(int v) {return EXP[255 - log(v)];}
	//endregion
	//region GFPolynomial
	private static byte[] polyNewGenerator(int size) {
		byte[] MEM = new byte[(size + 1) * 2 + 2];
		int pLambdaA = 2;
		int pLambdaB = 3 + size;
		int pLambda = pLambdaA;

		MEM[pLambda] = 1;
		int lambdaLen = 1;

		MEM[0] = 1;

		for (var i = 0; i < size; i++) {
			MEM[1] = EXP[i];

			int pDest = (pLambda == pLambdaA) ? pLambdaB : pLambdaA;
			polyMul(MEM, pLambda, lambdaLen, 0, 2, pDest);
			lambdaLen++;
			pLambda = pDest;
		}

		return Arrays.copyOfRange(MEM, pLambda, pLambda + lambdaLen);
	}
	private static void polyMul(byte[] MEM, int p1, int p1Len, int p2, int p2Len, int pOut) {
		Arrays.fill(MEM, pOut, pOut + p1Len + p2Len - 1, (byte) 0);
		for (var i = 0; i < p1Len; i++) {
			for (int j = 0; j < p2Len; j++) {
				MEM[pOut + i + j] ^= mul(MEM[p1 + i], MEM[p2 + j]);
			}
		}
	}
	private static void polyMulCapped(byte[] MEM, int p1, int p1Len, int p2, int p2Len, int pOut) {
		Arrays.fill(MEM, pOut, pOut + p1Len, (byte) 0);
		for (var i = 0; i < p1Len; i++) {
			int head = p2Len - 1 - i;
			for (int j = Math.max(0, head), k = j - head; j < p2Len; j++, k++) {
				MEM[pOut + k] ^= mul(MEM[p1 + i], MEM[p2 + j]);
			}
		}
	}
	private static byte polyEval(byte[] poly, int offset, int len, int i) {
		if (len == 0) return 0;
		if (i == 0) return poly[offset + len - 1];

		if (i == 1) {
			int val = 0;
			int idx = 0;
			long uAddr = U.ARRAY_BYTE_BASE_OFFSET + offset;

			for (; idx <= len - 8; idx += 8) {
				long batch = U.getLong(poly, uAddr + idx);
				batch ^= (batch >>> 32);
				batch ^= (batch >>> 16);
				batch ^= (batch >>> 8);
				val ^= (batch & 0xFF);
			}
			for (; idx < len; idx++) {
				val ^= poly[offset + idx];
			}
			return (byte) val;
		}

		int val = poly[offset] & 0xFF;
		int logI = LOG[i & 0xFF];
		for (int j = 1; j < len; j++) {
			int p = poly[offset + j] & 0xFF;
			if (val != 0) val = EXP[LOG[val] + logI] & 0xFF;
			val ^= p;
		}
		return (byte) val;
	}
	//endregion
}
