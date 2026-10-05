package roj.ci;

import org.jetbrains.annotations.Nullable;
import roj.archive.zip.ZipEntry;
import roj.archive.zip.ZipFile;
import roj.asm.ClassView;
import roj.asmx.AnnotationRepo;
import roj.asmx.Context;
import roj.collect.HashSet;
import roj.collect.XashMap;
import roj.config.ConfigMaster;
import roj.config.node.IntValue;
import roj.config.node.xml.Document;
import roj.config.node.xml.Node;
import roj.io.IOUtil;
import roj.text.CharList;
import roj.text.ParseException;
import roj.text.TextUtil;
import roj.util.ByteList;
import roj.util.DynByteBuf;
import roj.util.Helpers;
import roj.util.Pair;

import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static roj.ci.BuildContext.*;

/**
 * @author Roj234
 * @since 2025/08/30 03:32
 */
public sealed interface Dependency extends Closeable {
	default @Nullable Project project() {return null;}
	default void open(BuildContext ctx) throws IOException {}
	default void unlock() {}
	default void close() throws IOException {}

	int getResources(Project project, ZipOutput zipOutput, BuildContext ctx) throws IOException;
	void getClasses(BuildContext context) throws IOException;
	AnnotationRepo getAnnotations(BuildContext context) throws IOException;

	void forEachJar(Consumer<File> consumer);
	default void getClassPath(CharList classpath, File prefix) {
		forEachJar(file -> {
			String absolutePath = file.getAbsolutePath();
			String relativized = IOUtil.relativize(prefix.getAbsolutePath(), absolutePath);
			classpath.append(relativized == null ? absolutePath : relativized).append(File.pathSeparatorChar);
		});
	}
	void writeProjectConfiguration(File root, CharList sb, String type);

	static boolean verifyFile(String checksumAlgorithm, File file, byte[] expectedChecksum, String id) {
		try {
			MessageDigest digest = MessageDigest.getInstance(checksumAlgorithm);
			IOUtil.digestFile(file, file.length(), digest);
			byte[] checksum = digest.digest();
			if (!Arrays.equals(checksum, expectedChecksum)) {
				MCMake.log.warn("文件校验失败: {} (期望: {}, 实际: {})", id, IOUtil.encodeHex(expectedChecksum), IOUtil.encodeHex(checksum));
				return false;
			}
		} catch (Exception e) {
			MCMake.log.warn("无法计算文件校验和: {}", e, id);
			return false;
		}

		return true;
	}

	final class ProjectDep implements Dependency {
		private final Project project;

		public ProjectDep(Project project) {this.project = project;}

		@Override
		public @Nullable Project project() {return project;}

		@Override
		public void open(BuildContext ctx) throws IOException {project.initializeFileList(false);}

		@Override
		public int getResources(Project building, ZipOutput writer, BuildContext ctx) {
			int updated = 0;
			var prev = project.mappedWriter;
			project.mappedWriter = writer;
			try {
				int increment = ctx.incrementLevel;

				BuildContext.Changeset changeset = increment <= INC_REBUILD ? project.compiling.getFullResources() : project.compiling.resources;

				List<File> changed = changeset.getChanged();
				Set<String> removed = changeset.getRemoved();

				long stamp = ctx.lastBuildTime;
				// 是否在同一批次编译
				if (increment == INC_UPDATE && project.compiling.lastBuildTime > stamp) {
					changed = IOUtil.listFiles(project.resPath, (pathname, attr) -> attr.lastModifiedTime().toMillis() >= stamp);
				}

				if ((changed.size()|removed.size()) > 0)
					MCMake.log.debug("Dependency resources [{}]: {} changed, {} removed.", project.getName(), changed.size(), removed.size());

				long useCompileTimestamp = project.shouldUseCompileTimestamp() ? ctx.buildStartTime : 0;

				for (File file : changed) {
					String relPath = file.getAbsolutePath().substring(project.getResPrefix()).replace(File.separatorChar, '/');
					String shadePath = applyShade(building.conf, relPath, this);
					if (shadePath != null) {
						project.writeRes(file, shadePath, useCompileTimestamp != 0 ? useCompileTimestamp : file.lastModified(), building.variables, ctx);
						updated++;
					}
				}

				for (String relPath : removed) {
					String shadePath = applyShade(building.conf, relPath, this);
					if (shadePath != null) {
						writer.set(shadePath, (ByteList) null);
						updated++;
					}
				}
			} catch (IOException e) {
				MCMake.log.warn("Exception writing resources for ProjectDep {}", e, project);
			} finally {
				project.mappedWriter = prev;
			}
			return updated;
		}

