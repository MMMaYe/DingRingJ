package com.dingring.common.util;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.Date;

/**
 * 时间工具类：基于 {@code java.time} 的常用时间转换与操作封装。
 * <p>项目内统一使用 JDK 8+ 时间 API（{@code LocalDateTime / LocalDate / Instant}），
 * 仅在对接遗留系统（MyBatis {@code java.util.Date}、前端时间字符串）时做转换。</p>
 * <p>默认日期格式：{@code yyyy-MM-dd}，默认日期时间格式：{@code yyyy-MM-dd HH:mm:ss}。</p>
 */
public final class DateUtil {

    /** 默认日期格式 */
    public static final String DATE_PATTERN = "yyyy-MM-dd";
    /** 默认日期时间格式 */
    public static final String DATETIME_PATTERN = "yyyy-MM-dd HH:mm:ss";
    /** 紧凑日期时间格式（无分隔符） */
    public static final String DATETIME_COMPACT_PATTERN = "yyyyMMddHHmmss";

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern(DATE_PATTERN);
    private static final DateTimeFormatter DATETIME_FORMATTER = DateTimeFormatter.ofPattern(DATETIME_PATTERN);
    private static final DateTimeFormatter DATETIME_COMPACT_FORMATTER = DateTimeFormatter.ofPattern(DATETIME_COMPACT_PATTERN);

    /** 系统默认时区（Asia/Shanghai） */
    public static final ZoneId DEFAULT_ZONE = ZoneId.of("Asia/Shanghai");

    private DateUtil() {
        throw new UnsupportedOperationException("工具类不允许实例化");
    }

    // ======================== ① 字符串 ↔ LocalDateTime / LocalDate ========================

    /**
     * 将字符串解析为 {@link LocalDateTime}，默认格式 {@code yyyy-MM-dd HH:mm:ss}。
     *
     * @param text 时间字符串，如 "2025-06-01 14:30:00"
     * @return 解析失败时返回 {@code null}
     */
    public static LocalDateTime parseDateTime(String text) {
        return parseDateTime(text, DATETIME_FORMATTER);
    }

    /**
     * 将字符串按指定格式解析为 {@link LocalDateTime}。
     *
     * @param text    时间字符串
     * @param pattern 格式，如 "yyyy/MM/dd HH:mm"
     * @return 解析失败时返回 {@code null}
     */
    public static LocalDateTime parseDateTime(String text, String pattern) {
        return parseDateTime(text, DateTimeFormatter.ofPattern(pattern));
    }

