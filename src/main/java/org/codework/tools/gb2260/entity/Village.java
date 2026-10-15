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
 * 居委会/村委会行政区划实体类 (第5级)
 * <p>
 * 对应数据库表：village
 * 包括社区居委会、村民委员会，仅在国家统计局模式下提供城乡分类代码
 *
 * @author benjaminkc
 */
@ApiModel(description = "村委会/居委会行政区划实体")
@Builder
@Data
@AllArgsConstructor
@NoArgsConstructor
public class Village implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 12位标准行政区划代码（主键），例如：440106001001
     */
    @ApiModelProperty(value = "12位标准行政区划代码", example = "440106001001")
    @TableId
    private String code;

    /**
     * 村委会/居委会名称，例如：岳洲社区居委会
     */
    @ApiModelProperty(value = "村委会/居委会名称", example = "岳洲社区居委会")
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

    /**
     * 所属乡镇/街道级行政区划代码，例如：440106001000
     */
    @ApiModelProperty(value = "所属乡镇/街道级行政区划代码", example = "440106001000")
    private String townCode;

    /**
     * 城乡分类代码（统计用区划代码和城乡划分代码，例如：111 主城区）
     */
    @ApiModelProperty(value = "城乡分类代码", example = "111")
    private String categoryCode;
}
