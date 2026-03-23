package roj.archive.sevenz;

import org.jetbrains.annotations.NotNull;
import roj.collect.HashMap;
import roj.collect.IntMap;
import roj.io.Finishable;
import roj.io.IOUtil;
import roj.io.XDataOutput;
import roj.text.CharList;
import roj.text.TextUtil;
import roj.util.ArrayUtil;
import roj.util.ByteList;
import roj.util.Helpers;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 流处理器图的节点.
 * 注：Javadoc由AI给我优化了，过了几年我忘得差不多了，AI更加不懂了，所以要是你也看不懂，好吧，没办法
 * @author Roj234
 * @since 2023/5/30 17:50
 */
final class CodecNode extends SevenZCodec {
	private final SevenZCodec codec;
	// 大概类似邻接表
	Object[] inputLinks;
	int outCount;
	/**
	 * 输出流索引，用于跟踪编码器的输出位置，同时是该编码器数据块大小在WordBlock的输入输出大小数组中的索引
	 * -1表示该编码器不直接参与最终输出流的写入。
	 */
	private int outputStreamIndex;

	CodecNode(SevenZCodec codec, int outputStreamIndex) {
		this.codec = codec;
		if (codec instanceof SevenZMultiStreamCodec msCodec) {
			this.inputLinks = new Object[msCodec.getInCount()];
			this.outCount = msCodec.getOutCount();
		} else {
			this.inputLinks = new Object[1];
			this.outCount = 1;
		}
		this.outputStreamIndex = outputStreamIndex;
	}

	@Override public byte[] id() {return null;}
	@Override public InputStream decode(InputStream a, byte[] b, long c, AtomicInteger d) {throw new AssertionError("CodecNode不是根节点");}
	@Override public OutputStream encode(OutputStream a) {throw new AssertionError("CodecNode不是根节点");}

	@Override
	public String toString() { return String.valueOf(outputStreamIndex)+'#'+ codec; }

	/**
	 * Checks if this codec or any of its dependencies is of the specified type.
	 *
	 * @param type The codec type to check for
	 * @return true if this codec or any dependency matches the type
	 */
	public <T extends SevenZCodec> T getCodec(Class<T> type) {
		if (type.isInstance(codec)) return type.cast(codec);
		for (Object link : inputLinks) {
			if (link.getClass() == Integer.class) {
				continue;
			} else if (link.getClass() == CodecNode.class) {
				T processor = ((CodecNode) link).getCodec(type);
				if (processor != null) return processor;
			} else {
				IntMap.Entry<CodecNode> entry = Helpers.cast(link);
				T processor = entry.getValue().getCodec(type);
				if (processor != null) return processor;
			}
		}
		return null;
	}

	/**
	 * Creates input streams for decoding, handling complex codec dependencies.
	 *
	 * @param outputSizes Array of output sizes for each stream
	 * @param fileStreams Input streams from files
	 * @param streamCache Cache for already created streams
	 * @param password Password for decryption
	 * @param memoryLimit Memory limit for decompression
	 * @return Array of input streams for this codec
	 * @throws IOException If an I/O error occurs
	 */
	InputStream[] getInputStream(long[] outputSizes, InputStream[] fileStreams,
								 Map<CodecNode, InputStream[]> streamCache,
								 byte[] password, AtomicInteger memoryLimit) throws IOException {
		var assoc = new InputStream[inputLinks.length];
		for (int i = 0; i < inputLinks.length; i++) {
			Object link = inputLinks[i];
			if (link.getClass() == Integer.class) {
				assoc[i] = fileStreams[(int) link];
			} else if (link.getClass() == CodecNode.class) {
				var cached = streamCache.get(link);
				if (cached == null) {
					cached = ((CodecNode) link).getInputStream(outputSizes, fileStreams, streamCache, password, memoryLimit);
					streamCache.put((CodecNode) link, cached);
				}
				assoc[i] = cached[0];
			} else {
				IntMap.Entry<CodecNode> entry = Helpers.cast(link);
				var cached = streamCache.get(entry.getValue());
				if (cached == null) {
					cached = entry.getValue().getInputStream(outputSizes, fileStreams, streamCache, password, memoryLimit);
					streamCache.put(entry.getValue(), cached);
				}
				assoc[i] = cached[entry.getIntKey()];
			}
		}

		return codec instanceof SevenZMultiStreamCodec msCodec
				? msCodec.decodeMulti(assoc, outputSizes, outputStreamIndex, memoryLimit)
				: new InputStream[] {codec.decode(assoc[0], password, outputSizes[outputStreamIndex], memoryLimit)};
	}

