package roj.compiler.resolve;

import org.jetbrains.annotations.NotNull;
import roj.asm.ClassNode;
import roj.asm.FieldNode;
import roj.compiler.CompileContext;
import roj.util.Pair;
import roj.util.function.Flow;

/**
 * @author Roj234
 * @since 2024/2/6 3:02
 */
final class FieldListSingle extends ComponentList {
	FieldListSingle(ClassNode owner, FieldNode node) {
		this.owner = owner;
		this.node = node;
	}

	final ClassNode owner;
	final FieldNode node;

	@Override
	public Flow<Pair<ClassNode, FieldNode>> listFields() {
		return Flow.of(new Pair<>(owner, node));
	}

	@NotNull
	public FieldResult findField(CompileContext ctx, int flag) {
		ctx.enableErrorCapture();
		try {
			if (ctx.canAccessSymbol(owner, node, (flag&IN_STATIC) != 0, true)) {
				FieldList.checkBridgeMethod(ctx, owner, node);
				checkDeprecation(ctx, owner, node);
				return new FieldResult(owner, node);
			}
			return new FieldResult(ctx.getCapturedError());
		} finally {
			ctx.disableErrorCapture();
		}
	}
}