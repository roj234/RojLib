package roj.compiler.ast.expr;

import org.jetbrains.annotations.NotNull;
import roj.annotation.MayMutate;
import roj.asm.ClassNode;
import roj.asm.MethodNode;
import roj.asm.Opcodes;
import roj.asm.annotation.Annotation;
import roj.asm.insn.Label;
import roj.asm.type.IType;
import roj.asm.type.ParameterizedType;
import roj.asm.type.Type;
import roj.collect.ArrayList;
import roj.collect.IntMap;
import roj.compiler.CompileContext;
import roj.compiler.LavaCompiler;
import roj.compiler.api.Compiler;
import roj.compiler.api.InvokeHook;
import roj.compiler.api.Types;
import roj.compiler.asm.MethodWriter;
import roj.compiler.ast.EnumUtil;
import roj.compiler.diagnostic.Kind;
import roj.compiler.resolve.*;
import roj.compiler.runtime.SwitchMap;
import roj.compiler.types.CompoundType;
import roj.text.CharList;
import roj.text.TextUtil;
import roj.util.Helpers;

import java.util.*;

import static roj.asm.Opcodes.*;

/**
 * AST - 方法调用.
 * 支持以下形式：
 * <ul>
 *   <li>实例方法调用：{@code obj.method(args)}
 *   <li>静态方法调用：{@code Class.staticMethod(args)}
 *   <li>构造函数调用：{@code new Type(args)}
 *   <li>接口方法调用：{@code interface.defaultMethod(args)}
 * </ul>
 *
 * <p>{@link #resolve(CompileContext)}处理以下语义：
 * <ul>
 *   <li>方法重载解析
 *   <li>可变参数处理
 *   <li>尾递归优化
 *   <li>空安全调用（?.操作符）
 * </ul>
 *
 * @see MethodList 重载决策算法Part1
 * @see Inferrer 重载决策算法Part2
 *
 * @author Roj233
 * @since 2020/10/13 22:17
 */
public final class Invoke extends Expr {
	// package-private: NewAnonymousClass 会修改expr和args
	// 两个可能的类型: ExprNode | IType
	Object expr;
	// 方法参数
	List<Expr> args;
	// 显式泛型参数, 我的类型推断足够智能, 应该没有什么场景用得到, 除非存在多种不同的对象方法重载, 或者要手动管理优先级时
	private List<IType> explicitArgumentType;

	// 方法
	private MethodNode method;
	// 方法参数类型
	private List<IType> argTypes;
	// 方法实例的类型
	private IType instanceType;
	// 标志位
	private static final byte RESOLVED = 1, INVOKE_SPECIAL = ComponentList.THIS_ONLY, INTERFACE_CLASS = 4, POLYSIGN = 8, NONSTATIC_CHILD = 16, THIS_CALL = 32;
	private byte flag;

	public static Invoke staticMethod(MethodNode node) {return staticMethod(node, Collections.emptyList());}
	public static Invoke staticMethod(MethodNode node, @MayMutate Expr... args) {return staticMethod(node, Arrays.asList(args));}
	public static Invoke staticMethod(MethodNode node, @MayMutate List<Expr> args) {return resolved(node, null, args, 0);}

	public static Invoke virtualMethod(MethodNode node, Expr loader) {return virtualMethod(node, loader, Collections.emptyList());}
	public static Invoke virtualMethod(MethodNode node, Expr loader, @MayMutate Expr... args) {return virtualMethod(node, loader, Arrays.asList(args));}
	public static Invoke virtualMethod(MethodNode node, Expr loader, @MayMutate List<Expr> args) {return resolved(node, loader, args, 0);}

	public static Invoke interfaceMethod(MethodNode node, Expr loader) {return interfaceMethod(node, loader, Collections.emptyList());}
	public static Invoke interfaceMethod(MethodNode node, Expr loader, @MayMutate Expr... args) {return interfaceMethod(node, loader, Arrays.asList(args));}
	public static Invoke interfaceMethod(MethodNode node, Expr loader, @MayMutate List<Expr> args) {return resolved(node, loader, args, INTERFACE_CLASS);}

