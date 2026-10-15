package com.gdal.openfilegdb.exception;

/**
 * 底层 IO 失败:文件读不完、被截断、写盘失败等。
 *
 * <p>对标 GDAL 的 {@code VSIFReadL} / {@code VSIFWriteL} 返回短读短写
 * 时抛出的 {@code CPLE_FileIO} 错误。
 */
public class GdbIoException extends OpenFileGdbException {

    private static final long serialVersionUID = 1L;

    public GdbIoException(String message) {
        super(message);
    }

    public GdbIoException(String message, Throwable cause) {
        super(message, cause);
    }
}
