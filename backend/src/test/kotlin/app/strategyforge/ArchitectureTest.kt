package app.strategyforge

import com.tngtech.archunit.core.domain.JavaClasses
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields
import org.junit.jupiter.api.Test

/** Modular-monolith and safety-boundary rules (section 7, FR-113, NFR-002). */
class ArchitectureTest {
    private val classes: JavaClasses =
        ClassFileImporter().withImportOption(ImportOption.DoNotIncludeTests()).importPackages("app.strategyforge")

    @Test
    fun `FR-113 no brokerage or live-trading components exist outside the safety policy`() {
        noClasses()
            .that()
            .resideOutsideOfPackage("app.strategyforge.safety..")
            .should()
            .haveSimpleNameContaining("Broker")
            .orShould()
            .haveSimpleNameContaining("LiveTrad")
            .orShould()
            .haveSimpleNameContaining("RealMoney")
            .orShould()
            .resideInAnyPackage("..broker..", "..brokerage..", "..live..")
            .check(classes)
    }

    @Test
    fun `NFR-002 no floating point fields in production code`() {
        noFields()
            .should()
            .haveRawType(Double::class.javaPrimitiveType)
            .orShould()
            .haveRawType(Float::class.javaPrimitiveType)
            .orShould()
            .haveRawType(java.lang.Double::class.java)
            .orShould()
            .haveRawType(java.lang.Float::class.java)
            .check(classes)
    }

    @Test
    fun `common infrastructure does not depend on feature modules`() {
        noClasses()
            .that()
            .resideInAPackage("app.strategyforge.common..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                "app.strategyforge.identity..",
                "app.strategyforge.market..",
                "app.strategyforge.portfolio..",
                "app.strategyforge.execution..",
                "app.strategyforge.strategy..",
                "app.strategyforge.backtest..",
                "app.strategyforge.risk..",
                "app.strategyforge.signals..",
                "app.strategyforge.autonomy..",
                "app.strategyforge.research..",
                "app.strategyforge.notifications..",
                "app.strategyforge.reports..",
                "app.strategyforge.operations..",
            ).check(classes)
    }

    @Test
    fun `safety policy depends only on common infrastructure`() {
        classes()
            .that()
            .resideInAPackage("app.strategyforge.safety..")
            .should()
            .onlyDependOnClassesThat()
            .resideOutsideOfPackages(
                "app.strategyforge.portfolio..",
                "app.strategyforge.execution..",
                "app.strategyforge.strategy..",
            ).check(classes)
    }

    @Test
    fun `FR-092 AI research cannot reach order execution, the risk engine, autonomy or portfolios`() {
        noClasses()
            .that()
            .resideInAPackage("app.strategyforge.research..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                "app.strategyforge.execution..",
                "app.strategyforge.risk..",
                "app.strategyforge.signals..",
                "app.strategyforge.autonomy..",
                "app.strategyforge.portfolio..",
            ).check(classes)
    }
}
