package roj.archive.sevenz.util;

import org.intellij.lang.annotations.Language;
import org.intellij.lang.annotations.MagicConstant;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import roj.archive.ArchiveUtils;
import roj.archive.sevenz.*;
import roj.archive.sevenz.vcs.iVCS;
import roj.archive.xz.LZMA2Options;
import roj.archive.xz.LZMA2ParallelEncoder;
import roj.collect.ArrayList;
import roj.collect.HashMap;
import roj.collect.HashSet;
import roj.collect.*;
import roj.concurrent.TaskGroup;
import roj.config.node.LongValue;
import roj.io.IOUtil;
import roj.io.SecurityDescriptor;
import roj.io.source.CompositeSource;
import roj.math.MathUtils;
import roj.text.TextUtil;
import roj.text.logging.Logger;
import roj.ui.EasyProgressBar;
import roj.util.*;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.DosFileAttributeView;
import java.nio.file.attribute.DosFileAttributes;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.regex.Pattern;

import static roj.archive.WinAttributes.*;

/**
 * @author Roj234
 * @since 2023/6/4 3:54
 */
public class SevenZArchiver {
	private static final Logger LOGGER = Logger.getLogger();

	/**
	 * 待归档的目录列表 必须为目录
	 */
	public List<File> inputDirectories;
	//region 预处理
	/** 完全替换：完全覆盖目标归档文件 */
	public static final int UM_REPLACE = 0;
	/** 添加并替换文件：替换(不检测是否和源相同)归档中存在的文件，并添加新文件 */
	public static final int UM_REPLACE_EXISTING = 1;
	/** 更新并添加文件：更新源中已修改的文件，并添加新文件 */
	public static final int UM_UPDATE = 2;
	/** 只更新已存在的文件：更新源中已修改的文件，不添加新文件 */
	public static final int UM_UPDATE_EXISTING = 3;
	/** 同步压缩包内容：删除归档中未在源中出现的文件 */
	public static final int UM_SYNC = 4;
	/** 仅保留差异：仅保留源中被修改或删除的文件 */
	public static final int UM_DIFF = 5;
	/** 增量版本控制： */
	public static final int UM_IVCS = 6;
	/**
	 * 归档追加操作模式
	 * @see #UM_REPLACE
	 * @see #UM_REPLACE_EXISTING
	 * @see #UM_UPDATE
	 * @see #UM_UPDATE_EXISTING
	 * @see #UM_SYNC
	 * @see #UM_DIFF
	 * @see #UM_IVCS
	 */
	@MagicConstant(intValues = {UM_REPLACE_EXISTING, UM_UPDATE, UM_UPDATE_EXISTING, UM_SYNC, UM_REPLACE, UM_DIFF, UM_IVCS})
	public int updateMode;

	/**
	 * 启用快速追加检查（仅检查CRC32和修改时间，而不是完整比较内容）
	 */
	public boolean fastAppendCheck;

	/** 相对路径：相对于当前处理的目录 */
	public static final int PF_RELATIVE = 0;
	/** 公共相对路径：相对于所有输入目录的最近共同祖先 */
	public static final int PF_COMMON_RELATIVE = 1;
	/** 绝对路径：系统绝对路径 */
	public static final int PF_ABSOLUTE = 2;
	/**
	 * 归档内路径存储格式
	 * @see #PF_RELATIVE
	 * @see #PF_COMMON_RELATIVE
	 * @see #PF_ABSOLUTE
	 */
	@MagicConstant(intValues = {PF_RELATIVE, PF_COMMON_RELATIVE, PF_ABSOLUTE})
	public int pathFormat;

	/**
	 * 文件路径排序比较器（null表示使用默认顺序）
	 */
	public Comparator<File> fileSorter;

	@Language("regexp")
	public String exclusionPattern = "^[A-Z]:\\\\(?:\\$RECYCLE\\.BIN|System Volume Information)";
	//endregion
	//region 属性存储/过滤
	/**
	 * 是否存储文件夹条目
	 */
	public boolean storeDirectories;
	/**
	 * 是否存储文件修改时间
	 */
	public boolean storeModifiedTime;
	/**
	 * 是否存储文件创建时间
	 */
	public boolean storeCreationTime;
	/**
	 * 是否存储文件访问时间
	 */
	public boolean storeAccessTime;
	/**
	 * 是否存储文件属性（如只读、隐藏等）
	 */
	public boolean storeAttributes;
	/**
	 * 是否检测并存储符号链接（软链接）
	 * <p>自定义格式与其他工具不兼容，只能使用本项目解压</p>
	 */
	public boolean storeSymbolicLinks;
	/**
	 * 是否检测并存储硬链接
	 * <p>自定义格式与其他工具不兼容，只能使用本项目解压</p>
	 */
	public boolean storeHardLinks;
	/**
	 * 是否仅处理带有"归档"属性的文件
	 * <p>仅在Windows系统上有意义</p>
	 */
	public boolean filterByArchiveAttribute;
	/**
	 * 压缩完成后是否清除文件的"归档"属性
	 * <p>仅在Windows系统上有意义</p>
	 */
	public boolean clearArchiveAttribute;
	/**
	 * 是否存储文件的安全标识符
	 * <p>仅在Windows系统上有意义</p>
	 */
	public boolean storeSecurity;
	//endregion
	//region 并行
	/**
	 * 预期压缩线程数，用于组织文件块
	 * 如果设置为1并在compress函数中提供了一个实际为多线程的Executor，会导致JVM崩溃
	 * 默认值0只能和NONE chunkOrganizer一起使用
	 */
	public int threads;
	/**
	 * 是否自动计算固实块大小
	 * <p>启用时，将尝试生成恰好 {@link #threads} 个固实块</p>
	 */
	public boolean autoSolidSize;
	/**
	 * 固实大小（单位：字节）
	 * 7-zip的默认设置为词典大小的256倍
	 * 0 为 非固实 (默认值)
	 * <0 为 完全固实
	 */
	public long solidSize;
	/**
	 * 并行优化策略
	 */
	public ChunkOrganizer chunkOrganizer = ChunkOrganizer.DEFAULT;

