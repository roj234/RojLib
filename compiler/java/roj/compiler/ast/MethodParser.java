package roj.compiler.ast;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import roj.asm.ClassNode;
import roj.asm.MethodNode;
import roj.asm.Opcodes;
import roj.asm.attr.*;
import roj.asm.cp.Constant;
import roj.asm.cp.CstClass;
import roj.asm.frame.FrameVisitor;
import roj.asm.insn.*;
import roj.asm.type.*;
import roj.collect.ArrayList;
import roj.collect.HashMap;
import roj.collect.HashSet;
import roj.collect.*;
import roj.compiler.CompileContext;
import roj.compiler.CompileUnit;
import roj.compiler.LavaCompiler;
import roj.compiler.LavaTokenizer;
import roj.compiler.api.AStatementParser;
import roj.compiler.api.Compiler;
import roj.compiler.api.SwitchableType;
import roj.compiler.api.Types;
import roj.compiler.asm.AnnotationBuilder;
import roj.compiler.asm.MethodWriter;
import roj.compiler.asm.Variable;
import roj.compiler.ast.expr.*;
import roj.compiler.diagnostic.Kind;
import roj.compiler.resolve.*;
import roj.compiler.runtime.DeferList;
import roj.compiler.runtime.RtUtil;
import roj.config.node.ConfigValue;
import roj.text.ParseException;
import roj.text.Token;
import roj.util.ArrayUtil;
import roj.util.Helpers;
import roj.util.OperationDone;

import java.util.*;
import java.util.function.Consumer;
import java.util.function.Function;

import static roj.asm.Opcodes.*;
import static roj.compiler.LavaTokenizer.*;
import static roj.compiler.ast.GeneratorUtil.RETURNSTACK_TYPE;

/**
 * Lava Compiler - 方法体(语句/Statement)解析器<p>
 * Parser levels: <ol>
 *     <li>{@link CompileUnit Class Parser}</li>
 *     <li>{@link ParseTask Segment Parser}</li>
 *     <li><b><i>Method Parser</i></b></li>
 *     <li>{@link ExprParser Expression Parser}</li>
 * </ol>
 * @author solo6975
 * @since 2020/10/3 19:20
 */
public final class MethodParser {
	private static final String VARIABLE_IGNORE = "_";

	private final CompileContext ctx;
	private final ExprParser ep;
	private final LavaTokenizer wr;

	private final VisMap visMap = new VisMap();
	@Nullable private HashSet<String> varInLoop;
	private final VarMapper varMapper = new VarMapper();
	private int paramSize;

	private CompileUnit file;
	private MethodWriter cw;
	private Type returnType;
	private IType returnTypeG;
	private int methodType;
	public static final int MT_NORMAL = 0, MT_BLOCK = 1, MT_EXPRESSION_LAMBDA = 2;

	public MethodParser(CompileContext ctx) {
		this.ctx = ctx;
		this.ep = ctx.ep;
		this.wr = ctx.tokenizer;
	}

	public void reset() {
		this.visMap.clear();
		this.varMapper.clear();
		this.varInLoop = null;

		this.variables.clear();
		this.regionNew.clear();
		this.sectionFlag = 0;

		this.flowHook = null;
		this.checkedExceptions = null;
		this.deferNode = null;

		this.labels.clear();
		this.imBreak = this.imContinue = null;
		this.imPendingVis.clear();

		this.switchMapId = 0;
		this.generatorEntry = null;
	}
	// for GC ()
	public void clear() {
		file = null;
		cw = null;
		returnType = null;
		returnTypeG = null;
	}

	// region 解析
	public void parseBlockMethod(CompileUnit file, MethodWriter cw) throws ParseException {
		methodType = MT_BLOCK;
		this.file = file;
		ctx.setMethod(cw.method);
		reset();

		paramSize = 0;
		returnTypeG = returnType = Type.VOID_TYPE;

		wr.next(); wr.retractWord();
		wr.setLines(cw.lines());
		setCw(cw);
		parse0();

		wr.setLines(null);
	}

	public MethodWriter parseMethod(CompileUnit file, MethodNode mn, List<String> names) throws ParseException {return parseMethod(file, mn, names, MT_NORMAL);}
	public MethodWriter parseMethod(CompileUnit file, MethodNode mn, List<String> names, int methodType) throws ParseException {
		this.methodType = methodType;
		this.file = file;
		ctx.setMethod(mn);
		reset();

		wr.next(); wr.retractWord();
		var cw = ctx.createMethodWriter(file, mn);
		wr.setLines(cw.lines());
		setCw(cw);

		var flags = mn.getAttribute(null, Attribute.MethodParameters);
		var signature = mn.getAttribute(null, Attribute.SIGNATURE);

		returnType = mn.returnType();
		returnTypeG = signature == null ? returnType : signature.values.get(signature.values.size()-1);

		paramSize = (mn.modifier() & ACC_STATIC) == 0 ? 1 : 0;

		var parameters = mn.parameters();
		for (int i = 0; i < parameters.size(); i++) {
			IType type = parameters.get(i);
			if (i < names.size()) {
				Variable var = newVar(names.get(i), signature != null ? signature.values.get(i) : type);
				if (flags != null && (flags.getFlag(i, 0)&ACC_FINAL) != 0) var.isFinal = true;
				if (ctx.isArgumentDynamic) var.pos = 0; // keep dynamic
			} else {
				paramSize += type.rawType().length();
			}
		}

		parse0();

		wr.setLines(null);
		return cw;
	}

	public MethodWriter parseGenerator(CompileUnit file, MethodNode mn, List<String> names, ClassNode generatorOwner, MethodNode generator) throws ParseException {
		methodType = MT_BLOCK;
		this.file = file;
		ctx.setMethod(mn);
		reset();

		Signature signature = Objects.requireNonNull((Signature) mn.getAttribute("Signature"));
		returnTypeG = ((ParameterizedType) signature.values.get(signature.values.size()-1)).typeParameters.get(0);
		returnType = returnTypeG.rawType();

		wr.next(); wr.retractWord();
		var cw = ctx.createMethodWriter(generatorOwner, generator);
		wr.setLines(cw.lines());
		setCw(cw);

		if ((mn.modifier&ACC_STATIC) == 0) {
			Variable that = newVar("@this", Type.klass(mn.owner()));
			that.isFinal = true;
			that.hasValue = true;

			cw.addSegment(StaticSegment.emptyWritable());
			cw.insn(ALOAD_0);
			cw.field(GETFIELD, generatorOwner, 0);
			cw.insn(ASTORE_0);
			cw.addSegment(StaticSegment.emptyWritable());
		}

		// 返回值临时存储在0或1中，this存储在0中，堆栈自身存储在1中
		paramSize = 2;
		varMapper.reserve(0);
		varMapper.reserve(1);

		cw.insn(ALOAD_1);
		cw.invokeV(RETURNSTACK_TYPE, "getI", "()I");
		generatorEntry = cw.tableSwitch();

		// INIT
		generatorEntry.branch(0, generatorEntry.def = cw.label());

		var flags = mn.getAttribute(null, Attribute.MethodParameters);
		var sign = mn.getAttribute(null, Attribute.SIGNATURE);
		var parameters = mn.parameters();
		for (int i = 0; i < parameters.size(); i++) {
			Variable var = newVar(names.get(i), sign != null ? sign.values.get(i) : parameters.get(i));
			if (flags != null && (flags.getFlag(i, 0)&ACC_FINAL) != 0) var.isFinal = true;
			var.hasValue = true;

			cw.insn(ALOAD_1);
			char vType = (char) var.type.getActualType();
			if (vType == Type.OBJECT) {
				cw.invokeV(RETURNSTACK_TYPE, "getL", "()Ljava/lang/Object;");
				cw.clazz(CHECKCAST, var.type.rawType());
			} else {
				cw.invokeV(RETURNSTACK_TYPE, "get"+vType, "()"+vType);
			}
			cw.store(var);
		}

		parse0();

		cw.visitSizeMax(2, 2);
		cw.insn(ALOAD_1);
		cw.invokeV(RETURNSTACK_TYPE, "forWrite", "()L"+RETURNSTACK_TYPE+";");
		cw.ldc(-1);
		cw.invokeV(RETURNSTACK_TYPE, "put", "(I)L"+RETURNSTACK_TYPE+";");
		cw.insn(POP);
		cw.insn(Opcodes.RETURN);

		if ((mn.modifier&ACC_STATIC) == 0 && !ctx.thisUsed) {
			cw.replaceSegment(1, StaticSegment.emptyWritable());
		}

		wr.setLines(null);
		return cw;
	}

	private void parse0() throws ParseException {
		beginCodeBlock();

		while (true) {
			Token w = wr.next();
			if (w.type() == rBrace) {
				var blockMethod = methodType == MT_BLOCK;
				if (blockMethod != cw.isContinuousControlFlow()) {
					if (blockMethod) {
						if (generatorEntry == null)
							ctx.report(Kind.ERROR, "block.initCantComplete");
					} else {
						if (returnType.type == Type.VOID) cw.insn(Opcodes.RETURN);
						else ctx.report(Kind.ERROR, "block.return.exceptExpr");
					}
				}
				break;
			}

			statement(w);
		}

		endCodeBlock();

		if (ctx.hasError()) return;

		cw._updateOffsets();
		if (ctx.enclosingContext().getLast() instanceof NestContext.Lambda lambda) {
			lambda.processVariableIndex(ctx, variables);
		}
		if (ctx.thisUsed || ctx.inConstructor) varMapper.reserve(0);
		for (Variable v : variables.values()) {
			if (v.end != null) {
				varMapper.reserve(v.slot);
				if (v.type.rawType().length() > 1)
					varMapper.reserve(v.slot+1);
			}
		}
		int lvtSize = varMapper.map();
		//cw.visitSize(0, Math.max(lvtSize, paramSize));
	}
	//endregion
	/**
	 * 语句
	 */
	@SuppressWarnings("fallthrough")
	private void statement(Token w) throws ParseException {
		Scope imLabel = null;
		for(;;) {
		switch (w.type()) {
			case semicolon -> ctx.report(Kind.WARNING, "block.emptyStatement");
			case lBrace -> {
				if (imLabel != null) visMap.enter(imLabel);
				blockV();
				if (imLabel != null) visMap.exit();
			}

			case TRY -> _try(imLabel);
			case DEFER -> _defer();

			case CONTINUE, BREAK -> _break(w.type() == BREAK);
			case LavaTokenizer.GOTO -> _goto();
			case LavaTokenizer.RETURN -> _return();
			case THROW -> _throw();

			case IF -> _if(imLabel);
			case FOR -> _for(imLabel);
			case WHILE -> _while(imLabel);
			case DO -> _doWhile(imLabel);
			case SWITCH -> writeSwitch(parseSwitch(false, imLabel));
			case YIELD -> _yield();

			case SYNCHRONIZED -> _sync();
			case WITH -> _with();
			case ASSERT -> _assert();
			//case AWAIT -> _await(); // require Async function

			case at -> {
				ctx.tmpAnnotations.clear();
				file.readAnnotations(ctx.tmpAnnotations);
				wr.next();
				file.resolveAnnotationTypes(ctx, ctx.tmpAnnotations);

				var statement = resolveAnnotationValues();
				if (statement == null) continue;

				var copy = new ArrayList<>(ctx.tmpAnnotations);
				ctx.tmpAnnotations.clear();
				statement.parser.parse(ctx, cw, copy);
			}

			case FINAL -> defineVariables(null, true);
			case CONST -> {
				if (!varConst(true)) ctx.report(Kind.ERROR, "expr.illegalStart");
			}
			default -> {
				// case LITERAL, BOOLEAN, BYTE, SHORT, CHAR, INT, LONG, FLOAT, DOUBLE
				// 其它的
				if (w.type() == Token.LITERAL) {
					// 为了兼容性，var作为literal处理，当然了也是为start弄一个Lexer Category比较麻烦
					if (w.text().equals("var") && varConst(false)) break;

					wr.mark();

					// 标签
					String val = w.text();
					if (wr.next().type() == colon) {
						wr.skip();
						if (labels.containsKey(val)) ctx.report(Kind.ERROR, "block.labelExist", val);
						if (!regionNew.isEmpty()) regionNew.getLast().add(val);

						if (imLabel == null) {
							imLabel = new Scope(cw.label());
							imLabel.breakTarget = new Label();
						}
						labels.put(val, imLabel);

						w = wr.next();
						continue;
					}

					wr.retract();
				}

				wr.retractWord();

				var expr = ep.parse(ExprParser.STOP_SEMICOLON|ExprParser.CHECK_VARIABLE_DECLARE|ExprParser.NAE);

				if (expr instanceof VariableDeclare vd) {
					wr.retractWord(); // next=变量名
					defineVariables(vd.type, false);
				} else {
					expr.resolve(ctx).writeStmt(cw);

					if (!wr.nextIf(semicolon) && !ctx.compiler.hasFeature(Compiler.OPTIONAL_SEMICOLON)) {
						wr.unexpected(w.text(), ";");
					}
				}
			}
		}

		if (imLabel != null) {
			// 不是循环语句
			if (imLabel.continueTarget == null) {
				cw.label(imLabel.breakTarget);
			} else {
				imLabel.continueTarget = null;
			}
			imLabel.breakTarget = null;
		}

		ctx.tmpAnnotations.clear();
		return;
		}
	}

