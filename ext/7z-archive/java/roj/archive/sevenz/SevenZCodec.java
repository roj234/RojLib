package roj.archive.sevenz;

import roj.archive.xz.MemoryLimitException;
import roj.asmx.AnnotationRepoManager;
import roj.collect.HashMap;
import roj.text.TextUtil;
import roj.util.ByteList;
import roj.util.DynByteBuf;
import roj.util.Helpers;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * @author Roj234
 * @since 2023/3/14 8:04
 */
public abstract class SevenZCodec {
	private static final HashMap<ByteList, Factory> REGISTRY = new HashMap<>();

	/**
	 * 注册一个无配置的单例Filter
	 */
	public static void register(SevenZCodec codec) { register(codec.id(), options -> codec); }
	/**
	 * 注册一个需要配置的Filter
	 */
	public static synchronized void register(byte[] id, Factory factory) { REGISTRY.put(ByteList.wrap(id), factory); }

	public abstract byte[] id();

	public OutputStream encode(OutputStream out) throws IOException { throw new UnsupportedOperationException(getClass().getSimpleName() + " 不支持压缩"); }
	public abstract InputStream decode(InputStream in, byte[] password, long uncompressedSize, AtomicInteger memoryLimit) throws IOException;

	protected static void checkMemoryUsage(AtomicInteger memoryLimit, int memoryUsage) throws MemoryLimitException {
		if (memoryLimit.addAndGet(-memoryUsage) < 0) throw new MemoryLimitException(memoryUsage, memoryLimit.get()+memoryUsage);
	}

	@Override
	public String toString() { return getClass().getSimpleName(); }

	/**
	 * 写入配置字节
	 * @apiNote 若没有额外状态（如加密key），请覆盖equals和hashCode以在读取时去重
	 */
	public void writeOptions(DynByteBuf props) {}

	public interface Factory {
		SevenZCodec newInstance(DynByteBuf props) throws IOException;
	}

	public static Factory create(DynByteBuf id) {
		if (REGISTRY.isEmpty()) {
			synchronized (REGISTRY) {
				if (REGISTRY.isEmpty()) {
					register(Copy.INSTANCE);
					register(LZMA.ID, LZMA::new);
					register(LZMA2.ID, LZMA2::new);

					scanExtensions();
				}
			}
		}

		var coder = REGISTRY.get(id);
		if (coder == null) {
			byte[] idBytes = id.toByteArray();
			return props -> new UnknownCodec(idBytes, props);
		}
		return coder;
	}

	private static void scanExtensions() {
		for (var element : AnnotationRepoManager.getAnnotations(
				"roj/archive/sevenz/SevenZCodecExtension",
				SevenZCodec.class.getClassLoader()
		)) {
			var className = element.owner().replace('/', '.');
			var annotation = element.annotations().get("roj/archive/sevenz/SevenZCodecExtension");

			String[] hexIds = annotation.getList("value").toStringArray();
			for (String hexId : hexIds) {
				var id = ByteList.wrap(TextUtil.hex2bytes(hexId));
				// 希望不要哪里死锁了.jpg
				REGISTRY.put(id, (props) -> {
					try {
						Class.forName(className, true, SevenZCodec.class.getClassLoader());
						return REGISTRY.get(id).newInstance(props);
					} catch (ClassNotFoundException e) {
						Helpers.athrow(e);
						return null;
					}
				});
			}
		}
	}
}