# 自动识别中文编码（ASCII UTF-8 GB2312 GBK GB18030）
 * roj.text.FastCharset
 * roj.text.CharsetDetector

## 注意：对于既不是中文也不是英文的文件，比如俄文和日文，可能会报错

## used in: 
 * roj.config.TextParser#parseRaw
 * roj.text.TextReader#auto
 * roj.io.ChineseInputStream

## example
```java
import roj.text.TextReader;
import roj.text.CharList;
import roj.io.IOUtil;
class Example {
	public static void example1() {
		try (TextReader in = TextReader.auto(new File("D:\\Desktop\\Python39\\phrase-pinyin-data-master.txt"))) {
			CharList sb = IOUtil.getSharedCharBuf();
			while (true) {
				sb.clear();
				if (!in.readLine(sb)) break;

				if (sb.startsWith("#")) continue;

				System.out.println(sb);
			}
		}
	}
	public static void example2() {
		System.out.println(IOUtil.readString(new File("D:\\Desktop\\Python39\\phrase-pinyin-data-master.txt")));
	}
}
```