	public enum ChunkOrganizer {
		/**
		 * 根据文件内容进行分组，尽可能优化大小
		 * <p><b>警告：非常慢！</b></p>
		 * @see ContentAwareChunkOrganizer
		 */
		CONTEXT_AWARE(true),
		/**
		 * 不进行并行优化
		 * <p><b>警告：可能很慢！</b></p>
		 */
		DEFAULT(false),
		/**
		 * 将小文件与 超过固实大小的文件 合并 以提高CPU利用率
		 */
		GREEDY_FILL(false),
		/**
		 * 根据文件大小进行分组，尽可能优化速度
		 */
		BEST_SPEED(true);

		/**
		 * 这个策略是否<i>完全</i>忽略文件顺序
		 */
		public final boolean ignoreFileOrder;
		public boolean useAltOptions() {
			return ordinal() == 2 || ordinal() == 3;
		}

		ChunkOrganizer(boolean ignoreFileOrder) {
			this.ignoreFileOrder = ignoreFileOrder;
		}
	}

	/**
	 * GREEDY_FILL和BEST_SPEED中大文件（包含 超过固实大小的 单个文件 的块）使用的LZMA2配置参数
	 * 其余优化策略不使用这个字段
	 */
	public LZMA2Options streamParallelOptions;
	//endregion
	//region 压缩
	/**
	 * 分卷压缩大小（单位：字节） 0禁用
	 */
	public long splitSize;

	/**
	 * LZMA2压缩算法参数
	 */
	@NotNull public LZMA2Options options = new LZMA2Options();
	/**
	 * 是否启用部分文件格式的预处理器 (WAV,EXE,SO等)
	 */
	public boolean useFilter;
	/**
	 * 是否启用可执行文件跳转地址预处理器V2 (BCJ2)
	 * <p>仅支持X86体系结构的文件</p>
	 */
	public boolean useBCJ2;
	/**
	 * 压缩归档头 (建议启用)
	 */
	public boolean compressHeader = true;
	//endregion
	//region 加密
	/**
	 * 归档密码（null表示无加密）
	 */
	public String encryptionPassword;
	/**
	 * 加密文件名
	 */
	public boolean encryptFileName;
	/**
	 * 加密算法迭代次数（强度级别，通常19 ≈ 600000 iters）
	 */
	public int encryptionPower = 19;
	/**
	 * 盐值长度（字节数，0表示不使用盐值）
	 */
	public int encryptionSaltLength = 16;
	//endregion
	//region 输出
	/**
	 * 输出目录（用于存放生成的归档文件）
	 */
	@NotNull public File outputDirectory;
	/**
	 * 输出文件名（不含路径，例如"archive.7z"）
	 * <p>与{@link #outputDirectory}组合形成完整输出路径</p>
	 */
	@NotNull public String outputFilename;
	/**
	 * 是否保留原始归档文件（对'部分追加'模式无法生效 - 因为直接修改原始文件性能更佳）
	 * 如果保留，那么请手动重命名compress()返回的File
	 */
	public boolean keepOldArchive;
	//endregion

	private SevenZFile oldArchive;
	private Map<String, SevenZEntry> archiveEntries;
	private final Map<WordBlock, List<SevenZEntry>> copyFromOriginal = new HashMap<>();
	private long keepSize;

	private boolean firstIsUncompressed;
	private long compressibleSize;
	private int parallelableBlocks;
	private final ArrayList<Chunk> chunks = new ArrayList<>();
	private final List<SevenZEntry> directories = new ArrayList<>();

	// links
	private static final LinkOption[] READ_LINK = {LinkOption.NOFOLLOW_LINKS}, FOLLOW_LINK = new LinkOption[0];

	private final List<SevenZEntry> symbolicLinks = new ArrayList<>();
	private final List<String> symbolicLinkValues = new ArrayList<>();
	private final Map<String, SevenZEntry> hardlinkRef = new HashMap<>();

	// ACLs
	private final ToIntMap<DynByteBuf> aclIndex = new ToIntMap<>();
	private final List<byte[]> aclTable = new ArrayList<>();
	private final ToIntMap<SevenZEntry> aclMapping = new ToIntMap<>();

	// categories
	private static final HashSet<String> PROGRAM_FILE_EXT = new HashSet<>("exe", "dll", "sys", "so");

	private final Map<FileCategory, List<Pair<SevenZEntry, File>>> fileByCategory = new EnumMap<>(FileCategory.class);
	private int prefixLength;
	private enum FileCategory {
		UNCOMPRESSED,
		REGULAR,
		EXECUTABLE_X86,
		EXECUTABLE_ARM,
		EMPTY_OR_FOLDER,
		RAW_AUDIO,
	}

	// ivcs
	private iVCS vcsInstance;
	private iVCS.Commit vcsCommit;

	public @Nullable FastFailException warnings;
	private void addWarning(Throwable message) {
		if (warnings == null) warnings = new FastFailException("发生了一些警告");
		warnings.addSuppressed(message);
	}

