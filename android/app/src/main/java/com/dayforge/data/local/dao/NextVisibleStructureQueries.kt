package com.dayforge.data.local.dao

// Display filtering is not terminal/ACK authority. Sync and retained-fact readers keep raw queries.
internal const val VISIBLE_METRIC_SQL = " NOT EXISTS (SELECT 1 FROM sync_outbox q JOIN next_request_origins o ON o.kind='sync_operation' AND o.requestId=q.operationId AND o.queueId=q.id AND o.protocol=5 WHERE q.action='delete' AND q.recordType='metric' AND q.entityUuid=metrics.uuid) "
internal const val VISIBLE_LINK_SQL = " NOT EXISTS (SELECT 1 FROM sync_outbox q JOIN next_request_origins o ON o.kind='sync_operation' AND o.requestId=q.operationId AND o.queueId=q.id AND o.protocol=5 WHERE q.action='delete' AND ((q.recordType='link' AND q.entityUuid=links.uuid) OR (q.recordType='metric' AND q.entityUuid=links.metricUuid) OR (q.recordType='habit' AND q.entityUuid=links.habitUuid))) "
