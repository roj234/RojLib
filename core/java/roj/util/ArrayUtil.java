package roj.util;

import roj.compiler.plugins.annotations.Attach;
import roj.reflect.Bypass;
import roj.reflect.Telescope;

import java.util.*;
import java.util.function.ToIntFunction;

import static roj.reflect.Unsafe.U;

/**
 * @author Roj234
 * @since 2020/10/15 0:43
 */
public final class ArrayUtil {
	public static byte[] newUninitializedByteArray(int size) {return (byte[]) U.allocateUninitializedArray(byte.class, size);}
	public static char[] newUninitializedCharArray(int size) {return (char[]) U.allocateUninitializedArray(char.class, size);}
	public static int[] newUninitializedIntArray(int size) {return (int[]) U.allocateUninitializedArray(int.class, size);}
	public static long[] newUninitializedLongArray(int size) {return (long[]) U.allocateUninitializedArray(long.class, size);}

	interface H {
		H INSTANCE = Bypass.builder(H.class).delegate(Telescope.findClass("jdk.internal.util.ArraysSupport"), "vectorizedMismatch").build();

		int vectorizedMismatch(Object a, long aOffset,
							   Object b, long bOffset,
							   int length,
							   int log2ArrayIndexScale);
	}

	public static boolean equals(Collection<String> a, Collection<String> b) {return a.size() == b.size() && a.containsAll(b);}

	@Attach
	public static <T> List<T> reverse(List<T> list, int offset, int count) {
		if (--count <= 0) return list;

		for (int mid = Math.max((count + 1) >> 1, 1); offset < mid; offset++) {
			T a = list.set(offset, list.get(count-offset));
			list.set(count-offset, a);
		}
		return list;
	}

	public static final int LOG2_ARRAY_BOOLEAN_INDEX_SCALE = 0;
	public static final int LOG2_ARRAY_BYTE_INDEX_SCALE = 0;
	public static final int LOG2_ARRAY_CHAR_INDEX_SCALE = 1;
	public static final int LOG2_ARRAY_SHORT_INDEX_SCALE = 1;
	public static final int LOG2_ARRAY_INT_INDEX_SCALE = 2;
	public static final int LOG2_ARRAY_LONG_INDEX_SCALE = 3;
	public static final int LOG2_ARRAY_FLOAT_INDEX_SCALE = 2;
	public static final int LOG2_ARRAY_DOUBLE_INDEX_SCALE = 3;

	public static int mismatch(Object a, long aOffset,
							   Object b, long bOffset,
							   int length,
							   int log2ArrayIndexScale) {
		int i = 0;
		// 当null时也就意味着是Java8 ... 无法确定处理器是否支持不对齐访问呢
		if (length > (8 >> log2ArrayIndexScale) - 1 && H.INSTANCE != null) {
			if (U.getByte(a, aOffset) != U.getByte(b, bOffset))
				return 0;
			i = H.INSTANCE.vectorizedMismatch(a, aOffset, b, bOffset, length, log2ArrayIndexScale);
			if (i >= 0) return i;
			i = length - ~i;
		}

		for (; i < length; i++) {
			if (U.getByte(a, aOffset+i) != U.getByte(b, bOffset+i))
				return i;
		}

		return -1;
	}

	public static String toString(Object[] a, int offset, int count) {
		if (count - offset <= 0) return "[]";
		StringBuilder sb = new StringBuilder().append('[');

		for (; offset < count; offset++) {
			sb.append(a[offset]).append(", ");
		}
		sb.delete(sb.length() - 2, sb.length());
		return sb.append(']').toString();
	}

	public static int byteHashCode(Object o, long off, int len) {
		if (len == 0) return 0;

		long end = off+len;
		int hash = 1;
		while (off < end) {
			hash = U.getByte(o, off++) + 31 * hash;
		}
		return hash;
	}

	public static <T> int binarySearchEx(List<T> list, ToIntFunction<T> comparator) { return binarySearchEx(list, 0, list.size(), comparator); }
	public static <T> int binarySearchEx(List<T> list, int low, int high, ToIntFunction<T> comparator) {
		high--;
		while (low <= high) {
			int mid = (low + high) >>> 1;
			T midVal = list.get(mid);
			int cmp = comparator.applyAsInt(midVal);
			if (cmp < 0) low = mid + 1;
			else if (cmp > 0) high = mid - 1;
			else return mid;
		}
		return -(low + 1);
	}

	public static void checkRange(byte[] b, int off, int len) {Objects.checkFromIndexSize(off, len, b.length);}

	private static final Class<?> IMMUTABLE_ARRAY_TYPE = Arrays.asList().getClass();
	@SuppressWarnings("unchecked")
	public static <T> List<T> immutableCopyOf(Collection<T> list) {
		if (list.isEmpty()) return Collections.emptyList();
		if (list.getClass() == IMMUTABLE_ARRAY_TYPE) return (List<T>) list;
		if (JVM.VERSION > 9) {
			return List.copyOf(list);
		} else {
			return (List<T>) Arrays.asList(list.toArray());
		}
	}
}