	public boolean prepare() throws IOException {
		warnings = null;

		firstIsUncompressed = false;
		compressibleSize = 0;
		chunks.clear();
		directories.clear();

		copyFromOriginal.clear();
		keepSize = 0;

		List<File> paths = inputDirectories;
		File targetArchive = new File(outputDirectory, outputFilename);
		String targetArchivePath = targetArchive.getAbsolutePath();

		// 重新建立压缩包
		if (updateMode != UM_REPLACE) {
			if (updateMode == UM_IVCS) {
				if (splitSize != 0) throw new IllegalStateException("Cannot customize splitSize when using iVCS");
				CompositeSource io = CompositeSource.dynamic(targetArchive, true);
				if (io.length() > 0) {
					try {
						oldArchive = new SevenZFile(io, encryptionPassword);
						vcsInstance = new iVCS(oldArchive);
						iVCS.Commit commit = vcsInstance.getActiveCommit();
						if (commit == null) {
							if (vcsInstance.getActiveBranch() == null) throw new IllegalStateException("No active branch");
							archiveEntries = new HashMap<>();
						} else {
							var view = new iVCS.FileView();
							vcsInstance.buildFileTree(commit, view);
							archiveEntries = view.getFiles();
						}
						System.out.println("Previous commit: "+commit);
					} catch (Exception e) {
						IOUtil.closeSilently(io);
						throw e;
					}
				} else {
					oldArchive = new SevenZFile(io, 0, 0, null);
					vcsInstance = new iVCS(oldArchive);
					archiveEntries = new HashMap<>();
				}
			} else {
				File out = new File(outputDirectory, outputFilename.concat(splitSize == 0 ? "" : ".001"));
				if (out.length() > 0) {
					var io = ArchiveUtils.tryOpenSplitArchive(out, false);
					try {
						oldArchive = new SevenZFile(io, encryptionPassword);
						archiveEntries = oldArchive.getEntries();
					} catch (Exception e) {
						IOUtil.closeSilently(io);
						throw e;
					}
				} else {
					updateMode = UM_REPLACE;
				}
			}
		} else {
			Files.deleteIfExists(targetArchive.toPath());
		}

		var exclusion = exclusionPattern == null ? null : Pattern.compile(exclusionPattern).matcher("");
		BiConsumer<File, BasicFileAttributes> callback = (file, attr) -> {
			String absolutePath = file.getAbsolutePath();
			if (exclusion != null && exclusion.reset(absolutePath).find(0)) {
				if (attr.isDirectory()) skipSubtree = true;
				return;
			}
			if (absolutePath.startsWith(targetArchivePath)) return;

			var entryName = absolutePath.substring(prefixLength).replace(File.separatorChar, '/');

			switch (updateMode) {
				case UM_REPLACE_EXISTING -> {
					// 添加并替换文件：不检查，追加所有新文件
					archiveEntries.remove(entryName);
				}
				case UM_UPDATE -> {
					// 更新并添加文件：检查并排除未更改的旧文件，并添加新文件
					SevenZEntry prevEntry = archiveEntries.get(entryName);
					if (prevEntry != null && isSame(file, attr, prevEntry)) return;
					archiveEntries.remove(entryName);
				}
				case UM_UPDATE_EXISTING -> {
					// 只更新已存在的文件
					SevenZEntry prevEntry = archiveEntries.remove(entryName);
					if (prevEntry == null) return;
				}
				case UM_SYNC -> {
					// 同步压缩包内容
					SevenZEntry prevEntry = archiveEntries.get(entryName);
					if (prevEntry != null && isSame(file, attr, prevEntry)) {
						keepEntry(prevEntry);
						return;
					}
				}
				case UM_DIFF -> {
					// 仅保留差异的文件
					SevenZEntry prevEntry = archiveEntries.remove(entryName);
					if (prevEntry != null) {
						if (isSame(file, attr, prevEntry)) return;

						keepEntry(prevEntry);
						entryName = "MODIFIED/new/".concat(entryName);
						prevEntry.setName("MODIFIED/old/".concat(prevEntry.getName()));
					} else {
						entryName = "ADDED/".concat(entryName);
					}
				}
				case UM_IVCS -> {
					// 增量版本控制
					SevenZEntry prevEntry = archiveEntries.remove(entryName);
					if (prevEntry != null && isSame(file, attr, prevEntry)) return;
				}
			}

			long length = attr.size();
			if (length == 0) {
				addFile(FileCategory.EMPTY_OR_FOLDER, file, entryName, attr);
				return;
			}

			String ext = IOUtil.getExtension(file.getName());
			if (ArchiveUtils.INCOMPRESSIBLE_FILE_EXT.contains(ext) || options.getMode() == LZMA2Options.MODE_UNCOMPRESSED) {
				addFile(FileCategory.UNCOMPRESSED, file, entryName, attr);
			} else {
				compressibleSize += attr.size();

				FileCategory category = FileCategory.REGULAR;

				if (useFilter) {
					if (PROGRAM_FILE_EXT.contains(ext)) {
						category = FileCategory.EXECUTABLE_X86;
					}
					else if ("wav".equals(ext)) {
						category = FileCategory.RAW_AUDIO;
					}
				}

				addFile(category, file, entryName, attr);
			}
		};

		if (pathFormat == PF_COMMON_RELATIVE) {
			List<String> shortestCommonParent = TextUtil.split(paths.get(0).getAbsolutePath(), File.separatorChar);
			prefixLength = shortestCommonParent.size();
			for (int i = 1; i < paths.size(); i++) {
				List<String> subpaths = TextUtil.split(paths.get(i).getAbsolutePath(), File.separatorChar);
				for (int j = 0; j < Math.min(prefixLength, subpaths.size()); j++) {
					if (!subpaths.get(j).equals(shortestCommonParent.get(j))) {
						prefixLength = j;
						break;
					}
				}
			}

			prefixLength = TextUtil.join(shortestCommonParent.subList(0, prefixLength), File.separator).length()+1;
		}
		for (File path : paths) {
			if (path.isFile()) {
				if (pathFormat == PF_RELATIVE) prefixLength = path.getAbsolutePath().length() - path.getName().length();
				callback.accept(path, Files.readAttributes(path.toPath(), BasicFileAttributes.class, FOLLOW_LINK));
			} else {
				if (pathFormat == PF_RELATIVE) {
					prefixLength = IOUtil.getPrefixLength(path);
				}
				traverseFolder(path, callback);
			}
		}

		if (fileSorter != null) {
			if (chunkOrganizer.ignoreFileOrder) {
				addWarning(new FastFailException("选择的ChunkOrganizer忽略文件顺序！"));
			} else {
				for (var files : fileByCategory.values()) {
					files.sort((o1, o2) -> fileSorter.compare(o1.getValue(), o2.getValue()));
				}
			}
		}

		switch (updateMode) {
			case UM_REPLACE_EXISTING, UM_UPDATE, UM_UPDATE_EXISTING -> {
				for (var entry : archiveEntries.values()) {
					keepEntry(entry);
				}
			}
			case UM_SYNC -> {/* keepEntry() already called */}
			case UM_DIFF -> {
				for (SevenZEntry entry : archiveEntries.values()) {
					entry.setName("DELETED/".concat(entry.getName()));
					keepEntry(entry);
				}
			}
			case UM_IVCS -> {
				var newCommit = new iVCS.Commit();
				newCommit.date = System.currentTimeMillis();
				newCommit.desc = "incl update";

				iVCS.Commit activeCommit = vcsInstance.getActiveCommit();
				newCommit.upstreamCommitHashes = activeCommit == null ? new byte[0][] : new byte[][] { activeCommit.getHash() };

				for (var kv : archiveEntries.entrySet()) {
					SevenZEntry entry = SevenZEntry.ofNoAttribute(kv.getKey());
					entry.setAntiItem(true); // deleted
					entry.setIsDirectory(kv.getValue().isDirectory());
					/*if (kv.getValue().hasModificationTime()) {
						entry.setModificationTime(System.currentTimeMillis());
					}*/

					directories.add(entry);
				}

				List<SevenZEntry> vcsModified = new ArrayList<>(directories);
				vcsModified.addAll(symbolicLinks);

				for (var files : fileByCategory.values()) {
					for (var pair : files) {
						vcsModified.add(pair.getKey());
					}
				}

				if (!vcsModified.isEmpty()) {
					for (var link : symbolicLinks) link._setSize(1);
					vcsInstance.preCommit(newCommit, vcsModified, false);
					vcsCommit = newCommit;
					System.out.println("New commit for iVCS: "+newCommit);
				}
			}
		}

		return !fileByCategory.isEmpty() || !copyFromOriginal.isEmpty() || !directories.isEmpty();
	}

