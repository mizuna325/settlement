package com.example.settlement.order.adapter.in.web;

import java.util.List;
import java.util.UUID;

import com.example.settlement.order.domain.CustomerId;
import com.example.settlement.order.domain.OrderLine;
import com.example.settlement.order.domain.ProductId;
import com.example.settlement.order.domain.Quantity;
import com.example.settlement.shared.Currency;
import com.example.settlement.shared.Money;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

record CreateOrderRequest(@NotNull UUID customerId,
                @NotEmpty @Valid List<Line> lines) {

        record Line(@NotBlank String productId,
                        @Positive int quantity,
                        @PositiveOrZero long amount,
                        @NotNull Currency currency) {
        }

        CustomerId toCustomerId() {
                return new CustomerId(customerId);
        }

        List<OrderLine> toOrderLines() {
                return lines.stream()
                                .map(line -> new OrderLine(
                                                new ProductId(line.productId()),
                                                new Quantity(line.quantity()),
                                                new Money(line.amount(), line.currency())))
                                .toList();
        }
}