package com.ias.banco_ias.domain;

import java.math.BigDecimal;

public record CollectionOrder(String id, String merchantId, OrderStatus status, BigDecimal pendingBalance) {
}
