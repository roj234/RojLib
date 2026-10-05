package roj.plugin;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.Nullable;
import roj.archive.zip.ZipFile;
import roj.collect.ArrayList;
import roj.collect.TrieTreeSet;
import roj.collect.XashMap;
import roj.config.ConfigMaster;
import roj.config.mapper.ObjectMapper;
import roj.config.node.MapValue;
import roj.io.CorruptedInputException;
import roj.io.IOUtil;
import roj.io.source.FileSource;
import roj.text.logging.Logger;
import roj.util.ArtifactVersion;
import roj.util.FastFailException;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.EnumMap;
import java.util.regex.Pattern;

/**
 * @author Roj234
 * @since 2023/12/25 16:08
 */
public class PluginManager {
	private static final XashMap.Template<String, PluginDescriptor> PM_TEMPLATE = XashMap.forType(String.class, PluginDescriptor.class).key("id").build();
	static final Logger LOGGER = Logger.getLogger();

	final PluginDescriptor systemPlugin = new PluginDescriptor();
	final XashMap<String, PluginDescriptor> plugins = PM_TEMPLATE.create();
	final EnumMap<PluginDescriptor.Role, PluginDescriptor> rolePlugins = new EnumMap<>(PluginDescriptor.Role.class);

	boolean stopping;

	private final ClassLoader env = getClass().getClassLoader();
	private final File pluginFolder;

	public PluginManager(File pluginFolder) { this.pluginFolder = pluginFolder; }
	public File getPluginFolder() {return pluginFolder;}

	protected boolean isCriticalPlugin(PluginDescriptor pd) {return pd.fileName == null;}

	protected final void findPlugins() {
		File[] plugins = pluginFolder.listFiles();
		if (plugins == null) return;
		for (File file : plugins) {
			String ext = IOUtil.getExtension(file.getName());
			if (ext.equals("jar") || ext.equals("zip")) findPlugin(file);
		}
	}
	protected final void loadPlugins() {
		for (var pd : plugins) {
			for (String s : pd.loadBefore) {
				var dep = plugins.get(s);
				if (dep != null) dep.loadAfter.add(pd.id);
			}
		}

		for (PluginDescriptor pd : plugins) {
			if (!pd.library) {
				try {
					loadPlugin(pd);
				} catch (Throwable e) {
					LOGGER.error("插件{}加载失败", e, pd);
				}
			}
		}

		for (var itr = plugins.iterator(); itr.hasNext(); ) {
			var pd = itr.next();
			if (pd.state < LOADED) {
				itr.remove();
				if (pd.library && pd.state == UNLOAD)
					LOGGER.debug("插件{}已识别,但是由于没有插件依赖它,未加载", pd);
				continue;
			}

			try {
				enablePlugin(pd);
			} catch (Throwable e) {
				LOGGER.error("插件{}启用失败", e, pd);
				itr.remove();
				unloadPluginTrusted(pd);
			}
		}
	}

