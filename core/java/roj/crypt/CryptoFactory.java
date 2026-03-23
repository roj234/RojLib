package roj.crypt;

import org.intellij.lang.annotations.MagicConstant;
import org.jetbrains.annotations.Nullable;
import roj.RojLib;
import roj.collect.HashMap;
import roj.compiler.runtime.SwitchMap;
import roj.io.IOUtil;
import roj.reflect.Unsafe;
import roj.util.ByteList;
import roj.util.DynByteBuf;
import roj.util.Helpers;
import roj.util.OperationDone;
import roj.util.optimizer.IntrinsicCandidate;

import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import java.security.*;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.Map;
import java.util.Random;
import java.util.function.Function;

import static java.lang.Integer.rotateLeft;
import static roj.reflect.Unsafe.U;

/**
 * @author Roj234
 * @since 2023/4/29 3:44
 */
public final class CryptoFactory extends Provider {
	static Provider instance;
	public static Provider getInstance() {return instance;}
	public static synchronized void register() {
		if (instance == null) Security.addProvider(new CryptoFactory());
	}

	private CryptoFactory() {
		super("RojLib", 2.0, "RojLib Security Provider v2.0");
		setup();
		instance = this;
	}

	private static final SwitchMap CIPHERS = SwitchMap.Builder
			.builder(20, true)
			.add("AES", 1)
			.add("AES/ECB/NoPadding", 1)
			.add("SM4", 2)
			.add("ChaCha20", 3)
			.add("XChaCha20", 4)
			.add("ChaCha20-Poly1305", 5)
			.add("XChaCha20-Poly1305", 6)
			.add("AES/GCM/NoPadding", 7)
			.build();
	public static RCipher getCipherInstance(String transformation) {
		return switch (CIPHERS.get(transformation)) {
			case 1 -> new AES();
			case 2 -> new SM4();
			case 3 -> new ChaCha();
			case 4 -> new XChaCha();
			case 5 -> new ChaCha_Poly1305(new ChaCha());
			case 6 -> new ChaCha_Poly1305(new XChaCha());
			case 7 -> new AES_GCM();
			default -> {
				int pos = transformation.indexOf('/');
				if (pos < 0) Helpers.athrow2(new NoSuchAlgorithmException("找不到"+transformation+"算法的RojLib实现"));

				var cipher = getCipherInstance(transformation.substring(0, pos));
				try {
					if (!cipher.isBareBlockCipher()) throw new InvalidAlgorithmParameterException(transformation+"算法不是标准块密码");
					cipher = new FeedbackCipher(cipher, FeedbackCipher.MODE_ECB);
					int endIndex = transformation.indexOf('/', pos+1);
					if (endIndex < 0) Helpers.athrow2(new NoSuchAlgorithmException(transformation+"算法格式错误"));

					cipher.engineSetMode(transformation.substring(pos+1, endIndex));
					cipher.engineSetPadding(transformation.substring(endIndex+1));
				} catch (Exception e) {
					Helpers.athrow(e);
				}

				yield cipher;
			}
		};
	}

	private void setup() {
		// Not register Cipher SPI anymore

		put("MessageDigest.Blake3", "roj.crypt.Blake3");
		put("MessageDigest.SM3", "roj.crypt.SM3");

		if (!Security.getAlgorithms("KeyFactory").contains("EdDSA")) {
			put("KeyFactory.EdDSA", "roj.crypt.eddsa.EdKeyFactory");
			put("KeyPairGenerator.EdDSA", "roj.crypt.eddsa.EdKeyGenerator");
			put("KeyPairGenerator.Ed25519", "roj.crypt.eddsa.EdKeyGenerator");
			put("Signature.EdDSA", "roj.crypt.eddsa.EdSignature");
			put("Signature.Ed25519", "roj.crypt.eddsa.EdSignature");
			//put("KeyAgreement.X25519", "roj.crypt.eddsa.X25519DH_ForSPI");
		}
	}

