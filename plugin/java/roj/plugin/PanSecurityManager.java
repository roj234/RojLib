package roj.plugin;

import roj.asm.ClassUtil;
import roj.asm.MemberDescriptor;
import roj.asm.cp.Constant;
import roj.asm.cp.CstClass;
import roj.asm.cp.CstUTF;
import roj.asm.type.TypeHelper;
import roj.asmx.Context;
import roj.asmx.MethodHook;
import roj.asmx.TransformException;
import roj.asmx.Transformer;
import roj.collect.*;
import roj.reflect.ILSecurityManager;
import roj.reflect.Reflection;
import roj.reflect.Telescope;
import roj.reflect.Unsafe;
import roj.text.URICoder;
import roj.text.logging.Logger;
import roj.util.ArrayCache;
import roj.util.ByteList;
import roj.util.Helpers;
import roj.util.optimizer.VarHandleRewriter;

import java.io.*;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.security.ProtectionDomain;
import java.util.Arrays;

/**
 * 这只是一个示例，应该还有不少文件访问函数没有包括在内
 * @author Roj234
 * @since 2023/8/4 15:36
 */
public final class PanSecurityManager extends MethodHook {
	static final TrieTreeSet GlobalClassBlackList = new TrieTreeSet("roj/plugin/Pan", VarHandleRewriter.PROXY_NAME, Reflection.PROXY_CLASS);
	static final TrieTreeSet GlobalFileWhiteList = new TrieTreeSet();
	static final TrieTreeSet GlobalReflectWhiteList = new TrieTreeSet();

	static final Logger LOGGER = Jocker.LOGGER.context().child("Security").logger();

	PanSecurityManager() {
		hooks.put(new MemberDescriptor("roj/reflect/Unsafe", "U", "Lroj/reflect/Unsafe;"),
			new MemberDescriptor("roj/plugin/PanSecurityManager", "Unsafe_getUnsafe", "(Ljava/lang/Class;)Lroj/reflect/Unsafe;", 2));
	}

	static final class ILHook extends ILSecurityManager {
		public void checkAccess(String owner, String name, String desc, Class<?> caller) {
			MemberDescriptor d = ClassUtil.getInstance().sharedDesc;
			d.owner = owner;
			d.name = name;
			d.rawDesc = desc;

			Object v = ban(d, caller);
			if (v == IntMap.UNDEFINED) throw new SecurityException(d+" is not allowed");
		}
	}

	// region 反射保护
	@RealDesc(value = "java/lang/Class.forName(Ljava/lang/String;)Ljava/lang/Class;", callFrom = true)
	public static Class<?> hook_static_forName(String name, Class<?> caller) throws Exception { return checkClass(name, true, caller.getClassLoader(), caller); }
	@RealDesc(value = "java/lang/Class.forName(Ljava/lang/String;ZLjava/lang/ClassLoader;)Ljava/lang/Class;", callFrom = true)
	public static Class<?> hook_static_forName(String name, boolean init, ClassLoader loader, Class<?> caller) throws Exception { return checkClass(name, init, loader, caller); }

	private static final Method[] EMPTY_METHODS = new Method[0];
	private static final Field[] EMPTY_FIELDS = new Field[0];
	private static final Constructor<?>[] EMPTY_CONSTRUCTORS = new Constructor<?>[0];

