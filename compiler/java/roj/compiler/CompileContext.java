package roj.compiler;

import org.jetbrains.annotations.*;
import roj.asm.*;
import roj.asm.annotation.Annotation;
import roj.asm.attr.Annotations;
import roj.asm.attr.Attribute;
import roj.asm.attr.ClassListAttribute;
import roj.asm.attr.InnerClasses;
import roj.asm.cp.*;
import roj.asm.insn.Label;
import roj.asm.type.*;
import roj.collect.ArrayList;
import roj.collect.HashMap;
import roj.collect.HashSet;
import roj.collect.*;
import roj.compiler.api.Compiler;
import roj.compiler.api.MethodDefault;
import roj.compiler.api.Types;
import roj.compiler.api.ValueBased;
import roj.compiler.asm.AnnotationBuilder;
import roj.compiler.asm.MethodWriter;
import roj.compiler.asm.Variable;
import roj.compiler.ast.MethodParser;
import roj.compiler.ast.expr.*;
import roj.compiler.diagnostic.Diagnostic;
import roj.compiler.diagnostic.IText;
import roj.compiler.diagnostic.Kind;
import roj.compiler.diagnostic.TranslatableText;
import roj.compiler.resolve.*;
import roj.compiler.types.CompoundType;
import roj.config.node.ConfigValue;
import roj.text.CharList;
import roj.text.ParseException;
import roj.text.TextUtil;
import roj.util.ArrayCache;
import roj.util.Helpers;
import roj.util.function.Flow;

import java.util.*;
import java.util.function.Consumer;
import java.util.function.Function;

import static roj.compiler.LavaCompiler.*;
import static roj.compiler.LavaTokenizer.assign;
import static roj.compiler.diagnostic.IText.*;

/**
 * CompileContext 解析(Parse/Resolve)环境 线程本地+递归
 * 1. 错误处理
 * 2. 类型转换，解析类型（扩展到全限定名，然后变成ASM数据，同时做权限检查）  (代理TypeCast)
 * 3. 解析符号引用 Class Field Method  （代理TypeResolver和Inferrer）
 * 4. instanceof，getCommonParent 最近共同祖先
 * 5. 访问权限检查和final赋值检查
 */
public class CompileContext {
	public final LavaCompiler compiler;

	public final ExprParser ep;
	public final MethodParser bp;
	public final LavaTokenizer tokenizer;

	public final TypeCast caster;
	public final Inferrer inferrer;

	private ImportList importList;
	public final HashMap<String, ClassNode> importCache = new HashMap<>(), importCacheMethod = new HashMap<>();
	public final HashMap<String, Object[]> importCacheField = new HashMap<>();

	public CompileUnit file;
	public MethodNode method;
	// 功能应该自解释了
	// 后两个：如果没显式调用super就插入默认构造器，如果没调用this就插入GlobalInit
	public boolean inStatic, inConstructor, noCallConstructor, thisConstructor;
	/**
	 * 递归构造器检查
	 */
	private final HashMap<MethodNode, MethodNode> constructorChain = HashMap.withCustomHasher(Hasher.identity());

	/**
	 * stage2.1解析泛型方法和字段的基础类型时不生成警告
	 */
	public boolean reportPseudoType;

	/**
	 * 是否在return语句中
	 * @see Invoke#write(MethodWriter, TypeCast.Cast) Tail Call Elimination 尾递归优化
	 * @see NewArray#resolve(CompileContext) MultiReturn语法
	 */
	public boolean inReturn;
	/**
	 * this的槽位 暂时用不上
	 */
	public final int thisSlot = 0;
	/**
	 * expressionBeforeThisOrSuper()检查
	 * 只有在未使用this类型前才能调用this()或super()
	 * 不要求必须是构造器的第一个语句
	 * 同时用于lambda的自动静态化
	 * * 具有传递性
	 */
	public boolean thisUsed;
	/**
	 * 跳过枚举的构造器调用检测，抽象枚举类的构造器不是private的
	 * @see Invoke#resolve(CompileContext) int check_flag
	 */
	public boolean enumConstructor;
	/**
	 * 全局构造器插入点的segmentId
	 */
	public int globalInitInsertTo;

	/**
	 * lambda方法的序号
	 */
	protected int syntheticMethodId;
	public String lambdaName() {
		var sb = getTmpSb();
		if (method == null) sb.append("field");
		else {
			String name = method.name();
			if (name.startsWith("<")) {
				sb.append("^").append(name, 1, name.length()-1);
			} else {

				sb.append(name);
			}
		}
		return sb.append("^lmd^").append(syntheticMethodId++).toString();
	}
	public String accessorName() {return "access^"+(syntheticMethodId++);}

	/**
	 * lambda用到，阻止预定义ID的变量被直接序列化入SolidBlock
	 */
	public boolean isArgumentDynamic;

	// stage3 constant resolution
	public boolean fieldDFS;

	/**
	 * 当前解析的文件所处的阶段
	 */
	public int currentStage;

	public CompileContext(LavaCompiler ctx) {
		this.compiler = ctx;
		this.tokenizer = ctx.createTokenizer();
		this.ep = new ExprParser(this);
		this.bp = ctx.createMethodParser(this);
		this.variables = bp.getVariables();
		this.caster = new TypeCast(ctx);
		this.inferrer = new Inferrer(this);
	}

	public MethodWriter createMethodWriter(ClassNode file, MethodNode node) {return new MethodWriter(file, node, compiler.hasFeature(Compiler.EMIT_LOCAL_VARIABLES), this);}

	public void clear() {
		this.file = null;
		this.importList = null;
		this.importCache.clear();
		this.importCacheMethod.clear();
		this.importCacheField.clear();
		this.tokenizer.init("");
		this.constructorChain.clear();
	}
	public void setClass(CompileUnit file) {
		this.reportedType.clear();
		this.errorReportIndex = -1;
		this.file = file;
		this.importList = file.getImportList();
		this.importCache.clear();
		this.importCacheMethod.clear();
		this.importCacheField.clear();
		int pos = this.tokenizer.index;
		this.tokenizer.init(file.getCode());
		this.tokenizer.index = pos;
		this.fieldDFS = false;
		this.constructorChain.clear();
		this.compileUnits.clear();

		file.ctx = this;
	}
	public void setupFieldDFS() {
		uninitializedFinalFields = file.uninitializedFinalFields;
		inStatic = true;
		inConstructor = true;
		fieldDFS = true;
	}
	public void setMethod(MethodNode node) {
		file._setSign(node);

		inStatic = (node.modifier&Opcodes.ACC_STATIC) != 0;
		noCallConstructor = inConstructor = node.name().startsWith("<");
		if (inConstructor) {
			if (node.name().equals("<init>")) {
				uninitializedFinalFields = new HashSet<>(Hasher.identity());
				uninitializedFinalFields.addAll(file.uninitializedFinalFields);
			} else {
				uninitializedFinalFields = file.uninitializedFinalFields;
			}
		}
		method = node;

		inReturn = false;
		thisUsed = false;

		syntheticMethodId = 0;
		globalInitInsertTo = 0;

		thisConstructor = false;

		hasError = false;
		errorReportIndex = -1;
	}

