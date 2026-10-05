package roj.ci;

import org.jetbrains.annotations.NotNull;
import roj.archive.sevenz.*;
import roj.archive.sevenz.util.SevenZArchiver;
import roj.archive.xz.LZMA2Options;
import roj.archive.zip.ZipEditor;
import roj.archive.zip.ZipEntry;
import roj.archive.zip.ZipPacker;
import roj.asm.ClassNode;
import roj.asmx.AnnotationRepo;
import roj.asmx.ClassResource;
import roj.asmx.Context;
import roj.ci.annotation.ReplaceConstant;
import roj.ci.plugin.Plugin;
import roj.collect.ArrayList;
import roj.collect.HashMap;
import roj.collect.HashSet;
import roj.compiler.CompileContext;
import roj.compiler.LavaCompileUnit;
import roj.compiler.LavaCompiler;
import roj.compiler.resolve.Resolver;
import roj.compiler.test.TestPlugin;
import roj.concurrent.Timer;
import roj.concurrent.TimerTask;
import roj.concurrent.*;
import roj.config.ConfigMaster;
import roj.config.TextParser;
import roj.config.YamlParser;
import roj.config.mapper.ObjectMapper;
import roj.config.node.ConfigValue;
import roj.config.node.MapValue;
import roj.config.schema.Schema;
import roj.crypt.jar.JarVerifier;
import roj.gui.Profiler;
import roj.http.HttpRequest;
import roj.http.curl.DownloadTask;
import roj.io.IOUtil;
import roj.io.source.FileSource;
import roj.reflect.Sandbox;
import roj.text.CharList;
import roj.text.DateFormat;
import roj.text.ParseException;
import roj.text.TextUtil;
import roj.text.logging.LogContext;
import roj.text.logging.Logger;
import roj.ui.*;
import roj.util.*;
import roj.util.function.Flow;

import java.io.*;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.net.URI;
import java.security.*;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.util.*;
import java.util.concurrent.Future;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.ReentrantLock;
import java.util.zip.Deflater;
import java.util.zip.ZipInputStream;

import static roj.ci.BuildContext.*;
import static roj.ui.CommandNode.argument;
import static roj.ui.CommandNode.literal;

/**
 * MCMake，你的最后一个Minecraft构建工具
 * @author Roj234
 * @since 2021/6/18 10:51
 */
@ReplaceConstant
public final class MCMake {
	static final String VERSION = "${project_version}";

	public static final Logger log = Logger.getLogger("MCMake");
	public static final File
			BASE = new File("").getAbsoluteFile(),
			BIN_PATH = IOUtil.getJar(MCMake.class).getParentFile(),
			CONF_PATH = new File(BASE, "conf"),
			CACHE_PATH = new File(BASE, "cache"),
			OUTPUT_PATH = new File(BASE, "build");

	public static final Timer TIMER = Timer.getDefault();

	public static final Shell COMMANDS = new Shell("");
	static final ObjectMapper CONFIG = ObjectMapper.getInstance()
			.serializeCharsetToString()
			.registerAdapter(ArtifactVersion.class, MySerializer.class)
			.registerAdapter(File.class, MySerializer.class);
	private static Schema configSchema, envSchema;

	public static MapValue config;
	static IFileWatcher watcher;
	static final List<Plugin> REGISTERED_PLUGINS = new ArrayList<>();

	public static Project defaultProject;
	public static final HashMap<String, Project> projects = new HashMap<>();
	static final HashMap<String, Compiler.Factory> compilerTypes = new HashMap<>();
	static final HashMap<String, Workspace.Factory> workspaceTypes = new HashMap<>();
	public static final HashMap<String, Workspace> workspaces = new HashMap<>();

	private static boolean immediateExit;

	private static TimerTask rainbowTask;
	private static final KeyHandler cancelRainbowTask = (keyCode, isVirtualKey) -> {
		rainbowTask.cancel();
		Tty.setHandler(COMMANDS);
		COMMANDS.keyEnter(keyCode, isVirtualKey);
	};

	private static final ReentrantLock COMPILE_LOCK = new ReentrantLock();
	public static void _lock(boolean notifyWaiting) {if (!COMPILE_LOCK.tryLock()) {
		if (notifyWaiting) Tty.warning("其他项目正在编译，您的操作已排队");
		COMPILE_LOCK.lock();
	}}
	public static void _unlock() {
		if (COMPILE_LOCK.getHoldCount() == 1) {
			for (Project project : projects.values()) {
				project.compiling = null;
			}
			BuildContext.cleanup();
			log.debug("编译组结束, 释放临时状态.");
		}
		COMPILE_LOCK.unlock();
	}

	@SuppressWarnings({"fallthrough"})
	public static void main(String[] args) throws Exception {
		JVM.AccurateTimer.setEventDriven();

		var parser = new YamlParser();
		var value = parser.parse(IOUtil.getTextResourceIL("config.schema.yml"));
		configSchema = CONFIG.read(value, Schema.class);
		value = parser.parse(IOUtil.getTextResourceIL("env.schema.yml"));
		envSchema = CONFIG.read(value, Schema.class);

		if (Tty.IS_RICH) {
			Tty.write(Tty.Screen.clearScreen);
			Tty.write("\u001b]0;MCMake v"+VERSION+" [Roj234]\7");
			Tty.write(Tty.Cursor.to(0,0));
		}

		String slogan = "MCMake，您的最后一个模组开发工具！ "+VERSION+" | 2019-2025";
		System.out.println(slogan);

		System.setProperty("roj.compiler.symbolCache", CACHE_PATH.getAbsolutePath());

		if (!loadConfig()) System.exit(-1);
		var loadEnv = false;
		try {
			loadEnv();
			loadEnv = true;
			Runtime.getRuntime().addShutdownHook(new Thread(MCMake::saveProjectStates));
		} catch (Exception e) {
			log.fatal("环境配置env.yml解析失败! 请修复并输入reload", e);
			updatePrompt();
		}

		registerCommands();

		if (args.length != 0) {
			immediateExit = true;
			COMMANDS.executeSync(TextUtil.join(Arrays.asList(args), " "));
			return;
		}

		if (Tty.IS_RICH) {
			rainbowTask = TIMER.loop(() -> {
				CharList sb1 = new CharList().append("\u001b7\u001b[?25l\u001b["+1+";1H");
				Tty.TextEffect.rainbow(slogan, sb1).append("\u001b[?25h\u001b8");
				synchronized (System.out) {
					Tty.write(sb1);
				}
				sb1._free();
				if (((PeriodicTask) rainbowTask.task()).isExpired()) {
					cancelRainbowTask.keyEnter('\b', true);
				}
			}, 1000/30, 30 * 10);
		}

		if (loadEnv && projects.isEmpty()) {
			Tty.warning("""
							欢迎使用MCMake，您的最后一个模组开发工具！
							使用前请先阅读readme.md
							把您的时间花在更有意义的事情，而不是等待编译上
																—— Roj234
							""");
		}

		if (Tty.IS_RICH) {
			Tty.info("按F1查看快速帮助");
			System.out.println();
			Tty.pushHandler(cancelRainbowTask);
		} else {
			System.out.println("使用支持ANSI转义的终端以获得更好的体验");
			Tty.pushHandler(COMMANDS);
		}
	}

