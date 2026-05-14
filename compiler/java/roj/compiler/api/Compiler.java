package roj.compiler.api;

import org.intellij.lang.annotations.MagicConstant;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Range;
import org.jetbrains.annotations.Unmodifiable;
import roj.asm.Attributed;
import roj.asm.ClassDefinition;
import roj.asm.ClassNode;
import roj.asm.MethodNode;
import roj.asm.annotation.Annotation;
import roj.asm.attr.InnerClasses;
import roj.asm.type.IType;
import roj.asm.type.Type;
import roj.asmx.AnnotationRepo;
import roj.collect.ToIntMap;
import roj.compiler.CompileContext;
import roj.compiler.CompileUnit;
import roj.compiler.LavaCompiler;
import roj.compiler.LavaTokenizer;
import roj.compiler.asm.AnnotationBuilder;
import roj.compiler.asm.MethodWriter;
import roj.compiler.asm.ParamAnnotationBinder;
import roj.compiler.ast.expr.Expr;
import roj.compiler.ast.expr.PrefixOperator;
import roj.compiler.diagnostic.Kind;
import roj.compiler.library.Library;
import roj.compiler.resolve.ComponentList;
import roj.compiler.resolve.LinkedClass;
import roj.text.CharList;
import roj.text.ParseException;
import roj.text.Token;
import roj.util.TypedKey;

import java.util.List;
import java.util.Map;

/**
 * @author Roj234
 * @since 2024/2/20 16:57
 */
public interface Compiler {
	/**
	 * 通过全限定名获取类结构
	 * @param name 类全限定名（使用'/'分隔，如java/lang/String）
	 */
	@Nullable ClassNode resolve(CharSequence name);
	/**
	 * 将库添加到编译器类路径
	 * @param library 要添加的库对象
	 * @implNote 与某些实现(javac)不同，此方法不会导致库中的代码被执行
	 */
	void addLibrary(Library library);
	/**
	 * 根据短名称查找可能存在的包路径
	 * @param shortName 短名称，例如"List"
	 * @return 包含完整包路径的候选列表, 如["java/lang"]，或空列表
	 */
	@NotNull @Unmodifiable List<String> getPackageNameByShortName(String shortName);

	//region 类扩展信息API
	@NotNull LinkedClass link(@NotNull ClassNode info);
	/**
	 * 修改指定类结构后，使指定类的ResolveHelper失效，并在下一次getResolveHelper中重新生成
	 * @param info 发生结构变化的类信息
	 * @apiNote 不是线程安全的，最好别用
	 */
	void unlink(ClassNode info);
	/**
	 * 获取类继承层级信息
	 * 值的高16位存放递增的index，低16位存放info类到key类需要向上转型的最低次数
	 * @param info 目标类信息
	 * @see LavaCompiler#getCommonAncestor(ClassNode, ClassNode)}
	 */
	@NotNull @Unmodifiable ToIntMap<String> getHierarchyList(ClassNode info);
	/**
	 * 获取方法重载列表，不存在时返回{@link ComponentList#NOT_FOUND}
	 */
	@NotNull ComponentList getMethodList(ClassNode info, String name);
	/**
	 * 获取字段重载列表，不存在时返回{@link ComponentList#NOT_FOUND}
	 */
	@NotNull ComponentList getFieldList(ClassNode info, String name);
	/**
	 * 获取泛型类型参数的实际类型
	 * 注意：info和superType是同一类型时将会返回null！
	 * @apiNote 最好用这个：{@link LavaCompiler#inferGeneric(IType, String)}
	 * @param superType 父类/接口的全限定名
	 * @return 类型参数列表，当superType不是info的父类/接口时返回null
	 * @throws ClassNotFoundException 当superType不存在时抛出
	 */
	@Nullable @Unmodifiable List<IType> getTypeArgumentsFor(ClassNode info, String superType) throws ClassNotFoundException;
	/**
	 * 获取内部类元信息
	 * @param info 外层类元数据（不可为null）
	 * @return 内部类名到元信息的不可变映射（键为全限定名，或以!开头的简单类名，只包含具名类，不包含匿名类或引用）
	 */
	@NotNull @Unmodifiable Map<String, InnerClasses.Item> getInnerClassInfo(ClassNode info);
	//endregion
	//region 预定义特性API
	// not change bytecode
	/**
	 * 生成MethodParameters属性(方法参数名)
	 */
	int EMIT_METHOD_PARAMETERS = 0;
	/**
	 * 生成SourceFile属性(源文件)
	 */
	int EMIT_SOURCE_FILE = 1;
	/**
	 * 生成LineNumberTable属性(行号)
	 */
	int EMIT_LINE_NUMBERS = 2;
	/**
	 * 生成LocalVariable及其泛型变种(方法变量类型)
	 * Not implemented
	 */
	int EMIT_LOCAL_VARIABLES = 3;
	/**
	 * 生成InnerClasses属性(内部类更详细的modifier及引用)
	 */
	int EMIT_INNER_CLASS = 4;

