package roj.util;

import org.jetbrains.annotations.NotNull;
import roj.compiler.plugins.eval.Constexpr;
import roj.io.BufferPool;
import roj.math.MathUtils;
import roj.reflect.Unsafe;
import roj.text.CharList;
import roj.text.TextUtil;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.BufferOverflowException;
import java.nio.BufferUnderflowException;
import java.nio.ReadOnlyBufferException;
import java.nio.charset.StandardCharsets;
import java.util.function.IntUnaryOperator;

import static roj.reflect.Unsafe.U;

/**
 * @author Roj234
 * @since 2021/5/29 20:45
 */
public class ByteList extends DynByteBuf implements Appendable {
	public static final ByteList EMPTY = new Slice(ArrayCache.BYTES, 0, 0);

	public byte[] list;

	public ByteList() { list = ArrayCache.BYTES; }
	public ByteList(int len) { list = new byte[len]; }

	public ByteList(byte[] array) {
		list = array;
		wIndex = array.length;
	}

	public synchronized void release(int count) {
		clear();
		byte[] b = list;
		// REMIND: b.length can be zero, but ArrayCache rejects it
		list = ArrayCache.BYTES;
		ArrayCache.putArray(b);
	}

	public int capacity() { return list.length; }
	public int maxCapacity() { return Integer.MAX_VALUE - 16; }
	public final boolean isDirect() { return false; }
	public long _unsafeAddr() { return (long) Unsafe.ARRAY_BYTE_BASE_OFFSET+arrayOffset(); }
	public boolean hasArray() { return true; }
	public byte[] array() { return list; }
	public int arrayOffset() { return 0; }

	@Override
	public final void copyTo(long address, int len) {
		DirectByteList.copyFromArray(list, Unsafe.ARRAY_BYTE_BASE_OFFSET, arrayOffset() + preRead(len), address, len);
	}

	public void clear() { wIndex = rIndex = 0; }

	public void ensureCapacity(int required) {
		if (required < 0) throw new IllegalArgumentException("required capacity must >= 0: "+required);
		if (required > list.length) {
			int newLen = Math.max(MathUtils.nextPowerOfTwo(required), 1024);
			if (newLen > 1073741823 || newLen > maxCapacity()) newLen = maxCapacity();
			if (newLen <= list.length) throw new IndexOutOfBoundsException("cannot hold "+required+" bytes in this buffer("+list.length+"/"+newLen+")");

			byte[] newList = ArrayCache.getByteArray(newLen, false);

			if (wIndex > 0) System.arraycopy(list, 0, newList, 0, wIndex);
			ArrayCache.putArray(list);
			list = newList;
		}
	}

	public ByteList setArray(byte[] array) {
		if (array == null) array = ArrayCache.BYTES;

		list = array;
		clear();
		wIndex = array.length;
		return this;
	}

	// region Byte Streams

	public final ByteList readStreamFully(InputStream in) throws IOException {
		return readStreamFully(in, true);
	}

	public final ByteList readStreamFully(InputStream in, boolean close) throws IOException {
		while (true) {
			if (wIndex == capacity()) {
				int r = in.read();
				if (r < 0) break;
				ensureCapacity(wIndex + Math.max(1, in.available()));
				list[arrayOffset()+wIndex++] = (byte) r;
			}

			int r = in.read(list, arrayOffset()+wIndex, capacity()-wIndex);
			if (r < 0) break;
			wIndex += r;
		}

		if (close) in.close();
		return this;
	}

	public final int readStream(InputStream in, int max) throws IOException {
		int read = wIndex;
		while (max > 0) {
			if (wIndex == capacity()) ensureCapacity(wIndex+max);

			int r = in.read(list, arrayOffset()+wIndex, Math.min(capacity()-wIndex, max));
			if (r < 0) break;
			wIndex += r;
			max -= r;
		}
		return wIndex-read;
	}

	public final void writeToStream(OutputStream os) throws IOException {
		int w = readableBytes();
		if (w > 0) os.write(list, arrayOffset()+rIndex, w);
	}

	// endregion
	// region PUTxxx

	public final ByteList put(int x) {int i = preWrite(1)+arrayOffset();list[i] = (byte) x;return this;}
	public final ByteList set(int offset, int x) {list[testWI(offset, 1)+arrayOffset()] = (byte) x;return this;}

	public ByteList put(byte[] b, int off, int len) {
		if (len < 0 || off < 0 || len > b.length - off) throw new ArrayIndexOutOfBoundsException();
		if (len > 0) {
			int off1 = arrayOffset() + preWrite(len);
			System.arraycopy(b, off, list, off1, len);
		}
		return this;
	}

