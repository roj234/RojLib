package roj.archive.sevenz;

import org.jetbrains.annotations.NotNull;
import roj.archive.ArchiveFile;
import roj.archive.ArchiveUtils;
import roj.collect.*;
import roj.concurrent.TaskGroup;
import roj.config.node.LongValue;
import roj.crypt.CRC32;
import roj.io.*;
import roj.io.source.Source;
import roj.io.source.SourceInputStream;
import roj.optimizer.FastVarHandle;
import roj.text.CharList;
import roj.util.ArrayCache;
import roj.util.ByteList;
import roj.util.DynByteBuf;
import roj.util.FastFailException;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;

import static roj.archive.sevenz.BlockId.*;
import static roj.reflect.Unsafe.U;

/**
 * A reader for 7z archives, 我有意保留了一部分C++的味道，这样你才知道自己用的是Java (玩梗而已).
 * <p>
 * Supports loading entries, sequential/concurrent decompression, appending files, and resource management
 * with optional password protection and recovery mode. Memory usage is controlled via {@link #setMemoryLimitKb(int)}.
 * Use {@link #forkReader()} for creating shared readers suitable for concurrent access to different blocks.
 * For parallel decompression across multiple threads, use {@link #parallelDecompress(TaskGroup, BiConsumer)}.
 *
 * @author Roj233
 * @since 2022/3/14 7:09
 * @see SevenZReader
 * @see ArchiveFile
 */
@FastVarHandle
public final class SevenZFile extends SevenZReader implements ArchiveFile<SevenZEntry> {
	private WordBlock[] blocks;
	private SevenZEntry[] entries;
	private HashMap<String, SevenZEntry> byName;
	private CharMap<byte[]> attributes;

	/**
	 * 恢复模式：禁用哈希校验，并在头部错误时尝试寻找尾部
	 */
	public static final byte FLAG_RECOVERY = 1,
	/**
	 * 省内存模式：不加载修改时间等per-Entry元数据
	 */
	FLAG_SKIP_METADATA = 2,
	/**
	 * 惊喜模式（无聊写的）：列出
	 */
	FLAG_DUMP_HIDDEN = 4,
	/**
	 * 加载自定义非标准属性，这些属性是任意的，并且不被7-zip支持
	 */
	FLAG_LOAD_ATTRIBUTES = 8;

	private final byte[] password;
	private int maxFileCount = 0xFFFFF, memoryLimitKb = 131072;

	public SevenZFile(String path) throws IOException { this(new File(path), null); }
	public SevenZFile(File file) throws IOException { this(file, null); }
	public SevenZFile(File file, String password) throws IOException {
		r = ArchiveUtils.tryOpenSplitArchive(file, true);
		this.password = password == null ? null : password.getBytes(StandardCharsets.UTF_16LE);
		reload();
	}

	public SevenZFile(Source src) throws IOException { this(src, null); }
	public SevenZFile(Source src, String password) throws IOException {
		r = src;
		r.seek(0);
		this.password = password == null ? null : password.getBytes(StandardCharsets.UTF_16LE);
		reload();
	}
	/**
	 * Creates an uninitialized 7z archive for advanced use (e.g., error recovery).
	 *
	 * @param src the source stream
	 * @param recovery recovery flags (bitwise OR of FLAG_* constants)
	 * @param maxFileCount maximum number of files to load
	 * @param pass the password bytes (null if unencrypted)
	 * @see #reload()
	 */
	public SevenZFile(Source src, int recovery, int maxFileCount, byte[] pass) {
		r = src;
		this.flag = (byte) recovery;
		this.password = pass;
		this.maxFileCount = maxFileCount;
		this.entries = Loader.NO_FILES;
	}

	/**
	 * Sets the memory limit in KB for decompression operations. Exceeding this may throw an exception.
	 *
	 * @param v the new memory limit in KB
	 */
	public void setMemoryLimitKb(int v) { memoryLimitKb = v; }

	public Source getSource() { return r; }
	public final boolean isEmpty() { return entries.length == 0; }
	public WordBlock[] getWordBlocks() { return blocks; }

	public CharMap<byte[]> getAttributes() {return Objects.requireNonNull(attributes);}

	/**
	 * Prepares a writer for appending new files to this archive.
	 * The archive must be writable and not skipping metadata. Positions the writer after existing content.
	 * Entries are copied to the writer for continuation.
	 *
	 * @return a writer for appending files
	 * @throws IOException if the source is not writable or metadata is skipped
	 * @throws IllegalStateException if prerequisites not met
	 */
	public SevenZPacker append() throws IOException {
		if (!r.isWritable()) throw new IllegalStateException("Source ["+r+"] not writable");
		if ((flag&FLAG_SKIP_METADATA) != 0) throw new IllegalStateException("Metadata skip");

		SevenZPacker fw = new SevenZPacker(r);

		for (SevenZEntry ent : entries) {
			if (ent.uSize > 0) fw.files.add(ent);
			else fw.emptyFiles.add(ent);
		}

		if (blocks != null) {
			fw.blocks._setArray(blocks);
			fw.blocks._setSize(blocks.length);

			WordBlock b = blocks[blocks.length-1];
			r.seek(b.offset+b.size());
		}

		fw.recountFlags();
		return fw;
	}

	@Override
	public final void reload() throws IOException {
		if (byName == null) byName = new HashMap<>();
		else byName.clear();
		entries = null;

		var cache = (Source) CACHE.getAndSet(this, r);
		if (cache != r) IOUtil.closeSilently(cache);

		try {
			Loader loader = new Loader(this);
			loader.load();
			blocks = loader.blocks;
			entries = loader.files;
		} catch (Exception e) {
			close();
			throw e;
		}
	}