	public static final int UNLOAD = 0, LOADING = 1, ERRORED = 2, LOADED = 3, ENABLED = 4, DISABLED = 5;
	private void loadPlugin(PluginDescriptor pd) {
		if (stopping) throw new FastFailException("系统正在关闭");

		switch (pd.state) {
			default: return;
			case ERRORED: throw new IllegalStateException("插件加载失败");
			case LOADING: throw new IllegalStateException("前置循环引用");
			case UNLOAD: pd.state = LOADING;
				for (String id : pd.loadBefore) {
					var dep = plugins.get(id);
					if (dep != null && dep.state >= LOADED) throw new IllegalStateException("loadBefore["+id+"]已加载");
				}
				for (String s : pd.depend) {
					var dep = plugins.get(s);
					if (dep == null) throw new IllegalStateException("缺少前置: "+s);
					try {
						loadPlugin(dep);
					} catch (Throwable e) {
						throw new IllegalStateException("前置 "+dep.id+" 加载失败",e);
					}
				}
				for (String s : pd.loadAfter) {
					var dep = plugins.get(s);
					if (dep != null) try {
						loadPlugin(dep);
					} catch (Throwable e) {
						throw new IllegalStateException("可选前置 "+dep.id+" 加载失败",e);
					}
				}
		}

		LOGGER.debug("正在加载插件 {}", pd);
		Class<?> klass;
		try {
			if (pd.source != null) {
				var accessible = new PluginDescriptor[pd.depend.size()];
				for (int i = 0; i < pd.depend.size(); i++) accessible[i] = getPlugin(pd.depend.get(i));

				var pcl = new PluginClassLoader(env, pd, accessible);
				pd.classLoader = pcl;

				if (pd.mainClass.isEmpty()) {
					pd.state = ENABLED;
					return;
				}

				klass = pcl.loadClass(pd.mainClass);
			} else {
				klass = PluginManager.class.getClassLoader().loadClass(pd.mainClass);
			}
		} catch (ClassNotFoundException|NoClassDefFoundError|IOException e) {
			unloadPluginTrusted(pd);
			pd.state = ERRORED;
			throw new FastFailException("无法初始化插件主类"+pd.mainClass, e);
		}

		try {
			var fn = ObjectMapper.containerFactory(klass);
			if (fn == null) {
				throw new FastFailException("在插件主类"+pd.mainClass+"中找不到无参构造器");
			} else {
				pd.instance = (Plugin) fn.apply(0);
				File dataFolder;
				if (pd.fileName.equals("/builtin_inherit")) {
					pd.instance.config = Jocker.CONFIG;
					dataFolder = new File(pluginFolder, "Core");
				} else {
					dataFolder = new File(pluginFolder, pd.id);
				}
				pd.instance.init(this, dataFolder, pd);
				pd.instance.onLoad();

				pd.state = LOADED;
				if (pd.role != null) rolePlugins.putIfAbsent(pd.role, pd);
			}
		} catch (Exception e) {
			unloadPluginTrusted(pd);
			pd.state = ERRORED;
			throw e;
		}
	}

	protected final void findAndLoadPlugin(File plugin) throws Exception {
		var pd = findPlugin(plugin);
		if (pd != null) loadAndEnablePlugin(pd);
	}
	protected final void loadAndEnablePlugin(PluginDescriptor pd) throws Exception {
		plugins.add(pd);
		loadPlugin(pd);
		enablePlugin(pd);
	}

	private PluginDescriptor findPlugin(File plugin) {
		try {
			var pd = getMetadata(plugin);
			if (pd == null) {
				LOGGER.warn("{} 不是插件, 忽略", plugin);
			} else {
				var prev = plugins.get(pd.id);
				if (prev != null) {
					LOGGER.warn("插件 {} 已加载另外的版本 {}", pd, prev.version);
					if (prev.version.compareTo(pd.version) >= 0) {
						return prev;
					} else {
						unloadPlugin(prev);
					}
				}

				plugins.add(pd);
				return pd;
			}
		} catch (Exception e) {
			LOGGER.error("加载插件 {} 出错",e,plugin);
		}

		return null;
	}
	private static final Pattern PLUGIN_ID = Pattern.compile("^[a-zA-Z_][a-zA-Z0-9\\-_.]*$");
	private PluginDescriptor getMetadata(File plugin) throws IOException {
		try (ZipFile za = new ZipFile(plugin)) {
			InputStream in = za.getInputStream("plugin.yml");
			if (in == null) return null;

			MapValue config = ConfigMaster.YAML.parse(in).asMap();

			var pd = new PluginDescriptor();
			pd.fileName = plugin.getName();
			pd.source = new FileSource(plugin, false);
			pd.id = config.getString("id");
			pd.moduleId = config.getString("moduleId", pd.id);
			if (!PLUGIN_ID.matcher(pd.id).matches()) throw new FastFailException("插件id不合法");
			pd.version = new ArtifactVersion(config.getString("version", "1"));
			pd.charset = Charset.forName(config.getString("charset", "UTF-8"));
			pd.mainClass = config.getString("mainClass");
			if (pd.mainClass.isEmpty()) pd.library = true;
			pd.role = config.containsKey("role") ? PluginDescriptor.Role.valueOf(config.getString("role")) : null;

			pd.depend = config.getList("depend").toStringList();
			pd.loadBefore = config.getList("loadBefore").toStringList();
			pd.loadAfter = config.getList("loadAfter").toStringList();
			pd.javaModuleDepend = config.getList("moduleDepend").toStringList();

			ArrayList<String> path = config.getList("extraPath").toStringList();
			pd.extraPath = new TrieTreeSet();
			pd.extraPath.add("plugins"+File.separatorChar+plugin.getName());
			pd.extraPath.add("plugins"+File.separatorChar+pd.id+File.separatorChar);
			for (String s : path) pd.extraPath.add(new File(s).getAbsolutePath());

			pd.reflectiveClass = new TrieTreeSet(config.getList("reflectivePackage").toStringList());
			pd.dynamicLoadClass = config.getBool("dynamicLoadClass");
			pd.loadNative = config.getBool("loadNative");

			pd.desc = config.getString("desc");
			pd.authors = config.getList("authors").toStringList();
			pd.website = config.getString("website");
			return pd;
		} catch (Exception e) {
			throw new CorruptedInputException("无效的 plugin.yml", e);
		}
	}

