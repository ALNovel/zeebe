/**
 * Zeebe Broker Core
 * Copyright © 2017 camunda services GmbH (info@camunda.com)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package io.zeebe.broker.incident;

import org.agrona.DirectBuffer;

import io.zeebe.broker.Constants;
import io.zeebe.broker.incident.data.ErrorType;
import io.zeebe.broker.incident.data.IncidentEvent;
import io.zeebe.broker.incident.data.IncidentEventType;
import io.zeebe.broker.logstreams.BrokerEventMetadata;
import io.zeebe.broker.logstreams.processor.StreamProcessorIds;
import io.zeebe.broker.workflow.data.WorkflowInstanceEvent;
import io.zeebe.logstreams.log.LogStream;
import io.zeebe.logstreams.log.LogStreamWriter;
import io.zeebe.logstreams.log.LogStreamWriterImpl;
import io.zeebe.logstreams.log.LoggedEvent;
import io.zeebe.logstreams.processor.StreamProcessorErrorHandler;
import io.zeebe.msgpack.mapping.MappingException;
import io.zeebe.protocol.clientapi.EventType;

public class IncidentStreamProcessorErrorHandler implements StreamProcessorErrorHandler
{
    private final LogStream logStream;
    private final DirectBuffer sourceStreamTopicName;
    private final int sourceStreamPartitionId;

    private final LogStreamWriter logStreamWriter;

    private final BrokerEventMetadata incidentEventMetadata = new BrokerEventMetadata();
    private final IncidentEvent incidentEvent = new IncidentEvent();

    private final BrokerEventMetadata failureEventMetadata = new BrokerEventMetadata();
    private final WorkflowInstanceEvent workflowInstanceEvent = new WorkflowInstanceEvent();

    public IncidentStreamProcessorErrorHandler(LogStream logStream)
    {
        this.logStream = logStream;
        this.sourceStreamTopicName = logStream.getTopicName();
        this.sourceStreamPartitionId = logStream.getPartitionId();

        this.logStreamWriter = new LogStreamWriterImpl(logStream);
    }

    @Override
    public int onError(LoggedEvent failureEvent, Exception error)
    {
        int result = RESULT_REJECT;

        if (error instanceof MappingException)
        {
            result = handlePayloadException(failureEvent, ErrorType.IO_MAPPING_ERROR, error);
        }

        return result;
    }

    private int handlePayloadException(LoggedEvent failureEvent, ErrorType errorType, Exception error)
    {

        incidentEventMetadata.reset()
            .protocolVersion(Constants.PROTOCOL_VERSION)
            .eventType(EventType.INCIDENT_EVENT)
            .raftTermId(logStream.getTerm());

        incidentEvent.reset();
        incidentEvent
            .setErrorType(errorType)
            .setErrorMessage(error.getMessage())
            .setFailureEventPosition(failureEvent.getPosition());

        failureEventMetadata.reset();
        failureEvent.readMetadata(failureEventMetadata);

        setWorkflowInstanceData(failureEvent);

        if (!failureEventMetadata.hasIncidentKey())
        {
            incidentEvent.setEventType(IncidentEventType.CREATE);

            logStreamWriter.positionAsKey();
        }
        else
        {
            incidentEvent.setEventType(IncidentEventType.RESOLVE_FAILED);

            logStreamWriter.key(failureEventMetadata.getIncidentKey());
        }

        final long position = logStreamWriter
                .producerId(StreamProcessorIds.INCIDENT_PROCESSOR_ID)
                .sourceEvent(sourceStreamTopicName, sourceStreamPartitionId, failureEvent.getPosition())
                .metadataWriter(incidentEventMetadata)
                .valueWriter(incidentEvent)
                .tryWrite();

        return position > 0 ? RESULT_SUCCESS : RESULT_FAILURE;
    }

    private void setWorkflowInstanceData(LoggedEvent failureEvent)
    {
        if (failureEventMetadata.getEventType() == EventType.WORKFLOW_EVENT)
        {
            workflowInstanceEvent.reset();
            failureEvent.readValue(workflowInstanceEvent);

            incidentEvent
                .setBpmnProcessId(workflowInstanceEvent.getBpmnProcessId())
                .setWorkflowInstanceKey(workflowInstanceEvent.getWorkflowInstanceKey())
                .setActivityId(workflowInstanceEvent.getActivityId())
                .setActivityInstanceKey(failureEvent.getKey());
        }
        else
        {
            throw new RuntimeException(String.format("Unsupported failure event type '%s'.", failureEventMetadata.getEventType().name()));
        }
    }

}
