package roj.asm.cp;

import org.intellij.lang.annotations.MagicConstant;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import roj.asm.AsmCache;
import roj.asm.attr.BootstrapMethods;
import roj.collect.ArrayList;
import roj.collect.IntMap;
import roj.math.MathUtils;
import roj.text.TextUtil;
import roj.util.ByteList;
import roj.util.DynByteBuf;

import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

import static roj.asm.cp.Constant.*;

/**
 * @author Roj234
 * @version 3.0
 * @since 2021/5/29 17:16
 */
public final class ConstantPool {
	public static final int ONLY_STRING = -1, BYTE_STRING = 0, CHAR_STRING = 1;

	private final ArrayList<Constant> constants;
	private RefMap refMap;
	private int length;
	private boolean useSharedArray;

	public ConstantPool(int size) {constants = new ArrayList<>(size);}
	/**
	 * Initialize for WRITE (intern calls)
	 */
	public ConstantPool() {constants = new ArrayList<>();}

	public void read(DynByteBuf r, @MagicConstant(intValues = {ONLY_STRING,BYTE_STRING,CHAR_STRING}) int stringDecodeType) {
		int len = r.readUnsignedShort()-1;
		if (len < 0) throw new IllegalArgumentException("size is negative: "+len);

		constants.clear();
		constants.ensureCapacity(len);
		constants._setSize(len);

		if (refMap != null) refMap.clear();

		Object[] csts = constants.getInternalArray();

		int begin = r.rIndex;

		if (stringDecodeType == ONLY_STRING) {
			readName(r, csts, len, listener);

			length = r.rIndex - begin;
			return;
		}

		boolean decodeUtf = stringDecodeType != BYTE_STRING;

		int twoPass = len;
		int i = 0;
		while (i < len) {
			Constant c;
			try {
				c = readConstant(r, csts, i, decodeUtf);
			} catch (Exception e) {
				String cst;
				try {
					cst = String.valueOf(csts[i]);
				} catch (Exception ignored) {
					cst = csts[i].getClass().getName();
				}

				throw new IllegalArgumentException("常量池["+i+"]解析失败！当前值: "+cst, e);
			}

			csts[i++] = c;
			c.index = (char) i;
			if (listener != null) listener.accept(c);
			switch (c.type()) {
				case LONG, DOUBLE -> csts[i++] = CstTop.TOP;
				case METHOD_HANDLE -> {
					var mh = (CstMethodHandle) c;
					Object c1 = csts[mh.getRefIndex() - 1];
					if (c1 != null) mh.setTarget((CstRef) c1);
					else if (twoPass == len) twoPass = i - 1;
				}
			}
		}

		// kind确定ref的类型，不过暂时摸鱼中
		i = twoPass;
		while (i < len) {
			Constant c = (Constant) csts[i++];
			if (c.type() == METHOD_HANDLE) {
				CstMethodHandle mh = (CstMethodHandle) c;
				mh.setTarget((CstRef) csts[mh.getRefIndex()-1]);
			}
		}

		length = r.rIndex - begin;
	}
	private static void readName(DynByteBuf r, Object[] csts, int len, Consumer<Constant> listener) {
		int i = 0;
		while (i < len) {
			switch (r.getByte(r.rIndex)) {
				case UTF, CLASS -> {
					var c = readConstant(r, csts, i, false);
					if (listener != null) listener.accept(c);
					csts[i++] = c;
					c.index = (char) i;
				}
				case INT, FLOAT, NAME_AND_TYPE, FIELD, METHOD, INTERFACE, DYNAMIC, INVOKE_DYNAMIC -> {
					r.rIndex += 5;
					i++;
				}
				case LONG, DOUBLE -> {
					r.rIndex += 9;
					i += 2;
				}
				case METHOD_TYPE, MODULE, PACKAGE, STRING -> {
					r.rIndex += 3;
					i++;
				}
				case METHOD_HANDLE -> {
					r.rIndex += 4;
					i++;
				}
				default -> throw new IllegalArgumentException("不支持的常量类型"+r.dump());
			}
		}
	}
	private static Constant readConstant(DynByteBuf r, Object[] arr, int i, boolean parseUTF) {
		int b = r.readUnsignedByte();
		switch (b) {
			case UTF: {
				Object data;
				if (parseUTF) data = r.readUTF();
				else {
					int len = r.readUnsignedShort();
					data = len <= 16 ? r.readBytes(len) : r.slice(len);
				}

				CstUTF known = (CstUTF) arr[i];
				if (known != null) {
					known.data = data;
					return known;
				}
				return new CstUTF(data);
			}
			case INT: return new CstInt(r.readInt());
			case FLOAT: return new CstFloat(r.readFloat());
			case LONG: return new CstLong(r.readLong());
			case DOUBLE: return new CstDouble(r.readDouble());

			case METHOD_TYPE, MODULE, PACKAGE, CLASS, STRING: {
				int id = r.readUnsignedShort()-1;
				CstUTF utf;
				if (arr[id] == null) arr[id] = utf = new CstUTF();
				else utf = (CstUTF) arr[id];

				CstRefUTF known = (CstRefUTF) arr[i];
				if (known != null) {
					known.setValue(utf);
					return known;
				}

				return switch (b) {
					case METHOD_TYPE -> new CstMethodType(utf);
					case MODULE -> new CstRefUTF.Module(utf);
					case PACKAGE -> new CstRefUTF.Package(utf);
					case CLASS -> new CstClass(utf);
					default -> new CstString(utf);
				};
			}
			case NAME_AND_TYPE: {
				int id = r.readUnsignedShort()-1;
				CstUTF name;
				if (arr[id] == null) arr[id] = name = new CstUTF();
				else name = (CstUTF) arr[id];

				CstUTF type;
				id = r.readUnsignedShort()-1;
				if (arr[id] == null) arr[id] = type = new CstUTF();
				else type = (CstUTF) arr[id];

				CstNameAndType known = (CstNameAndType) arr[i];
				if (known != null) {
					known.name(name);
					known.rawDesc(type);
					return known;
				}

				return new CstNameAndType(name, type);
			}
			case FIELD, METHOD, INTERFACE: {
				int id = r.readUnsignedShort()-1;
				CstClass clz;
				if (arr[id] == null) arr[id] = clz = new CstClass();
				else clz = (CstClass) arr[id];

				CstNameAndType nat;
				id = r.readUnsignedShort()-1;
				if (arr[id] == null) arr[id] = nat = new CstNameAndType();
				else nat = (CstNameAndType) arr[id];

				return switch (b) {
					case FIELD -> new CstRef.Field(clz, nat);
					case METHOD -> new CstRef.Method(clz, nat);
					default -> new CstRef.Interface(clz, nat);
				};
			}
			case DYNAMIC, INVOKE_DYNAMIC: {
				int id = r.getUnsignedShort(r.rIndex + 2)-1;
				CstNameAndType desc;
				if (arr[id] == null) arr[id] = desc = new CstNameAndType();
				else desc = (CstNameAndType) arr[id];

				CstDynamic dyn = new CstDynamic(b == INVOKE_DYNAMIC, r.readUnsignedShort(), desc);
				r.rIndex += 2;
				return dyn;
			}
			case METHOD_HANDLE: return new CstMethodHandle(r.readByte(), r.readUnsignedShort());
			default: throw new IllegalArgumentException("不支持的常量类型"+r.dump());
		}
	}

