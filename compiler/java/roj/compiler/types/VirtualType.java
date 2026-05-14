package roj.compiler.types;

import roj.asm.type.IType;
import roj.asm.type.Type;
import roj.compiler.api.Types;
import roj.compiler.resolve.TypeCast;
import roj.text.CharList;
import roj.util.OperationDone;

import static roj.compiler.resolve.Resolver.debugLogger;

/**
 * @author Roj234
 * @since 2026/01/29 17:26
 */
public class VirtualType implements IType {
	public static IType anyType(String name) {return new VirtualType(name);}

	protected final String name;

	protected VirtualType(String name) {this.name = name;}

	public String getI18nKey() {return name;}

	@Override public void toDesc(CharList sb) {
		debugLogger().warn("VirtualType.toDesc(): " + name);
		sb.append("Ljava/lang/Object;");
	}

	@Override
	public String owner() {
		debugLogger().warn("VirtualType.owner(): " + name);
		return "java/lang/Object";
	}

	@Override public void toString(CharList sb) {sb.append(name);}
	@Override public String toString() {return name;}
	@Override public IType clone() {
		try {
			return (IType) super.clone();
		} catch (CloneNotSupportedException e) {
			throw OperationDone.NEVER;
		}
	}

	@Override public Type rawType() {return Types.OBJECT_TYPE;}
	@Override public byte kind() {return ANY_TYPE;}
	public TypeCast.Cast castFrom(IType type) {return TypeCast.ERROR(TypeCast.IMPOSSIBLE);}
	public TypeCast.Cast castTo(IType type) {return TypeCast.ERROR(TypeCast.IMPOSSIBLE);}
}
