package org.codework.tools.gb2260.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.extern.java.Log;
import org.codework.tools.gb2260.entity.CrawlHistory;
import org.codework.tools.gb2260.mapper.CityMapper;
import org.codework.tools.gb2260.mapper.CountyMapper;
import org.codework.tools.gb2260.mapper.CrawlHistoryMapper;
import org.codework.tools.gb2260.mapper.ProvinceMapper;
import org.codework.tools.gb2260.mapper.TownMapper;
import org.codework.tools.gb2260.mapper.VillageMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.List;

/**
 * 抓取任务历史执行审计服务
 * <p>
 * 负责任务启动埋点登记、完成与收敛状态核验回写、入库规模统计与历史记录分页查询
 *
 * @author benjaminkc
 */
@Log
@Service
public class CrawlHistoryService {

    private final CrawlHistoryMapper crawlHistoryMapper;
    private final ProvinceMapper provinceMapper;
    private final CityMapper cityMapper;
    private final CountyMapper countyMapper;
    private final TownMapper townMapper;
    private final VillageMapper villageMapper;

    @Autowired
    public CrawlHistoryService(CrawlHistoryMapper crawlHistoryMapper,
                               ProvinceMapper provinceMapper,
                               CityMapper cityMapper,
                               CountyMapper countyMapper,
                               TownMapper townMapper,
                               VillageMapper villageMapper) {
        this.crawlHistoryMapper = crawlHistoryMapper;
        this.provinceMapper = provinceMapper;
        this.cityMapper = cityMapper;
        this.countyMapper = countyMapper;
        this.townMapper = townMapper;
        this.villageMapper = villageMapper;
    }

    /**
     * 任务启动时创建历史记录流水
     *
     * @param taskFlag       任务批次标识（时间戳）
     * @param dataSource     数据源引擎（mca / stats）
     * @param provinceFilter 省份过滤条件
     * @param isOfflineFirst 是否离线快照优先
     * @return 新增的 CrawlHistory 实体对象
     */
    public CrawlHistory recordStart(String taskFlag, String dataSource, String provinceFilter, boolean isOfflineFirst) {
        try {
            CrawlHistory history = CrawlHistory.builder()
                    .taskFlag(taskFlag)
                    .dataSource(dataSource != null ? dataSource : "mca")
                    .provinceFilter(provinceFilter != null ? provinceFilter : "")
                    .isOfflineFirst(isOfflineFirst ? 1 : 0)
                    .startTime(new Date())
                    .status("RUNNING")
                    .provinceCount(0)
                    .cityCount(0)
                    .countyCount(0)
                    .townCount(0)
                    .villageCount(0)
                    .durationMs(0L)
                    .build();

            crawlHistoryMapper.insert(history);
            log.info("已创建抓取批次审计记录: flag=" + taskFlag + ", dataSource=" + dataSource);
            return history;
        } catch (Exception e) {
            log.warning("记录爬虫启动审计信息失败: " + e.getMessage());
            return null;
        }
    }

    /**
     * 任务完成或异常终止时，更新历史记录与各级数据表入库数量
     *
     * @param taskFlag     任务批次标识
     * @param status       最终状态（SUCCESS / TERMINATED / FAILED）
     * @param errorMessage 异常摘要（如有）
     */
    public void recordFinish(String taskFlag, String status, String errorMessage) {
        try {
            QueryWrapper<CrawlHistory> wrapper = new QueryWrapper<>();
            wrapper.eq("task_flag", taskFlag);
            CrawlHistory history = crawlHistoryMapper.selectOne(wrapper);
            if (history == null) {
                log.warning("未找到对应批次审计记录: taskFlag=" + taskFlag);
                return;
            }

            // 若已被主动终止机制标记为 TERMINATED，则保留 TERMINATED 状态与原终止信息，防止被后续延迟完成回调覆盖
            if ("TERMINATED".equals(history.getStatus())) {
                log.info("批次 " + taskFlag + " 已被终止流程标记为 TERMINATED，保留原终止状态");
                return;
            }

            Date endTime = new Date();
            long duration = 0L;
            if (history.getStartTime() != null) {
                duration = endTime.getTime() - history.getStartTime().getTime();
            }

            // 统计各层级当前落库记录总数
            Integer pCount = provinceMapper.selectCount(null);
            Integer cCount = cityMapper.selectCount(null);
            Integer coCount = countyMapper.selectCount(null);
            Integer tCount = townMapper.selectCount(null);
            Integer vCount = villageMapper.selectCount(null);

            history.setEndTime(endTime);
            history.setDurationMs(duration);
            history.setProvinceCount(pCount != null ? pCount : 0);
            history.setCityCount(cCount != null ? cCount : 0);
            history.setCountyCount(coCount != null ? coCount : 0);
            history.setTownCount(tCount != null ? tCount : 0);
            history.setVillageCount(vCount != null ? vCount : 0);
            history.setStatus(status != null ? status : "SUCCESS");
            if (errorMessage != null) {
                history.setErrorMessage(errorMessage);
            }

            crawlHistoryMapper.updateById(history);
            log.info("已更新抓取批次审计记录: flag=" + taskFlag + ", status=" + status + ", duration=" + duration + "ms");
        } catch (Exception e) {
            log.warning("更新爬虫结束审计信息失败: " + e.getMessage());
        }
    }

    /**
     * 强制终止时将所有正在运行中的批次标记为 TERMINATED
     */
    public void recordTermination() {
        try {
            QueryWrapper<CrawlHistory> wrapper = new QueryWrapper<>();
            wrapper.eq("status", "RUNNING");
            List<CrawlHistory> runningList = crawlHistoryMapper.selectList(wrapper);
            Date now = new Date();
            for (CrawlHistory history : runningList) {
                history.setStatus("TERMINATED");
                history.setEndTime(now);
                if (history.getStartTime() != null) {
                    history.setDurationMs(now.getTime() - history.getStartTime().getTime());
                }
                history.setProvinceCount(provinceMapper.selectCount(null));
                history.setCityCount(cityMapper.selectCount(null));
                history.setCountyCount(countyMapper.selectCount(null));
                history.setTownCount(townMapper.selectCount(null));
                history.setVillageCount(villageMapper.selectCount(null));
                history.setErrorMessage("用户通过控制台主动中止任务");
                crawlHistoryMapper.updateById(history);
            }
        } catch (Exception e) {
            log.warning("记录终止审计信息失败: " + e.getMessage());
        }
    }

    /**
     * 分页查询历史抓取审计记录
     *
     * @param page 页码（从 1 开始）
     * @param size 每页记录条数
     * @return 分页历史记录对象
     */
    public Page<CrawlHistory> getHistoryPage(long page, long size) {
        Page<CrawlHistory> pageParam = new Page<>(page, size);
        QueryWrapper<CrawlHistory> wrapper = new QueryWrapper<>();
        wrapper.orderByDesc("id");
        return (Page<CrawlHistory>) crawlHistoryMapper.selectPage(pageParam, wrapper);
    }
}
