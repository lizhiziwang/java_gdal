package com.gdal.openfilegdb.dataset;

import com.gdal.openfilegdb.exception.GdbFileNotFoundException;
import com.gdal.openfilegdb.exception.GdbIoException;
import com.gdal.openfilegdb.exception.GdbNotFoundException;
import com.gdal.openfilegdb.exception.GdbWriteException;
import com.gdal.openfilegdb.feature.FieldType;
import com.gdal.openfilegdb.feature.GdbFeature;
import com.gdal.openfilegdb.feature.GdbField;
import com.gdal.openfilegdb.feature.GdbGeomField;
import com.gdal.openfilegdb.feature.GeometryType;
import com.gdal.openfilegdb.layer.OpenFileGdbLayer;
import com.gdal.openfilegdb.srs.SpatialReference;
import com.gdal.openfilegdb.table.GdbTable;
import com.gdal.openfilegdb.util.GdbDateTimeUtil;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.gdal.openfilegdb.util.GdbCollections.set;

/**
 * 一个 FileGDB 数据集({@code .gdb} 目录)。
 *
 * <p>对标 GDAL 的 {@code OGROpenFileGDBDataSource}
 * ({@code ogr/ogrsf_frmts/openfilegdb/ogropenfilegdbdatasource.cpp} 与其
 * {@code _write.cpp}),把"目录级文件 + 系统表 + 用户图层"串起来:
 * <ul>
 *   <li>{@link #open(Path)} —— 打开一个已存在的 {@code .gdb}(只读);</li>
 *   <li>{@link #create(Path)} —— 新建一个空的 {@code .gdb}(写)。</li>
 * </ul>
 *
 * <h2>建库文件清单(对应 {@code Create()},ogropenfilegdbdatasource_write.cpp:1281)</h2>
 * <pre>
 *   gdb            8 字节  05 00 00 00 DE AD BE EF
 *   timestamps     400 字节 全 0xFF
 *   a00000001..a00000007   7 张系统表(每张 .gdbtable + .gdbtablx)
 * </pre>
 * 不建 {@code a00000008}(GDB_ReplicaLog);文件名是 {@code gdb} 而不是 {@code g}。
 *
 * <h2>物理编号</h2>
 * 物理名 = {@code String.format("a%08x", catalog 行号 + 1)}。空库 catalog 有 8 条,
 * 故第一个用户图层是 {@code a00000009}。见 {@link GdbSystemCatalog#nextPhysicalNumber()}。
 */
public final class OpenFileGdbDataset implements AutoCloseable {

    /** {@code gdb} 文件的全部内容(照抄 FileGDB SDK,见 Create())。 */
    private static final byte[] GDB_FILE_BYTES =
            {0x05, 0x00, 0x00, 0x00, (byte) 0xDE, (byte) 0xAD, (byte) 0xBE, (byte) 0xEF};

    /** {@code timestamps} 文件的长度:400 个 0xFF。 */
    private static final int TIMESTAMPS_FILE_SIZE = 400;

    /** 默认坐标系:WGS84 的 ESRI WKT(见 core.py::DEFAULT_WKT_WGS84)。 */
    static final String DEFAULT_WKT_WGS84 =
            "GEOGCS[\"GCS_WGS_1984\",DATUM[\"D_WGS_1984\",SPHEROID[\"WGS_1984\","
                    + "6378137.0,298.257223563]],PRIMEM[\"Greenwich\",0.0],"
                    + "UNIT[\"Degree\",0.0174532925199433]]";

    /** 默认 WGS84 的 WKID。 */
    private static final int DEFAULT_WKID_WGS84 = 4326;

    // 几何字段的量化参数(与 GDAL CreateGDBItems 硬编码的一组完全一致)。
    private static final double DF_X_ORIGIN = -180.0;
    private static final double DF_Y_ORIGIN = -90.0;
    private static final double DF_XY_SCALE = 1000000.0;
    private static final double DF_XY_TOLERANCE = 2e-6;
    private static final double DF_Z_ORIGIN = -100000.0;
    private static final double DF_Z_SCALE = 10000.0;
    private static final double DF_Z_TOLERANCE = 0.001;
    private static final double DF_M_ORIGIN = -100000.0;
    private static final double DF_M_SCALE = 10000.0;
    private static final double DF_M_TOLERANCE = 0.001;

    /** 空间索引格网分辨率(GDAL 默认)。 */
    private static final double[] DEFAULT_GRID_RESOLUTIONS = {0.012, 0.4, 12.0};

    /** OID 字段名。 */
    private static final String OID_FIELD_NAME = "OBJECTID";

    /** 几何字段名。 */
    private static final String GEOM_FIELD_NAME = "Shape";

    /** Definition XML 的根级 {@code <CatalogPath>} 前缀。 */
    private static final String PATH_PREFIX = "\\";

    /** 非"图层"的 item 种类(枚举用户图层时跳过)。 */
    private static final Set<String> NON_LAYER_KINDS = set(
            "Folder", "Workspace", "Feature Dataset", "Domain", "Range Domain",
            "Coded Value Domain", "Relationship Class", "Index", "Toolbox", "Replica",
            "Sync Dataset", "Sync Replica", "Historical Marker", "Workspace Extension");

    // ==================================================================
    // 实例状态
    // ==================================================================

    private static final Logger LOG = LoggerFactory.getLogger(OpenFileGdbDataset.class);

    private final Path path;
    private final boolean writable;

    /** 物理名 → 已打开的表(读写共用,避免同一文件开两个句柄)。 */
    private final Map<String, GdbTable> tables = new LinkedHashMap<>();

    /** 小写图层名 → 已构建的图层。 */
    private final Map<String, OpenFileGdbLayer> layers = new LinkedHashMap<>();

    private GdbSystemCatalog catalog;
    private Map<String, ItemRow> itemIndex;
    private boolean closed;

    private OpenFileGdbDataset(Path path, boolean writable) {
        this.path = path;
        this.writable = writable;
    }

