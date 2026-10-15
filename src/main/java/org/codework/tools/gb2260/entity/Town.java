package org.codework.tools.gb2260.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 乡镇/街道办事处行政区划实体类 (第4级)
 * <p>
 * 对应数据库表：town
 * 包括街道办事处、镇、乡、民族乡、苏木等（全国共计 41,000+ 条）
 *
 * @author benjaminkc
 */
@ApiModel(description = "乡镇/街道行政区划实体")
@Builder
@Data
@AllArgsConstructor
@NoArgsConstructor
public class Town implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 12位标准行政区划代码（主键），例如：440106001000
     */
    @ApiModelProperty(value = "12位标准行政区划代码", example = "440106001000")
    @TableId
    private String code;

    /**
     * 乡镇/街道名称，例如：五山街道
     */
    @ApiModelProperty(value = "乡镇/街道名称", example = "五山街道")
    private String name;

    /**
     * 所属省级行政区划代码，例如：440000000000
     */
    @ApiModelProperty(value = "所属省级行政区划代码", example = "440000000000")
    private String provinceCode;

    /**
     * 所属市级行政区划代码，例如：440100000000
     */
    @ApiModelProperty(value = "所属市级行政区划代码", example = "440100000000")
    private String cityCode;

    /**
     * 所属区县级行政区划代码，例如：440106000000
     */
    @ApiModelProperty(value = "所属区县级行政区划代码", example = "440106000000")
    private String countyCode;
}
