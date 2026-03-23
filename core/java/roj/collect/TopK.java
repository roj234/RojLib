package roj.collect;

import roj.util.Helpers;

import java.util.AbstractCollection;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Iterator;

/**
 * 一个用于维护前K个最小元素的集合（基于提供的比较器）。
 * 内部使用数组和二分查找来高效地维护元素顺序，确保始终保留最小的K个元素。
 *
 * @param <E> 集合中元素的类型
 * @author Roj234
 * @since 2024/7/12 8:50
 */
public final class TopK<E> extends AbstractCollection<E> {
	private final Comparator<E> comparator;
	private final Object[] elements;
	private int size;

	public TopK(int k, Comparator<E> comparator) {
		this.elements = new Object[k];
		this.comparator = comparator;
	}

	public Object[] toArray() {return elements;}
	public Iterator<E> iterator() {return Helpers.cast(new ArrayIterator<>(elements, 0, size));}
	public int size() {return size;}

	@SuppressWarnings("unchecked")
	public boolean add(E element) {
		if (size == elements.length && comparator.compare(element, (E) elements[size-1]) > 0) return false;
		int pos = Arrays.binarySearch((E[]) elements, 0, size, element, comparator);
		if (pos < 0) pos = -pos - 1;
		insertAt(pos, element);
		return true;
	}

	private void insertAt(int index, E e) {
		if (index < size) {
			int c = size;
			if (c == elements.length) c--;
			System.arraycopy(elements, index, elements, index + 1, c - index);
		}
		elements[index] = e;
		if (size < elements.length) size++;
	}

	public void clear() {
		for (int i = 0; i < size; i++) {
			elements[i] = null;
		}
		size = 0;
	}
}