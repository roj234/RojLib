package roj.archive.algorithms.model;

import org.jetbrains.annotations.NotNull;
import roj.archive.rangecoder.RangeEncoderToStream;
import roj.io.Finishable;
import roj.io.MBOutputStream;
import roj.util.ArrayUtil;

import java.io.IOException;
import java.io.OutputStream;

/**
 * @author Roj234
 * @since 2025/10/24 21:46
 */
public final class EntropyModelOutputStream extends MBOutputStream implements Finishable {
	private final EntropyModel model;
	private final RangeEncoderToStream rc;
	private boolean finished;

	public EntropyModelOutputStream(OutputStream out, EntropyModel model) {
		this.model = model;
		this.rc = new RangeEncoderToStream(out);
	}

	@Override
	public void write(@NotNull byte[] b, int off, int len) throws IOException {
		ArrayUtil.checkRange(b, off, len);
		if (finished) throw new IOException("Stream is closed");

		int end = off + len;
		while (off < end) {
			model.encodeSymbol(rc, b[off++] & 0xFF);
		}
	}

	@Override
	public synchronized void finish() throws IOException {
		if (!finished) {
			model.free();
			rc.finish();
			finished = true;
		}
	}

	@Override
	public void close() throws IOException {
		finish();
		rc.out.close();
	}
}
