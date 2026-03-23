/*
 * Created by JFormDesigner on Wed Sep 06 02:36:12 CST 2023
 */

package roj.ebook.gui;

import roj.collect.*;
import roj.concurrent.Promise;
import roj.concurrent.TaskPool;
import roj.concurrent.Timer;
import roj.concurrent.TimerTask;
import roj.config.node.IntValue;
import roj.ebook.Chapter;
import roj.ebook.EbookWriter;
import roj.gui.DragReorderHelper;
import roj.gui.GuiUtil;
import roj.gui.OnChangeHelper;
import roj.io.IOUtil;
import roj.text.*;
import roj.text.diff.BsDiff;
import roj.text.logging.LogHelper;
import roj.util.FastFailException;
import roj.util.OperationDone;

import javax.swing.*;
import javax.swing.event.ChangeEvent;
import javax.swing.text.JTextComponent;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import java.awt.*;
import java.awt.event.*;
import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 屎山范例！
 * @author Roj234
 */
public class NovelFrame extends JFrame {
	public static void main(String[] args) {
		GuiUtil.systemLaf();
		var f = new NovelFrame();
		f.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
		f.show();
	}

	private final DragReorderHelper uxDrag;

	private final List<Chapter> chapters;
	private final DefaultTreeModel chaptersTree;

	private final CharList tmp = new CharList();
	public NovelFrame() {
		initComponents();
		OnChangeHelper uxChange = new OnChangeHelper(this);
		uxChange.addRoot(advancedMenu);

		JLabel draggedItem = new JLabel();
		draggedItem.setEnabled(false);
		getContentPane().add(draggedItem);
		uxDrag = new DragReorderHelper(uiChapters, draggedItem);

		GuiUtil.dropFilePath(uiNovelPath, (e) -> read_novel(null), false);

		btnFixEnter.addActionListener(e -> {
			Int2IntMap length = new Int2IntMap();
			int linenum = 0;
			List<String> lines = LineReader.getAllLines(novel_in, false);
			for (String line : lines) {
				if (line.isEmpty()) continue;
				length.getEntryOrCreate(line.length()).value++;
				linenum++;
			}
			ArrayList<Int2IntMap.Entry> entries = new ArrayList<>(length.selfEntrySet());
			entries.sort((o1, o2) -> Integer.compare(o1.value, o2.value));
			System.out.println(linenum);
			Int2IntMap.Entry pop = entries.pop();
			System.out.println(pop);
			novel_in.clear();
			linenum = 0;
			for (int i = 0; i < lines.size();) {
				String line = lines.get(i++);
				while (line.length() == pop.getIntKey()) {
					System.out.println("merge "+line);
					novel_in.append(line);
					line = lines.get(i++);
					linenum++;
				}
				novel_in.append(line).append('\n');
			}

			errout.setText("长度："+pop.getIntKey()+"\n合并："+linenum);
		});
		btnRemoveHalfLine.addActionListener(e -> {
			novel_in.replace("\n\n", "\n");
			errout.setText("");
			sample(novel_in);
		});

		uiPresetRegexs.setModel(uxPresetRegexs);
		uxChange.addEventListener(presetRegexpInp, this::presetRegexChanged);
		uxChange.addEventListener(uiRegex, this::regexChanged);

		PresetRegexp regexp = new PresetRegexp();
		regexp.name = "";
		regexp.chapterId = 1;
		regexp.chapterName = 2;
		regexp.from = "〔(.+?)〕(.+)$";
		regexp.to = "第$1章 $2";
		uxPresetRegexs.addElement(regexp);

		presetRegexChanged(presetRegexpInp);
		regexChanged(uiRegex);
		btnMakeChapter.setEnabled(false);

		btnRegexMatch.addActionListener(e -> {
			IntList tmp2 = new IntList();
			tmp.clear();
			int matches = novel_in.preg_match_callback(novel_regexp, m -> {
				tmp.append(m.start()).append("@").append(Tokenizer.escape(m.group())).append('\n');
				tmp2.add(m.start());
			});

			if (matches > 0) {
				int[] keys = tmp2.getRawArray();
				String[] vals = new String[matches];
				int arrOff = 0;

				int ln = 1;
				int i = 0;
				out:
				while (true) {
					int j = TextUtil.gNextCRLF(novel_in, i);
					while (j > keys[arrOff]) {
						vals[arrOff] = "L"+ln+":"+(keys[arrOff]-i+1);
						if (++arrOff == vals.length) break out;
					}

					i = j;
					ln++;
				}

				keys[0] = 0;
				tmp.preg_replace_callback(Pattern.compile("^(\\d+)@", Pattern.MULTILINE), m -> vals[keys[0]++]+" ("+m.group(1)+") ");
			}

			tmp.append("共找到").append(matches).append("个匹配");
			errout.setText(tmp.toString());
		});
		btnRegexRpl.addActionListener(e -> {
			int prevHash = novel_in.hashCode();
			int count = 0;
			IntValue rpl = new IntValue(0);
			do {
				novel_in.preg_replace_callback(novel_regexp, m -> {
					tmp.clear();
					tmp.append(uiRegexRplTo.getText());
					for (int j = 0; j <= m.groupCount(); j++) {
						String str = m.group(j);
						tmp.replace("$"+j, str);
					}

					rpl.value++;
					return tmp;
				});

				count++;
			} while (uiRepeatRpl.isSelected() && prevHash != (prevHash = novel_in.hashCode()));

			tmp.append("共循环").append(count).append("次,进行了").append(rpl.value).append("次替换");
			errout.setText(tmp.toString());
		});

		chapters = new ArrayList<>();
		chaptersTree = (DefaultTreeModel) uiChapters.getModel();

		ChapterDblClickHelper uxChapterClick = new ChapterDblClickHelper();
		uiChapters.addMouseListener(uxChapterClick);

		btnDelByLen.addActionListener(e -> {
			String s = JOptionPane.showInputDialog(this, "长度 (以!开始来小于等于)");
			boolean lss = s.startsWith("!");
			if (lss) s = s.substring(1);
			int i = Integer.parseInt(s);
			List<Chapter> children = ((Chapter) chaptersTree.getRoot()).children;
			for (int j = children.size() - 1; j >= 0; j--) {
				Chapter c = children.get(j);
				int len = (c.text == null ? c.end - c.start : c.text.length());
				if (lss ? len <= i : len == i) {
					children.remove(j);
				}
			}
			chaptersTree.reload();
		});

		btnToEpub.addActionListener(this::write_epub);

		btnLegadoImport.addActionListener(e -> {
			initChapters();

			File path = GuiUtil.fileLoadFrom("LegadoNewBee", this, JFileChooser.DIRECTORIES_ONLY);
			if (path == null) return;
			int offset = Integer.parseInt(JOptionPane.showInputDialog("chapter offset", "-1"));
			for (File file : path.listFiles()) {
				String name = file.getName();
				int i = name.indexOf('-');
				int chapter = Integer.parseInt(name.substring(0, i));

				try {
					Chapter chap = chapters.get(chapter + offset);
					System.out.println("chapter "+chap.toString());
					chap.text = new CharList(IOUtil.readUTF(file));
					System.out.println("Data "+chap.text);
				} catch (IOException ex) {
					ex.printStackTrace();
				}
			}
		});

		btnGroup.addActionListener(e -> {
			Chapter root = (Chapter) chaptersTree.getRoot();

			char matcher = JOptionPane.showInputDialog("子项目", "章").charAt(0);
			char upcome = JOptionPane.showInputDialog("父项目", "卷").charAt(0);

			List<Chapter> _ch = root.children;
			int pi = -1;
			int i = 0;
			for (; i < _ch.size(); i++) {
				Chapter chapter =  _ch.get(i);
				if (chapter.type != matcher && pi >= 0) {
					// range [pi+1 ... i)
					List<Chapter> toAdd = _ch.subList(pi + 1, i);
					Chapter p = _ch.get(pi);
					p.children = new ArrayList<>(toAdd);
					for (Chapter ch : toAdd) ch.parent = p;
					toAdd.clear();
					i = pi+1;

					pi = -1;
				}
				if (chapter.type == upcome) {
					pi = i;
				}
			}

			if (pi >= 0) {
				List<Chapter> toAdd = _ch.subList(pi + 1, i);
				Chapter p = _ch.get(pi);
				p.children = new ArrayList<>(toAdd);
				for (Chapter ch : toAdd) ch.parent = p;
				toAdd.clear();
			}

			chaptersTree.setRoot(root);
		});
	}