	public static final long QZ_HEADER = 0x377abcaf271c_00_02L;
	private static final class Loader {
		public static final SevenZEntry[] NO_FILES = new SevenZEntry[0];

		// -- IN --
		private final SevenZFile that;
		private final boolean strictMode;

		Loader(SevenZFile archive) { this.that = archive; strictMode = !archive.isRecovering(); }

		// -- OUT --
		WordBlock[] blocks;
		SevenZEntry[] files;

		//共享以减少无用对象
		private final HashSet<SevenZCodec[]> codecInterner = new HashSet<>(Hasher.array(SevenZCodec[].class));
		private final SevenZCodec[] temp = new SevenZCodec[32];
		private final Int2IntBiMap pipe1 = new Int2IntBiMap();
		private final int[] sorted1 = new int[32];

		// -- INTERNAL --
		private ByteList buf;
		private InputStream rawIn;
		private XDataInputStream in;

		private WordBlock[] externalBlocks;

		final void load() throws IOException {
			blocks = null;
			files = null;
			that.r.seek(0);
			rawIn = that.r.asInputStream();
			buf = (ByteList) BufferPool.buffer(false, 256);
			if ((that.flag&FLAG_LOAD_ATTRIBUTES) != 0) {
				that.attributes = new CharMap<>();
			}

			try {
				ByteList buf = read(32);

				if ((buf.readLong() >>> 16) != (QZ_HEADER >>> 16))
					throw new CorruptedInputException("文件头错误"+Long.toHexString(buf.getLong(0)>>>16));
				buf.rIndex = 6;

				int major = buf.readUnsignedByte();
				int minor = buf.readUnsignedByte();
				if (major != 0 || minor > 4)
					throw new UnsupportedOperationException("不支持的版本"+major+"."+minor);

				buf.rIndex = 12;
				long offset = buf.readLongLE();
				long length = buf.readLongLE();
				if ((offset|length) < 0 || offset+length > that.r.length()) {
					throw new CorruptedInputException("目录表偏移错误"+offset+'+'+length+",len="+that.r.length());
				}

				int crc = CRC32.crc32(buf.list, buf.arrayOffset()+12, 20);
				int myCrc = buf.getIntLE(8);
				if (crc != myCrc) {
					// https://www.7-zip.org/recover.html : if crc, offset and length are zero
					if (that.isRecovering()) {
						if ((offset|length|myCrc) == 0) {
							recoverFT();
							return;
						}
					} else {
						throw new CorruptedInputException("文件头校验错误"+Integer.toHexString(crc)+"/"+Integer.toHexString(myCrc));
					}
				}

				if (length == 0) {
					files = NO_FILES;
					return;
				}
				if (length/8 > that.maxFileCount) throw new IOException("文件表过大"+length);

				myCrc = buf.readIntLE();

				// header + offset
				that.r.seek(32+offset);

				InputStream in = rawIn;
				if (in instanceof SourceInputStream sin) {
					sin.doClose = false;
					sin.remain = length;
				} else {
					in = new LimitInputStream(in, length);
				}

				this.in = new XDataInputStream(rawIn = that.isRecovering() ? in : new CRC32InputStream(in, myCrc));
				readHeader();
				if ((that.flag & FLAG_DUMP_HIDDEN) != 0) findHiddenData(32+offset, length);
			} finally {
				IOUtil.closeSilently(buf);
				if (in != null) in.finish();
			}
		}

		private long ecOff, ecLen;
		private void findHiddenData(long ehOff, long ehLen) throws IOException {
			if (ecLen != 0 && in.position() != ecLen) System.out.println("EncodedHeader尾部存在剩余信息");

			List<SweepLine.SimpleRange<String>> items = new ArrayList<>();

			items.add(new SweepLine.SimpleRange<>("SignatureHeader", 0, 32));
			items.add(new SweepLine.SimpleRange<>("Header", ehOff, ehOff+ehLen));
			if (blocks != null) {
				WordBlock lastBlock = blocks[blocks.length - 1];
				items.add(new SweepLine.SimpleRange<>("PackedStreams", blocks[0].offset, lastBlock.offset+lastBlock.size));
			}
			if (externalBlocks != null) {
				WordBlock lastBlock = externalBlocks[externalBlocks.length - 1];
				items.add(new SweepLine.SimpleRange<>("AdditionalPackedStreams", externalBlocks[0].offset, lastBlock.offset+lastBlock.size));
			}
			if (ecOff != 0) {
				items.add(new SweepLine.SimpleRange<>("PackedStreamsForHeaders", ecOff, ecOff+ecLen));
			}

			LongValue hasInfo = new LongValue(0);
			SweepLine.merge(items, (ranges, length) -> {
				long prevEnd = hasInfo.value;
				long start = ranges.get(0).startPos;

				if (prevEnd < start) {
					System.out.println("文件的["+prevEnd+","+start+"]有一些隐藏信息");
				}

				hasInfo.value = start + length;
			});

			if (hasInfo.value != that.r.length()) {
				System.out.println("文件的["+hasInfo+","+that.r.length()+"]有一些隐藏信息");
			}
		}

		private void recoverFT() throws IOException {
			Source r = that.r;
			long pos = r.length()-2;
			long min = Math.min(pos-1048576, 0); // 1MB

			while (pos > min) {
				r.seek(pos);

				int id = r.read();
				if (id == kEncodedHeader || id == kHeader) {
					r.seek(pos);
					try (var in = new XDataInputStream(rawIn)) {
						this.in = in;
						readHeader();
					} catch (Exception ignored) {}
					return;
				}

				pos--;
			}
		}

