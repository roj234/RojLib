package roj.ci;

import org.jetbrains.annotations.Unmodifiable;
import roj.asmx.AnnotationRepo;
import roj.collect.ArrayList;
import roj.collect.HashMap;
import roj.collect.HashSet;
import roj.collect.TrieTreeSet;
import roj.concurrent.TimerTask;
import roj.config.ConfigMaster;
import roj.io.IOUtil;
import roj.text.Formatter;
import roj.text.ParseException;
import roj.ui.Tty;
import roj.util.ByteList;
import roj.util.DynByteBuf;
import roj.util.Helpers;
import roj.util.function.ExceptionalSupplier;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.Charset;
import java.util.*;
import java.util.concurrent.Callable;

/**
 * @author Roj233
 * @since 2021/7/11 13:59
 */
public final class Project {
	Env.Project conf;

	private final String name;
	String version;
	final Charset charset;
	public final File root, srcPath, resPath, cachePath;

	private List<Project> directDep, buildOrderDep;
	private final EnumMap<Dependency.Scope, List<Dependency>> dependencies;
	private List<Dependency> compileDependencies;

	final Compiler compiler;
	public final Workspace workspace;
	final Map<String, String> variables;
	private final TrieTreeSet varReplacePathPrefix;

	final File unmappedJar;
	private final int resPrefix;
	ZipOutput mappedWriter, unmappedWriter;

	public BuildContext compiling;
	public BuildContext.PersistentState persistentState;

	MCMake.SignatureCache signatureCache;
	final DependencyGraph dependencyGraph = new DependencyGraph();
	final AnnotationRepo annotationRepo = new AnnotationRepo();
	final StructureRepo structureRepo = new StructureRepo();
	/**
	 * @since 3.13
	 */
	private FileList fileList = new FileList();

	public String getName() {return conf.name;}
	public String getSafeName() {return cachePath.getName();}
	public String getShortName() {return name;}
	int getResPrefix() {return resPrefix;}