	public final List<Constant> constants() {return constants;}

	//region resolve
	/**
	 * 从缓冲区读取一个指定类型的常量。
	 *
	 * @param r 包含 u2 索引的缓冲区
	 * @param <T> 预期的常量类型
	 * @return 对应的常量对象
	 * @throws IndexOutOfBoundsException 如果索引无效
	 */
	@SuppressWarnings("unchecked")
	public final @NotNull <T extends Constant> T resolve(DynByteBuf r) {return (T) constants.getInternalArray()[r.readUnsignedShort()-1];}
	/**
	 * 从缓冲区读取常量。
	 *
	 * @param r 包含 u2 索引的缓冲区
	 * @return 常量对象，如果索引为 0 则返回 null
	 */
	public final @Nullable Constant resolveOrNull(DynByteBuf r) {
		int i = r.readUnsignedShort()-1;
		return i < 0 ? null : (Constant) constants.getInternalArray()[i];
	}
	/**
	 * 从缓冲区读取一个{@link CstClass}常量，并返回其引用的类名。
	 *
	 * @param r 包含 u2 索引的缓冲区
	 * @return 类名字符串，如果索引为 0 则返回 null
	 */
	public final @Nullable String resolveClassName(DynByteBuf r) {
		int i = r.readUnsignedShort()-1;
		return i < 0 ? null : ((CstClass) constants.getInternalArray()[i]).value().str();
	}
	/**
	 * 从缓冲区读取一个{@link CstRefUTF}常量，验证其类型，并返回引用值。
	 * @param expectedType 期望的常量类型（如 MODULE, PACKAGE 等）
	 */
	public final @NotNull String resolveName(DynByteBuf r, int expectedType) {
		var c = (CstRefUTF) constants.getInternalArray()[r.readUnsignedShort()-1];
		if (c.type() != expectedType) throw new IllegalStateException("excepting"+Constant.toString(expectedType)+" but got "+c);
		return c.value().str();
	}
	public final @NotNull CstRef resolveMember(DynByteBuf r, boolean isField) {
		var c = (CstRef) constants.getInternalArray()[r.readUnsignedShort()-1];
		if (c.type() == FIELD != isField) throw new IllegalStateException("excepting" + (isField ? "field" : "method") + "but got "+c);
		return c;
	}
	//endregion