	//region 注解
	private boolean getCompilerIntrinsic(String name, boolean def) {
		for (var annotation : ctx.tmpAnnotations) {
			String type = annotation.type();
			if (type.endsWith(name)) {
				ctx.compiler.fillAnnotationDefault(annotation);
				return annotation.getBool("value");
			}
		}
		return def;
	}
	private AStatementParser resolveAnnotationValues() {
		HashMap<String, Object> dup = ctx.tmpMap1, extra = ctx.tmpMap2;
		var missed = ctx.getTmpSet();
		AStatementParser statement = null;

		var list = ctx.tmpAnnotations;
		for (int i = 0; i < list.size(); i++) {
			var a = list.get(i);
			ctx.errorReportIndex = a.pos;

			var type = ctx.compiler.resolve(a.type());
			if (type.getAttribute(AStatementParser.NAME) instanceof AStatementParser stmt) {
				if (statement != null)
					ctx.report(Kind.ERROR, "block.annotation.duplicate", type);
				statement = stmt;
			}
			var desc = ctx.compiler.getAnnotationDescriptor(type);
			if (desc == null) {
				ctx.report(Kind.ERROR, "cu.annotation.not", type);
				continue;
			}

			if (0 == (desc.applicableTo()&AnnotationType.TYPE_USE)) {
				ctx.report(Kind.ERROR, "cu.annotation.notApplicable", type, "block.annotation.type");
			}

			if (desc.retention() != AnnotationType.SOURCE) {
				ctx.report(Kind.WARNING, "block.annotation.retentionNotSource");
			}

			dup.clear();
			extra.putAll(a.raw());

			for (Map.Entry<String, Type> entry : desc.elementType.entrySet()) {
				String name = entry.getKey();

				Object node = Helpers.cast(extra.remove(name));
				if (node instanceof Expr expr) a.raw().put(name, AnnotationBuilder.evaluate(ctx, expr, entry.getValue()));
				else if (node == null && !desc.elementDefault.containsKey(entry.getKey())) missed.add(name);
			}

			if (!extra.isEmpty()) ctx.report(Kind.ERROR, "cu.annotation.extra", type, extra.keySet());
			if (!missed.isEmpty()) ctx.report(Kind.ERROR, "cu.annotation.missing", type, missed);

			missed.clear();
			extra.clear();
		}

		dup.clear();
		extra.clear();
		ctx.errorReportIndex = -1;
		return statement;
	}
	//endregion
	// region Block, SectionFlag, 变量ID和作用域
	/**
	 * Unreachable statement检测
	 */
	private byte sectionFlag;
	private static final byte SF_BLOCK = 1, SF_SWITCH = 2, SF_FOREACH_VAR_DECL = 4;

	/**
	 * 好点的代码块
	 */
	private void blockV() throws ParseException {beginCodeBlock();block();}
	private void block() throws ParseException {
		sectionFlag |= SF_BLOCK;
		Token w;
		while ((w = wr.next()).type() != rBrace) {
			statement(w);
		}
		endCodeBlock();
	}

	private void blockOrStatement() throws ParseException {
		Token w = wr.next();
		if (w.type() == lBrace) {
			blockV();
		} else {
			sectionFlag &= ~SF_BLOCK;
			statement(w);
		}
	}
	/**
	 * will not resolve these expressions
	 */
	private void skipBlockOrStatement() throws ParseException {
		if (wr.nextIf(lBrace)) {
			int level = 1;
			while (true) {
				Token w = wr.next();
				if (w.type() == lBrace) level++;
				else if (w.type() == rBrace && --level == 0) break;
			}
		} else {
			var flag = sectionFlag;
			var prev = cw;
			setCw(cw.fork());

			visMap.enter(null);
			visMap.orElse();

			blockOrStatement();

			visMap.exit();
			setCw(prev);

			sectionFlag = flag;
			//ep.parse(ExprParser.STOP_SEMICOLON|ExprParser.SKIP_SEMICOLON);
		}
	}
	private void controlFlowTerminate() throws ParseException {
		if ((sectionFlag & SF_BLOCK) != 0) {
			if (wr.next().type() != rBrace) ctx.report(Kind.ERROR, "block.unreachable");
			wr.retractWord();
		}
	}

	// 当前作用域新增的东西
	private final ArrayList<ArrayList<Object>> regionNew = new ArrayList<>();
	private final HashMap<String, Variable> variables = new HashMap<>();

	private void beginCodeBlock() {
		ArrayList<Object> list = new ArrayList<>();
		list.add(cw.createExternalLabel());
		regionNew.add(list);
	}
	private void endCodeBlock() {
		var added = regionNew.pop();
		for (int i = 1; i < added.size(); i++) {
			Object var = added.get(i);
			if (var instanceof Variable v) {
				variables.remove(v.name);

				// should be monitored
				if (v.end == null) {
					if (!v.name.startsWith("@")) {
						if (!v.forceUsed)
							ctx.report(v.pos, Kind.WARNING, "block.var.unused", v.name);
						v.slot = -1; // magic, 如果这个变量只赋值，那么它的赋值语句会变成pop(see LazyLoadStore) // Optimizer ?
						continue;
					}

					cw.updateVariableScope(v);
				} else {
					cw.addLocal(v);
				}

				varMapper.add(v);
			} else {
				labels.remove(var.toString());
			}
		}

		// TODO 如果一个变量在其赋值过的区域内被再次赋值，（无前向依赖），那么它可以拆分为赋值前和赋值后两个变量，以方便VarMapper优化
		//  这个还可以反向传播，不过太麻烦了，先不去管
		//  可能需要SSA转换
		//  或者LoopNode
	}

	public Variable newVar(String name, IType type) {
		Variable v = new Variable(name, type);
		v.pos = wr.index;
		postDefineVar(type, v);
		return v;
	}
	private void postDefineVar(IType type, Variable v) {
		if (!v.name.startsWith("@")) {
			if (null != variables.putIfAbsent(v.name, v)) ctx.report(Kind.ERROR, "block.var.exist", v.name);
		} else {
			v.hasValue = true;
		}
		v.start = cw.createExternalLabel();

		if (!regionNew.isEmpty()) regionNew.getLast().add(v);
		else {
			v.slot = paramSize;
			v.forceUsed = true;
			v.hasValue = true;
			paramSize += type.rawType().length();
		}
	}
	// endregion
	//region 变量使用 (ExprNode -> CompileContext 的回调)
	public HashMap<String, Variable> getVariables() {return variables;}
	public VisMap vis() {return visMap;}
	/**
	 * 变量读取preHook
	 */
	public void loadVar(Variable v) {
		if (!visMap.hasValue(v)) {
			ctx.report(Kind.ERROR, "var.notAssigned", v.name);
		}
		if (varInLoop != null) varInLoop.add(v.name);
		cw.updateVariableScope(v);
	}
	/**
	 * 变量赋值preHook
	 */
	public void storeVar(Variable v) {
		if (v.implicitCopied) {
			ctx.report(Kind.NOTE, "var.refByNest", v.name);
			v.implicitCopied = false;
		}
		visMap.assign(v);
		if (varInLoop != null) varInLoop.add(v.name);
		cw.updateVariableScope(v);
	}
	/**
	 * 常量传播(resolve阶段变量赋值hook)
	 * 注意: storeVar一定会在这之后调用
	 * 注意: 也许有bug, 因为resolve和write之前隔了十万八千里
	 */
	public void assignVar(Variable v, Object o) {
		if (ctx.compiler.hasFeature(Compiler.CONSTANT_SPREAD)) {
			v.value = Objects.requireNonNull(o);
			// update [varDefined]
			visMap.assign(v);
		}
	}
	public Variable tempVar(IType type) {return newVar("@tmp", type);}
	//endregion
	// try-finally和synchronized重定向return/break需要的字段
	private ArrayList<IType> checkedExceptions;
	private TryNode deferNode;
	// TODO 需要解决的问题
	//  1. 非静态类的泛型语法
	//  2. while不再使用那个弃用的方法
	//  新增Feature
	//  3. 基于模板的无包装泛型
	//  4. 自动Constexpr
	private FlowHook flowHook;

	// 也是回调 抓住能被try捕获的异常
	public boolean addException(IType type) {
		if (checkedExceptions != null) {
			checkedExceptions.add(type);
			return true;
		}
		return false;
	}
	// region 异常: try-catch-finally try-with-resource
	private static final int TRY_WITH_RESOURCE = 1, TRY_ANY_CATCH = 2, TRY_EMPTY = 4;
	private void _try(Scope imLabel) throws ParseException {
		var prevExceptions = checkedExceptions;
		var prevTryNode = deferNode;
		checkedExceptions = new ArrayList<>();

		final boolean MODERN_TWR = this.getCompilerIntrinsic("ModernTWR", true);

		int flags = 0;

		var tryNode = deferNode = new TryNode();
		tryNode.parent = flowHook;
		flowHook = tryNode;

		tryNode.placeholderId = cw.nextSegmentId();
		cw.addSegment(StaticSegment.EMPTY);

		Label tryBegin = cw.label();

		Token w = wr.next();
		//region try body
		if (w.type() != lBrace) {
			if (w.type() != lParen) wr.unexpected(w.text(), "block.except.tryOrAuto");

			beginCodeBlock();
			if (MODERN_TWR) initDeferList();

			do {
				w = wr.next();

				IType type;
				if (w.text().equals("var") || w.text().equals("const")) {
					type = null;
				} else {
					wr.retractWord();
					file.readModifiers(wr, FINAL);
					type = ctx.resolveType(file.readType(CompileUnit.TYPE_GENERIC));
				}

				String name = wr.except(Token.LITERAL).text();

				except(assign);
				var expr = ep.parse(ExprParser.STOP_RSB|ExprParser.STOP_SEMICOLON);
				if (expr == null) {
					ctx.report(Kind.ERROR, "expr.illegalStart");
				} else {
					Expr node = expr.resolve(ctx);
					if (type == null) type = node.type();

					var closeable = newVar(name, type);
					closeable.isFinal = true;
					closeable.hasValue = true;

					ctx.writeCast(cw, node, Types.AUTOCLOSEABLE_TYPE);
					cw.store(closeable);

					if (MODERN_TWR) {
						cw.load(tryNode.listVar);
						cw.load(closeable);
						cw.invokeV(DeferList.CLASS_NAME, "add", "(Ljava/lang/Object;)V");
					} else {
						tryNode.add(closeable, cw.label());
					}
				}

				w = wr.next();
			} while (w.type() == semicolon);
			if (w.type() != rParen) wr.unexpected(w.text(), "block.except.tryEnd");

			flags |= TRY_WITH_RESOURCE;

			except(lBrace);
			visMap.enter(imLabel);
			block();
		} else {
			visMap.enter(imLabel);
			blockV();
		}
		//endregion

		deferNode = prevTryNode;
		// check for catch
		var _checkedExceptions = checkedExceptions;
		checkedExceptions = prevExceptions;

		Label tryEnd = cw.label();
		if (tryBegin.equals(tryEnd)) flags |= TRY_EMPTY;

		Label blockEnd = new Label();
		boolean anyNormal = cw.isContinuousControlFlow(), nowNormal = anyNormal;

		if (tryNode.placeholderId < 0) {
			// TODO i18n
			if (!MODERN_TWR) ctx.report(Kind.ERROR, "错误：defer无法和传统TWR实现共存");

			// 同时处理 defer 和 TWR
			dispatchDeferListTWR(anyNormal, tryNode, tryBegin, tryEnd, blockEnd);
		} else if ((flags&TRY_WITH_RESOURCE) != 0) {
			ctx.report(Kind.WARNING, "警告：传统TWR实现复杂度过高，不再维护，出现任何字节码错误请直接交PR，issue不会被处理");
			dispatchLegacyTWR(anyNormal, tryNode, tryBegin, tryEnd, blockEnd);
		} else if (anyNormal) {
			cw.jump(blockEnd);
		}

		// region catch
		ArrayList<String> exTypes = new ArrayList<>();
		while ((w = wr.next()).type() == CATCH) {
			wr.except(lParen, "block.except.tryOrAuto");

			if ((flags&TRY_ANY_CATCH) != 0) ctx.report(Kind.ERROR, "block.try.duplicateCatch");

			TryCatchBlock entry = cw.addException(tryBegin,tryEnd,cw.label(),null);
			visMap.orElse();
			beginCodeBlock();

			var type = file.readType(0);
			w = wr.next();
			if (w.type() == Token.LITERAL) {
				var cast = ctx.castTo(ctx.resolveType(type), Types.THROWABLE_TYPE, 0);
				entry.type = type.owner();

				// 不可能 if readType flag=0
				if (type.kind() != IType.SIMPLE_TYPE) {
					ctx.report(Kind.ERROR, "block.try.generic", type);
				}

				for (int i = 0; i < exTypes.size(); i++) {
					if (ctx.compiler.instanceOf(entry.type, exTypes.get(i))) {
						ctx.report(Kind.ERROR, "block.try.captured", type, exTypes.get(i));
						break;
					}
				}

				if (!ctx.compiler.hasFeature(Compiler.OMIT_CHECKED_EXCEPTION)
					&& !ctx.compiler.instanceOf(entry.type, "java/lang/RuntimeException")) {

					boolean notFound = true;
					for (int i = _checkedExceptions.size() - 1; i >= 0; i--) {
						if (ctx.compiler.instanceOf(_checkedExceptions.get(i).owner(), entry.type)) {
							_checkedExceptions.remove(i);
							notFound = false;
						}
					}

					if (notFound) ctx.report(Kind.ERROR, "block.try.cantThrow", entry.type);
				}

				exTypes.add(entry.type);

				if ("java/lang/Throwable".equals(entry.type)) flags |= TRY_ANY_CATCH;

				if (w.text().equals(VARIABLE_IGNORE)) cw.insn(POP);
				else {
					Variable v = newVar(w.text(), type);
					// QoL for IDEA
					if (w.text().equals("ignored")) v.forceUsed = true;
					v.hasValue = true;
					cast.write(cw);
					cw.store(v);
				}

				except(rParen);
			} else {
				if (w.type() != rParen) throw wr.err("block.except.name");

				//entry.type = TryCatchEntry.ANY;
				_checkedExceptions.clear();
				flags |= TRY_ANY_CATCH;
				Variable v = newVar(type.owner(), Types.THROWABLE_TYPE);
				v.hasValue = true;
				cw.store(v);
			}

			except(lBrace);
			if ((flags&TRY_EMPTY) != 0) {
				skipBlockOrStatement();
				endCodeBlock();
				continue;
			}
			block();

			nowNormal = cw.isContinuousControlFlow();
			if (nowNormal) {
				cw.jump(blockEnd);
				anyNormal = true;
			}
		}
		// endregion

		for (int i = 0; i < _checkedExceptions.size(); i++) {
			ctx.addException(_checkedExceptions.get(i));
		}

		flowHook = tryNode.parent;

		if (w.type() == FINALLY) {
			visMap.orElse();

			boolean disableOptimization = getCompilerIntrinsic("DisableOptimization", flowHook == null);

			beginCodeBlock();
			except(lBrace);

			Label finallyHandler = new Label();
			cw.addException(tryBegin, cw.label(), finallyHandler, TryCatchBlock.ANY);

			if (disableOptimization) {
				blockEnd = writeFinallyCopied(tryNode, tryBegin, blockEnd, finallyHandler, anyNormal);
			} else {
				blockEnd = writeFinallyDispatched(tryNode, tryBegin, blockEnd, finallyHandler, anyNormal, nowNormal);
			}

			visMap.terminate();
		} else {
			wr.retractWord();
			if ((flags&TRY_WITH_RESOURCE) == 0) {
				// 孤立的try
				if (exTypes.isEmpty() && (flags&TRY_ANY_CATCH) == 0) ctx.report(Kind.ERROR, "block.try.noHandler");

				if (flowHook != null) {
					flowHook.returnHook.addAll(tryNode.returnHook);
				} else {
					StaticSegment ret = new StaticSegment(returnType.getOpcode(IRETURN));
					for (int i = 0; i < tryNode.returnHook.size(); i++) cw.replaceSegment(tryNode.returnHook.get(i), ret);
				}
			} else if (!MODERN_TWR) {
				cw.label(tryNode.returnTarget);
			}
		}

		visMap.exit();

		if (anyNormal) cw.label(blockEnd);
		else controlFlowTerminate();
	}

