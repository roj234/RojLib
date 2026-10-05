package roj.asmx.mapper;

import org.jetbrains.annotations.NotNull;
import roj.archive.xz.LZMA2InputStream;
import roj.archive.xz.LZMA2Options;
import roj.archive.xz.LZMAInputStream;
import roj.archive.zip.ZipEntry;
import roj.archive.zip.ZipFile;
import roj.asm.*;
import roj.asm.annotation.AnnVal;
import roj.asm.annotation.Annotation;
import roj.asm.attr.*;
import roj.asm.cp.*;
import roj.asm.type.Signature;
import roj.asm.type.Type;
import roj.asmx.Context;
import roj.asmx.ParamNameMapper;
import roj.collect.ArrayList;
import roj.collect.HashMap;
import roj.collect.HashSet;
import roj.collect.*;
import roj.concurrent.Executor;
import roj.concurrent.TaskGroup;
import roj.concurrent.TaskPool;
import roj.io.IOUtil;
import roj.io.XDataInputStream;
import roj.text.CharList;
import roj.text.logging.Level;
import roj.util.ByteList;
import roj.util.DynByteBuf;
import roj.util.Helpers;
import roj.util.StringPool;

import java.io.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

import static roj.asm.Opcodes.*;
import static roj.asm.type.Type.ARRAY;

/**
 * @author Roj234
 * @version 3.1
 * @since 2020/8/19 22:13
 */
@Deprecated
public class Mapper extends Mapping {
	public static final String DONT_LOAD_PREFIX = "[x]";

	/** 任意methodMap和fieldMap的owner均能在classMap中找到 */
	public static final int FLAG_FULL_CLASS_MAP = 1,
	/** (Obfuscator用) 删除冲突的mapping（方法继承） */
	FLAG_FIX_INHERIT = 4,
	/** (Obfuscator用) 删除冲突的mapping（子类实现的接口覆盖父类方法） */
	MF_FIX_SUBIMPL = 2,
	/** 启用注解伪继承 */
	MF_ANNOTATION_INHERIT = 8,
	/** 类名有变动 */
	MF_RENAME_CLASS = 16,
	/** 单线程 */
	MF_SINGLE_THREAD = 32,
	/** 修复class改名造成的访问问题 */
	MF_FIX_ACCESS = 64;

	private static final int ASYNC_THRESHOLD = 1000;
	// 注意事项
	// 1. 返回值的重载通过bridge method实现，JVM就是傻逼，当然也可以让你实现骚操作
	// 2. stopAnchor
	// 3. fixAccess inheritor

	private final UnaryOperator<String> signatureMapper = (old) -> {
		String now = ClassUtil.getInstance().mapClassName(classMap, old);
		return now == null ? old : now;
	};
	private final ParamNameMapper paramMapper = new ParamNameMapper() {
		@Override
		protected List<String> getParamNames(MethodNode m) {
			if (!paramMap.isEmpty()) {
				String owner = m.owner();

				MemberDescriptor d = ClassUtil.getInstance().sharedDesc;
				d.owner = classMap.getOrDefault(owner, owner);
				d.name = m.name();
				d.rawDesc = m.rawDesc();

				List<String> parents = hierarchy.getOrDefault(owner, Collections.emptyList());
				int i = 0;
				while (true) {
					List<String> param = paramMap.get(d);
					if (param != null) return copyOf(param);

					if (i == parents.size()) break;
					d.owner = parents.get(i++);
				}
			}

			return Collections.emptyList();
		}

		@Override
		protected String mapType(String type) { return ClassUtil.getInstance().mapFieldType(classMap, type); }
		@Override
		protected String mapGenericType(String type) {
			if ((flag&MF_RENAME_CLASS) == 0) return type;

			Signature s = Signature.parse(type);
			s.rename(signatureMapper);
			return s.toDesc();
		}
	};

	private final List<State> extraStates = new ArrayList<>();
	/**
	 * 来自依赖的数据
	 */
	public final HashSet<MemberDescriptor> libShadows;
	public final HashMap<String, List<String>> libHierarchy;

	/**
	 * 工作中数据
	 */
	private Map<MemberDescriptor, String> selfInherited;
	private Set<MemberDescriptor> shadows;
	// 加入flag区分接口和类，以提升FieldRef和InterfaceMethodRef处理时的性能
	private Map<String, List<String>> hierarchy;

	public byte flag = FLAG_FULL_CLASS_MAP;

	public Mapper() { this(false); }
	public Mapper(boolean checkFieldType) {
		super(checkFieldType);
		libHierarchy = new HashMap<>(128);
		libShadows = new HashSet<>();
	}

	public Mapper(Mapper o) {
		super(o);
		this.libShadows = new HashSet<>(o.libShadows);
		this.libHierarchy = new HashMap<>(o.libHierarchy);
		this.paramMapper.isValid = o.paramMapper.isValid;
		this.flag = o.flag;
	}
	public Mapper(Mapping o) {
		super(o);
		this.libHierarchy = new HashMap<>(128);
		this.libShadows = new HashSet<>();
	}

	// region 缓存

	private static LZMA2Options getLZMAOption() {return new LZMA2Options(6).setDictSize(2097152).setPb(0);}

	public void saveCache(OutputStream cache, int saveMode) throws IOException {
		StringPool pool = new StringPool();
		if (!fieldHasType) pool.add("");

		ByteList w = IOUtil.getSharedByteBuf();

		if ((saveMode&1) != 0) {
			w.putVUInt(classMap.size());
			for (Map.Entry<String, String> s : classMap.entrySet()) {
				pool.add(pool.add(w, s.getKey()), s.getValue());
			}

			w.putVUInt(fieldMap.size());
			for (Map.Entry<MemberDescriptor, String> s : fieldMap.entrySet()) {
				MemberDescriptor desc = s.getKey();
				pool.add(pool.add(pool.add(pool.add(w, desc.owner), desc.name), desc.rawDesc).put(desc.modifier), s.getValue());
			}

			w.putVUInt(methodMap.size());
			for (Map.Entry<MemberDescriptor, String> s : methodMap.entrySet()) {
				MemberDescriptor desc = s.getKey();
				pool.add(pool.add(pool.add(pool.add(w, desc.owner), desc.name), desc.rawDesc).put(desc.modifier), s.getValue());
			}
		} else {
			w.putMedium(0);
		}

		if ((saveMode&2) != 0) {
			w.putVUInt(libHierarchy.size());
			for (Map.Entry<String, List<String>> s : libHierarchy.entrySet()) {
				pool.add(w, s.getKey());

				MapperList list = (MapperList) s.getValue();
				w.putVUInt(list.selfIdx).putVUInt(list.size());
				for (int i = 0; i < list.size(); i++) pool.add(w, list.get(i));
			}

			w.putVUInt(libShadows.size());
			for (MemberDescriptor s : libShadows) {
				pool.add(pool.add(pool.add(w, s.owner), s.name), s.rawDesc);
			}
		} else {
			w.putShort(0);
		}

		try (var out = getLZMAOption().getOutputStream(cache)) {
			try (var header = new ByteList.ToStream(out, false)) {
				pool.writePool(header);
			}
			w.writeToStream(out);
		}
	}