	private void initRefMap() {
		if (refMap == null) {
			refMap = new RefMap(Math.max(constants.size(), 16));
		} else if (refMap.isEmpty()) {
			refMap.ensureCapacity(constants.size());
		} else {
			return;
		}

		Object[] cst = constants.getInternalArray();
		for (int i = 0; i < constants.size(); i++) {
			Constant c = (Constant) cst[i];
			if (c == CstTop.TOP) continue;

			Constant c1 = refMap.intern(c);
			if (c != c1) c.index = c1.index;
		}

		if (!useSharedArray) {
			useSharedArray = true;
			AsmCache.getInstance().retainHugeArray(constants);
		}
	}

	public void add(Constant c) {
		constants.add(c);
		int size = constants.size();
		c.index = (char) size;
		if (size >= 0xFFFF) throw new IllegalStateException("常量池满!");

		switch (c.type()) {
			case UTF -> length += 3 + DynByteBuf.countJavaUTF(((CstUTF) c).str());
			case INT, FLOAT, NAME_AND_TYPE, INVOKE_DYNAMIC, DYNAMIC, METHOD, FIELD, INTERFACE -> length += 5;
			case LONG, DOUBLE -> {
				length += 9;
				constants.add(CstTop.TOP);
			}
			case METHOD_TYPE, STRING, CLASS, MODULE, PACKAGE -> length += 3;
			case METHOD_HANDLE -> length += 4;
			default -> throw new IllegalArgumentException("不支持的常量类型"+c.type()+" "+c.getClass().getName());
		}

		if (refMap != null && !refMap.isEmpty()) {
			refMap.ensureCapacity(size);
			refMap.add(c);
		}
		if (listener != null) listener.accept(c);
	}
	public boolean contains(Constant c) {
		int id = c.index-1;
		return id >= 0 && id < constants.size() && constants.getInternalArray()[id] == c;
	}

	public void setUTFValue(CstUTF utf, String str) {
		if (!contains(utf)) throw new IllegalArgumentException(utf+"不在该常量池中");
		verifyUtfLength(str);

		boolean rm;
		if (refMap != null) {
			rm = refMap.remove(utf);
			assert rm : "不在该常量池中";
		} else {
			rm = false;
		}

		int prev = utf.byteLength();
		int curr = ByteList.countJavaUTF(str);

		length += curr - prev;

		utf.data = str;

		if (rm) refMap.add(utf);
	}
	static void verifyUtfLength(String str) {
		if (str.length() > 0x10000/3) {
			if (str.length() >= 0x10000 || ByteList.countJavaUTF(str) >= 0x10000)
				throw new IllegalArgumentException("UTF8字符串太长，限制是65535字节，"+str.length()+"！");
		}
	}

