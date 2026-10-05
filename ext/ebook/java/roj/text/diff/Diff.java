package roj.text.diff;

import org.intellij.lang.annotations.MagicConstant;
import roj.config.ValueEmitter;
import roj.io.CorruptedInputException;
import roj.io.IOUtil;
import roj.io.XDataInput;
import roj.io.source.ByteSource;
import roj.io.source.Source;
import roj.reflect.Unsafe;
import roj.text.FastCharset;
import roj.util.ArrayUtil;
import roj.util.ByteList;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 文本差异对象
 * @author Roj234
 * @since 2024/3/5 2:49
 */
public class Diff {
	private static List<Patch> parsePatches(Source in, XDataInput patch, int threshold) throws IOException {
		List<Patch> patches = new ArrayList<>();
		int outputSize = patch.readIntLE();
		int wrote = 0;
		int inPos = 0;

		while (wrote < outputSize) {
			int copyLen = patch.readIntLE();
			int diffLen = patch.readIntLE();
			int patchLen = patch.readIntLE();
			int skipLen = patch.readIntLE();

			int thisWrote = copyLen + diffLen + patchLen;
			wrote += thisWrote;
			if (wrote > outputSize) throw new CorruptedInputException("invalid patch");

			if (copyLen > 0) {
				var chunk = IOUtil.getSharedByteBuf();
				in.readFully(chunk, copyLen);

				patches.add(new Copy(inPos/2, copyLen/2, readUTF16LE(chunk.list, 0, copyLen)));
				inPos += copyLen;
			}

			if (diffLen > 0) {
				byte[] deltaBytes = new byte[diffLen];
				patch.readFully(deltaBytes); // delta bytes
				byte[] inputBytes = new byte[diffLen];
				in.readFully(inputBytes); // original bytes

				byte[] outputBytes = new byte[diffLen];
				for (int i = 0; i < diffLen; i++) {
					byte delta = deltaBytes[i];
					outputBytes[i] = (byte) toNormal(toPositive(inputBytes[i]) - delta);
				}

				int appendStart = 0;
				int i = 0;
				while (i < diffLen) {
					int runStart = i;
					while (i + 1 < diffLen && deltaBytes[i] == 0 && deltaBytes[i+1] == 0) {
						i += 2;
					}

					int runLen = i - runStart;
					if (runLen > threshold) {
						if (runStart > appendStart) {
							patches.add(new Append(readUTF16LE(outputBytes, appendStart, runStart - appendStart)));
						}
						appendStart = runStart + runLen;
						int charStart = (inPos + runStart) / 2;
						patches.add(new Copy(charStart, runLen / 2, readUTF16LE(inputBytes, runStart, runLen)));
					}

					i += 2;
					// else: short run, treat as part of changed (outputBytes)
				}
				if (appendStart < diffLen) {
					patches.add(new Append(readUTF16LE(outputBytes, appendStart, diffLen - appendStart)));
				}

				inPos += diffLen;
			}

			if (patchLen > 0) {
				patches.add(new Append(readUTF16LE(patch.readBytes(patchLen), 0, patchLen)));
			}

			// skipLen 可以为负: bsdiff 的匹配是全局的(后缀数组), 下一个匹配块可能引用
			// 原文中更靠前的位置(文本块被移动/复制)。解析层只负责忠实回放, 乱序与重叠
			// 统一交给 generateDiff 的锚点选择(加权LIS)处理。
			in.skip(skipLen);
			inPos += skipLen;
		}

		if (wrote != outputSize) throw new CorruptedInputException("invalid patch");
		return patches;
	}

	public static char[] readUTF16LE(byte[] b, int off, int len) {
		var buf = ArrayUtil.newUninitializedCharArray(len / 2);
		FastCharset.UTF16LE().fastDecode(b, Unsafe.ARRAY_BYTE_BASE_OFFSET, off, off + len, buf, 0, len / 2);
		return buf;
	}

	/**
	 * Parses the patch byte stream into Patch objects (character-aligned), then converts
	 * them to Diff objects using the original text.
	 * This is the main function for the requested conversion: patch bytes -> Patches -> Diffs.
	 * The 'in' must provide the original bytes in UTF-16LE encoding.
	 *
	 * @param original the original text as char[] (used for generateDiff)
	 * @param in the Source providing the original bytes (advanced during parsing)
	 * @param patch the MyDataInput providing the patch bytes
	 * @return the list of Diff objects
	 * @throws IOException if reading or skipping fails
	 * @throws CorruptedInputException if format is invalid
	 * @apiNote 生成 patch 的 BsDiff 必须 {@code setAlignment(1)}, 保证所有长度按 char(2 字节)对齐,
	 *          否则奇数字节偏移会把一个 char 劈成两半, 解析出的文本会错位
	 */
	public static List<Diff> parseToDiffs(char[] original, Source in, XDataInput patch) throws IOException {
		List<Patch> patches = parsePatches(in, patch, 8);
		return generateDiff(original, patches.toArray(new Patch[0]));
	}

