package com.gdal.openfilegdb.feature;

/**
 * 图层几何类型(OGR 风格)。
 *
 * <p>对外 API 使用这一套类型名(与 OGR {@code OGRwkbGeometryType} 的命名精神一致),
 * 内部再映射到 FileGDB 的 <b>表级几何类型</b> {@code FGTGT_*}(写在字段描述区
 * 第 8 字节)。
 *
 * <p>⚠️ 两套编号完全不同,不能混用:
 * <ul>
 *   <li>本枚举的 {@code wkbCode} 是 OGC/OGR 的 WKB 类型码
 *       (POINT=1, LINESTRING=2, POLYGON=3, MULTIPOINT=4, ...)</li>
 *   <li>{@code tableGeomType} 是 FileGDB 的表级几何类型
 *       ({@code FGTGT_POINT=1, FGTGT_MULTIPOINT=2, FGTGT_LINE=3,
 *       FGTGT_POLYGON=4, FGTGT_MULTIPATCH=9})</li>
 * </ul>
 *
 * <p>对标 GDAL {@code filegdbtable.h::enum FileGDBTableGeometryType} 与
 * {@code ogropenfilegdblayer.cpp} 在 {@code GetGeomType()} 里做的换算。
 */
public enum GeometryType {

    /** 无几何 / 未知。 */
    UNKNOWN(0, 0),

    /** 点。 */
    POINT(1, 1),

    /** 线(单部件)。 */
    LINESTRING(2, 3),

    /** 面(单部件)。 */
    POLYGON(3, 4),

    /** 多点。 */
    MULTIPOINT(4, 2),

    /** 多线。 */
    MULTILINESTRING(5, 3),

    /** 多面。 */
    MULTIPOLYGON(6, 4),

    /**
     * MultiPatch(3D 表面集合)。
     *
     * <p>OGR 用非标准的 {@code wkbMultiPatch = 32}(不属于 OGC 简单要素规范),
     * 对应 FileGDB 的 {@code FGTGT_MULTIPATCH = 9}。
     */
    MULTIPATCH(32, 9);

    private final int wkbCode;
    private final int tableGeomType;

    GeometryType(int wkbCode, int tableGeomType) {
        this.wkbCode = wkbCode;
        this.tableGeomType = tableGeomType;
    }

    /** WKB 类型码(OGC/OGR 风格)。 */
    public int wkbCode() {
        return wkbCode;
    }

    /** FileGDB 表级几何类型({@code FGTGT_*})。 */
    public int tableGeomType() {
        return tableGeomType;
    }

    /** 是否为多部件类型。 */
    public boolean isMultiPart() {
        return this == MULTIPOINT || this == MULTILINESTRING
                || this == MULTIPOLYGON || this == MULTIPATCH;
    }

    /** 是否为面状类型(含多面与 multipatch)。 */
    public boolean isPolygonal() {
        return this == POLYGON || this == MULTIPOLYGON || this == MULTIPATCH;
    }

    /**
     * 由 FileGDB 表级几何类型反查。
     *
     * @param tableGeomType {@code FGTGT_*} 数值
     * @return 对应的 OGR 风格类型;{@code FGTGT_NONE} 返回 {@link #UNKNOWN}
     */
    public static GeometryType fromTableGeomType(int tableGeomType) {
        switch (tableGeomType) {
            case 1:
                return POINT;
            case 2:
                return MULTIPOINT;
            case 3:
                return LINESTRING;
            case 4:
                return POLYGON;
            case 9:
                return MULTIPATCH;
            default:
                return UNKNOWN;
        }
    }

    /**
     * 由 WKB 类型码反查(不含 Z/M 偏移)。
     *
     * @param wkbCode 类型码(允许带 ISO 的 1000/2000/3000 偏移)
     * @return 对应类型
     */
    public static GeometryType fromWkbCode(int wkbCode) {
        int base = wkbCode % 1000;
        if (base == 0 && wkbCode >= 1000) {
            base = wkbCode - (wkbCode / 1000) * 1000;
        }
        switch (base) {
            case 1:
                return POINT;
            case 2:
                return LINESTRING;
            case 3:
                return POLYGON;
            case 4:
                return MULTIPOINT;
            case 5:
                return MULTILINESTRING;
            case 6:
                return MULTIPOLYGON;
            case 32:
                return MULTIPATCH;
            default:
                return UNKNOWN;
        }
    }

    /** OGR 风格的名称,用于日志与调试。 */
    public String ogrName() {
        switch (this) {
            case POINT:
                return "Point";
            case LINESTRING:
                return "LineString";
            case POLYGON:
                return "Polygon";
            case MULTIPOINT:
                return "MultiPoint";
            case MULTILINESTRING:
                return "MultiLineString";
            case MULTIPOLYGON:
                return "MultiPolygon";
            case MULTIPATCH:
                return "MultiPatch";
            default:
                return "Unknown";
        }
    }
}
