package org.sense.flink.examples.stream.tpch;

import static org.sense.flink.util.MetricLabels.OPERATOR_SINK;
import static org.sense.flink.util.SinkOutputs.PARAMETER_OUTPUT_FILE;
import static org.sense.flink.util.SinkOutputs.PARAMETER_OUTPUT_LOG;
import static org.sense.flink.util.SinkOutputs.PARAMETER_OUTPUT_MQTT;

import java.io.Serializable;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Date;
import java.util.Iterator;
import java.util.List;

import org.apache.flink.api.common.functions.AggregateFunction;
import org.apache.flink.api.common.functions.FilterFunction;
import org.apache.flink.api.common.functions.RichMapFunction;
import org.apache.flink.api.common.functions.RichReduceFunction;
import org.apache.flink.api.common.state.ListState;
import org.apache.flink.api.common.state.ListStateDescriptor;
import org.apache.flink.api.java.functions.KeySelector;
import org.apache.flink.api.java.tuple.Tuple3;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.contrib.streaming.state.RocksDBStateBackend;
import org.apache.flink.shaded.guava18.com.google.common.collect.Iterators;
import org.apache.flink.streaming.api.TimeCharacteristic;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.functions.timestamps.AscendingTimestampExtractor;
import org.apache.flink.streaming.api.functions.windowing.ProcessWindowFunction;
import org.apache.flink.streaming.api.windowing.time.Time;
import org.apache.flink.streaming.api.windowing.windows.TimeWindow;
import org.apache.flink.util.Collector;
import org.sense.flink.examples.stream.tpch.pojo.Customer;
import org.sense.flink.examples.stream.tpch.pojo.LineItem;
import org.sense.flink.examples.stream.tpch.pojo.Order;
import org.sense.flink.examples.stream.tpch.udf.CustomerSource;
import org.sense.flink.examples.stream.tpch.udf.LineItemSource;
import org.sense.flink.examples.stream.tpch.udf.OrdersSource;
import org.sense.flink.mqtt.MqttStringPublisher;
import org.sense.flink.util.CpuGauge;

import net.openhft.affinity.impl.LinuxJNAAffinity;

/**
 * Implementation of the TPC-H Benchmark query 03 also available at:
 * 
 * https://github.com/apache/flink/blob/master/flink-examples/flink-examples-batch/src/main/java/org/apache/flink/examples/java/relational/TPCHQuery3.java
 * 
 * The original query can be found at:
 * 
 * https://docs.deistercloud.com/content/Databases.30/TPCH%20Benchmark.90/Sample%20querys.20.xml
 * 
 * 
 * @author Felipe Oliveira Gutierrez
 *
 */
public class TPCHQuery03 {

	private final String topic = "topic-tpch-query-03";

	public static void main(String[] args) throws Exception {
		new TPCHQuery03();
	}

	public TPCHQuery03() throws Exception {
		this(PARAMETER_OUTPUT_LOG, "127.0.0.1", false, true);
	}

