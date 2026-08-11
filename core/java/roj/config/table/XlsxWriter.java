package roj.config.table;

import roj.archive.zip.ZipEntry;
import roj.archive.zip.ZipFile;
import roj.archive.zip.ZipPacker;
import roj.collect.ArrayList;
import roj.collect.IntList;
import roj.collect.ToIntMap;
import roj.io.IOUtil;
import roj.io.source.ByteSource;
import roj.text.CharList;
import roj.text.Tokenizer;
import roj.util.ByteList;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * @author Roj234
 * @since 2023/5/5 15:16
 */
public class XlsxWriter implements TableWriter {
	private static ZipFile TEMPLATE;
	static {
		try {
			TEMPLATE = new ZipFile(new ByteSource(IOUtil.getResourceIL("roj/config/table/template.xlsx")), 0, StandardCharsets.UTF_8);
			TEMPLATE.reload();
		} catch (IOException ignored) {}
	}

	private final ZipPacker zip;

	private String sheetName;
	private ByteList sheet;
	private IntList cw;
	private List<String> merge;
	private final List<Object> sheets = new ArrayList<>();
	private static final int SHEET_OBJECT_SIZE = 4;

	public XlsxWriter(ZipPacker zfw) throws IOException {
		zip = zfw;
		for (ZipEntry entry : TEMPLATE.entries()) {
			if (!entry.getName().endsWith("/")) zfw.copy(TEMPLATE, entry);
		}
		zip.beginEntry(new ZipEntry("xl/sharedStrings.xml"));
		tmpB.putAscii("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n" + "<sst xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">").writeToStream(zip);
		tmpB.clear();
	}

	private final ToIntMap<String> sharedStrings = new ToIntMap<>();
	private final ByteList tmpB = new ByteList();
	private int sst(String key) throws IOException {
		int id = sharedStrings.getOrDefault(key, -1);
		if (id < 0) {
			sharedStrings.putInt(key, id = sharedStrings.size());

			tmpB.clear();
			tmpB.putAscii(Tokenizer.WHITESPACE.contains(key.charAt(0)) || Tokenizer.WHITESPACE.contains(key.charAt(key.length()-1))
				? "<si><t xml:space=\"preserve\">"
				: "<si><t>").putUTFData(xmlEncode(key)).putAscii("</t></si>").writeToStream(zip);
		}
		return id;
	}

	private final CharList tmpC2 = new CharList();
	private CharList xmlEncode(String key) {
		tmpC2.clear();
		return tmpC2.append(key).replace("&", "&amp;").replace("<", "&lt;");
	}

	private final CharList tmpC = new CharList();
	private CharList rc(int col) {
		int start = tmpC.length();

		do {
			tmpC.append((char) ('A' + (col % 26)));
			col /= 26;
		} while (col != 0);

		char[] arr = tmpC.list;
		int length = tmpC.length() - start - 1;
		for (int i = 0, end = Math.max((length + 1) >> 1, 1); i < end; i++) {
			char a = arr[start+i];
			arr[start+i] = arr[start + length-i];
			arr[start + length-i] = a;
		}

		return tmpC;
	}

	public void beginSheet(String name) {
		if (sheet != null) {
			sheets.add(sheetName);
			sheets.add(sheet);
			sheets.add(cw);
			sheets.add(merge);
		}

		maxHeight = 0;
		if (name != null) {
			sheetName = name;
			sheet = new ByteList();
		} else {
			sheet = null;
		}
		cw = null;
		merge = null;
	}

	public void setColWidth(int col, float width) {
		if (cw == null) cw = new IntList();
		cw.setSize(Math.max(cw.size(), col+1));
		cw.getRawArray()[col] = Float.floatToRawIntBits(width);
	}

	/**
	 * @param startCol 区间开始 列 包含
	 * @param startRow 区间结束 行 包含
	 * @param endCol 区间开始 列 包含
	 * @param endRow 区间结束 行 不包含
	 */
	public void setMergedRow(int startCol, int startRow, int endCol, int endRow) {
		endRow--;

		if (merge == null) merge = new ArrayList<>();
		rc(startCol).append(startRow).append(':');
		merge.add(rc(endCol).append(endRow).toString());
		tmpC.clear();
	}

	private int maxHeight;
	@Override
	public void writeRow(List<?> row) throws IOException {
		int r = ++maxHeight;

		// <row r="1" spans="1:1"><c r="A1" t="s"><v>0</v></c><c r="B1" t="s"><v>1</v></c></row>
		int min = 0, max = row.size();
		for (int i = 0; i < max; i++) {
			Object o = row.get(i);
			if (o != null && !"".equals(o)) {
				min = i; break;
			}
		}
		for (int i = max - 1; i >= min; i--) {
			Object o = row.get(i);
			if (o != null && !"".equals(o)) {
				max = i; break;
			}
		}

		tmpC.append("<row r=\"").append(r).append("\" spans=\"").append(min+1).append(":").append(max+1).append("\">");
		for (int i = 0; i < row.size(); i++) {
			Object key = row.get(i);
			if (key == null) continue;

			tmpC.append("<c r=\"");
			rc(i).append(r);

			if (key instanceof Number) {
				tmpC.append("\"><v>").append(key.toString());
			} else {
				tmpC.append("\" t=\"s\"><v>").append(sst(key.toString()));
			}
			tmpC.append("</v></c>");
		}
		sheet.putUTFData(tmpC.append("</row>"));
		tmpC.clear();
	}

	@Override
	@SuppressWarnings("unchecked")
	public void close() throws IOException {
		tmpB.clear();
		tmpB.putAscii("</sst>").writeToStream(zip);

		zip.closeEntry();
		beginSheet(null);
		for (int i = 0; i < sheets.size(); i += SHEET_OBJECT_SIZE) {
			zip.beginEntry(new ZipEntry("xl/worksheets/sheet"+(i/SHEET_OBJECT_SIZE+1)+".xml"));

			tmpB.clear();
			tmpB.putAscii("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n" +
				"<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\" xmlns:xdr=\"http://schemas.openxmlformats.org/drawingml/2006/spreadsheetDrawing\" xmlns:x14=\"http://schemas.microsoft.com/office/spreadsheetml/2009/9/main\" xmlns:mc=\"http://schemas.openxmlformats.org/markup-compatibility/2006\">");

			IntList colWidth = (IntList) sheets.get(i+2);
			if (colWidth != null) {
				tmpB.putAscii("<cols>");
				for (int col = 0; col < colWidth.size();) {
					int width = colWidth.get(col++);
					if (width != 0) tmpB.putAscii("<col min=\""+col+"\" max=\""+col+"\" width=\""+Float.intBitsToFloat(width)+"\" customWidth=\"1\"/>");
				}
				tmpB.putAscii("</cols>");
			}
			// <cols><col min="1" max="1" width="16" customWidth="1"/><col min="2" max="2" width="91.75" customWidth="1"/></cols>

			tmpB.putAscii("<sheetData>").writeToStream(zip);
			((ByteList)sheets.get(i+1)).writeToStream(zip);

			tmpB.clear();
			tmpB.putAscii("</sheetData>");
			var mergedCells = ((List<String>) sheets.get(i + 3));
			if (mergedCells != null) {
				tmpB.putAscii("<mergeCells count=\"").putAscii(String.valueOf(mergedCells.size())).putAscii("\">");
				for (int j = 0; j < mergedCells.size(); j++) {
					tmpB.putAscii("<mergeCell ref=\"").putAscii(mergedCells.get(j)).putAscii("\"/>");
				}
				tmpB.putAscii("</mergeCells>");
			}
			tmpB.putAscii("</worksheet>").writeToStream(zip);

			zip.closeEntry();
		}
		zip.beginEntry(new ZipEntry("xl/workbook.xml"));
		tmpB.clear();
		tmpB.putAscii("<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><fileVersion appName=\"xl\" lastEdited=\"3\" lowestEdited=\"5\" rupBuild=\"9302\"/><sheets>");
		for (int i = 0; i < sheets.size(); i += SHEET_OBJECT_SIZE) {
			tmpB.putAscii("<sheet name=\"").putUTFData(Tokenizer.escape(sheets.get(i).toString())).putAscii("\" sheetId=\""+(i/SHEET_OBJECT_SIZE+1)+"\" r:id=\"rId"+(i/SHEET_OBJECT_SIZE+1)+"\"/>");
		}
		tmpB.putAscii("</sheets></workbook>").writeToStream(zip);
		zip.closeEntry();
		zip.beginEntry(new ZipEntry("xl/_rels/workbook.xml.rels"));
		tmpB.clear();
		tmpB.putAscii("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n" + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">");
		for (int i = 0; i < sheets.size(); i += SHEET_OBJECT_SIZE) {
			tmpB.putAscii("<Relationship Id=\"rId"+(i/SHEET_OBJECT_SIZE+1)+"\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet"+(i/SHEET_OBJECT_SIZE+1)+".xml\"/>");
		}
		tmpB.putAscii("<Relationship Id=\"rId"+(sheets.size()/SHEET_OBJECT_SIZE+1)+"\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/sharedStrings\" Target=\"sharedStrings.xml\"/>");

		tmpB.putAscii("</Relationships>").writeToStream(zip);
		zip.close();

		sharedStrings.clear();
	}
	@Override
	public void flush() {}
}