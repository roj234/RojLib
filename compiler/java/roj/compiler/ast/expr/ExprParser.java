package roj.compiler.ast.expr;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import roj.asm.MethodNode;
import roj.asm.Opcodes;
import roj.asm.attr.UnparsedAttribute;
import roj.asm.type.IType;
import roj.asm.type.Type;
import roj.asm.type.TypeVariable;
import roj.asm.type.TypeVariableDeclaration;
import roj.collect.ArrayList;
import roj.collect.Int2IntMap;
import roj.collect.IntList;
import roj.collect.IntMap;
import roj.compiler.CompileContext;
import roj.compiler.CompileUnit;
import roj.compiler.LavaTokenizer;
import roj.compiler.api.Compiler;
import roj.compiler.api.Types;
import roj.compiler.ast.MethodParser;
import roj.compiler.ast.ParseTask;
import roj.compiler.ast.VariableDeclare;
import roj.compiler.diagnostic.Kind;
import roj.compiler.types.LavaParameterizedType;
import roj.config.ConfigMaster;
import roj.config.ValueEmitter;
import roj.config.mapper.ObjectMapper;
import roj.config.node.ConfigValue;
import roj.io.IOUtil;
import roj.text.CharList;
import roj.text.ParseException;
import roj.text.Token;
import roj.util.Helpers;
import roj.util.function.Flow;

import java.util.Collections;
import java.util.List;

import static roj.compiler.LavaTokenizer.*;
import static roj.compiler.diagnostic.IText.translatable;
import static roj.text.Token.LITERAL;

/**
 * Lava Compiler - 表达式<p>
 * Parser levels: <ol>
 *     <li>{@link CompileUnit Class Parser}</li>
 *     <li>{@link ParseTask Segment Parser}</li>
 *     <li>{@link MethodParser Method Parser}</li>
 *     <li><b><i>Expression Parser</i></b></li>
 * </ol>
 * 您为终极的灵活性支付了复杂度的代价，并获得了相应的回报。
 * @version 2.7 (Typed lambda)
 * @author Roj233
 * @since 2024/01/22 13:51
 */
public final class ExprParser {
	public static final int
		STOP_SEMICOLON = 1, SKIP_SEMICOLON = 2,
		STOP_RSB = 4, SKIP_RSB = 8,
		STOP_RMB = 16, SKIP_RMB = 32,
		STOP_COMMA = 64, SKIP_COMMA = 128,
		STOP_RLB = 256,
		STOP_COLON = 512,
		STOP_LAMBDA = 1024,

		CHECK_VARIABLE_DECLARE = 2048,
		NAE = 4096,

		_ENV_INVOKE = 8192,
		_ENV_TYPED_ARRAY = 16384;

	static final int OP_OPTIONAL = 1;

	public static final int SM_UnaryPre = 1 << 29, SM_ExprStart = 2 << 29, SM_ExprNext = 3 << 29, SM_ExprTerm = 4 << 29, CU_TerminateFlag = 0x80000000;
	public static final int CU_Binary = 0x800, SM_RightAssoc = 0x80000000;

	public static Int2IntMap getStateMap() { return new Int2IntMap(SM); }
	public static IntMap<Type> getPrimitiveWords() { return PW; }