	private void initChapters() {
		chapters.clear();
		((Chapter) chaptersTree.getRoot()).flat(chapters);
	}

	public Chapter getRootChapter() {return (Chapter) chaptersTree.getRoot();}

	public <T> Promise<T> loadBook(File file, Function<Chapter, T> callback) {
		return Promise.async(TaskPool.common(), promise -> {
			setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
			show();
			addWindowListener(new WindowAdapter() {
				@Override
				public void windowClosing(WindowEvent e) {
					promise.reject(new FastFailException("Window Closed"));
				}
			});
			load(file);
			GuiUtil.removeComponent(btnLoad);
			GuiUtil.removeComponent(uiNovelPath);
			GuiUtil.removeComponent(btnFindNovel);
			for (ActionListener actionListener : btnWrite.getActionListeners()) {
				btnWrite.removeActionListener(actionListener);
			}
			btnWrite.addActionListener(e -> {
				T result = callback.apply(getRootChapter());
				promise.resolve(result);
				dispose();
			});
		});
	}

	private class ChapterDblClickHelper extends MouseAdapter {
		long prevClick;
		Chapter prevId;

		@Override
		public void mousePressed(MouseEvent e) {
			TreePath location = uiChapters.getPathForLocation(e.getX(), e.getY());

			if (location != null) {
				Chapter c = (Chapter) location.getLastPathComponent();

				if (prevId == c && System.currentTimeMillis() - prevClick < 300) {
					assert getOneChapter() == c;

					cpwOutName.setEnabled(true);

					cpwOrigName.setText("在下方修改【"+c.matches +"】");
					cpwOutName.setText(c.customName);
					cpwChapNo.setValue((int) c.index);
					cpwChapName.setText(c.name);
					return;
				}

				focusLost();
				prevId = c;
				prevClick = System.currentTimeMillis();
				errout.setText(c.text != null ? c.text.toString() : novel_in.substring(c.start, c.end));
			} else {
				focusLost();
			}
		}

		final void focusLost() {
			Chapter c = prevId;
			prevId = null;

			if (c != null) {
				if (cpwOutName.isEnabled()) {
					if (!cpwOutName.getText().equals(c.customName)) {
						c.customName = cpwOutName.getText();
						c.hasCustomName = true;
						cpwOrigName.setText("数据已保存");
						chaptersTree.nodeChanged(c);
					}

					cpwOutName.setEnabled(false);
				}
			}
		}
	}

	private static final class PresetRegexp {
		String name;
		int chapterId, chapterName;
		String from, to;

		public String toString() { return name; }
	}
	private final DefaultComboBoxModel<PresetRegexp> uxPresetRegexs = new DefaultComboBoxModel<>();
	private boolean isPresetRegexp;

	private Pattern novel_regexp;
	private int chapterId_group, chapterName_group;

	CharList novel_in = new CharList(), novel_out = new CharList();


	static final BitSet myWhiteSpace = BitSet.from("\r\n\t 　 \uE4C6\uE5E5\uFEFF\u200B");

	// region 功能区-文件读取
	private void select_novel(ActionEvent e) {
		JFileChooser fileChooser = new JFileChooser(uiNovelPath.getText());
		fileChooser.setDialogTitle("选择");
		fileChooser.setFileSelectionMode(JFileChooser.FILES_ONLY);

		int status = fileChooser.showOpenDialog(this);
		//没有选打开按钮结果提示
		if (status == JFileChooser.APPROVE_OPTION) {
			uiNovelPath.setText(fileChooser.getSelectedFile().getAbsolutePath());
		}
	}
	public void load(File file) {
		try (var sr = TextReader.auto(file)) {
			novel_in.clear();
			novel_in.readFully(sr).replace("\r\n", "\n").replace('\r', '\n');
			errout.setText("charset:"+sr.charset()+"\n");
			sample(novel_in);
		} catch (IOException ex) {
			CharList myOut = new CharList();
			LogHelper.printError(ex, myOut);
			errout.setText(myOut.toStringAndFree());
		}

		btnMakeChapter.setEnabled(novel_regexp != null);
		btnAlign.setEnabled(false);
		btnWrite.setEnabled(false);
		btnToEpub.setEnabled(false);
	}
	private void read_novel(ActionEvent e) {load(new File(uiNovelPath.getText()));}
	// endregion
	// region 功能区-校对整理
	private void on_preset_regexp_clicked(ActionEvent e) {
		PresetRegexp item = (PresetRegexp) uiPresetRegexs.getSelectedItem();
		if (item == null) return;

		if (!isPresetRegexp && !item.name.isEmpty()) {
			PresetRegexp prev = uxPresetRegexs.getElementAt(0);
			prev.from = novel_regexp.pattern();
			prev.to = uiRegexRplTo.getText();
			prev.chapterId = chapterId_group;
			prev.chapterName = chapterName_group;
		}

		isPresetRegexp = !item.name.isEmpty();
		uiRegex.setText(item.from);
		regexChanged(uiRegex);
		uiRegexRplTo.setText(item.to);
		uiRegexIdGroup.setValue(chapterId_group = item.chapterId);
		uiRegexNameGroup.setValue(chapterName_group = item.chapterName);
	}

