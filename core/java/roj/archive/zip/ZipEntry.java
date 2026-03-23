package roj.archive.zip;

import org.intellij.lang.annotations.MagicConstant;
import roj.archive.ArchiveEntry;
import roj.archive.WinAttributes;
import roj.crypt.CRC32;
import roj.text.DateFormat;
import roj.text.TextUtil;
import roj.util.ByteList;
import roj.util.DynByteBuf;
import roj.util.OperationDone;

import java.nio.file.attribute.FileTime;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

import static roj.archive.ArchiveUtils.java2WinTime;
import static roj.archive.ArchiveUtils.readWinTime;
import static roj.archive.zip.ZipFile.*;

/**
 * @author Roj234
 * @since 2023/3/14 0:43
 */
public class ZipEntry implements ArchiveEntry, Cloneable {
	public static final int
			STORED = 0,
			DEFLATED = 8,
			LZMA = 14;

	public static final byte
			ENC_NONE      = 0,
			ENC_ZIPCRYPTO = 1,
			ENC_AES       = 2,
			ENC_AES_NOCRC = 3;

	char method;

	int crc32;
	long compressedSize, size;
	int modTime;

	long pModTime, pAccTime, pCreTime;
	public int attributes;

	// bit 1-16: General Purpose Flags
	// bit 17: LOC have been read
	// bit 18-19: EXTLenOfLOC
	// bit 20-22: internalAttribute (isTextFile, reserved, PK00)
	// bit 23-24: hostSystem
	// bit 25: ReadOnly [Has error]
	// bit 26: ZipAES  Encrypt
	// bit 27: ZipAES2 Encrypt [No CRC32]
	// bit 28: Precision Timestamp
	// bit 29: Unicode Path
	// bit 30: Roj234 CEN format
	int flags;

	static final int
			MZ_BACKWARD = 0x10000,
			MZ_EXTLenOfLOC = 0x60000,
			MZ_InternalAttribute = 0x380000,
			MZ_HostSystem = 0xC00000,
			MZ_Error = 1 << 25,
			MZ_AES = 1 << 26,
			MZ_AES2 = 1 << 27,
			MZ_PrecisionTime = 1 << 28,
			MZ_UniPath = 1 << 29,
			MZ_RCEN = 1 << 30;

	String name;
	byte[] nameBytes;

	/** 数据起始 */
	long offset;
	char extraLenOfLOC;

	private ZipEntry _next;

	public ZipEntry(String name) {
		if (name.indexOf('\\') >= 0 || name.startsWith("/")) throw new IllegalArgumentException("名称"+name+"不合法");
		this.name = name;
		this.method = DEFLATED;
	}

	ZipEntry() {}

	public String getName() { return name; }
	public boolean isDirectory() { return name.endsWith("/"); }

	public long getOffset() { return offset; }
	public final long startPos() { return (flags&MZ_RCEN) != 0 ? offset : offset - 30 - nameBytes.length - extraLenOfLOC; }
	public final long endPos() {
		long EXTLenOfLOC = (flags & GP_HAS_EXT) != 0 ? ((flags >>> 16) & 12) + 12 : 0;
		return offset + compressedSize + EXTLenOfLOC;
	}

	// 12 16 20 24
	final void setEXTLenOfLOC(int size) {
		int i = (size - 12) / 4;
		if (i < 0 || i > 3) throw new IllegalArgumentException();

		flags |= i << 18;
	}

	final int getMinExtractVersion() {
		if (method == LZMA) return 63;
		if ((flags & MZ_AES) != 0) return 51;
		if (size > U32_MAX || compressedSize > U32_MAX || offset > U32_MAX) return 45;
		return method == DEFLATED ? 20 : 10;
	}

	final int getMethodFW() { return (flags & MZ_AES) != 0 ? 99 : method; }
	final int getCRC32FW() { return (flags & MZ_AES2) != 0 ? 0 : crc32; }

