package com.cvlibrary.io.stream;

import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.solr.client.solrj.io.Tuple;
import org.apache.solr.client.solrj.io.comp.StreamComparator;
import org.apache.solr.client.solrj.io.stream.StreamContext;
import org.apache.solr.client.solrj.io.stream.TupleStream;
import org.apache.solr.client.solrj.io.stream.expr.*;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

public class KafkaStream extends TupleStream implements Expressible {

    public static String BATCH_INDEXED_FIELD_NAME = "batchIndexed";
    private int batchNumber;
    private int updateBatchSize;
    private TupleStream stream;
    private KafkaProducer<String, String> producer;
    private int totalDocsIndex;
    private String topicName;
    private String bootstrapServers;

    public KafkaStream(TupleStream stream, String topicName, KafkaProducer<String, String> producer) {
        this.stream = stream;
        this.updateBatchSize = 1;
        this.topicName = topicName;
        this.producer = producer;
    }

    public KafkaStream(StreamExpression expression, StreamFactory factory) throws IOException {
        List<StreamExpression> streamExpressions =
                factory.getExpressionOperandsRepresentingTypes(
                        expression, Expressible.class, TupleStream.class);
        int updateBatchSize = extractBatchSize(expression, factory);
        String topicName = extractTopicName(expression, factory);
        String bootstrapServers = extractBootstrapServers(expression, factory);
        init(factory.constructStream(streamExpressions.get(0)), topicName, updateBatchSize, bootstrapServers);
    }

    private void init(
            TupleStream tupleSource, String topicName, int updateBatchSize, String bootstrapServers) {
        this.stream = tupleSource;
        this.topicName = topicName;
        this.updateBatchSize = updateBatchSize;
        this.bootstrapServers = bootstrapServers;
        Properties properties = new Properties();
        properties.setProperty("bootstrap.servers", bootstrapServers);

        // high throughput producer (at the expense of a bit of latency and CPU usage)
        //properties.setProperty(ProducerConfig.COMPRESSION_TYPE_CONFIG, "snappy");
        properties.setProperty(ProducerConfig.LINGER_MS_CONFIG, "20");
        properties.setProperty(ProducerConfig.BATCH_SIZE_CONFIG, Integer.toString(32 * 1024)); // 32 KB batch size
        properties.put("key.serializer", "org.apache.kafka.common.serialization.StringSerializer");
        properties.put("value.serializer", "org.apache.kafka.common.serialization.StringSerializer");
        producer = new KafkaProducer<>(properties);

    }

    private String extractTopicName(StreamExpression expression, StreamFactory factory) throws IOException {
        StreamExpressionNamedParameter topicNameParam =
                factory.getNamedOperand(expression, "topicName");
        if (topicNameParam == null) {
            throw new IOException(
                    String.format(
                            Locale.ROOT,
                            "invalid expression %s - topicName not found",
                            expression));
        }
        return ((StreamExpressionValue) topicNameParam.getParameter()).getValue();
    }

    private String extractBootstrapServers(StreamExpression expression, StreamFactory factory) {
        StreamExpressionNamedParameter topicNameParam =
                factory.getNamedOperand(expression, "bootstrapServers");
        if (topicNameParam == null) {
            // Sensible default batch size
            return "localhost:9092";
        }
        return ((StreamExpressionValue) topicNameParam.getParameter()).getValue();
    }

    private int extractBatchSize(StreamExpression expression, StreamFactory factory)
            throws IOException {
        StreamExpressionNamedParameter batchSizeParam =
                factory.getNamedOperand(expression, "batchSize");
        if (batchSizeParam == null) {
            // Sensible default batch size
            return 250;
        }
        String batchSizeStr = ((StreamExpressionValue) batchSizeParam.getParameter()).getValue();
        return parseBatchSize(batchSizeStr, expression);
    }

    private int parseBatchSize(String batchSizeStr, StreamExpression expression) throws IOException {
        try {
            int batchSize = Integer.parseInt(batchSizeStr);
            if (batchSize <= 0) {
                throw new IOException(
                        String.format(
                                Locale.ROOT,
                                "invalid expression %s - batchSize '%d' must be greater than 0.",
                                expression,
                                batchSize));
            }
            return batchSize;
        } catch (NumberFormatException e) {
            throw new IOException(
                    String.format(
                            Locale.ROOT,
                            "invalid expression %s - batchSize '%s' is not a valid integer.",
                            expression,
                            batchSizeStr));
        }
    }

    @Override
    public void setStreamContext(StreamContext context) {
        this.stream.setStreamContext(context);
    }

    @Override
    public List<TupleStream> children() {
        List<TupleStream> l = new ArrayList<>();
        l.add(stream);
        return l;
    }

    @Override
    public void open() throws IOException {
        stream.open();
    }

    @Override
    public void close() throws IOException {
        stream.close();
        producer.close();
    }

    @Override
    public Tuple read() throws IOException {
        for (int i = 0; i < updateBatchSize; i++) {
            Tuple tuple = stream.read();
            if (tuple.EOF) return i == 0 ? tuple : createBatchSummaryTuple(i);
            producer.send(new ProducerRecord<>(topicName, tuple.get("id").toString(),tuple.jsonStr()));
        }
        return createBatchSummaryTuple(updateBatchSize);
    }

    private Tuple createBatchSummaryTuple(int batchSize) {
        assert batchSize > 0;
        Tuple tuple = new Tuple();
        this.totalDocsIndex += batchSize;
        ++batchNumber;
        tuple.put(BATCH_INDEXED_FIELD_NAME, batchSize);
        tuple.put("totalIndexed", this.totalDocsIndex);
        tuple.put("batchNumber", batchNumber);
        return tuple;
    }

    @Override
    public StreamComparator getStreamSort() {
        return stream.getStreamSort();
    }

    @Override
    public StreamExpressionParameter toExpression(StreamFactory factory) throws IOException {
        return toExpression(factory, true);
    }

    private StreamExpression toExpression(StreamFactory factory, boolean includeStreams)
            throws IOException {
        StreamExpression expression = new StreamExpression(factory.getFunctionName(this.getClass()));
        expression.addParameter(topicName);
        expression.addParameter(new StreamExpressionNamedParameter("bootstrapServers", bootstrapServers));
        expression.addParameter(
                new StreamExpressionNamedParameter("batchSize", Integer.toString(updateBatchSize)));

        if (includeStreams) {
            if (stream instanceof Expressible) {
                expression.addParameter(((Expressible) stream).toExpression(factory));
            } else {
                throw new IOException(
                        "This ParallelStream contains a non-expressible TupleStream - it cannot be converted to an expression");
            }
        } else {
            expression.addParameter("<stream>");
        }

        return expression;
    }

    @Override
    public Explanation toExplanation(StreamFactory factory) throws IOException {
        return null;
    }
}