	//region add-on-demand
	public CstUTF getUtf(CharSequence str) {
		initRefMap();

		CstUTF utf;
		var find = AsmCache.getInstance().constantMatcher;
		var found = refMap.find(find.set(str));
		if (found == find) {
			add(utf = new CstUTF(str.toString()));
		} else if (!str.equals((utf = (CstUTF) found).str())) {
			throw new IllegalStateException("UTF类型的值被外部修改，期待 '"+str+"' 实际 '"+utf.str()+'\'');
		}

		return utf;
	}
	public int getUtfId(CharSequence msg) {return getUtf(msg).index;}

	public CstNameAndType getDesc(String name, String type) {
		var uName = getUtf(name);
		var uType = getUtf(type);

		var find = AsmCache.getInstance().constantMatcher;
		var found = refMap.find(find.set(uName, uType));
		if (found == find) add(found = new CstNameAndType(uName, uType));
		return (CstNameAndType) found;
	}
	public int getDescId(String name, String desc) {return getDesc(name, desc).index;}

	public CstClass getClazz(String name) {
		var utf = getUtf(name);

		var find = AsmCache.getInstance().constantMatcher;
		var found = refMap.find(find.set(CLASS, utf));
		if (found == find) add(found = new CstClass(utf));
		return (CstClass) found;
	}
	public int getClassId(String owner) {return getClazz(owner).index;}

	public CstRef getRefByType(String owner, String name, String desc, @MagicConstant(intValues = {METHOD, FIELD, INTERFACE}) byte type) {
		var clazz = getClazz(owner);
		var nat = getDesc(name, desc);

		var find = AsmCache.getInstance().constantMatcher;
		var found = refMap.find(find.set(type, clazz, nat));
		if (found == find) {
			found = switch (type) {
				case FIELD -> new CstRef.Field(clazz, nat);
				case METHOD -> new CstRef.Method(clazz, nat);
				//case INTERFACE
				default -> new CstRef.Interface(clazz, nat);
			};
			add(found);
		}

		return (CstRef) found;
	}
	public int getMethodRefId(String owner, String name, String desc) {return getRefByType(owner, name, desc, METHOD).index;}
	public int getFieldRefId(String owner, String name, String desc) {return getRefByType(owner, name, desc, FIELD).index;}
	public int getItfRefId(String owner, String name, String desc) {return getRefByType(owner, name, desc, INTERFACE).index;}

	public CstMethodHandle getMethodHandle(@MagicConstant(valuesFromClass = BootstrapMethods.Kind.class) byte kind, CstRef ref) {
		initRefMap();

		var find = new CstMethodHandle(kind, intern(ref));
		var found = (CstMethodHandle) refMap.find(find);
		if (found == find) add(find);
		return found;
	}
	public int getMethodHandleId(@MagicConstant(valuesFromClass = BootstrapMethods.Kind.class) byte kind, CstRef ref) {return getMethodHandle(kind, ref).index;}

	private CstDynamic getDynamic(boolean isMethod, int table, String name, String desc) {
		var nat = getDesc(name, desc);

		var find = new CstDynamic(isMethod, table, nat);
		var found = (CstDynamic) refMap.find(find);
		if (found == find) add(find);
		return found;
	}
	public int getInvokeDynId(int table, String name, String desc) {return getDynamic(true, table, name, desc).index;}
	public CstDynamic getLoadDyn(int table, String name, String desc) {return getDynamic(false, table, name, desc);}

