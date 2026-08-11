package roj.ecc;

import org.jetbrains.annotations.Nullable;
import roj.collect.ArrayList;
import roj.collect.Hasher;
import roj.collect.ToIntMap;
import roj.concurrent.TaskGroup;
import roj.crypt.CRC32;
import roj.io.CorruptedInputException;
import roj.io.IOUtil;
import roj.io.source.FileSource;
import roj.io.source.Source;
import roj.math.MathUtils;
import roj.util.ArrayCache;
import roj.util.ByteList;
import roj.util.DynByteBuf;
import roj.util.NativeMemory;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.util.Arrays;
import java.util.function.IntConsumer;

/**
 * @author Roj234
 * @since 2026/05/20
 */
public final class ECFile {
	public final int dataBytes, ecBytes, lanes;
	public final int redundancyPercent, burstLen;
	public long dataLength;

	public ECFile(int redundancyPercent, int burstLen, long length) {
		if (redundancyPercent < 1 || redundancyPercent > 50000)
			throw new IllegalArgumentException("redundancyPercent must be 0.001..50.000");
		if (burstLen < 1) throw new IllegalArgumentException("burstLen must be >= 1");

		this.redundancyPercent = redundancyPercent;
		this.burstLen      = burstLen;

		float exceptingRatio = redundancyPercent / 100000f;
		float bestDelta = 1;

		int data = -1, ec = -1;

		for (int dataBytes = 253; dataBytes > 0; dataBytes--) {
			int start = Math.max(2, 127 - dataBytes); // 保证空间使用率
			int maxEc = 255 - dataBytes;

			int ecMin = (int) Math.ceil(2.0 * exceptingRatio * dataBytes);
			if (ecMin < start) ecMin = start;
			if ((ecMin & 1) != 0) ecMin++;

			if (ecMin > maxEc) continue;

			float ratio = (ecMin / 2f) / dataBytes;
			float delta = ratio - exceptingRatio;
			if (delta > 0 && delta < bestDelta) {
				bestDelta = delta;
				data = dataBytes;
				ec = ecMin;
			}
		}

		int maxFixPerLane = Math.max(1, ec / 2);
		int lanesNeeded = MathUtils.clamp((burstLen + maxFixPerLane - 1) / maxFixPerLane, 1, 0xFFFF);
		if (lanesNeeded == 0xFFFF) throw new IllegalArgumentException("burstLen too large for given redundancy");

		this.dataBytes = data;
		this.ecBytes   = ec;
		this.lanes     = lanesNeeded;
		this.dataLength= length;
	}

	private static final String MARKER = "RECC";
	// MARKER(4) + LENGTH(8) + REDUNDANCY_PERCENT(4) + BURST_LENGTH(4) + WINDOW_HASH(4) + SEQ(4) + CRC32(4) = 32
	private static final int FOOTER_SIZE = 32;
	private static final int SPRINKLE_STRIDE_MIN = 4096, SPRINKLE_STRIDE_MAX = 65536;
	private static final int HASH_BASE = 101, HASH_WINDOW = 1024, HASH_WINDOW_POWER = MathUtils.pow(HASH_BASE, HASH_WINDOW);

	private static final class TaskContext {
		final Source in, out;
		final ByteList footer;
		final ReedSolomonCodec codec;
		final long dataLength;

		ArrayList<Source> inputs = new ArrayList<>();
		ArrayList<Source> outputs = new ArrayList<>();
		ArrayList<DynByteBuf> footers = new ArrayList<>();

		TaskContext(Source in, Source out, ByteList footer, ReedSolomonCodec codec, long dataLength) {
			this.in = in;
			this.out = out;
			this.footer = footer;
			this.codec = codec;
			this.dataLength = dataLength;
		}

		public Source getInput() throws IOException {
			var exist = inputs.pop();
			return exist == null ? in.copy() : exist;
		}

		public Source getOutput() throws IOException {
			var exist = outputs.pop();
			return exist == null ? out.copy() : exist;
		}

		public DynByteBuf getFooter() {
			var exist = footers.pop();
			return exist == null ? footer.copySlice() : exist;
		}

		public void putInput(Source in) {
			inputs.add(in);
		}

		public void putOutput(Source out) {
			outputs.add(out);
		}