	public static Method hook_callFrom_getMethod(Class<?> c, String name, Class<?>[] param, Class<?> caller) throws Exception { return filterMethod(c.getMethod(name, param), true, caller); }
	public static Method hook_callFrom_getDeclaredMethod(Class<?> c, String name, Class<?>[] param, Class<?> caller) throws Exception { return filterMethod(c.getDeclaredMethod(name, param), true, caller); }
	public static Method[] hook_callFrom_getMethods(Class<?> c, Class<?> caller) { return filterMethods(c.getMethods(), caller); }
	public static Method[] hook_callFrom_getDeclaredMethods(Class<?> c, Class<?> caller) { return filterMethods(c.getDeclaredMethods(), caller); }
	private static Method[] filterMethods(Method[] a, Class<?> caller) {
		if (a.length == 0 || a[0].getDeclaringClass().getClassLoader() == caller.getClassLoader()) return a;

		ArrayList<Method> list = ArrayList.asModifiableList(a);
		for (int i = list.size()-1; i >= 0; i--) {
			Method f = filterMethod(list.get(i), false, caller);
			if (f == null) {
				list.remove(i);
				a = EMPTY_METHODS;
			} else list.set(i, f);
		}
		return list.toArray(a);
	}
	private static Method filterMethod(Method m, boolean _throw, Class<?> caller) {
		if (m.getDeclaringClass().getClassLoader() == caller.getClassLoader()) return m;

		MemberDescriptor d = ClassUtil.getInstance().sharedDesc;
		d.owner = m.getDeclaringClass().getName().replace('.', '/');
		d.name = m.getName();
		d.rawDesc = TypeHelper.class2asm(m.getParameterTypes(), m.getReturnType());

		Object v = ban(d, caller);
		if (v == null) return m;
		if (v == IntMap.UNDEFINED) {
			if (_throw) Helpers.athrow(new NoSuchMethodException(m.getDeclaringClass().getName()+'.'+m.getName()+argumentTypesToString(m.getParameterTypes())));
			return null;
		}
		return (Method) v;
	}

	private static final Method INVOKE, NEWINSTANCE;
	static {
		try {
			INVOKE = Method.class.getDeclaredMethod("invoke", Object.class, Object[].class);
			NEWINSTANCE = Constructor.class.getDeclaredMethod("newInstance", Object[].class);
		} catch (NoSuchMethodException e) {
			throw new RuntimeException(e);
		}
	}

	public static Object hook_callFrom_invoke(Method m, Object inst, Object[] value, Class<?> caller) throws Exception {
		if (m.getDeclaringClass().getClassLoader() == caller.getClassLoader()) return m.invoke(inst, value);

		HashSet<Object[]> arr = null;
		while (m.equals(INVOKE)) {
			m = (Method) inst;
			inst = value[0];

			// 防止可能的DoS漏洞
			if (arr == null) arr = new HashSet<>(Hasher.identity());
			if (!arr.add(value)) throw new StackOverflowError();

			value = (Object[]) value[1];
		}

		if (m.equals(NEWINSTANCE)) return hook_callFrom_newInstance((Constructor<?>) inst, value, caller);

		return checkInvoke(m, inst, value, caller);
	}

	public static Field hook_callFrom_getField(Class<?> c, String name, Class<?> caller) throws Exception { return filterField(c.getField(name), true, caller); }
	public static Field hook_callFrom_getDeclaredField(Class<?> c, String name, Class<?> caller) throws Exception { return filterField(c.getDeclaredField(name), true, caller); }
	public static Field[] hook_callFrom_getFields(Class<?> c, Class<?> caller) { return filterFields(c.getFields(), caller); }
	public static Field[] hook_callFrom_getDeclaredFields(Class<?> c, Class<?> caller) { return filterFields(c.getDeclaredFields(), caller); }
	private static Field[] filterFields(Field[] a, Class<?> caller) {
		if (a.length == 0 || a[0].getDeclaringClass().getClassLoader() == caller.getClassLoader()) return a;

		ArrayList<Field> list = ArrayList.asModifiableList(a);
		for (int i = list.size()-1; i >= 0; i--) {
			Field f = filterField(list.get(i), false, caller);
			if (f == null) {
				list.remove(i);
				a = EMPTY_FIELDS;
			} else list.set(i, f);
		}
		return list.toArray(a);
	}
	private static Field filterField(Field f, boolean _throw, Class<?> caller) {
		if (f.getDeclaringClass().getClassLoader() == caller.getClassLoader()) return f;

		MemberDescriptor d = ClassUtil.getInstance().sharedDesc;
		d.owner = f.getDeclaringClass().getName().replace('.', '/');
		d.name = f.getName();
		d.rawDesc = TypeHelper.class2asm(f.getType());

		Object v = ban(d, caller);
		if (v == null) return f;
		if (v == IntMap.UNDEFINED) {
			if (_throw) Helpers.athrow(new NoSuchFieldException(f.getName()));
			return null;
		}
		return (Field) v;
	}

