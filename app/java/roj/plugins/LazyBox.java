package roj.plugins;

import roj.archive.sevenz.*;
import roj.archive.zip.ZipEditor;
import roj.archive.zip.ZipEntry;
import roj.archive.zip.ZipFile;
import roj.asm.ClassNode;
import roj.asmx.Context;
import roj.asmx.injector.CodeWeaver;
import roj.asmx.injector.WeaveException;
import roj.collect.CollectionX;
import roj.collect.HashMap;
import roj.collect.IntMap;
import roj.concurrent.TaskGroup;
import roj.concurrent.TaskPool;
import roj.ecc.ECFile;
import roj.io.IOUtil;
import roj.plugin.Plugin;
import roj.plugin.SimplePlugin;
import roj.text.CharList;
import roj.ui.*;
import roj.util.ArrayCache;
import roj.util.DynByteBuf;
import roj.util.FastFailException;
import roj.util.Helpers;

import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.StandardOpenOption;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static roj.ui.CommandNode.argument;
import static roj.ui.CommandNode.literal;

/**
 * @author Roj234
 * @since 2024/5/15 14:10
 */
@SimplePlugin(id = "lazyBox", version = "1.4", desc = """
	高仿瑞士军刀/doge
	
	[文件工具]
	复制文件(夹):  cp[copy] <src> <dst>
	移动文件(夹):  mv[move] <src> <dst>
	删除文件(夹):  rd[rmdir] <path>
	读取修改时间:  mtime <file>
	设置修改时间:  mtime <file> <time>
	修改时间与创建时间的差值: writecost <file>
	批量正则替换:  batchrpl <path> <regexp> <replacement>
	
	[压缩包工具]
	多线程7z验证: 7zverify <file>
	7z差异计算:   7zdiff <file1> <file2>
	zip增量更新:  zipupdate <file>
	删除文件夹:   archive_del_folder <file>
	
	[网页工具]
	多线程下载文件: curl <url> <saveTo> [threads]
	
	[Java工具]
	Cpk压缩: cpk <input> [output]
	Nixim注入: nixim <injector> <reference> [output]
	""")
