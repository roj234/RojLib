package roj.asmx;

import roj.archive.zip.ZipEntry;
import roj.archive.zip.ZipFile;
import roj.archive.zip.ZipPacker;
import roj.asm.AsmCache;
import roj.asm.ClassNode;
import roj.asm.cp.Constant;
import roj.asm.cp.ConstantPool;
import roj.asm.cp.CstClass;
import roj.asm.cp.CstRef;
import roj.io.IOUtil;
import roj.util.ByteList;
import roj.util.Helpers;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

public final class Context implements ClassResource, Consumer<Constant>, Supplier<ByteList> {
	private static final int ID_METHOD = 0, ID_FIELD = 1, ID_CLASS = 2;

	private String name;
	private Object in;
	private ClassNode data;
	private boolean isCompressed;
	private int initialCpOffset;

	public Context(ClassNode o) {
		data = o;
		getFileName();
	}
	public Context(String name, Object o) {
		setName(name);
		if (o instanceof ClassNode) this.data = (ClassNode) o;
		else this.in = Objects.requireNonNull(o, "in");
	}

	private static ByteList read0(Object o) throws IOException {
		if (o instanceof InputStream in) {
			try {
				return ByteList.wrap(IOUtil.read(in));
			} finally {
				IOUtil.closeSilently(in);
			}
		} else if (o instanceof ByteList n) return n;
		else if (o instanceof byte[] n) return ByteList.wrap(n);
		else if (o instanceof File) {
			try {
				return ByteList.wrap(IOUtil.read((File) o));
			} catch (IOException e) {
				throw new RuntimeException(e);
			}
		}
		throw new IllegalArgumentException("无法读取"+o);
	}

	public boolean hasData() {return data != null;}
	public ClassNode getData() {
		isCompressed = false;
		if (data == null) {
			try {
				ByteList r = read0(in);
				in = null;

				if (cp == null) {
					data = ClassNode.parseSkeleton(r);
				} else {
					r.rIndex += 4;
					int version = r.readInt();
					r.skipBytes(initialCpOffset);
					data = ClassNode.parseSkeletonWith(r, version, cp);
				}
				cp = data.cp;
			} catch (Throwable e) {
				throw new IllegalArgumentException(name+" 解析失败", e);
			}

			getFileName();
		}
		return data;
	}

	//region ConstantRef
	private ConstantPool cp;
	private ArrayList<Constant>[] cstCache;

	public ConstantPool getConstantPool() {
		isCompressed = false;
		if (cp == null) {
			try {
				ByteList r = read0(in);
				in = r;

				int i = r.rIndex;

				if (r.readInt() != 0xcafebabe) throw new IllegalArgumentException("Illegal header");
				r.rIndex += 4;

				cp = new ConstantPool();
				cp.read(r, ConstantPool.BYTE_STRING);
				initialCpOffset = 2 + cp.byteLength();

				r.rIndex = i;
			} catch (Throwable e) {
				throw new IllegalArgumentException(name+" 解析失败", e);
			}

			getFileName();
		}
		return cp;
	}

	public List<CstRef> getMethodConstants() { cstInit(); return Helpers.cast(cstCache[ID_METHOD]); }
	public List<CstRef> getFieldConstants() { cstInit(); return Helpers.cast(cstCache[ID_FIELD]); }
	public List<CstClass> getClassConstants() { cstInit(); return Helpers.cast(cstCache[ID_CLASS]); }

	private void cstInit() {
		if (cstCache == null) {
			cstCache = Helpers.cast(new ArrayList<?>[] {
				new ArrayList<>(),
				new ArrayList<>(),
				new ArrayList<>()
			});
		}

		if (cstCache[0].isEmpty()) {
			var cp = getConstantPool();
			cp.setAddListener(this);
			List<Constant> csts = cp.constants();
			for (int i = 0; i < csts.size(); i++) accept(csts.get(i));
		}
	}

	@Override
	public void accept(Constant c) {
		if (c == null) {
			for (List<?> list : cstCache) list.clear();
			cstInit();
			return;
		}

		switch (c.type()) {
			case Constant.INTERFACE: case Constant.METHOD: cstCache[ID_METHOD].add(c); break;
			case Constant.CLASS: cstCache[ID_CLASS].add(c); break;
			case Constant.FIELD: cstCache[ID_FIELD].add(c); break;
		}
	}
	// endregion

	public final ByteList get() {return getClassBytes();}
	/**
	 * This buffer maybe (ThreadLocal) shared, use toByteArray() to distinguish it
	 */
	public ByteList getClassBytes() {
		try {
			if (data != null) {
				getFileName();
				return AsmCache.toByteArrayShared(data);
			} else {
				if (cp != null) {
					var r = (ByteList) in;
					int i = r.rIndex;
					r.rIndex += 4;
					int version = r.readInt();
					r.skipBytes(initialCpOffset);

					var buf = IOUtil.getSharedByteBuf().putInt(0xCAFEBABE).putInt(version);
					cp.write(buf, false);
					buf.put(r);
					r.rIndex = i;
					return (ByteList) buf;
				}

				var buf = read0(in);
				in = buf;
				return buf;
			}
		} catch (Throwable e) {
			throw new IllegalArgumentException(name+" 序列化失败", e);
		}
	}

	public void set(String newName, ByteList bytes) {
		this.in = bytes;
		this.data = null;
		this.cp = null;
		if (newName != null) setName(newName);
	}

	public ByteList getCompressedShared() {
		compress();
		return getClassBytes();
	}

	public void compress() {
		if (isCompressed) return;
		TransformUtil.compress(getData());
		isCompressed = true;
	}

	@Override
	public String toString() {return "{"+name+"}";}

	private void setName(String name) {
		if (!name.endsWith(".class")) name = name.replace('.', '/').concat(".class");
		this.name = name;
	}

	public String getFileName() {
		if (data != null && (name == null || data.name().length()+6 != name.length() || !name.startsWith(data.name()))) setName(data.name());
		return name;
	}
	public String getClassName() {return getFileName().substring(0, name.length()-6);}

	public static List<Context> fromZip(File input, ZipPacker zp) throws IOException {
		List<Context> ctx = new ArrayList<>();

		try (ZipFile zf = new ZipFile(input)) {
			for (ZipEntry entry : zf.entries()) {
				String name = entry.getName();
				if (name.endsWith("/")) continue;

				if (name.endsWith(".class")) {
					ctx.add(new Context(name, zf.get(entry)));
				} else if (zp != null) {
					zp.copy(zf, entry);
				}
			}
		}
		return ctx;
	}
}