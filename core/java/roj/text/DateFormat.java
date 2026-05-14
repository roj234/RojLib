package roj.text;

import org.jetbrains.annotations.ApiStatus;
import roj.annotation.MayMutate;
import roj.collect.ArrayList;
import roj.collect.Int2IntMap;
import roj.collect.IntList;
import roj.io.IOUtil;

import java.util.Calendar;
import java.util.TimeZone;

/**
 * 将时间格式解析为时间戳.
 * DateFormat.create("YYYY-MM-DD").parse("2025-01-01");
 * @see #getCalendar(long)
 * @author Roj234
 * @since 2021/6/16 2:48
 */
public final class DateFormat {
	/** 年份字段索引 */
	public static final int YEAR = 0;
	/** 月份字段索引（1-12） */
	public static final int MONTH = 1;
	/** 日期字段索引（1-31） */
	public static final int DAY = 2;
	/** 小时字段索引（0-23） */
	public static final int HOUR = 3;
	/** 分钟字段索引（0-59） */
	public static final int MINUTE = 4;
	/** 秒钟字段索引（0-59） */
	public static final int SECOND = 5;
	/** 毫秒字段索引（0-999） */
	public static final int MILLISECOND = 6;
	/** 闰年标识字段索引（0-否，1-是） */
	public static final int LEAP_YEAR = 7;
	/** 星期几字段索引（1-7，1表示星期一） */
	public static final int DAY_OF_WEEK = 8;
	/** 一年中的第几天字段索引（1-366） */
	public static final int DAY_OF_YEAR = 9;
	/** 一月中的第几周字段索引 */
	public static final int WEEK_OF_MONTH = 10;
	/** 一年中的第几周字段索引 */
	public static final int WEEK_OF_YEAR = 11;
	/** ISO周编号字段索引（格式：年份*100 + 周数） */
	public static final int ISO_WEEK_OF_YEAR = 12;

	/** 核心字段数量（提供的数组不应小于此大小） */
	public static final int CORE_FIELD_COUNT = 8;
	/** 辅助字段总数量 */
	public static final int AUXILIARY_FIELD_COUNT = 13;

	private static TimeZone localTimeZone;
	public static TimeZone getLocalTimeZone() {
		if (localTimeZone == null) {
			localTimeZone = TimeZone.getDefault();
		}
		return localTimeZone;
	}

	private final int[] formats;
	private final String[] chars;

	private static final int F_YWD = 1, F_AMPM = 2;
	private final int method;

	private TimeZone timezone;
	private int century = 19;
	private boolean optional;

	/**
	 * 时区，如果格式未提供时区，就按这个时区的本地时间解析，同时还会影响{@link #format(long, CharList)}
	 */
	public DateFormat withTimeZone(TimeZone timeZone) {this.timezone = timeZone;return this;}
	/**
	 * 缺少年份时的世纪，默认19
	 */
	public DateFormat withDefaultCentury(int century) {this.century = century;return this;}
	/**
	 * 允许更短的字符串，也就是可以省略靠后的部分
	 */
	public DateFormat withPartialMatch(boolean optional) {this.optional = optional;return this;}

	public static DateFormat create(String format) {
		IntList formats = new IntList();
		ArrayList<String> formatChars = new ArrayList<>();

		int prevI = 0;
		int currentKind = 0;
		for (int i = 0; i <= format.length(); i++) {
			char c = 0;
			if (i == format.length() || currentKind != kindOf(c = format.charAt(i))) {
				var pattern = format.substring(prevI, i);
				prevI = i;

				if (currentKind == 0) {
					var entry = STRATEGIES.getEntry(pattern.charAt(0) | (pattern.length() << 16));
					if (entry == null) throw new IllegalStateException("invalid pattern "+pattern);
					formats.add(entry.value);
					currentKind = 2;
				} else {
					pattern = pattern.trim();
					if (!pattern.isEmpty()) {
						formats.add(-1);
						formatChars.add(pattern);
					}
					currentKind = 0;
				}

				if (c == '"') {
					var output = IOUtil.getSharedCharBuf();
					boolean slash = false;

					try {
						while (true) {
							c = format.charAt(++i);
							if (slash) {
								i = Tokenizer.unescapeChar(format, c, output, i);
								slash = false;
							} else if (c == '"') {
								prevI = ++i;
								break;
							} else if (c == '\\') {
								slash = true;
							} else {
								output.append(c);
							}
						}
					} catch (Exception e) {
						throw new IllegalArgumentException("invalid pattern ", e);
					}

					formats.add(-1);
					formatChars.add(output.toString());
					currentKind = 0;
				}
			}
		}

		return new DateFormat(formats.toArray(), formatChars.toArray(new String[formatChars.size()]));
	}
	private static int kindOf(char c) {return Character.isAlphabetic(c) ? 0 : c == '"' ? 1 : 2;}

