package roj.asmx.launcher;

import org.jetbrains.annotations.Nullable;
import roj.asm.ClassNode;
import roj.asm.insn.CodeWriter;
import roj.asm.type.Type;
import roj.asmx.AnnotationRepo;
import roj.asmx.AnnotationRepoManager;
import roj.asmx.Context;
import roj.asmx.Transformer;
import roj.asmx.launcher.boot.Main;
import roj.ci.annotation.IndirectReference;
import roj.collect.ArrayList;
import roj.collect.TrieTreeSet;
import roj.crypt.jar.JarVerifier;
import roj.io.CorruptedInputException;
import roj.io.IOUtil;
import roj.reflect.Bypass;
import roj.reflect.Reflection;
import roj.text.URICoder;
import roj.text.logging.Level;
import roj.text.logging.LogAppender;
import roj.text.logging.LogContext;
import roj.text.logging.Logger;
import roj.util.ByteList;
import roj.util.Helpers;
import roj.util.function.ExceptionalFunction;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.security.CodeSigner;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

import static roj.asm.Opcodes.*;

/**
 * @author Roj233
 * @since 2020/11/9 23:10
 */
public class Loader implements ExceptionalFunction<String, Class<?>, ClassNotFoundException> {
	private static final ThreadLocal<int[]> IS_TRANSFORMING = ThreadLocal.withInitial(() -> new int[1]);
	private static final Main MAIN;
	static {
		try {
			MAIN = (Main) Loader.class.getClassLoader();
		} catch (ClassCastException e) {
			throw new IllegalStateException("只能在launcher启动的应用中引用Loader", e);
		}
	}

	private static final Logger LOGGER = Logger.getLogger("Ignis");
	public static final Loader instance = new Loader();

	static {Main.classFinder = instance;Main.resourceFinder = instance::getResource;}

	// 这里和Loader$Init是相同类加载器的相同包了
	static ArrayList<String> args;
	@IndirectReference
	static String[] args() {
		var s = args.toArray(new String[args.size()]);
		args = null;
		return s;
	}

