package com.gdal.openfilegdb.srs;

import java.util.Objects;

/**
 * 空间参考(坐标参考系)。
 *
 * <p>对标 OGR 的 {@code OGRSpatialReference},但只保留 FileGDB 场景需要的部分。
 *
 * <p>FileGDB 里空间参考有 <b>两个来源</b>,本类把它们合并保存:
 * <ol>
 *   <li>几何字段描述区里的一段 <b>WKT</b>(UTF-16)—— 这是几何量化与
 *       OGR 输出的直接依据,对标 {@code filegdbtable.cpp} 读取几何字段时的
 *       {@code osWKT} 与 {@code m_osSpatialRef};</li>
 *   <li>{@code GDB_Items} 表里 {@code Definition} XML 的
 *       {@code <SpatialReference>} 节点,含 {@code <WKID>} 与
 *       {@code <LatestWKID>} —— ArcGIS 用它标识"当前推荐"的编码。</li>
 * </ol>
 *
 * <p>因此 {@link #getEffectiveWkid()} 优先返回 LatestWKID。
 * 对标 GDAL {@code ogropenfilegdbdatasource.cpp} 中解析
 * {@code GDB_Items.Definition} 的那段 XML 处理。
 */
public final class SpatialReference {

    /** 无坐标系的空实例。 */
    public static final SpatialReference EMPTY = new SpatialReference(0, 0, "", "");

    private final int wkid;
    private final int latestWkid;
    private final String wkt;
    private final String name;

    /**
     * @param wkid       WKID(可能为 0 表示未知)
     * @param latestWkid LatestWKID
     * @param wkt        WKT 文本
     * @param name       名称(可为空)
     */
    public SpatialReference(int wkid, int latestWkid, String wkt, String name) {
        this.wkid = wkid;
        this.latestWkid = latestWkid;
        this.wkt = wkt == null ? "" : wkt;
        this.name = name == null ? "" : name;
    }

    /** WKID。 */
    public int getWkid() {
        return wkid;
    }

    /** LatestWKID。 */
    public int getLatestWkid() {
        return latestWkid;
    }

    /** 有效的 WKID(优先 LatestWKID)。 */
    public int getEffectiveWkid() {
        return latestWkid != 0 ? latestWkid : wkid;
    }

    /** WKT 文本。 */
    public String getWkt() {
        return wkt;
    }

    /** 名称。 */
    public String getName() {
        return name;
    }

    /** 是否为空(无坐标系)。 */
    public boolean isEmpty() {
        return wkt.isEmpty() && wkid == 0 && latestWkid == 0;
    }

    /**
     * 以 WKT 为准构造,并尽量从 WKT 里解析出 WKID。
     *
     * <p>两级回退:
     * <ol>
     *   <li>先找 WKT 里的 {@code AUTHORITY}/{@code ID} 节点(WKT1/WKT2 通用);</li>
     *   <li>找不到时,再拿文本去 {@link WktSupport#builtinEpsg(String)} 做内建比对
     *       —— 因为 ArcGIS / GDAL 写进 {@code .gdb} 的 ESRI 风格 WKT1 没有
     *       {@code AUTHORITY} 节点,只靠第 1 步会把 WKID 丢掉。</li>
     * </ol>
     *
     * @param wkt WKT 文本
     * @return 空间参考
     */
    public static SpatialReference fromWkt(String wkt) {
        int wkid = WktSupport.extractLastAuthorityCode(wkt);
        if (wkid == 0) {
            wkid = WktSupport.builtinEpsg(wkt);
        }
        String nm = WktSupport.extractName(wkt);
        return new SpatialReference(wkid, 0, wkt, nm);
    }

    /**
     * 构造带 WKID 的空间参考。
     *
     * @param wkid WKID
     * @param wkt  WKT 文本
     * @return 空间参考
     */
    public static SpatialReference of(int wkid, String wkt) {
        return new SpatialReference(wkid, 0, wkt, WktSupport.extractName(wkt));
    }

    /**
     * 由 EPSG 编码取内置空间参考。
     *
     * <p>内建了最常用的几个(EPSG:4326 / 3857 / 4490 / 32650)。
     * 若编码不在表内,返回一个只带 WKID、WKT 为空的实例 ——
     * 调用方应改用 {@link #of(int, String)} 显式提供 WKT。
     *
     * @param epsg EPSG 编码
     * @return 空间参考
     */
    public static SpatialReference fromEpsg(int epsg) {
        String wkt = WktSupport.builtinWkt(epsg);
        String nm = WktSupport.builtinName(epsg);
        return new SpatialReference(epsg, 0, wkt, nm);
    }

    /**
     * 返回一份带 WKID 的副本。
     *
     * @param wkidIn       WKID
     * @param latestWkidIn LatestWKID
     * @return 新实例
     */
    public SpatialReference withWkid(int wkidIn, int latestWkidIn) {
        return new SpatialReference(wkidIn, latestWkidIn, wkt, name);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof SpatialReference)) {
            return false;
        }
        SpatialReference other = (SpatialReference) o;
        return wkid == other.wkid && latestWkid == other.latestWkid
                && Objects.equals(wkt, other.wkt);
    }

    @Override
    public int hashCode() {
        return Objects.hash(wkid, latestWkid, wkt);
    }

    @Override
    public String toString() {
        if (isEmpty()) {
            return "SpatialReference[empty]";
        }
        return "SpatialReference[wkid=" + getEffectiveWkid()
                + ", name=" + (name.isEmpty() ? "(unnamed)" : name)
                + ", wkt=" + (wkt.length() > 80 ? wkt.substring(0, 80) + "..." : wkt) + "]";
    }
}
