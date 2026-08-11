package roj.misc;

import com.sun.tools.attach.*;
import roj.archive.zip.ZipEditor;
import roj.archive.zip.ZipEntry;
import roj.archive.zip.ZipFile;
import roj.archive.zip.ZipPacker;
import roj.io.IOUtil;
import roj.io.source.ByteSource;
import roj.text.TextReader;
import roj.util.ByteList;

import javax.swing.*;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.instrument.UnmodifiableClassException;
import java.nio.charset.StandardCharsets;
import java.security.ProtectionDomain;
import java.util.*;
import java.util.concurrent.locks.LockSupport;
import java.util.jar.Manifest;
import java.util.zip.Deflater;
import java.util.zip.ZipOutputStream;

/**
 * @author Roj234
 * @since 2025/12/14 14:53
 */
public class GTOPatcher {
	public static void main(String[] args) throws IOException {
		if (System.console() == null) {
			JOptionPane.showMessageDialog(null, "You must run this program in console", "Error", JOptionPane.ERROR_MESSAGE);
			return;
		}
		System.out.println("Searching GTOCore");

		var curDir = new File("").getAbsoluteFile();
		var mods = new File(curDir, "mods");

		if (!mods.isDirectory()) {
			System.out.println(mods.getAbsolutePath()+" is not a directory");
			System.out.println("Either put this jar to game path or create mods dir and copy gtocore in");
			return;
		}

		for (File file : mods.listFiles()) {
			if (file.getName().startsWith("gtocore") && file.getName().endsWith(".jar")) {
				try (var zf = new ZipEditor(file)) {
					System.out.println("Found GTOCore");
					for (ZipEntry entry : zf.entries()) {
						if (entry.getName().contains("gtolib") && entry.getName().endsWith(".jar")) {
							System.out.println("Found GTOLib");

							foundGTOLib(zf, entry);
							return;
						}
					}
				}
			}
		}
	}

	private static void foundGTOLib(ZipEditor zf, ZipEntry gtoLibEncrypted) throws IOException {
		VirtualMachine vm;
		try (var scanner = new Scanner(System.in)) {
			System.out.println();

			Set<String> temp = new HashSet<>();
			List<VirtualMachineDescriptor> vms = VirtualMachine.list();
			for (int i = 0; i < vms.size(); i++) {
				VirtualMachineDescriptor vmd = vms.get(i);
				if (!vmd.displayName().toLowerCase().contains("fml")) continue;
				temp.add(vmd.id());
				System.out.println("PID: "+vmd.id());
				System.out.println("Name: "+vmd.displayName());
			}

			// 提示输入目标JVM的PID
			System.out.print("Waiting for new Minecraft instance.");

			aaa:
			while (true) {
				vms = VirtualMachine.list();
				for (int i = 0; i < vms.size(); i++) {
					VirtualMachineDescriptor vmd = vms.get(i);
					if (vmd.displayName().toLowerCase().contains("fml")) {
						if (temp.add(vmd.id())) {
							System.out.println("Found Minecraft instance #"+vmd.id());
							vm = VirtualMachine.attach(vmd);
							break aaa;
						}
					}
				}
				LockSupport.parkNanos(1000);
			}

			System.out.println("Generating ClassList "+gtoLibEncrypted);

			var src = new ByteSource(zf.get(gtoLibEncrypted));
			var gtoLib = new ZipFile(src, 0, StandardCharsets.UTF_8);
			gtoLib.reload();

			var classListPath = new File("classList.txt").getAbsolutePath();
			try (var out = new FileOutputStream(classListPath)) {
				for (ZipEntry nextEntry : gtoLib.entries()) {
					if (nextEntry == null) break;
					if (nextEntry.getName().endsWith(".class") && !nextEntry.getName().startsWith("com/gtolib/mixin")) {
						out.write(nextEntry.getName().substring(0, nextEntry.getName().length() - 6).getBytes(StandardCharsets.UTF_8));
						out.write('\n');
					}
				}
			}

			System.out.println("Attaching");

			File agentJar = IOUtil.getJar(GTOPatcher.class);
			String agentArgs = IOUtil.encodeBase64(IOUtil.encodeUTF8(classListPath));
			try {
				System.out.println("Loading decrypt agent from JAR: " + agentJar);
				vm.loadAgent(agentJar.getAbsolutePath(), agentArgs);

				System.out.println("Agent attached and loaded successfully!");
				vm.detach(); // 分离
			} catch (IOException e) {
				System.err.println("IO error: " + e.getMessage());
			} catch (AgentLoadException e) {
				System.err.println("Agent load error: " + e.getMessage());
			} catch (AgentInitializationException e) {
				System.err.println("Agent initialization error: " + e.getMessage());
			}

			LockSupport.parkUntil(System.currentTimeMillis() + 1000);

			System.out.println("Exit the game and press Enter to continue");

			scanner.nextLine();

			System.out.println("Constructing GTOLib-new");

			ByteSource patched = new ByteSource();

			try (var gtoLibDecypted = new ZipFile("classes.zip");
				 var zfw = new ZipPacker(patched, Deflater.DEFAULT_COMPRESSION)
			) {
				var written = new HashSet<String>();

				for (ZipEntry entry : gtoLibDecypted.entries()) {
					zfw.copy(gtoLibDecypted, entry);
					written.add(entry.getName());
				}

				for (ZipEntry entry : gtoLib.entries()) {
					if (entry.getName().startsWith("native0/")) continue;

					if (written.add(entry.getName())) {
						if (entry.getName().startsWith("META-INF/")) {
							if (entry.getName().endsWith("MANIFEST.MF")) {
								var str = new Manifest(gtoLib.getInputStream(entry));
								str.getEntries().clear();

								zfw.beginEntry(entry);
								str.write(zfw);
								zfw.closeEntry();
								continue;
							} else if (
									entry.getName().endsWith(".SF") ||
											entry.getName().endsWith(".EC") ||
											entry.getName().endsWith(".RSA") ||
											entry.getName().endsWith(".DSA")) {
								continue;
							}
						}

						zfw.copy(gtoLib, entry);
					}
				}
			}

			System.out.println("Patching");

			zf.put(gtoLibEncrypted.getName(), (ByteList) patched.buffer());


			for (ZipEntry entry : zf.entries()) {
				if (entry.getName().endsWith("MANIFEST.MF")) {
					var str = new Manifest(zf.getInputStream(entry));
					str.getEntries().clear();
					var out = new ByteList();
					str.write(out);

					zf.put(entry.getName(), out);
				} else if (
						entry.getName().endsWith(".SF") ||
								entry.getName().endsWith(".EC") ||
								entry.getName().endsWith(".RSA") ||
								entry.getName().endsWith(".DSA")) {

					zf.put(entry.getName(), null);
				}

			}

			zf.save();

			System.out.println("Done, you can safely remove GTONative module now");
		} catch (AttachNotSupportedException e) {
			System.err.println("Attach not supported: " + e.getMessage());
			System.err.println("There are more ways to do this, check source code for info!");
		}
	}

