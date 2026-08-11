package roj.io.source;

import roj.util.DynByteBuf;

import java.io.DataInput;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;

/**
 * 缓存: 能随大小切换内存和磁盘缓存，并在close时清空数据
 * @author Roj234
 * @since 2024/1/18 19:40
 */
public class CacheSource extends Source {
	private volatile Source source;
	private final File next;
	private final int maxLength;
	private String prefix;
	public CacheSource() { this(0, Integer.MAX_VALUE); }
	public CacheSource(int initMem, int maxMemory) { this(initMem, maxMemory, null, null); }
	public CacheSource(int initMem, int maxMemory, String prefix, File folder) {
		this.source = new ByteSource(DynByteBuf.allocateDirect(initMem, maxMemory));
		this.maxLength = maxMemory;
		this.next = folder;
		this.prefix = prefix;
	}

	public int read(byte[] b, int off, int len) throws IOException { return source.read(b, off, len); }
	public void write(byte[] b, int off, int len) throws IOException {
		if (prefix != null && source.position()+len > maxLength) convert();
		source.write(b, off, len);
	}
	public void write(DynByteBuf data) throws IOException {
		int len = data.readableBytes();
		if (prefix != null && source.position()+len > maxLength) convert();
		source.write(data);
	}

	public void flush() throws IOException { source.flush(); }
	public void seek(long pos) throws IOException { if (prefix != null && pos > maxLength) convert(); source.seek(pos); }
	public long position() throws IOException { return source.position(); }
	public void setLength(long length) throws IOException { if (prefix != null && length > maxLength) convert(); source.setLength(length); }
	public long length() throws IOException { return source.length(); }
	public DynByteBuf buffer() { return source.buffer(); }
	public synchronized void close() throws IOException {
		if (source == null) return;

		source.close();
		if (source instanceof FileSource fs) Files.deleteIfExists(fs.getFile().toPath());
		else source.buffer().release();
		source = null;
	}
	/*public void reopen() throws IOException { source.reopen(); }*/
	public DataInput asDataInput() { return source.asDataInput(); }
	public InputStream asInputStream() { return source.asInputStream(); }
	public Source copy() throws IOException { throw new UnsupportedOperationException("unexpected operation"); }
	public void moveSelf(long from, long to, long length) throws IOException { source.moveSelf(from, to, length); }
	public boolean isBuffered() { return source.isBuffered(); }

	private synchronized void convert() throws IOException {
		FileSource fs = new FileSource(File.createTempFile(prefix, ".tmp", next));
		var buffer = source.buffer();
		fs.write(buffer);
		source = fs;
		buffer.release();
		prefix = null;
	}
}