	private static final IntMap<Type> PW = new IntMap<>(8);
	private static final Int2IntMap SM = new Int2IntMap(64);
	static {
		String s = "VZBCSIJFD";
		for (int i = 0; i < s.length(); i++) {
			//noinspection MagicConstant
			PW.put(VOID+i, Type.primitive(s.charAt(i)));
		}

		SM.putInt(SM_UnaryPre | inc, -1);
		SM.putInt(SM_UnaryPre | dec, -1);

		SM.putInt(SM_UnaryPre | add, -1);
		SM.putInt(SM_UnaryPre | sub, -5);
		SM.putInt(SM_UnaryPre | logic_not, -1);
		SM.putInt(SM_UnaryPre | inv, -1);
		SM.putInt(SM_UnaryPre | lParen, -2);
		SM.putInt(SM_UnaryPre | LITERAL, -3);
		SM.putInt(SM_UnaryPre | SWITCH, -4);

		SM.putInt(SM_ExprStart | NEW, -1);
		SM.putInt(SM_ExprStart | CHARACTER, -2);
		SM.putInt(SM_ExprStart | Token.STRING, -3);
		SM.putInt(SM_ExprStart | Token.INTEGER, -4);
		SM.putInt(SM_ExprStart | Token.LONG, -5);
		SM.putInt(SM_ExprStart | Token.FLOAT, -6);
		SM.putInt(SM_ExprStart | Token.DOUBLE, -7);
		SM.putInt(SM_ExprStart | TRUE, -8);
		SM.putInt(SM_ExprStart | FALSE, -9);
		SM.putInt(SM_ExprStart | NULL, -10);
		SM.putInt(SM_ExprStart | LavaTokenizer.SUPER, -11);
		SM.putInt(SM_ExprStart | LavaTokenizer.THIS, -12);
		SM.putInt(SM_ExprStart | lBrace, -13);
		SM.putInt(SM_ExprStart | lBracket, -14);
		batch(SM_ExprStart, -15, /*VOID, */BOOLEAN, BYTE, CHAR, SHORT, INT, LONG, FLOAT, DOUBLE);
		SM.putInt(SM_ExprStart | LITERAL, -16);
		SM.putInt(SM_ExprStart | INT_MIN_VALUE, -17);
		SM.putInt(SM_ExprStart | LONG_MIN_VALUE, -17);

		SM.putInt(SM_ExprNext | lss, -1);
		SM.putInt(SM_ExprNext | lBracket, -2);
		SM.putInt(SM_ExprNext | optional_chaining, -3);
		SM.putInt(SM_ExprNext | dot, -4);
		SM.putInt(SM_ExprNext | LITERAL, -5);
		SM.putInt(SM_ExprNext | lParen, -6);
		SM.putInt(SM_ExprNext | assign, -7);
		for (int i = binary_assign_base_offset; i < binary_assign_base_offset+binary_assign_count; i++) {
			SM.putInt(SM_ExprNext | i, -7);
		}
		SM.putInt(SM_ExprNext | inc, -8);
		SM.putInt(SM_ExprNext | dec, -8);
		SM.putInt(SM_ExprNext | method_referent, -9);
		SM.putInt(SM_ExprNext | Token.STRING, -10);
		SM.putInt(SM_ExprNext | NEW, -11);

		// 终结
		// 0x400 = FLAG_TERMINATOR
		// 0x040 = FLAG_ALWAYS_STOP
		// 0x020 = FLAG_NEVER_SKIP
		SM.putInt(SM_ExprTerm | semicolon, 0x400);
		SM.putInt(SM_ExprTerm | rParen, 0x402);
		SM.putInt(SM_ExprTerm | rBracket, 0x404);
		SM.putInt(SM_ExprTerm | comma, 0x446);
		SM.putInt(SM_ExprTerm | rBrace, 0x428);
		SM.putInt(SM_ExprTerm | colon, 0x429);
		SM.putInt(SM_ExprTerm | lambda, 0x42A);

		// 优先级
		// 20250111 按照“正确”的优先级重新排序它们，但是并未改变val
		SM.putInt(SM_ExprTerm | pow, SM_RightAssoc | 8);

		SM.putInt(SM_ExprTerm | mul, 7);
		SM.putInt(SM_ExprTerm | div, 7);
		SM.putInt(SM_ExprTerm | rem, 7);

		SM.putInt(SM_ExprTerm | add, 6);
		SM.putInt(SM_ExprTerm | sub, 6);

		SM.putInt(SM_ExprTerm | shl, 9);
		SM.putInt(SM_ExprTerm | shr, 9);
		SM.putInt(SM_ExprTerm | ushr, 9);

		SM.putInt(SM_ExprTerm | lss, 5);
		SM.putInt(SM_ExprTerm | gtr, 5);
		SM.putInt(SM_ExprTerm | geq, 5);
		SM.putInt(SM_ExprTerm | leq, 5);

		SM.putInt(SM_ExprTerm | INSTANCEOF, -1);
		// 不是吧，这也低人一等？
		SM.putInt(SM_ExprTerm | equ, 5);
		SM.putInt(SM_ExprTerm | neq, 5);

		// 这三个个个不一样草了
		SM.putInt(SM_ExprTerm | and, 10);
		SM.putInt(SM_ExprTerm | or, 10);
		SM.putInt(SM_ExprTerm | xor, 10);

		SM.putInt(SM_ExprTerm | logic_and, 4);
		SM.putInt(SM_ExprTerm | logic_or, 4);
		SM.putInt(SM_ExprTerm | nullish_coalescing, 4);

		// 然后是
		SM.putInt(SM_ExprTerm | ask, -1);
	}
	private static void batch(int mask, int val, short... tokens) {
		for (short token : tokens) SM.putInt(token|mask, val);
	}

	private final CompileContext ctx;

	private final ArrayList<?> tmp0 = new ArrayList<>();
	private <T> List<T> tmp() {
		if (depth > 0) return new ArrayList<>();
		var t = tmp0; t.clear(); return Helpers.cast(t);
	}

	private final ArrayList<Expr> nodes = new ArrayList<>();
	private final IntList opStack = new IntList();
	private boolean shouldMerge(int stackTop, int newState) {
		int curState = stateMap.getInt(SM_ExprTerm | stackTop);
		// 优先级相同时考虑结合性
		if (curState == newState) return (curState&SM_RightAssoc) == 0;
		// 优先级不同时直接比较优先级, 这里用 == 或 >= 没有区别
		return (curState & 0x3FF) >= (newState & 0x3FF);
	}

	private int depth = -1;
	public Int2IntMap stateMap = SM;
	public List<Object> callbacks;

	public ExprParser(CompileContext ctx) {this.ctx = ctx;}