	private boolean isSame(File file, BasicFileAttributes attr, SevenZEntry entry) {
		if (attr.size() == entry.getSize()) {
			if (attr.size() == 0) return entry.isDirectory() == attr.isDirectory();

			try {
				return fastAppendCheck
						? (entry.hasModificationTime() && attr.lastModifiedTime().toMillis() <= entry.getModificationTime()) ||
							(entry.hasCrc32() && IOUtil.crc32File(file) == entry.getCrc32())
						: contentSame(file, entry);
			} catch (IOException e) {
				return false;
			}
		}
		return false;
	}
	private boolean contentSame(File file, SevenZEntry entry) {
		byte[] data = ArrayCache.getIOBuffer();
		byte[] data2 = ArrayCache.getIOBuffer();
		try (var in = new FileInputStream(file)) {
			try (var in2 = oldArchive.getInputStream(entry)) {
				while (true) {
					int r = in.read(data);
					if (r < 0) break;
					IOUtil.readFully(in2, data2, 0, r);

					// this MAY use AVX
					if (!Arrays.equals(data, 0, r, data2, 0, r))
						return false;
				}
			}
			return true;
		} catch (Exception e) {
			return false;
		} finally {
			ArrayCache.putArray(data);
			ArrayCache.putArray(data2);
		}
	}

	private void keepEntry(SevenZEntry entry) {
		WordBlock block = entry.block();
		if (block == null) directories.add(entry);
		else {
			copyFromOriginal.computeIfAbsent(block, Helpers.fnArrayList()).add(entry);
			keepSize += entry.getSize();
		}
	}

