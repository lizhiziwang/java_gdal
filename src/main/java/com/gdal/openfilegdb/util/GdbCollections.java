package com.gdal.openfilegdb.util;

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Java 8 兼容的集合工厂(shim)。
 *
 * <p><b>为什么需要这个类:</b>本工程以 Java 8 为目标运行环境,而
 * {@code List.of(...)} / {@code Set.of(...)} / {@code Map.ofEntries(...)} 这些
 * 便捷工厂是 Java 9 才引入的。为了保持调用端"声明式常量表"的写法,这里用
 * Java 8 的 {@link Arrays#asList} / {@link Collections#unmodifiableList} 等
 * 复刻出语义等价的方法。
 *
 * <p>语义对齐 Java 9 工厂的关键点:<b>返回的集合一律不可变</b>
 * (unmodifiable),与 {@code List.of} 一致;遍历顺序保持插入顺序
 * ({@link LinkedHashMap}/{@link LinkedHashSet}),这一点比 JDK 的
 * {@code Map.of}/{@code Set.of}(顺序未定义)更严格,便于样板数据逐行比对。
 *
 * <p>与 JDK 工厂唯一的差别是<b>允许 null 元素</b>(JDK 的
 * {@code List.of}/{@code Map.ofEntries} 遇 null 抛 NPE)。本工程里
 * {@code GdbTemplates} 的样板行确实需要写入 null 占位,因此这是刻意放宽。
 * 如果调用方依赖"遇 null 快速失败"的行为,请自行检查。
 */
public final class GdbCollections {

    private GdbCollections() {
    }

    /**
     * 不可变列表,等价于 Java 9 的 {@code List.of(items)}。
     *
     * @param items 元素(允许 null)
     * @param <T>   元素类型
     * @return 不可变列表
     */
    @SafeVarargs
    public static <T> List<T> list(T... items) {
        // 先 clone 再 asList:避免调用方复用同一数组后意外改变集合内容。
        return Collections.unmodifiableList(Arrays.asList(items.clone()));
    }

    /**
     * 不可变有序集合,等价于 Java 9 的 {@code Set.of(items)}(但保持插入顺序)。
     *
     * @param items 元素(允许 null;重复元素会被去重)
     * @param <T>   元素类型
     * @return 不可变集合
     */
    @SafeVarargs
    public static <T> Set<T> set(T... items) {
        return Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList(items.clone())));
    }

    /**
     * 不可变列表副本,等价于 Java 10 的 {@code List.copyOf(c)}。
     *
     * @param c   源集合
     * @param <T> 元素类型
     * @return 不可变列表
     */
    public static <T> List<T> copyOfList(Collection<? extends T> c) {
        return Collections.unmodifiableList(new ArrayList<>(c));
    }

    /**
     * 不可变集合副本,等价于 Java 10 的 {@code Set.copyOf(c)}。
     *
     * @param c   源集合
     * @param <T> 元素类型
     * @return 不可变集合(插入顺序)
     */
    public static <T> Set<T> copyOfSet(Collection<? extends T> c) {
        return Collections.unmodifiableSet(new LinkedHashSet<>(c));
    }

    /**
     * 键值对,等价于 Java 9 的 {@code Map.entry(k, v)}。
     *
     * @param key   键
     * @param value 值
     * @param <K>   键类型
     * @param <V>   值类型
     * @return 不可变键值对
     */
    public static <K, V> Map.Entry<K, V> entry(K key, V value) {
        return new AbstractMap.SimpleImmutableEntry<>(key, value);
    }

    /**
     * 不可变映射,等价于 Java 9 的 {@code Map.ofEntries(entries)}。
     *
     * @param entries 键值对(重复键以后者为准)
     * @param <K>     键类型
     * @param <V>     值类型
     * @return 不可变映射(插入顺序)
     */
    @SafeVarargs
    public static <K, V> Map<K, V> ofEntries(Map.Entry<? extends K, ? extends V>... entries) {
        Map<K, V> map = new LinkedHashMap<>();
        for (Map.Entry<? extends K, ? extends V> e : entries) {
            map.put(e.getKey(), e.getValue());
        }
        return Collections.unmodifiableMap(map);
    }
}
