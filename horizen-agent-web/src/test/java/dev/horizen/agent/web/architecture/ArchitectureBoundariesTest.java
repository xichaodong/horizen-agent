package dev.horizen.agent.web.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import static org.junit.jupiter.api.Assertions.assertFalse;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;

import org.junit.jupiter.api.Test;

class ArchitectureBoundariesTest {
    @Test
    void applicationDependsOnPortsRatherThanRuntimeImplementations() {
        var classes = productionClasses("dev.horizen.agent.application");
        noClasses()
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "io.agentscope..",
                        "dev.horizen.agent.adapter..",
                        "dev.horizen.agent.web..",
                        "org.springframework..",
                        "dev.horizen.agent.storage..",
                        "dev.horizen.agent.observability..")
                .check(classes);
    }

    @Test
    void publicRuntimeContractDoesNotExposeAgentScope() {
        var classes = productionClasses("dev.horizen.agent.runtime.api");
        noClasses()
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "io.agentscope..", "dev.horizen.agent.adapter..", "dev.horizen.agent.web..")
                .check(classes);
    }

    @Test
    void toolsDoNotDependOnConcreteSandboxAdapters() {
        var classes = productionClasses("dev.horizen.agent.tools");
        noClasses()
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "dev.horizen.agent.sandbox.e2b..",
                        "dev.horizen.agent.storage..",
                        "dev.horizen.agent.web..")
                .check(classes);
    }

    private JavaClasses productionClasses(String packageName) {
        JavaClasses classes =
                new ClassFileImporter()
                        .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                        .importPackages(packageName);
        assertFalse(classes.isEmpty(), "No production classes imported for " + packageName);
        return classes;
    }
}