	public final long getSize() { return size; }
	public final long getCompressedSize() { return compressedSize; }

	public final long getAccessTime() { return pAccTime; }
	public final long getCreationTime() { return pCreTime; }
	public final long getModificationTime() { return pModTime == 0 ? dos2JavaTime(modTime) : pModTime; }

	public final FileTime getPrecisionAccessTime() { return pAccTime == 0 ? null : FileTime.from(pAccTime, TimeUnit.MILLISECONDS); }
	public final FileTime getPrecisionCreationTime() { return pCreTime == 0 ? null : FileTime.from(pCreTime, TimeUnit.MILLISECONDS); }
	public final FileTime getPrecisionModificationTime() { return !hasModificationTime() ? null : FileTime.from(pModTime == 0 ? pModTime : dos2JavaTime(modTime), TimeUnit.MILLISECONDS); }

	public final boolean hasAccessTime() { return pAccTime != 0; }
	public final boolean hasCreationTime() { return pCreTime != 0; }
	public final boolean hasModificationTime() { return modTime != 0 || pModTime != 0; }

	public final void setAccessTime(long t) { pAccTime = t == 0 ? 0 : java2WinTime(t); flags |= MZ_PrecisionTime; }
	public final void setCreationTime(long t) { pCreTime = t == 0 ? 0 : java2WinTime(t); flags |= MZ_PrecisionTime; }
	public final void setModificationTime(long t) {
		if (t == 0) {
			modTime = 0;
			pModTime = 0;
		} else {
			modTime = java2DosTime(t);
			pModTime = t;
			if (modTime == INVALID_DATE) flags |= MZ_PrecisionTime;
		}
	}
	public final void setPrecisionAccessTime(FileTime t) { pAccTime = t == null ? 0 : t.toMillis(); flags |= MZ_PrecisionTime; }
	public final void setPrecisionCreationTime(FileTime t) { pCreTime = t == null ? 0 : t.toMillis(); flags |= MZ_PrecisionTime; }
	public final void setPrecisionModificationTime(FileTime t) { pModTime = t == null ? 0 : t.toMillis(); modTime = t == null ? 0 : java2DosTime(t.toMillis()); flags |= MZ_PrecisionTime; }

	@Override
	public int getWinAttributes() {return attributes;}
	public void setWinAttributes(@MagicConstant(flagsFromClass = WinAttributes.class) int attributes) {this.attributes = attributes;}

	public final int getMethod() { return method; }
	public final void setMethod(@MagicConstant(intValues = {STORED, DEFLATED, LZMA}) int m) { this.method = (char) m; }

	public final int getCrc32() { return crc32; }

	public final boolean isEncrypted() { return (flags & GP_ENCRYPTED) != 0; }
	public final int getEncryptMethod() {
		if ((flags & GP_ENCRYPTED) == 0) return ENC_NONE;
		if ((flags & GP_STRONG_ENC) != 0) return /*CRYPT_UNKNOWN*/-1;
		if ((flags & MZ_AES) != 0) return (flags & MZ_AES2) != 0 ? ENC_AES_NOCRC : ENC_AES;
		return ENC_ZIPCRYPTO;
	}
	@SuppressWarnings("fallthrough")
	public final void setEncryptMethod(@MagicConstant(intValues = {ENC_NONE, ENC_ZIPCRYPTO, ENC_AES, ENC_AES_NOCRC}) int crypt) {
		if (crypt != ENC_NONE) {
			flags |= GP_ENCRYPTED;
			switch (crypt) {
				case ENC_ZIPCRYPTO: break;
				case ENC_AES_NOCRC: flags |= MZ_AES2;
				case ENC_AES: flags |= MZ_AES; break;
			}
		} else {
			flags &= ~(GP_ENCRYPTED|MZ_AES|MZ_AES2);
		}
	}

	public final int getGeneralPurposeFlags() { return flags; }
	public final void setGeneralPurposeFlags(int flags) { this.flags = flags; }

