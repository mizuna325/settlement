package com.example.settlement.shared;

public record Money(long amount, Currency unit) {

    public Money(long amount, Currency unit) {
        if (unit == null) {
            throw new IllegalArgumentException("unit must not be null.");
        }
        if (amount < 0) {
            throw new IllegalArgumentException("amount must not be negative.");
        }
        this.amount = amount;
        this.unit = unit;
    }

    public Money plus(Money other) {
        this.requireSameUnit(other);
        return new Money(this.amount + other.amount, this.unit);
    }

    public boolean isGreaterThan(Money other) {
        this.requireSameUnit(other);
        if (this.amount > other.amount) {
            return true;
        }
        return false;
    }

    private void requireSameUnit(Money other) {
        if (other == null) {
            throw new IllegalArgumentException("other must not be null.");
        }
        if (this.unit != other.unit) {
            throw new IllegalArgumentException("The currency unit should be the same.");
        }
    }
}