	private Label writeFinallyCopied(TryNode tryNode, Label tryBegin, Label blockEnd, Label finallyHandler, boolean anyNormal) throws ParseException {
		MethodWriter finallyBlock = cw.fork();
		MethodWriter prev = cw;

		setCw(finallyBlock);
		block();
		setCw(prev);

		int copyCount = 0;
		int finallyLength = finallyBlock.bci();
		var finallyCanComplete = finallyBlock.isContinuousControlFlow();
		if (!finallyCanComplete)
			ctx.report(Kind.WARNING, "block.try.cantComplete");

		// 副本的: 正常执行
		if (anyNormal) {
			copyCount++;

			cw.label(blockEnd);
			finallyBlock.writeTo(cw);

			blockEnd = new Label();

			if (finallyCanComplete)
				cw.jump(blockEnd);
		}

		// 副本: 异常处理
		Variable exc = newVar("@异常", Types.OBJECT_TYPE);
		int pos = wr.index;
		cw.label(finallyHandler);
		cw.store(exc);

		finallyBlock.writeTo(cw);

		if (finallyCanComplete) {
			cw.load(exc);
			cw.insn(ATHROW);
		}

		// 副本: return/break
		copyCount += tryNode.finallyExecute(cw, tryBegin, finallyBlock::writeTo);

		// 这个magic number是新finally方式的最大overhead
		if (copyCount*finallyLength >= 36) {
			ctx.report(pos, Kind.WARNING, "block.try.waste", finallyLength, copyCount);
		}
		return blockEnd;
	}

	/**
	 * 实现基于状态机的 finally 逻辑。
	 * <p>
	 * 该方法会生成一个统一的 finally 处理区域，并在进入该区域前，通过 {@code procedureIdVar}
	 * 记录来源（正常完成、return、break、或异常抛出），并在 finally 执行完毕后根据该 ID 进行跳转。
	 * </p>
	 *
	 * @param tryNode        FlowHook Node
	 * @param tryBegin       try 块起始位置。
	 * @param blockEnd       try 块结束位置。
	 * @param finallyHandler 异常处理器中 finally 的入口。
	 * @param anyNormal      try 块是否可能正常执行结束（goto blockEnd）。
	 * @param nowNormal      try 块内最后一个语句没有终止控制流（是goto blockEnd）。
	 * @return 返回一个新的 {@code Label}，代表 finally 及其后续分发逻辑执行完毕后的正常流向终点。
	 */
	private Label writeFinallyDispatched(TryNode tryNode, Label tryBegin, Label blockEnd, Label finallyHandler, boolean anyNormal, boolean nowNormal) throws ParseException {
		Variable procedureIdVar = newVar("@跳转自", Type.INT_TYPE);
		// TODO 支持非对象 ACONST_NULL 需要改
		Variable returnValue = returnType.type == Type.VOID ? null : newVar("@返回值", Types.OBJECT_TYPE);
		Label optimizedFinallyHandler = new Label();
		int procedureId = 0;

		Variable exc = newVar("@异常", Types.OBJECT_TYPE);
		MethodWriter finallyBlock = null;

		int diagnosticPos = wr.index;
		if (finallyBlock == null) {
			finallyBlock = cw.fork();
			MethodWriter prev = cw;
			setCw(finallyBlock);
			block();
			setCw(prev);
		}

		var finallyCanComplete = finallyBlock.isContinuousControlFlow();
		if (!finallyCanComplete)
			ctx.report(diagnosticPos, Kind.WARNING, "block.try.cantComplete");

		// 副本: 正常执行
		if (anyNormal) {
			if (nowNormal) // 删掉一个多余的跳转
				cw.replaceSegment(cw.nextSegmentId()-1, StaticSegment.EMPTY);

			cw.label(blockEnd);
			if (finallyCanComplete) {
				cw.ldc(procedureId++);
				cw.store(procedureIdVar);
				if (returnValue != null) {
					cw.insn(ACONST_NULL);
					cw.store(returnValue);
				}
				cw.insn(ACONST_NULL);
				cw.store(exc);
			}
			cw.jump(optimizedFinallyHandler);
		}

		// 副本: return
		if (tryNode.returnHook.size() > 0) {
			cw.label(tryNode.returnTarget);

			if (returnValue != null) cw.store(returnValue);
			if (finallyCanComplete) {
				cw.ldc(procedureId++);
				cw.store(procedureIdVar);
				cw.insn(ACONST_NULL);
				cw.store(exc);
			}
			cw.jump(optimizedFinallyHandler);
		}

		// 副本: break
		ToIntMap<Label> breakHookId;
		IntList breakHook = tryNode.breakHook;
		if (breakHook.size() > 0) {
			Arrays.sort(breakHook.getRawArray(), 0, breakHook.size());

			breakHookId = new ToIntMap<>();
			breakHookId.setHasher(Hasher.identity());

			for (int i = 0; i < breakHook.size(); i++) {
				var segmentId = breakHook.get(i);
				Label target = ((JumpTo) cw.getSegment(segmentId)).target;
				if (target.isBound() && target.compareTo(tryBegin) >= 0) continue;

				int _branchId = breakHookId.getOrDefault(target, 0);
				if (_branchId == 0) breakHookId.putInt(target, _branchId = procedureId++);

				var trampoline = cw.fork();
				if (finallyCanComplete) {
					trampoline.ldc(_branchId);
					trampoline.store(procedureIdVar);
					if (returnValue != null) {
						trampoline.insn(ACONST_NULL);
						trampoline.store(returnValue);
					}
					trampoline.insn(ACONST_NULL);
					trampoline.store(exc);
				}
				trampoline.jump(optimizedFinallyHandler);

				cw.replaceSegment(segmentId, trampoline);
			}
		} else {
			breakHookId = null;
		}

		// 副本: 异常
		cw.label(finallyHandler);
		cw.store(exc);
		if (finallyCanComplete) {
			cw.insn(ICONST_M1);
			cw.store(procedureIdVar);
			if (returnValue != null) {
				cw.insn(ACONST_NULL);
				cw.store(returnValue);
			}
		}

		// finally不再复制
		cw.label(optimizedFinallyHandler);
		finallyBlock.writeTo(cw);

		blockEnd = new Label();
		// finally可以执行完
		done:
		if (finallyCanComplete) {
			if (breakHookId != null) {
				cw.load(procedureIdVar);
				var segment = cw.tableSwitch();

				segment.def = cw.label();
				cw.load(exc);
				cw.insn(ATHROW);

				procedureId = 0;
				if (anyNormal) segment.branch(procedureId++, blockEnd);
				if (tryNode.returnHook.size() > 0) {
					segment.branch(procedureId, cw.label());
					if (returnValue != null) cw.load(returnValue);
					_returnHook();
				}

				if (flowHook != null) {
					for (var entry : breakHookId.selfEntrySet()) {
						segment.branch(entry.value, cw.label());
						flowHook.breakHook.add(cw.nextSegmentId());
						cw.jump(entry.getKey());
					}
				} else {
					for (var entry : breakHookId.selfEntrySet()) {
						segment.branch(entry.value, entry.getKey());
					}
				}

				break done;
			}

			if (tryNode.returnHook.size() > 0) {
				Label returnHook = new Label();

				cw.load(procedureIdVar);
				// 匹配branchId
				cw.jump(anyNormal ? IFLE : IFLT, returnHook);

				// procedureIdVar > 0 : return hook
				if (returnValue != null) cw.load(returnValue);

				_returnHook();

				cw.label(returnHook);
			}

			if (anyNormal) {
				cw.load(procedureIdVar);
				cw.jump(IFEQ, blockEnd);

				// procedureIdVar = 0 : normal execution
			}

			// procedureIdVar < 0 : exception
			cw.load(exc);
			cw.insn(ATHROW);
		} else {
			controlFlowTerminate();
		}
		return blockEnd;
	}

	private void _returnHook() {
		if (flowHook != null) {
			flowHook.returnHook.add(cw.nextSegmentId());
			cw.jump(flowHook.returnTarget);
		} else {
			cw.insn(returnType.getOpcode(IRETURN));
		}
	}

	private void _defer() throws ParseException {
		if (deferNode == null) ctx.report(Kind.ERROR, "block.try.noDefer");
		else {
			if (deferNode.placeholderId >= 0) initDeferList();

			Expr lambda = ctx.ep.lambda(wr, Collections.emptyList(), ExprParser.STOP_SEMICOLON | ExprParser.SKIP_SEMICOLON | ExprParser.NAE);

			cw.load(deferNode.listVar);
			// TODO 如果lambda没有外部依赖和闭包，我们可以把它内联，然后 triggeredDefers |= X; 根据 bitset 执行代码
			//   感觉这很麻烦，而且收益不大，以后再说
			ctx.writeCast(cw, lambda, Type.klass("java/lang/Runnable"));
			cw.invokeV(DeferList.CLASS_NAME, "add", "(Ljava/lang/Object;)V");
		}
	}

	private void initDeferList() {
		var listVar = newVar("@deferList", Types.OBJECT_TYPE);
		//regionNew.getLast().pop();

		var listSizeVar = newVar("@deferListSize", Type.INT_TYPE);
		//regionNew.getLast().pop();

		deferNode.listVar = listVar;
		deferNode.listSizeVar = listSizeVar;

		int delta = deferNode.placeholderId - listVar.start.getBlock();
		listVar.start.__move(delta);
		listSizeVar.start.__move(delta);

		var fork = cw.fork();
		fork.invokeS(DeferList.CLASS_NAME, "get", "()L"+DeferList.CLASS_NAME+";");
		fork.store(listVar);
		fork.load(listVar);
		fork.invokeV(DeferList.CLASS_NAME, "size", "()I");
		fork.store(listSizeVar);

		cw.replaceSegment(deferNode.placeholderId, fork);
		deferNode.placeholderId = -1;
	}

	private void dispatchDeferListTWR(boolean mayNormallyComplete, TryNode tryNode, Label tryStart, Label tryEnd, Label blockEnd) {
		Consumer<MethodWriter> defer = mw -> {
			mw.insn(ACONST_NULL);
			mw.load(tryNode.listVar);
			mw.load(tryNode.listSizeVar);
			mw.invokeS(DeferList.CLASS_NAME, "defer", "(Ljava/lang/Throwable;L"+DeferList.CLASS_NAME+";I)V");
		};

		if (mayNormallyComplete) {
			defer.accept(cw);
			cw.jump(blockEnd);
		}

		cw.addException(tryStart, tryEnd, cw.label(), TryCatchBlock.ANY);
		cw.load(tryNode.listVar);
		cw.load(tryNode.listSizeVar);
		cw.invokeS(DeferList.CLASS_NAME, "defer", "(Ljava/lang/Throwable;L"+DeferList.CLASS_NAME+";I)V");
		cw.insn(ACONST_NULL);
		cw.insn(ATHROW);

		tryNode.finallyExecute(cw, tryStart, defer);
	}

