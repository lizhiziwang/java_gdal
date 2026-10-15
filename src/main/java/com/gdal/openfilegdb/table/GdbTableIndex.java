package com.gdal.openfilegdb.table;

import com.gdal.openfilegdb.exception.GdbFormatException;
import com.gdal.openfilegdb.exception.GdbVersionException;
import com.gdal.openfilegdb.exception.GdbWriteException;
import com.gdal.openfilegdb.io.LittleEndian;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * 要素类的 {@code .gdbtablx} 行索引文件的读写。
 *
 * <p><b>作用</b>:{@code .gdbtable} 本身是扁平的记录流,要随机访问第 N 条要素
 * 就必须知道它在 {@code .gdbtable} 里的 <b>绝对文件偏移</b>。本类就是这张
 * "行号 → 偏移"表,同时也是"记录是否存在"的唯一依据:
 * <ul>
 *   <li>偏移 == 0 → 该槽位为空(从未写过,或已被删除)</li>
 *   <li>偏移 != 0 → 记录体从该偏移开始</li>
 * </ul>
 *
 * <p>⚠️ {@code .gdbtable} 中并没有"块链"。这里出现的 block/page 指的是
 * <b>每 1024 条记录一页</b>的索引分页单位,与压缩无关。
 *
 * <h2>磁盘布局(version 3)</h2>
 * <pre>
 *   +0   uint32  version                 必须等于 .gdbtable 的版本(3)
 *   +4   uint32  n1024BlocksPresent      已分配的索引页数
 *   +8   int32   nTotalRecordCount       记录槽位总数(含空洞)
 *   +12  uint32  nTablxOffsetSize        每个偏移占用的字节数,取值 4/5/6
 *   +16          偏移表,n1024BlocksPresent * 1024 项,每项 nTablxOffsetSize 字节
 *   +16+...      16 字节 trailer:
 *                  uint32 nBitmapInt32Words  位图占多少个 32bit 字(0 = 无位图)
 *                  uint32 nBitsForBlockMap   位图覆盖多少页
 *                  uint32 n1024BlocksBis     冗余的页数(必须等于 +4 的值)
 *                  uint32 nLeadingNonZero32BitWords
 *                若 nBitmapInt32Words != 0,后面跟位图本体
 * </pre>
 *
 * <p><b>位图(base block map)</b>:要素被删除后,ArcGIS 可以选择把偏移表
 * "压实",并用位图记录哪些页仍然非空,使剩下的偏移连续存放。读取时:
 * <pre>
 *   iBlock     = row / 1024
 *   若位图[iBlock] == 0  →  该页整体为空,偏移为 0
 *   iCorrected = 已置位页数(在 iBlock 之前) * 1024 + row % 1024
 *   偏移位置    = 16 + nTablxOffsetSize * iCorrected
 * </pre>
 *
 * <p>对标 GDAL:
 * <ul>
 *   <li>{@code filegdbtable.cpp::FileGDBTable::ReadTableXHeaderV3} —— 读头 + trailer</li>
 *   <li>{@code filegdbtable.cpp::FileGDBTable::GetOffsetInTableForRow} —— 偏移查找</li>
 *   <li>{@code filegdbtable_write.cpp::FileGDBTable::WriteHeaderX} —— 写头</li>
 *   <li>{@code filegdbtable_write.cpp::FileGDBTable::Sync} —— 写 trailer</li>
 * </ul>
 */
public final class GdbTableIndex implements AutoCloseable {

    /** 头部 16 字节。 */
    public static final int HEADER_SIZE = 16;

    /** 尾部 trailer 16 字节。 */
    public static final int TRAILER_SIZE = 16;

    /** 每页(block)的记录数。注意<b>不是</b> 512,而是 1024。 */
    public static final int RECORDS_PER_BLOCK = 1024;

    private final Path path;

    /** 版本号(与 .gdbtable 一致)。 */
    private int version = 3;

    /** n1024BlocksPresent。 */
    private int blocksPresent;

    /** nTotalRecordCount(槽位总数,含已删除空洞)。 */
    private int totalRecordCount;

    /** 每个偏移占用的字节数(4/5/6)。 */
    private int offsetSize = 5;

    /** 偏移表:行号 → .gdbtable 内的偏移(0 表示空槽)。 */
    private long[] offsets = new long[0];

    /** base block map;{@code null} 表示"无位图"的简单布局。 */
    private byte[] blockMap;

    /** 位图覆盖的页数。 */
    private int blockMapBits;

