package roj.archive.algorithms.crypt;

import roj.crypt.RCipher;
import roj.util.ByteList;
import roj.util.DynByteBuf;

import javax.crypto.ShortBufferException;
import java.security.InvalidKeyException;
import java.security.SecureRandom;
import java.security.spec.AlgorithmParameterSpec;
import java.util.Arrays;

/**
 * XTS, and 不（太可能）是标准实现
 * @author Roj233
 * @since 2022/2/17 22:05
 */
public final class AES_XTS extends RCipher implements SeekableCipher {
	private final RCipher cipherData, cipherTweak;

	private long sector;

	private final ByteList tweak, cipherIn, cipherOut;
	private final byte[] steal;

	public AES_XTS() {this(getInstance("AES/ECB/NoPadding"));}
	public AES_XTS(RCipher cipher) {
		int blockSize = cipher.engineGetBlockSize();
		if (blockSize == 0) throw new IllegalArgumentException("Not a block cipher");

		cipherData = cipher;
		cipherTweak = cipher.copyWith(true);

		tweak = DynByteBuf.allocate(blockSize, blockSize);
		cipherIn = DynByteBuf.allocate(blockSize, blockSize);
		cipherOut = DynByteBuf.wrap(cipherIn.array(), 0, blockSize);
		steal = new byte[blockSize];
	}

	public int engineGetBlockSize() { return cipherData.engineGetBlockSize(); }
	public String getAlgorithm() { return cipherData.getAlgorithm()+"/XTS/NoPadding"; }

	@Override
	public void init(boolean encrypt, byte[] key, AlgorithmParameterSpec config, SecureRandom random) throws InvalidKeyException {
		var dataKey = Arrays.copyOfRange(key, 0, key.length / 2);
		cipherData.init(encrypt, dataKey);

		var tweakKey = Arrays.copyOfRange(key, key.length / 2, key.length);
		cipherTweak.init(encrypt, tweakKey);

		sector = 0;
	}

	@Override public long getSectorSize() {return 512;}
	@Override public long getSector() {return sector;}
	@Override public void setSector(long sector) {this.sector = sector;}

	@Override
	public void crypt(DynByteBuf in, DynByteBuf out) throws ShortBufferException {
		if (in.readableBytes() < cipherData.engineGetBlockSize()) throw new ShortBufferException("At least 1 block("+ cipherData.engineGetBlockSize()+") required");
		if (out.writableBytes() < in.readableBytes()) throw new ShortBufferException();

		byte[] T = tweak.array();
		ByteList lastPlainBlock = cipherIn;
		ByteList lastCipherBlock = cipherOut;
		byte[] lpb = lastPlainBlock.array();
		byte[] ctsTmp = steal;
		RCipher cip = cipherData;
		int blockSize = T.length;

		//Tweak = E(tweakInput)
		var tweakInput = lastPlainBlock;
		tweakInput.clear();
		tweakInput.putLong(sector++).putZero(8);

		tweak.wIndex(0);
		cipherTweak.cryptOneBlock(tweakInput, tweak);

		int remain = in.readableBytes();
		// C = E(P ^ X) ^ X
		if (remain >= blockSize) while (true) {
			in.readFully(lpb);
			for (int i = 0; i < blockSize; i++) lpb[i] ^= T[i];

			lastPlainBlock.rIndex = 0; lastCipherBlock.wIndex(0);
			cip.cryptOneBlock(lastPlainBlock, lastCipherBlock);

			for (int i = 0; i < blockSize; i++) lpb[i] ^= T[i];

			remain -= blockSize;
			if (remain < blockSize) break;

			out.write(lpb);

			// 注意：放在这个位置并不符合XTS规范（否则解密时处理很麻烦），但是似乎能少写点代码
			// 然后因为没有RFC好像我也不太懂就是
			// X *= a
			gfMul(T, blockSize);
		}

		if (remain == 0) {
			out.write(lpb);
		} else {
			System.arraycopy(lpb, 0, ctsTmp, 0, remain);
			in.readFully(lpb, 0, remain);

			// gfMul(T, blockSize);

			for (int i = 0; i < blockSize; i++) lpb[i] ^= T[i];

			lastPlainBlock.rIndex = 0; lastCipherBlock.wIndex(0);
			cip.cryptOneBlock(lastPlainBlock, lastCipherBlock);

			for (int i = 0; i < blockSize; i++) lpb[i] ^= T[i];

			out.write(lpb);
			out.write(ctsTmp, 0, remain);
		}
	}

	private static byte[] gfMul(byte[] x, int blockSize) {
		int carry = (x[0]&0x80) >>> 7;
		for (int i = 0; i < blockSize - 1; i++) {
			x[i] = (byte) (((x[i] & 0xFF) << 1) | ((x[i + 1] & 0xFF) >>> 7));
		}
		x[blockSize - 1] = (byte) ((x[blockSize - 1] & 0xFF) << 1);

		if (carry == 1) x[blockSize - 1] ^= 0x87;

		return x;
	}
}
