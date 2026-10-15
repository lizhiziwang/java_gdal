package com.gdal.openfilegdb.sql;

import com.gdal.openfilegdb.feature.FieldType;
import com.gdal.openfilegdb.feature.GdbField;
import com.gdal.openfilegdb.feature.GdbGeometry;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.sql.type.SqlTypeName;

/**
 * FileGDB 字段类型 ↔ Calcite SQL 类型的双向映射,以及属性值的进出转换。
 *
 * <p><b>类型映射表</b>(左 = {@link FieldType},右 = Calcite {@code SqlTypeName}):
 * <pre>
 *   INT16                -> SMALLINT
 *   INT32                -> INTEGER
 *   INT64                -> BIGINT
 *   OBJECTID             -> BIGINT(OID 是 64 位整数)
 *   FLOAT32              -> REAL
 *   FLOAT64              -> DOUBLE
 *   STRING               -> VARCHAR(声明长度,缺省 4096,上限 65536)
 *   DATE / TIME / DATETIME / DATETIME_WITH_OFFSET
 *                        -> TIMESTAMP(见下方说明)
 *   GEOMETRY             -> OTHER(GdbGeometry)
 *   BINARY / RASTER      -> OTHER(byte[])
 *   GUID / GLOBALID / XML-> VARCHAR
 * </pre>
 *
 * <p><b>为什么 DATE / TIME 也映射成 TIMESTAMP?</b>FileGDB 里 DATE / TIME / DATETIME
 * 三种字段底层是<b>同一个 8 字节的“天数 double”</b>(见 {@code filegdbtable.cpp}
 * 的 {@code FileGDBDoubleDateToOGRDate}),本库统一解码成 {@link LocalDateTime}。
 * 既然运行时值类型相同、精度也只有“天”的 double,就没必要在 SQL 侧造出三种不同的
 * 类型 —— 统一暴露成 TIMESTAMP,行为简单可预期。日期比较、排序、聚合都照常工作。
 *
 * <p>几何列的类型是 {@code OTHER} 且 Java 类是 {@link GdbGeometry}:Calcite 不认这个
 * 类型,所以几何列<b>能 select 出来、能排序比较引用,但不能做空间运算</b> ——
 * 空间过滤请用 {@code executeSql(sql, bbox)} 或 {@code OpenFileGdbLayer.search()}。
 */
public final class GdbSqlTypes {

    /** VARCHAR 未声明长度时的兜底精度。 */
    private static final int DEFAULT_VARCHAR_LENGTH = 4096;

    /** Calcite 的 VARCHAR 精度上限。 */
    private static final int MAX_VARCHAR_LENGTH = 65536;

    private GdbSqlTypes() {
        // 工具类,不实例化
    }

    /**
     * 把一个 FileGDB 字段映射成 Calcite 关系类型。
     *
     * <p><b>可空性必须跟着字段走</b>:Calcite 的 {@code createSqlType} 默认产出
     * <b>NOT NULL</b> 类型,若照搬,优化器会把 {@code WHERE name IS NULL} 直接折叠成
     * {@code FALSE}、{@code IS NOT NULL} 折叠成 {@code TRUE} —— 于是空值行永远查不出来。
     * 这里按 {@link GdbField#isNullable()} 显式置可空。
     *
     * @param typeFactory Calcite 类型工厂
     * @param field       字段定义
     * @return 关系类型
     */
    public static RelDataType toRelType(RelDataTypeFactory typeFactory, GdbField field) {
        RelDataType type;
        switch (field.getType()) {
            case INT16:
                type = typeFactory.createSqlType(SqlTypeName.SMALLINT);
                break;
            case INT32:
                type = typeFactory.createSqlType(SqlTypeName.INTEGER);
                break;
            case INT64:
            case OBJECTID:
                type = typeFactory.createSqlType(SqlTypeName.BIGINT);
                break;
            case FLOAT32:
                type = typeFactory.createSqlType(SqlTypeName.REAL);
                break;
            case FLOAT64:
                type = typeFactory.createSqlType(SqlTypeName.DOUBLE);
                break;
            case DATE:
            case TIME:
            case DATETIME:
            case DATETIME_WITH_OFFSET:
                type = typeFactory.createSqlType(SqlTypeName.TIMESTAMP);
                break;
            case GEOMETRY:
                type = typeFactory.createJavaType(GdbGeometry.class);
                break;
            case BINARY:
            case RASTER:
                type = typeFactory.createJavaType(byte[].class);
                break;
            default:
                type = typeFactory.createSqlType(SqlTypeName.VARCHAR, varcharLength(field));
                break;
        }
        return field.isNullable() ? typeFactory.createTypeWithNullability(type, true) : type;
    }