    /** 是否有未落盘的改动。 */
    private boolean dirty;

    /** 就地写用的文件句柄;只读路径永远为 {@code null}。 */
    private RandomAccessFile raf;

    /** 磁盘上已物化的页数。 */
    private int pagesOnDisk;

    /**
     * 磁盘上的布局是否"偏移表按行号直接索引"(无位图)。
     *
     * <p>有位图的文件 <b>不能</b> 就地写 —— 那时第 row 行的位置是
     * {@code 16 + offsetSize * (该页之前的置位页数 * 1024 + row % 1024)},
     * 不是 {@code 16 + offsetSize * row}。这类文件交给整份重写
     * (重写成无位图布局)。
     */
    private boolean inplaceOk;

    private GdbTableIndex(Path path) {
        this.path = path;
    }

    // ==================================================================
    // 读
    // ==================================================================

    /**
     * 解析一个已存在的 {@code .gdbtablx}。
     *
     * @param path 文件路径
     * @return 索引对象
     * @throws GdbFormatException  结构非法
     * @throws GdbVersionException 版本不支持
     */
    public static GdbTableIndex open(Path path) {
        byte[] data;
        try {
            data = Files.readAllBytes(path);
        } catch (IOException e) {
            throw new GdbFormatException("无法读取 " + path.getFileName() + ": " + e.getMessage(), e);
        }
        GdbTableIndex self = new GdbTableIndex(path);
        if (data.length < HEADER_SIZE) {
            throw new GdbFormatException(path.getFileName() + ": 文件过短,不是 .gdbtablx");
        }
        self.version = (int) LittleEndian.getUInt32(data, 0);
        if (self.version != 3 && self.version != 4) {
            throw new GdbVersionException(
                    path.getFileName() + ": 不支持的 .gdbtablx 版本 " + self.version);
        }
        self.blocksPresent = (int) LittleEndian.getUInt32(data, 4);
        self.totalRecordCount = LittleEndian.getInt32(data, 8);
        self.offsetSize = (int) LittleEndian.getUInt32(data, 12);

        if (self.offsetSize < 4 || self.offsetSize > 6) {
            throw new GdbFormatException(
                    path.getFileName() + ": 非法的 offset size " + self.offsetSize);
        }
        if (self.totalRecordCount < 0) {
            throw new GdbFormatException(
                    path.getFileName() + ": 负的记录数 " + self.totalRecordCount);
        }
        int trailerOffset = HEADER_SIZE + self.offsetSize * RECORDS_PER_BLOCK * self.blocksPresent;
        if (self.blocksPresent != 0) {
            self.readTrailer(data, trailerOffset);
        } else if (self.totalRecordCount != 0) {
            throw new GdbFormatException(path.getFileName() + ": 页数为 0 但记录数不为 0");
        }
        self.readOffsetTable(data);
        self.inplaceOk = self.blockMap == null;
        self.pagesOnDisk = self.inplaceOk ? self.blocksPresent : 0;
        return self;
    }

    /** 解析尾部 16 字节 trailer,并按需加载 base block map。 */
    private void readTrailer(byte[] data, int trailerOffset) {
        if (trailerOffset + TRAILER_SIZE > data.length) {
            throw new GdbFormatException(path.getFileName() + ": trailer 越界 (off="
                    + trailerOffset + ", size=" + data.length + ")");
        }
        int nBitmapWords = (int) LittleEndian.getUInt32(data, trailerOffset);
        int nBitsForMap = (int) LittleEndian.getUInt32(data, trailerOffset + 4);
        int nBlocksBis = (int) LittleEndian.getUInt32(data, trailerOffset + 8);
        // trailerOffset + 12 是 nLeadingNonZero32BitWords,读取时用不到

        if (nBlocksBis != blocksPresent) {
            throw new GdbFormatException(path.getFileName() + ": trailer 页数 " + nBlocksBis
                    + " 与头部 " + blocksPresent + " 不一致");
        }
        if (nBitmapWords == 0) {
            // 没有位图:偏移表按行号直接索引(ArcGIS 最常见的情形)
            if (nBitsForMap != blocksPresent) {
                throw new GdbFormatException(path.getFileName() + ": 无位图时 nBitsForBlockMap ("
                        + nBitsForMap + ") 应等于页数 (" + blocksPresent + ")");
            }
            blockMap = null;
            return;
        }
        int nBytes = (nBitmapWords * 4 + 7) / 8;
        int body = trailerOffset + TRAILER_SIZE;
        if (body + nBytes > data.length) {
            throw new GdbFormatException(path.getFileName() + ": block map 越界");
        }
        blockMap = new byte[nBytes];
        System.arraycopy(data, body, blockMap, 0, nBytes);
        blockMapBits = nBitsForMap;
    }