	private static Instrumentation instrumentation;
	private static ZipOutputStream zos;
	private static final Set<String> processedClasses = Collections.synchronizedSet(new HashSet<String>());
	private static Set<String> whitelist;
	private static ClassLoader appClassLoader;

	public static void premain(final String agentArgs, final Instrumentation inst) {
		instrumentation = inst;
		try {
			if (agentArgs != null && !agentArgs.trim().isEmpty()) {
				ByteList s = IOUtil.decodeBase64(agentArgs.trim());
				try (var tr = TextReader.from(new File(s.readUTF(s.readableBytes())), StandardCharsets.UTF_8)) {
					whitelist = new HashSet<>(tr.lines());
				}
				System.out.println("Loaded " + whitelist.size() + " classes into whitelist");
			}

			File agentJar = IOUtil.getJar(GTOPatcher.class);
			zos = new ZipOutputStream(new FileOutputStream(new File(agentJar.getParentFile(), "classes.zip")));
			processClasses();
			addShutdownHook();
		} catch (IOException ex) {
			System.err.println("Failed to load whitelist: " + ex.getMessage());
			ex.printStackTrace();
		}
	}

	public static void agentmain(final String agentArgs, final Instrumentation inst) {
		premain(agentArgs, inst);  // Reuse the same logic
	}

	private static void processClasses() {
		try {
			instrumentation.addTransformer(new ClassDumpTransformer(), true);
			final Class<?>[] allLoadedClasses = instrumentation.getAllLoadedClasses();
			final List<Class<?>> toRetransform = new ArrayList<>();
			for (final Class<?> clazz : allLoadedClasses) {
				final String className = clazz.getName();
				if (accepts(className.replace(".", "/")) && instrumentation.isModifiableClass(clazz)) {
					toRetransform.add(clazz);
				}
			}
			System.out.println("Found " + toRetransform.size() + " modifiable whitelist classes to retransform");
			if (!toRetransform.isEmpty()) {
				try {
					instrumentation.retransformClasses(toRetransform.toArray(new Class[0]));
				} catch (final UnmodifiableClassException ex) {
					System.err.println("Failed to retransform some classes: " + ex.getMessage());
				}
			}
		} catch (final Exception ex) {
			System.err.println("Error processing classes: " + ex.getMessage());
			ex.printStackTrace();
		}
	}

	private static void addShutdownHook() {
		Runtime.getRuntime().addShutdownHook(new Thread(() -> {
			loadMissingClasses();
			try {
				if (zos != null) {
					zos.close();
					System.out.println("ZipOutputStream closed successfully");
				}
			} catch (final IOException ex) {
				System.err.println("Error closing ZipOutputStream: " + ex.getMessage());
			}
			System.out.println("Dumped class: " + processedClasses);
		}));
	}

	private static void loadMissingClasses() {
		if (appClassLoader == null || whitelist == null) return;
		for (String className : whitelist) {
			try {
				Class.forName(className.replace('/', '.'), false, appClassLoader);
			} catch (Throwable t) {
				System.err.println("Failed to preload class " + className + ": " + t.getMessage());
			}
		}
	}

	static boolean accepts(String internalName) {
		var block =
				internalName == null ||
						internalName.startsWith("java/") ||
						internalName.startsWith("javax/") ||
						internalName.startsWith("sun/")
				;
		if (block) return false;
		return whitelist == null || whitelist.contains(internalName);
	}

	static class ClassDumpTransformer implements ClassFileTransformer {
		@Override
		public byte[] transform(final ClassLoader classLoader, final String internalName,
								final Class<?> clazz, final ProtectionDomain protectionDomain,
								final byte[] classfileBuffer) {
			if (classLoader == null || !accepts(internalName)) return null;
			if (!processedClasses.add(internalName)) return null;

			final String className = internalName.replace('/', '.');

			appClassLoader = classLoader;
			synchronized (zos) {
				try {
					zos.putNextEntry(new java.util.zip.ZipEntry(internalName + ".class"));
					zos.write(classfileBuffer);
					zos.closeEntry();
					zos.flush();
				} catch (final IOException ex) {
					System.err.println("Error writing class to ZIP: " + className + ", error: " + ex.getMessage());
				}
			}
			return null;
		}
	}
}
