package com.gdal.openfilegdb.exception;

/**
 * 写入失败:不支持的操作、字段类型/数量不匹配、索引与记录不一致等。
 *
 * <p>对标 GDAL {@code OGROpenFileGDBLayer::ICreateFeature} /
 * {@code FileGDBTable::CreateFeature} 在校验失败时返回 {@code OGRERR_FAILURE}
 * 并报错的路径,见 {@code filegdbtable_write.cpp}。
 */
public class GdbWriteException extends OpenFileGdbException {

    private static final long serialVersionUID = 1L;

    public GdbWriteException(String message) {
        super(message);
    }

    public GdbWriteException(String message, Throwable cause) {
        super(message, cause);
    }
}