    /** 把偏移表读进内存(每条记录一个整数)。 */
    private void readOffsetTable(byte[] data) {
        int n = totalRecordCount;
        int need = HEADER_SIZE + offsetSize * n;
        if (n > 0 && need > data.length) {
            // 文件被截断:只读到能读的部分,其余算空槽(容错)
            n = Math.max(0, (data.length - HEADER_SIZE) / offsetSize);
        }
        offsets = new long[totalRecordCount];
        if (blockMap == null) {
            // 简单布局:第 row 项的偏移在 16 + offsetSize * row
            for (int row = 0; row < n; row++) {
                offsets[row] = readOffsetAt(data, HEADER_SIZE + offsetSize * row);
            }
        } else {
            // 位图布局:只有置位的页才有条目
            int phys = 0;
            int blocks = (totalRecordCount + RECORDS_PER_BLOCK - 1) / RECORDS_PER_BLOCK;
            for (int iBlock = 0; iBlock < blocks; iBlock++) {
                if (!testBlockBit(iBlock)) {
                    continue;
                }
                int base = iBlock * RECORDS_PER_BLOCK;
                for (int k = 0; k < RECORDS_PER_BLOCK; k++) {
                    int row = base + k;
                    if (row >= totalRecordCount) {
                        break;
                    }
                    int off = HEADER_SIZE + offsetSize * phys;
                    if (off + offsetSize > data.length) {
                        break;
                    }
                    offsets[row] = readOffsetAt(data, off);
                    phys++;
                }
            }
        }
    }

    /** 读一个 offsetSize 字节的小端整数(GDAL 是零扩展)。 */
    private long readOffsetAt(byte[] data, int pos) {
        return LittleEndian.getUnsignedOfSize(data, pos, offsetSize);
    }

    private boolean testBlockBit(int iBlock) {
        if (iBlock >= blockMapBits) {
            return false;
        }
        return (blockMap[iBlock >> 3] & (1 << (iBlock & 7))) != 0;
    }

    // ==================================================================
    // 读接口
    // ==================================================================

    /** 槽位总数(含已删除空洞)。 */
    public int recordCount() {
        return totalRecordCount;
    }

    /** 已分配的索引页数。 */
    public int blocksPresent() {
        return blocksPresent;
    }

    /** 每个偏移占用的字节数。 */
    public int offsetSize() {
        return offsetSize;
    }

    /**
     * 第 {@code row} 条(0 基)在 {@code .gdbtable} 中的偏移;0 表示空槽。
     *
     * <p>等价于 GDAL {@code FileGDBTable::GetOffsetInTableForRow}。
     *
     * @param row 0 基行号
     * @return 文件偏移;越界或空槽返回 0
     */
    public long offsetForRow(int row) {
        if (row < 0 || row >= totalRecordCount) {
            return 0;
        }
        return offsets[row];
    }

    // ==================================================================
    // 写接口
    // ==================================================================

    /**
     * 新建一个空的 {@code .gdbtablx}(文件此时尚未落盘)。
     *
     * @param path       文件路径
     * @param offsetSize 每个偏移的字节数(4/5/6);5 字节可表示 1TB
     * @return 索引对象
     */
    public static GdbTableIndex create(Path path, int offsetSize) {
        if (offsetSize < 4 || offsetSize > 6) {
            throw new GdbWriteException("非法的 offsetSize: " + offsetSize);
        }
        GdbTableIndex self = new GdbTableIndex(path);
        self.offsetSize = offsetSize;
        self.blocksPresent = 0;
        self.totalRecordCount = 0;
        self.offsets = new long[0];
        self.blockMap = null;
        self.dirty = true;
        self.inplaceOk = true;
        self.pagesOnDisk = 0;
        return self;
    }

