package com.gdal.openfilegdb.util;

import java.math.BigDecimal;

/**
 * 浮点数值的“普通十进制”文本化工具。
 *
 * <p>{@link Double#toString(double)} 在 {@code |v| < 1e-3} 或 {@code |v| >= 1e7} 时
 * 会退化成科学计数法(如 {@code 1.23E-5}、{@code 1.23456789E7})。这在本库的两类输出里
 * 都不可接受:
 * <ul>
 *   <li><b>WKT 坐标</b> —— 用户看到的是 {@code 4.5E7} 这种形态,既难与 ArcGIS / GDAL
 *       的文本输出对照,也让文本 diff 与日志变得难读;OGC WKT 虽容忍科学计数法,
 *       但通行写法是普通十进制。</li>
 *   <li><b>Definition XML</b> —— 与 ArcGIS 自身写出的 {@code <XMin>} / {@code <Scale>}
 *       形态保持一致(均为普通十进制)。</li>
 * </ul>
 *
 * <p><b>实现要点</b>:先取 {@link Double#toString(double)} —— 它给出“能唯一还原该
 * double 的最短十进制串”,精度不丢;仅当串里含 {@code E}/{@code e} 时才用
 * {@link BigDecimal#toPlainString()} 展开成普通形式。<b>不要</b>用
 * {@code new BigDecimal(double)}:那个构造器会取 double 的精确二进制值,展开后是一长串
 * {@code 113.264400000000015481...} 之类的噪声。按本方法走,
 * {@code Double.parseDouble(plain(v)) == v} 恒成立。
 *
 * <p>整数值单独走近 {@code long} 的分支,避免输出 {@code 5.0}。
 */
public final class GdbNumbers {

    /**
     * 整值转 long 的上界。
     *
     * <p>超过这个量级的 double 已无法精确表示相邻整数(2^53 ≈ 9.007e15),再走
     * {@code (long)} 反而会悄悄丢精度,所以留给 BigDecimal 展平。
     */
    private static final double INT_LONG_LIMIT = 1e15;

    private GdbNumbers() {
        // 工具类,不实例化
    }

    /**
     * 把 double 格式化为<b>不含科学计数法</b>的十进制文本;整值不带小数点。
     *
     * <pre>
     * plain(0.0000123)   -&gt; "0.0000123"       (而非 "1.23E-5")
     * plain(12345678.9)  -&gt; "12345678.9"      (而非 "1.23456789E7")
     * plain(-5.0)        -&gt; "-5"
     * plain(Double.NaN)  -&gt; "NaN"
     * </pre>
     *
     * @param v 待格式化的值
     * @return 普通十进制文本;{@code NaN} / {@code Infinity} 返回其字面量
     */
    public static String plain(double v) {
        if (v == Math.rint(v) && !Double.isInfinite(v) && Math.abs(v) < INT_LONG_LIMIT) {
            return Long.toString((long) v);
        }
        String s = Double.toString(v);
        if (s.indexOf('E') < 0 && s.indexOf('e') < 0) {
            return s;
        }
        // 仅科学计数法形态才转为普通形式。BigDecimal(String) 原样保留 Double.toString
        // 的尾数(精度不丢),但 "1.0E-9" 这类尾数展开后会多出一个零
        // (-> "0.0000000010"),所以再去一次尾随零;"1.0E20" 同样归并成
        // "100000000000000000000"。stripTrailingZeros 只改标度、不改值。
        return new BigDecimal(s).stripTrailingZeros().toPlainString();
    }
}