		// region 实现细节
		private void readHeader() throws IOException {
			int id = in.readUnsignedByte();
			if (id == kEncodedHeader) {
				readEncodedHeader();
				id = in.readUnsignedByte();
			}

			if (id != kHeader) fatalError();
			id = in.readUnsignedByte();

			if (id == kArchiveProperties) {
				readProperties();
				id = in.readUnsignedByte();
			}

			if (id == kAdditionalStreamsInfo) {
				readStreamsInfo(false);
				id = in.readUnsignedByte();

				// 这会在readFilesInfo函数中被引用
				externalBlocks = blocks;
				blocks = null;
			}

			if (id == kMainStreamsInfo) {
				readStreamsInfo(true);
				id = in.readUnsignedByte();
			}

			if (id != kFilesInfo) {
				// 罕见，毕竟空文件只需要32字节，没必要在来一个kHeader+kEnd
				if (id == kEnd && (blocks == null || blocks.length == 0)) {
					return;
				}
				fatalError();
			}

			readFilesInfo();

			id = in.readUnsignedByte();
			end(id);
		}

		private void readEncodedHeader() throws IOException {
			readStreamsInfo(false);

			if (blocks.length != 1) throw new CorruptedInputException("元数据错误");
			if (files != null) error("files != null");

			var b = blocks[0];
			blocks = null;
			ecOff = b.offset;
			ecLen = b.size;

			in.close();

			try {
				// BETTER THAN 7-zip (really, it does not stream)
				// - 不过难以想象几十MB的文件头……
				in = new XDataInputStream(that.getBlockInputStream(b, null, that.r, that, (that.flag&FLAG_RECOVERY) == 0));
			} catch (Exception e) {
				if (b.hasCodec(SevenZAES.class) && that.password != null)
					throw new CorruptedInputException("压缩包打开失败，可能是密码错误", e);
				throw e;
			}
		}

		// 压缩包属性, 似乎没人用, 7z的代码直接忽略了
		private void readProperties() throws IOException {
			int nid = in.readUnsignedByte();
			while (nid != kEnd) {
				long size = readVULong();
				if ((that.flag&FLAG_LOAD_ATTRIBUTES) != 0) {
					if (size > ArrayCache.MAX_ARRAY_SIZE)
						throw new OutOfMemoryError("Could not allocate byte["+size+"]");
					that.attributes.put((char) nid, in.readBytes((int) size));
				} else {
					if ((that.flag&FLAG_DUMP_HIDDEN) != 0)
						System.out.println("扩展属性:"+nid+",长度为"+size);
					in.skipForce(size);
				}
				nid = in.readUnsignedByte();
			}
		}