    /**
     * 把第 {@code row} 条的偏移设为 {@code offset}(0 表示删除该槽位)。
     *
     * <p>行号必须是"下一条待写"或其之前已存在的槽位,不允许跳跃式留洞 ——
     * 与 GDAL 的 append-only 语义一致。
     *
     * <p>对标 GDAL {@code FileGDBTable::CreateFeature} 中维护
     * {@code m_nTotalRecordCount} 的那段。
     *
     * @param row    0 基行号
     * @param offset 文件偏移
     */
    public void setOffset(int row, long offset) {
        if (row < 0) {
            throw new GdbWriteException("行号不能为负: " + row);
        }
        if (row > totalRecordCount) {
            throw new GdbWriteException(
                    "不能跳过行号: 当前有 " + totalRecordCount + " 条, 却要写第 " + row + " 条");
        }
        if (offset >= (1L << (8 * offsetSize))) {
            throw new GdbWriteException("偏移 " + offset + " 超出 " + offsetSize + " 字节表示范围");
        }
        if (row == totalRecordCount) {
            long[] grown = new long[totalRecordCount + 1];
            System.arraycopy(offsets, 0, grown, 0, totalRecordCount);
            grown[row] = offset;
            offsets = grown;
            totalRecordCount++;
        } else {
            offsets[row] = offset;
        }
        int pagesNeeded = (totalRecordCount + RECORDS_PER_BLOCK - 1) / RECORDS_PER_BLOCK;
        if (pagesNeeded > blocksPresent) {
            blocksPresent = pagesNeeded;
        }
        dirty = true;
    }

    /**
     * 把第 {@code row} 行的偏移 <b>就地</b> 写进 {@code .gdbtablx}(O(1))。
     *
     * <p>对应 GDAL 的两步(每条要素写入时都会被调一次):
     * <ul>
     *   <li>{@code FileGDBTable::SeekIntoTableXForNewFeature}
     *       ({@code filegdbtable_write.cpp}):新行落进还没分配的页时,
     *       <b>只补一页零</b>(不是整份重写),并把 trailer 标脏;</li>
     *   <li>{@code FileGDBTable::WriteFeatureOffset}:seek 到
     *       {@code HEADER_SIZE + row * offsetSize} 后覆盖 4/5/6 字节。</li>
     * </ul>
     *
     * <p>内存里的 {@code offsets} 在任何情况下都已经是对的,所以返回
     * {@code false} 不算失败(那是"这次没写文件,交给 {@link #flush()} 一起写"):
     * 文件还不存在时由本方法建出一个结构合法的空索引,文件带位图时直接放弃就地写。
     *
     * @param row 0 基行号
     * @return 是否真的落到了文件上
     */
    public boolean writeOffsetInPlace(int row) {
        if (row < 0 || row >= totalRecordCount) {
            throw new GdbWriteException("行号 " + row + " 越界(当前 " + totalRecordCount + " 条)");
        }
        if (!inplaceOk) {
            return false;
        }
        try {
            if (raf == null) {
                if (!Files.exists(path)) {
                    createEmptyFile();
                }
                raf = new RandomAccessFile(path.toFile(), "rw");
            }
            // 跨页:只补零页(GDAL 的 bWriteEmptyPageAtEnd 那一支)
            int page = row / RECORDS_PER_BLOCK + 1;
            if (page > pagesOnDisk) {
                int nNew = page - pagesOnDisk;
                int pageBytes = offsetSize * RECORDS_PER_BLOCK;
                raf.seek((long) HEADER_SIZE + (long) pageBytes * pagesOnDisk);
                raf.write(new byte[pageBytes * nNew]);
                // 新页覆盖了旧 trailer,立刻补一个合法的 trailer,并把头部的页数
                // (+4)同步改掉 —— 这两处必须一起动:读端要求 header 的
                // n1024BlocksPresent 与 trailer 的 n1024BlocksBis 一致。
                pagesOnDisk = page;
                blocksPresent = page;
                raf.seek(4);
                writeIntLE(raf, page);
                raf.seek((long) HEADER_SIZE + (long) pageBytes * page);
                raf.write(packTrailer(page));
            }
            raf.seek((long) HEADER_SIZE + (long) offsetSize * row);
            byte[] buf = new byte[offsetSize];
            long v = offsets[row];
            for (int i = 0; i < offsetSize; i++) {
                buf[i] = (byte) (v >>> (8 * i));
            }
            raf.write(buf);
            return true;
        } catch (IOException e) {
            throw new GdbWriteException("写入 " + path.getFileName() + " 失败: " + e.getMessage(), e);
        }
    }

    /** 建一个 0 页的空 {@code .gdbtablx}(头 16 字节 + trailer 16 字节)。 */
    private void createEmptyFile() throws IOException {
        // 对应 GDAL FileGDBTable::Create 里当场落盘的那部分
        java.io.ByteArrayOutputStream bos =
                new java.io.ByteArrayOutputStream(HEADER_SIZE + TRAILER_SIZE);
        bos.write(packHeader(version, 0, 0, offsetSize));
        bos.write(packTrailer(0));
        Files.write(path, bos.toByteArray());
        pagesOnDisk = 0;
    }