	final void readLOCExtra(ZipFile o, ByteList buf) {
		while (buf.readableBytes() > 4) {
			int id = buf.readUnsignedShortLE();
			int len = buf.readUnsignedShortLE();
			int end = buf.rIndex + len;
			switch (id) {
				case 0x5455 -> // high precision timestamp
						readHighPrecisionTime(buf, len);
				case 0x0001 -> {
					// LOC extra zip64 entry MUST include BOTH original
					// and compressed file size fields.
					// If invalid zip64 extra fields, simply skip. Even
					// it's rare, it's possible the entry size happens to
					// be the magic value and it "accidently" has some
					// bytes in extra match the id.
					if (len >= 16) {
						size = buf.readLongLE();
						compressedSize = buf.readLongLE();
					}
				}
				case 0x7075 -> // Info-ZIP Unicode Path
						readUnicodePath(buf, len, o);
				case 0x9901 -> // AE-x encryption structure
						readAES(buf, len);
			}
			buf.rIndex = end;
		}
	}
	final void writeLOCExtra(ByteList buf, int extOff, int extLenOff) {
		if (compressedSize >= U32_MAX || size >= U32_MAX) {
			if (size > U32_MAX) buf.setIntLE(extLenOff-6, (int) U32_MAX);
			if (compressedSize > U32_MAX) buf.setIntLE(extLenOff-10, (int) U32_MAX);

			buf.putShortLE(0x0001).putShortLE(16).putLongLE(size).putLongLE(compressedSize);
		}

		if ((flags & MZ_AES) != 0) {
			writeAES(buf);
			if ((flags & MZ_AES2) != 0) buf.setIntLE(extLenOff-14, 0);
		}

		if ((flags & MZ_PrecisionTime) != 0) writeHighPrecisionTime(buf);
		if ((flags & (GP_UFS|MZ_UniPath)) == MZ_UniPath) writeUnicodePath(buf);

		buf.setShortLE(extLenOff, extraLenOfLOC = (char) (buf.wIndex() - extOff));
	}

	final long readCENExtra(ZipFile o, ByteList buf, long header) {
		while (buf.readableBytes() > 4) {
			int id = buf.readUnsignedShortLE();
			int len = buf.readUnsignedShortLE();
			int end = buf.rIndex + len;
			switch (id) {
				case 0x5455 -> // Extended timestamp
						readHighPrecisionTime(buf, len);
				case 0x000A -> {
					while (buf.rIndex < end) {
						int k = buf.readUnsignedShortLE();
						DynByteBuf v = buf.slice(buf.readUnsignedShortLE());
						if (k == 1) {
							if (v.readableBytes() >= 8) pModTime = readWinTime(v.readLongLE());
							if (v.readableBytes() >= 8) pAccTime = readWinTime(v.readLongLE());
							if (v.readableBytes() >= 8) pCreTime = readWinTime(v.readLongLE());
							flags |= MZ_PrecisionTime;
						}
					} // NTFS timestamp
				}
				case 0x0001 -> {
					if (size == U32_MAX && len >= 8) {
						len -= 8;
						size = buf.readLongLE();
					}
					if (compressedSize == U32_MAX && len >= 8) {
						len -= 8;
						compressedSize = buf.readLongLE();
					}
					if (header == U32_MAX && len >= 8) {
						header = buf.readLongLE();
					} // Zip64 extended information
				}
				case 0x7075 -> readUnicodePath(buf, len, o);
				case 0x9901 -> readAES(buf, len);
			}
			if (buf.rIndex > end) throw new IllegalStateException("0x" + Integer.toHexString(id));
			buf.rIndex = end;
		}
		return header;
	}
	final void writeCENExtra(ByteList buf, int extLenOff, long offsetDelta) {
		// u2LE(0x01) u2LE(0x00)
		buf.putInt(0x01000000);
		int pos = buf.wIndex();

		int z64 = 0;
		if (size >= U32_MAX) {
			buf.setIntLE(extLenOff-6, (int) U32_MAX)
			   .putLongLE(size);
			z64++;
		}
		if (compressedSize >= U32_MAX) {
			buf.setIntLE(extLenOff-10, (int) U32_MAX)
			   .putLongLE(compressedSize);
			z64++;
		}
		long offset = startPos() + offsetDelta;
		if (offset >= U32_MAX) {
			buf.setIntLE(extLenOff+14, (int) U32_MAX)
			   .putLongLE(offset);
			z64++;
		}

		if (z64 > 0) {
			buf.setShortLE(pos-2, z64<<3);
		} else {
			buf.wIndex(buf.wIndex()-4);
		}

		if ((flags & MZ_PrecisionTime) != 0) writeHighPrecisionTime(buf);
		if ((flags & (GP_UFS|MZ_UniPath)) == MZ_UniPath) writeUnicodePath(buf);

		if ((flags & MZ_AES) != 0) {
			writeAES(buf);
			if ((flags & MZ_AES2) != 0) buf.setIntLE(extLenOff-14, 0);
		}
	}