public class LazyBox extends Plugin {
	@Override
	protected void onEnable() throws Exception {
		registerCommand(literal("7zverify").then(argument("path", Argument.file()).executes(this::qzVerify)));
		registerCommand(literal("7zdiff").then(argument("file1", Argument.file()).then(argument("file2", Argument.file()).executes(this::qzDiff))));

		registerCommand(literal("zipupdateexe")
				.then(argument("自解压模板", Argument.file())
						.then(argument("压缩包", Argument.file())
								.executes(this::updateExe))));
		registerCommand(literal("zipupdate").then(argument("path", Argument.file()).executes(this::zipUpdate)));
		registerCommand(literal("ziprmfolder").then(argument("file", Argument.file()).executes(ctx -> {
			try (var za = new ZipEditor(ctx.argument("file", File.class))) {
				for (ZipEntry ze : za.entries()) {
					String name = ze.getName();
					if (name.endsWith("/")) {
						if (ze.getSize() == 0) {
							za.put(name, null);
						} else {
							getLogger().warn("'文件夹'{}的大小不为零: {}", name, ze.getSize());

							za.put(name, null);
							za.put(name.substring(0, name.length()-1), DynByteBuf.wrap(za.get(ze)));
						}
					}
				}
				za.save();
			}
		})));

		registerCommand(literal("reccgen").then(argument("file", Argument.file()).then(argument("ratio", Argument.real(0.001, 0.5)).executes(ctx -> {
			var exceptingRatio = ctx.argument("ratio", Double.class);
			var file = ctx.argument("file", File.class);

			int burstLen = (int) Math.min(file.length(), 1048576);

			var prev = ECFile.readFooter(file);
			if (prev != null) {
				System.out.println("这个文件看起来已经追加过纠错码了，按Y继续，其它键中止");
				char c = TUI.key(null, new CharList());
				if (c != 'y' && c != 'Y') return;
			}

			var rf = new ECFile((int)(exceptingRatio * 100000), burstLen, file.length());

			try (var bar = new EasyProgressBar("生成纠错码")) {
				bar.setTotal(file.length());
				rf.protect(file, bar::increment);
				bar.end("生成完毕");
			} catch (IOException e) {
				getLogger().warn("生成失败", e);
			}
		}))));
		registerCommand(literal("reccfix").then(argument("file", Argument.file()).executes(ctx -> {
			var file = ctx.argument("file", File.class);

			var rf = ECFile.readFooter(file);
			if (rf == null) {
				System.out.println("这个文件看起来没有纠错码或损坏过于严重");
				return;
			}

			try (var bar = new EasyProgressBar("校验&纠错")) {
				bar.setTotal(file.length());
				ECFile.RepairResult result = rf.repair(file, true, bar::increment);

				bar.end(result.toString());
				if (result.bytesFixed != 0) {
					System.out.println("纠正了一些错误，不过，纠错码中的错误并不会被纠正，建议你删除并重新生成纠错码");
				}
			} catch (Exception e) {
				getLogger().warn("校验失败", e);
			}
		})));
		registerCommand(literal("reccdel").then(argument("file", Argument.file()).executes(ctx -> {
			var file = ctx.argument("file", File.class);

			try {
				ECFile.unprotect(file);
				System.out.println("纠错码已删除");
			} catch (Exception e) {
				getLogger().warn("校验失败", e);
			}
		})));

		Command nixim = ctx -> {
			var nx = new CodeWeaver();
			File src = ctx.argument("注入(Nixim)", File.class);
			if (src.isDirectory()) {
				IOUtil.listFiles(src, file -> {
					if (IOUtil.getExtension(file.getName()).equals("class")) {
						try {
							nx.read(ClassNode.parseSkeleton(IOUtil.read(file)));
						} catch (WeaveException | IOException e) {
							Helpers.athrow(e);
						}
					}
					return false;
				});
			} else {
				if (IOUtil.getExtension(src.getName()).equals("class")) {
					nx.read(ClassNode.parseSkeleton(IOUtil.read(src)));
				} else {
					try (var zf = new ZipFile(src)) {
						for (var ze : zf.entries()) {
							if (IOUtil.getExtension(ze.getName()).equals("class")) {
								nx.read(ClassNode.parseSkeleton(zf.get(ze)));
							}
						}
					}
				}
			}

			src = ctx.argument("源", File.class);
			File dst = ctx.argument("保存至", File.class);
			if (dst == null) dst = IOUtil.addSuffix(src, "-注入");
			IOUtil.copyFile(src, dst);

			try (var archive = new ZipEditor(dst)) {
				for (var entry : nx.registry().entrySet()) {
					String file = entry.getKey().replace('.', '/')+".class";
					InputStream in = archive.getInputStream(file);
					if (in == null) {
						System.err.println("nixim target "+file+" not found");
						continue;
					}

					try {
						var klass = new Context(entry.getKey(), in);
						nx.transform(entry.getKey(), klass);
						archive.put(file, klass::getCompressedShared, true);
					} catch (Exception e) {
						e.printStackTrace();
					} finally {
						in.close();
					}
				}

				archive.save();
			}

			System.out.println("注入完成");
		};
		registerCommand(literal("nixim")
			.then(argument("注入(Nixim)", Argument.file())
				.then(argument("源", Argument.file())
					.executes(nixim)
					.then(argument("保存至", Argument.fileOptional(true)).executes(nixim)))));
	}