	private void dispatchLegacyTWR(boolean anyNormal, TryNode tn, Label tryStart, Label tryEnd, Label blockEnd) {
		// 这个方法已经改过很多次，当初为了性能写的代码已经腐烂了
		// 目前来说它能处理不嵌套的try-with-resource，但是如果有其他FlowHook (edge case)很可能会炸
		// 你看tryStart不知道为啥都没用到，我先留着万一哪天用到呢（会吗？）

		int moreSituations = 0;
		if (anyNormal) moreSituations++;
		if (flowHook.returnHook.size() > 0) moreSituations++;

		var sri = moreSituations < 2 ? null : newVar("@跳转自", Type.INT_TYPE);
		//var exc = newVar("@异常", Types.OBJECT_TYPE);

		if (sri != null) {
			var label = new Label();
			cw.ldc(0);
			cw.store(sri);
			cw.jump(label);

			cw.label(flowHook.returnTarget);
			cw.ldc(1);
			cw.store(sri);

			cw.label(label);
		} else if (flowHook.returnHook.size() > 0) {
			cw.label(flowHook.returnTarget);
		}

		var vars = tn.resources;
		Label[] normalHandlers;
		if (moreSituations > 0) {
			Label prev_nc = null;

			normalHandlers = ctx.getTmpLabels(vars.size() << 1);
			for (int i = vars.size()-1; i >= 0; i--) {
				var v = vars.get(i);

				Label nc = new Label();
				//    TCE [a2_normal_close, a1_normal_close, @local] => LABEL1
				if (prev_nc != null) {
					normalHandlers[i*2] = prev_nc;
					normalHandlers[i*2 + 1] = new Label();
				}
				prev_nc = nc;

				invokeClose(v, false);

				cw.label(nc);
			}

			if (sri != null) {
				cw.load(sri);
				cw.jump(IFEQ, blockEnd);
				cw.jump(flowHook.returnTarget = new Label());
			} else {
				cw.jump(anyNormal ? blockEnd : (flowHook.returnTarget = new Label()));
			}
		} else {
			// 如果这个try不存在控制流直接到它下方
			// 感觉这也全是bug
			normalHandlers = null;
		}

		var pos = tn.pos;
		pos.add(0, tryStart);
		int i = pos.size()-1;
		cw.addException(pos.get(i--), tryEnd, cw.label(), TryCatchBlock.ANY);

		while (true) {
			var o = vars.get(i);

			cw.load(o);
			cw.invokeS(RtUtil.CLASS_NAME, "twr", "(Ljava/lang/Throwable;Ljava/lang/AutoCloseable;)Ljava/lang/Throwable;");

			if (i == 0) {
				cw.insn(ATHROW);
				break;
			}

			i--;

			if (normalHandlers == null) {
				cw.addException(pos.get(i), pos.get(i+1), cw.label(), TryCatchBlock.ANY);
			} else {
				Label ph_start = normalHandlers[i*2], ph_handler = normalHandlers[i*2 + 1];
				cw.label(ph_handler);
				cw.addException(pos.get(i), ph_start, ph_handler, TryCatchBlock.ANY);
			}
		}
	}
	private void invokeClose(Variable v, boolean report) {
		ClassNode info = ctx.compiler.resolve(v.type.owner());
		var result = ctx.compiler.getMethodList(info, "close").findMethod(ctx, Collections.emptyList(), 0);
		assert result != null;

		if (report) result.addExceptions(ctx, true);

		cw.load(v);
		cw.invoke((result.method.modifier&ACC_INTERFACE) != 0 ? INVOKEINTERFACE : INVOKEVIRTUAL, result.method);
	}
	// endregion
	// 带名称的label
	private final HashMap<String, Scope> labels = new HashMap<>();
	// 当前代码块可用的break和continue目标
	private Label imBreak, imContinue;
	// VisMap变量定义状态
	private final ArrayList<VisMap.State> imPendingVis = new ArrayList<>();
	// region goto
	private void _goto() throws ParseException {
		var w = wr.except(Token.LITERAL);
		var info = labels.get(w.text());
		if (info != null && info.entry != null) {
			if (flowHook != null) flowHook.breakHook.add(cw.nextSegmentId());
			cw.jump(info.entry);
			info.onBreak(visMap.jump());
		} else {
			ctx.report(Kind.ERROR, "block.goto.noSuchLabel", w.text());
		}
		except(semicolon);
		controlFlowTerminate();
	}
	//endregion
	//region break & continue
	/**
	 * @param isBreak true => break, false => continue
	 */
	private void _break(boolean isBreak) throws ParseException {
		var errId = wr.current().text();

		if ((sectionFlag&SF_SWITCH) != 0) ctx.report(Kind.ERROR, "block.switch.exprMode", errId);

		var w = wr.next();
		if (w.type() == Token.LITERAL) {
			var info = labels.get(w.text());
			Label node;
			if (info != null && (node = isBreak ? info.breakTarget : info.continueTarget) != null) {
				if (flowHook != null) flowHook.breakHook.add(cw.nextSegmentId());
				cw.jump(node);

				if (isBreak) info.onBreak(visMap.jump());
				else visMap.terminate();
			} else {
				ctx.report(Kind.ERROR, "block.goto.noSuchLabel", w.text());
			}

			except(semicolon);
		} else if (w.type() == semicolon) {
			Label node = isBreak ? imBreak : imContinue;
			if (node != null) {
				if (flowHook != null) flowHook.breakHook.add(cw.nextSegmentId());
				cw.jump(node);

				if (isBreak) imPendingVis.add(visMap.jump());
				else visMap.terminate();
			} else {
				ctx.report(Kind.ERROR, "block.goto.outsideLoop", errId);
			}
		} else {
			throw wr.err("block.except.labelOrSemi");
		}

		controlFlowTerminate();
	}
	//endregion
	//region return
	private void _return() throws ParseException {
		if (methodType == MT_BLOCK) ctx.report(Kind.ERROR, "block.return.outsideMethod");

		RawExpr expr;
		if (!wr.nextIf(semicolon)) {
			expr = ep.parse(ExprParser._ENV_TYPED_ARRAY|ExprParser.STOP_SEMICOLON|ExprParser.SKIP_SEMICOLON|ExprParser.NAE);

			ctx.inReturn = true;
			Expr node = expr.resolve(ctx);
			ctx.writeCast(cw, node, returnTypeG);
			ctx.inReturn = false;
		} else {
			expr = null;
		}

		var rt = returnType;
		if (rt.type == Type.VOID) {
			if (expr != null && methodType != MT_EXPRESSION_LAMBDA) {
				ctx.report(Kind.ERROR, "block.return.exceptVoid");
			}
		} else if (expr == null) {
			ctx.report(Kind.ERROR, "block.return.exceptExpr");
		}

		if (flowHook != null) {
			if ((sectionFlag&SF_SWITCH) != 0) ctx.report(Kind.ERROR, "block.switch.exprMode.return");

			if (RETURNSTACK_TYPE.equals(rt.owner))
				cw.invokeV(RETURNSTACK_TYPE, "toImmutable", "()L"+RETURNSTACK_TYPE+";");

			flowHook.returnHook.add(cw.nextSegmentId());
			cw.jump(flowHook.returnTarget);
		} else {
			cw.insn(rt.getOpcode(IRETURN));
		}

		visMap.terminate();
		controlFlowTerminate();
	}
	//endregion
	//region throw
	private void _throw() throws ParseException {
		var expr = ep.parse(ExprParser.STOP_SEMICOLON|ExprParser.SKIP_SEMICOLON);
		if (expr == null) {
			ctx.report(Kind.ERROR, "expr.illegalStart");
		} else {
			var node = expr.resolve(ctx);
			ctx.writeCast(cw, node, Types.THROWABLE_TYPE);
			ctx.addException(node.type());
		}

		cw.insn(ATHROW);
		visMap.terminate();
		controlFlowTerminate();
	}
	// endregion
	// region 条件判断: if / generic-condition
	private void _if(Scope imLabel) throws ParseException {
		Label ifFalse = condition(null, 0);
		int hint = constantHint;

		visMap.enter(imLabel);

		if (hint < 0) skipBlockOrStatement();
		else {
			blockOrStatement();
			visMap.orElse();
		}

		// if (xx) {} else if() {}
		//      is equivalent to
		// if (xx) {} else { if() {} }
		if (!wr.nextIf(ELSE)) {
			cw.label(ifFalse);
		} else {
			Label end;
			if (hint == 0 && cw.isContinuousControlFlow()) {
				cw.jump(end = new Label());
			} else {
				end = null;
			}

			// if goto else goto 由MethodWriter处理
			cw.label(ifFalse);
			if (hint > 0) skipBlockOrStatement();
			else {
				blockOrStatement();
				visMap.orElse();
			}
			if (end != null) cw.label(end);
		}

		visMap.terminate();
		visMap.exit();
	}

	// 宏的一部分，忽略常量部分的所有内容，其它的处理还在Trinary和switch
	private int constantHint;
	/**
	 * 条件判断
	 * @return 不成立的跳转目标
	 */
	@NotNull
	private Label condition(Label target, int mode) throws ParseException {
		boolean checkLSB = mode != /*is not FOR*/1;
		if (checkLSB) except(lParen);

		if (target == null) target = new Label();

		constantHint = 0;
		RawExpr expr = ep.parse((checkLSB ? ExprParser.STOP_RSB|ExprParser.SKIP_RSB : ExprParser.STOP_SEMICOLON));
		if (expr == null) {
			if (checkLSB) ctx.report(Kind.ERROR, "expr.illegalStart");
		} else {
			Expr node = expr.resolve(ctx);
			if (node.isConstant() && node.type() == Type.BOOLEAN_TYPE) {
				boolean flag = ((ConfigValue) node.constVal()).asBool();
				constantHint = flag ? 1 : -1;

				if (node.hasFeature(Expr.Feature.CONSTANT_WRITABLE)) {
					if (mode != 0) ctx.report(Kind.ERROR, "expr.constantWritable.ifOnly");
					node.writeStmt(cw);
				}
			} else {
				var r = ctx.castTo(node.type(), Type.BOOLEAN_TYPE, 0);
				if (r.type >= 0) node.writeShortCircuit(cw, r, mode == 2/*WHILE*/, target);
			}
		}

		if(!checkLSB) except(semicolon);
		return target;
	}
	// endregion
	// region 循环: for while do-while
	private void loopBody(@Nullable Scope immediateLabel, @Nullable Label continueTo, @NotNull Label breakTo) throws ParseException {
		if (immediateLabel != null) {
			immediateLabel.breakTarget = breakTo;
			immediateLabel.continueTarget = continueTo;
		}

		Label prevBreak = imBreak, prevContinue = imContinue;
		int combineSize = imPendingVis.size();
		var prevCol = varInLoop;
		if (prevCol == null) varInLoop = new HashSet<>();

		if (continueTo != null) imContinue = continueTo;
		imBreak = breakTo;

		blockOrStatement();

		// [20241207] 如果在循环体中使用了一个变量，那么将其范围提升至循环块末尾
		//		也许以后可以优化，不过目前生成LVT足够了，就是反编译器不认，这不是我的问题
		for (var itr = varInLoop.iterator(); itr.hasNext(); ) {
			var v = variables.get(itr.next());
			if (v != null) cw.updateVariableScope(v);
			else itr.remove();
		}
		varInLoop = prevCol;

		while (combineSize < imPendingVis.size()) {
			visMap.orElse(imPendingVis.pop());
		}
		imContinue = prevContinue;
		imBreak = prevBreak;
	}