	public static Invoke constructor(MethodNode node) {return constructor(node, Collections.emptyList());}
	public static Invoke constructor(MethodNode node, @MayMutate Expr... args) {return constructor(node, Arrays.asList(args));}
	public static Invoke constructor(MethodNode node, @MayMutate List<Expr> args) {
		if (!node.name().equals("<init>")) throw new IllegalArgumentException("调用的不是构造函数");
		return resolved(node, null, args, 0);
	}

	static Invoke resolved(MethodNode method, Expr expr, List<Expr> args, int flag) {
		var node = new Invoke();
		node.expr = method.name().equals("<init>") ? Type.klass(method.owner()) : expr;
		node.method = method;
		node.argTypes = Helpers.cast(Type.getMethodTypes(method.rawDesc()));
		// 不可能在生成的代码中出现，仅供插件使用（a.k.a 不需要owner信息判断也能知道这是一个接口里的类）
		if ((method.modifier&ACC_INTERFACE) != 0) flag |= INTERFACE_CLASS;
		if (((method.modifier&Opcodes.ACC_STATIC) == 0) == (expr == null)) throw new IllegalArgumentException("静态参数错误:"+node);
		if (args.size() != node.argTypes.size()-1) throw new IllegalArgumentException("参数数量错误:"+node);
		node.flag = (byte) (flag | RESOLVED);
		return node;
	}

	private Invoke() {}

	public Invoke(IType type, List<Expr> args) {
		this.expr = Objects.requireNonNull(type);
		this.args = args;
	}

	private static final SwitchMap TypeId = SwitchMap.Builder
			.builder(4, true)
			.add(This.class, 1)
			.add(MemberAccess.class, 2)
			.add(Literal.class, 3)
			.add(LocalVariable.class, 4)
			.build();

	public Invoke(Expr expr, List<Expr> args) {
		int type = TypeId.get(expr.getClass());
		if (type == 0) throw new IllegalArgumentException("不支持的表达式类型 "+expr);
		this.expr = expr;
		this.args = args;
	}

	@Override
	public String toString() {
		CharList sb = new CharList();
		if (!(expr instanceof IType)) {
			if (expr != null) sb.append(expr); else if (method != null) sb.append(method.owner());
			if (method != null) sb.replace('/', '.').append('.').append(method.name());
		} else sb.append("new ").append(expr);
		return TextUtil.join(args, ", ", sb.append('(')).append(')').toStringAndFree();
	}