	public RawExpr parse(int flag) throws ParseException {
		hasGrammarError = false;
		depth = -1;
		RawExpr node;
		try {
			node = parse1(flag);
		} catch (ParseException e) {
			nodes.clear();
			opStack.clear();
			tmp0.clear();

			//throw e;
			ctx.report(Kind.ERROR, e.getMessage());
			// IDEA一边说不该滥用Nullable一边在这里疯狂infer
			return (flag & NAE) != 0 ? NaE.resolveFailed() : Helpers.maybeNull();
		}
		return node;
	}
	@Nullable
	@SuppressWarnings({"fallthrough", "unchecked"})
	private Expr parse1(int flag) throws ParseException {
		var wr = ctx.tokenizer;
		Token w;

		if (++depth > 127) throw wr.err("expr.stackOverflow");

		var nodes = this.nodes;
		int opCount = opStack.size();

		while (true) {
			PrefixOperator up = null;
			Expr cur = null;
			int _sid;

			w = wr.next();
			endValueConvNrt:{
			endValueConv:{
			endValueGen:{
			// region 前缀操作 (++ -- + - ! ~ 类型转换) 和 检测lambda 和 switch表达式
			// 一次性前缀操作(++ --)的判断在setRight中
			loop:
			while (true) {
				PrefixOperator pf;
				switch (_sid = stateMap.getOrDefaultInt(w.type()|SM_UnaryPre, 0)) {
					case -5: //dec
						w = wr.next();
						if (w.type() == INT_MIN_VALUE) {
							w = wr.next();
							cur = Expr.valueOf(ConfigValue.valueOf(Integer.MIN_VALUE));
							break endValueGen;
						} else if (w.type() == LONG_MIN_VALUE) {
							w = wr.next();
							cur = Expr.valueOf(ConfigValue.valueOf(Long.MIN_VALUE));
							break endValueGen;
						} else {
							wr.retractWord();
							pf = prefixOp(sub);
							break;
						}
					case -1: //inc, add, sub, logic_not, rev
						pf = prefixOp(w.type());
					break;
					case -2: //lParen
						int startOff = w.pos();

						wr.mark();
						var o = detectParen(wr);
						if (o == null) {// 表达式
							wr.retract();

							cur = parse1(STOP_RSB|SKIP_RSB);
							if (cur == null) ctx.report(Kind.ERROR, "expr.lambda.illegal");
							w = wr.next();
							break endValueGen;
						} else if (o instanceof IType type) {// 类型转换
							wr.except(rParen);

							pf = cast(type);
							pf.wordStart = startOff;
						} else {// lambda 【(a,b,c.....) -> {}】 [terminal operator]
							// [cast] <lambda> [invoke]
							cur = lambda(wr, (List<VariableDeclare>) o, flag);
							break endValueConv;
						}
					break;
					case -3: //LITERAL
						if ((flag&STOP_LAMBDA) == 0) {
							w = w.immutable(); // no more singleton
							if (wr.nextIf(lambda)) {
								cur = lambda(wr, Collections.singletonList(VariableDeclare.lambdaNamedOnly(w.text())), flag);
								break endValueConv;
							}
						}
					break loop;
					case -4: //switch
						cur = new Switch(ctx.bp.parseSwitch(true));
					break endValueConv;

					case 0: break loop;
					default: // 自定义前缀 | 自定义完整表达式
						int alt_sid = _sid & ~CU_TerminateFlag;
						if (_sid != alt_sid) {
							cur = ((Compiler.StartOp) callbacks.get(alt_sid)).parse(ctx);
							break endValueConv;
						}

						pf = ((Compiler.ContinueOp<PrefixOperator>) callbacks.get(alt_sid)).parse(ctx, up);
				}
				w = wr.next();

				if (up == null) nodes.add(pf);
				else {
					var code = up.setRight(pf);
					if (code != null) ctx.report(Kind.ERROR, code);
				}
				up = pf;
			}
			// endregion
			// *AI命名建议：后缀表达式（Postfix Expression） | 如方法调用`foo()`、成员访问`obj.field`等需在前缀基础上处理的语法结构。
			// region 一次性"值生成"(自造词)操作 (加载常量 new this 花括号(direct)数组内容 int[].class String.class)
			switch (_sid = stateMap.getOrDefaultInt(w.type()|SM_ExprStart, 0)) {
				case -1 -> {//NEW
					// double[]的部分不受支持
					// new <double[]>test<int[]>(new int[0], (Object) assign2op((short) 2));
					// test.<int[]>b();
					// String[] a = new test<int[]>(null, null).<String[]>a(new int[3]);
					int start = w.pos();
					IType newType = ctx.file.readType(CompileUnit.TYPE_PRIMITIVE|CompileUnit.TYPE_GENERIC|CompileUnit.TYPE_NO_ARRAY);

					w = wr.next();

					// String[][] array = new String[][0] {};
					// 从零开始，有数字则继续，至[]结束，往后均为null
					// 若有容量，则不能手动指定内容
					if (w.type() == lBracket) {
						List<Expr> args = tmp();
						int array = 1;
						arrayDef: {
							while (true) {
								Expr expr = parse1(STOP_RMB|SKIP_RMB);
								if (expr == null) break; // END OF COUNTED DEFINITION
								args.add(expr);

								w = wr.next();
								if (w.type() == lBracket) array++;
								else break arrayDef; // END OF ARRAY DEFINITION
							}

							while (true) {
								w = wr.next();
								if (w.type() != lBracket) break;

								wr.except(rBracket);
								array++;
							}
						}

						newType = newType.clone();
						newType.setArrayDim(array);
						// 以后可以把这个检查删了 CompileSpec
						if (newType instanceof LavaParameterizedType lp && lp.isGenericArray()) {
							ctx.report(Kind.ERROR, "expr.newArray.generic");
						}

						if (!args.isEmpty()) {
							cur = newArray(newType, copyOf(args), true);
						} else {
							if (w.type() != lBrace) throw wr.err("expr.newArray.unInit");
							cur = newArray(newType);
							w = wr.next();
						}
					} else if (w.type() == lParen) {
						List<Expr> args = tmp();
						parseInvokeArguments(args);
						var node = newInstance(newType, copyOrEmpty(args));

						w = wr.next();
						if (w.type() == lBrace) {
							wr.retractWord();
							wr.state = STATE_CLASS;
							var anonymousClass = ctx.file.newAnonymousClass(ctx.method);
							cur = newAnonymousClass(node, anonymousClass);
							wr.state = STATE_EXPR;

							w = wr.next();
						} else {
							cur = node;
						}
					} else {
						// 语法糖: new n => 无参数调用
						cur = newInstance(newType, Collections.emptyList());
					}
					cur.wordStart = start;
				break endValueGen;
				}
				// constant
				case -2 -> {
					var ch = w.text();
					if (ch.length() != 1) ctx.report(Kind.ERROR, "lexer.unterminated.character", w.pos());
					cur = Expr.constant(Type.CHAR_TYPE, ConfigValue.valueOf(ch.charAt(0)));
				}

				case -3 -> cur = Expr.valueOf(w.text());
				case -4 -> cur = Expr.valueOf(w.asInt());
				case -5 -> cur = Expr.constant(Type.LONG_TYPE, ConfigValue.valueOf(w.asLong()));
				case -6 -> cur = Expr.constant(Type.FLOAT_TYPE, ConfigValue.valueOf(w.asFloat()));
				case -7 -> cur = Expr.constant(Type.DOUBLE_TYPE, ConfigValue.valueOf(w.asDouble()));
				case -8 -> cur = Expr.valueOf(true);
				case -9 -> cur = Expr.valueOf(false);
				case -10-> cur = Expr.constant(Types.anyType, null);
				// MIN_VALUE_NUMBER_LITERAL
				case -17 -> ctx.report(Kind.ERROR, "lexer.number.overflow");
				// this
				case -11 -> cur = Super();
				case -12 -> cur = This();
				// lBrace (define unknown array)
				case -13 -> {
					check_param_map:
					if ((flag & _ENV_INVOKE) != 0) {
						// invoke_env:
						// a.b({xxx: yyy})

						wr.mark();
						w = wr.next();
						String firstName = w.text();
						if (w.type() != LITERAL || wr.next().type() != colon) {
							wr.retract();
							break check_param_map;
						}
						wr.skip();

						var npl = new NamedArgumentList();
						while (true) {
							Expr val = parse1(STOP_RLB|STOP_COMMA|SKIP_COMMA|NAE);
							if (!npl.add(firstName, val))
								ctx.report(Kind.ERROR, "expr.invoke.paramName", firstName);

							w = wr.next();
							if (w.type() != LITERAL) break;
							firstName = w.text();
							wr.except(colon);
						}
						if (w.type() != rBrace) ue(wr, w.text(), "lexer.identifier");
						cur = npl;
						break endValueConv;
					}

					if ((flag & _ENV_TYPED_ARRAY) == 0) {
						ctx.report(Kind.ERROR, "expr.newArray.noTypePresent");
					}

					// 可以直接写json，好像没啥用，先不加了
					// { "key" => "val" } like this
					// [t => 3, async => 4, testVal => [ ref => 1, tar => 2 ]];

					cur = newArray(null);
					break endValueConv;
				}
				case -14 -> {//lBracket
					//if ((flag & _ENV_FIELD) == 0) wr.unexpected(w.val());

					List<Expr> keys = tmp();

					checkMapLiteral: {
						List<Expr> values = new ArrayList<>();
						// 形如 [ exprkey -> exprval, ... ] 的直接Map<K, V>构建
						do {
							var key = parse1(STOP_LAMBDA|STOP_COMMA|SKIP_COMMA|NAE);
							keys.add(key);

							if (w.type() != lambda) {
								if (w.type() == comma && keys.size() == 1) break checkMapLiteral;
								else ue(wr, w.text(), "expr.mapLiteral.kvOrRet");
							}

							var val = parse1(STOP_RMB|STOP_COMMA|NAE);

							values.add(val);
							w = wr.next();
						} while (w.type() != rBracket);

						cur = new MapLiteral(copyOf(keys), values);
						break;
					}

					while (w.type() == comma) {
						keys.add(parse1(STOP_RMB|STOP_COMMA|SKIP_COMMA|NAE));
					}

					wr.except(rBracket, "]");
					cur = new ListLiteral(copyOf(keys));
				}
				case -15 -> {//primitive type ref
					Type typeRef = PW.get(w.type());
					assert typeRef != null;

					cur = _typeRef(wr.next(), typeRef, flag);
					if (cur == null) ctx.report(Kind.ERROR, "expr.typeRef.illegal");
					else if (cur.getClass() == VariableDeclare.class) return cur;
				}
				case -16 -> {//type ref
					while (true) {
						cur = chain(cur, w.text(), 0);

						w = wr.next();
						if (w.type() != dot) {
							if (w.type() == lBracket) {
								// some[ ].class
								// some[ ] variable
								// some[ index] ...
								Type typeRef = ((MemberAccess) cur).toClassRef();
								var tmp = _typeRef(w = w.immutable(), typeRef, flag);
								if (tmp != null) {
									if (tmp.getClass() == VariableDeclare.class) return tmp;
									cur = tmp;
									break;
								}
							} else if (nodes.isEmpty() && (flag&CHECK_VARIABLE_DECLARE) != 0) {
								if (w.type() == LITERAL) {
									// some  variable
									return new VariableDeclare(((MemberAccess) cur).toClassRef(), w.text());
								} else if (w.type() == lss) {
									// some< generic type> variable;
									w = w.immutable();
									wr.state = STATE_TYPE;
									boolean b = detectGeneric(wr);
									wr.state = STATE_EXPR;
									wr.retract();

									if (b) {
										IType type = ctx.file.readGenericPart(w, ((MemberAccess) cur).toClassRef().owner, false);

										w = wr.next();
										if (w.type() != LITERAL) throw wr.err("unexpected_2:[\""+w.text()+"\",lexer.identifier]");
										return new VariableDeclare(type, w.text());
									}
								}
							}

							break endValueGen;
						}

						w = wr.next();
						if (w.type() != LITERAL) {
							if (w.type() == CLASS) {
								cur = Expr.classRef(((MemberAccess) cur).toClassRef());
								break;
							} else if (w.type() == LavaTokenizer.THIS || w.type() == LavaTokenizer.SUPER) {
								cur = qualifiedThis(w.type() == LavaTokenizer.THIS, ((MemberAccess) cur).toClassRef());
								break;
							}
							_sid = 0;
							break endValueGen;
						}
					}
				}
				case 0 -> {break endValueGen;}
				default -> cur = ((Compiler.StartOp) callbacks.get(_sid)).parse(ctx);
			}
			w = wr.next();
			// endregion
			}//END endValueGen
			// region 重复性"值生成" (变量|字段访问 数组获取 函数调用) 和 值终止 (赋值运算符 后缀自增/自减 方法引用lambda)
			boolean waitDot = cur != null && _sid != 0;
			int opFlag = 0;
			while (true) {
				switch (_sid = stateMap.getOrDefaultInt(w.type()|SM_ExprNext, 0)) {
					case -1:{//lss
						// '小于'操作符
						if (waitDot) break endValueConvNrt;

						if (cur == null) ctx.report(Kind.ERROR, "expr.illegalStart");

						// 方法的泛型边界
						// 不能是<>
						// Helpers.<Helpers>nonnull().<int[]>nonnull();
						List<IType> bounds = tmp();
						do {
							bounds.add(ctx.file.readType(CompileUnit.TYPE_PRIMITIVE|CompileUnit.TYPE_GENERIC));
							w = wr.next();
						} while (w.type() != gtr);
						bounds = copyOf(bounds);

						cur = chain(cur, wr.except(LITERAL).text(), (opFlag&OP_OPTIONAL));
						opFlag = 0;
						wr.except(lParen);

						List<Expr> args = tmp();
						parseInvokeArguments(args);
						var node = invoke(cur, copyOrEmpty(args));

						node.wordStart = cur.wordStart;
						if (bounds != null) node.setExplicitArgumentType(bounds);

						cur = node;

						waitDot = true;
					break;}
					case -2://lBracket a[b]
						if (!waitDot) ue(wr, w.text(), "lexer.identifier");
						var index = parse1(STOP_RMB|SKIP_RMB);
						if (index == null) chain(cur, MemberAccess.EMPTY_BRACKET_MAGIC, 0);
						else cur = accessArray(cur, index);
					break;
					case -3://optional_chaining
						opFlag |= OP_OPTIONAL;
					//fallthrough
					case -4://dot
						if (!waitDot) ue(wr, w.text(), "lexer.identifier");
						waitDot = false;
					break;
					case -5://LITERAL => a.b
						if (!waitDot) {
							waitDot = true;
							cur = chain(cur, w.text(), (opFlag&OP_OPTIONAL));
							opFlag = 0;
							break;
						}

						if (ctx.compiler.hasFeature(Compiler.OPTIONAL_SEMICOLON) && cur != null) {
							wr.retractWord();
							break endValueConv;
						}

						ue(wr, w.text(), ".");
					break;
					case -6:{//lParen a(b...)
						if (!waitDot) ue(wr, w.text(), "lexer.identifier");

						int wordStart = ctx.tokenizer.prevIndex;
						List<Expr> args = tmp();
						parseInvokeArguments(args);

						var node = invoke(cur, copyOrEmpty(args));
						node.wordStart = wordStart;
						cur = node;
					break;}
					case -11:{//xxx.new SomeClass(...)
						if (waitDot) ue(wr, w.text(), ".");
						String className = wr.except(LITERAL, "cu.name").text();
						wr.except(lParen);

						List<Expr> args = tmp();
						args.add(cur);
						parseInvokeArguments(args);

						var node = newInstance(Type.klass(className), copyOf(args));
						node.wordStart = cur.wordStart;
						cur = node.setThisArg();
						waitDot = true;
					break;}

					// 我是无敌可爱的分隔线

					case -10://字符串格式化 FMT. ""
						if (cur instanceof MemberAccess dg && dg.maybeStringTemplate()) {
							//if (waitDot) ue(wr, w.val(), "."); // 使得.可加可不加
							cur = new TemplateStringLiteral(dg, w.text());
							break endValueConv;
						}
					case 0://其他token
						if (cur != null && !waitDot) ue(wr, w.text(), "lexer.identifier");
					break endValueConvNrt;

					case -7: {//assign及其变种
						if (!(cur instanceof LeftValue vn)) {
							ctx.report(Kind.ERROR, "expr.notVariable", cur);
							break endValueConv;
						}

						short vtype = w.type();

						Expr right = parse1(flag & ~(CHECK_VARIABLE_DECLARE|SKIP_SEMICOLON|SKIP_COMMA|SKIP_RMB|SKIP_RSB) | STOP_COMMA | NAE);
						cur = assign(vn, vtype == assign ? right : binaryOp(vtype, cur, right));
					}
					break endValueConv;
					case -8://inc, dec
						if (!(cur instanceof LeftValue vn)) ctx.report(Kind.ERROR, "expr.notVariable", cur);
						else cur = postfixOp(w.type(), vn);
					break endValueConv;
					case -9://method_referent this::stop
						if (!waitDot) ue(wr, w.text(), "lexer.identifier");
						w = wr.next();
						String method = w.text();
						if (w.type() != LITERAL) {
							if (w.type() == NEW) method = "<init>";
							else ctx.report(Kind.ERROR, "unexpected_2", w.text(), translatable("block.except.name"));
						}
						cur = new Lambda(cur, method);
					break endValueConv;

					// 我是无敌可爱的分隔线

					default: // 自定义继续 | 自定义终止
						int alt_sid = _sid & ~CU_TerminateFlag;

						cur = ((Compiler.ContinueOp<Expr>) callbacks.get(alt_sid)).parse(ctx, cur);

						if (_sid == alt_sid) break;
						else break endValueConv;
				}
				w = wr.next();
			}
			// endregion
			}//END endValueConv
			w = wr.next();
			}//END endValueConvNrt
			// 总结-在valueConv之外的终止: lambda lambdaB methodReference namedParamList 嵌套数组定义(省略new array[])

			// 应用前缀算符
			if (up != null) {
				var code = up.setRight(cur);
				if (code != null) ctx.report(Kind.ERROR, code);
			} else {
				// Nullable
				nodes.add(cur);
			}

			// 优先级也不低
			// if ("5"+3 instanceof String ? "5"+3 instanceof String : "5"+3 instanceof String);
			if (w.type() == INSTANCEOF) {
				cur = mergeOp(opCount, nodes);

				IType targetType = ctx.file.readType(CompileUnit.TYPE_GENERIC);
				String variable = wr.nextIf(LITERAL) ? w.text() : null;

				cur = instanceOf(targetType.rawType(), cur, variable);
				nodes.add(cur);

				w = wr.next();
			}

			// *AI命名建议：中缀运算符（Infix Operator） | 如`+`、`*`等连接子表达式的符号。
			// 二元运算符 | 三元运算符 | 终结符
			_sid = checkTerminalOrInfix(wr, w, flag, cur != null);
			if (_sid == 0) break;

			// 二元运算符
			checkNullExpr(cur);
			while (opStack.size() > opCount && shouldMerge(opStack.peek(), _sid)) {
				mergeOp(nodes);
			}
			opStack.add(w.type());
		}

		Expr cur = mergeOp(opCount, nodes);

		if (w.type() == comma) {
			if ((flag & STOP_COMMA) == 0) {
				checkNullExpr(cur);

				List<Expr> args = tmp();
				args.add(cur);

				int start = wr.current().pos();

				do {
					args.add(parse1(flag|STOP_COMMA|SKIP_COMMA|NAE));
					w = wr.current();
				} while (w.type() == comma);

				int _sid = checkTerminalOrInfix(wr, w, flag, true);
				if (_sid != 0) exceptingStopWord(w.text(), flag);

				cur = new SequenceExpr(copyOf(args));
				cur.wordStart = start;
			} else if ((flag & SKIP_COMMA) == 0) {
				wr.retractWord();
			}
		}

		// 这也是终结. 但是优先级最高
		if (w.type() == ask) {
			checkNullExpr(cur);
			Expr middle = parse1(flag|STOP_COLON);
			checkNullExpr(middle);
			wr.except(colon);
			Expr right = parse1(flag);
			checkNullExpr(right);
			cur = conditional(cur, middle, right);
		}

		if (hasGrammarError) cur = null;

		if (cur == null && (flag&NAE) != 0) {
			checkNullExpr(null);
			cur = NaE.NOEXPR;
		}

		depth--;
		return cur;
	}

