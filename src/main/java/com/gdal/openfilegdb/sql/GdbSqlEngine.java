package com.gdal.openfilegdb.sql;

import com.gdal.openfilegdb.exception.GdbSqlException;
import com.gdal.openfilegdb.layer.OpenFileGdbLayer;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import org.apache.calcite.jdbc.CalciteConnection;
import org.apache.calcite.schema.SchemaPlus;

/**
 * {@code executeSql} 的执行引擎:把数据集的图层挂到 Apache Calcite 上执行 SQL。
 *
 * <p>对标 GDAL {@code GDALDataset::ExecuteSQL(pszSQL, poSpatialFilter, pszDialect)}
 * —— GDAL 里那套由 {@code OGRGenSQLResultsLayer}({@code ogr_gensql.cpp})驱动的
 * "SELECT 子集 + 逐条拉取"语义,在这里由 Calcite 的流式执行管线承载:
 * 简单 {@code SELECT/WHERE/LIMIT} 与聚合计数全程不物化,{@code ORDER BY} /
 * {@code DISTINCT} / {@code GROUP BY} 按 SQL 语义物化(与 GDAL 同款取舍)。
 *
 * <h2>方言</h2>
 * GDAL 有 OGRSQL / SQLITE 两种方言;本引擎用的是 <b>Calcite SQL</b>
 * (SQL:92 级别的标准语法,支持 {@code WITH} / 窗口函数 / {@code CAST} /
 * {@code LIKE} / {@code IN} / 子查询 / {@code UNION} 等,比 OGR SQL 的子集大)。
 * 与 OGR SQL 的差异:没有 {@code fid} 伪列(OBJECTID 直接可用)、没有
 * {@code DELLAYER:} / {@code DROP TABLE} 等写命令(本引擎只读)、
 * 空间过滤走 {@code executeSql(sql, bbox)} 的 bbox 参数而不是 SQL 函数。
 *
 * <h2>标识符</h2>
 * 未加引号的图层名 / 字段名一律<b>大小写不敏感</b>(与 OGR 一致);
 * 含空格等特殊字符的名字用双引号括起({@code FROM "图层 A"})。
 * 注意:每个查询独占一个 Calcite 连接,图层按<b>查询时刻</b>的清单注册。
 *
 * <h2>线程模型</h2>
 * 每次调用新建连接与语句,不同 {@code executeSql} 结果互不干扰;但底层读路径
 * 并不保证并发安全,沿用本库"读可并发、同一结果的遍历必须单线程"的约定。
 */
public final class GdbSqlEngine {

    private GdbSqlEngine() {
        // 静态入口,不实例化
    }

    /**
     * 执行一条只读查询。
     *
     * @param sql    SQL 语句(SELECT / WITH / VALUES / TABLE)
     * @param bbox   可选的空间过滤矩形 {xmin, ymin, xmax, ymax};null 表示无
     * @param layers 参与查询的图层(名字 → 根 schema 下的表)
     * @return 游标式结果(调用方负责 close)
     * @throws GdbSqlException 语法错误 / 表或字段不存在 / 非查询语句
     */
    public static GdbSqlResult execute(String sql, double[] bbox,
                                       List<OpenFileGdbLayer> layers) {
        // ⚠️ 必须是本方法的第一条语句:字符集属性要在 Calcite 的 Util 类初始化前设好,
        //    否则中文/非 Latin-1 字面量会在 ISO-8859-1 下编码失败(详见 GdbSqlBootstrap)
        GdbSqlBootstrap.ensureUtf8();
        if (sql == null || sql.trim().isEmpty()) {
            throw new GdbSqlException("SQL 语句为空");
        }
        String query = sql.trim();
        // 用户带分号的习惯(GDAL/ogr 常见写法)替他去掉,Calcite 语法不收尾分号
        while (query.endsWith(";")) {
            query = query.substring(0, query.length() - 1).trim();
        }
        requireQuery(query);

        if (bbox != null && bbox.length != 4) {
            throw new GdbSqlException("bbox 必须是 {xmin, ymin, xmax, ymax} 的 4 元素数组");
        }

        Connection connection = null;
        try {
            connection = DriverManager.getConnection(
                    "jdbc:calcite:", connectionProperties());
            CalciteConnection calcite = connection.unwrap(CalciteConnection.class);
            SchemaPlus root = calcite.getRootSchema();
            for (OpenFileGdbLayer layer : layers) {
                try {
                    root.add(layer.getName(), new GdbSqlTable(layer, bbox));
                } catch (IllegalArgumentException e) {
                    // 典型情形:图层名与 Calcite 根 schema 里的保留条目同名(如 metadata)
                    throw new GdbSqlException("图层名 " + layer.getName()
                            + " 与 SQL 引擎的保留名冲突,无法参与查询: " + e.getMessage(), e);
                }
            }
            Statement statement = connection.createStatement();
            ResultSet resultSet = statement.executeQuery(query);
            return new GdbSqlResult(query, connection, statement, resultSet);
        } catch (SQLException e) {
            closeConnection(connection);
            throw toSqlException(query, e);
        } catch (RuntimeException e) {
            closeConnection(connection);
            throw e instanceof GdbSqlException
                    ? (GdbSqlException) e
                    : new GdbSqlException("执行 SQL 失败: " + e.getMessage(), e);
        }
    }