	private final List<CompileUnit> compileUnits = new ArrayList<>();
	public void addCompileUnit(CompileUnit unit) {compileUnits.add(unit);}
	public void commitCompileUnits() {
		synchronized (compiler) {
			for (CompileUnit unit : compileUnits) {
				compiler.addCompileUnit(unit);
			}
		}
		compileUnits.clear();
	}

	public IText currentCodeBlockForReport() {return translatable("symbol.type").append(" ").append(literal(file));}

	//region 错误报告

	// 当前MethodNode是否编译失败
	protected boolean hasError;
	public boolean hasError() {return hasError;}

	private IText capturedError;
	private boolean errorCaptureEnabled;

	public void enableErrorCapture() {errorCaptureEnabled = true;}
	public IText getCapturedError() {return capturedError;}
	public void disableErrorCapture() {
		errorCaptureEnabled = false;
		capturedError = null;
	}

	public int errorReportIndex;
	private Set<Object> reportedType = new HashSet<>();

	public final void report(Kind kind, @PropertyKey(resourceBundle = "roj.compiler.messages") String message) {report(kind, message, ArrayCache.OBJECTS);}
	public final void report(Kind kind, @PropertyKey(resourceBundle = "roj.compiler.messages") String message, Object... args) {
		if (errorReportIndex >= 0) report(errorReportIndex, kind, message, args);
		else report(tokenizer.__getlwBegin(), tokenizer.__getlwEnd(), kind, message, args);
	}

	public final void report(Expr pos, Kind kind, @PropertyKey(resourceBundle = "roj.compiler.messages") String message) {report(pos, kind, message, ArrayCache.OBJECTS);}
	public void report(Expr pos, Kind kind, @PropertyKey(resourceBundle = "roj.compiler.messages") String message, Object... args) {
		if (checkArgument(args) || checkCapture(message, args)) return;
		int start = pos.getWordStart();
		int end = pos.getWordEnd();
		if (end == 0) start = end = tokenizer.index;
		hasError |= compiler.report(new Diagnostic(file, kind, start, end, message, translatable(message, args)));
	}
	public void report(Expr pos, Kind kind, IText message) {
		int start = pos.getWordStart();
		int end = pos.getWordEnd();
		if (end == 0) start = end = tokenizer.index;
		hasError |= compiler.report(new Diagnostic(file, kind, start, end, ((TranslatableText) message).getTranslationKey(), message));
	}
	public void report(int start, int end, Kind kind, @PropertyKey(resourceBundle = "roj.compiler.messages") String message, Object... args) {
		if (checkArgument(args) || checkCapture(message, args)) return;
		hasError |= compiler.report(new Diagnostic(file, kind, start, end, message, translatable(message, args)));
	}

	public void report(int pos, Kind kind, @PropertyKey(resourceBundle = "roj.compiler.messages") String message, Object... args) {
		if (checkArgument(args) || checkCapture(message, args)) return;
		hasError |= compiler.report(new Diagnostic(file, kind, pos, pos, message, translatable(message, args)));
	}

	private static boolean checkArgument(Object[] args) {
		for (Object arg : args) {
			if (arg == NaE.UNRESOLVABLE) return true;
		}
		return false;
	}

	private boolean checkCapture(String message, Object[] arguments) {
		if (errorCaptureEnabled) {
			capturedError = translatable(message, arguments);
			return true;
		}
		return false;
	}

	public final void reportNoSuchType(Kind kind, Object owner) {
		if (reportedType.add(owner))
			report(kind, "symbol.noSuchSymbol", translatable("symbol.type"), owner, currentCodeBlockForReport());}
	public final void reportNoSuchType(Expr node, Kind kind, Object owner) {
		if (reportedType.add(owner))
			report(node, kind, "symbol.noSuchSymbol", translatable("symbol.type"), owner, currentCodeBlockForReport());
	}
	//endregion
	// region 访问权限和final字段赋值检查
	@SuppressWarnings("fallthrough")
	public void accessTypeOrReport(ClassNode type) {canAccessType(type, true);}
	public boolean canAccessType(ClassNode type, boolean report) {
		if (type == file) return true;

		int modifier = type.modifier();
		// get actual modifier for inner class
		var innerClass = compiler.getInnerClassInfo(type).get(type.name());
		if (innerClass != null) modifier = innerClass.modifier;

		return checkAccessModifier(modifier, type, null, report ? "symbol.accessDenied" : null, "symbol.type");
	}
	// 这里不会检测某些东西 (override, static has been written等)
	public boolean canAccessSymbol(ClassNode type, Member member, boolean staticEnv, boolean report) {
		String memberType = member instanceof FieldNode ? "symbol.field" : "invoke.method";
		if (staticEnv && (member.modifier()&Opcodes.ACC_STATIC) == 0) {
			if (report) report(Kind.ERROR, "symbol.nonStatic.symbol", type.name(), member.name(), translatable(memberType));
			return false;
		}

		return type == file || checkAccessModifier(member.modifier(), type, member.name(), report ? "symbol.accessDenied" : null, memberType);
	}
	private boolean checkAccessModifier(int flag, ClassNode type, String member, String message, String memberType) {
		String modifier;
		switch ((flag & (Opcodes.ACC_PUBLIC|Opcodes.ACC_PROTECTED|Opcodes.ACC_PRIVATE))) {
			default: throw ResolveException.ofIllegalInput("semantic.resolution.illegalModifier", Integer.toHexString(flag));
			case Opcodes.ACC_PUBLIC: return true;
			case Opcodes.ACC_PROTECTED:
				String testClass = file.name();
				String instClass = type.name();
				if (ClassUtil.arePackagesSame(type.name(), file.name()) || compiler.instanceOf(testClass, instClass)) return true;
				modifier = "protected";
				break;
			case Opcodes.ACC_PRIVATE:
				// 同一个类可以互相访问
				// 条件: a/b仅比较$之前的部分
				// 除了桥接方法, NestHost属性也可以达成该目的
				if (ClassUtil.canAccessPrivate(type.name(), file.name())) return true;
				modifier = "private";
				break;
			case 0:
				if (ClassUtil.arePackagesSame(type.name(), file.name())) return true;
				modifier = "package-private";
				break;
		}
		if (message != null) report(Kind.ERROR, message, translatable(memberType), type.name()+(member!=null?"."+member:""), modifier, file);
		return false;
	}

	private HashSet<FieldNode> uninitializedFinalFields;
	public final void accessFinalField(FieldNode node, boolean write) {
		if (write) {
			if (!uninitializedFinalFields.remove(node)) {
				report(Kind.ERROR, "symbol.field.writeAfterWrite", file.name(), node.name());
			}
		} else {
			if (uninitializedFinalFields.contains(node)) {
				report(Kind.ERROR, "symbol.field.readBeforeWrite", file.name(), node.name());
			}
		}
	}