	private static void registerCommands() {
		var c = COMMANDS;

		var buildProject = argument("选项", Argument.stringFlags("full", "diagnostic", "profile", "full/inherit")).executes(ctx -> {
			var workspace = ctx.argument("工作空间", Workspace.class);

			Flow<Project> flow;
			if (workspace != null) {
				flow = Flow.of(MCMake.projects.values()).filter(p -> workspace == p.workspace);
			} else {
				Set<Project> projects = Helpers.cast(ctx.argument("项目名称", Set.class));
				flow = Flow.of(projects);
			}

			Set<String> options = Helpers.cast(ctx.argument("选项", Set.class));

			Profiler p = options.contains("profile") ? new Profiler("build").begin() : null;
			_lock(true);
			try {
				int exitcode = 0;

				Set<Project> projects = flow.filter(p1 -> p1.conf.type.canBuild()).toSet();
				for (Project project : new ArrayList<>(projects)) {
					projects.removeAll(project.getProjectDependencies());
				}
				for (Project project : projects) {
					exitcode |= build(options, project);
				}

				if (immediateExit) System.exit(exitcode);
			} finally {
				_unlock();
				if (p != null) {
					p.end();
					p.popup();
				}
			}
		});
		c.register(literal("build")
				.then(argument("项目名称", Argument.someOf(projects)).then(buildProject))
				.then(argument("工作空间", Argument.oneOf(workspaces)).then(buildProject)));

		var pr = literal("project")
			.then(literal("create").then(argument("项目名称", Argument.string()).then(argument("工作空间名称", Argument.oneOf(workspaces)).executes(ctx -> {
				var example = new Env.Project();
				example.name = ctx.argument("项目名称", String.class);
				example.workspace = ctx.argument("工作空间名称", Workspace.class).id();

				var project = new Project(example, true);
				project.init();
				projects.put(project.getName(), project);
				Workspace.registerModule(project, 0);
				saveEnv();
			}))))
			.then(literal("delete").then(argument("项目名称", Argument.oneOf(projects)).executes(ctx -> {
				var proj = ctx.argument("项目名称", Project.class);
				Workspace.registerModule(proj, 1);
				IOUtil.deleteRecursively(proj.cachePath);
				projects.remove(proj.getName());
				saveEnv();
				System.out.println("已从配置中删除，请手动删除projects目录下文件");
			})))
			.then(literal("setdefault").then(argument("项目名称", Argument.oneOf(projects)).executes(ctx -> {
				defaultProject = ctx.argument("项目名称", Project.class);
				saveEnv();
				updatePrompt();
			})))
			.then(literal("auto").then(argument("项目名称", Argument.oneOf(projects)).then(argument("自动编译开关", Argument.bool()).executes(ctx -> {
				var proj = ctx.argument("项目名称", Project.class);
				proj.setAutoCompile(ctx.argument("自动编译开关", Boolean.class));
				saveEnv();
			}))));
		importExportProject(pr);
		c.register(pr);

		var ws = literal("workspace")
			.then(literal("create").then(argument("工作空间类型", Argument.oneOf(workspaceTypes)).executes(ctx -> {
				var space = ctx.argument("工作空间类型", Workspace.Factory.class).build(config.getMap("工作区").getMap("x"));
				if (space != null) {
					String id = TUI.inputOpt(TUI.text("自定义工作空间'"+space.id+"'的名称，按Ctrl+C以取消"), Argument.string());
					if (id != null && !id.isBlank()) space.id = id;

					TUI.stepInfo(TUI.text("注册Library和配置"));
					space.postBuild();
					var ws1 = new Workspace(space);
					ws1.onAdd();
					workspaces.put(space.id, ws1);
					saveEnv();

					TUI.end(TUI.text("工作空间'"+space.id+"'构建成功！"));
				}
			})))
			.then(literal("delete").then(argument("工作空间名称", Argument.oneOf(workspaces)).executes(ctx -> {
				Workspace space = ctx.argument("工作空间名称", Workspace.class);
				char nn = TUI.key("YyNn", "输入Y确定删除'"+space.id()+"'及其所有文件");
				if (nn != 'Y' && nn != 'y') return;
				space.onRemove();
				workspaces.remove(space.id());
				saveEnv();
				System.out.println("工作空间'"+space.id()+"'已删除");
			})));
		importExportWorkspace(ws);
		c.register(ws);

		c.register(literal("reload").executes(ctx -> loadEnv()));

		c.register(literal("zipclean").then(argument("路径", Argument.file()).executes(ctx -> {
			var file = ctx.argument("路径", File.class);
			if (file.getName().endsWith(".7z")) {
				try (var qz = new SevenZFile(new FileSource(file))) {
					SevenZPacker qzfw = qz.append();

					ArrayList<SevenZEntry> emptyFiles = qzfw.getEmptyFiles();
					for (int i = emptyFiles.size() - 1; i >= 0; i--) {
						SevenZEntry emptyFile = emptyFiles.get(i);
						if (emptyFile.isDirectory() && emptyFile.getName().contains("/")) {
							qzfw.removeEmptyFile(i);
						}
					}

					qzfw.close();
				}
				return;
			}
			try (ZipEditor za = new ZipEditor(file)) {
				for (ZipEntry ze : za.entries()) {
					if (ze.getName().endsWith("/")) {
						za.put(ze.getName(), null);
                        if (ze.getSize() != 0) {
							log.warn("找到了有大小的文件夹{}", ze);
							za.put(ze.getName().substring(0, ze.getName().length()-1), DynByteBuf.wrap(za.get(ze)));
						}
                    }
				}

				za.save();
			}
		})));

		c.register(literal("statistic").executes(ctx -> {
			Statistic instance = Statistic.instance;
			long buildCountManual = 0, buildCountTotal = 0, buildCountFail = 0, buildTimes = 0;
			for (var entry : instance.buildTimes.selfEntrySet()) {
				buildTimes += entry.value;
			}
			for (var entry : instance.buildCounts.selfEntrySet()) {
				buildCountTotal += entry.value;
				if (entry.key.endsWith(":manual")) buildCountManual += entry.value;
			}
			for (var entry : instance.buildFailures.selfEntrySet()) {
				buildCountFail += entry.value;
			}
			System.out.println("自 "+DateFormat.toLocalDateTime(instance.since)+" 起,");
			System.out.println("你已使用MCMake进行了 "+buildCountTotal+" 次构建，其中 "+buildCountManual+" 次("+TextUtil.toFixed(100d*buildCountManual/buildCountTotal, 2)+"%) 为手动构建");
			System.out.println("这些构建共花费了 "+myTime(buildTimes)+", 成功率为 "+TextUtil.toFixed(100d*(buildCountTotal-buildCountFail)/buildCountTotal, 2)+"%");
			long timeWasted = 30000 * buildCountTotal;
			System.out.println("按ForgeGradle每次构建至少需要30秒计算, MCMake至少为你节约了 "+myTime(timeWasted-buildTimes));
		}));

		c.register(literal("downgrade").then(argument("version", Argument.number(6, JVM.VERSION)).then(argument("jarFile", Argument.file()).executes(ctx -> {
			var targetVersion = ClassNode.JavaVersion(ctx.argument("version", Integer.class));
			var targetJar = ctx.argument("jarFile", File.class);
			try (var za = new ZipEditor(targetJar)) {
				for (ZipEntry entry : za.entries()) {
					if (entry.getName().endsWith(".class")) {
						ByteList r = IOUtil.getSharedByteBuf().readStreamFully(za.getInputStream(entry));
						r.setInt(4, targetVersion);
						za.put(entry.getName(), r.copySlice());
					}
				}
				za.save();
			}
		}))));

		//c.register(literal("run").then(argument("artifact", )))

		runScript();

		c.sortCommands();
		c.setAutoComplete(config.getBool("自动补全"));
	}

