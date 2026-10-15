package org.codework.tools.gb2260.controller;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 级联查询响应精简对象
 * <p>
 * 面向前端下拉选择组件（如 Element-UI Cascader）提供标准键值对
 *
 * @author benjaminkc
 */
@ApiModel(description = "行政区划精简展示信息")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RespInfo {

    /**
     * 12位标准行政区划代码
     */
    @ApiModelProperty(value = "12位标准行政区划代码", example = "440106000000")
    private String code;

    /**
     * 行政区划名称
     */
    @ApiModelProperty(value = "行政区划名称", example = "天河区")
    private String name;
}
