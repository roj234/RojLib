package roj.config.table;

import roj.io.source.FileSource;
import roj.io.source.Source;
import roj.text.ParseException;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;

/**
 * @author Roj234
 * @since 2024/11/8 19:47
 */
public interface TableParser {
	static TableParser xlsxParser() {return new XlsxParser();}
	static TableParser csvParser() {return new CsvParser();}

	default void table(File file, TableReader listener) throws IOException, ParseException {table(new FileSource(file, false), null, listener);}
	default void table(File file, Charset charset, TableReader listener) throws IOException, ParseException {table(new FileSource(file, false), charset, listener);}
	void table(Source file, Charset charset, TableReader listener) throws IOException, ParseException;
}
