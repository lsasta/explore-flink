package org.sense.flink.examples.stream.udf.impl;

import org.apache.flink.api.java.tuple.Tuple2;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.dropwizard.metrics.DropwizardMeterWrapper;
import org.apache.flink.metrics.Meter;
import org.apache.flink.streaming.api.functions.sink.RichSinkFunction;
import org.sense.flink.mqtt.CompositeKeySensorTypePlatformStation;
import org.sense.flink.mqtt.MqttSensor;

public class PrinterSink extends RichSinkFunction<Tuple2<CompositeKeySensorTypePlatformStation, MqttSensor>> {
	private static final long serialVersionUID = 5210213011373679518L;

	// Create metrics
	// private transient Counter counter;
	private transient Meter meter;

	@Override
	public void open(Configuration parameters) throws Exception {
		super.open(parameters);
		// this.counter=getRuntimeContext().getMetricGroup().counter("counterSensorTypeMapper");
		com.codahale.metrics.Meter dropwizardMeter = new com.codahale.metrics.Meter();
		this.meter = getRuntimeContext().getMetricGroup().meter(PrinterSink.class.getSimpleName() + "-meter",
				new DropwizardMeterWrapper(dropwizardMeter));
	}

	@Override
	public void invoke(Tuple2<CompositeKeySensorTypePlatformStation, MqttSensor> value) throws Exception {
		this.meter.markEvent();
		// this.counter.inc();
		System.out.println(PrinterSink.class.getSimpleName() + ": " + value);
		System.out.flush();
	}
}