	/**
	 * 递归构造器检查
	 */
	public void onCallConstructor(MethodNode callee) {
		noCallConstructor = false;
		if (!callee.owner().equals(file.name())) return;

		thisConstructor = true;
		constructorChain.putIfAbsent(method, callee);
		MethodNode node = constructorChain.get(callee);
		while (node != null) {
			if (node == method) {
				report(Kind.ERROR, "lc.recursiveConstructor");
				break;
			}
			node = constructorChain.get(node);
		}
	}
	// endregion
	// region 解析 符号引用 类型表示 类型实例
	@NotNull
	public ComponentList getMethodListOrReport(ClassNode type, String name, Expr callerForDebug) {
		var list = compiler.getMethodList(type, name);
		if (list == ComponentList.NOT_FOUND) {
			int argc = callerForDebug instanceof Invoke a ? a.getArgumentCount() : Integer.MAX_VALUE;
			report(callerForDebug, Kind.ERROR, "symbol.noSuchSymbol",
					translatable(name.equals("<init>") ? "invoke.constructor" : "invoke.method"), name,
					translatable("symbol.type").append(literal(type)).append(reportSimilarMethod(type, name, argc))
			);
		}
		return list;
	}
	private IText reportSimilarMethod(ClassNode type, String method, int argc) {
		var similar = new ArrayList<String>();
		for (var entry : compiler.link(type).getMethods(compiler).entrySet()) {
			if (entry.getKey().startsWith("<")) continue;

			boolean present = Flow.of(entry.getValue().getMethods())
				.filter(node -> canAccessSymbol(resolve(node.owner()), node, false, false))
				.anyMatch(node -> {
					int parSize = node.parameters().size();
					return argc >= parSize - ((node.modifier & Opcodes.ACC_VARARGS) != 0 ? 1 : 0);
				});

			if (present) checkSimilarity(method, entry.getKey(), similar);
		}

		if (similar.isEmpty()) return empty();

		var rest = empty();
		rest.append(TextUtil.join(similar, "\n    "));
		return translatable("symbol.similar", translatable("invoke.method"), rest);
	}
	private IText reportSimilarField(ClassNode type, String field) {
		var similar = new ArrayList<String>();
		for (var entry : compiler.link(type).getFields(compiler).entrySet()) {
			boolean present = entry.getValue().listFields()
				.anyMatch(pair -> canAccessSymbol(resolve(pair.getKey().name()), pair.getValue(), false, false));

			if (present) checkSimilarity(field, entry.getKey(), similar);
		}

		if (similar.isEmpty()) return empty();

		var rest = empty();
		rest.append(TextUtil.join(similar, "\n    "));
		return translatable("symbol.similar", translatable("symbol.field"), rest);
	}

	//Pattern WORD = Pattern.compile("_+|(?>[A-Z])[a-z]+");
	private static void checkSimilarity(String method, String candidate, ArrayList<String> similar) {
		int len = method.length();
		int editThreshold = Math.max(2, (len + 1) / 2);

		// 条件1：检查编辑距离
		if (TextUtil.editDistance(method, candidate) <= editThreshold) {
			similar.add(candidate);
		}

		// 条件2：加权连续匹配相似度（解决换位问题）
		else if (len >= 3) {
			double weightedSimilarity = TextUtil.weightedContiguousSimilarity(method, candidate);
			if (weightedSimilarity > 0.65) similar.add(candidate);
		}
	}

	/**
	 * 将一个全限定名称或短名称解析到其类型实例.
	 * 通过{@link ImportList#resolve(CompileContext, String)}，
	 * 并进行导入限制的检查
	 * @param klass 短名称
	 */
	@Nullable
	public final ClassNode resolve(String klass) {
		ClassNode node = importList.resolve(this, klass);
		if (node != null && hasRestriction()) {
			checkRestriction(node);
		}
		return node;
	}

	public boolean hasRestriction() {return importList.isRestricted();}
	/**
	 * 进行导入限制的完整检查, 防止通过返回值或字段类型引入不允许的类
	 * @param type 类名
	 */
	public void checkRestriction(ClassNode type) {
		if (type instanceof CompileUnit) return;

		FlagSet restriction = importList.getRestriction();
		if (restriction.get(type.name(), 0) == 0) {
			report(Kind.ERROR, "semantic.feature.import.restricted.intermediate", type);
		}
	}
	public boolean checkRestriction(ClassNode owner, Member member) {
		if (owner instanceof CompileUnit) return false;

		String type = owner.name();

		FlagSet restriction = importList.getRestriction();
		int allow = restriction.get(type+"/"+member.name(), -1);
		if (allow < 0) allow = restriction.get(type, 0);
		if (allow != 0) return false;

		report(Kind.ERROR, "semantic.feature.import.restricted.member", type, member instanceof MethodNode ? "invoke.method" : "symbol.field", member.name());
		return true;
	}

	/**
	 * 将(已经resolveType的)类型表示解析到其类型实例，支持数组
	 * @param type 类型
	 */
	@Nullable
	public final ClassNode resolve(IType type) {
		if (type.array() > 0) return compiler.resolveArray(type);
		if (type.kind() == IType.BOUNDED_WILDCARD) return resolveWildcard((CompoundType) type);
		return compiler.resolve(type.owner());
	}

	private ClassNode resolveWildcard(CompoundType compoundType) {
		var traits = compoundType.getTraits();
		if (traits.size() == 1) return compiler.resolve(compoundType.owner());

		String typename = Type.getMethodDescriptor(traits);

		ClassNode resolve = compiler.resolve(typename);
		if (resolve != null) return resolve;

		traits.subList(1, traits.size()).sort((o1, o2) -> o1.owner().compareTo(o2.owner()));
		typename = Type.getMethodDescriptor(traits);

		synchronized (compiler) {
			resolve = compiler.resolve(typename);
			if (resolve != null) return resolve;

			ClassNode virtualNode = new ClassNode();
			virtualNode.name(typename);

			for (int i = 0; i < traits.size(); i++) {
				IType trait = traits.get(i);
				ClassNode type = resolve(trait);
				if ((type.modifier & Opcodes.ACC_INTERFACE) != 0) {
					virtualNode.addInterface(type.name());
				} else {
					if (!"java/lang/Object".equals(virtualNode.parent()))
						throw new AssertionError("遇到多个非接口类型: " + traits);
					assert i == 0;
					virtualNode.parent(type.name());
				}
			}

			compiler.addGeneratedClass(virtualNode);

			// 在add之后改名字，这样只改变display name
			virtualNode.name("*Wildcard<"+TextUtil.join(traits, " & ")+">");
			return virtualNode;
		}
	}