	public Boolean loadCache(InputStream cache, boolean readClassInheritanceMap) throws IOException {
		var push = new PushbackInputStream(cache);
		int read = push.read();
		push.unread(read);
		InputStream in;
		if (read == 0) {
			// legacy
			LZMA2Options opt = getLZMAOption();
			in = new LZMAInputStream(push, -1, opt.getPropByte(), opt.getDictSize());
		} else {
			in = new LZMA2InputStream(push, 2097152);
		}
		var r = XDataInputStream.wrap(in);

		var pool = new StringPool(r);

		int count = r.readVUInt();
		while(count-- > 0) classMap.put(pool.get(r), pool.get(r));

		count = r.readVUInt();
		fieldMap.ensureCapacity(count);
		while(count-- > 0) {
			fieldMap.put(new MemberDescriptor(pool.get(r), pool.get(r), pool.get(r), r.readUnsignedByte()), pool.get(r));
		}

		count = r.readVUInt();
		methodMap.ensureCapacity(count);
		while(count-- > 0) {
			methodMap.put(new MemberDescriptor(pool.get(r), pool.get(r), pool.get(r), r.readUnsignedByte()), pool.get(r));
		}

		if (!readClassInheritanceMap) return false;

		count = r.readVUInt();
		while(count-- > 0) {
			String name = pool.get(r);

			int idx = r.readVUInt();
			int len2 = r.readVUInt();
			MapperList sch = new MapperList(len2);
			while(len2-- > 0) sch.add(pool.get(r));
			sch.selfIdx = idx;
			sch.index();

			libHierarchy.put(name, sch);
		}

		count = r.readVUInt();
		while(count-- > 0) {
			libShadows.add(new MemberDescriptor(pool.get(r), pool.get(r), pool.get(r)));
		}

		return true;
	}

	// endregion
	/**
	 * 全量
	 */
	public void map(List<Context> ctxs) {map(ctxs, TaskPool.cpu());}
	public void map(List<Context> ctxs, Executor pool) {
		if ((flag&MF_SINGLE_THREAD)!=0 || ctxs.size() <= ASYNC_THRESHOLD) {
			mapSync(ctxs, true);
			return;
		}

		shadows = Collections.newSetFromMap(new ConcurrentHashMap<>(libShadows.size()));
		shadows.addAll(libShadows);
		hierarchy = new ConcurrentHashMap<>(ctxs.size());
		selfInherited = new SynchronizedFindMap<>();

		List<List<Context>> tasks = TaskGroup.split(ctxs, ASYNC_THRESHOLD);
		var group = pool.newGroup();

		group.executeAll(tasks, this::S1_parse).await();

		buildHierarchy();

		syncStage2(ctxs);

		group.executeAll(tasks, (ctx) -> S3_mapSelf(ctx, false)).await();
		group.executeAll(tasks, this::S4_mapConstant).await();
		if ((flag&MF_RENAME_CLASS) != 0) {
			group.executeAll(tasks, this::S5_mapClassName).await();
			group.executeAll(tasks, this::S5_1_resetDebugInfo).await();
		}
	}

	/**
	 * 增量
	 */
	public void mapIncr(List<Context> ctxs) { mapSync(ctxs, false); }

	private void mapSync(List<Context> ctxs, boolean full) {
		if (hierarchy == null || full) setupHierarchy(ctxs.size());

		Context ctx = null;
		try {
			for (int i = 0; i < ctxs.size(); i++) S1_parse(ctx = ctxs.get(i));

			buildHierarchy();

			if (!full) {
				HashSet<String> modified = new HashSet<>();
				for (int i = 0; i < ctxs.size(); i++) {
					modified.add(ctxs.get(i).getData().name());
				}
				onClassChanged(modified, false);
			}

			syncStage2(ctxs);

			for (int i = 0; i < ctxs.size(); i++) S3_mapSelf(ctx = ctxs.get(i), false);
			for (int i = 0; i < ctxs.size(); i++) S4_mapConstant(ctx = ctxs.get(i));
			if ((flag&MF_RENAME_CLASS) != 0) {
				for (int i = 0; i < ctxs.size(); i++) {
					S5_mapClassName(ctx = ctxs.get(i));
					S5_1_resetDebugInfo(ctx);
				}
			}
		} catch (Throwable e) {
			throw new RuntimeException("At parsing " + ctx, e);
		}
	}

	private void syncStage2(List<Context> ctxs) {
		if ((flag&(MF_FIX_ACCESS|MF_FIX_SUBIMPL)) != 0) {
			S2_begin(ctxs);

			if ((flag&MF_FIX_ACCESS) != 0) S2_1_FixAccess(ctxs, false);
			if ((flag&MF_FIX_SUBIMPL) != 0) S2_3_FixSubImpl(ctxs, false);

			S2_end();
		}
	}

	public void onClassChanged(HashSet<String> modified, boolean removed) {
		if (removed) hierarchy.keySet().removeAll(modified);
		Predicate<MemberDescriptor> rem = key -> modified.contains(key.owner);
		selfInherited.keySet().removeIf(rem);
		shadows.removeIf(rem);
	}

	// region 映射

	/**
	 * Step 1 Prepare parent mapping
	 */
	public final void S1_parse(Context c) {
		ClassNode data = c.getData();
		var itfs = data.interfaces();

		int size = itfs.size() + ("java/lang/Object".equals(data.parent()) ? 0 : 1);
		if (size == 0 && (flag&MF_ANNOTATION_INHERIT) == 0) return;

		ArrayList<String> list = new ArrayList<>(size);
		if (!"java/lang/Object".equals(data.parent())) list.add(data.parent());
		for (int i = 0; i < itfs.size(); i++) list.add(itfs.get(i));

		if ((flag&MF_ANNOTATION_INHERIT) != 0) {
			var found = Annotation.findInvisible(data, data, "roj/asmx/mapper/Inherited");
			if (found != null) {
				var value = found.getList("value");
				for (int i = 0; i < value.size(); i++) {
					list.add(value.getType(i).owner);
				}
			}
			if (list.isEmpty()) return;
		}

		hierarchy.put(data.name(), list);
	}

	private HashMap<String, ClassDefinition> s2_tmp_byName;
	private HashMap<String, List<MemberDescriptor>> s2_tmp_methods;
	public final void S2_begin(List<Context> ctxs) {
		HashMap<String, ClassDefinition> classInfo = new HashMap<>(ctxs.size());
		for (int i = 0; i < ctxs.size(); i++) {
			ClassNode data = ctxs.get(i).getData();
			classInfo.put(data.name(), data);
		}
		s2_tmp_byName = classInfo;
		s2_tmp_methods = new HashMap<>();
	}

