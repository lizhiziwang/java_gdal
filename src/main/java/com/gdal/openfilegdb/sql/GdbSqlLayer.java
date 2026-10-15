package com.gdal.openfilegdb.sql;

import com.gdal.openfilegdb.exception.GdbSqlException;
import com.gdal.openfilegdb.exception.GdbWriteException;
import com.gdal.openfilegdb.feature.FieldType;
import com.gdal.openfilegdb.feature.GdbFeature;
import com.gdal.openfilegdb.feature.GdbField;
import com.gdal.openfilegdb.feature.GdbGeomField;
import com.gdal.openfilegdb.feature.GdbGeometry;
import com.gdal.openfilegdb.feature.GeometryType;
import com.gdal.openfilegdb.layer.GdbFeatureDefn;
import com.gdal.openfilegdb.layer.GdbLayer;
import com.gdal.openfilegdb.layer.OpenFileGdbLayer;
import com.gdal.openfilegdb.srs.SpatialReference;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * {@code executeSql} 的查询结果 —— 一个<b>跟普通图层一样读</b>的结果图层。
 *
 * <p>对标 GDAL {@code ExecuteSQL()} 返回的 {@code OGRGenSQLResultsLayer}:
 * <ul>
 *   <li>{@link #getName()} 返回 {@code "SELECT"} —— GDAL 的 GenSQL 层也是拿固定串
 *       {@code "SELECT"} 作层名({@code ogrinfo -sql "..."} 打印的
 *       {@code Layer name: SELECT} 即来源于此);</li>
 *   <li>{@link #resetReading()} + {@link #getNextFeature()} 单游标逐条拉,
 *       与 {@link OpenFileGdbLayer} 用法完全一致,也能直接
 *       {@code for (GdbFeature f : layer)};</li>
 *   <li>必须释放:用 {@code OpenFileGdbDataset.releaseResultSet(layer)}
 *       (对应 GDAL 的 {@code ReleaseResultSet})或 try-with-resources。</li>
 * </ul>
 *
 * <h2>行 → 要素的映射规则</h2>
 * <ul>
 *   <li><b>几何列</b>(值类型是 {@link GdbGeometry} 的那一列)→
 *       {@link GdbFeature#getGeometry()},<b>不</b>再放进属性表;</li>
 *   <li><b>OBJECTID 列</b>(名字等于源图层 {@code GetFIDColumn()} 的列,通常就是
 *       {@code OBJECTID})→ {@link GdbFeature#getFID()},也不放进属性表 ——
 *       与普通图层的约定一致(那里 OBJECTID 同样只由 {@code getOid()} 暴露);</li>
 *   <li>其余列按名字进属性表,{@code getField("列名")} 可取。
 *       <b>没有 OBJECTID 列时</b>(例如 {@code SELECT COUNT(*) ...})FID 退化成
 *       从 1 开始的行号。</li>
 * </ul>
 *
 * <h2>要素定义是"读到第一行才定型"</h2>
 * 列类型取自 JDBC 元数据,但"哪一列是几何"要看实际的值类型 —— 所以
 * {@link #getLayerDefn()} 在取到第一行之后才完全确定(第一次取会自动预取一行并缓存,
 * 不会丢行)。<b>空结果集</b>只能靠元数据猜,此时几何列识别不出来,
 * {@link #getGeometryColumn()} 会是 {@code ""}。
 *
 * <h2>内存</h2>
 * 与 GDAL 同款取舍:{@code SELECT/WHERE/LIMIT} 与聚合全程流式;
 * {@code ORDER BY / DISTINCT / GROUP BY} 按 SQL 语义物化。
 */
public final class GdbSqlLayer implements GdbLayer, AutoCloseable, Iterator<GdbFeature> {

    /** GDAL 的 GenSQL 结果层名字就叫这个(OGRFeatureDefn("SELECT"))。 */
    private static final String GENSQL_LAYER_NAME = "SELECT";

    /** 源图层(取空间参考 / 几何类型 / 支持带 bbox 重跑)。 */
    private final List<OpenFileGdbLayer> sources;

    private final String sql;

    private GdbSqlResult result;

    /** 当前的空间过滤矩形(重跑查询用);{@code null} = 无。 */
    private double[] bbox;

    private GdbFeatureDefn defn;

    /** 几何列下标;-1 = 无几何列。 */
    private int geometryColumnIndex = -1;

    /** FID(OBJECTID)列下标;-1 = 没有。 */
    private int fidColumnIndex = -1;

    /** {@link #hasNext()} 预取的要素。 */
    private GdbFeature pending;

    /** 没有 OBJECTID 列时,用它给要素编 FID。 */
    private long nextFid = 1;

    private boolean closed;

    /**
     * 构造一个结果图层。
     *
     * @param sql     SQL 语句(已去掉尾分号)
     * @param result  底层游标式结果
     * @param sources 参与查询的源图层(可为 {@code null};非 null 时支持
     *                {@link #setSpatialFilterRect} 重新带 bbox 跑一遍)
     */
    public GdbSqlLayer(String sql, GdbSqlResult result, List<OpenFileGdbLayer> sources) {
        this.sql = sql;
        this.result = result;
        this.sources = sources == null
                ? Collections.<OpenFileGdbLayer>emptyList()
                : new ArrayList<>(sources);
    }

    // ==================================================================
    // 元信息
    // ==================================================================

    @Override
    public String getName() {
        return GENSQL_LAYER_NAME;
    }

    /** 原始 SQL 语句(GDAL 的 GenSQL 层没有对外接口,这里额外暴露,便于排查)。 */
    public String getSql() {
        return sql;
    }

    @Override
    public List<GdbField> getFields() {
        return getLayerDefn().getFields();
    }

    @Override
    public GdbFeatureDefn getLayerDefn() {
        if (defn == null) {
            Object[] row = result.nextRow();
            if (row != null) {
                buildDefn(row);
                pending = toFeature(row);
            } else {
                buildDefn(null);
            }
        }
        return defn;
    }

    @Override
    public GdbField getField(String name) {
        if (name == null) {
            return null;
        }
        for (GdbField f : getFields()) {
            if (f.getName().equalsIgnoreCase(name)) {
                return f;
            }
        }
        return null;
    }

    @Override
    public GeometryType getGeometryType() {
        return getGeomType();
    }

    @Override
    public GeometryType getGeomType() {
        if (geometryColumnIndex < 0 && defn == null) {
            // 还没定型:先触发一次定型,再看有没有几何列
            getLayerDefn();
        }
        if (geometryColumnIndex < 0) {
            return GeometryType.UNKNOWN;
        }
        OpenFileGdbLayer src = firstSpatialSource();
        return src == null ? GeometryType.UNKNOWN : src.getGeomType();
    }

    @Override
    public SpatialReference getSpatialReference() {
        return getSpatialRef();
    }

    /**
     * 空间参考:取<b>第一个带几何的源图层</b>的坐标系。
     *
     * <p>与 GDAL 的 {@code OGRGenSQLResultsLayer} 一致 —— 它也是从参与查询的第一个
     * 图层上取 {@code GetSpatialRef()} 存起来;SQL 层自己并不重新推断坐标系。
     */
    @Override
    public SpatialReference getSpatialRef() {
        if (geometryColumnIndex < 0 && defn == null) {
            getLayerDefn();
        }
        if (geometryColumnIndex < 0) {
            return SpatialReference.EMPTY;
        }
        OpenFileGdbLayer src = firstSpatialSource();
        return src == null ? SpatialReference.EMPTY : src.getSpatialRef();
    }

    @Override
    public String getFIDColumn() {
        getLayerDefn();
        return fidColumnIndex < 0
                ? ""
                : result.getColumnNames().get(fidColumnIndex);
    }

    @Override
    public String getGeometryColumn() {
        getLayerDefn();
        return geometryColumnIndex < 0
                ? ""
                : result.getColumnNames().get(geometryColumnIndex);
    }

    // ==================================================================
    // 计数 / 范围
    // ==================================================================

    /**
     * 要素数:SQL 结果层<b>默认未知</b>,返回 {@code -1}(OGR 的约定:
     * {@code GetFeatureCount} 无法快速确定时返回 -1)。
     *
     * @return -1
     */
    @Override
    public long getFeatureCount() {
        return -1;
    }

    @Override
    public long getFeatureCount(boolean force) {
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
     * 包围盒:无几何列返回 {@code null};否则 {@code force=true} 时扫一遍算出来。
     *
     * @return {@code {xmin, ymin, xmax, ymax}} 或 {@code null}
     */
    @Override
    public double[] getExtent() {
        return getExtent(true);
    }

    @Override
    public double[] getExtent(boolean force) {
        if (!force) {
            return null;
        }
        getLayerDefn();
        if (geometryColumnIndex < 0) {
            return null;
        }
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
            xmin = Math.min(xmin, e[0]);
            ymin = Math.min(ymin, e[1]);
            xmax = Math.max(xmax, e[2]);
            ymax = Math.max(ymax, e[3]);
        }
        resetReading();
        if (xmin > xmax || ymin > ymax) {
            return null;
        }
        return new double[]{xmin, ymin, xmax, ymax};
    }

    // ==================================================================
    // 遍历
    // ==================================================================

    @Override
    public GdbFeature getNextFeature() {
        ensureOpen();
        if (pending != null) {
            GdbFeature f = pending;
            pending = null;
            return f;
        }
        if (defn == null) {
            Object[] row = result.nextRow();
            if (row == null) {
                buildDefn(null);
                return null;
            }
            buildDefn(row);
            return toFeature(row);
        }
        Object[] row = result.nextRow();
        return row == null ? null : toFeature(row);
    }

    /**
     * 复位游标(对应 OGR {@code ResetReading}):重新执行一遍查询回到第一行之前。
     *
     * <p>已关闭的结果不能复位;第二次执行同一 SQL 会命中 Calcite 的语句缓存,
     * 规划开销显著低于第一次。
     */
    @Override
    public void resetReading() {
        ensureOpen();
        pending = null;
        nextFid = 1;
        result.reset();
    }

    @Override
    public Iterator<GdbFeature> iterator() {
        return this;
    }

    @Override
    public boolean hasNext() {
        ensureOpen();
        if (pending != null) {
            return true;
        }
        pending = getNextFeatureInternal();
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

    private GdbFeature getNextFeatureInternal() {
        return getNextFeature();
    }

    /**
     * 随机读:SQL 结果层<b>不支持</b>(OGR 的 GenSQL 层同样没有高效随机读),返回
     * {@code null};要看某条请把它写进 SELECT 列表或用 {@code WHERE OBJECTID = n}。
     */
    @Override
    public GdbFeature getFeature(long oid) {
        return null;
    }

    // ==================================================================
    // 空间过滤:带 bbox 重跑一遍查询(GDAL 的 ExecuteSQL(sql, poSpatialFilter))
    // ==================================================================

    @Override
    public GdbLayer setSpatialFilterRect(double xmin, double ymin, double xmax, double ymax) {
        return setSpatialFilter(new double[]{xmin, ymin, xmax, ymax});
    }

    /**
     * 设置空间过滤:重新执行一遍 SQL,把矩形作为扫描源的前置条件。
     *
     * <p>等价于 {@code OpenFileGdbDataset.executeSql(sql, bbox)} —— 有 {@code .spx}
     * 索引时先粗筛候选行号,比"查完再在 Java 里过滤"快得多。
     */
    @Override
    public GdbLayer setSpatialFilter(double[] rect) {
        ensureOpen();
        if (rect == null) {
            if (bbox != null) {
                bbox = null;
                reexecute();
            }
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
        bbox = copy;
        reexecute();
        return this;
    }

    @Override
    public void clearSpatialFilter() {
        setSpatialFilter(null);
    }

    @Override
    public double[] getSpatialFilter() {
        return bbox == null ? null : bbox.clone();
    }

    private void reexecute() {
        result.close();
        defn = null;
        geometryColumnIndex = -1;
        fidColumnIndex = -1;
        pending = null;
        nextFid = 1;
        result = GdbSqlEngine.execute(sql, bbox, sources);
    }

    // ==================================================================
    // 能力 / 写入
    // ==================================================================

    @Override
    public boolean testCapability(String capability) {
        if (capability == null) {
            return false;
        }
        switch (capability) {
            case "OLCStringsAsUTF8":
                return true;
            case "OLCFastSpatialFilter":
                // 有源图层就能带 bbox 重跑(走 .spx 索引),否则只能全表扫
                return !sources.isEmpty();
            // GenSQL 结果层一律只读、不支持随机读、要素数未知
            case "OLCRandomRead":
            case "OLCFastFeatureCount":
            case "OLCFastGetExtent":
            case "OLCFastSetNextByIndex":
            case "OLCSequentialWrite":
            case "OLCRandomWrite":
            case "OLCDeleteFeature":
            case "OLCCreateField":
            case "OLCTransactions":
            case "OLCCurveGeometries":
            default:
                return false;
        }
    }

    @Override
    public long createFeature(GdbFeature feature) {
        throw new GdbWriteException(
                "SQL 结果层是只读的,不能 CreateFeature(GDAL 的 GenSQL 层同样只读)");
    }

    @Override
    public void updateFeature(GdbFeature feature) {
        throw new GdbWriteException(
                "SQL 结果层是只读的,不能 SetFeature(GDAL 的 GenSQL 层同样只读)");
    }

    @Override
    public boolean deleteFeature(long oid) {
        throw new GdbWriteException(
                "SQL 结果层是只读的,不能 DeleteFeature(GDAL 的 GenSQL 层同样只读)");
    }

    // ==================================================================
    // 资源
    // ==================================================================

    /**
     * 取底层游标式结果(想按 {@code Object[]} 直接读行时用)。
     *
     * <p>注意:拿到它之后请不要再单独 {@code close()} —— 本层的 {@link #close()}
     * 会一并关掉。
     */
    public GdbSqlResult getResult() {
        return result;
    }

    /** 关闭并释放 JDBC 资源;幂等。 */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        pending = null;
        result.close();
    }

    // ==================================================================
    // 内部:要素定义与行转换
    // ==================================================================

    /**
     * 建立要素定义。
     *
     * @param sample 第一行(可为 {@code null},表示空结果集)
     */
    private void buildDefn(Object[] sample) {
        List<String> names = result.getColumnNames();
        int[] types = result.getColumnTypes();
        String[] classNames = result.getColumnClassNames();
        List<GdbField> fields = new ArrayList<>(names.size());
        int geom = -1;
        int fid = -1;
        for (int i = 0; i < names.size(); i++) {
            String name = names.get(i);
            boolean isGeom = isGeometryColumn(i, sample, types, classNames);
            if (isGeom && geom < 0) {
                geom = i;
                GdbGeomField gf = new GdbGeomField(name);
                gf.setNullable(true);
                SpatialReference srs = firstSpatialSource() == null
                        ? SpatialReference.EMPTY : firstSpatialSource().getSpatialRef();
                gf.setWkt(srs.getWkt() == null ? "" : srs.getWkt());
                fields.add(gf);
            } else if (isGeom) {
                // FileGDB 一个表只有一个几何字段,多出来的按二进制处理
                fields.add(new GdbField(name, FieldType.BINARY).setNullable(true));
            } else {
                fields.add(new GdbField(name, fieldTypeFor(types[i])).setNullable(true));
            }
            if (fid < 0 && isFidColumnName(name)) {
                fid = i;
            }
        }
        this.geometryColumnIndex = geom;
        this.fidColumnIndex = fid;
        this.defn = new GdbFeatureDefn(fields);
    }

    /**
     * 判定第 {@code i} 列是不是几何列。三条线索取或:
     * 实际值类型 / JDBC 声明的类名 / 列名等于源图层的几何字段名且类型是不透明类型。
     */
    private boolean isGeometryColumn(int i, Object[] sample, int[] types, String[] classNames) {
        if (sample != null && sample[i] instanceof GdbGeometry) {
            return true;
        }
        if (classNames != null && classNames[i] != null
                && GdbGeometry.class.getName().equals(classNames[i])) {
            return true;
        }
        if (types[i] != Types.OTHER && types[i] != Types.JAVA_OBJECT) {
            return false;
        }
        String name = result.getColumnNames().get(i);
        for (OpenFileGdbLayer src : sources) {
            String gcol = src.getGeometryColumn();
            if (!gcol.isEmpty() && gcol.equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }

    /** 列名是否算 FID 列(源图层的 {@code GetFIDColumn()},通常是 OBJECTID)。 */
    private boolean isFidColumnName(String name) {
        if ("OBJECTID".equalsIgnoreCase(name)) {
            return true;
        }
        for (OpenFileGdbLayer src : sources) {
            String fcol = src.getFIDColumn();
            if (!fcol.isEmpty() && fcol.equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }

    /** JDBC 列类型 → 本库字段类型。 */
    private static FieldType fieldTypeFor(int jdbcType) {
        switch (jdbcType) {
            case Types.SMALLINT:
            case Types.TINYINT:
                return FieldType.INT16;
            case Types.INTEGER:
                return FieldType.INT32;
            case Types.BIGINT:
                return FieldType.INT64;
            case Types.REAL:
            case Types.FLOAT:
                return FieldType.FLOAT32;
            case Types.DOUBLE:
            case Types.NUMERIC:
            case Types.DECIMAL:
                return FieldType.FLOAT64;
            case Types.DATE:
                return FieldType.DATE;
            case Types.TIME:
                return FieldType.TIME;
            case Types.TIMESTAMP:
            case Types.TIMESTAMP_WITH_TIMEZONE:
                return FieldType.DATETIME;
            case Types.BIT:
            case Types.BOOLEAN:
                return FieldType.INT16;
            case Types.BINARY:
            case Types.VARBINARY:
            case Types.LONGVARBINARY:
                return FieldType.BINARY;
            default:
                return FieldType.STRING;
        }
    }

    /** 行 → 要素(几何列与 FID 列单独处理,其余进属性表)。 */
    private GdbFeature toFeature(Object[] row) {
        List<String> names = result.getColumnNames();
        GdbFeature f = new GdbFeature();
        for (int i = 0; i < row.length && i < names.size(); i++) {
            Object v = row[i];
            if (i == geometryColumnIndex) {
                if (v instanceof GdbGeometry) {
                    f.setGeometry((GdbGeometry) v);
                }
                continue;
            }
            if (i == fidColumnIndex) {
                if (v instanceof Number) {
                    f.setFID(((Number) v).longValue());
                }
                continue;
            }
            f.setAttribute(names.get(i), v);
        }
        if (!f.hasOid()) {
            f.setFID(nextFid++);
        }
        return f;
    }

    /** 第一个带几何的源图层(GDAL 取坐标系 / 几何类型的基准)。 */
    private OpenFileGdbLayer firstSpatialSource() {
        for (OpenFileGdbLayer src : sources) {
            if (src.getGeometryType() != GeometryType.UNKNOWN) {
                return src;
            }
        }
        return null;
    }

    private void ensureOpen() {
        if (closed) {
            throw new GdbSqlException("SQL 结果图层已关闭(ReleaseResultSet 之后不能再读)");
        }
    }

    @Override
    public String toString() {
        return "<GdbSqlLayer SELECT sql=\"" + sql
                + "\" columns=" + result.getColumnNames() + ">";
    }
}