	public int getPackageId(String owner) {
		var utf = getUtf(owner);

		var find = AsmCache.getInstance().constantMatcher;
		var found = refMap.find(find.set(PACKAGE, utf));
		if (found == find) add(found = new CstRefUTF.Package(utf));
		return found.index;
	}
	public int getModuleId(String owner) {
		var utf = getUtf(owner);

		var find = AsmCache.getInstance().constantMatcher;
		var found = refMap.find(find.set(MODULE, utf));
		if (found == find) add(found = new CstRefUTF.Module(utf));
		return found.index;
	}

	public int getIntId(int i) {
		initRefMap();

		var find = AsmCache.getInstance().constantMatcher;
		var found = refMap.find(find.set(i));
		if (found == find) add(found = new CstInt(i));
		return found.index;
	}
	public int getLongId(long i) {
		initRefMap();

		var find = AsmCache.getInstance().constantMatcher;
		var found = refMap.find(find.set(i));
		if (found == find) add(found = new CstLong(i));
		return found.index;
	}
	public int getFloatId(float i) {
		initRefMap();

		var find = AsmCache.getInstance().constantMatcher;
		var found = refMap.find(find.set(i));
		if (found == find) add(found = new CstFloat(i));
		return found.index;
	}
	public int getDoubleId(double i) {
		initRefMap();

		var find = AsmCache.getInstance().constantMatcher;
		var found = refMap.find(find.set(i));
		if (found == find) add(found = new CstDouble(i));
		return found.index;
	}
	//endregion

	public int internIndex(Constant c) {return intern(c).index;}
	@SuppressWarnings({"unchecked", "fallthrough"})
	public <T extends Constant> T intern(T c) {
		switch (c.type()) {
			case DYNAMIC, INVOKE_DYNAMIC -> {
				CstDynamic dyn = (CstDynamic) c;
				dyn.setDesc(intern(dyn.desc()));
			}
			case CLASS, STRING, METHOD_TYPE, MODULE, PACKAGE -> {
				CstRefUTF ref = (CstRefUTF) c;
				ref.setValue(intern(ref.value()));
			}
			case METHOD_HANDLE -> {
				CstMethodHandle ref = ((CstMethodHandle) c);
				ref.setTarget(intern(ref.getTarget()));
			}
			case METHOD, INTERFACE, FIELD -> {
				CstRef ref = (CstRef) c;
				ref.clazz(intern(ref.clazz()));
				ref.nameAndType(intern(ref.nameAndType()));
			}
			case NAME_AND_TYPE -> {
				CstNameAndType nat = (CstNameAndType) c;
				nat.name(intern(nat.name()));
				nat.rawDesc(intern(nat.rawDesc()));
			}
			// No container type
			case UTF, INT, DOUBLE, FLOAT, LONG -> {}
			default -> throw new IllegalArgumentException("不支持的常量类型"+c.type()+" "+c.getClass().getName());
		}

		int id = c.index-1;
		if (id >= 0 && id < constants.size() && constants.getInternalArray()[id] == c) {
			return c;
		}

		initRefMap();
		T t = (T) refMap.find(c);
		if (t != c) return t;

		add(c);
		return c;
	}

	public int byteLength() {
		if (length == 0) throw new IllegalStateException("This pool is not ready to write");
		return length;
	}

	/**
	 *
	 * @param discard 释放资源
	 */
	public void write(DynByteBuf w, boolean discard) {
		w.putShort(constants.size()+1);
		List<Constant> csts = constants;
		for (int i = 0; i < csts.size(); i++)
			csts.get(i).write(w);

		if (useSharedArray) {
			useSharedArray = false;
			AsmCache.getInstance().freeHugeArray(constants, discard);
		}
	}

	@Override
	public String toString() {
		Object[] array = new Object[constants.size()*4];
		int i = 0;
		for (int j = 0; j < constants.size(); j++) {
			Constant c = constants.get(j);
			String s1 = Integer.toString(c.index);
			int k = s1.length()+2;
			array[i++] = s1;
			s1 = Constant.toString(c.type());
			if (s1 == null) s1 = "TOP";
			k += s1.length();
			array[i++] = s1;
			array[i++] = s1.equals("TOP")?"/":c.toString().substring(c.type() < 3 || c.type() > 8 ? k : 0);
			array[i++] = IntMap.UNDEFINED;
		}
		return TextUtil.prettyTable(new StringBuilder("constants["+constants.size()+"]=["), "    ", array, " ", " ").append("]").toString();
	}