	private Expr mergeOp(int opCount, ArrayList<Expr> nodes) {
		while (opStack.size() > opCount) mergeOp(nodes);
		return nodes.pop();
	}
	private void mergeOp(ArrayList<Expr> nodes) {
		short opInfo = (short)opStack.pop();

		Expr op, right = nodes.pop(), left = nodes.pop();
		//checkNullExpr(right);

		int sid = stateMap.getInt(SM_ExprTerm | opInfo);
		if ((sid & CU_Binary) != 0) {
			op = ((Compiler.BinaryOp) callbacks.get(sid >>> 12)).parse(ctx, left, right);
		} else {
			op = new BinaryOp(opInfo, left, right);
		}
		nodes.add(op);
	}

	private int checkTerminalOrInfix(LavaTokenizer wr, Token w, int flag, boolean hasExpr) {
		int _sid = stateMap.getOrDefaultInt(SM_ExprTerm | w.type(), 0);
		if (_sid == 0) {
			if (ctx.compiler.hasFeature(Compiler.OPTIONAL_SEMICOLON) && depth == 0 && hasExpr) {
				wr.retractWord();
				return 0;
			}

			exceptingStopWord(w.text(), flag);
			return 0;
		}

		if ((_sid&0x400) != 0) {
			// 除了逗号之外, 还有 instanceof 和 ?
			// 它们是-1, 所以这两个值都为真
			if ((_sid & 0x40) != 0) return 0;

			// 终结符
			// 0x400 = FLAG_TERMINATOR
			// 0x020 = FLAG_NEVER_SKIP
			int shl = _sid & 31;
			if ((flag & (1 << shl)) == 0) {
				if (ctx.compiler.hasFeature(Compiler.OPTIONAL_SEMICOLON) && depth == 0 && hasExpr) {
					wr.retractWord();
					return 0;
				}

				exceptingStopWord(w.text(), flag);
				return 0;
			}
			if ((_sid & 0x20) != 0 || (flag & (1 << (shl+1))) == 0) wr.retractWord();

			return 0;
		}

		return _sid;
	}

