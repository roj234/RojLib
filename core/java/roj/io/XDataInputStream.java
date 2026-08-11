package roj.io;

import org.jetbrains.annotations.NotNull;
import roj.io.source.Source;
import roj.reflect.Unsafe;
import roj.text.FastCharset;
import roj.util.ArrayCache;
import roj.util.ArrayUtil;
import roj.util.DynByteBuf;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static roj.reflect.Unsafe.U;

/**
 * @author Roj234
 * @since 2024/3/10 3:10
 */
public class XDataInputStream extends MBInputStream implements XDataInput, Finishable {
	public static XDataInput wrap(InputStream in) {
		if (in instanceof XDataInputStream d) return d;
		if (in instanceof DynByteBuf.BufferInputStream b) return b.buffer();
		return new XDataInputStream(in);
	}

	private final InputStream in;
	public XDataInputStream(InputStream in) {this.in = in;}
	public XDataInputStream(InputStream in, int bufferSize) {
		this.in = in;
		this.buf = new byte[bufferSize];
	}

	@Override
	public final int read(byte[] b, int off, int len) throws IOException {
		ArrayUtil.checkRange(b,off,len);
		int total = lim - pos;
		if (len <= total) {
			System.arraycopy(buf, pos, b, off, len);
			pos += len;
			return len;
		}
		System.arraycopy(buf, pos, b, off, total);
		pos = lim = 0;
		int r = in.read(b, off + total, len - total);
		if (r > 0) {
			totalRead += r;
			mark = -1;
		}
		return r < 0 ? total == 0 ? r : total : total+r;
	}
	@Override public final void readFully(byte[] b) throws IOException {IOUtil.readFully(this, b);}
	@Override public final void readFully(byte[] b, int off, int len) throws IOException {IOUtil.readFully(this, b, off, len);}

	@Override
	public final int skipBytes(int n) throws IOException {
		int total = lim - pos;
		if (n < total) {
			if (n < 0) return 0;
			pos += n;
			return n;
		}
		pos = lim = 0;

		int cur;
		while ((total<n) && ((cur = (int) in.skip(n-total)) > 0)) {
			total += cur;
			totalRead += cur;
		}

		return total;
	}

	public long skip(long n) throws IOException {
		long total = lim - pos;
		if (n < total) {
			if (n < 0) return 0;
			pos += n;
			return n;
		}
		pos = lim = 0;

		return in.skip(n-total)+total;
	}

	@Override
	public int available() throws IOException { return lim - pos + in.available(); }

	@Override
	public void finish() throws IOException {
		ArrayCache.putArray(buf);
		buf = ArrayCache.BYTES;
	}
	@Override
	public void close() throws IOException {
		finish();
		in.close();
	}

	private byte[] buf = ArrayCache.BYTES;
	private int pos, lim;
	private int doRead(int count) throws IOException {
		int l = lim;
		int o = pos;
		if (l - o < count) {
			byte[] dst = buf;
			if (dst.length < count) {
				ArrayCache.putArray(dst);
				dst = ArrayCache.getByteArray(Math.max(512, count), false);
			}

			System.arraycopy(buf, pos, dst, 0, l -= o);
			buf = dst;

			while (l < dst.length) {
				int r = in.read(dst, l, dst.length - l);
				if (r < 0) break;
				totalRead += r;
				l += r;
			}

			if (l < count) throw new EOFException("need="+count+",read="+l);
			pos = count;
			lim = l;
			mark = -1;
			return 0;
		} else {
			pos = o+count;
			return o;
		}
	}

	public void unread(byte[] b, int off, int len) {
		byte[] buf = this.buf;

		int immediateAvail = buf.length - lim;
		if (immediateAvail >= len) {
			System.arraycopy(b, off, buf, lim, len);
			lim += len;
			return;
		}

		int remaining = lim - pos;
		if (buf.length - remaining < len) {
			buf = ArrayCache.getByteArray(remaining + len, false);
		}

		System.arraycopy(this.buf, pos, buf, 0, remaining);
		System.arraycopy(b, off, buf, remaining, len);
		pos = 0;
		lim = remaining+len;

		if (this.buf != buf) {
			ArrayCache.putArray(this.buf);
			this.buf = buf;
		}
	}

