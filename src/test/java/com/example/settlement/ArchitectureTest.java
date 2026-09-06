package com.example.settlement;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;

/** design.md §7 の依存関係ルール。 */
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
    @DisplayName("application 層は adapter 層に依存しない")
    void applicationMustNotDependOnAdapters() {
        noClasses().that().resideInAPackage("..application..")
                .should().dependOnClassesThat().resideInAPackage("..adapter..")
                .check(classes);
    }

    @Test
    @DisplayName("本番コードは演習用の pspsimulator に依存しない")
    void productionCodeMustNotDependOnPspSimulator() {
        noClasses().that().resideInAnyPackage("..order..", "..payment..", "..shared..")
                .should().dependOnClassesThat().resideInAPackage("..pspsimulator..")
                .check(classes);
    }

    /**
     * payment 側の窓口を order 以外が塞がないことを守る。実装が増えると、payment の結果が
     * どこへ流れるのかがDIの解決順でしか決まらなくなり、複数Beanで起動にも失敗する。
     */
    @Test
    @DisplayName("PaymentOutcomePort を実装してよいのは order だけ")
    void onlyOrderMayImplementPaymentOutcomePort() {
        classes().that()
                .implement("com.example.settlement.payment.application.port.out.PaymentOutcomePort")
                .should().resideInAPackage("..order..")
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
