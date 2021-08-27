/*
 *   Copyright 2021 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 *   Licensed under the Apache License, Version 2.0 (the "License").
 *   You may not use this file except in compliance with the License.
 *   A copy of the License is located at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *   or in the "license" file accompanying this file. This file is distributed
 *   on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 *   express or implied. See the License for the specific language governing
 *   permissions and limitations under the License.
 */

package com.amazon.opendistroforelasticsearch.alerting.transport

import com.amazon.opendistroforelasticsearch.alerting.action.ImportMonitorAction
import com.amazon.opendistroforelasticsearch.alerting.action.ImportMonitorRequest
import com.amazon.opendistroforelasticsearch.alerting.action.ImportMonitorResponse
import com.amazon.opendistroforelasticsearch.alerting.core.ScheduledJobIndices
import com.amazon.opendistroforelasticsearch.alerting.core.model.ScheduledJob.Companion.SCHEDULED_JOBS_INDEX
import com.amazon.opendistroforelasticsearch.alerting.core.model.SearchInput
import com.amazon.opendistroforelasticsearch.alerting.model.Monitor
import com.amazon.opendistroforelasticsearch.alerting.settings.AlertingSettings
import com.amazon.opendistroforelasticsearch.alerting.settings.AlertingSettings.Companion.ALERTING_MAX_MONITORS
import com.amazon.opendistroforelasticsearch.alerting.settings.AlertingSettings.Companion.FILTER_BY_BACKEND_ROLES
import com.amazon.opendistroforelasticsearch.alerting.settings.AlertingSettings.Companion.INDEX_TIMEOUT
import com.amazon.opendistroforelasticsearch.alerting.settings.AlertingSettings.Companion.MAX_ACTION_THROTTLE_VALUE
import com.amazon.opendistroforelasticsearch.alerting.settings.AlertingSettings.Companion.REQUEST_TIMEOUT
import com.amazon.opendistroforelasticsearch.alerting.settings.DestinationSettings.Companion.ALLOW_LIST
import com.amazon.opendistroforelasticsearch.alerting.util.AlertingException
import com.amazon.opendistroforelasticsearch.alerting.util.IndexUtils
import com.amazon.opendistroforelasticsearch.alerting.util.checkFilterByUserBackendRoles
import com.amazon.opendistroforelasticsearch.commons.ConfigConstants
import com.amazon.opendistroforelasticsearch.commons.authuser.User
import org.apache.logging.log4j.LogManager
import org.elasticsearch.ElasticsearchSecurityException
import org.elasticsearch.ElasticsearchStatusException
import org.elasticsearch.action.ActionListener
import org.elasticsearch.action.admin.indices.create.CreateIndexResponse
import org.elasticsearch.action.index.IndexRequest
import org.elasticsearch.action.index.IndexResponse
import org.elasticsearch.action.search.SearchRequest
import org.elasticsearch.action.search.SearchResponse
import org.elasticsearch.action.support.ActionFilters
import org.elasticsearch.action.support.HandledTransportAction
import org.elasticsearch.client.Client
import org.elasticsearch.cluster.service.ClusterService
import org.elasticsearch.common.inject.Inject
import org.elasticsearch.common.settings.Settings
import org.elasticsearch.common.unit.TimeValue
import org.elasticsearch.common.xcontent.NamedXContentRegistry
import org.elasticsearch.common.xcontent.ToXContent
import org.elasticsearch.common.xcontent.XContentFactory.jsonBuilder
import org.elasticsearch.index.query.QueryBuilders
import org.elasticsearch.rest.RestStatus
import org.elasticsearch.search.builder.SearchSourceBuilder
import org.elasticsearch.tasks.Task
import org.elasticsearch.transport.TransportService
import java.time.Duration

private val log = LogManager.getLogger(TransportImportMonitorAction::class.java)

