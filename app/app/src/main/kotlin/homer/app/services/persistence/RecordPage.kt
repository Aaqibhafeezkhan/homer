package homer.app.services.persistence

/** One page of a list read, and the number of rows matching its filters before paging. */
data class RecordPage<T>(
    val items: List<T>,
    val total: Int,
)
