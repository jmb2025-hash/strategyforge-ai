package app.strategyforge

import app.strategyforge.operations.backup.BackupCommand
import app.strategyforge.safety.RealMoneyPolicy
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling
import kotlin.system.exitProcess

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
class StrategyForgeApplication

fun main(args: Array<String>) {
    // Fail before any bean is created if real-money trading is requested (FR-113).
    RealMoneyPolicy.assertEnvironmentSafe(System.getenv())
    // Offline backup commands run without the application context (D-007).
    if (args.firstOrNull() in BackupCommand.COMMANDS) exitProcess(BackupCommand.run(args.toList(), System.getenv()))
    runApplication<StrategyForgeApplication>(*args)
}