	/**
	 * Step 2.1 (Optional) Fix access bug when changing package
	 * @param downgradeToo downgrade access level also (no supported yet)
	 */
	public final void S2_1_FixAccess(List<Context> ctxs, boolean downgradeToo) {
		HashSet<MemberDescriptor> upgraded = new HashSet<>();
		HashSet<Object> processed = new HashSet<>();

		for (int i = 0; i < ctxs.size(); i++) {
			Context ctx = ctxs.get(i);

			String selfName = ctx.getData().name();
			String selfNewName = classMap.getOrDefault(selfName, selfName);

			List<CstClass> cref = ctx.getClassConstants();
			for (int j = 0; j < cref.size(); j++) {
				String name = cref.get(j).value().str();
				if (name.equals(selfName)) continue;

				if (processed.contains(name)) continue;

				ClassDefinition cn = s2_tmp_byName.get(name);
				//SIG:reflectClassInfo
				if (cn == null) continue;

				if ((cn.modifier()&ACC_PUBLIC) != 0) continue;

				String newName = classMap.getOrDefault(name, name);
				if (!ClassUtil.arePackagesSame(selfNewName, newName)) {
					cn.modifier(cn.modifier()|ACC_PUBLIC);
					processed.add(name);

					LOGGER.log(Level.TRACE, "[FAcc-C] 提升了 {} 引用的 {} (+public)", null, selfName, name);
				}
			}

			checkAccess(ctx.getMethodConstants(), true, processed, selfName, selfNewName, upgraded);
			checkAccess(ctx.getFieldConstants(), false, processed, selfName, selfNewName, upgraded);

			ClassNode data = ctx.getData();
			ArrayList<MethodNode> methods = data.methods;
			for (int j = 0; j < methods.size(); j++) {
				MethodNode mn = methods.get(j);
				// inheritable package-private method
				if ((mn.modifier() & (ACC_PUBLIC|ACC_PROTECTED|ACC_PRIVATE|ACC_STATIC|ACC_FINAL)) == 0) {
					upgraded.add(new MemberDescriptor(data.name(), mn.name(), mn.rawDesc(), 6));
				}
			}
		}

		MemberDescriptor d = ClassUtil.getInstance().sharedDesc;
		List<String> exist = new ArrayList<>();
		for (int i = 0; i < ctxs.size(); i++) {
			ClassNode data = ctxs.get(i).getData();

			List<String> parents = hierarchy.get(data.name());
			if (parents == null) continue;

			exist.clear();
			for (int j = 0; j < parents.size(); j++) {
				String parent = parents.get(j);
				if (s2_tmp_byName.containsKey(parent)) exist.add(parent);
			}
			if (exist.isEmpty()) continue;

			ArrayList<MethodNode> methods = data.methods;
			for (int j = 0; j < methods.size(); j++) {
				MethodNode mn = methods.get(j);

				d.owner = data.name();
				d.name = mn.name();
				d.rawDesc = mn.rawDesc();
				if (d.name.startsWith("<")) continue;

				int k = 0;
				while (true) {
					MemberDescriptor d1 = upgraded.find(d);
					if (d1 != d) {
						char acc = mn.modifier();
						boolean isProtected = (d1.modifier & ACC_PUBLIC) == 0;
						boolean isInherited = d1.modifier == 6;
						if (isProtected) {
							if ((acc&(ACC_PUBLIC|ACC_PROTECTED)) == 0) {
								mn.modifier(acc|ACC_PROTECTED);
								LOGGER.log(Level.TRACE, "[FAcc-I] 提升了 {} 继承的 {} (+protected)", null, data.name(), d);
							}
						} else {
							if ((acc& ACC_PUBLIC) == 0) {
								mn.modifier(acc & ~ACC_PROTECTED | ACC_PUBLIC);
								LOGGER.log(Level.TRACE, "[FAcc-I] 提升了 {} 继承的 {} (+public)", null, data.name(), d);
							}
						}

						if (isInherited) {
							ClassDefinition ctx = s2_tmp_byName.get(d1.owner);
							Member m = ctx.methods().get(ctx.getMethod(d1.name, d1.rawDesc));
							if ((m.modifier()&5) == 0) m.modifier(m.modifier()|ACC_PROTECTED);
							d1.modifier = 0;
						}
					}

					if (shadows.contains(d)) break;

					if (k == exist.size()) break;
					d.owner = exist.get(k++);
				}
			}
		}
	}
	private void checkAccess(List<CstRef> ref, boolean method,
							 HashSet<Object> processed,
							 String selfName, String selfNewName,
							 HashSet<MemberDescriptor> upgraded) {
		MemberDescriptor d = ClassUtil.getInstance().sharedDesc;
		nextNode:
		for (int j = 0; j < ref.size(); j++) {
			if (processed.contains(ref.get(j))) continue;
			d.read(ref.get(j));

			Member node;

			int k = 0;
			List<String> parents = hierarchy.getOrDefault(d.owner, Collections.emptyList());

			found:
			while (true) {
				//SIG:reflectClassInfo | getMethodInfo
				ClassDefinition aa = s2_tmp_byName.get(d.owner);
				if (aa != null) {
					List<Member> methods = Helpers.cast(method?aa.methods():aa.fields());
					for (int i1 = 0; i1 < methods.size(); i1++) {
						node = methods.get(i1);
						if (node.rawDesc().equals(d.rawDesc) && node.name().equals(d.name)) {
							break found;
						}
					}
				}

				if (shadows.contains(d) || k == parents.size()) {
					processed.add(ref.get(j));
					continue nextNode;
				}

				d.owner = parents.get(k++);
			}

			char acc = node.modifier();
			if ((acc & (ACC_PUBLIC|ACC_PRIVATE)) != 0) {
				processed.add(ref.get(j));
				continue; // public
			}

			String newName = classMap.getOrDefault(d.owner, d.owner);
			if (ClassUtil.arePackagesSame(newName, selfNewName)) continue; // package-private

			//SIG:不检测class: protected的判断太麻烦了，还要检测是不是aload_0
			boolean protectedEnough = selfName.equals(ref.get(j).owner());
			if ((acc & ACC_PROTECTED) == 0 || !protectedEnough) {
				LOGGER.log(Level.TRACE, "[FAcc-N] 提升了 {} 引用的 {} (+{})", null, selfName, d, protectedEnough ? "protected" : "public");
				if (protectedEnough) {
					node.modifier(acc|ACC_PROTECTED);
				} else {
					processed.add(ref.get(j));
					node.modifier(acc & ~ACC_PROTECTED | ACC_PUBLIC);
				}

				if (method && (acc & (ACC_STATIC|ACC_FINAL)) == 0) {
					d.modifier = node.modifier();
					upgraded.add(d.copy());
				}
			}
		}
	}

