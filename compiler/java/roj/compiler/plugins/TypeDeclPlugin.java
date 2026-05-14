package roj.compiler.plugins;

import roj.asm.MethodNode;
import roj.asm.Opcodes;
import roj.asm.type.IType;
import roj.asm.type.ParameterizedType;
import roj.compiler.CompileContext;
import roj.compiler.CompileUnit;
import roj.compiler.api.Compiler;
import roj.compiler.api.CompilerPlugin;
import roj.compiler.ast.expr.Expr;
import roj.compiler.ast.expr.Invoke;
import roj.text.ParseException;

import java.util.Collections;

/**
 * @author Roj234
 * @since 2024/12/1 8:35
 */
@CompilerPlugin(name = "typedecl", desc = """
		加入支持编译和运行期泛型推断的__Type( type )表达式

		例如 method(__Type ( List<String> ))
		其中，method应当接收roj.asm.IType<T>
		注意：IType不是真正的泛型，你应该通过IType的成员函数读取类型信息""")
public final class TypeDeclPlugin implements Compiler.StartOp {
	public TypeDeclPlugin(Compiler api) {api.newExprOp("__Type", this);}

	private final MethodNode typeDecl = new MethodNode(Opcodes.ACC_STATIC|Opcodes.ACC_PUBLIC, "roj/asm/type/Signature", "parseGeneric", "(Ljava/lang/CharSequence;)Lroj/asm/type/IType;");
	@Override
	public Expr parse(CompileContext ctx) throws ParseException {
		IType type = ctx.resolveType(ctx.file.readType(CompileUnit.TYPE_PRIMITIVE | CompileUnit.TYPE_GENERIC | CompileUnit.TYPE_ALLOW_VOID));

		var node = Invoke.staticMethod(typeDecl, Expr.valueOf(type.toDesc()));
		node.setGenericReturnType(new ParameterizedType("roj/asm/type/IType", Collections.singletonList(type)));
		return node;
	}
}
