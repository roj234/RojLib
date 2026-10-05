package roj.ci;

import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import roj.asmx.mapper.Mapper;
import roj.asmx.mapper.Mapping;
import roj.ci.plugin.Plugin;
import roj.collect.ArrayList;
import roj.collect.LinkedHashMap;
import roj.collect.TrieTreeSet;
import roj.config.XmlParser;
import roj.config.node.MapValue;
import roj.config.node.xml.Document;
import roj.config.node.xml.Node;
import roj.io.IOUtil;
import roj.reflect.Bypass;
import roj.text.CharList;
import roj.text.ParseException;
import roj.text.TextWriter;
import roj.text.Tokenizer;
import roj.util.function.Flow;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * @author Roj234
 * @since 2025/2/12 7:02
 */
public final class Workspace {
	public interface Factory { Env.Workspace build(MapValue config); }
	public static Factory factory(Class<?> type) {return Bypass.builder(Factory.class).delegate(type, "build").build();}

	public String id() {return conf.id;}

	@Unmodifiable public List<File> getDepend() {return depend;}
	@Unmodifiable public List<File> getMappedDepend() {return mappedDepend;}
	@Unmodifiable public List<File> getUnmappedDepend() {return unmappedDepend;}

	@Unmodifiable public LinkedHashMap<String, String> getVariables() {return variables;}
	@Unmodifiable public TrieTreeSet getVariableReplaceContext() {return variableReplaceContext;}

	public Workspace(Env.Workspace conf) {
		this.conf = conf;
		this.depend = conf.depend;
		this.mappedDepend = conf.mappedDepend;
		this.unmappedDepend = conf.unmappedDepend;
		this.variables = conf.variables;
		this.variableReplaceContext = conf.variableReplaceContext;
		this.mapping = conf.mapping;
	}

	private Workspace _next;

	final Env.Workspace conf;

	private File path;

	private List<Plugin> plugins;
	private List<File> depend, mappedDepend, unmappedDepend;
	@Nullable public File mapping;
	private Mapper mapper, invMapper;

	private LinkedHashMap<String, String> variables;
	private TrieTreeSet variableReplaceContext;

	void init() {
		if (plugins == Collections.EMPTY_LIST) throw new IllegalArgumentException("Recursive dependency chain involving "+conf.id);
		if (plugins != null) return;
		plugins = Collections.emptyList();

		var processors = new ArrayList<Plugin>();

		var depName = conf.inherits;
		if (depName != null) {
			Workspace dep = MCMake.workspaces.get(depName);
			if (dep == null) {
				MCMake.log.warn("找不到工作空间{}的继承{}", conf.id, depName);
				return;
			}
			dep.init();

			if (path == null) path = dep.path;
			depend = new ArrayList<>(dep.depend);
			depend.addAll(conf.depend);
			mappedDepend = new ArrayList<>(dep.mappedDepend);
			mappedDepend.addAll(conf.mappedDepend);
			unmappedDepend = new ArrayList<>(dep.unmappedDepend);
			unmappedDepend.addAll(conf.unmappedDepend);
			if (mapping == null) mapping = dep.mapping;
			variables = new LinkedHashMap<>(dep.variables);
			variables.putAll(conf.variables);
			variableReplaceContext = new TrieTreeSet(dep.variableReplaceContext);
			variableReplaceContext.addAll(conf.variableReplaceContext);

			processors.addAll(dep.plugins);
		}
		int initialSize = processors.size();
		Flow.of(MCMake.REGISTERED_PLUGINS).filter(processor -> conf.processors.contains(processor.getClass().getName())).forEach(processors::add);
		if (processors.size()-initialSize < conf.processors.size()) MCMake.log.warn("工作空间 {} 的部分插件 {} 未启用", conf.id, conf.processors);
		this.plugins = processors;

		path = new File(MCMake.BASE, conf.path == null ? conf.id : conf.path);
		path.mkdir();
	}

	@Unmodifiable
	public List<Plugin> getPlugins() {return plugins;}
	public boolean hasPlugin(Plugin plugin) {return plugins.contains(plugin);}

	File getPath() {return path;}

	public Mapping getMapping() throws IOException {
		var m = mapper;
		if (m == null) {
			m = new Mapper();
			if (mapping != null) {
				try (var fs = new FileInputStream(mapping)) {
					m.loadCache(fs, false);
				}
			}
			mapper = m;
		}
		return m;
	}
	public Mapper getInverseMapping() throws IOException {
		var m = invMapper;
		if (m == null) {
			m = new Mapper(getMapping());
			m.reverseSelf();
			m.loadLibraries(unmappedDepend);
			m.flag = 0;
			m.packup();
			invMapper = m;
		}
		return m;
	}

	void onAdd() {
		File file = new File(path, ".idea/libraries/"+IOUtil.escapeFileName(conf.id)+".xml");
		file.getParentFile().mkdirs();

		var doc = new Document();
		doc.headless();

		var component = doc.createElement("component").attr("name", "libraryTable");
		doc.add(component);

		var library = doc.createElement("library").attr("name", getLibraryId());
		component.add(library);

		var classes = doc.createElement("CLASSES");
		library.add(classes);
		library.add(doc.createElement("JAVADOC").voidElement());
		library.add(doc.createElement("SOURCES").voidElement());

		for (File file1 : mappedDepend) {
			classes.add(doc.createElement("root").attr("url", "jar://$PROJECT_DIR$/../cache/"+file1.getName()+"!/").voidElement());
		}

		for (File file1 : depend) {
			classes.add(doc.createElement("root").attr("url", "jar://$PROJECT_DIR$/../cache/"+file1.getName()+"!/").voidElement());
		}

		try (var sout = TextWriter.to(file, StandardCharsets.UTF_8)) {
			component.toCompatXML(sout);
		} catch (IOException e) {
			MCMake.log.error("registerLibrary", e);
		}
	}
	void onRemove() throws IOException {
		for (File dependency : conf.unmappedDepend) {
			Files.deleteIfExists(dependency.toPath());
		}
		for (File dependency : conf.mappedDepend) {
			Files.deleteIfExists(dependency.toPath());
		}
		for (File dependency : conf.depend) {
			Files.deleteIfExists(dependency.toPath());
		}
		if (conf.mapping != null)
			Files.deleteIfExists(conf.mapping.toPath());

		File file = new File(path, ".idea/libraries/"+IOUtil.escapeFileName(conf.id)+".xml");
		Files.deleteIfExists(file.toPath());
	}

