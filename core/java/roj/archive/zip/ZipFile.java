package roj.archive.zip;

import roj.archive.ArchiveFile;
import roj.archive.ArchiveUtils;
import roj.collect.ArrayList;
import roj.collect.XashMap;
import roj.io.IOUtil;
import roj.io.XDataInputStream;
import roj.io.source.Source;
import roj.io.source.SourceInputStream;
import roj.optimizer.FastVarHandle;
import roj.reflect.Telescope;
import roj.text.FastCharset;
import roj.util.ArrayCache;
import roj.util.ByteList;
import roj.util.DynByteBuf;

import java.io.*;
import java.lang.invoke.VarHandle;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.zip.Inflater;
import java.util.zip.ZipException;

/**
 * @author Roj234
 * @since 2024/3/18 10:01
 */
@FastVarHandle
public sealed class ZipFile implements ArchiveFile<ZipEntry> permits ZipEditor {
	Source r, cache;
	static final VarHandle CACHE = Telescope.lookup().findVarHandle(ZipFile.class, "cache", Source.class);

	static final XashMap.Template<String, ZipEntry> ENTRY_TEMPLATE = XashMap.forType(String.class, ZipEntry.class).key("name").build();

	XashMap<String, ZipEntry> namedEntries;
	ArrayList<ZipEntry> entries = new ArrayList<>();

	final Charset cs;
	byte flags;

	public static final long U32_MAX = 4294967295L;
	public static final int ARRAY_READ_MAX = 100 << 20;

	static final int
		HEADER_EXT               = 0x504b0708,
		HEADER_ZIP64_END_LOCATOR = 0x504b0607,
		HEADER_ZIP64_END         = 0x504b0606,
		HEADER_END               = 0x504b0506,
		HEADER_LOC               = 0x504b0304,
		HEADER_CEN               = 0x504b0102,
		HEADER_RCEN              = 0x504be1f2;

	public static final int
		FLAG_Verify      = 1,
		FLAG_ReadCENOnly = 2,
		FLAG_RemoveEXT   = 4,
		/**
		 * Use Roj234 Alt CEN Format (previously Roar)
		 */
		FLAG_RCEN        = 8,

		FLAG_SaveInUTF   = 64,
		FLAG_HasError = 128;

	static final int
		GP_ENCRYPTED  = 1,
		GP_HAS_EXT    = 8,
		GP_STRONG_ENC = 64,
		/** Unicode (UTF-8) File System */
		GP_UFS        = 2048;

	public ZipFile(String name) throws IOException { this(new File(name)); }
	public ZipFile(File file) throws IOException { this(file, FLAG_ReadCENOnly | FLAG_Verify); }
	public ZipFile(File file, int flag) throws IOException { this(file, flag, 0, StandardCharsets.UTF_8); }
	public ZipFile(File file, int flag, Charset charset) throws IOException { this(file, flag, 0, charset); }
	public ZipFile(File file, int flag, long offset, Charset charset) throws IOException {
		this.flags = (byte) flag;
		this.cs = charset;

		r = ArchiveUtils.tryOpenSplitArchive(file, (flag & FLAG_RemoveEXT) == 0);
		r.seek(offset);

		reload();
	}

	public ZipFile(Source source, int flag, Charset cs) {
		this.r = source;
		this.flags = (byte) flag;
		this.cs = cs;
	}

	public final Source source() { return r; }
	public final boolean isClosed() { return r == null; }

	@Override
	public void close() throws IOException {
		Source r1 = r;
		if (r1 == null) return;
		r1.close();
		r = null;

		Source cache = (Source) CACHE.getAndSet(this, r1);
		IOUtil.closeSilently(cache);
	}

