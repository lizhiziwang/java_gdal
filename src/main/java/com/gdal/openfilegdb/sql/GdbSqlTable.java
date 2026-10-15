package com.gdal.openfilegdb.sql;

import com.gdal.openfilegdb.feature.GdbFeature;
import com.gdal.openfilegdb.feature.GdbField;
import com.gdal.openfilegdb.feature.GdbGeometry;
import com.gdal.openfilegdb.index.GdbSpatialIndex;
import com.gdal.openfilegdb.layer.OpenFileGdbLayer;
import com.gdal.openfilegdb.table.GdbTable;
import com.google.common.collect.ImmutableList;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import org.apache.calcite.DataContext;
import org.apache.calcite.linq4j.AbstractEnumerable;
import org.apache.calcite.linq4j.Enumerable;
import org.apache.calcite.linq4j.Enumerator;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.rex.RexInputRef;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.rex.RexVisitorImpl;
import org.apache.calcite.schema.Statistics;
import org.apache.calcite.schema.impl.AbstractTable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 把一个 {@link OpenFileGdbLayer} 暴露成 Calcite 的表 —— {@code executeSql} 的数据源。
 *
 * <p>实现 {@link org.apache.calcite.schema.ProjectableFilterableTable}:Calcite 会把
 * 能下推的 WHERE 条件与列投影直接传进来,本表据此做到
 * <b>边流式读 .gdbtable 边过滤、只解码被引用到的列</b>:
 * <ul>
 *   <li><b>流式</b>:{@code scan()} 返回的 {@link Enumerable} 是惰性的,
 *       每一行都从 {@link GdbTable#iterator()} 逐条定位读出(不预取、不缓存),
 *       消费一行丢一行 —— 与 GDAL {@code OGRGenSQLResultsLayer::GetNextFeature}
 *       的逐条拉取语义一致;真正的物化只发生在 Calcite 必须物化的地方
 *       ({@code ORDER BY} 全表排序、{@code DISTINCT}/{@code GROUP BY} 建哈希表),
 *       与 GDAL 文档明写的内存取舍相同。</li>
 *   <li><b>几何列惰性解码</b>:FileGDB 的几何 blob 只有<b>被投影或被过滤条件引用</b>
 *       时才解码成 {@link GdbGeometry}。{@code SELECT name FROM t WHERE id <= 5}
 *       这类纯属性查询全程不碰几何解码(几何解码是读路径上最贵的一步)。</li>
 *   <li><b>过滤下推</b>:简单比较({@code = <> < <= > >=}、{@code IS [NOT] NULL}、
 *       {@code AND/OR/NOT})由 {@link GdbSqlFilters} 翻译成本库行谓词,在扫描里
 *       原地求值;复杂条件({@code LIKE / IN / CAST}…)留给 Calcite 的执行器,
 *       它同样逐条流式求值,只是会要求把相关列一并投影出来。</li>
 * </ul>
 *
 * <p><b>行模型</b>:列 = {@code layer.getFields()}(OBJECTID、属性、几何,顺序与
 * 磁盘一致);OBJECTID 列取 {@link GdbFeature#getOid()},几何列取
 * {@link GdbFeature#getGeometry()},其余取属性并经 {@link GdbSqlTypes#toSqlValue}
 * 转 SQL 值。类型映射也见 {@link GdbSqlTypes}。
 *
 * <p><b>空间过滤</b>:构造时传入的 {@code bbox} 模拟 GDAL
 * {@code ExecuteSQL(pszSQL, poSpatialFilter, ...)} 的第二个参数 —— 先用
 * {@code .spx} 空间索引粗筛候选行号,再对候选做精确的包围盒相交;
 * 索引不存在/损坏时退化为全表扫描(与 {@link OpenFileGdbLayer#search} 同款降级)。
 */
final class GdbSqlTable extends AbstractTable
        implements org.apache.calcite.schema.ProjectableFilterableTable,
        org.apache.calcite.schema.ScannableTable {

    private static final Logger LOG = LoggerFactory.getLogger(GdbSqlTable.class);

    private final OpenFileGdbLayer layer;

    /** 可选的空间过滤矩形 {xmin, ymin, xmax, ymax};null 表示不过滤。 */
    private final double[] bbox;

    /** 几何列在全宽行里的下标;-1 表示该表没有几何列。 */
    private final int geometryIndex;

    /** 全宽行的列数。 */
    private final int columnCount;

    /**
     * 构造一个 SQL 表。
     *
     * @param layer 图层
     * @param bbox  可选的空间过滤矩形(4 元素,null 表示无)
     */
    GdbSqlTable(OpenFileGdbLayer layer, double[] bbox) {
        this.layer = layer;
        this.bbox = bbox;
        int geomIdx = -1;
        List<GdbField> fields = layer.getFields();
        for (int i = 0; i < fields.size(); i++) {
            if (fields.get(i).isGeometry()) {
                geomIdx = i;
                break;
            }
        }
        this.geometryIndex = geomIdx;
        this.columnCount = fields.size();
    }

    @Override
    public RelDataType getRowType(RelDataTypeFactory typeFactory) {
        // 用 builder() 而不是已弃用的 FieldInfoBuilder
        RelDataTypeFactory.Builder builder = typeFactory.builder();
        for (GdbField field : layer.getFields()) {
            builder.add(field.getName(), GdbSqlTypes.toRelType(typeFactory, field));
        }
        return builder.build();
    }

    @Override
    public org.apache.calcite.schema.Statistic getStatistic() {
        // 行数是 O(1) 可得的(有效槽数),给规划器一个真实基数
        return Statistics.of((double) layer.getFeatureCount(), ImmutableList.of());
    }

    // ==================================================================
    // ScannableTable:无过滤全表扫描(兜底路径)
    // ==================================================================

    @Override
    public Enumerable<Object[]> scan(DataContext root) {
        return scan(root, new ArrayList<RexNode>(), null);
    }

    // ==================================================================
    // ProjectableFilterableTable:带下推过滤与投影的扫描
    // ==================================================================

    @Override
    public Enumerable<Object[]> scan(DataContext root, List<RexNode> filters,
                                     int[] projects) {
        // 1. 翻译能处理的条件,并按契约从可变列表里移除;
        //    留在列表里的由 Calcite 自己再滤一遍(宁重复,不出错)。
        final List<GdbSqlFilters.RowPredicate> predicates = new ArrayList<>();
        final BitSet referenced = new BitSet();
        Iterator<RexNode> it = filters.iterator();
        while (it.hasNext()) {
            RexNode filter = it.next();
            GdbSqlFilters.RowPredicate predicate = GdbSqlFilters.tryTranslate(filter);
            LOG.debug("图层 {}:下推条件 {} -> {}", layer.getName(), filter,
                    predicate == null ? "交给 Calcite" : "已接管");
            if (predicate != null) {
                collectInputRefs(filter, referenced);
                predicates.add(predicate);
                it.remove();
            }
        }
        if (!predicates.isEmpty()) {
            LOG.debug("图层 {}:下推并接管了 {} 个过滤条件,留下 {} 个给 Calcite",
                    layer.getName(), predicates.size(), filters.size());
        }

        // 2. 哪些列需要真的取值:投影列 + 已接管条件引用的列。
        //    其余列(尤其是几何)填 null,既不取属性也不解码几何。
        final BitSet needed = new BitSet();
        if (projects != null) {
            for (int p : projects) {
                needed.set(p);
            }
        } else {
            needed.set(0, columnCount);
        }
        needed.or(referenced);

        final int[] proj = projects;
        final List<GdbField> fields = layer.getFields();
        final boolean geomNeeded = geometryIndex >= 0 && needed.get(geometryIndex);

        return new AbstractEnumerable<Object[]>() {
            @Override
            public Enumerator<Object[]> enumerator() {
                // 每次枚举都拿全新游标:GdbTable.iterator() 每调一次新建一个
                Iterator<GdbFeature> source = bbox == null
                        ? layer.getTable().iterator()
                        : bboxIterator();
                return new RowEnumerator(source, fields, proj, predicates,
                        needed, geomNeeded);
            }
        };
    }

    // ==================================================================
    // 空间过滤
    // ==================================================================

    /**
     * 空间过滤的流式行迭代器:优先走 {@code .spx} 候选,再精确判包围盒相交。
     *
     * <p>候选行号数组会整个驻留(int/行,与 GDAL 的索引粗筛相比可忽略),
     * 但<b>要素本身不驻留</b> —— 按行号逐条定位读,过一条丢一条。
     */
    private Iterator<GdbFeature> bboxIterator() {
        int[] candidates = null;
        GdbSpatialIndex index = null;
        try {
            index = GdbSpatialIndex.open(layer.getTable().getPath());
            // open 返回 null 是"没有可用空间索引"的正常信号(与 OpenFileGdbLayer 同款),
            // 不是异常 —— 别把 null 当对象用,否则每次查询都要白抛一个 NPE
            if (index == null) {
                LOG.debug("图层 {} 没有空间索引,空间过滤退化为全表扫描", layer.getName());
            } else {
                candidates = index.search(bbox[0], bbox[1], bbox[2], bbox[3]);
            }
        } catch (RuntimeException e) {
            LOG.warn("图层 {} 的空间索引不可用,空间过滤退化为全表扫描: {}",
                    layer.getName(),
                    e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            candidates = null;
        } finally {
            if (index != null) {
                index.close();
            }
        }
        if (candidates == null || candidates.length == 0) {
            // 与 OpenFileGdbLayer.search 同款保守策略:空候选不当作"无命中",
            // 退回全表扫描逐条精确判断
            return fullScanWithBbox();
        }
        LOG.debug("图层 {} 空间索引粗筛出 {} 个候选", layer.getName(), candidates.length);
        final int[] rows = candidates;
        return new Iterator<GdbFeature>() {
            private int next;
            private GdbFeature pending;

            @Override
            public boolean hasNext() {
                if (pending != null) {
                    return true;
                }
                while (next < rows.length) {
                    GdbFeature f = layer.getTable().readFeature(rows[next++]);
                    if (f != null && intersectsBbox(f)) {
                        pending = f;
                        return true;
                    }
                }
                return false;
            }

            @Override
            public GdbFeature next() {
                if (!hasNext()) {
                    throw new NoSuchElementException();
                }
                GdbFeature f = pending;
                pending = null;
                return f;
            }
        };
    }

    /** 无索引(或索引不可用)时的空间过滤:全表流式扫 + 精确包围盒判断。 */
    private Iterator<GdbFeature> fullScanWithBbox() {
        return new Iterator<GdbFeature>() {
            private final Iterator<GdbFeature> source = layer.getTable().iterator();
            private GdbFeature pending;

            @Override
            public boolean hasNext() {
                if (pending != null) {
                    return true;
                }
                while (source.hasNext()) {
                    GdbFeature f = source.next();
                    if (intersectsBbox(f)) {
                        pending = f;
                        return true;
                    }
                }
                return false;
            }

            @Override
            public GdbFeature next() {
                if (!hasNext()) {
                    throw new NoSuchElementException();
                }
                GdbFeature f = pending;
                pending = null;
                return f;
            }
        };
    }

    /** 要素包围盒与空间过滤矩形是否相交(与 OpenFileGdbLayer.search 同判据)。 */
    /**
     * 要素与 bbox 是否<b>真正相交</b>(与 {@code OpenFileGdbLayer} 用的是同一套判定,
     * 见 {@link GdbGeometry#intersectsRect}):索引只是粗筛,这里做精确判定。
     */
    private boolean intersectsBbox(GdbFeature f) {
        GdbGeometry geom = f.getGeometry();
        return geom != null && geom.intersectsRect(bbox[0], bbox[1], bbox[2], bbox[3]);
    }

    // ==================================================================
    // 行构造
    // ==================================================================

    /**
     * 流式行枚举器:逐条要素 → 全宽行值 → 谓词过滤 → 列投影。
     */
    private final class RowEnumerator implements Enumerator<Object[]> {
        private final Iterator<GdbFeature> source;
        private final List<GdbField> fields;
        private final int[] projects;
        private final List<GdbSqlFilters.RowPredicate> predicates;
        private final BitSet needed;
        private final boolean geomNeeded;
        private Object[] current;

        RowEnumerator(Iterator<GdbFeature> source, List<GdbField> fields,
                      int[] projects, List<GdbSqlFilters.RowPredicate> predicates,
                      BitSet needed, boolean geomNeeded) {
            this.source = source;
            this.fields = fields;
            this.projects = projects;
            this.predicates = predicates;
            this.needed = needed;
            this.geomNeeded = geomNeeded;
        }

        @Override
        public Object[] current() {
            return current;
        }

        @Override
        public boolean moveNext() {
            while (source.hasNext()) {
                GdbFeature f = source.next();
                Object[] row = toRow(f);
                if (!passes(row)) {
                    continue;
                }
                current = projects == null ? row : project(row, projects);
                return true;
            }
            current = null;
            return false;
        }

        @Override
        public void reset() {
            // Calcite 的 Enumerator 契约里 reset 允许抛不支持;
            // 我们的数据源是单向流(.gdbtable 定位读),重扫请重开查询
            throw new UnsupportedOperationException(
                    "流式结果不支持 reset,请重新执行 executeSql");
        }

        @Override
        public void close() {
            // 底层是 FileChannel 定位读,无需要释放的游标资源
        }

        /** 要素 → 全宽 SQL 行。只对被引用到的列取值,几何列按需解码。 */
        private Object[] toRow(GdbFeature f) {
            Object[] row = new Object[columnCount];
            for (int i = 0; i < columnCount; i++) {
                if (!needed.get(i)) {
                    continue;
                }
                GdbField field = fields.get(i);
                if (field.isObjectId()) {
                    row[i] = f.getOid();
                } else if (field.isGeometry()) {
                    // getGeometry() 对空几何返回 null;blob 只在第一次被要时解码
                    row[i] = f.getGeometry();
                } else {
                    row[i] = GdbSqlTypes.toSqlValue(f.getAttribute(field.getName()));
                }
            }
            return row;
        }

        /** 已下推谓词全部为 TRUE 才放行(UNKNOWN 不入选,SQL WHERE 语义)。 */
        private boolean passes(Object[] row) {
            for (GdbSqlFilters.RowPredicate predicate : predicates) {
                if (!Boolean.TRUE.equals(predicate.test(row))) {
                    return false;
                }
            }
            return true;
        }

        private Object[] project(Object[] row, int[] projects) {
            Object[] out = new Object[projects.length];
            for (int i = 0; i < projects.length; i++) {
                out[i] = row[projects[i]];
            }
            return out;
        }
    }

    /** 收集条件表达式引用的输入列下标。 */
    private static void collectInputRefs(RexNode node, BitSet refs) {
        node.accept(new RexVisitorImpl<Void>(true) {
            @Override
            public Void visitInputRef(RexInputRef inputRef) {
                refs.set(inputRef.getIndex());
                return null;
            }
        });
    }

    /** 仅供日志/排查用的图层名。 */
    @Override
    public String toString() {
        return "<GdbSqlTable " + layer.getName() + " columns=" + columnCount
                + (bbox == null ? "" : " bbox=" + java.util.Arrays.toString(bbox)) + ">";
    }
}