	@Override public final boolean readBoolean() throws IOException {int i = doRead(1);return buf[i] != 0;}
	@Override public final byte readByte() throws IOException {int i = doRead(1);return buf[i];}
	@Override public final int readUnsignedByte() throws IOException {int i = doRead(1);return buf[i]&0xFF;}
	@Override public final short readShort() throws IOException {return (short) readUnsignedShort();}
	@Override public final char readChar() throws IOException {return (char) readUnsignedShort();}
	@Override public final int readUnsignedShort() throws IOException {int i = doRead(2);return U.get16UB(buf, Unsafe.ARRAY_BYTE_BASE_OFFSET + i);}
	@Override public final int readUnsignedShortLE() throws IOException {int i = doRead(2);return U.get16UL(buf, Unsafe.ARRAY_BYTE_BASE_OFFSET + i);}
	@Override
	public final int readMedium() throws IOException {
		int i = doRead(3);
		byte[] l = buf;
		return (l[i++] & 0xFF) << 16 | (l[i++] & 0xFF) << 8 | (l[i] & 0xFF);
	}
	@Override
	public final int readMediumLE() throws IOException {
		int i = doRead(3);
		byte[] l = buf;
		return (l[i++] & 0xFF)| (l[i++] & 0xFF) << 8 | (l[i] & 0xFF) << 16;
	}
	@Override public final int readInt() throws IOException {int i = doRead(4);return U.get32UB(buf, Unsafe.ARRAY_BYTE_BASE_OFFSET + i);}
	@Override public final int readIntLE() throws IOException {int i = doRead(4);return U.get32UL(buf, Unsafe.ARRAY_BYTE_BASE_OFFSET + i);}
	@Override public final long readUnsignedInt() throws IOException {return readInt() & 0xFFFFFFFFL;}
	@Override public final long readUnsignedIntLE() throws IOException {return readIntLE() & 0xFFFFFFFFL;}
	@Override public final long readLong() throws IOException {int i = doRead(8);return U.get64UB(buf, Unsafe.ARRAY_BYTE_BASE_OFFSET + i);}
	@Override public final long readLongLE() throws IOException {int i = doRead(8);return U.get64UL(buf, Unsafe.ARRAY_BYTE_BASE_OFFSET + i);}
	@Override public final float readFloat() throws IOException {return Float.intBitsToFloat(readInt());}
	@Override public final double readDouble() throws IOException {return Double.longBitsToDouble(readLong());}

	@Override
	public final int readVarInt() throws IOException {
		int value = 0;
		int i = 0;

		while (i <= 28) {
			int chunk = readByte();
			value |= (chunk & 0x7F) << i;
			i += 7;
			if ((chunk & 0x80) == 0) {
				if (value < 0) break;
				return value;
			}
		}
		throw new RuntimeException("VarInt太长");
	}


	@Override
	public final long readVarLong() throws IOException {
		long value = 0;
		int i = 0;

		while (i <= 63) {
			int chunk = readByte();
			value |= (long) (chunk & 0x7F) << i;
			i += 7;
			if ((chunk & 0x80) == 0) {
				if (value < 0) break;
				return value;
			}
		}

		throw new RuntimeException("VarLong太长");
	}