	public final void reload() throws IOException {
		cenLength = cenOffset = 0;

		entries = new ArrayList<>();
		namedEntries = null;

		var tmp = ArrayCache.getIOBuffer();
		var in = new XDataInputStream(r.asInputStream(), 4096);
		try {
			var buf = DynByteBuf.wrap(tmp);

			long pos = r.position();
			if ((flags & FLAG_ReadCENOnly) != 0 && r.length() >= 1024) {
				if (readBackward(in, buf)) return;
			}
			in.seek(r, pos);
			readForward(in, buf);
		} catch (Throwable e) {
			IOUtil.closeSilently(this);
			throw e;
		} finally {
			ArrayCache.putArray(tmp);
			in.finish();
		}
	}

	@Override
	public final ZipEntry getEntry(String name) {
		if (namedEntries == null) {
			var namedEntries = ENTRY_TEMPLATE.createSized(entries.size());

			for (int i = 0; i < entries.size(); i++) {
				if (!namedEntries.add(entries.get(i))) {
					throw new IllegalArgumentException("这个压缩文件包含重复的名称！该文件可能已损坏，请通过entries()迭代获取Entry");
				}
			}

			this.namedEntries = namedEntries;
		}

		return namedEntries.get(name);
	}
	@Override
	public final List<ZipEntry> entries() { return Collections.unmodifiableList(entries); }

	// region Load (LOC EXT CEN END)
	private void readForward(XDataInputStream in, ByteList buf) throws IOException {
		ArrayList<ZipEntry> locEntries = new ArrayList<>();
		int header = 0;

		foundUnknownHeader: {
			while (in.position() < r.length()) {
				header = in.readInt();
				if (header == HEADER_LOC) {
					readLOC(locEntries, in, buf);
				} else if (header == HEADER_CEN) {
					readCEN(locEntries, in, buf);
				} else {
					int state = 0;
					if (header == HEADER_ZIP64_END) {
						readEND64(in);
						header = in.readInt();
						state = 1;
					}

					if (header == HEADER_ZIP64_END_LOCATOR) {
						in.skipBytes(16);
						header = in.readInt();
						state = 1;
					}

					if (header == HEADER_END) {
						readEND(state != 0, in);
					} else {
						break foundUnknownHeader;
					}

					break;
				}
			}

			if (in.position() != r.length() || (flags& FLAG_HasError) != 0 || !locEntries.equals(entries)) {
				flags |= FLAG_HasError;
				entries = locEntries;
			}
			return;
		}

		throw new ZipException("unknown header 0x"+Integer.toHexString(header)+" at "+Long.toHexString(in.position() - 4));
	}
	private boolean readBackward(XDataInputStream in, ByteList buf) throws IOException {
		final int MAX_EOCD_SEARCH = 65535 + 22;

		byte[] tmp = buf.list;
		long fileSize = r.length();
		long pos = fileSize - 18;
		long lim = Math.max(0, fileSize - MAX_EOCD_SEARCH);
		int cenCount = 0;

		foundEOCD:
		while (pos > lim+3) {
			int toRead = (int) Math.min(pos - lim, 512);
			long off = pos - toRead;

			r.seek(off);
			r.readFully(tmp, 0, toRead);

			for (int i = toRead - 4; i >= 0; i--) {
				if (tmp[i] != 'P'
					|| tmp[i+1] != 'K'
					|| tmp[i+2] != 0x05
					|| tmp[i+3] != 0x06
				) continue;

				long endPos = off + i;
				long zip64Pos = endPos - 20;

				if (zip64Pos >= 0) {
					in.seek(r, zip64Pos);

					// 提前检查zip64，减少IO次数
					if (in.readInt() == HEADER_ZIP64_END_LOCATOR) {
						in.skipBytes(4); // u4 startDisk
						endPos = zip64Pos = in.readLongLE();
						in.skipBytes(8); // u4 totalDisks
					} else {
						zip64Pos = -1;
						in.skipBytes(20);
					}
				} else {
					in.seek(r, endPos + 4);
				}

				try {
					cenCount = readEND(false, in);
				} catch (IOException ignored) {
					continue;
				}

				// Verify EOCD
				if (in.position() != r.length()) continue;

				boolean endNeedZip64 = cenLength == U32_MAX || cenOffset == U32_MAX || cenCount == 0xFFFF;

				if (endNeedZip64 && zip64Pos >= 0) {
					in.seek(r, zip64Pos);
					if (in.readInt() != HEADER_ZIP64_END)
						throw new ZipException("invalid END header (bad zip64 locator)");
					cenCount = readEND64(in);
				}

				if (cenOffset + cenLength > endPos) throw new ZipException("invalid END header (bad central directory size)");
				if (endPos - cenLength < 0) throw new ZipException("invalid END header (bad central directory offset)");

				pos = -1;
				break foundEOCD;
			}

			pos = off + 3;
		}

		if (cenLength == 0) {
			flags &= ~FLAG_ReadCENOnly;
			return pos < 0;
		}

        entries.ensureCapacity(cenCount);

        try {
			in.seek(r, cenOffset);

			long endPos = cenOffset + cenLength;
			long remain;

			int header = in.readInt();
			if (header == HEADER_RCEN && (flags&FLAG_RCEN) != 0) {
				while ((remain = endPos - in.position()) > 0) {
					readRCENv1(in, buf);
				}
			} else while (true) {
				if (header != HEADER_CEN) {
					throw new ZipException("invalid CEN header (bad signature 0x"+Integer.toHexString(header)+" at offset "+(in.position()-4)+")");
				}

				readCEN(null, in, buf);

				remain = endPos - in.position();
				if (remain <= 0) break;

				header = in.readInt();
			}

			if (remain != 0)
				throw new ZipException("invalid END header (bad central directory size)");
        } finally {
			in.finish();
		}
		if (entries.size() != cenCount) throw new ZipException("invalid END header (bad central directory count)");
		return true;
    }