	private static final ThreadLocal<Map<String, MessageDigest>> DIGESTS = ThreadLocal.withInitial(HashMap::new);
	private static final Function<String, MessageDigest> NEW_DIGEST = a -> {
		try {
			return MessageDigest.getInstance(a);
		} catch (NoSuchAlgorithmException e) {
			Helpers.athrow(e);
			throw OperationDone.NEVER;
		}
	};
	public static MessageDigest getSharedDigest(@MagicConstant(stringValues = {"SHA-1", "SHA-256"}) String algorithm) {
		MessageDigest digest = DIGESTS.get().computeIfAbsent(algorithm, NEW_DIGEST);
		digest.reset();
		return digest;
	}
	public static MessageDigest getMessageDigest(String algorithm) {
		return NEW_DIGEST.apply(algorithm);
		//(MessageDigest) getSharedDigest(algorithm).clone();
	}

	public static RCipher XChaCha(int nrounds) {
		if ((nrounds&1) != 0) throw new IllegalArgumentException("nrounds must be even");
		return new XChaCha(nrounds/2);
	}

	//Hash
	public static BufferedDigest SM3() {return new SM3();}
	//end Hash
	//MAC
	@SuppressWarnings("unchecked")
	public static <T extends BufferedDigest & MessageAuthenticCode> T Blake3(int digestLength) {return (T) new Blake3(digestLength);}
	public static MessageAuthenticCode HMAC(MessageDigest digest) {return new HMAC(digest);}
	public static MessageAuthenticCode HMAC(MessageDigest digest, int blockSize) {return new HMAC(digest, blockSize);}
	public static MessageAuthenticCode Poly1305() {return new Poly1305();}
	//end MAC

	//密钥交换
	//public static KeyExchange DH() {return new DH();}
	//public static KeyExchange ECDH() {return new ECDH();}
	//public static KeyExchange X25519DH() {return new X25519DH();}
	//end 密钥交换

	//数字签名(复用Java的API)

	//伪随机数生成器
	public static Random WyRandom() {return new WyRand();}
	public static Random WyRandom(long seed) {return new WyRand(seed);}
	public static Random L64W64X128MixRandom() {return new L64W64X128Mix();}
	public static Random L64W64X128MixRandom(long seed) {return new L64W64X128Mix(seed);}
	//end 伪随机数生成器
	//region 证书验证
	private static X509TrustManager defaultTrustStore;
	public static X509TrustManager getDefaultTrustStore() throws NoSuchAlgorithmException {
		if (defaultTrustStore == null) {
			TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
			try {
				tmf.init((KeyStore) null);
			} catch (KeyStoreException e) {
				throw new NoSuchAlgorithmException("Unable to find system default trust manger", e);
			}
			for (TrustManager manager : tmf.getTrustManagers()) {
				if (manager instanceof X509TrustManager) {
					return defaultTrustStore = (X509TrustManager) manager;
				}
			}
			throw new NoSuchAlgorithmException("Unable to find system default trust manger");
		}
		return defaultTrustStore;
	}

	/**
	 * 检查证书是否由可信CA签发, 并处于有效期中.
	 * 这个方法不检查在线吊销列表(大概), 也不判断证书是否拥有某项权限
	 * @param certificates 证书链
	 * @throws GeneralSecurityException 当证书无效时
	 */
	public static void checkCertificateValidity(X509Certificate[] certificates) throws GeneralSecurityException {
		getDefaultTrustStore().checkServerTrusted(certificates, "UNKNOWN");
	}
	//endregion
	//region 密钥派生算法
	/**
	 * 使用PBKDF2算法从密码派生密钥
	 *
	 * @param hashAlgorithm 使用的哈希算法(作为HMAC)
	 * @param password 输入的密码字节数组
	 * @param salt 盐值字节数组
	 * @param iterationCount 迭代次数(成本因子)
	 * @param derivedKeyLength 要派生的密钥长度(字节数)
	 * @return 派生出的密钥字节数组
	 */
	public static byte[] PBKDF2_Derive(MessageAuthenticCode hashAlgorithm, byte[] password, byte[] salt, int iterationCount, int derivedKeyLength) {
		hashAlgorithm.init(password);

		byte[] out = new byte[derivedKeyLength];
		byte[] io = Arrays.copyOf(salt, salt.length+4);
		byte[] tmp = new byte[hashAlgorithm.getDigestLength()];

		int off = 0;
		int blockCounter = 1;
		while (off < derivedKeyLength) {
			Unsafe.U.put32UB(io, Unsafe.ARRAY_BYTE_BASE_OFFSET + io.length-4, blockCounter++);
			hashAlgorithm.update(io);
			salt = hashAlgorithm.digestShared();
			System.arraycopy(salt, 0, tmp, 0, salt.length);

			for (int i = 1; i < iterationCount; i++) {
				hashAlgorithm.update(salt);
				salt = hashAlgorithm.digestShared();
				for (int k = salt.length-1; k >= 0; k--) tmp[k] ^= salt[k];
			}

			System.arraycopy(tmp, 0, out, off, Math.min(tmp.length, derivedKeyLength-off));
			off += tmp.length;
		}

		return out;
	}

