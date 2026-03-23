package roj.archive.zip;

import org.jetbrains.annotations.NotNull;
import roj.archive.ArchiveUtils;
import roj.collect.ArrayList;
import roj.collect.FindSet;
import roj.collect.LinkedOpenHashKVSet;
import roj.crypt.CRC32;
import roj.io.IOUtil;
import roj.io.source.BufferedSource;
import roj.io.source.Source;
import roj.optimizer.FastVarHandle;
import roj.util.ArrayCache;
import roj.util.ByteList;
import roj.util.DynByteBuf;
import roj.util.FastFailException;
import roj.util.function.ExceptionalSupplier;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Set;
import java.util.zip.Deflater;

import static roj.archive.zip.ZipEntryWriter.*;

/**
 * 支持分卷压缩文件
 * 支持AES、ZipCrypto加密的读写
 * 支持任意编码，支持InfoZip的UTF文件名
 * 支持ZIP64
 * <p>
 * 禁止使用{@link #FLAG_ReadCENOnly}的限制已移除，但对性能的影响有待测试.
 * 现已支持重命名！
 *
 * @author Roj233
 * @since 2021/7/10
 * @revised 2025/12/28
 * @version 4.3
 */
@FastVarHandle
public final class ZipEditor extends ZipFile {
	private final FindSet<ZipUpdate> pendingUpdates = new LinkedOpenHashKVSet<>();

	public ZipEditor(String name) throws IOException { this(new File(name)); }
	public ZipEditor(File file) throws IOException { this(file, FLAG_RemoveEXT | FLAG_Verify | FLAG_ReadCENOnly); }
	public ZipEditor(File file, int flags) throws IOException { this(file, flags, StandardCharsets.UTF_8); }
	public ZipEditor(File file, int flags, Charset charset) throws IOException {
		this(ArchiveUtils.tryOpenSplitArchive(file, false), flags, charset);
		if (r.length() > 0) reload();
		else namedEntries = ENTRY_TEMPLATE.create();
		this.file = file;
	}
	public ZipEditor(Source source, int flags, Charset cs) {
		super(source, flags, cs);

		if (cs == StandardCharsets.UTF_8) {
			this.flags |= FLAG_SaveInUTF;
		}
	}

	public void setComment(String str) {
		comment = str == null || str.isEmpty() ? ArrayCache.BYTES : IOUtil.encodeUTF8(str);
		if (comment.length > 65535) {
			comment = Arrays.copyOf(comment, 65535);
			throw new IllegalArgumentException("Comment too long");
		}
	}

	public void setComment(byte[] str) { comment = str == null ? ArrayCache.BYTES : str; }

	/** 如果 data 为 null 那么删除 */
	public ZipUpdate put(String name, DynByteBuf data) {
		ZipUpdate mod = prepareUpdate(name);
		mod.data = data;
		mod.setMethod(data != null && data.readableBytes() > 100 ? ZipEntry.DEFLATED : ZipEntry.STORED);
		return mod;
	}
	public ZipUpdate put(String name, ExceptionalSupplier<DynByteBuf, IOException> getData, boolean compress) {
		ZipUpdate mod = prepareUpdate(name);
		mod.data = getData;
		mod.setMethod(compress ? ZipEntry.DEFLATED : ZipEntry.STORED);
		return mod;
	}
	public ZipUpdate putStream(String name, ExceptionalSupplier<InputStream, IOException> in, boolean compress) {
		ZipUpdate mod = prepareUpdate(name);
		mod.data = in;
		mod.setMethod(compress ? ZipEntry.DEFLATED : ZipEntry.STORED);
		return mod;
	}
	public ZipUpdate prepareUpdate(String name) {
		if ((flags & FLAG_HasError) != 0) throw new IllegalStateException("该压缩文件不符合规范，因此无法写入");
		return pendingUpdates.intern(new ZipUpdate(name));
	}

	public Set<ZipUpdate> getPendingUpdates() { return pendingUpdates; }

	@Override
	public void close() throws IOException {
		pendingUpdates.clear();
		super.close();
	}

