package roj.concurrent;

import roj.collect.ArrayList;
import roj.optimizer.FastVarHandle;
import roj.reflect.Telescope;
import roj.text.CharList;
import roj.text.logging.Logger;

import java.lang.invoke.VarHandle;
import java.util.Collection;
import java.util.concurrent.locks.LockSupport;

/**
 * 处理定时任务
 * @author Roj234
 * @version 2.4
 * @since 2024/3/6 0:38
 */
@FastVarHandle
public class Timer implements Runnable {
	private static final Logger LOGGER = Logger.getLogger("定时器");

	private static volatile Timer defaultTimer;
	public static Timer getDefault() {
		if (defaultTimer == null) {
			synchronized (Timer.class) {
				if (defaultTimer != null) return defaultTimer;

				defaultTimer = new Timer(TaskPool.common());
				Thread t = new Thread(defaultTimer, "RojLib 定时器");
				t.setDaemon(true);
				t.start();
			}
		}
		return defaultTimer;
	}

	@FastVarHandle
	private static final class TaskHandle implements TimerTask {
		// root
		TaskHandle(TimingWheel wheel) {
			owner = wheel;
			prev = next = this;
		}
		TaskHandle(TimingWheel wheel, Runnable task, long delay) {
			owner = wheel;
			this.task = task;
			timeLeft = delay;
		}

		@Override
		public String toString() {
			var sb = new CharList().append("TimerTask{");
			if (timeLeft <= 0) return sb.append(timeLeft == -1 ? "cancelled" : "expired").append(",task=").append(task).append('}').toStringAndFree();
			return sb.append("queued,timeLeft=").append(timeLeft).append(",owner=").append(wheel()).append(",task=").append(task).append('}').toStringAndFree();
		}

		static final VarHandle
				NEXT = Telescope.lookup().findVarHandle(TaskHandle.class, "next", TaskHandle.class),
				TIME = Telescope.lookup().findVarHandle(TaskHandle.class, "timeLeft", long.class);

		// TimingWheel | TimerTask
		private Object owner;
		private TimingWheel wheel() {
			Object x = owner;
			return (TimingWheel) (x instanceof TaskHandle h ? h.owner : x);
		}

		TaskHandle prev, next;

		Runnable task;
		volatile long timeLeft;
		static final long WRITE_LOCK = 1L, READ_LOCK = 2L;

		/*@Override*/ public Timer timer() { return wheel().owner(); }
		@Override public Runnable task() { return task; }
		@Override public void reschedule(long delay) {
			for(;;) {
				long t = timeLeft;
				if (t < 0) break;
				if (TIME.weakCompareAndSet(this, t, -1L)) { removeFromTimer(); break; }
				Thread.yield();
			}

			timeLeft = System.currentTimeMillis()+delay;

			// 追加到单向链表
			TaskHandle head = (TaskHandle) HEAD.getAndSet(timer(), this);
			NEXT.setVolatile(this, head);
		}
		@Override public boolean isExpired() { return timeLeft == 0 || timeLeft == -2; }
		@Override public boolean isCancelled() { return timeLeft < 0; }
		@Override public boolean cancel(boolean mayInterruptIfRunning) {
			boolean taskCancelled;

			for(;;) {
				long t = timeLeft;
				if (t < 0) return true;

				if (TIME.compareAndSet(this, t, t == 0/*isExpired*/ ? -2L : -1L)) {
					taskCancelled = t == 0 && task instanceof Cancellable cancellable && cancellable.cancel(mayInterruptIfRunning);
					break;
				}

				Thread.yield();
			}

			return taskCancelled;
		}
		private boolean removeFromTimer() {
			if (!(owner instanceof TaskHandle root)) return false;

			boolean removed = false;

			// 尝试上锁，如果失败，意味着这个链表正在被遍历，那么由于 timeLeft<0 它马上就会被删除
			while (!TIME.compareAndSet(root, 0L, READ_LOCK)) {
				Thread.yield();
			}

			// critical zone
			if (prev != null) {
				prev.next = next;
				next.prev = prev;
				removed();
				removed = true;
			}
			// critical zone

			for (;;) {
				long t = root.timeLeft;
				if (TIME.compareAndSet(root, t, t & WRITE_LOCK)) break;
				Thread.yield();
			}

			return removed;
		}
		void removed() {
			prev = null;
			next = null;
			owner = wheel();
		}

