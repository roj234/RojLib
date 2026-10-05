package roj.plugin;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import roj.archive.zip.ZipEntry;
import roj.archive.zip.ZipFile;
import roj.asmx.*;
import roj.asmx.launcher.Loader;
import roj.collect.ArrayList;
import roj.crypt.jar.JarVerifier;
import roj.io.source.FileSource;
import roj.text.URICoder;
import roj.util.ByteList;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.security.CodeSigner;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

/**
 * @author Roj234
 * @since 2023/12/25 15:48
 */
public class PluginClassLoader extends ClassLoader {
	public static final ThreadLocal<PluginClassLoader> PLUGIN_CONTEXT = new ThreadLocal<>();

	final PluginDescriptor desc;
	final PluginDescriptor[] accessible;
	final ZipFile archive;
	final JarVerifier jarVerifier;
	final ProtectionDomain protectionDomain;

	final List<Transformer> transformers = new ArrayList<>();
	ConstantPoolHooks hooks;

	public PluginClassLoader(ClassLoader parent, PluginDescriptor plugin, PluginDescriptor[] accessible) throws IOException {
		super(plugin.id, parent);
		this.desc = plugin;
		this.accessible = accessible;
		this.archive = new ZipFile(plugin.source, ZipFile.FLAG_Verify|ZipFile.FLAG_ReadCENOnly, plugin.charset);
		this.archive.reload();

		File file = ((FileSource) plugin.source).getFile();
		this.jarVerifier = JarVerifier.create(archive, file);
		if (jarVerifier != null) jarVerifier.getTrustLevel();
		var cs = jarVerifier == null ? new CodeSource(new URL("file", "", file.getAbsolutePath().replace(File.separatorChar, '/')), (CodeSigner[]) null) : jarVerifier.getCodeSource();
		protectionDomain = new ProtectionDomain(cs, null);

		if (Arrays.equals(cs.getCertificates(), Jocker.digitalCertificates)) {
			desc.isTrusted = true;
		}

		AnnotationRepoManager.setAnnotations(this, () -> {
			var repo = new AnnotationRepo();
			repo.loadCacheOrAdd(archive);
			return repo;
		});
	}

	@Override
	protected Class<?> findClass(String name) throws ClassNotFoundException {
		String entryName = name.replace('.', '/').concat(".class");
		ZipEntry entry = archive.getEntry(entryName);
		if (entry == null) {
			for (var s : accessible) {
				if (s.classLoader != null && s.classLoader.archive.getEntry(entryName) != null) {
					return s.classLoader.findClass(name);
				}
			}
			throw new ClassNotFoundException(name);
		}

		PluginClassLoader prev = PLUGIN_CONTEXT.get();
		PLUGIN_CONTEXT.set(this);
		try {
			var in = archive.getInputStream(entry);
			if (in != null) jarVerifier.wrapInput(entryName, in);
			var ctx = new Context(entryName, in);

			var globalTransformers = Loader.instance.getTransformers();
			for (int i = 0; i < globalTransformers.size(); i++)
				globalTransformers.get(i).transform(name, ctx);

			for (int i = 0; i < transformers.size(); i++)
				transformers.get(i).transform(name, ctx);

			if (!desc.isTrusted)
				PanSecurityManager.transformer.transform(name, ctx);

			ByteList buf = ctx.get();
			return defineClass(name, buf.list, 0, buf.wIndex(), protectionDomain);
		} catch (Exception e) {
			throw new ClassNotFoundException("failed", e);
		} finally {
			if (prev == null) PLUGIN_CONTEXT.remove();
			else PLUGIN_CONTEXT.set(prev);
		}
	}

	@Override
	protected URL findResource(String name) {
		if (archive.getEntry(name) != null) {
			try {
				return getUrl(name);
			} catch (MalformedURLException ignored) {}
		}
		return null;
	}

	@NotNull
	private URL getUrl(String name) throws MalformedURLException {
		return new URL("jar:file:/"+(desc.source instanceof FileSource fs ? fs.getFile().getAbsolutePath() : desc.fileName)+"!/"+ URICoder.encodeURI(name));
	}

	@Override
	protected Enumeration<URL> findResources(String name) { return archive.getEntry(name) != null ? Collections.enumeration(Collections.singleton(getResource(name))) : Collections.emptyEnumeration(); }

	@Nullable
	@Override
	public InputStream getResourceAsStream(String name) {
		ZipEntry entry = archive.getEntry(name);
		if (entry == null) return getParent().getResourceAsStream(name);
		try {
			return archive.getInputStream(entry);
		} catch (Exception e) {
			return null;
		}
	}

	public void close() throws IOException { archive.close(); }
}