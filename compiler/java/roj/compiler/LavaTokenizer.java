package roj.compiler;

import roj.asm.attr.LineNumberTable;
import roj.collect.BitSet;
import roj.collect.Int2IntMap;
import roj.collect.IntBiMap;
import roj.collect.TrieTree;
import roj.compiler.asm.MethodWriter;
import roj.compiler.diagnostic.Kind;
import roj.compiler.doc.Javadoc;
import roj.compiler.plugins.annotations.AutoIncrement;
import roj.io.IOUtil;
import roj.text.*;
import roj.util.OperationDone;

import java.io.File;
import java.io.IOException;
import java.util.List;

import static roj.text.Token.EOF;
import static roj.text.Token.LITERAL;

/**
 * Lava词法分析器
 *
 * @author solo6975
 * @since 2020/10/3 19:20
 */
public final class LavaTokenizer extends Tokenizer {
	public static final short CHARACTER = 9;

	public static final String[] keywords = TextUtil.split1(
		// 类结构
		// 这些除外:
		// class => 常量
		// extends, super => 泛型
		"package,module,package-restricted,import,as," +
		"interface,@interface,enum,record,__struct,class," +
		"sealed,non-sealed," +
		"permits,implements,extends,super," +
		// MethodOnly
		"default,throws," +

		// 权限
		"public,protected,private,static,final," +
		// FieldOnly
		"volatile,transient," +
		// MethodOnly
		"strictfp,abstract,native,synchronized," +
		"\1a,const,_adt," +

		// 类型
		"void,boolean,byte,char,short,int,long,float,double," +
		// 表达式
		"this,true,false,null,new,instanceof,\1UDL," +

		// 方法内
		"for,while,do," +
		"continue,break,goto,return,throw," +
		"if,switch,try,assert," +
		"\1c,\1d,yield,with,_await,\1e,"+
		// statement rest
		"else,case,defer,catch,finally," +

		// 模块 with: #74
		"requires,exports,opens,uses,provides,transitive,to", ',');
	@AutoIncrement(10)
	public static final short
		PACKAGE = 10, MODULE = 11, PACKAGE_RESTRICTED = 12, IMPORT = 13, AS = 14,
		INTERFACE = 15, AT_INTERFACE = 16, ENUM = 17, RECORD = 18, STRUCT = 19, CLASS = 20,
		SEALED = 21, NON_SEALED = 22,
		PERMITS = 23, IMPLEMENTS = 24, EXTENDS = 25, SUPER = 26,
		DEFAULT = 27, THROWS = 28,

		PUBLIC = 29, PROTECTED = 30, PRIVATE = 31, STATIC = 32, FINAL = 33,
		VOLATILE = 34, TRANSIENT = 35,
		STRICTFP = 36, ABSTRACT = 37, NATIVE = 38, SYNCHRONIZED = 39,
		_unused0 = 40, CONST = 41, ADT = 42,

		VOID = 43, BOOLEAN = 44, BYTE = 45, CHAR = 46, SHORT = 47, INT = 48, LONG = 49, FLOAT = 50, DOUBLE = 51,
		THIS = 52, TRUE = 53, FALSE = 54, NULL = 55, NEW = 56, INSTANCEOF = 57, UDL = 58,

		FOR = 59, WHILE = 60, DO = 61,
		CONTINUE = 62, BREAK = 63, GOTO = 64, RETURN = 65, THROW = 66,
		IF = 67, SWITCH = 68, TRY = 69, ASSERT = 70,
		_unused2 = 71, _unused3 = 72, YIELD = 73, WITH = 74, AWAIT = 75, _unused4 = 76,
		ELSE = 77, CASE = 78, DEFER = 79, CATCH = 80, FINALLY = 81,

		REQUIRES = 82, EXPORTS = 83, OPENS = 84, USES = 85, PROVIDES = 86, TRANSITIVE = 87, TO = 88;

	public static final String[] operators = {
		// Syntax
		"{", "}",
		"[", "]",
		"(", ")",
		"->", ">>>>>>>>>>>", "?", ":",
		".", ",", ";",
		// Misc
		"?.", "@", "...", "::",
		// Unary
		"++", "--", "~", "!",
		// Number Binary (sorted to match java opcode order)
		"+", "-", "*", "/", "%", "**",
		"<<", ">>", ">>>",
		// Number Binary with boolean support
		"&", "|", "^",
		// Boolean Binary with boolean support
		"&&", "||",
		// nullish_coalescing
		"??",
		// Boolean Binary without boolean support
		"==", "!=", "<", ">=", ">", "<=",
		// Assign
		"=",
		"+=", "-=", "*=", "/=", "%=", "**=",
		"<<=", ">>=", ">>>=",
		"&=", "|=", "^=",
		// Misc? custom
		"<<<"
	};
	@AutoIncrement(100)
	public static final short
		lBrace = 100, rBrace = 101,
		lBracket = 102, rBracket = 103,
		lParen = 104, rParen = 105,
		lambda = 106, UNUSED = 107, ask = 108, colon = 109,
		dot = 110, comma = 111, semicolon = 112,