	private void _for(Scope imLabel) throws ParseException {
		boolean disableOptimization = getCompilerIntrinsic("DisableOptimization", false);
		except(lParen);

		Token w = wr.next();
		boolean hasVar;
		Label continueTo, breakTo, nBreakTo;
		RawExpr execLast;
		int hint = 0;

		// for (var i = 0; i < len; i++) => keep i and len
		var prevCol = varInLoop;
		if (prevCol == null) varInLoop = new HashSet<>();

		NoForEach:{
		if (w.type() != semicolon) {
			beginCodeBlock();
			sectionFlag |= SF_FOREACH_VAR_DECL;
			statement(w);
			sectionFlag &= ~SF_FOREACH_VAR_DECL;
			hasVar = true;

			// region ForEach for (Vtype vname : expr) {}
			if (wr.nextIf(colon)) {
				Variable lastVar = null;

				int varFound = 0;
				var rn = regionNew.getLast();
				for (int i = 0; i < rn.size(); i++) {
					if (rn.get(i) instanceof Variable v) {
						lastVar = v;
						if (varFound++ > 0)
							ctx.report(Kind.ERROR, "block.for.manyVariablesInForeach", varFound);
					}
				}
				assert lastVar != null;

				Expr iter = ep.parse(ExprParser.STOP_RSB|ExprParser.SKIP_RSB|ExprParser.NAE).resolve(ctx);
				IType type = iter.type();
				ClassNode owner;
				TypeCast.Cast cast = null;

				breakTo = CodeWriter.newLabel();
				if (type.array() == 0) {
					if (type.isPrimitive()) {
						ctx.report(Kind.ERROR, "symbol.derefPrimitive", type);
						skipBlockOrStatement();
						return;
					}

					owner = ctx.resolve(type);
					if (owner == null) {skipBlockOrStatement();return;}

					int iterableMode = ctx.compiler.link(owner).getIterableMode(ctx.compiler);
					if (iterableMode == 2) { // 2=LavaRandomAccessible
						var result = ctx.getMethodListOrReport(owner, "get", iter).findMethod(ctx, type, Collections.singletonList(Type.INT_TYPE), 0);
						if (result.error != null) {skipBlockOrStatement();return;}

						IType inferredType = result.returnType();

						if (lastVar.type == Types.anyType) {
							lastVar.type = inferredType;
						} else {
							if (ctx.castTo(inferredType, lastVar.type, 0).type < 0) {
								skipBlockOrStatement();
								return;
							}
						}
					} else {
						String genericCastCheck = iterableMode == -2 ? "java/util/Iterator" : "java/lang/Iterable";

						// -3=unknown, -2=Iterator, -1=Iterable
						if (ctx.castTo(type, new ParameterizedType(genericCastCheck, Collections.singletonList(lastVar.type)), 0).type < 0) {
							skipBlockOrStatement();
							return;
						}

						// var x : t 获取正确类型
						if (lastVar.type == Types.anyType) lastVar.type = ctx.compiler.inferGeneric(type, genericCastCheck).get(0);
					}

					// iterable
					if (disableOptimization || iterableMode < 0) {
						Variable _itr = newVar("@迭代器", Types.ITERATOR_TYPE);
						iter.write(cw);

						var isInterface = (owner.modifier()&ACC_INTERFACE) != 0;

						// -2=Iterator
						String itrType = owner.name();
						if (iterableMode != -2) {
							cw.invoke(isInterface ? INVOKEINTERFACE : INVOKEVIRTUAL, itrType, "iterator", "()Ljava/util/Iterator;", false);
							itrType = "java/util/Iterator";
							isInterface = true;
						}

						cw.store(_itr);

						continueTo = cw.label();
						execLast = null;

						cw.load(_itr);
						cw.invoke(isInterface ? INVOKEINTERFACE : INVOKEVIRTUAL, itrType, "hasNext", "()Z", false);
						cw.jump(IFEQ, breakTo);

						cw.load(_itr);
						Type elementType = lastVar.type.rawType();
						if (!elementType.isPrimitive()) {
							// 20250409 如果有重载，要不要调用更具体的重载并省略checkcast？
							cw.invoke(isInterface ? INVOKEINTERFACE : INVOKEVIRTUAL, itrType, "next", "()Ljava/lang/Object;", false);
							cw.clazz(CHECKCAST, elementType);
						} else {
							// 20250708 nextLong etc, 这样和PrimitiveIterator兼容
							cw.invoke(isInterface ? INVOKEINTERFACE : INVOKEVIRTUAL, itrType, "next"+elementType.capitalized(), "()"+(char)elementType.type, false);
						}
						cw.store(lastVar);

						break NoForEach;
					}
					// 1=RandomAccessList, 2=LavaRandomAccessible => indexed list access
				} else {
					// array access
					cast = ctx.castTo(TypeHelper.componentType(type), lastVar.type, 0);
					owner = null; // checked below
				}

				Variable _arr;
				if (iter instanceof LocalVariable lv) {
					_arr = lv.getVariable();
				} else {
					_arr = newVar("@数组表达式的结果", type);
					iter.write(cw);
					cw.store(_arr);
				}
				Variable _i = newVar("@索引", Type.INT_TYPE);
				Variable _len = newVar("@数组长度", Type.INT_TYPE);

				// type[] __var = expr;
				// int __i = 0;
				// int __len = __var.length;
				cw.load(_arr);
				if (owner != null) {
					var result = ctx.compiler.getMethodList(owner, "size").findMethod(ctx, type, Collections.emptyList(), 0);
					if (result != null) MethodResult.writeInvoke(result.method, ctx, cw);
				} else {
					cw.insn(ARRAYLENGTH);
				}
				cw.store(_len);
				cw.insn(ICONST_0);
				cw.store(_i);

				continueTo = cw.label();
				execLast = ctx.ep.postfixOp(inc, new LocalVariable(_i));

				// :continue_to
				cw.load(_i);
				cw.load(_len);
				cw.jump(IF_icmpge, breakTo);

				cw.load(_arr);
				cw.load(_i);
				if (owner != null) {
					// 检查可能存在的override
					var result = ctx.compiler.getMethodList(owner, "get").findMethod(ctx, type, Collections.singletonList(Type.INT_TYPE), 0);
					if (result != null) {
						MethodResult.writeInvoke(result.method, ctx, cw);
						ctx.castTo(result.method.returnType(), lastVar.type, TypeCast.DOWNCAST).write(cw);
					}
				} else {
					cw.arrayLoad(type.rawType());
					cast.write(cw);
				}
				cw.store(lastVar);
				// type vname = __var[__i];
				cw.updateVariableScope(_arr);
				break NoForEach;
			}
			// endregion
		} else {
			hasVar = false;
		}

			continueTo = cw.label();
			breakTo = condition(null, 1);
			hint = constantHint;
			execLast = ep.parse(ExprParser.STOP_RSB | ExprParser.SKIP_RSB);
		}

		visMap.enter(imLabel);

		nBreakTo = CodeWriter.newLabel();
		if (hint < 0) skipBlockOrStatement();
		else {
			loopBody(imLabel, continueTo, nBreakTo);
			boolean runRest = cw.isContinuousControlFlow();
			if (!runRest && !cw.willJumpTo(continueTo)) {
				ctx.report(Kind.WARNING, "block.loop.notLoop");
			} else if (execLast != null) {
				Label ct1 = new Label(continueTo);
				cw._addLabel(ct1);

				continueTo.clear();
				cw.label(continueTo);
				execLast.resolve(ctx).writeStmt(cw);
				cw.jump(ct1);
			} else if (runRest) {
				cw.jump(continueTo);
			}
		}

		varInLoop = prevCol;

		if (hasVar) endCodeBlock();

		cw.label(breakTo);

		visMap.orElse();

		// for-else (python语法) 如果在循环内使用break退出，则不会进入该分支
		// 如果循环正常结束，或从未开始，都会进入该分支
		if (wr.nextIf(ELSE)) {
			if (!cw.isContinuousControlFlow()) controlFlowTerminate();

			blockOrStatement();
			cw.label(nBreakTo);
		} else {
			cw._addLabel(nBreakTo);
			nBreakTo.set(breakTo);

			if (!cw.isContinuousControlFlow()) controlFlowTerminate();
		}

		visMap.exit();
	}
	private void _while(Scope imLabel) throws ParseException {
		MethodWriter fork = cw.fork(), prev = cw;
		setCw(fork);
		Label head = condition(null, 2);
		setCw(prev);

		// 结构: (VM规范说这样每次循环都少一次goto)
		// ...
		// goto continueTo
		// head:
		// 循环体
		// continueTo:
		// 比较 => head
		// breakTo:

		visMap.enter(imLabel);
		switch (constantHint) {
			case 0 -> {
				Label continueTo = new Label(), breakTo = new Label();
				cw.jump(continueTo);
				int i = cw.nextSegmentId();
				cw.label(head);

				loopBody(imLabel, continueTo, breakTo);

				cw.label(continueTo);
				if (!cw.isImmediateBeforeContinuous(cw.nextSegmentId()-1) && !cw.willJumpTo(continueTo, i)) {
					ctx.report(Kind.WARNING, "block.loop.notLoop");
				}
				fork.writeTo(cw);
				cw.label(breakTo);

				visMap.orElse();
			}
			case 1 -> { // while true
				Label continueTo = new Label(), breakTo = new Label();
				cw.label(continueTo);

				loopBody(imLabel, continueTo, breakTo);

				if (cw.isContinuousControlFlow()) cw.jump(continueTo);
				if (!cw.willJumpTo(continueTo)) ctx.report(Kind.WARNING, "block.loop.notLoop");
				cw.label(breakTo);
				if (!cw.isContinuousControlFlow()) controlFlowTerminate();
			}
			case -1 -> skipBlockOrStatement(); // while false
		}
		visMap.exit();
	}
	private void _doWhile(Scope imLabel) throws ParseException {
		// 这个不需要VisMap因为会执行至少一次
		Label continueTo = new Label(), breakTo = new Label();
		var head = cw.label();

		visMap.enter(imLabel);
		loopBody(imLabel, continueTo, breakTo);

		cw.label(continueTo);
		boolean neverContinue = !cw.isContinuousControlFlow();

		except(WHILE);
		condition(head, 2);
		except(semicolon);
		// do {} while (false);
		// C的宏常这样使用……
		// 怎么，C没有代码块么，真是优优又越越啊
		if (constantHint > 0) cw.jump(head);
		cw.label(breakTo);
		if (!cw.isContinuousControlFlow()) {
			// condition() resets SF_BLOCK
			sectionFlag |= SF_BLOCK;
			controlFlowTerminate();
		} else if (neverContinue) ctx.report(Kind.WARNING, "block.loop.notLoop");

		visMap.exit();
	}
	// endregion
	//region switch
	@NotNull
	public SwitchNode parseSwitch(boolean isExpr) throws ParseException {return parseSwitch(isExpr, null);}
	@SuppressWarnings("DanglingJavadoc")
	private SwitchNode parseSwitch(boolean isExpr, Scope imLabel) throws ParseException {
		boolean disableOptimization = getCompilerIntrinsic("DisableOptimization", false);
		except(lParen);

		var sval = ep.parse(ExprParser.STOP_RSB|ExprParser.SKIP_RSB|ExprParser.NAE).resolve(ctx);
		IType sType = sval.type();
		/** @see SwitchNode#kind */
		int kind = disableOptimization ? 0 : 5;
		Function<String, CompileContext.Import> prevDFI = ctx.dynamicFieldImport, DFI = prevDFI;

		if (sType instanceof SwitchableType switchableType)
			DFI = ctx.getFieldDFI(ctx.resolve(switchableType.getFieldOwner()), null, prevDFI);

		switch (sType.getActualType()) {
			case Type.BOOLEAN, Type.VOID, Type.FLOAT, Type.DOUBLE -> ctx.report(Kind.ERROR, "block.switch.incompatible", sType);
			case Type.LONG -> kind = 6;
			case Type.OBJECT -> {
				String owner = sType.owner();
				if (owner.equals("java/lang/Integer")) break;
				if (owner.equals("java/lang/Long")) {kind = 6;break;}
				if (owner.equals("java/lang/String")) {
					if (disableOptimization) {
						ctx.report(Kind.NOTE, "block.switch.lookupSwitch");
						kind = 1;
					} else {
						kind = 4;
					}
					break;
				}

				var ownerInfo = ctx.compiler.resolve(owner);
				if (ctx.compiler.instanceOf(owner, "java/lang/Enum")) {
					kind = disableOptimization ? 2 : 3;
					DFI = ctx.getFieldDFI(ownerInfo, null, prevDFI);
					break;
				}

				var switchable = ctx.getAnnotation(ownerInfo, ownerInfo, "roj/compiler/api/Switchable", false);
				if (switchable != null) {
					kind = switchable.getBool("identity", true) ? 3 : 4;
					if (switchable.getBool("suggest", false)) {
						DFI = ctx.getFieldDFI(ownerInfo, null, prevDFI);
					}
				} else {
					kind = -1;
				}
			}
		}

		Object cst = sval.isConstant() ? sval.constVal() : null;
		except(lBrace);

		List<Expr> labels = Helpers.cast(ctx.tmpList2); labels.clear();
		HashSet<Object> labelDeDup = Helpers.cast(ctx.getTmpSet());

		Label breakTo = new Label();
		MethodWriter tmp = cw;

		var branches = new ArrayList<SwitchNode.Branch>();
		SwitchNode.Branch nullBranch = null;

		/*
		 * error checking:
		 * 1  => type switch
		 * 2  => pattern switch
		 *
		 * 4  => legacy switch grammar
		 * 8  => new switch grammar
		 * 16 => grammar error reported
		 *
		 * 32 => non-constant error reported
		 *
		 * 64 => hasDefault
		 *
		 * 128 => isContinuousControlFlow ever return true
		 */
		int flags = 0;
		boolean lastBreak = false;

		visMap.enter(imLabel);
		loop:
		while (true) {
			SwitchNode.Branch kase;
			boolean match = false;
			boolean blockBegin = true;

			Token w = wr.next();
			skipVD:
			switch (w.type()) {
				default: throw wr.err("unexpected_2:[\""+w.text()+"\",block.except.switch]");
				case rBrace: break loop;

				case CASE:
					labels.clear();
					boolean hasNull = false;
					do {
						var expr = ep.parse(ExprParser.STOP_COLON|ExprParser.STOP_COMMA|ExprParser.STOP_LAMBDA|ExprParser.CHECK_VARIABLE_DECLARE|ExprParser.NAE);

						if (expr instanceof VariableDeclare vd) {
							IType type = ctx.resolveType(vd.type);
							if (type.isPrimitive()) {
								ctx.report(Kind.ERROR, "type.primitiveNotAllowed");
								type = Types.VOID_TYPE;
							}

							for (Object o : labelDeDup) {
								if (o instanceof IType t1) {
									// 没有反向检查，因为本来就是一个一个instanceof
									if (ctx.compiler.getHierarchyList(ctx.resolve(t1)).containsKey(type.owner())) {
										ctx.report(Kind.ERROR, "block.switch.collisionType", t1, type);
									}
								}
							}
							ctx.castTo(sType, type, TypeCast.DOWNCAST);
							labelDeDup.add(type);

							w = wr.next();
							// flag8: check type switch in case ':'
							flags |= 10;
							blockBegin = false;

							beginCodeBlock();
							var v = newVar(vd.name, type);
							v.hasValue = true;
							v.forceUsed = true;
							kase = new SwitchNode.Branch(v);
							break skipVD;
						}

						ctx.dynamicFieldImport = DFI;
						Expr node = expr.resolve(ctx);
						ctx.dynamicFieldImport = prevDFI;
						if (node.isConstant()) {
							if (node.constVal() == null) {
								if (sval.type().isPrimitive()) ctx.report(Kind.ERROR, "block.switch.nullfail");
								hasNull = true;
							}
							// 如果这个switch是选择常量，并且当前节点命中
							if (cst != null && cst.equals(node.constVal()))
								match = true;
						} else {
							// 防止用case (a = b)之类的搞坏VisMap
							if (!node.hasFeature(Expr.Feature.STATIC_MEMBER_ACCESS) || (kind < 3 && (flags&32) == 0)) {
								ctx.report(Kind.ERROR, "block.switch.nonConstant");
								flags |= 32;
							}
						}
						if (!labelDeDup.add(node)) ctx.report(Kind.ERROR, "block.switch.duplicate", node);
						labels.add(node);

						flags |= 1;
					} while ((w = wr.next()).type() == comma);

					kase = new SwitchNode.Branch(ArrayUtil.immutableCopyOf(labels));
					labels.clear();
					kase.lineNumber = wr.LN;

					if (hasNull) {
						if (nullBranch != null) ctx.report(Kind.ERROR, "block.switch.duplicate", "null");
						nullBranch = kase;
					}
				break;
				case DEFAULT:
					w = wr.next();
					kase = new SwitchNode.Branch((List<Expr>) null);
					match = true;
				break;
			}

			if (w.type() == colon) {
				flags |= 4;
			} else if (w.type() == lambda) {
				flags |= 8;
				if (blockBegin) beginCodeBlock();
			} else throw wr.err("unexpected_2:[\""+w.type()+"\",block.except.switchCase]");

			// has 4, 8 but not 16
			if ((flags&28) == 12) {
				ctx.report(Kind.ERROR, "block.switch.mixedCase");
				flags |= 16; // error already thrown
			}

			setCw(tmp.fork());
			kase.block = cw;

			// default
			if (kase.labels == null && kase.variable == null) {
				if ((flags&64) != 0) ctx.report(Kind.ERROR, "block.switch.manyDefault");
				flags |= 64;
			}

			boolean shouldParse = cst == null || match;
			if (shouldParse) branches.add(kase);

			if (isExpr) {
				if (w.type() != lambda) ctx.report(Kind.ERROR, "block.switch.exprMode.legacy");
				kase.value = switchExprBlock(shouldParse);
				// switchExpr必然返回一个值，所以不可能有lastBreak
			} else {
				lastBreak = switchBlock(breakTo, w.type() == lambda, shouldParse);
			}
			if (lastBreak) flags |= 128;
			visMap.orElse();
		}

		// 忽略最后一个分支的最后的break;
		var last = branches.getLast();
		if (lastBreak) last.block.replaceSegment(last.block.nextSegmentId()-1, StaticSegment.EMPTY);

		setCw(tmp);

		visMap.terminate();
		visMap.exit();
		if ((flags&128) == 0) controlFlowTerminate();

		boolean defaultBranch = (flags & 64) != 0;
		// 检测type switch和pattern switch的混用
		flags &= 3;
		mixedButOnlyNull:
		if (flags == 3) {
			fail:
			if (nullBranch != null && nullBranch.labels.size() == 1) {
				for (var branch : branches) {
					if (branch.labels != null && branch != nullBranch) break fail;
				}

				kind = -1;
				break mixedButOnlyNull;
			}

			ctx.report(Kind.ERROR, "block.switch.mixedCase");
		} else if (flags == 2) {
			kind = -1;
		} else if (kind == -1) {
			ctx.report(Kind.ERROR, "block.switch.incompatible", sType);
		}

		return new SwitchNode(sval, kind, cst, breakTo, branches, nullBranch, defaultBranch);
	}
	public void writeSwitch(SwitchNode node) {
		if (node.kind < 0) {
			if (ctx.compiler.getMaximumBinaryCompatibility() >= LavaCompiler.JAVA_17) {
				file.setMinimumBinaryCompatibility(LavaCompiler.JAVA_17);
				writePatternSwitch(node, "java/lang/runtime/SwitchBootstraps");
			} else {
				writePatternSwitchSlow(node);
			}
		}
		else writeTypeSwitch(node);
	}
	private void writePatternSwitch(SwitchNode node, String runtimeClass) {
		List<Constant> arguments = new ArrayList<>();
		int tableIdx = file.addNewLambdaRef(new BootstrapMethods.Item(
				BootstrapMethods.Kind.INVOKESTATIC,
				cw.cpw.getRefByType(runtimeClass,
						"typeSwitch",
						"(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;[Ljava/lang/Object;)Ljava/lang/invoke/CallSite;",
						Constant.METHOD
				),
				arguments
		));

		var sval = node.sval;
		var switchVal = newVar("@临时_switchValue", sval.type());
		sval.write(cw);
		cw.store(switchVal);

		cw.load(switchVal);
		cw.ldc(0);
		cw.invokeDyn(tableIdx, "typeSwitch", "(Ljava/lang/Object;I)I");
		SwitchBlock c = cw.tableSwitch();
		c.def = node.breakTo; // 防止SwitchBlock#willJumpTo出现NPE，另外若是caseDefault==null，这个值也是真实的值

		var nullBranch = node.nullBranch;
		if (nullBranch != null) {
			Label label = cw.label();
			c.branch(-1, label);
			if (cw.lines != null) cw.lines.add(label, nullBranch.lineNumber);

			nullBranch.block.writeTo(cw);

			if (cw.isContinuousControlFlow())
				cw.jump(node.breakTo);
		}

		SwitchNode.Branch caseDefault = null;
		int offset = 0;
		for (int i = 0; i < node.branches.size(); i++) {
			var kase = node.branches.get(i);
			if (kase == nullBranch) {
				offset--;
				continue;
			}
			if (kase.variable == null) {
				offset--;
				caseDefault = kase;
				continue;
			}

			Label label = cw.label();
			c.branch(i + offset, label);
			// javac generates at here 反正也不会抛异常
			if (cw.lines != null && kase.lineNumber > 0) cw.lines.add(label, kase.lineNumber);

			cw.load(switchVal);
			cw.clazz(CHECKCAST, kase.variable.type.rawType());
			// 也可以是Integer String，虽然不知有啥用，另外VM对这个到底有优化吗？
			arguments.add(new CstClass(kase.variable.type.owner()));
			cw.store(kase.variable);

			kase.block.writeTo(cw);

			if (cw.isContinuousControlFlow())
				cw.jump(node.breakTo);
		}

		if (caseDefault != null) {
			c.def = cw.label();
			if (cw.lines != null && caseDefault.lineNumber > 0) cw.lines.add(c.def, caseDefault.lineNumber);
			caseDefault.block.writeTo(cw);
		}

		cw.label(node.breakTo);
	}
	private void writePatternSwitchSlow(SwitchNode node) {
		var sval = node.sval;
		var switchVal = newVar("@临时_switchValue", sval.type());
		sval.write(cw);
		cw.store(switchVal);

		// 250819 如果继承范围已知，考虑使用SwitchMap<Class<?>>分派
		var nullBranch = node.nullBranch;
		if (nullBranch != null) {
			var endOfBlock = new Label();

			cw.load(switchVal);
			cw.jump(IFNONNULL, endOfBlock);

			if (cw.lines != null) cw.lines.add(cw.label(), nullBranch.lineNumber);
			nullBranch.block.writeTo(cw);

			if (cw.isContinuousControlFlow())
				cw.jump(node.breakTo);

			cw.label(endOfBlock);
		}

		SwitchNode.Branch caseDefault = null;
		for (int i = 0; i < node.branches.size(); i++) {
			var kase = node.branches.get(i);
			if (kase == nullBranch) continue;
			if (kase.variable == null) {
				caseDefault = kase;
				continue;
			}

			var endOfBlock = new Label();

			cw.load(switchVal);
			var vType = kase.variable.type.rawType();
			cw.clazz(Opcodes.INSTANCEOF, vType);
			cw.jump(IFEQ, endOfBlock);
			cw.load(switchVal);
			cw.clazz(CHECKCAST, vType);
			cw.store(kase.variable);

			if (cw.lines != null && kase.lineNumber > 0) cw.lines.add(cw.label(), kase.lineNumber);
			kase.block.writeTo(cw);

			if (cw.isContinuousControlFlow())
				cw.jump(node.breakTo);

			cw.label(endOfBlock);
		}

		if (caseDefault != null) {
			if (cw.lines != null && caseDefault.lineNumber > 0) cw.lines.add(cw.label(), caseDefault.lineNumber);
			caseDefault.block.writeTo(cw);
		}

		cw.label(node.breakTo);
	}
	private void writeTypeSwitch(SwitchNode node) {
		var branches = node.branches;
		var sval = node.sval;
		var breakTo = node.breakTo;

		smallOptimize:
		if (branches.size()-(node.defaultBranch?1:0) <= 1) {
			int size = branches.size();
			if (size == 0) {
				ctx.report(Kind.SEVERE_WARNING, "block.switch.noBranch");
				return;
			}

			SwitchNode.Branch branch;

			// 找任意case，如果没有取default
			do {
				branch = branches.get(--size);
				if (branch.labels != null) break;
			} while (size != 0);

			myBlock:
			if (node.cst != null) {
				branch.block.writeTo(cw);
			} else {
				if (branch.labels == null) {
					// default, 没找到任何case
					ctx.report(Kind.SEVERE_WARNING, "block.switch.noBranch");

					sval.write(cw);
					// POP or POP2
					// noinspection MagicConstant
					cw.insn((byte) (0x56 + sval.type().rawType().length()));
					branch.block.writeTo(cw);
					break myBlock;
				} else if (branch.labels.size() > 1) {
					// 还是有超过一个case的
					break smallOptimize;
				}

				boolean shouldInvert = branches.size() != 1 && size != 0;
				// if
				Expr cmp = ep.binaryOp(shouldInvert ? neq : equ, sval, branch.labels.get(0)).resolve(ctx);

				Label ifNe = new Label();

				// 正确的，IFEQ对于Binary equ来说是0，是false，是不相等
				cmp.writeShortCircuit(cw, TypeCast.Cast.IDENTITY, false, ifNe);

				if (shouldInvert) {
					branches.get(size^1).block.writeTo(cw);
					cw.label(ifNe);
					branch.block.writeTo(cw);
				} else {
					branch.block.writeTo(cw);
					cw.label(ifNe);
					if (branches.size() != 1) {
						branches.get(size^1).block.writeTo(cw);
					}
				}
			}

			cw.label(breakTo);
			return;
		}

		switch (node.kind) {
			case 0 -> {// int or Integer
				ctx.writeCast(cw, sval, Type.INT_TYPE);
				switchInt(breakTo, branches, null);
			}
			case 1 -> {// legacy String
				ctx.writeCast(cw, sval, Types.STRING_TYPE);
				if (node.nullBranch != null) makeNullsafe(node.nullBranch, sval);
				switchString(branches, breakTo);
			}
			case 2 -> {// enum
				ClassNode switchMap = switchEnum(branches);

				Label next = new Label();

				Label start = cw.label();
				cw.field(GETSTATIC, switchMap, 0);
				ctx.writeCast(cw, sval, Types.ENUM_TYPE);
				if (node.nullBranch != null) makeNullsafe(node.nullBranch, sval);

				cw.invoke(INVOKEVIRTUAL, "java/lang/Enum", "ordinal", "()I");
				cw.insn(IALOAD);
				cw.jump(next);
				Label end = cw.label();

				cw.insn(POP);
				cw.insn(ICONST_0); // 0, default
				cw.label(next);
				cw.addException(start, end, end, "java/lang/ArrayIndexOutOfBoundsException");

				linearMapping(breakTo, branches);
			}
			case 3, 4 -> {// (Identity)HashMap
				ClassNode switchMap = switchMap(branches, (byte) node.kind, "roj/compiler/runtime/SwitchMap", Types.OBJECT_TYPE);

				cw.field(GETSTATIC, switchMap, 0);
				sval.write(cw);
				cw.invoke(INVOKEVIRTUAL, "roj/compiler/runtime/SwitchMap", "get", "(Ljava/lang/Object;)I");

				linearMapping(breakTo, branches);
			}
			case 5 -> {// SwitchMapI
				if (switchInt(breakTo, branches, sval)) break;

				ClassNode switchMap = switchMap(branches, (byte) 0, "roj/compiler/runtime/SwitchMapI", Type.INT_TYPE);

				cw.field(GETSTATIC, switchMap, 0);
				ctx.writeCast(cw, sval, Type.INT_TYPE);
				cw.invoke(INVOKEVIRTUAL, "roj/compiler/runtime/SwitchMapI", "get", "(I)I");

				linearMapping(breakTo, branches);
			}
			case 6 -> {// SwitchMapJ
				ClassNode switchMap = switchMap(branches, (byte) 0, "roj/compiler/runtime/SwitchMapJ", Type.LONG_TYPE);

				cw.field(GETSTATIC, switchMap, 0);
				ctx.writeCast(cw, sval, Type.LONG_TYPE);
				cw.invoke(INVOKEVIRTUAL, "roj/compiler/runtime/SwitchMapJ", "get", "(J)I");

				linearMapping(breakTo, branches);
			}
		}

		cw.label(breakTo);
	}

