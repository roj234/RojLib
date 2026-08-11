package roj.archive.zip;

import org.jetbrains.annotations.Nullable;
import roj.collect.HashMap;
import roj.collect.IntMap;
import roj.collect.TrieTree;
import roj.io.IOUtil;
import roj.io.source.ByteSource;
import roj.util.DynByteBuf;
import roj.util.function.ExceptionalSupplier;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;

/**
 * 对ZIP修改操作的集合，可以批量应用.
 * 注意：这个类中所有文件夹处理方法，其路径参数如果不是空字符串""那么必须以/结尾，否则会发生奇怪的事情（看源代码就知道是什么奇怪的事情了）.
 * @author Roj234
 * @since 2026/01/13 02:31
 */
public class ZipChangeList {
	public static ZipEditor newMemoryZip() {
		return new ZipEditor(new ByteSource(), ZipFile.FLAG_SaveInUTF, StandardCharsets.UTF_8);
	}

	private final HashMap<String, Object> additions = new HashMap<>();
	private final HashMap<String, Object> changes = new HashMap<>();
	private final TrieTree<Object> prefixChanges = new TrieTree<>();
	private String comment;

	/**
	 * 创建新文件模式的Zip修改
	 */
	@SuppressWarnings("unchecked")
	public void applyTo(ZipFile src, ZipPacker dst) throws IOException {
		if (comment != null)
			dst.setComment(comment);

		for (ZipEntry entry : src.entries()) {
			var LOCChanged = false; // 不能直接复制旧 Entry 的 LOC

			var match1 = changes.getEntry(entry.getName());
			if (match1 != null) {
				var value = match1.getValue();

				if (value instanceof String newName) {
					entry = entry.clone();
					entry.name = newName;
					entry.nameBytes = newName.getBytes(StandardCharsets.UTF_8);
					entry.flags |= ZipFile.GP_UFS;
					LOCChanged = true;
				} else {
					// 删除
					continue;
				}
			} else {
				IntMap.Entry<Object> match = prefixChanges.longestMatch(entry.getName());
				if (match != null) {
					var value = match.getValue();
					if (value instanceof String newName) {
						entry = entry.clone();
						entry.name = newName;
						entry.nameBytes = newName.getBytes(StandardCharsets.UTF_8);
						entry.flags |= ZipFile.GP_UFS;
						LOCChanged = true;
					} else {
						// 删除
						continue;
					}
				}
			}

			dst.copy(src, entry, LOCChanged);
		}

		for (Map.Entry<String, Object> item : additions.entrySet()) {
			var value = item.getValue();
			if (value instanceof ExceptionalSupplier<?,?>) {
				value = ((ExceptionalSupplier<?, IOException>) value).get();
			}

			ZipEntry entry = new ZipEntry(item.getKey());

			if (value instanceof InputStream in) {
				dst.beginEntry(entry);
				try {
					IOUtil.copyStream(in, dst);
				} finally {
					dst.closeEntry();
					IOUtil.closeSilently(in);
				}

			} else {
				dst.write(entry, (DynByteBuf) value);
			}
		}
	}

	/**
	 * 原位修改，不产生临时文件，IO操作会智能合并
	 * 缺陷：如果修改完成前程序崩溃，压缩包将损坏
	 * @implNote 不建议用此方法进行批量重命名，否则可能产生多倍磁盘写入，反而背离使用它的初衷
	 */
	public void applyTo(ZipEditor zip) {
		if (comment != null)
			zip.setComment(comment);

		for (ZipEntry entry : zip.entries()) {
			var match1 = changes.getEntry(entry.getName());
			if (match1 != null) {
				var value = match1.getValue();
				var update = zip.prepareUpdate(entry.getName());
				if (value instanceof String newName) {
					update.newName = newName;
				} else {
					update.data = value;
				}

				continue;
			}

			IntMap.Entry<Object> match = prefixChanges.longestMatch(entry.getName());
			if (match != null) {
				var value = match.getValue();
				var update = zip.prepareUpdate(entry.getName());
				if (value instanceof String newName) {
					update.newName = newName.concat(entry.getName().substring(match.getIntKey()));
				} else {
					update.data = value;
				}
			}
		}

		for (var item : additions.entrySet()) {
			var update = zip.prepareUpdate(item.getKey());
			update.data = item.getValue();
			update.setMethod(ZipEntry.DEFLATED);
		}
	}

	public ZipChangeList addFile(File file) {addFile(file, null);return this;}
	public ZipChangeList addFile(File file, String pathname) {
		if (pathname == null) pathname = file.getName();
		additions.put(pathname, (ExceptionalSupplier<InputStream, IOException>) () -> new FileInputStream(file));
		return this;
	}

	public ZipChangeList addFolder(File folder) {addFolder(folder, null);return this;}
	public ZipChangeList addFolder(File folder, String basename) {
		if (basename == null) basename = "";
		else basename = basename.replace(File.separatorChar, '/');

		int prefixLength = IOUtil.getPrefixLength(folder);
		String finalBasename = basename;
		IOUtil.listPaths(folder, (pathname, attr) -> {
			var relPath = finalBasename.concat(pathname.substring(prefixLength));
			additions.put(relPath, (ExceptionalSupplier<InputStream, IOException>) () -> new FileInputStream(pathname));
		});
		return this;
	}

	public ZipChangeList addStream(ExceptionalSupplier<InputStream, IOException> stream, String pathname) {additions.put(Objects.requireNonNull(pathname), Objects.requireNonNull(stream));return this;}
	public ZipChangeList addBuffer(DynByteBuf buf, String pathname) {additions.put(Objects.requireNonNull(pathname), Objects.requireNonNull(buf));return this;}

	private static String mustBeFolder(String pathname) {
		if (!pathname.endsWith("/") && !pathname.isEmpty())
			pathname = pathname.concat("/");
		return pathname;
	}

	public ZipChangeList remove(String pathname) {changes.put(Objects.requireNonNull(pathname), null);return this;}
	public ZipChangeList removeFolder(String prefix) {prefixChanges.put(mustBeFolder(prefix), null);return this;}

	public ZipChangeList rename(String oldname, String newname) {changes.put(oldname, newname);return this;}
	public ZipChangeList renameFolder(String oldPrefix, String newPrefix) {prefixChanges.put(mustBeFolder(oldPrefix), mustBeFolder(newPrefix));return this;}

	public ZipChangeList removePrefix(String prefix) {prefixChanges.put(prefix, null);return this;}
	public ZipChangeList renamePrefix(String oldPrefix, String newPrefix) {prefixChanges.put(oldPrefix, newPrefix);return this;}

	public ZipChangeList setComment(@Nullable String comment) {this.comment = comment;return this;}
}
