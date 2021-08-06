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

package com.amazon.opendistroforelasticsearch.alerting.script

import com.amazon.opendistroforelasticsearch.alerting.model.BucketLevelTrigger
import com.amazon.opendistroforelasticsearch.alerting.model.BucketLevelTriggerRunResult
import com.amazon.opendistroforelasticsearch.alerting.model.Alert
import com.amazon.opendistroforelasticsearch.alerting.model.Monitor
import com.amazon.opendistroforelasticsearch.alerting.model.MonitorRunResult
import java.time.Instant

data class BucketLevelTriggerExecutionContext(
    override val monitor: Monitor,
    val trigger: BucketLevelTrigger,
    override val results: List<Map<String, Any>>,
    override val periodStart: Instant,
    override val periodEnd: Instant,
    val dedupedAlerts: List<Alert> = listOf(),
    val newAlerts: List<Alert> = listOf(),
    val completedAlerts: List<Alert> = listOf(),
    override val error: Exception? = null
) : TriggerExecutionContext(monitor, results, periodStart, periodEnd, error) {

    constructor(
        monitor: Monitor,
        trigger: BucketLevelTrigger,
        monitorRunResult: MonitorRunResult<BucketLevelTriggerRunResult>,
        dedupedAlerts: List<Alert> = listOf(),
        newAlerts: List<Alert> = listOf(),
        completedAlerts: List<Alert> = listOf()
    ) : this(monitor, trigger, monitorRunResult.inputResults.results, monitorRunResult.periodStart, monitorRunResult.periodEnd,
        dedupedAlerts, newAlerts, completedAlerts, monitorRunResult.scriptContextError(trigger))

    /**
     * Mustache templates need special permissions to reflectively introspect field names. To avoid doing this we
     * translate the context to a Map of Strings to primitive types, which can be accessed without reflection.
     */
    override fun asTemplateArg(): Map<String, Any?> {
        val tempArg = super.asTemplateArg().toMutableMap()
        tempArg["trigger"] = trigger.asTemplateArg()
        tempArg["dedupedAlerts"] = dedupedAlerts.map { it.asTemplateArg() }
        tempArg["newAlerts"] = newAlerts.map { it.asTemplateArg() }
        tempArg["completedAlerts"] = completedAlerts.map { it.asTemplateArg() }
        return tempArg
    }
}