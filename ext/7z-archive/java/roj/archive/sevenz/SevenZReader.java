package roj.archive.sevenz;

import roj.io.CRC32InputStream;
import roj.io.IOUtil;
import roj.io.LimitInputStream;
import roj.io.source.Source;
import roj.io.source.SourceInputStream;
import roj.optimizer.FastVarHandle;
import roj.reflect.Telescope;

import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.lang.invoke.VarHandle;

/**
 * @author Roj233
 * @since 2024/1/8 23:37
 */
@FastVarHandle
public abstract sealed class SevenZReader implements Closeable permits SevenZFile, SevenZFile.ForkedReader {
	Source r, cache;
	static final VarHandle CACHE = Telescope.lookup().findVarHandle(SevenZReader.class, "cache", Source.class);

	byte flag;

	private SevenZEntry activeEntry;
	private LimitInputStream activeInputStream;

	SevenZReader() {}

	/**
	 * @see #getBlockInputStream(SevenZEntry, byte[])
	 */
	public final InputStream getInputStream(SevenZEntry entry) throws IOException { return getInputStream(entry, null); }
	/**
	 * Returns an input stream for the specified entry, optimized for sequential access to entries in the same block.
	 * This method reuses the active stream if possible, reducing overhead for ordered reads, but it is not thread-safe
	 * and does not support concurrent access to multiple entries.
	 * For concurrent access, use {@link #getConcurrentInputStream(SevenZEntry, byte[])}.
	 *
	 * @param entry the entry to read
	 * @param password optional password for encrypted entries (null if unencrypted)
	 * @return an input stream for the entry's uncompressed data
	 * @throws IOException if an I/O error occurs, the entry is encrypted without a password,
	 *                     or the data stream ends prematurely
	 */
	public final InputStream getInputStream(SevenZEntry entry, byte[] password) throws IOException {
		if (entry.uSize == 0) return new SourceInputStream(null, 0);

		// 优化顺序访问
		if (activeEntry != null && activeEntry.block == entry.block) {
			long skipSize = 0;
			SevenZEntry next = activeEntry.next;
			while (next != null) {
				if (next == entry) {
					activeEntry = entry;
					activeInputStream.remain += skipSize;
					// noinspection all
					activeInputStream.skip(activeInputStream.remain);
					if (activeInputStream.remain > 0)
						throw new EOFException("数据流过早终止");
					activeInputStream.remain = next.uSize;

					return withVerification(entry, activeInputStream);
				}
				skipSize += next.uSize;
				next = next.next;
			}
		}

		closeActiveStream();

		InputStream blockInput = getBlockInputStream(entry, password);
		LimitInputStream in = new LimitInputStream(blockInput, entry.uSize);
		if (entry.next != null) {
			activeEntry = entry;
			activeInputStream = in;
		} else {
			in.dispatchClose = true;
		}
		return withVerification(entry, in);
	}
	/**
	 * @see #getConcurrentInputStream(SevenZEntry, byte[])
	 */
	public final InputStream getConcurrentInputStream(SevenZEntry entry) throws IOException {return getConcurrentInputStream(entry, null);}
	/**
	 * Returns an input stream for the specified entry, suitable for limited concurrent access (e.g., a few threads).
	 * Each call opens an independent stream to the block, which may lead to resource overhead (e.g., multiple file handles)
	 * if used for many concurrent reads. For high-concurrency scenarios, use {@link SevenZFile#forkReader()}.
	 *
	 * @param entry the entry to read
	 * @param password optional password for encrypted entries (null if unencrypted)
	 * @return an input stream for the entry's uncompressed data
	 * @throws IOException if an I/O error occurs, the entry is encrypted without a password,
	 *                     or the data stream ends prematurely
	 */
	public final InputStream getConcurrentInputStream(SevenZEntry entry, byte[] password) throws IOException {
		if (entry.uSize == 0) return new SourceInputStream(null, 0);

		InputStream blockInput = getBlockInputStream(entry, password);
		LimitInputStream in = new LimitInputStream(blockInput, entry.uSize);
		return withVerification(entry, in);
	}

	private InputStream withVerification(SevenZEntry entry, LimitInputStream in) {
		if ((flag& SevenZFile.FLAG_RECOVERY) == 0 && (entry.flag& SevenZEntry.CRC) != 0)
			return new CRC32InputStream(in, entry.crc32);
		return in;
	}

	final void closeActiveStream() {
		var in = activeInputStream;
		if (in != null) {
			activeInputStream = null;
			IOUtil.closeSilently(in.unwrap());
		}
		activeEntry = null;
	}

	public final InputStream getBlockInputStream(WordBlock block, byte[] password) throws IOException {
		Source src = (Source) CACHE.getAndSet(this, null);
		if (src == null) src = r.copy();
		return getBlockInputStream(block, password, src, this, true);
	}

	final InputStream getBlockInputStream(SevenZEntry entry, byte[] password) throws IOException {
		Source src = (Source) CACHE.getAndSet(this, null);
		if (src == null) src = r.copy();
		InputStream blockInput = getBlockInputStream(entry.block, password, src, this, false);
		if (blockInput.skip(entry.offset) < entry.offset) {
			blockInput.close();
			throw new EOFException("数据流过早终止");
		}
		return blockInput;
	}
	abstract InputStream getBlockInputStream(WordBlock block, byte[] password, Source src, SevenZReader self, boolean verify) throws IOException;
}