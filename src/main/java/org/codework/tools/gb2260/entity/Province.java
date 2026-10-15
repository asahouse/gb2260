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
 * 省级行政区划实体类 (第1级)
 * <p>
 * 对应数据库表：province
 * 涵盖中国大陆 31 个省/直辖市/自治区，以及港澳台地区（共 34 个）
 *
 * @author benjaminkc
 */
@ApiModel(description = "省级行政区划实体")
@Builder
@Data
@AllArgsConstructor
@NoArgsConstructor
public class Province implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 12位标准行政区划代码（主键），例如：110000000000
     */
    @ApiModelProperty(value = "12位标准行政区划代码", example = "110000000000")
    @TableId
    private String code;

    /**
     * 省级行政区名称，例如：北京市
     */
    @ApiModelProperty(value = "省级行政区名称", example = "北京市")
    private String name;
}