	public static Constructor<?> hook_callFrom_getConstructor(Class<?> c, Class<?>[] param, Class<?> caller) throws Exception { return filterConstructor(c.getConstructor(param), true, caller); }
	public static Constructor<?> hook_callFrom_getDeclaredConstructor(Class<?> c, Class<?>[] param, Class<?> caller) throws Exception { return filterConstructor(c.getDeclaredConstructor(param), true, caller); }
	public static Constructor<?>[] hook_callFrom_getConstructors(Class<?> c, Class<?> caller) { return filterConstructors(c.getConstructors(), caller); }
	public static Constructor<?>[] hook_callFrom_getDeclaredConstructors(Class<?> c, Class<?> caller) { return filterConstructors(c.getDeclaredConstructors(), caller); }
	private static Constructor<?>[] filterConstructors(Constructor<?>[] a, Class<?> caller) {
		if (a.length == 0 || a[0].getDeclaringClass().getClassLoader() == caller.getClassLoader()) return a;

		ArrayList<Constructor<?>> list = ArrayList.asModifiableList(a);
		for (int i = list.size()-1; i >= 0; i--) {
			Constructor<?> f = filterConstructor(list.get(i), false, caller);
			if (f == null) {
				list.remove(i);
				a = EMPTY_CONSTRUCTORS;
			} else list.set(i, f);
		}
		return list.toArray(a);
	}
	private static Constructor<?> filterConstructor(Constructor<?> c, boolean _throw, Class<?> caller) {
		if (c.getDeclaringClass().getClassLoader() == caller.getClassLoader()) return c;

		MemberDescriptor d = ClassUtil.getInstance().sharedDesc;
		d.owner = c.getDeclaringClass().getName().replace('.', '/');
		d.name = "<init>";
		d.rawDesc = TypeHelper.class2asm(c.getParameterTypes(), void.class);

		Object v = ban(d, caller);
		if (v == null) return c;
		if (v == IntMap.UNDEFINED) {
			if (_throw) Helpers.athrow(new NoSuchMethodException(c.getDeclaringClass().getName()+".<init>"+argumentTypesToString(c.getParameterTypes())));
			return null;
		}
		return (Constructor<?>) v;
	}

	public static Method hook_callFrom_getEnclosingMethod(Class<?> c, Class<?> caller) {
		Method m = c.getEnclosingMethod();
		return m == null ? null : filterMethod(m, false, caller); }
	public static Constructor<?> hook_callFrom_getEnclosingConstructor(Class<?> c, Class<?> caller) {
		Constructor<?> c1 = c.getEnclosingConstructor();
		return c1 == null ? null : filterConstructor(c1, false, caller); }

	public static Object hook_callFrom_newInstance(Class<?> c, Class<?> caller) throws Exception {
		if (c.getDeclaringClass().getClassLoader() == caller.getClassLoader()) return c;

		MemberDescriptor d = ClassUtil.getInstance().sharedDesc;
		d.owner = c.getDeclaringClass().getName().replace('.', '/');
		d.name = "<init>";
		d.rawDesc = "()V";

		Object v = ban(d, caller);
		if (v == null) return c.newInstance();
		if (v == IntMap.UNDEFINED) throw new InstantiationException(c.getName());
		return ((Constructor<?>) v).newInstance(ArrayCache.OBJECTS);
	}

