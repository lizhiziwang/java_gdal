package com.gdal.openfilegdb.layer;

import com.gdal.openfilegdb.feature.GdbFeature;
import com.gdal.openfilegdb.feature.GdbField;
import com.gdal.openfilegdb.feature.GeometryType;
import com.gdal.openfilegdb.srs.SpatialReference;
import java.util.Iterator;
import java.util.List;

/**
 * 一个"图层"—— 对标 OGR 的 {@code OGRLayer}。
 *
 * <p>本库有两种实现,用法完全一致:
 * <ul>
 *   <li>{@link OpenFileGdbLayer} —— 库里真实的要素类 / 表(对应
 *       {@code OGROpenFileGDBLayer});</li>
 *   <li>{@code com.gdal.openfilegdb.sql.GdbSqlLayer} —— {@code executeSql} 的查询结果
 *       (对应 GDAL {@code ExecuteSQL()} 返回的 {@code OGRGenSQLResultsLayer})。</li>
 * </ul>
 * 所以 {@code executeSql} 出来的结果可以<b>跟普通图层一样读</b>:
 * {@code resetReading()} → {@code getNextFeature()} → {@code getFID()/getField()/getGeometryRef()},
 * 也可以直接 {@code for (GdbFeature f : layer)}。
 *
 * <h2>与 GDAL / OGR 的名字对照</h2>
 * <pre>
 *   本库                                  OGR / GDAL
 *   ------------------------------------  --------------------------------
 *   getName()                             GetName()
 *   getLayerDefn()                        GetLayerDefn()
 *   getFields()                           GetLayerDefn()->GetFieldDefn(i)
 *   getGeomType()                         GetGeomType()
 *   getSpatialRef()                       GetSpatialRef()
 *   getFeatureCount()                     GetFeatureCount()
 *   getFeatureCount(force)                GetFeatureCount(bForce)
 *   getExtent()                           GetExtent(&psExtent, bForce=TRUE)
 *   getExtent(force)                      GetExtent(&psExtent, bForce)
 *   resetReading()                        ResetReading()
 *   getNextFeature()                      GetNextFeature()
 *   getFeature(oid)                       GetFeature(fid)
 *   setSpatialFilterRect(x0,y0,x1,y1)     SetSpatialFilterRect(...)
 *   setSpatialFilter(double[] rect)       SetSpatialFilter(geom)
 *   clearSpatialFilter()                  SetSpatialFilter(nullptr)
 *   getSpatialFilter()                    GetSpatialFilter()
 *   getFIDColumn()                        GetFIDColumn()
 *   getGeometryColumn()                   GetGeometryColumn()
 *   createFeature/updateFeature/...       CreateFeature/SetFeature/DeleteFeature
 *   iterator()                            (OGR 没有,Java 便利)
 * </pre>
 *
 * <p><b>单游标语义</b>(与 OGR 一致):一个图层只有一份读取游标,
 * {@link #iterator()} 返回 {@code this};嵌套遍历必须先 {@link #resetReading()}。
 *
 * <p><b>资源</b>:普通图层的生命周期归数据集所有(由 {@code OpenFileGdbDataset.close()}
 * 统一关表),不单独 close;SQL 结果图层实现了 {@link AutoCloseable},
 * 用 {@code OpenFileGdbDataset.releaseResultSet(layer)} 释放(对应 GDAL 的
 * {@code ReleaseResultSet})。
 */
public interface GdbLayer extends Iterable<GdbFeature> {

    /**
     * 图层名。
     *
     * @return 名字(OGR: {@code GetName()});SQL 结果层返回 {@code "SELECT"} 之类的
     *         语句摘要
     */
    String getName();

    /**
     * 字段定义(OBJECTID、属性、几何,顺序与磁盘一致)。
     *
     * @return 字段清单(OGR: {@code GetLayerDefn()})
     */
    List<GdbField> getFields();

    /**
     * 要素定义(字段清单 + 按名查下标 + 几何字段),对应 OGR 的 {@code OGRFeatureDefn}。
     *
     * @return 要素定义
     */
    GdbFeatureDefn getLayerDefn();

    /**
     * 按名字取字段(大小写不敏感)。
     *
     * @param name 字段名
     * @return 字段定义;不存在返回 {@code null}
     */
    GdbField getField(String name);

    /**
     * 几何类型(无几何层返回 {@link GeometryType#UNKNOWN})。
     *
     * @return 几何类型(OGR: {@code GetGeomType()})
     */
    GeometryType getGeometryType();

    /** {@link #getGeometryType()} 的 OGR 风格别名(OGR: {@code GetGeomType()})。 */
    GeometryType getGeomType();

    /**
     * 空间参考;无几何或 WKT 为空时返回 {@link SpatialReference#EMPTY}。
     *
     * @return 空间参考(OGR: {@code GetSpatialRef()})
     */
    SpatialReference getSpatialReference();

    /** {@link #getSpatialReference()} 的 OGR 风格别名(OGR: {@code GetSpatialRef()})。 */
    SpatialReference getSpatialRef();

