package roj.compiler.api;

import roj.compiler.CompileUnit;

import java.util.List;

/**
 * @author Roj234
 * @since 2026/05/09 22:39
 */
@FunctionalInterface
public interface StageHook {
	void postStage(int stage, Compiler compiler, List<CompileUnit> files);
}