	public static Class<?> hook_defineClass(ClassLoader cl, byte[] b, int off, int len) { return hook_defineClass(cl, null, b, off, len, null); }
	public static Class<?> hook_defineClass(ClassLoader cl, String name, byte[] b, int off, int len) { return hook_defineClass(cl, name, b, off, len, null); }
	public static Class<?> hook_defineClass(ClassLoader cl, String name, byte[] b, int off, int len, ProtectionDomain pd) { return hook_defineClass(cl, null, name, b, off, len, pd, 0, cl.getClass()); }

	private static String argumentTypesToString(Class<?>[] types) {
		StringBuilder buf = new StringBuilder().append('(');
		for (int i = 0; i < types.length; i++) {
			if (i > 0) buf.append(", ");

			Class<?> c = types[i];
			buf.append((c == null) ? "null" : c.getName());
		}
		return buf.append(')').toString();
	}
	// endregion
	// region 文件访问保护 (不全) (这些函数可以放心的使用caller，因为PanSelfProtect已经禁止调用这些函数)
	/* -- Attribute accessors -- */

	public static boolean hook_callFrom_canRead(File f, Class<?> caller) { return checkFileAccess(f, caller) && f.canRead(); }
	public static boolean hook_callFrom_canWrite(File f, Class<?> caller) { return checkFileAccess(f, caller) && f.canWrite(); }
	public static boolean hook_callFrom_exists(File f, Class<?> caller) { return checkFileAccess(f, caller) && f.exists(); }
	public static boolean hook_callFrom_isDirectory(File f, Class<?> caller) { return checkFileAccess(f, caller) && f.isDirectory(); }
	public static boolean hook_callFrom_isFile(File f, Class<?> caller) { return checkFileAccess(f, caller) && f.isFile(); }
	public static boolean hook_callFrom_isHidden(File f, Class<?> caller) { return checkFileAccess(f, caller) && f.isHidden(); }
	public static long hook_callFrom_lastModified(File f, Class<?> caller) { return checkFileAccess(f, caller) ? f.lastModified() : 0; }
	public static long hook_callFrom_length(File f, Class<?> caller) { return checkFileAccess(f, caller) ? f.length() : 0; }

	/* -- File operations -- */
	public static boolean hook_callFrom_createNewFile(File f, Class<?> caller) throws IOException { return checkFileAccess(f, caller) && f.createNewFile(); }

	public static boolean hook_callFrom_delete(File f, Class<?> caller) { return checkFileAccess(f, caller) && f.delete(); }
	public static void hook_callFrom_deleteOnExit(File f, Class<?> caller) { if (checkFileAccess(f, caller)) f.deleteOnExit(); }

	public static String[] hook_callFrom_list(File f, Class<?> caller) { return checkFileAccess(f, caller) ? f.list() : null; }
	public static String[] hook_callFrom_list(File f, FilenameFilter filter, Class<?> caller) { return checkFileAccess(f, caller) ? f.list(filter) : null; }

	public static File[] hook_callFrom_listFiles(File f, Class<?> caller) { return checkFileAccess(f, caller) ? f.listFiles() : null; }
	public static File[] hook_callFrom_listFiles(File f, FilenameFilter filter, Class<?> caller) { return checkFileAccess(f, caller) ? f.listFiles(filter) : null; }
	public static File[] hook_callFrom_listFiles(File f, FileFilter filter, Class<?> caller) { return checkFileAccess(f, caller) ? f.listFiles(filter) : null; }

	public static boolean hook_callFrom_mkdir(File f, Class<?> caller) { return checkFileAccess(f, caller) && f.mkdir(); }
	public static boolean hook_callFrom_mkdirs(File f, Class<?> caller) {return checkFileAccess(f, caller) && f.mkdirs(); }

	public static boolean hook_callFrom_renameTo(File f, File dest, Class<?> caller) { return checkFileAccess(f, caller) && checkFileAccess(dest, caller) && f.renameTo(dest); }