	private List<Expr> copyOrEmpty(List<Expr> args) {
		return args.isEmpty() ? Collections.emptyList() : copyOf(args);
	}

	private boolean hasGrammarError;
	private void checkNullExpr(Expr cur) {
		if (cur == null && !hasGrammarError) {
			ctx.report(Kind.ERROR, "expr.illegalEnd", ctx.tokenizer.current().text());
			hasGrammarError = true;
		}
	}

	@Nullable
	private Expr _typeRef(Token w, Type type, int flag) throws ParseException {
		var wr = ctx.tokenizer;

		int array = 0;
		if (w.type() == lBracket) {
			if (!wr.nextIf(rBracket)) return null;
			array++;

			while ((w = wr.next()).type() == lBracket) {
				wr.except(rBracket);
				array++;
			}

			type = type.clone();
			type.setArrayDim(array);
		}

		// 变量定义
		if (w.type() == LITERAL && nodes.isEmpty() && (flag&CHECK_VARIABLE_DECLARE) != 0) {
			// int [] variable
			return new VariableDeclare(type, w.text());
		}

		// int [] . class
		if (w.type() == dot && wr.nextIf(CLASS)) return Expr.classRef(type);

		if (array != 0) ctx.report(Kind.ERROR, "expr.typeRef.arrayDecl");
		return null;
	}
	private boolean detectGeneric(LavaTokenizer wr) throws ParseException {
		wr.mark();
		int depth = 1;
		// T<
		while (true) {
			var w = wr.next();
			if (w.type() == ask) return true; // T<? 只能是泛型

			// T<a
			if (w.type() == LITERAL) {
				while (true) {
					w = wr.next();
					if (w.type() != dot) break;
					w = wr.next();
					if (w.type() != LITERAL) return false;
				}

				// 嵌套 T<A<B
				if (w.type() == lss) {
					depth++;
					continue;
				}
			}
			// 如果不是基本类型，也不是LITERAL，那么不是泛型
			else if (w.type() < VOID || w.type() > DOUBLE) return false;
			// 基本类型不能接. 所以没有上面那个循环
			else w = wr.next();

			// 看看是不是泛型数组 T<A>[]
			while (w.type() == lBracket) {
				w = wr.next();
				// 如果不是数组格式
				if (w.type() != rBracket) return false;
				w = wr.next();
			}

			// T<A, B
			if (w.type() != comma) {
				// 必须以>结束泛型，明显这不是合法的表达式，所以实际上没有二义性
				if (w.type() != gtr) return false;

				do {
					// 直到泛型完全结束
					if (--depth == 0) return true;
					w = wr.next();
				} while (w.type() == gtr);
				// 循环继续
				wr.retractWord();
			}
		}
	}
	// 解析带有歧义的语法
	// Union<null(表达式), Type(类型转换), List<String>(lambda)>
	//
	// 是的我知道下面这种路径更简单，但是我过不了心理那一关
	// try readType
	//  match next()
	//   case 'LITERAL'
	//    try return typedLambda()
	//    except return expression
	//   case 'comma'
	//    try return untypedLambda()
	//    except return expression
	// 	 case 'rParen'
	//    if next() is Tag.ExpressionStart
	//      return cast
	// except return expression
	//
	//更形象的比喻
	// 第一种解析器像一个极度谨慎的侦探。在进入每个房间前，他先用各种工具窥探、分析、推理里面有什么（peek, if判断），直到有绝对把握才推门进去。这个过程很慢，而且一旦推理错误就全盘皆输。
	// 第二种解析器像一个行动力强的探险家。他看到一扇门，假设里面是宝藏，就直接闯进去。如果发现是死胡同，他就用魔法瞬间回到门口（回溯），并尝试打开另一扇门。他可能会闯进几个死胡同，但他总能找到正确的路，而且行动规则非常简单。
	//
	//最终，第二种方法之所以在现代解析器设计中更受欢迎，正是因为它承认了“完美预测所有情况”这一目标的不现实性。
	// 它通过接受“回溯”这一事实，并提供一套优雅、安全、结构化的机制来处理它，从而将开发者从无法管理的复杂性中解放出来。
	// 这是一种“打不过就加入”的智慧，用微小的、可控的性能代价，换来了巨大的开发效率、代码清晰度和可靠性的提升。
	private Object detectParen(LavaTokenizer wr) throws ParseException {
		Token w = wr.next();
		// 基本类型
		if (w.type() >= VOID && w.type() <= DOUBLE) {
			wr.retractWord();
			wr.skip();

			var primitive = ctx.file.readType(CompileUnit.TYPE_PRIMITIVE);
			if (wr.nextIf(LITERAL)) return pLambdaTyped(wr, primitive);
			return primitive;
		}
		// 非字面量
		if (w.type() != LITERAL) {
			// () -> ...
			if (w.type() == rParen) {
				if (wr.next().type() != lambda) {
					ctx.report(Kind.ERROR, "expr.lambda.pattern");
				}
				wr.skip();
				return Collections.emptyList();
			}
			return null;
		}

		String first = w.text();

		var sb = ctx.getTmpSb();
		sb.append(first);

		// lambda (a, b) ->
		notUntypedLambda:{
			while (true) {
				w = wr.next();
				if (w.type() != dot) {
					// 如果遇到逗号，那么不可能是类型转换
					// (a, b.c ...
					if (w.type() == comma) break;
					break notUntypedLambda;
				}
				w = wr.next();
				// 通常是 .class
				if (w.type() != LITERAL) return null;

				sb.append('/').append(w.text());
			}

			return pLambdaNoType(wr, first);
		}

		if (w.type() != rParen) {
			// 泛型 或 数组
			if (w.type() == lss || w.type() == lBracket) {
				IType extended;
				try {
					extended = ctx.file.readGenericPart(w, sb.toString(), true);
				} catch (Exception e) {
					// 也不是很corner的case: (variable < 5
					return null;
				}

				wr.skip();
				if (wr.nextIf(LITERAL)) return pLambdaTyped(wr, extended);
				return extended;
			}

			if (w.type() == LITERAL) {
				wr.skip();
				return pLambdaTyped(wr, Type.klass(sb.toString()));
			}

			return null;
		}

		// 类型转换语句后，必须是合法的表达式开始
		int flag = wr.next().type();
		flag = stateMap.getOrDefaultInt(flag|SM_UnaryPre, 0) | stateMap.getOrDefaultInt(flag|SM_ExprStart, 0);
		if (flag == 0) return null;

		String typename = sb.toString();
		// 然而我们还要处理 (A) - B 的砷铋情况，其中A可能是变量也可能是类型
		if (ctx.bp.getVariables().containsKey(typename)) return null;

		wr.skip(-2);

		var signature = ctx.file.activeSignature;
		TypeVariableDeclaration decl;
		if (signature != null && (decl = signature.resolveTypeVariable(typename)) != null)
			return new TypeVariable(decl);
		return Type.klass(typename);
	}