	private void presetRegexChanged(JTextComponent c) {
		LineReader.Impl lr = LineReader.create(c.getText());

		PresetRegexp none = uxPresetRegexs.getElementAt(0);
		uxPresetRegexs.removeAllElements();
		uxPresetRegexs.addElement(none);

		int i = 1;
		try {
			while (lr.hasNext()) {
				List<String> id = TextUtil.split(lr.next(), '|');
				String from = lr.next();
				String to = lr.next();
				PresetRegexp regexp = new PresetRegexp();
				Pattern.compile(from);
				regexp.from = from;
				regexp.to = to;
				regexp.name = id.get(0);
				regexp.chapterId = Integer.parseInt(id.get(1));
				regexp.chapterName = Integer.parseInt(id.get(2));
				uxPresetRegexs.addElement(regexp);
				i++;
			}
		} catch (Exception e) {
			CharList myOut = new CharList();
			LogHelper.printError(e, myOut);
			errout.setText(myOut.toStringAndFree());

			JOptionPane.showMessageDialog(advancedMenu, "第"+i+"个正则表达式解析失败", "错误", JOptionPane.ERROR_MESSAGE);
		}
	}

	private void open_advanced_menu(ActionEvent e) {
		advancedMenu.show();
		setEnabled(false);
	}

	private void advancedMenuWindowClosing(WindowEvent e) {
		setEnabled(true);
		requestFocus();
	}

	private void chapIdGroupInpStateChanged(ChangeEvent e) {
		chapterId_group = (int) uiRegexIdGroup.getValue();
	}

	private void chapNameGroupInpStateChanged(ChangeEvent e) {
		chapterName_group = (int) uiRegexNameGroup.getValue();
	}

	private void regexChanged(JTextComponent e) {
		btnAlign.setEnabled(false);
		novel_regexp = null;
		try {
			if (e.getText().isEmpty()) throw OperationDone.INSTANCE;

			novel_regexp = Pattern.compile(e.getText(), Pattern.MULTILINE);
		} catch (Exception e1) {
			uiRegex.setForeground(new Color(0xB60000));
			btnMakeChapter.setEnabled(false);
			return;
		}
		uiRegex.setForeground(new Color(0x008100));
		btnMakeChapter.setEnabled(true);
	}

	private void test_chapter(ActionEvent e) {
		Chapter root = new Chapter();
		List<Chapter> chapters = root.children = new ArrayList<>();

		Chapter c = root;
		c.name = DateFormat.toLocalDateTime(System.currentTimeMillis());
		CharList tmp = this.tmp;

		Matcher m = novel_regexp.matcher(novel_in);
		int i = 0;
		while (m.find(i)) {
			c.end = m.start();

			c = new Chapter();
			c.start = m.end();

			try {
				c.index = Chapter.parseChapterNo(novel_in.list, m.start(chapterId_group), m.end(chapterId_group));
			} catch (NumberFormatException ignored) {}

			for (int j = 1; j <= m.groupCount(); j++)
				if (j != chapterName_group && j != chapterId_group && m.group(j).length() == 1)
					c.type = m.group(j).charAt(0);

			c.name = mytrim(m.group(chapterName_group));

			tmp.clear(); tmp.append(uiRegexRplTo.getText());
			for (int j = 0; j <= m.groupCount(); j++) {
				tmp.replace("$" + j, j == chapterName_group ? c.name : m.group(j));
			}
			c.matches = m.group();
			c.customName = tmp.toStringAndFree();

			chapters.add(c);

			i = m.end();
		}
		c.end = novel_in.length();

		root.setParents();
		chaptersTree.setRoot(root);
		errout.setText("mode: "+root.name+"\nchapter count: "+root.sumChildCount());

		uiChapters.setRootVisible(true);
		btnAlign.setEnabled(true);

		btnAddChapter.setEnabled(true);
		btnDelChapter.setEnabled(true);
		btnWrongChapter.setEnabled(true);
		btnMergeChapter.setEnabled(true);
		btnPutChapter.setEnabled(true);
		btnDeDupChapter.setEnabled(true);
		btnDelByLen.setEnabled(true);
	}
	// endregion
	// region 功能区-章节管理

	/** 删除 */
	private void delChapterText(ActionEvent e) {
		Chapter[] cs = getSelectedChapters();
		if (cs == null) return;

		boolean confirm = false;
		Chapter prev = null;
		for (Chapter c : cs) {
			if (c.getChildCount() > 0) {
				if (!confirm) {
					if (JOptionPane.YES_OPTION != JOptionPane.showConfirmDialog(this, "删除上级会一并删除下级的所有章节\n"+c, "警告", JOptionPane.YES_NO_OPTION)) {
						return;
					}
					confirm = true;
				}
			}

			prev = c.getParent();
			int off = prev.getIndex(c);
			if (off+1 < prev.getChildCount()) prev = prev.getChildAt(off+1);

			chaptersTree.removeNodeFromParent(c);
		}

		TreePath path = makePath(prev);
		uiChapters.setSelectionPath(path);
		uiChapters.scrollPathToVisible(path);
	}

	/** 错误匹配 */
	private void nextDisorderChapter(ActionEvent e) {
		Chapter[] cs = getSelectedChapters();

		initChapters();

		int i;
		if (cs == null || cs.length != 1) {
			i = 1;
		} else {
			i = chapters.indexOf(cs[0])+1;
		}

		for (; i < chapters.size(); i++) {
			Chapter prev = chapters.get(i-1);
			Chapter curr = chapters.get(i);

			int delta = (int) (curr.index - prev.index);
			if (delta != 1) {
				if (curr.getParent() == prev) continue;
				if (curr.type != prev.type) {
					GuiUtil.insert(errout, "\n"+curr+"不是那么可疑");
				}

				TreePath path = makePath(curr);
				uiChapters.setSelectionPath(path);
				uiChapters.scrollPathToVisible(path);
				return;
			}
		}

		JOptionPane.showMessageDialog(advancedMenu, "没有了", "提示", JOptionPane.INFORMATION_MESSAGE);
	}

	/** 替换内容 */
	private void replaceChapter(ActionEvent e) {
		Chapter c = getOneChapter();
		if (c == null) {
			JOptionPane.showMessageDialog(this, "选中的章节数量不为1");
			return;
		}

		data(c).clear();
		c.text.append(errout.getText());
	}

	/** 合并 */
	private void delChapterName(ActionEvent e) {
		Chapter[] cs = getSelectedChapters();
		if (cs == null) return;

		boolean confirm = false;
		Chapter prev = null;
		for (int i = cs.length-1; i >= 0; i--) {
			Chapter c = cs[i];
			if (c.getChildCount() > 0) {
				if (!confirm) {
					if (JOptionPane.YES_OPTION != JOptionPane.showConfirmDialog(this, "合并上级会一并合并下级的所有章节\n"+c, "警告", JOptionPane.YES_NO_OPTION)) {
						return;
					}
					confirm = true;
				}
			}

			prev = c.getParent();
			int off = prev.getIndex(c);
			if (off > 0) prev = prev.getChildAt(off-1);

			chaptersTree.removeNodeFromParent(c);
			CharList str = data(prev);

			if (c.getChildCount() > 0) {
				List<Chapter> flat = new ArrayList<>();
				c.flat(flat);
				for (Chapter c1 : flat)
					str.append(c1.matches).append(data(c1));
			} else {
				str.append(c.matches).append(data(c));
			}
		}

		TreePath path = makePath(prev);
		uiChapters.setSelectionPath(path);
		uiChapters.scrollPathToVisible(path);
	}

