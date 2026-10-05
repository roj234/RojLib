package roj.archive.algorithms.filter;

import org.jetbrains.annotations.NotNull;
import roj.archive.algorithms.crypt.Ferned;
import roj.archive.sevenz.SevenZCodec;
import roj.archive.sevenz.SevenZCodecExtension;
import roj.crypt.CipherInputStream;
import roj.crypt.CipherOutputStream;
import roj.crypt.CryptoFactory;
import roj.crypt.HMAC;
import roj.io.IOUtil;
import roj.io.MBInputStream;
import roj.util.ArrayCache;
import roj.util.DynByteBuf;
import roj.util.Helpers;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * @author Roj234
 * @since 2025/12/26 06:05
 */
@SevenZCodecExtension
public final class SeekableCrypt extends SevenZCodec {
	private static final byte[] ID = {'R',':','S','c','F','e'};
	static {register(ID, SeekableCrypt::new);}

	private static final int CYCLE_POWER_MAX = 24;

	byte cyclePower;
	byte[] salt;

	public SeekableCrypt(String pass) {this(pass, 19, 16);}
	public SeekableCrypt(String pass, int cyclePower, int saltLength) {this(pass.getBytes(StandardCharsets.UTF_8), cyclePower, saltLength);}
	public SeekableCrypt(byte[] pass, int cyclePower, int saltLength) {
		if ((cyclePower < 0 || cyclePower > CYCLE_POWER_MAX)) throw new IllegalStateException("cyclePower的范围是[0,"+CYCLE_POWER_MAX+"]");
		if (saltLength < 0 || saltLength > 16) throw new IllegalStateException("saltLength的范围是[0,16]");

		this.cyclePower = (byte) cyclePower;

		if (saltLength == 0) salt = ArrayCache.BYTES;
		else salt = new SecureRandom().generateSeed(saltLength);

		init(pass);
	}
	private SeekableCrypt(DynByteBuf props) {
		cyclePower = (byte) (props.readUnsignedByte() & 31);
		int saltLen = props.readUnsignedByte() & 31;

		salt = saltLen == 0 ? ArrayCache.BYTES : props.readBytes(saltLen);
	}

	public byte[] id() {return ID;}

	public OutputStream encode(OutputStream out) {
		if (lastKey == null) throw new IllegalArgumentException("缺少密码");

		return new CipherOutputStream(out, ferned.copyWith(true));
	}
	public InputStream decode(InputStream in, byte[] key, long uncompressedSize, AtomicInteger memoryLimit) {
		if (key == null) throw new IllegalArgumentException("缺少密码");
		init(key);

		Ferned cipher = ferned.copyWith(false);
		var cin = new CipherInputStream(in, cipher);
		return new MBInputStream() {
			long bytesRead;

			@Override
			public int read(@NotNull byte[] b, int off, int len) throws IOException {
				int read = cin.read(b, off, len);
				if (read > 0) bytesRead += read;
				return read;
			}
			@Override
			public long skip(long n) throws IOException {
				if (n <= 0) return 0;

				final long requestedSkip = n;
				int sectorSize = cipher.engineGetBlockSize();

				// 跳过当前扇区内剩余的部分
				long offsetInSector = bytesRead & (sectorSize - 1);
				if (offsetInSector != 0) {
					long toSkip = Math.min(n, sectorSize - offsetInSector);
					long skipped = IOUtil.skip(cin, toSkip);
					bytesRead += skipped;
					n -= skipped;
					if (skipped < toSkip || n == 0) return requestedSkip - n;
				}

				// 扇区跳跃
				if (n >= sectorSize) {
					long toSkip = n / sectorSize * sectorSize;
					// 直接在原始输入流上 skip
					long skipped = IOUtil.skip(in, toSkip);
					bytesRead += skipped;
					n -= skipped;

					long skippedSectors = skipped / sectorSize;
					cipher.setSector(cipher.getSector() + skippedSectors);
					cin.wipe(); // 清除解密缓存

					if (skipped < toSkip) return requestedSkip - n;
				}

				// 处理尾部
				if (n > 0) {
					long skipped = IOUtil.skip(cin, n);
					bytesRead += skipped;
					n -= skipped;
				}

				return requestedSkip - n;
			}

			@Override
			public void close() throws IOException {cin.close();}
		};
	}

	@Override
	public void writeOptions(DynByteBuf props) {
		props.put(cyclePower).put(salt.length).put(salt);
	}

	private byte[] lastKey;
	private Ferned ferned;
	private void init(byte[] key) {
		if (Arrays.equals(lastKey, key)) return;
		lastKey = key;

		if (cyclePower > CYCLE_POWER_MAX) throw new IllegalArgumentException("Cycle Power too large in "+this);

		MessageDigest sha = CryptoFactory.getSharedDigest("SHA-256");
		byte[] realKey = CryptoFactory.PBKDF2_Derive(new HMAC(sha), key, salt, 1 << cyclePower, 32);

		try {
			// 其实AES-CTR也许就行，不需要使用这种磁盘加密算法
			var cipher = new Ferned(1024);
			cipher.init(false, realKey);
			ferned = cipher;
		} catch (Exception e) {
			Helpers.athrow(e);
		}
	}

	@Override
	public String toString() { return "Roj234SeekableCrypt[Ferned]:"+cyclePower; }
}