	/**
	 * 解析一个类型表示, 包括将它的名称解析为全限定名称, 对泛型参数作出限制, 实现虚拟泛型等
	 * @return 大部分时间和输入参数相同 有些Magic会被特殊处理
	 */
	public final IType resolveType(IType type) {
		if (type.kind() == IType.SIMPLE_TYPE ? type.rawType().type != Type.OBJECT : type.kind() != IType.PARAMETERIZED_TYPE) return type;

		// 不预先检查全限定名，适配package-restricted模式
		var info = resolve(type.owner());
		if (info == null) {
			reportNoSuchType(Kind.ERROR, type.owner());
			return type;
		}
		type.owner(info.name());

		if (reportPseudoType && info.parent() == null && !info.name().equals("java/lang/Object")) {
			var vb = (ValueBased) info.getAttribute(ValueBased.NAME);
			if (vb != null) {
				report(Kind.WARNING, "pseudoType.cast");
				return vb.exactType;
			} else {
				report(Kind.ERROR, "pseudoType.pseudo");
				return type;
			}
		}

		Signature sign = info.getAttribute(Attribute.SIGNATURE);
		int count = sign == null ? 0 : sign.typeVariables.size();

		int typeFlag = compiler.getTypeFlag(info);

		if (type.array() != 0 && (typeFlag&TF_NOARRAY) != 0) {
			report(Kind.ERROR, "expr.newArray.generic");
		}

		if (type.kind() == IType.PARAMETERIZED_TYPE) {
			ParameterizedType type1 = (ParameterizedType) type;
			List<IType> params = type1.typeParameters;

			if (params.size() != count && (typeFlag & TF_ANYARGC) == 0) {
				if (count == 0) report(Kind.ERROR, "symbol.generic.paramCount.0", type.rawType());
				else if (params.size() != 1 || params.get(0) != Types.anyGeneric) {
					report(Kind.ERROR, "symbol.generic.paramCount", type.rawType(), params.size(), count);
					return type;
				}
			}

			if (sign == null) return type;
			var itr = sign.typeVariables.iterator();

			for (int i = 0; i < params.size(); i++) {
				IType param = resolveType(params.get(i));
				TypeVariableDeclaration declaration = itr.next();

				if ((typeFlag & TF_ANYARGC) != 0) continue;

				// skip if is AnyType (?)
				if (param.kind() <= 2) {
					for (IType bound : declaration) {
						castTo(param, bound, 0);
					}
				}

				if (param instanceof ParameterizedType g && g.isUnboundedWildcard()) {
					params.set(i, Signature.unboundedWildcard());
				} else if (param.isPrimitive() && (typeFlag & TF_PRIMITIVE) == 0) {
					// TODO generate template class
					// 这里是实现的关键……不再处理什么基本类型泛型的保存读取这个那个
					// 从这里，把每一次带有基本类型
					// roj.collect.MyHashMap<int, Object>
					// 都换成类名为 ;PGEN;I;L;roj.collect.MyHashMap
					// 泛型为 <Object>

					// PGEN: Primitive Generic (Generator)
					// I: int
					// L: object

					//return resolvePrimitiveGeneric(type);

					// 吗？
					// bushi(
					// 为此，我们还需要生成一个;PGEN;int，作为T的内部类型
					// 它不继承Object，这样就不会被作为普通对象处理
					// 它仅提供hashCode和equals通过Evaluable接口，可以把他们inline成对应的比较
					// 除此之外，传递给BlockParser的方法参数也需要是PGEN类型
					// 真实的方法参数、fieldList类型、以及BlockParser生成变量的类型，依然是基本类型
					report(Kind.ERROR, "当前版本暂时未实现基本类型泛型");
				}
			}

			//TODO not tested yet
			// HashMap<K,V>.Entry<Z>
			// HashMap.Entry<K,V>
			// HashMap<K,V>.Entry.SomeClass<Z>
			// class G1<T> { class G2 { class G3<T2> {} } }
			GenericSub x = type1.sub;
			while (x != null) {
				var ic = compiler.getInnerClassInfo(info).get(x.owner);
				if (ic == null || (info = compiler.resolve(ic.self)) == null) {
					report(Kind.ERROR, "symbol.noSuchSymbol", "symbol.type", x.owner, "[symbol.type,\""+type1+"\"]");
					break;
				}

				if ((ic.modifier&Opcodes.ACC_STATIC) != 0) {
					report(Kind.ERROR, "type.staticGenericSub", type1, ic.name);
				}

				sign = info.getAttribute(Attribute.SIGNATURE);
				count = sign == null ? 0 : sign.typeVariables.size();

				if (x.typeParameters.size() != count) {
					if (count == 0) report(Kind.ERROR, "symbol.generic.paramCount.0", ic.self);
					else report(Kind.ERROR, "symbol.generic.paramCount", ic.self, x.typeParameters.size(), count);
				}

				x = x.sub;
			}

			// 250819 This is MAGIC!!!
			if (type.owner().equals("roj/compiler/api/Union")) {
				List<IType> children = type1.typeParameters;
				for (int i = 0; i < children.size(); i++) {
					var child = compiler.getHierarchyList(resolve(children.get(i)));

					for (int j = i+1; j < children.size(); j++) {
						if (child.containsKey(children.get(j).owner())) {
							report(Kind.ERROR, "block.switch.collisionType", children.get(i), children.get(j));
							break;
						}
					}
				}
				return CompoundType.union(type1.typeParameters);
			}
		} else if (count > 0) {
			report((typeFlag & TF_NORAW) != 0 ? Kind.ERROR : Kind.WARNING, "symbol.generic.rawTypes", type);
		}
		return type;
	}
	//endregion
	//region DotGet字符串解析
	public static final IText NO_ERROR = empty();

	private ClassNode frStart;
	private final ArrayList<FieldNode> frChains = new ArrayList<>();
	private IType frFinalType;
	private int frClassPrefix;

	/**
	 * 将a/b/c格式的字符串, 例如 {@code net/minecraft/client/Minecraft/fontRender/FONT_HEIGHT} 解析为类名与字段访问的组合.
	 * 注意：并非完全符合JLS，但是正常代码几乎不会遇到结果不同
	 * @param allowClassExpr 允许仅解析到类而不包含字段，比如 java/lang/String
	 * @return 错误码,返回null时成功,返回""时为classExpr
	 * @see MemberAccess#resolveEx(CompileContext, Consumer, String)
	 */
	@Nullable
	public final IText resolveStaticClassOrField(CharList desc, boolean allowClassExpr) {
		IText error = NO_ERROR;
		frClassPrefix = 0;

		int slash = desc.indexOf("/");
		if (slash >= 0) {
			// we have already known (from caller) desc cannot be
			// X local variable
			// X field
			// X static import

			// checking short (imported) name + field chain
			ClassNode type = resolve(desc.substring(0, slash++));
			if (type != null) {
				error = fcResolveInnerClass(desc, type, slash, error, allowClassExpr);
				if (error == null || error == NO_ERROR) return error;
			}

			int depth = 0;
			int length = desc.length();
			// full qualified name (with inner class resolving) + field chain
			for(;;) {
				// try to find inner class
				slash = desc.indexOf("/", slash);
				if (slash < 0) break;

				frClassPrefix = ++depth;
				desc.setLength(slash++);

				ClassNode owner = compiler.resolve(desc);
				if (owner == null) continue;

				desc.setLength(length);
				error = fcResolveInnerClass(desc, owner, slash, error, allowClassExpr);
				if (error == null || error == NO_ERROR) return error;

				// to follow JLS, this loop should break once owner != null, but WHY?
				// continue, or failure. I choose continue to fail later
			}
		} else {
			ClassNode type = resolve(desc.toString());
			if (type != null) {
				if (allowClassExpr) {
					frStart = type;
					frChains.clear();
					frFinalType = null;
					return NO_ERROR;
				}

				// 期待表达式，而不是类，Example: println(Object)
				return translatable("symbol.expression", desc);
			}
		}

		//noinspection StringEquality
		if (error == NO_ERROR)
			return translatable("symbol.noSuchSymbol", translatable("symbol.field"), desc, currentCodeBlockForReport());
		return error;
	}

