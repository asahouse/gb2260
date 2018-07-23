package org.codework.tools.gb2260.mapper;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

public interface DDLMapper {

    @Update("truncate table ${tableName}")
    int truncateTable(@Param("tableName") String tableName);
}