	/**
	 * 启用'可选的分号'
	 */
	int OPTIONAL_SEMICOLON = 6;
	/**
	 * 启用'文件名必须和公共类名相同'检查
	 */
	int VERIFY_FILENAME = 7;
	/**
	 * 创建对象时允许省略new关键字
	 * Allows the omission of the new keyword to create objects
	 */
	int OMISSION_NEW = 8;
	/**
	 * 允许延迟推断变量类型 (例如 var t; 后在 if 块中赋值)
	 * Allows deferred type inference for variables
	 */
	int ALLOW_DEFERRED_INFERENCE = 9;

	// change bytecode
	/**
	 * 使用带有数组缓存的CharList而不是StringBuilder进行字符串加法
	 * String addition using CharList with array caching instead of StringBuilder
	 * @see CharList#_free()
	 */
	int SHARED_STRING_CONCAT = 100;
	/**
	 * 不编译assert语句
	 * Dont compile assert statements
	 * */
	int DISABLE_ASSERT = 101;
	/**
	 * 将‘抛出检查的异常’的诊断等级从错误下调为严重警告
	 * 同时将允许try捕获未抛出的检查异常
	 * Downgraded the diagnostic level of 'Throwing unchecked exception' from ERROR to WARNING.
	 */
	int OMIT_CHECKED_EXCEPTION = 102;
	/**
	 * 将record类型的字段从private final提升为public final
	 */
	int PUBLIC_RECORD_FIELD = 103;
	/**
	 * 当synchronized对Lock的子类使用时，会调用其lock和unlock方法，而不是monitorEnter/exit
	 */
	int SYNCHRONIZED_LOCK = 104;
	/**
	 * 常量传播，通过VisMap，int test = 5 这种语句也能被提取常量，建议在调试时关闭
	 */
	int CONSTANT_SPREAD = 105;