	public TPCHQuery03(String output, String ipAddressSink, boolean disableOperatorChaining, boolean pinningPolicy)
			throws Exception {
		StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
		env.setStateBackend(new RocksDBStateBackend("file:///tmp/flink/state", true));
		env.setStreamTimeCharacteristic(TimeCharacteristic.ProcessingTime);

		if (disableOperatorChaining) {
			env.disableOperatorChaining();
		}

		// @formatter:off
		DataStream<Order> orders = env
				.addSource(new OrdersSource()).name(OrdersSource.class.getSimpleName()).uid(OrdersSource.class.getSimpleName())
				.assignTimestampsAndWatermarks(new OrderTimestampAndWatermarkAssigner());

		// Filter market segment "AUTOMOBILE"
		// customers = customers.filter(new CustomerFilter());

		// Filter all Orders with o_orderdate < 12.03.1995
		// orders = orders.filter(new OrderFilter());

		// Join customers with orders and package them into a ShippingPriorityItem
		DataStream<ShippingPriorityItem> shippingPriorityStream01 = orders
				.keyBy(new OrderCustomerKeySelector())
				.timeWindow(Time.seconds(10))
				.aggregate(new OrderCustomerAggregator(), new OrderProcessWindowFunction(pinningPolicy)).name(OrderProcessWindowFunction.class.getSimpleName()).uid(OrderProcessWindowFunction.class.getSimpleName());

		// Join the last join result with Lineitems
		DataStream<ShippingPriorityItem> shippingPriorityStream02 = shippingPriorityStream01
				.keyBy(new ShippingPriorityKeySelector())
				.timeWindow(Time.seconds(60))
				.aggregate(new ShippingPriorityItemAggregator(), new ShippingPriorityProcessWindowFunction(pinningPolicy)).name(ShippingPriorityProcessWindowFunction.class.getSimpleName()).uid(ShippingPriorityProcessWindowFunction.class.getSimpleName());

		// Group by l_orderkey, o_orderdate and o_shippriority and compute revenue sum
		DataStream<ShippingPriorityItem> shippingPriorityStream03 = shippingPriorityStream02
				.keyBy(new ShippingPriority3KeySelector())
				.reduce(new SumShippingPriorityItem(pinningPolicy)).name(SumShippingPriorityItem.class.getSimpleName()).uid(SumShippingPriorityItem.class.getSimpleName());

		// emit result
		if (output.equalsIgnoreCase(PARAMETER_OUTPUT_MQTT)) {
			shippingPriorityStream03
				.map(new ShippingPriorityItemMap(pinningPolicy)).name(ShippingPriorityItemMap.class.getSimpleName()).uid(ShippingPriorityItemMap.class.getSimpleName())
				.addSink(new MqttStringPublisher(ipAddressSink, topic, pinningPolicy)) ; // .name(OPERATOR_SINK).uid(OPERATOR_SINK);
		} else if (output.equalsIgnoreCase(PARAMETER_OUTPUT_LOG)) {
			shippingPriorityStream03.print().name(OPERATOR_SINK).uid(OPERATOR_SINK);
		} else if (output.equalsIgnoreCase(PARAMETER_OUTPUT_FILE)) {
			shippingPriorityStream03.print().name(OPERATOR_SINK).uid(OPERATOR_SINK);
		} else {
			System.out.println("discarding output");
		}
		// @formatter:on

		System.out.println("Execution plan >>>\n" + env.getExecutionPlan());
		env.execute(TPCHQuery03.class.getSimpleName());
	}

	private static class OrderCustomerAggregator implements AggregateFunction<Order, List<Order>, List<Order>> {
		private static final long serialVersionUID = 1L;

		@Override
		public List<Order> createAccumulator() {
			return new ArrayList<Order>();
		}

		@Override
		public List<Order> add(Order value, List<Order> accumulator) {
			accumulator.add(value);
			return accumulator;
		}

		@Override
		public List<Order> getResult(List<Order> accumulator) {
			return accumulator;
		}

		@Override
		public List<Order> merge(List<Order> a, List<Order> b) {
			a.addAll(b);
			return a;
		}

	}

