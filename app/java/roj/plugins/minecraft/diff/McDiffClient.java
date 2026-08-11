package roj.plugins.minecraft.diff;

import roj.archive.sevenz.SevenZEntry;
import roj.archive.sevenz.SevenZFile;
import roj.archive.sevenz.WordBlock;
import roj.collect.HashMap;
import roj.concurrent.TaskPool;
import roj.config.NbtEncoder;
import roj.config.NbtParser;
import roj.crypt.CryptoFactory;
import roj.crypt.KeyType;
import roj.io.CorruptedInputException;
import roj.io.IOUtil;
import roj.io.RegionFile;
import roj.io.XDataInputStream;
import roj.io.source.ByteSource;
import roj.text.TextReader;
import roj.text.TextUtil;
import roj.text.diff.BsDiff;
import roj.ui.EasyProgressBar;
import roj.util.ArrayCache;
import roj.util.ByteList;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.PublicKey;
import java.security.Signature;
import java.util.zip.GZIPOutputStream;

/**
 * @author Roj234
 * @since 2023/12/21 1:31
 */
public class McDiffClient {
	SevenZFile archive;
	HashMap<String, String> renames = new HashMap<>();

	public void apply(File basePath) throws IOException {
		SevenZEntry hashes = archive.getEntry(".vcs|hashes");
		System.out.println("正在验证哈希");
		if (hashes != null) try (XDataInputStream in = new XDataInputStream(archive.getInputStream(hashes))) {
			byte[] tmp = ArrayCache.getIOBuffer();
			while (in.isReadable()) {
				String name = in.readVUIGB();
				File file = new File(basePath, name);
				if (in.readLong() != file.length() || in.readInt() != McDiffServer.computeCrc32(file, tmp)) {
					throw new CorruptedInputException("文件 "+name+" 哈希错误，可能不是这个更新包对应的版本");
				}
			}
			ArrayCache.putArray(tmp);
		}

		var pool = TaskPool.cpu().newGroup();
		var bar = new EasyProgressBar("应用更新包");
		bar.addTotal(archive.getEntriesByPresentOrder().length);
		for (SevenZEntry entry : archive.getEntriesByPresentOrder()) {
			String name = entry.getName();

			if (name.startsWith(".vcs")) {
				if (!name.startsWith(".vcs/")) continue;
				name = name.substring(5);
				File file = new File(basePath, name);

				if (entry.getSize() == 0) {
					if (!file.delete()) {
						System.out.println("删除文件 "+name+" 失败");
					}
				} else {
					File destFile = new File(basePath, renames.get(name));
					if (!file.renameTo(destFile)) {
						System.out.println("移动文件 "+name+" => "+destFile+" 失败");
					}
				}
				bar.increment(1);
			}
		}
		archive.parallelDecompress(pool, (entry, in) -> {
			if (entry.getName().startsWith(".vcs")) return;

			File file = new File(basePath, entry.getName());
			file.getParentFile().mkdirs();

			try {
				switch ((int) entry.getModificationTime()) {
					case 0 -> {
						try (var out = new FileOutputStream(file)) {
							IOUtil.copyStream(in, out);
						}
					}
					case McDiffServer.GZIP -> {
						try (var out = new GZIPOutputStream(new FileOutputStream(file))) {
							IOUtil.copyStream(in, out);
						}
					}
					case McDiffServer.REGION -> {
						RegionFile rf;
						try {
							rf = new RegionFile(file);
						} catch (Exception e) {
							return;
						}

						try (var mdi = new XDataInputStream(in)) {
							// short pos int timestamp int patchLength
							while (mdi.isReadable()) {
								int pos = mdi.readShort();
								int time = mdi.readInt();
								int patch = mdi.readInt();

								try (var dos = rf.getDataOutput(pos)) {
									if (patch == 0) {
										try (var buf = new ByteList.ToStream(dos)) {
											NbtParser.INSTANCE.parse(mdi, new NbtEncoder(buf));
										}
									} else {
										if (!rf.hasData(pos)) throw new CorruptedInputException("diff source missing");

										ByteList xin = IOUtil.getSharedByteBuf().readStreamFully(rf.getInputStream(pos, null));
										BsDiff.patch(new ByteSource(xin), mdi, dos);
									}
								}
								rf.setTimestamp(pos, time);
							}
						} finally {
							IOUtil.closeSilently(rf);
						}
					}
					case McDiffServer.DIFF -> throw new IllegalStateException("not supported yet");
				}
			} catch (IOException e) {
				e.printStackTrace();
			}
			bar.increment(1);
		});
		pool.await();
		bar.end("应用成功");
		archive.close();
	}

