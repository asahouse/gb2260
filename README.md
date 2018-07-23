# GB2260
SpringBoot2.0 实现行政区域划分抓取

需要DB, Redis配置
通过RunController.start()即可开始抓取, query()方法查询线程状态, termination()方法强制停止线程池[EventBus会抛错,可以忽略]
每次start均对DB进行Truncate操作, 及Redis 进行FlushDB操作.是全量抓取方案.

通过实际调试得知, 统计局网站是有单个IP的并发数及访问时间间距控制,某一条条件超过均返回503及502
对应使用线程池参数是控制并发数量.
使用对每个线程Sleep控制间距.
在不断调试下, 兼顾DB写入及WEB访问线程. 预计完成约70W数据需时约1小时. 这个无关性能问题.

过期方法说明
1) 递归方法在AreaSnatchMethodHandler, 其单个递归的所有节点均只能在一条线程上, 所以效率其低
2) Redis方式可行, 但由于Spring方式使用Jedis注入会出现循环引用, 同时Redis的消息效率不及Guava的EventBus高,放弃
3) AreaSnatchHandler中URLConnection和Okhttp的选用上, 性能上或许okhttp更好,
		但由于对GBK子集等抓取时,出现不可预测的乱码(设置编码转换正常下,一时正常一时乱码),故使用前者

最后通过Guava的事件总线实现单机高效分发, 事件分发隔离每个行政级别处理, 分发给单独线程进行每个数据处理, 能更高效完成.(虽然网站限制了)
