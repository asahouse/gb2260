package org.codework.tools.gb2260.snatch;

/**
 * 抓取元素转换回调函数式接口
 * <p>
 * 用于在 HTML 元素解析遍历过程中，将转换生成的 {@link AreaInfo} 传递给后续业务处理器或事件发布器
 *
 * @author benjaminkc
 */
@FunctionalInterface
public interface Process {

    /**
     * 处理解析后的行政区划模型对象
     *
     * @param info 行政区划数据载体对象
     */
    void run(AreaInfo info);
}
