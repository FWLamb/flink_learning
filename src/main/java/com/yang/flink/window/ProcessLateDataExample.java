package com.yang.flink.window;

import com.yang.flink.pojo.Event;
import com.yang.flink.pojo.UrlViewCount;
import org.apache.flink.api.common.eventtime.SerializableTimestampAssigner;
import org.apache.flink.api.common.eventtime.WatermarkStrategy;
import org.apache.flink.api.common.functions.AggregateFunction;
import org.apache.flink.api.common.functions.MapFunction;
import org.apache.flink.streaming.api.datastream.SingleOutputStreamOperator;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.functions.windowing.ProcessWindowFunction;
import org.apache.flink.streaming.api.windowing.assigners.TumblingEventTimeWindows;
import org.apache.flink.streaming.api.windowing.time.Time;
import org.apache.flink.streaming.api.windowing.windows.TimeWindow;
import org.apache.flink.util.Collector;
import org.apache.flink.util.OutputTag;

import java.time.Duration;

/**
 * @author Bin
 * @date 2022/4/26 9:43
 * @Description
 */
public class ProcessLateDataExample {
    public static void main(String[] args) throws Exception {
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        env.setParallelism(1);

        SingleOutputStreamOperator<Event> stream = env.socketTextStream("localhost", 7777)
                .map((MapFunction<String, Event>) value -> {
                    String[] fields = value.split(" ");
                    return new Event(fields[0].trim(), fields[1].trim(), Long.valueOf(fields[2].trim()));
                })
                // 方式一：设置 watermark 延迟时间，2 秒钟
                .assignTimestampsAndWatermarks(
                        WatermarkStrategy.<Event>forBoundedOutOfOrderness(Duration.ofSeconds(2))
                                .withTimestampAssigner(
                                        (SerializableTimestampAssigner<Event>) (element, recordTimestamp) -> element.timestamp));

        // 定义侧输出流标签
        OutputTag<Event> outputTag = new OutputTag<Event>("late"){};
        SingleOutputStreamOperator<UrlViewCount> result = stream.keyBy(data -> data.url)
                .window(TumblingEventTimeWindows.of(Time.seconds(10)))
                // 方式二：允许窗口处理迟到数据，设置 1 分钟的等待时间
                .allowedLateness(Time.minutes(1))
                // 方式三：将最后的迟到数据输出到侧输出流
                .sideOutputLateData(outputTag)
                .aggregate(new UrlViewCountAgg(),
                        new ProcessWindowFunction<Long, UrlViewCount, String, TimeWindow>() {
                            @Override
                            public void process(String url, Context context, Iterable<Long> elements,
                                                Collector<UrlViewCount> out) throws Exception {
                                // 结合窗口信息，包装输出内容
                                Long start = context.window().getStart();
                                Long end = context.window().getEnd();
                                out.collect(new UrlViewCount(url, elements.iterator().next(), start, end));
                            }
                        });
        result.print("result");
        result.getSideOutput(outputTag).print("late");

        env.execute();
    }

    public static class UrlViewCountAgg implements AggregateFunction<Event, Long, Long> {
        @Override
        public Long createAccumulator() {
            return 0L;
        }

        @Override
        public Long add(Event value, Long accumulator) {
            return accumulator + 1;
        }

        @Override
        public Long getResult(Long accumulator) {
            return accumulator;
        }

        @Override
        public Long merge(Long a, Long b) {
            return null;
        }

    }
}
