package roj.compiler.ast;

import roj.asm.ClassNode;
import roj.asm.FieldNode;
import roj.asm.MethodNode;
import roj.asm.Opcodes;
import roj.asm.attr.AnnotationDefault;
import roj.asm.attr.ConstantValue;
import roj.asm.attr.UnparsedAttribute;
import roj.asm.cp.*;
import roj.asm.frame.FrameVisitor;
import roj.asm.type.IType;
import roj.compiler.CompileContext;
import roj.compiler.CompileUnit;
import roj.compiler.LavaCompiler;
import roj.compiler.LavaTokenizer;
import roj.compiler.api.MethodDefault;
import roj.compiler.asm.AnnotationBuilder;
import roj.compiler.asm.MethodWriter;
import roj.compiler.ast.expr.Expr;
import roj.compiler.ast.expr.ExprParser;
import roj.compiler.ast.expr.NewArray;
import roj.compiler.ast.expr.RawExpr;
import roj.compiler.diagnostic.Kind;
import roj.config.node.ConfigValue;
import roj.text.ParseException;
import roj.util.ByteList;
import roj.util.DynByteBuf;

import java.util.List;

/**
 * Lava Compiler - 表达式或代码块懒解析<p>
 * Parser levels: <ol>
 *     <li>{@link CompileUnit Class Parser}</li>
 *     <li><b><i>Segment Parser</i></b></li>
 *     <li>{@link MethodParser Method Parser}</li>
 *     <li>{@link ExprParser Expression Parser}</li>
 * </ol>
 *
 * Stage 3:
 *   annotation, methodDefault
 * Stage 4:
 *   static {}, static field, enum
 *   {}, field
 *   method
 *
 * @author Roj234
 */
public interface ParseTask {
	static void defaultParameter(CompileUnit file, MethodNode mn, int id) throws ParseException {
		var lc = file.context();
		var wr = lc.tokenizer;
		int state = wr.setState(LavaTokenizer.STATE_EXPR);
		RawExpr expr = lc.ep.parse(ExprParser.STOP_RSB|ExprParser.STOP_COMMA|ExprParser.NAE);
		wr.state = state;

		if (!expr.isConstant()) lc.report(wr.current().pos(), Kind.WARNING, "ps.method.paramDef");

		String jsonString = ExprParser.serialize((Expr) expr);

		var attr = mn.getAttribute(null, MethodDefault.ID);
		if (attr == null) mn.addAttribute(attr = new MethodDefault());

		attr.defaultValue.put(id-1, jsonString);
	}

	static ParseTask annotationDefault(CompileUnit file, MethodNode mn) throws ParseException {
		var wr = file.context().tokenizer;
		int index = wr.index;
		int state = wr.setState(LavaTokenizer.STATE_EXPR);
		var expr = file.context().ep.parse(ExprParser.STOP_SEMICOLON|ExprParser.SKIP_SEMICOLON|ExprParser._ENV_TYPED_ARRAY|ExprParser.NAE);
		wr.state = state;

		var attr = new AnnotationDefault(null);
		mn.addAttribute(attr);

		return (ctx) -> {
			ctx.errorReportIndex = index;
			ctx.setMethod(file.getStaticInit().method);
			attr.val = AnnotationBuilder.evaluate(ctx, (Expr) expr, mn.returnType());
			ctx.errorReportIndex = -1;
		};
	}


	static ParseTask staticInit(CompileUnit file) throws ParseException {
		var wr = file.context().tokenizer;
		int linePos = wr.LN;
		int lineIdx = wr.LNIndex;
		int pos = wr.skipBrace();

		return (ctx) -> {
			ctx.tokenizer.init(pos, linePos, lineIdx);
			ctx.bp.parseBlockMethod(file, file.getStaticInit());
		};
	}