	private List<VariableDeclare> pLambdaTyped(LavaTokenizer wr, IType first) throws ParseException {
		Token w;
		List<VariableDeclare> names = tmp();
		// 这里resolve和Lambda里面没处理是对应的，主要是不想做nullcheck，否则放在Lambda表达式里面更加合适
		names.add(new VariableDeclare(ctx.resolveType(first), wr.current().text()));

		while (true) {
			w = wr.next();
			if (w.type() != comma) {
				if (w.type() == rParen) {
					w = wr.next();
					if (w.type() != lambda) {
						ctx.report(Kind.ERROR, "expr.lambda.pattern");
					}
					wr.skip();
					return names;
				}
			}

			IType type = ctx.file.readType(CompileUnit.TYPE_PRIMITIVE | CompileUnit.TYPE_GENERIC);
			String name = wr.except(LITERAL).text();
			names.add(new VariableDeclare(ctx.resolveType(type), name));
		}
	}

	private List<VariableDeclare> pLambdaNoType(LavaTokenizer wr, String first) throws ParseException {
		Token w;
		List<VariableDeclare> names = tmp();
		names.add(VariableDeclare.lambdaNamedOnly(first));

		// lambda或表达式，尝试读取参数列表
		while (true) {
			w = wr.next();
			if (w.type() != LITERAL) return null;
			names.add(VariableDeclare.lambdaNamedOnly(w.text()));

			w = wr.next();
			if (w.type() != comma) {
				if (w.type() == rParen) {
					w = wr.next();
					if (w.type() != lambda) {
						ctx.report(Kind.ERROR, "expr.lambda.pattern");
					}
					wr.skip();
					return names;
				}
			}
		}
	}