	/**
	 * HKDF提取阶段 - 从输入密钥材料(IKM)中提取伪随机密钥(PRK)
	 *
	 * @param hmac 使用的HMAC算法实例
	 * @param salt 可选的盐值(如果不需要可为空)
	 * @param inputKeyMaterial 输入密钥材料(IKM)
	 * @return 提取出的伪随机密钥(PRK)
	 */
	public static byte[] HKDF_extract(MessageAuthenticCode hmac, byte[] salt, byte[] inputKeyMaterial) {
		hmac.init(salt,0,salt.length);
		hmac.update(inputKeyMaterial);
		return hmac.digest();
	}

	/**
	 * HKDF扩展阶段 - 从伪随机密钥(PRK)扩展出指定长度的密钥材料
	 *
	 * @param hmac 使用的HMAC算法实例
	 * @param pseudoRandomKey 伪随机密钥(PRK)
	 * @param outputSize 要输出的密钥材料长度
	 * @return 扩展出的密钥材料字节数组
	 */
	public static byte[] HKDF_expand(MessageAuthenticCode hmac, byte[] pseudoRandomKey, int outputSize) {
		return HKDF_expand(hmac, pseudoRandomKey, ByteList.EMPTY, outputSize);
	}

	/**
	 * HKDF扩展阶段 - 从伪随机密钥(PRK)扩展出指定长度的密钥材料(带附加信息)
	 *
	 * @param hmac 使用的HMAC算法实例
	 * @param pseudoRandomKey 伪随机密钥(PRK)
	 * @param additionalInfo 可选的附加信息(可为空)
	 * @param outputSize 要输出的密钥材料长度
	 * @return 扩展出的密钥材料字节数组
	 */
	public static byte[] HKDF_expand(MessageAuthenticCode hmac, byte[] pseudoRandomKey, DynByteBuf additionalInfo, int outputSize) {
		byte[] out = new byte[outputSize];
		HKDF_expand(hmac, pseudoRandomKey, additionalInfo, outputSize, out, Unsafe.ARRAY_BYTE_BASE_OFFSET);
		return out;
	}

	public static void HKDF_expand(MessageAuthenticCode hmac, byte[] pseudoRandomKey, DynByteBuf additionalInfo, int outputSize, Object outputRef, long outputAddress) {
		if (pseudoRandomKey != null) hmac.init(pseudoRandomKey);

		if (additionalInfo != null) hmac.update(additionalInfo);
		hmac.update((byte) 0);
		byte[] io = hmac.digestShared();

		int off = 0;
		int counter = 1;
		while (off < outputSize) {
			hmac.update(io);
			if (additionalInfo != null) hmac.update(additionalInfo);
			hmac.update((byte) counter++);

			Unsafe.U.copyMemory(hmac.digestShared(), Unsafe.ARRAY_BYTE_BASE_OFFSET, outputRef, outputAddress+off, Math.min(io.length, outputSize-off));

			off += io.length;
		}
	}