		// comment at: 2025-11-28 02:14 CST
		// 你知道吗, 7z‘规范’（真的有这玩意，只不过过于简陋我才吐槽）里，这三个属性全都是可选的
		// 我不知道作者是抱着什么样的心态设计的，也可能我的OOP设计模式不同
		// 总而言之，我的实现要求前两个属性必须存在
		private void readStreamsInfo(boolean allowEntries) throws IOException {
			int id = in.readUnsignedByte();

			if (id != kPackInfo) {
				// 三个属性都不存在的特判
				if (id == 0) return;
				fatalError();
			}
			// region 压缩流
			long offset = 32 + readVULong();
			int count = readVUInt(that.maxFileCount);

			id = in.readUnsignedByte();
			// 压缩流偏移
			if (id != kSize) fatalError();

			long[] streamLen = new long[count];
			for (int i = 0; i < count; i++) streamLen[i] = readVULong();

			id = in.readUnsignedByte();
			if (id == kCRC) {
				BitSet hasCrc = readBitsOrTrue(count);
				/* CRC of packed streams is unused now */
				in.skipForce((long) hasCrc.size() << 2);

				id = in.readUnsignedByte();
			}

			end(id);
			id = in.readUnsignedByte();
			// endregion
			if (id != kUnPackInfo) fatalError();
			// region 字块
			id = in.readUnsignedByte();
			if (id != kFolder) fatalError();

			count = readVUInt();

			if (in.readByte() != 0) error("kFolder.external");

			var blocks = this.blocks = new WordBlock[count];
			var tempSorter = new int[count][];
			for (int i = 0; i < count; i++) blocks[i] = readBlock(tempSorter, i);

			// assign offset
			{
				final long length = that.r.length();

				int streamId = 0;
				for (int i = 0; i < blocks.length; i++) {
					var b = blocks[i];

					b.offset = offset;

					long[] offs = b.extraSizes;
					for (int j = -1; j < offs.length; j++) {
						long len = streamLen[streamId++];
						if (j < 0) b.size = len;
						else offs[j] = len;
						offset += len;
					}

					if (offset > length) error("字块["+i+"](子流"+streamId+")越过文件边界("+offset+" > "+length+")");
				}
			}

			id = in.readUnsignedByte();
			if (id != kCodersUnPackSize) fatalError();

			for (int k = 0; k < blocks.length; k++) {
				var b = blocks[k];
				var cc = b.multiStreamCodec;
				int uSizeId = cc != null ? cc.getUncompressedSizeIndex(b) : b.hasCrc >>> 2;

				long[] out = b.outSizes;
				int i = 0;
				for (int j = 0; j <= out.length; j++) {
					long size = readVULong();
					if (j == uSizeId) b.uSize = size;
					else out[i++] = size;
				}

				var sorter = tempSorter[k];
				if (sorter != null) {
					var sortedSize = new long[out.length];
					for (i = 0; i < out.length; i++)
						sortedSize[i] = out[sorter[i]];
					b.outSizes = sortedSize;
				}
			}

			id = in.readUnsignedByte();
			if (id == kCRC) {
				BitSet set = readBitsOrTrue(count);
				for (IntIterator itr = set.iterator(); itr.hasNext(); ) {
					WordBlock b = blocks[itr.nextInt()];
					b.hasCrc |= 1; // uncompressed crc
					b.crc = in.readIntLE();
				}

				id = in.readUnsignedByte();
			}

			end(id);
			id = in.readUnsignedByte();
			// endregion
			// 可选 字块到Entry数据
			if (id == kSubStreamsInfo) {
				if (!allowEntries) throw new CorruptedInputException("unexpected kSubStreamsInfo");
				readSubStreamsInfo();
				id = in.readUnsignedByte();
			} else {
				if (allowEntries) throw new CorruptedInputException("expecting kSubStreamsInfo");
			}

			end(id);
		}
		private WordBlock readBlock(int[][] sorterArr, int thisId) throws IOException {
			WordBlock b = new WordBlock();

			int ioLen = readVUInt(32);
			if (ioLen == 0) error("没有codec");

			SevenZCodec[] codecs = temp;
			for (int i = 0; i < ioLen; i++) {
				int flag = in.readUnsignedByte();

				boolean complex = (flag & 0x10) != 0;
				if (complex) return readComplexBlock(codecs, b, i, ioLen, flag);

				boolean alternate = (flag & 0x80) != 0;
				if (alternate) error("codec["+i+"].alternate");

				var factory = SevenZCodec.create(read(flag & 0xF));

				boolean hasProperties = (flag & 0x20) != 0;
				ByteList properties = hasProperties ? read(readVUInt(0xFFFFF)) : ByteList.EMPTY;

				codecs[i] = factory.newInstance(properties);
				if (properties.isReadable()) error("codec属性有剩余");
			}

			int pipeCount = ioLen-1;
			var pipe = pipe1; pipe.clear();
			// 物理顺序index到排序后顺序val的映射
			var sortedIds = sorted1;

			if (pipeCount > 0) {
				b.outSizes = new long[pipeCount];
				// 2024/7/24 注释 这里可以检测部分管道错误：Int2IntMap的KV都不能重复，所以不能添加任意一侧在map中的管道
				for (int i = pipeCount; i > 0; i--) {
					sortedIds[i] = -1;
					pipe.putInt(readVUInt(pipeCount), readVUInt(pipeCount));
				}
			}

			var sorted = new SevenZCodec[ioLen];
			for (int i = 0; i < ioLen; i++) {
				if (!pipe.containsKey(i)) {
					int sortId = 0, id = i;

					// 2024/7/24 注释 这里检测剩余所有的管道错误
					while (sortId < ioLen) {
						sortedIds[id] = sortId;
						sorted[sortId++] = codecs[id];
						id = pipe.getByValueOrDefault(id, -1);
					}
				} else if (!pipe.containsValue(i)) {
					b.hasCrc |= i<<2;
				}
			}

			// 如果正好和解压路径一样（这也是正常文件的行为）那么可以省下一个数组拷贝
			for (int i = 0; i < ioLen; i++) {
				if (sortedIds[i] != i) {
					sorterArr[thisId] = Arrays.copyOf(sortedIds, ioLen);
					break;
				}
			}

			// 已排序：解压包装顺序
			b.codecs = this.codecInterner.intern(sorted);
			return b;
		}
		private WordBlock readComplexBlock(SevenZCodec[] codecs, WordBlock b, int i, int count, int flag) throws IOException {
			List<IntMap.Entry<CodecNode>>
				inputs = new ArrayList<>(),
				outputs = new ArrayList<>();

			var DAGNodes = new CodecNode[count];
			for (int j = 0; j < i; j++) {
				CodecNode node = new CodecNode(codecs[j], j);
				DAGNodes[j] = node;

				var pointer = new IntMap.Entry<>(0, node);
				inputs.add(pointer);
				outputs.add(pointer);
			}

			for (;;) {
				boolean alternate = (flag & 0x80) != 0;
				if (alternate) error("codec["+i+"].alternate");

				var factory = SevenZCodec.create(read(flag&0xF));

				boolean complex = (flag & 0x10) != 0;

				int inCount, outCount;
				if (complex) {
					inCount = readVUInt(8);
					outCount = readVUInt(8);
				} else {
					inCount = outCount = 1;
				}

				boolean hasProperties = (flag & 0x20) != 0;
				ByteList properties = hasProperties ? read(readVUInt(0xFFFFF)) : ByteList.EMPTY;

				var codec = factory.newInstance(properties);

				if (properties.isReadable()) error("codec属性有剩余");

				var node = new CodecNode(codec, outputs.size());
				DAGNodes[i] = node;

				if (node.inputLinks.length != inCount || node.outCount != outCount) {
					if (!(codec instanceof UnknownCodec))
						error(codec+" 管道参数异常 ["+inCount+" => "+outCount+"] != ["+node.inputLinks.length+" => "+node.outCount+"]");
					node.inputLinks = new Object[inCount];
					node.outCount = outCount;
				}

				if (complex) {
					int j = 0;
					// 减少重复pointer对象
					int sharedCount = Math.min(inCount, outCount);
					for (; j < sharedCount; j++) {
						var pointer = new IntMap.Entry<>(j, node);
						inputs.add(pointer);
						outputs.add(pointer);
					}
					for (; j < inCount; j++) inputs.add(new IntMap.Entry<>(j, node));
					for (; j < outCount; j++) outputs.add(new IntMap.Entry<>(j, node));
				}

				if (++i == count) break;

				flag = in.readUnsignedByte();
			}
			// 2026/01/09 现在可以intern了, 但是因为CodecNode是DAG，所以有多种可能的顺序？？（是吗？？）
			// 反正对于常规文件能去重就行
			// 这里可能是一个逻辑上的坑，不过因为隔壁writeOptions依赖这个数组的顺序最好还是别排序
			b.codecs = this.codecInterner.intern(DAGNodes);

			int inCount = inputs.size();
			int outCount = outputs.size();
			int pipeCount = outCount-1;
			// only have one 'actual' output
			if (pipeCount < 0) throw new CorruptedInputException("too few output");
			// but can have many inputs
			if (inCount <= pipeCount) throw new CorruptedInputException("too few input");

			b.outSizes = new long[outCount-1];

			for (i = pipeCount; i > 0; i--) {
				IntMap.Entry<CodecNode>
					in = inputs.set(readVUInt(inCount), null),
					out = outputs.set(readVUInt(outCount), null);

				// 包装一下，不再抛出NullPointerException以免有人觉得是我的库垃圾
				// Apache commons compress没检查这个就直接死循环了喵(CVE呢)
				if (in == null || out == null)
					throw new CorruptedInputException("invalid pipe");

				in.getValue().pipe(in.getIntKey(), out.getValue(), out.getIntKey());
			}

			int dataCount = inCount-pipeCount;
			b.extraSizes = new long[dataCount-1];
			for (int j = 0; j < dataCount; j++) {
				var entry = inputs.set(readVUInt(inCount), null);
				entry.getValue().setFileInput(j, entry.getIntKey());
			}

			for (int j = 0; j < outputs.size(); j++) {
				var entry = outputs.get(j);
				if (entry != null) {
					b.multiStreamCodec = entry.getValue();
					return b;
				}
			}

			throw new CorruptedInputException("no output specified");
		}