	/**
	 * 某个编译器预定义特性{@link Compiler#EMIT_METHOD_PARAMETERS 比如这个}是否开启
	 */
	boolean hasFeature(int specId);
	//endregion
	//region 诊断API
	void report(ClassDefinition source, Kind kind, int pos, String code);
	void report(ClassDefinition source, Kind kind, int pos, String code, Object... args);
	//endregion
	//region 注解处理API
	/**
	 * 注册自定义注解处理器
	 *
	 * <p>支持处理的元素类型及处理器参数说明：
	 * <ul>
	 *   <li><b>类元素</b>（包括TYPE, ANNOTATION_TYPE, PACKAGE, MODULE）
	 *     <ul>
	 *           <li>{@code file}参数类型(后续相同)：
	 *             <ul>
	 *               <li>编译单元：{@link CompileUnit}</li>
	 *               <li>classpath类：{@link roj.asm.ClassView}</li>
	 *             </ul>
	 *           </li>
	 *           <li>{@code node}参数类型与{@code file}参数类型一致</li>
	 *     </ul>
	 *   </li>
	 *
	 *   <li><b>字段（FIELD）</b>
	 *     <ul>
	 *       <li>{@code node}参数类型：{@link roj.asm.FieldNode}</li>
	 *     </ul>
	 *   </li>
	 *
	 *   <li><b>方法（METHOD）</b>
	 *     <ul>
	 *       <li>{@code node}参数类型：{@link roj.asm.MethodNode}</li>
	 *     </ul>
	 *   </li>
	 *
	 *   <li><b>方法参数（METHOD_PARAMETER）</b>
	 *     <ul>
	 *       <li>{@code node}参数类型：
	 *         <ul>
	 *           <li>编译单元：{@link ParamAnnotationBinder}</li>
	 *           <li><i>classpath类：暂未实现</i></li>
	 *         </ul>
	 *       </li>
	 *     </ul>
	 *   </li>
	 *
	 *   <li><b>类型使用（TypeUse）</b>
	 *     <ul>
	 *       <li><i>暂未实现</i></li>
	 *     </ul>
	 *   </li>
	 * </ul>
	 *
	 * <p><b>注意事项：</b>
	 * <ul>
	 *   <li>标注"暂未实现"的功能可能在后续版本中支持</li>
	 *   <li>处理classpath中的方法参数注解时可能抛出未支持异常</li>
	 * </ul>
	 *
	 * @see Processor#handle(CompileContext, ClassDefinition file, Attributed node, Annotation)
	 */
	void addAnnotationProcessor(Processor processor);

	/**
	 * 获取classpath中所有注解。
	 * <p><b>性能警告：</b>此方法在初次调用时会扫描整个类路径，非常耗时。</p>
	 */
	AnnotationRepo getClasspathAnnotations();
	//endregion
	//region 库加载API
	@FunctionalInterface
	interface Resolver {
		/**
		 * 当 {@link Library} 中的类首次被加载到全局上下文时触发。
		 * <p>典型用途：
		 * <ul>
		 *   <li>动态修改类的方法实现（例如 AOP）</li>
		 *   <li>作为类加载限制的兜底措施（虽然我觉得{@link roj.compiler.resolve.ImportList#setRestricted(boolean) 包导入限制}足够了）</li>
		 * </ul>
		 *
		 * @return 修改后的类节点（可返回或修改原对象）
		 */
		ClassNode classResolved(ClassNode info);
	}

	/**
	 * 添加库解析监听器。
	 * @param priority 优先级（数值越小优先级越高）
	 */
	void addResolveListener(@Range(from = 0, to = 0xFFFF) int priority, Resolver resolver);
	//endregion
	//region 词法分析API
	int STATE_CLASS  = 1 << LavaTokenizer.STATE_CLASS;
	int STATE_MODULE = 1 << LavaTokenizer.STATE_MODULE;
	int STATE_EXPR   = 1 << LavaTokenizer.STATE_EXPR;
	int STATE_TYPE   = 1 << LavaTokenizer.STATE_TYPE;
	/**
	 * 创建具有指定分类的新词法标记。
	 *
	 * @param token 标记名称（允许包含空格和Unicode字符，需唯一）
	 * @param tokenCategory 标记上下文分类（使用位掩码组合下列常量：
	 *                      <ul>
	 *                        <li>{@link #STATE_CLASS} - 允许在类/接口声明上下文出现</li>
	 *                        <li>{@link #STATE_MODULE} - 允许在模块声明上下文出现</li>
	 *                        <li>{@link #STATE_EXPR} - 允许在表达式上下文出现</li>
	 *                        <li>{@link #STATE_TYPE} - 允许在类型声明上下文出现</li>
	 *                      </ul>
	 *                      例如 {@code STATE_CLASS | STATE_MODULE} 表示同时适用于两种上下文）
	 * @return 分配的全局唯一标记ID（正整数），可用于后续词法解析和语法分析
	 */
	int tokenCreate(String token, @MagicConstant(flags = {STATE_CLASS, STATE_MODULE, STATE_EXPR, STATE_TYPE}) int tokenCategory);