	private void readUnicodePath(ByteList buf, int len, ZipFile o) {
		if(len >= 5) {
			int crc = CRC32.crc32(nameBytes, 0, nameBytes.length);

			buf.skipBytes(1);
			int expectedCrc = buf.readIntLE();
			len -= 5;

			if (crc == expectedCrc) {
				String name1 = buf.readUTF(len);
				if ((o.flags & (FLAG_Verify | FLAG_ReadCENOnly)) == FLAG_Verify && !name.equals(name1)) {
					throw new IllegalArgumentException("Extra7075: 字符集错误(以只读模式打开,或关闭验证)");
				}
				name = name1;
				flags |= MZ_UniPath;
			} else {
				if ((o.flags & FLAG_Verify) != 0) {
					throw new IllegalArgumentException("Extra7075: 文件名CRC校验错误: hex(name)=["+TextUtil.bytes2hex(nameBytes) +"], crc="+Integer.toHexString(expectedCrc));
				}
			}
		}
	}
	private void writeUnicodePath(ByteList buf) {
		int pos = buf.wIndex();
		buf.wIndex(pos+2);

		int crc = CRC32.crc32(nameBytes, 0, nameBytes.length);
		buf.put(0)
		   .putIntLE(crc)
		   .putUTFData(name)
		   .setShortLE(pos, buf.wIndex()-pos-2);
	}

	private void readAES(ByteList buf, int len) {
		if(len >= 7) {
			// 1 => AE-1, CRC presented, 2 => CRC set to 0
			int type = buf.readUnsignedShortLE();
			if (type == 2) flags |= MZ_AES2;
			else if (type != 1) flags |= MZ_Error;

			int vendor = buf.readUnsignedShortLE();
			if (vendor != 0x4541) flags |= MZ_Error;

			int algorithm = buf.readUnsignedByte();
			flags |= algorithm != 0x3 ? MZ_Error : MZ_AES;

			method = buf.readChar();
		}
	}
	private void writeAES(ByteList buf) {
		buf.putShortLE(0x9901).putShortLE(7)
		   .putShortLE((flags & MZ_AES2) != 0 ? 0x2 : 0x1)
		   .putShortLE(0x4541) // vendor id, ASCII"AE"
		   .put(0x3) // encryption strength: AES-256
		   .putShortLE(method);
	}