	public void load(File arc, boolean skipSignatureCheck) throws IOException, GeneralSecurityException {
		load(new SevenZFile(arc));

		SevenZEntry entry = archive.getEntry(".vcs|signature");
		if (entry == null) {
			if (skipSignatureCheck) return;
			throw new CorruptedInputException("更新包未签名");
		}

		WordBlock[] blocks = archive.getWordBlocks();
		if (entry.block() != blocks[blocks.length-1] || blocks[blocks.length-1].getFileCount() != 1) throw new CorruptedInputException("签名方式不合法");

		verifySign(arc, entry, blocks);
	}
	public void load(SevenZFile arc) throws IOException, GeneralSecurityException {
		archive = arc;

		for (SevenZEntry entry : archive.getEntriesByPresentOrder()) {
			String name = entry.getName();
			if (name.indexOf('\\') >= 0 || name.contains("../")) throw new CorruptedInputException("文件不安全");

			if (name.startsWith(".vcs")) {
				if (!name.startsWith(".vcs/")) continue;

				if (entry.getSize() != 0) {
					InputStream in = archive.getInputStream(entry);
					String rename = new String(IOUtil.read(in), StandardCharsets.UTF_16LE);
					if (rename.indexOf('\\') >= 0 || rename.contains("../")) throw new CorruptedInputException("文件不安全");

					renames.put(entry.getName(), rename);
				}
			}
		}
	}

	private void verifySign(File file, SevenZEntry entry, WordBlock[] blocks) throws IOException, GeneralSecurityException {
		byte[] sign;
		Signature dsa;
		try (TextReader in = new TextReader(archive.getInputStream(entry), StandardCharsets.US_ASCII)) {
			var algorithm = in.readLine();
			dsa = Signature.getInstance(algorithm);
			sign = TextUtil.hex2bytes(in.readLine());

			var publicKey = IOUtil.read(in);
			PublicKey key = (PublicKey) KeyType.getInstance(algorithm).fromPEM(publicKey);
			dsa.initVerify(key);

			System.out.println("正在验证签名，请稍等 "+key);
		}

		long count = blocks[blocks.length - 1].getOffset()-32;
		var hash1 = CryptoFactory.Blake3(32);
		var hash2 = CryptoFactory.SM3();

		byte[] tmp = ArrayCache.getIOBuffer();
		try (InputStream in = new FileInputStream(file)) {
			IOUtil.readFully(in, tmp, 0, 32); // 跳过文件头
			while (count > 0) {
				int r = in.read(tmp);
				if (r < 0) throw new CorruptedInputException("未预料的错误");

				r = (int) Math.min(count, r);
				count -= r;

				hash1.update(tmp, 0, r);
				hash2.update(tmp, 0, r);
			}
		}
		ArrayCache.putArray(tmp);

		ByteList buf = IOUtil.getSharedByteBuf();
		for (SevenZEntry entry1 : archive.getEntriesByPresentOrder()) {
			if (entry1 == entry) continue;

			buf.putChars(entry1.getName());
			if (entry1.getSize() > 0) buf.putLong(entry1.getSize());

			if (buf.wIndex() > 4096) {
				hash1.update(buf);
				buf.rIndex = 0;
				hash2.update(buf);
				buf.clear();
			}
		}

		hash1.update(buf);
		buf.rIndex = 0;
		hash2.update(buf);
		buf.clear();

		buf.clear();
		buf.put(hash1.digest()).put(hash2.digest());

		dsa.update(buf.list, 0, buf.wIndex());
		if (!dsa.verify(sign)) throw new CorruptedInputException("无效的签名");
		System.out.println("签名验证通过");
	}
}