	/**
	 * 为现有标记注册等义词法表示。
	 * <p>例如将中文分号「；」映射到英文分号{@code ;}，可与{@link #tokenLiteralEnd}结合打造属于你的中文编程语言（bushi<br>
	 * 所有别名在词法分析阶段将被视为原始标记。
	 *
	 * @param token 目标标记ID（需通过{@link #tokenCreate}预先创建，或使用系统预定义ID）
	 * @apiNote 该方法不适用于动态标记（如{@link Token#FLOAT}等字面量类型）
	 * <p>不当调用可能导致词法分析阶段未定义行为
	 */
	void tokenAlias(int token, String alias);

	/**
	 * 注册字面量解析终止符。
	 * <p>当解析数值/字符/字符串等字面量时，遇到注册字符将立即终止当前字面量解析。
	 * 例如注册{@code '_'}后，数字字面量{@code 123_456}将被解析为{@code 123}和{@code _456}两个token。
	 *
	 * @param lend 终止符（长度必须为1，重复注册无效）
	 */
	void tokenLiteralEnd(String lend);

	/**
	 * 默认情况下，注册新的token会复用已有的ID，因而也会被识别为旧token，你可以使用这个函数删除已有的token
	 */
	void tokenDelete(String token);
	//endregion
	//region 表达式解析API
	/**
	 * 创建注解语句处理器，以 @typename 的方式开始DSL，详见文档
	 * @see AStatementParser
	 * @since 1.2
	 * @param typename 注解类型名称，不存在将自动生成
	 * @param impl 注解语句处理器实现
	 * @return 类型节点
	 */
	@NotNull ClassNode addAnnotationStatement(String typename, AnnotationStatement impl);
	interface AnnotationStatement {
		void parse(CompileContext ctx, MethodWriter writer, List<AnnotationBuilder> annotations);
	}

	/**
	 * 表达式解析流程:<br>
	 * 一次 不可重复前缀一元运算符 (++ --)<br>
	 * 多次 可重复前缀一元运算符 (+ - ! ~ 类型转换)<br>
	 * 一次 值生成运算符 (new this super 常量 类型[.class .this .super] Lava.EasyMap Lava.NamedParamList 嵌套数组创建省略new switch表达式模式)<br>
	 * 多次 值转换运算符 (方法调用 数组访问 字段读取)<br>
	 * 一次 值终止运算符 (后缀++ -- 赋值 ::)<p>
	 * 或<p>
	 * 一次 表达式生成运算符 (lambda)<br>
	 * <p>
	 * 而后再解析二元运算符<p>
	 * * 代码与此注释未必相同，不过结果相同
	 * <p>
	 * 所有名称为token的参数都是运算符，<br>
	 * 它们可以是任意字符串、可以包含空白字符、并且不能与其它注册的运算符重复
	 */
	@FunctionalInterface
	interface StartOp { Expr parse(CompileContext ctx) throws ParseException;}

	@FunctionalInterface
	interface ContinueOp<T extends Expr> { T parse(CompileContext ctx, T node) throws ParseException;}

	@FunctionalInterface
	interface BinaryOp { Expr parse(CompileContext ctx, Expr left, Expr right); }

	@FunctionalInterface
	interface UDLOp { Expr parse(CompileContext ctx, Token token) throws ParseException; }

	/**
	 * 注册自定义字面量
	 * @param unit 单位，如km，应(SHOULD)只包含字母或数字，不得(MUST NOT)以[0-9]开始，不应(SHOULD NOT)以[a-fA-FLl]开始
	 * @param parser 接收完整的数字Token，如 0x3km 需要自行判断格式、解析数字、并抛出异常
	 * @since 1.3
	 */
	void newUDLOp(String unit, UDLOp parser);

