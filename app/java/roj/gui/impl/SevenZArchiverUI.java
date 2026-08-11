/*
 * Created by JFormDesigner on Tue Nov 28 01:30:28 CST 2023
 */

package roj.gui.impl;

import roj.archive.sevenz.util.SevenZArchiver;
import roj.archive.xz.LZMA2Options;
import roj.archive.xz.LZMA2ParallelEncoder;
import roj.concurrent.TaskGroup;
import roj.concurrent.TaskPool;
import roj.ecc.ECFile;
import roj.gui.CMBoxValue;
import roj.gui.GuiProgressBar;
import roj.gui.GuiUtil;
import roj.gui.OnChangeHelper;
import roj.io.IOUtil;
import roj.io.source.CompositeSource;
import roj.io.source.FileSource;
import roj.math.MathUtils;
import roj.text.CharList;
import roj.text.TextUtil;
import roj.text.logging.LogHelper;
import roj.util.*;
import roj.util.function.Flow;

import javax.swing.*;
import javax.swing.event.ChangeListener;
import java.awt.*;
import java.awt.event.ActionListener;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.List;

/**
 * @author Roj234
 */
public class SevenZArchiverUI extends JFrame {
	public static void main(String[] args) throws Exception {
		GuiUtil.systemLaf();
		SevenZArchiverUI f = new SevenZArchiverUI();

		f.pack();
		f.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
		f.setVisible(true);
	}

	private final LZMA2Options options = new LZMA2Options();
	private final OnChangeHelper helper;
	private final ActionListener beginListener;

	enum ParallelOptimizer {
		BETTER_SIZE("体积优化", SevenZArchiver.ChunkOrganizer.GREEDY_FILL, true),
		BEST_SIZE("基于内容的分块", SevenZArchiver.ChunkOrganizer.CONTEXT_AWARE, true),
		SEVENZ("7-zip默认", SevenZArchiver.ChunkOrganizer.DEFAULT, true),
		BEST_SPEED("速度优化", SevenZArchiver.ChunkOrganizer.BEST_SPEED, true),
		DISABLED("禁用（可能爆内存）", SevenZArchiver.ChunkOrganizer.DEFAULT, false),
		;

		final String displayName;
		final SevenZArchiver.ChunkOrganizer organizer;
		final boolean useStreamParallel;

		ParallelOptimizer(String displayName, SevenZArchiver.ChunkOrganizer organizer, boolean useStreamParallel) {
			this.displayName = displayName;
			this.organizer = organizer;
			this.useStreamParallel = useStreamParallel;
		}

		@Override
		public String toString() {
			return displayName;
		}
	}