	private static String myTime(long time) {
		long sec = time / 1000;
		long ms = time - sec * 1000;
		long min = sec / 60;
		sec = sec - min * 60;
		long hour = min / 60;
		min = min - hour * 60;
		long day = hour / 24;
		hour = hour - day * 24;
		return IOUtil.getSharedCharBuf().append(day).append("d ").padNumber(hour, 2).append(':').padNumber(min, 2).append(':').padNumber(sec, 2).append('.').padNumber(ms, 3).toString();
	}

	private static void updatePrompt() {COMMANDS.setPrompt("\u001b[33mMCMake"+(defaultProject == null ? "" : "\u001b[97m[\u001b[96m"+defaultProject.getName()+"\u001b[97m]")+"\u001b[33m > ");}

	//region 导入导出&其它指令
	private static void runScript() {
		COMMANDS.register(literal("runscript").comment("执行脚本").then(argument("脚本名称", Argument.fileIn(new File(CONF_PATH, "scripts"))).executes(ctx -> {
			File scriptFile = ctx.argument("脚本名称", File.class);

			var files = Collections.singletonList(LavaCompileUnit.create(scriptFile.getName(), IOUtil.readString(scriptFile)));

			var compiler = new LavaCompiler();
			new TestPlugin(compiler);

			compiler.features.add(roj.compiler.api.Compiler.EMIT_INNER_CLASS);
			compiler.features.add(roj.compiler.api.Compiler.EMIT_LINE_NUMBERS);
			compiler.features.add(roj.compiler.api.Compiler.OPTIONAL_SEMICOLON);
			compiler.features.add(roj.compiler.api.Compiler.OMISSION_NEW);
			compiler.features.add(roj.compiler.api.Compiler.SHARED_STRING_CONCAT);
			compiler.features.add(roj.compiler.api.Compiler.OMIT_CHECKED_EXCEPTION);
			compiler.addLibrary(Resolver.Libs.SELF);

			CompileContext.set(compiler.createContext());
			try {
				var result = compiler.compile(files);

				if (result != null) {
					var sandbox = new Sandbox("fmdScript", MCMake.class.getClassLoader());
					sandbox.restriction = null;
					for (var node : result) sandbox.add(node);

					MethodHandle handle = MethodHandles.lookup().findStatic(sandbox.loadClass(files.get(0).name().replace('/', '.')), "main", MethodType.methodType(void.class, String[].class));
					handle.invoke(new String[0]);
				}
			} catch (Throwable e) {
				e.printStackTrace();
			} finally {
				CompileContext.set(null);
			}
		})));
	}
	public static void importExportWorkspace(CommandNode node) {
		node.then(literal("import").then(argument("工作空间归档", Argument.file()).executes(ctx -> {
			File archiveFile = ctx.argument("工作空间归档", File.class);
			try (var archive = new SevenZFile(archiveFile);
				 var bar = new EasyProgressBar("导入工作空间", "B")) {
				archive.setMemoryLimitKb(26214400);

				InputStream in = archive.getInputStream("workspace.yml");
				if (in == null) {
					Tty.error("不是有效的归档文件：找不到workspace.yml");
					return;
				}

				var workspace = CONFIG.read(in, Env.Workspace.class, ConfigMaster.YAML);
				workspace.id = IOUtil.normalize(workspace.id);

				if (MCMake.workspaces.containsKey(workspace.id)) {
					Tty.warning("同名工作空间("+workspace.id+")已存在");
					return;
				}

				bar.setName("导入"+workspace.id);
				for (SevenZEntry entry : archive.entries()) {
					if (entry.getName().equals("workspace.yml")) continue;
					bar.addTotal(entry.getSize());
				}

				Map<String, File> filenameMapping = new HashMap<>();
				List<File> depend = workspace.depend;
				for (int i = 0; i < depend.size(); i++) {
					File file = depend.get(i);
					File result;

					var path = file.getName();
					result = new File(CACHE_PATH, "ws-"+workspace.id+"-cd"+i+path.substring(path.lastIndexOf('.')));

					filenameMapping.putIfAbsent(file.getName(), result);
					depend.set(i, result);
				}
				List<File> mappedDepend = workspace.mappedDepend;
				for (int i = 0; i < mappedDepend.size(); i++) {
					File file = mappedDepend.get(i);
					File result;

					var path = file.getName();
					result = new File(CACHE_PATH, "ws-"+workspace.id+"-md"+i+path.substring(path.lastIndexOf('.')));

					filenameMapping.putIfAbsent(file.getName(), result);
					mappedDepend.set(i, result);
				}
				List<File> unmappedDepend = workspace.unmappedDepend;
				for (int i = 0; i < unmappedDepend.size(); i++) {
					File file = unmappedDepend.get(i);
					File result;

					var path = file.getName();
					result = new File(CACHE_PATH, "ws-"+workspace.id+"-ud"+i+path.substring(path.lastIndexOf('.')));

					filenameMapping.putIfAbsent(file.getName(), result);
					unmappedDepend.set(i, result);
				}

				for (var itr = filenameMapping.entrySet().iterator(); itr.hasNext(); ) {
					Map.Entry<String, Object> generalizedEntry = Helpers.cast(itr.next());
					generalizedEntry.setValue(new ZipPacker(((File) generalizedEntry.getValue()), Deflater.BEST_COMPRESSION, 0));
				}
				Map<String, ZipPacker> libraryWriter = Helpers.cast(filenameMapping);

				if (workspace.mapping != null) {
					workspace.mapping = new File(CACHE_PATH, "ws-"+workspace.id+"-mapping.lzma");
				}

				TaskGroup monitor = TaskPool.cpu().newGroup();
				archive.parallelDecompress(monitor, (entry, in1) -> {
					if (entry.isDirectory() || entry.getName().equals("workspace.yml")) return;

					String pathname = IOUtil.normalize(entry.getName());
					int i = pathname.indexOf('/');
					if (i < 0) {
						var file = workspace.mapping;
						if (file != null) try (var out = new FileOutputStream(file)) {
							SevenZArchiver.copyStreamWithProgress(in1, out, bar);
						} catch (IOException e) {
							e.printStackTrace();
						}
					} else {
						ZipPacker zfw = libraryWriter.get(pathname.substring(0, i));
						pathname = pathname.substring(i+1);
						synchronized (zfw) {
							try {
								zfw.beginEntry(new ZipEntry(pathname));
								SevenZArchiver.copyStreamWithProgress(in1, zfw, bar);
							} catch (IOException e) {
								e.printStackTrace();
							}
						}
					}
				});
				monitor.await();
				for (ZipPacker zfw : libraryWriter.values()) zfw.close();

				if (!monitor.isCancelled()) {
					var ws = new Workspace(workspace);
					MCMake.workspaces.put(workspace.id, ws);
					ws.init();
					MCMake.saveEnv();

					bar.end("成功");
				}
			}
		}))).then(literal("export").then(argument("工作空间名称", Argument.oneOf(MCMake.workspaces)).executes(ctx -> {
			Workspace space = MCMake.CONFIG.reader(Workspace.class).copyOf(ctx.argument("工作空间名称", Workspace.class));

			try (var qzfw = new SevenZPacker("workspace-"+space.id()+".7z");
				 var bar = new EasyProgressBar("导出"+space.id(), "B")) {

				bar.setIndeterminate();

				var files = new ArrayList<>(space.getDepend());
				files.addAll(space.getMappedDepend());
				files.addAll(space.getUnmappedDepend());

				TaskGroup monitor = TaskPool.cpu().newGroup();

				for (File file : files) {
					monitor.executeUnsafe(() -> {
						try (var in = new ZipInputStream(new FileInputStream(file));
							 var out = qzfw.newParallelWriter()) {
							out.setCodec(new LZMA2(new LZMA2Options(9).setDictSize(16777216)));

							while (true) {
								java.util.zip.ZipEntry entry = in.getNextEntry();
								if (entry == null || entry.isDirectory()) break;

								out.beginEntry(SevenZEntry.of(file.getName()+"/"+entry));
								SevenZArchiver.copyStreamWithProgress(in, out, bar);
							}
						}
					});
				}

				monitor.await();

				qzfw.setCodec(Copy.INSTANCE);

				File mapping = space.mapping;
				if (mapping != null) {
					qzfw.beginEntry(SevenZEntry.of(mapping.getName()));
					try (var in = new FileInputStream(mapping)) {
						SevenZArchiver.copyStreamWithProgress(in, qzfw, bar);
					}
				}

				qzfw.setCodec(new LZMA2(new LZMA2Options(9).setDictSize(524288)));

				qzfw.beginEntry(SevenZEntry.of("workspace.yml"));
				CONFIG.write(ConfigMaster.YAML, space.conf, IOUtil.getSharedByteBuf()).writeToStream(qzfw);
				bar.end("成功");
			}
		})));
	}

