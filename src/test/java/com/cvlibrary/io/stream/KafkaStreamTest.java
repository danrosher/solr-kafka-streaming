
package com.cvlibrary.io.stream;

import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.solr.client.solrj.io.Tuple;
import org.apache.solr.client.solrj.io.stream.StreamContext;
import org.apache.solr.client.solrj.io.stream.TupleStream;
import org.apache.solr.client.solrj.io.stream.expr.DefaultStreamFactory;
import org.apache.solr.client.solrj.io.stream.expr.StreamExpression;
import org.apache.solr.client.solrj.io.stream.expr.StreamExpressionParser;
import org.apache.solr.client.solrj.io.stream.expr.StreamFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

public class KafkaStreamTest {
    private TupleStream stream;
    @Mock
    KafkaProducer<String, String> producer;

    @BeforeEach
    public void cleanIndex() throws IOException {
        StreamFactory factory = new DefaultStreamFactory();
        StreamExpression expression = StreamExpressionParser.parse(
                "list(tuple(id=1,title=\"one\"),tuple(id=2,title=\"two\"))");
        stream = factory.constructStream(expression);
        stream.setStreamContext(new StreamContext());
    }

    @Test
    public void testKafkaStream() throws IOException {
        System.out.println("@testKafkaStream");
        producer = Mockito.mock(KafkaProducer.class);
        String topic = "testTopic";
        ArgumentCaptor<ProducerRecord<String, String>> producerCaptor = ArgumentCaptor.forClass(ProducerRecord.class);
        stream = new KafkaStream(stream, topic, producer);
        List<Tuple> list = getTuples(stream);
        verify(producer, times(2)).send(producerCaptor.capture());
        List<ProducerRecord<String, String>> capturedRecords = producerCaptor.getAllValues();
        assertEquals(topic, capturedRecords.get(0).topic());
        assertEquals("""
                {
                  "id":"1",
                  "title":"one"}""", capturedRecords.get(0).value());
        assertEquals(topic, capturedRecords.get(1).topic());
        assertEquals("""
                {
                  "id":"2",
                  "title":"two"}""", capturedRecords.get(1).value());

        assertEquals(2,list.size());
        assertEquals("""
                {
                  "totalIndexed":1,
                  "batchIndexed":1,
                  "batchNumber":1}""", list.get(0).jsonStr());
        assertEquals("""
                {
                  "totalIndexed":2,
                  "batchIndexed":1,
                  "batchNumber":2}""", list.get(1).jsonStr());

    }

    protected List<Tuple> getTuples(TupleStream tupleStream) throws IOException {
        List<Tuple> tuples = new ArrayList<>();
        try (tupleStream) {
            tupleStream.open();
            for (Tuple t = tupleStream.read(); !t.EOF; t = tupleStream.read()) {
                tuples.add(t);
            }
        }
        return tuples;
    }
}