	/**
	 * Step 2.2 (Optional) Fix mapping on inheritance chain result in same name method <br>
	 * The mapping must be modifiable
	 */
	public final boolean S2_2_FixInheritConflict(List<Context> ctxs) {
		HashMap<NameAndType, Set<NameAndType>> sameNameNodes = new HashMap<>();
		HashSet<String> checked = new HashSet<>();

		boolean success = true;
		for (Context ctx : ctxs) {
			ClassNode data = ctx.getData();
			if (!checked.add(data.name())) continue;

			tryMap(null, data.name(), data.fields, sameNameNodes, fieldMap);
			tryMap(null, data.name(), data.methods, sameNameNodes, methodMap);

			List<String> parents = hierarchy.getOrDefault(data.name(), Collections.emptyList());
			for (String parent : parents) {
				checked.add(parent);
				tryMap(data.name(), parent, getMethodInfoEx(parent), sameNameNodes, methodMap);
			}

			for (Map.Entry<NameAndType, Set<NameAndType>> entry : sameNameNodes.entrySet()) {
				if (entry.getValue().size() <= 1) continue;

				success = false;
				LOGGER.log(Level.WARN, "[InheritConflict]: {} => {}", null, entry.getValue(), entry.getKey().name);

				boolean remove = false;
				for (NameAndType desc : entry.getValue()) {
					if (desc.modifier == 0) {
						// unmappable
						remove = true;
						break;
					}
				}

				for (NameAndType desc : entry.getValue()) {
					if (remove) methodMap.remove(desc.copy());
					remove = true;
				}
			}
			sameNameNodes.clear();
		}
		return success;
	}
	private void tryMap(String inheritTo,
						String owner, List<?> list,
						HashMap<NameAndType, Set<NameAndType>> descs,
						FindMap<MemberDescriptor, String> map) {
		List<String> parents = hierarchy.getOrDefault(owner, Collections.emptyList());
		MemberDescriptor d = ClassUtil.getInstance().sharedDesc;
		for (int i = 0; i < list.size(); i++) {
			Member m = (Member) list.get(i);

			d.owner = owner;
			d.name = m.name();
			d.rawDesc = m.rawDesc();
			d.modifier = 0;

			if (inheritTo != null) {
				char acc = m.modifier();
				if ((acc & (ACC_PRIVATE)) != 0) continue;
				if ((acc & (ACC_PUBLIC|ACC_PROTECTED)) == 0 && !ClassUtil.arePackagesSame(inheritTo, owner)) continue;
			}

			int j = 0;
			while (true) {
				Map.Entry<MemberDescriptor, String> entry = map.find(d);
				if (entry != null) {
					d.name = entry.getValue();
					d.modifier = 1;
					break;
				}

				if (shadows.contains(d)) {
					LOGGER.log(Level.DEBUG, "[{}][Shadow] stopOn {}.{}{}", null, owner, i==0?"~":d.owner, d.name, d.rawDesc);
					break;
				}

				if (j == parents.size()) break;
				d.owner = parents.get(j++);
			}

			NameAndType key = new NameAndType();
			key.name = d.name;
			key.rawDesc = d.rawDesc;

			NameAndType val = new NameAndType();
			val.owner = d.owner;
			val.name = m.name();
			val.rawDesc = key.rawDesc;
			val.modifier = d.modifier;
			descs.computeIfAbsent(key, Helpers.fnHashSet()).add(val);
		}
	}

	/**
	 * Step 2.3 (Optional) 修复父类的方法被子类实现的接口使用时的映射冲突 <BR>
	 *     <p>"Implements method in <i>some interface</i> via subclass"</p>
	 * @param mapIsMutable allow to remove mapping
	 * @return added mapping
	 */
	public final List<MemberDescriptor> S2_3_FixSubImpl(List<Context> ctxs, boolean mapIsMutable) {
		List<MemberDescriptor> added = new ArrayList<>();

		for (SubImpl method : collectSubImpl(ctxs)) {
			MemberDescriptor desc = method.type.copy();

			for (Set<String> classList : method.owners) {
				if (classList.remove(null)) {
					if (!mapIsMutable) throw new IllegalArgumentException("SubImpl映射失败: 不可变: " + classList + " of " + method.type);

					for (String owner : classList) {
						desc.owner = owner;
						methodMap.remove(desc);
					}

					continue;
				}

				String mapName = null, mapClass = null;
				for (String owner : classList) {
					desc.owner = owner;

					String name = methodMap.get(desc);
					if (name != null) {
						if (mapName == null) {
							mapClass = owner;
							mapName = name;
							if (mapIsMutable) break;
						} else if (!mapName.equals(name)) {
							throw new IllegalStateException("SubImpl映射失败: 映射名称不同: " + classList + " of " + method.type);
						}
					}
				}

				// 未找到任何映射，略过
				if (mapName == null) continue;

				for (String owner : classList) {
					if (owner.equals(mapClass)) continue;
					desc.owner = owner;

					if (!mapName.equals(methodMap.put(desc, mapName))) {
						added.add(desc);

						ClassNode data = (ClassNode) s2_tmp_byName.get(desc.owner);
						int i = data.getMethod(desc.name, desc.rawDesc);
						if (i < 0) throw new IllegalStateException("缺少元素(not in context...): " + desc);
						desc.modifier = data.methods.get(i).modifier();

						desc = desc.copy();
					}
				}
			}
		}
		return added;
	}
	private Set<SubImpl> collectSubImpl(List<Context> ctx) {
		HashSet<SubImpl> out = new HashSet<>();

		SubImpl sTest = new SubImpl();
		NameAndType nTest = new NameAndType();
		MemberDescriptor dTest = ClassUtil.getInstance().sharedDesc;

		ArrayList<NameAndType> interfaceMethods = new ArrayList<>();

		for (int i = 0; i < ctx.size(); i++) {
			ClassNode data = ctx.get(i).getData();
			if ((data.modifier() & (ACC_INTERFACE|ACC_ANNOTATION|ACC_MODULE)) != 0) continue;

			List<String> itfs = data.interfaces();
			if (itfs.isEmpty()) continue;

			interfaceMethods.clear();

			List<String> parents = hierarchy.get(data.parent());
			if (parents == null) parents = ClassUtil.getInstance().getHierarchyList(data.parent());

			for (int j = 0; j < itfs.size(); j++) {
				String name = itfs.get(j);
				if (!parents.contains(name)) {
					List<Member> nodes = getMethodInfoEx(name);

					for (int k = 0; k < nodes.size(); k++) {
						Member node = nodes.get(k);
						if ((node.modifier() & (ACC_PRIVATE|ACC_STATIC)) != 0) continue;

						NameAndType key = new NameAndType();
						key.owner = name;
						key.name = node.name();
						key.rawDesc = node.rawDesc();
						interfaceMethods.add(key);
					}
				}
			}
			if (interfaceMethods.isEmpty()) continue;

			int j = 0;
			String parent = data.parent();
			while (true) {
				List<Member> nodes = getMethodInfoEx(parent);
				for (int k = 0; k < nodes.size(); k++) {
					Member node = nodes.get(k);
					if ((node.modifier() & (ACC_PUBLIC|ACC_STATIC)) != ACC_PUBLIC) continue;

					if ((nTest.name = node.name()).startsWith("<")) continue;
					nTest.rawDesc = node.rawDesc();

					int id = -1;
					while ((id = interfaceMethods.indexOf(nTest, id+1)) >= 0) {
						NameAndType issuer = interfaceMethods.get(id);

						dTest.owner = issuer.owner;
						dTest.name = issuer.name;
						dTest.rawDesc = issuer.rawDesc;

						if (!methodMap.containsKey(dTest)) {
							dTest.owner = parent;
							if (!methodMap.containsKey(dTest)) continue;
						}

						sTest.type = issuer;
						SubImpl s_get = out.intern(sTest);
						if (s_get == sTest) sTest = new SubImpl();

						Set<String> set;
						found: {
							for (var itr = s_get.owners.iterator(); itr.hasNext(); ) {
								set = itr.next();
								if (set.contains(parent) || set.contains(issuer.owner)) break found;
							}

							set = new HashSet<>(2);
							s_get.owners.add(set);
						}

						set.add(parent);
						set.add(issuer.owner);

						// native不能
						if ((node.modifier() & ACC_NATIVE) != 0) set.add(null);
						// 至少有一个类不是要处理的类: 不能混淆
						if (!s2_tmp_byName.containsKey(parent) || !s2_tmp_byName.containsKey(issuer.owner)) set.add(null);
					}
				}

				if (j == parents.size()) break;
				parent = parents.get(j++);
			}
		}

		return out;
	}
	@NotNull
	private List<Member> getMethodInfoEx(String name) {
		ClassDefinition c = s2_tmp_byName.get(name);
		if (c == null) c = ClassUtil.getInstance().resolve(name);
		if (c != null) return Helpers.cast(c.methods());

		if (s2_tmp_methods.isEmpty()) {
			for (MemberDescriptor key : methodMap.keySet()) {
				s2_tmp_methods.computeIfAbsent(key.owner, Helpers.fnArrayList()).add(key);
			}
		}
		List<MemberDescriptor> list = s2_tmp_methods.getOrDefault(name, Collections.emptyList());
		for (int i = 0; i < list.size(); i++) {
			MemberDescriptor desc = list.get(i);
			if (desc.modifier == MemberDescriptor.FLAG_UNSET) throw new IllegalStateException("缺少元素: "+desc);
		}
		return Helpers.cast(list);
	}

