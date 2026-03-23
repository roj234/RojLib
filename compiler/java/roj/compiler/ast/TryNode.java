package roj.compiler.ast;

import roj.asm.insn.Label;
import roj.collect.ArrayList;
import roj.compiler.asm.Variable;

import java.util.List;

/**
 * 存放try-with-resource或defer的数据
 * @author Roj234
 * @since 2024/4/30 16:47
 */
final class TryNode extends FlowHook {
	final List<Variable> resources = new ArrayList<>();
	final List<Label> pos = new ArrayList<>();

	int placeholderId;
	Variable listVar, listSizeVar;

	public void add(Variable closeable, Label defineAt) {
		resources.add(closeable);
		pos.add(defineAt);
	}
}