	public static void init(String[] args) throws Exception {
		LOGGER.context().appender(LogAppender.appendTo(IOUtil.getSharedCharBuf()));
		LOGGER.fatal("");
		LOGGER.context().appenders((LogContext.Appender[]) null);

		if (addClasspaths()) {
			LOGGER.warn("classpath复制失败，可能会出现奇怪问题");

			URL myJar = Main.class.getProtectionDomain().getCodeSource().getLocation();
			if (myJar != null && myJar.getProtocol().equals("file") && myJar.getPath().indexOf('!') < 0) {
				try {
					instance.addClasspath(myJar);
				} catch (Exception e) {
					e.printStackTrace();
				}
			}
		}

		String tweakerName = "roj/asmx/launcher/Tweaker";
		String mainClass = null;
		int argOffset = 0;

		Manifest manifest = instance.getManifest();
		if (manifest != null) {
			Attributes mainAttributes = manifest.getMainAttributes();
			mainClass = mainAttributes.getValue("Ignis-Main");
			if (mainClass != null) {
				String tweakerClass = mainAttributes.getValue("Ignis-Tweaker");
				if (tweakerClass != null) tweakerName = tweakerClass;
			}
		}

		if (mainClass == null && args.length > 0) {
			String p = args[0];
			if (args.length >= 3 && (p.equals("-t") || p.equals("--tweaker"))) {
				tweakerName = args[1];
				mainClass = args[2];
				argOffset = 3;
			} else {
				mainClass = p;
				argOffset = 1;
			}
		}

		if (mainClass == null) {
			LOGGER.fatal("Usage: EntryPoint [-t <tweaker>] <main> [args...]");
			return;
		}

		var initArgs = new String[args.length-argOffset];
		System.arraycopy(args, argOffset, initArgs, 0, initArgs.length);
		Loader.args = ArrayList.asModifiableList(initArgs);

		ClassNode init = new ClassNode();
		init.name("roj/asmx/launcher/Loader$Init");
		init.addInterface("java/lang/Runnable");
		init.defaultConstructor();

		CodeWriter c = init.newMethod(ACC_PUBLIC|ACC_STATIC, "<clinit>", "()V");
		c.visitSize(3, 0);

		instance.addTransformerExclusion(tweakerName.substring(0, tweakerName.lastIndexOf('.')+1));
		c.newObject(tweakerName.replace('.', '/'));
		c.field(GETSTATIC, "roj/asmx/launcher/Loader", "args", "Lroj/collect/ArrayList;");
		c.field(GETSTATIC, "roj/asmx/launcher/Loader", "instance", "Lroj/asmx/launcher/Loader;");
		c.invokeV("roj/asmx/launcher/Tweaker", "init", "(Ljava/util/List;Lroj/asmx/launcher/Loader;)V");
		c.insn(RETURN);
		c.finish();

		c = init.newMethod(ACC_PUBLIC, "run", "()V");
		c.visitSize(1, 1);
		c.invokeS("roj/asmx/launcher/Loader", "args", "()[Ljava/lang/String;");
		c.invokeS(mainClass.replace('.', '/'), "main", "([Ljava/lang/String;)V");
		c.insn(RETURN);
		c.finish();

		AnnotationRepoManager.setAnnotations(MAIN, instance.getAnnotations());

		Main.main = (Runnable) Reflection.createInstance(Loader.class, init);

		// jarVerifier依赖很多库，例如Tokenizer和BufferPool等，它们可能需要ASM优化器处理；
		// 在bootstrap期间，系统只信任Loader所在的jar
		instance.initJarVerifier();
	}
	private static boolean addClasspaths() {
		H fn;
		var builder = Bypass.builder(H.class).i_access("sun.net.www.protocol.jar.JarFileFactory", "fileCache", Type.klass("java/util/HashMap"), "fileCache", null, true);
		var loader = Main.class.getClassLoader();
		if (!loader.getClass().getName().endsWith("AppClassLoader")) {
			LOGGER.warn("不支持的类加载器: {}", loader.getClass().getName());
			return true;
		} else {
			fn = builder.i_access("jdk/internal/loader/BuiltinClassLoader", "ucp", Type.klass("jdk/internal/loader/URLClassPath"), "ucp", null, false)
					.i_access("jdk/internal/loader/URLClassPath", "path", Type.klass("java/util/ArrayList"), "path", null, false)
					.i_delegate("jdk/internal/loader/URLClassPath", "closeLoaders", "()Ljava/util/List;", "closeLoaders", (byte) 0)
					.build();

		}

		var ucp = fn.ucp(loader);
		var path = fn.path(ucp);
		if (path.isEmpty()) return true;

		boolean hasError = false;
		for (URL url : path) {
			if (!url.getProtocol().equals("file")) {
				LOGGER.warn("非文件的classpath {}", url);
				hasError = true;
			} else {
				try {
					instance.addClasspath(url);
				} catch (Exception e) {
					hasError = true;
					e.printStackTrace();
				}
			}
		}

		if (!hasError) {
			fn.closeLoaders(ucp);
			for (var jar : new ArrayList<>(fn.fileCache().values())) {
				IOUtil.closeSilently(jar);
			}
		}
		return false;
	}

	private interface H {
		Object ucp(Object o);
		java.util.ArrayList<URL> path(Object o);
		List<IOException> closeLoaders(Object o);
		HashMap<String, JarFile> fileCache();
	}

	private final List<TinyArchive> archives = new ArrayList<>();
	private final List<ProtectionDomain> locations = new ArrayList<>();
	private final List<JarVerifier> verifiers = new ArrayList<>();

	private INameTransformer nameTransformer;
	private final List<Transformer> transformers = new ArrayList<>();

	private final TrieTreeSet transformExcept = new TrieTreeSet();
	private final TrieTreeSet loadExcept = new TrieTreeSet();
	public Loader() {
		transformExcept.addAll(Arrays.asList("roj.asm.", "roj.asmx.launcher.", "roj.reflect."));
		loadExcept.addAll(Arrays.asList("java.", "javax.", "roj.asmx.launcher.boot."));
		new Context(null);
	}

