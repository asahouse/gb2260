package org.codework.tools.gb2260.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.codework.tools.gb2260.entity.CrawlHistory;

/**
 * 抓取任务历史执行记录数据访问接口
 * <p>
 * 继承 MyBatis-Plus {@link BaseMapper}，提供对 crawl_history 表的 CRUD 操作与审计追溯
 *
 * @author benjaminkc
 */
public interface CrawlHistoryMapper extends BaseMapper<CrawlHistory> {

}
