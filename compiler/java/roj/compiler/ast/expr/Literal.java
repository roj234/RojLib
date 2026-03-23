package roj.compiler.ast.expr;

import org.jetbrains.annotations.NotNull;
import roj.asm.Opcodes;
import roj.asm.cp.CstClass;
import roj.asm.type.IType;
import roj.asm.type.Type;
import roj.compiler.CompileContext;
import roj.compiler.asm.MethodWriter;
import roj.compiler.resolve.ResolveException;
import roj.compiler.resolve.TypeCast;
import roj.config.node.BoolValue;
import roj.config.node.ConfigValue;
import roj.text.Tokenizer;
import roj.util.OperationDone;

import java.util.Objects;

/**
 * AST - 常量
 * @author Roj233
 * @since 2020/10/13 22:17
 */
final class Literal extends Expr {
	private static final Object CLASSREF = Literal.class;
	// 给CompileContext的getConstantValue省几个对象
	private boolean isClassRef() { return c == CLASSREF || c instanceof CstClass; }

	private final IType type;
	private Object c;

	Literal(IType type, Object c) {
		this.type = type;
		this.c = c;
	}

	@Override public String toString() { return c instanceof String ? '"'+Tokenizer.escape(c.toString())+'"' : isClassRef() ? type+".class" : String.valueOf(c); }
	@Override public boolean hasFeature(Feature feature) {return feature == Feature.IMMEDIATE_CONSTANT || (feature == Feature.LDC_CLASS && isClassRef());}
	@Override
	public IType minType() {
		if (c instanceof ConfigValue x && Type.getSort(type.getActualType()) <= Type.SORT_INT) {
			if (x.mayCastTo(roj.config.node.Type.Int1)) return Type.BYTE_TYPE;
			if (x.mayCastTo(roj.config.node.Type.Int2)) return Type.SHORT_TYPE;
			if (x.mayCastTo(roj.config.node.Type.CHAR)) return Type.CHAR_TYPE;
			if (x.mayCastTo(roj.config.node.Type.INTEGER)) return Type.INT_TYPE;
		}
		return type;
	}
	@Override public IType type() { return type; }
	@Override public boolean isConstant() { return !isClassRef(); }
	@Override public Object constVal() { return c; }

	@Override
	public Expr resolve(CompileContext ctx) throws ResolveException {
		if (c instanceof IType t) c = ctx.resolveType(t);
		return this;
	}

	@Override
	protected void write1(MethodWriter cw, @NotNull TypeCast.Cast cast) {throw OperationDone.NEVER;}
	@Override
	public void write(MethodWriter cw, @NotNull TypeCast.Cast cast) {
		if (cast.getOp1() != 0) {
			/*switch (cast.type) {
				case TypeCast.LOSSY, TypeCast.NUMBER_UPCAST, TypeCast.BOXING -> {}
			}*/
			char targetPrimitiveType = Opcodes.toString(cast.getOp1()).charAt(2);
			if (targetPrimitiveType == 'L') targetPrimitiveType = 'J';
			writePrimitive(cw, Math.max(Type.SORT_INT, Type.getSort(targetPrimitiveType)));
			if (cast.type == TypeCast.BOXING)
				cast.writeBox(cw);

			return;
		}
		if (cast.type == TypeCast.IMPLICIT) {
			int value = ((ConfigValue) c).asInt();
			// it is ok to cast
			if (value >= 0) cast.type = 0;
		}

		if (c instanceof IType t) {
			if (t.isPrimitive()) {
				cw.field(Opcodes.GETSTATIC, Objects.requireNonNull(Type.getWrapper(t)).owner, "TYPE", "Ljava/lang/Class;");
			} else {
				cw.ldc(new CstClass(t.rawType().getActualClass()));
			}
		} else if (c instanceof CstClass cx) cw.ldc(cx);
		else if (c == null) cw.insn(Opcodes.ACONST_NULL);
		else if ("java/lang/String".equals(type.owner())) cw.ldc(c.toString());
		else {
			assert type.isPrimitive();
			writePrimitive(cw, Type.getSort(type.getActualType()));
			cast.write(cw);
		}
	}

	private void writePrimitive(MethodWriter cw, int cap) {
		switch (cap) {
			case Type.SORT_BOOLEAN -> cw.ldc(c == BoolValue.TRUE ? 1 : 0);
			default -> cw.ldc(((ConfigValue) c).asInt());
			case Type.SORT_LONG -> cw.ldc(((ConfigValue) c).asLong());
			case Type.SORT_FLOAT -> cw.ldc(((ConfigValue) c).asFloat());
			case Type.SORT_DOUBLE -> cw.ldc(((ConfigValue) c).asDouble());
		}
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (!(o instanceof Expr r)) return false;

		return r.isConstant() && type.equals(r.type()) && (c == null ? r.constVal() == null : c.equals(r.constVal()));
	}

	@Override
	public int hashCode() {
		int result = type.hashCode();
		result = 31 * result + (c != null ? c.hashCode() : 0);
		return result;
	}
}