	private void begin() throws Exception {
		List<File> input = Flow.of(TextUtil.split(uiInput.getText(), File.pathSeparatorChar)).map(File::new).toList();
		if (uiOutput.getText().isEmpty()) {
			File file = GuiUtil.fileSaveTo("保存到何方?", "新建 7z 压缩文件.7z", this);
			if (file == null) return;
			uiOutput.setText(file.getAbsolutePath());
		}

		File out = new File(uiOutput.getText());
		if (out.isDirectory() && !out.getName().contains("."))
			out = new File(out, input.get(0).getName() + ".7z");

		boolean ok = true;
		try {
			if (!out.isFile() && (!out.createNewFile() || !out.delete())) ok = false;
		} catch (Exception e) {
			ok = false;
		}

		if (!ok) {
			JOptionPane.showMessageDialog(this, out.getAbsolutePath()+"\n请注意，输出路径包括文件名称和扩展名", "路径不合法", JOptionPane.ERROR_MESSAGE);
			return;
		}

		SevenZArchiver archiver = new SevenZArchiver();
		archiver.inputDirectories = input;
		archiver.outputDirectory = out.getParentFile();
		archiver.outputFilename = out.getName();

		var options = this.options.clone(); // 避免压缩中途修改UI导致问题
		configureArchiverFromUI(archiver, options);

		uiLog.setText("正在统计文件，请稍等\n");
		if (!archiver.prepare()) {
			uiLog.append("压缩文件无需更新\n");
			archiver.interrupt();
			return;
		}

		StringBuilder warnings = new StringBuilder();

		FastFailException warnings1 = archiver.warnings;
		if (warnings1 != null) {
			warnings1.printStackTrace();
			warnings.append("列目录发生异常，请在控制台查看\n");
		}

		// 第二阶段，处理分块
		long totalSize = archiver.getCompressibleSize();
		uiLog.append("compressibleSize="+TextUtil.scaledNumber1024(totalSize)+"\n");

		int threads = ((Number) uiThreads.getValue()).intValue();
		var optimizer = (ParallelOptimizer) uiParallelOptimizer.getSelectedItem();
		LZMA2ParallelEncoder streamParallel = null;

		long solidSize;
		String solidSizeText = uiSolidSize.getText();
		if (solidSizeText.startsWith("*")) solidSizeText = solidSizeText.substring(1);
		if (solidSizeText.isEmpty()) {
			// 目标：让每个块大概是字典的8倍，既保证压缩率，又控制内存
			int dictSize = options.getDictSize();
			long targetBlock = dictSize * 8L;

			// 确保不会因为块太大导致并发度不够 (至少让每个线程分到2个块)
			if (totalSize / targetBlock < threads * 2) {
				targetBlock = totalSize / (threads * 2);
			}

			// 确保块不小于字典大小 (除非文件本身就很小)
			targetBlock = Math.max(targetBlock, dictSize);

			solidSize = MathUtils.clamp(targetBlock, LZMA2Options.ASYNC_BLOCK_SIZE_MIN, LZMA2Options.ASYNC_BLOCK_SIZE_MAX);
		} else {
			solidSize = (long) TextUtil.unscaledNumber1024(solidSizeText);
		}

		uiSolidSize.setText("*".concat(TextUtil.scaledNumber1024(solidSize)));

		archiver.threads = threads;
		archiver.solidSize = solidSize;
		archiver.chunkOrganizer = optimizer.organizer;

		if (optimizer.useStreamParallel) {
			LZMA2Options parallelOpt = optimizer.organizer.useAltOptions() ? options.clone() : options;
			archiver.streamParallelOptions = parallelOpt;

			int blockSize = 0; // auto
			boolean noContext = false;

			streamParallel = new LZMA2ParallelEncoder(parallelOpt, blockSize, noContext);

			parallelOpt.enableParallel(streamParallel);
		}

		// 执行分块任务
		archiver.organize();

		// 计算内存用量
		String memoryLimitText = uiMemoryLimit.getText();
		long memoryLimit = memoryLimitText.isEmpty() ? Long.MAX_VALUE : (long) TextUtil.unscaledNumber1024(memoryLimitText);

		int parallelTaskCount = archiver.getParallelTaskCount();
		if (parallelTaskCount > 0) {
			long parallelTaskUsagePerThread = archiver.solidSize + (options.getEncoderMemoryUsage() + 256L) * 1024;
			long parallelMemoryUsage = Math.min(parallelTaskCount, threads) * parallelTaskUsagePerThread;

			if (parallelMemoryUsage > memoryLimit) {
				warnings.append("阶段一（并行小块）内存峰值超出限制：").append(TextUtil.scaledNumber1024(parallelMemoryUsage)).append('\n');
			}

			if (parallelTaskCount < threads - 1) {
				warnings.append("阶段一未充分利用处理器：").append(parallelTaskCount).append('\n');
			}
			uiLog.append("parallelTaskCount="+parallelTaskCount+"\n");
		}

		TaskPool pool2;
		int serialTaskCountMax = archiver.getSerialTaskCountMax();
		if (streamParallel != null && serialTaskCountMax > 0) {
			long serialTaskUsagePerThread = streamParallel.getAsyncCompressorMemoryUsage();
			long serialMemoryUsage = Math.min(serialTaskCountMax, threads) * serialTaskUsagePerThread + streamParallel.getEncodeBufferCapacity();
			if (serialMemoryUsage > memoryLimit) {
				warnings.append("阶段二（串行大块）内存峰值超出限制：").append(TextUtil.scaledNumber1024(serialMemoryUsage)).append('\n');
			}

			if (serialTaskCountMax < threads - 1) {
				warnings.append("阶段二未充分利用处理器：").append(serialTaskCountMax).append('\n');
			}

			pool2 = TaskPool.newFixed(threads, "StreamParallel-worker-");
			streamParallel.setExecutionProfile(new MemoryLimit(memoryLimit), pool2, threads);
			uiLog.append("serialTaskCountMax="+serialTaskCountMax+"\n");
		} else {
			// 如果实际上没有用到流并行
			if (optimizer.useStreamParallel)
				archiver.streamParallelOptions.disableParallel();
			streamParallel = null;
			pool2 = null;
		}

		ECFile ef = null;
		if (bRecoveryRecord.isSelected()) {
			int value = (int) iRecoveryRecord.getValue();
			int burstSize = (int) MathUtils.clamp(totalSize * value / 1000f, 4096, 1048576);
			while (true) {
				try {
					ef = new ECFile(value * 1000, burstSize, 0);
					System.out.println("burstLen="+burstSize);
					break;
				} catch (Exception e) {
					burstSize >>= 1;
					if (burstSize < 4096) {
						warnings.append(e.getMessage());
						break;
					}
				}
			}
		}

		if (warnings.length() > 0 &&
				JOptionPane.showConfirmDialog(this, warnings.append("\n\n继续吗？").toString()) != JOptionPane.YES_OPTION) {
			archiver.interrupt();
			return;
		}

		TaskPool mainPool = TaskPool.newFixed(threads, "Compress-worker-");
		TaskGroup group = mainPool.newGroup();

		ActionListener stopListener = e -> {
			LZMA2ParallelEncoder man = options.getParallelEncoder();
			if (man != null) {
				try {
					man.cancel();
				} catch (IOException ex) {
					ex.printStackTrace();
				}
			}

			archiver.interrupt();
			group.cancel();
			mainPool.shutdownNow();
		};

		uiBegin.setText("中止");
		uiBegin.removeActionListener(beginListener);
		uiBegin.addActionListener(stopListener);

		ECFile fuckJavc = ef;
		File fuckJavacOut = out;
		TaskPool.common().executeUnsafe(() -> {
			mainPool.setRejectPolicy(TaskPool::waitPolicy);
			try (var bar = new GuiProgressBar(uiLog, uiProgress)) {
				archiver.compress(group, bar);
				if (fuckJavc != null) {
					try (var out1 = archiver.splitSize == 0 ? new FileSource(fuckJavacOut) : CompositeSource.dynamic(fuckJavacOut, true);
						 var in = out1.copy()
					) {
						out1.seek(fuckJavacOut.length());
						var tmp = out1;
						if (out1 instanceof CompositeSource cs) {
							cs.next();
							tmp = cs.getCurrentFragment();
						}

						bar.setName("生成校验文件");
						bar.reset();
						bar.setTotal(fuckJavacOut.length());
						fuckJavc.protect(in, tmp, in.length(), group, bar::increment);
						bar.end("已在文件末尾生成纠错数据");
						if (!(out1 instanceof CompositeSource))
							System.out.println(ECFile.readFooter(fuckJavacOut));
					}
				}
			} finally {
				stopListener.actionPerformed(null);
				mainPool.shutdownNow();
				if (pool2 != null) {
					pool2.shutdownNow();
				}
				uiProgress.setValue(10000);
				uiBegin.setText("压缩");
				uiBegin.removeActionListener(stopListener);
				uiBegin.addActionListener(beginListener);
			}
		});
	}

	private void configureArchiverFromUI(SevenZArchiver archiver, LZMA2Options options) {
		//region 预处理
		archiver.updateMode = uiAppendOptions.getSelectedIndex();
		archiver.fastAppendCheck = uiFastCheck.isSelected();
		archiver.pathFormat = uiPathType.getSelectedIndex();
		if (uiSortByFilename.isSelected()) {
			archiver.fileSorter = (f1, f2) -> IOUtil.getExtension(f1.getName()).compareTo(IOUtil.getExtension(f2.getName()));
		}
		//endregion
		//region 属性存储/过滤
		archiver.storeDirectories = bStoreFolder.isSelected();
		archiver.storeModifiedTime = bStoreMT.isSelected();
		archiver.storeCreationTime = bStoreCT.isSelected();
		archiver.storeAccessTime = bStoreAT.isSelected();
		archiver.storeAttributes = bStoreAttr.isSelected();
		archiver.storeSymbolicLinks = bCheckSymbolicLink.isSelected();
		archiver.storeHardLinks = bCheckHardLink.isSelected();
		archiver.filterByArchiveAttribute = bCompressArchiveOnly.isSelected();
		archiver.clearArchiveAttribute = bClearArchive.isSelected();
		archiver.storeSecurity = bStoreNTSec.isSelected();
		//endregion

		archiver.splitSize = uiSplitSize.getText().isEmpty() ? 0 : (long) TextUtil.unscaledNumber1024(uiSplitSize.getText());
		archiver.options = options;
		archiver.useFilter = uiBCJ.isSelected();
		archiver.useBCJ2 = uiBCJ2.isSelected();
		archiver.compressHeader = uiCompressHeader.isSelected();

		if (uiCrypt.isSelected()) {
			archiver.encryptionPassword = uiPassword.getText();
			archiver.encryptionPower = ((Number) uiCyclePower.getValue()).intValue();
			archiver.encryptionSaltLength = ((Number) uiSaltLength.getValue()).intValue();
			archiver.encryptFileName = uiCryptHeader.isSelected();
		}

		archiver.keepOldArchive = uiKeepArchive.isSelected();
	}