	// (b & 0xFF) ^ 0x80
	private static int toPositive(byte b) { return b + 128; }
	private static int toNormal(int b) { return b - 128; }

	// 定义补丁类型
	sealed interface Patch { }
	static final class Copy implements Patch {
		int offset, length;
		char[] data;

		Copy(int offset, int length) {
			this.offset = offset;
			this.length = length;
		}
		Copy(int offset, int length, char[] data) {
			this.offset = offset;
			this.length = length;
			this.data = data;
		}

		@Override
		public String toString() {return "copy { "+(offset+","+length)+(true ? "" : new String(data))+" }";}
	}
	static final class Append implements Patch {
		char[] data;
		Append(char[] data) {this.data = data;}

		@Override
		public String toString() {return "append { "+new String(data)+" }";}
	}

	// 定义差异类型
	public static final byte EQUAL = 0, REPLACE = 1, INSERT = 2, DELETE = 3;
	public static final String[] TYPES = {"EQUAL","REPLACE","INSERT","DELETE"};

	final byte type;
	final int[] ranges;

	public Diff(@MagicConstant(intValues = {EQUAL, REPLACE, INSERT, DELETE}) byte type, int originalStart, int originalEnd, int modifiedStart, int modifiedEnd) {
		this.type = type;
		this.ranges = new int[]{ originalStart, originalEnd, modifiedStart, modifiedEnd };
	}

	@MagicConstant(intValues = {EQUAL, REPLACE, INSERT, DELETE})
	public byte getType() {return type;}
	public int[] getRanges() {return ranges;}

	public void accept(ValueEmitter cv) {
		cv.emitMap(3);
		cv.emitKey("type");
		cv.emit(TYPES[type]);
		cv.emitKey("originalRange");
		cv.emitList(2);
		cv.emit(ranges[0]);
		cv.emit(ranges[1]);
		cv.pop();
		cv.emitKey("modifiedRange");
		cv.emitList(2);
		cv.emit(ranges[2]);
		cv.emit(ranges[3]);
		cv.pop();
	}

	public void acceptSchemaless(ValueEmitter cv) {
		cv.emitMap(3);
		cv.emitKey(0);
		cv.emit(TYPES[type]);
		cv.emitKey(1);
		cv.emitList(2);
		cv.emit(ranges[0]);
		cv.emit(ranges[1]);
		cv.pop();
		cv.emitKey(2);
		cv.emitList(2);
		cv.emit(ranges[2]);
		cv.emit(ranges[3]);
		cv.pop();
	}

	@Override
	public String toString() {return TYPES[type]+"{["+ranges[0]+", "+ranges[1]+"] => ["+ranges[2]+", "+ranges[3]+"]}";}

	/**
	 * 文本 diff 一站式入口: 直接比较两个字符串, 返回可渲染的 Diff 序列。
	 */
	public static List<Diff> diff(String original, String modified) throws IOException {
		byte[] ob = original.getBytes(StandardCharsets.UTF_16LE);
		byte[] mb = modified.getBytes(StandardCharsets.UTF_16LE);

		BsDiff bs = new BsDiff();
		bs.setLeft(ob);
		bs.setAlignment(1); // 按 char(2 字节)对齐, 见 parseToDiffs 的说明

		var patch = new ByteList();
		bs.makePatch(mb, patch);

		return parseToDiffs(original.toCharArray(), new ByteSource(ob), patch);
	}

	public static void main(String[] args) throws Exception {
		var original = "快速棕毛狐狸了懒狗";
		// 常规(单调)场景: [REPLACE 快速=>敏捷的] EQUAL 棕毛狐狸 [INSERT 跃过] EQUAL 了懒 [DELETE 狗]
		System.out.println(generateDiff(original.toCharArray(), new Patch[]{
				new Append("敏捷的".toCharArray()),
				new Copy(2, 4),
				new Append("跃过".toCharArray()),
				new Copy(6, 2)
		}));
		// 负偏移场景: 新文本把尾部的"懒狗"移到了开头, Copy 块在原文中的位置发生倒退(交叉)。
		// 加权LIS会选择覆盖更大的 [快速棕毛狐狸了] 作为锚点, 前面的"懒狗"退化为 INSERT
		System.out.println(generateDiff(original.toCharArray(), new Patch[]{
				new Copy(7, 2),   // 懒狗
				new Copy(0, 7),   // 快速棕毛狐狸了
				new Append("!".toCharArray())
		}));
		// 完整管线(含块移动)
		System.out.println(diff("白日依山尽，黄河入海流", "黄河入海流。白日依山尽！"));
	}