	public void registerTransformer(Transformer tr) {
		if (transformers.contains(tr)) throw new IllegalArgumentException("Transformer already exist: "+tr);
		transformers.add(tr);
		if (tr instanceof INameTransformer) {
			if (nameTransformer == null) {
				nameTransformer = (INameTransformer) tr;
				LOGGER.trace("注册类名映射 '{}'", tr.getClass().getName());
			} else {
				LOGGER.debug("类名映射 '{}' 因为已存在 '{}' 而被跳过", tr.getClass().getName(), nameTransformer.getClass().getName());
			}
		} else {
			LOGGER.trace("注册类转换器 '{}'", tr.getClass().getName());
		}
	}
	private void addPackage(URL sealBase, String name, JarVerifier jv) {
		int dot = name.lastIndexOf('/');
		if (dot > -1) {
			Manifest man = jv.getManifest();
			String pkgName = name.substring(0, dot).replace('/', '.');
			Attributes pkgAttrs = man.getAttributes(name.substring(0, dot+1));

			// 优先使用包专属属性，若无则使用主属性
			Attributes attrs = pkgAttrs != null ? pkgAttrs : man.getMainAttributes();

			// 提取包属性
			String specTitle = getAttribute(attrs, Attributes.Name.SPECIFICATION_TITLE);
			String specVersion = getAttribute(attrs, Attributes.Name.SPECIFICATION_VERSION);
			String specVendor = getAttribute(attrs, Attributes.Name.SPECIFICATION_VENDOR);
			String implTitle = getAttribute(attrs, Attributes.Name.IMPLEMENTATION_TITLE);
			String implVersion = getAttribute(attrs, Attributes.Name.IMPLEMENTATION_VERSION);
			String implVendor = getAttribute(attrs, Attributes.Name.IMPLEMENTATION_VENDOR);
			String sealed = getAttribute(attrs, Attributes.Name.SEALED);

			// 获取或定义包
			Package pkg = MAIN.getPackage(pkgName);
			if (pkg == null) {
				// 包未定义，创建新包
				MAIN.definePackage(
						pkgName,
						specTitle,
						specVersion,
						specVendor,
						implTitle,
						implVersion,
						implVendor,
						"true".equalsIgnoreCase(sealed) ? sealBase : null
				);
			} else if (pkg.isSealed() && !pkg.isSealed(sealBase)) {
				// 密封性冲突警告
				LOGGER.log(Level.WARN, "URL {} 加载了其他文件的封闭包 {} 的类 {}", null, sealBase, pkgName, name);
			}
		}
	}
	private String getAttribute(Attributes attrs, Attributes.Name name) {
		return attrs != null ? attrs.getValue(name) : null;
	}

	@Override
	public Class<?> apply(String name) throws ClassNotFoundException {
		String newName;
		if (nameTransformer == null) {
			newName = name;
		} else {
			newName = nameTransformer.mapName(name);
			var oldName = nameTransformer.unmapName(name);
			if (!oldName.equals(name)) {
				var clazz = MAIN.findLoadedClass1(oldName);
				if (clazz != null) return clazz;
			}
		}

		if (loadExcept.strStartsWithThis(newName)) return Main.PARENT.loadClass(newName);

		var buf = new ByteList();
		buf.ensureCapacity(4096);

		ProtectionDomain pd;
		try {
			name/*File Name*/ = newName.replace('.', '/').concat(".class");

			found: {
				for (int i = 0; i < locations.size(); i++) {
					var za = archives.get(i);
					if (za.get(name, buf)) {
						pd = locations.get(i);
						var jv = verifiers.get(i);
						if (jv != null) {
							jv.verify(name, buf);
							addPackage(pd.getCodeSource().getLocation(), name, jv);
						}
						break found;
					}
				}

				var in = MAIN.getInitialResource(name);
				if (in == null) return Main.PARENT.loadClass(newName);

				buf.readStreamFully(in);
				pd = Loader.class.getProtectionDomain();
			}

			if (!transformExcept.strStartsWithThis(newName))
				transform(name, newName.replace('.', '/'), buf);
			return MAIN.defineClassA(newName, buf.list, 0, buf.wIndex(), pd);
		} catch (ClassNotFoundException e) {
			throw e;
		} catch (Throwable e) {
			LOGGER.debug("类 {} 加载失败", e, newName);
			throw new ClassNotFoundException(newName, e);
		} finally {
			buf.release();
		}
	}