	// 以后用load constantDynamic实现SwitchMap？
	private int switchMapId;
	private InnerClasses.Item switchMapDesc() {
		return new InnerClasses.Item("", file.name(), "SwitchMap"+switchMapId++, ACC_PRIVATE|ACC_STATIC|ACC_FINAL);
	}

	private void linearMapping(Label breakTo, List<SwitchNode.Branch> branches) {
		SwitchBlock sw = cw.tableSwitch();
		sw.def = breakTo;

		int j = 0;
		for (int i = 0; i < branches.size();) {
			var branch = branches.get(i++);

			Label pos;
			if (branch.tmpLoc != null) cw.label(pos = branch.tmpLoc);
			else pos = cw.label();

			branch.block.writeTo(cw);

			if (branch.labels == null) sw.def = pos;
			else sw.branch(++j, pos);
		}
	}
	private void makeNullsafe(SwitchNode.Branch branch, Expr sval) {
		var labels = branch.labels;
		for (int i = 0; i < labels.size(); i++) {
			var n = labels.get(i);
			if (n.isConstant() && n.constVal() == null) {
				labels.remove(i);
				break;
			}
		}

		beginCodeBlock();

		Variable xvar = newVar("@", sval.type());
		cw.insn(DUP);
		cw.store(xvar);
		cw.jump(IFNULL, branch.tmpLoc = new Label());
		cw.load(xvar);

		endCodeBlock();
	}
	private boolean switchInt(Label breakTo, ArrayList<SwitchNode.Branch> branches, Expr lookupTesting) {
		if (lookupTesting != null) {
			var sw = SwitchBlock.ofAuto();

			for (int i = 0; i < branches.size();) {
				var branch = branches.get(i++);

				if (branch.labels != null) {
					for (Expr label : branch.labels) {
						var tmp1 = label.constVal();
						int key;
						if (tmp1 instanceof ConfigValue x && x.mayCastTo(roj.config.node.Type.INTEGER)) {
							key = x.asInt();
						} else {
							continue;
						}

						sw.branch(key, breakTo);
					}
				}
			}

			if (sw.findBestCode() == LOOKUPSWITCH && branches.size() > 2) return false;
			ctx.writeCast(cw, lookupTesting, Type.INT_TYPE);
		}

		var sw = cw.autoSwitch();
		sw.def = breakTo;

		for (int i = 0; i < branches.size();) {
			var branch = branches.get(i++);

			Label pos = cw.label();
			branch.block.writeTo(cw);

			if (branch.labels == null) {
				sw.def = pos;
			} else {
				for (Expr label : branch.labels) {
					var tmp1 = label.constVal();
					int key;
					if (tmp1 instanceof ConfigValue x && x.mayCastTo(roj.config.node.Type.INTEGER)) {
						key = x.asInt();
					} else {
						// 报告错误，xxx无法转换为常量整数
						int type = ctx.castTo(label.type(), Type.INT_TYPE, 0).type;
						assert type < 0;
						continue;
					}

					sw.branch(key, pos);
				}
			}
		}

		if (sw.findBestCode() == LOOKUPSWITCH && branches.size() > 2) {
			ctx.report(Kind.WARNING, "block.switch.lookupSwitch");
		}

		return true;
	}
	private void switchString(List<SwitchNode.Branch> branches, Label breakTo) {
		var v = newVar("@", Types.STRING_TYPE);
		var c = cw;

		c.insn(DUP);
		c.store(v);
		c.invoke(INVOKESPECIAL, "java/lang/String", "hashCode", "()I");

		SwitchBlock sw = c.lookupSwitch();
		sw.def = breakTo;

		// check duplicate
		IntMap<List<Map.Entry<String, Label>>> tmp = new IntMap<>();

		for (int i = 0; i < branches.size();) {
			var branch = branches.get(i++);
			Label pos = branch.tmpLoc == null ? branch.tmpLoc = new Label() : branch.tmpLoc;

			if (branch.labels == null) {
				sw.def = pos;
			} else {
				for (Expr label : branch.labels) {
					var tmp1 = label.constVal();
					String key;
					if (tmp1 instanceof String) {
						key = (String) tmp1;
					} else if (label.type().equals(Type.CHAR_TYPE)) {
						key = String.valueOf(((ConfigValue) tmp1).asChar());
					} else {
						// 报告错误：xxx无法转换为常量字符串
						int type = ctx.castTo(label.type(), Types.STRING_TYPE, 0).type;
						assert type < 0;
						continue;
					}
					int hash = key.hashCode();

					var dup = tmp.get(hash);
					if (dup == null) tmp.put(hash, dup = new ArrayList<>(2));

					dup.add(new AbstractMap.SimpleEntry<>(key, pos));
				}
			}
		}

		for (IntMap.Entry<List<Map.Entry<String, Label>>> entry : tmp.selfEntrySet()) {
			Label pos = new Label();
			sw.branch(entry.getIntKey(), pos);
			c.label(pos);
			List<Map.Entry<String, Label>> list1 = entry.getValue();
			for (int i = 0; i < list1.size(); i++) {
				Map.Entry<String, Label> entry1 = list1.get(i);
				c.load(v);
				c.ldc(entry1.getKey());
				c.invoke(INVOKESPECIAL, "java/lang/String", "equals", "(Ljava/lang/Object;)Z");
				c.jump(IFNE, entry1.getValue());
			}

			c.jump(sw.def);
		}

		// 如果要内联到上面的循环，条件判断比较麻烦，每个list1的长度都是1才可以，懒得做了
		for (int i = 0; i < branches.size();) {
			SwitchNode.Branch branch = branches.get(i++);
			c.label(branch.tmpLoc);
			branch.block.writeTo(c);
		}

		ctx.report(Kind.WARNING, "block.switch.lookupSwitch");
	}
	@NotNull
	private ClassNode switchEnum(List<SwitchNode.Branch> branches) {
		var sm = file.newAnonymousClass_NoBody(ctx.method, switchMapDesc());
		sm.modifier |= ACC_SYNTHETIC;

		sm.newField(ACC_SYNTHETIC|ACC_STATIC|ACC_FINAL, "switchMap", "[I");

		sm.newMethod(ACC_PUBLIC|ACC_STATIC, "<clinit>", "()V");
		var c = ctx.createMethodWriter(sm, sm.methods.getLast());
		c.visitSize(3, 1);

		int size = 0;
		for (int i = 0; i < branches.size(); i++) {
			var labels = branches.get(i).labels;
			if (labels != null) size += labels.size();
		}
		c.ldc(size);
		c.newArray(Type.getArrayType('I'));
		c.field(PUTSTATIC, sm, 0);

		int i = 0;
		for (SwitchNode.Branch branch : branches) {
			var labels = branch.labels;
			if (labels == null) continue;

			for (Expr o : labels) {
				Label start = c.label();

				// an enum constant
				c.field(GETSTATIC, sm, 0);
				o.write(c);
				c.invoke(INVOKEVIRTUAL, "java/lang/Enum", "ordinal", "()I");
				c.ldc(i);
				c.insn(IASTORE);

				Label next = new Label();
				c.jump(next);

				Label end = c.label();
				c.insn(POP);
				// 若类正常加载，还可能抛出ArrayIndexOutOfBoundsException
				// 但是我并无办法预测这个数组多大
				// 也许可以使用IntMap
				// 但是我已经有了SwitchMap，所以这不会是个todo，而只是注释
				c.addException(start, end, end, "java/lang/NoSuchFieldError");

				c.label(next);
			}

			i++;
		}

		c.insn(Opcodes.RETURN);
		c.finish();
		c.method.addAttribute(new UnparsedAttribute("Code", c.bw.toByteArray()));
		return sm;
	}
	@NotNull
	private ClassNode switchMap(List<SwitchNode.Branch> branches, byte kind, String switchMapType, Type valueType) {
		var sm = ctx.file.newAnonymousClass_NoBody(ctx.method, switchMapDesc());
		sm.modifier |= ACC_SYNTHETIC;

		sm.newField(ACC_SYNTHETIC|ACC_STATIC|ACC_FINAL, "map", "L"+switchMapType+";");

		sm.newMethod(ACC_PUBLIC|ACC_STATIC, "<clinit>", "()V");
		var c = ctx.createMethodWriter(sm, sm.methods.getLast());
		c.visitSize(3, 1);

		int size = 0;
		for (var branch : branches) {
			var labels = branch.labels;
			if (labels != null) size += labels.size();
		}

		c.ldc(size);

		String builderType = switchMapType+"$Builder";
		String addDesc = "("+valueType.toDesc()+"I)L"+builderType+";";

		if (kind != 0) {
			c.insn(kind); // trick: ICONST_0 = 3 = false, ICONST_1 = 4 = true
			c.invokeS(builderType, "builder", "(IZ)L"+builderType+";");
		} else {
			c.invokeS(builderType, "builder", "(I)L"+builderType+";");
		}
		c.insn(ASTORE_0);

		var lines = new LineNumberTable();

		int i = 1; // start from 1, 0 is default
		for (var branch : branches) {
			var labels = branch.labels;
			if (labels == null) continue;

			Label start = null;
			for (Expr expr : labels) {
				if (start == null) lines.add(start = c.label(), branch.lineNumber);
				else start = c.label();

				c.insn(ALOAD_0);
				ctx.writeCast(c, expr, valueType);
				Label end = c.label();
				c.ldc(i);
				c.invokeV(builderType, "add", addDesc);

				Label handler = c.label();
				c.insn(POP);
				c.addException(start, end, handler, null);
			}

			i++;
		}

		c.insn(ALOAD_0);
		c.invokeV(builderType, "build", "()L"+switchMapType+";");
		c.field(PUTSTATIC, sm, 0);

		c.insn(Opcodes.RETURN);
		c.computeFrames(FrameVisitor.COMPUTE_FRAMES| FrameVisitor.COMPUTE_SIZES);
		if (ctx.compiler.hasFeature(Compiler.EMIT_LINE_NUMBERS)) {
			c.visitExceptions();
			c.visitAttributes();
			c.visitAttribute(lines);
		}
		c.finish();
		c.method.addAttribute(new UnparsedAttribute("Code", c.bw.toByteArray()));
		return sm;
	}