    // ==================================================================
    // 打开 / 新建
    // ==================================================================

    /**
     * 打开一个已存在的 {@code .gdb}(只读)。
     *
     * @param path {@code .gdb} 目录路径
     * @return 数据集
     * @throws GdbFileNotFoundException 路径不存在 / 不是目录 / 缺少 {@code a00000001.gdbtable}
     */
    public static OpenFileGdbDataset open(String path) {
        return open(Paths.get(path));
    }

    /**
     * 打开一个已存在的 {@code .gdb}(只读)。
     *
     * <p>对应 GDAL {@code GDALOpenEx(..., GDAL_OF_VECTOR)}。
     *
     * @param path {@code .gdb} 目录路径
     * @return 数据集
     * @throws GdbFileNotFoundException 路径不存在 / 不是目录 / 缺少 {@code a00000001.gdbtable}
     */
    public static OpenFileGdbDataset open(Path path) {
        return openInternal(path, false);
    }

    /**
     * 以可写方式打开一个已存在的 {@code .gdb}。
     *
     * <p>对应 GDAL {@code GDALOpenEx(..., GDAL_OF_UPDATE)}:{@code bUpdate} 下所有表都以
     * 可写方式打开(建图层要往系统表追加记录)。
     *
     * @param path {@code .gdb} 目录路径
     * @return 数据集
     */
    public static OpenFileGdbDataset openForWrite(Path path) {
        return openInternal(path, true);
    }

    /**
     * 新建一个空的 {@code .gdb} 目录(含全部系统表)。
     *
     * @param path 目标目录
     * @return 以可写方式打开的数据集
     * @throws GdbWriteException 目标已存在
     */
    public static OpenFileGdbDataset create(String path) {
        return create(Paths.get(path));
    }

