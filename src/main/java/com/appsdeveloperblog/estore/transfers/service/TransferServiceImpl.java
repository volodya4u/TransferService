package com.appsdeveloperblog.estore.transfers.service;

import com.appsdeveloperblog.estore.transfers.io.TransferEntity;
import com.appsdeveloperblog.estore.transfers.io.TransferRepository;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.common.Uuid;
import org.springframework.beans.BeanUtils;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import com.appsdeveloperblog.estore.transfers.error.TransferServiceException;
import com.appsdeveloperblog.estore.transfers.model.TransferRestModel;
import com.appsdeveloperblog.ws.core.events.DepositRequestedEvent;
import com.appsdeveloperblog.ws.core.events.WithdrawalRequestedEvent;

@Slf4j
@Service
public class TransferServiceImpl implements TransferService {

	private KafkaTemplate<String, Object> kafkaTemplate;
	private Environment environment;
	private RestTemplate restTemplate;
	private TransferRepository transferRepository;

	public TransferServiceImpl(KafkaTemplate<String, Object> kafkaTemplate, Environment environment,
			RestTemplate restTemplate, TransferRepository transferRepository) {
		this.kafkaTemplate = kafkaTemplate;
		this.environment = environment;
		this.restTemplate = restTemplate;
		this.transferRepository = transferRepository;
	}

	@Transactional("transactionManager")
	@Override
	public boolean transfer(TransferRestModel transferRestModel) {
		WithdrawalRequestedEvent withdrawalEvent = new WithdrawalRequestedEvent(transferRestModel.getSenderId(),
				transferRestModel.getRecepientId(), transferRestModel.getAmount());
		DepositRequestedEvent depositEvent = new DepositRequestedEvent(transferRestModel.getSenderId(),
				transferRestModel.getRecepientId(), transferRestModel.getAmount());

		TransferEntity transferEntity = new TransferEntity();
		BeanUtils.copyProperties(transferRestModel, transferEntity);
		transferEntity.setTransferId(Uuid.randomUuid().toString());

		try {

			// Save record to a database table
			transferRepository.save(transferEntity);

			kafkaTemplate.send(environment.getProperty("withdraw-money-topic", "withdraw-money-topic"),
					withdrawalEvent);
			log.info("Sent event to withdrawal topic.");

			// Business logic that causes and error
			callRemoteService();

			kafkaTemplate.send(environment.getProperty("deposit-money-topic", "deposit-money-topic"), depositEvent);
			log.info("Sent event to deposit topic");

		} catch (Exception ex) {
			log.error(ex.getMessage(), ex);
			throw new TransferServiceException(ex);
		}

		return true;
	}

	private ResponseEntity<String> callRemoteService() throws Exception {
		String requestUrl = "http://localhost:8082/response/200";
		ResponseEntity<String> response = restTemplate.exchange(requestUrl, HttpMethod.GET, null, String.class);

		if (response.getStatusCode().value() == HttpStatus.SERVICE_UNAVAILABLE.value()) {
			throw new Exception("Destination Microservice not availble");
		}

		if (response.getStatusCode().value() == HttpStatus.OK.value()) {
			log.info("Received response from mock service: " + response.getBody());
		}
		return response;
	}

}