		public void putFooter(DynByteBuf footer) {
			footers.add(footer);
		}

		public void close() {
			for (Source input : inputs) {
				IOUtil.closeSilently(input);
			}
			for (Source output : outputs) {
				IOUtil.closeSilently(output);
			}
			footers.clear();
		}
	}

	private int sprinkleStride(long fileLength, int dataSize, int eccSize) {
		int byRedundancy = (int) ((FOOTER_SIZE * 10L * 100000L + redundancyPercent - 1L) / redundancyPercent);
		return MathUtils.clamp(byRedundancy, SPRINKLE_STRIDE_MIN, SPRINKLE_STRIDE_MAX);
	}

	public void protect(File file, @Nullable IntConsumer progressCallback) throws IOException {
		try (var in = new FileSource(file, false);
			 var out = new FileSource(file, true)
		) {
			out.seek(file.length());
			protect(in, out, file.length(), progressCallback);
		}
	}
	public void protect(Source in, Source out, long fileLength, @Nullable IntConsumer progressCallback) throws IOException {
		this.dataLength = fileLength;

		var dataCodec = new ReedSolomonCodec(dataBytes, ecBytes);

		var footer = ByteList.allocate(FOOTER_SIZE, FOOTER_SIZE);
		footer.putAscii(MARKER).putLong(fileLength).putInt(redundancyPercent).putInt(burstLen);

		var ctx = new TaskContext(null, null, null, dataCodec, fileLength);
		int stride = sprinkleStride(fileLength, dataBytes, ecBytes);

		ctx.inputs.add(in);
		ctx.outputs.add(out);
		ctx.footers.add(footer);

		int laneSeq = protectTask(ctx, 0, in == out ? fileLength : out.position(), 0, stride, 0, Integer.MAX_VALUE, progressCallback);

		// 尾部总是有一个
		footer.wIndex(FOOTER_SIZE - 12);
		footer.putInt(0).putInt(laneSeq).putInt(CRC32.crc32(footer)).writeToStream(out);
	}
	public void protect(Source in, Source out, long inputLength, TaskGroup group, @Nullable IntConsumer progressCallback) throws IOException {
		this.dataLength = inputLength;

		var dataCodec = new ReedSolomonCodec(dataBytes, ecBytes);

		var footer = ByteList.allocate(FOOTER_SIZE, FOOTER_SIZE);
		footer.putAscii(MARKER).putLong(inputLength).putInt(redundancyPercent).putInt(burstLen);

		var dataBytes = dataCodec.dataBytes();
		var ecBytes = dataCodec.ecBytes();
		int lanes = this.lanes;
		int stride = sprinkleStride(inputLength, dataBytes, ecBytes);

		var ctx = new TaskContext(in, out, footer, dataCodec, dataLength);

		int seq = 0;

		long inputOffset = 0;
		long outputOffset = in == out ? inputLength : out.position();
		long lastOffset = 0; // 数据后立刻写 footer

		int inputSize = lanes * dataBytes;
		int perThreadSeq = Math.max(4194304 / inputSize, 1);

		while (inputOffset < inputLength) {
			int immSeq = seq;
			var immInputOffset = inputOffset;
			var immOutputOffset = outputOffset;
			var immLastOffset = lastOffset;

			group.executeUnsafe(() -> protectTask(ctx, immInputOffset, immOutputOffset, immLastOffset, stride, immSeq, perThreadSeq, progressCallback));

			seq += perThreadSeq;

			for (int i = 0; i < perThreadSeq; i++) {
				if (outputOffset - lastOffset >= stride) {
					outputOffset += FOOTER_SIZE;
					lastOffset = outputOffset;
				}

				outputOffset += (long) lanes * ecBytes;
				inputOffset += (long) lanes * dataBytes;
			}
		}

		group.await();
		ctx.close();

		out.seek(outputOffset - FOOTER_SIZE);
		// 尾部总是有一个
		footer.wIndex(FOOTER_SIZE - 12);
		footer.putInt(0).putInt(seq).putInt(CRC32.crc32(footer)).writeToStream(out);
	}