		// 文件的大小偏移等"数据的元数据"
		private void readSubStreamsInfo() throws IOException {
			int neFiles;

			int nid = in.readUnsignedByte();
			if (nid == kNumUnPackStream) {
				neFiles = 0;
				for (WordBlock b : blocks)
					neFiles += b.fileCount = readVUInt();

				nid = in.readUnsignedByte();
			} else {
				neFiles = blocks.length;
				for (WordBlock b : blocks)
					b.fileCount = 1;
			}

			if (neFiles > that.maxFileCount) error("文件数量超出限制");
			SevenZEntry[] files = this.files = new SevenZEntry[neFiles];
			boolean skipMetadata = (that.flag&FLAG_SKIP_METADATA) != 0;
			for (int i = 0; i < neFiles; i++)
				files[i] = skipMetadata ? new SevenZEntry() : new SevenZEntryA();

			int fileId = 0;
			if (nid == kSize) {
				WordBlock[] blocks = this.blocks;
				for (int j = 0; j < blocks.length; j++) {
					WordBlock b = blocks[j];

					SevenZEntry prev = null;
					long sum = 0;
					for (int i = b.fileCount-1; i > 0; i--) {
						long size = readVULong();

						SevenZEntry f = files[fileId++];
						f.block = b;
						f.offset = sum;
						f.uSize = size;

						if (prev != null) prev.next = f;
						else b.firstEntry = f;
						prev = f;

						sum += size;
					}

					SevenZEntry f = files[fileId++];
					f.block = b;
					f.offset = sum;
					f.uSize = b.uSize - sum;

					if (prev != null) prev.next = f;
					else b.firstEntry = f;

					if (f.uSize <= 0) error("block["+j+"]没有足够的数据:最后的解压大小为"+f.uSize);
				}

				nid = in.readUnsignedByte();
			} else {
				if (neFiles != blocks.length) fatalError();

				for (WordBlock b : blocks) {
					SevenZEntry f = files[fileId++];

					f.block = b;
					f.offset = 0;
					f.uSize = b.uSize;

					b.firstEntry = f;

					if ((b.hasCrc&1) != 0) f._setCrc(b.crc);
				}
			}

			if (nid == kCRC) {
				int extraCrc = 0;
				for (WordBlock b : blocks) {
					if (b.fileCount != 1 || (b.hasCrc&1) == 0) {
						extraCrc += b.fileCount;
					}
				}

				BitSet extraCrcs = readBitsOrTrue(extraCrc);

				fileId = 0;
				extraCrc = 0;

				for (WordBlock b : blocks) {
					if (b.fileCount == 1 && (b.hasCrc&1) != 0) {
						files[fileId++]._setCrc(b.crc);
					} else {
						for (int i = b.fileCount; i > 0; i--) {
							if (extraCrcs.contains(extraCrc++)) {
								int crc = in.readIntLE();
								files[fileId++]._setCrc(crc);

								if (b.fileCount == 1) {
									b.hasCrc |= 1;
									b.crc = crc;
								}
							}
						}
					}
				}

				nid = in.readUnsignedByte();
			}

			end(nid);
		}