	public SevenZArchiverUI() {
		initComponents();
		helper = new OnChangeHelper(this);

		DefaultComboBoxModel<Object> md;

		// region LZMA2Options
		md = new DefaultComboBoxModel<>();
		md.addElement(new CMBoxValue("Hash4", LZMA2Options.MF_HC4));
		md.addElement(new CMBoxValue("Binary4", LZMA2Options.MF_BT4));
		md.addElement(new CMBoxValue("Hash5", LZMA2Options.MF_HC5));
		md.addElement(new CMBoxValue("Binary5", LZMA2Options.MF_BT5));
		uiMatchFinder.setModel(Helpers.cast(md));

		md = new DefaultComboBoxModel<>();
		md.addElement(new CMBoxValue("仅存储", 0));

		md.addElement(new CMBoxValue("1-极速压缩", 1));
		md.addElement(new CMBoxValue("2", 2));
		md.addElement(new CMBoxValue("3-快速压缩", 3));
		md.addElement(new CMBoxValue("4", 4));
		md.addElement(new CMBoxValue("5-标准压缩", 5));
		md.addElement(new CMBoxValue("6", 6));
		md.addElement(new CMBoxValue("7-最大压缩", 7));
		md.addElement(new CMBoxValue("8", 8));
		md.addElement(new CMBoxValue("9-极限压缩", 9));
		uiPreset.setModel(Helpers.cast(md));
		uiPreset.addActionListener(e -> {
			int level = ((CMBoxValue) uiPreset.getSelectedItem()).value;
			options.setPreset(level);
			uiBCJ2.setSelected(level == 9);
			syncToUI(true);
		});
		uiPreset.setSelectedIndex(5);

		ChangeListener cl1 = e -> {
			try {
				options.setLcLp(((Number) uiLc.getValue()).intValue(), ((Number) uiLp.getValue()).intValue());
			} catch (Exception ex) {
				uiLc.setValue(options.getLc());
				uiLp.setValue(options.getLp());
			}
		};
		uiLc.addChangeListener(cl1);
		uiLp.addChangeListener(cl1);
		uiPb.addChangeListener(e -> options.setPb(((Number) uiPb.getValue()).intValue()));
		helper.addEventListener(uiDictSize, field -> {
			try {
				options.setDictSize(fromDigital(field.getText()));
				updateMemoryUsage();
			} catch (Exception e) {
				JOptionPane.showMessageDialog(this, e.getMessage(), "输入错误", JOptionPane.WARNING_MESSAGE);
			}
		});
		uiNiceLen.addChangeListener(e -> options.setNiceLen(((Number) uiNiceLen.getValue()).intValue()));
		uiDepthLimit.setModel(new SpinnerListModel() { // hack createEditor()
			private int val;
			@Override
			public Object getValue() { return val == 0 ? "自动" : val; }
			@Override
			public void setValue(Object value) {
				String s = value.toString();
				int v = s.equals("自动") ? 0 : Integer.parseInt(s);
				if (v < 0) v = 0;
				else if (v > 1000) v = 1000;

				if (val != v) {
					val = v;
					fireStateChanged();
				}
			}
			@Override
			public Object getNextValue() { return val < 1000 ? val+1 : 1000; }
			@Override
			public Object getPreviousValue() { return val > 0 ? val-1 : 0; }
		});
		uiDepthLimit.addChangeListener(e -> {
			Object value = uiDepthLimit.getValue();
			options.setDepthLimit(value instanceof Number ? ((Number) value).intValue() : 0);
		});
		uiMatchFinder.addActionListener(e -> {
			options.setMatchFinder(uiMatchFinder.getSelectedIndex());
			updateMemoryUsage();
		});
		uiFastLZMA.addActionListener(e -> {
			options.setMode(uiFastLZMA.isSelected() ? LZMA2Options.MODE_FAST : LZMA2Options.MODE_NORMAL);
			updateMemoryUsage();
		});
		uiFindBestProp.addActionListener(e -> {
			File[] files = GuiUtil.filesLoadFrom("选择一些文件来测试最好的LcLpPb及压缩率", this, JFileChooser.FILES_ONLY);
			if (files == null) return;

			uiFindBestProp.setEnabled(false);
			uiLog.setText("开始测试,时间依据您除了LcLpPb的LZMA设定而变化,请耐心等待\n" +
				"请不要在测试过程中修改LZMA设定,这会导致结果不准确");

			TaskPool.common().execute(() -> {
				int threads = ((Number) uiThreads.getValue()).intValue();

				String text = uiMemoryLimit.getText();
				double memoryLimit = text.isEmpty() ? Long.MAX_VALUE : TextUtil.unscaledNumber1024(text) / 1024;

				double perThreadUsageKb = options.getEncoderMemoryUsage() + 256;

				threads = (int) Math.min(threads, Math.floor(memoryLimit / perThreadUsageKb));

				uiLog.append("实际能创建的线程:"+threads+'\n');

				TaskPool pool = TaskPool.newFixed(threads, "LcLpTest-worker-");
				try {
					ByteList data = new ByteList();
					for (File file : files) {
						try (FileInputStream in = new FileInputStream(file)) {
							data.readStreamFully(in);
						} catch (IOException ex) {
							ex.printStackTrace();
						}
					}

					byte[] b = data.toByteArray();
					data.list = null;

					int minSize = options.findBestProps(b, pool);
					uiLc.setValue(options.getLc());
					uiLp.setValue(options.getLp());
					uiPb.setValue(options.getPb());
					uiLog.setText("测试结束,LcLpPb已经填入输入框\n" +
						"使用它们的压缩大小为"+toDigital(minSize)+" ("+TextUtil.toFixed((double)minSize/data.length() * 100, 2)+"%)");
				} finally {
					uiFindBestProp.setEnabled(true);
					pool.shutdownNow();
				}
			});
		});
		// endregion
		// region Crypt
		uiCrypt.addActionListener(e -> {
			boolean b = uiCrypt.isSelected();
			uiPassword.setEnabled(b);
			uiCyclePower.setEnabled(b);
			uiCryptHeader.setEnabled(b);
			uiSaltLength.setEnabled(b);
			if (!b) uiPassword.setText("");
		});
		// endregion
		// region
		md = new DefaultComboBoxModel<>();
		md.addElement("完全替换");
		md.addElement("添加并替换文件");
		md.addElement("更新并添加文件");
		md.addElement("只更新已存在的文件");
		md.addElement("同步压缩包内容");
		md.addElement("计算压缩包差异");
		md.addElement("增量版本控制(iVCS)");
		uiAppendOptions.setModel(Helpers.cast(md));
		md = new DefaultComboBoxModel<>();
		md.addElement("相对路径"); // RELATIVE_PATH
		md.addElement("公共相对路径"); // RELATIVE_COMMON_PATH
		md.addElement("绝对路径"); // ABSOLUTE_PATH
		md.setSelectedItem("公共相对路径");
		uiPathType.setModel(Helpers.cast(md));
		// endregion
		// region
		uiThreads.setModel(new SpinnerNumberModel(Runtime.getRuntime().availableProcessors(), 1,256,1));

		long directMemory = 67108864;
		String s = System.getProperty("sun.nio.MaxDirectMemorySize");
		if (s == null || s.isEmpty() || s.equals("-1")) {
			// -XX:MaxDirectMemorySize not given, take default
			directMemory = Runtime.getRuntime().maxMemory();
		} else {
			long l = Long.parseLong(s);
			if (l > -1)
				directMemory = l;
		}
		uiMemoryLimit.setText(toDigital(directMemory));

		uiBCJ.addActionListener(e -> uiBCJ2.setEnabled(uiBCJ.isSelected()));

		uiLog.setText("""
				【使用帮助与注意事项】
				
				💡 提示：将鼠标悬停在界面各选项上，可查看更详细的 Tooltip 说明。
				
				1. 并行优化策略（v4.0 新增）
				   下拉框的选择将决定核心算法，直接影响 CPU 利用率和最终体积：
				   · 其它优化模式：支持高速并行解压（推荐），但由于多线程并发的不确定性，每次生成的压缩包哈希值可能不同（内容是完好的）。
				   · "7-zip默认"：你几乎没有选择它的理由，因为它们都是兼容的LZMA2，这个算法并行解压能力差。
				   · "基于内容的分块"：尝试智能提升压缩率，但预处理是单线程且计算量巨大（O(n^2)）。文件数量极多时请勿使用，否则会卡死。
				
				2. 兼容性警告
				   软链接、硬链接、NTFS 安全标识符（ACL）和恢复记录的存储格式为私有扩展，仅支持使用 RojLib GUI 完整还原。
				
				3. 性能与资源
				   · 线程策略：软件会尽可能利用线程以追求速度，这可能会轻微牺牲压缩率。
				   · 内存管理：所有大缓冲区都使用堆外内存，不受 GC 限制。如果物理内存耗尽，进程可能会被系统杀死。请谨慎忽略内存警告。
				
				⚠️ 数据安全
				   默认逻辑为“直接覆盖”：如果输出路径已存在同名文件，将直接被新文件替换，而不是追加内容。""");

		ActionListener[] tip = new ActionListener[3];
		tip[2] = e -> {
			updateSolidSize();
			updateMemoryUsage();
		};

		uiSolidSize.setToolTipText("自动：* 非固实：0 固实：-1 可以使用单位");
		helper.addEventListener(uiSolidSize, e -> updateMemoryUsage());
		uiParallelOptimizer.addActionListener(tip[2]);
		var md1 = new DefaultComboBoxModel<ParallelOptimizer>();
		for (ParallelOptimizer value : ParallelOptimizer.values()) {
			md1.addElement(value);
		}
		uiParallelOptimizer.setModel(Helpers.cast(md1));
		// endregion
		GuiUtil.dropFilePath(uiInput, null, false);
		GuiUtil.dropFilePath(uiOutput, null, false);
		uiBegin.addActionListener(beginListener = e -> {
			try {
				begin();
			} catch (Exception ex) {
				ex.printStackTrace();
				CharList sb = IOUtil.getSharedCharBuf();
				LogHelper.printError(ex, sb);
				JOptionPane.showMessageDialog(this, sb.toString(), "无法打开压缩包", JOptionPane.ERROR_MESSAGE);
			}
		});
		uiHideComplicate.addActionListener(e -> {
			boolean v = uiHideComplicate.isSelected();
			Component[] c = {uiPathType,uiKeepArchive,bStoreAT,bStoreAttr,bStoreCT,bStoreFolder,bStoreMT,uiCyclePower,uiSaltLength,uiFastLZMA,uiFastCheck,uiCompressHeader,uiBCJ,uiBCJ2,uiMatchFinder,uiLc,uiLp,uiPb,uiDepthLimit,bCheckSymbolicLink,bCheckHardLink,bCompressArchiveOnly,bClearArchive};
			for (Component cc : c) {
				cc.setVisible(!v);
			}
		});
		bRecoveryRecord.addActionListener(e -> iRecoveryRecord.setEnabled(bRecoveryRecord.isSelected()));
		bCheckSymbolicLink.addActionListener(e -> {
			if (bCheckSymbolicLink.isSelected())JOptionPane.showMessageDialog(
					this,
					"<html><body style='width: 400px;'>"
							+ "<b>软硬链接仅支持配套UnarchiverUI解压：</b><br><br>"
							+ "• <font color='red'>7-zip格式：</font><br>"
							+ "&nbsp;&nbsp;- 仅支持Windows系统<br>"
							+ "&nbsp;&nbsp;- 使用raw reparse point stream<br>"
							+ "&nbsp;&nbsp;- 体积更大<br><br>"
							+ "• <font color='green'>本项目自定义格式：</font><br>"
							+ "&nbsp;&nbsp;1. 符号链接：设置REPARSE_POINT属性<br>"
							+ "&nbsp;&nbsp;&nbsp;&nbsp;→ 直接存储链接路径<br>"
							+ "&nbsp;&nbsp;2. 硬链接：额外添加NORMAL属性<br>"
							+ "&nbsp;&nbsp;&nbsp;&nbsp;→ 指向压缩包内Entry的绝对路径<br>"
							+ "&nbsp;&nbsp;3. 硬链接必须位于最后一个字块，以保证解压时依赖顺序正确"
							+ "</body></html>",
					"你需要知道的",
					JOptionPane.INFORMATION_MESSAGE
			);
		});
		bStoreNTSec.addActionListener(e -> {
			if (bStoreNTSec.isSelected()) {
				JOptionPane.showMessageDialog(this, "除UnarchiverUI外，其他压缩软件都会忽略这些属性"+(JVM.isRoot()?"":"\n建议以管理员身份压缩以完整保留SACL"), "你需要知道的", JOptionPane.INFORMATION_MESSAGE);
			}
		});

		if (!LZMA2Options.isNativeAccelerateAvailable())
			uiNativeAccel.setEnabled(false);
		else {
			uiNativeAccel.addActionListener(e -> {
				JOptionPane.showMessageDialog(this, "作者C++技术力不行，压缩后可能没法解压（数据错误）\n请自行决定是否使用");
				options.setNativeAccelerate(uiNativeAccel.isSelected());
			});
		}
	}

