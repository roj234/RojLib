package roj.io;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import roj.RojLib;
import roj.collect.ArrayList;
import roj.collect.BitSet;
import roj.compiler.plugins.annotations.Attach;
import roj.concurrent.FastThreadLocal;
import roj.config.node.IntValue;
import roj.crypt.Base64;
import roj.crypt.CRC32;
import roj.reflect.Reflection;
import roj.reflect.Unsafe;
import roj.text.*;
import roj.util.ArrayCache;
import roj.util.ByteList;
import roj.util.Helpers;
import roj.util.NativeMemory;
import roj.util.function.ExceptionalConsumer;

import java.io.*;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BiConsumer;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.Predicate;

import static roj.reflect.Unsafe.U;

/**
 * @author Roj234
 * @since 2021/5/29 22:1
 */
public final class IOUtil {
	public static final FastThreadLocal<IOUtil> SharedBuf = FastThreadLocal.withInitial(IOUtil::new);

	// region ThreadLocal part
	public final byte[] singleByteBuffer = new byte[1];

	public final CharList charBuf = new CharList(1024);
	public final ByteList byteBuf = new ByteList(1024);

	private final ByteList.Slice slice = new ByteList.Slice();
	@Deprecated
	public final ByteList.Slice slice1 = new ByteList.Slice();

	public final StringBuilder numberHelper = new StringBuilder(32);

	public static byte[] encodeUTF8(CharSequence str) {return getSharedByteBuf().putUTFData(str).toByteArray();}
	public static String decodeUTF8(byte[] bytes) {
		var TL = SharedBuf.get();
		var sb = TL.charBuf; sb.clear();
		FastCharset.UTF8().decodeFixedIn(TL.slice.setR(bytes, 0, bytes.length), bytes.length, sb);
		return sb.toString();
	}

	public static String encodeBase64(byte[] bytes) {
		var TL = SharedBuf.get();
		var sb = TL.charBuf; sb.clear();
		return Base64.encode(TL.slice.setR(bytes,0, bytes.length), sb).toString();
	}
	public static ByteList decodeBase64(CharSequence str) {
		var buf = getSharedByteBuf();
		Base64.decode(str, 0, str.length(), buf, Base64.B64_CHAR_REV);
		return buf;
	}

	public static String encodeHex(byte[] bytes) {return TextUtil.bytes2hex(bytes, getSharedCharBuf()).toString();}
	@Deprecated
	public static byte[] decodeHex(CharSequence str) {return TextUtil.hex2bytes(str);}

	public static void writeSingleByteHelper(OutputStream out, int b) throws IOException {
		byte[] b1 = SharedBuf.get().singleByteBuffer;
		b1[0] = (byte) b;
		out.write(b1, 0, 1);
	}
	public static int readSingleByteHelper(InputStream in) throws IOException {
		byte[] b1 = SharedBuf.get().singleByteBuffer;
		return in.read(b1, 0, 1) < 0 ? -1 : b1[0] & 0xFF;
	}

	public static IOException rethrowAsIOException(InterruptedException e) {
		var ex = new InterruptedIOException();
		ex.setStackTrace(e.getStackTrace());
		return ex;
	}

	@Deprecated public ByteList wrap(byte[] b) { return slice.setR(b,0,b.length); }
	@Deprecated public ByteList wrap(byte[] b, int off, int len) { return slice.setR(b,off,len); }
	// endregion

	public static ByteList getSharedByteBuf() {
		ByteList o = SharedBuf.get().byteBuf; o.clear();
		return o;
	}

	public static CharList getSharedCharBuf() {
		CharList o = SharedBuf.get().charBuf; o.clear();
		return o;
	}