		// 文件名称，空文件，文件夹，修改时间等(以属性形式存放的)元数据
		private void readFilesInfo() throws IOException {
			boolean skipMetadata = (that.flag&FLAG_SKIP_METADATA) != 0;
			int fileCount = readVUInt(that.maxFileCount);
			SevenZEntry[] files = this.files;
			int nonEmptyFileCount;

			if (files == null) {
				nonEmptyFileCount = 0;
				// 全部是空文件
				files = new SevenZEntry[fileCount];
				for (int i = 0; i < fileCount; i++) files[i] = skipMetadata ? new SevenZEntry() : new SevenZEntryA();
			} else {
				nonEmptyFileCount = files.length;
				if (fileCount != files.length) {
					if (fileCount < files.length) fatalError();

					// 有一些空文件
					files = new SevenZEntry[fileCount];
					for (int i = nonEmptyFileCount; i < fileCount; i++) files[i] = skipMetadata ? new SevenZEntry() : new SevenZEntryA();
				}
			}

			int id = in.readUnsignedByte();
			BitSet empty = null;
			if (id == kEmptyStream) {
				if (fileCount == nonEmptyFileCount) fatalError();

				int length = in.readVUInt();
				BitSet emptyFile = null, anti = null;
				empty = BitSet.readBits(in, fileCount);
				while (true) {
					id = in.readUnsignedByte();
					if (id == kEmptyFile) {
						if (emptyFile != null) error("kEmptyFile属性重复");
						length = in.readVUInt();
						emptyFile = BitSet.readBits(in, empty.size());
					} else if (id == kAnti) {
						if (anti != null) error("kAnti属性重复");
						length = in.readVUInt();
						anti = BitSet.readBits(in, empty.size());
					} else {
						break;
					}
				}

				// 重排序空文件
				int emptyNo = this.files == null ? 0 : this.files.length, fileNo = 0;
				int emptyNoNo = 0;
				for (int i = 0; i < fileCount; i++) {
					if (!empty.contains(i)) {
						if (fileNo == emptyNo) fatalError();
						// noinspection all
						files[i] = this.files[fileNo++];
						// 明显的，uSize不可能等于0
					} else {
						// 同样uSize必然等于0
						SevenZEntry entry = files[i] = files[emptyNo++];

						if (emptyFile == null || !emptyFile.contains(emptyNoNo)) entry.flag |= SevenZEntry.DIRECTORY;
						if (anti != null && anti.contains(emptyNoNo)) entry.flag |= SevenZEntry.ANTI;

						emptyNoNo++;
					}
				}
			} else if (nonEmptyFileCount != fileCount) {
				if (strictMode) error("!L 属性缺失或恶意构造的文件, 添加RECOVERY标志位来接受, 极大内存开销, 注意拒绝服务攻击");
				relocationAll(id, files, nonEmptyFileCount, fileCount, skipMetadata);
				return;
			}

			while (id != 0) {
				int len = readVUInt();
				switch (id) {
					case kEmptyFile, kAnti -> {
						if (nonEmptyFileCount == fileCount) fatalError();
						if (strictMode) error("!L 属性缺失或恶意构造的文件, 添加RECOVERY标志位来接受, 极大内存开销, 注意拒绝服务攻击");
						if (id == kEmptyFile) relocation15(in, files, empty);
						else relocation16(in, files, empty);
					}
					case kName -> {
						var in = getOptionalExternalData();

						len >>= 1;
						int j = 0;

						CharList sb = IOUtil.getSharedCharBuf();
						// UTF-16 LE
						for (int i = 0; i < len; i++) {
							int c = in.readUnsignedShortLE();
							if (c == 0) {
								files[j++].name = sb.toString();
								sb.clear();
							} else {
								sb.append((char) c);
							}
						}

						if (in != this.in) in.close();
						if (j != fileCount) error("文件名太少");
					}
					case kCTime, kATime, kMTime -> {
						if (skipMetadata) {in.skipForce(len);continue;}

						BitSet set = readBitsOrTrue(fileCount);
						var in = getOptionalExternalData();

						long off = SevenZEntryA.FIELD_OFFSET[id-kCTime];
						int flag = 1 << (id-kCTime);

						for (var itr = set.iterator(); itr.hasNext(); ) {
							var entry = files[itr.nextInt()];
							entry.flag |= flag;
							U.putLong(entry, off, in.readLongLE());
						}

						if (in != this.in) in.close();
					}
					case kWinAttributes -> {
						if (skipMetadata) {in.skipForce(len);continue;}

						BitSet set = readBitsOrTrue(fileCount);
						var in = getOptionalExternalData();

						long off = SevenZEntryA.FIELD_OFFSET[3];
						for (var itr = set.iterator(); itr.hasNext(); ) {
							var entry = files[itr.nextInt()];
							entry.flag |= SevenZEntry.ATTR;
							U.putInt(entry, off, in.readIntLE());
						}

						if (in != this.in) in.close();
					}
					default -> handleOtherAttribute(id, len);
				}

				id = in.readUnsignedByte();
			}

			this.files = files;
		}

		private XDataInputStream getOptionalExternalData() throws IOException {
			var in = this.in;
			if (in.readByte() == 0) return in;

			int index = readVUInt();
			if (externalBlocks == null || index >= externalBlocks.length)
				error("不存在externalBlocks["+in+"]");

			return new XDataInputStream(that.getBlockInputStream(externalBlocks[index], null, that.r, that, (that.flag&FLAG_RECOVERY) == 0));
		}

		private void handleOtherAttribute(int id, int len) throws IOException {
			if ((that.flag&FLAG_LOAD_ATTRIBUTES) != 0) {
				if (len > ArrayCache.MAX_ARRAY_SIZE)
					throw new OutOfMemoryError("Could not allocate byte["+len+"]");
				that.attributes.put((char) (0x1000 | id), in.readBytes(len));
			} else {
				if ((that.flag&FLAG_DUMP_HIDDEN) != 0)
					System.out.println("扩展属性(文件):"+id+",长度为"+ len);
				in.skipForce(len);
			}
		}

