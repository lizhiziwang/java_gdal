package com.gdal.openfilegdb.sql;

import com.gdal.openfilegdb.feature.GdbGeometry;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import org.apache.calcite.rex.RexCall;
import org.apache.calcite.rex.RexInputRef;
import org.apache.calcite.rex.RexLiteral;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.type.SqlTypeName;

/**
 * 把 Calcite 下推的 WHERE 谓词({@link RexNode})翻译成能直接在本库行值上求值的
 * {@link RowPredicate}。
 *
 * <p>这是 {@link GdbSqlTable} 实现 {@code ProjectableFilterableTable} 的配套件:
 * Calcite 把过滤条件传给表,表<b>能翻译的就从列表里移除</b>(由本类执行),
 * 翻译不了的<b>留在列表里</b>(由 Calcite 自己的执行器补一道过滤)—— 两侧职责
 * 与 Calcite 的 {@code FilterableTable} 契约一致。这样简单比较
 * ({@code WHERE objectid <= 5})可以在流式扫描里<b>边读边滤</b>,而
 * {@code LIKE / IN / CAST} 等复杂条件仍然正确(只是回到 Calcite 侧求值)。
 *
 * <p><b>支持的条件子集</b>(都是 OGR SQL 属性过滤里最常用的形态):
 * <ul>
 *   <li>比较运算 {@code = <> < <= > >=}:操作数为字段引用或字面量
 *       (数值 / 字符串 / 布尔 / TIMESTAMP 字面量 / NULL);</li>
 *   <li>字段与字段之间的比较;</li>
 *   <li>{@code IS NULL / IS NOT NULL}(对任意列,包括几何列);</li>
 *   <li>{@code AND / OR / NOT} 组合。</li>
 * </ul>
 *
 * <p><b>三值逻辑</b>:求值结果用可空的 {@link Boolean} 表达
 * TRUE / FALSE / UNKNOWN(任一操作数为 NULL 的比较结果是 UNKNOWN,
 * UNKNOWN 不入选 —— 与 SQL 的 WHERE 语义一致,也与 OGR SQL 一致)。
 *
 * <p><b>数值比较</b>:两个整数按 long 比,否则按 double 比 —— 这样
 * {@code INT32 字段 = BIGINT 字面量} 之类的跨宽度比较不会丢精度
 * (OBJECTID 是 BIGINT,字面量默认是 DECIMAL,必须走这条路径)。
 *
 * <p>类型映射见 {@link GdbSqlTypes};行值里 TIMESTAMP 列是
 * {@link Timestamp}(UTC 约定),TIMESTAMP 字面量是 epoch 毫秒的 {@link Long},
 * 两者直接按毫秒比较。
 */
final class GdbSqlFilters {

    private GdbSqlFilters() {
        // 工具类,不实例化
    }

    /**
     * 尝试翻译一个下推的过滤条件。
     *
     * @param node Calcite 的谓词表达式(基于全宽行类型的 InputRef)
     * @return 翻译成功的谓词;不支持该形态时返回 {@code null}(调用方应把
     *         原条件留给 Calcite 执行)
     */
    static RowPredicate tryTranslate(RexNode node) {
        if (node instanceof RexCall) {
            return translateCall((RexCall) node);
        }
        if (node instanceof RexInputRef) {
            // 裸列当布尔用(WHERE flag)—— 直接取行值按真值解释
            final int index = ((RexInputRef) node).getIndex();
            return row -> asBoolean(row[index]);
        }
        return null;
    }

    /** 翻译函数调用形态的条件。 */
    private static RowPredicate translateCall(RexCall call) {
        SqlKind kind = call.getKind();
        List<RexNode> operands = call.getOperands();
        switch (kind) {
            case AND:
            case OR: {
                RowPredicate[] subs = translateAll(operands);
                if (subs == null) {
                    return null;
                }
                return kind == SqlKind.AND
                        ? row -> and(subs, row)
                        : row -> or(subs, row);
            }
            case NOT: {
                if (operands.size() != 1) {
                    return null;
                }
                RowPredicate sub = tryTranslate(operands.get(0));
                if (sub == null) {
                    return null;
                }
                return row -> not(sub.test(row));
            }
            case IS_NULL:
            case IS_NOT_NULL: {
                if (operands.size() != 1 || !(operands.get(0) instanceof RexInputRef)) {
                    return null;
                }
                final int index = ((RexInputRef) operands.get(0)).getIndex();
                return kind == SqlKind.IS_NULL
                        ? row -> row[index] == null
                        : row -> row[index] != null;
            }
            case EQUALS:
            case NOT_EQUALS:
            case LESS_THAN:
            case LESS_THAN_OR_EQUAL:
            case GREATER_THAN:
            case GREATER_THAN_OR_EQUAL: {
                if (operands.size() != 2 || !comparableTypes(operands, kind)) {
                    return null;
                }
                Operand left = operand(operands.get(0));
                Operand right = operand(operands.get(1));
                if (left == null || right == null) {
                    return null;
                }
                return row -> compare(left.value(row), right.value(row), kind);
            }
            default:
                return null;
        }
    }