	private DateFormat(int[] formats, String[] chars) {
		this.formats = formats;
		this.chars = chars;
		this.method = findMethod(formats);
	}
	private static int findMethod(int[] formats) {
		int bitset = 0;
		for (int f : formats) {
			bitset |= 1 << f;
		}

		int weekday_accept = 0b11_111000000;
		int weekday_ignore = 0b111_1100;

		int method = 0;
		if (Integer.bitCount(bitset&weekday_ignore) < Integer.bitCount(bitset&weekday_accept)) {
			method |= F_YWD;
		}
		if ((bitset & (1 << 16)) != 0) {
			method |= F_AMPM;
		}
		return method;
	}

	private static final Int2IntMap STRATEGIES = new Int2IntMap(32);
	private static void fmt(char fmt, int count, int id) {STRATEGIES.putInt(fmt | (count << 16), id);}
	static {
		fmt('Y', 4, 0); // year
		fmt('Y', 2, 1); // length-2 year
		fmt('M', 1, 2); // Unpadded month
		fmt('M', 2, 3); // Padded month
		fmt('M', 3, 4); // English month
		fmt('D', 1, 5); // Unpadded day
		fmt('D', 2, 6); // Padded day

		fmt('W', 1, 7); // Number day of week
		fmt('W', 3, 8); // English day of week
		fmt('W', 4, 9); // Chinese day of week

		fmt('w', 1, 10); // Week of the year
		fmt('w', 2, 11); // Week of the year

		fmt('H', 1, 12); // Unpadded 24 hour
		fmt('H', 2, 13); // Padded 24 hour

		fmt('h', 1, 14); // Unpadded 12 hour
		fmt('h', 2, 15); // Padded 12 hour
		fmt('a', 1, 16); // am/pm
		fmt('A', 1, 25); // AM/PM

		fmt('i', 1, 17); // Unpadded minute
		fmt('i', 2, 18); // Padded minute
		fmt('s', 1, 19); // Unpadded second
		fmt('s', 2, 20); // Padded second

		fmt('x', 1, 21); // Unpadded millisecond
		fmt('x', 3, 22); // Padded millisecond

		fmt('Z', 1, 23); // ±00:00 | GMT Timezone
		fmt('P', 1, 26); // ±00:00 | Z Timezone
		fmt('z', 1, 24); // String Timezone
	}

