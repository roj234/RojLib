package roj.asmx;

import roj.io.IOUtil;
import roj.reflect.VirtualReference;
import roj.text.logging.Logger;
import roj.util.Helpers;

import java.net.URL;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.function.BiConsumer;

/**
 * @author Roj234
 * @since 2025/10/21 22:34
 */
public class AnnotationRepoManager {
	private static final VirtualReference<Object> CACHE = new VirtualReference<>();
	private static final Logger log = Logger.getLogger(AnnotationRepoManager.class.getName());
	private static boolean initialized;

	public static void initializeAnnotatedType(String type, ClassLoader loader, boolean inherit) {
		getAnnotations(type, loader, (loader1, element) -> {
			String className = element.owner().replace('/', '.');
			try {
				Class.forName(className, true, loader1);
			} catch (ClassNotFoundException e) {
				log.warn("Class {} not found", className);
			}
		}, inherit);
	}

	public static void getAnnotations(String type, ClassLoader loader, BiConsumer<ClassLoader, AnnotatedElement> consumer, boolean inherit) {
		if (!initialized) initializeFromClassLoader(loader);
		do {
			var repo = CACHE.get(loader);
			if (repo instanceof Callable<?> supplier) {
				try {
					CACHE.put(loader, repo = supplier.call());
				} catch (Exception e) {
					log.warn("ClassLoader {} failed to load AnnotationRepo", e, loader);
					repo = null;
				}
			}
			if (repo != null) {
				for (AnnotatedElement element : ((AnnotationRepo) repo).annotatedBy(type)) {
					consumer.accept(loader, element);
				}
			}

			if (!inherit) return;
			loader = loader.getParent();
		} while (loader != null);
	}
	public static Set<AnnotatedElement> getAnnotations(String type, ClassLoader loader) {
		if (!initialized) initializeFromClassLoader(loader);
		var repo = CACHE.get(loader);
		if (repo instanceof Callable<?> supplier) {
			try {
				CACHE.put(loader, repo = supplier.call());
			} catch (Exception e) {
				log.warn("ClassLoader {} failed to load AnnotationRepo", e, loader);
				repo = null;
			}
		}
		return repo == null ? Collections.emptySet() : ((AnnotationRepo) repo).annotatedBy(type);
	}

	private static void initializeFromClassLoader(ClassLoader loader) {
		do {
			var repo = CACHE.get(loader);
			if (repo != null) return;

			var repo_ = new AnnotationRepo();
			loadFromClassicClassLoader(loader, repo_);
			CACHE.put(loader, repo_);

			loader = loader.getParent();
		} while (loader != null);

	}
	public static void loadFromClassicClassLoader(ClassLoader loader, AnnotationRepo repo) {
		try {
			Enumeration<URL> resources = loader.getResources("META-INF/annotations.repo");
			while (resources.hasMoreElements()) {
				URL resource = resources.nextElement();
				try (var in = resource.openStream()) {
					repo.deserialize(IOUtil.getSharedByteBuf().readStreamFully(in));
				}
			}
		} catch (Exception e) {
			Helpers.athrow(e);
		}
	}

	public static void setAnnotations(ClassLoader loader, AnnotationRepo repo) {initialized = true;CACHE.put(loader, repo);}
	public static void setAnnotations(ClassLoader loader, Callable<AnnotationRepo> repoLoader) {initialized = true;CACHE.put(loader, repoLoader);}
}