	private void readLOC(ArrayList<ZipEntry> locEntries, XDataInputStream in, ByteList buf) throws IOException {
		ZipEntry entry = new ZipEntry();

		in.skipBytes(2); // u2 minExtractVer
		int flags = in.readUnsignedShortLE();
		entry.flags = flags;
		entry.method = (char) in.readUnsignedShortLE();
		entry.modTime = in.readIntLE();
		entry.crc32 = in.readIntLE();
		long cSize = in.readUnsignedIntLE();
		entry.compressedSize = cSize;
		entry.size = in.readUnsignedIntLE();

		int nameLen = in.readUnsignedShortLE();
		int extraLen = in.readUnsignedShortLE();

		readName(entry, flags, nameLen, in);

		if (extraLen > 0) {
			buf.clear();
			buf.readStream(in, extraLen);
			entry.extraLenOfLOC = (char) extraLen;
			entry.readLOCExtra(this, buf);
			cSize = entry.compressedSize;
		}

		long off = in.position();
		if (off + cSize > r.length()) throw new ZipException("invalid LOC header (bad compressed size)");

		entry.offset = off;

		if ((flags & GP_HAS_EXT) != 0 && cSize == 0) {
			if (entry.isEncrypted()) throw new ZipException("invalid EXT header (encrypted entry)");
			if (entry.getMethod() != ZipEntry.DEFLATED) throw new ZipException("invalid EXT header (unsupported compression method)");

			// skip method
			var in1 = (InflateInputStream) InflateInputStream.getInstance(in);
			IOUtil.skip(in1, Long.MAX_VALUE);
			Inflater inf = in1.getInflater();

			// 不删除EXT标记，只是保存大小，这样不需要【移动文件】这么大的IO
			if ((this.flags & FLAG_RemoveEXT) != 0) {
				if (inf.getBytesRead() < U32_MAX && inf.getBytesWritten() < U32_MAX) {
					int compressedSize = inf.getTotalIn();
					int uncompressedSize = inf.getTotalOut();
					fixEntrySize(entry, compressedSize, uncompressedSize);
				}
			}

			entry.compressedSize = inf.getBytesRead();
			entry.size = inf.getBytesWritten();

			in.seek(r, off + inf.getBytesRead());
			skipEXT(entry, in);

			in1.finish();
		} else {
			in.seek(r, off + cSize);
			if ((flags & GP_HAS_EXT) != 0) skipEXT(entry, in);
		}

		locEntries.add(entry);
	}