    /** 全部翻译成功返回数组;任何一个失败返回 {@code null}。 */
    private static RowPredicate[] translateAll(List<RexNode> nodes) {
        RowPredicate[] out = new RowPredicate[nodes.size()];
        for (int i = 0; i < nodes.size(); i++) {
            out[i] = tryTranslate(nodes.get(i));
            if (out[i] == null) {
                return null;
            }
        }
        return out;
    }

    // ==================================================================
    // 操作数:字段引用 / 字面量
    // ==================================================================

    /** 从行里取值(字段引用)或取常量(字面量)。 */
    private interface Operand {
        Object value(Object[] row);
    }

    private static Operand operand(RexNode node) {
        if (node instanceof RexInputRef) {
            final int index = ((RexInputRef) node).getIndex();
            return row -> row[index];
        }
        if (node instanceof RexLiteral) {
            Object literal = literalValue((RexLiteral) node);
            if (literal == NO_LITERAL) {
                return null;
            }
            return row -> literal;
        }
        return null;
    }

    /** 翻译失败时 {@link #literalValue} 的哨兵(避免用 null 混淆"值为 NULL")。 */
    private static final Object NO_LITERAL = new Object();

    /**
     * 取字面量的 Java 值;不支持的形态返回 {@link #NO_LITERAL}。
     *
     * <p>数值字面量统一转成 {@link BigDecimal} / {@link Double},
     * TIMESTAMP 字面量转成 epoch 毫秒的 {@link Long}(与 GdbSqlTypes 的
     * UTC 约定配套),NULL 字面量返回 {@code null}。
     */
    private static Object literalValue(RexLiteral literal) {
        SqlTypeName typeName = literal.getTypeName();
        switch (typeName) {
            case DECIMAL:
            case BIGINT:
            case INTEGER:
            case SMALLINT:
            case TINYINT:
                return literal.getValueAs(BigDecimal.class);
            case DOUBLE:
            case FLOAT:
            case REAL:
                return literal.getValueAs(Double.class);
            case CHAR:
            case VARCHAR:
                return literal.getValueAs(String.class);
            case BOOLEAN:
                return literal.getValueAs(Boolean.class);
            case TIMESTAMP:
            case TIMESTAMP_WITH_LOCAL_TIME_ZONE:
                // Calcite 的 TIMESTAMP 字面量内部值 = UTC 解释的 epoch 毫秒
                return literal.getValueAs(Long.class);
            case NULL:
                return null;
            default:
                return NO_LITERAL;
        }
    }

    // ==================================================================
    // 求值
    // ==================================================================

    /** 三值逻辑 AND:见 FALSE 即 FALSE,否则见 UNKNOWN 即 UNKNOWN,否则 TRUE。 */
    private static Boolean and(RowPredicate[] subs, Object[] row) {
        boolean sawUnknown = false;
        for (RowPredicate sub : subs) {
            Boolean v = sub.test(row);
            if (Boolean.FALSE.equals(v)) {
                return Boolean.FALSE;
            }
            if (v == null) {
                sawUnknown = true;
            }
        }
        return sawUnknown ? null : Boolean.TRUE;
    }

    /** 三值逻辑 OR:见 TRUE 即 TRUE,否则见 UNKNOWN 即 UNKNOWN,否则 FALSE。 */
    private static Boolean or(RowPredicate[] subs, Object[] row) {
        boolean sawUnknown = false;
        for (RowPredicate sub : subs) {
            Boolean v = sub.test(row);
            if (Boolean.TRUE.equals(v)) {
                return Boolean.TRUE;
            }
            if (v == null) {
                sawUnknown = true;
            }
        }
        return sawUnknown ? null : Boolean.FALSE;
    }

    private static Boolean not(Boolean v) {
        return v == null ? null : !v;
    }