	private void syncToUI(boolean isPreset) {
		helper.setEnabled(false);
		try {
			uiLc.setValue(options.getLc());
			uiLp.setValue(options.getLp());
			uiPb.setValue(options.getPb());
			uiDictSize.setText(toDigital(options.getDictSize()));
			uiNiceLen.setValue(options.getNiceLen());
			uiDepthLimit.setValue(options.getDepthLimit());
			uiMatchFinder.setSelectedIndex(options.getMatchFinder());
			uiFastLZMA.setSelected(options.getMode() == LZMA2Options.MODE_FAST);
			updateSolidSize();
			updateMemoryUsage();
		} finally {
			helper.setEnabled(true);
		}
	}

	private void updateSolidSize() {
		if (uiSolidSize.getText().startsWith("*")) {
			long solidSize;
			var optimizer = (ParallelOptimizer) uiParallelOptimizer.getSelectedItem();
			if (optimizer == ParallelOptimizer.SEVENZ) {
				solidSize = options.getDictSize() * 256L;
				uiSolidSize.setText("*".concat(TextUtil.scaledNumber1024(Math.min(solidSize, 16 * 1024 * 1024 * 1024L))));
			} else {
				uiSolidSize.setText("*");
			}
		}
	}

	private void updateMemoryUsage() {
		uiMemoryUsage.setText("内存/线程:"+toDigital(options.getEncoderMemoryUsage() * 1024L));
	}