	private void fixEntrySize(ZipEntry entry, int compressedSize, int uncompressedSize) throws IOException {
		long offset = r.position();

		// top + header + offset (usize)
		r.seek(entry.startPos() + 4 + 14);
		r.writeInt(Integer.reverseBytes(compressedSize));
		r.writeInt(Integer.reverseBytes(uncompressedSize));

		r.seek(offset);
	}

	private void skipEXT(ZipEntry entry, DataInput in) throws IOException {
		boolean is64 = entry.compressedSize >= U32_MAX | entry.size >= U32_MAX;
		int skipSize = in.readInt() != HEADER_EXT
				? is64 ? 16 : 8
				: is64 ? 20 : 12;
		in.skipBytes(skipSize);
		entry.setEXTLenOfLOC(skipSize + 4);
	}
	private void readName(ZipEntry entry, int flags, int nameLen, XDataInputStream in) throws IOException {
		entry.nameBytes = in.readBytes(nameLen);

		var cs = (flags & GP_UFS) != 0 ? StandardCharsets.UTF_8 : this.cs;

		FastCharset fcs = FastCharset.getInstance(cs);
		if (fcs != null) {
			var TL = IOUtil.SharedBuf.get();
			var sb = TL.charBuf; sb.clear();
			fcs.decodeFixedIn(TL.wrap(entry.nameBytes), entry.nameBytes.length, sb);
			entry.name = sb.toString();
		} else {
			entry.name = new String(entry.nameBytes, 0, nameLen, cs);
		}
	}
	private void readCEN(ArrayList<ZipEntry> entryForward, XDataInputStream in, ByteList buf) throws IOException {
		ZipEntry entry = new ZipEntry();

		in.skipBytes(1); // versionMadeBy
		int hostSystem = in.readUnsignedByte();
		in.skipBytes(2); // minExtractVersion

		int flags = in.readUnsignedShortLE();
		entry.method = (char) in.readUnsignedShortLE();
		entry.modTime = in.readIntLE();
		entry.crc32 = in.readIntLE();
		entry.compressedSize = in.readUnsignedIntLE();
		entry.size = in.readUnsignedIntLE();

		int nameLen = in.readUnsignedShortLE();
		int extraLen = in.readUnsignedShortLE();
		int commentLen = in.readUnsignedShortLE();

		in.skipBytes(2); // u2 diskId

		flags |= switch (hostSystem) {
			default -> 0;
			case  3 -> 1;
			case 10 -> 2;
			case 19 -> 3;
		} << 23;

		int iAttr = in.readUnsignedShortLE();
		flags |= (iAttr&7) << 20;

		entry.flags = flags;
		entry.attributes = in.readIntLE();
		long fileHeader = in.readUnsignedIntLE();

		readName(entry, flags, nameLen, in);

		// ignore per-file comment
		in.skipBytes(commentLen);

		if (extraLen > 0) {
			buf.clear();
			buf.readStream(in, extraLen);
			fileHeader = entry.readCENExtra(this, buf, fileHeader);
		}

		entry.flags |= ZipEntry.MZ_BACKWARD;
		entry.offset = fileHeader + 30 + nameLen;

		if (entryForward == null) {
			if (!entries.isEmpty() && entries.getLast().offset > entry.offset) {
				this.flags |= FLAG_HasError;
				entry.flags |= ZipEntry.MZ_Error;
			}
		} else {
			if (entries.size() >= entryForward.size()) {
				this.flags |= FLAG_HasError;
				entry.flags |= ZipEntry.MZ_Error;
			} else {
				ZipEntry prev = entryForward.get(entries.size());
				if (!prev.merge(entry)) {
					if ((this.flags & FLAG_Verify) != 0)
						throw new ZipException("压缩参数在LOC和CEN间不匹配("+prev+", "+entry+")");
					this.flags |= FLAG_HasError;
					entry.flags |= ZipEntry.MZ_Error;
					prev.flags |= ZipEntry.MZ_Error;
				} else {
					entry = prev;
				}
			}
		}

		entries.add(entry);
	}
	private void readRCENv1(XDataInputStream in, ByteList buf) throws IOException {
		ZipEntry entry = new ZipEntry();

		int flags = in.readUnsignedShortLE();
		entry.method = (char) in.readUnsignedShortLE();
		entry.crc32 = in.readIntLE();
		entry.compressedSize = in.readUnsignedIntLE();
		entry.size = in.readUnsignedIntLE();

		int nameLen = in.readUnsignedShortLE();
		int extraLen = in.readUnsignedShortLE();

		entry.flags = flags | ZipEntry.MZ_RCEN;
		long fileHeader = in.readUnsignedIntLE();

		readName(entry, flags, nameLen, in);

		if (extraLen > 0) {
			buf.clear();
			buf.readStream(in, extraLen);
			fileHeader = entry.readCENExtra(this, buf, fileHeader);
		}

		entry.offset = fileHeader;

		if (!entries.isEmpty() && entries.getLast().offset > entry.offset) {
			this.flags |= FLAG_HasError;
			entry.flags |= ZipEntry.MZ_Error;
		}

		entries.add(entry);
	}
	private int readEND(boolean zip64, XDataInputStream in) throws IOException {
		int cenCount;

		if (!zip64) {
			in.skipBytes(6);
			// u2 diskId
			// u2 cenBeginDiskId
			// u2 cenOnThisDisk
			cenCount = in.readUnsignedShortLE();
			cenLength = in.readUnsignedIntLE();
			cenOffset = in.readUnsignedIntLE();
		} else {
			cenCount = 0;
			in.skipBytes(16);
		}

		int commentLen = in.readUnsignedShortLE();
		comment = commentLen > 0 ? in.readBytes(commentLen) : ArrayCache.BYTES;

		return cenCount;
	}
	private int readEND64(XDataInputStream in) throws IOException {
		long dataLength = in.readLongLE();

		in.skipBytes(20);
		// 0  u2 verMajor
		// 2  u2 verMinor
		// 4  u4 diskId
		// 8  u4 attrBeginId
		// 12 u8 cenOnThisDisk
		int cenCount = Math.toIntExact(in.readLongLE());
		cenLength = in.readLongLE();
		cenOffset = in.readLongLE();

		in.skipForce(dataLength - 44);

		return cenCount;
	}

