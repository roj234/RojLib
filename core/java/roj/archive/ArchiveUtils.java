package roj.archive;

import roj.collect.HashSet;
import roj.io.source.CompositeSource;
import roj.io.source.FileSource;
import roj.io.source.Source;

import java.io.File;
import java.io.IOException;
import java.nio.file.attribute.FileTime;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @author Roj234
 * @since 2024/1/3 15:43
 */
public class ArchiveUtils {
	// 有意的不包含zip格式
	public static final HashSet<String> INCOMPRESSIBLE_FILE_EXT = new HashSet<>("7z", "rar", "xz", "txz", "lzma", "lzma2", "bz2", "bzip2", "tbz", "tbz2", "gz", "gzip", "esd", "wim", "woff", "woff2", "qz");
	public static final Pattern SPLIT_ARCHIVE_PATTERN = Pattern.compile("\\.[0zZ]01$");

	public static Source tryOpenSplitArchive(File file, boolean readonly) throws IOException {
		Matcher m = SPLIT_ARCHIVE_PATTERN.matcher(file.getName());
		if (m.find()) {
			String s = file.getAbsolutePath();
			return CompositeSource.dynamic(new File(s.substring(0, s.length() + m.start() - m.end())), !readonly);
		} else {
			return new FileSource(file, !readonly);
		}
	}

	private static final long WINDOWS_TIME_NOT_AVAILABLE = Long.MIN_VALUE;
	public static long readWinTime(long time) { return time == WINDOWS_TIME_NOT_AVAILABLE ? 0 : winTime2JavaTime(time); }
	private static final long WINDOWS_EPOCH_IN_MICROSECONDS = -11644473600000000L;
	public static long winTime2JavaTime(long wtime) { return (wtime / 10 + WINDOWS_EPOCH_IN_MICROSECONDS) / 1000; }
	public static long java2WinTime(long time) { return (time*1000 - WINDOWS_EPOCH_IN_MICROSECONDS) * 10; }
	public static FileTime winTime2FileTime(long wtime) { return FileTime.from(wtime / 10 + WINDOWS_EPOCH_IN_MICROSECONDS, TimeUnit.MICROSECONDS); }
	public static long fileTime2WinTime(FileTime time) { return (time.to(TimeUnit.MICROSECONDS) - WINDOWS_EPOCH_IN_MICROSECONDS) * 10; }
}