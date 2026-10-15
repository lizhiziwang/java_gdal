package com.gdal.openfilegdb.feature;

/**
 * 几何字段定义。
 *
 * <p>对标 GDAL {@code FileGDBGeomField}(继承自 {@code FileGDBField})。
 *
 * <p>FileGDB 在字段描述区里存的是 <b>WKT + 量化参数</b>
 * (origin / scale / tolerance),几何坐标在记录里以这些参数为基准做
 * <b>定点量化</b>后再 delta 编码,见 {@code _esri_geometry} 的对应 Java 实现
 * {@code FileGdbGeometryCodec}。因此读几何时必须拿到本对象,
 * 否则解出来会是一堆 ~1e11 量级的裸整数。
 *
 * <p>{@code xyScale} 是最关键的一项:ArcGIS 默认 {@code 1e9},
 * 意味着坐标精度为 {@code 1e-9} 度(约 0.1 毫米)。
 * 对标 {@code filegdbtable.cpp} 读取几何字段时对
 * {@code m_dfXYScale}/{@code m_dfXYTolerance} 等的赋值。
 */
public class GdbGeomField extends GdbField {

    /** 空间参考 WKT(UTF-16 存储)。 */
    private String wkt = "";

    /** 是否带 M 的 origin/scale/tolerance 三元组(geomFlags bit1)。 */
    private boolean hasMOriginScaleTolerance;

    /** 是否带 Z 的 origin/scale/tolerance 三元组(geomFlags bit2)。 */
    private boolean hasZOriginScaleTolerance;

    /** X 方向量化原点。 */
    private double xOrigin;
    /** Y 方向量化原点。 */
    private double yOrigin;
    /** XY 量化比例尺(ArcGIS 默认 1e9)。 */
    private double xyScale = 1e9;
    /** XY 容差。 */
    private double xyTolerance;

    /** M 量化原点。 */
    private double mOrigin;
    /** M 量化比例尺。 */
    private double mScale;
    /** M 容差。 */
    private double mTolerance;

    /** Z 量化原点。 */
    private double zOrigin;
    /** Z 量化比例尺。 */
    private double zScale;
    /** Z 容差。 */
    private double zTolerance;

    /** 全表 X 最小值。 */
    private double xmin;
    /** 全表 Y 最小值。 */
    private double ymin;
    /** 全表 X 最大值。 */
    private double xmax;
    /** 全表 Y 最大值。 */
    private double ymax;
    /** 全表 Z 最小值。 */
    private double zmin;
    /** 全表 Z 最大值。 */
    private double zmax;
    /** 全表 M 最小值。 */
    private double mmin;
    /** 全表 M 最大值。 */
    private double mmax;

    /** 空间索引格网分辨率(1~3 个),写在字段描述区最后。 */
    private double[] spatialIndexGridResolutions = new double[0];

    /**
     * 构造一个几何字段定义。
     *
     * @param name 字段名(通常为 "SHAPE")
     */
    public GdbGeomField(String name) {
        super(name, FieldType.GEOMETRY);
    }

    /** 空间参考 WKT。 */
    public String getWkt() {
        return wkt;
    }

    /**
     * 设置空间参考 WKT。
     *
     * @param wkt WKT 文本
     * @return this
     */
    public GdbGeomField setWkt(String wkt) {
        this.wkt = wkt == null ? "" : wkt;
        return this;
    }

    /** 是否带 M 的 origin/scale/tolerance。 */
    public boolean isHasMOriginScaleTolerance() {
        return hasMOriginScaleTolerance;
    }

    /**
     * 设置是否带 M 的 origin/scale/tolerance。
     *
     * @param v 是否带
     * @return this
     */
    public GdbGeomField setHasMOriginScaleTolerance(boolean v) {
        this.hasMOriginScaleTolerance = v;
        return this;
    }

    /** 是否带 Z 的 origin/scale/tolerance。 */
    public boolean isHasZOriginScaleTolerance() {
        return hasZOriginScaleTolerance;
    }