    /**
     * 要素数;未知时返回 {@code -1}(OGR 的约定:{@code GetFeatureCount} 无法快速确定时
     * 返回 -1,例如 SQL 结果层)。
     *
     * @return 要素数,或 -1 表示未知
     */
    long getFeatureCount();

    /**
     * 要素数(带 force 开关,语义同 OGR {@code GetFeatureCount(bForce)})。
     *
     * @param force {@code false} 时允许在代价高的情况下返回 -1
     * @return 要素数,或 -1 表示未知
     */
    long getFeatureCount(boolean force);

    /**
     * 包围盒 {@code {xmin, ymin, xmax, ymax}};无几何或未知时返回 {@code null}。
     *
     * @return 包围盒(OGR: {@code GetExtent()})
     */
    double[] getExtent();

    /**
     * 包围盒(带 force 开关,语义同 OGR {@code GetExtent(&sExtent, bForce)})。
     *
     * @param force {@code false} 时允许在需要全表扫描的情况下返回 {@code null}
     * @return 包围盒,或 {@code null}
     */
    double[] getExtent(boolean force);

    /**
     * 取下一条要素;读到末尾返回 {@code null}(OGR: {@code GetNextFeature()})。
     *
     * @return 要素或 {@code null}
     */
    GdbFeature getNextFeature();

    /**
     * 把游标复位到开头(OGR: {@code ResetReading()})。
     */
    void resetReading();

    /**
     * 按 ID 随机读一条(OGR: {@code GetFeature(fid)})。
     *
     * @param oid 要素 ID(1 基)
     * @return 要素;ID 非法、空槽或该层不支持随机读时返回 {@code null}
     */
    GdbFeature getFeature(long oid);

    /**
     * 设置空间过滤矩形(OGR: {@code SetSpatialFilterRect})。
     *
     * <p>设置之后,本层的遍历只返回与矩形相交的要素;传 {@code null} 等价于
     * {@link #clearSpatialFilter()}。有 {@code .spx} 索引时先用索引粗筛候选行号
     * (与 GDAL 的 {@code FileGDBSpatialIndexIterator} 同思路),没有就全表流式扫 +
     * 精确包围盒判断。
     *
     * @param xmin X 最小
     * @param ymin Y 最小
     * @param xmax X 最大
     * @param ymax Y 最大
     * @return this
     */
    GdbLayer setSpatialFilterRect(double xmin, double ymin, double xmax, double ymax);

    /**
     * 设置空间过滤矩形(数组形式 {@code {xmin, ymin, xmax, ymax}})。
     *
     * @param rect 矩形;{@code null} 表示清空
     * @return this
     */
    GdbLayer setSpatialFilter(double[] rect);

    /** 清空空间过滤(OGR: {@code SetSpatialFilter(nullptr)})。 */
    void clearSpatialFilter();

    /**
     * 当前空间过滤矩形。
     *
     * @return {@code {xmin, ymin, xmax, ymax}};未设置返回 {@code null}
     */
    double[] getSpatialFilter();

    /**
     * ID 字段名(一般是 {@code "OBJECTID"});没有返回 {@code ""}(OGR 的约定)。
     *
     * @return ID 字段名
     */
    String getFIDColumn();

    /**
     * 几何字段名(一般是 {@code "Shape"});没有几何返回 {@code ""}(OGR 的约定)。
     *
     * @return 几何字段名
     */
    String getGeometryColumn();

    /**
     * 能力查询(OGR: {@code TestCapability})。
     *
     * <p>支持的能力名(与 OGR 常量同名):
     * {@code OLCRandomRead} / {@code OLCSequentialWrite} / {@code OLCRandomWrite} /
     * {@code OLCFastSpatialFilter} / {@code OLCFastFeatureCount} /
     * {@code OLCFastGetExtent} / {@code OLCFastSetNextByIndex} / {@code OLCCreateField} /
     * {@code OLCDeleteFeature} / {@code OLCStringsAsUTF8} / {@code OLCTransactions}。
     *
     * @param capability 能力名
     * @return 是否具备
     */
    boolean testCapability(String capability);

    /** OGR 风格游标:与 {@link #getNextFeature()} 共用同一游标。 */
    @Override
    Iterator<GdbFeature> iterator();

    /** 是否还有下一条(与 {@link #getNextFeature()} 共用游标)。 */
    boolean hasNext();

    /** 取下一条,没有则抛 {@link java.util.NoSuchElementException}。 */
    GdbFeature next();

    /**
     * 追加一条要素(OGR: {@code CreateFeature})。
     *
     * @param feature 要素
     * @return 分配到的 ID(1 基);该层只读时抛异常
     */
    long createFeature(GdbFeature feature);

    /**
     * 就地更新一条要素(OGR: {@code SetFeature})。
     *
     * @param feature 要素(需要 {@code getFID()})
     */
    void updateFeature(GdbFeature feature);

    /**
     * 删除一条要素(OGR: {@code DeleteFeature})。
     *
     * @param oid 要素 ID
     * @return 是否真的删掉了一条
     */
    boolean deleteFeature(long oid);
}