	public Project(Env.Project config, boolean mkdirs) throws IOException {
		this.conf = config;
		this.name = config.name.substring(config.name.lastIndexOf('/')+1);
		this.version = config.version.toString();
		this.charset = config.charset;

		var ws = MCMake.workspaces.get(config.workspace);
		if (ws == null) throw new NullPointerException("找不到工作空间"+config.workspace);
		this.workspace = ws;

		this.root = new File(workspace.getPath(), config.name);
		this.srcPath = new File(root, "java");
		this.resPath = new File(root, "resources");
		this.cachePath = new File(MCMake.CACHE_PATH, ".pr/"+(config.name.equals(name) ? name : name+"@"+Integer.toHexString(config.name.hashCode())));
		this.unmappedJar = new File(cachePath, "classes.jar");

		this.variables = new HashMap<>(ws.getVariables());
		this.variables.put("project_name", name);
		this.variables.put("project_version", version);
		this.variables.putAll(config.variables);
		/*for (var itr = this.variables.keySet().iterator(); itr.hasNext(); ) {
			if (itr.next().startsWith("fmd:")) itr.remove();
		}*/

		this.dependencies = new EnumMap<>(Dependency.Scope.class);
		if (workspace.getVariableReplaceContext().isEmpty() || conf.variableReplaceContext.isEmpty()) {
			this.varReplacePathPrefix = workspace.getVariableReplaceContext().isEmpty() ? conf.variableReplaceContext : workspace.getVariableReplaceContext();
		} else {
			this.varReplacePathPrefix = new TrieTreeSet(conf.variableReplaceContext);
			this.varReplacePathPrefix.addAll(workspace.getVariableReplaceContext());
		}

		if (config.type == Env.Type.ARTIFACT) {
			resPrefix = 0;
			compiler = null;
			return;
		}

		String compilerType = variables.getOrDefault("fmd:compiler", "Javac");
		var obj = MCMake.compilerTypes.get(compilerType);
		if (obj == null) throw new NullPointerException("找不到编译器"+compilerType);

		compiler = obj.getInstance(srcPath.getAbsolutePath().replace(File.separatorChar, '/'));

		if (root.mkdirs() || mkdirs) {
			srcPath.mkdir();
			resPath.mkdir();
		}
		cachePath.mkdirs();

		var fileListCache = new File(cachePath, "fileList.msg");
		successFullyLoadFileList: {
			if (MCMake.config.getBool("持久化ChangeList") && fileListCache.exists()) {
				try {
					fileList = MCMake.CONFIG.read(fileListCache, FileList.class, ConfigMaster.MSGPACK);
					break successFullyLoadFileList;
				} catch (ParseException e) {
					MCMake.log.error("Could not deserialize FileList", e);
				}
			}

			fileList = new FileList();
		}
		fileList.setPrefix(root.getAbsolutePath()+"/");

		unmappedWriter = new ZipOutput(unmappedJar);
		if (unmappedJar.length() == 0) {
			unmappedWriter.begin(false);
			unmappedWriter.end();
			unmappedJar.setLastModified(0L);
		}

		resPrefix = resPath.getAbsolutePath().length()+1;
	}
	public void init() {
		if (compileDependencies != null) return;
		compileDependencies = new ArrayList<>();
		directDep = new ArrayList<>();

		conf.initShade();
		conf.dependencyInstances = new HashMap<>(conf.dependency.size());

		for (var entry : conf.dependency.entrySet()) {
			var id = entry.getKey();
			Dependency dep;
			if (id.contains("://")) {
				URI uri;
				try {
					uri = new URI(id);
				} catch (URISyntaxException e) {
					MCMake.log.warn("不支持的依赖类型{}", e, id);
					continue;
				}

				// 解析校验信息
				String checksumAlgorithm = null;
				byte[] expectedChecksum = null;
				String userInfo = uri.getUserInfo();
				if (userInfo != null) {
					checksumAlgorithm = userInfo;
					expectedChecksum = IOUtil.decodeHex(uri.getHost());
				}

				switch (uri.getScheme()) {
					case "file" -> {
						String path = uri.getPath();
						File file = IOUtil.resolve(root, path);
						if (!file.exists()) {
							MCMake.log.warn("找不到依赖项目{}", uri);
							continue;
						}

						if (checksumAlgorithm != null && !Dependency.verifyFile(checksumAlgorithm, file, expectedChecksum, id))
							continue;

						dep = file.isDirectory() ? new Dependency.DirDep(file) : new Dependency.FileDep(file);
					}
					case "maven" -> {
						String path = uri.getPath();
						if (path.startsWith("/")) path = path.substring(1);
						dep = new Dependency.MavenDep(this, id, path, checksumAlgorithm, expectedChecksum);
					}
					case "resource" -> {
						String path = uri.getPath();
						File file = IOUtil.resolve(root, path);
						if (!file.isDirectory()) {
							MCMake.log.warn("找不到依赖项目{}", uri);
							continue;
						}

						dep = new Dependency.ResourceFolderDep(file);
					}
					case "project" -> {
						Project project = MCMake.projects.get(uri.getPath());
						if (project == null) {
							MCMake.log.warn("找不到依赖项目{}", uri.getPath());
							continue;
						}
						dep = new Dependency.ProjectDep(project);
						directDep.add(project);
					}
					default -> {
						MCMake.log.warn("不支持的依赖类型{}", id);
						continue;
					}
				}
			} else {
				Project project = MCMake.projects.get(id);
				if (project == null) {
					MCMake.log.warn("找不到依赖项目{}", id);
					continue;
				}
				dep = new Dependency.ProjectDep(project);
				directDep.add(project);
			}

			dep = BuildContext.internDependency(dep);
			dependencies.computeIfAbsent(entry.getValue(), Helpers.fnArrayList()).add(dep);
			conf.dependencyInstances.put(entry.getKey(), dep);

			if (entry.getValue().inClasspath()) compileDependencies.add(dep);
			if (entry.getValue().copyResource()) {
				if (!conf.type.canBuild()) throw new IllegalArgumentException("不可构建的子模块不允许使用BUNDLED或PROCESSED引用\n如果子模块被BUNDLED，那么其EXPORT类型将被一并合入");
			}
		}

		var projects = new LinkedHashSet<Project>();
		gatherDependencies(projects, this);
		buildOrderDep = new ArrayList<>(projects);
	}

