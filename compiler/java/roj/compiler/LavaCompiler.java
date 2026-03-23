package roj.compiler;

import org.intellij.lang.annotations.MagicConstant;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Range;
import roj.annotation.MayMutate;
import roj.asm.Attributed;
import roj.asm.ClassNode;
import roj.asm.MethodNode;
import roj.asm.Opcodes;
import roj.asm.attr.ModuleAttribute;
import roj.asm.type.IType;
import roj.asm.type.Type;
import roj.asmx.AnnotatedElement;
import roj.asmx.AnnotationRepo;
import roj.collect.*;
import roj.compiler.api.AStatementParser;
import roj.compiler.api.Compiler;
import roj.compiler.api.Processor;
import roj.compiler.api.StageHook;
import roj.compiler.asm.AnnotationBuilder;
import roj.compiler.ast.MethodParser;
import roj.compiler.ast.expr.Expr;
import roj.compiler.ast.expr.ExprParser;
import roj.compiler.ast.expr.Invoke;
import roj.compiler.ast.expr.PrefixOperator;
import roj.compiler.diagnostic.Kind;
import roj.compiler.doc.Javadoc;
import roj.compiler.doc.JavadocProcessor;
import roj.compiler.library.JarLibrary;
import roj.compiler.library.Library;
import roj.compiler.resolve.Resolver;
import roj.concurrent.TaskGroup;
import roj.io.IOUtil;
import roj.text.ParseException;
import roj.text.Token;
import roj.ui.EasyProgressBar;
import roj.util.ByteList;
import roj.util.Helpers;
import roj.util.TypedKey;
import roj.util.function.ExceptionalRunnable;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 编译器实例，包含全局上下文和配置，包括但不限于
 * <ol>
 * <li>A mapping from fully qualified class names (String) to ASM class data (ClassNode).</li>
 * <li>{@link roj.compiler.resolve.LinkedClass Cache}s for data bound to classes, including:
 *     <ul>
 *     <li>MethodList and FieldList, distinguished only by name (ignoring access modifiers or parameter counts). The former improves error reporting beyond "not found," and the latter supports varargs and named types.</li>
 *     <li>Hierarchy list, using {@code IntBiMap} for ordered access and O(n) {@link #getCommonAncestor(ClassNode, ClassNode) nearest common ancestor lookup}.</li>
 *     <li>{@link #getTypeArgumentsFor(ClassNode, String)} and</li>
 *     <li>InnerClassFlags for retrieving actual access flags of inner classes.</li>
 *     </ul>
 * </li>
 * <li>Short name cache (resolving short names to fully qualified names), used by TypeResolver's importAny.</li>
 * <li>Creation of {@code MethodWriter} for potential future switching of compilation targets (e.g., to C or x86).</li>
 * <li>Annotation processing and other {@code Compiler} 插件 APIs.</li>
 * </ol>
 * </p>
 * @see Compiler
 * @see CompileContext
 * @see CompileUnit
 */
public class LavaCompiler extends Resolver implements Compiler {
	public LavaCompiler() {super();}
	public LavaCompiler(boolean addRuntimeLibrary) {super(addRuntimeLibrary);}

	private final GeneratedClasses generated = new GeneratedClasses();
	private final GeneratedClasses pluginTypes = new GeneratedClasses();
	private static final class GeneratedClasses implements Library {
		final HashMap<String, ClassNode> classes = new HashMap<>();
		ModuleAttribute module;

		@Override public ClassNode get(CharSequence name) {return classes.get(name);}
		@Override public String moduleName() {return module == null ? null : module.self.name;}
	}

	public ArrayList<CompileUnit> parsables = new ArrayList<>();
	public boolean structOnly;

	/**
	 * 编译输入的源文件并返回生成的类定义。
	 * 编译过程分为多个阶段：语法解析、名称解析、类型解析、方法解析、注解处理和代码生成。
	 * 如果在任何阶段遇到错误，将返回null并设置错误标志。
	 * [未实现] 输入的列表会被修改，如果发生了编译错误，也许可以从这个列表中获得额外信息
	 *
	 * @param files 要编译的源文件列表
	 * @return 成功编译后生成的类定义列表，如果编译失败则返回null
	 * @implNote 如果不返回null，并且你想复用编译器实例，那么你必须手动清空它
	 */
	public @Nullable List<? extends ClassNode> compile(@MayMutate List<? extends CompileUnit> files) {return compile(files, null);}
	@SuppressWarnings("unchecked")
	public @Nullable List<? extends ClassNode> compile(@MayMutate List<? extends CompileUnit> files, @Nullable StageHook hook) {
		if (hasError) throw new IllegalStateException("hasError is true before compilation.");
		try {
			if (CompileContext.get() == null) CompileContext.set(createContext());

			for (int i = 0; i < files.size(); i++) {
				try {
					// 返回假如果不需要解析 (没有有意义的内容)
					files.get(i).S1parseStruct();
				} catch (ParseException e) {
					e.printStackTrace();
					hasError = true;
				}
			}
			var units = parsables;
			if (hook != null) hook.postStage(10, this, units);
			if (hasError()) return null;
			for (int i = 0; i < units.size(); i++) {
				units.get(i).S2p1resolveInheritance();
			}
			if (hook != null) hook.postStage(21, this, units);
			if (hasError()) return null;
			for (int i = 0; i < units.size(); i++) {
				units.get(i).S2p2resolveMembers();
			}
			if (hook != null) hook.postStage(22, this, units);
			if (hasError()) return null;
			for (int i = 0; i < units.size(); i++) {
				units.get(i).S2p3resolveMethod();
			}
			if (hook != null) hook.postStage(23, this, units);
			if (hasError()) return null;
			for (int i = 0; i < units.size(); i++) {
				try {
					units.get(i).S3processAnnotation();
				} catch (ParseException e) {
					e.printStackTrace();
					hasError = true;
				}
			}
			if (hook != null) hook.postStage(30, this, units);
			if (hasError()) return null;
			if (!structOnly) {
				for (int i = 0; i < units.size(); i++) {
					try {
						units.get(i).S4parseCode();
					} catch (ParseException e) {
						e.printStackTrace();
						hasError = true;
					}
				}
				if (hook != null) hook.postStage(40, this, units);
				if (hasError()) return null;
				for (int i = 0; i < units.size(); i++) {
					try {
						units.get(i).S5serialize();
					} catch (Exception e) {
						e.printStackTrace();
						hasError = true;
					}
				}
			}
			units.addAll((Collection<? extends CompileUnit>) Helpers.cast(getGeneratedClasses()));
			return units;
		} finally {
			reset();
			CompileContext.set(null);
		}
	}

	private final ArrayList<CompileContext> parallelContexts = new ArrayList<>();
	public synchronized void releaseContext(CompileContext ctx) {parallelContexts.add(ctx);}
	public synchronized CompileContext retainContext() {
		CompileContext r = parallelContexts.pop();
		return r != null ? r : createContext();
	}

	@SuppressWarnings("unchecked")
	public @Nullable List<? extends ClassNode> compile(
			@MayMutate List<? extends CompileUnit> files,
			TaskGroup group,
			EasyProgressBar prog,
			@Nullable StageHook hook
	) {
		if (hasError) throw new IllegalStateException("hasError is true before compilation.");
		try {
			CompileContext ctx = retainContext();
			for (CompileUnit unit : files) unit.setContext(ctx);
			releaseContext(ctx);

			var taskForFirstPass = split(Helpers.cast(files), 1000);

			prog.setTotal(taskForFirstPass.size());
			prog.setName("阶段1: 结构解析");

			ExceptionalRunnable<Exception> cleanup = () -> {
				CompileContext.remove();
				prog.increment();
			};
			try {
				group.executeAll(taskForFirstPass, CompileUnit::S1parseStruct, cleanup).await();
			} catch (Exception e) {
				e.printStackTrace();
				hasError = true;
			}
			if (hook != null) hook.postStage(10, this, parsables);
			if (hasError()) return null;

			var taskSecondPass = split(parsables, 1000);

			prog.end("完成");
			prog.setTotal(taskSecondPass.size());
			prog.setName("阶段2.1: 名称解析");

			group.executeAll(taskSecondPass, CompileUnit::S2p1resolveInheritance, cleanup).await();
			if (hook != null) hook.postStage(21, this, parsables);
			if (hasError()) return null;

			prog.end("完成");
			prog.setTotal(taskSecondPass.size());
			prog.setName("阶段2.2: 类型解析");

			group.executeAll(taskSecondPass, CompileUnit::S2p2resolveMembers, cleanup).await();
			if (hook != null) hook.postStage(22, this, parsables);
			if (hasError()) return null;

			prog.end("完成");
			prog.setTotal(taskSecondPass.size());
			prog.setName("阶段2.3: 引用解析");

			group.executeAll(taskSecondPass, CompileUnit::S2p3resolveMethod, cleanup).await();
			if (hook != null) hook.postStage(23, this, parsables);
			if (hasError()) return null;

			prog.end("完成");
			prog.setTotal(taskSecondPass.size());
			prog.setName("阶段3: 注解和静态常量");

			try {
				group.executeAll(taskSecondPass, CompileUnit::S3processAnnotation, cleanup).await();
			} catch (Exception e) {
				e.printStackTrace();
				hasError = true;
			}
			if (hook != null) hook.postStage(30, this, parsables);
			if (hasError()) return null;

			prog.end("完成");
			if (!structOnly) {
				prog.setTotal(taskSecondPass.size());
				prog.setName("阶段4: 方法");

				try {
					group.executeAll(taskSecondPass, CompileUnit::S4parseCode, cleanup).await();
				} catch (Exception e) {
					e.printStackTrace();
					hasError = true;
				}
				if (hook != null) hook.postStage(40, this, parsables);
				if (hasError()) return null;

				prog.end("完成");
				prog.setTotal(taskSecondPass.size());
				prog.setName("阶段5: 后处理");

				group.executeAll(taskSecondPass, CompileUnit::S5serialize, cleanup).await();
			}

			parsables.addAll((Collection<? extends CompileUnit>) Helpers.cast(getGeneratedClasses()));
			return parsables;
		} finally {
			reset();
		}
	}
	private static @NotNull List<List<CompileUnit>> split(List<CompileUnit> files, int threshold) {
		List<List<CompileUnit>> tasks = new ArrayList<>((files.size()+threshold-1)/threshold);

		int i = 0;
		while (i < files.size()) {
			int len = Math.min(files.size(), i+threshold);

			// 内部类必须和父类在同一线程上编译
			// 这是一个没有考虑边界情况的检查，但是应该可用
			var unit = files.get(len-1);
			while (unit.nestHost != unit && len < files.size()) {
				unit = files.get(len++);
			}

			tasks.add(files.subList(i, len));
			i = len;
		}
		return tasks;
	}

	public Collection<ClassNode> getGeneratedClasses() {return generated.classes.values();}
	public void reset() {
		if (hasError) parsables.clear();

		//extraInfos.removeIf(v -> v.owner instanceof CompileUnit);
		for (CompileUnit unit : compileUnits) extraInfos.removeKey(unit);
		compileUnits.clear();

		libraryByName.values().removeIf(v -> v == generated);
		generated.classes.clear();
		generated.module = null;
	}

	// synchronized in CompileContext#addCompileUnit
	public void addCompileUnit(CompileUnit unit) {
		var existing = compileUnits.putIfAbsent(unit.name(), unit);
		if (existing != null) throw new IllegalStateException("重复的编译单位: "+unit.name());
		parsables.add(unit);
	}
	public synchronized void setModule(ModuleAttribute module) {
		if (generated.module != null) report(null, Kind.ERROR, -1, "module.dup");
		generated.module = module;
	}
	public synchronized void addGeneratedClass(ClassNode data) {
		libraryByName.put(data.name(), generated);
		var prev = generated.classes.putIfAbsent(data.name(), data);
		if (prev != null) throw new IllegalStateException("重复的生成类: "+data.name());
	}

	public JavadocProcessor createJavadocProcessor(Javadoc javadoc, CompileUnit file) {return JavadocProcessor.NULL;}

	public ClassNode resolveArray(IType type) {
		System.out.println("resolveArray("+type+")");
		if (true) return AnyArray;

		// 在不同类型的数组上创建附加方法
		String arrayTypeDesc = type.toDesc();
		Object o = libraryByName.get(arrayTypeDesc);
		if (o != null) return (ClassNode) o;

		var anyArray = AnyArray;
		var arrayInfo = new ClassNode();
		arrayInfo.name(arrayTypeDesc);
		arrayInfo.parent(anyArray.parent());
		arrayInfo.itfList().addAll(anyArray.itfList());
		arrayInfo.methods.addAll(anyArray.methods);
		arrayInfo.fields.addAll(anyArray.fields);

		synchronized (libraryByName) {
			libraryByName.putIfAbsent(arrayTypeDesc, arrayInfo);
		}

		return arrayInfo;
	}

	// Misc
	/**
	 * The maximum Java version supported for binary compatibility.
	 * @see Compiler
	 */
	public int maxVersion = JAVA_17;
	public BitSet features = new BitSet();

	public boolean hasFeature(int specId) {return features.contains(specId);}

	/**
	 * Gets the maximum binary compatibility version allowed.
	 *
	 * @return the maximum version, ranging from 6 to 21
	 */
	@Range(from = 6, to = 21)
	public int getMaximumBinaryCompatibility() {return maxVersion;}
	//region CompileContext创建和扩展
	public CompileContext createContext() {
		var ctx = new CompileContext(this);
		ctx.ep.stateMap = stateMap;
		ctx.ep.callbacks = callbacks;
		return ctx;
	}
	// TODO 如何添加新的语句？
	protected MethodParser createMethodParser(CompileContext ctx) {return new MethodParser(ctx);}

	protected static final int TF_ANYARGC = 1, TF_PRIMITIVE = 2, TF_NORAW = 4, TF_NOARRAY = 8;
	/**
	 * 解析时忽略这个类的泛型限制，用于某些编译器内部类.
	 * These flags are used to handle special cases like varargs in generics.
	 */
	@MagicConstant(flags = {TF_ANYARGC, TF_PRIMITIVE, TF_NORAW, TF_NOARRAY})
	protected int getTypeFlag(ClassNode info) {
		return switch (info.name()) {
			case "roj/compiler/runtime/ReturnStack" -> TF_ANYARGC | TF_PRIMITIVE | TF_NORAW;
			case "roj/compiler/runtime/Generator" -> TF_PRIMITIVE | TF_NORAW;
			case "roj/compiler/runtime/F" -> TF_ANYARGC | TF_PRIMITIVE | TF_NORAW;
			case "roj/compiler/api/Union" -> TF_ANYARGC | TF_NORAW | TF_NOARRAY;
			default -> 0;
		};
	}
	/**
	 * @see CompileContext#getOperatorOverride(Expr, Object, int)
	 */
	@Nullable
	protected Expr getOperatorOverride(CompileContext ctx, @NotNull Expr e1, @Nullable Object e2, int operator) {
		IType left = e1.type(), right = e2 instanceof Expr n ? n.type() : (IType) e2;

		var ctx1 = new OperatorContext((short) operator, left, right);

		for (var op : operators.getOrDefault(operator, Collections.emptyList())) {
			var node = op.test(ctx, ctx1, e1, e2);
			if (node != null) return node.resolve(ctx);
		}
		return null;
	}
	//endregion
	//region 注解处理API
	private AnnotationRepo annotations;
	private final HashMap<String, List<Processor>> processors = new HashMap<>();

	public void addLibrary(Library library) {
		if (annotations != null) addAnnotations(library);
		super.addLibrary(library);
	}

	public AnnotationRepo getClasspathAnnotations() {
		if (annotations == null) {
			annotations = new AnnotationRepo();
			for (int i = unernumerableEnd; i < libraries.size(); i++) {
				addAnnotations(libraries.get(i));
			}
		}
		return annotations;
	}

	private void addAnnotations(Library library) {
		if (library instanceof JarLibrary lib) {
			File cache = getCacheDirectory("annotations$"+Helpers.sha1Hash(lib.jar.source().toString())+".repo");
			if (cache != null) {
				if (cache.isFile()) {
					if (cache.length() == 0) return;

					try (var in = new FileInputStream(cache)) {
						if (annotations.deserialize(IOUtil.getSharedByteBuf().readStreamFully(in))) {
							return;
						}
					} catch (Exception e) {
						debugLogger().error("Failed to load annotation cache", e);
					}
				}

				if (lib.jar.getEntry(AnnotationRepo.CACHE_PATH) == null) {
					var repo = new AnnotationRepo();
					try (var fos = new FileOutputStream(cache)) {
						repo.add(lib.jar);
						if (!repo.getAnnotations().isEmpty()) {
							ByteList buf = IOUtil.getSharedByteBuf();
							repo.serialize(buf);
							buf.writeToStream(fos);

							annotations.deserialize(buf);
						}
						return;
					} catch (Exception e) {
						debugLogger().error("Failed to save annotation cache", e);
					}
				}
			}
			try {
				annotations.loadCacheOrAdd(lib.jar);
			} catch (IOException e) {
				e.printStackTrace();
			}
		} else {
			for (String name : library.indexedContent()) {
				ClassNode node = library.get(name);
				if (node != null) annotations.add(node);
			}
		}
	}

	@Override
	public void addAnnotationProcessor(Processor processor) {
		for (String annotation : processor.acceptedAnnotations()) {
			processors.computeIfAbsent(annotation, Helpers.fnArrayList()).add(processor);
		}

		if (processor.readClasspath()) {
			getClasspathAnnotations();
			for (String annotation : processor.acceptedAnnotations()) {
				for (AnnotatedElement element : annotations.annotatedBy(annotation)) {
					processor.handle(CompileContext.get(), element.parent().node(), element.node(), element.annotations().get(annotation));
				}
			}
		}
	}

	public void runAnnotationProcessor(CompileUnit file, Attributed node, List<AnnotationBuilder> annotations) {
		var lc = file.ctx;
		for (AnnotationBuilder annotation : annotations) {
			for (Processor processor : processors.getOrDefault(annotation.type(), Collections.emptyList())) {
				processor.handle(lc, file, node, annotation);
			}
		}
	}
	//endregion
	//region 库加载API
	private final IterablePriorityQueue<Prioritized<Resolver>> resolvers = new IterablePriorityQueue<>();
	private record Prioritized<T>(int priority, T value) implements Comparable<Prioritized<T>> {
		public int compareTo(Prioritized<T> o) {return Integer.compare(priority, o.priority);}
	}

	@Override
	public void addResolveListener(int priority, Resolver dtr) {resolvers.add(new Prioritized<>((priority << 16) | resolvers.size(), dtr));}

	protected ClassNode onClassLoaded(String name, ClassNode clazz) {
		for (var sortable : resolvers) {
			clazz = sortable.value.classResolved(clazz);
		}
		return clazz;
	}
	//endregion
	@Override
	public ClassNode addAnnotationStatement(String typename, AnnotationStatement impl) {
		ClassNode node = resolve(typename);
		if (node == null) {
			libraryByName.put(typename, pluginTypes);

			node = new ClassNode();
			node.name(typename);
			node.modifier |= Opcodes.ACC_ANNOTATION;

			var prev = pluginTypes.classes.putIfAbsent(typename, node);
			if (prev != null) throw new IllegalStateException("重复的类: "+typename);
		}
		node.addAttribute(new AStatementParser(impl));
		return node;
	}
	// region 词法分析API
	private final TrieTree<Token> myTokens = LavaTokenizer.getTokenMap();
	private final BitSet myLends = LavaTokenizer.getLiteralEnd();
	private final BitSet[] myLiteralStates = LavaTokenizer.getLiteralState();
	private int nextTokenId = LavaTokenizer.getInitialTokenCount();

	public LavaTokenizer createTokenizer() {
		var tokenizer = new LavaTokenizer();
		tokenizer.literalEnd(myLends).tokenIds(myTokens);
		tokenizer.literalState = myLiteralStates;
		tokenizer.udlCallbacks = udlCallbacks;
		return tokenizer;
	}

	@Override public int tokenCreate(String token, int tokenCategory) {
		Token word = myTokens.putIfAbsent(token, new Token().init(nextTokenId, 0, token));
		int tokenId = word == null ? nextTokenId++ : word.type();
		for (int i = 0; i < myLiteralStates.length; i++) {
			if (((1 << i) & tokenCategory) != 0)
				myLiteralStates[i].add(tokenId);
		}
		return tokenId;
	}
	@Override public void tokenAlias(int token, String alias) {myTokens.put(alias, new Token().init(token, 0, alias));}
	@Override public void tokenLiteralEnd(String lend) {myLends.add(lend.charAt(0));}
	@Override public void tokenDelete(String token) {myTokens.remove(token);}

	private int tokenId(String token) {
		Token w = myTokens.get(token);
		if (w != null) return w.type();
		return tokenCreate(token, STATE_EXPR);
	}
	// endregion
	//region 表达式解析API
	private final Int2IntMap stateMap = ExprParser.getStateMap();
	private final List<Object> callbacks = ArrayList.asModifiableList((Object) null);
	private final IntMap<List<ExprOp>> operators = new IntMap<>();
	private final TrieTree<Integer> udlCallbacks = new TrieTree<>();

	private void register(int mask, String token, Object fn, int flags) {
		int i = stateMap.putIntIfAbsent(tokenId(token) | mask, flags);
		if (i != flags) throw new IllegalStateException("token "+token+" was occupied");
		callbacks.add(fn);
	}

	@Override
	public void newUDLOp(String unit, UDLOp parser) {
		int tokenId = tokenId("\0UDL\0"+unit);

		int flags = callbacks.size();
		int i = stateMap.putIntIfAbsent(tokenId | ExprParser.SM_ExprStart, flags);
		if (i != flags) throw new IllegalStateException("token "+unit+" was occupied");

		callbacks.add((StartOp) ctx -> {
			Token udl = ctx.tokenizer.current();
			assert udl.type() == tokenId;
			return parser.parse(ctx, udl);
		});

		udlCallbacks.put(IOUtil.getSharedCharBuf().append(unit).reverse().toString(), tokenId);
	}

	@Override public final void newUnaryOp(String token, ContinueOp<PrefixOperator> parser) {register(ExprParser.SM_UnaryPre, token, parser, callbacks.size());}
	@Override public final void newExprOp(String token, StartOp parser) {register(ExprParser.SM_UnaryPre, token, parser, ExprParser.CU_TerminateFlag | callbacks.size());}
	@Override public final void newStartOp(String token, StartOp parser) {register(ExprParser.SM_ExprStart, token, parser, callbacks.size());}
	@Override public final void newContinueOp(String token, ContinueOp<Expr> parser) {register(ExprParser.SM_ExprNext, token, parser, callbacks.size());}
	@Override public final void newTerminalOp(String token, ContinueOp<Expr> parser) {register(ExprParser.SM_ExprNext, token, parser, ExprParser.CU_TerminateFlag | callbacks.size());}
	@Override public final void newBinaryOp(String token, int priority, BinaryOp parser, boolean rightAssoc) {register(ExprParser.SM_ExprTerm, token, parser, priority | ExprParser.CU_Binary | (rightAssoc ? ExprParser.SM_RightAssoc : 0) | (callbacks.size() << 12));}

	private static ExprOp SimpleOp(Type left1, Type right1, MethodNode node) {
		return (ctx, opctx, left, right) -> {
			if (ctx.castTo(opctx.leftType(), left1, -8).type >= 0 &&
					(right == null ? right1 == null : ctx.castTo(opctx.rightType(), right1, -8).type >= 0)) {

				boolean isObject = (node.modifier() & Opcodes.ACC_STATIC) == 0;
				if (isObject) return Invoke.virtualMethod(node, left, (Expr) right);
				return Invoke.staticMethod(node, left, (Expr) right);
			}
			return null;
		};
	}

	@Override public void onUnary(String operator, Type type, MethodNode node, int side) {operators.computeIfAbsentI(tokenId(operator), x -> new ArrayList<>()).add(SimpleOp(type, null, node));}
	@Override public void onBinary(Type left, String operator, Type right, MethodNode node, boolean swap) {operators.computeIfAbsentI(tokenId(operator), x -> new ArrayList<>()).add(SimpleOp(left, right, node));}
	@Override public void addOpHandler(String operator, ExprOp resolver) {operators.computeIfAbsentI(tokenId(operator), x -> new ArrayList<>()).add(resolver);}
	//endregion
	//region 附件API
	private Map<TypedKey<?>, Object> attachments = Collections.emptyMap();
	@SuppressWarnings("unchecked")
	public final <T> T attachment(TypedKey<T> key) {return (T) attachments.get(key);}
	@SuppressWarnings("unchecked")
	public synchronized final <T> T attachment(TypedKey<T> key, T val) {
		if (attachments.isEmpty()) attachments = new HashMap<>(4);
		return (T) (val == null ? attachments.remove(key) : attachments.put(key, val));
	}
	//endregion
}