	boolean skipSubtree;
	/**
	 * 自定义文件处理函数
	 */
	protected void traverseFolder(File path, BiConsumer<File, BasicFileAttributes> callback) throws IOException {
		Files.walkFileTree(path.toPath(), new SimpleFileVisitor<>() {
			boolean isRootDirectory;

			@Override
			public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
				if (storeDirectories) {
					if (!isRootDirectory) isRootDirectory = true;
					else {
						callback.accept(dir.toFile(), attrs);
						if (skipSubtree) {
							System.out.println("Skip subtree: "+dir);
							skipSubtree = false;
							return FileVisitResult.SKIP_SUBTREE;
						}
					}
				}
				return FileVisitResult.CONTINUE;
			}

			@Override
			public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
				callback.accept(file.toFile(), attrs);
				return FileVisitResult.CONTINUE;
			}

			@Override
			public FileVisitResult visitFileFailed(Path file, IOException exc) {
				addWarning(exc);
				return FileVisitResult.CONTINUE;
			}

			@Override
			public FileVisitResult postVisitDirectory(Path dir, IOException exc) {
				if (exc != null) addWarning(exc);
				return FileVisitResult.CONTINUE;
			}
		});
	}
	private void addFile(FileCategory category, File file, String name, BasicFileAttributes attr) {
		SevenZEntry entry = SevenZEntry.of(name);

		if (attr.isDirectory()) entry.setIsDirectory(true);
		if (storeModifiedTime) entry.setModificationTime(attr.lastModifiedTime().toMillis());
		if (storeCreationTime) entry.setCreationTime(attr.creationTime().toMillis());
		if (storeAccessTime) entry.setAccessTime(attr.lastAccessTime().toMillis());

		entry._setSize(attr.size());

		if (storeSymbolicLinks) {
			if (attr.isSymbolicLink()) {
				try {
					String linkTarget = Files.readSymbolicLink(file.toPath()).toString();
					entry.setAttributes(entry.getWinAttributes() | FILE_ATTRIBUTE_REPARSE_POINT);

					symbolicLinks.add(entry);
					symbolicLinkValues.add(linkTarget);
				} catch (IOException e) {
					LOGGER.error("Read SymbolLink for "+file+" failed", e);
				}
				return;
			}
		}

		if (storeHardLinks) {
			String uuid = IOUtil.getHardLinkUUID(file.getAbsolutePath());
			if (uuid != null) {
				SevenZEntry prevEntry = hardlinkRef.putIfAbsent(uuid, entry);
				if (prevEntry != null) {
					entry.setAttributes(entry.getWinAttributes() | FILE_ATTRIBUTE_REPARSE_POINT | FILE_ATTRIBUTE_NORMAL);

					symbolicLinks.add(entry);
					symbolicLinkValues.add(prevEntry.getName());
					return;
				}
			}
		}

		if (attr instanceof DosFileAttributes dosAttr) {
			if (filterByArchiveAttribute) {
				if (!dosAttr.isArchive()) return;
			}

			if (storeAttributes) {
				int flag = 0;
				if (dosAttr.isDirectory()) flag |= FILE_ATTRIBUTE_DIRECTORY;
				if (dosAttr.isReadOnly()) flag |= FILE_ATTRIBUTE_READONLY;
				if (dosAttr.isHidden()) flag |= FILE_ATTRIBUTE_HIDDEN;
				if (dosAttr.isSystem()) flag |= FILE_ATTRIBUTE_SYSTEM;
				if (dosAttr.isArchive()) flag |= FILE_ATTRIBUTE_ARCHIVE;
				if (dosAttr.isSymbolicLink()) flag |= FILE_ATTRIBUTE_REPARSE_POINT;
				entry.setAttributes(flag);
			}

			if (storeSecurity) {
				byte[] sd = SecurityDescriptor.get(file.getAbsolutePath());
				if (sd != null) {
					System.out.println(sd.length);
					int size = aclIndex.size();
					int id = aclIndex.putOrGet(DynByteBuf.wrap(sd), size, -1);
					if (id < 0) {
						aclTable.add(sd);
						id = size;
					}
					aclMapping.putInt(entry, id);
				}
			}
		}

		if (category == FileCategory.EMPTY_OR_FOLDER) {
			directories.add(entry);
		} else {
			fileByCategory.computeIfAbsent(category, Helpers.fnArrayList()).add(new Pair<>(entry, file));
		}
	}

	public long getCompressibleSize() {return compressibleSize;}
	public int getParallelTaskCount() {return parallelableBlocks;}
	public int getSerialTaskCountMax() {
		long taskCountMax = 0;

		for (int i = parallelableBlocks + (firstIsUncompressed ? 1 : 0); i < chunks.size(); i++) {
			Chunk chunk = chunks.get(i);
			for (SevenZCodec coder : chunk.coders) {
				if (coder instanceof LZMA2 lzma2) {
					LZMA2ParallelEncoder asyncMan = lzma2.options().getParallelEncoder();
					if (asyncMan != null) {
						taskCountMax = Math.max((int) calcBlockCount(chunk.size, asyncMan.getBlockSize()), taskCountMax);
					}
				}
			}
		}

		assert taskCountMax < Integer.MAX_VALUE;
		return (int) taskCountMax;
	}

	public void organize() {
		SevenZAES encrypt = encryptionPassword == null ? null : new SevenZAES(encryptionPassword, encryptionPower, encryptionSaltLength);

		var uncompressed = fileByCategory.getOrDefault(FileCategory.UNCOMPRESSED, Collections.emptyList());
		if (!uncompressed.isEmpty()) {
			firstIsUncompressed = true;
			createChunk(new SevenZCodec[] {encrypt == null ? Copy.INSTANCE : encrypt}, uncompressed);
		}

		var lzma2 = options.getMode() == LZMA2Options.MODE_UNCOMPRESSED ? Copy.INSTANCE : new LZMA2(options);
		SevenZCodec[] regularMethods = encrypt == null ? new SevenZCodec[] {lzma2} : new SevenZCodec[] {lzma2, encrypt};

		if (options.getMode() != LZMA2Options.MODE_UNCOMPRESSED) {
			List<?> tempList = new ArrayList<>();

			long chunkSize = solidSize;
			if (chunkSize < 0) chunkSize = Long.MAX_VALUE;
			else if (autoSolidSize) {
				long base = compressibleSize / threads;
				//int remainder = (int) (compressibleSize % threads);
				chunkSize = MathUtils.clamp(Math.max(base + 1, (long) options.getDictSize() << 2), LZMA2Options.ASYNC_BLOCK_SIZE_MIN, LZMA2Options.ASYNC_BLOCK_SIZE_MAX);
				solidSize = chunkSize;
			}

			var regular = fileByCategory.remove(FileCategory.REGULAR);
			if (regular != null) makeChunks(regular, chunkSize, regularMethods, tempList);

			var executable = fileByCategory.remove(FileCategory.EXECUTABLE_X86);
			if (executable != null) makeChunks(executable, chunkSize, getBcjCoder(encrypt, lzma2), tempList);

			var audio = fileByCategory.remove(FileCategory.RAW_AUDIO);
			if (audio != null) {
				var delta = new Delta(2);
				SevenZCodec[] methods = encrypt == null ? new SevenZCodec[]{delta, lzma2} : new SevenZCodec[]{delta, lzma2, encrypt};
				makeChunks(audio, chunkSize, methods, tempList);
			}

			int startIndex = firstIsUncompressed ? 1 : 0;
			Arrays.sort(chunks.getInternalArray(), startIndex, chunks.size(),
					(o1, o2) -> {
						var a = (Chunk) o1;
						var b = (Chunk) o2;
						var aIsBig = isStreamParallelChunk(a);
						var bIsBig = isStreamParallelChunk(b);
						if (aIsBig != bIsBig) return bIsBig ? -1 : 1;
						return Long.compare(b.size, a.size);
					}
			);

			parallelableBlocks = 0;
			for (int i = chunks.size(); i > 0; i--) {
				if (!isStreamParallelChunk(chunks.get(i - 1))) {
					parallelableBlocks = i - startIndex;
					break;
				}
			}
		}

		hardlinkRef.clear();
		if (!symbolicLinks.isEmpty()) {
			List<String> contents = symbolicLinkValues;
			appendChunk(regularMethods, contents, symbolicLinks);
			contents.clear();
		}
	}
	private void makeChunks(List<Pair<SevenZEntry, File>> files, long chunkSize, SevenZCodec[] filters, List<?> tempList) {
		switch (chunkOrganizer) {
			case CONTEXT_AWARE -> organizeContentAware(files, chunkSize, filters, tempList);
			case BEST_SPEED -> organizeBestSpeed(files, chunkSize, filters, tempList);
			default -> organizeDefault(files, chunkSize, filters);
		}
	}

	/**
	 * 计算需要的任务数 blockCount = ceil(size / blockSize)
	 */
	private static long calcBlockCount(long size, long blockSize) {
		return (size + blockSize - 1) / blockSize;
	}

	private void organizeDefault(List<Pair<SevenZEntry, File>> candidates, long chunkSize, SevenZCodec[] filters) {
		SevenZCodec[] copyCoder = null;

		int startIndex = 0;
		long size = 0;
		for (int i = 0; i < candidates.size(); i++) {
			var entry = candidates.get(i).getKey();

			long nextSize = size + entry.getSize();
			if (nextSize > chunkSize) {
				createChunk(filters, candidates, startIndex, i, size);
				startIndex = i;

				size = entry.getSize();
			} else {
				size = nextSize;
			}

			// 当单个文件超过chunkSize时
			if (size > chunkSize && chunkOrganizer == ChunkOrganizer.GREEDY_FILL) {
				long blockSize = streamParallelOptions.getParallelEncoder().getBlockSize();
				if (copyCoder == null) {
					copyCoder = filters.clone();
					for (int j = 0; j < copyCoder.length; j++) {
						SevenZCodec coder = copyCoder[j];
						if (coder instanceof LZMA2 lzma2 && lzma2.options() == options) {
							copyCoder[j] = new LZMA2(streamParallelOptions);
						}
					}
				}

				long threadCount = calcBlockCount(size, blockSize) % threads;
				while (i + 1 < candidates.size()) {
					if (threadCount == 0) break;

					entry = candidates.get(i + 1).getKey();

					// 如果加了下一个文件会导致 taskCount 溢出
					long nextThreadCount = calcBlockCount(size + entry.getSize(), blockSize) % threads;
					if (nextThreadCount != 0 && nextThreadCount < threadCount) break;

					size += entry.getSize();
					i++;
				}

				createChunk(copyCoder, candidates, startIndex, i + 1, size);
				startIndex = i + 1;
				size = 0;
			}
		}
		createChunk(filters, candidates, startIndex, candidates.size(), size);
	}
	private void organizeBestSpeed(List<Pair<SevenZEntry, File>> candidates, long chunkSize, SevenZCodec[] filters, List<?> tempList) {
		// 1. 数据预处理：打包并分类
		var hugeCandidates = new ArrayList<Pair<SevenZEntry, File>>();
		var smallCandidates = RingBuffer.<Pair<SevenZEntry, File>>unbounded();

		for (int i = 0; i < candidates.size(); i++) {
			var file = candidates.get(i);

			if (file.getKey().getSize() > chunkSize) {
				hugeCandidates.add(file);
			} else {
				smallCandidates.add(file);
			}
		}

		smallCandidates.sort((a, b) -> Long.compare(b.getKey().getSize(), a.getKey().getSize()));

		// 对于每个 Huge 文件，如果它的 taskCount 不是 coreCount 的倍数，
		// 我们从 smallCandidates 中"借用"一些文件拼在后面，凑成整数倍。
		// 这样既解决了大文件的对齐问题，又顺便处理了一些小文件。
		if (!hugeCandidates.isEmpty()) {
			long blockSize = streamParallelOptions.getParallelEncoder().getBlockSize();
			var copyCoder = filters.clone();
			for (int j = 0; j < copyCoder.length; j++) {
				SevenZCodec coder = copyCoder[j];
				if (coder instanceof LZMA2 lzma2 && lzma2.options() == options) {
					copyCoder[j] = new LZMA2(streamParallelOptions);
				}
			}
			for (var huge : hugeCandidates) {
				List<Pair<SevenZEntry, File>> chunkMembers = Helpers.cast(tempList);
				chunkMembers.clear();
				chunkMembers.add(huge);
				long currentSize = huge.getKey().getSize();

				emptyOrGood: {
					long threadCount = calcBlockCount(currentSize, blockSize) % threads;
					// 尝试追加最大文件
					while (true) {
						Pair<SevenZEntry, File> imposter = smallCandidates.peekFirst();
						if (imposter == null) break emptyOrGood;
						long nextBlockCount = calcBlockCount(currentSize + imposter.getKey().getSize(), chunkSize);
						long nextThreadCount = nextBlockCount % threads;

						// 追加最大文件导致溢出
						if (nextThreadCount != 0 && nextThreadCount < threadCount) break;

						chunkMembers.add(imposter);
						smallCandidates.removeFirst();
						currentSize += imposter.getKey().getSize();

						threadCount = nextThreadCount;

						// 刚好
						if (nextThreadCount == 0) break emptyOrGood;
					}

					// 尝试追加最小文件
					while (true) {
						Pair<SevenZEntry, File> imposter = smallCandidates.peekLast();
						if (imposter == null) break emptyOrGood;

						long nextBlockCount = calcBlockCount(currentSize + imposter.getKey().getSize(), chunkSize);
						long nextThreadCount = nextBlockCount % threads;

						// 追加最小文件也会导致溢出，那么确实没得追加了
						if (nextThreadCount != 0 && nextThreadCount < threadCount) break;

						chunkMembers.add(imposter);
						smallCandidates.removeLast();
						currentSize += imposter.getKey().getSize();

						threadCount = nextThreadCount;

						if (nextThreadCount == 0) break emptyOrGood;
					}
				}

				createChunk(copyCoder, chunkMembers);
			}
		}

		// 使用 Bin Packing 算法，让每个 Chunk 尽可能接近 chunkSize 但不超过它

		// 简单的 Next-Fit 或者 First-Fit 算法
		// 由于已经降序排列，我们采用 First-Fit 策略装填
		tempList.clear();
		List<List<Pair<SevenZEntry, File>>> bins = Helpers.cast(tempList);
		List<LongValue> binSizes = new ArrayList<>();

		for (var p : smallCandidates) {
			boolean placed = false;
			long pSize = p.getKey().getSize();

			// 尝试放入现有的桶
			for (int i = 0; i < bins.size(); i++) {
				if (binSizes.get(i).value + pSize <= chunkSize) {
					bins.get(i).add(p);
					binSizes.get(i).value += pSize;
					placed = true;
					break;
				}
			}

			// 放不下，开新桶
			if (!placed) {
				List<Pair<SevenZEntry, File>> newBin = new ArrayList<>();
				newBin.add(p);
				bins.add(newBin);
				binSizes.add(new LongValue(pSize));
			}
		}

		// 将桶转换为 Chunk
		for (var bin : bins) {
			createChunk(filters, bin);
		}
		bins.clear();
	}
	private void organizeContentAware(List<Pair<SevenZEntry, File>> candidates, long chunkSize, SevenZCodec[] filters, List<?> tempList) {
		List<File> files = Helpers.cast(tempList);
		ToIntMap<File> lookup = new ToIntMap<>();
		for (int i = 0; i < candidates.size(); i++) {
			var file = candidates.get(i).getValue();
			files.add(file);
			lookup.put(file, i);
		}

		for (List<File> group : ContentAwareChunkOrganizer.organizeChunks(files, chunkSize)) {
			tempList.clear();
			List<Pair<SevenZEntry, File>> tempGroups = Helpers.cast(tempList);
			for (int i = 0; i < group.size(); i++) {
				tempGroups.add(candidates.get(lookup.getInt(group.get(i))));
			}

			createChunk(filters, tempGroups);
		}

		tempList.clear();
	}

	private void createChunk(SevenZCodec[] filters, List<Pair<SevenZEntry, File>> chunkMembers, int fromIndex, int toIndex, long size) {
		var entries = new SevenZEntry[toIndex - fromIndex];
		var data = new Object[toIndex - fromIndex];

		for (int i = fromIndex; i < toIndex; i++) {
			entries[i-fromIndex] = chunkMembers.get(i).getKey();
			data[i-fromIndex] = chunkMembers.get(i).getValue();
		}

		// assert?
		if (size == 0) return;

		Chunk block = new Chunk();
		block.coders = filters;
		block.entries = entries;
		block.data = data;
		block.size = size;
		chunks.add(block);
	}
	private void createChunk(SevenZCodec[] filters, List<Pair<SevenZEntry, File>> chunkMembers) {
		var entries = new SevenZEntry[chunkMembers.size()];
		var data = new Object[chunkMembers.size()];
		var size = 0L;

		for (int i = 0; i < chunkMembers.size(); i++) {
			entries[i] = chunkMembers.get(i).getKey();
			data[i] = chunkMembers.get(i).getValue();
			size += entries[i].getSize();
		}

		// assert?
		if (size == 0) return;

		Chunk block = new Chunk();
		block.coders = filters;
		block.entries = entries;
		block.data = data;
		block.size = size;
		chunks.add(block);
	}

	private static final class Chunk {
		SevenZCodec[] coders;
		SevenZEntry[] entries;
		Object[] data;
		long size;

		@Override
		public String toString() {
			return Arrays.toString(coders)+" with "+entries.length+" files / "+ TextUtil.scaledNumber1024(size);
		}
	}

	public void appendChunk(SevenZCodec[] coders, List<?> contents, List<SevenZEntry> entries) {
		Chunk block = new Chunk();

		block.coders = coders;
		block.data = contents.toArray(new Object[contents.size()]);
		block.entries = entries.toArray(new SevenZEntry[entries.size()]);
		block.size = entries.size();

		chunks.add(block);
	}

	private SevenZCodec[] getBcjCoder(SevenZAES encrypt, SevenZCodec lzma2) {
		if (useBCJ2) {
			LZMA lc0lp2 = new LZMA(new LZMA2Options().setLcLp(0, 2));
			return encrypt == null ?
					new SevenZCodec[] {BCJ2.INSTANCE, lzma2, null, lc0lp2, null, lc0lp2, null, null} :
					new SevenZCodec[] {BCJ2.INSTANCE, lzma2, encrypt, null, lc0lp2, encrypt, null, lc0lp2, encrypt, null, encrypt, null};
		} else {
			return encrypt == null ? new SevenZCodec[] {BCJ.X86, lzma2} : new SevenZCodec[] {BCJ.X86, lzma2, encrypt};
		}
	}

	private static boolean isStreamParallelChunk(Chunk chunk) {
		for (var coder : chunk.coders) {
			if (coder instanceof LZMA2 lzma2)
				return lzma2.options().getParallelEncoder() != null;
		}
		return false;
	}

	private TaskGroup group;
	public void interrupt() {
		var w = group;
		if (w != null) w.cancel(true);
		IOUtil.closeSilently(oldArchive);
	}
	public File compress(TaskGroup th, EasyProgressBar bar) throws IOException {
		group = th;
		SevenZPacker writer;
		File tmp;

		long totalUncompressedSize = 0;
		for (Chunk block : chunks) {
			totalUncompressedSize += block.size;
		}

		fastpathAppendOnly: if (vcsInstance == null) {
			if (keepSize > 0) {
				long totalSize = 0;
				for (SevenZEntry entry : oldArchive.getEntriesByPresentOrder()) {
					totalSize += entry.getSize();
				}

				if (keepSize == totalSize) {
					System.out.println("FastPath: 尾部追加");

					writer = oldArchive.append();

					List<SevenZEntry> emptyFiles = copyFromOriginal.get(null);
					if (emptyFiles != null) {
						HashSet<SevenZEntry> set = new HashSet<>(emptyFiles);

						ArrayList<SevenZEntry> files = writer.getEmptyFiles();
						for (int i = files.size() - 1; i >= 0; i--) {
							SevenZEntry file = files.get(i);
							if (!set.contains(file)) {
								writer.removeEmptyFile(i);
							}
						}
					}

					tmp = null;
					copyFromOriginal.clear();
					keepSize = 0;
					oldArchive = null;
					break fastpathAppendOnly;
				}
			}

			do {
				tmp = new File(outputDirectory, outputFilename +"."+Integer.toString((int) System.nanoTime()&Integer.MAX_VALUE,36)+".tmp");
			} while (tmp.isFile());

			if (splitSize == 0) {
				if (totalUncompressedSize > 1073741823) IOUtil.createSparseFile(tmp, totalUncompressedSize);
				writer = new SevenZPacker(tmp);
			} else {
				// .tmp.001
				writer = new SevenZPacker(CompositeSource.fixed(tmp, splitSize));
			}
		} else {
			tmp = null;
			writer = vcsInstance.beginWrite(oldArchive);
			oldArchive = null;
		}

		if (bar != null) {
			bar.setName("1/4 复制未修改的文件");
			bar.setUnit("B");
			bar.setProgress(0);
			bar.addTotal(keepSize);
		}

		try {
		// 1. 复制未修改的整个字块
		for (Iterator<Map.Entry<WordBlock, List<SevenZEntry>>> itr = copyFromOriginal.entrySet().iterator(); itr.hasNext(); ) {
			Map.Entry<WordBlock, List<SevenZEntry>> entry = itr.next();
			WordBlock block = entry.getKey();
			if (entry.getValue().size() == block.getFileCount()) {
				writer.copy(oldArchive, block);
				itr.remove();
				if (bar != null) bar.increment((int)block.getUncompressedSize());
			}
		}

		// 2. 重新压缩部分修改的字块
		for (Map.Entry<WordBlock, List<SevenZEntry>> entry : copyFromOriginal.entrySet()) {
			group.executeUnsafe(() -> {
				try (SevenZReader _in = oldArchive.forkReader()) {
					try (SevenZWriter _out = parallel(writer)) {
						_out.setCodecFrom(entry.getKey());

						List<SevenZEntry> value = entry.getValue();
						for (int i = 0; i < value.size(); i++) {
							SevenZEntry _file = value.get(i);

							_out.beginEntry(_file.clone());
							try (InputStream in = _in.getInputStream(_file)) {
								copyStreamWithProgress(in, _out, bar);
							}
						}
					}
				}
			});
		}
		group.await();

		if (oldArchive != null) {
			oldArchive.close();
			if (!keepOldArchive)
				Files.delete(new File(outputDirectory, outputFilename).toPath());
		}

		if (bar != null) {
			bar.setName("2/4 复制不压缩的文件");
			bar.setTotal(totalUncompressedSize);
			bar.setProgress(0);
		}

		// copy empty
		for (SevenZEntry empty : directories) writer.beginEntry(empty);

		// then copy uncompressed
		if (firstIsUncompressed) writeBlock(bar, chunks.remove(0), writer);

		// and copy compressed
		AtomicInteger blockCompleted = new AtomicInteger();
		if (bar != null) {
			bar.setName("3/4 压缩("+blockCompleted+"/"+ chunks.size()+")");
			bar.setProgress(0);
		}

		var asyncMan = options.getParallelEncoder();
		if (asyncMan != null && bar != null) {
			asyncMan.setProgressListener(bar::increment);
			bar.setName("3/4 压缩(StreamParallel)");
		}
		EasyProgressBar altProgressbar = asyncMan == null ? bar : null;

		writer.finishWordBlock();
		writer.setIgnoreClose(true);

		for (int i = 0; i < parallelableBlocks; i++) {
			var block = chunks.get(i);

			group.executeUnsafe(() -> {
				try (SevenZWriter writer1 = parallel(writer)) {
					writeBlock(altProgressbar, block, writer1);
				}
				if (altProgressbar != null) altProgressbar.setName("3/4 压缩("+blockCompleted.incrementAndGet()+"/"+ chunks.size()+")");
			});
		}
		group.await();
		for (int i = parallelableBlocks; i < chunks.size(); i++) {
			var block = chunks.get(i);
			writeBlock(altProgressbar, block, writer);
			if (altProgressbar != null) altProgressbar.setName("3/4 主线程压缩("+blockCompleted.incrementAndGet()+"/"+ chunks.size()+")");
		}

		if (storeSecurity) {
			System.out.println(aclTable.size()+" unique ACLs of "+aclMapping.size()+" files");
			writer.setArchiveProperties(out -> {
				out.put(26);

				var buf = new ByteList();
				for (byte[] acl : aclTable) {
					buf.putVUInt(acl.length).put(acl);
				}

				out.putVUInt(buf.readableBytes());
				out.put(buf);

				buf.release();
			});
			writer.setFileAttributes(out -> {
				out.put(26);

				var buf = new ByteList();

				//BitVector isAllDefined;
				//BOOL external;

				for (SevenZEntry file : writer.getFiles()) {
					int slot = aclMapping.getInt(file);
					buf.putInt(slot);
				}

				out.putVUInt(buf.readableBytes());
				out.put(buf);

				buf.release();
			});
		}

		// finally write header
		if (bar != null) bar.setName("4/4 写入文件头");

		List<SevenZCodec> coders = new ArrayList<>();
		if (compressHeader) coders.add(new LZMA2(new LZMA2Options(9).setDictSize(524288)));
		if (encryptionPassword != null && encryptFileName) coders.add(new SevenZAES(encryptionPassword, encryptionPower, encryptionSaltLength));
		if (coders.isEmpty()) {
			writer.setCompressHeader(-1);
		} else {
			writer.setCodec(coders.toArray(new SevenZCodec[coders.size()]));
			writer.setCompressHeader(0);
		}

		if (vcsInstance != null) {
			iVCS.Commit newCommit = vcsCommit;
			vcsInstance.postCommit(newCommit);

			iVCS.Branch activeBranch = vcsInstance.getActiveBranch();
			if (activeBranch != null) activeBranch.commitHash = newCommit.getHash();
			System.out.println("New commit for iVCS: "+newCommit);

			vcsInstance.write(writer, Collections.singletonList(vcsCommit));
		} else {
			writer.finish();
		}

		if (clearArchiveAttribute) {
			for (Chunk append : chunks) {
				for (Object item : append.data) {
					if (item instanceof File f) {
						DosFileAttributeView attr;
						try {
							attr = Files.getFileAttributeView(f.toPath(), DosFileAttributeView.class, storeSymbolicLinks ? READ_LINK : FOLLOW_LINK);
							attr.setArchive(false);
						} catch (IOException e) {
							LOGGER.error("无法删除文件 {} 的归档属性", e, f);
						}
					}
				}
			}
		}

		} finally {
			writer.setIgnoreClose(false);
			IOUtil.closeSilently(writer);
			IOUtil.closeSilently(oldArchive);
			if (bar != null) bar.end(group.isCancelled() ? "部分成功" : "成功");
			if (tmp != null && !keepOldArchive) Files.move(tmp.toPath(), new File(outputDirectory, outputFilename).toPath());
		}

		return tmp;
	}

	private SevenZWriter parallel(SevenZPacker qfw) throws IOException {
		return threads == 1 ? qfw : qfw.newParallelWriter();
	}

	private void writeBlock(@Nullable EasyProgressBar bar, Chunk block, SevenZWriter writer) throws IOException {
		writer.setCodec(block.coders);
		SevenZEntry[] file = block.entries;
		for (int i = 0; i < file.length; i++) {
			if (group.isCancelled()) return;

			writer.beginEntry(file[i]);
			OutputStream newWriter = writer;
			if (vcsCommit != null) newWriter = vcsInstance.commitFile(vcsCommit, file[i], newWriter, null);

			if (block.data[i] instanceof File data) {
				try (FileInputStream in = new FileInputStream(data)) {
					copyStreamWithProgress(in, newWriter, bar);
				} catch (InterruptedIOException e) {
					group.cancel();
					return;
				} catch (IOException e) {
					e.printStackTrace();
				}
			} else if (block.data[i] instanceof DynByteBuf buf) {
				if (bar != null) bar.increment(buf.readableBytes());
				buf.writeToStream(newWriter);
				buf.release();
			} else {
				ByteList buf = IOUtil.getSharedByteBuf().putUTFData(block.data[i].toString());
				if (bar != null) bar.increment(buf.readableBytes());
				buf.writeToStream(newWriter);
			}

			if (/*vcsCommit != null*/newWriter != writer) newWriter.close();
		}
	}

	public static void copyStreamWithProgress(InputStream in, OutputStream out, EasyProgressBar bar) throws IOException {
		byte[] tmp = ArrayCache.getIOBuffer();
		try {
			while (true) {
				if (Thread.interrupted()) throw new InterruptedIOException();

				int len = in.read(tmp);
				if (len < 0) break;
				out.write(tmp, 0, len);

				if (bar != null) bar.increment(len);
			}
		} finally {
			ArrayCache.putArray(tmp);
		}
	}
}