	//region InputStream/TextReader
	public static byte[] getResourceIL(String path) throws IOException { return getResource(path, IOUtil.class); }
	public static byte[] getResource(String path) throws IOException { return getResource(path, Reflection.getCallerClass(2)); }
	public static byte[] getResource(String path, Class<?> caller) throws IOException {
		var in = caller.getClassLoader().getResourceAsStream(path);
		if (in == null) throw new FileNotFoundException(path+" is not in jar "+caller.getName());
		return new ByteList(Math.max(in.available(), 4096)).readStreamFully(in).toByteArrayAndFree();
	}
	public static String getTextResourceIL(String path) throws IOException { return getTextResource(path, IOUtil.class); }
	public static String getTextResource(String path) throws IOException { return getTextResource(path, Reflection.getCallerClass(2)); }
	public static String getTextResource(String path, Class<?> caller) throws IOException {
		var in = caller.getClassLoader().getResourceAsStream(path);
		if (in == null) throw new FileNotFoundException(path+" is not in jar "+caller.getName());
		try (var r = TextReader.from(in, StandardCharsets.UTF_8)) {
			return new CharList(Math.max(in.available()/3, 4096)).readFully(r).toStringAndFree();
		}
	}

	public static byte[] read(File file) throws IOException {
		long len = file.length();
		if (len > Integer.MAX_VALUE) throw new IOException("file > 2GB");
		byte[] data = new byte[(int) len];
		try (FileInputStream in = new FileInputStream(file)) {
			readFully(in, data);
			return data;
		}
	}
	public static byte[] read(InputStream in) throws IOException { return getSharedByteBuf().readStreamFully(in).toByteArray(); }


	public static String readUTF(File f) throws IOException {
		try (var r = TextReader.from(f, StandardCharsets.UTF_8)) {
			return f.length() > 1048576L ? read1(r) : read(r);
		}
	}
	public static String readUTF(InputStream in) throws IOException {
		try (var r = TextReader.from(in, StandardCharsets.UTF_8)) {
			return in.available() > 1048576L ? read1(r) : read(r);
		}
	}
	public static String readString(File f) throws IOException {
		try (var r = TextReader.auto(f)) {
			return f.length() > 1048576L ? read1(r) : read(r);
		}
	}
	public static String readString(InputStream in) throws IOException {
		try (var r = TextReader.auto(in)) {
			return in.available() > 1048576L ? read1(r) : read(r);
		}
	}

	public static String read(Reader r) throws IOException { return getSharedCharBuf().readFully(r).toString(); }
	private static String read1(Reader r) throws IOException { return new CharList(1048576).readFully(r).toStringAndFree(); }

	@Attach
	public static void readFully(InputStream in, byte[] b) throws IOException {readFully(in, b, 0, b.length);}
	@Attach
	public static void readFully(InputStream in, byte[] b, int off, int len) throws IOException {
		while (len > 0) {
			int r = in.read(b, off, len);
			if (r < 0) throw new EOFException("Premature end of stream, remaining "+len+" bytes");
			len -= r;
			off += r;
		}
	}

	@Attach("skipAlt")
	public static long skip(InputStream in, long count) throws IOException {
		long start = count;
		for(;;) {
			long i = in.skip(count);
			if (i == 0) break;
			count -= i;
			if (count == 0) break;
		}
		return start-count;
	}
	@Attach
	public static void skipFully(InputStream in, long count) throws IOException {
		if (skip(in, count) < count) throw new EOFException(in+"无法跳过"+count+"个字节");
	}

	public static void copyStream(InputStream in, OutputStream out) throws IOException {
		ByteList b = getSharedByteBuf();
		byte[] data = b.list;

		while (true) {
			int len = in.read(data);
			if (len < 0) break;
			out.write(data, 0, len);
		}
	}
	//endregion

	/**
	 * 获取这个类所属的jar
	 */
	public static File getJar(Class<?> clazz) {
		URL location = clazz.getProtectionDomain().getCodeSource().getLocation();
		if (location == null) return null;
		String loc = location.getPath();
		if (loc.startsWith("file:")) loc = loc.substring(5);
		int i = loc.lastIndexOf('!');
		loc = loc.substring(loc.startsWith("/")?1:0, i<0?loc.length():i);
		try {
			loc = URICoder.decodeURI(loc);
			i = loc.lastIndexOf('#');
			loc = loc.substring(0, i<0?loc.length():i);
			return new File(loc).getCanonicalFile();
		} catch (Exception e) {
			e.printStackTrace();
			return null;
		}
	}

