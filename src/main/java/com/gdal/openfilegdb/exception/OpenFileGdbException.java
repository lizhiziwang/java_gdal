package com.gdal.openfilegdb.exception;

/**
 * 所有 OpenFileGDB 异常的基类。
 *
 * <p>对标 GDAL 中 {@code CPLError} 抛出的错误路径:GDAL 用 {@code CPLE_AppDefined}、
 * {@code CPLE_OpenFailed} 等错误类别配合 {@code CPLError(CE_Failure, ...)} 报错,
 * 本工程把这些类别映射成一棵异常树,由基类 {@code OpenFileGdbException} 统一承载。
 * 参见 {@code ogr/ogrsf_frmts/openfilegdb/ogropenfilegdbdriver.cpp}。
 *
 * <p>设计为 {@link RuntimeException}:读要素是一个"逐条迭代"的热路径,若做成
 * checked exception 会让每一次 {@code nextFeature()} 都被 try/catch 包住,
 * 与 GDAL 的返回码风格并不匹配。
 */
public class OpenFileGdbException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public OpenFileGdbException(String message) {
        super(message);
    }

    public OpenFileGdbException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * 把底层 IO 异常包装为 OpenFileGDB 语义的异常。
     *
     * @param message 描述信息
     * @param cause   原始异常
     * @return 包装后的异常
     */
    public static OpenFileGdbException wrap(String message, Throwable cause) {
        if (cause instanceof OpenFileGdbException) {
            return (OpenFileGdbException) cause;
        }
        return new GdbIoException(message + ": " + cause.getMessage(), cause);
    }
}
