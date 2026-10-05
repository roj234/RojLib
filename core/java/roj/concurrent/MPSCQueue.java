package roj.concurrent;

import org.jetbrains.annotations.Nullable;
import roj.io.BufferPool;
import roj.reflect.Telescope;
import roj.util.ByteList;
import roj.util.DynByteBuf;

import java.lang.invoke.VarHandle;
import java.util.concurrent.locks.LockSupport;

/**
 * 一个适合【多线程写入，单线程读取】的无界FIFO队列
 * 多线程读取现在会出bug……除非把recycleSize设为0
 * @author Roj234
 * @since 2023/5/17 18:48
 */
public final class MPSCQueue extends FIFOQueue<MPSCQueue.Entry> {
	static final class Entry extends FIFOQueue.Node {
		volatile DynByteBuf buffer;
		volatile Thread waiter;
	}

	private static final VarHandle SIZE = Telescope.lookup().findVarHandle(MPSCQueue.class, "size", int.class);
	private static final VarHandle RECYCLE = Telescope.lookup().findVarHandle(MPSCQueue.class, "recycle", Node.class);

	private volatile int recycleSize;
	private volatile Node recycle;

	public MPSCQueue() {recycleSize = 4;}
	public MPSCQueue(int maxUnused) {this.recycleSize = maxUnused;}

	@Override
	protected void recycle(Node node) {
		if (node instanceof Entry && recycleSize > 0) {
			SIZE.getAndAdd(this, -1);
			node.next = (Node)RECYCLE.getAndSet(this, node);
		}
	}
	private Entry retain() {
		while (true) {
			var bin = recycle;
			if (bin != null) {
				if (RECYCLE.compareAndSet(this, bin, bin.next)) {
					SIZE.getAndAdd(this, 1);
					return (Entry) bin;
				}
			} else {
				return null;
			}
		}
	}

	public void offer(DynByteBuf b) {doOffer(b, false);}
	public void offerSync(DynByteBuf b) {doOffer(b, true);}
	private boolean doOffer(DynByteBuf b, boolean wait) {
		var entry = retain();
		if (entry == null) entry = new Entry();

		entry.buffer = BufferPool.buffer(true, b.readableBytes()).put(b);
		Thread waiter = wait ? Thread.currentThread() : null;
		entry.waiter = waiter;

		VarHandle.releaseFence();

		addLast(entry);

		if (wait) while (entry.waiter == waiter) LockSupport.park(this);

		return true;
	}

	@Nullable
	public DynByteBuf take(DynByteBuf b) {return removeWith(b, true);}
	public boolean mayTake(DynByteBuf b) { return removeWith(b, false) != null; }

	private DynByteBuf removeWith(DynByteBuf buf, boolean must) {
		Entry entry;
		while (true) {
			entry = peek();
			if (entry == null) return null;

			var data = entry.buffer;
			if (data == null) continue;
			if (buf.writableBytes() < data.readableBytes() && !must) return null;

			if (removeIf(entry) != null) break;
		}

		var data = entry.buffer;
		entry.buffer = null;
		assert data != null;

		try {
			if (buf.writableBytes() < data.readableBytes()) {
				buf = new ByteList(data.toByteArray());
			} else {
				buf.put(data);
			}
		} finally {
			data.release();
		}

		var thread = entry.waiter;
		entry.waiter = null;
		LockSupport.unpark(thread);

		VarHandle.releaseFence();

		return buf;
	}

	public boolean isEmpty() {return peek() == null;}

	public void clear() {
		while (true) {
			var entry = removeFirst();
			if (entry == null) break;

			var data = entry.buffer;
			entry.buffer = null;
			assert data != null;
			data.release();

			var thread = entry.waiter;
			entry.waiter = null;
			LockSupport.unpark(thread);
		}

		recycle = null;
	}
}