package org.codework.tools.gb2260.snatch.guava;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Guava EventBus 事件传输载体对象
 * <p>
 * 封装单次事件通知中包含的任务执行器标识、分发频道类型以及序列化后的行政区划 JSON 字符串
 *
 * @author benjaminkc
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Msg {

    /**
     * 关联的线程池执行器唯一标识（以任务启动时间戳命名），用于生命周期追踪
     */
    private String executorFlag;

    /**
     * 事件所属行政层级频道，例如：CHANNEL_PROVINCE, CHANNEL_CITY, CHANNEL_COUNTY, CHANNEL_TOWN
     */
    private String channel;

    /**
     * 传输的序列化行政区划 JSON 数据体
     */
    private String message;
}