		// 这个方法只会在任务计划线程调用
		boolean add(TaskHandle root) {
			if (isCancelled()) return false;

			// 如果是写锁，那么一定是当前线程
			boolean lock = root.timeLeft != WRITE_LOCK;
			if (lock) root.lock();

			// critical zone
			boolean shouldAdd = !isCancelled();
			if (shouldAdd) {
				var next = root.next;

				this.prev = root;
				this.next = next;

				next.prev = this;
				root.next = this;

				this.owner = root;
			}
			// critical zone

			if (lock) root.timeLeft = 0;
			return shouldAdd;
		}

		TaskHandle iter() {
			lock();
			var task = next;
			prev = next = this;
			return task;
		}
		private void lock() {
			for (;;) {
				var t = timeLeft;
				if (t == WRITE_LOCK) break;
				if (TIME.compareAndSet(this, t, t|WRITE_LOCK) && t == 0) break;
				Thread.yield();
			}
		}
	}

	private static final int DEPTH_SHL = 4, DEPTH_MASK = (1 << DEPTH_SHL) - 1;
	@FastVarHandle
	private final class TimingWheel {
		TimingWheel(TimingWheel prev) {
			this.prev = prev;
			this.slot = prev==null ? 0 : prev.slot+1;

			tasks = new TaskHandle[1 << DEPTH_SHL];
			for (int i = 0; i < tasks.length; i++)
				tasks[i] = new TaskHandle(this);
		}

		Timer owner() {return Timer.this;}

		private final TaskHandle[] tasks;
		private final int slot;
		private int tick;

		private final TimingWheel prev;
		// @Stable
		private volatile TimingWheel next;
		private static final VarHandle NEXT = Telescope.lookup().findVarHandle(TimingWheel.class, "next", TimingWheel.class);
		private TimingWheel next() {
			while (next == null) {
				TimingWheel next = new TimingWheel(this);
				if (NEXT.compareAndSet(this, null, next)) {
					return next;
				}
			}
			return next;
		}

		@Override
		public String toString() {
			var pos = 0;
			var clock = this;
			while (clock.next != null) {
				clock = clock.next;
				pos++;
			}

			var sb = new CharList().append("0x");
			while (clock != null) {
				if (pos == 0) sb.append('[');
				sb.append(Integer.toHexString(clock.tick));
				if (pos == 0) sb.append(']');

				clock = clock.prev;
				pos--;
			}
			return sb.toStringAndFree();
		}

		final void fastForward(int ticks, Collection<TaskHandle> reschedule, Executor pool) {
			int ff = ticks >>> (slot*DEPTH_SHL);
			int t = tick;
			tick = (t+ff) & DEPTH_MASK;
			if (ff > DEPTH_MASK) ff = DEPTH_MASK;

			for (int slot = t; slot < t+ff; slot++) {
				var root = tasks[slot&DEPTH_MASK];
				var task = root.iter();
				while (task != root) {
					var next = task.next;
					long time = task.timeLeft;
					task.removed();

					TaskWasCancelled:
					if (time > 0 && TaskHandle.TIME.compareAndSet(task, time, time = Math.max(0, time-ticks))) {
						if (time == 0) {
							time = safeExec(pool, task);
							if (time <= 0) break TaskWasCancelled;

							if (TaskHandle.TIME.compareAndSet(task, 0L, time)) reschedule.add(task);
						} else {
							add(prev, task);
						}
					}

					task = next;
				}

				root.timeLeft = 0;
			}

			if (next != null) next.fastForward(ticks, reschedule, pool);
		}

