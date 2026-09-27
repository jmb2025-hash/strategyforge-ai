package app.strategyforge.support

import java.util.concurrent.ConcurrentLinkedQueue

/** Collects real responses observed by integration tests for OpenAPI contract validation. */
object Contract {
    data class Observed(
        val method: String,
        val path: String,
        val status: Int,
        val body: String,
        val contentType: String?,
    )

    val observed = ConcurrentLinkedQueue<Observed>()

    fun record(
        method: String,
        path: String,
        r: Resp,
    ) {
        if (observed.size < 20_000) observed.add(Observed(method, path.substringBefore('?'), r.status, r.body, r.header("Content-Type")))
    }
}
