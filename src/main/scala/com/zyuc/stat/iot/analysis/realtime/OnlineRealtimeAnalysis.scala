package com.zyuc.stat.iot.analysis.realtime

import com.zyuc.stat.iot.analysis.realtime.AuthRealtimeAnalysis.logError
import com.zyuc.stat.iot.analysis.util.HbaseDataUtil
import com.zyuc.stat.properties.ConfigProperties
import com.zyuc.stat.utils.{DateUtils, HbaseUtils, MathUtil}
import org.apache.hadoop.hbase.client.Put
import org.apache.hadoop.hbase.io.ImmutableBytesWritable
import org.apache.hadoop.hbase.util.Bytes
import org.apache.spark.{Logging, SparkConf, SparkContext}
import org.apache.spark.sql.hive.HiveContext

/**
  * Created by zhoucw on 17-10-8.
  */
object OnlineRealtimeAnalysis extends Logging{
  def main(args: Array[String]): Unit = {
    val sparkConf = new SparkConf()
    val sc = new SparkContext(sparkConf)
    val sqlContext = new HiveContext(sc)
    sqlContext.sql("use " + ConfigProperties.IOT_HIVE_DATABASE)

    // 获取参数
    val appName = sc.getConf.get("spark.app.name","name_201710080040") // name_201708010040
    val userInfoTable = sc.getConf.get("spark.app.table.userInfoTable", "iot_basic_userinfo") //
    val userAndDomainTable = sc.getConf.get("spark.app.table.userAndDomainTable", "iot_basic_user_and_domain")
    val companyAndDomainTable = sc.getConf.get("spark.app.table.companyAndDomainTable", "iot_basic_company_and_domain")
    val userTableDataDayid = sc.getConf.get("spark.app.table.userTableDataDayid", "20170922")

    val auth3gTable = sc.getConf.get("spark.app.table.3gRadiusTable", "iot_radius_ha")
    val auth4gTable = sc.getConf.get("spark.app.table.4gRadiusTable", "pgwradius_out")
    val alarmHtablePre = sc.getConf.get("spark.app.htable.alarmTablePre", "analyze_summ_tab_")
    val resultHtablePre = sc.getConf.get("spark.app.htable.resultHtablePre", "analyze_summ_rst_everycycle_")
    val resultDayHtable = sc.getConf.get("spark.app.htable.resultDayHtable", "analyze_summ_rst_everyday")
    val analyzeBPHtable = sc.getConf.get("spark.app.htable.analyzeBPHtable", "analyze_bp_tab")

    // 实时分析类型： 0-后续会离线重跑数据, 2-后续不会离线重跑数据
    val progRunType = sc.getConf.get("spark.app.progRunType", "0")
    // 距离当前历史同期的天数
    val hisDayNumStr = sc.getConf.get("spark.app.hisDayNums", "7")

    if(progRunType!="0" && progRunType!="1" ) {
      logError("param progRunType invalid, expect:0|1")
      return
    }
    var hisDayNum:Int = 0
    try {
      hisDayNum = hisDayNumStr.toInt
    }catch {
      case e:Exception => {
        logError("TypeConvert Failed. hisDayNumStr [" + hisDayNumStr + " cannot convert to Int ] ")
        return
      }
    }
    //  dataTime-当前数据时间  nextDataTime-下一个时刻数据的时间
    val dataTime = appName.substring(appName.lastIndexOf("_") + 1)
    val nextDataTime = DateUtils.timeCalcWithFormatConvertSafe(dataTime, "yyyyMMddHHmm", 5*60, "yyyyMMddHHmm")
    // 转换成hive表中的时间格式
    val startTimeStr = DateUtils.timeCalcWithFormatConvertSafe(dataTime, "yyyyMMddHHmm", -5*60, "yyyyMMddHHmmss")
    val endTimeStr = DateUtils.timeCalcWithFormatConvertSafe(dataTime, "yyyyMMddHHmm", 0, "yyyyMMddHHmmss")
    // 转换成hive表中的分区字段值
    val startTime =  DateUtils.timeCalcWithFormatConvertSafe(dataTime, "yyyyMMddHHmm", -5*60, "yyyyMMddHHmm")
    val partitionD = startTime.substring(0, 8)
    val partitionH = startTime.substring(8, 10)



    /////////////////////////////////////////////////////////////////////////////////////////
    //  Hbase 相关的表
    //  表不存在， 就创建
    /////////////////////////////////////////////////////////////////////////////////////////
    //  curAlarmHtable-当前时刻的预警表,  nextAlarmHtable-下一时刻的预警表,
    val curAlarmHtable = alarmHtablePre + dataTime.substring(0,8)
    val nextAlarmHtable = alarmHtablePre + nextDataTime.substring(0,8)
    val alarmFamilies = new Array[String](1)
    alarmFamilies(0) = "s"
    // 创建表, 如果表存在， 自动忽略
    HbaseUtils.createIfNotExists(curAlarmHtable,alarmFamilies)
    HbaseUtils.createIfNotExists(nextAlarmHtable,alarmFamilies)

    //  curResultHtable-当前时刻的结果表,  nextResultHtable-下一时刻的结果表
    val curResultHtable = resultHtablePre + dataTime.substring(0,8)
    val nextResultHtable = resultHtablePre + nextDataTime.substring(0,8)
    val resultFamilies = new Array[String](2)
    resultFamilies(0) = "s"
    resultFamilies(1) = "e"
    HbaseUtils.createIfNotExists(curResultHtable, resultFamilies)
    HbaseUtils.createIfNotExists(nextResultHtable, resultFamilies)

    // resultDayHtable
    val resultDayFamilies = new Array[String](1)
    resultDayFamilies(0) = "s"
    HbaseUtils.createIfNotExists(resultDayHtable, resultDayFamilies)

    // analyzeBPHtable
    val analyzeBPFamilies = new Array[String](1)
    analyzeBPFamilies(0) = "bp"
    HbaseUtils.createIfNotExists(analyzeBPHtable, analyzeBPFamilies)


    ////////////////////////////////////////////////////////////////
    //   cache table
    ///////////////////////////////////////////////////////////////
    val userInfoTableCached = "userInfoTableCached"
    sqlContext.sql(s"cache table ${userInfoTableCached} as select mdn, imsicdma, companycode, vpdndomain, isvpdn, isdirect, iscommon from $userInfoTable where d=$userTableDataDayid")


    val radiusTable = "radiusTable" + dataTime
    sqlContext.sql(
      s"""
         |CACHE TABLE ${radiusTable} as
         |select mdn, status, '4g' as type, regexp_replace(terminatecause, ' ', '') as terminatecause,
         |(case when terminatecause='UserRequest' then 's' else 'f' end ) as result
         |from pgwradius_out where dayid='${partitionD}' and time>='${startTimeStr}'  and time<'${endTimeStr}'
         |union all
         |select mdn, status, '3g' as type, terminatecause,
         |(case when terminatecause in('2','7','8','9','11','12','13','14','15') then 's' else 'f' end ) as result
         |from iot_radius_ha where dayid='${partitionD}' and time>='${startTimeStr}'  and time<'${endTimeStr}'
       """.stripMargin)

    val vpdnStatSQL =
      s"""
         |select u.companycode, 'C' as servtype, "-1" as vpdndomain, c.type,
         |sum(case when c.status='Start' then 1 else 0 end) logincnt,
         |sum(case when c.status='Stop' then 1 else 0 end) logoutcnt,
         |sum(case when c.status='Stop' and c.result='s' then 1 else 0 end) normallogoutcnt
         |from ${userInfoTableCached} u, ${radiusTable} c
         |where c.mdn = u.mdn and u.isvpdn='1'
         |group by companycode, type
         |GROUPING SETS(companycode, (companycode, type))
         |union all
         |select t.companycode, 'C' as servtype, t.vpdndomain, t.type,
         |sum(case when status='Start' then 1 else 0 end) logincnt,
         |sum(case when status='Stop' then 1 else 0 end) logoutcnt,
         |sum(case when status='Stop' and result='s' then 1 else 0 end) normallogoutcnt
         |from(
         |select companycode, c.vpdndomain, type, status, result
         |from
         |( select u.companycode, vpdndomain, c.type, c.status,c.result
         |from ${userInfoTableCached} u, ${radiusTable} c
         |where c.mdn = u.mdn and u.isvpdn='1'
         |) m lateral view explode(split(m.vpdndomain,',')) c as vpdndomain
         |) t
         |group by companycode, vpdndomain, type
         |GROUPING SETS((companycode, vpdndomain), (companycode, vpdndomain, type))
       """.stripMargin
    val vpdnDF = sqlContext.sql(vpdnStatSQL)

    val commonAndDirectSQL =
      s"""
         |select companycode, servtype, null as vpdndomain, type,
         |       sum(case when status='Start' then 1 else 0 end) logincnt,
         |       sum(case when status='Stop' then 1 else 0 end) logoutcnt,
         |       sum(case when status='Stop' and result='s' then 1 else 0 end) normallogoutcnt
         |       from
         |       (
         |           select u.companycode,
         |                  (case when u.isdirect='1' then 'D' when u.iscommon='1' then 'P' else '-1' end) as servtype,
         |                  c.type, c.status, c.result
         |           from ${userInfoTableCached} u, ${radiusTable} c
         |           where c.mdn = u.mdn
         |       ) m
         |       group by companycode, servtype, type
         |       GROUPING SETS(companycode, (companycode, servtype), (companycode, servtype, type))
       """.stripMargin

    val commonAndDirectDF = sqlContext.sql(commonAndDirectSQL).filter("servtype is null or servtype!='-1'")

    val resultDF = vpdnDF.unionAll(commonAndDirectDF)

    val resultRDD = resultDF.rdd.map(x=>{

      val companyCode = x(0).toString
      val servType = if(null == x(1)) "-1" else x(1).toString
      val servFlag = if(servType == "D") "D" else if(servType == "C") "C"  else if(servType == "P") "P"  else "-1"
      val domain = if(null == x(2)) "-1" else x(2).toString
      val netType = if(null == x(3)) "-1" else x(3).toString
      val netFlag = if(netType=="3g") "3" else if(netType=="4g") "4" else "t"
      val loginCnt = x(4).toString
      val logoutCnt = x(5).toString
      val normalLogoutCnt = x(6).toString


      val curAlarmRowkey = progRunType + "_" + dataTime.substring(8,12) + "_" + companyCode + "_" + servType + "_" + domain
      val curAlarmPut = new Put(Bytes.toBytes(curAlarmRowkey))
      curAlarmPut.addColumn(Bytes.toBytes("s"), Bytes.toBytes("o_c_" + netFlag + "_li"), Bytes.toBytes(loginCnt))
      curAlarmPut.addColumn(Bytes.toBytes("s"), Bytes.toBytes("o_c_" + netFlag + "_lo"), Bytes.toBytes(logoutCnt))
      curAlarmPut.addColumn(Bytes.toBytes("s"), Bytes.toBytes("o_c_" + netFlag + "_nlo"), Bytes.toBytes(normalLogoutCnt))


      val nextAlarmRowkey = progRunType + "_" + nextDataTime.substring(8,12) + "_" + companyCode + "_" + servType + "_" + domain
      val nexAlarmtPut = new Put(Bytes.toBytes(nextAlarmRowkey))
      nexAlarmtPut.addColumn(Bytes.toBytes("s"), Bytes.toBytes("o_p_" + netFlag + "_li"), Bytes.toBytes(loginCnt))
      nexAlarmtPut.addColumn(Bytes.toBytes("s"), Bytes.toBytes("o_p_" + netFlag + "_lo"), Bytes.toBytes(logoutCnt))
      nexAlarmtPut.addColumn(Bytes.toBytes("s"), Bytes.toBytes("o_p_" + netFlag + "_nlo"), Bytes.toBytes(normalLogoutCnt))


      val curResKey = companyCode +"_" + servType + "_" + domain + "_" + dataTime.substring(8,12)
      val curResPut = new Put(Bytes.toBytes(curResKey))
      curResPut.addColumn(Bytes.toBytes("s"), Bytes.toBytes("o_c_" + netFlag + "_li"), Bytes.toBytes(loginCnt))
      curResPut.addColumn(Bytes.toBytes("s"), Bytes.toBytes("o_c_" + netFlag + "_lo"), Bytes.toBytes(logoutCnt))
      curResPut.addColumn(Bytes.toBytes("s"), Bytes.toBytes("o_c_" + netFlag + "_nlo"), Bytes.toBytes(normalLogoutCnt))



      val nextResKey = companyCode +"_" + servType + "_" + domain + "_" + nextDataTime.substring(8,12)
      val nextResPut = new Put(Bytes.toBytes(nextResKey))
      nextResPut.addColumn(Bytes.toBytes("s"), Bytes.toBytes("a_p_" + netFlag + "_li"), Bytes.toBytes(loginCnt))
      nextResPut.addColumn(Bytes.toBytes("s"), Bytes.toBytes("a_p_" + netFlag + "_lo"), Bytes.toBytes(logoutCnt))
      nextResPut.addColumn(Bytes.toBytes("s"), Bytes.toBytes("a_p_" + netFlag + "_nlo"), Bytes.toBytes(normalLogoutCnt))



      val dayResKey = dataTime.substring(2,8) + "_" + companyCode + "_" + servType + "_" + domain
      val dayResPut = new Put(Bytes.toBytes(dayResKey))
      dayResPut.addColumn(Bytes.toBytes("s"), Bytes.toBytes("a_c_" + netFlag + "_li"), Bytes.toBytes(loginCnt))
      dayResPut.addColumn(Bytes.toBytes("s"), Bytes.toBytes("a_c_" + netFlag + "_lo"), Bytes.toBytes(logoutCnt))
      dayResPut.addColumn(Bytes.toBytes("s"), Bytes.toBytes("a_c_" + netFlag + "_nlo"), Bytes.toBytes(normalLogoutCnt))


      ((new ImmutableBytesWritable, curAlarmPut), (new ImmutableBytesWritable, nexAlarmtPut), (new ImmutableBytesWritable, curResPut), (new ImmutableBytesWritable, nextResPut), (new ImmutableBytesWritable, dayResPut))
    })


    HbaseDataUtil.saveRddToHbase(curAlarmHtable, resultRDD.map(x=>x._1))
    HbaseDataUtil.saveRddToHbase(nextAlarmHtable, resultRDD.map(x=>x._2))
    HbaseDataUtil.saveRddToHbase(curResultHtable, resultRDD.map(x=>x._3))
    HbaseDataUtil.saveRddToHbase(nextResultHtable, resultRDD.map(x=>x._4))
    HbaseDataUtil.saveRddToHbase(resultDayHtable, resultRDD.map(x=>x._5))




  }

  def doOnlineDetail() = {

  }

}