	public static boolean hook_callFrom_setLastModified(File f, long time, Class<?> caller) { return checkFileAccess(f, caller) && f.setLastModified(time); }
	public static boolean hook_callFrom_setReadOnly(File f, Class<?> caller) { return checkFileAccess(f, caller) && f.setReadOnly(); }
	public static boolean hook_callFrom_setWritable(File f, boolean writable, boolean ownerOnly, Class<?> caller) { return checkFileAccess(f, caller) && f.setWritable(writable, ownerOnly); }
	public static boolean hook_callFrom_setWritable(File f, boolean writable, Class<?> caller) { return hook_callFrom_setWritable(f, writable, true, caller); }
	public static boolean hook_callFrom_setReadable(File f, boolean readable, boolean ownerOnly, Class<?> caller) { return checkFileAccess(f, caller) && f.setReadable(readable, ownerOnly); }
	public static boolean hook_callFrom_setReadable(File f, boolean readable, Class<?> caller) { return hook_callFrom_setReadable(f, readable, true, caller); }
	public static boolean hook_callFrom_setExecutable(File f, boolean executable, boolean ownerOnly, Class<?> caller) { return checkFileAccess(f, caller) && f.setExecutable(executable, ownerOnly); }
	public static boolean hook_callFrom_setExecutable(File f, boolean executable, Class<?> caller) { return hook_callFrom_setExecutable(f, executable, true, caller); }
	public static boolean hook_callFrom_canExecute(File f, Class<?> caller) { return checkFileAccess(f, caller) && f.canExecute(); }

	/* -- Filesystem interface -- */
	@RealDesc(value = "java/io/File.createTempFile(Ljava/lang/String;Ljava/lang/String;Ljava/io/File;)Ljava/io/File;", callFrom = true)
	public static File hook_static_createTempFile(String prefix, String suffix, File directory, Class<?> caller) throws IOException {
		if (directory != null && !checkFileAccess(directory, caller)) throw new SecurityException("没有写入权限");
		return File.createTempFile(prefix, suffix, directory);
	}

	@RealDesc(value = "java/io/File.createTempFile(Ljava/lang/String;Ljava/lang/String;)Ljava/io/File;", callFrom = true)
	public static File hook_static_createTempFile(String prefix, String suffix, Class<?> caller) throws IOException { return hook_static_createTempFile(prefix, suffix, null, caller); }

	/* -- NIO -- */
	public static Path hook_callFrom_getPath(FileSystem fs, String first, String[] more, Class<?> caller) {
		Path path = fs.getPath(first, more);
		if (fs == FileSystems.getDefault() && !checkFileAccess(path.toFile(), caller))
			throw new SecurityException("没有访问"+path+"的权限");
		return path;
	}
	public static Path hook_callFrom_toPath(File f, Class<?> caller) {
		if (!checkFileAccess(f, caller)) throw new SecurityException("没有访问"+f+"的权限");
		return f.toPath();
	}

	/* -- URL -- */
	public static InputStream hook_callFrom_openStream(URL url, Class<?> caller) throws IOException {
		if (url.getProtocol().equals("file")) {
			if (!checkFileAccess(new File(URICoder.decodeURI(url.getFile())), caller)) throw new SecurityException("没有读取权限");
		} else if (url.getProtocol().equals("jar")) {
			String spec = url.getFile();
			int separator = spec.indexOf("!/");
			if (separator == -1) throw new MalformedURLException("no !/ found in url spec:" + spec);

			if (!checkFileAccess(new File(URICoder.decodeURI(spec.substring(0, separator))), caller))
				throw new SecurityException("没有读取权限");
		}
		return url.openStream();
	}