	public final void transform(String name, String transformedName, ByteList list) {
		Context ctx = new Context(name, list);
		boolean changed = false;

		List<Transformer> ts = transformers;
		var reentrant = IS_TRANSFORMING.get();
		if (reentrant[0] > 0) {
			LOGGER.warn("类转换器可能循环依赖{}", transformedName);
			return;
		}
		reentrant[0]++;

		for (int i = 0; i < ts.size(); i++) {
			try {
				changed |= ts.get(i).transform(transformedName, ctx);
			} catch (Throwable e) {
				reentrant[0] = -1;

				LOGGER.fatal("{} 转换 {} 时发生异常", e, ts.get(i).getClass().getName(), name);
				try (var fos = new FileOutputStream("transform_failed.class")) {
					ctx.getClassBytes().writeToStream(fos);
				} catch (Throwable e1) {
					LOGGER.fatal("保存其内容用于调试时发生异常", e1, name);
				}
				Helpers.athrow(e);
			}
		}
		reentrant[0]--;
		if (changed) {
			// See ConstantPool#checkCollision
			ByteList b = ctx.getClassBytes();
			if (b != list) {
				list.clear();
				list.put(b);
			}
		}
	}

	private int manifestIndex;
	public Manifest getManifest() {
		for (; manifestIndex < locations.size(); manifestIndex++) {
			var jv = verifiers.get(manifestIndex);
			if (jv != null) return jv.getManifest();

			var zf = archives.get(manifestIndex);
			try (var in = zf.getInputStream("META-INF/MANIFEST.MF")) {
				if (in != null) return new Manifest(in);
			} catch (IOException ignored) {}
		}

		try(var in = MAIN.getResourceAsStream("META-INF/MANIFEST.MF")) {
			if (in != null) return new Manifest(in);
		} catch (IOException ignored) {}

		return null;
	}

	public boolean hasResource(String name) {
		for (int i = 0; i < archives.size(); i++) {
			var zf = archives.get(i);
			try {
				if (zf.get(name, null)) return true;
			} catch (IOException ignored) {}
		}

		var in = MAIN.getResource(name);
		if (in == null) in = ClassLoader.getSystemResource(name);
		return in != null;
	}

	@Nullable
	public InputStream getResource(String name) {
		for (int i = 0; i < archives.size(); i++) {
			var zf = archives.get(i);
			InputStream in = null;
			try {
				in = zf.getInputStream(name);
			} catch (IOException ignored) {}
			if (in != null) {
				var jv = verifiers.get(i);
				if (jv != null) in = jv.wrapInput(name, in);
				return in;
			}
		}

		var in = MAIN.getInitialResource(name);
		if (in == null) in = ClassLoader.getSystemResourceAsStream(name);
		return in;
	}

	public List<Transformer> getTransformers() {return transformers;}
	public void addTransformerExclusion(String toExclude) {transformExcept.add(toExclude);}

	private void addClasspath(URL url) throws IOException {
		File file = new File(URICoder.decodeURI(url.getPath().substring(1)));
		var zf = new TinyArchive(file);
		zf.reload();
		archives.add(zf);
		ProtectionDomain mypd = Loader.class.getProtectionDomain();
		locations.add(url.toString().equals(mypd.getCodeSource().getLocation().toString()) ? mypd : null);
		verifiers.add(null);
	}

	private void initJarVerifier() throws IOException {
		for (int i = 0; i < archives.size(); i++) {
			var zf = archives.get(i);
			JarVerifier verifier = JarVerifier.create(zf, zf.file);
			if (verifier != null && verifier.getTrustLevel() == JarVerifier.TRUST_LEVEL_INVALID_SIGN) {
				throw new CorruptedInputException("Signature verification failure");
			}
			locations.set(i, new ProtectionDomain(verifier == null ? new CodeSource(zf.file.toURI().toURL(), (CodeSigner[]) null) : verifier.getCodeSource(), null));
			verifiers.set(i, verifier);
		}
	}

	private AnnotationRepo getAnnotations() throws IOException {
		var repo = new AnnotationRepo();
		if (archives.isEmpty()) {
			AnnotationRepoManager.loadFromClassicClassLoader(MAIN, repo);
		} else {
			repo = new AnnotationRepo();
			for (var archive : archives) {
				repo.loadCacheOrAdd(archive);
			}
		}

		return repo;
	}
}