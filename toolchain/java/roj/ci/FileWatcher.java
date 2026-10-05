package roj.ci;

import com.sun.nio.file.ExtendedWatchEventModifier;
import roj.collect.*;
import roj.concurrent.TaskPool;
import roj.io.IOUtil;
import roj.ui.Tty;

import java.io.File;
import java.io.IOException;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.Collections;
import java.util.Set;
import java.util.function.Consumer;

import static java.nio.file.StandardWatchEventKinds.*;
import static roj.asmx.mapper.Mapper.DONT_LOAD_PREFIX;
import static roj.ci.MCMake.BASE;

/**
 * Project file change watcher
 *
 * @author Roj233
 * @since 2021/7/17 19:01
 */
final class FileWatcher extends IFileWatcher implements Consumer<WatchKey> {
	private static final class X {
		private X _next;

		WatchKey key;
		final Project owner;
		final byte no;
		final HashSet<String> mod = new HashSet<>();

		X(Project owner, WatchKey key, int tag) {
			this.owner = owner;
			this.key = key;
			this.no = (byte) tag;
		}

		X() {
			this.owner = null;
			this.no = 0;
		}
	}

	private final X via;
	private final XashMap<WatchKey, X> actions;
	private final WatchService watcher;
	private final String libPath;

	private final HashMap<String, X[]> listeners;

	public FileWatcher() throws IOException {
		watcher = IOUtil.syncWatchPoll("文件修改监控", this);
		XashMap.Template<WatchKey, X> template = XashMap.forType(WatchKey.class, X.class).hasher(Hasher.identity()).build();
		actions = template.create();
		listeners = new HashMap<>();
		via = new X();
		actions.add(via);

		File lib = new File(BASE, "lib");
		lib.mkdirs();
		lib.toPath().register(watcher, ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY, OVERFLOW);
		this.libPath = lib.getAbsolutePath();
	}

	@Override
	public void accept(WatchKey key) {
		X handler = actions.get(key);
		if (handler == null || handler == via) {
			for (WatchEvent<?> event : key.pollEvents()) {
				if (!event.kind().name().equals("OVERFLOW")) {
					String path = key.watchable().toString();
					String name = event.context().toString().toLowerCase();
					if (path.startsWith(libPath)) {
						if (!name.startsWith(DONT_LOAD_PREFIX) && (name.endsWith(".zip") || name.endsWith(".jar"))) {
							break;
						}
					}
				}
			}

			if (!key.reset()) Tty.warning("[watcher reset failed]lib没了？");
			return;
		}

		boolean isOverflow = false;
		loop:
		for (WatchEvent<?> event : key.pollEvents()) {
			String name = event.kind().name();
			HashSet<String> s = handler.mod;
			switch (name) {
				case "OVERFLOW": {
					Tty.error("[PW]更改的文件过多，已暂停自动编译 "+key.watchable());
					isOverflow = true;
					break loop;
				}
				case "ENTRY_MODIFY", "ENTRY_CREATE", "ENTRY_DELETE": {
					String pathname = key.watchable().toString()+File.separatorChar+event.context();
					if (new File(pathname).isDirectory()) break;
					if (handler.no == ID_SRC && !pathname.endsWith(".java")) continue;

					handler.owner.fileChanged(pathname);
					// 总是添加，不管是否删除
					synchronized (s) {
						s.add(pathname);
					}
				}
			}
		}

		// The key is not valid (directory was deleted
		if (isOverflow || !key.reset()) {
			X[] remove = listeners.remove(handler.owner);
			if (remove != null) {
				for (X set : remove) {
					if (set == null) continue;

					actions.remove(set);
					TaskPool.common().executeUnsafe(set.key::cancel);
					synchronized (set.mod) {
						set.mod.clear();
						set.mod.add(null);
					}
				}
			}
		} else {
			handler.owner.fileChanged();
		}
	}

	@Override
	public Set<String> getModified(Project proj, int id) {
		if (!listeners.containsKey(proj.getName())) return super.getModified(proj, id);
		X x = listeners.get(proj.getName())[id];
		return x == null ? Collections.emptySet() : x.mod;
	}

	public void removeAll() {
		for (X listener : new ArrayList<>(actions)) {
			var key1 = listener.key;
			if (key1 != null) key1.cancel();
		}
		actions.clear();
		listeners.clear();
	}

	public void add(Project proj) throws IOException {
		X[] arr = listeners.get(proj.getName());
		if (arr != null) {
			for (X x : arr) {
				if (x == null) continue;

				x.mod.clear();
			}
		} else {
			arr = new X[3];
			WatchEvent.Kind<?>[] ALL_KIND = {ENTRY_CREATE, ENTRY_MODIFY, ENTRY_DELETE, OVERFLOW};
			WatchKey key;
			if (proj.srcPath.isDirectory()) {
				key = proj.srcPath.toPath().register(watcher, ALL_KIND, ExtendedWatchEventModifier.FILE_TREE);
				actions.add(arr[ID_SRC] = new X(proj, key, ID_SRC));
			}
			if (proj.resPath.isDirectory()) {
				key = proj.resPath.toPath().register(watcher, ALL_KIND, ExtendedWatchEventModifier.FILE_TREE);
				actions.add(arr[0] = new X(proj, key, ID_RES));
			}

			listeners.put(proj.getName(), arr);
		}
	}

	@Override
	public void remove(Project proj) {
		X[] arr = listeners.remove(proj.getName());
		if (arr != null) {
			for (X x : arr) {
				if (x != null)
					x.key.cancel();
			}
		}
	}
}