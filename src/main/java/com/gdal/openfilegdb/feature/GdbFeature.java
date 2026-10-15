package com.gdal.openfilegdb.feature;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 一条要素 = OID + 属性 + 几何。
 *
 * <p>对标 OGR 的 {@code OGRFeature}。属性字典里 <b>只放非几何、非 OID</b> 的字段:
 * <ul>
 *   <li>OBJECTID 不放进属性表,它由行号推导,落在 {@link #getOid()}
 *       —— 对标 GDAL {@code FileGDBTable::SelectRow} 之后的
 *       {@code SetFID(iRow + 1)}。</li>
 *   <li>几何落在 {@link #getGeometry()} / {@link #getGeometryWkb()}。</li>
 * </ul>
 *
 * <p>字段名的匹配是 <b>大小写不敏感</b> 的(与 OGR 一致:
 * {@code OGRFeature::GetFieldIndex} 用 {@code EQUAL} 宏比较),
 * 因此 {@code getAttribute("name")} 与 {@code getAttribute("NAME")} 等价。
 */
public class GdbFeature {

    /** 要素 ID(1 基;0 表示尚未分配)。 */
    private long oid;

    /** 属性,保持插入顺序以匹配字段定义顺序。 */
    private final Map<String, Object> attributes = new LinkedHashMap<>();

    /** 几何(可能为 null)。 */
    private GdbGeometry geometry;

    /** 惰性 WKB 缓存:{@link #getGeometryWkb()} 只编码一次。 */
    private byte[] geometryWkb;

    /**
     * 尚未解码的几何 blob。
     *
     * <p>对应 GDAL {@code OGRFeature::GetGeomFieldRef} 的延迟构造:几何只在
     * <b>第一次被要</b> 的时候才解码,解完就缓存(同一个几何不会解两遍)。
     *
     * <p>批量遍历({@link com.gdal.openfilegdb.table.GdbTable#iterator()})只读属性时,
     * 几何解码是读路径上最贵的一步,因此这里记下原始 blob 与解码所需的字段定义,
     * 等到真正访问几何时再解。
     */
    private byte[] pendingGeometryBlob;

    /** 解码 pendingGeometryBlob 所需的几何字段定义(含量化参数)。 */
    private GdbGeomField pendingGeometryField;

    /** pendingGeometryBlob 所属表的 hasZ 标志。 */
    private boolean pendingHasZ;

    /** pendingGeometryBlob 所属表的 hasM 标志。 */
    private boolean pendingHasM;

    /** 构造一条空要素。 */
    public GdbFeature() {
    }

    /**
     * 构造一条要素。
     *
     * @param oid    要素 ID
     * @param geom   几何
     */
    public GdbFeature(long oid, GdbGeometry geom) {
        this.oid = oid;
        this.geometry = geom;
    }

    /** 要素 ID(1 基)。 */
    public long getOid() {
        return oid;
    }

    /**
     * 设置要素 ID。
     *
     * @param oid 1 基 ID
     */
    public void setOid(long oid) {
        this.oid = oid;
    }

    /** 是否已分配 ID。 */
    public boolean hasOid() {
        return oid > 0;
    }

    /** {@link #getOid()} 的 OGR 风格别名(OGR: {@code OGRFeature::GetFID()})。 */
    public long getFID() {
        return oid;
    }

    /** {@link #setOid(long)} 的 OGR 风格别名(OGR: {@code OGRFeature::SetFID()})。 */
    public void setFID(long fid) {
        this.oid = fid;
    }

    // ------------------------------------------------------------------
    // 属性
    // ------------------------------------------------------------------

    /**
     * 读属性(大小写不敏感)。
     *
     * @param name 字段名
     * @return 值;不存在返回 {@code null}
     */
    public Object getAttribute(String name) {
        if (attributes.containsKey(name)) {
            return attributes.get(name);
        }
        for (Map.Entry<String, Object> e : attributes.entrySet()) {
            if (e.getKey().equalsIgnoreCase(name)) {
                return e.getValue();
            }
        }
        return null;
    }

    /**
     * 写属性(保持原有键的大小写;新键按传入形式加入)。
     *
     * @param name  字段名
     * @param value 值
     * @return this
     */
    public GdbFeature setAttribute(String name, Object value) {
        for (String k : attributes.keySet()) {
            if (k.equalsIgnoreCase(name)) {
                attributes.put(k, value);
                return this;
            }
        }
        attributes.put(name, value);
        return this;
    }

    /**
     * 是否含某属性(大小写不敏感)。
     *
     * @param name 字段名
     * @return 是否存在
     */
    public boolean hasAttribute(String name) {
        for (String k : attributes.keySet()) {
            if (k.equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------
    // OGR 风格的属性访问(OGR: GetField / GetFieldAsXxx / IsFieldSet)
    // ------------------------------------------------------------------

    /**
     * 读字段值(大小写不敏感)(OGR: {@code OGRFeature::GetField(name)})。
     *
     * <p>与 {@link #getAttribute(String)} 完全等价,只是换成 OGR 的叫法。
     *
     * @param name 字段名
     * @return 值;字段不存在返回 {@code null}
     */
    public Object getField(String name) {
        return getAttribute(name);
    }

    /**
     * 该字段是否被赋过值且非空(OGR: {@code OGRFeature::IsFieldSet()})。
     *
     * @param name 字段名(大小写不敏感)
     * @return 字段存在且值非 {@code null} 时返回 {@code true}
     */
    public boolean isFieldSet(String name) {
        return getAttribute(name) != null;
    }

    /**
     * 读整数(OGR: {@code OGRFeature::GetFieldAsInteger()})。
     *
     * @param name 字段名
     * @return 整数值;空值 / 非数值 / 字段不存在返回 {@code 0}(与 OGR 同款降级)
     */
    public int getFieldAsInteger(String name) {
        return asInt(getAttribute(name));
    }

    /**
     * 读长整数(OGR: {@code OGRFeature::GetFieldAsInteger64()})。
     *
     * @param name 字段名
     * @return 长整数值;空值 / 非数值 / 字段不存在返回 {@code 0}
     */
    public long getFieldAsInteger64(String name) {
        return asLong(getAttribute(name));
    }

    /**
     * 读浮点(OGR: {@code OGRFeature::GetFieldAsDouble()})。
     *
     * @param name 字段名
     * @return 双精度值;空值 / 非数值 / 字段不存在返回 {@code 0.0}
     */
    public double getFieldAsDouble(String name) {
        return asDouble(getAttribute(name));
    }

    /**
     * 读字符串(OGR: {@code OGRFeature::GetFieldAsString()})。
     *
     * @param name 字段名
     * @return 字符串;空值返回 {@code ""}(与 OGR 一致)
     */
    public String getFieldAsString(String name) {
        Object v = getAttribute(name);
        return v == null ? "" : String.valueOf(v);
    }

    /**
     * 读布尔(OGR: {@code OGRFeature::GetFieldAsBoolean()} 的同类便利)。
     *
     * @param name 字段名
     * @return 布尔值;空值返回 {@code false};数值按"非零为真"
     */
    public boolean getFieldAsBoolean(String name) {
        Object v = getAttribute(name);
        if (v instanceof Boolean) {
            return ((Boolean) v).booleanValue();
        }
        if (v instanceof Number) {
            return ((Number) v).longValue() != 0L;
        }
        if (v instanceof String) {
            return Boolean.parseBoolean(((String) v).trim());
        }
        return false;
    }

    private static int asInt(Object v) {
        if (v instanceof Number) {
            return ((Number) v).intValue();
        }
        if (v instanceof String) {
            try {
                return Integer.parseInt(((String) v).trim());
            } catch (NumberFormatException e) {
                return (int) asDouble(v);
            }
        }
        return 0;
    }

    private static long asLong(Object v) {
        if (v instanceof Number) {
            return ((Number) v).longValue();
        }
        if (v instanceof String) {
            try {
                return Long.parseLong(((String) v).trim());
            } catch (NumberFormatException e) {
                return (long) asDouble(v);
            }
        }
        return 0L;
    }

    private static double asDouble(Object v) {
        if (v instanceof Number) {
            return ((Number) v).doubleValue();
        }
        if (v instanceof String) {
            try {
                return Double.parseDouble(((String) v).trim());
            } catch (NumberFormatException e) {
                return 0.0;
            }
        }
        return 0.0;
    }

    /** 属性视图(只读)。 */
    public Map<String, Object> getAttributes() {
        return Collections.unmodifiableMap(attributes);
    }

    /** 直接放属性(内部用于解码时填充)。 */
    public void putAttributeRaw(String name, Object value) {
        attributes.put(name, value);
    }

    // ------------------------------------------------------------------
    // 几何
    // ------------------------------------------------------------------

    /**
     * 几何对象;无几何返回 {@code null}。
     *
     * <p>若几何尚未解码(由批量遍历设置),这里会按需解一次并缓存 ——
     * 与 GDAL 在 feature 上缓存 geometry 的行为一致。
     */
    public GdbGeometry getGeometry() {
        if (geometry == null && pendingGeometryBlob != null) {
            geometry = FileGdbGeometryCodec.decode(
                    pendingGeometryBlob, pendingGeometryField, pendingHasZ, pendingHasM);
            pendingGeometryBlob = null;
            pendingGeometryField = null;
        }
        return geometry;
    }

    /**
     * {@link #getGeometry()} 的 OGR 风格别名
     * (OGR: {@code OGRFeature::GetGeometryRef()})。
     *
     * @return 几何;无几何返回 {@code null}
     */
    public GdbGeometry getGeometryRef() {
        return getGeometry();
    }

    /**
     * 记下未解码的几何 blob,等 {@link #getGeometry()} 首次访问时再解。
     *
     * @param blob      原生几何 blob
     * @param geomField 几何字段定义(携带 origin/scale 量化参数)
     * @param hasZ      表级 hasZ
     * @param hasM      表级 hasM
     */
    public void setPendingGeometry(byte[] blob, GdbGeomField geomField,
                                   boolean hasZ, boolean hasM) {
        this.pendingGeometryBlob = blob;
        this.pendingGeometryField = geomField;
        this.pendingHasZ = hasZ;
        this.pendingHasM = hasM;
        this.geometry = null;
        this.geometryWkb = null;
    }

    /** 几何是否尚未解码(仅有原始 blob)。 */
    public boolean hasPendingGeometry() {
        return pendingGeometryBlob != null;
    }

    /**
     * 设置几何(会作废 WKB 缓存)。
     *
     * @param geom 几何
     * @return this
     */
    public GdbFeature setGeometry(GdbGeometry geom) {
        this.geometry = geom;
        this.geometryWkb = null;
        this.pendingGeometryBlob = null;
        this.pendingGeometryField = null;
        return this;
    }

    /**
     * 几何的 WKB 表示(OGC 标准 WKB,小端 ISO 变体)。
     *
     * <p>编码结果会被缓存,重复调用不会重复计算。
     *
     * @return WKB 字节;无几何返回 {@code null}
     */
    public byte[] getGeometryWkb() {
        if (geometry == null) {
            return null;
        }
        if (geometryWkb == null) {
            geometryWkb = geometry.toWkb();
        }
        return geometryWkb;
    }

    /**
     * 由 WKB 设置几何。
     *
     * @param wkb WKB 字节;{@code null} 或空表示清除几何
     * @return this
     */
    public GdbFeature setGeometryWkb(byte[] wkb) {
        this.pendingGeometryBlob = null;
        this.pendingGeometryField = null;
        if (wkb == null || wkb.length == 0) {
            this.geometry = null;
        } else {
            this.geometry = GdbGeometry.fromWkb(wkb);
        }
        this.geometryWkb = wkb;
        return this;
    }

    /** 是否有非空几何(含尚未解码的情形)。 */
    public boolean hasGeometry() {
        if (pendingGeometryBlob != null) {
            return pendingGeometryBlob.length > 0;
        }
        return geometry != null && !geometry.isEmpty();
    }

    /** 是否为几何集合中的 multipatch 类型。 */
    public boolean isMultiPatch() {
        return geometry != null && geometry.getType() == GeometryType.MULTIPATCH;
    }

    // ------------------------------------------------------------------

    /** 浅拷贝(属性表复制一份,几何对象共享;未解码的几何保持未解码)。 */
    public GdbFeature copy() {
        GdbFeature f = new GdbFeature(oid, geometry);
        f.attributes.putAll(attributes);
        f.geometryWkb = geometryWkb;
        f.pendingGeometryBlob = pendingGeometryBlob;
        f.pendingGeometryField = pendingGeometryField;
        f.pendingHasZ = pendingHasZ;
        f.pendingHasM = pendingHasM;
        return f;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof GdbFeature)) {
            return false;
        }
        GdbFeature other = (GdbFeature) o;
        return oid == other.oid
                && Objects.equals(attributes, other.attributes)
                && Objects.equals(geometry, other.geometry);
    }

    @Override
    public int hashCode() {
        return Objects.hash(oid, attributes);
    }

    @Override
    public String toString() {
        return "GdbFeature[oid=" + oid + ", attrs=" + attributes
                + ", geom=" + (geometry == null ? "null" : geometry.toWkt()) + "]";
    }
}
