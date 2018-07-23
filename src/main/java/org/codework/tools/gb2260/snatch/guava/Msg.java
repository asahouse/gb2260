package org.codework.tools.gb2260.snatch.guava;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class Msg {
    String executorFlag;
    String channel;
    String message;
}
