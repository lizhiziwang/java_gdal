package com.gdal.openfilegdb.index;

import com.gdal.openfilegdb.exception.GdbFormatException;
import com.gdal.openfilegdb.exception.GdbIoException;
import com.gdal.openfilegdb.feature.GdbGeomField;
import com.gdal.openfilegdb.io.ByteReader;
import com.gdal.openfilegdb.io.LittleEndian;
import com.gdal.openfilegdb.table.GdbTable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * FileGDB 空间索引的<b>只读</b>查询:把"查询矩形"转成"候选行号集合"。
 *
 * <p>一个要素类的空间索引由两个文件共同描述(文件名与 {@code .gdbtable} 同前缀,
 * 例如 {@code a00000009.gdbtable} / {@code a00000009.gdbindexes} /
 * {@code a00000009.spx}):
 *
 * <table border="1">
 *   <caption>两个文件的分工</caption>
 *   <tr><th>文件</th><th>作用</th></tr>
 *   <tr><td>{@code .gdbindexes}</td>
 *       <td><b>索引清单</b>:列出该表的全部索引描述符(索引名 + 表达式 + 若干魔数),
 *           本身不含数据。对应 {@code filegdbtable.cpp::FileGDBTable::GetIndexCount}。</td></tr>
 *   <tr><td>{@code .spx}</td>
 *       <td><b>空间索引本体</b>:分页存储的 1 维有序树,条目键是 64 位的
 *           {@code (gridNo &lt;&lt; 62) | (xScaled &lt;&lt; 31) | yScaled} 复合键。
 *           对应 {@code filegdbindex.cpp::FileGDBIndexIteratorBase} 与
 *           {@code FileGDBSpatialIndexIteratorImpl}。</td></tr>
 * </table>
 *
 * <h2>.gdbindexes 布局(GDAL 逆向所得,无公开文档)</h2>
 * <pre>
 *   u32   nIndexCount
 *   重复 nIndexCount 次:
 *       u32    索引名的 UTF-16 码元个数
 *       u16[]  索引名(小端 UTF-16)
 *       u16    magic1(恒为 0)
 *       u32    magic2     ┐ 合起来决定描述符类型,合法组合见 {@link #MAGIC_OK}
 *       u16    magic3     ┘
 *       u32    magic4(恒为 1)
 *       u32    表达式(列名)的 UTF-16 码元个数
 *       u16[]  表达式
 *       u16    magic5(恒为 0)
 * </pre>
 *
 * <h2>.spx 布局</h2>
 * <p>文件被切成等长的页(第 1 页在偏移 0),页号 1 起算;文件末尾是 trailer。
 * <pre>
 *   [page 1][page 2]...[page N][trailer]
 *
 *   页  = [页号(objectIdSize 字节)][条目数 u32][到各段起点的填充]
 *         [非叶页: 子页号数组 | 叶页: FID 数组][有序值数组]
 *
 *   trailer(v1 共 22 字节 / v2 共 30 字节),相对 trailer 起点:
 *       +0   u8    m_nValueSize(空间索引恒为 8)
 *       +2   u32   nMagic1(必须为 1)
 *       +6   u32   nIndexDepth(1..MAX_DEPTH+1)
 *       +10  u32(v1) / u64(v2)  m_nValueCountInIdx(索引里登记的要素数)
 * </pre>
 *
 * <h2>为什么是"1 维有序树"而不是教科书上的 R 树</h2>
 * <p>FileGDB 的 {@code .spx} 并不是按二维包围盒组织的 R 树,而是把二维查询矩形
 * 按 grid 层级 + x 格号切成若干段,每段映射成一个 64 位区间 {@code [minVal,maxVal]},
 * 再在这个<b>有序</b>键空间里做范围定位。层级越粗的 grid 用越大的格网覆盖同一片
 * 区域;查询时从最细的 grid 0 开始,找不到候选再放大到 grid 1、grid 2。这一整套
 * 逻辑来自 {@code filegdbindex.cpp::FileGDBSpatialIndexIteratorImpl} 的
 * {@code ReadNewXRange} / {@code FindPages} / {@code GetNextRow}。
 *
 * <h2>与 Python 参考实现的对应</h2>
 * <p>{@code pyopenfilegdb/_gdbindex.py} 里 {@code .gdbindexes} 的解析是完整实现
 * ({@code read_gdbindexes}),本类逐行对照移植了它;而该 Python 版本把 {@code .spx}
 * 标为 Tier3 桩(未实现查询),因此 {@code .spx} 侧的每个字段偏移、大小端与字节数
 * 全部以 GDAL {@code filegdbindex.cpp} 为准(本类已按 GDAL 行号逐段加注)。
 *
 * <p><b>线程安全</b>:全部 IO 走 {@link FileChannel} 的位置读写
 * ({@code read(buf, pos)}),不使用共享的文件游标;每次 {@link #search} 都新建一份
 * 独立的查询游标状态,因此同一个实例可以被多个线程并发查询。索引一经打开即不可变。
 */
public final class GdbSpatialIndex {

    // ==================================================================
    // 常量:全部对齐 GDAL filegdbindex.cpp / filegdbtable.cpp
    // ==================================================================

    /** 树的最大层数,对应 {@code filegdbindex.cpp::MAX_DEPTH}。 */
    private static final int MAX_DEPTH = 3;

    /** v1 格式的页大小,对应 {@code filegdbindex.cpp::FGDB_PAGE_SIZE_V1}。 */
    private static final int PAGE_SIZE_V1 = 4096;

    /** v2 格式的页大小,对应 {@code filegdbindex.cpp::FGDB_PAGE_SIZE_V2}。 */
    private static final int PAGE_SIZE_V2 = 65536;

    /** v1 的 trailer 长度,对应 {@code filegdbindex.cpp::V1_TRAILER_SIZE}。 */
    private static final int V1_TRAILER_SIZE = 22;

    /** v2 的 trailer 长度,对应 {@code filegdbindex.cpp::V2_TRAILER_SIZE}。 */
    private static final int V2_TRAILER_SIZE = 30;

    /**
     * {@code .gdbindexes} 首字段的"这不是 v10 描述符"魔数。
     *
     * <p>对应 GDAL {@code FileGDBTable::GetIndexCount} 里那个
     * {@code nIndexCount == 0x03859813} 判断:FileGDB v9 的索引结构完全不同,
     * GDAL 自己也没实现(只对栅格 {@code blk_key_index} 做了硬编码特判),
     * 本库同样不支持,只把文件识别出来。
     */
    private static final long GDBINDEXES_V9_MAGIC = 0x03859813L;

    /**
     * 索引描述符里 4 个"魔数"字段的合法组合,对应 GDAL {@code GetIndexCount}
     * 中约 2594 行的判断:{@code (2,0)} 普通属性索引、{@code (4,0)} 另一种普通索引、
     * {@code (16,65535)} OBJECTID 索引(不落成 {@code .atx})。
     */
    private static final int[][] MAGIC_OK = {{2, 0}, {4, 0}, {16, 65535}};

    /**
     * 索引名 / 表达式长度的合理性上限。
     *
     * <p>GDAL 硬编码 1024,超过即认为文件损坏。
     */
    private static final int MAX_NAME_CHARS = 1024;

    /** {@code .gdbindexes} 的字节数上限,对应 GDAL 约 2521 行的 1MB 判脏。 */
    private static final int MAX_GDBINDEXES_BYTES = 1024 * 1024;

    /**
     * 索引个数的宽松上限。
     *
     * <p>GDAL 的判据是 {@code nIndexCount < (字段数+1)*10};这里没有字段数,
     * 用一个等价宽松的常量代替,防止损坏文件把我们带进死循环。
     */
    private static final long MAX_INDEX_COUNT = 100000;

    // ==================================================================
    // 不可变配置(由 open() 一次性建立)
    // ==================================================================

    private final Path spxPath;
    private final FileChannel channel;

    /* ---- trailer 解出的参数 ---- */

    /** {@code .spx} 的格式版本,1 或 2。 */
    private final int version;
    /** 页大小(字节):v1 = 4096,v2 = 65536。 */
    private final int pageSize;
    /** 页数 = (文件大小 - trailer 长度) / 页大小。 */
    private final int pageCount;
    /** 单页最多引用的要素数 / 子页数。 */
    private final int maxPerPages;
    /** 页内引用号宽度(对象数/页号):v1 = 4 字节,v2 = 8 字节。 */
    private final int objectIdSize;
    /** 索引值的宽度;空间索引恒为 8(64 位复合键)。 */
    private final int valueSize;
    /** 非叶页头长度:v1 = 8,v2 = 12。 */
    private final int nonLeafPageHeaderSize;
    /** 叶页头长度:v1 = 12,v2 = 20。 */
    private final int leafPageHeaderSize;
    /** 页内第一个值相对页起点的偏移。 */
    private final int offsetFirstValInPage;
    /** 索引里登记的要素数(m_nValueCountInIdx)。 */
    private final long valueCountInIdx;
    /** 树的层数(nIndexDepth)。 */
    private final int indexDepth;

    /* ---- 来自 .gdbtable 的几何字段信息 ---- */

    /** 空间索引格网分辨率数组(1~3 个);查询从最细的 grid 0 开始逐级放大。 */
    private final double[] gridRes;
    /** 全表 X 最小(用于格网分辨率可用性自检)。 */
    private final double geomXMin;
    /** 全表 Y 最小。 */
    private final double geomYMin;
    /** 全表 X 最大。 */
    private final double geomXMax;
    /** 全表 Y 最大。 */
    private final double geomYMax;

    /** 记录槽位总数(含空洞),取自 {@code .gdbtablx} 头,用于 FID 合法性校验。 */
    private final int totalRecordCount;

    /** {@link #getFeatureCount()} 的返回值。 */
    private final int featureCount;

    /**
     * {@code .gdbindexes} 中"看起来是空间索引"的那条描述符的索引名;没有则为
     * {@code null}。仅作诊断(见 {@link #toString()})。
     */
    private final String spatialEntryName;

    private GdbSpatialIndex(Path spxPath, FileChannel channel, int version, int pageSize,
                            int pageCount, int maxPerPages, int objectIdSize, int valueSize,
                            int nonLeafPageHeaderSize, int leafPageHeaderSize,
                            int offsetFirstValInPage, long valueCountInIdx, int indexDepth,
                            double[] gridRes, double geomXMin, double geomYMin,
                            double geomXMax, double geomYMax, int totalRecordCount,
                            int featureCount, String spatialEntryName) {
        this.spxPath = spxPath;
        this.channel = channel;
        this.version = version;
        this.pageSize = pageSize;
        this.pageCount = pageCount;
        this.maxPerPages = maxPerPages;
        this.objectIdSize = objectIdSize;
        this.valueSize = valueSize;
        this.nonLeafPageHeaderSize = nonLeafPageHeaderSize;
        this.leafPageHeaderSize = leafPageHeaderSize;
        this.offsetFirstValInPage = offsetFirstValInPage;
        this.valueCountInIdx = valueCountInIdx;
        this.indexDepth = indexDepth;
        this.gridRes = gridRes;
        this.geomXMin = geomXMin;
        this.geomYMin = geomYMin;
        this.geomXMax = geomXMax;
        this.geomYMax = geomYMax;
        this.totalRecordCount = totalRecordCount;
        this.featureCount = featureCount;
        this.spatialEntryName = spatialEntryName;
    }

    // ==================================================================
    // 打开
    // ==================================================================

    /**
     * 打开某个要素类的空间索引。
     *
     * <p>会在 {@code tablePath} 的同目录下寻找同前缀的 {@code .gdbindexes} 与
     * {@code .spx}。缺任一文件即认为"没有可用的空间索引",返回 {@code null} ——
     * 这是给调用方"退回全表扫描"用的正常信号,不是异常。
     *
     * <p>此外,若 {@code .spx} 本身格式合法、但按其自身声明无法安全使用
     * (GDAL 也会降级处理的两种情况:格网分辨率非法、或 {@code nIndexDepth}
     * 声称 1 而实际指向的是索引页),同样返回 {@code null},与 GDAL 的降级行为一致。
     *
     * @param tablePath 要素类主表路径,形如 {@code xxx.gdb/a00000009.gdbtable}
     *                  (同目录下找 {@code a00000009.gdbindexes} 与 {@code a00000009.spx})
     * @return 索引对象;若不存在可用的空间索引文件则返回 {@code null}
     * @throws GdbFormatException 文件存在但结构非法
     * @throws GdbIoException      读取失败
     */
    public static GdbSpatialIndex open(Path tablePath) {
        if (tablePath == null) {
            throw new GdbFormatException("tablePath 不能为空");
        }
        Path table = tablePath.toAbsolutePath().normalize();
        Path dir = table.getParent();
        String base = stripExtension(table.getFileName().toString());
        if (dir == null) {
            return null;
        }
        Path gdbIndexesPath = dir.resolve(base + ".gdbindexes");
        Path spxPath = dir.resolve(base + ".spx");

        // 没有 .gdbindexes 或没有 .spx —— 没有空间索引,直接降级。
        if (!Files.isRegularFile(gdbIndexesPath) || !Files.isRegularFile(spxPath)
                || !Files.isRegularFile(table)) {
            return null;
        }

        // ---- 1) 解析 .gdbindexes(索引清单,用于确认索引描述符合法) ----
        // 对应 pyopenfilegdb/_gdbindex.py::read_gdbindexes 与
        // filegdbtable.cpp::FileGDBTable::GetIndexCount。
        final List<IndexEntry> entries = readIndexEntries(gdbIndexesPath);

        // ---- 2) 打开 .gdbtable 取几何字段(格网分辨率 + 全表包围盒) ----
        // .spx 的键是把坐标按 gridRes 量化后拼出来的,没有 gridRes 就无法查询;
        // 全表包围盒用于格网分辨率的可用性自检(对应 GDAL Init() 里那段中心点检查)。
        double[] grid;
        double gxMin;
        double gyMin;
        double gxMax;
        double gyMax;
        int totalRecordCount;
        int validRecordCount;
        String geomFieldName;
        // 表对象只用于读取元信息,取完立刻关闭,避免占用文件句柄。
        GdbTable tableObj = GdbTable.open(table);
        try {
            GdbGeomField geomField = tableObj.getGeomField();
            if (geomField == null) {
                // 没有几何字段的表不可能有空间索引。
                return null;
            }
            grid = geomField.getSpatialIndexGridResolutions();
            gxMin = geomField.getXmin();
            gyMin = geomField.getYmin();
            gxMax = geomField.getXmax();
            gyMax = geomField.getYmax();
            totalRecordCount = tableObj.getRecordCount();
            validRecordCount = tableObj.getValidRecordCount();
            geomFieldName = tableObj.getGeometryFieldName();
        } finally {
            tableObj.close();
        }

        // ---- 3) 打开 .spx 并解析 trailer ----
        FileChannel channel = openChannel(spxPath);
        boolean ok = false;
        try {
            long fileSize = sizeOf(channel, spxPath);
            if (fileSize < V1_TRAILER_SIZE) {
                throw new GdbFormatException(
                        spxPath.getFileName() + ": 文件不足 " + V1_TRAILER_SIZE + " 字节,不是 .spx");
            }

            // 最末 4 字节是版本号。对应 filegdbindex.cpp::ReadTrailer 开头那段。
            byte[] last4 = readAt(channel, spxPath, fileSize - 4, 4);
            int version = (int) LittleEndian.getUInt32(last4, 0);
            if (version != 1 && version != 2) {
                throw new GdbFormatException(
                        spxPath.getFileName() + ": 未知的 .spx 版本 " + version);
            }
            int pageSize = version == 1 ? PAGE_SIZE_V1 : PAGE_SIZE_V2;
            int trailerSize = version == 1 ? V1_TRAILER_SIZE : V2_TRAILER_SIZE;
            int pageCount = (int) ((fileSize - trailerSize) / pageSize);

            byte[] tr = readAt(channel, spxPath, fileSize - trailerSize, trailerSize);
            int valueSize = tr[0] & 0xFF;
            int objectIdSize = version == 1 ? 4 : 8;
            int nonLeafHeader = version == 1 ? 8 : 12;
            int leafHeader = version == 1 ? 12 : 20;

            // 对应 ReadTrailer 里对 nMaxPerPages / m_nOffsetFirstValInPage 的计算。
            // 注意:valueSize 此刻可能非法(来自不可信文件),先按原式算出结构参数,
            // 再在下面像 GDAL Init() 那样单独校验 valueSize == 8。
            int maxPerPages = (pageSize - leafHeader) / (objectIdSize + valueSize);
            if (maxPerPages <= 0) {
                throw new GdbFormatException(
                        spxPath.getFileName() + ": 非法的每页条目数 " + maxPerPages);
            }
            int offsetFirstVal = leafHeader + maxPerPages * objectIdSize;

            long magic1 = LittleEndian.getUInt32(tr, 2);
            if (magic1 != 1) {
                throw new GdbFormatException(
                        spxPath.getFileName() + ": trailer 魔数 " + magic1 + " != 1");
            }
            int depth = (int) LittleEndian.getUInt32(tr, 6);
            if (depth < 1 || depth > MAX_DEPTH + 1) {
                throw new GdbFormatException(
                        spxPath.getFileName() + ": 非法的索引层数 " + depth);
            }

            long valueCount;
            if (version == 1) {
                valueCount = LittleEndian.getUInt32(tr, 10);
                if (valueCount == 0 && depth == 1) {
                    // 某些文件(如 issue #5888 的 .spx)在 trailer 里写 0,真实值在
                    // 文件偏移 4 处。对应 ReadTrailer 里那段回读。
                    byte[] b4 = readAt(channel, spxPath, 4, 4);
                    valueCount = LittleEndian.getUInt32(b4, 0);
                } else if (valueCount < maxPerPages && depth > 1) {
                    // 索引头登记的要素数不可信(普通属性索引的已知怪癖,GDAL 对
                    // .spx 也走同一段代码)—— 放弃使用该索引,退回全表扫描。
                    return null;
                }
            } else {
                valueCount = LittleEndian.getUInt64(tr, 10);
            }

            // 对应 Init() 里的 returnErrorIf(m_nValueSize != sizeof(uint64_t))。
            if (valueSize != 8) {
                throw new GdbFormatException(
                        spxPath.getFileName() + ": 索引值宽度为 " + valueSize
                                + " 字节(空间索引应为 8),可能不是空间索引");
            }

            // ---- 4) 格网分辨率可用性自检(对应 Init() 里那段) ----
            if (grid.length == 0 || !(grid[0] > 0) || Double.isNaN(gxMin)
                    || !isPositiveInt(scaledCoord(0.5 * (gxMin + gxMax), grid, 0))
                    || !isPositiveInt(scaledCoord(0.5 * (gyMin + gyMax), grid, 0))) {
                // 真实案例:某些图层的 gridRes[0] 是 1.6e-312 这类非法值,GDAL 同样不用它的 .spx。
                return null;
            }

            // ---- 5) 探测"声称 depth==1、实际却是内部页"的坏文件(对应 Init() 里那段) ----
            if (depth == 1 && looksLikeNonFeaturePage(channel, spxPath, pageCount, objectIdSize,
                    maxPerPages, totalRecordCount)) {
                return null;
            }

            // ---- 6) .gdbindexes 里挑出"空间索引"那条(仅诊断用) ----
            String spatialEntryName = null;
            for (IndexEntry e : entries) {
                if (e.fieldName().equalsIgnoreCase(geomFieldName)
                        || e.name().toLowerCase(java.util.Locale.ROOT).startsWith("shape_")) {
                    spatialEntryName = e.name();
                    break;
                }
            }

            // featureCount 优先取"索引头登记的要素数";为 0(不可信/未登记)时退回
            // 表格的有效记录数。对应接口说明里的"来自 .gdbindexes / 索引头"。
            int featureCount;
            if (valueCount > 0 && valueCount <= Integer.MAX_VALUE) {
                featureCount = (int) valueCount;
            } else {
                featureCount = validRecordCount;
            }

            GdbSpatialIndex index = new GdbSpatialIndex(spxPath, channel, version, pageSize,
                    pageCount, maxPerPages, objectIdSize, valueSize, nonLeafHeader, leafHeader,
                    offsetFirstVal, valueCount, depth, grid, gxMin, gyMin, gxMax, gyMax,
                    totalRecordCount, featureCount, spatialEntryName);
            ok = true;
            return index;
        } finally {
            if (!ok) {
                closeQuietly(channel);
            }
        }
    }

    // ==================================================================
    // 公开查询 API
    // ==================================================================

    /**
     * 查询与给定矩形<b>相交</b>的候选行号(0 基,升序,可能含假阳性 —— 索引是粗筛)。
     *
     * <p>等价于 GDAL 构造一个 {@code FileGDBSpatialIndexIterator} 后循环
     * {@code GetNextRowSortedByFID()};候选行号可直接喂给
     * {@code GdbTable.readFeature(int)} 再逐条做精确的几何相交判断。
     *
     * @param xmin 查询矩形 X 最小
     * @param ymin 查询矩形 Y 最小
     * @param xmax 查询矩形 X 最大
     * @param ymax 查询矩形 Y 最大
     * @return 候选行号数组(0 基、升序、已去重);空结果返回长度 0 的数组
     */
    public int[] search(double xmin, double ymin, double xmax, double ymax) {
        SearchCursor cursor = new SearchCursor(xmin, ymin, xmax, ymax);
        List<Integer> rows = new ArrayList<>();
        int row;
        while ((row = cursor.nextRow()) >= 0) {
            rows.add(row);
        }
        if (rows.isEmpty()) {
            return new int[0];
        }
        // 对应 GetNextRowSortedByFID():先累到 vector 再排序,比 unordered_set 快;
        // 排序后跳过相邻重复,保证"不返回连续相同的 FID"。
        Collections.sort(rows);
        int[] out = new int[rows.size()];
        int n = 0;
        int prev = -1;
        for (int r : rows) {
            if (n > 0 && r == prev) {
                continue;
            }
            out[n++] = r;
            prev = r;
        }
        if (n == out.length) {
            return out;
        }
        int[] trimmed = new int[n];
        System.arraycopy(out, 0, trimmed, 0, n);
        return trimmed;
    }

    /**
     * 索引里登记的总要素数(来自 {@code .gdbindexes} / {@code .spx} 索引头)。
     *
     * <p>取 {@code .spx} trailer 的 {@code m_nValueCountInIdx};若该值为 0
     * (未见登记或不可信),退回表格的有效记录数。
     *
     * @return 要素数
     */
    public int getFeatureCount() {
        return featureCount;
    }

    /** 关闭底层文件句柄。重复调用安全。 */
    public void close() {
        closeQuietly(channel);
    }

    /** 索引的 {@code .spx} 文件路径。 */
    public Path getSpxPath() {
        return spxPath;
    }

    /**
     * 该要素类<b>是否带有空间索引文件</b>(只做文件存在性检查,不解析内容)。
     *
     * <p>给 {@code OLCFastSpatialFilter} 这类"只问有没有、不想付解析代价"的场合用:
     * {@link #open(Path)} 会真正解析 {@code .gdbindexes} 与 {@code .spx}、还会开一次
     * 主表拿格网分辨率,当能力查询用太重。
     *
     * <p>注意:返回 {@code true} 只代表文件齐全,不代表 {@link #open} 一定成功
     * ({@code open} 还会做格网分辨率合法性等自检,失败时返回 {@code null})。
     *
     * @param tablePath 要素类主表路径,形如 {@code xxx.gdb/a00000009.gdbtable}
     * @return {@code .gdbindexes} 与 {@code .spx} 都存在时返回 {@code true}
     */
    public static boolean exists(Path tablePath) {
        if (tablePath == null) {
            return false;
        }
        Path table = tablePath.toAbsolutePath().normalize();
        Path dir = table.getParent();
        if (dir == null) {
            return false;
        }
        String base = stripExtension(table.getFileName().toString());
        return Files.isRegularFile(table)
                && Files.isRegularFile(dir.resolve(base + ".gdbindexes"))
                && Files.isRegularFile(dir.resolve(base + ".spx"));
    }

    @Override
    public String toString() {
        return "<GdbSpatialIndex " + spxPath.getFileName() + " v" + version
                + " pageSize=" + pageSize + " pages=" + pageCount + " depth=" + indexDepth
                + " values=" + valueCountInIdx + " grids=" + gridRes.length
                + (spatialEntryName != null ? " entry=" + spatialEntryName : "") + ">";
    }

    // ==================================================================
    // 查询游标:一次 search() 一份状态,保证并发安全
    // ==================================================================

    /**
     * 单次查询的游标状态。
     *
     * <p>逐行对照 {@code filegdbindex.cpp::FileGDBSpatialIndexIteratorImpl} 与
     * 其基类 {@code FileGDBIndexIteratorBase}:把 C++ 里散在对象成员上的
     * {@code iFirstPageIdx/iLastPageIdx/iCurPageIdx/nSubPagesCount/nLastPageAccessed}
     * 等游标状集中到这里,使 {@link #search} 之间互不干扰。
     */
    private final class SearchCursor {

        /** 查询矩形(X 不夹取;Y 在 GDAL 里会按投影做夹取,本实现不启用,见类注释)。 */
        private final double filterMinX;
        private final double filterMinY;
        private final double filterMaxX;
        private final double filterMaxY;

        /** 各层的候选区间端点(闭区间,层 index 0 为根)。-1 表示未初始化。 */
        private final int[] firstPageIdx = new int[MAX_DEPTH];
        private final int[] lastPageIdx = new int[MAX_DEPTH];
        private final int[] curPageIdx = new int[MAX_DEPTH];
        /** 各层当前页引用的子页/要素数。 */
        private final int[] subPagesCount = new int[MAX_DEPTH];
        /** 各层上次访问过的页号(用于识别父页里的重复项)。 */
        private final long[] lastPageAccessed = new long[MAX_DEPTH];
        /** 各层当前加载的整页字节。 */
        private final byte[][] page = new byte[MAX_DEPTH][];
        /** 当前加载的叶(要素)页字节。 */
        private byte[] featurePage;

        private boolean eof;
        private int curFeatureInPage;
        private int featuresInPage;

        /** 当前 grid 层级与 X 格号区间;逐格推进,格用尽后放大 grid。 */
        private int gridNo;
        private int curX;
        private int maxX;

        /** 当前 X 格对应的查询键区间(64 位有符号比较)。 */
        private long minVal;
        private long maxVal;

        SearchCursor(double xmin, double ymin, double xmax, double ymax) {
            this.filterMinX = xmin;
            this.filterMinY = ymin;
            this.filterMaxX = xmax;
            this.filterMaxY = ymax;
            // 对应基类构造函数里的 memset:游标端点初值全 -1。
            for (int i = 0; i < MAX_DEPTH; i++) {
                firstPageIdx[i] = -1;
                lastPageIdx[i] = -1;
                curPageIdx[i] = -1;
                subPagesCount[i] = 0;
                lastPageAccessed[i] = 0;
            }
            this.curFeatureInPage = -1;
            // 对应 Impl 构造函数末尾的 ResetInternal 之前的状态;真正的定位在
            // resetInternal() 里完成。
            resetInternal();
        }

        // ---------------- 坐标量化 ----------------

        /** 对应 {@code GetScaledCoord}:把坐标按 grid 层级量化成格号。 */
        private double scaled(double coord) {
            return scaledCoord(coord, gridRes, gridNo);
        }

        /** 夹取到 [0, INT_MAX] 的格号,对应 C++ 里的 min(max(0.0,x),INT_MAX) 转 int。 */
        private int clampToInt(double v) {
            return (int) Math.min(Math.max(0.0, v), (double) Integer.MAX_VALUE);
        }

        // ---------------- 定位与迭代 ----------------

        /**
         * 对应 {@code ResetInternal()}:从最细的 grid 0 开始,把 X 格号区间设为
         * 查询矩形在 grid 0 上的投影,然后准备好第一个 X 格的范围查询。
         */
        private void resetInternal() {
            gridNo = 0;
            if (gridRes.length == 0 || !(gridRes[0] > 0)) {
                // 走到这里说明 open() 的可用性自检放过了它,理论上不会发生。
                eof = true;
                return;
            }
            curX = clampToInt(scaled(filterMinX));
            maxX = clampToInt(scaled(filterMaxX));
            readNewXRange();
        }

        /**
         * 对应 {@code ReadNewXRange()}:把"当前 grid 层级 + 当前 X 格号 + Y 区间"
         * 编码成 64 位区间 {@code [minVal,maxVal]},再定位到该区间的候选子页。
         */
        private void readNewXRange() {
            // 复合键 = (gridNo << 62) | (xScaled << 31) | yScaled。
            // gridNo = 2 时最高位为 1,作为有符号 64 位整体看会变小,故下面的 min/max 需要交换。
            long yLo = clampToInt(scaled(filterMinY));
            long yHi = clampToInt(scaled(filterMaxY));
            long v1 = ((long) gridNo << 62) | ((long) curX << 31) | yLo;
            long v2 = ((long) gridNo << 62) | ((long) curX << 31) | yHi;
            if (gridNo < 2) {
                minVal = v1;
                maxVal = v2;
            } else {
                // 负号导致顺序反转。
                minVal = v2;
                maxVal = v1;
            }

            if (valueCountInIdx > 0) {
                if (indexDepth == 1) {
                    firstPageIdx[0] = 0;
                    lastPageIdx[0] = 0;
                } else if (!findPages(0, 1)) {
                    eof = true;
                    return;
                }
            }
            baseReset();
        }

        /**
         * 对应基类 {@code FileGDBIndexIteratorBase::Reset()}:把根层的候选区间端点
         * 装进游标,清掉其余层的状态。
         */
        private void baseReset() {
            // 空间索引总是升序扫描。
            curPageIdx[0] = firstPageIdx[0] - 1;
            for (int i = 1; i < MAX_DEPTH; i++) {
                firstPageIdx[i] = -1;
                lastPageIdx[i] = -1;
                curPageIdx[i] = -1;
            }
            for (int i = 0; i < MAX_DEPTH; i++) {
                lastPageAccessed[i] = 0;
            }
            curFeatureInPage = 0;
            featuresInPage = 0;
            eof = (valueCountInIdx == 0);
        }

        /**
         * 对应 {@code FileGDBSpatialIndexIteratorImpl::FindPages()}:读入某层的页,
         * 用二分把它上面有序的键区间收缩成"可能与查询相交"的子页下标区间。
         */
        private boolean findPages(int level, long pageNumber) {
            byte[] p = readPage(pageNumber);
            page[level] = p;

            firstPageIdx[level] = -1;
            lastPageIdx[level] = -1;

            int count = (int) LittleEndian.getUInt32(p, objectIdSize);
            if (count == 0 || count > maxPerPages) {
                throw new GdbFormatException(
                        spxPath.getFileName() + ": 页 " + pageNumber + " 的子页数 " + count
                                + " 不合理(上限 " + maxPerPages + ")");
            }
            subPagesCount[level] = count;

            if (LittleEndian.getInt64(p, offsetFirstValInPage) > maxVal) {
                // 页内最小值已经超过查询上界:只有第 0 个子页可能相关。
                firstPageIdx[level] = 0;
                long leaf0 = version == 1
                        ? LittleEndian.getUInt32(p, leafPageHeaderSize)
                        : LittleEndian.getUInt64(p, leafPageHeaderSize);
                lastPageIdx[level] = (count == 1 && leaf0 == 0) ? 0 : 1;
            } else {
                int[] mm = {-1, -1};
                if (!findMinMaxIdx(p, offsetFirstValInPage, count, mm)) {
                    firstPageIdx[level] = count;
                    lastPageIdx[level] = count;
                } else {
                    firstPageIdx[level] = mm[0];
                    lastPageIdx[level] = mm[1];
                    if (lastPageIdx[level] < count) {
                        // 候选值可能延伸到下一个子页。
                        lastPageIdx[level]++;
                    }
                }
            }
            return true;
        }

        /**
         * 对应 {@code FindMinMaxIdx()}:在有序的 64 位键数组里二分,找出
         * "最后一个 &lt;= maxVal 的下标"与"第一个 &gt;= minVal 的下标"。
         *
         * @param base   页缓冲
         * @param off    键数组在页内的起始偏移
         * @param nVals  键个数
         * @param out    {@code out[0] = minIdx, out[1] = maxIdx}
         * @return 区间非空为 true;区间为空(或没有键满足)返回 false
         */
        private boolean findMinMaxIdx(byte[] base, int off, int nVals, int[] out) {
            if (nVals <= 0) {
                return false;
            }
            // 找最后一个 <= maxVal 的下标。
            int nMinIdx = 0;
            int nMaxIdx = nVals - 1;
            while (nMaxIdx - nMinIdx >= 2) {
                int idx = (nMinIdx + nMaxIdx) / 2;
                long v = LittleEndian.getInt64(base, off + idx * 8);
                if (v <= maxVal) {
                    nMinIdx = idx;
                } else {
                    nMaxIdx = idx;
                }
            }
            while (LittleEndian.getInt64(base, off + nMaxIdx * 8) > maxVal) {
                nMaxIdx--;
                if (nMaxIdx < 0) {
                    return false;
                }
            }
            out[1] = nMaxIdx;

            // 找第一个 >= minVal 的下标。
            nMinIdx = 0;
            while (nMaxIdx - nMinIdx >= 2) {
                int idx = (nMinIdx + nMaxIdx) / 2;
                long v = LittleEndian.getInt64(base, off + idx * 8);
                if (v >= minVal) {
                    nMaxIdx = idx;
                } else {
                    nMinIdx = idx;
                }
            }
            while (LittleEndian.getInt64(base, off + nMinIdx * 8) < minVal) {
                nMinIdx++;
                if (nMinIdx == nVals) {
                    return false;
                }
            }
            out[0] = nMinIdx;
            return true;
        }

        /**
         * 对应 {@code LoadNextFeaturePage()}:把叶(要素)页游标推进到下一页。
         *
         * @return 是否真的读到了一个非空的叶页
         */
        private boolean loadNextFeaturePage() {
            long pageNumber;
            if (indexDepth == 1) {
                if (curPageIdx[0] == lastPageIdx[0]) {
                    return false;
                }
                curPageIdx[0]++;
                pageNumber = 1;
            } else {
                if (!loadNextPage(indexDepth - 2)) {
                    return false;
                }
                pageNumber = readPageNumber(indexDepth - 2);
                if (pageNumber < 2) {
                    throw new GdbFormatException(
                            spxPath.getFileName() + ": 叶页号 " + pageNumber + " 非法");
                }
            }

            byte[] p = readPage(pageNumber);
            featurePage = p;
            long nFeatures = LittleEndian.getUInt32(p, objectIdSize);
            if (nFeatures > maxPerPages) {
                throw new GdbFormatException(
                        spxPath.getFileName() + ": 页 " + pageNumber + " 的要素数 " + nFeatures
                                + " 超过上限 " + maxPerPages);
            }
            featuresInPage = (int) nFeatures;
            curFeatureInPage = 0;
            return nFeatures != 0;
        }

        /**
         * 对应基类 {@code ReadPageNumber()}:读出某层当前页里第
         * {@code curPageIdx[level]} 个子页号。遇到与"上次访问页号"相同时会推移父层
         * (父页里偶有重复项)。
         */
        private long readPageNumber(int level) {
            long pageNumber = pageNumberAt(level);
            if (pageNumber == lastPageAccessed[level]) {
                if (!loadNextPage(level)) {
                    return 0;
                }
                pageNumber = pageNumberAt(level);
            }
            lastPageAccessed[level] = pageNumber;
            if (pageNumber < 2) {
                throw new GdbFormatException(
                        spxPath.getFileName() + ": 子页号 " + pageNumber + " 非法");
            }
            return pageNumber;
        }

        /** 读 {@code page[level]} 里第 {@code curPageIdx[level]} 个子页号。 */
        private long pageNumberAt(int level) {
            int off = nonLeafPageHeaderSize + curPageIdx[level] * objectIdSize;
            return version == 1
                    ? LittleEndian.getUInt32(page[level], off)
                    : LittleEndian.getUInt64(page[level], off);
        }

        /**
         * 对应基类 {@code LoadNextPage()}:在某层内前移一格;走到区间端点时,
         * 递归地把上一层也前移,再据此重新定位本层。
         */
        private boolean loadNextPage(int level) {
            byte[] p = page[level];
            if (p == null) {
                throw new GdbFormatException(
                        spxPath.getFileName() + ": 层 " + level + " 的页尚未加载");
            }
            if (curPageIdx[level] == lastPageIdx[level]) {
                if (level == 0 || !loadNextPage(level - 1)) {
                    return false;
                }
                long pageNumber = readPageNumber(level - 1);
                findPages(level, pageNumber);
                curPageIdx[level] = firstPageIdx[level];
            } else {
                curPageIdx[level]++;
            }
            return true;
        }

        /**
         * 对应 {@code FileGDBSpatialIndexIteratorImpl::GetNextRow()}:推进游标,
         * 返回下一个候选行号(0 基);没有更多时返回 -1。
         */
        private int nextRow() {
            if (eof) {
                return -1;
            }
            while (true) {
                if (curFeatureInPage >= featuresInPage) {
                    int[] mm = {-1, -1};
                    if (!loadNextFeaturePage()
                            || !findMinMaxIdx(featurePage, offsetFirstValInPage, featuresInPage, mm)
                            || mm[0] > mm[1]) {
                        // 当前 X 格没有候选:先在同一 grid 内推进 X 格。
                        if (curX < maxX) {
                            curX++;
                            readNewXRange();
                            continue;
                        }
                        // X 格用尽:放大到更粗的 grid 层级再试。
                        if (gridNo + 1 < gridRes.length && gridRes[gridNo + 1] > 0) {
                            gridNo++;
                            curX = clampToInt(scaled(filterMinX));
                            maxX = clampToInt(scaled(filterMaxX));
                            readNewXRange();
                            continue;
                        }
                        eof = true;
                        return -1;
                    }
                    curFeatureInPage = mm[0];
                    featuresInPage = mm[1] + 1;
                }

                // v1 的 fid 是 4 字节,v2 是 8 字节
                long fid = version == 1
                        ? LittleEndian.getUInt32(
                                featurePage, leafPageHeaderSize + curFeatureInPage * 4)
                        : LittleEndian.getUInt64(
                                featurePage, leafPageHeaderSize + curFeatureInPage * 8);
                curFeatureInPage++;
                if (fid < 1 || fid > (long) totalRecordCount) {
                    eof = true;
                    return -1;
                }
                return (int) (fid - 1);
            }
        }

        /** 用外层只读句柄读第 {@code pageNumber}(1 基)页。 */
        private byte[] readPage(long pageNumber) {
            if (pageNumber < 1) {
                throw new GdbFormatException(spxPath.getFileName() + ": 页号 " + pageNumber + " 非法");
            }
            long off = (pageNumber - 1) * (long) pageSize;
            return readAt(channel, spxPath, off, pageSize);
        }
    }

    // ==================================================================
    // .gdbindexes 解析(逐行对照 _gdbindex.py::read_gdbindexes)
    // ==================================================================

    /** {@code .gdbindexes} 里的一条索引描述符。 */
    private static final class IndexEntry {

        private final String name;
        private final String expression;

        IndexEntry(String name, String expression) {
            this.name = name;
            this.expression = expression;
        }

        String name() {
            return name;
        }

        /**
         * 剥掉 {@code LOWER(...)} 包裹后的字段名。
         *
         * <p>对应 GDAL {@code FileGDBIndex::GetFieldNameFromExpression}
         * (filegdbindex.cpp:47)。
         */
        String fieldName() {
            if (expression.length() > 7
                    && expression.regionMatches(true, 0, "LOWER(", 0, 6)
                    && expression.endsWith(")")) {
                return expression.substring(6, expression.length() - 1);
            }
            return expression;
        }
    }

    /**
     * 解析与 {@code gdbtable_path} 同名的 {@code .gdbindexes}。
     *
     * <p>对应 GDAL {@code FileGDBTable::GetIndexCount()}(filegdbtable.cpp:2497-2683)
     * 与 pyopenfilegdb/_gdbindex.py::read_gdbindexes。
     *
     * <p>真实文件里会出现<b>被删除但没清干净的描述符</b> —— 此时 {@code magic2}
     * 被当成"名字长度"复用,正文是垃圾。GDAL 专门写了容错分支(约 2596-2625 行),
     * 这里的处理与它一致:识别出这种描述符后跳到它声明的长度之后,继续读下一条。
     *
     * @param gdbIndexesPath {@code .gdbindexes} 路径
     * @return 描述符列表(可能为空);损坏的条目被跳过
     */
    private static List<IndexEntry> readIndexEntries(Path gdbIndexesPath) {
        byte[] data;
        try {
            data = Files.readAllBytes(gdbIndexesPath);
        } catch (IOException e) {
            throw new GdbIoException(
                    "无法读取 " + gdbIndexesPath.getFileName() + ": " + e.getMessage(), e);
        }
        if (data.length > MAX_GDBINDEXES_BYTES) {
            throw new GdbFormatException(
                    gdbIndexesPath.getFileName() + ": 索引描述文件异常大");
        }
        if (data.length < 4) {
            throw new GdbFormatException(
                    gdbIndexesPath.getFileName() + ": 文件不足 4 字节");
        }
        long count = LittleEndian.getUInt32(data, 0);
        if (count == GDBINDEXES_V9_MAGIC) {
            // FileGDB v9 的索引结构,GDAL 也没实现,本库同样不支持。
            throw new GdbFormatException(
                    gdbIndexesPath.getFileName() + ": 这是 FileGDB v9 的索引结构(首字段 0x"
                            + Long.toHexString(count) + "),本库只支持 ArcGIS 10.x(v10)索引");
        }
        if (count > MAX_INDEX_COUNT) {
            throw new GdbFormatException(
                    gdbIndexesPath.getFileName() + ": 索引个数 " + count + " 不合理");
        }

        ByteReader r = new ByteReader(data, 4, data.length);
        List<IndexEntry> out = new ArrayList<>();
        for (long i = 0; i < count; i++) {
            IndexEntry entry = readOneIndex(r, gdbIndexesPath);
            if (entry != null) {
                out.add(entry);
            }
        }
        return out;
    }

    /**
     * 读一条索引描述符。
     *
     * @return 描述符;若是"已损坏/已删除"的条目则返回 {@code null}(按 GDAL 的做法跳过)
     */
    private static IndexEntry readOneIndex(ByteReader r, Path gdbIndexesPath) {
        String fname = gdbIndexesPath.getFileName().toString();

        // --- 索引名 ---
        long nameLen = r.u32();
        if (nameLen > MAX_NAME_CHARS) {
            throw new GdbFormatException(fname + ": 索引名长度 " + nameLen + " 不合理");
        }
        String name = r.utf16Units((int) nameLen);

        // --- 4 个魔数(u16 + u32 + u16 + u32 = 12 字节)---
        r.need(12);
        int base = r.position();
        long magic2 = LittleEndian.getUInt32(r.data(), base + 2);
        int magic3 = LittleEndian.getUInt16(r.data(), base + 6);

        if (!isMagicOk(magic2, magic3)) {
            // 损坏/已删除的描述符:magic2 的位置实际上是"名字长度"。
            // 与 GDAL 一样,顺着它声明的长度跳过去,再吃 2 字节魔数,继续下一条。
            long colCount = magic2;
            if (colCount > MAX_NAME_CHARS) {
                throw new GdbFormatException(fname + ": 损坏描述符的长度 " + colCount + " 不合理");
            }
            r.skip(2 + 4);              // magic1 + magic2 所在的 4 字节
            r.skip(2 * (int) colCount); // 被复用的"名字"
            r.skip(2);                  // magic5
            return null;
        }
        r.skip(12);

        // --- 表达式(列名)---
        long colLen = r.u32();
        if (colLen > MAX_NAME_CHARS) {
            throw new GdbFormatException(fname + ": 表达式长度 " + colLen + " 不合理");
        }
        String expression = r.utf16Units((int) colLen);
        r.skip(2); // magic5

        return new IndexEntry(name, expression);
    }

    /** {@code (magic2, magic3)} 是否属于合法组合,对应 {@code MAGIC_OK}。 */
    private static boolean isMagicOk(long magic2, int magic3) {
        for (int[] pair : MAGIC_OK) {
            if (magic2 == pair[0] && magic3 == pair[1]) {
                return true;
            }
        }
        return false;
    }

    // ==================================================================
    // 坐标量化 / 自检辅助
    // ==================================================================

    /**
     * 对应 {@code GetScaledCoord}:把坐标按 grid 层级量化成格号。
     *
     * <p>公式:{@code (coord / gridRes[0] + (1 << 29)) / (gridRes[gridNo] / gridRes[0])}。
     * 其中 {@code (1 << 29)} 是 ESRI 的坐标偏移量,把可能出现负值的坐标平移成非负。
     */
    private static double scaledCoord(double coord, double[] gridRes, int gridNo) {
        return (coord / gridRes[0] + (1 << 29)) / (gridRes[gridNo] / gridRes[0]);
    }

    /** 对应 GDAL {@code Init()} 里的 {@code IsPositiveInt} lambda。 */
    private static boolean isPositiveInt(double x) {
        return x >= 0 && x <= Integer.MAX_VALUE;
    }

    /**
     * 探测"声称 {@code nIndexDepth == 1}、实际却是内部页"的坏文件。
     *
     * <p>对应 {@code FileGDBSpatialIndexIteratorImpl::Init()} 里那段:读第 1 页,
     * 若它指向的 ID 全都落在 {@code [2, pageCount]} 内,说明这些其实是索引页号
     * 而不是要素号,该 {@code .spx} 的层数被写错了。此处<b>不抛异常</b>,只当作
     * "无法使用该索引"的信号(与 GDAL 的降级一致)。
     *
     * @return true 表示"确实像是坏文件",应放弃使用该索引
     */
    private static boolean looksLikeNonFeaturePage(FileChannel channel, Path spxPath,
                                                   int pageCount, int objectIdSize,
                                                   int maxPerPages, int totalRecordCount) {
        if (pageCount <= 0) {
            return false;
        }
        byte[] p1 = readAt(channel, spxPath, 0, probeBufferSize(objectIdSize, maxPerPages));
        long nFeatures = LittleEndian.getUInt32(p1, objectIdSize);
        if (nFeatures < 2 || nFeatures > maxPerPages
                || nFeatures >= (long) totalRecordCount / 10
                || pageCount <= nFeatures) {
            return false;
        }
        for (int i = 0; i < (int) nFeatures; i++) {
            long id = LittleEndian.getUInt32(p1, 8 + i * 4);
            if (id < 2 || id > pageCount) {
                return false;
            }
        }
        return true;
    }

    /**
     * 为读取"第 1 页的要素数 + 前若干 ID"准备一个足够长的缓冲。
     *
     * <p>这里只需要页头到 ID 数组前几项,不必整页;但为避免越界读,给足到
     * {@code 8 + maxPerPages*4} 与一页大小之间的较小值(至少覆盖要读的字段)。
     */
    private static int probeBufferSize(int objectIdSize, int maxPerPages) {
        int need = 8 + maxPerPages * 4;
        return Math.max(objectIdSize + 8, Math.min(need, 4096));
    }

    // ==================================================================
    // 底层 IO(位置读写,线程安全)
    // ==================================================================

    private static FileChannel openChannel(Path path) {
        try {
            return FileChannel.open(path, StandardOpenOption.READ);
        } catch (IOException e) {
            throw new GdbIoException("无法打开 " + path.getFileName() + ": " + e.getMessage(), e);
        }
    }

    private static long sizeOf(FileChannel channel, Path path) {
        try {
            return channel.size();
        } catch (IOException e) {
            throw new GdbIoException("无法获取 " + path.getFileName() + " 的大小: " + e.getMessage(), e);
        }
    }

    /**
     * 从指定位置读取 {@code len} 字节。
     *
     * <p>用 {@link FileChannel#read(ByteBuffer, long)} 的位置读写而非
     * {@code seek},因为位置读写在 JDK 中是线程安全的(C 层走 pread)。
     * 短读按 IO 失败处理。
     */
    private static byte[] readAt(FileChannel channel, Path path, long pos, int len) {
        if (len <= 0) {
            return new byte[0];
        }
        ByteBuffer bb = ByteBuffer.allocate(len);
        int total = 0;
        try {
            while (total < len) {
                int n = channel.read(bb, pos + total);
                if (n < 0) {
                    break;
                }
                total += n;
            }
        } catch (IOException e) {
            throw new GdbIoException(
                    "读取 " + path.getFileName() + " 失败(pos=" + pos + "): " + e.getMessage(), e);
        }
        if (total != len) {
            throw new GdbIoException(
                    path.getFileName() + ": 读越界/文件被截断 (pos=" + pos + ", 需要 " + len
                            + ", 实得 " + total + ")");
        }
        return bb.array();
    }

    private static void closeQuietly(FileChannel channel) {
        if (channel != null) {
            try {
                channel.close();
            } catch (IOException ignored) {
                // 关闭失败无需上抛
            }
        }
    }

    /** 去掉扩展名,得到 {@code a00000009} 这样的前缀。 */
    private static String stripExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }
}