	private static class OrderProcessWindowFunction
			extends ProcessWindowFunction<List<Order>, ShippingPriorityItem, Long, TimeWindow> {
		private static final long serialVersionUID = 1L;

		private ListState<Customer> customerList = null;
		private transient CpuGauge cpuGauge;
		private BitSet affinity;
		private boolean pinningPolicy;

		public OrderProcessWindowFunction(boolean pinningPolicy) {
			this.pinningPolicy = pinningPolicy;
		}

		@Override
		public void open(Configuration parameters) throws Exception {
			super.open(parameters);

			this.cpuGauge = new CpuGauge();
			getRuntimeContext().getMetricGroup().gauge("cpu", cpuGauge);

			if (this.pinningPolicy) {
				// listing the cpu cores available
				int nbits = Runtime.getRuntime().availableProcessors();
				// pinning operator' thread to a specific cpu core
				this.affinity = new BitSet(nbits);
				affinity.set(((int) Thread.currentThread().getId() % nbits));
				LinuxJNAAffinity.INSTANCE.setAffinity(affinity);
			}

			ListStateDescriptor<Customer> customerDescriptor = new ListStateDescriptor<Customer>("customerState",
					Customer.class);
			customerList = getRuntimeContext().getListState(customerDescriptor);
		}

		@Override
		public void process(Long key,
				ProcessWindowFunction<List<Order>, ShippingPriorityItem, Long, TimeWindow>.Context context,
				Iterable<List<Order>> input, Collector<ShippingPriorityItem> out) throws Exception {
			// updates the CPU core current in use
			this.cpuGauge.updateValue(LinuxJNAAffinity.INSTANCE.getCpu());

			if (customerList != null && Iterators.size(customerList.get().iterator()) == 0) {
				CustomerSource customerSource = new CustomerSource();
				List<Customer> customers = customerSource.getCustomers();
				customerList.addAll(customers);
			}

			for (Customer customer : customerList.get()) {
				// System.out.println("Customer: " + customer);
				for (Iterator<List<Order>> iterator = input.iterator(); iterator.hasNext();) {
					List<Order> orders = (List<Order>) iterator.next();

					for (Order order : orders) {
						// System.out.println("Order: " + order);
						if (order.getCustomerKey() == customer.getCustomerKey()) {
							try {
								ShippingPriorityItem spi = new ShippingPriorityItem(order.getOrderKey(), 0.0,
										OrdersSource.format(order.getOrderDate()), order.getShipPriority());
								// System.out.println("OrderProcessWindowFunction TRUE: " + spi);
								out.collect(spi);
							} catch (Exception e) {
								e.printStackTrace();
							}
						}
					}
				}
			}
		}
	}

	private static class ShippingPriorityItemAggregator
			implements AggregateFunction<ShippingPriorityItem, List<ShippingPriorityItem>, List<ShippingPriorityItem>> {
		private static final long serialVersionUID = 1L;

		@Override
		public List<ShippingPriorityItem> createAccumulator() {
			return new ArrayList<ShippingPriorityItem>();
		}

		@Override
		public List<ShippingPriorityItem> add(ShippingPriorityItem value, List<ShippingPriorityItem> accumulator) {
			accumulator.add(value);
			return accumulator;
		}

		@Override
		public List<ShippingPriorityItem> getResult(List<ShippingPriorityItem> accumulator) {
			return accumulator;
		}

		@Override
		public List<ShippingPriorityItem> merge(List<ShippingPriorityItem> a, List<ShippingPriorityItem> b) {
			a.addAll(b);
			return a;
		}
	}