class TransportImportMonitorAction @Inject constructor(
    transportService: TransportService,
    val client: Client,
    actionFilters: ActionFilters,
    val scheduledJobIndices: ScheduledJobIndices,
    val clusterService: ClusterService,
    val settings: Settings,
    val xContentRegistry: NamedXContentRegistry
) : HandledTransportAction<ImportMonitorRequest, ImportMonitorResponse>(
    ImportMonitorAction.NAME, transportService, actionFilters, ::ImportMonitorRequest
) {

    @Volatile private var maxMonitors = ALERTING_MAX_MONITORS.get(settings)
    @Volatile private var requestTimeout = REQUEST_TIMEOUT.get(settings)
    @Volatile private var indexTimeout = INDEX_TIMEOUT.get(settings)
    @Volatile private var maxActionThrottle = MAX_ACTION_THROTTLE_VALUE.get(settings)
    @Volatile private var allowList = ALLOW_LIST.get(settings)
    @Volatile private var filterByEnabled = AlertingSettings.FILTER_BY_BACKEND_ROLES.get(settings)

    init {
        clusterService.clusterSettings.addSettingsUpdateConsumer(ALERTING_MAX_MONITORS) { maxMonitors = it }
        clusterService.clusterSettings.addSettingsUpdateConsumer(REQUEST_TIMEOUT) { requestTimeout = it }
        clusterService.clusterSettings.addSettingsUpdateConsumer(INDEX_TIMEOUT) { indexTimeout = it }
        clusterService.clusterSettings.addSettingsUpdateConsumer(MAX_ACTION_THROTTLE_VALUE) { maxActionThrottle = it }
        clusterService.clusterSettings.addSettingsUpdateConsumer(ALLOW_LIST) { allowList = it }
        clusterService.clusterSettings.addSettingsUpdateConsumer(FILTER_BY_BACKEND_ROLES) { filterByEnabled = it }
    }

    override fun doExecute(task: Task, request: ImportMonitorRequest, actionListener: ActionListener<ImportMonitorResponse>) {

        val userStr = client.threadPool().threadContext.getTransient<String>(ConfigConstants.OPENDISTRO_SECURITY_USER_INFO_THREAD_CONTEXT)
        log.debug("User and roles string from thread context: $userStr")
        val user: User? = User.parse(userStr)

        if (!checkFilterByUserBackendRoles(filterByEnabled, user, actionListener)) {
            return
        }

        checkIndicesAndExecute(client, actionListener, request, user)
    }

    /**
     *  Check if user has permissions to read the configured indices on each monitor and
     *  then create monitor.
     */
    fun checkIndicesAndExecute(
        client: Client,
        actionListener: ActionListener<ImportMonitorResponse>,
        request: ImportMonitorRequest,
        user: User?
    ) {
        for (index in request.monitors.indices) {
            val monitor = request.monitors[index]
            val indices = mutableListOf<String>()
            val searchInputs = monitor.inputs.filter { it.name() == SearchInput.SEARCH_FIELD }
            searchInputs.forEach {
                val searchInput = it as SearchInput
                indices.addAll(searchInput.indices)
            }
            val searchRequest = SearchRequest().indices(*indices.toTypedArray())
                .source(SearchSourceBuilder.searchSource().size(1).query(QueryBuilders.matchAllQuery()))
            client.search(searchRequest, object : ActionListener<SearchResponse> {
                override fun onResponse(searchResponse: SearchResponse) {
                    // User has read access to configured indices in the monitor, now create monitor with out user context.
                    client.threadPool().threadContext.stashContext().use {
                        IndexMonitorHandler(client, actionListener, request, index, monitor, user).resolveUserAndStart()
                    }
                }

                //  Due to below issue with security plugin, we get security_exception when invalid index name is mentioned.
                //  https://github.com/opendistro-for-elasticsearch/security/issues/718
                override fun onFailure(t: Exception) {
                    actionListener.onFailure(AlertingException.wrap(
                        when (t is ElasticsearchSecurityException) {
                            true -> ElasticsearchStatusException("User doesn't have read permissions for one or more configured index " +
                                    "$indices", RestStatus.FORBIDDEN)
                            false -> t
                        }
                    ))
                }
            })
        }

        var responseMonitors = request.monitors
        actionListener.onResponse(
            ImportMonitorResponse(responseMonitors)
        )
    }

    inner class IndexMonitorHandler(
        private val client: Client,
        private val actionListener: ActionListener<ImportMonitorResponse>,
        private val request: ImportMonitorRequest,
        private val monitorIndex: Int,
        private val monitor: Monitor,
        private val user: User?
    ) {

        fun resolveUserAndStart() {
            if (user == null) {
                // Security is disabled, add empty user to Monitor. user is null for older versions.
                request.monitors[monitorIndex] = monitor
                    .copy(user = User("", listOf(), listOf(), listOf()))
                start()
            } else {
                request.monitors[monitorIndex] = monitor
                    .copy(user = User(user.name, user.backendRoles, user.roles, user.customAttNames))
                start()
            }
        }

        fun start() {
            if (!scheduledJobIndices.scheduledJobIndexExists()) {
                scheduledJobIndices.initScheduledJobIndex(object : ActionListener<CreateIndexResponse> {
                    override fun onResponse(response: CreateIndexResponse) {
                        onCreateMappingsResponse(response)
                    }
                    override fun onFailure(t: Exception) {
                        actionListener.onFailure(AlertingException.wrap(t))
                    }
                })
            }
            else {
                prepareMonitorIndexing()
            }
        }

        /**
         * This function prepares for indexing a new monitor.
         * We first check to see how many monitors already exist, and compare this to the [maxMonitorCount].
         * Requests that breach this threshold will be rejected.
         */
        private fun prepareMonitorIndexing() {

            // Below check needs to be async operations and needs to be refactored issue#269
            // checkForDisallowedDestinations(allowList)
            try {
                validateActionThrottle(monitor, maxActionThrottle, TimeValue.timeValueMinutes(1))
            } catch (e: RuntimeException) {
                actionListener.onFailure(AlertingException.wrap(e))
                return
            }

            val query = QueryBuilders.boolQuery().filter(QueryBuilders.termQuery("${Monitor.MONITOR_TYPE}.type", Monitor.MONITOR_TYPE))
            val searchSource = SearchSourceBuilder().query(query).timeout(requestTimeout)
            val searchRequest = SearchRequest(SCHEDULED_JOBS_INDEX).source(searchSource)
            client.search(searchRequest, object : ActionListener<SearchResponse> {
                override fun onResponse(searchResponse: SearchResponse) {
                    onSearchResponse(searchResponse)
                }

                override fun onFailure(t: Exception) {
                    actionListener.onFailure(AlertingException.wrap(t))
                }
            })
        }

        private fun validateActionThrottle(monitor: Monitor, maxValue: TimeValue, minValue: TimeValue) {
            monitor.triggers.forEach { trigger ->
                trigger.actions.forEach { action ->
                    if (action.throttle != null) {
                        require(TimeValue(Duration.of(action.throttle.value.toLong(), action.throttle.unit).toMillis())
                            .compareTo(maxValue) <= 0, { "Can only set throttle period less than or equal to $maxValue" })
                        require(TimeValue(Duration.of(action.throttle.value.toLong(), action.throttle.unit).toMillis())
                            .compareTo(minValue) >= 0, { "Can only set throttle period greater than or equal to $minValue" })
                    }
                }
            }
        }

        /**
         * After searching for all existing monitors we validate the system can support another monitor to be created.
         */
        private fun onSearchResponse(response: SearchResponse) {
            val totalHits = response.hits.totalHits?.value
            if (totalHits != null && totalHits >= maxMonitors) {
                log.error("This request would create more than the allowed monitors [$maxMonitors].")
                actionListener.onFailure(
                    AlertingException.wrap(IllegalArgumentException(
                        "This request would create more than the allowed monitors [$maxMonitors]."))
                )
            } else {
                indexMonitor()
            }
        }

        private fun onCreateMappingsResponse(response: CreateIndexResponse) {
            if (response.isAcknowledged) {
                log.info("Created $SCHEDULED_JOBS_INDEX with mappings.")
                prepareMonitorIndexing()
            } else {
                log.error("Create $SCHEDULED_JOBS_INDEX mappings call not acknowledged.")
                actionListener.onFailure(AlertingException.wrap(ElasticsearchStatusException(
                    "Create $SCHEDULED_JOBS_INDEX mappings call not acknowledged", RestStatus.INTERNAL_SERVER_ERROR))
                )
            }
        }

        private fun indexMonitor() {
            request.monitors[monitorIndex] = monitor.copy(schemaVersion = IndexUtils.scheduledJobIndexSchemaVersion)

            val indexRequest = IndexRequest(SCHEDULED_JOBS_INDEX)
                .source(request.monitors[monitorIndex].toXContent(jsonBuilder(), ToXContent.MapParams(mapOf("with_type" to "true"))))
                .timeout(indexTimeout)

            client.index(indexRequest, object : ActionListener<IndexResponse> {
                override fun onResponse(response: IndexResponse) {
                    val failureReasons = checkShardsFailure(response)
                    if (failureReasons != null) {
                        actionListener.onFailure(
                            AlertingException.wrap(ElasticsearchStatusException(failureReasons.toString(), response.status()))
                        )
                        return
                    }
                }
                override fun onFailure(t: Exception) {
                    actionListener.onFailure(AlertingException.wrap(t))
                }
            })
        }

        private fun checkShardsFailure(response: IndexResponse): String? {
            val failureReasons = StringBuilder()
            if (response.shardInfo.failed > 0) {
                response.shardInfo.failures.forEach {
                        entry -> failureReasons.append(entry.reason())
                }
                return failureReasons.toString()
            }
            return null
        }
    }
}