	private IText fcResolveInnerClass(CharList desc, ClassNode owner, int fieldNamePos, IText firstError, boolean allowClassExpr) {
		while (true) {
			// capture last error to tmpErrorMsg
			enableErrorCapture();
			if (!canAccessType(owner, true)) {
				if (firstError == NO_ERROR)
					firstError = getCapturedError();
			}
			disableErrorCapture();

			// priority field than InnerClass
			IText error = resolveFieldChain(owner, null, desc, fieldNamePos);
			if (error == null) return null;

			if (firstError == NO_ERROR)
				firstError = error;

			InnerClasses.Item innerClass = compiler.getInnerClassInfo(owner).get("!"+desc.substring(fieldNamePos));
			if (innerClass == null) break;
			owner = compiler.resolve(innerClass.self);
			if (owner == null) {
				reportNoSuchType(Kind.WARNING, innerClass.self);
				return null;
			}

			frClassPrefix++;
			fieldNamePos = desc.indexOf("/", fieldNamePos);
			if (fieldNamePos < 0) {
				if (allowClassExpr) {
					frStart = owner;
					frChains.clear();
					frFinalType = null;
					return NO_ERROR;
				}
				if (firstError == NO_ERROR)
					firstError = translatable("symbol.expression", desc);
			}
		}

		return firstError;
	}

	/**
	 * 将a/b/c格式的字符串解析为字段访问的组合.
	 * 在{@link #resolveStaticClassOrField(CharList, boolean) 这个}函数中可以找到更多信息.
	 * 简单来说，上一个函数用于MemberAccess排除了变量、当前类字段、外部类字段和静态导入之后的兜底方法，而这个函数用于上述任意情况符合时。
	 * 支持泛型、数组以及某些边界情况.
	 * @param owner 起始类型
	 * @param fieldType 起始类型的类型变量，如果没有ParameterizedType可以不提供
	 * @param desc a/b/c格式的字段访问字符串
	 */
	public final IText resolveFieldChain(ClassNode owner, @Nullable IType fieldType, CharList desc) { return resolveFieldChain(owner, fieldType, desc, 0); }

	private IText resolveFieldChain(ClassNode owner, IType fieldType, CharList desc, int fieldNamePos) {
		frStart = null;
		List<FieldNode> result = frChains; result.clear();

		int i = desc.indexOf("/", fieldNamePos);
		while (true) {
			String name = desc.substring(fieldNamePos, i < 0 ? desc.length() : i);

			FieldNode field;
			block: {
				var fields = compiler.getFieldList(owner, name);
				if (fields != ComponentList.NOT_FOUND) {
					var fr = fields.findField(this, 0);
					if (fr.error != null) return fr.error;

					owner = fr.owner;
					field = fr.field;
					if (frStart == null)
						frStart = owner;
					break block;
				}
				return translatable("symbol.noSuchSymbol", translatable("symbol.field"), name, IText.empty().append(translatable("symbol.type")).append(" ").append(literal(owner))).append(reportSimilarField(owner, name));
			}

			Signature fSign = field.getAttribute(owner, Attribute.SIGNATURE);
			if (fSign != null) {
				if (fieldType instanceof CompoundType wt) fieldType = wt.getBound();

				Signature cSign;
				Map<TypeVariableDeclaration, IType> substitution =
						fieldType instanceof ParameterizedType generic && (cSign = owner.getAttribute(Attribute.SIGNATURE)) != null
						? Inferrer.createSubstitutionMap(cSign.typeVariables, generic.typeParameters)
						: Collections.emptyMap();

				fieldType = Inferrer.substituteTypeVariables(fSign.values.get(0), substitution);
			} else {
				fieldType = field.fieldType();
			}
			result.add(field);

			if (i < 0) {
				this.frFinalType = fieldType;
				return null;
			}
			fieldNamePos = i+1;
			i = desc.indexOf("/", fieldNamePos);

			Type type = field.fieldType();
			if (type.isPrimitive()) {
				if (i < 0) {
					this.frFinalType = fieldType;
					return null;
				}
				// 不能解引用基本类型
				return translatable("symbol.derefPrimitive", type);
			}

			owner = resolve(type);
			if (owner == null) return translatable("symbol.noSuchClass", type);
		}
	}

	public final ClassNode getFrStart() {return frStart;}
	public final ArrayList<FieldNode> getFrChains() {return frChains;}
	public final IType getFrFinalType() {return frFinalType;}
	// optional chaining offset
	public final int getFrClassPrefix() {return frClassPrefix;}
	//endregion
	//region [可重载] 静态导入 内部类
	public static final class Import {
		public ClassNode owner;
		public String name;
		public Object parent;
		public boolean isStatic;

		public static Import replace(@NotNull Expr node) {return new Import(null, null, Objects.requireNonNull(node));}
		public static Import staticCall(@NotNull ClassNode owner, @NotNull String name) {return new Import(Objects.requireNonNull(owner), Objects.requireNonNull(name), null);}
		public static Import virtualCall(@NotNull ClassNode owner, @NotNull String name, @Nullable Expr prev) {return new Import(Objects.requireNonNull(owner), Objects.requireNonNull(name), prev);}
		public static Import constructor(@NotNull ClassNode owner, @NotNull String name, @Nullable Expr prev) {return new Import(Objects.requireNonNull(owner), "<init>", Type.klass(owner.name()));}

		public Import(ClassNode owner, String name, Object parent) {
			this.owner = owner;
			this.name = name;
			this.parent = parent;
		}

		public Import(ClassNode owner, String name, boolean isStatic) {
			this.owner = owner;
			this.name = name;
			this.isStatic = isStatic;
		}

		public Expr parent() {return (Expr) parent;}

		public Import setName(ClassNode owner, String name, boolean isStatic) {
			this.owner = owner;
			this.name = name;
			this.isStatic = isStatic;
			return this;
		}
	}

	// 动态导入对象
	@Nullable
	public Function<String, Import> dynamicMethodImport, dynamicFieldImport;

	// 这个对象在递归解析中继承
	ArrayList<NestContext> enclosing = new ArrayList<>();

	/**
	 * 实用函数，获取导入一个类中所有字段的DynamicImport
	 * @param info 类型实例
	 * @param ref 如果不为空，那么用它作为基础导入非静态字段
	 * @param prev 之前的动态导入对象
	 */
	@NotNull
	public final Function<String, Import> getFieldDFI(ClassNode info, Variable ref, Function<String, Import> prev) {
		return name -> {
			var cl = compiler.getFieldList(info, name);
			if (cl != ComponentList.NOT_FOUND) {
				FieldResult result = cl.findField(this, ref == null ? ComponentList.IN_STATIC : 0);
				if (result.error == null) return Import.virtualCall(info, result.field.name(), ref == null ? null : new LocalVariable(ref));
			}

			return prev == null ? null : prev.apply(name);
		};
	}

