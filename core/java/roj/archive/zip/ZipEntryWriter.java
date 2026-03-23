package roj.archive.zip;

import org.jetbrains.annotations.NotNull;
import roj.archive.xz.LZMA2Options;
import roj.archive.xz.LZMAInputStream;
import roj.archive.xz.LZMAOutputStream;
import roj.crypt.CRC32;
import roj.crypt.CipherInputStream;
import roj.crypt.CipherOutputStream;
import roj.io.CRC32InputStream;
import roj.io.Finishable;
import roj.io.IOUtil;
import roj.io.MBOutputStream;
import roj.io.source.Source;
import roj.io.source.SourceInputStream;
import roj.reflect.Unsafe;
import roj.util.ArrayCache;
import roj.util.ByteList;
import roj.util.DynByteBuf;
import roj.util.JVM;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.InvalidKeyException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.ZipException;

import static roj.archive.zip.ZipEditor.*;

/**
 * {@link ZipEditor}和{@link ZipPacker}共同的压缩者实现
 * @author Roj234
 * @since 2025/12/28 07:42
 */
final class ZipEntryWriter extends MBOutputStream {
	private static final int PKWARE_SPEC_VERSION = 63;
	private static final char[] HOST_SYSTEM_ID = {0x0000, 0x0300, 0x0a00, 0x1300};
	private static final int LZMA_VERSION = 0x0902;

	final Source rawOut;
	private int compressionLevel;
	// RCEN flag
	public int flags;

	// 压缩器配置
	private LZMA2Options lzma2Options;

	// 缓存的可复用对象
	private final ByteList buf;
	private Deflater def;
	private DeflateOutputStream defOut;
	private ZipAES aes;

	// per-ZEntry
	ZipEntry entry;
	private long LOCOffset;
	private boolean stream;
	private OutputStream out;
	private long inputSize;
	private int crc32 = CRC32.initial;

	public ZipEntryWriter(Source rawOut, ByteList buf, int compressionLevel) {
		this.rawOut = rawOut;
		this.buf = buf;
		this.compressionLevel = compressionLevel;
	}

	public void setCompressionLevel(int compressionLevel) {
		this.compressionLevel = compressionLevel;
	}

	private Deflater getDeflater() {
		if (def == null) def = new Deflater(compressionLevel, true);
		return def;
	}

	private LZMA2Options getLzmaOptions() {
		if (lzma2Options == null)
			lzma2Options = new LZMA2Options(compressionLevel);

		return lzma2Options;
	}

