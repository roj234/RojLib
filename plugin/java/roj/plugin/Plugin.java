package roj.plugin;

import org.jetbrains.annotations.ApiStatus;
import roj.collect.HashSet;
import roj.collect.Hasher;
import roj.concurrent.Timer;
import roj.config.TextParser;
import roj.config.YamlParser;
import roj.config.node.MapValue;
import roj.http.server.Router;
import roj.http.server.auto.OKRouter;
import roj.io.IOUtil;
import roj.text.ParseException;
import roj.text.logging.Logger;
import roj.ui.CommandNode;
import roj.util.TypedKey;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.Set;

/**
 * @author Roj234
 * @since 2023/12/25 16:00
 */
public abstract class Plugin {
	private Logger logger;

	private PluginManager pluginManager;
	private PluginDescriptor desc;

	protected MapValue config;
	private File dataFolder, configFile;

	final void init(PluginManager pm, File dataFolder, PluginDescriptor pd) {
		this.pluginManager = pm;
		this.desc = pd;
		this.dataFolder = dataFolder;
		this.configFile = new File(dataFolder, "config.yml");
		this.logger = Logger.getLogger(pd.id);
	}

	public final File getDataFolder() {return dataFolder;}
	public final PluginManager getPluginManager() {return pluginManager;}
	public final PluginDescriptor getDescription() {return desc;}
	public Logger getLogger() {return logger;}

	@ApiStatus.OverrideOnly public <T> T ipc(TypedKey<T> key, Object parameter) {throw new UnsupportedOperationException("unknown ipc id "+key.name);}

	protected final MapValue getConfig() {
		if (config == null) reloadConfig();
		return config;
	}
	protected void reloadConfig() {
		try {
			var parser = new YamlParser(TextParser.LENIENT);
			saveDefaultConfig();
			config = parser.parse(configFile).asMap();

			var defaults = getResource("config.yml");
			if (defaults != null) config.merge(parser.parse(defaults).asMap(), true, false);
		} catch (ParseException|IOException e) {
			throw new IllegalArgumentException("无法读取配置文件"+configFile.getName(),e);
		}
	}
	protected final void saveDefaultConfig() {
		if (!configFile.exists()) saveResource("config.yml", false);
	}

	protected final void saveResource(String path, boolean replace) {
		if (path == null || path.equals("")) throw new IllegalArgumentException("资源的路径不能为空");

		path = path.replace('\\', '/');

		InputStream in = getResource(path);
		if (in == null) {
			logger.error("无法找到资源文件{}", path);
			return;
		}

		File outFile = new File(dataFolder, path);
		File outDir = outFile.getParentFile();
		if (!outDir.exists()) outDir.mkdirs();

		if (!outFile.exists() || replace) {
			try (FileOutputStream fos = new FileOutputStream(outFile)) {
				IOUtil.copyStream(in, fos);
			} catch (IOException e) {
				logger.error("无法保存资源文件{}到{}",e,path,outFile);
			}
		}
	}
	public final InputStream getResource(String path) {
		if (path == null) throw new IllegalArgumentException("Filename cannot be null");
		return desc.classLoader == null ? null : desc.classLoader.getResourceAsStream(path);
	}

	private Set<String> pPaths = Collections.emptySet();
	protected final void registerRoute(String path, Router router) {router(path).addPrefixDelegation(path, router);}
	protected final void registerRoute(String path, Router router, String... interceptors) {router(path).addPrefixDelegation(path, router, interceptors);}
	private OKRouter router(String path) {
		synchronized (desc.stateLock) {
			if (pPaths.isEmpty()) pPaths = new HashSet<>(4);
			if (pPaths.add(path)) return Jocker.initHttp();
		}
		throw new IllegalStateException("路径"+path+"已注册");
	}
	protected final void unregisterRoute(String path) {
		synchronized (desc.stateLock) {
			if (pPaths.remove(path)) Jocker.initHttp().removePrefixDelegation(path);
		}
	}
	private Set<String> pIntecs = Collections.emptySet();
	protected final void registerInterceptor(String name, OKRouter.Dispatcher interceptor) {
		synchronized (desc.stateLock) {
			if (pIntecs.isEmpty()) pIntecs = new HashSet<>(4);
			if (pIntecs.add(name)) Jocker.initHttp().setInterceptor(name, interceptor);
		}
	}
	protected final void unregisterInterceptor(String name) {
		synchronized (desc.stateLock) {
			if (pIntecs.remove(name)) Jocker.initHttp().removeInterceptor(name);
		}
	}

	private Set<CommandNode> pCmds = Collections.emptySet();
	protected final void registerCommand(CommandNode node) {
		synchronized (desc.stateLock) {
			if (pCmds.isEmpty()) pCmds = new HashSet<>(4, Hasher.identity());
			if (pCmds.add(node)) Jocker.CMD.register(node);
		}
	}

	private volatile PluginScheduler scheduler;
	public final PluginScheduler getScheduler() {
		if (scheduler == null) {
			synchronized (desc.stateLock) {
				if (scheduler == null) scheduler = new PluginScheduler(this, Timer.getDefault());
			}
		}
		return scheduler;
	}

	protected void onLoad() {}
	protected void onEnable() throws Exception {}
	protected void onDisable() {}

	final void postDisable() {
		if (scheduler != null) scheduler.cancel();
		for (CommandNode cmd : pCmds) Jocker.CMD.unregister(cmd);
		for (String path : pPaths) unregisterRoute(path);
		for (String path : pIntecs) unregisterInterceptor(path);
		pCmds = Collections.emptySet();
		pPaths = Collections.emptySet();
		pIntecs = Collections.emptySet();
	}

	public String toString() { return desc.getFullDesc(); }
}