		private void relocation15(XDataInput in, SevenZEntry[] files, BitSet empty) throws IOException {
			var emptyFile = BitSet.readBits(in, empty.size());
			int j = 0;
			for (var itr = empty.iterator(); itr.hasNext(); ) {
				int i = itr.nextInt();
				if (emptyFile.contains(j++)) {
					files[i].flag &= ~SevenZEntry.DIRECTORY;
				}
			}
		}
		private void relocation16(XDataInput in, SevenZEntry[] files, BitSet empty) throws IOException {
			var anti = BitSet.readBits(in, empty.size());
			int j = 0;
			for (var itr = empty.iterator(); itr.hasNext(); ) {
				int i = itr.nextInt();
				if (anti.contains(j++)) {
					files[i].flag |= SevenZEntry.ANTI;
				}
			}
		}
		@SuppressWarnings("fallthrough")
		private void relocationAll(int id, SevenZEntry[] files, int nonEmptyFileCount, int fileCount, boolean skipMetadata) throws IOException {
			byte[][] deferredAttributes = new byte[8][];
			BitSet empty = null;
			while (id != 0) {
				int len = in.readVUInt();
				switch (id) {
					case kEmptyStream:
						empty = BitSet.readBits(in, fileCount);
						int emptyNo = nonEmptyFileCount, fileNo = 0;
						for (int i = 0; i < fileCount; i++) {
							if (!empty.contains(i)) {
								if (fileNo == emptyNo) fatalError();
								files[i] = this.files[fileNo++];
							} else {
								files[i] = files[emptyNo++];
							}
						}
					break;
					case kCTime, kATime, kMTime, kWinAttributes, kComment:
						if (skipMetadata) {
							in.skipForce(len);
							break;
						}
					case kEmptyFile, kAnti, kName:
						deferredAttributes[id - kEmptyFile] = in.readBytes(len);
					break;
					default:handleOtherAttribute(id, len);break;
				}

				id = in.readUnsignedByte();
			}

			if (empty == null) fatalError();

			for (id = kEmptyFile; id < kComment; id++) {
				byte[] attribute = deferredAttributes[id-kEmptyFile];
				if (attribute != null) {
					var in = DynByteBuf.wrap(attribute);
					int len = attribute.length;

					switch (id) {
						case kEmptyFile -> relocation15(in, files, empty);
						case kAnti -> relocation16(in, files, empty);
						case kName -> {
							if (in.readByte() != 0) error("iFileName.external");

							len >>= 1;
							int j = 0;

							CharList sb = IOUtil.getSharedCharBuf();
							// UTF-16 LE
							for (int i = 0; i < len; i++) {
								int c = in.readUnsignedShortLE();
								if (c == 0) {
									files[j++].name = sb.toString();
									sb.clear();
								} else {
									sb.append((char) c);
								}
							}

							if (j != fileCount) error("文件名太少");
						}
						case kCTime, kATime, kMTime, kWinAttributes -> {
							BitSet set;
							// all true
							if (in.readByte() != 0) {
								set = new BitSet();
								set.fill(fileCount);
							} else {
								set = BitSet.readBits(in, fileCount);
							}

							if (in.readByte() != 0) error("i_Time.external");

							long off = SevenZEntryA.FIELD_OFFSET[id-kCTime];

							if (id == kWinAttributes) {
								for (var itr = set.iterator(); itr.hasNext(); ) {
									var entry = files[itr.nextInt()];
									entry.flag |= SevenZEntry.ATTR;
									U.putInt(entry, off, in.readIntLE());
								}
							} else {
								int flag = 1 << (id-kCTime);

								for (var itr = set.iterator(); itr.hasNext(); ) {
									var entry = files[itr.nextInt()];
									entry.flag |= flag;
									U.putLong(entry, off, in.readLongLE());
								}
							}
						}
					}
				}
			}
		}

		private ByteList read(int len) throws IOException {
			var b = buf;
			b.clear();
			IOUtil.readFully(in == null ? rawIn : in, b.list, b.arrayOffset(), len);
			b.wIndex(len);
			return b;
		}
		private BitSet readBitsOrTrue(int size) throws IOException {
			BitSet set;
			// all true
			if (in.readByte() != 0) {
				set = new BitSet();
				set.fill(size);
			} else {
				set = BitSet.readBits(in, size);
			}
			return set;
		}
		private long readVULong() throws IOException {
			long i = in.readVULong();
			if (i < 0) error("溢出:"+i);
			return i;
		}
		private int readVUInt() throws IOException {
			long i = in.readVULong();
			if (i < 0 || i > Integer.MAX_VALUE) error("溢出:"+i);
			return (int) i;
		}
		private int readVUInt(int max) throws IOException {
			int i = readVUInt();
			if (i > max && !that.isRecovering()) error("长度超出限制");
			return i;
		}
		private void end(int type) throws IOException {
			if (type != 0) error("期待0,"+type);
		}

		private static void fatalError() throws IOException { throw new CorruptedInputException(); }
		private void error(String msg) throws IOException { if (!that.isRecovering()) throw new CorruptedInputException(msg); }

		// endregion
	}

	public boolean isRecovering() {return (flag & FLAG_RECOVERY) != 0;}

	@Override
	public SevenZEntry getEntry(String name) { return getEntries().get(name); }

	public HashMap<String, SevenZEntry> getEntries() {
		if (byName.isEmpty()) {
			byName.ensureCapacity(entries.length);
			for (SevenZEntry entry : entries) {
				if (byName.putIfAbsent(entry.name, entry) != null) {
					throw new IllegalArgumentException("文件名重复！该文件可能已损坏，请通过entries()获取Entry并读取");
				}
			}
		}
		return byName;
	}

	@Override
	public List<SevenZEntry> entries() { return Arrays.asList(entries); }
	public SevenZEntry[] getEntriesByPresentOrder() { return entries; }

