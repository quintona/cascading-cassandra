package com.ifesdjeen.cascading.cassandra;

import cascading.tuple.FieldsResolverException;
import org.apache.cassandra.db.ColumnSerializer;
import org.apache.cassandra.thrift.*;
import org.apache.cassandra.utils.ByteBufferUtil;

import cascading.flow.FlowProcess;
import cascading.scheme.Scheme;
import cascading.scheme.SinkCall;
import cascading.scheme.SourceCall;

import cascading.tap.Tap;

import cascading.tuple.Fields;
import cascading.tuple.Tuple;
import cascading.tuple.TupleEntry;
import org.apache.hadoop.mapred.*;
import org.apache.hadoop.fs.Path;

import org.apache.cassandra.hadoop.ColumnFamilyOutputFormat;
import org.apache.cassandra.hadoop.ConfigHelper;

import com.ifesdjeen.cascading.cassandra.hadoop.ColumnFamilyInputFormat;
import com.ifesdjeen.cascading.cassandra.hadoop.CassandraHelper;

import java.io.IOException;
import java.util.LinkedList;
import java.util.List;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.SortedMap;
import java.util.Map;
import java.util.UUID;
import java.nio.ByteBuffer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.cassandra.db.IColumn;

public class CassandraScheme extends
		Scheme<JobConf, RecordReader, OutputCollector, Object[], Object[]> {

	private static final Logger logger = LoggerFactory
			.getLogger(CassandraTap.class);
	
	private static enum Mode{SOURCE, WIDE,NARROW}

	private String pathUUID;
	private String host;
	private String port;
	private String keyspace;
	private String columnFamily;
	private String keyColumnName;
	private List<String> columnFieldNames;
	private Map<String, String> fieldMappings;
	private Map<String, String> settings;
	private CassandraHelper helper;
	private Mode mode;
	private String rowKey;

	// Use this constructor when using CassandraScheme as a Source
	public CassandraScheme(String host, String port, String keyspace,
			String columnFamily, String keyColumnName,
			List<String> columnFieldNames) {
		this(host, port, keyspace, columnFamily, keyColumnName,
				columnFieldNames, null);
	}

	// Use this constructor when using CassandraScheme as a Sink
	public CassandraScheme(String host, String port, String keyspace,
			String columnFamily, String keyColumnName,
			List<String> columnFieldNames, Map<String, String> fieldMappings) {
		this(host, port, keyspace, columnFamily, keyColumnName,
				columnFieldNames, fieldMappings, null);
	}

	// Use this constructor when using CassandraScheme as a Sink
	public CassandraScheme(String host, String port, String keyspace,
			String columnFamily, String keyColumnName,
			List<String> columnFieldNames, Map<String, String> fieldMappings,
			Map<String, String> settings) {
		this.mode = Mode.NARROW;
		this.host = host;
		this.port = port;
		this.keyspace = keyspace;
		this.columnFamily = columnFamily;
		this.columnFieldNames = columnFieldNames;
		this.keyColumnName = keyColumnName;
		this.fieldMappings = fieldMappings;
		this.settings = settings;
		this.pathUUID = UUID.randomUUID().toString();
	}

	// Use this constructor when using CassandraScheme as a Wide Sink
	public CassandraScheme(String host, String port, String keyspace,
			String columnFamily,String rowKey,
			Map<String, String> settings) {
		this.mode = Mode.WIDE;
		this.host = host;
		this.port = port;
		this.keyspace = keyspace;
		this.columnFamily = columnFamily;
		this.settings = settings;
		this.rowKey = rowKey;
		this.pathUUID = UUID.randomUUID().toString();
	}

	/**
	 * @param flowProcess
	 * @param sourceCall
	 */
	@Override
	public void sourcePrepare(FlowProcess<JobConf> flowProcess,
			SourceCall<Object[], RecordReader> sourceCall) {

		this.helper = new CassandraHelper(this.host,
				Integer.parseInt(this.port), this.keyspace, this.columnFamily);

		ByteBuffer key = ByteBufferUtil.clone((ByteBuffer) sourceCall
				.getInput().createKey());
		SortedMap<ByteBuffer, IColumn> value = (SortedMap<ByteBuffer, IColumn>) sourceCall
				.getInput().createValue();

		Object[] obj = new Object[] { key, value };

		sourceCall.setContext(obj);
	}

	/**
	 * @param flowProcess
	 * @param sourceCall
	 */
	@Override
	public void sourceCleanup(FlowProcess<JobConf> flowProcess,
			SourceCall<Object[], RecordReader> sourceCall) {
		sourceCall.setContext(null);
	}

	/**
	 * FIXME: Pitfalls: Currently only String is supported as a rowKey.
	 * 
	 * @param flowProcess
	 * @param sourceCall
	 * @return
	 * @throws IOException
	 */
	@Override
	public boolean source(FlowProcess<JobConf> flowProcess,
			SourceCall<Object[], RecordReader> sourceCall) throws IOException {
		Tuple result = new Tuple();

		Object key = sourceCall.getContext()[0];
		Object value = sourceCall.getContext()[1];

		ByteBuffer rowkey = ByteBufferUtil.clone((ByteBuffer) key);
		boolean hasNext = sourceCall.getInput().next(rowkey, value);

		if (!hasNext) {
			return false;
		}

		SortedMap<ByteBuffer, IColumn> columns = (SortedMap<ByteBuffer, IColumn>) value;

		result.add(ByteBufferUtil.string(rowkey).trim());

		if (!columnFieldNames.isEmpty()) {
			for (String columnFieldName : columnFieldNames) {
				IColumn col = columns
						.get(ByteBufferUtil.bytes(columnFieldName));

				if (col != null) {
					result.add(this.helper.getTypeForColumn(col).compose(
							col.value()));
				} else if (columnFieldName != this.keyColumnName) {
					result.add("");
				}
			}
		} else {
			result.add(columns);
		}

		sourceCall.getIncomingEntry().setTuple(result);
		return true;

	}
	
	private void sinkNarrow(FlowProcess<JobConf> flowProcess,
			SinkCall<Object[], OutputCollector> sinkCall) throws IOException{
		TupleEntry tupleEntry = sinkCall.getOutgoingEntry();
		OutputCollector outputCollector = sinkCall.getOutput();

		logger.info("key name {}", this.keyColumnName);
		logger.info("key mapping name {}",
				this.fieldMappings.get(this.keyColumnName));
		Tuple key = tupleEntry.selectTuple(new Fields(this.fieldMappings
				.get(this.keyColumnName)));
		ByteBuffer keyBuffer = CassandraHelper.serialize(key.get(0));

		int nfields = columnFieldNames.size();
		List mutations = new ArrayList<Mutation>(nfields);
		// TODO: ADD skipping for name field
		for (String columnFieldName : columnFieldNames) {
			String columnFieldMapping = fieldMappings.get(columnFieldName);
			Object tupleEntryValue = null;

			try {
				tupleEntryValue = tupleEntry.get(columnFieldMapping);
			} catch (FieldsResolverException e) {
				logger.error("Couldn't resolve field: {}", columnFieldName);
			}

			if (tupleEntryValue != null && columnFieldName != keyColumnName) {
				logger.info("Column filed name {}", columnFieldName);
				logger.info("Mapped column name {}", columnFieldMapping);
				logger.info("Column filed value {}",
						tupleEntry.get(columnFieldMapping));

				Mutation mutation = createColumnPutMutation(
						CassandraHelper.serialize(columnFieldName),
						CassandraHelper.serialize(tupleEntry
								.get(columnFieldMapping)));
				mutations.add(mutation);
			}
		}

		outputCollector.collect(keyBuffer, mutations);
	}
	
	private void sinkWide(FlowProcess<JobConf> flowProcess,
			SinkCall<Object[], OutputCollector> sinkCall) throws IOException{
		TupleEntry tupleEntry = sinkCall.getOutgoingEntry();
		OutputCollector outputCollector = sinkCall.getOutput();

		ByteBuffer keyBuffer = CassandraHelper.serialize(rowKey);
		
		List mutations = new ArrayList<Mutation>(1);
		Comparable columnName = tupleEntry.get(0);
		Comparable value = tupleEntry.get(1);
		Mutation mutation = createColumnPutMutation(
					CassandraHelper.serialize(columnName),
					CassandraHelper.serialize(value));
		mutations.add(mutation);

		outputCollector.collect(keyBuffer, mutations);
	}

	/**
	 * @param flowProcess
	 * @param sinkCall
	 * @throws IOException
	 */
	@Override
	public void sink(FlowProcess<JobConf> flowProcess,
			SinkCall<Object[], OutputCollector> sinkCall) throws IOException {
		if(this.mode == Mode.NARROW)
			sinkNarrow(flowProcess, sinkCall);
		else if(this.mode == Mode.WIDE)
			sinkWide(flowProcess, sinkCall);
	}

	/**
	 * @param name
	 * @param value
	 * @return
	 */
	protected Mutation createColumnPutMutation(ByteBuffer name, ByteBuffer value) {
		Column column = new Column(name);
		column.setName(name);
		column.setValue(value);
		column.setTimestamp(System.currentTimeMillis());

		Mutation m = new Mutation();
		ColumnOrSuperColumn columnOrSuperColumn = new ColumnOrSuperColumn();
		columnOrSuperColumn.setColumn(column);
		m.setColumn_or_supercolumn(columnOrSuperColumn);

		return m;
	}

	@Override
	public void sinkConfInit(FlowProcess<JobConf> process,
			Tap<JobConf, RecordReader, OutputCollector> tap, JobConf conf) {
		conf.setOutputFormat(ColumnFamilyOutputFormat.class);

		ConfigHelper.setRangeBatchSize(conf, 100);

		ConfigHelper.setOutputRpcPort(conf, port);
		ConfigHelper.setOutputInitialAddress(conf, host);

		if (this.settings.containsKey("cassandra.outputPartitioner")) {
			ConfigHelper.setOutputPartitioner(conf,
					this.settings.get("cassandra.outputPartitioner"));
		} else {
			ConfigHelper.setOutputPartitioner(conf,
					"org.apache.cassandra.dht.RandomPartitioner");
		}

		ConfigHelper.setOutputColumnFamily(conf, keyspace, columnFamily);
		conf.setInt(ColumnFamilyInputFormat.CASSANDRA_HADOOP_MAX_KEY_SIZE, 60);

		FileOutputFormat.setOutputPath(conf, getPath());
	}

	@Override
	public void sourceConfInit(FlowProcess<JobConf> process,
			Tap<JobConf, RecordReader, OutputCollector> tap, JobConf conf) {

		FileInputFormat.addInputPaths(conf, getPath().toString());
		conf.setInputFormat(ColumnFamilyInputFormat.class);

		ConfigHelper.setRangeBatchSize(conf, 100);
		ConfigHelper.setInputSplitSize(conf, 30);
		ConfigHelper.setInputRpcPort(conf, port);
		ConfigHelper.setInputInitialAddress(conf, host);

		if (this.settings.containsKey("cassandra.inputPartitioner")) {
			ConfigHelper.setInputPartitioner(conf,
					this.settings.get("cassandra.inputPartitioner"));
		} else {
			ConfigHelper.setInputPartitioner(conf,
					"org.apache.cassandra.dht.RandomPartitioner");
		}

		ConfigHelper.setInputColumnFamily(conf, keyspace, columnFamily);
		conf.setInt(ColumnFamilyInputFormat.CASSANDRA_HADOOP_MAX_KEY_SIZE, 60);

		SlicePredicate predicate = new SlicePredicate();

		if (!columnFieldNames.isEmpty()) {
			List<ByteBuffer> columnNames = new ArrayList<ByteBuffer>();
			for (String columnFieldName : columnFieldNames) {
				columnNames.add(ByteBufferUtil.bytes(columnFieldName));
			}
			predicate.setColumn_names(columnNames);
		} else {
			SliceRange sliceRange = new SliceRange();
			sliceRange.setStart(ByteBufferUtil.bytes(""));
			sliceRange.setFinish(ByteBufferUtil.bytes(""));
			predicate.setSlice_range(sliceRange);
		}

		ConfigHelper.setInputSlicePredicate(conf, predicate);
		// ConfigHelper.setInputSplitSize(conf, 3);
	}

	public Path getPath() {
		return new Path(pathUUID);
	}

	public String getIdentifier() {
		return host + "_" + port + "_" + keyspace + "_" + columnFamily;
	}

	@Override
	public boolean equals(Object other) {
		if (this == other)
			return true;
		if (!(other instanceof CassandraScheme))
			return false;
		if (!super.equals(other))
			return false;

		CassandraScheme that = (CassandraScheme) other;

		if (!getPath().toString().equals(that.getPath().toString()))
			return false;

		return true;
	}

	@Override
	public int hashCode() {
		int result = super.hashCode();
		result = 31 * result + getPath().toString().hashCode();
		result = 31 * result + (host != null ? host.hashCode() : 0);
		result = 31 * result + (port != null ? port.hashCode() : 0);
		result = 31 * result + (keyspace != null ? keyspace.hashCode() : 0);
		result = 31 * result
				+ (columnFamily != null ? columnFamily.hashCode() : 0);
		return result;
	}

}