	@Override
	public Expr resolve(CompileContext ctx) throws ResolveException {
		if ((flag&RESOLVED) != 0) return this;
		flag |= RESOLVED;

		// 实例方法的实例类型，例如List<T>.add(...)
		IType instanceType = null;
		// 取值表达式expr是否有副作用 (目前只有自动生成this时为false)
		boolean hasSideEffect = true;
		ClassNode methodOwner;
		String methodOwnerName, methodName;
		block:
		if (expr.getClass() == MemberAccess.class) {
			MemberAccess chain = (MemberAccess) expr;
			wordStart = chain.wordStart;

			// 如果是a.b，那么b是方法名称，而a才是它真正的取值表达式
			methodName = chain.nameChain.pop();

			Expr realExpr;
			// 只有一个点
			check:
			if (chain.nameChain.isEmpty()) {
				if (chain.parent == null) {
					hasSideEffect = false;

					// 省略this : a() => this.a();
					realExpr = ctx.ep.This().resolve(ctx);

					/*  这个查找顺序源于下列代码无法编译
						class TestN {
							{ a(); }
							void a (int a) {}
						}
						void a() {}
					*/
					for (MethodNode method : ctx.compiler.getMethodList(ctx.file, methodName).getMethods()) {
						if (ctx.canAccessSymbol(ctx.compiler.resolve(method.owner()), method, false, false)) {
							break check;
						}
					}

					var staticImport = ctx.tryImportMethod(methodName, args);
					if (staticImport != null) {
						methodOwner = staticImport.owner;
						if (methodOwner == null) return staticImport.parent();
						methodName = staticImport.name;
						expr = staticImport.parent;
						break block;
					}

					if(ctx.compiler.hasFeature(Compiler.OMISSION_NEW)) {
						methodOwner = ctx.resolve(methodName);
						if (methodOwner != null) {
							// 构造器
							methodName = "<init>";
							expr = Type.klass(methodOwner.name());
							hasSideEffect = true;
							break block;
						}
					}

					// will fail 但是要打印错误
				} else {
					// ((java.lang.Object) System.out).println(1);
					chain.checkNullishDecl(ctx);
					realExpr = chain.parent.resolve(ctx);

					// [Type].[this|super]也走这个分支
					if (realExpr == ctx.ep.Super() || realExpr instanceof QualifiedThis ref && ref.nestDepth != 0) {
						flag |= INVOKE_SPECIAL;
					}
				}
			} else {
				// [x.y].a();
				realExpr = chain.resolveEx(ctx, x -> expr = x, methodName);

				if (realExpr == null) {
					if (expr instanceof Type t) {
						// 构造器
						methodName = "<init>";
						methodOwner = Objects.requireNonNull(ctx.resolve(t));
					} else {
						// 静态方法
						methodOwner = (ClassNode) expr;
						expr = null;
					}
					break block;
				}
			}

			expr = realExpr;
			// 如果是this，因为This表达式返回值是rawtypes，所以泛型参数会被擦到上界
			instanceType = realExpr.type();
			// Notfound
			if (instanceType == Types.anyType) return NaE.resolveFailed(this);

			if (instanceType.isPrimitive()) {
				// 支持 4.5 .toFixed(5) 这种自定义函数
				// 注意因为lexer会把数字后面的.当成小数所以要括号或加空格
				methodOwner = ctx.getPrimitiveMethod(instanceType);
				if (methodOwner == null) {
					ctx.report(this, Kind.ERROR, "symbol.derefPrimitive", instanceType);
					return NaE.resolveFailed(this);
				} else {
					// 转换为静态方法调用，基本类型变成第一个参数
					ArrayList<Expr> tmp = Helpers.cast(ctx.tmpList); tmp.clear();
					tmp.add(realExpr); tmp.addAll(args);
					args = ctx.ep.copyOf(tmp);
					expr = null;
					tmp.clear();
				}
			} else {
				methodOwner = ctx.resolve(instanceType);
				if (methodOwner == null) {
					ctx.reportNoSuchType(this, Kind.ERROR, instanceType);
					return NaE.resolveFailed(this);
				}
			}
		} else if (expr.getClass() == This.class) {// this / super
			if (ctx.inConstructor && ctx.thisUsed) {
				ctx.report(this, Kind.ERROR, "invoke.constructor.thisUsed", expr);
				return NaE.resolveFailed(this);
			}

			flag |= INVOKE_SPECIAL|THIS_CALL;
			instanceType = ((This) expr).resolve(ctx).type();
			methodOwner = ctx.compiler.resolve(instanceType.owner());
			methodName = "<init>";

			if ((ctx.file.modifier()&Opcodes.ACC_ENUM) != 0) {
				args = EnumUtil.prependEnumConstructor(args);
			}

			if (ctx.file.isInheritedNonStaticInnerClass()) {
				args = EnumUtil.prepend(args, new LocalVariable(ctx.getVariable(NestContext.InnerClass.FIELD_HOST_REF)));
			}
		} else {// new Type
			if ((flag&NONSTATIC_CHILD) != 0) {
				Expr that = args.get(0).resolve(ctx);
				args.set(0, that);

				// that.new enclosing() 转换为全限定名称
				var myType = (IType) expr;

				var encloseInfo = ctx.compiler.resolve(that.type().owner());
				// !开头表示是短名称而非全限定名称
				var innerClassInfo = ctx.compiler.getInnerClassInfo(encloseInfo).get("!"+myType.owner());
				if (innerClassInfo == null || (innerClassInfo.modifier&ACC_STATIC) != 0 || !ctx.compiler.getHierarchyList(encloseInfo).containsKey(innerClassInfo.parent)) {
					ctx.report(this, Kind.ERROR, "invoke.wrongInstantiationEnclosing", expr, that.type());
					return NaE.resolveFailed(this);
				}
				myType.owner(innerClassInfo.self);
			}
			instanceType = ctx.resolveType((IType) expr);
			if (!Inferrer.isFullyResolved(instanceType)) ctx.report(this, Kind.ERROR, "invoke.noExact");
			methodOwnerName = instanceType.owner();
			methodName = "<init>";

			methodOwner = ctx.compiler.resolve(methodOwnerName);
			if (methodOwner == null) {
				ctx.reportNoSuchType(this, Kind.ERROR, methodOwnerName);
				return NaE.resolveFailed(this);
			}

			int check_flag = ctx.enumConstructor ? ACC_ABSTRACT|ACC_INTERFACE : ACC_ABSTRACT|ACC_INTERFACE|ACC_ENUM;
			if ((methodOwner.modifier()&check_flag) != 0) {
				ctx.report(this, Kind.ERROR, (methodOwner.modifier()&ACC_ENUM) != 0 ? "invoke.instantiationEnum" : "invoke.instantiationAbstract", methodOwnerName);
				return NaE.resolveFailed(this);
			}

			// newEnclosingClass检查
			if ((flag&NONSTATIC_CHILD) == 0) {
				var icFlags = ctx.compiler.getInnerClassInfo(methodOwner).get(methodOwner.name());
				if (icFlags != null && (icFlags.modifier&ACC_STATIC) == 0) {
					if (!ctx.compiler.getHierarchyList(ctx.file).containsKey(icFlags.parent)) {
						ctx.report(this, Kind.ERROR, "cu.inheritNonStatic", icFlags.parent);
						return NaE.resolveFailed(this);
					} else {
						args = EnumUtil.prepend(args, ctx.ep.This());
					}
				}
			}
		}

		ArrayList<IType> argumentType = Helpers.cast(ctx.tmpList);
		Map<String, IType> namedArgumentType = Collections.emptyMap();
		int size = args.size()-1;
		if (size >= 0) {
			for (int i = 0; i < size; i++) args.set(i, args.get(i).resolve(ctx));
			Expr last = args.get(size).resolve(ctx);
			args.set(size, last);

			argumentType.clear();
			for (int i = 0; i < size; i++) argumentType.add(args.get(i).type());

			if (last.getClass() != NamedArgumentList.class) argumentType.add(last.type());
			else namedArgumentType = ((NamedArgumentList) last).resolve();

			// has Error
			for (int i = 0; i < argumentType.size(); i++) {
				if (argumentType.get(i) == NaE.UNRESOLVABLE)
					return NaE.resolveFailed(this);
			}
		} else argumentType.clear();

		block:
		try {
			// TODO is this check redundant?
			ctx.accessTypeOrReport(methodOwner);

			int flags = (hasSideEffect ? expr == null : ctx.inStatic) ? ComponentList.IN_STATIC : 0;
			if ((flag&INVOKE_SPECIAL) != 0) flags |= ComponentList.THIS_ONLY;

			// 参数泛型上界，用的很少，但是对于javac羸弱的类型推断是必须的
			List<IType> explicitArgumentType = this.explicitArgumentType;
			if (explicitArgumentType != null) {
				for (int i = 0; i < explicitArgumentType.size(); i++) explicitArgumentType.set(i, ctx.resolveType(explicitArgumentType.get(i)));
				ctx.inferrer.manualTPBounds = explicitArgumentType;
			}
			MethodResult r = ctx.getMethodListOrReport(methodOwner, methodName, this).findMethod(ctx, instanceType, argumentType, namedArgumentType, flags);
			ctx.inferrer.manualTPBounds = null;
			if (r == null) return NaE.resolveFailed(this);

			// 放在重新赋值前是一个优化，另外注意如果要改这里，write也要改。
			// 如果取值表达式的实际类型T是methodOwner的子类，并且T不是接口，应该直接调用T.<invokevirtual>method
			// 这同时能减少常量池中的名称数量
			if ((methodOwner.modifier()&Opcodes.ACC_INTERFACE) != 0) flag |= INTERFACE_CLASS;

			if (ctx.hasRestriction()) {
				ctx.checkRestriction(methodOwner, r.method);
			}
			// 后面需要读取注解，所以改成方法真正属于的类
			methodOwner = ctx.compiler.resolve(r.method.owner());

			var method = r.method;
			this.method = method;
			this.instanceType = instanceType;

			if (method.name().equals("getClass") && method.rawDesc().equals("()Ljava/lang/Class;")) {
				// getClass的特殊处理，返回Class<? extends instanceType>
				ParameterizedType type1 = Objects.requireNonNull(instanceType) instanceof ParameterizedType ? (ParameterizedType) instanceType.clone() : new ParameterizedType(instanceType.owner(), instanceType.array(), ParameterizedType.EXTENDS_WILDCARD);
				type1.wildcard = ParameterizedType.EXTENDS_WILDCARD;
				argTypes = Collections.singletonList(new ParameterizedType("java/lang/Class", Collections.singletonList(type1)));
			} else if (method == LavaCompiler.arrayClone()) {
				// 数组clone的特殊处理，实际返回Object，在使用处转型为instanceType
				argTypes = Collections.singletonList(CompoundType.genericReturn(instanceType, Types.OBJECT_TYPE));
			} else {
				argTypes = r.desc();
			}

			if ((method.modifier&Opcodes.ACC_STATIC) != 0) {
				if (expr != null) {
					// 如果静态函数调用的取值表达式有副作用，那么警告"不应当由表达式限定"
					if (hasSideEffect) ctx.report(this, Kind.SEVERE_WARNING, "symbol.isStatic", method.owner(), method.name(), "invoke.method");
					else expr = null;
				}
			} else if ((method.modifier&Opcodes.ACC_PRIVATE) != 0) {
				// private使用invokespecial提高性能
				// 在this父类中的final方法也许也应该应用优化(未实现)
				flag |= INVOKE_SPECIAL;
			}

			// MethodResolver填充的参数，除了是按名称调用，也可能是参数默认值
			IntMap<Object> filledArguments = r.filledArguments;
			if (filledArguments != null) {
				NamedArgumentList namedArgumentList = namedArgumentType == Collections.EMPTY_MAP ? null : (NamedArgumentList) args.remove(args.size() - 1);
				if (args == Collections.EMPTY_LIST) args = new ArrayList<>();
				for (var entry : filledArguments.selfEntrySet()) {
					// map的遍历不是顺序的workaround，实际args一定不能有null值！
					while (args.size() <= entry.getIntKey()) args.add(null);

					Object expr = entry.getValue();
					// 默认值=ExprNode, 按名称调用=String
					if (expr.getClass() == String.class) expr = namedArgumentList.map.get(expr);
					args.set(entry.getIntKey(), (Expr) expr);
				}
			}

			if ((method.modifier & Opcodes.ACC_VARARGS) != 0) {
				var polySign = ctx.getAnnotation(methodOwner, method, "java/lang/invoke/MethodHandle$PolymorphicSignature", true);
				if (polySign != null) {
					// 可变签名函数, 动态生成一个MethodNode
					this.method = new MethodNode(method.modifier, method.owner(), method.name(), "()V");
					List<Type> parameters = this.method.parameters();
					for (int i = 0; i < argumentType.size(); i++)
						parameters.add(argumentType.get(i).rawType());

					flag |= POLYSIGN;
					break block;
				}

				if (!r.varargExplicitlyProvided) {
					int argc = method.parameters().size() - 1;
					var elements = args.subList(argc, args.size());

					var sizeContract = ctx.getAnnotation(methodOwner, method, "roj/compiler/api/VarargCount", false);
					if (sizeContract != null) {
						int multiplyOf = sizeContract.getInt("multiplyOf", 1);
						if (
								elements.size() < sizeContract.getInt("min", 0)
							||  elements.size() > sizeContract.getInt("max", Integer.MAX_VALUE)
							||  elements.size() / multiplyOf * multiplyOf != elements.size()) {
							ctx.report(Kind.ERROR, "invoke.varargCount", sizeContract, argc);
						}
					}

					IType arrayType = r.parameters().get(argc);
					NewArray newArray = new NewArray(arrayType, new ArrayList<>(elements), false);
					elements.clear(); // removeRange

					if (args == Collections.EMPTY_LIST) args = new ArrayList<>();
					args.add(newArray.resolve(ctx));
				}
			}

			// Constexpr等处理
			if (method.getAttribute(InvokeHook.NAME) instanceof InvokeHook hook) {
				Expr expr = hook.eval(method, getParent(), args, this);
				if (expr != null) return expr.resolve(ctx);
			}

			// add throws
			r.addExceptions(ctx, false);
		} finally {
			// clear ThreadLocal cache
			argumentType.clear();
		}

		return this;
	}