	public void close() {
		IOUtil.closeSilently(mappedWriter);
		IOUtil.closeSilently(unmappedWriter);
	}

	@Override
	public String toString() {return getName();}

	private void gatherDependencies(Set<Project> all, Project exportOwner) {
		for (var project : directDep) {
			project.init();
			project.gatherDependencies(all, exportOwner);

			// EXPORT类型的依赖具有传递性
			for (Dependency dependency : project.dependencies.getOrDefault(Dependency.Scope.EXPORT, Collections.emptyList())) {
				if (!exportOwner.compileDependencies.contains(dependency))
					exportOwner.compileDependencies.add(dependency);

				List<Dependency> bundleDeps = exportOwner.dependencies.getOrDefault(Dependency.Scope.BUNDLED, Collections.emptyList());
				boolean isBundled = bundleDeps.contains(new Dependency.ProjectDep(project));
				if (isBundled && dependency.project().variables.getOrDefault("fmd:exportBundle", "true").equals("true")) {
					if (!bundleDeps.contains(dependency)) bundleDeps.add(dependency);
				}
			}
			all.add(project);
		}
	}

	@Unmodifiable public List<Project> getProjectDependencies() {return buildOrderDep;}
	@Unmodifiable public List<Dependency> getCompileDependencies() {return compileDependencies;}
	@Unmodifiable public List<Dependency> getBundledDependencies() {return dependencies.getOrDefault(Dependency.Scope.BUNDLED, Collections.emptyList());}

	private int resCount;
	public Callable<Integer> getAsyncResourceWriter(BuildContext ctx) {
		return () -> {
			resCount = 0;
			long useCompileTimestamp = shouldUseCompileTimestamp() ? ctx.buildStartTime : 0;

			List<File> changed = ctx.resources.getChanged();
			for (File file : changed) {
				writeRes(file,
						file.getAbsolutePath().substring(resPrefix).replace(File.separatorChar, '/'),
						useCompileTimestamp != 0 ? useCompileTimestamp : file.lastModified(),
						Collections.emptyMap(),
						ctx
				);
				resCount++;
			}

			Set<String> removed = ctx.resources.getRemoved();
			for (String relPath : removed) {
				mappedWriter.set(relPath, (ByteList) null);
				resCount++;
			}

			if ((changed.size()|removed.size()) > 0)
				MCMake.log.debug("Resource: {} changed, {} removed.", changed.size(), removed.size());

			for (Dependency bundle : getBundledDependencies()) {
				resCount += bundle.getResources(this, mappedWriter, ctx);
			}

			return resCount;
		};
	}

	public boolean shouldUseCompileTimestamp() {return "true".equals(variables.get("fmd:resource:use_compile_timestamp"));}

	void writeRes(File file, String relPath, long time, Map<String, String> altVariables, BuildContext ctx) {
		ExceptionalSupplier<InputStream, IOException> writer;
		try {
			if (varReplacePathPrefix.strStartsWithThis(relPath)) {
				String string;
				try {
					string = IOUtil.readString(file);
				} catch (Exception e) {
					MCMake.log.warn("变量替换规则可能命中了二进制文件{}", e, relPath);
					return;
				}

				var template = Formatter.simple(string);
				if (!template.isConstant()) {
					string = template.format(new TwoMap(altVariables), IOUtil.getSharedCharBuf()).toString();
				}

				String fuckJavac = string;
				writer = () -> ctx.wrapResource(relPath, DynByteBuf.wrap(fuckJavac.getBytes(charset)).asInputStream());
			} else {
				writer = () -> ctx.wrapResource(relPath, new FileInputStream(file));
			}

			mappedWriter.setStream(relPath, writer, time);
		} catch (IOException e) {
			MCMake.log.warn("资源文件{}复制失败", e, relPath);
		}
	}

	private boolean autoCompile;
	public void setAutoCompile(boolean b) {autoCompile = b;}
	public boolean isAutoCompile() {return autoCompile;}