		static String applyShade(Env.Project bundleIgnore, String relPath, Dependency own) {
			if (bundleIgnore.shade.isEmpty()) return relPath.startsWith("META-INF/") ? null : relPath;

			var match = bundleIgnore.prefixShades.longestMatch(relPath);
			if (match != null) {
				String str = relPath+":"+own.toString();
				var subMatch = bundleIgnore.prefixShades.longestMatch(str);
				return subMatch.getIntKey() == str.length() ? subMatch.getValue() : match.getValue();
			}

			for (Pair<Pattern, String> entry : bundleIgnore.patternShades) {
				Matcher matcher = entry.getKey().matcher(relPath);
				if (matcher.matches()) {
					String shade = entry.getValue();
					if (shade != null && shade.indexOf('$') >= 0) {
						var sb = IOUtil.getSharedCharBuf().append(shade);
						for (int i = 0; i < matcher.groupCount(); i++) {
							sb.replace("$"+i, matcher.group(i));
						}
						return sb.toString();
					}
					return shade;
				}
			}

			return relPath;
		}

		@Override
		public void getClasses(BuildContext context) throws IOException {
			long stamp = context.lastBuildTime;
			int increment = context.incrementLevel;

			// 是否在同一批次编译
			if (increment == INC_UPDATE && project.compiling.lastBuildTime < context.lastBuildTime) {
				List<Context> changed = project.compiling.getChangedClasses();
				Set<String> removed = project.compiling.getRemovedClasses();
				for (Context ctx : changed) {
					context.addClass(ctx, project, false);
				}
				for (var className : removed) {
					context.classRemoved(className);
				}

				if ((changed.size()|removed.size()) > 0)
					MCMake.log.debug("Dependency binaries [{}]: {} changed, {} removed.", project.getName(), changed.size(), removed.size());
				return;
			}

			try (var archive = project.unmappedWriter.getArchive()) {
				for (ZipEntry entry : archive.entries()) {
					String name = entry.getName();
					if (increment != INC_UPDATE || entry.getModificationTime() >= stamp) {
						context.addClass(new Context(name, archive.get(entry)), project,
								increment != INC_LOAD || entry.getModificationTime() >= stamp);
					}
				}
			}
		}

		@Override public AnnotationRepo getAnnotations(BuildContext context) {return project.annotationRepo;}
		@Override public void forEachJar(Consumer<File> consumer) {consumer.accept(project.unmappedJar);}

		@Override
		public void writeProjectConfiguration(File root, CharList sb, String type) {
			sb.append("type=\"module\" module-name=\"").append(project.getShortName()).append("\" />");
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;

			ProjectDep that = (ProjectDep) o;

			return project == that.project;
		}

		@Override public int hashCode() {return project.hashCode();}
		@Override public String toString() {return project.getName();}
	}

	final class FileDep implements Dependency {
		private final File file;
		private long lastModified;
		private ZipFile archive;
		private Set<ZipEntry> classes;
		private AnnotationRepo repo;

		private FileDep _next;

		public FileDep(File file) {
			this.file = file;
		}

		public void open(BuildContext ctx) throws IOException {
			if (archive == null) archive = new ZipFile(file);
			else {
				if (file.lastModified() == lastModified) return;

				archive.source().reopen();
				archive.reload();
				repo = null;
			}

			lastModified = file.lastModified();
			classes = new HashSet<>();
			for (ZipEntry entry : archive.entries()) {
				String name = entry.getName();
				if (name.endsWith(".class")) {
					ClassView view = ClassView.parse(DynByteBuf.wrap(archive.get(entry)), false);
					if (view.name.regionMatches(0, name, 0, name.length()-6)) {
						classes.add(entry);
					}
				}
			}

			archive.source().close();
		}

