package roj.asm.attr;

import org.intellij.lang.annotations.MagicConstant;
import org.jetbrains.annotations.Nullable;
import roj.asm.Attributed;
import roj.asm.ClassNode;
import roj.asm.Member;
import roj.asm.MethodNode;
import roj.asm.cp.Constant;
import roj.asm.cp.ConstantPool;
import roj.asm.cp.CstNameAndType;
import roj.asm.cp.CstUTF;
import roj.asm.insn.Code;
import roj.asm.type.Signature;
import roj.collect.HashMap;
import roj.collect.IntBiMap;
import roj.text.logging.Logger;
import roj.util.DynByteBuf;
import roj.util.Helpers;
import roj.util.TypedKey;

import java.util.function.BiFunction;

/**
 * @author Roj234
 * @since 2021/5/26 23:26
 */
public abstract class Attribute {
	static final IntBiMap<String> NAMED_ID = new IntBiMap<>();
	static <T> TypedKey<T> register(String name) {
		NAMED_ID.put(NAMED_ID.size(), name);
		return new TypedKey<>(name);
	}

	public static final TypedKey<TypeAnnotations>
		VisibleTypeAnnotations = register("RuntimeVisibleTypeAnnotations"),
		InvisibleTypeAnnotations = register("RuntimeInvisibleTypeAnnotations");
	public static final TypedKey<Annotations>
		VisibleAnnotations = register("RuntimeVisibleAnnotations"),
		InvisibleAnnotations = register("RuntimeInvisibleAnnotations");
	public static final TypedKey<ParameterAnnotations>
		VisibleParameterAnnotations = register("RuntimeVisibleParameterAnnotations"),
		InvisibleParameterAnnotations = register("RuntimeInvisibleParameterAnnotations");
	public static final TypedKey<Signature> SIGNATURE = register("Signature");
	// class
	public static final TypedKey<RecordAttribute> Record = register("Record");
	public static final TypedKey<InnerClasses> InnerClasses = register("InnerClasses");
	public static final TypedKey<ModuleAttribute> Module = register("Module");
	public static final TypedKey<ClassListAttribute> ModulePackages = register("ModulePackages");
	public static final TypedKey<StringAttribute> ModuleMainClass = register("ModuleMainClass");
	public static final TypedKey<StringAttribute> NestHost = register("NestHost");
	public static final TypedKey<ClassListAttribute> PermittedSubclasses = register("PermittedSubclasses");
	public static final TypedKey<ClassListAttribute> NestMembers = register("NestMembers");
	public static final TypedKey<StringAttribute> SourceFile = register("SourceFile");
	public static final TypedKey<BootstrapMethods> BootstrapMethods = register("BootstrapMethods");
	public static final TypedKey<EnclosingMethod> EnclosingMethod = register("EnclosingMethod");
	public static final TypedKey<StringAttribute> ModuleTarget = register("ModuleTarget");
	public static final TypedKey<ModuleHashes> ModuleHashes = register("ModuleHashes");
	// u16 flags
	// public static final TypedKey<StringAttribute> ModuleResolution = register("ModuleResolution");
	// method
	public static final TypedKey<Code> Code = register("Code");
	public static final TypedKey<MethodParameters> MethodParameters = register("MethodParameters");
	public static final TypedKey<ClassListAttribute> Exceptions = register("Exceptions");
	public static final TypedKey<AnnotationDefault> AnnotationDefault = register("AnnotationDefault");
	// field
	public static final TypedKey<ConstantValue> ConstantValue = register("ConstantValue");

	private static final HashMap<String, BiFunction<ConstantPool, DynByteBuf, Attribute>> CUSTOM_ATTRIBUTE = new HashMap<>();
	public static <T extends Attribute> void addCustomAttribute(TypedKey<T> id, BiFunction<ConstantPool, DynByteBuf, T> deserializer) {
		CUSTOM_ATTRIBUTE.put(id.name, Helpers.cast(deserializer));
	}

	public static void parseAll(Attributed node, ClassNode owner, AttributeList list, int origin) {
		for (int i = 0; i < list.size(); i++) {
			Attribute attr = list.get(i);
			if (attr.getClass() == UnparsedAttribute.class && attr.getRawData() != null) {
				DynByteBuf data = attr.getRawData();
				attr = parse(node, owner.cp, owner, attr.name(), data, origin);
				if (attr == null) continue;
				list.set(i, attr);
			}
		}
	}

	@SuppressWarnings("unchecked")
	public static <T extends Attribute> T parseSingle(Attributed node, ClassNode owner, TypedKey<T> type, AttributeList list, int origin) {
		if (list == null) return null;
		Attribute attr = (Attribute) list.getByName(type.name);
		if (attr == null) return null;
		if (attr.getClass() == UnparsedAttribute.class) {
			if (owner == null) return null;
			// FIXME (lava) Thread safe for read-only access (260819-draft: pre-parse required attributes, Signature, etc.)
			//   或者，把这个ByteList用ThreadLocal克隆一下。但还要解决 AttributeList里面更新HashMap创建节点的并发问题
			attr = parse(node, owner.cp, owner, type.name, attr.getRawData(), origin);
			if (attr == null) throw new UnsupportedOperationException("不支持的属性");
			list.add(attr);
		}
		return (T) attr;
	}

