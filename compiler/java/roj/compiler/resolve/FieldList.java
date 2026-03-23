package roj.compiler.resolve;

import org.jetbrains.annotations.NotNull;
import roj.asm.ClassNode;
import roj.asm.FieldNode;
import roj.asm.Opcodes;
import roj.collect.ArrayList;
import roj.compiler.CompileContext;
import roj.compiler.CompileUnit;
import roj.compiler.api.Compiler;
import roj.compiler.api.FieldAccessHook;
import roj.compiler.diagnostic.IText;
import roj.text.CharList;
import roj.util.Pair;
import roj.util.function.Flow;

import static roj.compiler.diagnostic.IText.translatable;

/**
 * 主要是处理权限和泛型
 * @author Roj234
 * @since 2024/2/6 2:21
 */
final class FieldList extends ComponentList {
	final ArrayList<ClassNode> owners = new ArrayList<>();
	final ArrayList<FieldNode> fields = new ArrayList<>();
	private int childId;

	public Flow<Pair<ClassNode, FieldNode>> listFields() {
		return Flow.of(owners).zip(fields, Pair::new);
	}

	void add(ClassNode klass, FieldNode mn) {
		owners.add(klass);
		fields.add(mn);
	}

	boolean pack(ClassNode klass) {
		for (int i = 0; i < fields.size(); i++) {
			if (!owners.get(i).equals(klass)) {
				childId = i;
				return false;
			}
		}
		childId = fields.size();
		return childId == 1;
	}

	@NotNull
	public FieldResult findField(CompileContext ctx, int flags) {
		ArrayList<FieldNode> fields = this.fields;
		int size = (flags&THIS_ONLY) != 0 ? childId : fields.size();

		for (int j = 0; j < size; j++) {
			ClassNode owner = owners.get(j);
			FieldNode fn = fields.get(j);

			if (ctx.canAccessSymbol(owner, fn, (flags&IN_STATIC) != 0, false)) {
				checkBridgeMethod(ctx, owner, fn);
				checkDeprecation(ctx, owner, fn);
				return new FieldResult(owner, fn);
			}
		}

		CharList sb = new CharList().append(":[");

		String name = fields.get(0).name();
		sb.append(fields.get(0).name()).append(",[");

		IText rest = IText.empty();

		ctx.enableErrorCapture();

		for (int i = 0; i < size; i++) {
			ClassNode owner = owners.get(i);
			FieldNode fn = fields.get(i);

			ctx.canAccessSymbol(owner, fn, (flags&IN_STATIC) != 0, true);

			rest.append("  ").append(translatable("symbol.field")).append(owner.name()+"."+fn.name())
				.append(translatable("invoke.notApplicable")).append(ctx.getCapturedError()).append("\n");
		}

		ctx.disableErrorCapture();
		return new FieldResult(translatable("memberAccess.incompatible.plural", name, rest));
	}

	static void checkBridgeMethod(CompileContext ctx, ClassNode owner, FieldNode fn) {
		if ((fn.modifier&Opcodes.ACC_PRIVATE) == 0 || ctx.file == owner ||
			ctx.compiler.getMaximumBinaryCompatibility() >= Compiler.JAVA_11) return;

		if (fn.getAttribute(FieldAccessHook.NAME) != null) return;

		var fwr = new FieldBridge((CompileUnit) owner);
		fn.addAttribute(fwr);
	}
}