	@Override
	public boolean hasFeature(Feature feature) {
		if (feature == Feature.INVOKE_CONSTRUCTOR) return expr instanceof This;
		if (feature == Feature.TAILREC) return method == CompileContext.get().method;
		return false;
	}
	@Override
	public IType type() { return expr instanceof IType ? ((IType) expr) : argTypes != null ? argTypes.get(argTypes.size()-1) : Types.anyType; }

	private boolean checkTailrec(CompileContext ctx, MethodWriter cw) {
		var mn = method;
		if (mn != ctx.method) return false;

		Annotation tailrec;
		// static，private，final或者@Tailrec(true)
		if ((tailrec = ctx.getAnnotation(ctx.file, mn, "roj/compiler/api/Tailrec", false)) != null
			? tailrec.getBool("value", true)
			: (mn.modifier&(ACC_STATIC|ACC_PRIVATE|ACC_FINAL)) != 0
		) {
			int slot = (mn.modifier&ACC_STATIC) != 0 ? 0 : 1;
			var argType = mn.parameters();// not desc, not generic
			var argVal = this.args;
			// 可能会使用当前的变量，所以把结果放在栈上
			for (int i = 0; i < argVal.size(); i++) {
				var node = argVal.get(i);
				node.write(cw, ctx.castTo(node.type(), argType.get(i), 0));
			}
			for (int i = 0; i < argVal.size(); i++) {
				Type type = argType.get(i);
				cw.varStore(type, slot);
				slot += type.length();
			}

			cw.jump(Label.atZero());
			return true;
		}
		return false;
	}