	/**
	 * 新增前缀运算符, 后缀可以直接使用TerminalOp
	 */
	void newUnaryOp(String token, ContinueOp<PrefixOperator> parser);

	/**
	 * 新增表达式生成运算符<br>
	 * 该运算符自身就是一个完整的表达式<br>
	 * 值模式流程：(生成 -> 转换 -> 终止)
	 */
	void newExprOp(String token, StartOp parser);

	/**
	 * 新增值生成运算符
	 * 参考: &lt;minecraft:stone&gt;
	 */
	void newStartOp(String token, StartOp parser);
	/**
	 * 新增值转换运算符
	 */
	void newContinueOp(String token, ContinueOp<Expr> parser);
	/**
	 * 新增值终止运算符
	 */
	void newTerminalOp(String token, ContinueOp<Expr> parser);
	/**
	 * 新增一个二元运算符
	 * @param token 运算符 如果重复会报错
	 * @param priority 运算符优先级，不加括号时，越大越晚处理
	 * @param rightAssoc 结合方式，右结合
	 */
	void newBinaryOp(String token, int priority, BinaryOp parser, boolean rightAssoc);
	//endregion
	//region 运算符重载API
	@FunctionalInterface
	interface ExprOp { Expr test(CompileContext ctx, OperatorContext opctx, Expr left, Object right);}
	record OperatorContext(short symbol, IType leftType, IType rightType) { }

	/**
	 * 为已存在的一元运算符指定方法
	 * 参考: !"" => "".isEmpty()
	 * @param operator 运算符 可以是任意字符串 如果不使用特殊符号，可能需要管理{@link #tokenLiteralEnd(String) LiteralEnd}
	 * @param type 该运算符适配的类型
	 * @param node 调用的方法 可以是静态的
	 * @param side bitset bit1:左侧(UnaryPre) bit2:右侧(UnaryPost)
	 */
	void onUnary(String operator, Type type, MethodNode node, int side);
	/**
	 * 为已存在的二元运算符指定方法
	 * 参考: "awsl" * 10 => "awsl".repeat(10)
	 * 参考: 5 ** 3      => Math.pow(5, 3)
	 *
	 * @param left 该运算符左侧的类型
	 * @param operator 运算符 可以是任意字符串 如果不使用特殊符号，可能需要管理{@link #tokenLiteralEnd(String) LiteralEnd}
	 * @param right 该运算符右侧的类型
	 * @param node 调用的方法
	 * @param swap 是否允许交换左右操作数（当 node 是静态方法时有效）
	 */
	void onBinary(Type left, String operator, Type right, MethodNode node, boolean swap);
	/**
	 * 自定义运算符处理逻辑
	 * @param operator 运算符 可以是任意字符串 如果不使用特殊符号，可能需要管理{@link #tokenLiteralEnd(String) LiteralEnd}
	 */
	void addOpHandler(String operator, ExprOp resolver);
	//endregion
	//region 附件API
	<T> T attachment(TypedKey<T> key);
	// 存放类型安全的任意对象，线程安全，使val=null以清除
	<T> T attachment(TypedKey<T> key, T val);
	//endregion

	// lambda
	int JAVA_8 = 8;
	// module
	int JAVA_9 = 9;
	/**
	 * constant_dynamic,
	 * 使用Java11新增的NestMember属性而不是生成access方法来允许内部类的访问
	 * Use Java11's new NestMember attribute instead of generating an access method to allow access to an inner class.
	 * <a href="https://www.baeldung.com/java-nest-based-access-control">Nest based access control</a>
	 */
	int JAVA_11 = 11;
	/**
	 * Record,
	 * 使用Java17新增的PermittedClass属性来更好的控制抽象枚举类的可继承性
	 * Use Java17's new PermittedClass attribute to better control the inheritability of abstract enumerated classes
	 * */
	int JAVA_17 = 17;
	// string template via lambda
	int JAVA_21 = 21;
}