	/**
	 *
	 * @param stream 流式压缩 (若为false并且使用ZipCrypto加密，那么crc32必须已知！)
	 * @param useZip64 在LOC预先写入Zip64扩展字段
	 */
	public void beginEntry(
			ZipEntry entry,
			boolean stream,
			boolean useZip64,
			byte[] password
	) throws IOException {
		if (this.entry != null) throw new IllegalStateException();
		this.entry = entry;
		this.stream = stream;

		if (entry.nameBytes == null) {
			entry.flags |= GP_UFS;
			entry.nameBytes = IOUtil.encodeUTF8(entry.name);
		}

		int encryptType = entry.getEncryptMethod();

		sizeIsKnown: {
			if (stream) {
				if (encryptType == ZipEntry.ENC_ZIPCRYPTO) {
					// 由于无法预知大小，所以必须存在EXT标记
					entry.flags |= GP_HAS_EXT;
				}

				if (entry.getMethod() == ZipEntry.LZMA) {
					entry.flags |= 2; // LZMA EOS Marker Present
				}
			} else {
				if (entry.size != 0 && entry.getMethod() == ZipEntry.STORED) {
					entry.compressedSize = entry.size;
					break sizeIsKnown;
				}
			}

			entry.compressedSize = entry.size = useZip64 ? U32_MAX : 0;
		}

		LOCOffset = rawOut.position();
		if ((flags&ZipFile.FLAG_RCEN) == 0) {
			writeLOC(rawOut, buf, entry);
			entry.offset = LOCOffset + buf.wIndex();
		} else {
			entry.offset = LOCOffset;
		}

		OutputStream out = rawOut;

		if (encryptType != ZipEntry.ENC_NONE) {
			if (password == null) throw new NullPointerException("提供密码以加密文件");

			if (encryptType == ZipEntry.ENC_ZIPCRYPTO) {
				ZipCrypto c = new ZipCrypto();
				c.init(true, password);
				out = new CipherOutputStream(out, c);

				var rand = ThreadLocalRandom.current();

				byte[] iv = buf.list;
				Unsafe.U.put32UL(iv, Unsafe.ARRAY_BYTE_BASE_OFFSET, rand.nextInt());
				Unsafe.U.put32UL(iv, Unsafe.ARRAY_BYTE_BASE_OFFSET + 4, rand.nextInt());
				Unsafe.U.put32UL(iv, Unsafe.ARRAY_BYTE_BASE_OFFSET + 8, rand.nextInt());
				iv[11] = (byte) (stream ? entry.modTime >>> 8 : entry.crc32 >>> 24);

				out.write(iv, 0, 12);
			} else {
				if (aes == null)
					aes = new ZipAES();
				try {
					aes.init(true, password);
				} catch (InvalidKeyException ignored) {}
				aes.writeHeader(out);

				out = new CipherOutputStream(out, aes);
			}
		}

		switch (entry.getMethod()) {
			default -> throw new IllegalStateException("Unsupported method: " + entry.getMethod());
			case ZipEntry.STORED -> {}
			case ZipEntry.DEFLATED -> {
				// 使用自定义的实现，这样可以共享缓冲区 & 共享流
				if (defOut == null) defOut = new DeflateOutputStream(out, getDeflater(), buf.list);
				else defOut.setOut(out);
				out = defOut;
			}
			case ZipEntry.LZMA -> {
				LZMA2Options lzmaOptions = getLzmaOptions();

				buf.clear();
				//| 偏移量 | 长度 (字节) | 属性 | 描述 |
				//| :--- | :--- | :--- | :--- |
				//| 0 | 1 | LZMA SDK Major Version | LZMA SDK 主版本号 |
				//| 1 | 1 | LZMA SDK Minor Version | LZMA SDK 次版本号 |
				//| 2 | 2 | Size of Properties (L) | 属性数据的长度（通常为 5） |
				//| 4 | L | LZMA Properties | LZMA 解压所需的属性参数 |
				buf.putShort(LZMA_VERSION)
				   .putShortLE(0x0005)
				   .put(lzmaOptions.getPropByte()).putIntLE(lzmaOptions.getDictSize())
				   .writeToStream(out);

				out = new LZMAOutputStream(out, lzmaOptions, false, stream, -1);
			}
		}

		this.out = out;
	}

	@SuppressWarnings("IOResourceOpenedButNotSafelyClosed")
	static InputStream getInputStream(
			InputStream in,
			ZipEntry entry,
			byte[] pw,
			boolean verify
	) throws IOException {

		if (entry.isEncrypted()) {
			if (pw == null) throw new IllegalArgumentException("缺少密码: "+entry);
			if (entry.getEncryptMethod() == ZipEntry.ENC_ZIPCRYPTO) {
				ZipCrypto c = new ZipCrypto();
				c.init(false, pw);

				in = new CipherInputStream(in, c);

				// has ext, CRC cannot be computed before
				int checkByte = (entry.flags & GP_HAS_EXT) != 0 ? 0xFF&(entry.modTime >>> 8) : entry.crc32 >>> 24;

				long r = in.skip(11);
				if (r < 11) throw new ZipException("数据错误: "+r);

				int myCb = in.read();
				if (myCb != checkByte && verify) throw new ZipException("校验错误: check="+checkByte+", read="+myCb);
			} else {
				ZipAES c = new ZipAES();
				boolean checkPassed = c.setKeyDecrypt(pw, in);
				if (!checkPassed && verify) throw new ZipException("校验错误: 密码错误？");

				((SourceInputStream) in).remain -= 10;
				in = verify ? new ZipAES.MacChecker(in, c) : new CipherInputStream(in, c);
			}
		}

		switch (entry.method) {
			case ZipEntry.DEFLATED -> in = InflateInputStream.getInstance(in);
			case ZipEntry.LZMA -> {
				var tmp = ArrayCache.getIOBuffer();
				IOUtil.readFully(in, tmp, 0, 9);
				if (tmp[2] != 0x05) throw new ZipException("不支持的LZMA参数");
				int prop = tmp[4];
				int dictSize = Unsafe.U.get32UL(tmp, Unsafe.ARRAY_BYTE_BASE_OFFSET + 5);
				ArrayCache.putArray(tmp);

				@SuppressWarnings("IOResourceOpenedButNotSafelyClosed")
				var in1 = new LZMAInputStream(in, entry.size, (byte) prop, dictSize);
				if ((entry.flags & 0x02) != 0) in1.enableRelaxedEndCondition();
				in = in1;
			}
		}

		if ((entry.flags & ZipEntry.MZ_AES2) == 0 && verify)
			in = new CRC32InputStream(in, entry.crc32);

		return in;
	}