	public final void S2_end() { s2_tmp_byName = null; s2_tmp_methods = null; }

	/**
	 * Step 3 Self method/field name (and type in record)
	 */
	public final void S3_mapSelf(Context ctx, boolean simulate) {
		ClassNode node = ctx.getData();

		List<String> parents = hierarchy.getOrDefault(node.name(), Collections.emptyList());

		MemberDescriptor d = ClassUtil.getInstance().sharedDesc;

		List<MethodNode> methods = node.methods;
		for (int i = 0; i < methods.size(); i++) {
			MethodNode m = methods.get(i);

			if (!simulate) paramMapper.mapParam(node.cp, m);

			if (m.name().startsWith("<")) continue;

			int parentCount = (m.modifier&(Opcodes.ACC_PRIVATE|Opcodes.ACC_STATIC)) != 0 ? 0 : parents.size();

			d.owner = node.name();
			d.name = m.name();
			d.rawDesc = m.rawDesc();

			int j = 0;
			while (true) {
				Map.Entry<MemberDescriptor, String> entry = methodMap.find(d);
				if (entry == null) {
					if (j == parentCount) break;
					d.owner = parents.get(j++);
					continue;
				}

				// 成员B能否重写成员A，无关继承路径，只依赖于成员A的访问修饰符。
				// 如果从B到A的继承路径上有访问权限更低的，其不起作用，如果有final的，非法
				int acc = entry.getKey().modifier;
				if (acc == MemberDescriptor.FLAG_UNSET) {
					if (simulate) {
						LOGGER.warn("缺少 {} 的访问修饰符", entry.getKey());
						if (j >= parents.size()) break;
						d.owner = parents.get(j++);
						continue;
					}
					throw new IllegalStateException("缺少 "+entry.getKey()+" 的访问修饰符");
				}

				if (!d.owner.equals(node.name()) && (
						0 != (acc & (ACC_PRIVATE|ACC_STATIC|ACC_FINAL))
						|| (0 == (acc & (ACC_PUBLIC|ACC_PROTECTED)) && !ClassUtil.arePackagesSame(node.name(), d.owner))
				)) {
					LOGGER.log(Level.TRACE, "[Shadow]: {} 隐藏了 {}", null, node.name(), d);
					d.owner = node.name();
					shadows.add(d.copy());
				} else if (!simulate) {
					String newName = entry.getValue();
					m.name(newName);

					d.owner = node.name();
					// 加入一个fast path，用于快速查找reference
					selfInherited.put(d.copy(), newName);
				}
				break;
			}
		}

		d.rawDesc = "";

		List<? extends Member> fields = node.fields;
		for (int i = 0; i < fields.size(); i++) {
			FieldNode f = (FieldNode) fields.get(i);

			d.owner = node.name();
			d.name = f.name();
			if (fieldHasType) d.rawDesc = f.rawDesc();

			int j = 0;
			while (true) {
				String newName = fieldMap.get(d);
				if (newName != null) {
					// j == 0 <==> d.owner == node.name (library only)
					if (j > 0) {
						LOGGER.log(Level.TRACE, "[Shadow]: {} 隐藏了 {}", null, node.name(), d);
						d.owner = node.name();
						shadows.add(d.copy());
					} else {
						if (!simulate) f.name(newName);
					}
					break;
				}

				if (j == parents.size()) break;
				d.owner = parents.get(j++);
			}
		}

		if (!simulate) mapRecord(d, node);
	}
	/** Field name and type in 'Record' attribute */
	private void mapRecord(MemberDescriptor d, ClassNode node) {
		RecordAttribute r = node.getAttribute(Attribute.Record);
		if (r == null) return;

		ClassUtil U = ClassUtil.getInstance();

		d.owner = node.name();
		d.rawDesc = "";
		List<RecordAttribute.Field> vars = r.fields;
		for (int i = 0; i < vars.size(); i++) {
			RecordAttribute.Field v = vars.get(i);

			d.name = v.name;
			if (fieldHasType) d.rawDesc = v.type;

			String newName = fieldMap.get(d);
			if (newName != null) {
				v.name = newName;
			}

			String newCls = U.mapFieldType(classMap, v.type);
			if (newCls != null) v.type = newCls;
		}
	}

