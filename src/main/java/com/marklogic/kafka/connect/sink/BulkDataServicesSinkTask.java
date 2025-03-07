package com.marklogic.kafka.connect.sink;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.marklogic.client.DatabaseClient;
import com.marklogic.client.dataservices.IOEndpoint;
import com.marklogic.client.dataservices.InputCaller;
import com.marklogic.client.document.DocumentWriteOperation;
import com.marklogic.client.ext.DatabaseClientConfig;
import com.marklogic.client.ext.DefaultConfiguredDatabaseClientFactory;
import com.marklogic.client.io.BytesHandle;
import com.marklogic.client.io.Format;
import com.marklogic.client.io.JacksonHandle;
import com.marklogic.client.io.StringHandle;
import com.marklogic.client.io.marker.AbstractWriteHandle;
import com.marklogic.kafka.connect.DefaultDatabaseClientConfigBuilder;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.connect.sink.SinkRecord;
import org.springframework.util.StringUtils;

import java.util.Map;

/**
 * Uses Bulk Data Services - https://github.com/marklogic/java-client-api/wiki/Bulk-Data-Services - to allow the user
 * to provide their own endpoint implementation, thus giving the user full control over how data is written to
 * MarkLogic.
 */
public class BulkDataServicesSinkTask extends AbstractSinkTask {

    private DatabaseClient databaseClient;
    private InputCaller.BulkInputCaller<JsonNode> bulkInputCaller;
    private ObjectMapper objectMapper;
    private SinkRecordConverter sinkRecordConverter;

    public BulkDataServicesSinkTask() {
        this.objectMapper = new ObjectMapper();
    }

    @Override
    protected void onStart(Map<String, Object> parsedConfig) {
        DatabaseClientConfig databaseClientConfig = new DefaultDatabaseClientConfigBuilder().buildDatabaseClientConfig(parsedConfig);
        this.databaseClient = new DefaultConfiguredDatabaseClientFactory().newDatabaseClient(databaseClientConfig);

        JacksonHandle modulesHandle = new JacksonHandle(buildApiDeclaration(parsedConfig));
        InputCaller<JsonNode> inputCaller = InputCaller.on(databaseClient, modulesHandle, new JacksonHandle().withFormat(Format.JSON));

        IOEndpoint.CallContext callContext = inputCaller.newCallContext()
            .withEndpointConstants(new JacksonHandle(buildEndpointConstants(parsedConfig)));
        this.bulkInputCaller = inputCaller.bulkCaller(callContext);
        this.configureErrorListenerOnBulkInputCaller();

        this.sinkRecordConverter = new DefaultSinkRecordConverter(parsedConfig);
    }

    /**
     * When Kafka calls - the frequency of which can be controlled by the user - perform a synchronous flush of any
     * records waiting to be written to MarkLogic. {@code BulkInputCaller} does not yet have an asynchronous flush
     * like DMSDK does, but the use of a synchronous flush seems appropriate - i.e. Kafka seems to be okay with a
     * synchronous call here, while {@code put} is expected to be async.
     * <p>
     * For a good reference, see https://stackoverflow.com/questions/44871377/put-vs-flush-in-kafka-connector-sink-task
     *
     * @param currentOffsets
     */
    @Override
    public void flush(Map<TopicPartition, OffsetAndMetadata> currentOffsets) {
        if (bulkInputCaller != null) {
            logger.debug("Flushing BulkInputCaller");
            bulkInputCaller.awaitCompletion();
            logger.debug("Finished flushing BulkInputCaller");
        }
    }

    @Override
    public void stop() {
        flush(null);
        if (databaseClient != null) {
            databaseClient.release();
        }
    }

    /**
     * Queues up the sink record for writing to MarkLogic. Once the batch size, as defined in the Bulk API declaration,
     * is reached, the {@code BulkInputCaller} will write the data to MarkLogic.
     *
     * @param sinkRecord
     */
    @Override
    protected void writeSinkRecord(SinkRecord sinkRecord) {
        DocumentWriteOperation writeOp = sinkRecordConverter.convert(sinkRecord);
        JsonNode input = buildBulkDataServiceInput(writeOp, sinkRecord);
        bulkInputCaller.accept(input);
    }