	public void save() throws IOException { save(Deflater.DEFAULT_COMPRESSION); }
	@SuppressWarnings("unchecked")
	public void save(int level) throws IOException {
		if (pendingUpdates.isEmpty()) return;

		removeStaleEntryAndDoRename();

		ByteList buf = new ByteList(2048);
		FastFailException dataExceptions = null;

		long precisionModTime = System.currentTimeMillis();
		int modTime = ZipEntry.java2DosTime(precisionModTime);

		var impl = new ZipEntryWriter(r.isBuffered() ? r : BufferedSource.wrap(r), buf, level);
		impl.flags = flags;

		// write LOCs
		entries.ensureCapacity(entries.size() + pendingUpdates.size());
		for (ZipUpdate mod : pendingUpdates) {
			ZipEntry entry = mod.entry;
			entries.add(entry);
			if (namedEntries != null)
				namedEntries.add(entry);

			entry.setEncryptMethod(mod.getEncryptMethod());
			entry.method = (char) mod.getMethod();

			long overrideTime = mod.modificationTime;
			if (overrideTime != -1L) {
				if (overrideTime == 0) {
					entry.pModTime = precisionModTime;
					entry.modTime = modTime;
				} else {
					entry.pModTime = overrideTime;
					entry.modTime = ZipEntry.java2DosTime(overrideTime);
				}
			}

			Object data = mod.data;
			if (data instanceof ExceptionalSupplier) {
				try {
					data = ((ExceptionalSupplier<?, IOException>) data).get();
				} catch (Exception ex) {
					dataExceptions = SUPPRESS(dataExceptions, entry, ex);
					continue;
				}
			}

			InputStream in;
			boolean useZip64 = mod.isZip64();
			if (data instanceof DynByteBuf b) {
				useZip64 = false;
				in = null;
				entry.size = b.readableBytes();
				entry.crc32 = CRC32.crc32(b);
			} else {
				in = (InputStream) data;
				if (in.available() == Integer.MAX_VALUE) useZip64 = true;
			}

			long pos = impl.rawOut.position();
			try {
				impl.beginEntry(entry, in != null, useZip64, mod.password);
				if (in != null) {
					IOUtil.copyStream(in, impl);
				} else {
					impl.write((DynByteBuf) data, true);
				}
				impl.closeEntry();
			} catch (Throwable ex) {
				// 跳过失败的Entry
				impl.entry = null;
				impl.rawOut.seek(pos);

				dataExceptions = SUPPRESS(dataExceptions, entry, ex);
			} finally {
				IOUtil.closeSilently(in);
			}
		}

		impl.rawOut.close();
		writeCENandEND(buf, r);
		buf.release();

		pendingUpdates.clear();

		r.setLength(r.position());

		if (dataExceptions != null) throw dataExceptions;
	}

	private void removeStaleEntryAndDoRename() throws IOException {
		if (entries.isEmpty()) {
			r.seek(cenOffset);
		} else {
			var retainedEntries = new ArrayList<ZipEntry>();
			// 高效的合并IO & `压缩`文件，同时移除了之前版本中复杂的线段树和xor-like操作
			// - 应用'思想'，而不是'数据结构'甚至'实现'
			var relocator = new Object() {
				int lastCommitSize = 0;
				long writePtr = entries.get(0).startPos(); // 20260118修复: 压缩文件可能不从zero offset开始

				void flush(long blockEnd) throws IOException {
					if (retainedEntries.size() > lastCommitSize) {
						long blockStart = retainedEntries.get(lastCommitSize).startPos();

						long totalShift = writePtr - blockStart;
						for (int i = lastCommitSize; i < retainedEntries.size(); i++) {
							retainedEntries.get(i).offset += totalShift;
						}

						long dataLength = blockEnd - blockStart;
						if (blockStart != writePtr) r.moveSelf(blockStart, writePtr, dataLength);

						writePtr += dataLength;

						lastCommitSize = retainedEntries.size();
					}
				}
			};
			r.seek(relocator.writePtr);

			ZipUpdate queryKey = new ZipUpdate(null);

			for (int i = 0; i < entries.size(); i++) {
				ZipEntry entry = entries.get(i);
				queryKey.name = entry.name;

				ZipUpdate update = pendingUpdates.find(queryKey);
				if (update == queryKey) { // 保留
					retainedEntries.add(entry);
				} else {
					relocator.flush(entry.startPos());

					// 重命名
					if (update.newName != null) {
						if ((flags & FLAG_ReadCENOnly) != 0) {
							validateEntry(r, entry);
						}

						// 重命名中间状态可能产生键冲突
						namedEntries = null;

						entry.name = update.newName;
						entry.flags &= ~GP_HAS_EXT;

						if ((flags & FLAG_SaveInUTF) != 0 || (entry.flags & GP_UFS) != 0) {
							entry.flags |= GP_UFS;
							entry.nameBytes = IOUtil.encodeUTF8(entry.name);
						} else {
							entry.nameBytes = entry.name.getBytes(cs);
						}

						var buf = new ByteList();

						// 一个比较难绷的问题之文件名太长把后面数据覆盖了怎么办……
						int availableSpace = (int) Math.min(entry.offset - relocator.writePtr, Integer.MAX_VALUE);
						int neededSpace = 30 + entry.nameBytes.length + entry.extraLenOfLOC;
						int remainSpace = availableSpace - neededSpace;

						// 空间可能不足时再重算 extraLenOfLOC
						if (remainSpace < 256) {
							remainSpace += entry.extraLenOfLOC;
							buf.putShort(0);
							entry.writeLOCExtra(buf, 2, 0);
							buf.clear();
							remainSpace -= entry.extraLenOfLOC;

							if (remainSpace < 0) {
								// 增加 64K 空间，这是一次全部复制，代价很大
								// 不过扩展 64K 之后应该也不需要第二次扩展了
								//
								// 更好的办法是先遍历一遍重命名的Update，计算总共需要的扩展大小，然后从第一个文件名变长的Update之前的文件中，挑选一个足够大但最小的项目放到内存里
								// 这也许会很慢……
								//
								// 不过重命名本来就用的少，你看人家7zFM都不支持重命名！
								// 什么都不支持，连往已存在的压缩包里加东西的时候选算法都不支持！
								// 要是支持我还在这里写代码？
								//
								// 还有，你都看到这里了，还不去用那个创建新文件的 roj.archive.zip.ZipChangeList.applyTo(roj.archive.zip.ZipFile, roj.archive.zip.ZipPacker) ？

								int expandedSpace = 65536 - remainSpace;

								r.moveSelf(entry.endPos(), entry.endPos() + expandedSpace, cenOffset - entry.endPos());
								r.moveSelf(entry.offset, entry.offset -= remainSpace, entry.endPos() - entry.startPos());

								for (int j = i+1; j < entries.size(); j++) entries.get(j).offset += expandedSpace;
								cenOffset += expandedSpace;
							}
						}

						r.seek(relocator.writePtr);
						writeLOC(r, buf, entry);
						relocator.writePtr += buf.wIndex();

						buf.release();

						long length = entry.getCompressedSize();
						// 否则扩展时已经顺便复制了
						if (remainSpace >= 0) {
							r.moveSelf(entry.offset, relocator.writePtr, length);
							entry.offset = relocator.writePtr;
						}
						relocator.writePtr += length;

						if (update.data == null)
							pendingUpdates.remove(update);

						retainedEntries.add(entry);
						relocator.lastCommitSize++;

						// 重命名之后这个entry不能复用
						continue;
					}

					if (update.data == null) { // 删除
						pendingUpdates.remove(update);

						if (namedEntries != null)
							namedEntries.remove(entry);
					} else { // 变化
						checkEmptyDir(update);
						update.entry = entry;

						if ((flags & FLAG_SaveInUTF) != 0 && (entry.flags & GP_UFS) == 0) {
							entry.flags |= GP_UFS;
							entry.nameBytes = IOUtil.encodeUTF8(entry.name);
						}
						entry.flags &= GP_UFS|ZipEntry.MZ_PrecisionTime|ZipEntry.MZ_UniPath|ZipEntry.MZ_HostSystem;
					}
				}
			}

			relocator.flush(cenOffset);
			r.seek(relocator.writePtr);

			entries = retainedEntries;
		}

		for (var itr = pendingUpdates.iterator(); itr.hasNext(); ) {
			ZipUpdate mod = itr.next();
			// 删除不存在(没匹配上)的项目
			if (mod.data == null) itr.remove();
			else if (mod.entry == null) {
				// 创建新的项目
				checkEmptyDir(mod);

				var entry = new ZipEntry(mod.name);
				mod.entry = entry;

				if ((flags & FLAG_SaveInUTF) != 0) {
					entry.flags = GP_UFS;
					entry.nameBytes = IOUtil.encodeUTF8(mod.name);
				} else {
					entry.flags = 0;
					entry.nameBytes = mod.name.getBytes(cs);
				}
			}
		}
	}