	/* -- ArgumentCapture -- */
	@RealDesc(value = {
		"java/io/RandomAccessFile.<init>(Ljava/io/File;)V",
		"java/io/FileInputStream.<init>(Ljava/io/File;)V",
		"java/io/FileOutputStream.<init>(Ljava/io/File;)V",
	}, callFrom = true)
	public static File hook_newInstance_f(File file, Class<?> caller) throws IOException {
		if (checkFileAccess(file, caller)) return file;
		throw new IOException("权限不足");
	}
	@RealDesc(value = {
		"java/io/RandomAccessFile.<init>(Ljava/lang/String;)V",
		"java/io/FileInputStream.<init>(Ljava/lang/String;)V",
		"java/io/FileOutputStream.<init>(Ljava/lang/String;)V",
	}, callFrom = true)
	public static String hook_newInstance_s(String file, Class<?> caller) throws IOException {
		if (checkFileAccess(file, caller)) return file;
		throw new IOException("权限不足");
	}
	// endregion
	// region 本地库保护
	@RealDesc(value = "java/lang/System.load(Ljava/lang/String;)V", callFrom = true)
	public static void hook_callFrom_load(String path, Class<?> caller) {
		var pd = Jocker.pm.getOwner(caller);
		if (!pd.loadNative) throw new SecurityException("loadNative权限未为"+pd+"开启");
		System.load(path);
	}
	@RealDesc(value = "java/lang/System.loadLibrary(Ljava/lang/String;)V", callFrom = true)
	public static void hook_callFrom_loadLibrary(String path, Class<?> caller) {
		var pd = Jocker.pm.getOwner(caller);
		if (!pd.loadNative) throw new SecurityException("loadNative权限未为"+pd+"开启");
		System.loadLibrary(path);
	}
	// endregion
	// region Unsafe
	@RealDesc(value = "roj/reflect/Telescope.trustedLookup()Lroj/reflect/Telescope;", callFrom = true)
	public static Telescope hook_static_trustedLookup(Class<?> caller) {
		var pd = Jocker.pm.getOwner(caller);
		if (!pd.accessUnsafe) throw new SecurityException("accessUnsafe权限未为"+pd+"开启");
		return Telescope.trustedLookup();
	}

	public static Unsafe Unsafe_getUnsafe(Class<?> caller) {
		var pd = Jocker.pm.getOwner(caller);
		if (!pd.accessUnsafe) throw new SecurityException("accessUnsafe权限未为"+pd+"开启");
		return Unsafe.U;
	}
	// endregion
	// region 类定义
	@RealDesc(value = "roj/reflect/Reflection.defineClass(Ljava/lang/ClassLoader;Ljava/lang/Class;Ljava/lang/String;[BIILjava/security/ProtectDomain;I)Ljava/lang/Class;", callFrom = true)
	public static Class<?> hook_defineClass(ClassLoader cl, Class<?> nestHost, String name, byte[] b, int off, int len, ProtectionDomain pd, int flag, Class<?> caller) {
		ByteList buf = new ByteList(Arrays.copyOfRange(b, off, off+len));

		var pd1 = Jocker.pm.getOwner(caller);
		buf = preDefineHook(name, pd1, buf);
		return Reflection.defineClass(cl, nestHost, name, buf.list, 0, buf.wIndex(), pd, flag);
	}

	static ByteList preDefineHook(String name, PluginDescriptor pd1, ByteList buf) {
		if (!pd1.dynamicLoadClass) throw new SecurityException("dynamicLoadClass权限未为"+pd1+"开启");
		if (!pd1.isTrusted) {
			// 一个有趣的问题是，如果另一个线程异步修改这个数组？
			// 因为final了，所以就不用clone
			buf = new ByteList(buf.toByteArray());

			try {
				//name = ClassView.parse(buf, false).name;
				Context ctx = new Context(name, buf);
				if (transformer.transform(name/*目前未使用*/, ctx)) {
					ByteList b = ctx.getClassBytes();
					if (b != buf) {
						buf.clear();
						buf.put(b);
					}
				}
			} catch (TransformException e) {
				throw new ClassFormatError(e.toString());
			}
		}
		return buf;
	}
	// endregion

