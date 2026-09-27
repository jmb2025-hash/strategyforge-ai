package app.strategyforge.safety

import app.strategyforge.StrategyForgeApplication
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.boot.builder.SpringApplicationBuilder

class RealMoneyPolicyTest {
    @Test
    fun `FR-113 any flag value other than false prevents startup`() {
        for (v in listOf("true", "TRUE", "1", "yes", "on", "", " False", "enabled")) {
            assertThatThrownBy { RealMoneyPolicy.assertFlagSafe(v) }
                .`as`("value '$v'")
                .isInstanceOf(RealMoneyPolicy.RealMoneyProhibitedException::class.java)
        }
        RealMoneyPolicy.assertFlagSafe("false")
        RealMoneyPolicy.assertEnvironmentSafe(mapOf())
        assertThatThrownBy { RealMoneyPolicy.assertEnvironmentSafe(mapOf("REAL_MONEY_TRADING_ENABLED" to "true")) }
            .isInstanceOf(RealMoneyPolicy.RealMoneyProhibitedException::class.java)
    }

    @Test
    fun `FR-113 spring context refuses to start when configuration enables real money`() {
        // The environment variable is resolved through application.yml; a system property simulates it.
        System.setProperty("REAL_MONEY_TRADING_ENABLED", "true")
        try {
            assertThatThrownBy {
                SpringApplicationBuilder(StrategyForgeApplication::class.java)
                    .properties("spring.main.web-application-type=none")
                    .run()
            }.hasStackTraceContaining("Real-money trading is not available")
        } finally {
            System.clearProperty("REAL_MONEY_TRADING_ENABLED")
        }
    }

    @Test
    fun `FR-113 prohibited field and route patterns`() {
        listOf("liveTrading", "realMoney", "broker", "brokerAccount", "brokerageCredentials", "executionVenue", "withdrawal", "wallet")
            .forEach { assertThat(RealMoneyPolicy.isProhibitedField(it)).`as`(it).isTrue() }
        listOf("name", "description", "symbols", "allowShort").forEach { assertThat(RealMoneyPolicy.isProhibitedField(it)).`as`(it).isFalse() }
        assertThat(RealMoneyPolicy.PROHIBITED_ROUTE.matches("/v1/orders")).isFalse()
        assertThat(RealMoneyPolicy.PROHIBITED_ROUTE.matches("/v1/brokerage/connect")).isTrue()
    }
}