		optional_chaining = 113, at = 114, varargs = 115, method_referent = 116,

		inc = 117, dec = 118, inv = 119, logic_not = 120,

		add = 121, sub = 122, mul = 123, div = 124, rem = 125, pow = 126,
		shl = 127, shr = 128, ushr = 129,
		and = 130, or = 131, xor = 132,
		logic_and = 133, logic_or = 134,
		nullish_coalescing = 135,
		equ = 136, neq = 137, lss = 138, geq = 139, gtr = 140, leq = 141,

		assign = 142,
		// start from Number Binary
		binary_assign_base_offset = 143, binary_assign_count = 12,
		binary_assign_delta = add-binary_assign_base_offset,
		operator_end = binary_assign_base_offset+binary_assign_count;

	public static final short INT_MIN_VALUE = 155, LONG_MIN_VALUE = 156;
	private static final short INITIAL_TOKEN_COUNT = 157;

	private static final Int2IntMap C2C = new Int2IntMap();
	private static final TrieTree<Token> TOKEN_MAP = new TrieTree<>();
	private static final BitSet LITERAL_END = new BitSet();
	private static final BitSet[] LITERAL_STATE = new BitSet[5];

	public static final int STATE_CLASS = 0, STATE_MODULE = 1, STATE_EXPR = 2, STATE_TYPE = 3, STATE_HEADER = 4;
	public int state = STATE_CLASS;
	public BitSet[] literalState = LITERAL_STATE;

	public LavaTokenizer() {firstChar = C2C;}
	public static TrieTree<Token> getTokenMap() {return new TrieTree<>(TOKEN_MAP);}
	public static BitSet getLiteralEnd() {return LITERAL_END.copy();}
	public static BitSet[] getLiteralState() {
		BitSet[] r = LITERAL_STATE.clone();
		for (int i = 0; i < r.length; i++) {
			r[i] = r[i].copy();
		}
		return r;
	}
	public static short getInitialTokenCount() {return INITIAL_TOKEN_COUNT;}

	public static I18n i18n;
	static {
		String path = System.getProperty("roj.compiler.i18n");
		try {
			i18n = new I18n(path == null ? IOUtil.getTextResourceIL("roj/compiler/messages.properties") : IOUtil.readUTF(new File(path)));
		} catch (IOException e) {
			e.printStackTrace();
		}

		for (int state = 0; state < LITERAL_STATE.length; state++) {
			BitSet set = new BitSet();
			for (int id = 0; id < INITIAL_TOKEN_COUNT; id++) {
				if (id <= CHARACTER || id >= 100 || switch (state) {
					case STATE_HEADER -> id <= LavaTokenizer.DOUBLE;
					case STATE_CLASS -> id >= INTERFACE && id <= LavaTokenizer.DOUBLE;
					case STATE_EXPR -> id >= VOID && id <= FINALLY || id == CLASS || id == FINAL || id == CONST || id == DEFAULT || id == SUPER || id == SYNCHRONIZED;
					case STATE_TYPE -> id >= VOID && id <= FINALLY || id == SUPER || id == EXTENDS;
					case STATE_MODULE -> id >= REQUIRES && id <= TO || id == WITH || id == STATIC;
					default -> false;
				}) set.add(id);
			}
			LITERAL_STATE[state] = set;
		}

		addKeywords(TOKEN_MAP, 10, keywords);
		addSymbols(TOKEN_MAP, LITERAL_END, 100, operators);

		// 文本块的支持
		C2C.putAll(NUMBER_C2C);
		C2C.remove('"');

		TOKEN_MAP.put("\"", new Token().init(0, ST_STRING, "\""));
		TOKEN_MAP.put("'", new Token().init(4, -1, "'"));
		// hey, bro
		TOKEN_MAP.put("“", new Token().init(0, ST_STRING, "”"));
		TOKEN_MAP.put("//", new Token().init(0, ST_SINGLE_LINE_COMMENT, "//"));
		TOKEN_MAP.put("/*", new Token().init(0, ST_MULTI_LINE_COMMENT, "*/"));
		TOKEN_MAP.put("\"\"\"", new Token().init(1, -1, "\"\"\""));
		TOKEN_MAP.put("/**", new Token().init(2, -1, "*/"));
		TOKEN_MAP.put("`", new Token().init(3, -1, "`"));

		addWhitespace(LITERAL_END);
		LITERAL_END.remove('$');
		LITERAL_END.add('"');

		alias("。", dot, LITERAL_END);
		alias("，", comma, LITERAL_END);
		alias("：", colon, LITERAL_END);
		alias("；", semicolon, LITERAL_END);
		alias("？", ask, LITERAL_END);
		alias("（", lParen, LITERAL_END);
		alias("）", rParen, LITERAL_END);
		alias("【", lBracket, LITERAL_END);
		alias("】", rBracket, LITERAL_END);
		alias("《", lss, LITERAL_END);
		alias("》", gtr, LITERAL_END);
	}