	private static String toDigital(long size) { return TextUtil.scaledNumber1024(size); }
	private int fromDigital(String text) {
		double v = text.isEmpty() ? 0 : TextUtil.unscaledNumber1024(text);
		if (v > Integer.MAX_VALUE) throw new FastFailException(text+"超出了整型范围！");
		return (int) v;
	}

	private void initComponents() {
		// JFormDesigner - Component initialization - DO NOT MODIFY  //GEN-BEGIN:initComponents  @formatter:off
        var label4 = new JLabel();
        var label15 = new JLabel();
        uiInput = new JTextField();
        uiOutput = new JTextField();
        uiKeepArchive = new JCheckBox();
        uiPathType = new JComboBox<>();
        uiBegin = new JButton();
        uiHideComplicate = new JCheckBox();
        uiNativeAccel = new JCheckBox();
        uiProgress = new JProgressBar();
        var separator1 = new JSeparator();
        var label14 = new JLabel();
        uiSplitSize = new JTextField();
        var label3 = new JLabel();
        uiSolidSize = new JTextField();
        var separator5 = new JSeparator();
        uiThreads = new JSpinner();
        var label2 = new JLabel();
        uiParallelOptimizer = new JComboBox<>();
        uiMemoryLimit = new JTextField();
        var label5 = new JLabel();
        var separator2 = new JSeparator();
        uiPreset = new JComboBox<>();
        uiFindBestProp = new JButton();
        var label7 = new JLabel();
        var label8 = new JLabel();
        var label9 = new JLabel();
        var label10 = new JLabel();
        var label11 = new JLabel();
        uiLc = new JSpinner();
        uiLp = new JSpinner();
        uiPb = new JSpinner();
        uiDictSize = new JTextField();
        uiNiceLen = new JSpinner();
        uiDepthLimit = new JSpinner();
        uiMatchFinder = new JComboBox<>();
        uiMemoryUsage = new JLabel();
        uiFastLZMA = new JCheckBox();
        var separator3 = new JSeparator();
        uiCrypt = new JCheckBox();
        uiCryptHeader = new JCheckBox();
        uiPassword = new JTextField();
        var label12 = new JLabel();
        var label13 = new JLabel();
        uiCyclePower = new JSpinner();
        uiSaltLength = new JSpinner();
        var separator4 = new JSeparator();
        uiCompressHeader = new JCheckBox();
        uiFastCheck = new JCheckBox();
        uiAppendOptions = new JComboBox<>();
        var label6 = new JLabel();
        bStoreMT = new JCheckBox();
        bStoreCT = new JCheckBox();
        bStoreAT = new JCheckBox();
        bStoreAttr = new JCheckBox();
        bStoreFolder = new JCheckBox();
        bStoreNTSec = new JCheckBox();
        var separator6 = new JSeparator();
        uiBCJ = new JCheckBox();
        uiBCJ2 = new JCheckBox();
        var scrollPane1 = new JScrollPane();
        uiLog = new JTextArea();
        var label1 = new JLabel();
        uiSortByFilename = new JCheckBox();
        bCheckSymbolicLink = new JCheckBox();
        bCheckHardLink = new JCheckBox();
        bCompressArchiveOnly = new JCheckBox();
        bClearArchive = new JCheckBox();
        bRecoveryRecord = new JCheckBox();
        iRecoveryRecord = new JSpinner();
        var label16 = new JLabel();

        //======== this ========
        setTitle("Roj234 SevenZ Archiver 4.1");
        var contentPane = getContentPane();
        contentPane.setLayout(null);

        //---- label4 ----
        label4.setText("\u6e90\u3010\u4ee5\u5206\u53f7\u9694\u5f00\u7684\u4efb\u610f\u4e2a\u6587\u4ef6\u6216\u76ee\u5f55\u3011");
        contentPane.add(label4);
        label4.setBounds(new Rectangle(new Point(10, 0), label4.getPreferredSize()));

        //---- label15 ----
        label15.setText("\u6c47\u3010\u76ee\u6807\u6587\u4ef6\u7684\u8def\u5f84\u3011");
        contentPane.add(label15);
        label15.setBounds(new Rectangle(new Point(10, 40), label15.getPreferredSize()));

        //---- uiInput ----
        uiInput.setFont(uiInput.getFont().deriveFont(uiInput.getFont().getStyle() | Font.BOLD, uiInput.getFont().getSize() + 4f));
        contentPane.add(uiInput);
        uiInput.setBounds(15, 15, 335, uiInput.getPreferredSize().height);

        //---- uiOutput ----
        uiOutput.setFont(uiOutput.getFont().deriveFont(uiOutput.getFont().getSize() + 4f));
        contentPane.add(uiOutput);
        uiOutput.setBounds(15, 60, 335, uiOutput.getPreferredSize().height);

        //---- uiKeepArchive ----
        uiKeepArchive.setText("\u4fdd\u7559\u539f\u538b\u7f29\u5305");
        contentPane.add(uiKeepArchive);
        uiKeepArchive.setBounds(new Rectangle(new Point(135, 40), uiKeepArchive.getPreferredSize()));
        contentPane.add(uiPathType);
        uiPathType.setBounds(240, 40, 110, uiPathType.getPreferredSize().height);

        //---- uiBegin ----
        uiBegin.setText("\u538b\u7f29");
        uiBegin.setFont(uiBegin.getFont().deriveFont(uiBegin.getFont().getSize() + 10f));
        uiBegin.setMargin(new Insets(2, 2, 2, 2));
        contentPane.add(uiBegin);
        uiBegin.setBounds(355, 17, 60, 60);

        //---- uiHideComplicate ----
        uiHideComplicate.setText("\u6211\u662f\u65b0\u624b");
        contentPane.add(uiHideComplicate);
        uiHideComplicate.setBounds(new Rectangle(new Point(425, 20), uiHideComplicate.getPreferredSize()));

        //---- uiNativeAccel ----
        uiNativeAccel.setText("C\u8bed\u8a00\u52a0\u901f");
        contentPane.add(uiNativeAccel);
        uiNativeAccel.setBounds(new Rectangle(new Point(425, 50), uiNativeAccel.getPreferredSize()));

        //---- uiProgress ----
        uiProgress.setMaximum(10000);
        contentPane.add(uiProgress);
        uiProgress.setBounds(5, 480, 500, uiProgress.getPreferredSize().height);
        contentPane.add(separator1);
        separator1.setBounds(0, 90, 490, 2);

        //---- label14 ----
        label14.setText("\u5206\u5377\u5927\u5c0f");
        contentPane.add(label14);
        label14.setBounds(new Rectangle(new Point(5, 103), label14.getPreferredSize()));
        contentPane.add(uiSplitSize);
        uiSplitSize.setBounds(55, 100, 90, uiSplitSize.getPreferredSize().height);

        //---- label3 ----
        label3.setText("\u56fa\u5b9e\u5927\u5c0f");
        contentPane.add(label3);
        label3.setBounds(new Rectangle(new Point(5, 128), label3.getPreferredSize()));

        //---- uiSolidSize ----
        uiSolidSize.setText("*");
        contentPane.add(uiSolidSize);
        uiSolidSize.setBounds(55, 125, 90, uiSolidSize.getPreferredSize().height);

        //---- separator5 ----
        separator5.setOrientation(SwingConstants.VERTICAL);
        contentPane.add(separator5);
        separator5.setBounds(130, 175, separator5.getPreferredSize().width, 155);
        contentPane.add(uiThreads);
        uiThreads.setBounds(225, 100, 70, uiThreads.getPreferredSize().height);

        //---- label2 ----
        label2.setText("\u7ebf\u7a0b\u9650\u5236");
        contentPane.add(label2);
        label2.setBounds(new Rectangle(new Point(174, 103), label2.getPreferredSize()));
        contentPane.add(uiParallelOptimizer);
        uiParallelOptimizer.setBounds(55, 150, 170, uiParallelOptimizer.getPreferredSize().height);
        contentPane.add(uiMemoryLimit);
        uiMemoryLimit.setBounds(225, 125, 70, uiMemoryLimit.getPreferredSize().height);

        //---- label5 ----
        label5.setText("\u5185\u5b58\u9650\u5236");
        contentPane.add(label5);
        label5.setBounds(new Rectangle(new Point(174, 128), label5.getPreferredSize()));
        contentPane.add(separator2);
        separator2.setBounds(0, 175, 495, 2);
        contentPane.add(uiPreset);
        uiPreset.setBounds(140, 180, 110, uiPreset.getPreferredSize().height);

        //---- uiFindBestProp ----
        uiFindBestProp.setText("\u9884\u6d4b");
        uiFindBestProp.setMargin(new Insets(2, 4, 2, 4));
        contentPane.add(uiFindBestProp);
        uiFindBestProp.setBounds(new Rectangle(new Point(259, 180), uiFindBestProp.getPreferredSize()));

        //---- label7 ----
        label7.setText("Lc Lp Pb");
        contentPane.add(label7);
        label7.setBounds(new Rectangle(new Point(140, 207), label7.getPreferredSize()));

        //---- label8 ----
        label8.setText("\u5b57\u5178\u5927\u5c0f");
        contentPane.add(label8);
        label8.setBounds(new Rectangle(new Point(140, 233), label8.getPreferredSize()));

        //---- label9 ----
        label9.setText("\u5355\u8bcd\u5927\u5c0f");
        contentPane.add(label9);
        label9.setBounds(new Rectangle(new Point(140, 258), label9.getPreferredSize()));

        //---- label10 ----
        label10.setText("\u641c\u7d22\u6df1\u5ea6");
        contentPane.add(label10);
        label10.setBounds(new Rectangle(new Point(140, 283), label10.getPreferredSize()));

        //---- label11 ----
        label11.setText("\u5339\u914d\u7b97\u6cd5");
        contentPane.add(label11);
        label11.setBounds(new Rectangle(new Point(140, 308), label11.getPreferredSize()));

        //---- uiLc ----
        uiLc.setModel(new SpinnerNumberModel(0, 0, 4, 1));
        contentPane.add(uiLc);
        uiLc.setBounds(new Rectangle(new Point(200, 205), uiLc.getPreferredSize()));

        //---- uiLp ----
        uiLp.setModel(new SpinnerNumberModel(0, 0, 4, 1));
        contentPane.add(uiLp);
        uiLp.setBounds(new Rectangle(new Point(233, 205), uiLp.getPreferredSize()));

        //---- uiPb ----
        uiPb.setModel(new SpinnerNumberModel(0, 0, 4, 1));
        contentPane.add(uiPb);
        uiPb.setBounds(new Rectangle(new Point(266, 205), uiPb.getPreferredSize()));
        contentPane.add(uiDictSize);
        uiDictSize.setBounds(200, 230, 95, uiDictSize.getPreferredSize().height);

        //---- uiNiceLen ----
        uiNiceLen.setModel(new SpinnerNumberModel(8, 8, 273, 1));
        contentPane.add(uiNiceLen);
        uiNiceLen.setBounds(200, 255, 95, uiNiceLen.getPreferredSize().height);
        contentPane.add(uiDepthLimit);
        uiDepthLimit.setBounds(200, 280, 95, uiDepthLimit.getPreferredSize().height);
        contentPane.add(uiMatchFinder);
        uiMatchFinder.setBounds(200, 305, 95, uiMatchFinder.getPreferredSize().height);

        //---- uiMemoryUsage ----
        uiMemoryUsage.setText("0");
        contentPane.add(uiMemoryUsage);
        uiMemoryUsage.setBounds(320, 315, 170, uiMemoryUsage.getPreferredSize().height);

        //---- uiFastLZMA ----
        uiFastLZMA.setText("\u5feb\u901fLZMA");
        contentPane.add(uiFastLZMA);
        uiFastLZMA.setBounds(new Rectangle(new Point(315, 290), uiFastLZMA.getPreferredSize()));

        //---- separator3 ----
        separator3.setOrientation(SwingConstants.VERTICAL);
        contentPane.add(separator3);
        separator3.setBounds(310, 175, separator3.getPreferredSize().width, 155);

        //---- uiCrypt ----
        uiCrypt.setText("\u52a0\u5bc6");
        contentPane.add(uiCrypt);
        uiCrypt.setBounds(new Rectangle(new Point(316, 180), uiCrypt.getPreferredSize()));

        //---- uiCryptHeader ----
        uiCryptHeader.setText("\u52a0\u5bc6\u6587\u4ef6\u540d");
        uiCryptHeader.setEnabled(false);
        contentPane.add(uiCryptHeader);
        uiCryptHeader.setBounds(new Rectangle(new Point(365, 180), uiCryptHeader.getPreferredSize()));

        //---- uiPassword ----
        uiPassword.setEnabled(false);
        contentPane.add(uiPassword);
        uiPassword.setBounds(320, 205, 150, uiPassword.getPreferredSize().height);

        //---- label12 ----
        label12.setText("\u52a0\u5bc6\u5f3a\u5ea6");
        contentPane.add(label12);
        label12.setBounds(new Rectangle(new Point(320, 233), label12.getPreferredSize()));

        //---- label13 ----
        label13.setText("\u76d0\u957f\u5ea6");
        contentPane.add(label13);
        label13.setBounds(new Rectangle(new Point(320, 258), label13.getPreferredSize()));

        //---- uiCyclePower ----
        uiCyclePower.setModel(new SpinnerNumberModel(19, 16, 24, 1));
        uiCyclePower.setEnabled(false);
        contentPane.add(uiCyclePower);
        uiCyclePower.setBounds(395, 230, 75, uiCyclePower.getPreferredSize().height);

        //---- uiSaltLength ----
        uiSaltLength.setModel(new SpinnerNumberModel(16, 0, 16, 1));
        uiSaltLength.setEnabled(false);
        contentPane.add(uiSaltLength);
        uiSaltLength.setBounds(360, 255, 110, uiSaltLength.getPreferredSize().height);
        contentPane.add(separator4);
        separator4.setBounds(310, 285, 185, 2);

        //---- uiCompressHeader ----
        uiCompressHeader.setText("\u538b\u7f29\u5143\u6570\u636e");
        uiCompressHeader.setSelected(true);
        contentPane.add(uiCompressHeader);
        uiCompressHeader.setBounds(new Rectangle(new Point(5, 180), uiCompressHeader.getPreferredSize()));

        //---- uiFastCheck ----
        uiFastCheck.setText("\u5feb\u901f\u4fee\u6539\u68c0\u6d4b(CRC+MT)");
        uiFastCheck.setSelected(true);
        contentPane.add(uiFastCheck);
        uiFastCheck.setBounds(new Rectangle(new Point(300, 125), uiFastCheck.getPreferredSize()));
        contentPane.add(uiAppendOptions);
        uiAppendOptions.setBounds(5, 205, 120, uiAppendOptions.getPreferredSize().height);

        //---- label6 ----
        label6.setText("\u5728\u5143\u6570\u636e\u4e2d\u4fdd\u5b58");
        contentPane.add(label6);
        label6.setBounds(new Rectangle(new Point(5, 230), label6.getPreferredSize()));

        //---- bStoreMT ----
        bStoreMT.setText("\u4fee\u6539\u65f6\u95f4");
        bStoreMT.setSelected(true);
        contentPane.add(bStoreMT);
        bStoreMT.setBounds(new Rectangle(new Point(5, 245), bStoreMT.getPreferredSize()));

        //---- bStoreCT ----
        bStoreCT.setText("\u521b\u5efa\u65f6\u95f4");
        contentPane.add(bStoreCT);
        bStoreCT.setBounds(new Rectangle(new Point(5, 265), bStoreCT.getPreferredSize()));

        //---- bStoreAT ----
        bStoreAT.setText("\u8bbf\u95ee\u65f6\u95f4");
        contentPane.add(bStoreAT);
        bStoreAT.setBounds(new Rectangle(new Point(5, 285), bStoreAT.getPreferredSize()));

        //---- bStoreAttr ----
        bStoreAttr.setText("\u6587\u4ef6\u6743\u9650 (DOS)");
        contentPane.add(bStoreAttr);
        bStoreAttr.setBounds(new Rectangle(new Point(5, 305), bStoreAttr.getPreferredSize()));

        //---- bStoreFolder ----
        bStoreFolder.setText("\u6587\u4ef6\u5939");
        bStoreFolder.setSelected(true);
        contentPane.add(bStoreFolder);
        bStoreFolder.setBounds(new Rectangle(new Point(5, 325), bStoreFolder.getPreferredSize()));

        //---- bStoreNTSec ----
        bStoreNTSec.setText("NT Secure");
        contentPane.add(bStoreNTSec);
        bStoreNTSec.setBounds(new Rectangle(new Point(495, 160), bStoreNTSec.getPreferredSize()));
        contentPane.add(separator6);
        separator6.setBounds(130, 330, 180, 2);

        //---- uiBCJ ----
        uiBCJ.setText("\u4f7f\u7528\u9884\u5904\u7406\u5668");
        uiBCJ.setSelected(true);
        contentPane.add(uiBCJ);
        uiBCJ.setBounds(new Rectangle(new Point(300, 100), uiBCJ.getPreferredSize()));

        //---- uiBCJ2 ----
        uiBCJ2.setText("BCJ2");
        contentPane.add(uiBCJ2);
        uiBCJ2.setBounds(new Rectangle(new Point(395, 100), uiBCJ2.getPreferredSize()));

        //======== scrollPane1 ========
        {
            scrollPane1.setViewportView(uiLog);
        }
        contentPane.add(scrollPane1);
        scrollPane1.setBounds(5, 355, 495, 120);

        //---- label1 ----
        label1.setText("\u65e5\u5fd7");
        contentPane.add(label1);
        label1.setBounds(new Rectangle(new Point(80, 340), label1.getPreferredSize()));

        //---- uiSortByFilename ----
        uiSortByFilename.setText("\u6309\u6269\u5c55\u540d\u6392\u5e8f");
        contentPane.add(uiSortByFilename);
        uiSortByFilename.setBounds(new Rectangle(new Point(395, 290), uiSortByFilename.getPreferredSize()));

        //---- bCheckSymbolicLink ----
        bCheckSymbolicLink.setText("\u7b26\u53f7\u94fe\u63a5");
        contentPane.add(bCheckSymbolicLink);
        bCheckSymbolicLink.setBounds(new Rectangle(new Point(495, 180), bCheckSymbolicLink.getPreferredSize()));

        //---- bCheckHardLink ----
        bCheckHardLink.setText("\u786c\u94fe\u63a5");
        contentPane.add(bCheckHardLink);
        bCheckHardLink.setBounds(new Rectangle(new Point(495, 200), bCheckHardLink.getPreferredSize()));

        //---- bCompressArchiveOnly ----
        bCompressArchiveOnly.setText("\u4ec5\u538b\u7f29\u5f52\u6863\u7684\u6587\u4ef6");
        contentPane.add(bCompressArchiveOnly);
        bCompressArchiveOnly.setBounds(new Rectangle(new Point(495, 220), bCompressArchiveOnly.getPreferredSize()));

        //---- bClearArchive ----
        bClearArchive.setText("\u5b8c\u6210\u540e\u53bb\u9664\u5f52\u6863\u5c5e\u6027");
        contentPane.add(bClearArchive);
        bClearArchive.setBounds(new Rectangle(new Point(495, 240), bClearArchive.getPreferredSize()));

        //---- bRecoveryRecord ----
        bRecoveryRecord.setText("\u6dfb\u52a0\u6062\u590d\u8bb0\u5f55");
        contentPane.add(bRecoveryRecord);
        bRecoveryRecord.setBounds(new Rectangle(new Point(495, 260), bRecoveryRecord.getPreferredSize()));

        //---- iRecoveryRecord ----
        iRecoveryRecord.setModel(new SpinnerNumberModel(3, 1, 30, 1));
        iRecoveryRecord.setEnabled(false);
        contentPane.add(iRecoveryRecord);
        iRecoveryRecord.setBounds(530, 280, 75, 20);

        //---- label16 ----
        label16.setText("\u5e76\u884c\u4f18\u5316");
        contentPane.add(label16);
        label16.setBounds(new Rectangle(new Point(5, 153), label16.getPreferredSize()));

        contentPane.setPreferredSize(new Dimension(635, 495));
        pack();
        setLocationRelativeTo(getOwner());
		// JFormDesigner - End of component initialization  //GEN-END:initComponents  @formatter:on
	}