	/**
	 * 创建用于编码的组合输出流。
	 * <p>
	 * 该方法会构建一个包含主输出流和额外统计流的组合流，
	 * 通过{@link SizeTracker}自动统计各数据块的大小。
	 *
	 * @param block 要编码的单词块，用于记录压缩前后的大小信息
	 * @param output 主输出流，最终数据将写入此流
	 * @return 组合输出流，负责协调多个子流的写入操作
	 * @throws IOException 当流创建失败时抛出
	 */
	@SuppressWarnings("IOResourceOpenedButNotSafelyClosed")
	OutputStream getOutputStream(WordBlock block, OutputStream output) throws IOException {
		OutputStream[] streams = new OutputStream[block.extraSizes.length+1];
		streams[0] = new SizeTracker(block, output);
		for (int i = 1; i < streams.length; i++)
			streams[i] = new SizeTracker(block, i);

		return new CompositeOutputStream(createOutputStreams(block, streams, new HashMap<>())[0], streams);
	}
	private OutputStream[] createOutputStreams(WordBlock block, OutputStream[] fileStreams,
											   Map<CodecNode, OutputStream[]> streamCache) throws IOException {
		var assoc = new OutputStream[inputLinks.length];
		for (int i = 0; i < inputLinks.length; i++) {
			Object link = inputLinks[i];
			if (link.getClass() == Integer.class) {
				assoc[i] = fileStreams[(int) link];
			} else if (link.getClass() == CodecNode.class) {
				var cached = streamCache.get(link);
				if (cached == null) {
					cached = ((CodecNode) link).createOutputStreams(block, fileStreams, streamCache);
					streamCache.put((CodecNode) link, cached);
				}
				assoc[i] = cached[0];
			} else {
				IntMap.Entry<CodecNode> entry = Helpers.cast(link);
				var cached = streamCache.get(entry.getValue());
				if (cached == null) {
					cached = entry.getValue().createOutputStreams(block, fileStreams, streamCache);
					streamCache.put(entry.getValue(), cached);
				}
				assoc[i] = cached[entry.getIntKey()];
			}
		}

		var resultStreams = codec instanceof SevenZMultiStreamCodec msCodec
				? msCodec.encodeMulti(assoc)
				: new OutputStream[] {codec.encode(assoc[0])};

		// 输入(uncompressed size | partial compressed size)大小计数器
		if (outputStreamIndex >= 0) {
			for (int i = 0; i < resultStreams.length; i++) {
				// noinspection all
				resultStreams[i] = block.new Counter(resultStreams[i], outputStreamIndex+i);
			}
		}
		return resultStreams;
	}

	/**
	 * 统计WordBlock中各个文件区域大小(final compressed size)的辅助输出流。
	 * <p>
	 * 根据构造模式不同，可能直接写入文件或缓存数据：
	 * <ul>
	 *   <li>sizeIndex >= 0时：缓存数据到内存，统计额外流的大小</li>
	 *   <li>sizeIndex = -1时：直接写入文件流，统计主数据大小</li>
	 * </ul>
	 */
	static final class SizeTracker extends OutputStream {
		final WordBlock owner;
		final OutputStream fileStream;
		private final ByteList buffer;
		private final int sizeIndex;

		// memory cache
		SizeTracker(WordBlock owner, int sizeIndex) {
			this.owner = owner;
			this.sizeIndex = sizeIndex-1;
			this.fileStream = null;
			this.buffer = ByteList.allocate(1024);
		}

		// direct to file
		SizeTracker(WordBlock owner, OutputStream fileStream) {
			this.owner = owner;
			this.sizeIndex = -1;
			this.fileStream = fileStream;
			this.buffer = null;
		}

		@Override
		public void write(int b) throws IOException {
			if (fileStream != null) fileStream.write(b);
			else buffer.put(b);

			if (sizeIndex >= 0) owner.extraSizes[sizeIndex]++;
			else owner.size++;
		}

		@Override
		public void write(@NotNull byte[] b, int off, int len) throws IOException {
			ArrayUtil.checkRange(b, off, len);
			if (len == 0) return;

			if (fileStream != null) fileStream.write(b, off, len);
			else buffer.put(b, off, len);

			if (sizeIndex >= 0) owner.extraSizes[sizeIndex] += len;
			else owner.size += len;
		}
	}