    /**
     * 设置是否带 Z 的 origin/scale/tolerance。
     *
     * @param v 是否带
     * @return this
     */
    public GdbGeomField setHasZOriginScaleTolerance(boolean v) {
        this.hasZOriginScaleTolerance = v;
        return this;
    }

    public double getXOrigin() {
        return xOrigin;
    }

    public GdbGeomField setXOrigin(double v) {
        this.xOrigin = v;
        return this;
    }

    public double getYOrigin() {
        return yOrigin;
    }

    public GdbGeomField setYOrigin(double v) {
        this.yOrigin = v;
        return this;
    }

    public double getXyScale() {
        return xyScale;
    }

    public GdbGeomField setXyScale(double v) {
        this.xyScale = v;
        return this;
    }

    public double getXyTolerance() {
        return xyTolerance;
    }

    public GdbGeomField setXyTolerance(double v) {
        this.xyTolerance = v;
        return this;
    }

    public double getMOrigin() {
        return mOrigin;
    }

    public GdbGeomField setMOrigin(double v) {
        this.mOrigin = v;
        return this;
    }

    public double getMScale() {
        return mScale;
    }

    public GdbGeomField setMScale(double v) {
        this.mScale = v;
        return this;
    }

    public double getMTolerance() {
        return mTolerance;
    }

    public GdbGeomField setMTolerance(double v) {
        this.mTolerance = v;
        return this;
    }

    public double getZOrigin() {
        return zOrigin;
    }

    public GdbGeomField setZOrigin(double v) {
        this.zOrigin = v;
        return this;
    }

    public double getZScale() {
        return zScale;
    }

    public GdbGeomField setZScale(double v) {
        this.zScale = v;
        return this;
    }

    public double getZTolerance() {
        return zTolerance;
    }

    public GdbGeomField setZTolerance(double v) {
        this.zTolerance = v;
        return this;
    }

    public double getXmin() {
        return xmin;
    }

    public double getYmin() {
        return ymin;
    }

    public double getXmax() {
        return xmax;
    }

    public double getYmax() {
        return ymax;
    }

    public double getZmin() {
        return zmin;
    }

    public double getZmax() {
        return zmax;
    }

    public double getMmin() {
        return mmin;
    }

    public double getMmax() {
        return mmax;
    }

    /**
     * 设置全表包围盒。
     *
     * @param xminIn X 最小
     * @param yminIn Y 最小
     * @param xmaxIn X 最大
     * @param ymaxIn Y 最大
     * @return this
     */
    public GdbGeomField setExtent(double xminIn, double yminIn, double xmaxIn, double ymaxIn) {
        this.xmin = xminIn;
        this.ymin = yminIn;
        this.xmax = xmaxIn;
        this.ymax = ymaxIn;
        return this;
    }

    /**
     * 设置全表 Z 范围。
     *
     * @param zminIn Z 最小
     * @param zmaxIn Z 最大
     * @return this
     */
    public GdbGeomField setZRange(double zminIn, double zmaxIn) {
        this.zmin = zminIn;
        this.zmax = zmaxIn;
        return this;
    }

    /**
     * 设置全表 M 范围。
     *
     * @param mminIn M 最小
     * @param mmaxIn M 最大
     * @return this
     */
    public GdbGeomField setMRange(double mminIn, double mmaxIn) {
        this.mmin = mminIn;
        this.mmax = mmaxIn;
        return this;
    }

    /** 空间索引格网分辨率。 */
    public double[] getSpatialIndexGridResolutions() {
        return spatialIndexGridResolutions;
    }

    /**
     * 设置空间索引格网分辨率。
     *
     * @param res 分辨率数组(1~3 个)
     * @return this
     */
    public GdbGeomField setSpatialIndexGridResolutions(double[] res) {
        this.spatialIndexGridResolutions = res == null ? new double[0] : res;
        return this;
    }

    @Override
    public String toString() {
        return "GdbGeomField[" + getName() + " xyScale=" + xyScale
                + ", wkt=" + (wkt.length() > 60 ? wkt.substring(0, 60) + "..." : wkt) + "]";
    }
}
