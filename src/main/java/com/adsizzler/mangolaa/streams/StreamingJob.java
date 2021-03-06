package com.adsizzler.mangolaa.streams;


import com.adsizzler.mangolaa.streams.aggregations.*;
import com.adsizzler.mangolaa.streams.aggregations.functions.*;
import com.adsizzler.mangolaa.streams.constants.KafkaTopics;
import com.adsizzler.mangolaa.streams.deserializers.*;
import com.adsizzler.mangolaa.streams.domain.*;
import com.adsizzler.mangolaa.streams.keys.*;
import com.adsizzler.mangolaa.streams.serializers.JsonSerializer;
import lombok.val;
import org.apache.flink.contrib.streaming.state.RocksDBStateBackend;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.windowing.time.Time;
import org.apache.flink.streaming.connectors.kafka.FlinkKafkaConsumer011;
import org.apache.flink.streaming.connectors.kafka.FlinkKafkaProducer011;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;

import java.util.Properties;

public class StreamingJob {

	public static void main(String[] args) throws Exception {
		val flinkEnv = StreamExecutionEnvironment.getExecutionEnvironment();

		//Using RocksDB backend.
		flinkEnv.setStateBackend(new RocksDBStateBackend("file:///data/flink/checkpoints"));

		// Deserializers
		val bidReqGzipJsonDeserializer = new BidReqGzipJsonDeserializer();
		val bidRespGzipJsonDeserializer = new BidRespGzipJsonDeserializer();
		val winNotificationGzipJsonDeserializer = new WinNotificationGzipJsonDeserializer();
		val impressionGzipJsonDeserializer = new ImpressionGzipJsonDeserializer();
		val clickGzipJsonDeserializer = new ClickGzipJsonDeserializer();

		//Kafka v 0.11 is the source of the stream
		val bidReqKafkaConsumer  = new FlinkKafkaConsumer011<BidReq>(KafkaTopics.BID_REQ, bidReqGzipJsonDeserializer, kafkaProperties());
		val bidRespKafkaConsumer = new FlinkKafkaConsumer011<BidResp>(KafkaTopics.BID_RESPONSE, bidRespGzipJsonDeserializer, kafkaProperties());
		val winNotificationKafkaConsumer = new FlinkKafkaConsumer011<WinNotification>(KafkaTopics.WINS, winNotificationGzipJsonDeserializer, kafkaProperties());
		val impressionsKafkaConsumer = new FlinkKafkaConsumer011<Impression>(KafkaTopics.IMPRESSIONS, impressionGzipJsonDeserializer, kafkaProperties());
		val clicksKafkaConsumer = new FlinkKafkaConsumer011<Click>(KafkaTopics.CLICKS, clickGzipJsonDeserializer, kafkaProperties());

		//Streams
		val bidReqStream = flinkEnv.addSource(bidReqKafkaConsumer);
		val bidRespStream = flinkEnv.addSource(bidRespKafkaConsumer);
		val winNotificationStream = flinkEnv.addSource(winNotificationKafkaConsumer);
		val impressionStream = flinkEnv.addSource(impressionsKafkaConsumer);
		val clickStream = flinkEnv.addSource(clicksKafkaConsumer);

		//Windowed Stream
		val bidReqWindowedStream = bidReqStream
						.keyBy(new AggregatedBidReqKey())
						.timeWindow(Time.minutes(1));

		val bidRespWindowedStream = bidRespStream
						.keyBy(new AggregatedBidRespKey())
						.timeWindow(Time.minutes(1));

		val winNotificationWindowedStream = winNotificationStream
						.keyBy(new AggregatedWinNotificationKey())
						.timeWindow(Time.minutes(1));

		val impressionWindowedStream = impressionStream
						.keyBy(new AggregatedImpressionKey())
						.timeWindow(Time.minutes(1));

		val clickWindowedStream = clickStream
						.keyBy(new AggregatedClickKey())
						.timeWindow(Time.minutes(1));


		// Aggregated Streams
		val aggregatedBidReqStream = bidReqWindowedStream
						.apply(new BidReqWindowCountFunction())
						.name("Count Bid Requests in a Windowed Stream");

		val aggregatedBidRespStream = bidRespWindowedStream
						.apply(new BidRespWindowCountFunction())
						.name("Count Bid Responses in a Windowed Stream");

		val aggregatedWinStream = winNotificationWindowedStream
						.apply(new WinNotificationCountFunction())
						.name("Counting WinNotifications in a Windowed Stream");

		val aggregatedImpressionStream = impressionWindowedStream
						.apply(new ImpressionCountFunction())
						.name("Counting Impression in a Windowed Stream");

		val aggregatedClickStream = clickWindowedStream
						.apply(new ClickCountFunction())
						.name("Counting Clicks in a Windowed Stream");

		//Serializers for Aggregated objects
		val aggregatedBidReqJsonSerializer = new JsonSerializer<AggregatedBidReq>();
		val aggregatedBidRespJsonSerializer = new JsonSerializer<AggregatedBidResp>();
		val aggregatedWinNotificationJsonSerializer =  new JsonSerializer<AggregatedWin>();
		val aggregatedImpressionJsonSerializer = new JsonSerializer<AggregatedImpression>();
		val aggregatedClickJsonSerializer = new JsonSerializer<AggregatedClick>();

		//Sinks for Aggregated objects
		val aggregatedBidReqKafkaSink = new FlinkKafkaProducer011<AggregatedBidReq>(KafkaTopics.AGGREGATED_BID_REQ, aggregatedBidReqJsonSerializer, kafkaProperties());
		val aggregatedBidRespKafkaSink = new FlinkKafkaProducer011<AggregatedBidResp>(KafkaTopics.AGGREGATED_BID_RESP, aggregatedBidRespJsonSerializer, kafkaProperties());
		val aggregatedWinKafkaSink = new FlinkKafkaProducer011<AggregatedWin>(KafkaTopics.AGGREGATED_WINS, aggregatedWinNotificationJsonSerializer, kafkaProperties());
		val aggregatedImpressionKafkaSink = new FlinkKafkaProducer011<AggregatedImpression>(KafkaTopics.AGGREGATED_IMPRESSIONS, aggregatedImpressionJsonSerializer, kafkaProperties());
		val aggregatedClickKafkaSink = new FlinkKafkaProducer011<AggregatedClick>(KafkaTopics.AGGREGATED_CLICKS, aggregatedClickJsonSerializer, kafkaProperties());

		//Attach sink to aggregated streams
		aggregatedBidReqStream.addSink(aggregatedBidReqKafkaSink);
		aggregatedBidRespStream.addSink(aggregatedBidRespKafkaSink);
		aggregatedWinStream.addSink(aggregatedWinKafkaSink);
		aggregatedImpressionStream.addSink(aggregatedImpressionKafkaSink);
		aggregatedClickStream.addSink(aggregatedClickKafkaSink);

     		//execute program
		flinkEnv.execute("Count events in a time window for the Mangolaa platform");
	}

	private static Properties kafkaProperties(){
		val properties = new Properties();
		//Each key in Kafka is String
		properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
		//Each value is a byte[] (Each value is a JSON string encoded as bytes)
		properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
		
		properties.setProperty("zookeeper.connect", "localhost:2181"); // Zookeeper default host:port
		properties.setProperty("bootstrap.servers", "localhost:9092"); // Broker default host:port
		properties.setProperty("group.id", "mangolaa-flink-streams-processor");
		return properties;
	}



}