		@Override
		public void unlock() {
			if (archive != null)
				archive.closeCache();
		}

		@Override
		public void close() throws IOException {
			IOUtil.closeSilently(archive);
			archive = null;
		}

		@Override
		public int getResources(Project building, ZipOutput zipOutput, BuildContext ctx) throws IOException {
			int updated = 0;
			long lastBuildTime = ctx.incrementLevel <= INC_REBUILD ? 0 : ctx.lastBuildTime;
			for (ZipEntry entry : archive.entries()) {
				String relPath = entry.getName();
				String shadePath = ProjectDep.applyShade(building.conf, relPath, this);
				if (shadePath == null) continue;
				if (relPath.endsWith("/") || classes.contains(entry)) continue;

				if (entry.getModificationTime() >= lastBuildTime) {
					if (zipOutput.getWriter() != null && shadePath.equals(relPath)) {
						zipOutput.getWriter().copy(archive, entry);
					} else {
						zipOutput.set(shadePath, () -> DynByteBuf.wrap(archive.get(entry)), entry.getModificationTime());
					}

					updated++;
				}
			}
			return updated;
		}

		@Override
		public void getClasses(BuildContext ctx) throws IOException {
			int increment = ctx.incrementLevel;
			long lastBuildTime = ctx.lastBuildTime;

			for (ZipEntry entry : classes) {
				if (increment != INC_UPDATE || entry.getModificationTime() >= lastBuildTime) {
					ctx.addClass(new Context(entry.getName(), archive.get(entry)), null,
							increment != INC_LOAD || entry.getModificationTime() >= lastBuildTime);
				}
			}
		}

		@Override
		public AnnotationRepo getAnnotations(BuildContext ctx) throws IOException {
			if (repo == null) {
				repo = new AnnotationRepo();
				repo.loadCacheOrAdd(archive);
			}
			return repo;
		}

		@Override
		public void forEachJar(Consumer<File> consumer) {
			consumer.accept(file);
		}

		@Override
		public void writeProjectConfiguration(File root, CharList sb, String type) {
			String absolutePath = file == null ? "uninitialized.jar" : file.getAbsolutePath();
			String relativized = IOUtil.relativize(root.getAbsolutePath(), absolutePath);
			String uri = relativized == null ? "jar://" + IOUtil.normalize(absolutePath) : "jar://$MODULE_DIR$/" + relativized;
			sb.append(" type=\"module-library\"><library><CLASSES><root url=\"").append(uri).append("!/\" />\n" +
					"</CLASSES><JAVADOC /><SOURCES /></library></orderEntry>");
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;

			FileDep fileDep = (FileDep) o;

			return file.equals(fileDep.file);
		}

		@Override public int hashCode() {return file.hashCode();}
		@Override public String toString() {return "FILE:"+file.getName();}
	}

	final class DirDep implements Dependency {
		private final File dir;
		private static final XashMap.Template<File, FileDep> TEMPLATE = XashMap.forType(File.class, FileDep.class).key("file").newValue(FileDep::new).build();
		private final XashMap<File, FileDep> fileDeps = TEMPLATE.create();
		private AnnotationRepo repo;

		public DirDep(File dir) {this.dir = dir;}

		public void open(BuildContext ctx) throws IOException {
			var files = IOUtil.listFiles(dir, new HashSet<>(), (pathname, attr) -> {
				String ext = IOUtil.getExtension(pathname);
				return (ext.equals("zip") || ext.equals("jar")) && attr.size() != 0;
			});

			for (var itr = fileDeps.iterator(); itr.hasNext(); ) {
				FileDep dep = itr.next();
				if (!files.remove(dep.file)) {
					IOUtil.closeSilently(dep.archive);
					itr.remove();
				} else {
					dep.open(ctx);
				}
			}
			for (File file : files) fileDeps.computeIfAbsent(file).open(ctx);
		}

		@Override
		public void unlock() {
			for (FileDep dep : fileDeps) dep.unlock();
		}