	/**
	 * 组合输出流，协调多个{@link SizeTracker}的写入操作。
	 * <p>
	 * 关闭时会自动将缓存数据刷入主文件流，并确保所有流正确{@link Finishable 结束}。
	 */
	static final class CompositeOutputStream extends OutputStream implements Finishable {
		private final OutputStream primaryOutput;
		private final OutputStream[] fileStreams;

		public CompositeOutputStream(OutputStream primaryOutput, OutputStream[] fileStreams) {
			this.primaryOutput = primaryOutput;
			this.fileStreams = fileStreams;
		}

		public void write(int b) throws IOException { primaryOutput.write(b); }
		public void write(@NotNull byte[] b, int off, int len) throws IOException { primaryOutput.write(b, off, len); }
		public void flush() throws IOException { primaryOutput.flush(); }
		public void finish() throws IOException { if (primaryOutput instanceof Finishable f) f.finish(); }

		public void close() throws IOException {
			Exception e1 = null;
			try {
				primaryOutput.close();
			} catch (Exception e) {
				e1 = e;
			}

			OutputStream fileStream = ((SizeTracker) fileStreams[0]).fileStream;
			// 真正写入文件
			for (int i = 1; i < fileStreams.length; i++) {
				ByteList buffer = ((SizeTracker) fileStreams[i]).buffer;
				buffer.writeToStream(fileStream);
				buffer.release();
			}

			if (e1 != null) Helpers.athrow(e1);
		}
	}

	/**
	 * 将当前编码器的指定输入连接到另一个编码器的输出。
	 *
	 * @param streamId 当前编码器的输入流索引（0 ≤ index < uses.length）
	 * @param provider 提供输出流的源编码器（非null）
	 * @param providerStreamId 源编码器的输出流索引（0 ≤ index < provider.provides）
	 */
	void pipe(int streamId, CodecNode provider, int providerStreamId) {
		inputLinks[streamId] = providerStreamId == 0 ? provider : new IntMap.Entry<>(providerStreamId, provider);
	}

	/**
	 * 将当前编码器的指定输入连接到文件块的原始流。
	 *
	 * @param blockId 文件块索引（对应fileStreams数组的索引）
	 * @param streamId 当前编码器的输入流索引（0 ≤ index < uses.length）
	 */
	void setFileInput(int blockId, int streamId) {
		inputLinks[streamId] = blockId;
	}

	/**
	 * Writes codec information to the output buffer.
	 *
	 * @param block The word block being processed
	 * @param buffer The output buffer
	 */
	void writeCodecs(WordBlock block, XDataOutput buffer) throws IOException {
		ByteList options = IOUtil.getSharedByteBuf();

		var sortedCodecs = (CodecNode[]) block.codecs;
		buffer.putVUInt(sortedCodecs.length);

		// Write codecs
		for (CodecNode codec : sortedCodecs) {
			options.clear();
			codec.codec.writeOptions(options);

			byte[] id = codec.codec.id();

			int flags = id.length;
			if (codec.codec instanceof SevenZMultiStreamCodec) flags |= 0x10;
			if (options.wIndex() > 0) flags |= 0x20;
			buffer.put(flags).put(id);

			if (codec.codec instanceof SevenZMultiStreamCodec) {
				buffer.putVUInt(codec.inputLinks.length).putVUInt(codec.outCount);
			}

			if (options.wIndex() > 0) buffer.putVUInt(options.wIndex()).put(options);
		}

		// Write pipes
		int used = 0;
		int[] fileStreamIds = new int[block.extraSizes.length+1];

		for (var codec : sortedCodecs) {
			Object[] inputLinks = codec.inputLinks;
			for (int j = 0; j < inputLinks.length; j++) {
				Object link = inputLinks[j];
				if (link.getClass() == CodecNode.class) {
					buffer.putVUInt(j+used)
						  .putVUInt(findOutputIndex(sortedCodecs, (CodecNode) link));
				} else if (link.getClass() != Integer.class) {
					IntMap.Entry<CodecNode> entry = Helpers.cast(link);
					buffer.putVUInt(j+used)
						  .putVUInt(findOutputIndex(sortedCodecs, entry.getValue()) + entry.getIntKey());
				} else {
					fileStreamIds[(int)link] = j+used;
				}
			}
			used += inputLinks.length;
		}

		// Write fileStream mapping
		for (int id : fileStreamIds) buffer.putVUInt(id);

		if (block.outSizes.length == 0) return;

		// 最外面OutputStream写的是uSize,但是实际的streamId是0,需要交换一下
		long uncompressedSize = block.uSize;
		long compressedSize = block.outSizes[block.outSizes.length-1];
		System.arraycopy(block.outSizes, 0, block.outSizes, 1, block.outSizes.length-1);
		block.outSizes[0] = uncompressedSize;
		block.uSize = compressedSize;
	}
	private static int findOutputIndex(CodecNode[] codecs, CodecNode target) {
		int index = 0;
		for (CodecNode codec : codecs) {
			if (codec == target) break;
			index += codec.outCount;
		}
		return index;
	}