	public static void importExportProject(CommandNode node) {
		node.then(literal("export").then(argument("项目名称", Argument.oneOf(projects)).executes(ctx -> {
			Project proj = ctx.argument("项目名称", Project.class);
			File projectRoot = proj.root;

			SevenZArchiver arc = new SevenZArchiver();
			arc.inputDirectories = Collections.singletonList(projectRoot);
			//arc.threads = 0;
			arc.updateMode = SevenZArchiver.UM_REPLACE;
			arc.pathFormat = SevenZArchiver.PF_RELATIVE;
			arc.storeModifiedTime = true;
			arc.storeSymbolicLinks = true;
			arc.storeHardLinks = true;
			arc.solidSize = Long.MAX_VALUE;
			arc.options.setPreset(9).setDictSize(4194304);
			arc.useFilter = true;
			arc.outputDirectory = BASE;
			arc.outputFilename = "mcmake-project-"+proj.getSafeName()+"-"+ DateFormat.format("Ymd", System.currentTimeMillis())+".7z";

			arc.prepare();
			arc.organize();
			String ymldata = CONFIG.write(ConfigMaster.YAML, proj.conf, IOUtil.getSharedCharBuf()).toString();
			arc.appendChunk(new SevenZCodec[]{Copy.INSTANCE}, Collections.singletonList(ymldata), Collections.singletonList(SevenZEntry.of("project.yml")));

			try (var bar = new EasyProgressBar("导出项目")) {
				arc.compress(TaskPool.cpu().newGroup(), bar);
			}
		})))
		.then(literal("import").then(argument("项目归档", Argument.file()).executes(ctx -> {
			File archiveFile = ctx.argument("项目归档", File.class);

			try (var archive = new SevenZFile(archiveFile);
				 var bar = new EasyProgressBar("导入项目", "B")) {
				InputStream in = archive.getInputStream("project.yml");
				if (in == null) {
					Tty.error("不是有效的归档文件：找不到project.yml");
					return;
				}

				Env.Project pojo = CONFIG.read(in, Env.Project.class, ConfigMaster.YAML);
				pojo.name = IOUtil.normalize(pojo.name);

				if (projects.containsKey(pojo.name)) {
					Tty.warning("同名项目("+pojo.name+")已存在");
					return;
				}

				Workspace workspace = workspaces.get(pojo.workspace);
				if (workspace == null) {
					Tty.warning("依赖的工作空间["+pojo.workspace+"]不存在");
					return;
				}

				var basePath = new File(workspace.getPath(), pojo.name);
				if (basePath.exists()) {
					Tty.warning("项目目录'"+basePath+"'非空，是否清空目录\nY: 清空并导入\nN: 不清空并导入\nCtrl+C: 取消操作");
					char selection = TUI.key("YyNn", "您的选择: ");
					if (selection == 0) return;
					if (selection == 'Y' || selection == 'y') IOUtil.deleteRecursively(basePath);
				}

				bar.setName("导入"+pojo.name);
				for (SevenZEntry entry : archive.entries()) {
					if (entry.getName().equals("project.yml")) continue;
					bar.addTotal(entry.getSize());
				}

				TaskGroup monitor = TaskPool.cpu().newGroup();
				archive.parallelDecompress(monitor, (entry, in1) -> {
					if (entry.isDirectory() || entry.getName().equals("project.yml")) return;
					var file = new File(basePath, IOUtil.normalize(entry.getName()));
					file.getParentFile().mkdirs();

					try (var out = new FileOutputStream(file)) {
						SevenZArchiver.copyStreamWithProgress(in1, out, bar);
					} catch (IOException e) {
						e.printStackTrace();
					}
				});
				monitor.await();
				if (!monitor.isCancelled()) {
					Project project = new Project(pojo, false);
					project.init();
					projects.put(project.getName(), project);
					Workspace.registerModule(project, 0);
					saveEnv();

					bar.end("成功");
				}
			}
		})));
	}
	//endregion
	//region 配置文件
	private static boolean loadConfig() {
		try {
			YamlParser parser = new YamlParser(TextParser.NO_DUPLICATE_KEY | TextParser.ORDERED_MAP | TextParser.LENIENT);

			config = parser.parse(new File(CONF_PATH, "config.yml")).asMap();
			configSchema.validate(config);

			String string = config.getString("日志配置文件", null);
			var logger = string == null ? parser.parse(IOUtil.getTextResourceIL("logger.yml")) : parser.parse(string);
			LogContext.loadFromConfig(logger.asMap());
		} catch (Exception e) {
			log.fatal("系统配置config.yml解析失败!", e);
			return false;
		}

		DownloadTask.timeout = HttpRequest.DEFAULT_TIMEOUT = config.getInt("连接超时");
		DownloadTask.defChunkStart = DownloadTask.defMinChunkSize = config.getInt("分块大小");
		HttpRequest.DEFAULT_PROXY = config.containsKey("网络代理") ? URI.create(config.getString("网络代理")) : null;

		if (watcher != null) watcher.removeAll();

		IFileWatcher w = null;
		try {
			w = new FileWatcher();
		} catch (IOException e) {
			log.error("无法启动文件监控", e);
		}
		if (w == null) w = new IFileWatcher();
		watcher = w;

		workspaceTypes.clear();
		for (var entry : config.getMap("工作区").entrySet()) {
			var name = entry.getKey();
			var value = entry.getValue().asMap();
			try {
				var instance = Workspace.factory(Class.forName(value.getString("type")));
				workspaceTypes.put(name, instance);
			} catch (ClassNotFoundException e) {
				log.error("无法初始化工作区{}", e, name);
			}
		}

		compilerTypes.clear();
		for (var entry : config.getMap("编译器").entrySet()) {
			var name = entry.getKey();
			var value = entry.getValue().asMap();
			try {
				var instance = new Compiler.Factory(value);
				compilerTypes.put(name, instance);
			} catch (ClassNotFoundException e) {
				log.error("无法初始化编译器{}", e, name);
			}
		}

		REGISTERED_PLUGINS.clear();
		for (var entry : config.getMap("插件").entrySet()) {
			try {
				Plugin plugin = (Plugin) Class.forName(entry.getKey()).newInstance();
				REGISTERED_PLUGINS.add(plugin);
				plugin.init(entry.getValue());
			} catch (Throwable e) {
				log.error("无法加载插件{}", e, entry.getKey());
			}
		}

		return true;
	}
	private static void loadEnv() throws IOException, ParseException {
		watcher.removeAll();

		BuildContext.closeAllDependencies();
		for (var project : projects.values()) project.close();

		File file = new File(CONF_PATH, "env.yml");
		if (!file.isFile()) {
			workspaces.clear();
			projects.clear();
			return;
		}

		ConfigValue envData = new YamlParser().parse(file);
		envSchema.validate(envData);

		var env = CONFIG.reader(Env.class).read(envData);
		var regenerate = !projects.isEmpty();

		saveProjectStates();

		workspaces.clear();
		projects.clear();

		for (var workspace : env.workspaces) {
			workspaces.put(workspace.id, new Workspace(workspace));
		}
		for (Workspace value : workspaces.values()) {
			value.init();
			if (regenerate) value.onAdd();
		}
		for (var config : env.projects) {
			try {
				var project = new Project(config, false);
				projects.put(project.getName(), project);
			} catch (IOException e) {
				log.error("无法加载项目{}", e, config.name);
			}
		}
		for (var name : env.auto_compile) {
			var project = projects.get(name);
			if (project != null) project.setAutoCompile(true);
		}
		for (var value : projects.values()) {
			value.init();
			if (value.getVariables().getOrDefault("fmd:generate_project", "true").equals("true"))
				Workspace.registerModule(value, regenerate ? 2 : 0);
		}
		defaultProject = projects.get(env.default_project);
		updatePrompt();
	}
	static void saveEnv() {
		var env = getEnvPojo();
		try {
			CONFIG.write(ConfigMaster.YAML, env, new File(CONF_PATH, "env.yml"));
		} catch (IOException e) {
			log.error("配置保存失败", e);
		}
	}
	private static Env getEnvPojo() {
		var env = new Env();
		env.auto_compile = new ArrayList<>();
		env.projects = new ArrayList<>();
		env.workspaces = Flow.of(workspaces.values()).map(x -> x.conf).toList();
		env.workspaces.sort((o1, o2) -> o1.id.compareTo(o2.id));
		for (Project value : projects.values()) {
			env.projects.add(value.conf);
			if (value.isAutoCompile()) {
				env.auto_compile.add(value.getName());
			}
		}
		env.projects.sort((o1, o2) -> o1.name.compareTo(o2.name));
		if (defaultProject != null) env.default_project = defaultProject.getName();
		return env;
	}

