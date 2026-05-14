package roj.compiler.ast.expr;

import org.jetbrains.annotations.NotNull;
import roj.asm.Opcodes;
import roj.asm.type.IType;
import roj.asm.type.Type;
import roj.compiler.CompileContext;
import roj.compiler.LavaTokenizer;
import roj.compiler.api.Compiler;
import roj.compiler.api.Types;
import roj.compiler.asm.MethodWriter;
import roj.compiler.diagnostic.Kind;
import roj.compiler.resolve.TypeCast;
import roj.config.node.IntValue;

import static roj.compiler.LavaTokenizer.byId;

/**
 * AST - 赋值
 * @author Roj234
 * @since 2023/9/18 9:07
 */
final class Assign extends Expr {
	LeftValue left;
	Expr right;
	TypeCast.Cast cast;

	Assign(LeftValue left, Expr right) {
		this.left = left;
		this.right = right;
	}

	@Override public String toString() { return left+" = "+right; }
	@Override public IType type() { return left.type(); }

	public LeftValue getLeft() {return left;}
	public Expr getRight() {return right;}

	@Override
	public Expr resolve(CompileContext ctx) {
		Expr node = left.resolve(ctx);
		left = node.asLeftValue(ctx);
		if (left == null) return NaE.resolveFailed(this);

		if (right instanceof BinaryOp op) {
			right = op.resolveEx(ctx, true);
			if (right instanceof NaE) return right;

			if (node.equals(op.left)) {
				// a = a + b
				// check binary_assign override
				var override = ctx.getOperatorOverride(node, op.right, op.operator - LavaTokenizer.binary_assign_delta);
				if (override != null) return override;
			}

			if (right == null) {
				ctx.report(this, Kind.ERROR, "op.notApplicable.binary", op.left.type(), op.right.type(), byId(op.operator));
				return NaE.resolveFailed(this);
			}
		} else {
			right = right.resolve(ctx);
		}

		if (right instanceof Cast c) {
			int castType = c.cast.type;
			if ((castType == -1 || castType == -2) && !(left instanceof LocalVariable))
				ctx.report(this, Kind.FEATURE, "assign.incompatible.redundantCast");
		}

		if (left instanceof LocalVariable lv) {
			if (ctx.compiler.hasFeature(Compiler.ALLOW_DEFERRED_INFERENCE) && lv.v.type == Types.anyType) {
				lv.v.type = right.type();
			}
			// 常量传播

			if (right.isConstant()) {
				ctx.assignVar(lv.v, right.constVal());
			}
		}

		Object result = ctx.autoCast(right, left.type());
		if (result instanceof Expr expr) return expr;
		cast = (TypeCast.Cast) result;
		//if (!isCastNeeded()) cast = null;

		return cast.type >= 0 ? this : NaE.resolveFailed(this);
	}