	private static class ShippingPriorityProcessWindowFunction
			extends ProcessWindowFunction<List<ShippingPriorityItem>, ShippingPriorityItem, Long, TimeWindow> {
		private static final long serialVersionUID = 1L;

		private ListState<LineItem> lineItemList = null;

		private transient CpuGauge cpuGauge;
		private BitSet affinity;
		private boolean pinningPolicy;

		public ShippingPriorityProcessWindowFunction(boolean pinningPolicy) {
			this.pinningPolicy = pinningPolicy;
		}

		@Override
		public void open(Configuration parameters) throws Exception {
			super.open(parameters);

			this.cpuGauge = new CpuGauge();
			getRuntimeContext().getMetricGroup().gauge("cpu", cpuGauge);

			if (this.pinningPolicy) {
				// listing the cpu cores available
				int nbits = Runtime.getRuntime().availableProcessors();
				// pinning operator' thread to a specific cpu core
				this.affinity = new BitSet(nbits);
				affinity.set(((int) Thread.currentThread().getId() % nbits));
				LinuxJNAAffinity.INSTANCE.setAffinity(affinity);
			}

			ListStateDescriptor<LineItem> lineItemDescriptor = new ListStateDescriptor<LineItem>("lineItemState",
					LineItem.class);
			lineItemList = getRuntimeContext().getListState(lineItemDescriptor);
		}

		@Override
		public void process(Long key,
				ProcessWindowFunction<List<ShippingPriorityItem>, ShippingPriorityItem, Long, TimeWindow>.Context context,
				Iterable<List<ShippingPriorityItem>> input, Collector<ShippingPriorityItem> out) throws Exception {
			// updates the CPU core current in use
			this.cpuGauge.updateValue(LinuxJNAAffinity.INSTANCE.getCpu());

			if (lineItemList != null && Iterators.size(lineItemList.get().iterator()) == 0) {
				LineItemSource lineItemSource = new LineItemSource();
				List<LineItem> lineItems = lineItemSource.getLineItems();
				lineItemList.addAll(lineItems);
			}

			for (LineItem lineItem : lineItemList.get()) {
				// System.out.println("LineItem: " + lineItem);
				for (Iterator<List<ShippingPriorityItem>> iterator = input.iterator(); iterator.hasNext();) {
					List<ShippingPriorityItem> shippingPriorityItemList = (List<ShippingPriorityItem>) iterator.next();

					for (ShippingPriorityItem shippingPriorityItem : shippingPriorityItemList) {
						// System.out.println("ShippingPriorityItem: " + shippingPriorityItem);
						if (shippingPriorityItem.getOrderkey().equals(lineItem.getRowNumber())) {
							// System.out.println("ShippingPriorityProcessWindowFunction TRUE: " +
							// shippingPriorityItem);
							out.collect(shippingPriorityItem);
						}
					}
				}
			}
		}
	}

	private static class OrderTimestampAndWatermarkAssigner extends AscendingTimestampExtractor<Order> {
		private static final long serialVersionUID = 1L;

		@Override
		public long extractAscendingTimestamp(Order element) {
			return element.getTimestamp();
		}
	}

	private static class CustomerFilter implements FilterFunction<Customer> {
		private static final long serialVersionUID = 1L;

		@Override
		public boolean filter(Customer c) {
			return c.getMarketSegment().equals("AUTOMOBILE");
		}
	}

	private static class OrderFilter implements FilterFunction<Order> {
		private static final long serialVersionUID = 1L;
		private final DateFormat sdf = new SimpleDateFormat("yyyy-MM-dd");
		private final Date date;

		public OrderFilter() throws ParseException {
			date = sdf.parse("1995-03-12");
		}

		@Override
		public boolean filter(Order o) throws ParseException {
			Date orderDate = new Date(o.getOrderDate());
			return orderDate.before(date);
		}
	}

	private static class OrderCustomerKeySelector implements KeySelector<Order, Long> {
		private static final long serialVersionUID = 1L;

		@Override
		public Long getKey(Order value) throws Exception {
			return value.getCustomerKey();
		}
	}

	private static class ShippingPriorityKeySelector implements KeySelector<ShippingPriorityItem, Long> {
		private static final long serialVersionUID = 1L;

		@Override
		public Long getKey(ShippingPriorityItem value) throws Exception {
			return value.getOrderkey();
		}
	}

	private static class ShippingPriorityItem implements Serializable {
		private static final long serialVersionUID = 1L;

		private Long orderkey;
		private Double revenue;
		private String orderdate;
		private Integer shippriority;

		public ShippingPriorityItem(Long orderkey, Double revenue, String orderdate, Integer shippriority) {
			this.orderkey = orderkey;
			this.revenue = revenue;
			this.orderdate = orderdate;
			this.shippriority = shippriority;
		}

		public Long getOrderkey() {
			return orderkey;
		}

		public void setOrderkey(Long orderkey) {
			this.orderkey = orderkey;
		}