	/**
	 * Step 4: Reference
	 */
	public final void S4_mapConstant(Context ctx) {
		ClassNode node = ctx.getData();

		BootstrapMethods bsm = null;

		List<Constant> list = node.cp.constants();
		for (int j = 0; j < list.size(); j++) {
			Constant c = list.get(j);
			switch (c.type()) {
				case Constant.INTERFACE, Constant.METHOD -> mapRef(node, (CstRef) c, true);
				case Constant.FIELD -> mapRef(node, (CstRef) c, false);
				case Constant.INVOKE_DYNAMIC -> {
					if (bsm == null) {
						bsm = node.getAttribute(Attribute.BootstrapMethods);
						if (bsm == null) throw new IllegalArgumentException("有lambda却无BootstrapMethod, "+node.name());
					}
					mapLambda(bsm, node, (CstDynamic) c);
				}
			}
		}
	}
	/** Map: lambda method name */
	private void mapLambda(BootstrapMethods bs, ClassNode data, CstDynamic dyn) {
		if (dyn.tableIdx >= bs.methods.size())
			throw new IllegalArgumentException("BootstrapMethod #"+(int) dyn.tableIdx+"不存在, in class "+ data.name());

		BootstrapMethods.Item ibm = bs.methods.get(dyn.tableIdx);
		if (!ibm.isLambda()) return;

		MemberDescriptor d = ClassUtil.getInstance().sharedDesc;

		d.name = dyn.desc().name().str();
		// FP: init / clinit
		if (d.name.startsWith("<")) return;

		String allDesc = dyn.desc().rawDesc().str();
		if (!allDesc.endsWith(";")) return;

		d.rawDesc = ibm.lambdaInterfaceDesc();
		d.owner = Type.getReturnType(allDesc).owner;

		List<String> parents = hierarchy.getOrDefault(d.owner, Collections.emptyList());
		int i = 0;
		while (true) {
			String name = methodMap.get(d);
			if (name != null) {
				dyn.setDesc(data.cp.getDesc(name, allDesc));
				return;
			}

			if (shadows.contains(d)) {
				LOGGER.log(Level.TRACE, "[{}][Shadow] stopOn {}.{}{}", null, data.name(), i==0?"~":d.owner, d.name, d.rawDesc);
				break;
			}

			if (i == parents.size()) break;
			d.owner = parents.get(i++);
		}
	}
	/** method/field reference */
	private void mapRef(ClassNode data, CstRef ref, boolean method) {
		MemberDescriptor d = ClassUtil.getInstance().sharedDesc;
		d.read(ref);

		if (method) {
			// FP: init / clinit
			if (d.name.startsWith("<")) return;

			// FP: Stage 2
			String fpName = selfInherited.get(d);

			if (fpName != null) {
				setRefName(data, ref, fpName);
				return;
			}
		} else {
			if (!fieldHasType) d.rawDesc = "";
		}

		FindMap<MemberDescriptor, String> map = method ? methodMap : fieldMap;
		List<String> parents = hierarchy.getOrDefault(d.owner, Collections.emptyList());
		int i = 0;
		while (true) {
			String name = map.get(d);
			if (name != null) {
				setRefName(data, ref, name);

				// fix field inherit bug
				if (i > 0 && !method) ref.clazz(data.cp.getClazz(d.owner));
				break;
			}

			// 是放在这里，因为d.owner一开始是data.name
			if (shadows.contains(d)) {
				LOGGER.log(Level.TRACE, "[{}][Shadow] stopOn {}.{}{}", null, data.name(), i==0?"~":d.owner, d.name, d.rawDesc);
				break;
			}

			if (i == parents.size()) break;
			d.owner = parents.get(i++);
		}
	}

