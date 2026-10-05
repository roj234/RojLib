package roj.archive.algorithms.crypt;

import roj.crypt.CryptoFactory;
import roj.crypt.IvParameterSpecNC;
import roj.crypt.MessageAuthenticCode;
import roj.crypt.RCipher;
import roj.reflect.Unsafe;
import roj.util.DynByteBuf;
import roj.util.Helpers;

import javax.crypto.ShortBufferException;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.MessageDigestSpi;
import java.security.SecureRandom;
import java.security.spec.AlgorithmParameterSpec;

import static roj.reflect.Unsafe.U;

/**
 * '蕨'了，一种宽块加密算法.
 * 参考了 Goooogle 开发的 Adiantum 算法。由于没找到易读的文档，所以只有流程参考了它。
 * 赞美 RFC & LLM！
 * @author Roj234
 * @since 2025/12/26 03:41
 */
public final class Ferned extends RCipher implements SeekableCipher {
	private static final int NH_BLOCK_SIZE = 512;

	private final RCipher aes, xchacha = CryptoFactory.XChaCha(12);
	private final MessageAuthenticCode poly1305;
	private byte[] kChacha, kHash;

	private final byte[] header;
	private final DynByteBuf hIn, hOut;

	private final byte[] hashVal;
	private final DynByteBuf hashBuf;

	private final int sectorSize;

	private boolean encrypt;
	private long sector;

	public Ferned(int sectorSize) {
		this(getInstance("AES/ECB/NoPadding"), CryptoFactory.Poly1305(), sectorSize);}
	private Ferned(RCipher aes, MessageAuthenticCode poly1305, int sectorSize) {
		hIn = DynByteBuf.allocate(16, 16);
		hOut = DynByteBuf.wrap(hIn.array(), 0, 16);
		header = hOut.array();

		hashBuf = DynByteBuf.allocate(24, 24);
		hashVal = hashBuf.array();

		this.aes = aes;
		this.poly1305 = poly1305;
		this.sectorSize = sectorSize;
	}

	private Ferned(Ferned self, boolean isEncryptMode) throws CloneNotSupportedException {
		this(self.aes.copyWith(isEncryptMode), (MessageAuthenticCode) ((MessageDigestSpi)self.poly1305).clone(), self.sectorSize);
		kChacha = self.kChacha;
		kHash = self.kHash;
		sector = self.sector;
		encrypt = isEncryptMode;
	}

	@Override
	public Ferned copyWith(boolean isEncryptMode) {
		try {
			return new Ferned(this, isEncryptMode);
		} catch (CloneNotSupportedException e) {
			return Helpers.athrow2(e);
		}
	}

	@Override
	public void init(boolean encrypt, byte[] key, AlgorithmParameterSpec par, SecureRandom random) throws InvalidKeyException {
		if (key.length != 32) throw new InvalidKeyException("Key must be 32 bytes");

		var aad = hIn;
		aad.clear(); aad.putUTFData("AES");
		byte[] aesKey = CryptoFactory.HKDF_expand(poly1305, key, aad, 32);
		aad.clear(); aad.putUTFData("XChaCha");
		byte[] chachaKey = CryptoFactory.HKDF_expand(poly1305, key, aad, 32);
		aad.clear(); aad.putUTFData("nh_hash");
		byte[] hashKey = CryptoFactory.HKDF_expand(poly1305, key, aad, NH_BLOCK_SIZE);
		aad.clear(); aad.putUTFData("Poly1305");
		byte[] poly1305Key = CryptoFactory.HKDF_expand(poly1305, key, aad, 32);

		kChacha = chachaKey;
		kHash = hashKey;
		aes.init(encrypt, aesKey);
		poly1305.init(poly1305Key);
		hIn.rIndex = 0;
		hIn.wIndex(16);
		hOut.wIndex(0);
		sector = 0;

		this.encrypt = encrypt;
	}

	@Override
	public int engineGetBlockSize() {return sectorSize;}

	@Override public void crypt(DynByteBuf in, DynByteBuf out) throws ShortBufferException {
		if (out.writableBytes() < in.readableBytes()) throw new ShortBufferException();
		while (in.readableBytes() >= sectorSize) cryptOneBlock(in, out);
	}

	@Override
	public void cryptOneBlock(DynByteBuf in, DynByteBuf out) {crypt(in.slice(sectorSize), out, sector++);}

