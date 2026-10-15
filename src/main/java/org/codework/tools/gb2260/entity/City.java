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
 * 地级市/盟/州行政区划实体类 (第2级)
 * <p>
 * 对应数据库表：city
 * 包括地级市、地区、自治州、盟，以及直辖市虚拟“市辖区”节点和不设区直筒子市
 *
 * @author benjaminkc
 */
@ApiModel(description = "地级行政区划实体")
@Builder
@Data
@AllArgsConstructor
@NoArgsConstructor
public class City implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 12位标准行政区划代码（主键），例如：440100000000
     */
    @ApiModelProperty(value = "12位标准行政区划代码", example = "440100000000")
    @TableId
    private String code;

    /**
     * 地级市/盟/州名称，例如：广州市
     */
    @ApiModelProperty(value = "地级市/盟/州名称", example = "广州市")
    private String name;

    /**
     * 所属上级省级行政区划代码，例如：440000000000
     */
    @ApiModelProperty(value = "所属上级省级行政区划代码", example = "440000000000")
    private String provinceCode;
}