	static void incOrDec(LeftValue expr, MethodWriter cw, boolean noRet, boolean returnBefore, int amount) {
		var ctx = cw.ctx;
		boolean isVariable = false;

		IType type = expr.type();
		int sort, primType = 0;
		if (type.isPrimitive()) {
			sort = Type.getSort(type.getActualType());
		} else {
			primType = TypeCast.getWrappedPrimitive(type);
			if (primType == 0) throw new UnsupportedOperationException("incOrDec(expr = " + expr + ", cw = " + cw + ", noRet = " + noRet + ", returnBefore = " + returnBefore + ", amount = " + amount+") fail");
			sort = Type.getSort(primType);
		}

		// == 4 (int) 防止byte自增导致溢出什么的...
		if (expr instanceof LocalVariable lv) {
			isVariable = true;

			if (sort == Type.SORT_INT && (short)amount == amount) {
				ctx.loadVar(lv.v);
				if (!noRet & returnBefore) cw.load(lv.v);
				cw.iinc(lv.v, amount);
				if (!noRet & !returnBefore) cw.load(lv.v);
				ctx.storeVar(lv.v);
				return;
			}
		}

		expr.preLoadStore(cw);

		int state = 0;
		if (!noRet & returnBefore) state = expr.copyValue(cw, type.rawType().length() - 1 != 0);
		if (primType != 0) ctx.castTo(type, Type.primitive(primType), 0).write(cw);

		int op;
		if (amount < 0 && amount != Integer.MIN_VALUE) {
			amount = -amount;
			op = Opcodes.ISUB;
		} else {
			op = Opcodes.IADD;
		}

		int unifiedSort = Math.max(Type.SORT_INT, sort);
		switch (unifiedSort) {
			case Type.SORT_INT: cw.ldc(amount); break;
			case Type.SORT_LONG: cw.ldc((long)amount); break;
			case Type.SORT_FLOAT: cw.ldc((float)amount); break;
			case Type.SORT_DOUBLE: cw.ldc((double)amount); break;
		}
		cw.insn((byte) (op - Type.SORT_INT + unifiedSort));

		// char t += 3; 因为java都是int所以有一个implicit转换插入
		if (sort < Type.SORT_INT && primType == 0 && isVariable) cw.insn((byte) (Opcodes.I2B - Type.SORT_BYTE + sort));
		else if (primType != 0) ctx.castTo(Type.primitive(primType), type, 0).write(cw);

		if (!noRet & !returnBefore) state = expr.copyValue(cw, type.rawType().length() - 1 != 0);
		expr.postStore(cw, state);
	}

	@Override
	@SuppressWarnings("fallthrough")
	protected void write1(MethodWriter cw, @NotNull TypeCast.Cast cast) {
		var noRet = cast == NORET;

		optimizedBinaryOp: {
			fallback:
			// a = a + 1 这种尝试优化成 iinc, 甚至 a = 1 + a 也行
			if (right instanceof BinaryOp op) {
				if (op.left.equals(left)) {
					if (sameVarShrink(cw, op, noRet, op.right)) return;
				} else if (op.left.isConstant() && op.right.equals(left)) {
					if (sameVarShrink(cw, op, noRet, op.left)) return;
				} else {
					break fallback;
				}

				if (isCastNeeded()) this.cast.write(cw);
				break optimizedBinaryOp;
			}

			left.preStore(cw);
			right.write(cw, isCastNeeded() ? this.cast : TypeCast.Cast.IDENTITY);
		}

		int state = noRet ? 0 : left.copyValue(cw, left.type().rawType().length() - 1 != 0);
		left.postStore(cw, state);
	}

	private boolean isCastNeeded() { return (cast.type != TypeCast.IMPLICIT && cast.type != TypeCast.LOSSY)/* || left.getClass() == LocalVariable.class*/; }

	private boolean sameVarShrink(MethodWriter cw, BinaryOp br, boolean noRet, Expr operand) {
		// to IINC if applicable
		block:
		if (left instanceof LocalVariable lv && Type.getSort(br.left.type().getActualType()) == Type.SORT_INT && operand.isConstant()) {
			int value = ((IntValue) operand.constVal()).value;

			switch (br.operator) {
				default: break block;
				case LavaTokenizer.add: break;
				case LavaTokenizer.sub: value = -value; break;
			}
			if ((short)value != value) break block;

			cw.ctx.loadVar(lv.v);
			cw.iinc(lv.v, value);
			if (!noRet) left.write(cw);
			cw.ctx.storeVar(lv.v);
			return true;
		}

		left.preLoadStore(cw);
		if (operand == br.left) br.writeLeft(cw);
		else br.writeRight(cw);
		br.writeOperator(cw);

		return false;
	}

	@Override
	public final boolean equals(Object o) {
		if (this == o) return true;
		if (!(o instanceof Assign assign)) return false;
		return assign.left.equals(left) && assign.right.equals(right);
	}

	@Override
	public final int hashCode() {
		int result = left.hashCode();
		result = 31 * result + right.hashCode();
		result = 31 * result + getClass().hashCode();
		return result;
	}
}