	@Override
	protected void write1(MethodWriter cw, @NotNull TypeCast.Cast _returnType) {
		var ctx = cw.ctx;
		if (ctx.inReturn && checkTailrec(ctx, cw)) return;

		var isNullishOwner = false;
		var nullishTarget = MemberAccess.NULLISH_TARGET.get();

		byte opcode;
		if (expr instanceof IType type) {
			// 不可能是数组，数组会生成ArrayDef AST
			cw.clazz(Opcodes.NEW, type.owner());
			if (_returnType != NORET) cw.insn(Opcodes.DUP);
			opcode = Opcodes.INVOKESPECIAL;
		} else {
			var expr = (Expr) this.expr;
			if ((method.modifier&Opcodes.ACC_STATIC) != 0) {
				// this.staticMethod()，这时候不需要之前的值，但也不能write(cw, null)，有的取值语句不允许忽略返回值
				if (expr != null) {
					expr.write(cw);
					// 对象类型, 基本类型应该不会有
					cw.insn(Opcodes.POP);
				}
				opcode = Opcodes.INVOKESTATIC;
			} else {
				int nullishType = 0;
				if (expr instanceof MemberAccess chain && (nullishType = chain.isNullish()) != 0) {
					if (nullishTarget == null) {
						// 当前节点是nullish表达式的顶端，需要当前节点管理进入和退出（对writeNullishTarget的调用）
						isNullishOwner = true;
						MemberAccess.NULLISH_TARGET.set(nullishTarget = new Label());
					}
				}

				var cast = instanceType == null ? TypeCast.Cast.IDENTITY : ctx.castTo(instanceType, Type.klass(method.owner()), 0);
				expr.write(cw, cast);

				// DotGet最后的调用（当前表达式）是nullish的
				if (nullishType == 2) {
					cw.insn(DUP);
					cw.jump(IFNULL, nullishTarget);
				}

				opcode = (flag&INVOKE_SPECIAL) != 0 ? Opcodes.INVOKESPECIAL : Opcodes.INVOKEVIRTUAL;
			}
		}

		if (nullishTarget != null)
			// 这里不做remove，不然频繁创建Entry
			//noinspection ThreadLocalSetWithNull
			MemberAccess.NULLISH_TARGET.set(null);
		for (int i = 0; i < args.size(); i++) {
			ctx.writeCast(cw, args.get(i), argTypes.get(i));
		}
		if (nullishTarget != null) MemberAccess.NULLISH_TARGET.set(nullishTarget);

		if ((flag&POLYSIGN) != 0) {
			method.setReturnType(_returnType == NORET
					? Type.VOID_TYPE
					: _returnType.getTarget().rawType());
		}

		// 静态方法时为null
		// 这是一个优化，因为instanceType更有可能在常量池里，并且前面的注释也提到了，这会让生成的代码中尽可能少的使用invokeInterface，每一个都是两个字节。
		String owner = instanceType == null ? method.owner() : instanceType.owner();
		if ((flag&INTERFACE_CLASS) != 0) {
			if (opcode == Opcodes.INVOKESTATIC) {
				ctx.file.setMinimumBinaryCompatibility(Compiler.JAVA_8);
			}
			cw.invoke(opcode == Opcodes.INVOKEVIRTUAL ? Opcodes.INVOKEINTERFACE : opcode, owner, method.name(), method.rawDesc(), true);
		} else {
			cw.invoke(opcode, owner, method.name(), method.rawDesc());
			if ((flag&THIS_CALL) != 0 && ctx.inConstructor && expr instanceof This) {
				if (!ctx.bp.vis().isTopScope())
					ctx.report(Kind.ERROR, "invoke.constructor.childScope", expr);
				ctx.onCallConstructor(method);
				cw.insertGlobalInit(ctx);
			}
		}

		// pop or pop2
		if (!method.rawDesc().endsWith(")V") && _returnType == NORET) cw.insn((byte) (0x56 + method.returnType().length()));

		if (isNullishOwner) MemberAccess.writeNullishExpr(cw, nullishTarget, _returnType == NORET ? Type.VOID_TYPE : method.returnType(), this);
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (o == null || getClass() != o.getClass()) return false;

		Invoke invoke = (Invoke) o;

		if (expr != null ? !expr.equals(invoke.expr) : invoke.expr != null) return false;
		if (!args.equals(invoke.args)) return false;
		return explicitArgumentType != null ? explicitArgumentType.equals(invoke.explicitArgumentType) : invoke.explicitArgumentType == null;
	}