	Source openEntry(ZipEntry entry) throws IOException {
		Source src = (Source) CACHE.getAndSet(this, null);
		if (src == null) src = r.copy();

		validateEntry(src, entry);
		return src;
	}
	void closeEntry(Source source) {
		if (!CACHE.compareAndSet(this, null, source)) {
			IOUtil.closeSilently(source);
		}
	}

	public void closeCache() {
		Source src = (Source) CACHE.getAndSet(this, null);
		IOUtil.closeSilently(src);
	}

	final void validateEntry(Source r, ZipEntry entry) throws IOException {
		if ((entry.flags & ZipEntry.MZ_BACKWARD) == 0) return;
		if ((entry.flags & ZipEntry.MZ_Error) != 0 && (flags & FLAG_Verify) != 0) throw new ZipException(entry+"已损坏");

		int extraLen;
		if ((flags & FLAG_Verify) == 0) {
			r.seek(entry.startPos() + 28);

			extraLen = r.read() | (r.read()<<8);
			if (extraLen < 0) throw new EOFException();
		} else {
			r.seek(entry.startPos() + 4);
			// 2024/10/02 针对关键参数做验证 防止快速模式和正常模式解压的数据不统一
			var cachedArray = ArrayCache.getIOBuffer();

			verifySuccess:try {

			var tmp = cachedArray;
			r.readFully(tmp, 0, 26);
			var buf = DynByteBuf.wrap(tmp);

			verifyFailure:{

			var cSize = buf.getUnsignedIntLE(14);
			if (cSize != 0 && entry.compressedSize != cSize) break verifyFailure;

			var uSize = buf.getUnsignedIntLE(18);
			if (uSize != 0 && entry.size != uSize) break verifyFailure;

			extraLen = buf.getUnsignedShortLE(24);

			var prevNameBytes = entry.nameBytes;
			var nameLen = buf.getUnsignedShortLE(22);
			if (nameLen != prevNameBytes.length) break verifyFailure;

			if (nameLen > tmp.length) tmp = new byte[nameLen];
			r.readFully(tmp, 0, nameLen);

			if (!Arrays.equals(prevNameBytes, 0, nameLen, tmp, 0, nameLen)) {
				break verifyFailure;
			}

			var prevName = entry.name;
			long prevOffset = r.position();

			if (extraLen > 0) {
				if (extraLen > tmp.length) tmp = new byte[extraLen];
				r.readFully(tmp, 0, extraLen);
				entry.readLOCExtra(this, DynByteBuf.wrap(tmp, 0, extraLen));
			}

			if (prevName.equals(entry.name) && prevOffset == entry.offset)
				break verifySuccess;

			}
			throw new ZipException("invalid LOC header (名称，偏移和长度与CEN不同)");
			} finally {
				ArrayCache.putArray(cachedArray);
			}
		}

		if ((entry.flags & 8) != 0) {
			if ((this.flags & FLAG_RemoveEXT) != 0) {
				if (entry.compressedSize < U32_MAX && entry.size < U32_MAX) {
					fixEntrySize(entry, (int) entry.compressedSize, (int) entry.size);
				}
			}

			r.seek(entry.offset + entry.compressedSize);
			skipEXT(entry, r.asDataInput());
		}

		entry.flags ^= ZipEntry.MZ_BACKWARD;
		entry.extraLenOfLOC = (char) extraLen;
		entry.offset += extraLen;
	}
	// endregion