	static ParseTask instanceInit(CompileUnit file) throws ParseException {
		var wr = file.context().tokenizer;
		int linePos = wr.LN;
		int lineIdx = wr.LNIndex;
		int pos = wr.skipBrace();

		return new ParseTask() {
			@Override
			public int priority() {return 1;}
			@Override
			public void parse(CompileContext ctx) throws ParseException {
				ctx.tokenizer.init(pos, linePos, lineIdx);
				ctx.bp.parseBlockMethod(file, file.getGlobalInit());
			}
		};
	}
	static ParseTask field(CompileUnit file, FieldNode f) throws ParseException {
		var wr = file.context().tokenizer;
		int index = wr.index;
		int line = wr.LN;
		int state = wr.setState(LavaTokenizer.STATE_EXPR);
		var expr = file.context().ep.parse(ExprParser.STOP_COMMA|ExprParser.STOP_SEMICOLON|ExprParser._ENV_TYPED_ARRAY|ExprParser.NAE);
		wr.state = state;

		return new ParseTask() {
			private final boolean isStatic = (f.modifier & Opcodes.ACC_STATIC) != 0;

			@Override
			public boolean isStaticFinalField() {return (f.modifier&(Opcodes.ACC_STATIC|Opcodes.ACC_FINAL)) == (Opcodes.ACC_STATIC|Opcodes.ACC_FINAL);}
			@Override
			public int priority() {return isStatic ? 0 : 1;}
			@Override
			public void parse(CompileContext ctx) {
				// 已经解析
				if (ctx.fieldDFS && f.getAttribute("ConstantValue") != null) return;

				ctx.errorReportIndex = index;

				var file = ctx.file;

				IType fieldType = f.fieldType();
				if (expr instanceof NewArray def) def.setType(fieldType);
				var node = expr.resolve(ctx);

				ctx.errorReportIndex = -1;

				if ((f.modifier & Opcodes.ACC_FINAL) != 0) {
					if (!(node instanceof NewArray) && (node.isConstant() || node.hasFeature(Expr.Feature.LDC_CLASS))) {
						var constant = toConstant(node.constVal());
						f.addAttribute(new ConstantValue(constant));
					}

					if (ctx.fieldDFS) return;

					if (!file.uninitializedFinalFields.remove(f)) {
						ctx.report(Kind.ERROR, "symbol.field.writeAfterWrite", file.name(), f.name());
						return;
					}
				}

				if (isStatic) {
					if (f.getAttribute("ConstantValue") != null) return;

					var mp = file.getStaticInit();
					ctx.setMethod(mp.method);

					mp.lines().add(mp.label(), line);
					ctx.writeCast(mp, node, fieldType);
					mp.field(Opcodes.PUTSTATIC, file.name(), f.name(), f.rawDesc());
				} else {
					var mp = file.getGlobalInit();
					ctx.setMethod(mp.method);

					mp.lines().add(mp.label(), line);
					mp.vars(Opcodes.ALOAD, ctx.thisSlot);
					ctx.writeCast(mp, node, fieldType);
					mp.field(Opcodes.PUTFIELD, file.name(), f.name(), f.rawDesc());
					mp.visitSizeMax(1 + fieldType.rawType().length(), 1);
				}
			}
		};
	}
	private static roj.asm.cp.Constant toConstant(Object o) {
		if (o instanceof ConfigValue entry) {
			switch (entry.dataType()) {
				case 'F': return new CstFloat(entry.asFloat());
				case 'D': return new CstDouble(entry.asDouble());
				case 'J': return new CstLong(entry.asLong());
				case 'I', 'S', 'C', 'B', 'Z': return new CstInt(entry.asInt());
			}
		}
		if (o instanceof String) return new CstString(o.toString());
		if (o instanceof IType type) return new CstClass(type.rawType().toDesc());
		throw new UnsupportedOperationException("未预料的常量类型:"+o);
	}

	static ParseTask method(CompileUnit file, MethodNode mn, List<String> argNames) throws ParseException {
		var wr = file.context().tokenizer;
		int linePos = wr.LN;
		int lineIdx = wr.LNIndex;
		int pos = wr.skipBrace();

		return methodBody(mn, argNames, pos, linePos, lineIdx);
	}
	static ParseTask singleMethod(MethodNode mn, List<String> argNames) {return methodBody(mn, argNames, 0, 1, 0);}
	static ParseTask methodBody(MethodNode mn, List<String> argNames, int pos, int linePos, int lineIdx) {
		return new ParseTask() {
			@Override
			public int priority() {return 2;}
			@Override
			public void parse(CompileContext ctx) throws ParseException {
				var file = ctx.file;
				ctx.tokenizer.init(pos, linePos, lineIdx);
				MethodWriter cw = ctx.bp.parseMethod(file, mn, argNames);

				if (ctx.noCallConstructor) {
					ByteList buf = DynByteBuf.wrap(file.invokeDefaultConstructor());
					file.appendGlobalInit(cw, buf, cw.lines);

					cw.insertBefore(buf);
					buf.release();
				} else if (ctx.inConstructor && !ctx.thisConstructor) {
					file.appendGlobalInit(cw, null, cw.lines);
				}

				file.addParseTask(ctx1 -> {
					cw.computeFrames(file.version > ClassNode.JavaVersion(6)
											 ? FrameVisitor.COMPUTE_FRAMES | FrameVisitor.COMPUTE_SIZES
											 : FrameVisitor.COMPUTE_SIZES);

					try {
						cw.finish();
					} catch (Throwable e) {
						ctx1.report(Kind.INTERNAL_ERROR, "lava.internalError", e.toString());
						LavaCompiler.debugLogger().warn("lava.internalError", e);
					}

					mn.addAttribute(new UnparsedAttribute("Code", cw.bw.toByteArray()));
					cw.bw.release();
				});
			}
		};
	}

	default boolean isStaticFinalField() {return false;}
	default int priority() {return 0;}
	void parse(CompileContext ctx) throws ParseException;
}