	public void clear() {
		constants.clear();
		if (refMap != null) refMap.clear();
		length = 0;
		if (listener != null) listener.accept(null);
	}

	private Consumer<Constant> listener;
	public void setAddListener(Consumer<Constant> x) { listener = x; }

	public void checkCollision(DynByteBuf w) {
		for (int i = 0; i < constants.size(); i++) {
			Constant c = constants.get(i);
			if (c instanceof CstUTF u) {
				if (u.data instanceof DynByteBuf w2) {
					if (w2.array() == w.array()) {
						if (w.array() == null) {
							if (w2.address() > w.address() && w2.address() < w.address()+w.capacity())
								throw new AssertionError("请勿将未解析字符串常量的ConstantData写入和来源相同的DynByteBuf之中");
						} else {
							throw new AssertionError("请勿将未解析字符串常量的ConstantData写入和来源相同的DynByteBuf之中");
						}
					}
				}
			}
		}
	}

	/**
	 * 新的开放寻址哈希表实现，它比我对内存占用优化过的HashSet还少20%的内存，而且性能更好（我没测和大小分布的关系，可能大常量池表现差也说不定），这大概是因为缓存局部性
	 */
	private final class RefMap {
		private char[] table;
		private int mask;
		private boolean empty = true;

		RefMap(int capacity) {ensureCapacity(capacity);}

		public boolean isEmpty() {return empty;}

		public Constant find(Constant key) {return findOrAdd(key, false);}
		public void add(Constant key) {intern(key);}
		public Constant intern(Constant key) {return findOrAdd(key, true);}

		private Constant findOrAdd(Constant key, boolean add) {
			int i = hash(key.hashCode()) & mask;
			char idx;
			while ((idx = table[i]) != 0) {
				Constant c = constants.get(idx - 1);
				if (key.equals(c)) return c;
				i = (i + 1) & mask;
			}

			if (add) {
				table[i] = key.index;
				empty = false;
			}

			return key;
		}

		public boolean remove(Constant key) {
			int i = hash(key.hashCode()) & mask;
			char idx;
			while ((idx = table[i]) != 0) {
				if (constants.get(idx - 1) == key) {
					backshiftKey(i);
					return true;
				}
				i = (i + 1) & mask;
			}
			return false;
		}

		private void backshiftKey(int cur) {
			var tab = table;

			while(true) {
				int prev = cur;
				cur = cur + 1 & mask;

				int idx;
				while(true) {
					if ((idx = tab[cur]) == 0) {
						tab[prev] = 0;
						return;
					}

					int curSlot = hash(constants.get(idx - 1).hashCode()) & mask;
					if (cur <= prev) {
						if (cur < curSlot && curSlot <= prev) break;
					} else {
						if (cur < curSlot || curSlot <= prev) break;
					}
					cur = cur + 1 & mask;
				}

				tab[prev] = (char) idx;
			}
		}

		public void clear() {
			if (empty) return;
			empty = true;
			Arrays.fill(table, (char) 0);
		}

		public void ensureCapacity(int size) {
			// 0.75 Load Factor
			int newCap = MathUtils.nextPowerOfTwo((size * 4) / 3);
			if (newCap > 131072) newCap = 131072;
			if (table != null && table.length >= newCap) return;

			table = new char[newCap];
			mask = newCap - 1;
			empty = constants.isEmpty();

			for (int index = 0; index < constants.size(); ) {
				var c = constants.get(index++);
				if (c == CstTop.TOP) continue;

				int i = hash(c.hashCode()) & mask;
				while (table[i] != 0) {
					i = (i + 1) & mask;
				}
				table[i] = (char) index;
			}
		}

		private static int hash(int h) {return h ^ (h >>> 16);}
	}
}