	public void write(byte[] b, int off, int len) throws IOException {
		if (entry == null) throw new ZipException("Entry closed");
		if (!stream) throw new IllegalStateException("Not stream mode");

		inputSize += len;
		crc32 = CRC32.update(crc32, b, off, len);
		out.write(b, off, len);
	}

	public void write(DynByteBuf buf, boolean isLast) throws IOException {
		if (entry == null) throw new ZipException("Entry closed");

		inputSize += buf.readableBytes();
		if (stream) crc32 = CRC32.update(crc32, buf);

		if (isLast) {
			// tricks for accelerate
			if (out == defOut) {
				if (buf.hasArray()) {
					def.setInput(buf.array(), buf.relativeArrayOffset(), buf.readableBytes());
				} else {
					if (JVM.VERSION >= 11 && buf.isDirect()) {
						def.setInput(buf.nioBuffer());
					}
				}
				defOut.def();
				return;
			} else if (out == rawOut) {
				rawOut.write(buf);
				return;
			}
		}

		buf.writeToStream(out);
	}

	public void closeEntry() throws IOException {
		ZipEntry entry = this.entry;
		if (entry == null) return;
		this.entry = null;

		Source f = rawOut;

		// 不能调用close，不然把Source给关了
		if (out instanceof Finishable fin) fin.finish();
		// GC
		if (defOut != null) {
			defOut.setOut(null);
			def.reset();
		}

		int encryptType = entry.getEncryptMethod();
		if (encryptType == ZipEntry.ENC_AES || encryptType == ZipEntry.ENC_AES_NOCRC) {
			aes.writeFooter(f);
		}

		long pos = f.position();

		long compressedSize = pos - entry.offset;
		long uncompressedSize = inputSize;

		boolean useZip64 = entry.compressedSize >= U32_MAX || entry.size >= U32_MAX;
		boolean requireZip64 = compressedSize > U32_MAX || uncompressedSize > U32_MAX;
		// 这也可以降级为警告，因为只是LOC里缺失了这些数据
		if (requireZip64 && !useZip64) throw new ZipException("文件过大(4GB), 请在beginEntry中启用zip64");

		int _crc32 = CRC32.finish(crc32);

		boolean updateLOC =
				compressedSize != entry.compressedSize
			 || uncompressedSize != entry.size
			 || (!stream && entry.crc32 != _crc32);

		entry.compressedSize = compressedSize;
		entry.size = uncompressedSize;
		if (stream) entry.crc32 = _crc32;

		crc32 = CRC32.initial;
		inputSize = 0;

		if ((this.flags & ZipFile.FLAG_RCEN) != 0) return;

		if ((entry.flags & GP_HAS_EXT) != 0) {
			if (!stream) throw new IllegalStateException("EXT record on non-stream entry");

			writeEXT(f, buf, entry);
			pos += buf.readableBytes();
		}

		if (updateLOC) {
			var buf = this.buf; buf.clear();
			buf.putIntLE(entry.getCRC32FW())
			   .putIntLE((int) Math.min(compressedSize, U32_MAX))
			   .putIntLE((int) Math.min(uncompressedSize, U32_MAX));

			f.seek(LOCOffset+14);
			f.write(buf);

			if (useZip64) {
				f.seek(LOCOffset+34+entry.nameBytes.length);
				buf.clear();
				f.write(buf.putLongLE(uncompressedSize).putLongLE(compressedSize));
			}

			f.seek(pos);
		}
	}

	public void finish() {
		if (def != null) def.end();
	}