	/**
	 *
	 * @param endPoint break跳转到的目标
	 * @param newSwitch 是否新式switch (用 -> 的那个)，永远不fallthrough
	 * @param parse 是否解析还是忽略 (编译期常量跳过)
	 * @return 这个switch分支是否会fallthrough到后面的代码
	 * @throws ParseException
	 */
	@SuppressWarnings("fallthrough")
	private boolean switchBlock(Label endPoint, boolean newSwitch, boolean parse) throws ParseException {
		while (true) {
			if (parse) loopBody(null, null, endPoint);
			else skipBlockOrStatement();

			Token w = wr.next();
			wr.retractWord();

			switch (w.type()) {
				case CASE, DEFAULT, rBrace:
					boolean flow = cw.isContinuousControlFlow();
					if (newSwitch) {
						if (flow) cw.jump(endPoint);
						endCodeBlock();
						return flow;
					} else if (flow) {
						if (w.type() != rBrace) ctx.report(Kind.WARNING, "block.switch.fallthrough");
					}

				return cw.isContinuousControlFlow();
			}

			if (newSwitch) ctx.report(Kind.ERROR, "unexpected_2", w.text(), "block.except.switch");
		}
	}
	private Expr switchExprBlock(boolean parse) throws ParseException {
		Expr expr;

		if (parse) {
			Token w = wr.next();
			if (w.type() == THROW) {
				_throw();
				expr = null;
			} else if (w.type() == lBrace) {
				wr.retractWord();

				byte flag = sectionFlag;
				sectionFlag |= SF_SWITCH;

				try {
					blockV();
					// athrow
					if (!cw.isContinuousControlFlow()) return null;

					ctx.report(Kind.ERROR, "block.switch.exprMode.noYield");
					return NaE.NOEXPR;
				} catch (OperationDone od) {
					expr = ep.parse(ExprParser.STOP_SEMICOLON|ExprParser.SKIP_SEMICOLON|ExprParser.NAE).resolve(ctx);
					controlFlowTerminate();
					except(rBrace);
				} finally {
					sectionFlag = flag;
				}

			} else  {
				wr.retractWord();

				expr = ep.parse(ExprParser.STOP_SEMICOLON|ExprParser.SKIP_SEMICOLON|ExprParser.NAE).resolve(ctx);
			}
		} else {
			skipBlockOrStatement();
			expr = null;
		}

		endCodeBlock();
		return expr;
	}
	// endregion
	// region yield
	private SwitchBlock generatorEntry;
	private void _yield() throws ParseException {
		if ((sectionFlag&SF_SWITCH) != 0) throw OperationDone.INSTANCE;

		var expr = ep.parse(ExprParser.STOP_SEMICOLON|ExprParser.SKIP_SEMICOLON|ExprParser.NAE);

		if (generatorEntry == null) {ctx.report(Kind.ERROR, "block.switch.yield");return;}

		int point = generatorEntry.cases.size();

		cw.insn(ALOAD_1);
		cw.invokeV(RETURNSTACK_TYPE, "forWrite", "()L"+RETURNSTACK_TYPE+";");
		cw.ldc(point);
		cw.invokeV(RETURNSTACK_TYPE, "put", "(I)L"+RETURNSTACK_TYPE+";");

		Expr node = expr.resolve(ctx);
		// 20250707 少见的优化 消除 yield t += 1 时候的变量开销
		/*Variable mutated = null;
		if (node instanceof Assign assign && assign.getLeft() instanceof LocalVariable lv && lv.getVariable().type.equals(returnTypeG)) {
			mutated = lv.getVariable();
			node.write(cw, false);
		} else*/
		{
			ctx.writeCast(cw, node, returnTypeG);
		}

		if (!variables.isEmpty()) {
			// 0槽位的“this”在epilogue已经用不到了
			cw.varStore(returnType, 0);

			for (var variable : variables.values()) {
				if (visMap.hasValue(variable)) {
					int varType = variable.type.getActualType();

					cw.load(variable);
					// TODO 20250708 此时不知道这个变量在后面是否用过，未来可以考虑把这个做成一个Segment，检测end是否在后面
					cw.invokeV(RETURNSTACK_TYPE, "put", "("+(varType==Type.OBJECT?"Ljava/lang/Object;":(char)varType)+")L"+RETURNSTACK_TYPE+";");
				}
			}

			cw.varLoad(returnType, 0);
		}

		int varType = returnTypeG.getActualType();
		cw.invokeV(RETURNSTACK_TYPE, "put", "("+(varType==Type.OBJECT?"Ljava/lang/Object;":(char)varType)+")L"+RETURNSTACK_TYPE+";");

		cw.insn(POP);
		cw.insn(Opcodes.RETURN);

		generatorEntry.branch(point, cw.label());
		for (var variable : variables.values()) {
			if (visMap.hasValue(variable)) {
				varType = variable.type.getActualType();

				cw.insn(ALOAD_1);
				if (varType == Type.OBJECT) {
					cw.invokeV(RETURNSTACK_TYPE, "getL", "()Ljava/lang/Object;");
					cw.clazz(CHECKCAST, variable.type.rawType().getActualClass());
				} else {
					cw.invokeV(RETURNSTACK_TYPE, "get"+(char)varType, "()"+(char)varType);
				}
				cw.store(variable);
			}
		}
	}
	//endregion
	//region synchronized
	private void _sync() throws ParseException {
		except(lParen);

		var loadLock = ep.parse(ExprParser.STOP_RSB|ExprParser.SKIP_RSB|ExprParser.NAE).resolve(ctx);
		if (loadLock.type().isPrimitive()) {
			ctx.report(Kind.ERROR, "type.primitiveNotAllowed");
			loadLock = NaE.resolveFailed(loadLock);
		}
		loadLock.write(cw);

		beginCodeBlock();

		var lockType = ctx.compiler.resolve(loadLock.type().owner());
		Boolean lockIsItf = ctx.compiler.hasFeature(Compiler.SYNCHRONIZED_LOCK) && ctx.compiler.getHierarchyList(lockType).containsKey(Types.LOCK_TYPE)
				? (lockType.modifier & ACC_INTERFACE) != 0 : null;

		// ALOAD_3 DUP ASTORE_3 ... 能把后两者约掉，不过到优化器里再说吧
		var theLock = newVar("@lock", loadLock.type());
		cw.insn(DUP);
		cw.store(theLock);
		if (lockIsItf != null) {
			cw.invoke(lockIsItf ? INVOKEINTERFACE : INVOKEVIRTUAL, lockType.name(), "lock", "()V", false);
		}
		else cw.insn(MONITORENTER);

		var hook = flowHook = new FlowHook(flowHook);

		Label start = cw.label();

		except(lBrace);
		block();

		Label end = cw.label();
		Label realEnd = new Label();

		boolean hasNext = cw.isContinuousControlFlow();
		if (hasNext) cw.jump(realEnd);

		flowHook = hook.parent;
		Consumer<MethodWriter> unlocker = mw -> {
			mw.load(theLock);
			if (lockIsItf != null) {
				cw.invoke(lockIsItf ? INVOKEINTERFACE : INVOKEVIRTUAL, lockType.name(), "unlock", "()V", false);
			}
			else mw.insn(MONITOREXIT);
		};
		hook.finallyExecute(cw, start, unlocker);

		cw.addException(start,end,cw.label(), TryCatchBlock.ANY);
		unlocker.accept(cw);
		cw.insn(ATHROW);

		if (hasNext) {
			cw.label(realEnd);
			unlocker.accept(cw);
		} else {
			controlFlowTerminate();
		}
	}
	//endregion
	//region with
	private void _with() throws ParseException {
		except(lParen);

		Expr node = ep.parse(ExprParser.STOP_RSB|ExprParser.SKIP_RSB|ExprParser.NAE).resolve(ctx);

		if (node.type().isPrimitive()) {
			ctx.report(Kind.ERROR, "symbol.derefPrimitive", node.type());
		}

		beginCodeBlock();
		except(lBrace);

		Variable ref;
		if (node.hasFeature(Expr.Feature.LDC_CLASS)) ref = null;
		else if (node instanceof LocalVariable lv) {
			// trust user
			ref = lv.getVariable();
		} else {
			node.write(cw);
			ref = newVar("@", node.type());
			ref.hasValue = true;
			cw.store(ref);
		}

		ClassNode info = ctx.compiler.resolve(node.type().owner());

		var prevDFI = ctx.dynamicFieldImport;
		var prevDMI = ctx.dynamicMethodImport;

		ctx.dynamicFieldImport = ctx.getFieldDFI(info, ref, prevDFI);
		ctx.dynamicMethodImport = name -> {
			var cl = ctx.compiler.getMethodList(info, name);
			if (cl != ComponentList.NOT_FOUND) return CompileContext.Import.virtualCall(info, name, ref == null ? null : new LocalVariable(ref));

			return prevDMI == null ? null : prevDMI.apply(name);
		};

		try {
			block();
		} finally {
			ctx.dynamicFieldImport = prevDFI;
			ctx.dynamicMethodImport = prevDMI;
		}
	}
	//endregion
	//region assert
	private void _assert() throws ParseException {
		if (ctx.compiler.hasFeature(Compiler.DISABLE_ASSERT)) {
			ep.parse(ExprParser.STOP_SEMICOLON | ExprParser.SKIP_SEMICOLON | ExprParser.STOP_COLON | ExprParser.NAE);
			if (wr.nextIf(colon)) ep.parse(ExprParser.STOP_SEMICOLON | ExprParser.SKIP_SEMICOLON | ExprParser.NAE);
			return;
		}

		int assertEnabled = file.getAssertEnabled();

		cw.field(GETSTATIC, file, assertEnabled);
		Label assertDone = new Label();
		cw.jump(IFEQ, assertDone);

		Expr condition = ep.parse(ExprParser.STOP_SEMICOLON | ExprParser.SKIP_SEMICOLON | ExprParser.STOP_COLON | ExprParser.NAE).resolve(ctx);
		alwaysThrow: {
			ConfigValue value = condition.constValTyped(roj.config.node.Type.BOOL);
			if (value != null) {
				if (value.asBool()) {
					ctx.report(Kind.SEVERE_WARNING, "block.assert.constant");
				} else {
					break alwaysThrow;
				}
			}
			ctx.writeCast(cw, condition, Type.BOOLEAN_TYPE);
			cw.jump(IFNE, assertDone);
		}

		cw.clazz(Opcodes.NEW, "java/lang/AssertionError");
		cw.insn(DUP);

		String desc;
		if (wr.nextIf(colon)) {
			Expr message = ep.parse(ExprParser.STOP_SEMICOLON | ExprParser.SKIP_SEMICOLON | ExprParser.NAE).resolve(ctx);

			//ctx.stackEnter(2);
			int type = message.type().getActualType();
			if (type == Type.OBJECT) {
				ctx.writeCast(cw, message, Types.OBJECT_TYPE);
				desc = "(Ljava/lang/Object;)V";
			} else {
				message.write(cw);
				desc = "("+(char)type+")V";
			}
		} else {
			desc = "()V";
		}

		cw.invoke(INVOKESPECIAL, "java/lang/AssertionError", "<init>", desc);
		cw.insn(ATHROW);
		cw.label(assertDone);
	}
	//endregion
	//region 变量定义 & 多返回值
	private boolean varConst(boolean isFinal) throws ParseException {
		short type = wr.next().type();

		// 多返回值的语法糖
		if (type == lBrace) {
			unpackReturnStack(isFinal);
			return true;
		}

		wr.retractWord();

		if (type == Token.LITERAL) {
			defineVariables(Types.anyType, isFinal);
			return true;
		}
		return false;
	}