	long cenOffset, cenLength;
	byte[] comment = ArrayCache.BYTES;

	public final byte[] getComment() { return comment; }
	public final String getCommentString() { return new String(comment, cs); }

	@Override
	public final String toString() { return "ZipFile{"+entries.size()+" entries, comment='" + getCommentString() + '\'' + '}'; }

	// region Read
	public final InputStream getRawStream(ZipEntry entry) throws IOException {
		if (entry.nameBytes == null) throw new ZipException("ZEntry不是从文件读取的");

		Source src = openEntry(entry);
		src.seek(entry.offset);
		return new SourceInputStream.Shared(src, entry.compressedSize, this, CACHE);
	}

	public final byte[] get(String entry) throws IOException {
		ZipEntry file = getEntry(entry);
		if (file == null) return null;
		return get(file);
	}
	public final byte[] get(ZipEntry file) throws IOException {
		if (file.size > ARRAY_READ_MAX) throw new ZipException("Entry too large, either use a pre-sized array or use streaming method");
		return get(file, new ByteList((int) file.size)).list;
	}
	public ByteList get(ZipEntry file, ByteList buf) throws IOException {
		buf.ensureCapacity((int) (buf.wIndex() + file.size));
		return buf.readStreamFully(getInputStream(file, null));
	}

	public final InputStream getInputStream(ZipEntry entry) throws IOException { return getInputStream(entry, null); }
	public final InputStream getInputStream(ZipEntry entry, byte[] password) throws IOException {
		InputStream in = getRawStream(entry);
		return ZipEntryWriter.getInputStream(in, entry, password, (flags & FLAG_Verify) != 0);
	}
	// endregion
}