	/**
	 * Step 5: (Optional) Dedicated class name and annotation 'value' name
	 */
	public final void S5_mapClassName(Context ctx) {
		ClassUtil U = ClassUtil.getInstance();
		ClassNode data = ctx.getData();

		mapInnerClass(U, data);
		mapSignature(data, data);
		mapNodeAndAttrAndParam(U, data);
		mapAnnotations(U, data.cp, data);
		mapEnclosingMethod(U, data);

		data.unparsed(); // serialize to cp
		mapConstant(U, data.cp);
		mapClassAndSuper(data);
	}
	/** EnclosingMethod type & name */
	private void mapEnclosingMethod(ClassUtil U, ClassNode data) {
		EnclosingMethod attribute = data.getAttribute(Attribute.EnclosingMethod);
		if (attribute != null && attribute.name != EnclosingMethod.PREDEFINED) {
			MemberDescriptor d = U.sharedDesc;
			d.owner = attribute.owner;
			d.name = attribute.name;
			d.rawDesc = attribute.rawDesc();

			String mapName = methodMap.get(d);
			if (mapName != null) attribute.name = mapName;

			attribute.rawDesc(U.mapMethodParam(classMap, d.rawDesc));
		}
	}
	/** InnerClass type */
	private void mapInnerClass(ClassUtil U, ClassNode data) {
		var classes = data.getInnerClasses();
		CharList sb = IOUtil.getSharedCharBuf();
		for (int j = 0; j < classes.size(); j++) {
			InnerClasses.Item clz = classes.get(j);
			if (clz.parent != null) {
				sb.clear();
				String name = U.mapClassName(classMap, sb.append(clz.parent).append('$').append(clz.name));
				if (name != null) {
					int i = name.lastIndexOf('$');
					if (i <= 0 || i == name.length()-1) {
						LOGGER.log(Level.DEBUG, "[InnerClass]: {}${} => {}", null, clz.parent, clz.name, name);
						clz.name = name;
					} else {
						clz.name = name.substring(i+1);
					}
				}

				name = U.mapClassName(classMap, clz.parent);
				if (name != null) clz.parent = name;
			}

			String name = U.mapClassName(classMap, clz.self);
			if (name != null) clz.self = name;
		}
	}
	/** Annotation type and field key */
	private void mapAnnotations(ClassUtil U, ConstantPool cp, Attributed node) {
		var a = node.getAttribute(cp, "RuntimeVisibleAnnotations");
		if (a != null) mapAnnotations(U, cp, a.getRawData().slice());
		a = node.getAttribute(cp, "RuntimeInvisibleAnnotations");
		if (a != null) mapAnnotations(U, cp, a.getRawData().slice());
	}
	private void mapAnnotations(ClassUtil U, ConstantPool cp, DynByteBuf r) {
		int len = r.readUnsignedShort();
		while (len-- > 0) mapAnnotation(U, cp, r);
	}
	private void mapAnnotation(ClassUtil U, ConstantPool cp, DynByteBuf r) {
		CstUTF owner = cp.resolve(r);
		String newOwner = U.mapFieldType(classMap, owner.str());
		if (newOwner != null) r.setShort(r.rIndex-2, cp.getUtfId(newOwner));

		String owner_name = owner.str().substring(1, owner.str().length()-1);
		int len = r.readUnsignedShort();
		while (len-- > 0) {
			String name = ((CstUTF) cp.resolve(r)).str();

			// assert is annotation class...
			for (Map.Entry<MemberDescriptor, String> entry : methodMap.entrySet()) {
				if (entry.getKey().name.equals(name) && entry.getKey().owner.equals(owner_name)) {
					r.setShort(r.rIndex-2, cp.getUtfId(entry.getValue()));
					break;
				}
			}

			mapAnnotationNode(U, cp, r);
		}
	}
	private void mapAnnotationNode(ClassUtil U, ConstantPool cp, DynByteBuf r) {
		switch (r.readUnsignedByte()) {
			default: r.rIndex += 2; break;
			case AnnVal.ANNOTATION_CLASS: {
				CstUTF owner = cp.resolve(r);
				String newOwner = U.mapFieldType(classMap, owner.str());
				if (newOwner != null) {
					r.setShort(r.rIndex-2, cp.getUtfId(newOwner));
				}
			}
			break;
			case AnnVal.ENUM: {
				CstUTF owner = cp.resolve(r);
				String newOwner = U.mapFieldType(classMap, owner.str());
				if (newOwner != null) r.setShort(r.rIndex-2, cp.getUtfId(newOwner));

				CstUTF enum_name = cp.resolve(r);

				MemberDescriptor fd = U.sharedDesc;
				// old name
				fd.owner = owner.str().substring(1,owner.str().length()-1);
				fd.name = enum_name.str();
				fd.rawDesc = fieldHasType ? owner.str() : "";

				String newFieldName = fieldMap.get(fd);
				if (newFieldName != null) {
					r.setShort(r.rIndex-2, cp.getUtfId(newFieldName));
				}
			}
			break;
			case AnnVal.ANNOTATION: mapAnnotation(U, cp, r); break;
			case ARRAY:
				int len = r.readUnsignedShort();
				while (len-- > 0) mapAnnotationNode(U, cp, r);
				break;
		}
	}
	/** Generic signature type */
	private void mapSignature(ClassNode cn, Attributed node) {
		Signature generic = node.getAttribute(cn, Attribute.SIGNATURE);
		if (generic != null) generic.rename(signatureMapper);
	}
	/** Class name and parent */
	private void mapClassAndSuper(ClassNode data) {
		data.name(data.name());
		data.parent(data.parent());
	}
	/** Method/Field type, its signature, annotation and method parameter type */
	private void mapNodeAndAttrAndParam(ClassUtil U, ClassNode data) {
		String oldCls, newCls;

		List<MemberNode> nodes = Helpers.cast(data.fields);
		for (int i = 0; i < nodes.size(); i++) {
			MemberNode field = nodes.get(i);

			oldCls = field.rawDesc();
			newCls = U.mapFieldType(classMap, oldCls);

			if (newCls != null) field.rawDesc(newCls);

			mapSignature(data, field);
			mapAnnotations(U, data.cp, field);
		}

		nodes = Helpers.cast(data.methods);
		for (int i = 0; i < nodes.size(); i++) {
			MemberNode method = nodes.get(i);

			oldCls = method.rawDesc();
			newCls = U.mapMethodParam(classMap, oldCls);

			if (!oldCls.equals(newCls)) method.rawDesc(newCls);

			mapSignature(data, method);
			mapAnnotations(U, data.cp, method);
		}
	}
	/** Any other: interface, bootstrap method, class ref... */
	private void mapConstant(ClassUtil U, ConstantPool cp) {
		String oldCls, newCls;
		List<Constant> arr = cp.constants();
		for (int i = 0; i < arr.size(); i++) {
			Constant c = arr.get(i);
			switch (c.type()) {
				case Constant.NAME_AND_TYPE: {
					CstNameAndType ref = (CstNameAndType) c;

					oldCls = ref.rawDesc().str();
					newCls = oldCls.startsWith("(")?U.mapMethodParam(classMap, oldCls):U.mapFieldType(classMap, oldCls);

					if (newCls != null && !newCls.equals(oldCls)) ref.rawDesc(cp.getUtf(newCls));
				}
				break;
				case Constant.CLASS: {
					CstClass clazz = (CstClass) c;

					oldCls = clazz.value().str();
					newCls = U.mapClassName(classMap, oldCls);

					if (newCls != null) clazz.setValue(cp.getUtf(newCls));
				}
				break;
				case Constant.METHOD_TYPE: {
					CstMethodType type = (CstMethodType) c;

					oldCls = type.value().str();
					newCls = U.mapMethodParam(classMap, oldCls);

					if (!oldCls.equals(newCls)) type.setValue(cp.getUtf(newCls));
				}
				break;
			}
		}
	}

	public final void S5_1_resetDebugInfo(Context ctx) {
		ClassNode data = ctx.getData();
		StringAttribute sourceFile = data.getAttribute(data, Attribute.SourceFile);
		if (sourceFile != null) {
			String name = data.name();
			int $ = name.indexOf('$');
			sourceFile.value = name.substring(name.lastIndexOf('/')+1, $ < 0 ? name.length() : $).concat(".java");
		}
	}
	// endregion
	// region libraries
	public void loadLibraries(List<?> files) {
		ArrayList<Context> classes = new ArrayList<>();
		MemberDescriptor m = ClassUtil.getInstance().sharedDesc;

		var prevSS = hierarchy;
		var prevSA = shadows;

		hierarchy = libHierarchy;
		shadows = libShadows;

		for (int i = 0; i < files.size(); i++) {
			Object o = files.get(i);
			if (o instanceof File fi) {
				String f = fi.getName().toLowerCase(Locale.ROOT);
				if (!f.startsWith(DONT_LOAD_PREFIX) && (f.endsWith(".zip") || f.endsWith(".jar"))) {
					try (ZipFile archive = new ZipFile(fi)) {
						for (ZipEntry entry : archive.entries()) {
							if (entry.getName().endsWith(".class")) {
								try (InputStream in = archive.getInputStream(entry)) {
									readLibFile(new Context(entry.getName(), in), classes, m);
								} catch (Throwable e) {
									LOGGER.warn(f+"#!"+entry.getName()+" 无法读取", e);
								}
							}
						}
					} catch (IOException e) {
						e.printStackTrace();
					}
				}
			} else {
				List<Context> ctx = Helpers.cast(o);
				for (int j = 0; j < ctx.size(); j++) {
					readLibFile(ctx.get(j), classes, m);
				}
			}
		}

		makeHierarchyMap(libHierarchy, (flag & FLAG_FULL_CLASS_MAP) != 0 ? classMap : null);

		// compute stop anchors
		for (int i = 0; i < classes.size(); i++) S3_mapSelf(classes.get(i), true);

		hierarchy = prevSS;
		shadows = prevSA;
	}
	private void readLibFile(Context ctx, List<Context> classes, MemberDescriptor d) {
		classes.add(ctx);
		S1_parse(ctx);

		ClassNode data = ctx.getData();
		if ((flag & FLAG_FULL_CLASS_MAP) != 0 && !classMap.containsKey(data.name())) return;

		// 更新访问权限
		d.owner = data.name();
		findAndReplace(methodMap, d, data.methods(), true);
		d.rawDesc = "";
		findAndReplace(fieldMap, d, data.fields(), fieldHasType);
	}
	private void findAndReplace(FindMap<MemberDescriptor, String> flags, MemberDescriptor d, List<? extends Member> nodes, boolean par) {
		for (int i = 0; i < nodes.size(); i++) {
			Member n = nodes.get(i);
			d.name = n.name();
			if (par) d.rawDesc = n.rawDesc();

			Map.Entry<MemberDescriptor, String> entry = flags.find(d);
			if (entry != null) entry.getKey().modifier = n.modifier();
		}
	}