	/** 查重 */
	private void checkChapterDup(ActionEvent e) {
		new Thread(() -> {
			LongAdder finished = new LongAdder();

			initChapters();

			long total = (chapters.size() - 1) * chapters.size() / 2;

			TaskPool pool = TaskPool.newFixed(20, "ND-Worker");
			pool.setRejectPolicy(TaskPool::waitPolicy);

			ArrayList<IntMap.Entry<String>> list = new ArrayList<>();

			AtomicReference<TimerTask> task = new AtomicReference<>();
			task.set(Timer.getDefault().loop(() -> {
				progress.setValue((int) ((double)finished.sum() / total * 10000));
				progressStr.setText(finished + "/" + total);
				if (finished.sum() == total) {
					task.get().cancel();
					pool.shutdown();
					list.sort((o1, o2) -> Integer.compare(o2.getIntKey(), o1.getIntKey()));
					errout.setText("相似度(仅记录超过50%) - 章节名称\n"+TextUtil.join(list, "\n"));
					btnDeDupChapter.setEnabled(true);
					progressStr.setText("finished");
				}
			}, 20));

			for (int i = 0; i < chapters.size(); i++) {
				Chapter ca = chapters.get(i);
				byte[] ba = IOUtil.encodeUTF8(ca.text != null ? ca.text : novel_in.subSequence(ca.start, ca.end));
				if (ba.length <= 10) {
					finished.add(chapters.size()-i-1);
					continue;
				}

				BsDiff diff = new BsDiff();
				diff.setLeft(ba);

				for (int j = i+1; j < chapters.size(); j++) {
					Chapter cb = chapters.get(j);

					pool.executeUnsafe(() -> {
						byte[] bb = IOUtil.encodeUTF8(cb.text != null ? cb.text : novel_in.subSequence(cb.start, cb.end));
						if (bb.length > 10) {
							int siz = Math.min(ba.length, bb.length);
							int dd = diff.parallel().calculateDiffLength(bb, siz / 2);
							if (dd >= 0) {
								synchronized (list) {
									list.add(new IntMap.Entry<>((int) ((double)(siz-dd) / siz * 10000), ca.matches + "|" + cb.matches));
								}
							}
						}
						finished.add(1);
					});
				}
			}
		}).start();

		btnDeDupChapter.setEnabled(false);
	}

	private TreePath makePath(Chapter c) {
		int len = 1;
		Chapter c1 = c;
		while (c1.getParent() != null) {
			c1 = c1.getParent();
			len++;
		}
		Object[] paths = new Object[len];
		while (len > 0) {
			paths[--len] = c;
			c = c.getParent();
		}
		return new TreePath(paths);
	}

	private Chapter[] getSelectedChapters() {
		TreePath[] paths = uiChapters.getSelectionPaths();
		if (paths != null) {
			Chapter[] out = new Chapter[paths.length];
			for (int i = 0; i < paths.length; i++) {
				out[i] = (Chapter) paths[i].getLastPathComponent();
			}
			return out;
		}
		return null;
	}

	private CharList data(Chapter c) {
		if (c.text == null) c.text = new CharList(novel_in.subSequence(c.start, c.end));
		return c.text;
	}

	private void btnInsertMode(ActionEvent e) {
		//uxDrag.insertMode = btnInsertMode.isSelected();
	}
	// endregion
	// region 功能区-输出格式
	private void align_novel(ActionEvent e) {
		initChapters();

		boolean regen = uiRegenName.isSelected();
		if (regen) renameChapter();

		novel_out.clear();
		for (int i = 0; i < chapters.size(); i++) {
			Chapter c = chapters.get(i);
			if (i > 0) novel_out.append("\n\n").append(regen ? c.customName : c.matches).append('\n');
			writeChapter(c, novel_out, true);
		}
		errout.setText("");
		sample(novel_out);

		novel_out.replace("\n", "\r\n");

		btnWrite.setEnabled(true);
		btnToEpub.setEnabled(true);
	}

	private void write_novel(ActionEvent e) {
		File file = new File(uiNovelPath.getText());
		long time = file.lastModified();

		try (TextWriter out = TextWriter.to(file, Charset.forName("GB18030"))) {
			out.append(novel_out);
		} catch (IOException ex) {
			CharList myOut = new CharList();
			LogHelper.printError(ex, myOut);
			errout.setText(myOut.toStringAndFree());
			return;
		}

		file.setLastModified(time);
	}

	private void write_epub(ActionEvent e) {
		String text = uiNovelPath.getText();

		String fileName = IOUtil.getBaseName(text);
		File path = new File(new File(text).getParent(), fileName+".epub");

		String title, author;
		int pos = fileName.lastIndexOf(" - ");
		if (pos < 0) {
			title = fileName;
			author = "未知";
		} else {
			title = fileName.substring(0, pos);
			author = fileName.substring(pos+3);
		}

		File cover = GuiUtil.fileLoadFrom("选择小说封面");

		Function<Chapter, CharList> encoder = c -> {
			tmp.clear();
			writeChapter(c, tmp, false);
			return tmp;
		};

		Map<EbookWriter.Metadata<?>, Object> metadata = new HashMap<>();
		metadata.put(EbookWriter.TITLE, title);
		metadata.put(EbookWriter.AUTHOR, author);
		if (cover != null) metadata.put(EbookWriter.COVER, cover);

		try (var epw = EbookWriter.epub(metadata, path)) {
			Chapter root = (Chapter) chaptersTree.getRoot();
			epw.addChapter(root, encoder);

			//List<Chapter> children = root.children;
			//for (int i = 0; i < children.size(); i++)
			//	epw.addChapter(children.get(i), encoder);

			errout.setText("写入成功！保存为同名epub文件");
		} catch (Exception ex) {
			CharList myOut = new CharList();
			LogHelper.printError(ex, myOut);
			errout.setText(myOut.toStringAndFree());
		}
	}

	public void writeChapter(Chapter c, CharList ob, boolean addSpace) {
		int st, len;
		char[] val;
		if (c.text != null) {
			st = 0;
			len = c.text.length();
			val = c.text.list;
		} else {
			st = c.start;
			len = c.end;
			val = novel_in.list;
		}

		boolean firstLineRemoved = false;
		while ((st < len) && myWhiteSpace.contains(val[st])) {
			st++;
			firstLineRemoved = true;
		}
		while ((st < len) && myWhiteSpace.contains(val[len - 1])) {
			len--;
		}

		for (String line : LineReader.create(new CharList.Slice(val, st, len), false)) {
			if (!firstLineRemoved && (line.isEmpty() || uiSkipNoSpace.isSelected() && !Character.isWhitespace(line.charAt(0)))) {
				ob.append(line).append('\n');
				continue;
			}
			firstLineRemoved = false;

			st = 0;
			len = line.length();
			while ((st < len) && myWhiteSpace.contains(line.charAt(st))) {
				st++;
			}
			while ((st < len) && myWhiteSpace.contains(line.charAt(len - 1))) {
				len--;
			}

			if (st == len) ob.append('\n');
			else {
				if (addSpace) ob.append("　　");
				ob.append(line, st, len).append('\n');
			}
		}
	}
	private void renameChapter() {
		ToIntMap<String> myChapterNo = new ToIntMap<>();
		CharList tmp = this.tmp;

		for (int i = 1; i < chapters.size(); i++) {
			Chapter c = chapters.get(i);
			if (c.hasCustomName) continue;

			Matcher m = novel_regexp.matcher(c.matches);
			boolean ok = m.matches();
			assert ok;

			tmp.clear(); tmp.append(uiRegexRplTo.getText());
			for (int j = 1; j <= m.groupCount(); j++) {
				if (j != chapterId_group && j != chapterName_group) {
					String str = m.group(j);
					c.type ^= str.hashCode();
				}
			}

			for (int j = 0; j <= m.groupCount(); j++) {
				String str = m.group(j);
				if (j == chapterId_group) {
					if (uiRegenId.isSelected()) {
						str = Integer.toString(myChapterNo.increment(String.valueOf(c.type), 1));
					}

					switch (uiRegenNameType.getSelectedIndex()) {
						case 0: break;
						case 1:
							char[] cb = str.toCharArray();
							str = Long.toString(Chapter.parseChapterNo(cb, 0, cb.length));
							break;
						case 2:
							cb = str.toCharArray();
							str = ChinaNumeric.toString(Chapter.parseChapterNo(cb, 0, cb.length));
							break;
					}
				} else if (j == chapterName_group) {
					str = mytrim(str);
				}

				tmp.replace("$"+j, str);
			}

			c.customName = tmp.toString();
		}
	}