	public String getLibraryId() {
		return "MCMake工作空间-"+conf.id;
	}

	/**
	 * mode = 0 add
	 * mode = 1 del
	 * mode = 2 regenerate
	 */
	static void registerModule(Project p, int mode) throws IOException {
		if (!p.conf.type.needCompile()) return;
		File ipr = new File(p.workspace.getPath(), ".idea/modules.xml");
		if (!ipr.isFile()) {
			ipr.getParentFile().mkdirs();
			try (var fos = new FileOutputStream(ipr)) {
				fos.write(("""
						<?xml version="1.0" encoding="UTF-8"?>
						<project version="4">
						    <component name="ProjectModuleManager">
						        <modules></modules>
						    </component>
						</project>""").getBytes(StandardCharsets.UTF_8));
			}
		}

		var name = p.getShortName();

		Document document;
		try {
			document = (Document) new XmlParser().parse(ipr);
		} catch (ParseException e) {
			throw new RuntimeException("XML格式错误", e);
		}
		var modules = document.querySelector("/project/component[name=ProjectModuleManager]/modules").asElement();
		boolean existInProject = false;
		needSave: {
			List<Node> children = modules.children();
			for (int i = 0; i < children.size(); i++) {
				Node child = children.get(i);
				if (child.nodeType() == Node.ELEMENT) {
					var filepath = child.asElement().attr("filepath").asString();
					if (filepath.equals("$PROJECT_DIR$/"+p.getName()+"/"+p.getShortName()+".iml")) {
						if (mode == 1) children.remove(i);
						existInProject = true;
						break needSave;
					}
				}
			}
			if (mode != 0) return;
		}

		var iml = new File(p.root, name+".iml");
		if (iml.isFile() && mode == 0) return;

		if (mode == 1) {
			Files.delete(iml.toPath());
		} else {
			List<Node> nodes = Collections.emptyList();
			if (iml.isFile()) {
				try {
					nodes = ((Document) new XmlParser().parse(iml)).querySelectorAll("/module/component/content/excludeFolder");
				} catch (ParseException e) {
					MCMake.log.error("failed to parse {}", e, iml.getName());
				}
			}

			CharList sb = generateIMLForProject(p, nodes);
			try (var tw = TextWriter.to(iml, StandardCharsets.UTF_8)) {
				tw.append(sb);
			}

			if (existInProject) return;

			var module = document.createElement("module");
			module.isVoid = true;
			module.attr("fileurl", "file://$PROJECT_DIR$/"+p.getName()+"/"+p.getShortName()+".iml");
			module.attr("filepath", "$PROJECT_DIR$/"+p.getName()+"/"+p.getShortName()+".iml");
			modules.add(module);
		}

		IOUtil.writeAtomically(ipr.getParentFile(), ipr.getName(), file -> {
			try (var tw = TextWriter.to(file, StandardCharsets.UTF_8)) {
				document.toXML(tw);
			}
		});
	}

	private static CharList generateIMLForProject(Project p, List<Node> keepNodes) {
		var sb = new CharList("""
				<?xml version="1.0" encoding="UTF-8"?><!--MCMake-->
				<module type="JAVA_MODULE" version="4">
				  <component name="NewModuleRootManager" inherit-compiler-output="true">
				    <exclude-output />
				    <content url="file://$MODULE_DIR$">
				      <sourceFolder url="file://$MODULE_DIR$/java" isTestSource="false" />
				""");

		for (Node node : keepNodes) {
			node.toCompatXML(sb);
			sb.append('\n');
		}

		if (p.resPath.isDirectory()) {
			sb.append("<sourceFolder url=\"file://$MODULE_DIR$/resources\" type=\"java-resource\" />");
		}
		sb.append("</content>\n<orderEntry type=\"inheritedJdk\" />");

		var workspace = p.workspace;
		if (workspace.mappedDepend.size() + workspace.depend.size() > 0) {
			Tokenizer.escape(sb.append("<orderEntry type=\"library\" name=\""), p.workspace.getLibraryId()).append("\" level=\"project\" />\n");
		}

		sb.append("""
				<orderEntry type="library" name="MCMake公共依赖" level="project" />
				<orderEntry type="sourceFolder" forTests="false" />
				""");

		for (Map.Entry<String, Dependency.Scope> entry : p.conf.dependency.entrySet()) {
			Dependency dep = p.conf.dependencyInstances.get(entry.getKey());
			if (dep == null) continue;

			int lenI = sb.length();

			switch (entry.getValue()) {
				case COMPILE -> sb.append("<orderEntry ");
				case EXPORT -> sb.append("<orderEntry exported=\"\" ");
				case BUNDLED -> sb.append("<orderEntry scope=\"PROVIDED\" ");
			}

			int lenJ = sb.length();

			dep.writeProjectConfiguration(p.root, sb, "IML");

			if (lenJ == sb.length()) {
				sb.setLength(lenI);
			} else {
				sb.append('\n');
			}
		}

		return sb.append("</component></module>");
	}
}
