package roj.archive.algorithms.model;

import org.jetbrains.annotations.Range;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * @author Roj234
 * @since 2025/10/20 02:30
 */
public final class PPMdOptions {
	private int order;
	private int dictSize;

	private static final byte[] ORDER_BY_LEVEL  = {4,4,5,5,5,6,6,7,8,9};
	private static final byte[] ORDER_BY_LEVEL2 = {3,4,4,4,5,6,6,7,8,9};

	public PPMdOptions() {this(0);}
	public PPMdOptions(@Range(from = 0, to = 9) int preset) {setPreset(preset);}
	public PPMdOptions(@Range(from = PPMd7.PPMD7_MIN_ORDER, to = PPMd7.PPMD7_MAX_ORDER) int order,
					   @Range(from = PPMd7.PPMD7_MIN_MEM_SIZE, to = PPMd7.PPMD7_MAX_MEM_SIZE) int dictSize) {
		setOrder(order);
		setDictSize(dictSize);
	}

	public PPMdOptions setPreset(int preset) {
		if (preset == 0) preset = 5;

		preset--;
		dictSize = (1 << 20) << preset;
		order = ORDER_BY_LEVEL[preset];
		return this;
	}

	public byte getOrder() { return (byte) order; }
	public int getDictSize() { return dictSize; }

	public PPMdOptions setOrder(@Range(from = PPMd7.PPMD7_MIN_ORDER, to = PPMd7.PPMD7_MAX_ORDER) int order) { this.order = order; return this; }
	public PPMdOptions setDictSize(@Range(from = PPMd7.PPMD7_MIN_MEM_SIZE, to = PPMd7.PPMD7_MAX_MEM_SIZE) int dictSize) { this.dictSize = dictSize; return this; }

	public PPMd7 createModel() {
		var model = new PPMd7();
		model.alloc(dictSize);
		model.init(order);
		return model;
	}

	public InputStream getInputStream(InputStream in) throws IOException {return new EntropyModelInputStream(in, createModel());}
	public OutputStream getOutputStream(OutputStream out) {return new EntropyModelOutputStream(out, createModel());}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (o == null || getClass() != o.getClass()) return false;

		PPMdOptions that = (PPMdOptions) o;
		return order == that.order && dictSize == that.dictSize;
	}

	@Override
	public int hashCode() {
		int result = order;
		result = 31 * result + dictSize;
		return result;
	}
}