	private void renameChapterStateChanged(ChangeEvent e) {
		uiRegenNameType.setEnabled(uiRegenName.isSelected());
		uiRegenId.setEnabled(uiRegenName.isSelected());
	}
	// endregion

	private Chapter getOneChapter() {
		TreePath[] paths = uiChapters.getSelectionPaths();
		return paths != null && paths.length == 1 ? ((Chapter) paths[0].getLastPathComponent()) : null;
	}

	private void sample(CharList in) {
		GuiUtil.insert(errout, "chars:"+in.length()+
					   "\nHead 15k\n"+in.substring(0,Math.min(15000, in.length()))+
					   "\n\n\n\n\n\n\n\n\n\n==========Tail 15k==========\n\n\n\n\n\n\n\n\n\n"+in.substring(Math.max(0, in.length()-15000), in.length()));
	}

	private String mytrim(String c) {
		int st = 0;
		int len = c.length();
		while ((st < len) && myWhiteSpace.contains(c.charAt(st))) {
			st++;
		}
		while ((st < len) && myWhiteSpace.contains(c.charAt(len - 1))) {
			len--;
		}
		return c.substring(st,len);
	}

	private void initComponents() {
		// JFormDesigner - Component initialization - DO NOT MODIFY  //GEN-BEGIN:initComponents  @formatter:off
        progress = new JProgressBar();
        progressStr = new JLabel();
        var fg1 = new JSeparator();
        var lb1 = new JLabel();
        uiNovelPath = new JTextField();
        btnLoad = new JButton();
        btnFindNovel = new JButton();
        var fg2 = new JSeparator();
        var lb2 = new JLabel();
        btnMakeChapter = new JButton();
        var lb3 = new JLabel();
        uiRegexIdGroup = new JSpinner();
        var lb4 = new JLabel();
        uiRegexNameGroup = new JSpinner();
        btnFixEnter = new JButton();
        btnRemoveHalfLine = new JButton();
        uiPresetRegexs = new JComboBox<>();
        var uiModPresetRegexs = new JButton();
        btnRegexMatch = new JButton();
        uiRegex = new JTextField();
        btnRegexRpl = new JButton();
        uiRegexRplTo = new JTextField();
        uiRepeatRpl = new JCheckBox();
        lb5 = new JLabel();
        sp1 = new JScrollPane();
        uiChapters = new JTree();
        var fg3 = new JSeparator();
        var lb6 = new JLabel();
        btnAddChapter = new JButton();
        btnDelChapter = new JButton();
        btnWrongChapter = new JButton();
        btnPutChapter = new JButton();
        btnMergeChapter = new JButton();
        btnDeDupChapter = new JButton();
        btnInsertMode = new JCheckBox();
        btnDelByLen = new JButton();
        btnLegadoImport = new JButton();
        cpwOrigName = new JLabel();
        cpwOutName = new JTextField();
        var lb10 = new JLabel();
        cpwChapNo = new JSpinner();
        var lb11 = new JLabel();
        cpwChapName = new JTextField();
        var fg4 = new JSeparator();
        var lb12 = new JLabel();
        btnAlign = new JButton();
        btnWrite = new JButton();
        btnToEpub = new JButton();
        uiSkipNoSpace = new JCheckBox();
        uiRegenName = new JCheckBox();
        uiRegenNameType = new JComboBox<>();
        uiRegenId = new JCheckBox();
        var scrollPane1 = new JScrollPane();
        errout = new JEditorPane();
        btnGroup = new JButton();
        advancedMenu = new JDialog();
        scrollPane3 = new JScrollPane();
        presetRegexpInp = new JTextArea();

        //======== this ========
        setTitle("\u5c0f\u8bf4\u7ba1\u7406\u7cfb\u7edf (Novel Management System) v2.2");
        var contentPane = getContentPane();
        contentPane.setLayout(null);

        //---- progress ----
        progress.setValue(2000);
        progress.setMaximum(10000);
        contentPane.add(progress);
        progress.setBounds(5, 4, 320, progress.getPreferredSize().height);

        //---- progressStr ----
        progressStr.setText("ready");
        progressStr.setHorizontalAlignment(SwingConstants.CENTER);
        contentPane.add(progressStr);
        progressStr.setBounds(325, 4, 105, progressStr.getPreferredSize().height);
        contentPane.add(fg1);
        fg1.setBounds(5, 30, 420, 2);

        //---- lb1 ----
        lb1.setText("\u6587\u4ef6\u8bfb\u53d6");
        lb1.setFont(lb1.getFont().deriveFont(lb1.getFont().getSize() - 2f));
        contentPane.add(lb1);
        lb1.setBounds(385, 32, 40, 11);
        contentPane.add(uiNovelPath);
        uiNovelPath.setBounds(40, 36, 170, uiNovelPath.getPreferredSize().height);

        //---- btnLoad ----
        btnLoad.setText("\u52a0\u8f7d");
        btnLoad.setMargin(new Insets(2, 4, 2, 4));
        btnLoad.addActionListener(e -> read_novel(e));
        contentPane.add(btnLoad);
        btnLoad.setBounds(5, 35, btnLoad.getPreferredSize().width, 21);

        //---- btnFindNovel ----
        btnFindNovel.setText("\u2026");
        btnFindNovel.setMargin(new Insets(2, 2, 2, 2));
        btnFindNovel.addActionListener(e -> select_novel(e));
        contentPane.add(btnFindNovel);
        btnFindNovel.setBounds(208, 35, btnFindNovel.getPreferredSize().width, 21);
        contentPane.add(fg2);
        fg2.setBounds(5, 60, 420, 2);

        //---- lb2 ----
        lb2.setText("\u6821\u5bf9\u6574\u7406");
        lb2.setFont(lb2.getFont().deriveFont(lb2.getFont().getSize() - 2f));
        contentPane.add(lb2);
        lb2.setBounds(385, 62, 40, 11);

        //---- btnMakeChapter ----
        btnMakeChapter.setText("\u5206\u7ae0");
        btnMakeChapter.setEnabled(false);
        btnMakeChapter.setMargin(new Insets(2, 4, 2, 4));
        btnMakeChapter.addActionListener(e -> test_chapter(e));
        contentPane.add(btnMakeChapter);
        btnMakeChapter.setBounds(5, 95, 60, 20);

        //---- lb3 ----
        lb3.setText("\u7ae0\u8282\u5e8f\u53f7\u7ec4");
        contentPane.add(lb3);
        lb3.setBounds(new Rectangle(new Point(160, 65), lb3.getPreferredSize()));

        //---- uiRegexIdGroup ----
        uiRegexIdGroup.setModel(new SpinnerNumberModel(1, 0, null, 1));
        uiRegexIdGroup.addChangeListener(e -> chapIdGroupInpStateChanged(e));
        contentPane.add(uiRegexIdGroup);
        uiRegexIdGroup.setBounds(223, 65, 45, uiRegexIdGroup.getPreferredSize().height);

        //---- lb4 ----
        lb4.setText("\u7ae0\u8282\u540d\u79f0\u7ec4");
        contentPane.add(lb4);
        lb4.setBounds(new Rectangle(new Point(272, 65), lb4.getPreferredSize()));

        //---- uiRegexNameGroup ----
        uiRegexNameGroup.setModel(new SpinnerNumberModel(2, 0, null, 1));
        uiRegexNameGroup.addChangeListener(e -> chapNameGroupInpStateChanged(e));
        contentPane.add(uiRegexNameGroup);
        uiRegexNameGroup.setBounds(335, 65, 45, uiRegexNameGroup.getPreferredSize().height);

        //---- btnFixEnter ----
        btnFixEnter.setText("\u5171\u957f\u5408\u5e76");
        btnFixEnter.setMargin(new Insets(2, 2, 2, 2));
        contentPane.add(btnFixEnter);
        btnFixEnter.setBounds(new Rectangle(new Point(90, 65), btnFixEnter.getPreferredSize()));

        //---- btnRemoveHalfLine ----
        btnRemoveHalfLine.setText("\u53bb\u9664\u4e00\u534a\u7a7a\u884c");
        btnRemoveHalfLine.setMargin(new Insets(2, 4, 2, 4));
        contentPane.add(btnRemoveHalfLine);
        btnRemoveHalfLine.setBounds(new Rectangle(new Point(5, 65), btnRemoveHalfLine.getPreferredSize()));

        //---- uiPresetRegexs ----
        uiPresetRegexs.addActionListener(e -> on_preset_regexp_clicked(e));
        contentPane.add(uiPresetRegexs);
        uiPresetRegexs.setBounds(250, 91, 112, 21);

        //---- uiModPresetRegexs ----
        uiModPresetRegexs.setText("\u6539");
        uiModPresetRegexs.setMargin(new Insets(2, 2, 2, 2));
        uiModPresetRegexs.addActionListener(e -> open_advanced_menu(e));
        contentPane.add(uiModPresetRegexs);
        uiModPresetRegexs.setBounds(new Rectangle(new Point(360, 90), uiModPresetRegexs.getPreferredSize()));

        //---- btnRegexMatch ----
        btnRegexMatch.setText("\u6b63\u5219\u5339\u914d");
        btnRegexMatch.setMargin(new Insets(0, 0, 0, 0));
        contentPane.add(btnRegexMatch);
        btnRegexMatch.setBounds(5, 114, 60, 23);
        contentPane.add(uiRegex);
        uiRegex.setBounds(65, 115, 360, uiRegex.getPreferredSize().height);

        //---- btnRegexRpl ----
        btnRegexRpl.setText("\u6b63\u5219\u66ff\u6362");
        btnRegexRpl.setMargin(new Insets(0, 0, 0, 0));
        contentPane.add(btnRegexRpl);
        btnRegexRpl.setBounds(5, 135, 60, 23);
        contentPane.add(uiRegexRplTo);
        uiRegexRplTo.setBounds(65, 135, 360, uiRegexRplTo.getPreferredSize().height);

        //---- uiRepeatRpl ----
        uiRepeatRpl.setText("\u5faa\u73af\u5339\u914d");
        contentPane.add(uiRepeatRpl);
        uiRepeatRpl.setBounds(new Rectangle(new Point(80, 155), uiRepeatRpl.getPreferredSize()));

        //---- lb5 ----
        lb5.setText("\u7ae0\u8282\u5217\u8868");
        contentPane.add(lb5);
        lb5.setBounds(new Rectangle(new Point(5, 160), lb5.getPreferredSize()));

        //======== sp1 ========
        {

            //---- uiChapters ----
            uiChapters.setModel(new DefaultTreeModel(
                new DefaultMutableTreeNode("\u672a\u52a0\u8f7d") {
                    {
                    }
                }));
            uiChapters.setRootVisible(false);
            sp1.setViewportView(uiChapters);
        }
        contentPane.add(sp1);
        sp1.setBounds(5, 175, 420, 270);
        contentPane.add(fg3);
        fg3.setBounds(5, 450, 420, fg3.getPreferredSize().height);

        //---- lb6 ----
        lb6.setText("\u7ae0\u8282\u7ba1\u7406");
        lb6.setFont(lb6.getFont().deriveFont(lb6.getFont().getSize() - 2f));
        contentPane.add(lb6);
        lb6.setBounds(new Rectangle(new Point(385, 452), lb6.getPreferredSize()));

        //---- btnAddChapter ----
        btnAddChapter.setText("\u65b0\u589e");
        btnAddChapter.setEnabled(false);
        btnAddChapter.setMargin(new Insets(2, 4, 2, 4));
        contentPane.add(btnAddChapter);
        btnAddChapter.setBounds(new Rectangle(new Point(10, 455), btnAddChapter.getPreferredSize()));

        //---- btnDelChapter ----
        btnDelChapter.setText("\u5220\u9664");
        btnDelChapter.setEnabled(false);
        btnDelChapter.setToolTipText("\u5220\u9664\u8be5\u7ae0\u8282\u53ca\u5176\u5185\u5bb9");
        btnDelChapter.setMargin(new Insets(2, 4, 2, 4));
        btnDelChapter.addActionListener(e -> delChapterText(e));
        contentPane.add(btnDelChapter);
        btnDelChapter.setBounds(new Rectangle(new Point(50, 455), btnDelChapter.getPreferredSize()));

        //---- btnWrongChapter ----
        btnWrongChapter.setText("\u67e5\u627e\u9519\u8bef\u5339\u914d");
        btnWrongChapter.setEnabled(false);
        btnWrongChapter.setMargin(new Insets(2, 4, 2, 4));
        btnWrongChapter.addActionListener(e -> nextDisorderChapter(e));
        contentPane.add(btnWrongChapter);
        btnWrongChapter.setBounds(new Rectangle(new Point(90, 455), btnWrongChapter.getPreferredSize()));

        //---- btnPutChapter ----
        btnPutChapter.setText("\u4ece\u53f3\u4fa7\u66ff\u6362");
        btnPutChapter.setEnabled(false);
        btnPutChapter.setToolTipText("\u7528\u53f3\u4fa7\u8f93\u5165\u6846\u7684\u5185\u5bb9\u66ff\u6362\u9009\u4e2d\u7ae0\u8282\u7684\u5185\u5bb9");
        btnPutChapter.setMargin(new Insets(2, 4, 2, 4));
        btnPutChapter.addActionListener(e -> replaceChapter(e));
        contentPane.add(btnPutChapter);
        btnPutChapter.setBounds(new Rectangle(new Point(255, 455), btnPutChapter.getPreferredSize()));

        //---- btnMergeChapter ----
        btnMergeChapter.setText("\u4e0e\u4e0a\u7ae0\u5408\u5e76");
        btnMergeChapter.setEnabled(false);
        btnMergeChapter.setMargin(new Insets(2, 4, 2, 4));
        btnMergeChapter.addActionListener(e -> delChapterName(e));
        contentPane.add(btnMergeChapter);
        btnMergeChapter.setBounds(new Rectangle(new Point(178, 455), btnMergeChapter.getPreferredSize()));

        //---- btnDeDupChapter ----
        btnDeDupChapter.setText("\u67e5\u91cd");
        btnDeDupChapter.setEnabled(false);
        btnDeDupChapter.setToolTipText("\u67e5\u627e\u7591\u4f3c\u91cd\u590d\u7684\u7ae0\u8282");
        btnDeDupChapter.setMargin(new Insets(2, 4, 2, 4));
        btnDeDupChapter.addActionListener(e -> checkChapterDup(e));
        contentPane.add(btnDeDupChapter);
        btnDeDupChapter.setBounds(new Rectangle(new Point(330, 455), btnDeDupChapter.getPreferredSize()));

        //---- btnInsertMode ----
        btnInsertMode.setText("\u63d2\u5165\u5b50\u6811");
        btnInsertMode.setToolTipText("\u6309\u4f4f\u8282\u70b9(A)\u5e76\u62d6\u52a8\u5230\u8282\u70b9(B)\u4e0a\u65f6\n\u5c06A\u8bbe\u7f6e\u4e3aB\u7684\u5b69\u5b50");
        btnInsertMode.addActionListener(e -> btnInsertMode(e));
        contentPane.add(btnInsertMode);
        btnInsertMode.setBounds(new Rectangle(new Point(10, 485), btnInsertMode.getPreferredSize()));

        //---- btnDelByLen ----
        btnDelByLen.setText("\u6309\u957f\u5ea6\u5220\u9664");
        btnDelByLen.setEnabled(false);
        contentPane.add(btnDelByLen);
        btnDelByLen.setBounds(new Rectangle(new Point(85, 485), btnDelByLen.getPreferredSize()));

        //---- btnLegadoImport ----
        btnLegadoImport.setText("\u4ece\u9605\u8bfb\u5bfc\u5165");
        contentPane.add(btnLegadoImport);
        btnLegadoImport.setBounds(new Rectangle(new Point(265, 485), btnLegadoImport.getPreferredSize()));

        //---- cpwOrigName ----
        cpwOrigName.setText("\u53cc\u51fb\u9009\u62e9\u7ae0\u8282");
        contentPane.add(cpwOrigName);
        cpwOrigName.setBounds(10, 520, 415, cpwOrigName.getPreferredSize().height);

        //---- cpwOutName ----
        cpwOutName.setEnabled(false);
        contentPane.add(cpwOutName);
        cpwOutName.setBounds(10, 535, 415, cpwOutName.getPreferredSize().height);

        //---- lb10 ----
        lb10.setText("\u7ae0\u8282\u5e8f\u53f7");
        contentPane.add(lb10);
        lb10.setBounds(new Rectangle(new Point(305, 563), lb10.getPreferredSize()));
        contentPane.add(cpwChapNo);
        cpwChapNo.setBounds(355, 560, 70, cpwChapNo.getPreferredSize().height);

        //---- lb11 ----
        lb11.setText("\u7ae0\u8282\u540d\u79f0");
        contentPane.add(lb11);
        lb11.setBounds(new Rectangle(new Point(60, 562), lb11.getPreferredSize()));
        contentPane.add(cpwChapName);
        cpwChapName.setBounds(110, 560, 190, cpwChapName.getPreferredSize().height);
        contentPane.add(fg4);
        fg4.setBounds(5, 585, 420, fg4.getPreferredSize().height);

        //---- lb12 ----
        lb12.setText("\u8f93\u51fa\u683c\u5f0f");
        lb12.setFont(lb12.getFont().deriveFont(lb12.getFont().getSize() - 2f));
        contentPane.add(lb12);
        lb12.setBounds(new Rectangle(new Point(385, 587), lb12.getPreferredSize()));

        //---- btnAlign ----
        btnAlign.setText("\u6392\u7248");
        btnAlign.setEnabled(false);
        btnAlign.setMargin(new Insets(2, 4, 2, 4));
        btnAlign.addActionListener(e -> align_novel(e));
        contentPane.add(btnAlign);
        btnAlign.setBounds(15, 590, btnAlign.getPreferredSize().width, 20);

        //---- btnWrite ----
        btnWrite.setText("\u4fdd\u5b58");
        btnWrite.setEnabled(false);
        btnWrite.setMargin(new Insets(2, 4, 2, 4));
        btnWrite.addActionListener(e -> write_novel(e));
        contentPane.add(btnWrite);
        btnWrite.setBounds(15, 615, btnWrite.getPreferredSize().width, 20);

        //---- btnToEpub ----
        btnToEpub.setText("\u8f6cEPUB");
        btnToEpub.setEnabled(false);
        btnToEpub.setMargin(new Insets(2, 4, 2, 4));
        contentPane.add(btnToEpub);
        btnToEpub.setBounds(new Rectangle(new Point(10, 640), btnToEpub.getPreferredSize()));

        //---- uiSkipNoSpace ----
        uiSkipNoSpace.setText("\u4e0d\u6574\u7406\u9876\u683c\u7684\u884c");
        uiSkipNoSpace.setSelected(true);
        contentPane.add(uiSkipNoSpace);
        uiSkipNoSpace.setBounds(new Rectangle(new Point(75, 590), uiSkipNoSpace.getPreferredSize()));

        //---- uiRegenName ----
        uiRegenName.setText("\u91cd\u65b0\u751f\u6210\u7ae0\u8282\u6807\u9898");
        uiRegenName.addChangeListener(e -> renameChapterStateChanged(e));
        contentPane.add(uiRegenName);
        uiRegenName.setBounds(new Rectangle(new Point(75, 610), uiRegenName.getPreferredSize()));

        //---- uiRegenNameType ----
        uiRegenNameType.setModel(new DefaultComboBoxModel<>(new String[] {
            "\u4e0d\u5904\u7406\u6570\u5b57",
            "\u963f\u62c9\u4f2f\u6570\u5b57",
            "\u4e2d\u56fd\u6570\u5b57"
        }));
        uiRegenNameType.setEnabled(false);
        contentPane.add(uiRegenNameType);
        uiRegenNameType.setBounds(new Rectangle(new Point(195, 612), uiRegenNameType.getPreferredSize()));

        //---- uiRegenId ----
        uiRegenId.setText("\u91cd\u6570\u5e8f\u53f7");
        uiRegenId.setEnabled(false);
        contentPane.add(uiRegenId);
        uiRegenId.setBounds(new Rectangle(new Point(280, 610), uiRegenId.getPreferredSize()));

        //======== scrollPane1 ========
        {
            scrollPane1.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
            scrollPane1.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_ALWAYS);

            //---- errout ----
            errout.setText("\u5c0f\u63d0\u793a\n\u5728\u7ae0\u8282\u754c\u9762\u8bef\u62d6\u52a8\u53ef\u4ee5\u6309\u53f3\u952e\u53d6\u6d88\n\u9884\u5b9a\u4e49\u6b63\u5219\u53ef\u4ee5\u5728\u9ad8\u7ea7\u83dc\u5355\u4e2d\u4fee\u6539\n\u53cc\u51fb\u7ae0\u8282\u7f16\u8f91\u540d\u79f0\u548c\u5e8f\u53f7");
            scrollPane1.setViewportView(errout);
        }
        contentPane.add(scrollPane1);
        scrollPane1.setBounds(435, 5, 505, 630);