		@Override
		public void close() throws IOException {
			for (FileDep dep : fileDeps) dep.close();
			fileDeps.clear();
		}

		@Override
		public int getResources(Project project, ZipOutput zipOutput, BuildContext ctx) throws IOException {
			int cnt = 0;
			for (FileDep dep : fileDeps) {
				cnt += dep.getResources(project, zipOutput, ctx);
			}
			return cnt;
		}

		@Override
		public void getClasses(BuildContext context) throws IOException {
			for (FileDep dep : fileDeps) {
				dep.getClasses(context);
			}
		}

		@Override
		public void forEachJar(Consumer<File> consumer) {
			for (FileDep dep : fileDeps) {
				dep.forEachJar(consumer);
			}
		}

		@Override
		public void writeProjectConfiguration(File root, CharList sb, String type) {
			String absolutePath = dir.getAbsolutePath();
			String relativized = IOUtil.relativize(root.getAbsolutePath(), absolutePath);
			String uri = relativized == null ? "file://" + IOUtil.normalize(absolutePath) : "file://$MODULE_DIR$/" + relativized;
			sb.append("type=\"module-library\"><library><CLASSES><root url=\"").append(uri).append("\" />\n" +
					"</CLASSES><JAVADOC /><SOURCES />\n" +
					"<jarDirectory url=\"").append(uri).append("\" recursive=\"true\" />\n" +
					"</library></orderEntry>");
		}

		@Override
		public AnnotationRepo getAnnotations(BuildContext context) throws IOException {
			if (repo == null) {
				repo = new AnnotationRepo();
				for (FileDep dep : fileDeps) {
					repo.loadCacheOrAdd(dep.archive);
				}
			}
			return repo;
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;

			DirDep dirDep = (DirDep) o;

			return dir.equals(dirDep.dir);
		}

		@Override public int hashCode() {return dir.hashCode();}
		@Override public String toString() {return "DIR:"+dir.getName();}
	}

	final class MavenDep implements Dependency {
		private FileDep cache;

		private final File cacheBase;
		private final List<String> mavenUrls;
		private final MavenCoordinate coordinate;

		public MavenDep(Project owner, String dependencyId, String mavenCoordinate, String checksumAlgorithm, byte[] expectedChecksum) {
			this.coordinate = new MavenCoordinate(mavenCoordinate);
			if ("sha1".equals(checksumAlgorithm)) this.coordinate.setChecksum(expectedChecksum);

			this.mavenUrls = TextUtil.split(owner.variables.getOrDefault("fmd:maven:central", "https://repo1.maven.org/maven2/"), ';');

			String cachePath = owner.variables.get("fmd:maven:cache");
			cacheBase = cachePath != null ? new File(cachePath) : new File(MCMake.CACHE_PATH, ".m2");
		}

		@Override public void open(BuildContext ctx) throws IOException {
			locateCache(ctx, false);
			cache.open(ctx);
		}

		@Override public void close() throws IOException {IOUtil.closeSilently(cache);}
		@Override public void unlock() {if (cache != null) cache.unlock();}

		private void locateCache(BuildContext ctx, boolean optional) throws IOException {
			long time = System.currentTimeMillis();
			File file = coordinate.locate(cacheBase, mavenUrls, 1800, !optional);
			if (file == null) return;

			if (cache == null || file.equals(cache.file)) {
				IOUtil.closeSilently(cache);
				cache = new FileDep(file);

				if (ctx != null && System.currentTimeMillis() - time > 100)
					checkDeps(ctx);
			}
		}

