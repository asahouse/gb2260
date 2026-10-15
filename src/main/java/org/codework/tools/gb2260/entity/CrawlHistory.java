package org.codework.tools.gb2260.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.Date;

/**
 * 抓取任务历史执行记录实体类
 * <p>
 * 对应数据库表：crawl_history
 * 记录每次爬虫调度执行的批次标识、引擎类型、入库统计规模、耗时与最终状态
 *
 * @author benjaminkc
 */
@ApiModel(description = "抓取任务历史执行记录实体")
@TableName("crawl_history")
@Builder
@Data
@AllArgsConstructor
@NoArgsConstructor
public class CrawlHistory implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 自增主键 ID
     */
    @ApiModelProperty(value = "自增主键 ID", example = "1")
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 任务执行批次唯一标识（通常以启动时间戳命名）
     */
    @ApiModelProperty(value = "任务执行批次唯一标识", example = "1760499600000")
    private String taskFlag;

    /**
     * 采用的数据源引擎类型（mca: 民政部, stats: 国家统计局）
     */
    @ApiModelProperty(value = "数据源引擎类型(mca/stats)", example = "mca")
    private String dataSource;

    /**
     * 省份过滤条件（为空代表抓取全国，非空为指定省份代码如 '11,44'）
     */
    @ApiModelProperty(value = "省份过滤参数", example = "11,44")
    private String provinceFilter;

    /**
     * 是否优先使用离线快照
     */
    @ApiModelProperty(value = "是否优先使用离线快照(1:是, 0:否)", example = "1")
    private Integer isOfflineFirst;

    /**
     * 任务启动时间
     */
    @ApiModelProperty(value = "任务启动时间")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private Date startTime;

    /**
     * 任务结束时间
     */
    @ApiModelProperty(value = "任务结束时间")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private Date endTime;

    /**
     * 任务总耗时（毫秒）
     */
    @ApiModelProperty(value = "任务执行总耗时(毫秒)", example = "12560")
    private Long durationMs;

    /**
     * 本次任务最终入库省级记录数
     */
    @ApiModelProperty(value = "省级入库记录数", example = "34")
    private Integer provinceCount;

    /**
     * 本次任务最终入库市级记录数
     */
    @ApiModelProperty(value = "市级入库记录数", example = "341")
    private Integer cityCount;

    /**
     * 本次任务最终入库区县级记录数
     */
    @ApiModelProperty(value = "区县级入库记录数", example = "2974")
    private Integer countyCount;

    /**
     * 本次任务最终入库乡镇街道级记录数
     */
    @ApiModelProperty(value = "乡镇街道级入库记录数", example = "41351")
    private Integer townCount;

    /**
     * 本次任务最终入库村委会/居委会级记录数
     */
    @ApiModelProperty(value = "居委会级入库记录数", example = "0")
    private Integer villageCount;

    /**
     * 任务最终状态（RUNNING: 运行中, SUCCESS: 成功完成, TERMINATED: 强制中止, FAILED: 异常失败）
     */
    @ApiModelProperty(value = "任务执行状态(RUNNING/SUCCESS/TERMINATED/FAILED)", example = "SUCCESS")
    private String status;

    /**
     * 异常错误信息摘要（若执行失败或被中止）
     */
    @ApiModelProperty(value = "异常日志摘要")
    private String errorMessage;
}
