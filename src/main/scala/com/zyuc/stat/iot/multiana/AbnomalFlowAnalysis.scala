package com.zyuc.stat.iot.multiana

import com.zyuc.stat.properties.ConfigProperties
import com.zyuc.stat.utils.FileUtils
import org.apache.hadoop.fs.FileSystem
import org.apache.spark.sql.SaveMode
import org.apache.spark.sql.functions._
import org.apache.spark.sql.hive.HiveContext
import org.apache.spark.{SparkConf, SparkContext}

/**
  * Created by dell on 2017/8/19.
  */
object AbnomalFlowAnalysis {
  def main(args: Array[String]): Unit = {
    val sparkConf = new SparkConf()
    val sc = new SparkContext(sparkConf)
    val sqlContext = new HiveContext(sc)
    sqlContext.sql("use " + ConfigProperties.IOT_HIVE_DATABASE)
    val appName = sc.getConf.get("spark.app.name")
    val userTablePartitionID = sc.getConf.get("spark.app.userTablePartitionID")
    val userTable = sc.getConf.get("spark.app.table.userTable" )//"iot_customer_userinfo"
    val pdsnTable = sc.getConf.get("spark.app.table.pdsn.h") //"iot_cdr_data_pdsn_h" 2/3g
    val pgwTable = sc.getConf.get("spark.app.table.pgw.h") //"iot_cdr_data_pgw_h" 4g
    val hourid = sc.getConf.get("spark.app.hourid")
    val outputPath = sc.getConf.get("spark.app.outputPath") // hdfs://EPC-IOT-ES-06:8020/hadoop/IOT/data/multiAna/flow/
    val localOutputPath =  sc.getConf.get("spark.app.localOutputPath") // /slview/test/limm/multiAna/flow/json/
    val partitionD = hourid.substring(2, 8)
    val partitionH = hourid.substring(8)
    val dayid      = hourid.substring(0, 8)
    // company province nettype
    val tmpCompanyTable = s"${appName}_tmp_Company"
    sqlContext.sql(
      s"""select distinct (case when length(custprovince)=0 or custprovince is null then '其他' else custprovince end)  as custprovince,
         |       case when length(vpdncompanycode)=0 or vpdncompanycode is null then 'N999999999' else vpdncompanycode end  as vpdncompanycode
         |from ${userTable}
         |where d='${userTablePartitionID}'
       """.stripMargin
    ).cache().registerTempTable(tmpCompanyTable)


    val tmpCompanyNetTable = s"${appName}_tmp_CompanyNet"
    val companyDF = sqlContext.sql(
      s"""select custprovince, vpdncompanycode, '2/3G' as nettype from ${tmpCompanyTable}
         |union all
         |select custprovince, vpdncompanycode, '4G' as nettype from ${tmpCompanyTable}
       """.stripMargin
    ).cache()
    val companyName = sqlContext.table("iot_basic_company")
    val companyDF_r = companyDF.join(companyName,companyName.col("companycode")===companyDF.col("vpdncompanycode")).select(
      companyDF.col("custprovince"),companyDF.col("vpdncompanycode"),companyDF.col("nettype"),companyName.col("companyname")
    )
    // 2/3g flow
    val pdsnDF = sqlContext.sql(
      s"""select vpdncompanycode,custprovince,d,h,sum(upflow) as sumupflow,sum(downflow) as sumdownflow,count(distinct mdn) as usernum,
         |      "2/3G" as nettype
         |from  ${pdsnTable}
         |where  d = "${partitionD}" and h ="${partitionH}"
         |group by vpdncompanycode,custprovince,d,h,"2/3G"
       """.stripMargin
    )

    // 4g flow
    val pgwDF = sqlContext.sql(
      s"""select vpdncompanycode,custprovince,d,h,sum(upflow) as sumupflow,sum(downflow) as sumdownflow,count(distinct mdn) as usernum,
         |      "4G" as nettype
         |from  ${pgwTable}
         |where  d = "${partitionD}" and h ="${partitionH}"
         |group by vpdncompanycode,custprovince,d,h,"4G"
       """.stripMargin
    )

    val flowDF = pdsnDF.select(pdsnDF.col("vpdncompanycode"),pdsnDF.col("custprovince"),pdsnDF.col("d"),pdsnDF.col("h"),pdsnDF.col("sumupflow"),
      pdsnDF.col("sumdownflow"),pdsnDF.col("usernum"),
      when(pdsnDF.col("usernum")===0, 0).otherwise((pdsnDF.col("sumupflow")/pdsnDF.col("usernum"))).alias("avgupflow"),
      when(pdsnDF.col("usernum")===0, 0).otherwise((pdsnDF.col("sumdownflow")/pdsnDF.col("usernum"))).alias("avgdownflow"),
      pdsnDF.col("nettype")
    ).unionAll(
      pgwDF.select(pgwDF.col("vpdncompanycode"),pgwDF.col("custprovince"),pgwDF.col("d"),pgwDF.col("h"),pgwDF.col("sumupflow"),
        pgwDF.col("sumdownflow"),pgwDF.col("usernum"),
        when(pgwDF.col("usernum")===0, 0).otherwise((pgwDF.col("sumupflow")/pgwDF.col("usernum"))).alias("avgupflow"),
        when(pgwDF.col("usernum")===0, 0).otherwise((pgwDF.col("sumdownflow")/pgwDF.col("usernum"))).alias("avgdownflow"),
        pgwDF.col("nettype")

      )
    )


    val resultDF = companyDF_r.join(flowDF,Seq("vpdncompanycode","nettype"),"left").
      select(companyDF_r.col("custprovince"),companyDF_r.col("nettype"),companyDF_r.col("companyname"),
        when(flowDF.col("usernum").isNull,0).otherwise(flowDF.col("usernum")).alias("usernum"),
        when(flowDF.col("avgupflow").isNull,0).otherwise(round(flowDF.col("avgupflow"),0)).alias("avgupflow"),
        when(flowDF.col("avgdownflow").isNull,0).otherwise(round(flowDF.col("avgdownflow"),0)).alias("avgdownflow"),
        when(flowDF.col("sumupflow").isNull,0).otherwise(round(flowDF.col("sumupflow"),0)).alias("upflow"),
        when(flowDF.col("sumdownflow").isNull,0).otherwise(round(flowDF.col("sumdownflow"),0)).alias("downflow")
      ).withColumn("datetime", lit(hourid))




    val coalesceNum = 1
    val outputLocatoin = outputPath + "json/data/" + dayid + "/" + partitionH

    val fileSystem = FileSystem.newInstance(sc.hadoopConfiguration)

    resultDF.repartition(coalesceNum.toInt).write.mode(SaveMode.Overwrite).format("json").save(outputLocatoin)

    FileUtils.downFilesToLocal(fileSystem, outputLocatoin, localOutputPath + "/"+ dayid + "/", partitionH, ".json")

    sc.stop()
  }

}