	public static String randomFileName() {return Long.toString(ThreadLocalRandom.current().nextLong(), 36);}

	public static void copyFile(File source, File target) throws IOException {Files.copy(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);}

	@Attach("listAll")
	public static List<File> listFiles(File file) {return listFiles(file, Helpers.alwaysTrue());}
	@Attach("listAll")
	@Deprecated
	public static List<File> listFiles(File file, Predicate<File> predicate) {
		List<File> files = ArrayList.hugeCapacity(0);
		listPaths(file, (pathname, attr) -> {
			File t = new File(pathname);
			if (predicate.test(t)) files.add(t);
		});
		return files;
	}

	public static List<File> listFiles(File file, BiPredicate<String, BasicFileAttributes> predicate) {
		return listFiles(file, ArrayList.hugeCapacity(0), predicate);
	}
	public static <T extends Collection<File>> T listFiles(File file, T files, BiPredicate<String, BasicFileAttributes> predicate) {
		listPaths(file, (path, attr) -> {
			if (predicate.test(path, attr)) {
				files.add(new File(path));
			}
		});
		return files;
	}
	public static void listPaths(File path, BiConsumer<String, BasicFileAttributes> consumer) {
		try {
			if (!path.exists()) return;
			Files.walkFileTree(path.toPath(), new SimpleFileVisitor<Path>() {
				@Override
				public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
					consumer.accept(file.toString(), attrs);
					return FileVisitResult.CONTINUE;
				}
			});
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	public static void digestFile(File file, long length, MessageDigest digest) throws IOException {
		byte[] tmp = ArrayCache.getIOBuffer();
		try (var in = new FileInputStream(file)) {
			while (length > 0) {
				int r = in.read(tmp);
				if (r < 0) break;

				r = (int) Math.min(length, r);
				length -= r;

				digest.update(tmp, 0, r);
			}
		}
		ArrayCache.putArray(tmp);
	}

	public static int crc32File(File file) throws IOException {
		byte[] data = ArrayCache.getIOBuffer();
		try (var in = new FileInputStream(file)) {
			int crc = CRC32.initial;
			while (true) {
				int r = in.read(data);
				if (r < 0) break;
				crc = CRC32.update(crc, data, 0, r);
			}
			return CRC32.finish(crc);
		} finally {
			ArrayCache.putArray(data);
		}
	}

	/**
	 * 将Maven包ID转换为文件路径
	 * @param path groupId:artifactId:version[:classifier][@packaging]
	 */
	public static CharList mavenIdToPath(CharSequence path) {
		int i = TextUtil.indexOf(path, ':');
		CharList sb = new CharList().append(path).replace('.', '/', 0, i);
		List<String> parts = TextUtil.split(new ArrayList<>(4), sb, ':');

		String ext = "jar";
		String lastPart = parts.get(parts.size() - 1);
		int extPos = lastPart.lastIndexOf('@');
		if (extPos != -1) {
			ext = lastPart.substring(extPos + 1);
			parts.set(parts.size() - 1, lastPart.substring(0, extPos));
		}

		sb.clear();
		sb.append(parts.get(0)).append('/') // groupId
		  .append(parts.get(1)).append('/') // artifactId
		  .append(parts.get(2)).append('/') // version
		  .append(parts.get(1)).append('-').append(parts.get(2)); // artifactId-version

		if (parts.size() > 3) {
			sb.append('-').append(parts.get(3)); // classifier
		}

		return sb.append('.').append(ext);
	}

	//region 文件名和路径工具
	/**
	 * 在原文件名和扩展名之间插入后缀
	 * 例如: test.xml -> test_processed.xml
	 */
	public static File addSuffix(File base, String suffix) {
		var path = base.getName();
		int i = path.lastIndexOf('.');
		return new File(base.getParentFile(), i < 0 ? path+suffix : path.substring(0, i)+suffix+path.substring(i));
	}

	/**
	 * 获取小写的文件扩展名 (不含点)
	 * 例如: "index.HtMl" -> "html"
	 */
	public static String getExtension(String path) {
		path = getName(path);
		int i = path.lastIndexOf('.');
		return i < 0 ? "" : path.substring(i+1).toLowerCase(Locale.ROOT);
	}

	/**
	 * 获取小写的文件扩展名 (含点)
	 * 例如: "index.HtMl" -> ".html"
	 */
	public static String getExtensionWithDot(String path) {
		path = getName(path);
		int i = path.lastIndexOf('.');
		return i < 0 ? "" : path.substring(i).toLowerCase(Locale.ROOT);
	}

	/**
	 * 获取基础文件名 (不含路径，不含扩展名)
	 * 例如: "/path/to/script.sh" -> "script"
	 */
	public static String getBaseName(String path) {
		path = getName(path);
		int i = path.lastIndexOf('.');
		return i < 0 ? path : path.substring(0, i);
	}

	/**
	 * 获取完整文件名 (不含路径)
	 * 例如: "/path/to/image.png" -> "image.png"
	 */
	public static String getName(String path) {
		int i = path.lastIndexOf('/');
		i = Math.max(i, path.lastIndexOf('\\'));
		return path.substring(i+1);
	}

	/**
	 * 获取目录前缀的长度，用于切割相对路径
	 */
	public static int getPrefixLength(File path) {
		String path1 = path.getAbsolutePath();
		int length = path1.length();
		// windows 上 "C:\" 时为真
		return path1.endsWith(File.separator)/* && isWindowsPartition(path1) */ ? length : length+1;
	}

	/**
	 * 路径过滤函数，处理不可信的用户输入路径.
	 * 规范化路径，解析 . 和 .. 并统一分隔符
	 */
	public static String normalize(String path) {
		CharList sb = getSharedCharBuf();

		int end = 0;
		int i = 0;
		while (i < path.length()) {
			var c = path.charAt(i);
			if (c < 32) throw new IllegalArgumentException("Illegal character in path");
			i++;
			end = i;
		}

		sb.append(path, 0, end)
		  .replace(File.separatorChar, '/') // 在linux上，\是合法的文件名组成部分，而不是路径分隔符，而windows上二者皆是
		  .replaceRecursively("//", "/");

		var paths = TextUtil.split(sb, '/');
		if (sb.endsWith("/")) paths.add("");
		for (int j = 0; j < paths.size();) {
			var s = paths.get(j);
			if (s.equals(".")) {
				paths.remove(j);
			} else if (s.equals("..")) {
				paths.remove(j);
				if (j > 0 && !isWindowsPartition(paths.get(j-1))) paths.remove(--j);
			} else {
				j++;
			}
		}
		if (!paths.isEmpty() && paths.get(0).isEmpty())
			paths.remove(0);
		return TextUtil.join(paths, "/");
	}

	/**
	 * 解析路径，如果 child 是绝对路径则直接返回，否则相对于 base 解析
	 */
	public static File resolve(File base, String child) {
		File pathFile = new File(child);
		if (pathFile.isAbsolute()) {
			return pathFile;
		} else {
			return new File(base, child);
		}
	}

	/**
	 * 安全解析路径，防止路径穿越攻击
	 * @param base 根目录
	 * @param child 外部输入的子路径
	 * @return 越权则返回 null
	 */
	@Nullable
	public static File resolveSafe(File base, String child) {
		File file = new File(base, child);
		try {
			if (file.getCanonicalPath().startsWith(base.getCanonicalPath())) return file;
		} catch (IOException ignored) {}
		return null;
	}

	private static boolean isWindowsPartition(String path) {
		return path.length() >= 2 && path.charAt(1) == ':' && ((path.charAt(0) >= 'A' && path.charAt(0) <= 'Z') || (path.charAt(0) >= 'a' && path.charAt(0) <= 'z'));
	}

	/**
	 * 计算 child 相对于 base 的相对路径
	 * @param child1 源路径
	 * @param base1 基准路径
	 * @return 相对路径，如果无法转换(如windows上不同分区)则返回null
	 */
	@Nullable
	public static String relativize(String base1, String child1) {
		var base = TextUtil.split(normalize(base1), '/');
		var child = TextUtil.split(normalize(child1), '/');

		int baseCount = base.size();
		int childCount = child.size();

		// skip matching names
		int n = Math.min(baseCount, childCount);
		int i = 0;
		while (i < n) {
			if (!base.get(i).equals(child.get(i)))
				break;
			i++;
		}
		// 其实不太可能触发…… 现在传的都是绝对路径
		if (i == 0 && (isWindowsPartition(child.get(i)) || isWindowsPartition(base.get(i)))) return null;

		// remaining elements in child
		List<String> childRemaining = child.subList(i, childCount);

		// matched all of base
		if (i == baseCount) return TextUtil.join(childRemaining, "/");

		List<String> baseRemaining = base.subList(i, baseCount);

		var sb = IOUtil.getSharedCharBuf().padEnd("../", baseRemaining.size() * 3);

		for (int j = 0; j < childRemaining.size(); j++) {
			sb.append(childRemaining.get(j)).append('/');
		}

		sb.setLength(sb.length()-1);
		return sb.toString();
	}

	private static final BitSet FILE_NAME_INVALID = BitSet.from("%\\/:*?\"<>|"), FILE_PATH_INVALID = BitSet.from("%:*?\"<>|");
	public static String escapeFilePath(CharSequence src) { return escape(new CharList(), src, FILE_PATH_INVALID).toStringAndFree(); }
	public static String escapeFileName(CharSequence src) { return escape(new CharList(), src, FILE_NAME_INVALID).toStringAndFree(); }
	private static CharList escape(CharList sb, CharSequence src, BitSet blacklist) {
		for (int i = 0; i < src.length(); i++) {
			char c = src.charAt(i);
			if (!blacklist.contains(c)) sb.append(c);
			else sb.append("%").append(TextUtil.b2h(c>>>4)).append(TextUtil.b2h(c&15));
		}
		return sb;
	}
	//endregion
	//region 文件创建、复制和删除工具
	public static boolean isWritable(File file) {
		try {
			new RandomAccessFile(file, "rw").close();
		} catch (IOException e) {
			return false;
		}
		return true;
	}

	@Attach
	public static void allocateFile(File file, long length) throws IOException {
		if (file.length() != length) {
			try (var raf = new RandomAccessFile(file, "rw")) {
				raf.setLength(length);
			}
		} else if (length == 0) file.createNewFile();
	}

	@Attach
	public static void createSparseFile(File file, long length) throws IOException {
		if (file.length() != length) {
			Files.deleteIfExists(file.toPath());
			try (var fc = FileChannel.open(file.toPath(), StandardOpenOption.WRITE, StandardOpenOption.CREATE_NEW, StandardOpenOption.SPARSE).position(length-1)) {
				fc.truncate(length);
				fc.write(ByteBuffer.wrap(new byte[1]));
			}
		} else if (length == 0) file.createNewFile();
	}

	/**
	 * 文件内部复制, 返回时fc的位置不确定
	 */
	public static long copyInternal(FileChannel fc, long from, long to, long len) throws IOException {
		if (from == to || len == 0) return len;

		if (from > to ? to + len <= from : from + len <= to) { // 区间不交叉
			return fc.transferTo(from, len, fc.position(to));
		}

		if (len <= 1048576) {
			ByteBuffer direct = ByteBuffer.allocateDirect((int) len);
			try {
				direct.position(0).limit((int) len);
				fc.read(direct, from);
				direct.position(0);
				return fc.position(to).write(direct);
			} finally {
				NativeMemory.freeDirectBuffer(direct);
			}
		} else {
			File tmpPath = new File(System.getProperty("java.io.tmpdir"));
			File tmpFile;
			do {
				tmpFile = new File(tmpPath, "FUT~"+randomFileName()+".tmp");
			} while (tmpFile.exists());

			try (FileChannel ct = FileChannel.open(tmpFile.toPath(),
					StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, StandardOpenOption.READ, StandardOpenOption.DELETE_ON_CLOSE)) {
				fc.transferTo(from, len, ct.position(0));
				return ct.transferTo(0, len, fc.position(to));
			}
		}
	}

	public static void copyOrMove(File from, File to, boolean move) throws IOException {
		if (from.equals(to)) return;

		CopyOption[] OPTIONS = {StandardCopyOption.REPLACE_EXISTING};

		if (!from.isDirectory()) {
			if (move) Files.move(from.toPath(), to.toPath(), OPTIONS);
			else Files.copy(from.toPath(), to.toPath(), OPTIONS);
			return;
		}

		int prefixLength = getPrefixLength(from);
		Files.createDirectories(to.toPath());
		Files.walkFileTree(from.toPath(), new SimpleFileVisitor<>() {
			@Override
			public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
				String pathname = dir.toString();
				if (pathname.length() > prefixLength) {
					pathname = pathname.substring(prefixLength);
					if (!new File(to, pathname).mkdir()) {
						return FileVisitResult.TERMINATE;
					}
				}
				return FileVisitResult.CONTINUE;
			}

			@Override
			public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
				Path target = to.toPath().resolve(file.toString().substring(prefixLength));
				if (move) Files.move(file, target, OPTIONS);
				else Files.copy(file, target, OPTIONS);
				return FileVisitResult.CONTINUE;
			}

			@Override
			public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
				if (move) Files.delete(dir);
				return FileVisitResult.CONTINUE;
			}
		});
	}

	// 非线程安全，主要目的是写入文件时抛出异常，不会造成文件内容丢失
	public static boolean writeAtomically(File path, String name, ExceptionalConsumer<File, IOException> consumer) throws IOException {
		String rand = randomFileName();

		path.mkdirs();
		var file = new File(path, name);
		var writeTemp = new File(path, name+"."+rand+".new");
		var deleteTemp = new File(path, name+"."+rand+".old");

		if (!file.isFile()) writeTemp = file;

		try {
			consumer.accept(writeTemp);
		} catch (Throwable e) {
			if (!writeTemp.delete()) {
				writeTemp.deleteOnExit();
			}
			throw e;
		}
		if (writeTemp == file) return true;

		CopyOption[] stupidVarargs = {StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING};

		Files.move(file.toPath(), deleteTemp.toPath(), stupidVarargs);
		Files.move(writeTemp.toPath(), file.toPath(), stupidVarargs);
		Files.deleteIfExists(deleteTemp.toPath());
		return true;
	}

	@Attach
	public static boolean deleteRecursively(File path) {return deleteRecursively(path.toPath());}
	@Attach
	public static boolean deleteRecursively(Path path) {
		try {
			Files.walkFileTree(path, new SimpleFileVisitor<>() {
				@Override
				public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
					Files.delete(file);
					return FileVisitResult.CONTINUE;
				}

				@Override
				public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
					Files.delete(dir);
					if (exc != null) throw exc;
					return FileVisitResult.CONTINUE;
				}
			});
		} catch (IOException e) {
			return false;
		}
		return true;
	}

	@Attach
	public static int deleteEmptyDirectories(File path) {return deleteEmptyDirectories(path.toPath());}
	@Attach
	public static int deleteEmptyDirectories(Path path) {
		IntValue deleteCount = new IntValue();
		try {
			Files.walkFileTree(path, new SimpleFileVisitor<>() {
				int depth;
				@Override
				public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {depth++;return FileVisitResult.CONTINUE;}
				@Override
				public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
					if (--depth > 0) {
						try {
							Files.delete(dir);
							deleteCount.value++;
						} catch (DirectoryNotEmptyException ignored) {}
					}
					return FileVisitResult.CONTINUE;
				}
			});
		} catch (IOException ignored) {}
		return deleteCount.value;
	}
	//endregion

	@Attach
	public static void closeSilently(@Nullable AutoCloseable c) {
		if (c != null) try {
			c.close();
		} catch (Throwable e) {
			new UnsupportedOperationException(c.getClass()+"在关闭时抛出了异常！", e).printStackTrace();
		}
	}

	/**
	 * 获取硬链接的唯一标识符用于去重
	 * @param filePath 文件绝对路径
	 * @return 文件唯一标识符（原始二进制数据，可能包含不可打印字符），若无硬链接或失败返回null
	 */
	public static @Nullable String getHardLinkUUID(@NotNull String filePath) {
		return RojLib.hasNative(RojLib.WIN32) ? getHardLinkUUID0(Objects.requireNonNull(filePath)) : null;
	}
	public static boolean makeHardLink(@NotNull String link, @NotNull String existing) {
		RojLib.fastJni();
		return makeHardLink0(Objects.requireNonNull(link), Objects.requireNonNull(existing));
	}
	private static native String getHardLinkUUID0(String filePath);
	private static native boolean makeHardLink0(String link, String existing);

	public static void ioWait(AutoCloseable closeable, Object waiter) throws IOException {
		synchronized (waiter) {
			try {
				waiter.wait();
			} catch (InterruptedException e) {
				closeSilently(closeable);
				throw rethrowAsIOException(e);
			}
		}
	}

	private static long pendingKeys_offset;
	/**
	 * 创建一个同步的{@link WatchService}，不需要独立轮询线程，直接接收事件回调。<br>
	 * 当JDK版本不支持时，将自动创建守护线程进行轮询。
	 *
	 * <p><b>警告：</b>回调必须遵守以下约束：
	 * <ol>
	 *   <li>不应进行耗时操作——可能造成事件丢失</li>
	 *   <li>禁止在回调调用{@link WatchService#close()}或{@link WatchKey#cancel()}——必然导致死锁</li>
	 *   <li>禁止在任何线程调用{@link WatchService#take()}或{@link WatchService#poll()}——会无限期等待</li>
	 * </ol>
	 *
	 * @param threadName 当需要创建轮询线程时使用的名称（null时自动生成）
	 * @param consumer 事件消费者，接收WatchKey并处理文件事件
	 * @throws IOException 如果WatchService创建失败
	 */
	@ApiStatus.Experimental
	public static WatchService syncWatchPoll(String threadName, Consumer<WatchKey> consumer) throws IOException {
		var watcher = FileSystems.getDefault().newWatchService();
		try {
			long off;
			if ((off = pendingKeys_offset) == 0) {
				off = pendingKeys_offset = Unsafe.objectFieldOffset(Class.forName("sun.nio.fs.AbstractWatchService"), "pendingKeys", LinkedBlockingDeque.class);
			}
			if (off > 0) {
				U.putReference(watcher, off, new LinkedBlockingDeque<>() {
					@Override
					public boolean offer(Object o) {
						consumer.accept((WatchKey) o);
						return true;
					}
				});
				return watcher;
			}
		} catch (Exception e) {
			pendingKeys_offset = -1;
		}

		var t = new Thread(() -> {
			while (true) {
				WatchKey key;
				try {
					key = watcher.take();
					if (key.watchable() == null) break;
				} catch (Exception e) {
					break;
				}
				consumer.accept(key);
			}
		});
		if (threadName == null) threadName = "RojLib 文件监控/后备 #"+watcher.hashCode();
		t.setName(threadName);
		t.setDaemon(true);
		t.start();

		return watcher;
	}
}