    /**
     * Calcite 连接属性:标识符大小写不敏感 + 原样大小写(字段名保持
     * 磁盘上的写法)、字符串按 UTF-8、TIMESTAMP 按 UTC 解释
     * (与 GdbSqlTypes 的约定配套)。
     *
     * <p>⚠️ {@code charset} 必须显式设成 UTF-8:Calcite 的默认字符集是
     * <b>ISO-8859-1</b>({@code calcite.default.charset}),不设的话
     * {@code WHERE name = '北京'} 会直接抛
     * "Failed to encode '北京' in character set 'ISO-8859-1'"。
     */
    private static Properties connectionProperties() {
        Properties props = new Properties();
        props.setProperty("unquotedCasing", "UNCHANGED");
        props.setProperty("quotedCasing", "UNCHANGED");
        props.setProperty("caseSensitive", "false");
        props.setProperty("timeZone", "UTC");
        props.setProperty("charset", "UTF-8");
        return props;
    }

    /**
     * 只放行查询语句:GDAL 的 ExecuteSQL 还能执行 {@code DELLAYER:} /
     * {@code DROP TABLE} / {@code ALTER TABLE} 等写命令,本库的 SQL 层是
     * <b>只读</b>的,直接拒绝并说明原因(写请走 createLayer / createFeature)。
     */
    private static void requireQuery(String sql) {
        String probe = sql;
        while (probe.startsWith("(")) {
            probe = probe.substring(1).trim();
        }
        String head = probe.toUpperCase(Locale.ROOT);
        if (head.startsWith("SELECT") || head.startsWith("WITH")
                || head.startsWith("VALUES") || head.startsWith("TABLE")
                || head.startsWith("EXPLAIN")) {
            return;
        }
        throw new GdbSqlException("executeSql 只支持查询语句(SELECT/WITH/VALUES/"
                + "TABLE/EXPLAIN),不支持: " + abbreviate(sql)
                + "。写操作请使用 createLayer/createFeature/updateFeature"
                + "(GDAL 的 DELLAYER:/DROP TABLE 等写命令本库不做)");
    }

    /** 把 Calcite 的 SQLException 翻译成带定位信息的 GdbSqlException。 */
    private static GdbSqlException toSqlException(String sql, SQLException e) {
        String detail = e.getMessage();
        Throwable cause = e.getCause();
        if (cause != null && cause.getMessage() != null
                && !cause.getMessage().equals(detail)) {
            detail = detail + "; 原因: " + cause.getMessage();
        }
        return new GdbSqlException("SQL 执行失败(" + abbreviate(sql) + "): " + detail, e);
    }

    private static String abbreviate(String sql) {
        return sql.length() <= 80 ? sql : sql.substring(0, 80) + "...";
    }

    private static void closeConnection(Connection connection) {
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException e) {
                // 关闭失败忽略,以原始异常为准
            }
        }
    }
}