	@Override
	public final int readVUInt() throws IOException {
		int b = readUnsignedByte();
		if ((b&0x80) == 0) return b;
		if ((b&0x40) == 0) return ((b&0x3F)<< 8) | readUnsignedByte();
		if ((b&0x20) == 0) return ((b&0x1F)<<16) | readUnsignedShortLE();
		if ((b&0x10) == 0) return ((b&0x0F)<<24) | readMediumLE();
		if ((b&0x08) == 0) {
			if ((b&7) == 0)
				return readIntLE();
		}
		throw new RuntimeException("VUInt太长");
	}
	@Override
	public final long readVULong() throws IOException {
		int b = readUnsignedByte();

		if ((b&0x80) == 0) return b;
		if ((b&0x40) == 0) return ((b&0x3FL)<< 8) | readUnsignedByte();
		if ((b&0x20) == 0) return ((b&0x1FL)<<16) | readUnsignedShortLE();
		if ((b&0x10) == 0) return ((b&0x0FL)<<24) | readMediumLE();
		if ((b&0x08) == 0) return ((b&0x07L)<<32) | readUnsignedIntLE();
		if ((b&0x04) == 0) return ((b&0x03L)<<40) | readUnsignedIntLE() | (long) readUnsignedByte() << 32;
		if ((b&0x02) == 0) return ((b&0x01L)<<48) | readUnsignedIntLE() | (long) readUnsignedShortLE()     << 40;
		if ((b&0x01) == 0) return 					readUnsignedIntLE() | (long) readMediumLE()     << 48;
		return readLongLE();
	}

	@Override
	public final String readAscii(int len) throws IOException {
		int i = doRead(len);
		return new String(buf, i, len, StandardCharsets.ISO_8859_1);
	}

	@Override @NotNull public final String readUTF() throws IOException { return readUTF(readUnsignedShort()); }
	@Override public final String readVUIUTF() throws IOException { return readVUIUTF(DEFAULT_MAX_STRING_LEN); }
	@Override public final String readVUIUTF(int max) throws IOException { return readVUIStr(max, FastCharset.UTF8()); }
	@Override public final String readUTF(int len) throws IOException { return readStr(len, IOUtil.getSharedCharBuf(), FastCharset.UTF8()).toString(); }

	@Override public final String readVUIGB() throws IOException { return readVUIGB(DEFAULT_MAX_STRING_LEN); }
	@Override public final String readVUIGB(int max) throws IOException { return readVUIStr(max, FastCharset.GB18030()); }
	@Override public final String readGB(int len) throws IOException { return readStr(len, IOUtil.getSharedCharBuf(), FastCharset.GB18030()).toString(); }

	@Override public final String readVUIStr(FastCharset charset) throws IOException { return readVUIStr(DEFAULT_MAX_STRING_LEN, charset); }
	@Override public final String readVUIStr(int max, FastCharset charset) throws IOException {
		int len = readVUInt();
		if (len > max) throw new IllegalArgumentException("字符串长度不正确: "+len+" > "+max);
		return readStr(len, charset);
	}
	@Override public final String readStr(int len, FastCharset charset) throws IOException { return readStr(len, IOUtil.getSharedCharBuf(), charset).toString(); }
	@Override public final <T extends Appendable> T readStr(int len, T target, FastCharset charset) throws IOException {
		if (len < 0) throw new IllegalArgumentException("length="+len);
		if (len > 0) {
			int i = doRead(len);
			charset.decodeFixedIn(DynByteBuf.wrap(buf, i, len),len,target);
		}
		return target;
	}

	@Override
	public final String readLine() throws IOException {
		throw new UnsupportedOperationException("未实现...");
	}

	private long totalRead;
	@Override public long position() throws IOException {return totalRead + pos - lim;}

	public void seek(Source src, long pos) throws IOException {
		if (syncPosition(pos)) src.seek(pos);
	}

	public boolean syncPosition(long position) {
		long filePtr = totalRead;
		long bufferStart = filePtr - lim;

		if (position >= bufferStart && position < filePtr) {
			pos = (int) (position - bufferStart);
			return false;
		} else {
			pos = lim = 0;
			totalRead = position;
			return true;
		}
	}

	private int mark = -1;

	public boolean markSupported() {return true;}
	public void mark(int readlimit) {
		try {
			pos = doRead(readlimit);
			mark = pos;
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	@Override
	public void reset() throws IOException {
		if (mark < 0) throw new IOException("exceed readlimit");
		pos = mark;
	}

	@Override
	public boolean isReadable() {
		if (pos < 0) return false;

		try {
			pos = doRead(1);
			return true;
		} catch (Exception e) {
			pos = lim = -1;
			return false;
		}
	}
}