	public Expr chain(Expr expr, String name, int flag) {
		if (expr instanceof MemberAccess d) {
			d.wordEnd = ctx.tokenizer.index;
			return d.add(name, flag);
		}
		return new MemberAccess(expr, name, flag);
	}

	private void parseInvokeArguments(List<Expr> args) throws ParseException {
		while (true) {
			// _ENV_TYPED_ARRAY may not stable
			Expr expr = parse1(STOP_RSB|STOP_COMMA|SKIP_COMMA|_ENV_INVOKE|_ENV_TYPED_ARRAY);
			if (expr != null) {
				args.add(expr);
				if (ctx.tokenizer.current().type() != rParen && expr.getClass() != NamedArgumentList.class) continue;
			}

			ctx.tokenizer.except(rParen, ")");
			break;
		}
	}

	private Expr newArray(IType arrayType) throws ParseException {
		Token w = ctx.tokenizer.current();
		int wordStart = w.pos();

		List<Expr> args = tmp();
		do {
			var node = parse1(STOP_RLB|STOP_COMMA|SKIP_COMMA|_ENV_TYPED_ARRAY);
			if (node == null) break;
			args.add(node);
		} while (w.type() == comma);
		ctx.tokenizer.except(rBrace);

		var node = newArray(arrayType, copyOf(args), false);
		node.wordStart = wordStart;
		return node;
	}
	public Expr lambda(LavaTokenizer wr, List<VariableDeclare> argNameAndTypes, int stopWord) throws ParseException {
		var immutableArgTypes = copyOf(argNameAndTypes);
		var argNames = Flow.of(immutableArgTypes).map(def -> def.name).toList();

		var file = ctx.file;
		var method = new MethodNode(Opcodes.ACC_PRIVATE|(ctx.inStatic ? Opcodes.ACC_STATIC : 0)|Opcodes.ACC_SYNTHETIC, file.name(), "", "()V");
		ParseTask task;

		if (wr.nextIf(lBrace)) {
			task = ParseTask.method(file, method, argNames);
		} else {
			int start = wr.prevIndex;
			int linePos = wr.LN;
			int lineIdx = wr.LNIndex;
			Expr node = parse1(stopWord&(STOP_RSB|STOP_COMMA|STOP_SEMICOLON));
			if (node == null) {
				ctx.report(Kind.ERROR, "expr.illegalEnd", wr.current().text());
				return NaE.resolveFailed();
			}

			int end = wr.prevIndex;

			// 这里伪造offset是为了诊断能对上真实文件里的位置
			int offset = start-7; // 7是"return "的长度
			var methodStrTmp = "return "+wr.getText().subSequence(start, end)+";}";
			// TODO 一个更优雅的解决方案来将Expression lambda转换为Method lambda
			var methodStr = new CharSequence() {
				@Override public char charAt(int index) {return methodStrTmp.charAt(index - offset);}
				@Override public int length() {return methodStrTmp.length() + offset;}
				@Override public @NotNull CharSequence subSequence(int start, int end) {
					return new CharList().append(this, start, end).toStringAndFree();
				}
			};

			task = ctx -> {
				ctx.tokenizer.setText(methodStr, 0);
				ctx.tokenizer.init(offset, linePos, lineIdx);

				var cw = ctx.bp.parseMethod(ctx.file, method, argNames, MethodParser.MT_EXPRESSION_LAMBDA);
				cw.finish();
				method.addAttribute(new UnparsedAttribute("Code", cw.bw.toByteArray()));
			};
		}
		return new Lambda(immutableArgTypes, method, task);
	}

