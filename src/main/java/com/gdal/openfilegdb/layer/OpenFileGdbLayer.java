package com.gdal.openfilegdb.layer;

import com.gdal.openfilegdb.exception.GdbWriteException;
import com.gdal.openfilegdb.feature.GdbFeature;
import com.gdal.openfilegdb.feature.GdbField;
import com.gdal.openfilegdb.feature.GdbGeomField;
import com.gdal.openfilegdb.feature.GdbGeometry;
import com.gdal.openfilegdb.feature.GeometryType;
import com.gdal.openfilegdb.index.GdbSpatialIndex;
import com.gdal.openfilegdb.srs.SpatialReference;
import com.gdal.openfilegdb.table.GdbTable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.function.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 一个 FileGDB 图层(要素类或非空间表)—— 对标 GDAL 的
 * {@code OGROpenFileGDBLayer}({@code ogr/ogrsf_frmts/openfilegdb/ogropenfilegdblayer.cpp})。
 *
 * <p>它是 {@link GdbTable} 之上的薄封装:把"表"暴露成"图层",提供 OGR 那套
 * 单游标遍历({@link #getNextFeature()} / {@link #resetReading()})、随机读
 * ({@link #getFeature(long)}),以及 {@link #setSpatialFilterRect} 设置后的
 * <b>流式</b>空间过滤。完整的方法与 OGR 名字对照见 {@link GdbLayer}。
 *
 * <p><b>单游标语义</b>(与 OGR 一致):一个图层只有一份读取游标,
 * {@link #iterator()} 返回 {@code this};嵌套遍历必须先 {@link #resetReading()}。
 *
 * <p><b>几何类型 / 空间参考的来源</b>:权威来源是物理 {@code .gdbtable} 的字段描述区
 * (见 {@link GdbTable#getGeomField()}),<b>不</b>来自 {@code GDB_Items.Definition}
 * XML —— GDAL 源码原文:"We cannot trust the XML definition to build the field
 * definitions"(ogropenfilegdblayer.cpp:163)。
 *
 * <h2>两套过滤 API 的分工</h2>
 * <ul>
 *   <li><b>流式</b>(推荐):{@link #setSpatialFilterRect} 设好矩形后
 *       {@code getNextFeature()} / {@code for (GdbFeature f : layer)} 逐条吐 ——
 *       内存 O(1),与 OGR 同款;</li>
 *   <li><b>急切</b>:{@link #search} / {@link #where} 把命中要素<b>全部</b>驻留在
 *       {@code List} 里返回(且 {@code search} 会解出每条几何),大表慎用 ——
 *       它们是 OGR 没有的 Java 便利方法。</li>
 * </ul>
 */
public final class OpenFileGdbLayer implements GdbLayer, Iterator<GdbFeature> {

    private static final Logger LOG = LoggerFactory.getLogger(OpenFileGdbLayer.class);

    private final String name;
    private final GdbTable table;

    /** 遍历游标:下一条待检查的 0 基行号。 */
    private int cursorRow;

    /** {@link #hasNext()} 预取的要素(与 {@link #getNextFeature()} 共用)。 */
    private GdbFeature pending;

    /** 要素定义(懒构造,字段清单不变故只算一次)。 */
    private GdbFeatureDefn defn;

    /** 当前空间过滤矩形 {@code {xmin, ymin, xmax, ymax}};{@code null} = 不过滤。 */
    private double[] spatialFilter;

    /** 空间索引粗筛出的候选行号(升序);{@code null} = 退化为全表扫描。 */
    private int[] spatialCandidates;

    /** 候选集是否已经解析过(解析失败也记 true,避免每条要素重试一次)。 */
    private boolean candidatesResolved;

    /** {@link #spatialCandidates} 的消费位置。 */
    private int candidatePos;

    /**
     * 构造一个图层。
     *
     * @param name  图层名(GDB_SystemCatalog 里的逻辑名)
     * @param table 底层表
     */
    public OpenFileGdbLayer(String name, GdbTable table) {
        this.name = name;
        this.table = table;
    }

    // ==================================================================
    // 元信息(OGR: GetName / GetLayerDefn / GetGeomType / GetSpatialRef)
    // ==================================================================

    @Override
    public String getName() {
        return name;
    }

    @Override
    public List<GdbField> getFields() {
        return table.getFields();
    }

    @Override
    public GdbFeatureDefn getLayerDefn() {
        if (defn == null) {
            defn = new GdbFeatureDefn(table.getFields());
        }
        return defn;
    }

    @Override
    public GdbField getField(String name) {
        return table.getFieldByName(name);
    }

    @Override
    public GeometryType getGeometryType() {
        return table.getGeometryType();
    }

    @Override
    public GeometryType getGeomType() {
        return getGeometryType();
    }

    /**
     * 空间参考。
     *
     * <p>由几何字段描述区的 WKT 反解(见 {@link SpatialReference#fromWkt});无几何字段
     * 或 WKT 为空时返回 {@link SpatialReference#EMPTY}。
     *
     * @return 空间参考
     */
    @Override
    public SpatialReference getSpatialReference() {
        GdbGeomField gf = table.getGeomField();
        if (gf == null || gf.getWkt() == null || gf.getWkt().isEmpty()) {
            return SpatialReference.EMPTY;
        }
        return SpatialReference.fromWkt(gf.getWkt());
    }

    @Override
    public SpatialReference getSpatialRef() {
        return getSpatialReference();
    }

    @Override
    public String getFIDColumn() {
        String n = table.getOidFieldName();
        return n == null ? "" : n;
    }

    @Override
    public String getGeometryColumn() {
        String n = table.getGeometryFieldName();
        return n == null ? "" : n;
    }

    // ==================================================================
    // 计数 / 范围(OGR: GetFeatureCount / GetExtent)
    // ==================================================================

    /**
     * 要素数;等价于 OGR {@code GetFeatureCount(bForce=TRUE)}。
     *
     * <p>没设空间过滤时是 O(1)(直接读表头的有效记录数,不含空洞);设了过滤就要
     * 逐条扫一遍(与 OGR 一致)。
     *
     * @return 要素数
     */
    @Override
    public long getFeatureCount() {
        return getFeatureCount(true);
    }

    @Override
    public long getFeatureCount(boolean force) {
        if (spatialFilter == null) {
            return table.getValidRecordCount();
        }
        if (!force) {
            return -1;
        }
        resetReading();
        long n = 0;
        while (getNextFeature() != null) {
            n++;
        }
        resetReading();
        return n;
    }

    /**
     * 全表包围盒;等价于 OGR {@code GetExtent(&sExtent, bForce=TRUE)}。
     *
     * <p>没设空间过滤时优先用表头声明的范围(O(1));表头为空则退化为逐条算。
     *
     * @return {@code {xmin, ymin, xmax, ymax}};无几何字段、表里没有带几何的要素时
     *         返回 {@code null}
     */
    @Override
    public double[] getExtent() {
        return getExtent(true);
    }

    @Override
    public double[] getExtent(boolean force) {
        if (spatialFilter == null) {
            double[] header = headerExtent();
            if (header != null) {
                return header;
            }
        }
        if (!force) {
            return null;
        }
        return computeExtent();
    }

    /** 表头声明的范围;拿不到(无几何字段 / 是 NaN)返回 {@code null}。 */
    private double[] headerExtent() {
        GdbGeomField gf = table.getGeomField();
        if (gf == null) {
            return null;
        }
        // 只读打开时 GdbTable.getExtent() 可能为 null,退回字段描述区里的范围
        double[] e = table.getExtent();
        if (e == null) {
            e = new double[]{gf.getXmin(), gf.getYmin(), gf.getXmax(), gf.getYmax()};
        }
        for (double v : e) {
            if (Double.isNaN(v)) {
                return null;
            }
        }
        return e;
    }

    /** 逐条扫出包围盒(带当前空间过滤);扫完把游标复位。 */
    private double[] computeExtent() {
        double xmin = Double.POSITIVE_INFINITY;
        double ymin = Double.POSITIVE_INFINITY;
        double xmax = Double.NEGATIVE_INFINITY;
        double ymax = Double.NEGATIVE_INFINITY;
        resetReading();
        GdbFeature f;
        while ((f = getNextFeature()) != null) {
            GdbGeometry g = f.getGeometry();
            if (g == null) {
                continue;
            }
            double[] e = g.envelope();
            if (e == null) {
                continue;
            }
            if (e[0] < xmin) {
                xmin = e[0];
            }
            if (e[1] < ymin) {
                ymin = e[1];
            }
            if (e[2] > xmax) {
                xmax = e[2];
            }
            if (e[3] > ymax) {
                ymax = e[3];
            }
        }
        resetReading();
        if (xmin > xmax || ymin > ymax) {
            return null;
        }
        return new double[]{xmin, ymin, xmax, ymax};
    }

    // ==================================================================
    // 遍历(OGR: GetNextFeature / ResetReading)
    // ==================================================================

    /**
     * 取下一条要素(会把 {@link #hasNext()} 预取的结果一并消费)。
     *
     * @return 要素;读到末尾返回 {@code null}
     */
    public GdbFeature nextFeature() {
        return getNextFeature();
    }

    /** 把游标复位到开头。 */
    public void reset() {
        resetReading();
    }

    @Override
    public GdbFeature getNextFeature() {
        if (pending != null) {
            GdbFeature f = pending;
            pending = null;
            return f;
        }
        return advance();
    }

    @Override
    public void resetReading() {
        cursorRow = 0;
        candidatePos = 0;
        pending = null;
    }

    /** {@link Iterator} 支持:与 {@link #getNextFeature()} 共用同一游标。 */
    @Override
    public Iterator<GdbFeature> iterator() {
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
    public GdbFeature next() {
        if (!hasNext()) {
            throw new NoSuchElementException();
        }
        GdbFeature f = pending;
        pending = null;
        return f;
    }

    /** 推进游标到下一条满足过滤条件的要素。 */
    private GdbFeature advance() {
        if (spatialFilter == null) {
            int total = table.getRecordCount();
            while (cursorRow < total) {
                GdbFeature f = table.readFeature(cursorRow++);
                if (f != null) {
                    return f;
                }
            }
            return null;
        }
        resolveCandidates();
        if (spatialCandidates != null) {
            while (candidatePos < spatialCandidates.length) {
                GdbFeature f = table.readFeature(spatialCandidates[candidatePos++]);
                if (f != null && intersects(f, spatialFilter)) {
                    return f;
                }
            }
            return null;
        }
        int total = table.getRecordCount();
        while (cursorRow < total) {
            GdbFeature f = table.readFeature(cursorRow++);
            if (f != null && intersects(f, spatialFilter)) {
                return f;
            }
        }
        return null;
    }

    // ==================================================================
    // 空间过滤(OGR: SetSpatialFilterRect / SetSpatialFilter / GetSpatialFilter)
    // ==================================================================

    @Override
    public GdbLayer setSpatialFilterRect(double xmin, double ymin, double xmax, double ymax) {
        return setSpatialFilter(new double[]{xmin, ymin, xmax, ymax});
    }

    @Override
    public GdbLayer setSpatialFilter(double[] rect) {
        if (rect == null) {
            clearSpatialFilter();
            return this;
        }
        if (rect.length != 4) {
            throw new IllegalArgumentException(
                    "空间过滤矩形必须是 {xmin, ymin, xmax, ymax} 的 4 元素数组,实际长度 "
                            + rect.length);
        }
        for (double v : rect) {
            if (Double.isNaN(v) || Double.isInfinite(v)) {
                throw new IllegalArgumentException(
                        "空间过滤矩形含 NaN/Infinity: " + Arrays.toString(rect));
            }
        }
        double[] copy = rect.clone();
        // 容忍用户把 x0/x1 写反(OGR 的 OGREnvelope 内部也会规整)
        if (copy[0] > copy[2]) {
            double t = copy[0];
            copy[0] = copy[2];
            copy[2] = t;
        }
        if (copy[1] > copy[3]) {
            double t = copy[1];
            copy[1] = copy[3];
            copy[3] = t;
        }
        this.spatialFilter = copy;
        this.spatialCandidates = null;
        this.candidatesResolved = false;
        resetReading();
        LOG.debug("图层 {} 设置空间过滤 {}", name, Arrays.toString(copy));
        return this;
    }

    @Override
    public void clearSpatialFilter() {
        spatialFilter = null;
        spatialCandidates = null;
        candidatesResolved = false;
        resetReading();
    }

    @Override
    public double[] getSpatialFilter() {
        return spatialFilter == null ? null : spatialFilter.clone();
    }

    /**
     * 解析空间索引候选行号(只解一次)。
     *
     * <p>先用 {@code .spx} 粗筛(对应 GDAL 构造 {@code FileGDBSpatialIndexIterator}),
     * 索引不可用就置 {@code null} 退化成全表扫描 —— 与 {@link #search} 同款降级策略。
     * 结果按行号升序排列,保证遍历顺序与不设过滤时一致。
     */
    private void resolveCandidates() {
        if (candidatesResolved) {
            return;
        }
        candidatesResolved = true;
        spatialCandidates = null;
        if (spatialFilter == null) {
            return;
        }
        GdbSpatialIndex index;
        try {
            index = GdbSpatialIndex.open(table.getPath());
        } catch (RuntimeException e) {
            // 索引损坏/不可用:降级为全表扫描(与 GDAL 的降级行为一致)
            LOG.warn("图层 {} 的空间索引不可用,退化为全表扫描: {}", name, e.getMessage());
            return;
        }
        if (index == null) {
            LOG.debug("图层 {} 没有空间索引,退化为全表扫描", name);
            return;
        }
        try {
            int[] rows = index.search(spatialFilter[0], spatialFilter[1],
                    spatialFilter[2], spatialFilter[3]);
            if (rows != null && rows.length > 0) {
                int[] sorted = rows.clone();
                Arrays.sort(sorted);
                spatialCandidates = sorted;
                LOG.debug("图层 {} 空间索引粗筛出 {} 个候选", name, sorted.length);
            } else {
                LOG.debug("图层 {} 空间索引返回空候选,退化为全表扫描", name);
            }
        } catch (RuntimeException e) {
            LOG.warn("图层 {} 空间索引查询失败,退化为全表扫描: {}", name, e.getMessage());
            spatialCandidates = null;
        } finally {
            index.close();
        }
    }

    // ==================================================================
    // 随机读(OGR: GetFeature)与急切过滤
    // ==================================================================

    /**
     * 按 OID 随机读一条。
     *
     * <p>⚠️ 与空间过滤<b>无关</b>:OGR 的 {@code GetFeature(fid)} 同样绕过
     * {@code SetSpatialFilter} 设的过滤(过滤器只作用于 {@code GetNextFeature})。
     *
     * @param oid 要素 ID(1 基)
     * @return 要素;OID 非法或空槽返回 {@code null}
     */
    @Override
    public GdbFeature getFeature(long oid) {
        if (oid <= 0 || oid > Integer.MAX_VALUE) {
            return null;
        }
        return table.readFeature((int) (oid - 1));
    }

    /**
     * 空间范围过滤(<b>急切</b>:结果全部驻留 {@code List})。
     *
     * <p>需要流式遍历请用 {@link #setSpatialFilterRect} + {@link #getNextFeature()}。
     *
     * @param xmin 查询矩形 X 最小
     * @param ymin 查询矩形 Y 最小
     * @param xmax 查询矩形 X 最大
     * @param ymax 查询矩形 Y 最大
     * @return 相交的要素列表(按行号升序)
     */
    public List<GdbFeature> search(double xmin, double ymin, double xmax, double ymax) {
        List<GdbFeature> out = new ArrayList<>();
        GdbSpatialIndex index = null;
        try {
            index = GdbSpatialIndex.open(table.getPath());
        } catch (RuntimeException e) {
            LOG.warn("图层 {} 的空间索引不可用,退化为全表扫描: {}", name, e.getMessage());
            index = null;
        }
        if (index != null) {
            try {
                int[] candidates = index.search(xmin, ymin, xmax, ymax);
                if (candidates.length > 0) {
                    LOG.debug("图层 {} 空间索引粗筛出 {} 个候选", name, candidates.length);
                    int[] sorted = candidates.clone();
                    Arrays.sort(sorted);
                    for (int row : sorted) {
                        GdbFeature f = table.readFeature(row);
                        if (f != null && intersects(f, xmin, ymin, xmax, ymax)) {
                            out.add(f);
                        }
                    }
                    return out;
                }
                LOG.debug("图层 {} 空间索引返回空候选,退化为全表扫描", name);
            } catch (RuntimeException e) {
                LOG.warn("图层 {} 空间索引查询失败,退化为全表扫描: {}", name, e.getMessage());
                out.clear();
            } finally {
                index.close();
            }
        }
        for (GdbFeature f : table.features()) {
            if (intersects(f, xmin, ymin, xmax, ymax)) {
                out.add(f);
            }
        }
        return out;
    }

    /**
     * 属性过滤(<b>急切</b>:结果全部驻留 {@code List})。
     *
     * @param predicate 返回 {@code true} 的要素入选
     * @return 入选的要素列表
     */
    public List<GdbFeature> where(Predicate<GdbFeature> predicate) {
        List<GdbFeature> out = new ArrayList<>();
        for (GdbFeature f : table.features()) {
            if (predicate.test(f)) {
                out.add(f);
            }
        }
        return out;
    }

    /**
     * 要素与矩形是否<b>真正相交</b>(OGR 的 {@code Intersects} 语义,不是包围盒相交)。
     *
     * <p>见 {@link GdbGeometry#intersectsRect}:凹多边形上两者会差出个别要素,
     * GDAL 用的是精确判定,这里照抄。
     */
    private static boolean intersects(GdbFeature f, double xmin, double ymin,
                                      double xmax, double ymax) {
        GdbGeometry geom = f.getGeometry();
        return geom != null && geom.intersectsRect(xmin, ymin, xmax, ymax);
    }

    /** 要素包围盒与 {@link #spatialFilter} 是否相交。 */
    private static boolean intersects(GdbFeature f, double[] rect) {
        return intersects(f, rect[0], rect[1], rect[2], rect[3]);
    }

    // ==================================================================
    // 能力(OGR: TestCapability)
    // ==================================================================

    @Override
    public boolean testCapability(String capability) {
        if (capability == null) {
            return false;
        }
        boolean writable = table.isWritable();
        boolean hasTablx = table.getTableIndex() != null;
        switch (capability) {
            case "OLCRandomRead":
                return hasTablx;
            case "OLCSequentialWrite":
                return writable;
            case "OLCRandomWrite":
                return writable && hasTablx;
            case "OLCFastSpatialFilter":
                return GdbSpatialIndex.exists(table.getPath());
            case "OLCFastFeatureCount":
            case "OLCFastGetExtent":
            case "OLCStringsAsUTF8":
                return true;
            case "OLCFastSetNextByIndex":
                return hasTablx;
            case "OLCDeleteFeature":
                return writable;
            case "OLCZGeometries":
                return table.hasZ();
            case "OLCMeasuredGeometries":
                return table.hasM();
            // 建字段 / 事务 / 曲线几何 / 空间索引维护,本库都不做
            case "OLCCreateField":
            case "OLCTransactions":
            case "OLCCurveGeometries":
            default:
                return false;
        }
    }

    // ==================================================================
    // 写入(OGR: CreateFeature / SetFeature / DeleteFeature)
    // ==================================================================

    /**
     * 追加一条要素。
     *
     * @param feature 要素
     * @return 分配到的 OID(1 基)
     * @throws GdbWriteException 表是只读打开的
     */
    @Override
    public long createFeature(GdbFeature feature) {
        requireWritable("CreateFeature");
        return table.appendFeature(feature);
    }

    /**
     * 就地更新一条要素(需要 {@link GdbFeature#getOid()})。
     *
     * @param feature 要素
     * @throws GdbWriteException 表是只读打开的
     */
    @Override
    public void updateFeature(GdbFeature feature) {
        requireWritable("SetFeature");
        table.updateFeature(feature);
    }

    /**
     * 删除一条要素(逻辑删除)。
     *
     * @param oid 要素 ID
     * @return 是否真的删掉了一条
     * @throws GdbWriteException 表是只读打开的
     */
    @Override
    public boolean deleteFeature(long oid) {
        requireWritable("DeleteFeature");
        return table.deleteFeature(oid);
    }

    private void requireWritable(String what) {
        if (!table.isWritable()) {
            throw new GdbWriteException(name + ": 图层是只读打开的,不能 " + what
                    + ";请用 OpenFileGdbDataset.create()/openForWrite() 打开");
        }
    }

    // ==================================================================

    /** 底层表。 */
    public GdbTable getTable() {
        return table;
    }

    @Override
    public String toString() {
        return "<OpenFileGdbLayer " + name + " geom=" + getGeometryType().ogrName()
                + " fields=" + getFields().size() + " features=" + getFeatureCount() + ">";
    }
}
