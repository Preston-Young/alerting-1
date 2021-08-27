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

package com.amazon.opendistroforelasticsearch.alerting.action

import com.amazon.opendistroforelasticsearch.alerting.model.Monitor
import com.amazon.opendistroforelasticsearch.alerting.util._ID
import com.amazon.opendistroforelasticsearch.alerting.util._PRIMARY_TERM
import com.amazon.opendistroforelasticsearch.alerting.util._SEQ_NO
import com.amazon.opendistroforelasticsearch.alerting.util._VERSION
import org.elasticsearch.action.ActionResponse
import org.elasticsearch.common.io.stream.StreamInput
import org.elasticsearch.common.io.stream.StreamOutput
import org.elasticsearch.common.xcontent.ToXContent
import org.elasticsearch.common.xcontent.ToXContentObject
import org.elasticsearch.common.xcontent.XContentBuilder
import org.elasticsearch.rest.RestStatus
import java.io.IOException

class ImportMonitorResponse : ActionResponse, ToXContentObject {
    var monitors: MutableList<Monitor>

    constructor(
        monitors: MutableList<Monitor>
    ) : super() {
        this.monitors = monitors
    }

    @Throws(IOException::class)
    constructor(sin: StreamInput) : this(
        sin.readList(::Monitor) as MutableList<Monitor>
    )

    @Throws(IOException::class)
    override fun writeTo(out: StreamOutput) {
        out.writeList(monitors) // Todo: Check to see if this works
    }

    @Throws(IOException::class)
    override fun toXContent(builder: XContentBuilder, params: ToXContent.Params): XContentBuilder {
        return builder.startObject()
            .field("monitors", monitors)
            .endObject()
    }
}