    private static byte[] packHeader(int version, int nPages, int count, int offsetSize) {
        byte[] b = new byte[HEADER_SIZE];
        putInt(b, 0, version);
        putInt(b, 4, nPages);
        putInt(b, 8, count);
        putInt(b, 12, offsetSize);
        return b;
    }

    /**
     * 16 字节 trailer(无位图的布局)。
     *
     * <p>{@code nBitmapInt32Words = 0}、{@code nLeadingNonZero32BitWords = 0},
     * 中间两个字段都等于页数 —— 与 GDAL {@code FileGDBTable::Sync} 在
     * {@code m_abyTablXBlockMap} 为空时写出来的内容一致。
     */
    private static byte[] packTrailer(int nPages) {
        byte[] b = new byte[TRAILER_SIZE];
        putInt(b, 0, 0);
        putInt(b, 4, nPages);
        putInt(b, 8, nPages);
        putInt(b, 12, 0);
        return b;
    }

    private static void putInt(byte[] b, int off, int v) {
        b[off] = (byte) v;
        b[off + 1] = (byte) (v >>> 8);
        b[off + 2] = (byte) (v >>> 16);
        b[off + 3] = (byte) (v >>> 24);
    }

    private static void writeIntLE(RandomAccessFile out, int v) throws IOException {
        out.write(new byte[]{(byte) v, (byte) (v >>> 8), (byte) (v >>> 16), (byte) (v >>> 24)});
    }

    /** 关掉就地写用的句柄(不落盘;落盘是 {@link #flush()} 的事)。 */
    @Override
    public void close() {
        if (raf != null) {
            try {
                raf.close();
            } catch (IOException ignored) {
                // 关闭失败无需上抛
            }
            raf = null;
        }
    }

    /**
     * 把当前状态写回磁盘。
     *
     * <p>采用 ArcGIS 最常见的"无位图"简单布局:偏移表按行号直接索引。
     * 好处是不需要维护 block map 的压实逻辑,代价是删除记录后不会回收索引空间。
     *
     * <p>⚠️ 本方法 <b>不在写入路径上</b>(那是 {@link #writeOffsetInPlace(int)}
     * 的 O(1) 就地写);它只在 {@code sync()} / {@code close()} 时跑一次,
     * 对应 GDAL {@code FileGDBTable::Sync} 里的
     * {@code m_bDirtyTableXHeader} / {@code m_bDirtyTableXTrailer} 两支。
     */
    public void flush() {
        if (!dirty && Files.exists(path)) {
            return;
        }
        int n = totalRecordCount;
        int pages = n > 0 ? (n + RECORDS_PER_BLOCK - 1) / RECORDS_PER_BLOCK : 0;
        blocksPresent = (n > 0 || pages > 0) ? Math.max(pages, 1) : 0;

        int tableBytes = offsetSize * RECORDS_PER_BLOCK * blocksPresent;
        byte[] buf = new byte[HEADER_SIZE + tableBytes + TRAILER_SIZE];
        System.arraycopy(packHeader(version, blocksPresent, n, offsetSize), 0, buf, 0, HEADER_SIZE);
        for (int row = 0; row < n; row++) {
            long v = offsets[row];
            int base = HEADER_SIZE + offsetSize * row;
            for (int i = 0; i < offsetSize; i++) {
                buf[base + i] = (byte) (v >>> (8 * i));
            }
        }
        System.arraycopy(packTrailer(blocksPresent), 0, buf,
                HEADER_SIZE + tableBytes, TRAILER_SIZE);

        // 整份重写要换掉文件,所以就地写的句柄必须先关 ——
        // ⚠️ Windows 上不能覆盖还开着句柄的文件(实测 WinError 5 / AccessDeniedException)。
        close();
        Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
        try {
            Files.write(tmp, buf);
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new GdbWriteException("重写 " + path.getFileName() + " 失败: " + e.getMessage(), e);
        }
        inplaceOk = true;
        pagesOnDisk = blocksPresent;
        dirty = false;
    }

    @Override
    public String toString() {
        return "<GdbTableIndex " + path.getFileName() + " records=" + totalRecordCount
                + " pages=" + blocksPresent + " offsetSize=" + offsetSize + ">";
    }
}