	private int protectTask(
			TaskContext ctx,
			long inputOffset, long outputOffset,
			long lastOffset, int stride,
			int laneSeq, int count,
			@Nullable IntConsumer progressCallback
	) throws IOException {
		int lanes = this.lanes;
		int ecBytes = this.ecBytes;
		int dataBytes = this.dataBytes;
		var dataCodec = ctx.codec;
		int parityBytes = lanes * ecBytes;
		byte[] ec = ArrayCache.getByteArray(parityBytes, true);
		byte[] parity = ArrayCache.getByteArray(Math.max(lanes, ArrayCache.IO_BUFFER_SIZE), false);
		byte[] ioBuffer = ArrayCache.getIOBuffer();
		int laneIndex = 0;
		int laneRounds = 0;

		Source in, out;
		DynByteBuf footer;
		synchronized (ctx) {
			in = ctx.getInput();
			out = ctx.getOutput();
			footer = ctx.getFooter();
		}

		long remain = ctx.dataLength - inputOffset;
		in.seek(inputOffset);
		out.seek(outputOffset);

		while (true) {
			int r = in.read(ioBuffer, 0, (int) Math.min(remain, ioBuffer.length));
			if (r <= 0) break;
			remain -= r;

			int i = 0;
			while (true) {
				int rest = Math.min(r - i, lanes - laneIndex);
				if (rest == 0) break;

				while (rest > 0) {
					dataCodec.lfsrUpdate(ec, laneIndex++ * ecBytes, ioBuffer[i++]);
					rest--;
				}

				if (laneIndex == lanes) {
					if (++laneRounds == dataBytes) {
						if (outputOffset - lastOffset >= stride) {
							footer.wIndex(FOOTER_SIZE - 12);

							// 重新定位文件用的窗口哈希
							// 现在还不支持，但提前保存，万一用上了呢
							int hash = 0;
							if (r >= HASH_WINDOW) {
								for (int j = r - HASH_WINDOW; j < r; j++) {
									hash = (hash * HASH_BASE + (ioBuffer[j] & 0xFF));
								}
							}

							footer.putInt(hash).putInt(laneSeq).putInt(CRC32.crc32(footer)).writeToStream(out);

							outputOffset += FOOTER_SIZE;
							lastOffset = outputOffset;
						}
						outputOffset += (long) ecBytes * lanes;

						transposedWrite(ecBytes, lanes, parity, ec, out);

						laneSeq++;
						laneRounds = 0;

						if (--count == 0) break;
						Arrays.fill(ec, 0, parityBytes, (byte) 0);
					}

					laneIndex = 0;
				}

				if (progressCallback != null) progressCallback.accept(r);
			}
		}

		ArrayCache.putArray(ioBuffer);

		// 末尾零填充
		if ((laneIndex|laneRounds) != 0) {
			for (; laneIndex < lanes; laneIndex++) {
				dataCodec.lfsrUpdate(ec, laneIndex * ecBytes, (byte) 0);
			}

			if (laneRounds != dataBytes) {
				for (int laneIndex1 = 0; laneIndex1 < lanes; laneIndex1++) {
					for (int j = laneRounds+1; j < dataBytes; j++) {
						dataCodec.lfsrUpdate(ec, laneIndex1 * ecBytes, (byte) 0);
					}
				}
			}

			transposedWrite(ecBytes, lanes, parity, ec, out);
		}

		synchronized (ctx) {
			ctx.putInput(in);
			ctx.putOutput(out);
			ctx.putFooter(footer);
		}

		ArrayCache.putArray(parity);
		ArrayCache.putArray(ec);

		return laneSeq;
	}

	private static void transposedWrite(int ecBytes, int lanes, byte[] tmp, byte[] ec, Source out) throws IOException {
		int tmpLen = 0;
		for (int j = 0; j < ecBytes; j++) {
			if (tmpLen + lanes > tmp.length)  {
				out.write(tmp, 0, tmpLen);
				tmpLen = 0;
			}

			for (int lane = 0; lane < lanes; lane++) {
				tmp[tmpLen + lane] = ec[lane * ecBytes + j];
			}
			tmpLen += lanes;
		}

		out.write(tmp, 0, tmpLen);
	}

	public static ECFile readFooter(File file) throws IOException {
		var footer = readFooters(file, true);
		if (footer == null) return null;
		ByteList data = DynByteBuf.wrap(footer);
		long originalLength = data.readLong();
		int redundancyPercent = data.readInt();
		int burstLength = data.readInt();

		return new ECFile(redundancyPercent, burstLength, originalLength);
	}