		private void checkDeps(BuildContext ctx) throws IOException {
			cache.open(ctx);

			Set<String> deps = new HashSet<>();
			for (var dep : ctx.project.getBundledDependencies()) {
				if (dep instanceof MavenDep md) {
					deps.add(md.coordinate.groupId + ":" + md.coordinate.artifactId);
				}
			}
			for (ZipEntry entry : cache.archive.entries()) {
				if (entry.getName().startsWith("META-INF/maven") && entry.getName().equals("/pom.xml")) {
					try {
						var doc = (Document) ConfigMaster.XML.parse(cache.archive.getInputStream(entry));
						for (Node node : doc.querySelectorAll("/project/dependencies/dependency")) {
							Node groupId = node.querySelector("/groupId");
							Node artifactId = node.querySelector("/artifactId");
							Node scope = node.querySelector("/scope");
							if (scope != null && scope.asString().equals("test")) continue;

							String item = groupId + ":" + artifactId;
							if (deps.add(item)) {
								MavenCoordinate.LOGGER.warn("项目"+ ctx.project.getName()+"可能缺少Maven依赖 "+item);
							}
						}
					} catch (ParseException e) {
						MavenCoordinate.LOGGER.warn("Failed to parse "+entry.getName(), e);
					}
				}
			}

			cache.close();
		}

		@Override public int getResources(Project project, ZipOutput zipOutput, BuildContext ctx) throws IOException {return cache.getResources(project, zipOutput, ctx);}
		@Override public void getClasses(BuildContext context) throws IOException {cache.getClasses(context);}
		@Override public AnnotationRepo getAnnotations(BuildContext context) throws IOException {return cache.getAnnotations(context);}
		@Override public void forEachJar(Consumer<File> consumer) {cache.forEachJar(consumer);}
		@Override public void writeProjectConfiguration(File root, CharList sb, String type) {
			try {
				locateCache(null, true);
			} catch (IOException e) {
				Helpers.athrow(e);
			}
			if (cache != null)
				cache.writeProjectConfiguration(root, sb, type);
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;

			MavenDep mavenDep = (MavenDep) o;
			return mavenUrls.equals(mavenDep.mavenUrls) && coordinate.equals(mavenDep.coordinate);
		}

		@Override
		public int hashCode() {
			int result = mavenUrls.hashCode();
			result = 31 * result + coordinate.hashCode();
			return result;
		}
	}

	final class ResourceFolderDep implements Dependency {
		private final File resPath;

		public ResourceFolderDep(File resPath) {this.resPath = resPath;}

		@Override
		public int getResources(Project building, ZipOutput writer, BuildContext ctx) {
			IntValue updated = new IntValue(0);
			try {
				int increment = ctx.incrementLevel;
				long stamp = ctx.lastBuildTime;
				int prefixLength = resPath.getAbsolutePath().length() + 1;

				Files.walkFileTree(resPath.toPath(), new SimpleFileVisitor<>() {
					@Override
					public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
						String relPath = file.toString().substring(prefixLength).replace(File.separatorChar, '/');
						long modTime = attrs.lastModifiedTime().toMillis();
						if (increment <= INC_REBUILD || modTime >= stamp) {
							writer.setStream(relPath, () -> new FileInputStream(file.toString()), modTime);
							updated.value++;
						}
						return FileVisitResult.CONTINUE;
					}
				});
			} catch (IOException e) {
				MCMake.log.warn("Exception writing resources {}", e, resPath);
			}
			return updated.value;
		}

		@Override public void getClasses(BuildContext context) {}
		@Override public AnnotationRepo getAnnotations(BuildContext context) {return new AnnotationRepo();}
		@Override public void forEachJar(Consumer<File> consumer) {}
		@Override public void writeProjectConfiguration(File root, CharList sb, String type) {}

		@Override public String toString() {return "RES:"+ resPath.getName();}

		@Override public final boolean equals(Object object) {
			if (this == object) return true;
			if (!(object instanceof ResourceFolderDep that)) return false;

			return resPath.equals(that.resPath);
		}
		@Override public int hashCode() {return resPath.hashCode();}
	}

	enum Scope {
		/**
		 * 编译时需要，不传递
		 */
		COMPILE,
		/**
		 * 编译时需要，且传递性依赖（依赖该模块的模块会自动获得其 EXPORT 依赖）
		 */
		EXPORT,
		/**
		 * 打包时需要，包括所有直接和传递的 EXPORT 依赖（即递归包含所有必要的依赖）。
		 */
		BUNDLED;

		public boolean inClasspath() {return true;}
		public boolean exported() {return this == EXPORT;}
		public boolean copyResource() {return ordinal() >= 2;}
	}
}