	@Override
	protected void cryptFinal1(DynByteBuf in, DynByteBuf out) {
		crypt(in, out, sector);
		sector = 0;
	}

	@Override public long getSectorSize() {return sectorSize;}
	@Override public long getSector() {return sector;}
	@Override public void setSector(long sector) {this.sector = sector;}

	public void crypt(DynByteBuf in, DynByteBuf out, long tweak) {
		in.readFully(header);

		// H1 = Hash(R)
		HPoly1305(in, in.rIndex, tweak);

		// L' = L ⊕ H1
		for (int i = 0; i < 16; i++) header[i] ^= hashVal[i];

		// L'' = AES(L') and initialize XChaCha cipher
		if (encrypt) {
			cryptHeader();
			initChaCha(tweak);
		} else {
			initChaCha(tweak);
			cryptHeader();
		}

		int offset = out.wIndex();
		out.wIndex(offset + 16); // 留空, 稍后填写

		// R' = R ⊕ Stream(L'')
		try {
			xchacha.crypt(in, out);
		} catch (ShortBufferException e) {
			Helpers.athrow(e);
		}

		// H2 = Hash(R')
		HPoly1305(out, offset + 16, tweak);

		// L''' = L'' ⊕ H2
		for (int i = 0; i < 16; i++) header[i] ^= hashVal[i];
		out.set(offset, header);
	}

	private void HPoly1305(DynByteBuf buf, int pos, long tweak) {
		int remain;
		int dataLength = buf.wIndex() - pos;
		while (true) {
			remain = Math.min(buf.wIndex() - pos, NH_BLOCK_SIZE);
			long hash = nHash(buf.array(), buf._unsafeAddr() + pos, kHash, remain);
			hashBuf.clear();
			poly1305.update(hashBuf.putLong(hash));

			if (remain < NH_BLOCK_SIZE) break;
			pos += remain;
		}
		hashBuf.clear();
		poly1305.update(hashBuf.putLong(tweak).putLong(dataLength));
		hashBuf.clear();
		poly1305.digest(hashBuf);
	}

	private static long nHash(Object p1, long p2, byte[] key, int length) {
		long sum = 0;
		int i = 0;
		int rem;
		while ((rem = length - i) >= 8) {
			int m0 = U.get32UL(p1, p2 + i);
			int m1 = U.get32UL(p1, p2+i+4);

			int k0 = U.get32UL(key, Unsafe.ARRAY_BYTE_BASE_OFFSET + i);
			int k1 = U.get32UL(key, Unsafe.ARRAY_BYTE_BASE_OFFSET+i+4);

			int a = m0 + k0;
			int b = m1 + k1;

			sum += Math.multiplyFull(a, b);

			i += 8;
		}

		if (rem > 0) {
			int m0 = 0;
			int m1 = 0;

			if (rem >= 4) {
				m0 = U.get32UL(p1, p2 + i);

				if (rem > 4) {
					// 处理超过 4 字节但不足 8 字节的情况
					for (int j = 4; j < rem; j++) {
						int b = U.getByte(p1, p2 + i + j) & 0xFF;
						m1 |= b << ((j - 4) * 8);
					}
				}
			} else {
				// Little-Endian
				for (int j = 0; j < rem; j++) {
					int b = U.getByte(p1, p2 + i + j) & 0xFF;
					m0 |= b << (j * 8);
				}
			}

			int k0 = U.get32UL(key, Unsafe.ARRAY_BYTE_BASE_OFFSET + i);
			int k1 = U.get32UL(key, Unsafe.ARRAY_BYTE_BASE_OFFSET+i+4);

			int a = m0 + k0;
			int b = m1 + k1;

			sum += Math.multiplyFull(a, b);
		}

		return sum;
	}

	private void cryptHeader() {
		try {
			aes.crypt(hIn, hOut);
			hIn.rIndex = 0; hOut.wIndex(0);
		} catch (ShortBufferException e) {
			assert false;
		}
	}

	private void initChaCha(long tweak) {
		byte[] chachaIv = hashVal;
		System.arraycopy(header, 0, chachaIv, 0, 16);
		U.put64UL(chachaIv, Unsafe.ARRAY_BYTE_BASE_OFFSET + 16, tweak);

		try {
			xchacha.init(true, kChacha, new IvParameterSpecNC(chachaIv), null);
		} catch (InvalidKeyException | InvalidAlgorithmParameterException e) {
			assert false;
		}
	}
}
