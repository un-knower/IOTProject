package com.zyuc.stat.utils

import java.util.Properties

import kafka.common.TopicAndPartition
import kafka.message.MessageAndMetadata
import kafka.serializer.StringDecoder
import org.I0Itec.zkclient.ZkClient
import org.I0Itec.zkclient.exception.{ZkNodeExistsException, ZkNoNodeException}
import org.apache.kafka.clients.producer.{ProducerRecord, KafkaProducer}
import org.apache.spark.rdd.RDD
import org.apache.spark.streaming.StreamingContext
import org.apache.spark.streaming.dstream.InputDStream
import org.apache.spark.streaming.kafka.{KafkaUtils, HasOffsetRanges}

/**
 * Created by cuihs on 2017/6/14.
 */
object SparkKafkaUtils extends Serializable {
  // 从zookeeper获取offset信息
  def createDirectKafkaStream (ssc: StreamingContext, kafkaParams: Map[String, String],
                               zkClient: ZkClient, topics: Set[String], groupName: String
                                ): InputDStream[(String, String)] = {
    val (fromOffsets, flag) = SparkKafkaUtils.getFromOffsets(zkClient, topics, groupName)
    println("flag = " + flag)

    var kafkaStream : InputDStream[(String, String)] = null
    if (flag == 1) {
      // 这个会将kafka的消息进行transform,最终kafak的数据都会变成(topic_name, message)这样的tuple
      val messageHandler = (mmd : MessageAndMetadata[String, String]) => (mmd.topic, mmd.message())
      kafkaStream = KafkaUtils.createDirectStream[String, String, StringDecoder, StringDecoder, (String, String)](ssc, kafkaParams, fromOffsets, messageHandler)
      //      kafkaStream = KafkaUtils.createDirectStream[String, String, StringDecoder, StringDecoder](ssc, kafkaParams, topics)
    } else {
      // 如果未保存,根据kafkaParam的配置使用最新或者最旧的offset
      kafkaStream = KafkaUtils.createDirectStream[String, String, StringDecoder, StringDecoder](ssc, kafkaParams, topics)
    }
    kafkaStream
  }

  def getFromOffsets(zkClient : ZkClient,topics : Set[String],groupName : String): (Map[TopicAndPartition, Long], Int) = {
    // 如果 zookeeper中有保存offset,我们会利用这个offset作为kafkaStream 的起始位置
    var fromOffsets: Map[TopicAndPartition, Long] = Map()
    // 查看是否有未配置的topic
    var flag = 1
    topics.foreach(
      topic => {
        // 拼接zkTopicPath
        val zkTopicPath = "/consumers/" + groupName + "/offsets/" + topic

        // 查询该路径下是否字节点（默认有字节点为我们自己保存不同 partition 时生成的）
        val children = zkClient.countChildren(zkTopicPath)
        println("children is " + children)

        // 如果保存过 offset,这里更好的做法,还应该和kafka上最小的offset做对比,不然会报OutOfRange的错误
        if (children > 0) {
          for (i <- 0 until children) {
            val partitionOffset = zkClient.readData[String](s"${zkTopicPath}/${i}")
            val tp = TopicAndPartition(topic, i)
            //将不同 partition 对应的 offset 增加到 fromOffsets 中
            fromOffsets += (tp -> partitionOffset.toLong)
            println("consume record: topic[" + topic + "] partition[" + i + "] offset[" + partitionOffset + "]")
          }
        } else {
          flag = 0
        }
      }
    )

    (fromOffsets,flag)
  }

  def saveOffsets(zkClient: ZkClient, groupName: String, rdd: RDD[_]) = {
    val offsetsRanges = rdd.asInstanceOf[HasOffsetRanges].offsetRanges

    for (o <- offsetsRanges) {
      val zkPath = s"/consumers/${groupName}/offsets/${o.topic}/${o.partition}"
      // 将该partition的offset保存到zookeeper
      SparkKafkaUtils.updatePersistentPath(zkClient, zkPath, o.untilOffset.toString)
      println(s"deal consume topic ${o.topic} partition ${o.partition} fromoffset ${o.fromOffset} untiloffset ${o.untilOffset}")
    }
  }

  def updatePersistentPath(client: ZkClient, path: String, data: String) = {
    try {
      client.writeData(path, data)
    } catch {
      case e: ZkNoNodeException => {
        createParentPath(client, path)
        try {
          client.createPersistent(path, data)
        } catch {
          case e: ZkNodeExistsException =>
            client.writeData(path, data)
          case e2: Throwable => throw e2
        }
      }
      case e2: Throwable => throw e2
    }
  }

  private def createParentPath(client: ZkClient, path: String) = {
    val parentDir = path.substring(0, path.lastIndexOf('/'))
    if (parentDir.length != 0)
      client.createPersistent(parentDir, true)
  }

  def createBroker(brokers: String): KafkaProducer[String, String] = {
    val props = new Properties()
    props.put("bootstrap.servers", brokers)
    props.put("key.serializer", "org.apache.kafka.common.serialization.StringSerializer")
    props.put("value.serializer", "org.apache.kafka.common.serialization.StringSerializer")
    val producer = new KafkaProducer[String, String](props)

    producer
  }

  def sendKafka (producer : KafkaProducer[String, String],topic : String,msg : String) = {
    val record = new ProducerRecord[String, String](topic, msg)
    producer.send(record)
  }

  def getZkConnect(hostAndPortList: String):ZkClient = {
    val zkClient = new ZkClient(hostAndPortList)

    zkClient
  }
}