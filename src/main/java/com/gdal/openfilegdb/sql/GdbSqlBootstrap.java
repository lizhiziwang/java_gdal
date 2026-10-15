package com.gdal.openfilegdb.sql;

/**
 * Calcite 的字符集引导。
 *
 * <p><b>为什么需要它</b>:Calcite 的 CHAR/VARCHAR 字面量字符集取自
 * {@code CalciteSystemProperty.DEFAULT_CHARSET},即系统属性
 * {@code calcite.default.charset},默认值是 <b>ISO-8859-1</b>。这个默认值在
 * Calcite 的 {@code Util} 类初始化时被读进静态常量,之后改不动。后果是任何非
 * Latin-1 的字面量都会炸:
 * <pre>
 *   SELECT * FROM 村行政区划 WHERE 名称 = '北京'
 *   -> CalciteException: Failed to encode '北京' in character set 'ISO-8859-1'
 * </pre>
 * JDBC 连接属性里的 {@code charset=UTF-8} 只影响结果集字符串解码,
 * <b>不影响</b>解析期字面量的字符集,所以必须从系统属性下手。
 *
 * <p><b>做法</b>:在触碰任何 Calcite 类之前(第一次 {@code executeSql} 的第一行)
 * 把系统属性补上。本类<b>刻意不 import 任何 Calcite 类型</b> —— 只要它的静态初始化
 * 先跑,{@code Util} 还没有被加载,属性就来得及生效。
 * 用户若已自行设置(比如显式要 Latin-1),本库不覆盖。
 */
final class GdbSqlBootstrap {

    /** 默认字符集属性名(对应 {@code CalciteSystemProperty.DEFAULT_CHARSET})。 */
    private static final String DEFAULT_CHARSET_PROPERTY = "calcite.default.charset";

    /** 国家字符集属性名(对应{@code N'...'} 字面量)。 */
    private static final String NATIONAL_CHARSET_PROPERTY = "calcite.default.nationalcharset";

    private static final String UTF8 = "UTF-8";

    private GdbSqlBootstrap() {
        // 工具类,不实例化
    }

    /**
     * 确保 Calcite 的字面量字符集是 UTF-8(只补不覆盖)。
     *
     * <p>调用点必须在任何 Calcite 类型被加载之前 —— 目前是
     * {@link GdbSqlEngine#execute} 的第一条语句。
     *
     * @return 是否由本方法完成了设置
     */
    static synchronized boolean ensureUtf8() {
        boolean changed = false;
        if (System.getProperty(DEFAULT_CHARSET_PROPERTY) == null) {
            System.setProperty(DEFAULT_CHARSET_PROPERTY, UTF8);
            changed = true;
        }
        if (System.getProperty(NATIONAL_CHARSET_PROPERTY) == null) {
            System.setProperty(NATIONAL_CHARSET_PROPERTY, UTF8);
            changed = true;
        }
        return changed;
    }
}