	static final Transformer transformer = new PanSecurityManager();

	private static Object checkInvoke(Method m, Object inst, Object[] value, Class<?> caller) throws Exception {
		return m.invoke(inst, value);
	}
	public static Object hook_callFrom_newInstance(Constructor<?> c, Object[] value, Class<?> caller) throws Exception {
		return c.newInstance(value);
	}

	private static Class<?> checkClass(String name, boolean init, ClassLoader loader, Class<?> caller) throws Exception {
		MemberDescriptor d = ClassUtil.getInstance().sharedDesc;
		d.owner = name.replace('.', '/');
		d.name = "";
		d.rawDesc = "";

		Object result = ban(d, caller);
		if (result == IntMap.UNDEFINED) loader = null;

		return Class.forName(name, init, loader);
	}

	static boolean checkFileAccess(File file, Class<?> caller) { return checkFileAccess(file.getPath(), caller); }
	static boolean checkFileAccess(String path, Class<?> caller) {
		var pd = Jocker.pm.getOwner(caller);
		if (pd.isTrusted || GlobalFileWhiteList.strStartsWithThis(path) || pd.extraPath.strStartsWithThis(path)) {
			LOGGER.debug("允许{}读写 {}", pd.id, path);
			return true;
		}

		if (!path.endsWith(File.separator))
			path = path.concat(File.separator);

		if (GlobalFileWhiteList.strStartsWithThis(path) || pd.extraPath.strStartsWithThis(path)) {
			LOGGER.debug("允许{}读写 {}", pd.id, path);
			return true;
		}

		LOGGER.debug("禁止{}读写 {}", pd.id, path);
		return false;
	}

	private static Object ban(MemberDescriptor d, Class<?> caller) {
		if (d.owner.equals("java/lang/reflect/Constructor") || d.owner.equals("java/lang/reflect/Method")) {
			// prevent getting internal fields
			if (d.rawDesc.indexOf('(') == -1) {
				LOGGER.debug("禁止反射 {} (REFLECTION_INTERNAL)", d);
				return IntMap.UNDEFINED;
			}
		}

		if (d.owner.equals("sun/misc/Unsafe") || d.owner.equals("jdk/internal/misc/Unsafe")) {
			LOGGER.debug("禁止反射 {} (DIRECT_UNSAFE)", d);
			return IntMap.UNDEFINED;
		}

		var pd = Jocker.pm.getOwner(caller);

		if (!pd.isTrusted && (d.owner.startsWith("roj/plugin/") || d.owner.startsWith("roj/reflect/"))) {
			LOGGER.debug("禁止反射 {} (SYSTEM_INTERNAL)", d);
			return IntMap.UNDEFINED;
		}

		if (pd.isTrusted || GlobalReflectWhiteList.strStartsWithThis(d.owner) || pd.reflectiveClass.strStartsWithThis(d.owner)) {
			LOGGER.debug("允许{}反射 {}", pd.id, d);
			return null;
		}

		LOGGER.debug("禁止{}反射 {}", pd.id, d);
		throw new SecurityException(d+"不在"+pd.id+"的reflectivePackage中");
	}

	@Override
	public boolean transform(String name, Context ctx) {
		boolean changed = false;
		if (PluginClassLoader.PLUGIN_CONTEXT.get() != null) {
			for (Constant c : ctx.getData().cp.constants()) {
				if (c instanceof CstClass) {
					CstUTF ref = ((CstClass) c).value();
					if (GlobalClassBlackList.strStartsWithThis(ref.str())) {
						LOGGER.debug("屏蔽对类 {} 的访问", ref.str());
						ctx.getData().cp.setUTFValue(ref, "java/lang/Blocked");
						changed = true;
					}
				}
			}
		}
		return changed|super.transform(name, ctx);
	}
}