	{ literalEnd = LITERAL_END; tokens = TOKEN_MAP; }

	@Override
	public final Tokenizer init(CharSequence seq) {
		super.init(seq);
		LN = 1;

		stack.clear();
		labelGen = null;
		lines = null;
		return this;
	}

	private static void alias(String kw, short begin, BitSet noLiterals) {
		TOKEN_MAP.put(kw, new Token().init(begin, 0, kw));
		if (noLiterals != null) noLiterals.add(kw.charAt(0));
	}

	public static String byId(short id) { return id < 100 ? keywords[id - 10] : operators[id - 100]; }

	@Override
	protected Token newWord() {return new MyToken();}
	private final class MyToken extends Token {
		@Override
		public short type() {
			short id = super.type();
			return id < 0 || literalState[state].contains(id) ? id : LITERAL;
		}

		@Override
		public Token immutable() {return new MyToken().init(super.type(), super.pos(), super.text());}
	}

	@Override
	protected boolean isValidToken(int off, Token w) {
		if (!super.isValidToken(off, w)) return false;
		int id = w.type();
		return state != STATE_TYPE || (id != shr && id != ushr);
	}

	public int skipBrace() throws ParseException {
		//assert => for normal call, but not lambda compiler (index == 0)
		//if (wd.type() != lBrace) throw new IllegalStateException("not lBrace");
		int pos = index;

		int L = 1;

		while (true) {
			Token w = next();
			switch (w.type()) {
				case lBrace: L++; break;
				case rBrace: if (--L == 0) return pos; break;
				case EOF: throw err("lexer.brace.mismatch");
			}
		}
	}
	public void init(int pos, int ln, int lnIndex) throws ParseException {
		super.init(getText());
		index = pos;
		LN = ln;
		LNIndex = lnIndex;
	}
	public void setText(CharSequence text, int index) {
		super.init(text);
		this.index = index;
	}

	@Override
	protected Token readNumber(boolean sign) throws ParseException {
		return super.readNumber(sign, true);
	}

	@Override
	protected Token onNumberFlow(CharList str, short from, short to) {
		if (str.equals("2147483648")) return formClip(INT_MIN_VALUE, "2147483648");
		CompileContext.get().report(Kind.ERROR, "lexer.number.overflow");
		return null;
	}

	public TrieTree<Integer> udlCallbacks;

	@Override
	protected Token onInvalidNumber(int errorLoc, String reason) throws ParseException {
		if (reason.equals("lexer.number.longLarge") && found.equals("9223372036854775808"))
			return formClip(LONG_MIN_VALUE, "9223372036854775808");
		if (reason.equals("lexer.number.notNumber:") && udlCallbacks != null) {
			Token token = readLiteral();

			CharList sb = found.reverse();
			var udl = udlCallbacks.longestMatch(sb, 0, input.length());
			if (udl != null) {
				sb.reverse().setLength(sb.length() - udl.getIntKey());
				return token.init(udl.getValue(), token.pos(), sb);
			}

			index = errorLoc;
		}

		if (reason.endsWith(":")) reason += "["+input.charAt(errorLoc)+"]";
		CompileContext.get().report(errorLoc, Kind.ERROR, reason);
		//throw err(reason, i);
		return readLiteral();
	}

	public Javadoc javadoc;