	private void zipUpdate(CommandContext ctx) throws IOException {
		var za = new ZipEditor(ctx.argument("path", File.class));

		Collection<String> entryName = CollectionX.mapToView(za.entries(), ZipEntry::getName, ZipEntry::new);
		Map<String, String> fileView = CollectionX.toMap(entryName, x -> x.endsWith("/") ? null : x);

		var update = new Shell("\u001b[96mZUpdate \u001b[97m> ");
		update.register(literal("set").then(argument("name", Argument.suggest(fileView)).then(argument("path", Argument.file()).executes(c -> {
			String out = c.argument("name", String.class);
			File in = c.argument("path", File.class);
			if (out == null || out.endsWith("/")) {
				System.out.println("位置是目录");
				return;
			}

			za.putStream(out, () -> {
				try {
					return new FileInputStream(in);
				} catch (FileNotFoundException e) {
					return Helpers.athrow2(e);
				}
			}, true);
		}))));
		update.register(literal("del").then(argument("name", Argument.oneOf(fileView)).executes(c -> za.put(ctx.argument("name", String.class), null))));
		update.register(literal("reload").executes(c -> za.getPendingUpdates().clear()));
		update.register(literal("save").executes(c -> za.save()));
		update.register(literal("exit").executes(c -> {
			za.close();
			Tty.popHandler();
		}));
		update.onKeyboardInterrupt(() -> update.executeSync("exit"));
		update.sortCommands();
		Tty.pushHandler(update);
	}

	private void qzVerify(CommandContext ctx) {
		File file = ctx.argument("path", File.class);

		EasyProgressBar bar = new EasyProgressBar("验证压缩文件", "B");

		AtomicReference<Throwable> failed = new AtomicReference<>();
		try (SevenZFile archive = new SevenZFile(file)) {
			for (SevenZEntry entry : archive.getEntriesByPresentOrder()) {
				bar.addTotal(entry.getSize());
			}

			TaskGroup monitor = TaskPool.cpu().newGroup();
			archive.parallelDecompress(monitor, (entry, in) -> {
				byte[] arr = ArrayCache.getIOBuffer();
				try {
					while (true) {
						int r = in.read(arr);
						if (r < 0) break;

						if (failed.get() != null) throw new FastFailException("-other thread failed-");
						bar.increment(r);
					}
				} catch (FastFailException e) {
					throw e;
				} catch (Throwable e) {
					monitor.cancel();
					failed.set(e);
					throw new FastFailException("-验证失败-");
				} finally {
					ArrayCache.putArray(arr);
				}
			}, null);

			monitor.await();
		} catch (Exception e) {
			failed.compareAndSet(null, e);
		}

		Throwable exception = failed.getAndSet(null);
		if (exception != null) {
			bar.end("验证失败", Tty.RED);
			exception.printStackTrace();
		} else {
			bar.end("验证成功");
		}
		bar.close();
	}