	public String firstError;
	private Object[] firstErrorArgs;
	public void clearFirstError() {
		firstError = null;
		firstErrorArgs = null;
	}
	public void setImportError(String code, Object... args) {
		if (firstError != null) return;
		firstError = code;
		firstErrorArgs = args;
	}
	public void reportFirstError(Expr position) {
		report(position, Kind.ERROR, firstError, firstErrorArgs);
		clearFirstError();
	}

	/**
	 * 处理字段的静态导入
	 * @param name 导入名称
	 * @return '导入'对象
	 */
	@Nullable
	public Import tryImportField(String name) {
		clearFirstError();

		if (dynamicFieldImport != null) {
			Import result = dynamicFieldImport.apply(name);
			if (result != null) return result;
		}

		var nestHost = enclosing;
		for (int i = nestHost.size()-1; i >= 0; i--) {
			Import result = nestHost.get(i).resolveField(this, name);
			if (result != null) {
				for (int j = i+1; j < nestHost.size(); j++) {
					if (result.isStatic) break;
					result = nestHost.get(j).transferInto(this, result, name);
				}
				return result;
			}
		}

		frChains.clear();
		return importList.resolveField(this, name, frChains);
	}

	/**
	 * 处理方法的静态导入
	 * @param name 导入名称
	 * @param args 参数，子类的重载可能用到
	 * @return '导入'对象
	 */
	@Nullable
	public Import tryImportMethod(String name, List<Expr> args) {
		clearFirstError();

		if (dynamicMethodImport != null) {
			Import result = dynamicMethodImport.apply(name);
			if (result != null) return result;
		}

		var nestHost = enclosing;
		for (int i = nestHost.size()-1; i >= 0; i--) {
			Import result = nestHost.get(i).resolveMethod(this, name, args);
			if (result != null) {
				for (int j = i+1; j < nestHost.size(); j++) {
					if (result.isStatic) break;
					result = nestHost.get(j).transferInto(this, result, name);
				}
				return result;
			}
		}

		return importList.resolveMethod(this, name);
	}
	// endregion
	//region BlockParser API
	/**
	 * 添加方法抛出的异常, 并报告错误
	 * @param type 异常类型
	 */
	public final void addException(IType type) {
		if (caster.checkCast(type, Types.RUNTIME_EXCEPTION).type >= 0) return;
		if (caster.checkCast(type, Types.ERROR).type >= 0) return;
		if (bp.addException(type)) return;
		if (compiler.hasFeature(Compiler.OMIT_CHECKED_EXCEPTION)) return;

		var exceptions = (ClassListAttribute) method.getAttribute("Exceptions");
		if (exceptions != null) {
			if (type.kind() == 0) {
				ClassNode info = compiler.resolve(type.owner());
				var parents = compiler.getHierarchyList(info);
				for (String s : exceptions.value) {
					if (parents.containsKey(s)) return;
				}
			} else {
				for (String s : exceptions.value) {
					if (caster.checkCast(type, Type.klass(s)).type >= 0) return;
				}
			}
		}
		report(Kind.ERROR, "lc.unReportedException", type);
	}

	public final Map<String, Variable> variables;
	public final Variable getVariable(String name) {return variables.get(name);}
	public final void loadVar(Variable v) {bp.loadVar(v);}
	public final void storeVar(Variable v) {bp.storeVar(v);}
	public final void assignVar(Variable v, Object constant) {bp.assignVar(v, constant);}
	public final Variable createTempVariable(IType type) {return bp.tempVar(type);}
	//endregion
	//region 类型转换和推断
	public MethodResult inferGeneric(IType typeInst, MethodNode method) {
		List<IType> resolvedGeneric = compiler.inferGeneric(typeInst, method.owner());
		return inferrer.getSubstitutedParameters(compiler.resolve(method.owner()), method,
				resolvedGeneric == null ? Type.klass(method.owner()) : new ParameterizedType(method.owner(), resolvedGeneric));
	}
	/**
	 * 执行类型转换语义检查并生成对应的中间表示（IR）
	 *
	 * <p>该方法功能如下：
	 * <ul>
	 *   <li>当转换类型等级 {@code cast.type} 低于 {@code lowest_limit} 时，触发编译错误</li>
	 *   <li>支持自动装箱/拆箱、数值精度转换等隐式转换规则</li>
	 *   <li>若涉及泛型类型，会进行类型擦除和通配符捕获检查（如 {@code List<String> → List<? extends CharSequence>}）</li>
	 * </ul>
	 *
	 * <p><b>转换类型等级</b>（完整定义见{@link TypeCast}）：
	 * <table>
	 * <tr><th>类型</th>        <th>常量</th>               <th>值</th> <th>语义</th></tr>
	 * <tr><td rowspan="4">成功</td>
	 *     <td>{@link TypeCast#UPCAST}</td>         <td>0</td> <td>类/接口直接向上转型</td></tr>
	 * <tr><td>{@link TypeCast#NUMBER_UPCAST}</td> <td>1</td> <td>数值宽化（如 int → long）</td></tr>
	 * <tr><td>{@link TypeCast#UNBOXING}</td>      <td>2</td> <td>拆箱操作（如 Integer → int）</td></tr>
	 * <tr><td>{@link TypeCast#BOXING}</td>        <td>3</td> <td>装箱操作（如 int → Integer）</td></tr>
	 * <tr><td rowspan="3">可恢复错误</td>
	 *     <td>{@link TypeCast#IMPLICIT}</td> <td>-1</td> <td>数值窄化（但是在JVM里都是int的情况）（如 int → char）</td></tr>
	 * <tr><td>{@link TypeCast#LOSSY}</td><td>-2</td><td>数值窄化（如 double → float）</td></tr>
	 * <tr><td>{@link TypeCast#DOWNCAST}</td>     <td>-3</td> <td>向下转型（如 Collection → ArrayList）</td></tr>
	 * <tr><td>致命错误</td>
	 *     <td>{@link TypeCast#TO_PRIMITIVE}等</td>    <td>≤-4</td><td>不可恢复的类型错误（如 String → int）</td></tr>
	 * </table>
	 *
	 * <p><b>转换代价（distance）规则 *主要用于{@link Inferrer}的方法重载优先级计算</b>：
	 * <ul>
	 *   <li>仅在成功转换时有效（type ≥ 0）</li>
	 *   <li>计算方式：继承链层级差 + 装箱/拆箱次数 + 数值精度转换次数</li>
	 *   <li>示例：{@code byte → Integer} 转换代价为 2（1次装箱 + 1次数值宽化）</li>
	 * </ul>
	 * <b>本方法的返回值移除了Identity类型的转换代价以降低GC压力</b>
	 *
	 * <p><b>示例</b>：
	 * <pre>{@code
	 * // 自动装箱+数值转换
	 * castTo(byte, Integer) → BOXING(distance=2)
	 * // 向上转型+泛型
	 * castTo(List<String>, Collection<Object>) → UPCAST(distance=1)
	 * // 注意这里没有计算泛型内部参数的distance，因为不允许这种重载所以没必要
	 * // 好像给我加锅了，Stage3那边挺麻烦的？
	 *
	 * // 强制向下转型
	 * castTo(Object, String) → E_DOWNCAST
	 * // 强制数字窄化转换
	 * castTo(long, int) → E_NUMBER_DOWNCAST
	 * // 源类型小于等于int，实际类型小于源类型
	 * castTo(int, byte) → E_EXPLICIT_CAST
	 * // 致命错误
	 * castTo(String, int) → E_OBJ2INT
	 * }</pre>
	 *
	 * @param lowest_limit 允许的最低转换等级
	 *                    <ul>
	 *                      <li>{@link TypeCast#IMPOSSIBLE}：允许任何转换，即便不合法</li>
	 *                      <li>{@link TypeCast#DOWNCAST}：允许生成可行的转换</li>
	 *                      <li>{@link TypeCast#UPCAST}：仅允许向上转换</li>
	 *                    </ul>
	 * @return 转换描述对象，包含：
	 * <ul>
	 *   <li>{@link TypeCast.Cast#type}：转换类型代码</li>
	 *   <li>{@link TypeCast.Cast#distance}：转换代价（越小越优先）</li>
	 *   <li>{@link TypeCast.Cast#write(roj.asm.insn.CodeWriter)}：生成字节码的方法</li>
	 *   <li>{@link TypeCast.Cast#getTarget()}：获取泛型目标类型（用于类型推断）</li>
	 * </ul>
	 *
	 * @see TypeCast.Cast 查看转换描述对象的完整结构
	 */
	public final TypeCast.Cast castTo(@NotNull IType from, @NotNull IType to, @Range(from = -8, to = 0) int lowest_limit) {
		var cast = caster.checkCast(from, to);
		if (cast.type < lowest_limit) report(Kind.ERROR, "typeCast.error."+cast.type, from, to);
		return cast.intern();
	}
	//endregion

