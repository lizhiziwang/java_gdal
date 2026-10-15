package com.gdal.openfilegdb.sql;

import com.gdal.openfilegdb.exception.GdbSqlException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * {@code executeSql} 的查询结果 —— 一个<b>游标式</b>的结果图层。
 *
 * <p>对标 GDAL {@code ExecuteSQL()} 返回的 {@code OGRLayer*}:
 * <ul>
 *   <li><b>逐条拉取</b>:{@link #nextRow()} 每调一次只从底层流里取一行,
 *       内存与结果行数无关(见 {@link GdbSqlTable} 的流式语义);
 *       {@code ORDER BY / DISTINCT / GROUP BY} 的例外与 GDAL 文档一致
 *       (这些子句本身要求物化)。</li>
 *   <li><b>OGR 式单游标</b>:本类同时实现 {@link Iterator} 与
 *       {@link Iterable},且 {@link #iterator()} 返回 {@code this} ——
 *       与 {@code com.gdal.openfilegdb.layer.OpenFileGdbLayer} 的约定相同,
 *       可以直接 {@code for (Object[] row : result)} 遍历;嵌套遍历须先
 *       {@link #reset()}。</li>
 *   <li><b>必须关闭</b>:持有 JDBC 资源,一律 try-with-resources。</li>
 * </ul>
 *
 * <p><b>行与列</b>:行是 {@code Object[]},下标与 {@link #getColumnNames()}
 * 一一对应;列名保持 SQL 里写出的形式(别名优先)。值是<b>本库语义</b>的:
 * 日期时间列是 {@code LocalDateTime},几何列是
 * {@code com.gdal.openfilegdb.feature.GdbGeometry}(可继续
 * {@code toWkt() / toWkb()}),其余按 Calcite 的产出原样给出(数值、字符串、
 * 布尔、聚合结果…)。空值是 {@code null}。
 *
 * <p>{@link #reset()} 对应 OGR 的 {@code ResetReading()} —— 由于结果是流式的,
 * 复位 = 重新执行一遍查询(重复同一 SQL 的二次执行会命中 Calcite 的语句缓存,
 * 规划开销显著低于第一次)。
 */
public final class GdbSqlResult implements AutoCloseable, Iterator<Object[]>,
        Iterable<Object[]> {

    private final String sql;
    private final Connection connection;
    private final List<String> columnNames;
    private final int[] columnTypes;
    private final String[] columnClassNames;

    private Statement statement;
    private ResultSet resultSet;
    private Object[] pending;
    private boolean closed;

    GdbSqlResult(String sql, Connection connection, Statement statement,
                 ResultSet resultSet) throws SQLException {
        this.sql = sql;
        this.connection = connection;
        this.statement = statement;
        this.resultSet = resultSet;
        ResultSetMetaData md = resultSet.getMetaData();
        List<String> names = new ArrayList<>(md.getColumnCount());
        int[] types = new int[md.getColumnCount()];
        String[] classNames = new String[md.getColumnCount()];
        for (int i = 1; i <= md.getColumnCount(); i++) {
            names.add(md.getColumnLabel(i));
            types[i - 1] = md.getColumnType(i);
            try {
                classNames[i - 1] = md.getColumnClassName(i);
            } catch (SQLException e) {
                // 拿不到类名不影响取值,只是少了几何列的一种识别线索
                classNames[i - 1] = null;
            }
        }
        this.columnNames = names;
        this.columnTypes = types;
        this.columnClassNames = classNames;
    }

    /** 结果列名(别名优先,顺序与行数组的下标一致)。 */
    public List<String> getColumnNames() {
        return columnNames;
    }

    /** 结果列数。 */
    public int getColumnCount() {
        return columnNames.size();
    }

    /** 各列的 JDBC 类型({@link java.sql.Types}),下标与 {@link #getColumnNames()} 一致。 */
    public int[] getColumnTypes() {
        return columnTypes.clone();
    }

    /**
     * 各列声明的 Java 类名({@code ResultSetMetaData.getColumnClassName}),下标与
     * {@link #getColumnNames()} 一致;拿不到时对应位置是 {@code null}。
     *
     * <p>几何列靠它识别:{@code createJavaType(GdbGeometry.class)} 声明出来的列,
     * 类名就是 {@code com.gdal.openfilegdb.feature.GdbGeometry}。
     */
    public String[] getColumnClassNames() {
        return columnClassNames.clone();
    }

    /**
     * 取下一行(OGR 风格)。
     *
     * @return 行值数组;读到末尾返回 {@code null}
     */
    public Object[] nextRow() {
        if (pending != null) {
            Object[] row = pending;
            pending = null;
            return row;
        }
        return advance();
    }

    /**
     * 复位游标(对应 OGR {@code ResetReading}):重新执行查询回到第一行之前。
     *
     * <p>已关闭的结果不能复位;复位会丢弃未消费的预取行。
     */
    public void reset() {
        ensureOpen();
        closeQuietly();
        pending = null;
        try {
            statement = connection.createStatement();
            resultSet = statement.executeQuery(sql);
        } catch (SQLException e) {
            throw new GdbSqlException("重置查询失败: " + e.getMessage(), e);
        }
    }

    /**
     * 把剩余行全部取出(急切)。
     *
     * <p>便利方法,等价于循环 {@link #nextRow()} 直到 null;从当前位置开始,
     * 不含已消费的行。行数很大时注意内存(与 {@code where()} 的驻留特性相同)。
     *
     * @return 剩余行
     */
    public List<Object[]> toList() {
        List<Object[]> out = new ArrayList<>();
        Object[] row;
        while ((row = nextRow()) != null) {
            out.add(row);
        }
        return out;
    }

    /** OGR 式单游标:迭代器就是本对象。 */
    @Override
    public Iterator<Object[]> iterator() {
        return this;
    }

    @Override
    public boolean hasNext() {
        if (pending != null) {
            return true;
        }
        pending = advance();
        return pending != null;
    }

    @Override
    public Object[] next() {
        if (!hasNext()) {
            throw new NoSuchElementException();
        }
        Object[] row = pending;
        pending = null;
        return row;
    }

    /** 关闭结果(释放 JDBC 资源);幂等。 */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        pending = null;
        closeQuietly();
        try {
            connection.close();
        } catch (SQLException e) {
            // 关闭失败不再上抛(GDAL 的 ReleaseResultSet 也不报错)
        }
    }

    // ------------------------------------------------------------------

    private Object[] advance() {
        ensureOpen();
        try {
            if (!resultSet.next()) {
                return null;
            }
            Object[] row = new Object[columnNames.size()];
            for (int i = 0; i < row.length; i++) {
                row[i] = GdbSqlTypes.fromSqlValue(resultSet.getObject(i + 1),
                        columnTypes[i]);
            }
            return row;
        } catch (SQLException e) {
            throw new GdbSqlException("读取查询结果失败: " + e.getMessage(), e);
        }
    }

    private void ensureOpen() {
        if (closed) {
            throw new GdbSqlException("查询结果已关闭");
        }
    }

    private void closeQuietly() {
        try {
            if (resultSet != null) {
                resultSet.close();
            }
        } catch (SQLException e) {
            // 忽略
        }
        try {
            if (statement != null) {
                statement.close();
            }
        } catch (SQLException e) {
            // 忽略
        }
    }

    @Override
    public String toString() {
        return "<GdbSqlResult sql=\"" + sql + "\" columns=" + columnNames + ">";
    }
}