		final void tick(Executor pool) {
			int t = tick;
			if (t != DEPTH_MASK) {
				tick = t+1;
			} else {
				tick = 0;

				TimingWheel p = next;
				if (p != null) p.tick(pool);
			}

			var mask = (1L << (slot * DEPTH_SHL)) - 1;

			var root = tasks[t];
			var task = root.iter();

			while (task != root) {
				var next = task.next;
				long time = task.timeLeft;
				task.removed();

				TaskWasCancelled:
				if (time > 0 && TaskHandle.TIME.compareAndSet(task, time, time &= mask)) {
					if (time == 0) {
						time = safeExec(pool, task);
						if (time <= 0) break TaskWasCancelled;

						if (TaskHandle.TIME.compareAndSet(task, 0L, tweakTime(Timer.this.wheel, time))) {
							add(this, task);
						}
					} else {
						// 后序遍历
						add(prev, task);
					}
				}

				task = next;
			}

			root.timeLeft = 0;
		}

		static long safeExec(Executor exec, TaskHandle task) {
			try {
				var _task = task.task;
				long nextRun = _task instanceof PeriodicTask loop ? loop.getNextRun() : 0;
				if (nextRun >= 0) exec.execute(_task);
				return nextRun;
			} catch (Throwable e) {
				LOGGER.error("提交任务时发生了异常", e);
				return 0;
			}
		}

		static long tweakTime(TimingWheel wheel, long time) {
			assert wheel.prev == null;

			int i = 0;
			while (true) {
				int slot = (63 - Long.numberOfLeadingZeros(time)) / DEPTH_SHL;
				if (i >= slot) break;
				i++;

				time += (long) wheel.tick << (DEPTH_SHL * wheel.slot);
				wheel = wheel.next;
				if (wheel == null) break;
			}

			return time;
		}
		static void add(TimingWheel wheel, TaskHandle task) {
			long time = task.timeLeft;
			if (time <= 0) return;

			int slot = (63 - Long.numberOfLeadingZeros(time)) / DEPTH_SHL;

			int delta = wheel.slot - slot;
			if (delta != 0) {
				if (delta < 0) {
					while (delta++ < 0) wheel = wheel.next();
				} else {
					while (delta-- > 0) wheel = wheel.prev;
				}
			}

			int i = wheel.tick + ((int) (time >> (DEPTH_SHL*slot)) & DEPTH_MASK) - 1;
			var root = wheel.tasks[i & DEPTH_MASK];
			task.add(root);
		}

		final void collect(Collection<TaskHandle> collector) {
			for (TaskHandle root : tasks) {
				var task = root.iter();
				while (task != root) {
					if (task.timeLeft > 0) collector.add(task);

					var next = task.next;
					task.removed();
					task = next;
				}

				root.timeLeft = 0;
			}

			if (next != null) next.collect(collector);
		}
	}

	private static final VarHandle HEAD = Telescope.lookup().findVarHandle(Timer.class, "head", TaskHandle.class);

	private final TimingWheel wheel = new TimingWheel(null);
	private volatile boolean stopped;
	private final Executor executor;

	private static final TaskHandle SENTIAL_HEAD_END = new TaskHandle(null);
	private volatile TaskHandle head = SENTIAL_HEAD_END;

	public Timer(Executor th) {executor = th;}