	private static void saveProjectStates() {
		for (Project p : projects.values()) {
			p.savePersistentState();
		}
	}

	private static class MySerializer {
		static String dataBase = CACHE_PATH.getAbsolutePath();

		public static String toString(ArtifactVersion v) {return v.toString();}
		public static ArtifactVersion fromString(Object v) {return new ArtifactVersion(String.valueOf(v));}

		public static String toString(File v) {
			if (v == null) return null;
			String path = v.getAbsolutePath();
			return path.startsWith(dataBase) ? path.substring(dataBase.length() + 1) : path;
		}
		public static File fromString(String v) {return v == null ? null : IOUtil.resolve(CACHE_PATH, v);}
	}
	//endregion

	public static int build(Set<String> args, Project project) throws Exception {
		_lock(!args.contains("auto"));

		boolean success;
		block:
		try {
			if (!project.getProjectDependencies().isEmpty()) {
				Profiler.startSection("depend");
				try (var bar = new ProgressBar()) {
					var copyArgs = new HashSet<>(args);
					if (copyArgs.contains("full/inherit")) copyArgs.add("full");
					else copyArgs.remove("full");
					copyArgs.add("silent");

					List<Project> allDependencies = project.getProjectDependencies();
					for (int i = 0; i < allDependencies.size(); i++) {
						Project depend = allDependencies.get(i);
						bar.setName("构建依赖["+depend.getName()+"]");
						bar.setProgress((double)i / allDependencies.size());

						if (depend.compiling != null) {
							if (depend.compiling.hasError) return 1;
							continue;
						}

						Profiler.startSection(depend.getName());

						File artifact = new File(OUTPUT_PATH, depend.getOutputFormat());

						log.debug("Build [{}]: config={}", depend.getName(), copyArgs);
						try {
							success = depend.conf.type.canBuild() ? build(copyArgs, depend, artifact) : compile(copyArgs, depend);
							if (!success) {
								depend.compiling.hasError = true;
								Statistic.afterProjectBuild(depend.getName(), System.currentTimeMillis() - depend.compiling.buildStartTime, args, false);
								bar.end("构建失败");
								break block;
							}
						} finally {
							if (depend.mappedWriter != null) depend.mappedWriter.end();
						}
						Profiler.endSection();
					}
				}
				Profiler.endSection();
			}

			if (args.contains("full/inherit")) args.add("full");

			File artifact = new File(OUTPUT_PATH, project.getOutputFormat());

			log.debug("Build [{}]: config={}", project.getName(), args);
			success = build(args, project, artifact);
			if (!success) {
				project.compiling.hasError = true;
				Statistic.afterProjectBuild(project.getName(), System.currentTimeMillis() - project.compiling.buildStartTime, args, false);
				if (args.contains("full")) watcher.remove(project);
			}
		} finally {
			if (project.mappedWriter != null) try {
				project.mappedWriter.end();
			} catch (Throwable e) {
				log.warn("mapperWriter", e);
			}
			_unlock();
		}

		return success ? 0 : 1;
	}
	// region Build
	private static boolean compile(Set<String> args, Project p) throws IOException {
		int increment = p.unmappedJar.length() > 22 && !args.contains("full") ? INC_UPDATE : INC_FULL;

		Profiler.startSection("environment");

		var context = new BuildContext(p, increment);
		context.resources.getChanged(); // MODULE被编译肯定有谁依赖它

		context.openDependencies();

		Profiler.endStartSection("compile");
		if (!compile(args, p, context)) {
			Profiler.endSection();
			return false;
		}

		boolean wasUpdated = !context.sources.isEmpty();

		Profiler.endStartSection("script");
		if (wasUpdated) Statistic.afterProjectBuild(p.getName(), System.currentTimeMillis() - context.buildStartTime, args, true);
		if (wasUpdated && !p.unmappedJar.setLastModified(context.buildStartTime)) Tty.warning("设置时间戳失败!");
		if (wasUpdated || !args.contains("silent")) Tty.success("构建成功["+p.getName()+"]! "+(System.currentTimeMillis()-context.buildStartTime)+"ms");
		context.buildSuccess();
		p.buildSuccess(increment > 0);

		Profiler.endSection();
		return true;
	}
	private static boolean build(Set<String> args, Project p, File artifact) throws IOException {
		boolean needCompile = p.conf.type.needCompile();
		int increment = (p.unmappedJar.length() <= 22 && needCompile) || args.contains("full") ? INC_FULL
				: artifact.isFile() ? INC_UPDATE : INC_REBUILD;

		Profiler.startSection("environment");

		var context = new BuildContext(p, increment);
		if (!needCompile) context.lastBuildTime = artifact.lastModified();

		context.openDependencies();

		Profiler.endStartSection("lockOutput");

		artifact.getParentFile().mkdirs();
		ZipOutput mappedWriter = lockOutput(p, artifact);
		try {
			mappedWriter.begin(context.incrementLevel > INC_REBUILD);
		} catch (Exception e) {
			context.incrementLevel = INC_REBUILD;
			log.error("工件打开失败, 正在尝试重新生成", e);
			mappedWriter.begin(false);
		}
		mappedWriter.setComment("MCMake "+VERSION+" by Roj234\r\nhttps://www.github.com/roj234/rojlib");

		Future<Integer> resources = TaskPool.cpu().submit(p.getAsyncResourceWriter(context));

		List<Context> changed;
		int resourceUpdated;

		Profiler.endStartSection("compile");
		try {
			if (!compile(args, p, context)) return false;

			changed = context.getChangedClasses();

			Profiler.endStartSection("getDependencyClasses");
			for (var dependency : p.getBundledDependencies())
				dependency.getClasses(context);

			Profiler.endStartSection("afterCompile");
			int oldSize = changed.size();
			changed = context.afterCompile();
			log.debug("afterCompile hook({}): changed {} => {}", context.incrementLevel, oldSize, changed.size());

			Profiler.endStartSection("writeResource");
			try {
				resourceUpdated = resources.get();
			} catch (Exception e) {
				log.error("资源更新失败["+p.getName()+"]", e);
				return false;
			}
		} finally {
			Profiler.endStartSection("writeResource [FAILURE]");
			resources.cancel(false);
			try {
				resources.get();
			} catch (Exception ignored) {}
			Profiler.endSection();
		}

		Profiler.endStartSection("writeClasses");
		Context ctx = Helpers.maybeNull();
		try {
			for (int i = 0; i < changed.size(); i++) {
				ctx = changed.get(i);
				mappedWriter.set(ctx.getFileName(), ctx::getCompressedShared, context.buildStartTime);
			}
		} catch (Throwable e) {
			log.error("代码更新失败["+p.getName()+":"+ctx.getFileName()+"]", e);
			return false;
		}

		if (context.incrementLevel > INC_REBUILD) {
			ZipEditor writer = mappedWriter.getArchive();
			for (String className : context.getRemovedClasses()) {
				boolean replaced = writer.prepareUpdate(className).data != null;
				log.trace(replaced ? "Replaced (in artifact) {}" : "Remove (in artifact) {}", className);
			}
		}

		int extraWritten = context.writeGenerated(mappedWriter);

		mappedWriter.end();

		boolean wasUpdated = context.classesHaveChanged() | (extraWritten | resourceUpdated) != 0;
		if (wasUpdated && p.variables.get("fmd:signature:keystore") != null) {
			Profiler.endStartSection("signature");
			try {
				signatureJar(p);
				log.trace("签名成功");
			} catch (Exception e) {
				log.error("签名失败", e);
				return false;
			}
		}

		Profiler.endStartSection("script");
		if (wasUpdated) Statistic.afterProjectBuild(p.getName(), System.currentTimeMillis() - context.buildStartTime, args, true);
		if (wasUpdated && !(needCompile ? p.unmappedJar : artifact).setLastModified(context.buildStartTime)) Tty.warning("设置时间戳失败!");
		if (wasUpdated || !args.contains("silent")) Tty.success((wasUpdated?"构建成功":"无变更")+"["+p.getName()+"]! "+(System.currentTimeMillis()-context.buildStartTime)+"ms");
		context.buildSuccess();
		p.buildSuccess(context.incrementLevel > 0);

		Profiler.endSection();
		return true;
	}
	private static ZipOutput lockOutput(Project p, File jarFile) {
		int amount = 30 * 20;
		while (jarFile.isFile() && !IOUtil.isWritable(jarFile)) {
			if ((amount % 100) == 0) Tty.warning("输出jar已被其它程序锁定, 等待解锁……");
			LockSupport.parkNanos(50_000_000L);
			if (--amount == 0) throw new IllegalStateException("输出被锁定");
		}

		var zo = p.mappedWriter;
		if (zo == null || !zo.file.getAbsolutePath().equals(jarFile.getAbsolutePath())) {
			IOUtil.closeSilently(zo);

			p.mappedWriter = zo = new ZipOutput(jarFile);
		}
		return zo;
	}
	private static String getClassPath(Project p, boolean increment) {
		var classpath = new CharList(200);

		var prefix = BASE.getAbsolutePath().length()+1;

		for (File file : p.workspace.getDepend()) {
			classpath.append(file.getAbsolutePath().substring(prefix)).append(File.pathSeparatorChar);
		}
		for (File file : p.workspace.getMappedDepend()) {
			classpath.append(file.getAbsolutePath().substring(prefix)).append(File.pathSeparatorChar);
		}
		IOUtil.listPaths(new File(BASE, "lib"),  (pathname, attr) -> {
			String ext = IOUtil.getExtension(pathname);
			if ((ext.equals("zip") || ext.equals("jar")) && attr.size() != 0) {
				classpath.append(pathname.substring(prefix)).append(File.pathSeparatorChar);
			}
		});

		var dependencies = p.getCompileDependencies();
		for (int i = 0; i < dependencies.size(); i++) {
			dependencies.get(i).getClassPath(classpath, BASE);
		}

		if (increment) classpath.append(p.unmappedJar.getAbsolutePath().substring(prefix));
		else if (classpath.length() > 0) classpath.setLength(classpath.length()-1);

		return classpath.toStringAndFree();
	}
	private static boolean compile(Set<String> args, Project p, BuildContext context) throws IOException {
		if (!p.conf.type.needCompile()) {
			context.setChangedClasses(new ArrayList<>());
			return true;
		}

		int increment = context.incrementLevel;

		Profiler.startSection("listSources");

		List<File> changed = context.sources.getChanged();
		Set<String> removed = context.sources.getRemoved();

		if ((changed.size()|removed.size()) > 0)
			log.debug("Source: {} changed, {} removed.", changed.size(), removed.size());

		if (changed.isEmpty() && removed.isEmpty() && increment != INC_LOAD && increment != INC_REBUILD) {
			context.setChangedClasses(new ArrayList<>());
			Profiler.endSection();
			return true;
		}

		List<ClassResource> outputs = new ArrayList<>(), resources = new ArrayList<>();

		if (!changed.isEmpty()) {
			Profiler.endStartSection("parameters");

			String classPath = getClassPath(p, increment > 0);

			ArrayList<String> options = p.conf.compiler_options_overwrite ? new ArrayList<>() : p.compiler.factory().getDefaultOptions();
			options.addAll(p.conf.compiler_options);
			options.addAll("-cp", classPath, "-encoding", p.charset.name());
			p.compiler.modifyOptions(options, p);

			log.debug("ModifyArg pre: increment={}, argument={}", increment, options);
			Profiler.endStartSection("beforeCompile");
			increment = context.beforeCompile(options, changed);
			log.debug("ModifyArg post: increment={}, argument={}", increment, options);

			Profiler.endStartSection("compile");

			// 编译失败
			if (!p.compiler.compile(options, changed, outputs, resources, args.contains("diagnostic"))) {
				Profiler.endSection();
				return false;
			}

			if (!resources.isEmpty()) {
				String errorMessage = "使用注解处理器生成资源。详见: https://github.com/roj234/RojLib/blob/master/toolchain/docs/WARNINGS.md#APT_GENERATE_RESOURCE";
				if (p.conf.type != Env.Type.PROJECT) throw new UnsupportedOperationException("错误：不支持"+errorMessage);
				log.warn("警告: 不建议"+errorMessage);

				for (ClassResource resource : resources) {
					context.addFile(resource.getFileName(), resource.getClassBytes());
				}
			}

			log.debug("Compile successful with {} outputs", outputs.size());
		}

		Profiler.endStartSection("outputJar");

		List<Context> compilerOutput = Helpers.cast(outputs);
		context.setChangedClasses(compilerOutput);

		ZipOutput unmappedWriter = p.unmappedWriter;
		Profiler.startSection("update");
		try {
			unmappedWriter.begin(increment > 0);

			var needUpdate = new HashSet<String>();

			// 更新
			for (int i = 0; i < outputs.size(); i++) {
				var out = outputs.get(i);
				unmappedWriter.set(out.getFileName(), out.getClassBytes());
				Context ctx = new Context(out.getFileName(), out.getClassBytes().slice());
				compilerOutput.set(i, ctx);

				ClassNode data = ctx.getData();
				p.dependencyGraph.add(data);
				p.annotationRepo.add(data);

				Object originalClass = context.structureDiffHandles.remove(data.name());
				if (p.structureRepo.update(originalClass, data)) {
					needUpdate.add(AnnotationRepo.normalizeName(data.name()));
				}
			}

			for (var className : context.structureDiffHandles.keySet()) {
				needUpdate.add(AnnotationRepo.normalizeName(className));
				p.structureRepo.remove(className);
				log.trace("Remove structure for {}", className);
			}

			for (var itr = context.sources.structureChanged.iterator(); itr.hasNext(); ) {
				String className = itr.next();
				if (!needUpdate.contains(className)) {
					log.trace("Structure of {} did not change, stop propagating.", className);
					itr.remove();
				}
			}

			// 删除
			if (increment > 0) {
				Profiler.endStartSection("remove");

				ZipEditor za = unmappedWriter.getArchive();
				for (ZipEntry entry : za.entries()) {
					String className = entry.getName();
					if (className.endsWith(".class")) {
						int subClass = className.indexOf('$');
						className = className.substring(0, subClass < 0 ? className.length()-6 : subClass);

						if (removed.contains(className) || needUpdate.contains(className)) {
							// createMod: 当且仅当相同名称未调用set时才删除
							boolean fileReallyRemoved = za.prepareUpdate(entry.getName()).data == null;
							if (fileReallyRemoved) {
								log.trace("classRemoved('{}')", entry.getName());
								context.classRemoved(entry.getName());
							}
						}
					}
				}
			}

			// mappedJar不存在时，需要复制所有class
			if (increment == INC_REBUILD || increment == INC_LOAD) {
				Profiler.endStartSection("classEnvConstruct");

				HashSet<String> changedFiles = new HashSet<>(compilerOutput.size());
				for (int i = 0; i < compilerOutput.size(); i++) changedFiles.add(compilerOutput.get(i).getFileName());

				int count = 0;
				ZipEditor za = unmappedWriter.getArchive();
				for (ZipEntry ze : za.entries()) {
					if (!changedFiles.contains(ze.getName())) {
						count++;
						context.addClass(new Context(ze.getName(), za.get(ze)), null, false);
					}
				}

				log.debug("Loaded {} classes from build cache.", count);
			}
		} catch (Throwable e) {
			Tty.warning("增量更新失败["+p.getName()+"]", e);

			unmappedWriter.end();
			Profiler.endSection();
			Profiler.endSection();

			p.unmappedJar.setLastModified(0);
			return false;
		}

		unmappedWriter.end();
		Profiler.endSection();
		Profiler.endSection();
		return true;
	}