    /** 把行值按 SQL 真值解释:TRUE 才是 true,NULL/FALSE 都是 false。 */
    private static Boolean asBoolean(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof Boolean) {
            return (Boolean) v;
        }
        return null;
    }

    /**
     * 比较;任一侧为 NULL 返回 UNKNOWN。
     *
     * <p>数值:整数对整数走 long 精确比较,其余走 double;字符串字典序;
     * 时间戳与 epoch 毫秒互通;布尔只支持等/不等;其余同类型走
     * {@link Comparable}(如两个 TIMESTAMP 列)。
     */
    private static Boolean compare(Object left, Object right, SqlKind kind) {
        if (left == null || right == null) {
            return null;
        }
        int cmp;
        if (left instanceof Number && right instanceof Number) {
            cmp = compareNumbers((Number) left, (Number) right);
        } else if (left instanceof String && right instanceof String) {
            cmp = ((String) left).compareTo((String) right);
        } else if (left instanceof Boolean && right instanceof Boolean) {
            if (kind != SqlKind.EQUALS && kind != SqlKind.NOT_EQUALS) {
                return null;
            }
            cmp = Boolean.compare((Boolean) left, (Boolean) right);
        } else if (left instanceof Timestamp && right instanceof Long) {
            cmp = Long.compare(((Timestamp) left).getTime(), (Long) right);
        } else if (left instanceof Long && right instanceof Timestamp) {
            cmp = Long.compare((Long) left, ((Timestamp) right).getTime());
        } else if (left instanceof byte[] && right instanceof byte[]) {
            if (kind != SqlKind.EQUALS && kind != SqlKind.NOT_EQUALS) {
                return null;
            }
            cmp = Arrays.equals((byte[]) left, (byte[]) right) ? 0 : 1;
        } else if (left instanceof Timestamp && right instanceof Timestamp) {
            cmp = ((Timestamp) left).compareTo((Timestamp) right);
        } else if (left instanceof Comparable && left.getClass().isInstance(right)) {
            @SuppressWarnings("unchecked")
            Comparable<Object> l = (Comparable<Object>) left;
            cmp = l.compareTo(right);
        } else if (left instanceof GdbGeometry || right instanceof GdbGeometry) {
            // 几何不参与排序比较;等/不等退回 equals 语义
            if (kind != SqlKind.EQUALS && kind != SqlKind.NOT_EQUALS) {
                return null;
            }
            cmp = Objects.equals(left, right) ? 0 : 1;
        } else {
            return null;
        }
        switch (kind) {
            case EQUALS:
                return cmp == 0;
            case NOT_EQUALS:
                return cmp != 0;
            case LESS_THAN:
                return cmp < 0;
            case LESS_THAN_OR_EQUAL:
                return cmp <= 0;
            case GREATER_THAN:
                return cmp > 0;
            case GREATER_THAN_OR_EQUAL:
                return cmp >= 0;
            default:
                return null;
        }
    }

    /** 数值比较:整数对整数精确比,其余按 double。 */
    private static int compareNumbers(Number left, Number right) {
        if (isIntegral(left) && isIntegral(right)) {
            return Long.compare(left.longValue(), right.longValue());
        }
        if (left instanceof BigDecimal && right instanceof BigDecimal) {
            return ((BigDecimal) left).compareTo((BigDecimal) right);
        }
        return Double.compare(left.doubleValue(), right.doubleValue());
    }

    private static boolean isIntegral(Number n) {
        return n instanceof Long || n instanceof Integer
                || n instanceof Short || n instanceof Byte;
    }

    /**
     * 比较类条件的静态类型检查(翻译期判掉,避免运行期静默求成 UNKNOWN):
     * 数值×数值、字符串×字符串、时间戳×时间戳、时间戳×数值(时间戳字面量)
     * 允许全部比较运算;布尔×布尔、OTHER(几何/二进制)只允许等/不等。
     */
    private static boolean comparableTypes(List<RexNode> operands, SqlKind kind) {
        SqlTypeName left = operands.get(0).getType().getSqlTypeName();
        SqlTypeName right = operands.get(1).getType().getSqlTypeName();
        Category lc = category(left);
        Category rc = category(right);
        if (lc == null || rc == null) {
            return false;
        }
        boolean equalsOnly = lc == Category.BOOLEAN
                || rc == Category.BOOLEAN
                || lc == Category.OTHER
                || rc == Category.OTHER;
        if (equalsOnly) {
            return kind == SqlKind.EQUALS || kind == SqlKind.NOT_EQUALS;
        }
        if (lc == rc) {
            return true;
        }
        // TIMESTAMP 列 × 数值字面量(epoch 毫秒)或反之
        return (lc == Category.DATETIME && rc == Category.NUMERIC)
                || (lc == Category.NUMERIC && rc == Category.DATETIME);
    }

    /** 值类别(用于比较兼容性判断)。 */
    private enum Category {
        NUMERIC, STRING, BOOLEAN, DATETIME, OTHER
    }

    private static Category category(SqlTypeName typeName) {
        switch (typeName) {
            case DECIMAL:
            case BIGINT:
            case INTEGER:
            case SMALLINT:
            case TINYINT:
            case DOUBLE:
            case FLOAT:
            case REAL:
                return Category.NUMERIC;
            case CHAR:
            case VARCHAR:
                return Category.STRING;
            case BOOLEAN:
                return Category.BOOLEAN;
            case TIMESTAMP:
            case TIMESTAMP_WITH_LOCAL_TIME_ZONE:
                return Category.DATETIME;
            default:
                // GEOMETRY / BINARY 等 Java 类型列(含 DATE/TIME 字面量,
                // 那些留给 Calcite 自己处理更稳)
                return Category.OTHER;
        }
    }

    /** 行谓词:输入全宽行值,输出三值逻辑的 TRUE/FALSE/UNKNOWN。 */
    interface RowPredicate {
        Boolean test(Object[] row);
    }
}