	static void writeLOC(OutputStream out, ByteList buf, ZipEntry file) throws IOException {
		buf.clear();
		buf.putInt(HEADER_LOC)
		   .putShortLE(file.getMinExtractVersion())
		   .putShortLE(file.flags)
		   .putShortLE(file.getMethodFW())
		   .putIntLE(file.modTime)
		   .putIntLE(file.getCRC32FW())
		   .putIntLE((int) file.compressedSize)
		   .putIntLE((int) file.size)
		   .putShortLE(file.nameBytes.length)
		   .putShortLE(0) // extra
		   .put(file.nameBytes);
		file.writeLOCExtra(buf, buf.wIndex(), buf.wIndex() - file.nameBytes.length - 2);

		buf.writeToStream(out);
	}
	static void writeEXT(OutputStream out, ByteList buf, ZipEntry entry) throws IOException {
		if ((entry.flags & GP_HAS_EXT) == 0) throw new ZipException("缺少GP标志");
		entry.flags &= ~ZipEntry.MZ_EXTLenOfLOC;

		buf.clear();
		buf.putInt(HEADER_EXT).putIntLE(entry.crc32);
		if (entry.compressedSize >= U32_MAX | entry.size >= U32_MAX) {
			buf.putLongLE(entry.compressedSize).putLongLE(entry.size);
			entry.setEXTLenOfLOC(24);
		} else {
		   buf.putIntLE((int) entry.compressedSize).putIntLE((int) entry.size);
		   entry.setEXTLenOfLOC(16);
		}
		buf.writeToStream(out);
	}
	static void writeCEN(ByteList buf, ZipEntry entry, long offsetDelta) {
		int extLenOff = buf.wIndex() + 30;
		buf.putInt(HEADER_CEN)
		   .putShortLE(PKWARE_SPEC_VERSION | HOST_SYSTEM_ID[(entry.flags >>> 23) & 3])
		   .putShortLE(entry.getMinExtractVersion())
		   .putShortLE(entry.flags)
		   .putShortLE(entry.getMethodFW())
		   .putIntLE(entry.modTime).putIntLE(entry.getCRC32FW())
		   .putIntLE((int) entry.compressedSize).putIntLE((int) entry.size)
		   .putShortLE(entry.nameBytes.length)
		   .putShortLE(0) // ext
		   .putShortLE(0) // comment
		   .putShortLE(0) // disk
		   .putShortLE((entry.flags >>> 20) & 7).putIntLE(entry.attributes)
		   .putIntLE((int) (entry.startPos() + offsetDelta))
		   .put(entry.nameBytes);
		int extOff = buf.wIndex();
		entry.writeCENExtra(buf, extLenOff, offsetDelta);
		buf.setShortLE(extLenOff, buf.wIndex()-extOff);
	}
	static void writeEND(ByteList buf, long cenOffset, long cenLength, int cenCount, byte[] comment) {
		boolean zip64 = false;
		int co;
		if (cenOffset >= U32_MAX) {
			co = (int) U32_MAX;
			zip64 = true;
		} else {
			co = (int) cenOffset;
		}

		int cl;
		if (cenLength >= U32_MAX) {
			cl = (int) U32_MAX;
			zip64 = true;
		} else {
			cl = (int) cenLength;
		}

		int count = cenCount;
		if (count >= 0xFFFF) {
			count = 0xFFFF;
			zip64 = true;
		}

		if (zip64) {
			buf.putInt(HEADER_ZIP64_END).putLongLE(44).putShortLE(45).putShortLE(45) // size, ver, ver
			   .putIntLE(0).putIntLE(0) // disk id, attr begin id
			   .putLongLE(cenCount).putLongLE(cenCount) // disk entries, total entries
			   .putLongLE(cenLength).putLongLE(cenOffset)

			   .putInt(HEADER_ZIP64_END_LOCATOR).putInt(0) // eof disk id
			   .putLongLE(cenOffset + cenLength).putIntLE(1); // disk in total
		}

		buf.putInt(HEADER_END)
		   .putShortLE(0)
		   .putShortLE(0)
		   .putShortLE(count)
		   .putShortLE(count)
		   .putIntLE(cl)
		   .putIntLE(co)
		   .putShortLE(comment.length)
		   .put(comment);
	}
	static void writeRCEN(ByteList buf, ZipEntry entry, long offsetDelta) {
		int extLenOff = buf.wIndex() + 12;
		buf.putShortLE(entry.flags)
		   .putShortLE(entry.getMethodFW())
		   .putIntLE(entry.getCRC32FW())
		   .putIntLE((int) entry.compressedSize).putIntLE((int) entry.size)
		   .putShortLE(entry.nameBytes.length)
		   .putShortLE(0) // ext
		   .putIntLE((int) (entry.offset + offsetDelta))
		   .put(entry.nameBytes);
		int extOff = buf.wIndex();
		entry.writeCENExtra(buf, extLenOff, offsetDelta);
		buf.setShortLE(extLenOff, buf.wIndex()-extOff);
	}

	private static final class DeflateOutputStream extends DeflaterOutputStream implements Finishable {
		public DeflateOutputStream(@NotNull OutputStream out, @NotNull Deflater def, byte[] sharedBuffer) {
			super(out, def, 1);
			buf = sharedBuffer;
		}
		public void setOut(OutputStream out) {this.out = out;}

		public void def() throws IOException {
			if (def.finished()) throw new IOException("write beyond end of stream");
			while (!def.needsInput()) deflate();
		}

		@Override
		public void finish() throws IOException {
			super.finish();
		}
	}
}