	@Override
	public int hashCode() {
		int result = expr != null ? expr.hashCode() : 0;
		result = 31 * result + args.hashCode();
		result = 31 * result + (explicitArgumentType != null ? explicitArgumentType.hashCode() : 0);
		return result;
	}

	// 泛型边界，用于推断
	public void setExplicitArgumentType(List<IType> explicitArgumentType) { this.explicitArgumentType = explicitArgumentType; }

	public boolean isNew() {return expr instanceof IType;}
	public Expr getParent() {return expr instanceof Expr expr ? expr : null;}
	public MethodNode getMethod() {return method;}
	public void setMethod(MethodNode mn) {method = mn;}
	public List<Expr> getArguments() {return args;}
	/**
	 * 仅给静态工厂准备，指定该表达式的泛型返回值
	 */
	public void setGenericReturnType(IType genericReturnType) {argTypes.set(argTypes.size()-1, genericReturnType);}
	/**
	 * var.new XXX()语法, 指定第一个参数为This
	 */
	public Invoke setThisArg() {flag |= NONSTATIC_CHILD;return this;}

	public int getArgumentCount() {
		if (args.size() == 0) return 0;
		return args.get(args.size() - 1) instanceof NamedArgumentList argumentList ? argumentList.map.size() + args.size() - 1 : args.size();
	}
}