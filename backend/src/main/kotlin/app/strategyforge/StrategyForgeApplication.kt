package app.strategyforge

import app.strategyforge.safety.RealMoneyPolicy
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
class StrategyForgeApplication

fun main(args: Array<String>) {
    // Fail before any bean is created if real-money trading is requested (FR-113).
    RealMoneyPolicy.assertEnvironmentSafe(System.getenv())
    runApplication<StrategyForgeApplication>(*args)
}
