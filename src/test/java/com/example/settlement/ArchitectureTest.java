package com.example.settlement;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;

/**
 * design.md §7 の依存関係ルール。
 * onlyOrderMayImplementPaymentOutcomePort は PaymentOutcomePort の実装が現れるステップ3で追加する
 * (ArchUnit は対象が0件のルールを失敗として扱うため)。
 */
class ArchitectureTest {

    private static final JavaClasses classes = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.example.settlement");

    @Test
    @DisplayName("payment は order に依存しない")
    void paymentMustNotDependOnOrder() {
        noClasses().that().resideInAPackage("..payment..")
                .should().dependOnClassesThat().resideInAPackage("..order..")
                .check(classes);
    }

    @Test
    @DisplayName("shared は order / payment のどちらにも依存しない")
    void sharedMustNotDependOnOrderOrPayment() {
        noClasses().that().resideInAPackage("..shared..")
                .should().dependOnClassesThat().resideInAnyPackage("..order..", "..payment..")
                .check(classes);
    }

    @Test
    @DisplayName("domain 層は外側の層に依存しない")
    void domainMustNotDependOnOuterLayers() {
        noClasses().that().resideInAPackage("..domain..")
                .should().dependOnClassesThat().resideInAnyPackage("..application..", "..adapter..")
                .check(classes);
    }

    @Test
    @DisplayName("domain 層と shared はフレームワークに依存しない")
    void domainMustNotDependOnFrameworks() {
        noClasses().that().resideInAnyPackage("..domain..", "..shared..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "org.springframework..", "jakarta.persistence..", "jakarta.validation..")
                .check(classes);
    }
}