	/** 移除恢复信息，把文件截回原大小。 */
	public static void unprotect(File file) throws IOException {
		var footer = readFooters(file, true);
		if (footer == null) throw new CorruptedInputException("File is not protected or invalid");
		try (var raf = new RandomAccessFile(file, "rw")) {
			raf.setLength(DynByteBuf.wrap(footer).getLong(0));
		}
	}

	public enum Status { OK, FIXABLE, FIXED, UNFIXABLE }

	public static final class RepairResult {
		public Status status = Status.OK;
		public long bytesFixed;
		public int  groupsFixed;
		public int  groupsUnfixable;
		public long shiftedBytesRecovered;
		public String message;

		@Override public String toString() {
			return "Repair{status="+status+", bytesFixed="+bytesFixed+", groupsFixed="+groupsFixed+
						   ", groupsUnfixable="+groupsUnfixable+", shifted="+shiftedBytesRecovered+
						   (message==null?"":", msg="+message)+"}";
		}
	}

	public RepairResult repair(File data, boolean doFix, IntConsumer progressCallback) throws IOException {
		try (var fs = new FileSource(data, doFix)) {
			return repair(fs, fs, doFix, progressCallback);
		}
	}
	public RepairResult repair(Source data, boolean doFix, IntConsumer progressCallback) throws IOException {
		return repair(data, data, doFix, progressCallback);
	}
	public RepairResult repair(Source data, Source ec, boolean doFix, @Nullable IntConsumer progressCallback) throws IOException {
		var result = new RepairResult();
		var fixEC = false;

		long totalLength = data.length();
		long dataLength = this.dataLength;
		if (totalLength < dataLength) result.message = "文件被截断: 期望 > "+ dataLength +" 实际 "+totalLength;

		var codec = new ReedSolomonCodec(dataBytes, ecBytes);

		int dataBytes = this.dataBytes;
		int lanes     = this.lanes;
		int ecBytes   = this.ecBytes;
		int ecChunkSize   = lanes * ecBytes;
		int stride = sprinkleStride(totalLength, dataBytes, ecChunkSize);

		var matrix = ArrayCache.getByteArray(codec.chunkSize() * lanes, false);
		var poly = new byte[dataBytes + ecBytes];
		var erasureLocations = new byte[dataBytes + ecBytes];

		var ecOffset = data == ec ? dataLength : ec.position();
		long lastOffset = 0;

		while (true) {
			int r = lanes * dataBytes;
			long remain = dataLength - data.position();

			if (r > remain) {
				r = (int) (remain);
				if (r == 0) break;

				for (int i = r; i < lanes * dataBytes; i++) matrix[i] = 0;
			}

			data.readFully(matrix, 0, r);
			if (progressCallback != null) progressCallback.accept(r);
			int fileDataLength = lanes * dataBytes;

			var dataChunkOffset = data.position();

			if (ecOffset - lastOffset >= stride && dataChunkOffset < dataLength) {
				ecOffset += FOOTER_SIZE;
				lastOffset = ecOffset;
			}

			long ecChunkOffset = ecOffset;

			ec.seek(ecOffset);
			ec.readFully(matrix, fileDataLength, ecChunkSize);
			ecOffset += ecChunkSize;

			boolean needFix = false;
			for (int i = 0; i < lanes; i++) {
				int j = 0;
				for (; j < dataBytes; j++) poly[j] = matrix[j * lanes + i];
				for (; j < poly.length; j++) poly[j] = matrix[fileDataLength + (j -dataBytes) * lanes + i];

				int corrected;
				try {
					corrected = codec.errorCorrection(poly);
				} catch (Exception e) {
					int erasureCount = 0;
					for (int run = 0; run < poly.length;) {
						int value = poly[run];
						if (value != 0 && value != -1) {
							run++;
							continue;
						}

						int start = run++;
						while (run < poly.length && poly[run] == value) run++;
						if (run - start >= codec.maxError() + 1) {
							for (int off = start; off < run; off++) {
								erasureLocations[erasureCount++] = (byte) off;
							}
						}
					}

					if (erasureCount == 0) {
						result.groupsUnfixable++;
						continue;
					}

					try {
						corrected = codec.errorCorrection(poly, Arrays.copyOf(erasureLocations, erasureCount));
					} catch (Exception e1) {
						result.groupsUnfixable++;
						continue;
					}

				}

				if (corrected != 0) {
					needFix = true;

					if (doFix) {
						for (int k = 0; k < dataBytes; k++) {
							matrix[k * lanes + i] = poly[k];
						}
						if (fixEC) {
							for (int k = dataBytes; k < poly.length; k++) {
								matrix[fileDataLength + (k - dataBytes) * lanes + i] = poly[k];
							}
						}
					}

					result.bytesFixed += corrected;
					result.groupsFixed++;
				}
			}

			if (needFix && doFix) {
				data.seek(dataChunkOffset - r);
				data.write(matrix, 0, r);
				if (fixEC) {
					ec.seek(ecChunkOffset);
					ec.write(matrix, fileDataLength, ecChunkSize);
					if (data == ec) data.seek(dataChunkOffset);
				}
			} else if (data == ec) {
				data.seek(dataChunkOffset);
			}
		}

		ArrayCache.putArray(matrix);

		if (result.groupsUnfixable > 0) {
			result.status = Status.UNFIXABLE;
		} else if (result.bytesFixed > 0) {
			result.status = doFix ? Status.FIXED : Status.FIXABLE;
		} else {
			result.status = Status.OK;
		}
		return result;
	}