	private static void signatureJar(Project p) throws IOException, GeneralSecurityException {
		var keystore = new File(p.variables.get("fmd:signature:keystore"));
		var private_key_pass = p.variables.get("fmd:signature:keystore_pass").toCharArray();
		var key_alias = p.variables.get("fmd:signature:key_alias");

		var result = p.signatureCache;

		if (result == null) result = readKeys(keystore, private_key_pass, key_alias);
		var options = result.options;
		if (options.isEmpty()) {
			options.put("jarSigner:creator", "MCMake/"+VERSION);
			options.put("jarSigner:signatureFileName", p.variables.getOrDefault("fmd:signature:certificate_name", IOUtil.getBaseName(keystore.getName())));
			options.put("jarSigner:manifestHashAlgorithm", p.variables.getOrDefault("fmd:signature:manifest_hash_algorithm", "SHA-256"));
			options.put("jarSigner:signatureHashAlgorithm", p.variables.getOrDefault("fmd:signature:signature_hash_algorithm", "SHA-256"));
			options.put("jarSigner:cacheHash", "true");
		}

		try (var zf = p.mappedWriter.getArchive()) {
			JarVerifier.signJar(zf, result.certificate_chain, result.private_key, result.options);
			zf.save();
		}
	}

	@NotNull
	private static SignatureCache readKeys(File keystore, char[] keyPass, String keyAlias) throws KeyStoreException, IOException, NoSuchAlgorithmException, CertificateException, UnrecoverableEntryException {
		List<Certificate> certificate_chain;
		PrivateKey private_key;
		var ks = KeyStore.getInstance("PKCS12");
		try (var in = new FileInputStream(keystore)) {
			ks.load(in, keyPass);
			KeyStore.PrivateKeyEntry entry = (KeyStore.PrivateKeyEntry) ks.getEntry(keyAlias, new KeyStore.PasswordProtection(keyPass));
			certificate_chain = Arrays.asList(entry.getCertificateChain());
			private_key = entry.getPrivateKey();
		}
        return new SignatureCache(certificate_chain, private_key);
	}

    public static final class SignatureCache {
		final List<Certificate> certificate_chain;
		final PrivateKey private_key;
		Map<String, String> options = new HashMap<>();

		public SignatureCache(List<Certificate> certificate_chain, PrivateKey private_key) {
            this.certificate_chain = certificate_chain;
            this.private_key = private_key;
        }
    }
	// endregion
}