	// Cast | Expr
	public Object autoCast(Expr expr, IType toType) {
		var rType = expr.type();

		var allowImplicitCast = expr.isConstant() && toType.getActualType() == Type.CHAR;
		var cast = caster.checkCast(rType, toType).intern();
		if (cast.type < 0) {
			if (allowImplicitCast || !rType.equals(rType = expr.minType())) {
				cast = castTo(rType, toType, allowImplicitCast ? TypeCast.IMPLICIT : 0);
			} else {
				var override = getOperatorOverride(expr, toType, assign);
				if (override != null) return override;

				if (cast.type > TypeCast.DOWNCAST && expr.hasFeature(Expr.Feature.ALLOW_IMPLICIT_CAST))
					return cast;
				report(Kind.ERROR, "typeCast.error."+cast.type, rType, toType);
			}
		}

		if (allowImplicitCast && cast.type == TypeCast.IMPLICIT) {
			var number = ((ConfigValue)expr.constVal()).asInt();
			if (number < 0 || number > 65535)
				report(Kind.ERROR, "typeCast.error.-2", rType, toType);
		}

		return cast;
	}

	/**
	 * 统一函数：对常量进行范围内的自动窄化转换
	 * @see roj.compiler.ast.expr.Literal#write(MethodWriter, TypeCast.Cast)
	 */
	public IType writeCast(MethodWriter cw, Expr expr, IType toType) {
		var result = autoCast(expr, toType);

		if (result instanceof Expr override) {
			override.write(cw);
			return override.type();
		}

		var cast = (TypeCast.Cast) result;
		if (cast.type >= TypeCast.IMPLICIT) {
			expr.write(cw, cast);
		}

		return toType;
	}

	public IType writeVarCast(MethodWriter cw, Expr expr) {
		var type = expr.type();
		var cast = TypeCast.Cast.IDENTITY;

		if (type.kind() == IType.CAPTURED_WILDCARD) {
			var newType = ((CompoundType) type).getBound();
			// FIXME 在这里需要进行类型擦除吗，还是仅仅这样checkcast
			cast = caster.checkCast(type, newType);
		}

		// 将菱形泛型，例如 new ArrayList<>()，擦除到边界
		if (type instanceof ParameterizedType g && g.typeParameters.size() == 1 && g.typeParameters.get(0) == Types.anyGeneric) {
			ClassNode info = compiler.resolve(type.owner());
			g.typeParameters = info.getAttribute(Attribute.SIGNATURE).getBounds();
		}

		expr.write(cw, cast);
		return type;
	}

	// region 辅助函数，允许但通常无需重载
	/**
	 * 获取ASM节点的注解
	 * @param type 类型
	 * @param node 节点，可能等于类型
	 * @param annotation 注解类型
	 * @param rt 是否为运行时可见注解
	 * @return 注解对象
	 */
	public Annotation getAnnotation(ClassNode type, Attributed node, String annotation, boolean rt) {
		return Annotation.find(Annotations.getAnnotations(type, node, rt), annotation);
	}

	private static final IntMap<String> EMPTY = new IntMap<>(0);
	static {Attribute.addCustomAttribute(MethodDefault.ID, MethodDefault::new);}
	/**
	 * 获取方法的默认参数索引表
	 *
	 * <p>映射参数位置到默认值表达式，用于支持如下语法：
	 * <pre>{@code
	 * void foo(int a, String b = "def") {}
	 * foo(1); // 使用b的默认值
	 * }</pre>
	 *
	 * @param klass  方法所属类
	 * @param method 目标方法
	 * @return 参数默认值索引表（参数位置 → 序列化表达式）
	 * @see #parseDefaultArgument(String) 反序列化方法
	 */
	public IntMap<String> getDefaultArguments(ClassNode klass, MethodNode method) {
		MethodDefault attr = method.getAttribute(klass, MethodDefault.ID);
		return attr != null ? attr.defaultValue : EMPTY;
	}
	/**
	 * 反序列化参数默认值
	 * @param defVal 序列化的ExprNode
	 * @return 反序列化结果，失败返回null
	 */
	@Nullable
	public Expr parseDefaultArgument(@Nullable String defVal) {
		if (defVal != null) {
			var tmpPrev = tmpList;
			tmpList = new ArrayList<>();
			try {
				return ExprParser.deserialize(defVal).resolve(this);
			} catch (ParseException | ResolveException e) {
				e.printStackTrace();
			} finally {
				tmpList = tmpPrev;
			}
		}
		return null;
	}

