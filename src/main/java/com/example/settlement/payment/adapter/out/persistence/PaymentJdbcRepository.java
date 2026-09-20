package com.example.settlement.payment.adapter.out.persistence;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.repository.CrudRepository;

interface PaymentJdbcRepository extends CrudRepository<PaymentEntity, UUID> {

    /** 1つの注文に対して決済は1件。返金要求は注文IDを起点に届く(REQ-ORD-009)。 */
    Optional<PaymentEntity> findByOrderId(UUID orderId);
}
