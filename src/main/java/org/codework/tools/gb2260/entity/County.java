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
 * 区/县/县级市行政区划实体类 (第3级)
 * <p>
 * 对应数据库表：county
 * 包括市辖区、县级市、县、自治县、旗、自治旗、特区、林区等
 *
 * @author benjaminkc
 */
@ApiModel(description = "县级行政区划实体")
@Builder
@Data
@AllArgsConstructor
@NoArgsConstructor
public class County implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 12位标准行政区划代码（主键），例如：440106000000
     */
    @ApiModelProperty(value = "12位标准行政区划代码", example = "440106000000")
    @TableId
    private String code;

    /**
     * 区/县名称，例如：天河区
     */
    @ApiModelProperty(value = "区/县名称", example = "天河区")
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
}
