-- MySQL dump 10.13  Distrib 5.7.17, for macos10.12 (x86_64)
--
-- Host: localhost    Database: gb2260
-- ------------------------------------------------------
-- Server version	5.7.11-log

/*!40101 SET @OLD_CHARACTER_SET_CLIENT=@@CHARACTER_SET_CLIENT */;
/*!40101 SET @OLD_CHARACTER_SET_RESULTS=@@CHARACTER_SET_RESULTS */;
/*!40101 SET @OLD_COLLATION_CONNECTION=@@COLLATION_CONNECTION */;
/*!40101 SET NAMES utf8 */;
/*!40103 SET @OLD_TIME_ZONE=@@TIME_ZONE */;
/*!40103 SET TIME_ZONE='+00:00' */;
/*!40014 SET @OLD_UNIQUE_CHECKS=@@UNIQUE_CHECKS, UNIQUE_CHECKS=0 */;
/*!40014 SET @OLD_FOREIGN_KEY_CHECKS=@@FOREIGN_KEY_CHECKS, FOREIGN_KEY_CHECKS=0 */;
/*!40101 SET @OLD_SQL_MODE=@@SQL_MODE, SQL_MODE='NO_AUTO_VALUE_ON_ZERO' */;
/*!40111 SET @OLD_SQL_NOTES=@@SQL_NOTES, SQL_NOTES=0 */;

--
-- Table structure for table `city`
--

DROP TABLE IF EXISTS `city`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE `city` (
  `code` varchar(20) NOT NULL,
  `name` varchar(255) DEFAULT NULL,
  `province_code` varchar(20) DEFAULT NULL,
  PRIMARY KEY (`code`),
  KEY `idx_province_code` (`province_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `county`
--

DROP TABLE IF EXISTS `county`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE `county` (
  `code` varchar(20) NOT NULL,
  `name` varchar(255) DEFAULT NULL,
  `province_code` varchar(20) DEFAULT NULL,
  `city_code` varchar(20) DEFAULT NULL,
  PRIMARY KEY (`code`),
  KEY `idx_city_code` (`city_code`),
  KEY `idx_province_code` (`province_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `province`
--

DROP TABLE IF EXISTS `province`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE `province` (
  `code` varchar(20) NOT NULL,
  `name` varchar(255) DEFAULT NULL,
  PRIMARY KEY (`code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `town`
--

DROP TABLE IF EXISTS `town`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE `town` (
  `code` varchar(20) NOT NULL,
  `name` varchar(255) DEFAULT NULL,
  `province_code` varchar(20) DEFAULT NULL,
  `city_code` varchar(20) DEFAULT NULL,
  `county_code` varchar(20) DEFAULT NULL,
  PRIMARY KEY (`code`),
  KEY `idx_county_code` (`county_code`),
  KEY `idx_city_code` (`city_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `village`
--

DROP TABLE IF EXISTS `village`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE `village` (
  `code` varchar(20) NOT NULL,
  `name` varchar(255) DEFAULT NULL,
  `province_code` varchar(20) DEFAULT NULL,
  `city_code` varchar(20) DEFAULT NULL,
  `county_code` varchar(20) DEFAULT NULL,
  `town_code` varchar(20) DEFAULT NULL,
  `category_code` varchar(45) DEFAULT NULL,
  PRIMARY KEY (`code`),
  KEY `idx_town_code` (`town_code`),
  KEY `idx_county_code` (`county_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40103 SET TIME_ZONE=@OLD_TIME_ZONE */;

/*!40101 SET SQL_MODE=@OLD_SQL_MODE */;
/*!40014 SET FOREIGN_KEY_CHECKS=@OLD_FOREIGN_KEY_CHECKS */;
/*!40014 SET UNIQUE_CHECKS=@OLD_UNIQUE_CHECKS */;
/*!40101 SET CHARACTER_SET_CLIENT=@OLD_CHARACTER_SET_CLIENT */;
/*!40101 SET CHARACTER_SET_RESULTS=@OLD_CHARACTER_SET_RESULTS */;
/*!40101 SET COLLATION_CONNECTION=@OLD_COLLATION_CONNECTION */;
/*!40111 SET SQL_NOTES=@OLD_SQL_NOTES */;

--
-- Table structure for table `crawl_history`
--

CREATE TABLE IF NOT EXISTS `crawl_history` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '自增主键',
  `task_flag` varchar(64) NOT NULL COMMENT '任务批次标识',
  `data_source` varchar(32) NOT NULL DEFAULT 'mca' COMMENT '数据源引擎(mca/stats)',
  `province_filter` varchar(255) DEFAULT '' COMMENT '省份过滤参数',
  `is_offline_first` tinyint(1) NOT NULL DEFAULT 1 COMMENT '是否离线快照优先',
  `start_time` datetime NOT NULL COMMENT '开始时间',
  `end_time` datetime DEFAULT NULL COMMENT '结束时间',
  `duration_ms` bigint(20) DEFAULT 0 COMMENT '耗时毫秒数',
  `province_count` int(11) DEFAULT 0 COMMENT '省级入库数',
  `city_count` int(11) DEFAULT 0 COMMENT '市级入库数',
  `county_count` int(11) DEFAULT 0 COMMENT '区县入库数',
  `town_count` int(11) DEFAULT 0 COMMENT '街道入库数',
  `village_count` int(11) DEFAULT 0 COMMENT '居委会入库数',
  `status` varchar(32) NOT NULL DEFAULT 'RUNNING' COMMENT '状态: RUNNING/SUCCESS/TERMINATED/FAILED',
  `error_message` text DEFAULT NULL COMMENT '异常日志摘要',
  PRIMARY KEY (`id`),
  KEY `idx_task_flag` (`task_flag`),
  KEY `idx_start_time` (`start_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='抓取历史记录表';

-- Dump completed on 2018-07-24 11:30:13