	/**
	 * 使用HMAC-SHA256的HKDF便捷方法
	 *
	 * @param inputKeyMaterial 输入密钥材料
	 * @param salt 可选的盐值(如果不需要可为空)
	 * @param outputLength 要输出的密钥材料长度
	 * @return 派生出的密钥材料字节数组
	 * @throws RuntimeException 如果SHA-256算法不可用
	 */
	public static byte[] HKDF_HmacSha256(byte[] inputKeyMaterial, @Nullable byte[] salt, int outputLength) {
		return HKDF_expand(
				new HMAC(getMessageDigest("SHA-256")),
				inputKeyMaterial,
				salt == null ? ByteList.EMPTY : IOUtil.SharedBuf.get().wrap(salt),
				outputLength
		);
	}
	//endregion
	//region 小数据的无上下文哈希
	public static int CRC8(DynByteBuf in, int length) {
		int i = 0, j;
		int crc = 0;
		while (i < length) {
			crc ^= in.getByte(in.rIndex + i++);

			j = 7;
			do {
				if ((crc & 0x80) != 0) crc = (crc << 1) ^ 0x7;
				else crc <<= 1;
			} while (--j != 0);
		}
		return crc&0xFF;
	}

	private static final int BLOCK_SIZE = 16;
	private static final int P1 = 0x9e3779b1, P2 = 0x85ebca77, P3 = 0xc2b2ae3d, P4 = 0x27d4eb2f, P5 = 0x165667b1;

	static {RojLib.linkLibrary(CryptoFactory.class, RojLib.GENERIC);}

	@IntrinsicCandidate("IL_xxHash32")
	public static int xxHash32(int seed, byte[] buf, int off, int len) {
		return xxHash32(seed, (Object) buf, Unsafe.ARRAY_BYTE_BASE_OFFSET+off, len);
	}
	public static int xxHash32(int seed, Object buf, long off, int len) {
		long end = off + len;
		int hash;

		if (len >= BLOCK_SIZE) {
			int a = seed + P1 + P2;
			int b = seed + P2;
			int c = seed;
			int d = seed - P1;

			do {
				a = rotateLeft(a + U.get32UL(buf, off) * P2, 13) * P1;
				b = rotateLeft(b + U.get32UL(buf, (off + 4)) * P2, 13) * P1;
				c = rotateLeft(c + U.get32UL(buf, (off + 8)) * P2, 13) * P1;
				d = rotateLeft(d + U.get32UL(buf, (off + 12)) * P2, 13) * P1;

				off += BLOCK_SIZE;
			} while (off <= end - BLOCK_SIZE);

			hash = rotateLeft(a, 1) + rotateLeft(b, 7) + rotateLeft(c, 12) + rotateLeft(d, 18);
		} else {
			hash = seed + P5;
		}

		hash += len;

		while (off <= end - 4) {
			hash = rotateLeft(hash + U.get32UL(buf, off) * P3, 17) * P4;
			off += 4;
		}

		while (off < end) {
			hash = rotateLeft(hash + (U.getByte(buf, off) & 0xFF) * P5, 11) * P1;
			off++;
		}

		hash ^= hash >>> 15;
		hash *= P2;
		hash ^= hash >>> 13;
		hash *= P3;
		hash ^= hash >>> 16;

		return hash;
	}

	public static long wyhash(long seed, byte[] data) {return wyhash(seed, data, 0, data.length);}
	public static long wyhash(long seed, byte[] data, int off, int len) {return wyhash(data, Unsafe.ARRAY_BYTE_BASE_OFFSET + off, len, seed, WyRand.WYHASH_SECRET);}
	public static long wyhash(Object p1, long p2, int len, long seed, long[] secret) {return WyRand.wyhash(p1, p2, len, seed, secret);}
	//endregion

	static int[] reverse(int[] arr, int i, int length) {
		if (--length <= 0) return arr;

		for (int e = Math.max((length + 1) >> 1, 1); i < e; i++) {
			int a = arr[i];
			arr[i] = arr[length - i];
			arr[length - i] = a;
		}
		return arr;
	}
}