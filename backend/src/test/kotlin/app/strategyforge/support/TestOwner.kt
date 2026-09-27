package app.strategyforge.support

/** Bootstraps the shared test owner once per database and hands out authenticated clients. */
object TestOwner {
    const val USERNAME = "owner"
    const val PASSWORD = "correct-horse-battery-staple"
    private val tokens = mutableMapOf<String, String>()

    @Synchronized
    fun client(baseUrl: String): TestHttp {
        val http = TestHttp(baseUrl)
        tokens[baseUrl]?.let { t ->
            http.token = t
            if (http.get("/v1/auth/me").status == 200) return http
        }
        val status = http.get("/v1/bootstrap").json
        val token =
            if (!status["bootstrapped"].asBoolean()) {
                val r = http.post("/v1/bootstrap", mapOf("username" to USERNAME, "password" to PASSWORD, "deviceName" to "test-device", "timezone" to "America/Halifax"))
                check(r.status == 201) { "bootstrap failed: $r" }
                r.json["session"]["accessToken"].asText()
            } else {
                val r = http.post("/v1/auth/login", mapOf("username" to USERNAME, "password" to PASSWORD, "deviceName" to "test-device"))
                check(r.status == 200) { "login failed: $r" }
                r.json["accessToken"].asText()
            }
        tokens[baseUrl] = token
        http.token = token
        return http
    }

    /** A second, independent session (e.g. a second device). */
    fun secondDevice(baseUrl: String): TestHttp {
        client(baseUrl)
        val http = TestHttp(baseUrl)
        val r = http.post("/v1/auth/login", mapOf("username" to USERNAME, "password" to PASSWORD, "deviceName" to "second-device"))
        check(r.status == 200) { "login failed: $r" }
        http.token = r.json["accessToken"].asText()
        return http
    }
}