	/**
	 * 把 bsdiff 的补丁段序列转成可渲染的单调 Diff 序列。
	 *
	 * bsdiff 通过后缀数组在原文<b>任意位置</b>找匹配, 因此 Copy 块在原文中的位置可以
	 * "倒退"甚至相互重叠(对应 patch 流里的负 skipLen)——传统双栏对齐模型无法直接表达。
	 * 这里的做法是把所有 Copy 块当作候选锚点, 用<b>加权最长上升子序列</b>选出总覆盖最长、
	 * 在原文中互不交叉的锚点链, 再沿锚点链把间隙输出为 DELETE/INSERT/REPLACE。
	 * 落选的块(被移动/复制的内容)自动落入间隙, 按新增内容渲染;
	 * 结果保证: 双侧区间都单调递增、完整覆盖、互不重叠, 可直接驱动前端双栏视图。
	 */
	public static List<Diff> generateDiff(char[] original, Patch[] patches) {
		// 1. 收集所有引用原文的块(候选锚点), 它们在修改后文本中天然按序且不重叠
		List<int[]> refs = new ArrayList<>();
		int modPos = 0;
		for (Patch patch : patches) {
			if (patch instanceof Append a) {
				modPos += a.data.length;
			} else if (patch instanceof Copy c) {
				if (c.length > 0) refs.add(new int[]{c.offset, c.offset + c.length, modPos, modPos + c.length});
				modPos += c.length;
			}
		}
		int totalMod = modPos;

		// 2. 选锚: 总覆盖最长的非交叉子序列
		List<int[]> anchors = selectAnchors(refs);

		// 3. 沿锚点链走一遍, 间隙 -> DELETE / INSERT / REPLACE
		List<Diff> diffs = new ArrayList<>();
		int origCursor = 0, modCursor = 0;
		for (int[] a : anchors) {
			emitGap(diffs, origCursor, a[0], modCursor, a[2]);
			diffs.add(new Diff(EQUAL, a[0], a[1], a[2], a[3]));
			origCursor = a[1];
			modCursor = a[3];
		}
		emitGap(diffs, origCursor, original.length, modCursor, totalMod);
		return diffs;
	}

	/** 加权LIS: refs 按修改后位置有序, 要求在原文中也不交叉(oEnd[j] <= oStart[i]), 权重为块长。O(n^2), 段数不多时足够 */
	private static List<int[]> selectAnchors(List<int[]> refs) {
		int n = refs.size();
		List<int[]> anchors = new ArrayList<>(n);
		if (n == 0) return anchors;

		long[] best = new long[n];
		int[] parent = new int[n];
		int bestIdx = 0;
		for (int i = 0; i < n; i++) {
			int[] ri = refs.get(i);
			long w = ri[1] - ri[0];
			best[i] = w;
			parent[i] = -1;
			for (int j = 0; j < i; j++) {
				int[] rj = refs.get(j);
				if (rj[1] <= ri[0] && best[j] + w > best[i]) {
					best[i] = best[j] + w;
					parent[i] = j;
				}
			}
			if (best[i] > best[bestIdx]) bestIdx = i;
		}

		for (int i = bestIdx; i >= 0; i = parent[i]) anchors.add(refs.get(i));
		Collections.reverse(anchors);
		return anchors;
	}

	private static void emitGap(List<Diff> out, int oS, int oE, int mS, int mE) {
		boolean hasO = oS < oE, hasM = mS < mE;
		if (hasO && hasM) out.add(new Diff(REPLACE, oS, oE, mS, mE));
		else if (hasO) out.add(new Diff(DELETE, oS, oE, mS, mS));
		else if (hasM) out.add(new Diff(INSERT, oS, oS, mS, mE));
	}

	/**
	 * 渲染优化: 合并被过短 EQUAL(&lt; minLen 字符)隔开的两个修改为单个 REPLACE, 避免高亮闪烁。
	 * 对 REPLACE 区间可再次调用 {@link #diff} 做字符级精化, 实现行内高亮。
	 */
	public static List<Diff> mergeTinyEquals(List<Diff> diffs, int minLen) {
		List<Diff> out = new ArrayList<>(diffs.size());
		for (int i = 0; i < diffs.size(); i++) {
			Diff d = diffs.get(i);
			if (d.type == EQUAL && d.ranges[1] - d.ranges[0] < minLen
						&& !out.isEmpty() && i + 1 < diffs.size()
						&& out.get(out.size()-1).type != EQUAL && diffs.get(i+1).type != EQUAL) {
				Diff p = out.remove(out.size()-1), nx = diffs.get(i+1);
				out.add(new Diff(REPLACE, p.ranges[0], nx.ranges[1], p.ranges[2], nx.ranges[3]));
				i++; // next 已被合并
				continue;
			}
			out.add(d);
		}
		return out;
	}
}