	/**
	 * 加载完所有的库之后调用此函数来完成Mapper的准备 <br>
	 * 不调用此方法进行映射的行为是<i>未定义</i>的
	 */
	public Mapper packup() {
		if (classNameChanged()) flag |= MF_RENAME_CLASS;
		else flag &= ~MF_RENAME_CLASS;

		MemberDescriptor m = ClassUtil.getInstance().sharedDesc;

		// check inherit & overloads
		for (MemberDescriptor d : new ArrayList<>(methodMap.keySet())) {
			List<String> parents = libHierarchy.get(d.owner);
			if (parents == null) continue;

			m.name = d.name;
			m.rawDesc = d.rawDesc;

			Map.Entry<MemberDescriptor, String> prev = null, entry;
			for (int i = parents.size()-1; i >= -1; i--) {
				m.owner = i < 0 ? d.owner : parents.get(i);

				entry = methodMap.find(m);
				if (entry != null) {
					if (prev == null) {
						prev = entry;
					} else if (prev.getValue().equals(entry.getValue())) {
						if ((flag&FLAG_FIX_INHERIT) != 0) methodMap.remove(m);
					} else {
						if ((flag&FLAG_FIX_INHERIT) == 0) LOGGER.log(Level.WARN, "[Packup]: 映射继承冲突: {} and {}", null, prev, entry);
						methodMap.remove(m);
					}
				}

				if (libShadows.contains(m) || (m.modifier&ACC_PRIVATE) != 0) prev = null;
			}
		}

		HashSet<MemberDescriptor> unmatched = new HashSet<>();
		for (MemberDescriptor desc : methodMap.keySet())
			if (desc.modifier == MemberDescriptor.FLAG_UNSET)
				unmatched.add(desc);
		for (MemberDescriptor desc : fieldMap.keySet())
			if (desc.modifier == MemberDescriptor.FLAG_UNSET)
				unmatched.add(desc);

		if (!unmatched.isEmpty()) {
			LOGGER.log(Level.WARN, "[Packup]: 缺少元素({}): {}...", null, unmatched.size(), (LOGGER.canLog(Level.TRACE)?unmatched:unmatched.iterator().next()));
			// Stage2如果用到了这些元素会报错
		}

		return this;
	}
	// endregion

	/**
	 * Set reference name
	 */
	static void setRefName(ClassNode data, CstRef ref, String newName) {
		ref.nameAndType(data.cp.getDesc(newName, ref.nameAndType().rawDesc().str()));
	}

	@Deprecated
	public void clear() {
		classMap.clear();
		fieldMap.clear();
		methodMap.clear();
		libHierarchy.clear();
		libShadows.clear();
	}

	public final void setupHierarchy(int size) {
		shadows = new HashSet<>(libShadows);
		hierarchy = new HashMap<>(size);
		selfInherited = new HashMap<>();
	}

	public final void buildHierarchy() {
		Map<String, List<String>> universe = new HashMap<>(libHierarchy);
		for (int i = 0; i < extraStates.size(); i++) {
			State state = extraStates.get(i);
			universe.putAll(state.parents);
			shadows.addAll(state.stopAnchor);
		}
		universe.putAll(hierarchy); // replace lib class
		hierarchy = universe;

		makeHierarchyMap(universe, (flag & (FLAG_FULL_CLASS_MAP|MF_FIX_SUBIMPL)) == FLAG_FULL_CLASS_MAP ? classMap : null);
	}

	public Set<MemberDescriptor> getShadows() { return shadows; }
	public Map<String, List<String>> getHierarchy() { return hierarchy; }
	public final List<State> getSeperatedLibraries() { return extraStates; }
	public ParamNameMapper getParamTypeMapper() { return paramMapper; }

	/**
	 * By slot
	 * Caution: unmapped class name + mapped method name / descriptor
	 */
	public final void setParamMap(Map<MemberDescriptor, List<String>> paramMap) {this.paramMap = paramMap;}

	public final State snapshot() { return snapshot(null); }
	public final State snapshot(State s) {
		if (s == null) s = new State();

		if (hierarchy == null) throw new IllegalStateException("internal state is not MAP_FINISHED");

		s.parents.clear();
		s.parents.putAll(hierarchy);
		s.stopAnchor.clear();
		s.stopAnchor.addAll(shadows);
		s.stopAnchor.removeAll(libShadows);
		s.inheritor.clear();
		s.inheritor.putAll(selfInherited);

		return s;
	}

	public static final class State {
		final HashMap<String, List<String>> parents = new HashMap<>();
		final HashSet<MemberDescriptor> stopAnchor = new HashSet<>(Hasher.identity());
		final HashMap<MemberDescriptor, String> inheritor = new HashMap<>();
	}

	public void debugRelative(String owner, String name) {
		LOGGER.setLevel(Level.DEBUG);
		LOGGER.info("==== 继承 ====");
		LOGGER.info("  {} => {}", owner, classMap.get(owner));
		List<String> parents = hierarchy.getOrDefault(owner, Collections.emptyList());
		for (int i = 0; i < parents.size(); i++) {
			String p1 = classMap.get(parents.get(i));
			LOGGER.info(p1 == null ? "  {}" :"  {} => {}", parents.get(i), p1);
		}
		LOGGER.info("==== 映射表 ====");
		LOGGER.info("F: 终结; D: 直接; I: 继承; S: 无关标记");
		for (Map.Entry<MemberDescriptor, String> entry : methodMap.entrySet()) {
			MemberDescriptor d = entry.getKey();
			if (name != null && !d.name.equals(name)) continue;

			String type;
			String o = d.owner;
			if (o.equals(owner)) type = "D";
			else {
				int i = parents.indexOf(o);
				if (i < 0) continue;
				type = "I";

				d = d.copy();
				while (i-- > 0) {
					d.owner = parents.get(i);
					if (shadows.contains(d)) type = "S";
				}
			}
			if (shadows.contains(d)) type = "F";

			LOGGER.info("  [{}] {} => {}", type, entry.getKey(), entry.getValue());
		}
	}
}