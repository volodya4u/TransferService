package com.appsdeveloperblog.estore.transfers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.support.serializer.JacksonJsonDeserializer;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import com.appsdeveloperblog.estore.transfers.error.TransferServiceException;
import com.appsdeveloperblog.estore.transfers.io.TransferRepository;
import com.appsdeveloperblog.estore.transfers.model.TransferRestModel;
import com.appsdeveloperblog.estore.transfers.service.TransferService;
import com.appsdeveloperblog.ws.core.events.DepositRequestedEvent;
import com.appsdeveloperblog.ws.core.events.WithdrawalRequestedEvent;

@EmbeddedKafka(partitions = 3, count = 1)
@SpringBootTest(properties = {
		"spring.kafka.producer.bootstrap-servers=${spring.embedded.kafka.brokers}",
		"app.kafka.topic.replicas=1" })
class TransferServiceIntegrationTest {

	private static final String WITHDRAW_TOPIC = "withdraw-money-topic";
	private static final String DEPOSIT_TOPIC = "deposit-money-topic";

	@Autowired
	TransferService transferService;

	@Autowired
	TransferRepository transferRepository;

	@Autowired
	EmbeddedKafkaBroker embeddedKafka;

	@MockitoBean
	RestTemplate restTemplate;

	private final List<Consumer<String, Object>> consumers = new ArrayList<>();

	@AfterEach
	void closeConsumers() {
		consumers.forEach(Consumer::close);
		consumers.clear();
	}

	@Test
	void successfulTransferCommitsBothEventsAndTheTransfer() {
		when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), isNull(), eq(String.class)))
				.thenReturn(ResponseEntity.ok("OK"));
		Consumer<String, Object> consumer = committedEventsConsumer();
		long transfersBefore = transferRepository.count();

		assertTrue(transferService.transfer(transfer("sender-1", "recipient-1", "100.00")));

		List<ConsumerRecord<String, Object>> events = poll(consumer, 2, Duration.ofSeconds(10));
		assertEquals(2, events.size());
		WithdrawalRequestedEvent withdrawal = eventOn(events, WITHDRAW_TOPIC, WithdrawalRequestedEvent.class);
		assertEquals("sender-1", withdrawal.getSenderId());
		assertEquals(new BigDecimal("100.00"), withdrawal.getAmount());
		DepositRequestedEvent deposit = eventOn(events, DEPOSIT_TOPIC, DepositRequestedEvent.class);
		assertEquals("recipient-1", deposit.getRecepientId());
		assertEquals(new BigDecimal("100.00"), deposit.getAmount());
		assertEquals(transfersBefore + 1, transferRepository.count());
	}

	@Test
	void failedRemoteCallRollsBackTheEventsAndTheTransfer() {
		// The withdrawal event is sent before the remote call, so the Kafka
		// transaction has to be aborted for it to disappear.
		when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), isNull(), eq(String.class)))
				.thenThrow(new ResourceAccessException("Connection refused"));
		Consumer<String, Object> consumer = committedEventsConsumer();
		long transfersBefore = transferRepository.count();

		assertThrows(TransferServiceException.class,
				() -> transferService.transfer(transfer("sender-2", "recipient-2", "50.00")));

		assertTrue(poll(consumer, 1, Duration.ofSeconds(3)).isEmpty());
		assertEquals(transfersBefore, transferRepository.count());
	}

	private static TransferRestModel transfer(String senderId, String recipientId, String amount) {
		TransferRestModel transfer = new TransferRestModel();
		transfer.setSenderId(senderId);
		transfer.setRecepientId(recipientId);
		transfer.setAmount(new BigDecimal(amount));
		return transfer;
	}

	/**
	 * A consumer that reads only committed events, positioned at the current
	 * end of both topics so it sees just what the test produces.
	 */
	private Consumer<String, Object> committedEventsConsumer() {
		Map<String, Object> props = KafkaTestUtils.consumerProps(embeddedKafka, "transfer-test-" + UUID.randomUUID(),
				false);
		props.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed");
		props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
		props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, JacksonJsonDeserializer.class);
		props.put(JacksonJsonDeserializer.TRUSTED_PACKAGES, "com.appsdeveloperblog.ws.core.events");
		Consumer<String, Object> consumer = new KafkaConsumer<>(props);
		consumers.add(consumer);

		List<TopicPartition> partitions = new ArrayList<>();
		for (String topic : List.of(WITHDRAW_TOPIC, DEPOSIT_TOPIC)) {
			consumer.partitionsFor(topic).forEach(info -> partitions.add(new TopicPartition(topic, info.partition())));
		}
		consumer.assign(partitions);
		consumer.seekToEnd(partitions);
		partitions.forEach(consumer::position);
		return consumer;
	}

	private static List<ConsumerRecord<String, Object>> poll(Consumer<String, Object> consumer, int expected,
			Duration timeout) {
		List<ConsumerRecord<String, Object>> records = new ArrayList<>();
		long deadline = System.nanoTime() + timeout.toNanos();
		while (records.size() < expected && System.nanoTime() < deadline) {
			consumer.poll(Duration.ofMillis(200)).forEach(records::add);
		}
		return records;
	}

	private static <T> T eventOn(List<ConsumerRecord<String, Object>> records, String topic, Class<T> type) {
		List<Object> values = records.stream().filter(record -> record.topic().equals(topic))
				.map(ConsumerRecord::value).toList();
		assertEquals(1, values.size(), "events on " + topic);
		return type.cast(values.get(0));
	}
}