	private static final int IDX = 7, AMPM = 9, ZOF = 10;
	public long parse(CharList sb) {
		int charI = 0;
		TimeZone tz = null;
		int[] cal = new int[] {
			1900, 1, 1, // Y M D
			0, 0, 0, 0, // H I S MS
			0,			// IDX
			1, 0, -1, 1 // DayOfWeek AM/PM ZoneOffset WeekOfTheYear
		};
		loop:
		for (int j = 0; j < formats.length; j++) {
			int id = formats[j];

			if (cal[IDX] == sb.length()) {
				if (optional) break;
				else throw new IllegalArgumentException("字符串过短");
			}

			switch (id) {
				case -1 -> {
					String s = chars[charI++];
					if (!sb.regionMatches(cal[IDX], s)) {
						throw new IllegalArgumentException("Delim error");
					}
					cal[IDX] += s.length();
				}
				case 0 -> cal[YEAR] = dateNum(sb, cal, 1, 4, 0, 9999);
				case 1 -> cal[YEAR] = century * 100 + dateNum(sb, cal, 2, 2, 0, 99);
				case 2, 3 -> cal[MONTH] = dateNum(sb, cal, id-1, 2, 1, 12);
				case 4 -> {
					for (int i = 0; i < UTCMONTH.length;) {
						String month = UTCMONTH[i++];
						if (sb.regionMatches(cal[IDX], month)) {
							cal[MONTH] = i;
							continue loop;
						}
					}
					throw new IllegalArgumentException("Month error");
				}
				case 5, 6 -> cal[DAY] = dateNum(sb, cal, id-4, 2, 1, 31);
				case 7 -> cal[DAY_OF_WEEK] = dateNum(sb, cal, 1, 1, 1, 7);
				case 8 -> {
					for (int i = 0; i < UTCWEEK.length;) {
						String week = UTCWEEK[i++];
						if (sb.regionMatches(cal[IDX], week)) {
							cal[DAY_OF_WEEK] = i;
							continue loop;
						}
					}
					throw new IllegalArgumentException("Week error");
				}
				case 9 -> {
					if (sb.regionMatches(cal[IDX], "星期")) {
						int index = "一二三四五六七日天".indexOf(sb.charAt(cal[IDX] += 2)) + 1;
						if (index > 0) {
							if (index > 7) index = 7;
							cal[DAY_OF_WEEK] = index;
							continue loop;
						}
					}
					throw new IllegalArgumentException("Week error");
				}
				case 10, 11 -> cal[WEEK_OF_YEAR] = dateNum(sb, cal, id-9, 2, 1, 43);
				case 12, 13, 14, 15 -> cal[HOUR] = dateNum(sb, cal, (id&1) + 1, 2, 0, id > 13 ? 11 : 23);
				case 16, 25 -> {
					String s = sb.substring(cal[IDX], cal[IDX] += 2);
					if (s.equalsIgnoreCase("am")) {
						cal[AMPM] = 1;
					} else if (s.equalsIgnoreCase("pm")) {
						cal[AMPM] = 2;
					} else {
						throw new IllegalArgumentException("invalid AM/PM "+s);
					}
				}
				case 17, 18 -> cal[MINUTE] = dateNum(sb, cal, id-16, 2, 0, 59);
				case 19, 20 -> cal[SECOND] = dateNum(sb, cal, id-18, 2, 0, 59);
				case 21, 22 -> cal[MILLISECOND] = dateNum(sb, cal, id == 21 ? 1 : 3, 3, 0, 999);
				case 23, 26 -> {
					int i = sb.charAt(cal[IDX]);
					if (i == 'Z' || i == 'z') {
						cal[IDX]++;
						cal[ZOF] = 0;
					} else if (i == '+' || i == '-') {
						int fixTzOff = dateNum(sb, cal, 1, 2, 0, 99) * 60;
						char td = sb.charAt(cal[IDX]);
						if (td == ':' || td == '.') {
							cal[IDX]++;
							td = sb.charAt(cal[IDX]);
						}
						if (td >= '0' && td <= '9') fixTzOff += dateNum(sb, cal, 2, 2, 0, 59);
						cal[ZOF] = fixTzOff;
					} else if (i == 'G' && sb.regionMatches(i, "GMT")) {
						cal[IDX] += 3;
						cal[ZOF] = 0;
					}
				}
				case 24 -> {
					int i = cal[IDX];
					int prevI = i;
					while (i < sb.length()) {
						char c = sb.charAt(i);
						if (!((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '/')) break;
						i++;
					}

					if (i == prevI) throw new IllegalArgumentException("无效的时区Str");
					tz = TimeZone.getTimeZone(sb.substring(prevI, i));
				}
				case 27 -> {
					int field = formats[j++];
					int count = formats[j++];

					int i = 0;
					error: {
						for (; i < count; i++) {
							String s = chars[charI++];
							if (sb.regionMatches(cal[IDX], s)) break error;
						}
						throw new IllegalArgumentException("Delim error");
					}

					cal[field] = formats[j + i];
					j += count;
				}
			}
		}

		if ((method&F_AMPM) != 0 && cal[ZOF] == 2) cal[HOUR] += 12;

		long stamp = daySinceUnixZero(cal[YEAR], cal[MONTH], cal[DAY]);
		if ((method&F_YWD) != 0) stamp += ((cal[WEEK_OF_YEAR] - 1) * 7L + (cal[DAY_OF_WEEK] - 1));

		stamp *= 86400000L;

		stamp += cal[HOUR] * 3600000L;
		stamp += cal[MINUTE] * 60000L;
		stamp += cal[SECOND] * 1000L;
		stamp += cal[MILLISECOND];
		if (tz != null) stamp -= tz.getOffset(stamp);
		else if (cal[ZOF] != -1) stamp -= cal[ZOF];
		else if (timezone != null) stamp -= timezone.getOffset(stamp);

		return stamp;
	}
	private static int dateNum(CharSequence sb, int[] cal, int minLen, int maxLen, int min, int max) {
		int i = cal[IDX];
		int prevI = i;
		while (i < sb.length() && i-prevI < maxLen) {
			if (!Tokenizer.NUMBER.contains(sb.charAt(i))) break;
			i++;
		}

		if (i-prevI<minLen) throw new IllegalArgumentException("错误的时间范围 "+sb.subSequence(prevI, i)+" ["+minLen+","+maxLen+"]");

		int num = FastNumberParser.parseInt(sb, prevI, i);
		if (num < min || num > max) throw new IllegalArgumentException("错误的时间范围 "+num+" ["+min+","+max+"]");

		cal[IDX] = i;
		return num;
	}

	private static final ThreadLocal<int[]> SHARED_FIELDS = ThreadLocal.withInitial(() -> new int[AUXILIARY_FIELD_COUNT]);

	public static @MayMutate int[] getCalendar() {return getCalendar(System.currentTimeMillis());}
	public static @MayMutate int[] getCalendar(long timestamp) {return getCalendar(timestamp, SHARED_FIELDS.get());}
	/**
	 * 将时间戳解析到指定的日历字段数组中
	 * @param timestamp 毫秒时间戳
	 * @param fields 用于存储日历字段的数组
	 * @return 包含日历字段的数组
	 * @throws ArrayIndexOutOfBoundsException 如果数组长度小于CORE_FIELD_COUNT
	 * @throws ArithmeticException 如果时间早于1582年8月15日（格里历开始）
	 */
	public static int[] getCalendar(long timestamp, int[] fields) {
		if (fields.length < CORE_FIELD_COUNT) throw new ArrayIndexOutOfBoundsException(CORE_FIELD_COUNT);

		int totalDays = Math.toIntExact(timeOf(timestamp, fields)) + UNIX_ZERO;
		if (totalDays < MINIMUM_GREGORIAN_DAY) throw new ArithmeticException("My Calendar does not support time earlier than 1582/8/15");

		int year = fields[YEAR] = yearSinceAD(totalDays);
		int firstDayOfYear = daySinceAD(year, 1, 1);
		int daysOfYear = totalDays - firstDayOfYear;

		boolean leapYear;
		if (daysOfYear < 0) {
			fields[YEAR] --;
			fields[MONTH] = 12;
			fields[DAY] = 32 + daysOfYear;

			daysOfYear += 366;
			leapYear = isLeapYear(year);
		} else {
			leapYear = isLeapYear(year);
			// 至少3月1日
			if (daysOfYear >= 59/*SUMMED_DAYS[3]*/ + (leapYear ? 1 : 0)) {
				daysOfYear += leapYear ? 1 : 2;
			}

			int month = fields[MONTH] = (12 * daysOfYear + 373) / 367;

			int monthDays = firstDayOfYear + SUMMED_DAYS[month];
			if (month > 2 && leapYear) monthDays++;
			fields[DAY] = (totalDays - monthDays) + 1;
		}

		fields[LEAP_YEAR] = leapYear ? 1 : 0;

		if (fields.length > CORE_FIELD_COUNT) {
			fields[DAY_OF_YEAR] = daysOfYear;

			int dayOfWeek = (totalDays - 1) % 7;
			fields[DAY_OF_WEEK] = dayOfWeek + 1;

			int firstDayOfMonth = totalDays - (fields[DAY] - 1);
			int firstDayOfWeekMonth = (firstDayOfMonth - 1) % 7;
			fields[WEEK_OF_MONTH] = (fields[DAY] - 1 + firstDayOfWeekMonth) / 7 + 1;

			int firstDayOfWeekYear = (firstDayOfYear - 1) % 7;
			fields[WEEK_OF_YEAR] = (daysOfYear - 1 + firstDayOfWeekYear) / 7 + 1;

			int isoWeek;
			while (true) {
				// 今年第一个星期四
				int jan4th = daySinceAD(year, 1, 4);
				int dowJan4 = jan4th % 7;
				int firstThursdayOfYear = jan4th + (3 - dowJan4);

				// 最近的下一个星期四
				int vme50day = totalDays + (3 - dayOfWeek);

				isoWeek = ((vme50day - firstThursdayOfYear) / 7) + 1;

				if (vme50day >= firstThursdayOfYear) {
					if (isoWeek == 53) {
						// 处理跨年周的特殊情况
						int nextJan4th = daySinceAD(year + 1, 1, 4);
						int nextDowJan4 = nextJan4th % 7;
						int nextFirstThursday = nextJan4th + (3 - nextDowJan4);
						if (vme50day >= nextFirstThursday) {
							year++;
							isoWeek = 1;
						}
					}

					break;
				}
				// 属于上一年的周，这个循环最多执行两次
				else year--;
			}

			// format: 202253
			fields[ISO_WEEK_OF_YEAR] = year * 100 + isoWeek;
		}

		return fields;
	}

	/**
	 * 对日历字段进行规范化处理，确保字段值在合理范围内
	 * 例如：60秒会转换为+1分钟，32天会转换为下个月的第几天等
	 * 用途：加减之后修正日历字段
	 * @see Calendar#add(int, int)
	 * @param fields 需要规范化的日历字段数组
	 * @return 规范化后的日历字段数组
	 */
	public static int[] normalize(@MayMutate int[] fields) {
		int time, advance;

		time = fields[MILLISECOND];
		advance = time / 1000;
		if (time < 0) advance--;
		time -= 1000 * advance;
		fields[MILLISECOND] = time;

		time = fields[SECOND] + advance;
		advance = time / 60;
		if (time < 0) advance--;
		time -= 60 * advance;
		fields[SECOND] = time;

		time = fields[MINUTE] + advance;
		advance = time / 60;
		if (time < 0) advance--;
		time -= 60 * advance;
		fields[MINUTE] = time;

		time = fields[HOUR] + advance;
		advance = time / 24;
		if (time < 0) advance--;
		time -= 24 * advance;
		fields[HOUR] = time;

		time = fields[DAY] + advance;
		int monthDays = getMonthDays(fields);
		while (time > monthDays) {
			if (fields[MONTH] == 12) {
				fields[MONTH] = 1;
				fields[LEAP_YEAR] = isLeapYear(++fields[YEAR]) ? 1 : 0;
			} else {
				fields[MONTH]++;
			}
			time -= monthDays;
			monthDays = getMonthDays(fields);
		}
		while (time < 0) {
			if (fields[MONTH] == 1) {
				fields[MONTH] = 12;
				fields[LEAP_YEAR] = isLeapYear(--fields[YEAR]) ? 1 : 0;
			} else {
				fields[MONTH]--;
			}
			time += monthDays;
			monthDays = getMonthDays(fields);
		}
		fields[DAY] = time;

		time = fields[MONTH];
		advance = (time - (time > 0 ? 1 : 11)) / 12;
		time -= 12 * advance;
		fields[MONTH] = time;

		fields[YEAR] += advance;

		if (fields.length > CORE_FIELD_COUNT) {
			fields[DAY_OF_WEEK] = -1;
			fields[DAY_OF_YEAR] = -1;
		}
		fields[LEAP_YEAR] = isLeapYear(fields[YEAR]) ? 1 : 0;

		return fields;
	}

	/**
	 * 将日历字段数组转换为时间戳
	 * @param fields 包含年月日时分秒毫秒的日历字段数组
	 * @return 对应的毫秒时间戳
	 */
	public static long toMillis(int[] fields) {
		return daySinceUnixZero(fields[YEAR], fields[MONTH], fields[DAY]) * 86400000L + fields[HOUR] * 3600000L + fields[MINUTE] * 60000L + fields[SECOND] * 1000L + fields[MILLISECOND];
	}
	//region 日历字段辅助函数
	private static final int MINIMUM_GREGORIAN_DAY = 577736; // Fixed day of 1582/8/15
	private static final int UNIX_ZERO = 719163; // Fixed day of 1970/1/1 (Gregorian)

	/**
	 * 计算Unix零纪元至year-month-day过去的天数.
	 * 年份使用了si32，所以它会在遥远的未来，而不是“更加遥远”的未来溢出：约588万年
	 * 作为对比，si64的毫秒时间戳将在2.9亿年后溢出
	 * @param year 大于等于1的年数
	 * @param month 从1开始的月数
	 * @param day 从1开始的天数
	 * @return 合计天数
	 */
	public static int daySinceUnixZero(int year, int month, int day) {return daySinceAD(year, month, day) - UNIX_ZERO;}

	private static long timeOf(long date, int[] fields) {
		long q;

		if (date >= 0) {
			q = date / 1000;
			fields[MILLISECOND] = (int) (date - q * 1000);
			date = q;

			q = date / 60;
			fields[SECOND] = (int) (date - q * 60);
			date = q;

			q = date / 60;
			fields[MINUTE] = (int) (date - q * 60);
			date = q;

			q = date / 24;
		} else {
			q = (date + 1) / 1000 - 1;
			fields[MILLISECOND] = (int) (date - q * 1000);
			date = q;

			q = (date + 1) / 60 - 1;
			fields[SECOND] = (int) (date - q * 60);
			date = q;

			q = (date + 1) / 60 - 1;
			fields[MINUTE] = (int) (date - q * 60);
			date = q;

			q = (date + 1) / 24 - 1;
		}
		fields[HOUR] = (int) (date - q * 24);
		date = q;
		return date;
	}
	private static int yearSinceAD(long date) {
		int tmp400 = (int) (date / 146097);
		int datei = (int) (date - tmp400 * 146097L);
		int years = 400 * tmp400;

		int tmp100 = datei / 36524;
		datei -= tmp100 * 36524;
		years += 100 * tmp100;

		int tmp4 = datei / 1461;
		datei -= tmp4 * 1461;
		years += 4 * tmp4;

		years += datei / 365;

		if (tmp100 != 4 && years != 4) years++;

		return years;
	}

	/**
	 * 每月的天数
	 */
	private static final int[] SUMMED_DAYS = new int[] {-30, 0, 31, 59, 90, 120, 151, 181, 212, 243, 273, 304, 334};
	private static int daySinceAD(int year, int month, int day) {
		int yearm1 = year-1;
		return yearm1*365 + yearm1/4 - yearm1/100 + yearm1/400 // with leap days
				+ day + (SUMMED_DAYS[month] + (month > 2 && isLeapYear(year) ? 1 : 0));
	}

	private static boolean isLeapYear(int year) { return (year & 3) == 0 && (year % 100 != 0 || year % 400 == 0); }

	private static int getMonthDays(int[] fields) {
		int month = fields[MONTH];
		return month == 2 ? 28 + fields[LEAP_YEAR] : ((month & 1) != 0) == month < 8 ? 31 : 30;
	}
	//endregion
	//region 日期格式化(实例版)
	private static final String[]
			UTCWEEK = {"Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"},
			UTCMONTH = {"Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"};

	public String format(long timestampMillis) {return format(timestampMillis, IOUtil.getSharedCharBuf()).toString();}
	public CharList format(long timestampMillis, CharList sb) {return format(timestampMillis, sb, timezone);}
	public CharList format(long timestampMillis, CharList sb, TimeZone timezone) {
		if (timezone != null) timestampMillis += timezone.getOffset(timestampMillis);
		int[] fields = getCalendar(timestampMillis);

		int charI = 0;
		for (int j = 0; j < formats.length; j++) {
			int id = formats[j];
			switch (id) {
				case -1 -> sb.append(chars[charI++]);

				case 0 -> sb.append(fields[YEAR]);
				case 1 -> sb.append(fields[YEAR] % 100);

				case 2 -> sb.append(fields[MONTH]);
				case 3 -> sb.padNumber(fields[MONTH], 2);
				case 4 -> sb.append(UTCMONTH[fields[MONTH]-1]);

				case 5 -> sb.append(fields[DAY]);
				case 6 -> sb.padNumber(fields[DAY], 2);

				case 7 -> sb.append(fields[DAY_OF_WEEK]);
				case 8 -> sb.append(UTCWEEK[fields[DAY_OF_WEEK]-1]);
				case 9 -> sb.append("星期").append(fields[DAY_OF_WEEK] == 7 ? '日' : ChinaNumeric.NUMBER[fields[DAY_OF_WEEK]]);

				case 10 -> sb.append(fields[WEEK_OF_YEAR]);
				case 11 -> sb.padNumber(fields[WEEK_OF_YEAR], 2);

				case 12 -> sb.append(fields[HOUR]);
				case 13 -> sb.padNumber(fields[HOUR], 2);
				case 14 -> sb.append(fields[HOUR] == 0 ? 12 : fields[HOUR] % 12);
				case 15 -> sb.padNumber(fields[HOUR] == 0 ? 12 : fields[HOUR] % 12, 2);

				case 16 -> sb.append(fields[HOUR] > 11 ? "pm" : "am");
				case 25 -> sb.append(fields[HOUR] > 11 ? "PM" : "AM");

				case 17 -> sb.append(fields[MINUTE]);
				case 18 -> sb.padNumber(fields[MINUTE], 2);

				case 19 -> sb.append(fields[SECOND]);
				case 20 -> sb.padNumber(fields[SECOND], 2);

				case 21 -> sb.append(fields[MILLISECOND]);
				case 22 -> sb.padNumber(fields[MILLISECOND], 3);

				case 23 -> {
					if (tzoff(timezone, timestampMillis, sb)) sb.append('Z');
				}
				case 24 -> sb.append(timezone == null ? "GMT" : timezone.getID());
				case 27 -> {
					int value = fields[formats[j++]];
					int count = formats[j++];

					int i = 0;
					error: {
						for (; i < count; i++) {
							if (formats[i + j] == value) break error;
						}
						throw new IllegalArgumentException("Delim error");
					}

					sb.append(chars[charI + i]);
					charI += count;
					j += count;
				}
			}
		}
		return sb;
	}
	private static boolean tzoff(TimeZone zone, long stamp, CharList sb) {
		int offset;
		if (zone == null || (offset = zone.getOffset(stamp)) == 0) return true;

		offset /= 60000;

		sb.append(offset>0?'+':'-')
				.padNumber(Math.abs(offset / 60), 2)
				.append(':')
				.padNumber(offset % 60, 2);
		return false;
	}
	//endregion
	//region 日期格式化(简易版)
	public static @MayMutate CharList format(String format, long timestampMillis) { return format(format, timestampMillis, IOUtil.getSharedCharBuf()); }
	public static @MayMutate CharList format(String format, long timestampMillis, TimeZone timezone) { return format(format, timestampMillis, timezone, IOUtil.getSharedCharBuf()); }

	public static CharList format(String format, long timestampMillis, CharList sb) {return format(format, timestampMillis, null, sb);}
	public static CharList format(String format, long timestampMillis, TimeZone timezone, CharList sb) {
		if (timezone != null) timestampMillis += timezone.getOffset(timestampMillis);
		int[] fields = getCalendar(timestampMillis);
		char c;
		for (int i = 0; i < format.length(); i++) {
			switch (c = format.charAt(i)) {
				case 'Y' -> sb.append(fields[YEAR]);
				case 'y' -> sb.append(fields[YEAR] % 100);

				case 'L' -> sb.append(fields[LEAP_YEAR]);

				case 'M' -> sb.append(UTCMONTH[fields[MONTH] - 1]);
				case 'm' -> sb.padNumber(fields[MONTH], 2);
				case 'n' -> sb.append(fields[MONTH]);

				case 't' -> sb.append(getMonthDays(fields));

				case 'd' -> sb.padNumber(fields[DAY], 2);
				case 'j' -> sb.append(fields[DAY]);
				case 'D' -> sb.append(timestampMillis / 86400000L);

				case 'l' -> sb.append("星期").append(ChinaNumeric.NUMBER[fields[DAY_OF_WEEK]]);
				case 'W' -> sb.append(UTCWEEK[fields[DAY_OF_WEEK]-1]);
				case 'w' -> sb.append(fields[DAY_OF_WEEK]-1);

				case 'H' -> sb.padNumber(fields[HOUR], 2);
				case 'G' -> sb.append(fields[HOUR]);

				case 'A' -> sb.append(fields[HOUR] > 11 ? "PM" : "AM");
				case 'a' -> sb.append(fields[HOUR] > 11 ? "pm" : "am");

				case 'h' -> {
					int h = fields[HOUR] % 12;
					sb.padNumber(h == 0 ? 12 : h, 2);
				}
				case 'g' -> {// am/pm时间
					int h = fields[HOUR] % 12;
					sb.append(h == 0 ? 12 : h);
				}

				case 'i' -> sb.padNumber(fields[MINUTE], 2);
				case 's' -> sb.padNumber(fields[SECOND], 2);
				case 'x' -> sb.padNumber(fields[MILLISECOND], 3);

				// unix秒时间戳
				case 'U' -> sb.append(timestampMillis / 1000);

				case 'O', 'P' -> {// TimeZone offset
					if (DateFormat.tzoff(timezone, timestampMillis, sb)) sb.append(c == 'P' ? "Z" : "GMT");
				}

				case 'c' -> format(ISO8601_Seconds, timestampMillis, sb);
				default -> sb.append(c);
			}
		}
		return sb;
	}
	//endregion
	//region 常见的标准格式 (ISO 8601和RFC 5322)
	/**
	 * <a href="https://baike.baidu.com/item/ISO%208601/3910715">ISO8601时间戳</a>
	 * <p>例如："2020-07-14T07:59:08+08:00"
	 */
	public static final String ISO8601_Millis = "Y-m-dTH:i:s.xP", ISO8601_Seconds = "Y-m-dTH:i:sP";
	/**
	 * 将ISO8601格式的字符串解析为毫秒时间戳
	 * @see Tokenizer#ISO8601Datetime
	 * @param seq ISO8601格式的日期时间字符串
	 * @return 毫秒时间戳
	 */
	public static long parseISO8601Datetime(CharSequence seq) {
		try {
			return new Tokenizer().init(seq).ISO8601Datetime(true).asLong();
		} catch (ParseException e) {
			throw new IllegalArgumentException(e.toString());
		}
	}

	/**
	 * 将毫秒时间戳转换为ISO8601格式字符串
	 * @see #parseISO8601Datetime(CharSequence)
	 * @see #ISO8601_Millis
	 * @param timestampMillis 毫秒时间戳
	 * @return ISO8601格式的日期时间字符串
	 */
	public static String toISO8601Datetime(long timestampMillis) {return toISO8601Datetime(new CharList(), timestampMillis).toStringAndFree();}
	public static CharList toISO8601Datetime(CharList sb, long timestampMillis) {return format(timestampMillis%1000 != 0 ? ISO8601_Millis : ISO8601_Seconds, timestampMillis, sb);}

	/**
	 * <a href="https://www.rfc-editor.org/rfc/rfc5322#section-3.3">RFC5322</a>时间戳
	 * <p>例如："Tue, 25 Feb 2022 21:48:10 GMT"
	 */
	public static final String RFC5322Datetime = "W, d M Y H:i:s O";
	/**
	 * 将RFC5322格式的字符串解析为毫秒时间戳
	 * @see #toRFC5322Datetime(long)
	 * @param seq RFC5322格式的日期时间字符串
	 * @return 毫秒时间戳
	 */
	@SuppressWarnings("fallthrough")
	public static long parseRFC5322Datetime(CharSequence seq) {
		String str = seq.subSequence(0, 3).toString();
		String[] x = UTCWEEK;
		int week = 0;
		while (week < x.length) {
			if (str.equals(x[week++])) {
				break;
			}
		}
		if (week == 8) throw new IllegalArgumentException("无效的周"+str);
		if (seq.charAt(3) != ',' || seq.charAt(4) != ' ') throw new IllegalArgumentException("分隔符错误");
		int day = FastNumberParser.parseInt(seq, 5, 7);
		if (seq.charAt(7) != ' ') throw new IllegalArgumentException("分隔符错误");

		str = seq.subSequence(8, 11).toString();
		x = UTCMONTH;
		int month = 0;
		while (month < x.length) {
			if (str.equals(x[month++])) {
				break;
			}
		}
		if (month == 13) throw new IllegalArgumentException("无效的月"+str);

		if (seq.charAt(11) != ' ') throw new IllegalArgumentException("分隔符错误");

		int year = FastNumberParser.parseInt(seq, 12, 16);
		if (seq.charAt(16) != ' ') throw new IllegalArgumentException("分隔符错误");
		int h = FastNumberParser.parseInt(seq, 17, 19);
		if (seq.charAt(19) != ':') throw new IllegalArgumentException("分隔符错误");
		int m = FastNumberParser.parseInt(seq, 20, 22);
		if (seq.charAt(22) != ':') throw new IllegalArgumentException("分隔符错误");
		int s = FastNumberParser.parseInt(seq, 23, 25);
		if (seq.charAt(25) != ' ') throw new IllegalArgumentException("分隔符错误");

		if (h > 23) throw new IllegalArgumentException("你一天"+h+"小时");
		if (m > 59) throw new IllegalArgumentException("你一小时"+m+"分钟");
		if (s > 59) throw new IllegalArgumentException("你一分钟"+s+"秒");

		long a = daySinceUnixZero(year, month, day) * 86400000L + h * 3600000L + m * 60000L + s * 1000L;

		int i = -1;
		switch (seq.charAt(26)) {
			case '-':
				i = 1;
			case '+':
				i *= FastNumberParser.parseInt(seq, 27, 31);
				int d = i % 100;
				if (d < -59 || d > 59) throw new IllegalArgumentException("你一小时"+d+"分钟");
				a += 60000 * d;

				d = i / 100;
				if (d < -23 || d > 23) throw new IllegalArgumentException("你一天"+d+"小时");
				a += 3600000 * d;

				break;
			case 'G':
				if (seq.charAt(27) != 'M' || seq.charAt(28) != 'T') throw new IllegalArgumentException("分隔符错误");
				break;
			default:
				throw new IllegalArgumentException("分隔符错误");
		}

		return a;
	}
	/**
	 * 将毫秒时间戳转换为RFC5322格式的字符串
	 * @see #parseRFC5322Datetime(CharSequence)
	 * @see #RFC5322Datetime
	 * @param timestampMillis 毫秒时间戳
	 * @return RFC5322格式的日期时间字符串
	 */
	public static String toRFC5322Datetime(long timestampMillis) {return toRFC5322Datetime(new CharList(),timestampMillis).toStringAndFree();}
	public static CharList toRFC5322Datetime(CharList sb, long timestampMillis) {return format(RFC5322Datetime, timestampMillis, sb);}
	//endregion
	/**
	 * 将时间戳转换为本地日期时间字符串(建议用于调试输出, 格式可能随时修改)
	 * @param timestampMillis 毫秒时间戳
	 * @return 本地化的日期时间字符串
	 */
	@ApiStatus.Internal
	public static String toLocalDateTime(long timestampMillis) {
		var tz = getLocalTimeZone();
		return format("Y-m-d H:i:s.x (l)", timestampMillis, tz, new CharList()).append(" (").append(tz.getDisplayName()).append(')').toStringAndFree();
	}

	private static final int[]
			RT_STEP = {60, 1800, 3600, 86400, 604800, 2592000, 15552000},
			RT_DIVISION = {60, 1, 60, 24, 7, -2592000, 6};
	private static final String[] RT_NAME = {" 秒前", " 分前", "半小时前", " 小时前", " 天前", " 周前", " 月前"};
	/**
	 * 格式化相对时间（如"5分钟前"）
	 * @param timestampMillis 毫秒时间戳
	 * @return 相对时间字符串
	 */
	public static String formatRelativeTime(long timestampMillis) {
		long diff = System.currentTimeMillis() - timestampMillis;
		if (diff < 0) return diff+"ms";
		if (diff < 1000) return "刚刚";

		double val = diff;
		boolean flag = false;
		for (int i = 0; i < RT_STEP.length; i++) {
			int time = RT_STEP[i];
			if (diff < time) {
				return flag ? RT_NAME[i] : Math.round(val) + RT_NAME[i];
			}
			int dt = RT_DIVISION[i];
			flag = dt == 1;
			if (dt < 0) {
				val = (double) time / (-dt);
			} else {
				val /= dt;
			}
		}
		return format("Y-m-d H:i:s", timestampMillis, getLocalTimeZone()).toString();
	}
}