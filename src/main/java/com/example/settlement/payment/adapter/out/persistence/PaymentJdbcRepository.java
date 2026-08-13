package com.example.settlement.payment.adapter.out.persistence;

import java.util.UUID;

import org.springframework.data.repository.CrudRepository;

interface PaymentJdbcRepository extends CrudRepository<PaymentEntity, UUID> {
}