	private static void checkEmptyDir(ZipUpdate mod) {
		String name = mod.name;
		if (name.endsWith("/")) throw new IllegalArgumentException("目录不能包含数据");
	}

	private void writeCENandEND(ByteList buf, OutputStream out) throws IOException {
		cenOffset = r.position();

		buf.clear();
		int flushLimit = buf.list.length - 256;
		if ((flags & FLAG_RCEN) == 0) {
			for (int i = 0; i < entries.size(); i++) {
				writeCEN(buf, entries.get(i), 0);
				if (buf.wIndex() > flushLimit) {
					buf.writeToStream(out);
					buf.clear();
				}
			}
		} else {
			buf.putInt(ZipFile.HEADER_RCEN);

			for (int i = 0; i < entries.size(); i++) {
				writeRCEN(buf, entries.get(i), 0);

				if (buf.wIndex() > flushLimit) {
					buf.writeToStream(out);
					buf.clear();
				}
			}
		}
		cenLength = r.position()+buf.wIndex()-cenOffset;
		writeEND(buf, cenOffset, cenLength, entries.size(), comment);

		buf.writeToStream(out);
	}

	private static @NotNull FastFailException SUPPRESS(FastFailException dataExceptions, ZipEntry entry, Throwable ex) {
		if (dataExceptions == null)
			dataExceptions = new FastFailException("无法保存"+entry.name+"的数据", ex);
		else dataExceptions.addSuppressed(ex);
		return dataExceptions;
	}

	private File file;
	/**
	 * re-open internal RandomAccessFile, to continue operate
	 */
	public void ensureOpen() throws IOException {
		if (file == null) throw new IOException("不是从文件打开");
		if (r == null) {
			r = ArchiveUtils.tryOpenSplitArchive(file, false);
			var cache = (Source) CACHE.getAndSet(this, r);
			if (cache != r) IOUtil.closeSilently(cache);
		}
	}
}