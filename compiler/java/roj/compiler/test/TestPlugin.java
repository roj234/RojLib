package roj.compiler.test;

import org.jetbrains.annotations.NotNull;
import roj.asm.MethodNode;
import roj.asm.Opcodes;
import roj.asm.type.IType;
import roj.asm.type.Type;
import roj.compiler.CompileContext;
import roj.compiler.LavaTokenizer;
import roj.compiler.api.Compiler;
import roj.compiler.asm.MethodWriter;
import roj.compiler.ast.expr.Expr;
import roj.compiler.ast.expr.Invoke;
import roj.compiler.ast.expr.PrefixOperator;
import roj.compiler.resolve.ResolveException;
import roj.compiler.resolve.TypeCast;
import roj.text.Token;
import roj.util.Helpers;

/**
 * @author Roj234
 * @since 2024/2/20 1:28
 */
public class TestPlugin {
	public TestPlugin(Compiler api) {
		// <minecraft:stone>
		api.newStartOp("<", (ctx) -> {
			var wr = ctx.tokenizer;
			try {
				String nsKey = wr.except(Token.LITERAL).text();
				wr.except(LavaTokenizer.colon);
				String nsVal = wr.except(Token.LITERAL).text();
				wr.except(LavaTokenizer.gtr);

				MethodNode mn = new MethodNode(Opcodes.ACC_PUBLIC, "roj/compiler/test/CandyTestPlugin$Item", "<init>", "(Ljava/lang/String;Ljava/lang/String;)V");
				return Invoke.constructor(mn, Expr.valueOf(nsKey), Expr.valueOf(nsVal));
			} catch (Exception e) {
				Helpers.athrow(e);
				return null;
			}
		});

		// <minecraft:stone> * 5
		MethodNode mn = new MethodNode(Opcodes.ACC_STATIC, "roj/compiler/test/CandyTestPlugin$Item", "stack", "(Lroj/compiler/test/CandyTestPlugin$Item;I)Lroj/compiler/test/CandyTestPlugin$ItemStack;");
		api.onBinary(Type.klass("roj/compiler/test/CandyTestPlugin$Item"), "*", Type.INT_TYPE, mn, true);

		api.newUnaryOp("debugger", (ctx, node) -> new PrefixOperator() {
			Expr node;
			@Override public String setRight(Expr node) {this.node = node;return null;}
			@Override public String toString() {return node.toString();}
			@Override public IType type() {return node.type();}

			@Override
			public Expr resolve(CompileContext ctx) throws ResolveException {
				node = node.resolve(ctx);
				return this;
			}

			@Override protected void write1(MethodWriter cw, @NotNull TypeCast.Cast cast) {
				node.write(cw, cast);
			}
		});
	}
}