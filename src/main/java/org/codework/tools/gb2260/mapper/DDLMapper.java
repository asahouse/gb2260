package org.codework.tools.gb2260.mapper;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 数据库 DDL 操作数据访问层
 * <p>
 * 提供爬虫启动前的高性能快速 TRUNCATE 清表操作
 *
 * @author benjaminkc
 */
public interface DDLMapper {

    /**
     * 截断并清空指定的数据库表结构（释放空间并重置状态）
     *
     * @param tableName 目标表名（如 province, city, county, town, village）
     * @return 影响行数
     */
    @Update("truncate table ${tableName}")
    int truncateTable(@Param("tableName") String tableName);
}