	/**
	 * 重置输出流索引并返回原始值，用于最终大小计算。
	 * <p>
	 * 该方法会将当前编码器的outputStreamIndex标记为无效（-1），
	 * 并调整后续编码器的索引以保证连续性。
	 *
	 * @param block 包含编码器排序信息的单词块
	 * @return 原始输出流索引，对应主未压缩数据大小的存储位置
	 */
	int getUncompressedSizeIndex(WordBlock block) {
		assert outputStreamIndex >= 0;
		int originalIndex = outputStreamIndex;
		outputStreamIndex = -1;

		var sortedCodecs = (CodecNode[]) block.codecs;
		for (CodecNode codec : sortedCodecs) {
			if (codec.outputStreamIndex > originalIndex) codec.outputStreamIndex--;
		}

		return originalIndex;
	}

	/**
	 * 将过滤器节点树以Mermaid格式打印
	 * @return Mermaid格式的字符串
	 */
	public static String printFilterMermaid(WordBlock block) {
		CharList mermaid = new CharList();
		mermaid.append("```mermaid\ngraph TD\n");

		var sortedCodecs = (CodecNode[]) block.codecs;

		for (CodecNode codec : sortedCodecs) {
			for (Object pipe : codec.inputLinks) {
				Object parentNode;
				int offset = 0;

				if (pipe.getClass() == CodecNode.class) {
					parentNode = pipe;
				} else if (pipe.getClass() != Integer.class) {
					IntMap.Entry<CodecNode> entry = Helpers.cast(pipe);
					parentNode = entry.getValue();
					offset = entry.getIntKey();
				} else {
					parentNode = pipe;
					offset = -1;
				}

				String currentNodeId = getNodeId(codec);
				String currentNodeLabel = getNodeLabel(codec);

				long size;
				if (offset >= 0) {
					int i = ((CodecNode) pipe).outputStreamIndex;
					size = i == block.outSizes.length ? block.uSize : block.outSizes[i];
					mermaid.append("    ").append(getNodeId(parentNode))
							.append("[\"").append(getNodeLabel(parentNode)).append("\"]")
							.append(" --> ");
				} else {
					int i = (int) parentNode;
					size = i == 0 ? block.size : block.extraSizes[i - 1];
					mermaid.append("    src").append(i)
							.append("(\"Stream #").append(i).append("\")")
							.append(" --> ");
				}

				mermaid.append("|").append(TextUtil.scaledNumber1024(size)).append("|")
						.append(currentNodeId)
						.append("[\"").append(currentNodeLabel).append("\"]")
						.append("\n");
			}
		}

		var codec = block.multiStreamCodec;
		mermaid.append("    ").append(getNodeId(codec))
				.append("[\"").append(getNodeLabel(codec)).append("\"]")
				.append(" --> ")
				.append("|").append(TextUtil.scaledNumber1024(block.uSize)).append("|")
				.append("_output")
				.append("([\"Output\"])")
				.append("\n");

		return mermaid.append("```").toStringAndFree();
	}

	private static String getNodeId(Object node) {return Integer.toString(System.identityHashCode(node), 36).replace('-', '_');}

	/**
	 * 转义Mermaid文本中的特殊字符
	 */
	private static String getNodeLabel(Object node) {
		String text;
		if (node instanceof IntMap.Entry<?> entry) {
			text = entry.getValue().toString();
		} else {
			text = ((CodecNode) node).codec.toString();
		}

		return text.replace("\"", "&quot;")
				.replace("<", "&lt;")
				.replace(">", "&gt;");
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (o == null || getClass() != o.getClass()) return false;

		CodecNode codecNode = (CodecNode) o;
		return outCount == codecNode.outCount && outputStreamIndex == codecNode.outputStreamIndex && codec.equals(codecNode.codec) && Arrays.equals(inputLinks, codecNode.inputLinks);
	}

	@Override
	public int hashCode() {
		int result = codec.hashCode();
		result = 31 * result + Arrays.hashCode(inputLinks);
		result = 31 * result + outCount;
		result = 31 * result + outputStreamIndex;
		return result;
	}
}