        //---- btnGroup ----
        btnGroup.setText("\u7ae0\u8282\u5206\u7ec4");
        contentPane.add(btnGroup);
        btnGroup.setBounds(new Rectangle(new Point(180, 485), btnGroup.getPreferredSize()));

        contentPane.setPreferredSize(new Dimension(945, 700));
        pack();
        setLocationRelativeTo(getOwner());

        //======== advancedMenu ========
        {
            advancedMenu.setTitle("\u9884\u5b9a\u4e49\u6b63\u5219");
            advancedMenu.addWindowListener(new WindowAdapter() {
                @Override
                public void windowClosing(WindowEvent e) {
                    advancedMenuWindowClosing(e);
                }
            });
            var advancedMenuContentPane = advancedMenu.getContentPane();
            advancedMenuContentPane.setLayout(null);

            //======== scrollPane3 ========
            {

                //---- presetRegexpInp ----
                presetRegexpInp.setText("\u5e38\u7528|1|3\n^(?:\u6b63\u6587)?[\\t \u3000\u00a0\\uE4C6\\uE5E5\\uFEFF\\u200B]*\u7b2c[\\t \u3000\u00a0\\uE4C6\\uE5E5\\uFEFF\\u200B]*([\u2015\uff0d\\\\-\u2500\u2014\u58f9\u8d30\u53c1\u8086\u4f0d\u9646\u67d2\u634c\u7396\u4e00\u4e8c\u4e24\u4e09\u56db\u4e94\u516d\u4e03\u516b\u4e5d\u5341\u25cb\u3007\u96f6\u767e\u5343O0-9\uff10-\uff19]{1,12})[\\t \u3000\u00a0\\uE4C6\\uE5E5\\uFEFF\\u200B]*([\u7ae0\u5377])[\\t \u3000\u00a0\\uE4C6\\uE5E5\\uFEFF\\u200B]*(.*)$\n\u7b2c$1$2 $3\n\u7eaf\u4e2d\u6587|1|1\n(?<=[ \u3000\ue4c6\ue4c6\\t\\n])([0-9 \\x4e00-\\x9fa5\uff08\uff09\\(\\)\\[\\]]{1,15})[ \u3000\\t]*$\n$1\n\u786c\u56de\u8f66\u7b80\u6613\u4fee\u590d|0|0\n^([ \u3000\ue4c6\ue4c6\\t]+.+)\\r?\\n([^ \u3000\\t\\r\\n].+)$\n$1$2\n\u664b\u6c5f\u5e38\u7528|1|2\n^[ \u3000\ue4c6\ue4c6\\t]*([0-9\u4e00\u4e8c\u4e09\u56db\u4e94\u516d\u4e03\u516b\u4e5d\u96f6]{1,5})[\uff0e.\u3001\u203b](.+)\n\u7b2c$1\u7ae0 $2");
                scrollPane3.setViewportView(presetRegexpInp);
            }
            advancedMenuContentPane.add(scrollPane3);
            scrollPane3.setBounds(0, 0, 395, 270);

            {
                // compute preferred size
                Dimension preferredSize = new Dimension();
                for(int i = 0; i < advancedMenuContentPane.getComponentCount(); i++) {
                    Rectangle bounds = advancedMenuContentPane.getComponent(i).getBounds();
                    preferredSize.width = Math.max(bounds.x + bounds.width, preferredSize.width);
                    preferredSize.height = Math.max(bounds.y + bounds.height, preferredSize.height);
                }
                Insets insets = advancedMenuContentPane.getInsets();
                preferredSize.width += insets.right;
                preferredSize.height += insets.bottom;
                advancedMenuContentPane.setMinimumSize(preferredSize);
                advancedMenuContentPane.setPreferredSize(preferredSize);
            }
            advancedMenu.pack();
            advancedMenu.setLocationRelativeTo(advancedMenu.getOwner());
        }
		// JFormDesigner - End of component initialization  //GEN-END:initComponents  @formatter:on
	}


	// JFormDesigner - Variables declaration - DO NOT MODIFY  //GEN-BEGIN:variables  @formatter:off
    private JProgressBar progress;
    private JLabel progressStr;
    private JTextField uiNovelPath;
    private JButton btnLoad;
    private JButton btnFindNovel;
    private JButton btnMakeChapter;
    private JSpinner uiRegexIdGroup;
    private JSpinner uiRegexNameGroup;
    private JButton btnFixEnter;
    private JButton btnRemoveHalfLine;
    private JComboBox<PresetRegexp> uiPresetRegexs;
    private JButton btnRegexMatch;
    private JTextField uiRegex;
    private JButton btnRegexRpl;
    private JTextField uiRegexRplTo;
    private JCheckBox uiRepeatRpl;
    private JLabel lb5;
    private JScrollPane sp1;
    private JTree uiChapters;
    private JButton btnAddChapter;
    private JButton btnDelChapter;
    private JButton btnWrongChapter;
    private JButton btnPutChapter;
    private JButton btnMergeChapter;
    private JButton btnDeDupChapter;
    private JCheckBox btnInsertMode;
    private JButton btnDelByLen;
    private JButton btnLegadoImport;
    private JLabel cpwOrigName;
    private JTextField cpwOutName;
    private JSpinner cpwChapNo;
    private JTextField cpwChapName;
    private JButton btnAlign;
    private JButton btnWrite;
    private JButton btnToEpub;
    private JCheckBox uiSkipNoSpace;
    private JCheckBox uiRegenName;
    private JComboBox<String> uiRegenNameType;
    private JCheckBox uiRegenId;
    private JEditorPane errout;
    private JButton btnGroup;
    private JDialog advancedMenu;
    private JScrollPane scrollPane3;
    private JTextArea presetRegexpInp;
	// JFormDesigner - End of variables declaration  //GEN-END:variables  @formatter:on
}