    /**
     * Build an API declaration based on the user inputs for an endpoint URI and optional batch size. It's feasible to
     * do this because the connector knows what the {@code params} array must be, and the documentation instructs the
     * endpoint developer to use the same array of parameters. Building the API here also avoids having to read it
     * from a modules database which requires either the xdmp-eval-in or xdbc-eval privilege.
     *
     * @param parsedConfig
     * @return
     */
    private JsonNode buildApiDeclaration(Map<String, Object> parsedConfig) {
        final String endpoint = parsedConfig.get(MarkLogicSinkConfig.BULK_DS_ENDPOINT_URI).toString();

        ObjectNode api = this.objectMapper.createObjectNode();
        api.put("endpoint", endpoint);
        ArrayNode params = api.putArray("params");
        ObjectNode param = params.addObject();
        param.put("name", "endpointConstants");
        param.put("datatype", "jsonDocument");
        param.put("multiple", false);
        param.put("nullable", false);
        param = params.addObject();
        param.put("name", "input");
        param.put("datatype", "jsonDocument");
        param.put("multiple", true);
        param.put("nullable", true);
        if (parsedConfig.containsKey(MarkLogicSinkConfig.BULK_DS_BATCH_SIZE)) {
            int batchSize = Integer.parseInt(parsedConfig.get(MarkLogicSinkConfig.BULK_DS_BATCH_SIZE).toString());
            api.putObject("$bulk").put("inputBatchSize", batchSize);
        }
        return api;
    }

    /**
     * When using Bulk Data Services, include all "ml.document" config options in the endpoint constants in case the
     * endpoint developer wishes to use these.
     *
     * @param parsedConfig
     * @return
     */
    private ObjectNode buildEndpointConstants(Map<String, Object> parsedConfig) {
        ObjectNode endpointConstants = this.objectMapper.createObjectNode();
        for (String key : parsedConfig.keySet()) {
            if (key.startsWith("ml.document")) {
                Object value = parsedConfig.get(key);
                if (value != null) {
                    endpointConstants.put(key, value.toString());
                }
            }
        }
        return endpointConstants;
    }

    /**
     * An envelope structure is used so that both the content and Kafka metadata from the sink record can be sent to
     * the endpoint.
     *
     * @param writeOp
     * @param sinkRecord
     * @return
     */
    private JsonNode buildBulkDataServiceInput(DocumentWriteOperation writeOp, SinkRecord sinkRecord) {
        AbstractWriteHandle handle = writeOp.getContent();
        // This assumes that the SinkRecordConverter always constructs either a BytesHandle or StringHandle. This is an
        // implementation detail not exposed to the user, and sufficient testing should ensure that this assumption
        // holds up over time.
        String content;
        if (handle instanceof BytesHandle) {
            content = new String(((BytesHandle) handle).get());
        } else {
            content = ((StringHandle) handle).get();
        }
        ObjectNode input = new ObjectMapper().createObjectNode();
        input.put("content", content);

        ObjectNode kafkaMetadata = input.putObject("kafka-metadata");
        kafkaMetadata.put("topic", sinkRecord.topic());
        Object key = sinkRecord.key();
        if (key != null) {
            kafkaMetadata.put("key", key.toString());
        }
        kafkaMetadata.put("offset", sinkRecord.kafkaOffset());
        Integer partition = sinkRecord.kafkaPartition();
        if (partition != null) {
            kafkaMetadata.put("partition", partition);
        }
        Long timestamp = sinkRecord.timestamp();
        if (timestamp != null) {
            kafkaMetadata.put("timestamp", timestamp);
        }
        return input;
    }

    /**
     * For the initial release of this capability, applying the "skip" approach that behaves in the same manner as
     * the existing WriteBatcher approach - i.e. log the failure and keep processing other records/batches. Can make
     * this configurable in the future if a client wants "stop all calls" support.
     */
    private void configureErrorListenerOnBulkInputCaller() {
        this.bulkInputCaller.setErrorListener((retryCount, throwable, callContext, input) -> {
            // The stacktrace is not included here, as it will only contain references to Bulk Data Services code and
            // connector code, which won't help with debugging. The MarkLogic error log will be of much more value,
            // along with seeing the error message here.
            logger.error("Skipping failed write; cause: " + throwable.getMessage() + "; check the MarkLogic error " +
                "log file for additional information as to the cause of the failed write");
            return IOEndpoint.BulkIOEndpointCaller.ErrorDisposition.SKIP_CALL;
        });
    }
}