		public Double getRevenue() {
			return revenue;
		}

		public void setRevenue(Double revenue) {
			this.revenue = revenue;
		}

		public String getOrderdate() {
			return orderdate;
		}

		public void setOrderdate(String orderdate) {
			this.orderdate = orderdate;
		}

		public Integer getShippriority() {
			return shippriority;
		}

		public void setShippriority(Integer shippriority) {
			this.shippriority = shippriority;
		}

		@Override
		public String toString() {
			return "ShippingPriorityItem [getOrderkey()=" + getOrderkey() + ", getRevenue()=" + getRevenue()
					+ ", getOrderdate()=" + getOrderdate() + ", getShippriority()=" + getShippriority() + "]";
		}
	}

	private static class ShippingPriority3KeySelector
			implements KeySelector<ShippingPriorityItem, Tuple3<Long, String, Integer>> {
		private static final long serialVersionUID = 1L;

		@Override
		public Tuple3<Long, String, Integer> getKey(ShippingPriorityItem value) throws Exception {
			return Tuple3.of(value.getOrderkey(), value.getOrderdate(), value.getShippriority());
		}
	}

	private static class SumShippingPriorityItem extends RichReduceFunction<ShippingPriorityItem> {
		private static final long serialVersionUID = 1L;

		private transient CpuGauge cpuGauge;
		private BitSet affinity;
		private boolean pinningPolicy;

		public SumShippingPriorityItem(boolean pinningPolicy) {
			this.pinningPolicy = pinningPolicy;
		}

		@Override
		public void open(Configuration parameters) throws Exception {
			super.open(parameters);

			this.cpuGauge = new CpuGauge();
			getRuntimeContext().getMetricGroup().gauge("cpu", cpuGauge);

			if (this.pinningPolicy) {
				// listing the cpu cores available
				int nbits = Runtime.getRuntime().availableProcessors();
				// pinning operator' thread to a specific cpu core
				this.affinity = new BitSet(nbits);
				affinity.set(((int) Thread.currentThread().getId() % nbits));
				LinuxJNAAffinity.INSTANCE.setAffinity(affinity);
			}
		}

		@Override
		public ShippingPriorityItem reduce(ShippingPriorityItem value1, ShippingPriorityItem value2) throws Exception {
			// updates the CPU core current in use
			this.cpuGauge.updateValue(LinuxJNAAffinity.INSTANCE.getCpu());

			ShippingPriorityItem shippingPriorityItem = new ShippingPriorityItem(value1.getOrderkey(),
					value1.getRevenue() + value2.getRevenue(), value1.getOrderdate(), value1.getShippriority());
			return shippingPriorityItem;
		}
	}

	private static class ShippingPriorityItemMap extends RichMapFunction<ShippingPriorityItem, String> {
		private static final long serialVersionUID = 1L;

		private transient CpuGauge cpuGauge;
		private BitSet affinity;
		private boolean pinningPolicy;

		public ShippingPriorityItemMap(boolean pinningPolicy) {
			this.pinningPolicy = pinningPolicy;
		}

		@Override
		public void open(Configuration parameters) throws Exception {
			super.open(parameters);

			this.cpuGauge = new CpuGauge();
			getRuntimeContext().getMetricGroup().gauge("cpu", cpuGauge);

			if (this.pinningPolicy) {
				// listing the cpu cores available
				int nbits = Runtime.getRuntime().availableProcessors();
				// pinning operator' thread to a specific cpu core
				this.affinity = new BitSet(nbits);
				affinity.set(((int) Thread.currentThread().getId() % nbits));
				LinuxJNAAffinity.INSTANCE.setAffinity(affinity);
			}
		}

		@Override
		public String map(ShippingPriorityItem value) throws Exception {
			// updates the CPU core current in use
			this.cpuGauge.updateValue(LinuxJNAAffinity.INSTANCE.getCpu());

			return value.toString();
		}
	}
}