	public void run() {
		int delta = 1;
		long prevTime, time = System.currentTimeMillis();

		while (!stopped) {
			try {
				// 系统睡眠了或者怎么了，那就别管误差了，遍历到期的任务吧，O(n)
				if (delta > 127) fastForward(delta);
					// 1（每毫秒轮询一次确实不影响性能，毕竟这是O(1)的算法，如果没有任务，仅仅是tick++
					// 2（睡久了容易产生误差，比如10000ms差了400ms，我总不能循环tick400次吧
				else while (delta-- > 0) wheel.tick(executor);

				pollNewTasks(time);
			} catch (Throwable e) {
				LOGGER.error("遇到了异常", e);
			}

			// 奈奎斯特采样定理？不管了，反正也只有1ms的精度
			LockSupport.parkNanos(500_000L);

			prevTime = time;
			time = System.currentTimeMillis();

			delta = (int) (time - prevTime);
		}
	}

	private void fastForward(int ticks) {
		ArrayList<TaskHandle> tasks = new ArrayList<>();
		wheel.fastForward(ticks, tasks, executor);
		for (int i = 0; i < tasks.size(); i++) {
			TaskHandle task = tasks.get(i);
			long timeLeft = task.timeLeft;
			if (timeLeft > 0 && TaskHandle.TIME.compareAndSet(task, timeLeft, TimingWheel.tweakTime(wheel, timeLeft)))
				TimingWheel.add(wheel, task);
		}
	}
	private void pollNewTasks(long time) {
		TaskHandle h = (TaskHandle) HEAD.getAndSet(this, SENTIAL_HEAD_END);

		while (h != SENTIAL_HEAD_END) {
			TaskHandle next;
			do {
				next = (TaskHandle) TaskHandle.NEXT.getVolatile(h);
			} while (next == null || !TaskHandle.NEXT.compareAndSet(h, next, null));

			block: {
				long timeLeft = h.timeLeft;
				// 取消，下CAS同, 因为只能取消，所以不用放在循环里
				if (timeLeft <= 0) break block;

				long addTime = timeLeft - time;
				if (addTime <= 0) {
					addTime = TimingWheel.safeExec(executor, h);
					if (addTime == 0) {
						h.timeLeft = 0;
						break block;
					}
				}

				if (!TaskHandle.TIME.compareAndSet(h, timeLeft, TimingWheel.tweakTime(wheel, addTime)))
					break block;

				TimingWheel.add(wheel, h);
			}

			h = next;
		}
	}

	public TimerTask delay(Runnable task, long delayMs) {
		if (stopped) throw new IllegalStateException("Timer already cancelled.");
		if (delayMs < 0) throw new IllegalArgumentException("Negative delay.");
		var handle = new TaskHandle(wheel, task, System.currentTimeMillis()+delayMs);
		if (task instanceof PeriodicTask periodicTaskWrapper) {
			periodicTaskWrapper.setHandle(handle);
		}

		TaskHandle head = (TaskHandle) HEAD.getAndSet(this, handle);
		TaskHandle.NEXT.setVolatile(handle, head);

		return handle;
	}
	// 周期任务是通过包装器实现的
	public final TimerTask loop(Runnable task, long periodMs) { return loop(task, periodMs, -1, 0); }
	public final TimerTask loop(Runnable task, long periodMs, int repeat) { return loop(task, periodMs, repeat, 0); }
	public TimerTask loop(Runnable task, long periodMs, int repeat, long delayMs) {return delay(new PeriodicTask(task, periodMs, repeat, true), delayMs);}

	/**
	 * Terminates this timer, discarding any currently scheduled tasks.
	 * Does not interfere with a currently executing task (if it exists).
	 * Once a timer has been terminated, its execution thread terminates
	 * gracefully, and no more tasks may be scheduled on it.
	 *
	 * <p>Note that calling this method from within the run method of a
	 * timer task that was invoked by this timer absolutely <b>NOT</b> guarantees that
	 * the ongoing task execution is the last task execution that will ever
	 * be performed by this timer.
	 *
	 * <p>This method may be called repeatedly; the second and subsequent
	 * calls have no effect.
	 */
	public void cancel() {
		if (this == defaultTimer) throw new IllegalStateException("Cannot cancel defaultTimer");
		stopped = true;
	}
}