	public static Attribute parse(
			Attributed node, ConstantPool cp, @Nullable ClassNode owner,
			String name, DynByteBuf data,
			@MagicConstant(intValues = {
					Signature.CLASS,Signature.FIELD,Signature.METHOD,roj.asm.insn.Code.ATTR_CODE,RecordAttribute.ATTR_RECORD
			}) int origin
	) {
		int len = data.rIndex;
		try {
			switch (name) {
				default:
					var deserializer = CUSTOM_ATTRIBUTE.get(name);
					if (deserializer != null) return deserializer.apply(cp, data);
				break;
				case "RuntimeVisibleTypeAnnotations":
				case "RuntimeInvisibleTypeAnnotations": return new TypeAnnotations(name, data, cp);
				case "RuntimeVisibleAnnotations":
				case "RuntimeInvisibleAnnotations": return new Annotations(name, data, cp);
				case "RuntimeVisibleParameterAnnotations":
				case "RuntimeInvisibleParameterAnnotations": return new ParameterAnnotations(name, data, cp);
				case "Signature":
					Signature parent = owner == node || owner == null ? null : owner.getAttribute(SIGNATURE);
					return Signature.parse(((CstUTF) cp.resolve(data)).str(), origin, parent);
				case "Synthetic": case "Deprecated": break;
				// method only
				case "MethodParameters": limit(origin,Signature.METHOD); return new MethodParameters(data, cp);
				case "Exceptions": limit(origin,Signature.METHOD); return new ClassListAttribute(name, data, cp);
				case "AnnotationDefault": limit(origin,Signature.METHOD); return new AnnotationDefault(data, cp);
				case "Code": limit(origin,Signature.METHOD); return new Code(data, cp, (MethodNode)node);
				// field only
				case "ConstantValue": limit(origin,Signature.FIELD); return new ConstantValue(cp.resolve(data));
				// class only
				case "Record": limit(origin,Signature.CLASS); return new RecordAttribute(data, cp);
				case "InnerClasses": limit(origin,Signature.CLASS); return new InnerClasses(data, cp);
				case "Module": limit(origin,Signature.CLASS); return new ModuleAttribute(data, cp);
				case "ModulePackages":
				case "PermittedSubclasses":
				case "NestMembers": limit(origin,Signature.CLASS); return new ClassListAttribute(name, data, cp);
				case "ModuleMainClass":
				case "NestHost": limit(origin,Signature.CLASS); return new StringAttribute(name, cp.resolveName(data, Constant.CLASS));
				case "ModuleTarget":
				case "SourceFile": limit(origin,Signature.CLASS); return new StringAttribute(name, ((CstUTF) cp.resolve(data)).str());
				case "BootstrapMethods": limit(origin,Signature.CLASS); return new BootstrapMethods(data, cp);
				case "ModuleHash": limit(origin,Signature.CLASS); return new ModuleHashes(data, cp);
				// 匿名类所属的方法
				case "EnclosingMethod": limit(origin,Signature.CLASS); return new EnclosingMethod(cp.resolve(data), (CstNameAndType) cp.resolveOrNull(data));
				case "SourceDebugExtension": break;
			}
		} catch (Throwable e) {
			String str;
			try {
				node.attributes().clear();
				str = node.toString();
			} catch (Throwable ex) {
				str = "<toString() failed>";
				e.addSuppressed(ex);
			}
			data.rIndex = len;
			throw new IllegalStateException("无法读取"+str+"的属性'"+name+"',长度为"+data.readableBytes()+",数据:"+data.dump(), e);
		}
		return null;
	}
	private static void limit(int type, int except) {
		if (type != except) throw new IllegalStateException("意料之外的属性,仅能在"+except+"中出现,却在"+type);
	}

	public static void foundUnknownAttribute(Member node, String name) {
		Logger.FALLBACK.debug("{}.{} 中遇到不支持的属性 {}", node.owner(), node.name(), name);
	}

	public abstract String name();

	public void toByteArray(DynByteBuf w, ConstantPool pool) {
		w.putShort(pool.getUtfId(name())).putInt(0);
		int i = w.wIndex();
		toByteArrayNoHeader(w, pool);
		w.setInt(i-4, w.wIndex()-i);
	}
	public void toByteArrayNoHeader(DynByteBuf w, ConstantPool cp) { w.put(getRawData()); }

	public boolean writeIgnore() { return false; }

	public abstract String toString();

	public DynByteBuf getRawData() { throw new UnsupportedOperationException(getClass().getName()); }
}