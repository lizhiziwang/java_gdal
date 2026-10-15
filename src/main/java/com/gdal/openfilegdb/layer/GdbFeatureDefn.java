package com.gdal.openfilegdb.layer;

import com.gdal.openfilegdb.feature.GdbField;
import com.gdal.openfilegdb.feature.GdbGeomField;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 要素定义 —— 对标 OGR 的 {@code OGRFeatureDefn}。
 *
 * <p>它把图层的字段清单包装成 OGR 熟悉的形态:{@code getFieldCount()} /
 * {@code getFieldDefn(i)} / {@code getFieldIndex(name)},外加几何字段的快捷访问。
 * 字段名的匹配是<b>大小写不敏感</b>的(与 OGR 的
 * {@code OGRFeatureDefn::GetFieldIndex} 用 {@code EQUAL} 比较一致)。
 *
 * <p>一次取下标反复用的场景(OGR 的常见写法)请先 {@link #getFieldIndex(String)}
 * 再按下标读,避免每条要素都做一次字符串比较。
 */
public final class GdbFeatureDefn {

    private final List<GdbField> fields;

    /** 几何字段下标;-1 表示没有几何字段。 */
    private final int geometryIndex;

    /** ID(ObjectID)字段下标;-1 表示没有。 */
    private final int objectIdIndex;

    /**
     * 构造一个要素定义。
     *
     * @param fields 字段清单(顺序即下标顺序)
     */
    public GdbFeatureDefn(List<GdbField> fields) {
        this.fields = fields == null
                ? Collections.<GdbField>emptyList()
                : new ArrayList<>(fields);
        int geom = -1;
        int oid = -1;
        for (int i = 0; i < this.fields.size(); i++) {
            GdbField f = this.fields.get(i);
            if (f.isGeometry() && geom < 0) {
                geom = i;
            } else if (f.isObjectId() && oid < 0) {
                oid = i;
            }
        }
        this.geometryIndex = geom;
        this.objectIdIndex = oid;
    }

    /**
     * 字段数(OGR: {@code GetFieldCount()})。
     *
     * @return 字段数
     */
    public int getFieldCount() {
        return fields.size();
    }

    /**
     * 按下标取字段定义(OGR: {@code GetFieldDefn(i)})。
     *
     * @param index 0 基下标
     * @return 字段定义
     * @throws IndexOutOfBoundsException 越界
     */
    public GdbField getFieldDefn(int index) {
        return fields.get(index);
    }

    /**
     * 按下标取字段(OGR 风格别名,同 {@link #getFieldDefn(int)})。
     *
     * @param index 0 基下标
     * @return 字段定义
     */
    public GdbField getField(int index) {
        return fields.get(index);
    }

    /**
     * 几何字段数(OGR: {@code GetGeomFieldCount()})。
     *
     * @return 0 或 1(FileGDB 一个表只有一个几何字段)
     */
    public int getGeomFieldCount() {
        return geometryIndex >= 0 ? 1 : 0;
    }

    /**
     * 几何字段定义(OGR: {@code GetGeomFieldDefn(i)})。
     *
     * @param index 固定为 0
     * @return 几何字段;无几何字段返回 {@code null}
     */
    public GdbGeomField getGeomFieldDefn(int index) {
        if (index != 0 || geometryIndex < 0) {
            return null;
        }
        GdbField f = fields.get(geometryIndex);
        return f instanceof GdbGeomField ? (GdbGeomField) f : null;
    }

    /**
     * 按名字查下标(大小写不敏感,OGR: {@code GetFieldIndex()})。
     *
     * @param name 字段名
     * @return 0 基下标;不存在返回 {@code -1}(与 OGR 一致)
     */
    public int getFieldIndex(String name) {
        if (name == null) {
            return -1;
        }
        for (int i = 0; i < fields.size(); i++) {
            if (fields.get(i).getName().equalsIgnoreCase(name)) {
                return i;
            }
        }
        return -1;
    }

    /** 几何字段下标;无几何字段返回 -1。 */
    public int getGeometryFieldIndex() {
        return geometryIndex;
    }

    /** OBJECTID 字段下标;没有返回 -1。 */
    public int getObjectIdFieldIndex() {
        return objectIdIndex;
    }

    /**
     * 几何字段名;无几何字段返回 {@code ""}(与 OGR 的 {@code GetGeometryColumn()} 一致)。
     *
     * @return 几何字段名
     */
    public String getGeometryColumn() {
        return geometryIndex < 0 ? "" : fields.get(geometryIndex).getName();
    }

    /**
     * ID 字段名;没有返回 {@code ""}(与 OGR 的 {@code GetFIDColumn()} 一致)。
     *
     * @return ID 字段名
     */
    public String getFIDColumn() {
        return objectIdIndex < 0 ? "" : fields.get(objectIdIndex).getName();
    }

    /** 字段清单(只读视图)。 */
    public List<GdbField> getFields() {
        return Collections.unmodifiableList(fields);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("<GdbFeatureDefn fields=").append(fields.size());
        if (geometryIndex >= 0) {
            sb.append(" geom=").append(getGeometryColumn());
        }
        if (objectIdIndex >= 0) {
            sb.append(" oid=").append(getFIDColumn());
        }
        return sb.append('>').toString();
    }
}