	private void readHighPrecisionTime(ByteList buf, int len) {
		int flag = buf.readUnsignedByte();

		if (flag != 0) flags |= MZ_PrecisionTime;

		if (len >= 4 && (flag&1) != 0) {
			pModTime = buf.readUnsignedIntLE()*1000;
			len -= 4;
		}
		if (len >= 4 && (flag&2) != 0) {
			pAccTime = buf.readUnsignedIntLE()*1000;
			len -= 4;
		}
		if (len >= 4 && (flag&4) != 0) {
			pCreTime = buf.readUnsignedIntLE()*1000;
		}
	}
	private void writeHighPrecisionTime(ByteList buf) {
		int len = 0;
		if (pModTime != 0) len += 8;
		if (pAccTime != 0) len += 8;
		if (pCreTime != 0) len += 8;
		if (len == 0) return;

		buf.putShortLE(0x000A).putShortLE(len+4).putShortLE(1).putShortLE(len);

		if (pModTime != 0) buf.putLongLE(java2WinTime(pModTime));
		if (pAccTime != 0) buf.putLongLE(java2WinTime(pAccTime));
		if (pCreTime != 0) buf.putLongLE(java2WinTime(pCreTime));
	}

	final boolean merge(ZipEntry cen) {
		if (method != cen.method ||
			compressedSize != cen.compressedSize ||
			size != cen.size ||
			offset != cen.offset ||
			((flags^cen.flags) & 0xFFFF) != 0 ||
			!name.equals(cen.name)) {
			return false;
		}

		if (crc32 == 0) crc32 = cen.crc32;
		else if (crc32 != cen.crc32) return false;

		if (cen.pModTime != 0) pModTime = cen.pModTime;
		if (cen.pAccTime != 0) pAccTime = cen.pAccTime;
		if (cen.pCreTime != 0) pCreTime = cen.pCreTime;
		attributes = cen.attributes;

		flags |= cen.flags;
		flags &= ~MZ_BACKWARD;
		return true;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (o == null || getClass() != o.getClass()) return false;

		ZipEntry file = (ZipEntry) o;
		return name.equals(file.name);
	}
	@Override
	public int hashCode() {return name.hashCode();}

	@Override
	public String toString() {
		StringBuilder sb = new StringBuilder().append("ZEntry{")
				.append("name='").append(name).append('\'')
				.append(", method=").append((int) method)
				.append(", crc=0x").append(Integer.toHexString(crc32))
				.append(", compressedSize=").append(TextUtil.scaledNumber1024(compressedSize))
				.append(", size=").append(TextUtil.scaledNumber1024(size))
				.append(", flags=0x").append(Integer.toHexString(flags));

		if (attributes != 0) sb.append(", attribute=0x").append(Integer.toHexString(attributes));
		if (nameBytes != null) sb.append(", offset=0x").append(Long.toHexString(startPos()));

		return sb.append('}').toString();
	}

	private static final int INVALID_DATE = (1 << 21) | (1 << 16);

	public static long dos2JavaTime(int dtime) {
		long day = DateFormat.daySinceUnixZero(((dtime >> 25) & 0x7f) + 1980, ((dtime >> 21) & 0x0f), (dtime >> 16) & 0x1f);
		long localTime = 86400000L * day + 3600_000L * ((dtime >> 11) & 0x1f) + 60_000L * ((dtime >> 5) & 0x3f) + 1000L * ((dtime << 1) & 0x3e);
		return localTime - TimeZone.getDefault().getOffset(localTime);
	}
	public static int java2DosTime(long time) {
		int[] arr = DateFormat.getCalendar(time + DateFormat.getLocalTimeZone().getOffset(time));
		int year = arr[DateFormat.YEAR] - 1980;
		if (year < 0 || year > 127) return INVALID_DATE;
		return (year << 25) | (arr[DateFormat.MONTH] << 21) | (arr[DateFormat.DAY] << 16) | (arr[DateFormat.HOUR] << 11) | (arr[DateFormat.MINUTE] << 5) | (arr[DateFormat.SECOND] >> 1);
	}

	@Override
	protected ZipEntry clone() {
		try {
			ZipEntry clone = (ZipEntry) super.clone();
			clone._next = null;
			return clone;
		} catch (CloneNotSupportedException e) {
			throw OperationDone.NEVER;
		}
	}
}