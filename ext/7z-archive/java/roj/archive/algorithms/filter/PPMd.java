package roj.archive.algorithms.filter;

import roj.archive.algorithms.model.EntropyModelInputStream;
import roj.archive.algorithms.model.EntropyModelOutputStream;
import roj.archive.algorithms.model.PPMdOptions;
import roj.archive.sevenz.SevenZCodec;
import roj.archive.sevenz.SevenZCodecExtension;
import roj.util.DynByteBuf;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * @author Roj234
 * @since 2025/10/20 02:35
 */
@SevenZCodecExtension("030401")
public final class PPMd extends SevenZCodec {
	private static final byte[] ID = { 3, 4, 1 };
	static {register(ID, PPMd::new);}

	private final PPMdOptions options;

	public PPMd() {this(new PPMdOptions());}
	public PPMd(int level) {this(new PPMdOptions(level));}
	public PPMd(PPMdOptions options) {this.options = options;}

	private PPMd(DynByteBuf props) {
		var order = props.readByte();
		var dictSize = props.readIntLE();
		this.options = new PPMdOptions(order, dictSize);
	}

	public PPMdOptions getOptions() {return options;}

	@Override
	public byte[] id() { return ID; }

	@Override
	public OutputStream encode(OutputStream out) {return new EntropyModelOutputStream(out, options.createModel());}

	@Override
	public void writeOptions(DynByteBuf props) {props.put(options.getOrder()).putIntLE(options.getDictSize());}

	@Override
	public InputStream decode(InputStream in, byte[] password, long uncompressedSize, AtomicInteger memoryLimit) throws IOException {
		checkMemoryUsage(memoryLimit, getMemoryUsage(options.getDictSize()) >>> 10);
		return new EntropyModelInputStream(in, options.createModel());
	}

	// Memory estimation (call in decode; similar to LZMAInputStream.getMemoryUsage)
	private static int getMemoryUsage(int dictSize) {
		return Math.max(1 << 20, dictSize + (dictSize >> 4)); // ~dictSize + 6% overhead; min 1MB
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (o == null || getClass() != o.getClass()) return false;

		PPMd ppMd = (PPMd) o;
		return options.equals(ppMd.options);
	}

	@Override
	public int hashCode() {return options.hashCode()+1;}

	@Override
	public String toString() {return "PPMD:o"+options.getOrder()+":mem"+(options.getDictSize()>>>20);}
}