	@Override
	protected Token onSpecialToken(Token w) throws ParseException {
		switch (w.type()) {
			case 4 -> {
				return formClip(CHARACTER, readSlashString('\'', true));
			}
			case 3 -> {
				return formClip(LITERAL, readSlashString('`', true));
			}
			case 2 -> {
				readJavadoc();
				return CONSUMED_BY_SPECIAL_TOKEN;
			}
			case 1 -> {
				return readStringBlock();
			}
		}
		throw OperationDone.NEVER;
	}
	private void readJavadoc() {
		var doc = new Javadoc();
		var in = input;
		int i = index;
		var line = found;
		while (true) {
			line.clear();
			i = TextUtil.gAppendToNextCRLF(in, i, line);
			for (int j = 0; j < line.length(); j++) {
				char c = line.charAt(j);
				if (c != ' ' && c != '\t') {
					isBlockTag:
					if (c == '@' && !WHITESPACE.contains(line.charAt(++j))) {
						int k = j;
						while (k < line.length()) {
							if (WHITESPACE.contains(line.charAt(k))) {
								doc.visitBlockTag(line.substring(j, k));
								line.delete(0, k + 1);
								break isBlockTag;
							}
							k++;
						}
						doc.visitText(line.substring(j));
						line.clear();
					}
					// not block
					break;
				}
			}

			int j = line.indexOf("*/");
			if (j > 0) {
				doc.visitText(line.substring(0, j));
				i -= line.length() - j - 1;
				break;
			} else {
				doc.visitText(line);
			}
		}

		doc.visitEnd();
		if (javadoc != null) LavaCompiler.debugLogger().error("冲走"+javadoc);
		javadoc = doc;
		index = i+1;
	}
	private Token readStringBlock() throws ParseException {
		CharSequence in = input;
		int i = index;
		CharList v = found; v.clear();

		char c = in.charAt(i++);
		if (c != '\n' && (c != '\r' || in.charAt(i++) != '\n'))
			throw err("lexer.stringBlock.noCRLF", i);

		// 然后算初始的Indent
		int indent = i;
		while (true) {
			if (indent == in.length()) {
				indent = i;
				break;
			}

			c = in.charAt(indent);
			if (c != ' ' && c != '\t') break;
			indent++;
		}

		indent -= i;
		//if (indent == 0) throw err("lexer.stringBlock.noIndent", i);

		CharList line = new CharList();
		while (true) {
			int lend = TextUtil.gAppendToNextCRLF(in, i, line);

			identSucc: {
				fail:
				if (line.length() >= indent) {
					for (int j = 0; j < indent; j++) {
						char c1 = line.charAt(j);
						if (c1 != ' ' && c1 != '\t') break fail;
					}
					break identSucc;
				}

				throw err("lexer.stringBlock.indentChange", lend);
			}

			int j = line.rtrim().indexOf("\"\"\"", indent);
			if (j >= 0) {
				line.setLength(j);
				if (line.rtrim().length() > indent) v.append(line, indent, line.length());
				line._free();

				index = i+j+3;
				return formClip(Token.STRING, unescape(v));
			}

			i = lend;

			if (line.length() > indent) v.append(line, indent, line.length());
			v.append('\n');
			line.clear();
		}
	}

	private IntBiMap<MethodWriter> stack = new IntBiMap<>();
	private MethodWriter labelGen;
	private LineNumberTable lines;

	public void setCw(MethodWriter cw) {
		if (!stack.containsValue(cw)) {
			stack.put(stack.size(), cw);
			if (lines != null) lines.add(cw.createExternalLabel(), LN);
		} else {
			int id = stack.getByValue(cw);
			while (++id < stack.size()) stack.remove(id);
		}
		labelGen = cw;
	}

	public void setLines(LineNumberTable _new) {
		lines = _new;
		labelGen = null;
		stack.clear();
	}

	@Override
	protected void afterWord() {
		int line = LN;
		super.afterWord();
		if (line != LN) {
			if (lines != null) {
				if (labelGen.bci() == lines.lastBci()) {
					lines.list.getLast().setLine(LN);
				} else {
					lines.add(labelGen.createExternalLabel(), LN);
				}
			}
		}
	}

	@Override
	protected String i18n(String msg) { return i18n.translate(msg); }

	public final Token except(short type) throws ParseException {
		Token w = next();
		if (w.type() == type) return w;
		String s = type == LITERAL ? "lexer.identifier" : byId(type);
		throw err("unexpected_2:[\""+w.text()+"\",\""+s+"\"]");
	}

	public Token current() { return wd; }

	public boolean nextIf(short type) throws ParseException {
		if (next().type() == type) return true;
		retractWord(); return false;
	}

	public Token optionalNext(short type) throws ParseException {
		if (next().type() == type) next();
		return wd;
	}

	public int setState(int state1) {
		int prev = state;
		state = state1;
		return prev;
	}

	public void pushTokens(List<Token> tokens) {
		super.pushTokens(tokens);
	}
}