	/**
	 * 语句: 多返回值解包
	 * var [a, _, c] = expr; // ExprType = ReturnStack&lt;...>
	 * 这个方案比较像Go语言
	 */
	private void unpackReturnStack(boolean isFinal) throws ParseException {
		List<Object> variables = new ArrayList<>();

		Token w;
		do {
			String name = wr.except(Token.LITERAL).text();
			variables.add(name);
			w = wr.next();
		} while(w.type() == comma);
		if (w.type() != rBrace) throw wr.err("unexpected_2:[\""+w.text()+"\",block.except.multiReturn]");

		wr.except(assign);

		var node = ep.parse(ExprParser.STOP_SEMICOLON|ExprParser.SKIP_SEMICOLON|ExprParser.NAE).resolve(ctx);
		IType type = node.type();
		if (!type.owner().equals(RETURNSTACK_TYPE) || ! (type instanceof ParameterizedType g)) {
			ctx.report(Kind.ERROR, "block.multiReturn.typeError");
			return;
		}

		List<IType> types = g.typeParameters;
		if (variables.size() != types.size()) {
			ctx.report(Kind.ERROR, "block.multiReturn.countError");
			return;
		}

		node.write(cw);
		cw.ldc(types.hashCode()); // 检测篡改 IncompatibleClassChangeError
		cw.invokeV(RETURNSTACK_TYPE, "forRead", "(I)L"+RETURNSTACK_TYPE+";");

		for (int i = 0; i < variables.size(); i++) {
			Variable v;
			String name = variables.get(i).toString();
			if (name.equals(VARIABLE_IGNORE)) v = null;
			else {
				v = newVar(name, types.get(i));
				v.hasValue = true;
				v.isFinal = isFinal;
			}
			variables.set(i, v);
		}

		// no needed to update VisMap
		beginCodeBlock();
		var tmp = newVar("@", Type.klass(RETURNSTACK_TYPE));
		cw.store(tmp);

		outerLoop:
		for (int i = 0; i < variables.size();) {
			IType vtype = types.get(i);
			int tc = vtype.getActualType();

			Variable v = (Variable) variables.get(i);
			i++;
			if (v == null) {
				if (tc == Type.OBJECT) {
					int prevI = i;
					while (true) {
						if (i == variables.size()) break outerLoop;
						if (variables.get(i) != null || types.get(i).getActualType() != Type.OBJECT) break;
						i++;
					}

					cw.load(tmp);
					cw.ldc(i - prevI + 1);
					cw.invokeV(RETURNSTACK_TYPE, "skipL", "(I)V");
				} else {
					int byteLength = vtype.rawType().length();
					while (true) {
						if (i == variables.size()) break outerLoop;
						IType vtyp1 = types.get(i);
						if (variables.get(i) != null || vtyp1.getActualType() == Type.OBJECT) break;

						byteLength += vtyp1.rawType().length();
						i++;
					}

					cw.load(tmp);
					cw.ldc(byteLength * 4);
					cw.invokeV(RETURNSTACK_TYPE, "skip", "(I)V");
				}
				continue;
			}

			cw.load(tmp);

			if (tc == Type.OBJECT) {
				cw.invokeV(RETURNSTACK_TYPE, "getL", "()Ljava/lang/Object;");
				cw.clazz(CHECKCAST, vtype.rawType());
			} else {
				cw.invokeV(RETURNSTACK_TYPE, "get"+(char)tc, "()"+(char)tc);
			}

			cw.store(v);
		}

		endCodeBlock();
	}

	/**
	 * 语句: 变量定义
	 * var|const|Type name ?[= expr];
	 */
	private void defineVariables(IType type, boolean isFinal) throws ParseException {
		if (type == null) {
			type = file.readType(CompileUnit.TYPE_PRIMITIVE|CompileUnit.TYPE_GENERIC);
		}
		type = ctx.resolveType(type);

		boolean needInfer = type == Types.anyType;
		Token w;
		do {
			var name = wr.except(Token.LITERAL).text();
			var var = new Variable(name, type);
			var.pos = wr.index;
			var.start = cw.createExternalLabel();
			if (isFinal) var.isFinal = true;

			w = wr.next();
			if (w.type() == assign) {
				var.hasValue = true;

				var node = ep.parse(ExprParser.STOP_SEMICOLON|ExprParser.STOP_COMMA|ExprParser.NAE|ExprParser._ENV_TYPED_ARRAY).resolve(ctx);

				var nodeType = node.type();
				if (nodeType.kind() <= 1 && RETURNSTACK_TYPE.equals(nodeType.owner()))
					ctx.report(Kind.WARNING, "multiReturn.sideEffect");

				// var和const需要推断类型
				if (needInfer) {
					var.type = ctx.writeVarCast(cw, node);
					// var t = null => Object;
					if (var.type == type) {
						ctx.report(Kind.WARNING, "block.var.noInitValue", name);
						if (!ctx.compiler.hasFeature(Compiler.ALLOW_DEFERRED_INFERENCE))
							var.type = Types.OBJECT_TYPE;
					}
				} else {
					var.type = ctx.writeCast(cw, node, type);
				}
				if (node.isConstant()) assignVar(var, node.constVal());

				cw.store(var);

				w = wr.next();
			} else {
				if ((sectionFlag&SF_FOREACH_VAR_DECL) != 0) {
					var.hasValue = true;
				} else {
					if (isFinal) ctx.report(Kind.ERROR, "block.var.final");
					if (!ctx.compiler.hasFeature(Compiler.ALLOW_DEFERRED_INFERENCE)) {
						if (type == Types.anyType) ctx.report(Kind.ERROR, "block.var.noAssign");
					}
					visMap.add(var);
				}
			}

			postDefineVar(type, var);
		} while (w.type() == comma);

		if (w.type() != semicolon) {
			if (w.type() != colon || (sectionFlag&SF_FOREACH_VAR_DECL) == 0) {
				if (!ctx.compiler.hasFeature(Compiler.OPTIONAL_SEMICOLON))
					wr.unexpected(w.text(), ";");
			}

			wr.retractWord();
		}
	}
	//endregion

	private void except(short id) throws ParseException {wr.except(id, byId(id));}

	private void setCw(MethodWriter cw) {wr.setCw(this.cw = cw);}
}