	public static final int UNARY_PRE = 0, UNARY_POST = 65536;
	/**
	 * 操作符重载扩展点
	 *
	 * <p><b>重载优先级</b>：
	 * <ol>
	 *   <li>内置运算符语义（如数值运算）</li>
	 *   <li>用户重载实现（通过override此方法）</li>
	 *   <li>错误处理（类型不匹配）</li>
	 * </ol>
	 * 注意：重载是次低优先级，仅高于报错，无法覆盖默认行为！
	 *
	 * @param e1        左操作数表达式
	 * @param e2        右操作数表达式（一元运算符时为null）
	 * @param operator  运算符标识（一元和二元运算符列表见{@link LavaTokenizer}）
	 *                  <p>特例
	 *                  <ul>
	 *                    <li>{@link #UNARY_PRE 一元运算符}：前缀运算 ++a</li>
	 *                    <li>{@link #UNARY_POST} + 一元运算符：后缀运算 a++</li>
	 *                    <li>{@link LavaTokenizer#lBracket}：数组下标访问 a[b]</li>
	 *                    <li>{@link LavaTokenizer#lParen}：类型转换 (Type) expr</li>
	 *                    <li>{@link LavaTokenizer#rParen}：变量赋值 a = b</li>
	 *                  </ul>
	 * @return 自定义的表达式树，返回null表示报错
	 */
	@Nullable
	public Expr getOperatorOverride(@NotNull Expr e1, @Nullable Object e2, int operator) {return compiler.getOperatorOverride(this, e1, e2, operator);}

	/**
	 * 基本类型函数扩展点
	 *
	 * <p>用于为基本类型添加伪方法，例如：
	 * <pre>{@code
	 * getPrimitiveMethod(int.class, expr, args) → Integer#toHexString
	 * }</pre>
	 * 就可以使用{@code 4 .toHexString()}了
	 * 数字字面量后的一个空格是必须的，为了防止Tokenizer把小数点当成浮点数的一部分。
	 *
	 * @param type    基本类型（如int.class）
	 * @return 包含一些静态方法的类，这些方法的第一个参数必须是该基本类型，返回null表示报错
	 * @implNote 默认实现把基本类型包装类中的所有静态函数返回了
	 */
	@Nullable
	public ClassNode getPrimitiveMethod(IType type) {
		assert type.isPrimitive();
		return compiler.resolve(Type.getWrapper(type).owner());
	}

	/**
	 * 常量传播扩展点
	 *
	 * <p>在编译期解析常量字段值，用于优化：
	 * <pre>{@code
	 * public static final int MAX = 100;
	 * int a = MAX; → 直接替换为100
	 * }</pre>
	 *
	 * @param klass      字段所属类
	 * @param field      目标字段
	 * @param fieldType  字段的类型（包含泛型信息）
	 * @return 常量表达式。
	 */
	@Nullable
	public Expr getConstantValue(ClassNode klass, FieldNode field, IType fieldType) {
		if (fieldDFS && klass != file && klass instanceof CompileUnit cu) {
			ArrayList<NestContext> bak = enclosing;
			try {
				push();
				next.enclosing = new ArrayList<>();
				cu.S3_DFSField();
			} catch (ParseException e) {
				Helpers.athrow(e);
			} finally {
				next.enclosing = bak;
				pop();
			}
		}

		var cv = field.getAttribute(klass, Attribute.ConstantValue);
		if (cv == null) return null;

		var c = switch (cv.c.type()) {
			case Constant.INT -> ConfigValue.valueOf(((CstInt) cv.c).value);
			case Constant.FLOAT -> ConfigValue.valueOf(((CstFloat) cv.c).value);
			case Constant.LONG -> ConfigValue.valueOf(((CstLong) cv.c).value);
			case Constant.DOUBLE -> ConfigValue.valueOf(((CstDouble) cv.c).value);
			case Constant.CLASS -> cv.c;
			case Constant.STRING -> cv.c.getEasyCompareValue();
			default -> throw new IllegalArgumentException("Illegal ConstantValue "+cv.c);
		};
		return Expr.constant(fieldType, c);
	}
	// endregion
	//region 递归状态管理
	private CompileContext prev, next;

	private static final ThreadLocal<CompileContext> LOCAL_CONTEXT = new ThreadLocal<>();

	public static void set(@Nullable CompileContext ctx) {
		if (ctx != null) ctx.next = null;
		LOCAL_CONTEXT.set(ctx);
	}
	public static void remove() {
		var ctx = LOCAL_CONTEXT.get();
		LOCAL_CONTEXT.remove();
		if (ctx != null) ctx.compiler.releaseContext(ctx);
	}

	public static CompileContext get() {return LOCAL_CONTEXT.get();}

	public static CompileContext push() {
		var ctx = LOCAL_CONTEXT.get();
		CompileContext next = ctx.next;
		if (next == null) {
			ctx.next = next = ctx.compiler.createContext();
			next.enclosing = ctx.enclosing;
			next.prev = ctx;
		}

		LOCAL_CONTEXT.set(next);
		return next;
	}
	public static void pop() {set(Objects.requireNonNull(get().prev, "stack is empty"));}

	// TODO 暴露一个setupGlobalVariable之类的API来实现备份&恢复这些值
	static {
		// for FrameVisitor
		ClassUtil.currentResolver = () -> {
			CompileContext ctx = CompileContext.get();
			return ctx == null ? null : ctx.compiler;
		};
	}

	public void pushNestContext(NestContext context) {if (enclosing.size() > 10)report(Kind.FEATURE, "lc.nestTooDeep");enclosing.add(context);}
	public void popNestContext() {enclosing.pop().onPop();}

	@UnmodifiableView public ArrayList<NestContext> enclosingContext() {return enclosing;}
	public int pushEnclosingContext() {return enclosing.size();}
	public void popEnclosingContext(int backup) {enclosing.removeRange(backup, enclosing.size());}
	//endregion
	// region 缓存
	// GenericDecl, Invoke临时, 参数名称
	public ArrayList<String> tmpList = new ArrayList<>();
	// 暂存Switch的标签
	public final ArrayList<String> tmpList2 = new ArrayList<>();
	// 注解暂存
	public final ArrayList<AnnotationBuilder> tmpAnnotations = new ArrayList<>();
	// S2实现检查临时, 注解检查临时, 模块重复检查临时
	public final HashSet<String> tmpSet = new HashSet<>();
	public HashSet<String> getTmpSet() {tmpSet.clear();return tmpSet;}

	// 注解检查临时
	public final HashMap<String, Object> tmpMap1 = new HashMap<>(), tmpMap2 = new HashMap<>();

	// toClassRef, report, 等等
	private final CharList tmpSb = new CharList();
	public CharList getTmpSb() {tmpSb.clear();return tmpSb;}

	private final ArrayList<CharList> stringBuilders = new ArrayList<>();
	public CharList getRecursiveSb() {
		CharList pop = stringBuilders.pop();
		if (pop == null) pop = new CharList();
		return pop;
	}
	public void releaseRecursiveSb(CharList sb) {
		if (stringBuilders.size() < 10) {
			sb.clear();
			stringBuilders.add(sb);
		} else {
			sb._free();
		}
	}

	// S2实现检查临时
	public NameAndType tmpNat = new NameAndType();

	// try with resource临时
	private Label[] tmpLabels = new Label[8];
	public Label[] getTmpLabels(int i) {
		if (i > tmpLabels.length) return tmpLabels = new Label[i];
		return tmpLabels;
	}
	// endregion
}