package roj.io.source;

import roj.io.XDataInput;
import roj.reflect.Unsafe;
import roj.util.ByteList;
import roj.util.DynByteBuf;

import java.io.IOException;
import java.io.InputStream;

/**
 * @author Roj233
 * @since 2021/8/18 13:36
 */
public class ByteSource extends Source {
	private final DynByteBuf list;
	private int cap;

	public ByteSource() { list = new ByteList(); }
	public ByteSource(byte[] arr) { list = ByteList.wrap(arr); cap = arr.length; }
	public ByteSource(DynByteBuf bytes) { list = bytes; cap = bytes.readableBytes(); }

	public int read() { return list.isReadable() ? list.readUnsignedByte() : -1; }
	public int read(byte[] b, int off, int len) {
		int i = list.readableBytes();
		if (i <= 0) return -1;

		len = Math.min(len, i);
		list.readFully(b, off, len);
		return len;
	}

	public void write(byte[] b, int off, int len) throws IOException {
		list.wIndex(list.rIndex);
		list.put(b, off, len);
		cap = Math.max(list.rIndex = list.wIndex(), cap);
		list.wIndex(cap);
	}
	public void write(DynByteBuf data) throws IOException {
		list.wIndex(list.rIndex);
		list.put(data);
		cap = Math.max(list.rIndex = list.wIndex(), cap);
		list.wIndex(cap);
	}

	public void seek(long pos) throws IOException {
		if (pos > Integer.MAX_VALUE - 16) throw new IOException("Index "+pos+" too large");
		list.rIndex = (int) pos;
	}
	public long position() { return list.rIndex; }

	public void setLength(long length) throws IOException {
		if (length < 0 || length > Integer.MAX_VALUE - 16) throw new IOException("Index "+length+" too large");
		list.ensureCapacity(cap = (int) length);
		list.wIndex(cap);
	}
	public long length() { return cap; }

	public void reopen() {}

	public XDataInput asDataInput() { return list; }
	public InputStream asInputStream() { return list.asInputStream(); }

	public Source copy() { return new ByteSource(buffer()); }

	@Override
	public void moveSelf(long from, long to, long length) {
		if ((from | to | length) < 0) throw new IllegalArgumentException();
		Unsafe.U.copyMemory(list.array(), list._unsafeAddr()+from, list.array(), list._unsafeAddr()+to, length);
	}

	@Override
	public boolean isBuffered() { return true; }

	public DynByteBuf buffer() {return list.slice(0, cap);}

	@Override
	public String toString() { return "MemorySource@"+System.identityHashCode(list); }

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (o == null || getClass() != o.getClass()) return false;

		ByteSource source = (ByteSource) o;

		if (cap != source.cap) return false;
		return list == source.list;
	}

	@Override
	public int hashCode() {
		int result = System.identityHashCode(list);
		result = 31 * result + cap;
		return result;
	}
}