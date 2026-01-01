package com.appsdeveloperblog.estore.transfers.model;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
public class TransferRestModel {
    private String senderId;
    private String recepientId;
    private BigDecimal amount;

}