	public void buildSuccess(boolean increment) {
		fileList.commit();

		try {
			MCMake.watcher.add(this);
		} catch (IOException e) {
			Tty.warning("无法启动文件监控", e);
		}
		if (!increment) {
			var copyTo = variables.get("fmd:copy_to");
			if (copyTo != null) {
				try {
					IOUtil.copyFile(mappedWriter.file, new File(copyTo));
					Tty.success("已将编译产物["+name+"]复制到目标文件夹");
				} catch (IOException e) {
					Tty.warning("无法复制文件", e);
				}
			}
		}
	}
	void savePersistentState() {
		if (MCMake.config.getBool("持久化ChangeList") && fileList.wasChanged()) {
			try {
				MCMake.CONFIG.write(ConfigMaster.MSGPACK, fileList, new File(cachePath, "fileList.msg"));
			} catch (IOException e) {
				MCMake.log.warn("Failed to save FileList", e);
			}
		}
	}
	void clearPersistentState() {
		fileList = new FileList();
		fileList.setPrefix(root.getAbsolutePath()+"/");
	}
	void initializeFileList(boolean forceReload) {
		if (!MCMake.config.getBool("持久化ChangeList")) return;
		if (!fileList.isEmpty() && !forceReload) return;

		clearPersistentState();
		MCMake.log.debug("initializeFileList {}", name);

		var srcChanged = IOUtil.listFiles(srcPath, BuildContext.JavaChangeset::isCompilable);
		for (File file : srcChanged) fileList.addFileFromFS(file);

		var resChanged = IOUtil.listFiles(resPath);
		for (File file : resChanged) fileList.addFileFromFS(file);

		fileList.commit();
	}

	void fileChanged(String pathname) {fileList.fileChanged(pathname);}
	Set<String> getDeleted(int watcherSlot) {
		Set<String> alt = fileList.getDeletedAlt(watcherSlot == FileWatcher.ID_SRC ? "java" : "resources");
		if (alt != null) return alt;
		return Collections.emptySet();
	}

	private static TimerTask autoCompileTask;
	private static final Set<Project> buildPending = new HashSet<>();
	private static void executePendingBuild() {
		HashSet<Project> roots;
		synchronized (buildPending) {
			roots = new HashSet<>(buildPending);
			for (Project project : buildPending) {
				roots.removeAll(project.buildOrderDep);
			}
			buildPending.clear();
		}

		MCMake._lock(false);
		MCMake.log.debug("自动编译开始, 目标: {}", roots);
		try {
			for (Project project : roots) {
				try {
					int code = MCMake.build(new HashSet<>("auto", "silent"), project);
					if (code != 0) break;
				} catch (Throwable e) {
					MCMake.log.error("自动编译出错", e);
					MCMake.watcher.removeAll();
					return;
				}
			}
		} finally {
			MCMake._unlock();
		}
		throttleBuild();
	}

	public void fileChanged() {
		if (!fileList.havePendingChanges()) return;

		var isRoot = true;
		for (Project q : MCMake.projects.values()) {
			if (q.autoCompile && q.buildOrderDep.contains(this)) {
				synchronized (buildPending) {
					isRoot = false;
					buildPending.add(q);
				}
			}
		}
		if (isRoot) {
			synchronized (buildPending) { buildPending.add(this); }
		}

		throttleBuild();
	}

	private static void throttleBuild() {
		if (autoCompileTask != null) autoCompileTask.cancel();

		if (!buildPending.isEmpty()) {
			synchronized (buildPending) {
				if (autoCompileTask != null) autoCompileTask.cancel();
				autoCompileTask = MCMake.TIMER.delay(Project::executePendingBuild, MCMake.config.getInt("自动编译防抖"));
			}
		}
	}

	@Unmodifiable
	public Map<String, String> getVariables() {return variables;}
	public String getOutputFormat() {return Formatter.simple(variables.getOrDefault("fmd:name_format", "${project_name}-${project_version}.jar")).format(variables, IOUtil.getSharedCharBuf()).toString();}

	public boolean isArtifact() {return conf.type == Env.Type.ARTIFACT;}

	final class TwoMap extends AbstractMap<String, Object> {
		private final Map<String, String> altVariables;
		public TwoMap(Map<String, String> altVariables) {this.altVariables = altVariables;}

		public Set<Entry<String, Object>> entrySet() {return Collections.emptySet();}
		public Object get(Object key) {return altVariables.getOrDefault(key, variables.get(key));}
	}
}