    /**
     * 将字符串按指定 {@link DateTimeFormatter} 解析为 {@link LocalDateTime}。
     *
     * @param text      时间字符串
     * @param formatter 格式化器
     * @return 解析失败时返回 {@code null}
     */
    public static LocalDateTime parseDateTime(String text, DateTimeFormatter formatter) {
        try {
            return LocalDateTime.parse(text, formatter);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 将字符串解析为 {@link LocalDate}，默认格式 {@code yyyy-MM-dd}。
     *
     * @param text 日期字符串，如 "2025-06-01"
     * @return 解析失败时返回 {@code null}
     */
    public static LocalDate parseDate(String text) {
        return parseDate(text, DATE_FORMATTER);
    }

    /**
     * 将字符串按指定格式解析为 {@link LocalDate}。
     *
     * @param text    日期字符串
     * @param pattern 格式，如 "yyyy/MM/dd"
     * @return 解析失败时返回 {@code null}
     */
    public static LocalDate parseDate(String text, String pattern) {
        return parseDate(text, DateTimeFormatter.ofPattern(pattern));
    }

    /**
     * 将字符串按指定 {@link DateTimeFormatter} 解析为 {@link LocalDate}。
     *
     * @param text      日期字符串
     * @param formatter 格式化器
     * @return 解析失败时返回 {@code null}
     */
    public static LocalDate parseDate(String text, DateTimeFormatter formatter) {
        try {
            return LocalDate.parse(text, formatter);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 将 {@link LocalDateTime} 格式化为默认日期时间字符串 {@code yyyy-MM-dd HH:mm:ss}。
     *
     * @param dateTime 时间对象
     * @return 格式化字符串；参数为 {@code null} 时返回 {@code null}
     */
    public static String format(LocalDateTime dateTime) {
        return format(dateTime, DATETIME_FORMATTER);
    }

    /**
     * 将 {@link LocalDate} 格式化为默认日期字符串 {@code yyyy-MM-dd}。
     *
     * @param date 日期对象
     * @return 格式化字符串；参数为 {@code null} 时返回 {@code null}
     */
    public static String format(LocalDate date) {
        return format(date, DATE_FORMATTER);
    }

    /**
     * 将 {@link LocalDateTime} 按指定格式格式化。
     *
     * @param dateTime 时间对象
     * @param pattern  格式，如 "yyyy/MM/dd HH:mm"
     * @return 格式化字符串；参数为 {@code null} 时返回 {@code null}
     */
    public static String format(LocalDateTime dateTime, String pattern) {
        return format(dateTime, DateTimeFormatter.ofPattern(pattern));
    }

    /**
     * 将 {@link LocalDateTime} 按指定 {@link DateTimeFormatter} 格式化。
     *
     * @param dateTime  时间对象
     * @param formatter 格式化器
     * @return 格式化字符串；参数为 {@code null} 时返回 {@code null}
     */
    public static String format(LocalDateTime dateTime, DateTimeFormatter formatter) {
        if (dateTime == null) {
            return null;
        }
        return dateTime.format(formatter);
    }

    /**
     * 将 {@link LocalDate} 按指定格式格式化。
     *
     * @param date    日期对象
     * @param pattern 格式，如 "yyyy/MM/dd"
     * @return 格式化字符串；参数为 {@code null} 时返回 {@code null}
     */
    public static String format(LocalDate date, String pattern) {
        return format(date, DateTimeFormatter.ofPattern(pattern));
    }

    /**
     * 将 {@link LocalDate} 按指定 {@link DateTimeFormatter} 格式化。
     *
     * @param date      日期对象
     * @param formatter 格式化器
     * @return 格式化字符串；参数为 {@code null} 时返回 {@code null}
     */
    public static String format(LocalDate date, DateTimeFormatter formatter) {
        if (date == null) {
            return null;
        }
        return date.format(formatter);
    }

    // ======================== ② LocalDateTime / LocalDate ↔ java.util.Date ========================

    /**
     * 将 {@link LocalDateTime} 转换为 {@link Date}（系统默认时区）。
     *
     * @param dateTime 时间对象
     * @return Date 对象；参数为 {@code null} 时返回 {@code null}
     */
    public static Date toDate(LocalDateTime dateTime) {
        if (dateTime == null) {
            return null;
        }
        Instant instant = dateTime.atZone(DEFAULT_ZONE).toInstant();
        return Date.from(instant);
    }

    /**
     * 将 {@link LocalDate} 转换为 {@link Date}（系统默认时区，时间为当天 00:00:00）。
     *
     * @param date 日期对象
     * @return Date 对象；参数为 {@code null} 时返回 {@code null}
     */
    public static Date toDate(LocalDate date) {
        if (date == null) {
            return null;
        }
        return toDate(date.atStartOfDay(DEFAULT_ZONE).toLocalDateTime());
    }

    /**
     * 将 {@link Date} 转换为 {@link LocalDateTime}（系统默认时区）。
     *
     * @param date 遗留 Date 对象
     * @return LocalDateTime 对象；参数为 {@code null} 时返回 {@code null}
     */
    public static LocalDateTime toLocalDateTime(Date date) {
        if (date == null) {
            return null;
        }
        return date.toInstant().atZone(DEFAULT_ZONE).toLocalDateTime();
    }

    /**
     * 将 {@link Date} 转换为 {@link LocalDate}（系统默认时区）。
     *
     * @param date 遗留 Date 对象
     * @return LocalDate 对象；参数为 {@code null} 时返回 {@code null}
     */
    public static LocalDate toLocalDate(Date date) {
        if (date == null) {
            return null;
        }
        return date.toInstant().atZone(DEFAULT_ZONE).toLocalDate();
    }

    // ======================== ③ LocalDateTime ↔ 时间戳（毫秒/秒） ========================

    /**
     * 将 {@link LocalDateTime} 转换为毫秒时间戳。
     *
     * @param dateTime 时间对象
     * @return 毫秒时间戳；参数为 {@code null} 时返回 {@code null}
     */
    public static Long toEpochMilli(LocalDateTime dateTime) {
        if (dateTime == null) {
            return null;
        }
        return dateTime.atZone(DEFAULT_ZONE).toInstant().toEpochMilli();
    }

    /**
     * 将 {@link LocalDateTime} 转换为秒级时间戳。
     *
     * @param dateTime 时间对象
     * @return 秒级时间戳；参数为 {@code null} 时返回 {@code null}
     */
    public static Long toEpochSecond(LocalDateTime dateTime) {
        if (dateTime == null) {
            return null;
        }
        return dateTime.atZone(DEFAULT_ZONE).toEpochSecond();
    }

    /**
     * 将毫秒时间戳转换为 {@link LocalDateTime}（系统默认时区）。
     *
     * @param epochMilli 毫秒时间戳
     * @return LocalDateTime 对象
     */
    public static LocalDateTime fromEpochMilli(long epochMilli) {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMilli), DEFAULT_ZONE);
    }

    /**
     * 将秒级时间戳转换为 {@link LocalDateTime}（系统默认时区）。
     *
     * @param epochSecond 秒级时间戳
     * @return LocalDateTime 对象
     */
    public static LocalDateTime fromEpochSecond(long epochSecond) {
        return LocalDateTime.ofInstant(Instant.ofEpochSecond(epochSecond), DEFAULT_ZONE);
    }

    // ======================== ④ 时间边界（当天/本周/本月 开始 & 结束） ========================

    /**
     * 获取指定日期的开始时间 {@code 00:00:00}。
     *
     * @param dateTime 时间对象
     * @return 当天 00:00:00
     */
    public static LocalDateTime dayStart(LocalDateTime dateTime) {
        return dateTime.toLocalDate().atStartOfDay();
    }

    /**
     * 获取指定日期的结束时间 {@code 23:59:59.999999999}。
     *
     * @param dateTime 时间对象
     * @return 当天 23:59:59.999999999
     */
    public static LocalDateTime dayEnd(LocalDateTime dateTime) {
        return dateTime.toLocalDate().atTime(LocalTime.MAX);
    }

    /**
     * 获取当前时间的当天开始时间。
     *
     * @return 当天 00:00:00
     */
    public static LocalDateTime todayStart() {
        return LocalDate.now(DEFAULT_ZONE).atStartOfDay();
    }

    /**
     * 获取当前时间的当天结束时间。
     *
     * @return 当天 23:59:59.999999999
     */
    public static LocalDateTime todayEnd() {
        return LocalDate.now(DEFAULT_ZONE).atTime(LocalTime.MAX);
    }

    /**
     * 获取当前时间所在周的开始时间（周一 00:00:00）。
     *
     * @return 本周一 00:00:00
     */
    public static LocalDateTime weekStart() {
        return LocalDate.now(DEFAULT_ZONE)
                .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                .atStartOfDay();
    }

    /**
     * 获取当前时间所在周的开始时间（周一 00:00:00）。
     *
     * @param dateTime 参考时间
     * @return 参考时间所在周周一 00:00:00
     */
    public static LocalDateTime weekStart(LocalDateTime dateTime) {
        return dateTime.toLocalDate()
                .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                .atStartOfDay();
    }

    /**
     * 获取当前时间所在月的开始时间（1 号 00:00:00）。
     *
     * @return 本月 1 号 00:00:00
     */
    public static LocalDateTime monthStart() {
        return LocalDate.now(DEFAULT_ZONE).withDayOfMonth(1).atStartOfDay();
    }

    /**
     * 获取指定时间所在月的开始时间（1 号 00:00:00）。
     *
     * @param dateTime 参考时间
     * @return 参考时间所在月 1 号 00:00:00
     */
    public static LocalDateTime monthStart(LocalDateTime dateTime) {
        return dateTime.toLocalDate().withDayOfMonth(1).atStartOfDay();
    }

    // ======================== ⑤ 时间差计算 ========================

    /**
     * 计算两个时间相差的天数（绝对值）。
     *
     * @param start 开始时间
     * @param end   结束时间
     * @return 相差天数，任一参数为 {@code null} 时返回 0
     */
    public static long daysBetween(LocalDateTime start, LocalDateTime end) {
        if (start == null || end == null) {
            return 0;
        }
        return Math.abs(ChronoUnit.DAYS.between(start.toLocalDate(), end.toLocalDate()));
    }

    /**
     * 计算两个时间相差的小时数（绝对值）。
     *
     * @param start 开始时间
     * @param end   结束时间
     * @return 相差小时数，任一参数为 {@code null} 时返回 0
     */
    public static long hoursBetween(LocalDateTime start, LocalDateTime end) {
        if (start == null || end == null) {
            return 0;
        }
        return Math.abs(ChronoUnit.HOURS.between(start, end));
    }

    /**
     * 计算两个时间相差的分钟数（绝对值）。
     *
     * @param start 开始时间
     * @param end   结束时间
     * @return 相差分钟数，任一参数为 {@code null} 时返回 0
     */
    public static long minutesBetween(LocalDateTime start, LocalDateTime end) {
        if (start == null || end == null) {
            return 0;
        }
        return Math.abs(ChronoUnit.MINUTES.between(start, end));
    }

    /**
     * 计算两个时间相差的秒数（绝对值）。
     *
     * @param start 开始时间
     * @param end   结束时间
     * @return 相差秒数，任一参数为 {@code null} 时返回 0
     */
    public static long secondsBetween(LocalDateTime start, LocalDateTime end) {
        if (start == null || end == null) {
            return 0;
        }
        return Math.abs(ChronoUnit.SECONDS.between(start, end));
    }

    // ======================== ⑥ 判断辅助 ========================

    /**
     * 判断两个时间是否在同一天。
     *
     * @param t1 时间 1
     * @param t2 时间 2
     * @return 是否同一天；任一参数为 {@code null} 时返回 {@code false}
     */
    public static boolean isSameDay(LocalDateTime t1, LocalDateTime t2) {
        if (t1 == null || t2 == null) {
            return false;
        }
        return t1.toLocalDate().equals(t2.toLocalDate());
    }

    /**
     * 判断指定时间是否在今天。
     *
     * @param dateTime 待判断时间
     * @return 是否今天；参数为 {@code null} 时返回 {@code false}
     */
    public static boolean isToday(LocalDateTime dateTime) {
        if (dateTime == null) {
            return false;
        }
        return dateTime.toLocalDate().equals(LocalDate.now(DEFAULT_ZONE));
    }

    /**
     * 判断指定时间是否在 {@code past} 之后且在同一周内。
     *
     * @param dateTime 待判断时间
     * @return 是否本周；参数为 {@code null} 时返回 {@code false}
     */
    public static boolean isThisWeek(LocalDateTime dateTime) {
        if (dateTime == null) {
            return false;
        }
        LocalDate today = LocalDate.now(DEFAULT_ZONE);
        LocalDate weekStart = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        return !dateTime.toLocalDate().isBefore(weekStart) && !dateTime.toLocalDate().isAfter(today);
    }

    // ======================== ⑦ 当前时间快捷获取 ========================

    /**
     * 获取当前日期时间（系统默认时区）。
     *
     * @return 当前 LocalDateTime
     */
    public static LocalDateTime now() {
        return LocalDateTime.now(DEFAULT_ZONE);
    }

    /**
     * 获取当前日期（系统默认时区）。
     *
     * @return 当前 LocalDate
     */
    public static LocalDate today() {
        return LocalDate.now(DEFAULT_ZONE);
    }

    /**
     * 获取当前时间字符串，格式 {@code yyyy-MM-dd HH:mm:ss}。
     *
     * @return 当前时间字符串
     */
    public static String nowStr() {
        return now().format(DATETIME_FORMATTER);
    }

    /**
     * 获取当前日期字符串，格式 {@code yyyy-MM-dd}。
     *
     * @return 当前日期字符串
     */
    public static String todayStr() {
        return today().format(DATE_FORMATTER);
    }
}