	public void parallelDecompress(TaskGroup taskGroup, BiConsumer<SevenZEntry, InputStream> callback) {parallelDecompress(taskGroup, callback, password);}
	/**
	 * Decompresses all entries in parallel using the provided task group, with optional custom password.
	 * Each block is processed in a separate task, invoking the callback for each entry's input stream.
	 * Supports cancellation via {@link TaskGroup#isCancelled()} and shared source access.
	 *
	 * @implNote 解压在此方法返回时可能尚未结束 使用 {@link TaskGroup#await()} 以等待
	 *
	 * @param taskGroup the task group for parallel execution
	 * @param callback the consumer to process each entry and its stream (stream is auto-closed after callback)
	 * @param password the password for decryption (falls back to archive password if null)
	 * @throws IOException (in TaskGroup) if decompression fails or stream ends prematurely
	 * @throws FastFailException if the archive is closed by another thread
	 */
	public void parallelDecompress(TaskGroup taskGroup, BiConsumer<? super SevenZEntry, InputStream> callback, byte[] password) {
		if (blocks == null) return;

		Objects.requireNonNull(r, "Stream Closed");
		taskGroup.executeUnsafe(() -> {
			var nullIn = new LimitInputStream(null, 0);
			for (int i = entries.length - 1; i >= 0; i--) {
				SevenZEntry entry = entries[i];
				if (entry.getSize() > 0) break;

				callback.accept(entry, nullIn);
			}
		});
		for (WordBlock block : blocks) {
			taskGroup.executeUnsafe(() -> {
				if (r == null) throw new FastFailException("其他线程关闭了压缩包");

				SevenZEntry entry = block.firstEntry;
				try (var blockIn = getBlockInputStream(entry, password)) {
					//noinspection IOResourceOpenedButNotSafelyClosed
					LimitInputStream in = new LimitInputStream(blockIn, 0);
					long toSkip = 0;
					do {
						if (taskGroup.isCancelled()) return;

						in.remain = entry.uSize;

						InputStream fin = in;
						if ((flag&FLAG_RECOVERY) == 0 && (entry.flag& SevenZEntry.CRC) != 0) fin = new CRC32InputStream(fin, entry.crc32);
						if (toSkip > 0) fin = new DelayedSkipInputStream(fin, blockIn, toSkip);

						callback.accept(entry, fin);

						if (toSkip > 0) toSkip = ((DelayedSkipInputStream) fin).toSkip;
						toSkip += in.remain;

						entry = entry.next;
					} while (entry != null);
				}
			});
		}
	}

	private static final class DelayedSkipInputStream extends InputStream {
		private final InputStream in, rin;
		long toSkip;

		private DelayedSkipInputStream(InputStream in, InputStream rin, long skip) {
			this.in = in;
			this.rin = rin;
			toSkip = skip;
		}

		@Override
		public int read() throws IOException {
			if (toSkip != 0) doSkip();
			return in.read();
		}
		@Override
		public int read(@NotNull byte[] b, int off, int len) throws IOException {
			if (toSkip != 0) doSkip();
			return in.read(b, off, len);
		}
		@Override
		public long skip(long n) throws IOException {
			if (toSkip != 0) doSkip();
			return in.skip(n);
		}

		private void doSkip() throws IOException {
			IOUtil.skipFully(rin, toSkip);
			toSkip = 0;
		}
	}

	final InputStream getBlockInputStream(WordBlock block, byte[] password, Source src, SevenZReader self, boolean verify) throws IOException {
		if (password == null) password = this.password;
		assert blocks == null || Arrays.asList(blocks).contains(block) : "foreign word block "+block;

		src.seek(block.offset);

		var limit = new AtomicInteger(memoryLimitKb);
		InputStream in;
		var node = block.multiStreamCodec;
		if (node == null) {
			in = new SourceInputStream.Shared(src, block.size, self, CACHE);

			var codecs = block.codecs;
			SevenZCodec.checkMemoryUsage(limit, codecs.length);
			for (int i = 0; i < codecs.length; i++) {
				in = codecs[i].decode(in, password, i==codecs.length-1 ? block.uSize : block.outSizes[i], limit);
			}
		} else {
			long[] sizes = block.extraSizes;
			var streams = new InputStream[sizes.length+1];
			SevenZCodec.checkMemoryUsage(limit, streams.length);

			long off = block.offset;
			src.seek(off);
			//noinspection IOResourceOpenedButNotSafelyClosed
			streams[0] = new SourceInputStream.Shared(src, block.size, self, CACHE);
			off += block.size;

			for (int i = 0; i < sizes.length;) {
				src = src.copy();
				src.seek(off);

				long len = sizes[i++];
				// noinspection all
				streams[i] = new SourceInputStream(src, len);
				off += len;
			}

			var ins = node.getInputStream(block.outSizes, streams, new HashMap<>(), password, limit);
			if (ins.length != 1) throw new CorruptedInputException("root node has many outputs");
			in = ins[0];
		}

		// 通常false，因为QZEntry自身有CRC32了
		// 不过我喜欢搞骚操作，也许直接读WordBlock了
		if (verify && (block.hasCrc&1) != 0) in = new CRC32InputStream(in, block.crc);

		return in;
	}

	private List<ForkedReader> readers = Collections.emptyList();
	/**
	 * Creates a forked reader sharing the same data as this archive, suitable for concurrent access
	 * (e.g., multiple threads reading different blocks without resource duplication).
	 * The forked reader delegates block reading to this instance but manages its own active stream
	 * and file handle. May be used for high concurrency.
	 * Forks are tracked and closed with the parent archive.
	 *
	 * @return a new forked reader instance
	 */
	public synchronized SevenZReader forkReader() {
		if (readers.isEmpty()) readers = new ArrayList<>();
		ForkedReader copy = new ForkedReader();
		copy.r = r;
		readers.add(copy);
		return copy;
	}
	final class ForkedReader extends SevenZReader {
		@Override
		InputStream getBlockInputStream(WordBlock block, byte[] password, Source src, SevenZReader self, boolean verify) throws IOException {
			return SevenZFile.this.getBlockInputStream(block, password, src, self, verify);
		}

		@Override
		public void close() {
			synchronized (SevenZFile.this) { readers.remove(this); }
			closeActiveStream();
		}
	}

	@Override
	public synchronized void close() throws IOException {
		closeActiveStream();

		for (var reader : readers) {
			reader.closeActiveStream();

			var cache = (Source) CACHE.getAndSet(reader, r);
			IOUtil.closeSilently(cache);
		}
		readers.clear();

		if (r != null) {
			Source r1 = r;
			r1.close();
			r = null;

			Source cache = (Source) CACHE.getAndSet(this, r1);
			IOUtil.closeSilently(cache);

			if (password != null) Arrays.fill(password, (byte) 0);
		}
	}
}