	@Override
	public final ByteList put(DynByteBuf b, int len) {return put(b, b.rIndex, len);}
	@Override
	public ByteList put(DynByteBuf b, int off, int len) {
		if (off+len > b.wIndex) throw new IndexOutOfBoundsException();
		ensureCapacity(wIndex+len);
		b.readFully(off, list, wIndex+arrayOffset(), len);
		wIndex += len;
		return this;
	}
	public DynByteBuf set(int wi, DynByteBuf b, int off, int len) {
		wi = testWI(wi, len)+arrayOffset();
		b.readFully(off, list, wi, len);
		return this;
	}

	@Override
	public DynByteBuf set(int wi, byte[] b, int off, int len) {
		ArrayUtil.checkRange(b, off, len);
		wi = testWI(wi, len)+arrayOffset();
		System.arraycopy(b, off, list, wi, len);
		return this;
	}

	public final ByteList setChars(int wi, CharSequence s) {
		wi = testWI(wi, s.length() << 1)+arrayOffset();
		byte[] list = this.list;
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			list[wi++] = (byte) (c >>> 8);
			list[wi++] = (byte) c;
		}
		return this;
	}

	@SuppressWarnings("deprecation")
	final void writeJavaUTF(String s, int byteLen) {
		int wi = preWrite(byteLen);

		// better on Java 9 and later
		if (byteLen == s.length()) {
			s.getBytes(0,byteLen,list,arrayOffset()+wi);
			return;
		}

		jutf8_encode_all(s, list, _unsafeAddr()+wi);
	}

	public final ByteList putUTFData(CharSequence s) { return (ByteList) super.putUTFData(s); }

	public final ByteList putAscii(CharSequence s) { return setAscii(preWrite(s.length()), s); }
	@SuppressWarnings("deprecation")
	public final ByteList setAscii(int wi, CharSequence s) {
		wi = testWI(wi, s.length())+arrayOffset();
		if (s.getClass() == String.class) {
			s.toString().getBytes(0, s.length(), list, wi);
		} else {
			byte[] list = this.list;
			for (int i = 0; i < s.length(); i++) {
				list[wi++] = (byte) s.charAt(i);
			}
		}
		return this;
	}

	public final ByteList append(char c) {return put(c);}
	public final ByteList append(CharSequence s) { return append(s,0, s.length()); }
	@SuppressWarnings("deprecation")
	public final ByteList append(CharSequence s, int start, int end) {
		int wi = end-start;
		if ((wi|start|(s.length()-end)) < 0) throw new IndexOutOfBoundsException();
		wi = preWrite(wi);
		if (s.getClass() == String.class) {
			s.toString().getBytes(start, end, list, wi);
		} else {
			byte[] list = this.list;
			while (start < end) {
				list[wi++] = (byte) s.charAt(start++);
			}
		}
		return this;
	}

	// see DPSSecurityManager for why final
	public final byte[] toByteArray() {
		byte[] b = new byte[wIndex - rIndex];
		System.arraycopy(list, arrayOffset() + rIndex, b, 0, b.length);
		return b;
	}
	public final byte[] toByteArrayAndZero() {
		byte[] array = toByteArray();
		byte[] arr1 = list;
		for (int i = 0; i < wIndex; i++) arr1[i] = 0;
		return array;
	}
	public final void clearAndZero() {
		byte[] arr1 = list;
		for (int i = 0; i < wIndex; i++) arr1[i] = 0;
		wIndex = 0;
	}
	public final byte[] toByteArrayAndFree() {
		byte[] array = toByteArray();
		release();
		return array;
	}

	@Override
	public void preInsert(int off, int len) {
		byte[] tmp = list;
		if (tmp.length < wIndex+len) {
			if (immutableCapacity()) throw new BufferOverflowException();
			tmp = ArrayCache.getByteArray(wIndex+len, false);
		}

		if (wIndex != off) {
			if (list != tmp) System.arraycopy(list, 0, tmp, 0, off);

			System.arraycopy(list, off, tmp, off+len, wIndex-off);
		}
		wIndex += len;
		list = tmp;
	}

	// endregion
	// region GETxxx

	public final void readFully(byte[] b, int off, int len) {
		ArrayUtil.checkRange(b, off, len);
		if (len > 0) System.arraycopy(list, preRead(len) + arrayOffset(), b, off, len);
	}

	public final void readFully(int i, byte[] b, int off, int len) {
		ArrayUtil.checkRange(b, off, len);
		if (len > 0) System.arraycopy(list, testWI(i, len)+arrayOffset(), b, off, len);
	}

	public final byte getByte(int i) {return list[testWI(i, 1)+arrayOffset()];}
	@Override public final byte readByte() {return list[preRead(1)+arrayOffset()];}

	public final int readVarInt() {
		int value = 0;
		int i = 0;

		byte[] list = this.list;
		int off = arrayOffset() + rIndex;
		int len = arrayOffset() + wIndex;
		while (i <= 28) {
			if (off >= len) throw new BufferUnderflowException();

			int chunk = list[off++];
			rIndex++;
			value |= (chunk & 0x7F) << i;
			i += 7;
			if ((chunk & 0x80) == 0) {
				if (value < 0) break;
				return value;
			}
		}

		throw new RuntimeException("VarInt format error near " + rIndex);
	}

	public final String getAscii(int i, int len) {return new String(list, testWI(i, len)+arrayOffset(), len, StandardCharsets.ISO_8859_1);}

	@Override
	public final String readLine() {
		int i = rIndex + arrayOffset();
		int len = wIndex + arrayOffset();
		byte[] l = list;
		while (i < len) {
			byte b = l[i++];
			if (b == '\r' || b == '\n') {
				if (b == '\r' && i < wIndex && l[i] == '\n') i++;
				break;
			}
		}
		String s = new String(l, rIndex, i - rIndex, StandardCharsets.ISO_8859_1);
		rIndex = i - arrayOffset();
		return s;
	}

	public final int readCString(int max) {
		int i = rIndex + arrayOffset();
		int end = Math.min(i+max, wIndex+arrayOffset());
		byte[] l = list;
		while (i < end) {
			if (l[i] == 0) return i - arrayOffset() - rIndex;
			i++;
		}
		return max;
	}

	public final void forEachByte(IntUnaryOperator operator) {
		byte[] b = list;
		int i = rIndex + arrayOffset();
		int e = wIndex + arrayOffset();
		while (i < e) {
			int v = operator.applyAsInt(b[i]);
			b[i] = (byte) v;

			i++;
		}
	}

	// endregion
	// region Buffer Ops

	public ByteList slice(int off, int len) {
		return len == 0 ? EMPTY : new Slice(list, testWI(off, len)+arrayOffset(), len);
	}
	public final ByteList sliceNoIndexCheck(int off, int len) {return new Slice(list, off, len);}
	@Override
	public final DynByteBuf copySlice() {
		int length = wIndex - rIndex;
		byte[] b = ArrayCache.getByteArray(length, false);
		System.arraycopy(list, arrayOffset() + rIndex, b, 0, b.length);

		var result = new ByteList(b);
		result.wIndex = length;
		return result;
	}


	@Override
	public final ByteList compact() {
		if (rIndex > 0) {
			System.arraycopy(list, arrayOffset() + rIndex, list, arrayOffset(), wIndex -= rIndex);
			rIndex = 0;
		}
		return this;
	}

	@Override
	public String dump() {
		return "HeapBuffer:"+TextUtil.dumpBytes(list, arrayOffset()+rIndex, arrayOffset()+wIndex);
	}

	// endregion

	@Constexpr
	public static int FOURCC(CharSequence x) { return ((x.charAt(0) & 0xFF) << 24) | ((x.charAt(1) & 0xFF) << 16) | ((x.charAt(2) & 0xFF) << 8) | ((x.charAt(3) & 0xFF)); }
	@Constexpr
	public static String UNFOURCC(int fc) { return new String(new char[]{(char) (fc >>> 24), (char) ((fc >>> 16) & 0xFF), (char) ((fc >>> 8) & 0xFF), (char) (fc & 0xFF)}); }

	static void jutf8_encode_all(String s, Object ref, long addr) {
		int i = 0, len = s.length();
		while (i < len) {
			int c = s.charAt(i++);

			if (c == 0 || c > 0x7F) {
				if (c <= 0x7FF) {
					U.putByte(ref, addr++, (byte) (0xC0 | ((c >> 6) & 0x1F)));
					U.putByte(ref, addr++, (byte) (0x80 | (c & 0x3F)));
				} else {
					U.putByte(ref, addr++, (byte) (0xE0 | ((c >> 12) & 0x0F)));
					U.putByte(ref, addr++, (byte) (0x80 | ((c >> 6) & 0x3F)));
					U.putByte(ref, addr++, (byte) (0x80 | (c & 0x3F)));
				}
			} else {
				U.putByte(ref, addr++, (byte) c);
			}
		}
	}

	@Override
	@NotNull
	@SuppressWarnings("deprecation")
	public String toString() {return new String(list, 0, arrayOffset()+rIndex, wIndex-rIndex);}

	@Override public CharList hex(CharList sb) {return TextUtil.bytes2hex(list, arrayOffset()+rIndex, arrayOffset()+wIndex, sb);}

	public static final class ToStream extends ByteList {
		private OutputStream out;
		private int fakeWriteIndex;

		private DynByteBuf buf;
		private boolean dispatchClose;

		public ToStream(OutputStream out) { this(out, true); }
		public ToStream(OutputStream out, boolean dispatchClose) {
			this.out = out;
			this.buf = BufferPool.buffer(false, 1024);
			this.list = buf.array();
			this.dispatchClose = dispatchClose;
		}

		@Override
		public long totalWritten() {flush();return fakeWriteIndex;}
		@Override
		public int wIndex() {flush();return fakeWriteIndex;}
		public void wIndex(int w) { throw a(); }

		public final boolean isReal() { return false; }
		public final int arrayOffset() { return buf.arrayOffset(); }
		public final int capacity() { return buf.capacity(); }
		public final int maxCapacity() { return buf.maxCapacity(); }

		@Override
		public ByteList put(byte[] b, int off, int len) {
			if (len < buf.capacity()) return super.put(b, off, len);
			flush();
			try {
				out.write(b, off, len);
			} catch (IOException e) {
				Helpers.athrow(e);
			}
			fakeWriteIndex += len;
			return this;
		}

		@Override
		public ByteList put(DynByteBuf b, int off, int len) {
			if (len < buf.capacity()) return super.put(b, off, len);
			flush();
			try {
				if (b.hasArray()) out.write(b.array(), b.arrayOffset()+off, len);
				else {
					while (len > 0) {
						int read = Math.min(len, capacity());
						b.readFully(off, list, arrayOffset(), read);
						out.write(list, arrayOffset(), read);

						off += read;
						len -= read;
					}
				}
			} catch (IOException e) {
				Helpers.athrow(e);
			}
			fakeWriteIndex += len;
			return this;
		}

		@Override
		public DynByteBuf set(int wi, DynByteBuf b, int off, int len) {
			throw new UnsupportedOperationException();
		}

		@Override
		public void ensureCapacity(int cap) {
			if (cap > buf.capacity()) {
				cap -= wIndex;
				flush();

				if (wIndex+cap > buf.capacity()) {
					buf = BufferPool.localPool().expand(buf, cap);
					list = buf.array();
				}
			}
		}

		final int preWrite(int i) {
			ensureCapacity(wIndex+i);
			int j = wIndex;
			wIndex = j+i;
			return j;
		}
		final int preRead(int i) { throw a(); }

		public final boolean hasArray() { return false; }
		public final ByteList setArray(byte[] array) { throw a(); }

		public void flush() {
			if (wIndex > 0) {
				if (out != null) {
					try {
						out.write(list, arrayOffset(), wIndex);
					} catch (IOException e) {
						Helpers.athrow(e);
					}
				}
				fakeWriteIndex += wIndex;
				wIndex = 0;
			}
		}

		public synchronized void close() throws IOException {
			try {
				if (out != null) {
					try {
						flush();
					} finally {
						if (dispatchClose) out.close();
						out = null;
					}
				}
			} finally {
				if (buf != null) {
					BufferPool.reserve(buf);
					buf = null;
				}
			}

		}

		private static UnsupportedOperationException a() { return new UnsupportedOperationException("stream buffer is not readable"); }
	}

	public static class Slice extends ByteList {
		protected int off, len;

		public Slice() {}
		public Slice(byte[] b, int off, int len) { setR(b, off, len); }

		public ByteList set(byte[] b, int off, int len) {
			assert this != EMPTY;
			ArrayUtil.checkRange(b, off, len);

			wIndex = rIndex = 0;

			list = b;
			this.off = off;
			this.len = len;

			return this;
		}

		public ByteList setR(byte[] array, int off, int len) {
			set(array,off,len).wIndex = len;
			return this;
		}

		@Override public int capacity() {return len;}
		@Override public int maxCapacity() {return len;}
		@Override public void ensureCapacity(int required) {
			if (required > len) throw new IndexOutOfBoundsException("cannot hold "+required+" bytes in this buffer("+len+")");
		}
		@Override public int arrayOffset() {return off;}
		@Override public ByteList setArray(byte[] array) {throw new ReadOnlyBufferException();}
	}
}