	// JFormDesigner - Variables declaration - DO NOT MODIFY  //GEN-BEGIN:variables  @formatter:off
    private JTextField uiInput;
    private JTextField uiOutput;
    private JCheckBox uiKeepArchive;
    private JComboBox<String> uiPathType;
    private JButton uiBegin;
    private JCheckBox uiHideComplicate;
    private JCheckBox uiNativeAccel;
    private JProgressBar uiProgress;
    private JTextField uiSplitSize;
    private JTextField uiSolidSize;
    private JSpinner uiThreads;
    private JComboBox<ParallelOptimizer> uiParallelOptimizer;
    private JTextField uiMemoryLimit;
    private JComboBox<CMBoxValue> uiPreset;
    private JButton uiFindBestProp;
    private JSpinner uiLc;
    private JSpinner uiLp;
    private JSpinner uiPb;
    private JTextField uiDictSize;
    private JSpinner uiNiceLen;
    private JSpinner uiDepthLimit;
    private JComboBox<CMBoxValue> uiMatchFinder;
    private JLabel uiMemoryUsage;
    private JCheckBox uiFastLZMA;
    private JCheckBox uiCrypt;
    private JCheckBox uiCryptHeader;
    private JTextField uiPassword;
    private JSpinner uiCyclePower;
    private JSpinner uiSaltLength;
    private JCheckBox uiCompressHeader;
    private JCheckBox uiFastCheck;
    private JComboBox<String> uiAppendOptions;
    private JCheckBox bStoreMT;
    private JCheckBox bStoreCT;
    private JCheckBox bStoreAT;
    private JCheckBox bStoreAttr;
    private JCheckBox bStoreFolder;
    private JCheckBox bStoreNTSec;
    private JCheckBox uiBCJ;
    private JCheckBox uiBCJ2;
    private JTextArea uiLog;
    private JCheckBox uiSortByFilename;
    private JCheckBox bCheckSymbolicLink;
    private JCheckBox bCheckHardLink;
    private JCheckBox bCompressArchiveOnly;
    private JCheckBox bClearArchive;
    private JCheckBox bRecoveryRecord;
    private JSpinner iRecoveryRecord;
	// JFormDesigner - End of variables declaration  //GEN-END:variables  @formatter:on
}