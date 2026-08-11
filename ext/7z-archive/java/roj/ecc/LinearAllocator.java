package roj.ecc;

import roj.util.ArrayUtil;

/**
 * @author Roj234
 * @since 2026/08/21 00:14
 */
public final class LinearAllocator {
	public final byte[] MEM;
	public int ptr;

	public LinearAllocator(int mem) {
		MEM = ArrayUtil.newUninitializedByteArray(mem);
	}

	public int alloc(int size) {
		int p = ptr;
		ptr += size;
		return p;
	}

	public void clear() {
		ptr = 0;
	}
}
