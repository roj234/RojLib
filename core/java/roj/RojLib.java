package roj;

import roj.ci.annotation.ReplaceConstant;
import roj.util.OS;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;

/**
 * @author Roj233
 * @since 2021/10/15 12:57
 */
@ReplaceConstant
public final class RojLib {
	public static Object getObj(String key) {return System.getProperties().get(key);}
	public static void setObj(String key, Object val) {System.getProperties().put(key, val);}

	//region FastJNI
	public static boolean fastJni() {return false;}
	/**
	 * FastJNI
	 */
	public static void linkLibrary(Class<?> caller, int bit) {

	}
	//endregion

	private static final String LibName = "libOmniJni";
	private static File LibFile = new File("../RojLib/core/resources/"+LibName+".dll");

	public static final boolean ASM_DEBUG = false;
	public static final boolean IS_DEV;
	public static final int GENERIC = 0, WIN32 = 1, ANSI_READBACK = 2, SHARED_MEMORY = 3, AES_NI = 5, FastJNI = 6;
	public static boolean hasNative(int bit) {return (bits&(1L << bit)) != 0;}

	private static final long bits;

	static {
		IS_DEV = LibFile.isFile();
		long t = 0;
		long mask = ~Long.getLong("roj.nativeDisableBit", 0);

		if (mask != 0) try {
			if (loadLibrary()) t = init();
		} catch (Throwable e) {
			e.printStackTrace();
		}

		bits = t & mask;
	}

	public static void debug(String group, String message) {
		System.err.println("RojLib/"+group+" Warning: "+message);
	}

	private static boolean loadLibrary() throws Exception {
		if (OS.ARCH == 64) {
			String ext = OS.CURRENT.libext();
			String hash = "${jni_version}";

			var in = RojLib.class.getClassLoader().getResourceAsStream(LibName + ext);
			if (in != null) {
				File tmp = new File(System.getProperty("java.io.tmpdir"), LibName + hash + ext);
				if (!tmp.isFile()) {
					byte[] buf = new byte[4096];
					try (var out = new FileOutputStream(tmp)) {
						do {
							int r = in.read(buf);
							if (r <= 0) break;
							out.write(buf, 0, r);
						} while (true);
						in.close();
					}
				}

				try {
					System.load(tmp.getAbsolutePath());
				} catch (Exception e) {
					Files.deleteIfExists(tmp.toPath());
					throw e;
				}
				LibFile = tmp;
				return true;
			}
		}

		debug("Native", "您的平台没有可用的二进制，部分功能将不可用.");
		return false;
	}

	private static native long init();
	public static native long getLastError();
}