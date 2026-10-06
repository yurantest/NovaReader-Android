package com.novareader.app.search

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

class SearchRepository(
    private val searchFloorDomain: String = "",
    private val flibustaDomain: String = "",
) {
    suspend fun search(
        query: String,
        sources: Set<SearchSource>,
        limit: Int,
        maxPages: Int,
        cancelled: () -> Boolean,
        onBook: (BookSearchResult) -> Unit,
    ): Int = withContext(Dispatchers.IO) {
        if (sources.isEmpty() || query.isBlank()) return@withContext 0
        val searchId = SearchHttp.beginSearch()
        try {
        coroutineScope {
            val jobs = sources.map { source ->
                async {
                    if (cancelled()) return@async 0
                    try {
                        when (source) {
                            SearchSource.AUTHOR_TODAY ->
                                SiteParsers.searchAuthorToday(query, limit, maxPages, cancelled, onBook, searchId)
                            SearchSource.SEARCHFLOOR ->
                                SiteParsers.searchSearchFloor(
                                    query, limit, maxPages, searchFloorDomain, cancelled, onBook, searchId
                                )
                            SearchSource.MOREKNIG ->
                                SiteParsers.searchMoreKnig(query, limit, maxPages, cancelled, onBook, searchId)
                            SearchSource.FLIBUSTA ->
                                SiteParsers.searchFlibusta(
                                    query, limit, maxPages, flibustaDomain, cancelled, onBook, searchId
                                )
                        }
                    } catch (e: CancellationException) {
                        // Отмена поиска должна пройти вверх по coroutine,
                        // а не превращаться в обычный результат источника.
                        throw e
                    } catch (_: Exception) {
                        0
                    }
                }
            }
            jobs.awaitAll().sum()
        }
        } finally {
            SearchHttp.endSearch(searchId)
        }
    }
}