	private static void ue(LavaTokenizer wr, String wd, String except) throws ParseException { throw wr.err("unexpected_2:[\""+wd+"\","+except+"]"); }
	private void exceptingStopWord(String val, int flag) {
		var sb = IOUtil.getSharedCharBuf();
		if ((flag&STOP_SEMICOLON) != 0) sb.append(';');
		if ((flag&STOP_RSB) != 0) sb.append(')');
		if ((flag&STOP_RMB) != 0) sb.append(']');
		if ((flag&STOP_COMMA) != 0) sb.append(',');
		if ((flag&STOP_RLB) != 0) sb.append('}');
		if ((flag&STOP_COLON) != 0) sb.append(':');
		if ((flag&STOP_LAMBDA) != 0) sb.append("->");
		ctx.report(Kind.ERROR, "unexpected_2", val, sb.toString());
	}

	public int binaryOperatorPriority(short op) {return stateMap.getOrDefaultInt(SM_ExprTerm | op, -1) & 1023;}
	public boolean isLeftAssociative(short op) {return stateMap.getOrDefaultInt(SM_ExprTerm | op, -1) >= 0;}
	public boolean isCommutative(short op) {return op == add || op == mul;}

	public <T> List<T> copyOf(List<T> args) {
		if (!(args instanceof ArrayList<T>)) return args;
		ArrayList<T> copy = new ArrayList<>(args);
		args.clear();
		return copy;
	}

	private final This THIS = new This(true), SUPER = new This(false);
	public Expr This() {return THIS;}
	public Expr Super() {return SUPER;}

	public PrefixOperator prefixOp(short type) {return new PrefixOp(type);}
	public PrefixOperator cast(IType type) {return new Cast(type);}

	public Expr accessArray(Expr array, Expr index) {return new ArrayAccess(array, index);}
	public Expr newArray(IType type, List<Expr> args, boolean sized) {return new NewArray(type, args, sized);}
	public Expr assign(LeftValue cur, Expr node) {return new Assign(cur, node);}
	public Expr qualifiedThis(boolean ThisEnclosing, Type type) {return new QualifiedThis(ThisEnclosing, type);}
	public Invoke invoke(Expr expr, List<Expr> pars) {return new Invoke(expr, pars);}
	public Expr binaryOp(short op, Expr left, Expr right) {return new BinaryOp(op, left, right);}
	public Expr postfixOp(short op, LeftValue node) {return new PostfixOp(op, node);}
	public Expr instanceOf(Type type, Expr cur, String variable) {return new InstanceOf(type, cur, variable);}
	public Expr conditional(Expr cur, Expr middle, Expr right) {return new If(cur, middle, right);}
	public Invoke newInstance(IType type, List<Expr> pars) {return new Invoke(type, pars);}
	public Expr newAnonymousClass(Invoke expr, CompileUnit type) {return new NewAnonymousClass(expr, type);}

	private static final ObjectMapper MAPPER = ObjectMapper.pooled();

	public static String serialize(Expr node) {return MAPPER.writer(Expr.class).write(ConfigMaster.JSON, node, new CharList()).toStringAndFree(); }
	public static void serialize(Expr node, ValueEmitter visitor) { MAPPER.writer(Expr.class).write(visitor, node); }
	public static RawExpr deserialize(String string) throws ParseException {return MAPPER.reader(Expr.class).read(string, ConfigMaster.JSON);}
}