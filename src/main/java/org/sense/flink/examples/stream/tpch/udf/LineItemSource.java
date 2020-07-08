package org.sense.flink.examples.stream.tpch.udf;

import static org.sense.flink.util.MetricLabels.TPCH_DATA_LINE_ITEM;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;

import org.apache.flink.api.java.tuple.Tuple2;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.streaming.api.functions.source.RichSourceFunction;
import org.sense.flink.examples.stream.tpch.pojo.LineItem;
import org.sense.flink.util.DataRateListener;

public class LineItemSource extends RichSourceFunction<LineItem> {

	private static final long serialVersionUID = 1L;

	public static final transient DateFormat df = new SimpleDateFormat("yyyy-MM-dd");
	private final String dataFilePath;
	private DataRateListener dataRateListener;
	private boolean running;

	public LineItemSource() {
		this(TPCH_DATA_LINE_ITEM);
	}

	public LineItemSource(String dataFilePath) {
		this.running = true;
		this.dataFilePath = dataFilePath;
	}

	@Override
	public void open(Configuration parameters) throws Exception {
		super.open(parameters);
		this.dataRateListener = new DataRateListener();
		this.dataRateListener.start();
	}

	@Override
	public void run(SourceContext<LineItem> sourceContext) {
		try {
			while (running) {
				generateLineItem(sourceContext);
				Thread.sleep(2 * 1000);
			}
		} catch (InterruptedException e) {
			e.printStackTrace();
		}
	}

	private void generateLineItem(SourceContext<LineItem> sourceContext) {
		try {
			InputStream stream = new FileInputStream(dataFilePath);
			BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8));

			long startTime = System.nanoTime();
			String line = reader.readLine();
			while (line != null) {
				sourceContext.collect(getLineItem(line));

				// sleep in nanoseconds to have a reproducible data rate for the data source
				this.dataRateListener.busySleep(startTime);

				// get start time and line for the next iteration
				startTime = System.nanoTime();
				line = reader.readLine();
			}
			reader.close();
			reader = null;
			stream.close();
			stream = null;
		} catch (FileNotFoundException e) {
			System.err.println("Please make sure they are available at [" + dataFilePath + "].");
			System.err.println(
					" Follow the instructions at [https://docs.deistercloud.com/content/Databases.30/TPCH%20Benchmark.90/Data%20generation%20tool.30.xml?embedded=true] in order to download and create them.");
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	private LineItem getLineItem(String line) {
		String[] tokens = line.split("\\|");
		if (tokens.length != 16) {
			throw new RuntimeException("Invalid record: " + line);
		}
		LineItem lineItem;
		try {
			long rowNumber = Long.parseLong(tokens[0]);
			long orderKey = Long.parseLong(tokens[1]);
			long partKey = Long.parseLong(tokens[2]);
			long supplierKey = Long.parseLong(tokens[2]);
			int lineNumber = Integer.parseInt(tokens[3]);
			long quantity = Long.parseLong(tokens[4]);
			long extendedPrice = (long) Double.parseDouble(tokens[5]);
			long discount = (long) Double.parseDouble(tokens[6]);
			long tax = (long) Double.parseDouble(tokens[7]);
			String returnFlag = tokens[8];
			String status = tokens[9];
			int shipDate = Integer.parseInt(tokens[10].replace("-", ""));
			int commitDate = Integer.parseInt(tokens[11].replace("-", ""));
			int receiptDate = Integer.parseInt(tokens[12].replace("-", ""));
			String shipInstructions = tokens[13];
			String shipMode = tokens[14];
			String comment = tokens[15];

			lineItem = new LineItem(rowNumber, orderKey, partKey, supplierKey, lineNumber, quantity, extendedPrice,
					discount, tax, returnFlag, status, shipDate, commitDate, receiptDate, shipInstructions, shipMode,
					comment);
		} catch (NumberFormatException nfe) {
			throw new RuntimeException("Invalid record: " + line, nfe);
		} catch (Exception e) {
			throw new RuntimeException("Invalid record: " + line, e);
		}
		return lineItem;
	}

	public List<LineItem> getLineItems() {
		List<LineItem> lineItemsList = new ArrayList<LineItem>();
		String line = null;
		try {
			InputStream s = new FileInputStream(TPCH_DATA_LINE_ITEM);
			BufferedReader r = new BufferedReader(new InputStreamReader(s, StandardCharsets.UTF_8));

			line = r.readLine();
			while (line != null) {
				lineItemsList.add(getLineItem(line));
				line = r.readLine();
			}
			r.close();
			r = null;
			s.close();
			s = null;
		} catch (NumberFormatException nfe) {
			throw new RuntimeException("Invalid record: " + line, nfe);
		} catch (Exception e) {
			throw new RuntimeException("Invalid record: " + line, e);
		}
		return lineItemsList;
	}

	public List<Tuple2<Integer, Double>> getLineItemsRevenueByOrderKey() {
		String line = null;
		List<Tuple2<Integer, Double>> lineItemsList = new ArrayList<Tuple2<Integer, Double>>();
		try {
			InputStream s = new FileInputStream(TPCH_DATA_LINE_ITEM);
			BufferedReader r = new BufferedReader(new InputStreamReader(s, StandardCharsets.UTF_8));

			line = r.readLine();
			while (line != null) {
				LineItem lineItem = getLineItem(line);

				// LineItem: compute revenue and project out return flag
				// revenue per item = l_extendedprice * (1 - l_discount)
				Double revenue = lineItem.getExtendedPrice() * (1 - lineItem.getDiscount());
				Integer orderKey = (int) lineItem.getOrderKey();
				lineItemsList.add(Tuple2.of(orderKey, revenue));

				line = r.readLine();
			}
			r.close();
			r = null;
			s.close();
			s = null;
		} catch (NumberFormatException nfe) {
			throw new RuntimeException("Invalid record: " + line, nfe);
		} catch (Exception e) {
			throw new RuntimeException("Invalid record: " + line, e);
		}
		return lineItemsList;
	}

	@Override
	public void cancel() {
		this.running = false;
	}
}