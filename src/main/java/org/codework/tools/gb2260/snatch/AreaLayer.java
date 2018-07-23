package org.codework.tools.gb2260.snatch;

import java.util.Optional;

public enum AreaLayer {
    None(0), Province(1), City(2), County(3), Town(4), Village(5);

    private Integer value;

    AreaLayer(Integer value) {
        this.value = value;
    }

    public static AreaLayer fromValue(Integer value){
        if (Optional.ofNullable(value).isPresent()) {
            for (AreaLayer layer : values()) {
                if (layer.value.equals(value)) {
                    return layer;
                }
            }
        }
        return null;
    }

    public Integer toValue() {
        return value;
    }
}
