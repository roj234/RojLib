package roj.archive.algorithms.model;

import roj.archive.rangecoder.RangeDecoder;
import roj.archive.rangecoder.RangeEncoder;

import java.io.IOException;

/**
 * @author Roj234
 * @since 2025/10/23 07:41
 */
public interface EntropyModel {
	void reset();
	void encodeSymbol(RangeEncoder rc, int symbol) throws IOException;
	int decodeSymbol(RangeDecoder rc) throws IOException;
	default void free() {}
}
