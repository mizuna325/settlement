package com.example.settlement.order.adapter.out.persistence;

import java.util.UUID;

import org.springframework.data.repository.CrudRepository;

interface OrderJdbcRepository extends CrudRepository<OrderEntity, UUID> {
}