	@Nullable
	@Contract(pure = true)
	public PluginDescriptor getPlugin(String id) { return plugins.get(id); }
	@Nullable
	@Contract(pure = true)
	public Plugin getPluginInstance(String id) {
		var pd = plugins.get(id);
		return pd == null ? null : pd.instance;
	}
	@Nullable
	@Contract(pure = true)
	public Plugin getPluginInstance(PluginDescriptor.Role role) {
		var pd = rolePlugins.get(role);
		if (pd != null) {
			if (pd.instance == null)
				throw new NullPointerException("插件"+pd+"状态异常");
			return pd.instance;
		}
		return null;
	}

	public void enablePlugin(PluginDescriptor pd) throws Exception {
		if (stopping) throw new FastFailException("系统正在关闭");

		for (String s : pd.depend) {
			var dep = plugins.get(s);
			try {
				enablePlugin(dep);
			} catch (Throwable e) {
				throw new IllegalStateException("前置 "+dep.id+" 启用失败",e);
			}
		}
		for (String s : pd.loadAfter) {
			var dep = plugins.get(s);
			if (dep != null) try {
				enablePlugin(dep);
			} catch (Throwable e) {
				throw new IllegalStateException("可选前置 "+dep.id+" 启用失败",e);
			}
		}

		synchronized (pd.stateLock) {
			if (pd.state < LOADED) throw new IllegalStateException("无法启用未加载的插件 "+pd);
			if (pd.state == ENABLED) return;

			LOGGER.debug("正在启用插件 {}", pd);
			pd.state = ENABLED;
			try {
				pd.instance.onEnable();
			} catch (Throwable e) {
				unloadPluginTrusted(pd);
				pd.state = ERRORED;
				throw e;
			}
		}
	}
	public void disablePlugin(PluginDescriptor pd) {
		synchronized (pd.stateLock) {
			if (pd.state == UNLOAD) return;
			Plugin instance = pd.instance;
			if (pd.state != DISABLED && instance != null) {
				try {
					instance.onDisable();
				} catch (Throwable e) {
					LOGGER.error("无法禁用插件 {}",e,pd);
				} finally {
					pd.state = DISABLED;
					instance.postDisable();
				}
			}
		}
	}

	protected final void unloadPlugins() {
		for (PluginDescriptor pd : plugins)
			unloadPluginTrusted(pd);
		System.gc();
	}
	public void unloadPlugin(PluginDescriptor pd) {
		if (isCriticalPlugin(pd)) throw new IllegalArgumentException("不能禁用关键插件"+pd);
		synchronized (plugins) {if (!plugins.remove(pd)) return;}
		unloadPluginTrusted(pd);
	}
	private void unloadPluginTrusted(PluginDescriptor pd) {
		synchronized (pd.stateLock) {
			if (pd.role != null) rolePlugins.remove(pd.role, pd);
			if (pd.state == UNLOAD) return;
			LOGGER.debug("正在卸载插件 {}", pd);

			disablePlugin(pd);
			pd.instance = null;
			if (pd.classLoader != null) {
				try {
					pd.classLoader.close();
				}  catch (Throwable e) {
					LOGGER.error("卸载插件 {} 出错", e, pd);
				}
				pd.classLoader = null;
			}

			pd.state = UNLOAD;
		}
	}

	public PluginDescriptor getOwner(Class<?> type) {return type.getClassLoader() instanceof PluginClassLoader pcl ? pcl.desc : systemPlugin;}
}