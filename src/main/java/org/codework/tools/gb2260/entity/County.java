package org.codework.tools.gb2260.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Builder
@Data
@AllArgsConstructor
@NoArgsConstructor
public class County implements Serializable {
    private String code;
    private String name;
    private String provinceCode;
    private String cityCode;
}