    /**
     * 新建一个空的 {@code .gdb} 目录(含全部系统表)。
     *
     * <p>逐步骤对应 GDAL {@code OGROpenFileGDBDataSource::Create()}
     * (ogropenfilegdbdatasource_write.cpp:1281):目录已存在则失败 → 建目录 →
     * 写 {@code gdb} → 写 {@code timestamps} → 建 7 张系统表。
     *
     * @param path 目标目录
     * @return 以可写方式打开的数据集
     * @throws GdbWriteException 目标已存在(或已存在但不是目录)
     */
    public static OpenFileGdbDataset create(Path path) {
        Path abs = path.toAbsolutePath().normalize();
        if (Files.exists(abs)) {
            throw new GdbWriteException(abs + " 已存在");
        }
        if (!abs.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".gdb")) {
            // 不强制(调用方可能用别的目录名),但 GDAL/ArcGIS 靠 .gdb 后缀识别驱动
            // 与 Python core.py 的"目录名必须以 .gdb 结尾"不同,这里放宽以免误伤。
        }
        try {
            Files.createDirectories(abs);
            Files.write(abs.resolve("gdb"), GDB_FILE_BYTES);
            byte[] timestamps = new byte[TIMESTAMPS_FILE_SIZE];
            Arrays.fill(timestamps, (byte) 0xFF);
            Files.write(abs.resolve("timestamps"), timestamps);
        } catch (IOException e) {
            throw new GdbIoException("新建 " + abs + " 失败: " + e.getMessage(), e);
        }
        GdbTemplates.writeSystemTables(abs);
        LOG.info("已新建 FileGDB: {} (版本 3,gdb + timestamps + a00000001~a00000007 系统表)", abs);
        return openInternal(abs, true);
    }

    private static OpenFileGdbDataset openInternal(Path path, boolean writable) {
        Path abs = path.toAbsolutePath().normalize();
        if (!Files.exists(abs)) {
            throw new GdbFileNotFoundException("路径不存在: " + abs);
        }
        if (!Files.isDirectory(abs)) {
            throw new GdbFileNotFoundException("不是目录(FileGDB 是一个 .gdb 文件夹): " + abs);
        }
        Path cat = abs.resolve(GdbSystemCatalog.SYSTEM_CATALOG_PHYSICAL + ".gdbtable");
        if (!Files.isRegularFile(cat)) {
            throw new GdbFileNotFoundException(
                    "缺少 " + GdbSystemCatalog.SYSTEM_CATALOG_PHYSICAL
                            + ".gdbtable,不是有效的 FileGDB: " + abs);
        }
        OpenFileGdbDataset ds = new OpenFileGdbDataset(abs, writable);
        ds.catalog = GdbSystemCatalog.open(abs, writable);
        ds.tables.put(GdbSystemCatalog.SYSTEM_CATALOG_PHYSICAL, ds.catalog.getTable());
        LOG.debug("已打开 FileGDB: {} (writable={}, 版本 {})", abs, writable,
                ds.catalog.getTable().getVersion());
        return ds;
    }

    // ==================================================================
    // 基本信息
    // ==================================================================

    /** {@code .gdb} 目录(绝对路径)。 */
    public Path getPath() {
        return path;
    }

    /**
     * 数据集名 = {@code .gdb} 目录路径(OGR: {@code GDALDataset::GetName()} /
     * {@code GetDescription()} 返回打开时给的那个名字/路径)。
     *
     * @return 绝对路径字符串
     */
    public String getName() {
        return path.toString();
    }

    /** {@link #getName()} 的 GDAL 风格别名({@code GetDescription()})。 */
    public String getDescription() {
        return getName();
    }

    /** 是否可写({@link #create} 出来的,或以写方式打开的)。 */
    public boolean isWritable() {
        return writable && !closed;
    }

    /**
     * 数据集级能力查询(GDAL: {@code GDALDataset::TestCapability})。
     *
     * <p>支持的常量(与 OGR 同名):{@code ODsCCreateLayer} /
     * {@code ODsCDeleteLayer} / {@code ODsCCreateGeomFieldAfterCreateLayer} /
     * {@code ODsCTransactions} / {@code ODsCRandomLayerRead} /
     * {@code ODsCZGeometries} / {@code ODsCMeasuredGeometries} /
     * {@code ODsCCurveGeometries}。
     *
     * @param capability 能力名
     * @return 是否具备
     */
    public boolean testCapability(String capability) {
        if (capability == null) {
            return false;
        }
        switch (capability) {
            case "ODsCCreateLayer":
                return writable && !closed;
            case "ODsCRandomLayerRead":
                return true;
            // 删图层、建库后加几何字段、事务、Z/M、曲线几何 —— 本库都不做
            case "ODsCDeleteLayer":
            case "ODsCCreateGeomFieldAfterCreateLayer":
            case "ODsCTransactions":
            case "ODsCZGeometries":
            case "ODsCMeasuredGeometries":
            case "ODsCCurveGeometries":
            default:
                return false;
        }
    }

    /**
     * 可被 ArcGIS/GDAL 打开的用户图层名(不含系统表),顺序按 catalog 记录顺序,稳定。
     *
     * <p>分类依据是 {@code GDB_Items} 的 {@code Type} GUID / {@code Definition} 根标签
     * (见 {@link GdbSystemCatalog#classify});Folder / Workspace / FeatureDataset /
     * 域 / 关系类 / 索引等非图层条目会被跳过。判定不出来时,只要物理表文件存在就当作图层。
     *
     * @return 图层名列表
     */
    public List<String> getLayerNames() {
        ensureOpen();
        List<String> out = new ArrayList<>();
        for (GdbSystemCatalog.Entry e : catalog.entries()) {
            if (GdbSystemCatalog.isSystemTableName(e.name())) {
                continue;
            }
            String physical = physicalForEntry(e);
            if (!Files.isRegularFile(path.resolve(physical + ".gdbtable"))) {
                continue;
            }
            if (isLayerKind(classify(e.name()))) {
                out.add(e.name());
            }
        }
        return out;
    }

    /**
     * 用户图层数量(不含系统表)。
     *
     * <p>等价于 {@code getLayerNames().size()},与 GDAL
     * {@code OGROpenFileGDBDataSource::GetLayerCount()} 同口径:GDAL 把用户图层收在
     * {@code m_apoLayers}、把系统表收在 {@code m_apoHiddenLayers},计数只算前者
     * (见 {@code ogropenfilegdbdatasource_write.cpp:594} 的系统表入列 与
     * {@code :1388} 的用户图层入列)。系统表不计入这里的数量。
     *
     * @return 用户图层数量
     */
    public int getLayerCount() {
        ensureOpen();
        return getLayerNames().size();
    }

    /**
     * 按名字取图层(大小写不敏感)。
     *
     * <p>{@code name} 可以是逻辑名(如 {@code "道路"})、物理表名(如
     * {@code "a00000009"}),也可以是系统表名。
     *
     * @param name 图层名
     * @return 图层
     * @throws GdbNotFoundException 库里没有这个图层
     * @see #getLayer(int)
     */
    public OpenFileGdbLayer getLayer(String name) {
        ensureOpen();
        if (name == null || name.trim().isEmpty()) {
            throw new GdbNotFoundException("图层名不能为空");
        }
        String key = name.trim().toLowerCase(Locale.ROOT);
        OpenFileGdbLayer cached = layers.get(key);
        if (cached != null) {
            return cached;
        }
        GdbSystemCatalog.Entry target = null;
        for (GdbSystemCatalog.Entry e : catalog.entries()) {
            if (e.name().equalsIgnoreCase(key)) {
                target = e;
                break;
            }
        }
        if (target == null) {
            for (GdbSystemCatalog.Entry e : catalog.entries()) {
                if (GdbSystemCatalog.physicalNameForIndex(e.physicalNumber())
                        .equalsIgnoreCase(key)) {
                    target = e;
                    break;
                }
            }
        }
        if (target == null) {
            throw new GdbNotFoundException(name + ": 没有这个图层;现有图层: "
                    + catalog.entries().stream().map(GdbSystemCatalog.Entry::name)
                            .collect(Collectors.toList()));
        }
        String physical = physicalForEntry(target);
        OpenFileGdbLayer layer = new OpenFileGdbLayer(target.name(), openTable(physical));
        layers.put(target.name().toLowerCase(Locale.ROOT), layer);
        layers.put(key, layer);
        return layer;
    }

    /**
     * 按名字取图层(OGR: {@code GDALDataset::GetLayerByName()})。
     *
     * <p>就是 {@link #getLayer(String)} 的 OGR 叫法,同样接受逻辑名 / 物理名 /
     * 系统表名,同样大小写不敏感。
     *
     * @param name 图层名
     * @return 图层
     * @throws GdbNotFoundException 库里没有这个图层
     */
    public OpenFileGdbLayer getLayerByName(String name) {
        return getLayer(name);
    }

    /**
     * 按序号取用户图层(0 基)。
     *
     * <p>序号即 {@link #getLayerNames()} 里的下标,有效范围
     * {@code [0, getLayerCount())}。语义对应 GDAL
     * {@code OGROpenFileGDBDataSource::GetLayer(int iLayer)} —— 拿 {@code iLayer}
     * 去索引 {@code m_apoLayers}(用户图层顺序表),
     * 越界时 GDAL 返回 {@code nullptr},本库改为抛异常以便定位问题。
     *
     * <p>⚠️ <b>作用域只覆盖用户图层。</b>系统表({@code GDB_SystemCatalog} /
     * {@code GDB_Items} 等)既不参与计数、也不能用序号取 —— GDAL 把它们单独收在
     * {@code m_apoHiddenLayers}。需要系统表请用 {@link #getLayer(String)}(它接受
     * 逻辑名、物理名与系统表名)。
     *
     * <p>序号由 catalog 的记录顺序推导(过滤掉系统表与非图层条目),每次调用都要
     * 重新遍历 catalog。本库建图层是追加式的、新图层总排在末尾,所以既有图层的
     * 序号保持稳定;但若 catalog 被外部(ArcGIS / GDAL)改动过,序号可能变化,
     * 需要长期稳定的引用请按名字取。
     *
     * @param index 0 基序号
     * @return 图层
     * @throws GdbNotFoundException 序号越界(负数或 {@code >= getLayerCount()})
     * @see #getLayer(String)
     * @see #getLayerCount()
     */
    public OpenFileGdbLayer getLayer(int index) {
        ensureOpen();
        List<String> names = getLayerNames();
        if (index < 0 || index >= names.size()) {
            throw new GdbNotFoundException("图层序号越界: " + index + ",有效范围 [0, "
                    + names.size() + ");现有图层: " + names);
        }
        return getLayer(names.get(index));
    }

    // ==================================================================
    // SQL 查询(对齐 GDAL ExecuteSQL)
    // ==================================================================

    /**
     * 执行一条只读 SQL 查询,返回一个<b>跟普通图层一样读</b>的结果图层。
     *
     * <p>与 GDAL 完全同形:{@code GDALDataset::ExecuteSQL(pszSQL, nullptr,
     * pszDialect)} 返回 {@code OGRLayer*}(实际是 {@code OGRGenSQLResultsLayer}),
     * 消费完调 {@code ReleaseResultSet};这里返回
     * {@link com.gdal.openfilegdb.layer.GdbLayer}(实际是
     * {@link com.gdal.openfilegdb.sql.GdbSqlLayer}),消费完调
     * {@link #releaseResultSet}。方言是 <b>Calcite SQL</b>(SQL:92 级别的标准语法,
     * 比 OGR SQL 的子集大,支持 {@code WITH}/聚合/窗口函数/子查询等),细节见
     * {@link com.gdal.openfilegdb.sql.GdbSqlEngine}。
     *
     * <p><b>行 → 要素</b>:几何列落到 {@code getGeometry()},OBJECTID 列落到
     * {@code getFID()},其余列按名字进属性表。所以:
     * <pre>{@code
     * try (OpenFileGdbDataset ds = OpenFileGdbDataset.open("data.gdb")) {
     *     GdbLayer rs = ds.executeSql("SELECT name, Shape, dcmj FROM 村 WHERE dcmj > 1000");
     *     try {
     *         for (GdbFeature f : rs) {
     *             System.out.println(f.getFID() + " " + f.getField("name")
     *                     + " " + f.getGeometry().toWkt());
     *         }
     *     } finally {
     *         ds.releaseResultSet(rs);
     *     }
     * }
     * }</pre>
     *
     * <p><b>内存特性(与 GDAL 同款取舍)</b>:简单 {@code SELECT/WHERE/LIMIT} 与
     * {@code COUNT/SUM/AVG/MIN/MAX} 全程流式,内存与结果行数无关;
     * {@code ORDER BY} 全表排序、{@code DISTINCT}/{@code GROUP BY} 建哈希表
     * 会物化(GDAL 文档对这些子句同样警告内存)。
     *
     * @param sql 查询语句(SELECT / WITH / VALUES / TABLE / EXPLAIN)
     * @return 结果图层(调用方负责 {@link #releaseResultSet} 或 {@code close()})
     * @throws com.gdal.openfilegdb.exception.GdbSqlException 语法错误 /
     *         表或字段不存在 / 非查询语句
     */
    public com.gdal.openfilegdb.layer.GdbLayer executeSql(String sql) {
        return executeSql(sql, null);
    }

    /**
     * 执行一条只读 SQL 查询,叠加空间范围过滤。
     *
     * <p>对标 GDAL {@code ExecuteSQL(pszSQL, poSpatialFilter, ...)} 的第二个参数:
     * 空间过滤不走 SQL 语法,而是作为扫描源的前置条件 —— 有 {@code .spx} 空间索引
     * 就先粗筛候选行号再精确判包围盒相交(与 {@link OpenFileGdbLayer#search} 同款
     * 流程与降级策略),没有就全表流式扫 + 精确判断。相当于对 FROM 的每个空间表
     * 预置了 {@code Intersects(bbox)}。
     *
     * @param sql  查询语句
     * @param bbox {xmin, ymin, xmax, ymax};{@code null} 表示无空间过滤
     * @return 结果图层(调用方负责 {@link #releaseResultSet} 或 {@code close()})
     * @throws com.gdal.openfilegdb.exception.GdbSqlException 查询失败
     */
    public com.gdal.openfilegdb.layer.GdbLayer executeSql(String sql, double[] bbox) {
        ensureOpen();
        List<OpenFileGdbLayer> layers = new ArrayList<>();
        for (String name : getLayerNames()) {
            layers.add(getLayer(name));
        }
        com.gdal.openfilegdb.sql.GdbSqlResult result =
                com.gdal.openfilegdb.sql.GdbSqlEngine.execute(sql, bbox, layers);
        String query = result.toString();
        // GdbSqlResult 的 toString 里带的是去过分号的语句,这里用原始入参更直观
        query = sql.trim();
        while (query.endsWith(";")) {
            query = query.substring(0, query.length() - 1).trim();
        }
        return new com.gdal.openfilegdb.sql.GdbSqlLayer(query, result, layers);
    }

    /**
     * 释放 {@link #executeSql} 的结果(GDAL: {@code GDALDataset::ReleaseResultSet})。
     *
     * <p>对 SQL 结果图层会关掉底层 JDBC 资源;传普通图层进来是安全的空操作
     * (普通图层的生命周期归数据集,由 {@link #close()} 统一关表 —— 与 GDAL 里
     * "只对 ExecuteSQL 出来的层调 ReleaseResultSet"一致)。幂等。
     *
     * @param layer {@link #executeSql} 返回的结果图层;可为 {@code null}
     */
    public void releaseResultSet(com.gdal.openfilegdb.layer.GdbLayer layer) {
        if (layer instanceof AutoCloseable) {
            try {
                ((AutoCloseable) layer).close();
            } catch (Exception e) {
                // GDAL 的 ReleaseResultSet 也不报错;这里兜住底层 close 的受检异常
                LOG.warn("释放 SQL 结果图层失败: {}", e.getMessage());
            }
        }
    }

    /**
     * {@link #executeSql} 的"原始行"版本:不做行 → 要素的转换,直接给
     * {@code Object[]} 游标。
     *
     * <p>聚合 / 分组这类<b>没有 OBJECTID、没有几何</b>的结果用它最顺手
     * ({@code row[0]}、{@code row[1]} 按列取值);有 OBJECTID 和几何的查询请用
     * {@link #executeSql} 拿图层。GDAL 里没有对应物(那里一律是要素层),
     * 这是本库给的便利通道。
     *
     * @param sql 查询语句
     * @return 游标式结果(调用方负责 close)
     * @throws com.gdal.openfilegdb.exception.GdbSqlException 查询失败
     */
    public com.gdal.openfilegdb.sql.GdbSqlResult executeSqlRows(String sql) {
        return executeSqlRows(sql, null);
    }

    /**
     * {@link #executeSqlRows(String)} 带空间过滤的版本。
     *
     * @param sql  查询语句
     * @param bbox {xmin, ymin, xmax, ymax};{@code null} 表示无空间过滤
     * @return 游标式结果(调用方负责 close)
     * @throws com.gdal.openfilegdb.exception.GdbSqlException 查询失败
     */
    public com.gdal.openfilegdb.sql.GdbSqlResult executeSqlRows(String sql, double[] bbox) {
        ensureOpen();
        List<OpenFileGdbLayer> layers = new ArrayList<>();
        for (String name : getLayerNames()) {
            layers.add(getLayer(name));
        }
        return com.gdal.openfilegdb.sql.GdbSqlEngine.execute(sql, bbox, layers);
    }

    // ==================================================================
    // 写:新建图层
    // ==================================================================

    /**
     * 新建要素类并登记进系统表。
     *
     * <p>对应 GDAL {@code OGROpenFileGDBDataSource::ICreateLayer} +
     * {@code OGROpenFileGDBLayer::Create} + {@code RegisterTable}。步骤:
     * <ol>
     *   <li>物理编号 = {@code 1 + catalog 槽位数},物理名 {@code a%08x};</li>
     *   <li>建 {@code .gdbtable}/{@code .gdbtablx},字段顺序 OBJECTID、Shape、属性;</li>
     *   <li>{@code GDB_SystemCatalog} 追加 {@code (Name, FileFormat=0)};</li>
     *   <li>{@code GDB_Items} 追加一条 item(见 {@link #registerLayer});</li>
     *   <li>{@code GDB_ItemRelationships} 追加"根条目 → 图层"的 {@code DatasetInFolder};</li>
     *   <li>坐标系不在 {@code GDB_SpatialRefs} 里就追加一行。</li>
     * </ol>
     *
     * <p>⚠️ {@code srs} 为空(WKT 为空)时用默认的 WGS84(与 GDAL/Python 一致),
     * 因此读回来时会得到 WGS84 而不是空坐标系。
     *
     * @param name     图层名
     * @param srs      空间参考(可为 {@code null} / {@link SpatialReference#EMPTY})
     * @param geomType 几何类型(不能是 {@link GeometryType#UNKNOWN},否则请用
     *                 {@link #createTable})
     * @param fields   属性字段(不要在里面放 OBJECTID / 几何字段)
     * @return 新建的图层
     */
    public OpenFileGdbLayer createLayer(String name, SpatialReference srs,
                                        GeometryType geomType, List<GdbField> fields) {
        requireWritable();
        if (geomType == null || geomType == GeometryType.UNKNOWN) {
            throw new GdbWriteException("要素类必须有几何类型;建非空间表请用 createTable()");
        }
        String logical = validateLayerName(name);
        ensureLayerNotExists(logical);

        int number = catalog.nextPhysicalNumber();
        String physical = GdbSystemCatalog.physicalNameForIndex(number);
        Path tablePath = path.resolve(physical + ".gdbtable");
        if (Files.exists(tablePath)) {
            throw new GdbWriteException(logical + ": 物理文件 " + physical
                    + ".gdbtable 已被占用(catalog 与磁盘不同步?)");
        }

        String wkt = resolveWkt(srs);
        int wkid = (srs == null || srs.getWkt().isEmpty()) ? DEFAULT_WKID_WGS84 : srs.getWkid();
        int latestWkid = (srs == null || srs.getWkt().isEmpty())
                ? DEFAULT_WKID_WGS84 : srs.getLatestWkid();
        List<GdbField> full = buildLayerFields(wkt, fields);

        GdbTable table = GdbTable.create(tablePath, full, geomType.tableGeomType(),
                false, false, false, null, 5);
        tables.put(physical, table);
        try {
            registerLayer(logical, physical, true, geomType.tableGeomType(), wkt, wkid,
                    latestWkid, geomType, full);
        } catch (RuntimeException e) {
            rollbackNewLayer(physical, table);
            throw e;
        }
        itemIndex = null;
        return getLayer(logical);
    }

    /**
     * 新建非空间表并登记进系统表(流程同 {@link #createLayer},但无几何字段、
     * {@code GDB_Items.Type} 用 Table GUID,且不写几何相关列与空间参考)。
     *
     * @param name   表名
     * @param fields 属性字段
     * @return 新建的图层(非空间表)
     */
    public OpenFileGdbLayer createTable(String name, List<GdbField> fields) {
        requireWritable();
        String logical = validateLayerName(name);
        ensureLayerNotExists(logical);

        int number = catalog.nextPhysicalNumber();
        String physical = GdbSystemCatalog.physicalNameForIndex(number);
        Path tablePath = path.resolve(physical + ".gdbtable");
        if (Files.exists(tablePath)) {
            throw new GdbWriteException(logical + ": 物理文件 " + physical
                    + ".gdbtable 已被占用(catalog 与磁盘不同步?)");
        }

        List<GdbField> full = buildTableFields(fields);
        GdbTable table = GdbTable.create(tablePath, full, 0, false, false, false, null, 5);
        tables.put(physical, table);
        try {
            registerLayer(logical, physical, false, 0, null, 0, 0, null, full);
        } catch (RuntimeException e) {
            rollbackNewLayer(physical, table);
            throw e;
        }
        itemIndex = null;
        return getLayer(logical);
    }

    // ==================================================================
    // 落盘 / 关闭
    // ==================================================================

    /**
     * 把所有待写改动落盘(逐个 {@link GdbTable#sync()} 所有图层表 + 系统表)。
     *
     * <p>对应 GDAL {@code OGROpenFileGDBDataSource::FlushCache(true)}。
     */
    public void flush() {
        ensureOpen();
        for (GdbTable t : new ArrayList<>(tables.values())) {
            t.sync();
        }
    }

    /** {@code flush()} + 关闭。 */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        RuntimeException error = null;
        try {
            flush();
        } catch (RuntimeException e) {
            error = e;
        }
        for (GdbTable t : tables.values()) {
            try {
                t.close();
            } catch (RuntimeException e) {
                if (error == null) {
                    error = e;
                }
            }
        }
        tables.clear();
        layers.clear();
        itemIndex = null;
        closed = true;
        if (error != null) {
            throw error;
        }
    }

    // ==================================================================
    // 内部:表打开与缓存
    // ==================================================================

    /** 打开(并缓存)一个物理表的句柄。 */
    private GdbTable openTable(String physical) {
        GdbTable cached = tables.get(physical);
        if (cached != null) {
            return cached;
        }
        Path tablePath = path.resolve(physical + ".gdbtable");
        if (!Files.isRegularFile(tablePath)) {
            throw new GdbNotFoundException("缺少表文件 " + physical + ".gdbtable");
        }
        GdbTable table = writable ? GdbTable.openForWrite(tablePath) : GdbTable.open(tablePath);
        tables.put(physical, table);
        return table;
    }

    /** 取一张系统表。 */
    private GdbTable systemTable(String logical) {
        String physical = catalog.physicalFor(logical);
        if (physical == null) {
            throw new GdbNotFoundException("找不到系统表 " + logical);
        }
        return openTable(physical);
    }

    // ==================================================================
    // 内部:登记
    // ==================================================================

    /**
     * 往系统表登记一个新建的图层。
     *
     * <p>对应 {@code RegisterLayerInSystemCatalog} /
     * {@code RegisterFeatureClassInItems} / {@code RegisterASpatialTableInItems} /
     * {@code RegisterInItemRelationships} / {@code AddNewSpatialRef}
     * (ogropenfilegdbdatasource_write.cpp:144/459/508/165/101)。
     */
    private void registerLayer(String logical, String physical, boolean spatial,
                               int tableGeomType, String wkt, int wkid, int latestWkid,
                               GeometryType geomType, List<GdbField> fullFields) {
        String rootGuid = rootGuid();
        if (rootGuid == null || rootGuid.isEmpty()) {
            throw new GdbWriteException(logical + ": GDB_Items 里找不到根条目(Path == \"\\\\\")");
        }

        // 1) GDB_SystemCatalog:追加 (Name, FileFormat=0)
        catalog.appendEntry(logical, 0);
        catalog.sync();

        // 2) GDB_Items:追加 item
        GdbTable items = systemTable("GDB_Items");
        int dsid = 1 + items.getRecordCount();
        String layerGuid = GdbSystemCatalog.generateUuid();
        String definition = spatial
                ? GdbSystemCatalog.makeFeatureClassXml(logical, fullFields, wkt, wkid, latestWkid,
                        false, false, esriShapeType(geomType), OID_FIELD_NAME, GEOM_FIELD_NAME,
                        PATH_PREFIX + logical, "", dsid)
                : GdbSystemCatalog.makeTableXml(logical, fullFields, OID_FIELD_NAME,
                        PATH_PREFIX + logical, "", dsid);

        GdbFeature item = new GdbFeature();
        item.setAttribute("UUID", layerGuid);
        item.setAttribute("Type",
                GdbSystemCatalog.itemTypeUuid(spatial ? "Feature Class" : "Table"));
        item.setAttribute("Name", logical);
        item.setAttribute("PhysicalName", physical);
        item.setAttribute("Path", PATH_PREFIX + logical);
        item.setAttribute("URL", "");
        item.setAttribute("Definition", definition);
        item.setAttribute("Properties", 1);
        if (spatial) {
            // DatasetSubtype1 恒为 1;DatasetSubtype2 是表级几何类型;DatasetInfo1 是几何字段名
            item.setAttribute("DatasetSubtype1", 1);
            item.setAttribute("DatasetSubtype2", tableGeomType);
            item.setAttribute("DatasetInfo1", GEOM_FIELD_NAME);
        }
        items.appendFeature(item);
        items.sync();

        // 3) GDB_ItemRelationships:根条目 -> 图层(DatasetInFolder)
        GdbTable rel = systemTable("GDB_ItemRelationships");
        GdbFeature relation = new GdbFeature();
        relation.setAttribute("UUID", GdbSystemCatalog.generateUuid());
        relation.setAttribute("OriginID", rootGuid);
        relation.setAttribute("DestID", layerGuid);
        relation.setAttribute("Type", GdbSystemCatalog.relationshipUuid("DatasetInFolder"));
        relation.setAttribute("Properties", 1);
        rel.appendFeature(relation);
        rel.sync();

        // 4) GDB_SpatialRefs:去重后追加坐标系
        if (spatial) {
            registerSpatialRef(wkt);
        }
    }

    /**
     * 坐标系没登记过就追加一行。
     *
     * <p>对应 {@code AddNewSpatialRef}(ogropenfilegdbdatasource_write.cpp:101):先全表找
     * {@code SRTEXT} 相同且 10 个量化参数全相等的行,找到就不写。
     */
    private void registerSpatialRef(String wkt) {
        if (wkt == null || wkt.isEmpty()) {
            return;
        }
        GdbTable table = systemTable("GDB_SpatialRefs");
        String[] numeric = {"FalseX", "FalseY", "XYUnits", "FalseZ", "ZUnits",
                "FalseM", "MUnits", "XYTolerance", "ZTolerance", "MTolerance"};
        double[] wanted = {DF_X_ORIGIN, DF_Y_ORIGIN, DF_XY_SCALE, DF_Z_ORIGIN, DF_Z_SCALE,
                DF_M_ORIGIN, DF_M_SCALE, DF_XY_TOLERANCE, DF_Z_TOLERANCE, DF_M_TOLERANCE};
        int total = table.getRecordCount();
        for (int row = 0; row < total; row++) {
            GdbFeature f = table.readFeature(row);
            if (f == null) {
                continue;
            }
            Object srsText = f.getAttribute("SRTEXT");
            if (srsText == null || !String.valueOf(srsText).equals(wkt)) {
                continue;
            }
            boolean allEqual = true;
            for (int i = 0; i < numeric.length; i++) {
                Object got = f.getAttribute(numeric[i]);
                if (!(got instanceof Number) || ((Number) got).doubleValue() != wanted[i]) {
                    allEqual = false;
                    break;
                }
            }
            if (allEqual) {
                return;
            }
        }
        GdbFeature entry = new GdbFeature();
        entry.setAttribute("SRTEXT", wkt);
        for (int i = 0; i < numeric.length; i++) {
            entry.setAttribute(numeric[i], wanted[i]);
        }
        table.appendFeature(entry);
        table.sync();
    }

    /**
     * 根条目的 GUID({@code GDB_Items} 里 {@code Path == "\"} 那一行的 UUID)。
     *
     * <p>对应 {@code ogropenfilegdbdatasource.cpp:731} 的
     * {@code m_osRootGUID = psUUID->String}。
     */
    private String rootGuid() {
        for (ItemRow it : itemIndex().values()) {
            if (PATH_PREFIX.equals(it.path())) {
                return it.uuid();
            }
        }
        return "";
    }

    /** 登记失败时清掉半成品,别留下 catalog 里没有的孤儿表。 */
    private void rollbackNewLayer(String physical, GdbTable table) {
        try {
            table.close();
        } catch (RuntimeException ignored) {
            // 清理路径上不再关心失败
        }
        tables.remove(physical);
        try (Stream<Path> s = Files.list(path)) {
            List<Path> victims = s.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString()
                            .toLowerCase(Locale.ROOT).startsWith(physical))
                    .collect(Collectors.toList());
            for (Path v : victims) {
                Files.deleteIfExists(v);
            }
        } catch (IOException e) {
            // 清理失败只是留下垃圾文件,不影响上抛的原始异常
        }
    }

    // ==================================================================
    // 内部:字段构造
    // ==================================================================

    /** 要素类字段清单:{@code [OBJECTID, Shape, 属性...]}。 */
    private List<GdbField> buildLayerFields(String wkt, List<GdbField> fields) {
        List<GdbField> out = new ArrayList<>();
        out.add(new GdbField(OID_FIELD_NAME, FieldType.OBJECTID)
                .setNullable(false).setRequired(true).setEditable(false));
        out.add(buildGeomField(wkt));
        appendAttributeFields(out, fields);
        return out;
    }

    /** 非空间表字段清单:{@code [OBJECTID, 属性...]}。 */
    private List<GdbField> buildTableFields(List<GdbField> fields) {
        List<GdbField> out = new ArrayList<>();
        out.add(new GdbField(OID_FIELD_NAME, FieldType.OBJECTID)
                .setNullable(false).setRequired(true).setEditable(false));
        appendAttributeFields(out, fields);
        return out;
    }

    /** 追加用户属性字段,校验重复与非法 OID/几何字段。 */
    private void appendAttributeFields(List<GdbField> out, List<GdbField> fields) {
        Set<String> seen = new HashSet<>();
        for (GdbField f : out) {
            seen.add(f.getName().toLowerCase(Locale.ROOT));
        }
        if (fields == null) {
            return;
        }
        for (GdbField spec : fields) {
            if (spec == null) {
                continue;
            }
            if (spec.isObjectId() || spec.isGeometry()) {
                throw new GdbWriteException(
                        "不要把 OBJECTID / 几何字段放进 fields(Dataset 会自动补上):" + spec.getName());
            }
            String fieldName = spec.getName();
            if (fieldName == null || fieldName.trim().isEmpty()) {
                throw new GdbWriteException("字段缺少名字");
            }
            if (!seen.add(fieldName.toLowerCase(Locale.ROOT))) {
                throw new GdbWriteException("字段名重复: " + fieldName);
            }
            GdbField copy = spec.copy();
            if (copy.getType() == FieldType.STRING && copy.getLength() <= 0) {
                copy.setLength(255);
            }
            out.add(copy);
        }
    }

    /** 构造几何字段(量化参数与空包围盒)。 */
    private GdbGeomField buildGeomField(String wkt) {
        GdbGeomField gf = new GdbGeomField(GEOM_FIELD_NAME);
        gf.setAlias("");
        gf.setNullable(true);
        gf.setRequired(true);
        gf.setEditable(true);
        gf.setWkt(wkt);
        // 无 Z/M 支持(API 未暴露 hasZ/hasM 参数)
        gf.setHasZOriginScaleTolerance(false);
        gf.setHasMOriginScaleTolerance(false);
        gf.setXOrigin(DF_X_ORIGIN).setYOrigin(DF_Y_ORIGIN).setXyScale(DF_XY_SCALE);
        gf.setXyTolerance(DF_XY_TOLERANCE);
        double nan = GdbDateTimeUtil.ESRI_NAN;
        gf.setExtent(nan, nan, nan, nan);
        gf.setSpatialIndexGridResolutions(DEFAULT_GRID_RESOLUTIONS);
        return gf;
    }

    /** 由入参空间参考定 WKT(空时用默认 WGS84)。 */
    private static String resolveWkt(SpatialReference srs) {
        if (srs == null || srs.getWkt() == null || srs.getWkt().isEmpty()) {
            return DEFAULT_WKT_WGS84;
        }
        return srs.getWkt();
    }

    /** {@link GeometryType} → ESRI 几何类型串(写进 Definition XML)。 */
    private static String esriShapeType(GeometryType t) {
        switch (t) {
            case POINT:
                return "esriGeometryPoint";
            case MULTIPOINT:
                return "esriGeometryMultipoint";
            case LINESTRING:
            case MULTILINESTRING:
                return "esriGeometryPolyline";
            case POLYGON:
            case MULTIPOLYGON:
                return "esriGeometryPolygon";
            case MULTIPATCH:
                return "esriGeometryMultiPatch";
            default:
                return "";
        }
    }

    // ==================================================================
    // 内部:GDB_Items 视图与分类
    // ==================================================================

    /** {@code GDB_Items} 的一行(读路径用)。 */
    private static final class ItemRow {
        private final String name;
        private final String uuid;
        private final String physicalName;
        private final String path;
        private final String typeGuid;
        private final String definition;

        ItemRow(String name, String uuid, String physicalName, String path,
                String typeGuid, String definition) {
            this.name = name;
            this.uuid = uuid;
            this.physicalName = physicalName;
            this.path = path;
            this.typeGuid = typeGuid;
            this.definition = definition;
        }

        String name() {
            return name;
        }

        String uuid() {
            return uuid;
        }

        String physicalName() {
            return physicalName;
        }

        String path() {
            return path;
        }

        String typeGuid() {
            return typeGuid;
        }

        String definition() {
            return definition;
        }
    }

    /** 读 {@code GDB_Items} 建"小写名 → item"索引(懒加载 + 手动失效)。 */
    private Map<String, ItemRow> itemIndex() {
        if (itemIndex != null) {
            return itemIndex;
        }
        Map<String, ItemRow> map = new LinkedHashMap<>();
        String physical = catalog.physicalFor(GdbSystemCatalog.GDB_ITEMS_LOGICAL_NAME);
        if (physical != null && Files.isRegularFile(path.resolve(physical + ".gdbtable"))) {
            try {
                GdbTable items = openTable(physical);
                for (GdbFeature f : items.features()) {
                    Object name = f.getAttribute("Name");
                    if (name == null) {
                        continue;
                    }
                    ItemRow row = new ItemRow(String.valueOf(name),
                            str(f.getAttribute("UUID")), str(f.getAttribute("PhysicalName")),
                            str(f.getAttribute("Path")), str(f.getAttribute("Type")),
                            str(f.getAttribute("Definition")));
                    map.put(row.name().toLowerCase(Locale.ROOT), row);
                }
            } catch (RuntimeException e) {
                // 拿不到 item 分类不影响库的可打开性:退回"有表文件就算图层"
            }
        }
        itemIndex = map;
        return map;
    }

    /** 一个 catalog 条目对应的物理名(优先 GDB_Items.PhysicalName)。 */
    private String physicalForEntry(GdbSystemCatalog.Entry e) {
        ItemRow it = itemIndex().get(e.name().toLowerCase(Locale.ROOT));
        if (it != null && it.physicalName() != null
                && it.physicalName().matches("^[aA][0-9a-fA-F]{8}$")) {
            return it.physicalName().toLowerCase(Locale.ROOT);
        }
        return GdbSystemCatalog.physicalNameForIndex(e.physicalNumber());
    }

    /** 判定一个图层名的 item 种类(拿不到时返回空串)。 */
    private String classify(String logicalName) {
        ItemRow it = itemIndex().get(logicalName.toLowerCase(Locale.ROOT));
        if (it == null) {
            return "";
        }
        return GdbSystemCatalog.classify(it.typeGuid(), it.definition());
    }

    /** 该 item 种类是否算"图层"(判定不出来时按图层处理)。 */
    private static boolean isLayerKind(String kind) {
        return kind == null || kind.isEmpty() || !NON_LAYER_KINDS.contains(kind);
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    // ==================================================================
    // 内部:校验
    // ==================================================================

    private void ensureOpen() {
        if (closed) {
            throw new GdbWriteException(path + ": 数据集已关闭");
        }
    }

    private void requireWritable() {
        ensureOpen();
        if (!writable) {
            throw new GdbWriteException(path + ": 只读打开,不能写;请用 create() 或 openForWrite()");
        }
    }

    private String validateLayerName(String name) {
        if (name == null || name.trim().isEmpty()) {
            throw new GdbWriteException("图层名不能为空");
        }
        String logical = name.trim();
        if (logical.indexOf('\\') >= 0 || logical.indexOf('/') >= 0) {
            throw new GdbWriteException("图层名不能含路径分隔符: " + logical);
        }
        if (logical.length() > 160) {
            throw new GdbWriteException("图层名超过 160 字符(GDB_Items.Name 的宽度上限)");
        }
        if (GdbSystemCatalog.isSystemTableName(logical)) {
            throw new GdbWriteException(logical + " 是系统表名,不能用作图层名");
        }
        return logical;
    }

    private void ensureLayerNotExists(String logical) {
        for (GdbSystemCatalog.Entry e : catalog.entries()) {
            if (e.name().equalsIgnoreCase(logical)) {
                throw new GdbWriteException(logical + " 已存在");
            }
        }
    }

    @Override
    public String toString() {
        if (closed) {
            return "<OpenFileGdbDataset " + path.getFileName() + " (closed)>";
        }
        return "<OpenFileGdbDataset " + path.getFileName()
                + (writable ? " writable" : " read-only") + ">";
    }
}
