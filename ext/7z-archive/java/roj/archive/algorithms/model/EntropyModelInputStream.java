package roj.archive.algorithms.model;

import roj.archive.rangecoder.RangeDecoderFromStream;
import roj.io.Finishable;
import roj.io.MBInputStream;
import roj.util.ArrayUtil;

import java.io.IOException;
import java.io.InputStream;

/**
 * @author Roj234
 * @since 2025/10/24 21:46
 */
public final class EntropyModelInputStream extends MBInputStream implements Finishable {
	private final EntropyModel model;
	private final RangeDecoderFromStream rc;
	private boolean closed;

	public EntropyModelInputStream(InputStream in, EntropyModel model) throws IOException {
		this.model = model;
		this.rc = new RangeDecoderFromStream(in);
	}

	@Override
	public int read(byte[] b, int off, int len) throws IOException {
		ArrayUtil.checkRange(b, off, len);
		if (closed) return -1;
		if (len == 0) return 0;

		int pos = off;
		int end = off + len;

		while (pos < end) {
			int symbol = model.decodeSymbol(rc);
			if (symbol < 0) {
				finish();
				if (symbol == -1) break;
				throw new IOException(model+" decode error");
			}

			b[pos++] = (byte) symbol;
		}

		int i = pos - off;
		return i == 0 ? -1 : i;
	}

	public synchronized void finish() {
		if (!closed) {
			model.free();
			rc.finish();
			closed = true;
		}
	}

	@Override
	public void close() throws IOException {
		finish();
		rc.in.close();
	}
}