	private static final int CHUNK_SIZE = 32 * 1024 * 1024;
	private static final long MAX_CONTINUOUS_EMPTY = 128L * 1024 * 1024;
	private static byte[] readFooters(File file, boolean quickCheckFooter) throws IOException {
		ToIntMap<byte[]> headers = new ToIntMap<>();
		headers.setHasher(Hasher.array(byte[].class));

		//var hashBuffer = IOUtil.getSharedByteBuf();

		var magic = IOUtil.getSharedByteBuf().putAscii(MARKER).readInt();

		try (var raf = new RandomAccessFile(file, "r");
			 var channel = raf.getChannel()) {

			long lastPos = 0;
			var magicBuffer = new byte[FOOTER_SIZE];
			var dataBuffer = new byte[FOOTER_SIZE - 12];
			var buf = DynByteBuf.wrap(magicBuffer);

			long end = channel.size() - 32;
			if (end <= 0) return null;

			if (quickCheckFooter) {
				raf.seek(end);
				raf.readFully(magicBuffer);

				// HEADER(4) DATA(...) NONCE(4) CRC32(4)
				if (CRC32.crc32(magicBuffer, 0, 28) == buf.getInt(28)) {
					System.arraycopy(magicBuffer, 4, dataBuffer, 0, dataBuffer.length);
					return dataBuffer;
				}
			}

			while (end > 0) {
				if (lastPos != 0 && end - lastPos > MAX_CONTINUOUS_EMPTY) {
					break;
				}

				long start = Math.max(0, end - CHUNK_SIZE);
				int length = (int) (end - start);
				var buffer = channel.map(FileChannel.MapMode.READ_ONLY, start, length);

				for (int i = length - 4; i >= 0; i--) {
					if (buffer.getInt(i) == magic) {
						int avail = length - i;
						if (avail >= 32) {
							buffer.get(i, magicBuffer);
						} else {
							raf.seek(i);
							raf.readFully(magicBuffer);
						}

						if (CRC32.crc32(magicBuffer, 0, 28) == buf.getInt(28)) {
							System.arraycopy(magicBuffer, 4, dataBuffer, 0, dataBuffer.length);

							int val = headers.increment(dataBuffer, 1);
							if (val == 1) dataBuffer = new byte[FOOTER_SIZE - 12];

							if (headers.size() == 1 && val > 50) break;

							lastPos = start + i;
						}
					}
				}

				NativeMemory.freeDirectBuffer(buffer);

				end = start;
			}
		}

		// 不做参数校验了，NONCE + CRC32应该够用
		int bestCount = 0;
		byte[] bestHeader = null;
		for (ToIntMap.Entry<byte[]> entry : headers.selfEntrySet()) {
			if (entry.value > bestCount) {
				bestCount = entry.value;
				bestHeader = entry.key;
			}
		}

		return bestHeader;
	}
}