	private void qzDiff(CommandContext ctx) throws IOException {
		SevenZFile in1 = new SevenZFile(ctx.argument("file1", File.class));
		SevenZFile in2 = new SevenZFile(ctx.argument("file2", File.class));
		HashMap<String, SevenZEntry> remain = in1.getEntries();

		int add = 0, change = 0, del = 0, move = 0;

		IntMap<SevenZEntry> in2_by_crc32 = new IntMap<>();
		HashMap<SevenZEntry, String> in1_should_copy = new HashMap<>(), in2_should_copy = new HashMap<>();
		for (SevenZEntry entry : in2.getEntriesByPresentOrder()) {
			if (entry.isDirectory()) continue;

			SevenZEntry oldEntry = remain.remove(entry.getName());
			if (oldEntry == null) {
				SevenZEntry prev = in2_by_crc32.put(entry.getCrc32(), entry);
				if (prev != null) System.out.println("警告：在"+entry.getCrc32()+"["+entry.getName()+"]上出现了CRC冲突");

				in2_should_copy.put(entry, "add/"+entry.getName());
				add++;
			} else if (oldEntry.getCrc32() != entry.getCrc32()) {
				in1_should_copy.put(oldEntry, "mod_old/"+oldEntry.getName());
				in2_should_copy.put(entry, "mod_new/"+entry.getName());

				change++;
			}
		}

		for (SevenZEntry oldEntry : remain.values()) {
			if (oldEntry.isDirectory()) continue;

			SevenZEntry entry = in2_by_crc32.get(oldEntry.getCrc32());
			if (entry != null) {
				in2_should_copy.remove(entry);

				add--;
				move++;
			} else {
				in1_should_copy.put(oldEntry, "del/"+oldEntry.getName());
				del++;
			}
		}

		if ((add|change|del|move) == 0) {
			System.out.println("\u001b[92m两个压缩包完全相同");
			return;
		}

		System.out.println("\u001b[93m新增\u001b[94m"+add+" \u001b[93m删除\u001b[94m"+del+" \u001b[93m修改\u001b[94m"+change+" \u001b[93m移动\u001b[94m"+move);
		Shell c1 = new Shell("\u001b[96m7zDiff \u001b[97m> ");
		Tty.pushHandler(c1);
		c1.register(literal("save").then(argument("out", Argument.string()).executes(c -> {
			SevenZPacker out = new SevenZPacker(c.argument("out", String.class));
			out.setCodec(new LZMA2(3));

			for (SevenZEntry oldEntry : remain.values()) {
				if (oldEntry.isDirectory()) continue;
				SevenZEntry entry = in2_by_crc32.remove(oldEntry.getCrc32());
				if (entry != null) {
					in2_should_copy.remove(entry);
					out.beginEntry(SevenZEntry.ofNoAttribute("renamed/"+oldEntry.getName()));
					out.write(entry.getName().getBytes(StandardCharsets.UTF_8));
				} else {
					in1_should_copy.put(oldEntry, "del/"+oldEntry.getName());
				}
			}

			out.flush();

			EasyProgressBar bar = new EasyProgressBar("复制块", "块");
			bar.addTotal(in1_should_copy.size()+in2_should_copy.size());

			var monitor = TaskPool.cpu().newGroup();

			copy(monitor, in1, in1_should_copy, out, bar);
			copy(monitor, in2, in2_should_copy, out, bar);

			monitor.await();

			in1.close();
			in2.close();

			out.close();
			bar.end("Diff已保存");

			Tty.popHandler();
		})));
		c1.register(literal("exit").executes(c -> {
			in1.close();
			in2.close();

			Tty.popHandler();
		}));
	}
	private static void copy(TaskGroup monitor, SevenZFile arc, HashMap<SevenZEntry, String> should_copy, SevenZPacker out, EasyProgressBar bar) {
		arc.parallelDecompress(monitor, (entry, in) -> {
			String prefix = should_copy.get(entry);
			if (prefix == null) return;

			try (SevenZWriter w = out.newParallelWriter()) {
				w.beginEntry(SevenZEntry.ofNoAttribute(prefix));
				IOUtil.copyStream(in, w);
				bar.increment(1);
			} catch (Exception e) {
				Helpers.athrow(e);
			}
		});
	}

	private void updateExe(CommandContext ctx) throws IOException {
		File exe = ctx.argument("自解压模板", File.class);
		File zip = ctx.argument("压缩包", File.class);

		long offset = Long.MAX_VALUE;
		try (var zf = new ZipFile(exe, ZipFile.FLAG_Verify |ZipFile.FLAG_ReadCENOnly)) {
			for (ZipEntry entry : zf.entries()) {
				offset = Math.min(offset, entry.startPos());
			}
		}

		var from = FileChannel.open(zip.toPath(), StandardOpenOption.READ);
		var to = FileChannel.open(exe.toPath(), StandardOpenOption.WRITE, StandardOpenOption.READ).position(offset);
		from.transferTo(0, from.size(), to);
		from.close();

		if (to.size() != to.position()) to.truncate(to.position());
		to.close();
	}
}