    /**
     * VARCHAR 精度:优先用字段声明的长度,不合法时回退。
     *
     * @param field 字段定义
     * @return 1..65536 之间的精度
     */
    private static int varcharLength(GdbField field) {
        int len = field.getLength();
        if (len <= 0) {
            return DEFAULT_VARCHAR_LENGTH;
        }
        return Math.min(len, MAX_VARCHAR_LENGTH);
    }

    /**
     * 把本库的属性值转成 Calcite 能处理的 SQL 值。
     *
     * <p>目前只有一处需要动:FileGDB 的日期字段在库里是 {@link LocalDateTime}
     * (GDAL 也是这么导出的,是"无时区的墙上时钟"),而 Calcite 内部
     * <b>TIMESTAMP 的行值就是 epoch 毫秒的 {@link Long}</b>
     * ({@code Enumerable} 约定下聚合/排序的生成代码直接按 {@code long} 拆箱 ——
     * 若塞 {@link Timestamp} 会得 {@code java.sql.Timestamp cannot be cast to
     * java.lang.Long})。这里的换算<b>显式按 UTC 解释墙上时钟</b>:
     * <ul>
     *   <li>{@code 2020-01-01 00:00:00} → epoch 0,与连接的 {@code timeZone=UTC}
     *       属性下 {@code TIMESTAMP '2020-01-01 00:00:00'} 字面量的内部值一致
     *       —— 否则 {@code WHERE dt >= TIMESTAMP '...'} 会随 JVM 时区偏移
     *       (用 {@code Timestamp.valueOf} 就是这个坑);</li>
     *   <li>换算与 JVM 默认时区完全无关,任何机器上结果相同;</li>
     *   <li>{@link #fromSqlValue(Object)} 用同一约定逆变换,墙上时钟往返无损。</li>
     * </ul>
     * 其余类型(String / Integer / Long / Double / byte[] / GdbGeometry)原样传递。
     *
     * @param value 属性值,可为 {@code null}
     * @return SQL 侧的值
     */
    public static Object toSqlValue(Object value) {
        if (value instanceof LocalDateTime) {
            return ((LocalDateTime) value).atZone(ZoneOffset.UTC).toInstant()
                    .toEpochMilli();
        }
        return value;
    }

    /**
     * 把 SQL 结果里的值转回本库的属性值,与 {@link #toSqlValue(Object)} 互逆
     * (TIMESTAMP 一律按 UTC 解释,与 JVM 时区无关)。
     *
     * <p>不认识的值(聚合结果、字符串、数值、布尔…)原样返回;
     * 时间戳有两种可能形态,见 {@link #fromSqlValue(Object, int)}。
     *
     * @param value SQL 值,可为 {@code null}
     * @return 属性值
     */
    public static Object fromSqlValue(Object value) {
        if (value instanceof Timestamp) {
            return ((Timestamp) value).toInstant()
                    .atZone(ZoneOffset.UTC).toLocalDateTime();
        }
        if (value instanceof java.sql.Date) {
            return ((java.sql.Date) value).toLocalDate().atStartOfDay();
        }
        if (value instanceof java.sql.Time) {
            return java.time.LocalDate.of(1970, 1, 1)
                    .atTime(((java.sql.Time) value).toLocalTime());
        }
        return value;
    }

    /**
     * 带列类型的版本:时间戳列在结果里可能是 {@link Timestamp}(JDBC 层做过转换)
     * 也可能是 epoch 毫秒的 {@link Long}(Calcite 内部表示原样透出),按列类型统一
     * 还原成 {@link LocalDateTime}。
     *
     * @param value      SQL 值,可为 {@code null}
     * @param columnType JDBC 列类型({@link java.sql.Types})
     * @return 属性值
     */
    public static Object fromSqlValue(Object value, int columnType) {
        if (value instanceof Number
                && (columnType == java.sql.Types.TIMESTAMP
                        || columnType == java.sql.Types.TIMESTAMP_WITH_TIMEZONE)) {
            return java.time.Instant.ofEpochMilli(((Number) value).longValue())
                    .atZone(ZoneOffset.UTC).toLocalDateTime();
        }
        return fromSqlValue(value);
    }
}
